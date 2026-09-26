package com.artifactsmmo.engine

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.RealtimeClient
import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.BankState
import com.artifactsmmo.core.task.ContentCache
import com.artifactsmmo.core.task.CoopOptimizer
import com.artifactsmmo.core.task.EventExecutor
import com.artifactsmmo.core.task.FightingExecutor
import com.artifactsmmo.core.task.GatheringExecutor
import com.artifactsmmo.core.task.MonsterProfileStore
import com.artifactsmmo.core.task.TaskLogger
import com.artifactsmmo.core.task.TeleportPotionStore
import com.artifactsmmo.core.task.WebSocketManager
import com.artifactsmmo.engine.config.ConfigStore
import com.artifactsmmo.engine.config.TypedConfigs
import com.artifactsmmo.engine.queue.TaskQueue
import com.artifactsmmo.engine.realtime.EventTaskProducer
import com.artifactsmmo.engine.realtime.LegacyEventPlanner
import com.artifactsmmo.engine.realtime.LegacyRaidPlanner
import com.artifactsmmo.engine.scheduler.RaidTaskProducer
import com.artifactsmmo.engine.scheduler.ScheduleRunner
import com.artifactsmmo.engine.scheduler.ScheduleStore
import com.artifactsmmo.engine.worker.ApiCharacterView
import com.artifactsmmo.engine.worker.BossFightExecutor
import com.artifactsmmo.engine.worker.CharacterSettingsStore
import com.artifactsmmo.engine.worker.CharacterWorker
import com.artifactsmmo.engine.worker.GroupCoordinator
import com.artifactsmmo.engine.worker.LegacyBossOps
import com.artifactsmmo.engine.worker.LegacyExecutors
import com.artifactsmmo.engine.worker.WorkerControl
import com.artifactsmmo.engine.worker.WorkerPool
import kotlinx.coroutines.CoroutineScope

/**
 * Wires the whole game backend together (replaces the old TaskManager):
 * gateway client → caches → executors → one worker per character, plus the realtime event
 * producer, raid producer and schedule runner. Only call [start] in the process that holds
 * the instance lock.
 */
class Engine(
    val client: ArtifactsMMOClient,
    private val token: String,
    val queue: TaskQueue,
    val settings: CharacterSettingsStore,
    configStore: ConfigStore,
    private val schedules: ScheduleStore,
    private val scope: CoroutineScope,
    val logger: TaskLogger = TaskLogger(),
) {
    val configs = TypedConfigs(configStore)
    val control = WorkerControl()
    val contentCache = ContentCache(client.content)
    val bankState = BankState(client, scope, logger)
    val helper = ActionHelper(client, contentCache, bankState)
    private val fighting = FightingExecutor(helper)
    val gearOptimizer get() = fighting.gearOptimizer
    val coop = CoopOptimizer(helper, fighting.gearOptimizer, client)
    val webSocket = WebSocketManager(RealtimeClient(token), client.events, scope, bankState)

    lateinit var characters: List<String>
        private set
    lateinit var pool: WorkerPool
        private set
    lateinit var events: EventTaskProducer
        private set

    suspend fun start() {
        val achievements = runCatching {
            client.account.getCompletedAchievementCodes(client.account.getMyDetails().username)
        }.getOrDefault(emptySet())
        contentCache.preWarmMaps(achievements)
        helper.completedAchievements = achievements
        MonsterProfileStore.load()
        TeleportPotionStore.load()
        characters = client.characters.getMyCharacters().map { it.name }

        val log: (String) -> Unit = { logger.log(it) }
        events = EventTaskProducer(webSocket.messages, queue, configs,
            LegacyEventPlanner(helper, fighting.gearOptimizer, { characters }, log), log)

        val registry = LegacyExecutors(
            helper, gathering = GatheringExecutor(helper), fighting = fighting,
            event = EventExecutor(helper, fighting),
            eventActive = { events.isActive(it) },
            bossFights = BossFightExecutor(LegacyBossOps(helper, fighting), queue, GroupCoordinator()),
        )
        val view = ApiCharacterView(helper)
        pool = WorkerPool(queue, characters.map { name ->
            CharacterWorker(
                character = name, queue = queue, executors = registry, view = view, settings = settings,
                awaitCooldown = { client.cooldowns.awaitReady(it) }, control = control,
                log = { c, m -> logger.log(c, m) },
            )
        })

        bankState.start()
        // Subscribe the producer before the socket starts so the initial replay isn't missed.
        events.start(scope)
        RaidTaskProducer(queue, configs, { client.raids.getRaids().data }, LegacyRaidPlanner(coop),
            webSocket.messages, log = log).start(scope)
        ScheduleRunner(schedules, queue, log = log).start(scope)
        pool.start(scope)
        webSocket.start()
    }

    fun stop() {
        webSocket.stop()
        bankState.stop()
    }
}
