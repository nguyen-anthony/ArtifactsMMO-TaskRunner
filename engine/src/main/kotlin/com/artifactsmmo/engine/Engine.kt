package com.artifactsmmo.engine

import com.artifactsmmo.client.ArtifactsMMOClient
import com.artifactsmmo.client.RealtimeClient
import com.artifactsmmo.client.utils.CharacterUtils
import com.artifactsmmo.core.task.ActionHelper
import com.artifactsmmo.core.task.BankReserver
import com.artifactsmmo.core.task.BankState
import com.artifactsmmo.core.task.CleanupHolder
import com.artifactsmmo.core.task.ContentCache
import com.artifactsmmo.core.task.CoopOptimizer
import com.artifactsmmo.core.task.CraftingExecutor
import com.artifactsmmo.core.task.EventExecutor
import com.artifactsmmo.core.task.FightingExecutor
import com.artifactsmmo.core.task.GatheringExecutor
import com.artifactsmmo.core.task.ItemTaskTarget
import com.artifactsmmo.core.task.KnownLoadoutStore
import com.artifactsmmo.core.task.MonsterProfileStore
import com.artifactsmmo.core.task.NpcTrade
import com.artifactsmmo.core.task.ResourceContent
import com.artifactsmmo.core.task.TaskLogger
import com.artifactsmmo.core.task.TeleportPotionStore
import com.artifactsmmo.core.task.ToolEffects
import com.artifactsmmo.core.task.ToolKitPlan
import com.artifactsmmo.core.task.WebSocketManager
import com.artifactsmmo.engine.config.ConfigStore
import com.artifactsmmo.engine.config.TypedConfigs
import com.artifactsmmo.engine.queue.TaskQueue
import com.artifactsmmo.engine.realtime.EventTaskProducer
import com.artifactsmmo.engine.realtime.LegacyEventPlanner
import com.artifactsmmo.engine.realtime.LegacyRaidPlanner
import com.artifactsmmo.engine.scheduler.BankThresholdProducer
import com.artifactsmmo.engine.scheduler.CleanupProducer
import com.artifactsmmo.engine.scheduler.OfferingTaskProducer
import com.artifactsmmo.engine.scheduler.RaidTaskProducer
import com.artifactsmmo.engine.scheduler.ScheduleRunner
import com.artifactsmmo.engine.scheduler.ScheduleStore
import com.artifactsmmo.engine.scheduler.ToolKitProducer
import com.artifactsmmo.engine.worker.ApiCharacterView
import com.artifactsmmo.engine.worker.BossFightExecutor
import com.artifactsmmo.engine.worker.CharacterSettingsStore
import com.artifactsmmo.engine.worker.CharacterWorker
import com.artifactsmmo.engine.worker.DailyOfferingExecutor
import com.artifactsmmo.engine.worker.GroupCoordinator
import com.artifactsmmo.engine.worker.LegacyBossOps
import com.artifactsmmo.engine.worker.LegacyExecutors
import com.artifactsmmo.engine.worker.LegacyOfferingOps
import com.artifactsmmo.engine.worker.OfferingBoard
import com.artifactsmmo.engine.worker.ToolKitSyncExecutor
import com.artifactsmmo.engine.worker.toolHolderOf
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
    realtimeUrl: String = RealtimeClient.PRODUCTION_URL,
) {
    val configs = TypedConfigs(configStore)
    val control = WorkerControl()
    val contentCache = ContentCache(client.content)
    val bankState = BankState(client, scope, logger)
    val helper = ActionHelper(client, contentCache, bankState)
    private val fighting = FightingExecutor(helper)
    val gearOptimizer get() = fighting.gearOptimizer
    val coop = CoopOptimizer(helper, fighting.gearOptimizer, client)
    val webSocket = WebSocketManager(RealtimeClient(token, realtimeUrl), client.events, scope, bankState, log = { logger.log(it) })

    lateinit var characters: List<String>
        private set
    lateinit var pool: WorkerPool
        private set
    lateinit var events: EventTaskProducer
        private set
    /** Seasonal Altar overview/previews for the API. */
    lateinit var offerings: OfferingBoard
        private set
    private var legacyExecutors: LegacyExecutors? = null

    /** A running fight / task-master task re-optimizes its loadout before the next fight. */
    fun forgetLoadout(character: String) {
        legacyExecutors?.forgetLoadout(character)
    }

    /** Recomputes the tool kit plan and queues syncs/recycles (also runs every 2 minutes). */
    val toolKit = ToolKitProducer(
        queue, configs,
        holders = {
            client.characters.getMyCharacters().map { c -> toolHolderOf(c) { contentCache.getItemOrNull(it) } }
        },
        tools = { contentCache.getItemsByType("weapon").filter { ToolEffects.skillOf(it) != null } },
        bank = { bankState.snapshot.value },
        onPlan = { helper.toolKitPlan = it },
        log = { logger.log(it) },
    )

    /** The tool kit allocation in force (null = disabled or not computed yet). */
    val toolKitPlan: ToolKitPlan? get() = helper.toolKitPlan

    /** Re-read the gathering gear focus settings (on start and whenever they're saved). */
    suspend fun reloadGearFocus() {
        helper.gearFocus = runCatching { configs.gearFocus() }.getOrElse { helper.gearFocus }
    }

    /** Re-read the fight food settings (on start and whenever they're saved). */
    suspend fun reloadFood() {
        helper.foodConfig = runCatching { configs.food() }.getOrElse { helper.foodConfig }
    }

    /** Obsolete gear report; queues recycles when auto-recycle is on (every 10 minutes). */
    val cleanup = CleanupProducer(
        queue, configs,
        holders = {
            client.characters.getMyCharacters().map { c ->
                CleanupHolder(
                    name = c.name, level = c.level,
                    skillLevels = CLEANUP_SKILLS.associateWith { CharacterUtils.getSkillLevel(c, it) ?: 0 },
                    achievements = helper.completedAchievements,
                    equipped = listOf(
                        c.weaponSlot, c.runeSlot, c.shieldSlot, c.helmetSlot, c.bodyArmorSlot, c.legArmorSlot,
                        c.bootsSlot, c.ring1Slot, c.ring2Slot, c.amuletSlot, c.artifact1Slot, c.artifact2Slot,
                        c.artifact3Slot, c.bagSlot,
                    ).filter { it.isNotEmpty() }.groupingBy { it }.eachCount(),
                    inventory = c.inventory.filter { it.code.isNotEmpty() && it.quantity > 0 }
                        .groupingBy { it.code }.fold(0) { acc, s -> acc + s.quantity },
                )
            }
        },
        items = { types -> types.flatMap { contentCache.getItemsByType(it) } },
        bank = { bankState.snapshot.value },
        bankAvailable = { bankState.availableFor(emptySet()) },
        loadouts = { KnownLoadoutStore().all() },
        resourceContent = { resourceContent() },
        log = { logger.log(it) },
    )

    @Volatile private var resourceMeta: Pair<Long, Pair<List<ItemTaskTarget>, List<NpcTrade>>>? = null

    /** Items (cached by ContentCache) + Task Master targets and NPC trades (re-fetched daily). */
    private suspend fun resourceContent(): ResourceContent {
        val items = contentCache.getAllItems()
        val now = System.currentTimeMillis()
        val meta = resourceMeta?.takeIf { now - it.first < 24 * 60 * 60_000L }?.second ?: run {
            suspend fun <T> pages(fetch: suspend (Int) -> com.artifactsmmo.client.models.DataPage<T>): List<T> {
                val out = mutableListOf<T>()
                var page = 1
                while (true) {
                    val p = fetch(page)
                    out += p.data
                    if (p.data.isEmpty() || page >= (p.pages ?: 1)) break
                    page++
                }
                return out
            }
            val tasks = pages { client.tasks.getTasksList(page = it, size = 100) }
                .filter { it.type == "items" }.map { ItemTaskTarget(it.code, it.level, it.skill) }
            val names = pages { client.content.getNPCs(page = it, size = 100) }.associate { it.code to it.name }
            val eventNpcs = runCatching {
                pages { client.events.getEvents(page = it, size = 100) }
                    .filter { it.content.type == "npc" }.mapTo(HashSet()) { it.content.code }
            }.getOrDefault(emptySet())
            val staticNpcs = contentCache.mapContentCodes("npc")
            val trades = contentCache.getAllNpcItems().map {
                NpcTrade(it.npc, names[it.npc] ?: it.npc, it.code, it.currency, it.buyPrice, it.sellPrice,
                    permanent = it.npc in staticNpcs && it.npc !in eventNpcs)
            }
            (tasks to trades).also { resourceMeta = now to it }
        }
        return ResourceContent(items, meta.first, meta.second)
    }

    /** Bank restock rules -> unassigned craft/gather tasks (every 3 minutes). */
    val thresholds = BankThresholdProducer(
        queue, configs,
        bank = { bankState.snapshot.value },
        itemOf = { contentCache.getItemOrNull(it) },
        resourcesDropping = { runCatching { contentCache.getResourcesByDrop(it) }.getOrDefault(emptyList()) },
        onReserves = { helper.bankReserves = it },
        log = { logger.log(it) },
    )

    @OptIn(kotlin.time.ExperimentalTime::class)
    suspend fun start() {
        val achievements = runCatching {
            client.account.getCompletedAchievementCodes(client.account.getMyDetails().username)
        }.getOrDefault(emptySet())
        contentCache.preWarmMaps(achievements)
        helper.completedAchievements = achievements
        reloadGearFocus()
        reloadFood()
        MonsterProfileStore.load()
        TeleportPotionStore.load()
        characters = client.characters.getMyCharacters().map { it.name }

        val log: (String) -> Unit = { logger.log(it) }
        events = EventTaskProducer(webSocket.messages, queue, configs,
            LegacyEventPlanner(helper, fighting.gearOptimizer, coop, { characters }, log), log, reserver = bankState)

        val gathering = GatheringExecutor(helper)
        val crafting = CraftingExecutor(helper)
        val offeringOps = LegacyOfferingOps(
            helper, client.offerings, gathering, fighting, crafting,
            findAltars = { client.content.getMaps(contentType = "seasonal_altar").data },
        )
        offerings = OfferingBoard(offeringOps, { client.offerings.getDaily() }, { characters })
        val registry = LegacyExecutors(
            helper, gathering = gathering, fighting = fighting, crafting = crafting,
            event = EventExecutor(helper, fighting),
            eventActive = { events.isActive(it) },
            bossFights = BossFightExecutor(LegacyBossOps(helper, fighting), queue, GroupCoordinator()),
            offerings = DailyOfferingExecutor(offeringOps),
            toolKitSync = ToolKitSyncExecutor(helper),
        )
        legacyExecutors = registry
        val view = ApiCharacterView(helper)
        pool = WorkerPool(queue, characters.map { name ->
            CharacterWorker(
                character = name, queue = queue, executors = registry, view = view, settings = settings,
                awaitCooldown = { client.cooldowns.awaitReady(it) }, control = control,
                log = { c, m -> logger.log(c, m) },
                onTaskEnd = { t ->
                    bankState.release(BankReserver.taskOwner(t.id))
                    t.groupId?.let { bankState.release(BankReserver.taskOwner(it)) }
                },
            )
        })

        bankState.start()
        // Subscribe the producer before the socket starts so the initial replay isn't missed.
        events.start(scope)
        RaidTaskProducer(queue, configs, { client.raids.getRaids().data }, LegacyRaidPlanner(coop),
            webSocket.messages, log = log, reserver = bankState).start(scope)
        ScheduleRunner(schedules, queue, log = log).start(scope)
        OfferingTaskProducer(queue, configs, { characters }, log = log).start(scope)
        toolKit.start(scope)
        cleanup.start(scope)
        thresholds.start(scope)
        pool.start(scope)
        webSocket.start()
    }

    fun stop() {
        webSocket.stop()
        bankState.stop()
    }

    private companion object {
        val CLEANUP_SKILLS = listOf(
            "mining", "woodcutting", "fishing", "weaponcrafting", "gearcrafting", "jewelrycrafting", "cooking", "alchemy",
        )
    }
}
