package com.artifactsmmo.server.api

import kotlinx.coroutines.flow.sample
import com.artifactsmmo.engine.worker.ApiCharacterView
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.artifactsmmo.domain.queue.GroupRole
import com.artifactsmmo.domain.queue.NewTask
import com.artifactsmmo.domain.queue.TaskFilter
import com.artifactsmmo.domain.queue.TaskSource
import com.artifactsmmo.domain.queue.TaskStatus
import com.artifactsmmo.domain.task.TaskSpec
import com.artifactsmmo.engine.worker.CharacterSettings
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sessions.clear
import io.ktor.server.sessions.sessions
import io.ktor.server.sessions.set
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import java.time.ZoneId

/** Public routes: login / logout / me. */
fun Route.authRoutes(adminKey: String?, throttle: LoginThrottle, backend: ApiBackend?) {
    post("/api/login") {
        val ip = call.clientIp()
        if (adminKey == null) return@post call.respond(HttpStatusCode.ServiceUnavailable, ApiError("ADMIN_API_KEY not configured"))
        if (throttle.blocked(ip)) return@post call.respond(HttpStatusCode.TooManyRequests, ApiError("too many attempts, try later"))
        val req = runCatching { call.receive<LoginRequest>() }.getOrNull()
        if (req == null || !keyMatches(req.key, adminKey)) {
            throttle.fail(ip)
            return@post call.respond(HttpStatusCode.Unauthorized, ApiError("invalid key"))
        }
        throttle.reset(ip)
        call.sessions.set(UserSession(System.currentTimeMillis()))
        call.respond(me(call, backend))
    }
    post("/api/logout") {
        call.sessions.clear<UserSession>()
        call.respond(HttpStatusCode.NoContent)
    }
    get("/api/me") { call.respond(me(call, backend)) }
}

private fun me(call: ApplicationCall, backend: ApiBackend?) = MeResponse(
    loggedIn = call.isLoggedIn(),
    engineRunning = backend?.engine != null,
    paused = backend?.engine?.control?.fatalReason?.value,
)

/** Everything below requires a session (install [RequireSession] on the parent route). */
fun Route.apiRoutes(b: ApiBackend) {
    // ── Tasks ──────────────────────────────────────────────────────────────
    route("/tasks") {
        get {
            val q = call.request.queryParameters
            val statuses = q["status"]?.split(',')?.filter { it.isNotBlank() }?.map { TaskStatus.fromDb(it) }?.toSet().orEmpty()
            val filter = TaskFilter(
                statuses = statuses,
                character = q["character"],
                source = q["source"]?.let { TaskSource.fromDb(it) },
                limit = (q["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500),
            )
            // Group parents are bookkeeping rows; the UI shows the member slots.
            call.respond(b.queue.list(filter).filter { q["includeGroups"] == "true" || it.groupRole != GroupRole.GROUP })
        }
        post {
            val req = call.receive<CreateTaskRequest>()
            if (req.spec is TaskSpec.BossFight) return@post call.badRequest("boss fights must be created via POST /api/tasks/group")
            validateCharacter(b, req.assignedCharacter)?.let { return@post call.badRequest(it) }
            // Skill gates the game enforces (gathering/crafting level) become queue
            // requirements, so the task waits until a character qualifies.
            val requirements = b.engine?.let { e ->
                SpecRequirements.withSkillMinimums(req.spec, req.requirements,
                    resourceLevel = { code -> runCatching { e.contentCache.getResource(code).level }.getOrNull() },
                    itemLevel = { code -> runCatching { e.contentCache.getItem(code).craft?.level }.getOrNull() })
            } ?: req.requirements
            val task = b.queue.enqueue(
                NewTask(
                    type = req.spec.typeName, spec = req.spec.toJson(), source = TaskSource.MANUAL,
                    priority = req.priority ?: TaskSource.MANUAL.defaultPriority,
                    assignedCharacter = req.assignedCharacter, requirements = requirements,
                    stopCondition = req.stopCondition, dedupeKey = req.dedupeKey,
                    notBeforeMillis = req.notBeforeMillis, expiresAtMillis = req.expiresAtMillis,
                )
            ) ?: return@post call.respond(HttpStatusCode.Conflict, ApiError("a live task with that dedupe key exists"))
            call.respond(HttpStatusCode.Created, task)
        }
        post("/group") {
            val req = call.receive<CreateGroupRequest>()
            if (req.slots.size !in 1..3) return@post call.badRequest("a group needs 1-3 slots")
            val named = req.slots.mapNotNull { it.assignedCharacter }
            if (named.size != named.distinct().size) return@post call.badRequest("a character can hold only one slot")
            named.forEach { n -> validateCharacter(b, n)?.let { return@post call.badRequest(it) } }
            val group = b.queue.enqueueGroup(
                NewTask(
                    type = req.spec.typeName, spec = req.spec.toJson(), source = TaskSource.MANUAL,
                    priority = req.priority ?: TaskSource.MANUAL.defaultPriority,
                    stopCondition = req.stopCondition, expiresAtMillis = req.expiresAtMillis,
                ),
                req.slots,
            ) ?: return@post call.respond(HttpStatusCode.Conflict, ApiError("duplicate group"))
            call.respond(HttpStatusCode.Created, group)
        }
        get("/{id}") {
            val t = b.queue.get(call.id()) ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("no such task"))
            call.respond(t)
        }
        get("/{id}/events") { call.respond(b.queue.events(call.id())) }
        get("/{id}/group") {
            val t = b.queue.get(call.id()) ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("no such task"))
            val g = t.groupId?.let { b.queue.group(it) } ?: return@get call.respond(HttpStatusCode.NotFound, ApiError("not a group task"))
            call.respond(g)
        }
        post("/{id}/cancel") {
            val reason = runCatching { call.receive<CancelRequest>().reason }.getOrDefault("cancelled by user")
            b.queue.cancel(call.id(), reason)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ── Characters ─────────────────────────────────────────────────────────
    route("/characters") {
        get {
            val engine = b.engine
            val names = engine?.characters.orEmpty()
            val view = engine?.let { ApiCharacterView(it.helper) }
            call.respond(coroutineScope {
                names.map { n ->
                    async {
                        val snap = view?.let { v -> runCatching { v.snapshot(n) }.getOrNull() }
                        CharacterDto(n, engine?.pool?.statuses?.get(n)?.value, b.settings.get(n).toDto(),
                            level = snap?.level, skills = snap?.skills.orEmpty())
                    }
                }.awaitAll()
            })
        }
        get("/{name}/details") {
            val e = b.engine ?: return@get call.engineDown()
            val name = call.parameters["name"]!!
            if (name !in e.characters) return@get call.respond(HttpStatusCode.NotFound, ApiError("unknown character"))
            call.respond(e.helper.refreshCharacter(name))
        }
        put("/{name}/settings") {
            val name = call.parameters["name"]!!
            validateCharacter(b, name)?.let { return@put call.badRequest(it) }
            val dto = call.receive<CharacterSettingsDto>()
            b.settings.put(CharacterSettings(
                name = name, enabled = dto.enabled, allowedTypes = dto.allowedTypes,
                filler = dto.filler?.let { NewTask(type = it.typeName, spec = it.toJson()) },
            ))
            call.respond(b.settings.get(name).toDto())
        }
    }

    // ── Configs (event / raid documents) ───────────────────────────────────
    route("/configs/{kind}") {
        get { call.respond(b.configStore.list(call.parameters["kind"]!!)) }
        put("/{key}") {
            val body = call.receive<JsonElement>()
            b.configStore.put(call.parameters["kind"]!!, call.parameters["key"]!!, body)
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/{key}") {
            b.configStore.delete(call.parameters["kind"]!!, call.parameters["key"]!!)
            call.respond(HttpStatusCode.NoContent)
        }
    }

    // ── Game state ─────────────────────────────────────────────────────────
    get("/bank") {
        val e = b.engine ?: return@get call.engineDown()
        call.respond(e.bankState.snapshot.value.map { BankItemDto(it.key, it.value) }.sortedBy { it.code })
    }
    get("/rates") {
        val e = b.engine ?: return@get call.engineDown()
        val snap = e.client.rateLimiter.snapshot()
        call.respond(RatesDto(snap.entries.associate { (cat, windows) ->
            cat.name.lowercase() to windows.map { (w, used) -> RateWindowDto(w.limit, w.durationMillis, used) }
        }))
    }
    get("/logs") {
        val e = b.engine ?: return@get call.engineDown()
        val n = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 200).coerceIn(1, 500)
        call.respond(e.logger.getRecent(n, call.request.queryParameters["character"]).map { it.toDto() })
    }

    // ── Control ────────────────────────────────────────────────────────────
    post("/control/resume") {
        val e = b.engine ?: return@post call.engineDown()
        e.control.resume()
        call.respond(ControlDto(null))
    }

    // ── Live stream (Server-Sent Events) ───────────────────────────────────
    // Events: `worker` (WorkerStatus), `task` ({id}), `log` (LogDto), `control` ({paused}), `bank` ({seq}).
    // The browser keeps one EventSource open and applies these to its stores.
    sse("/stream") {
        val out = Channel<ServerSentEvent>(capacity = 512)
        fun <T> emit(event: String, s: KSerializer<T>, v: T) {
            out.trySend(ServerSentEvent(data = ApiJson.encodeToString(s, v), event = event))
        }
        coroutineScope {
            val e = b.engine
            if (e != null) {
                // Current state first, then changes.
                e.pool.statuses.values.forEach { emit("worker", WorkerStatusSerializer, it.value) }
                emit("control", ControlDto.serializer(), ControlDto(e.control.fatalReason.value))
                e.pool.statuses.values.forEach { flow ->
                    launch { flow.drop(1).collect { emit("worker", WorkerStatusSerializer, it) } }
                }
                launch { e.logger.live.collect { emit("log", LogDto.serializer(), it.toDto()) } }
                launch { e.control.fatalReason.drop(1).collect { emit("control", ControlDto.serializer(), ControlDto(it)) } }
                // Bank contents change live (WebSocket deltas); tell the UI at most once a
                // second so pickers showing "max craftable" can refresh.
                launch {
                    var n = 0L
                    e.bankState.snapshot.drop(1).sample(1_000).collect { emit("bank", BankChangedDto.serializer(), BankChangedDto(++n)) }
                }
            }
            launch { b.queue.changes.collect { emit("task", TaskChangedDto.serializer(), TaskChangedDto(it)) } }
            // Keep proxies from closing an idle connection.
            launch { while (isActive) { delay(20_000); out.trySend(ServerSentEvent(comments = "ping")) } }
            for (ev in out) send(ev)
        }
    }
}

private val WorkerStatusSerializer = com.artifactsmmo.engine.worker.WorkerStatus.serializer()

private fun CharacterSettings.toDto() = CharacterSettingsDto(
    enabled = enabled, allowedTypes = allowedTypes,
    filler = filler?.let { runCatching { TaskSpec.fromJson(it.spec) }.getOrNull() },
)

private fun com.artifactsmmo.core.task.TaskLogger.LogEntry.toDto() = LogDto(
    timestampMillis = timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    character = characterName, message = message,
)

private fun validateCharacter(b: ApiBackend, name: String?): String? {
    val known = b.engine?.characters ?: return null // can't validate before the engine boots
    return if (name != null && name !in known) "unknown character '$name'" else null
}

private fun ApplicationCall.id(): Long =
    parameters["id"]?.toLongOrNull() ?: throw BadRequest("task id must be a number")

private suspend fun ApplicationCall.badRequest(msg: String) = respond(HttpStatusCode.BadRequest, ApiError(msg))
private suspend fun ApplicationCall.engineDown() =
    respond(HttpStatusCode.ServiceUnavailable, ApiError("engine not running (no token, or waiting for the instance lock)"))

class BadRequest(message: String) : Exception(message)
