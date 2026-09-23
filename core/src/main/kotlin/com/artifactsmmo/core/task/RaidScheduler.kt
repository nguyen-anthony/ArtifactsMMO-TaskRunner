package com.artifactsmmo.core.task

import com.artifactsmmo.client.RealtimeMessage
import com.artifactsmmo.client.models.Raid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime

/**
 * Dispatches explicitly configured raid teams before scheduled starts and restores their
 * interrupted work when a raid ends. Raid mechanics remain in BossFight/FightingExecutor.
 */
@OptIn(ExperimentalTime::class)
class RaidScheduler(
    private val taskManager: TaskManager,
    private val configStore: RaidConfigStore,
    private val webSocketManager: WebSocketManager,
    private val scope: CoroutineScope
) {
    private val dispatchedStarts = mutableSetOf<String>()
    private val interruptedTasks = mutableMapOf<String, Map<String, TaskType>>()
    private var pollJob: Job? = null
    private var messageJob: Job? = null

    fun start() {
        pollJob?.cancel()
        messageJob?.cancel()
        messageJob = scope.launch {
            webSocketManager.messages.collect { message ->
                if (message is RealtimeMessage.RaidEnded && message.raidCode.isNotEmpty()) {
                    finishRaid(message.raidCode, "WebSocket raid_ended")
                }
            }
        }
        pollJob = scope.launch {
            // Startup late-join check.
            checkActiveRaids()
            while (isActive) {
                checkScheduledRaids()
                checkRunningRaids()
                delay(60.seconds)
            }
        }
    }

    fun stop() {
        pollJob?.cancel()
        messageJob?.cancel()
    }

    fun getConfigs(): List<RaidConfig> = configStore.load()

    fun saveConfigs(configs: List<RaidConfig>) = configStore.save(configs)

    private suspend fun checkActiveRaids() {
        val configs = configStore.load().filter { it.enabled && it.initiatorName != null }
        if (configs.isEmpty()) return
        val active = try { taskManager.client.raids.getRaids(active = true).data } catch (_: Exception) { return }
        for (raid in active) {
            val config = configs.firstOrNull { it.raidCode == raid.code } ?: continue
            dispatch(config, raid, lateJoin = true)
        }
    }

    private suspend fun checkScheduledRaids() {
        val configs = configStore.load().filter { it.enabled && it.initiatorName != null }
        if (configs.isEmpty()) return
        val raids = try { taskManager.client.raids.getRaids().data } catch (_: Exception) { return }
        val now = Clock.System.now()
        for (raid in raids) {
            val config = configs.firstOrNull { it.raidCode == raid.code } ?: continue
            if (raid.status == "active") {
                dispatch(config, raid, lateJoin = true)
                continue
            }
            val start = raid.nextStartAt ?: continue
            val key = "${raid.code}:${start}"
            if (key in dispatchedStarts) continue
            if (start - now <= config.leadTimeMinutes.minutes && start > now) {
                dispatch(config, raid, lateJoin = false)
            }
        }
    }

    /** 60s fallback when raid_ended is missed. */
    private suspend fun checkRunningRaids() {
        for (raidCode in interruptedTasks.keys.toList()) {
            val raid = try { taskManager.client.raids.getRaid(raidCode) } catch (_: Exception) { continue }
            if (raid.status.startsWith("finished") || raid.activeInstance == null) {
                finishRaid(raidCode, "Raid status poll")
            }
        }
    }

    private suspend fun dispatch(config: RaidConfig, raid: Raid, lateJoin: Boolean) {
        val initiator = config.initiatorName ?: return
        val participants = config.participantNames.filter { it != initiator }.take(2)
        val allNames = listOf(initiator) + participants
        if (allNames.distinct().size != allNames.size) return
        if (raid.code in interruptedTasks) return

        val start = raid.activeInstance?.startsAt ?: raid.nextStartAt
        val dedupeKey = "${raid.code}:${start ?: "active"}"
        if (!dispatchedStarts.add(dedupeKey)) return

        val target = taskManager.findRaidMap(raid.code)
        if (target == null) {
            taskManager.logger.log("RaidScheduler: no map found for raid ${raid.code}")
            return
        }

        val interrupted = allNames.associateWith { taskManager.currentTask(it) ?: TaskType.Idle }
        interruptedTasks[raid.code] = interrupted
        try {
            val optimized = taskManager.optimizeForBossFight(allNames, raid.monster, config.tankOverride)
            taskManager.assignRaidFight(
                raid = raid,
                initiatorName = initiator,
                participantNames = participants,
                participantPlans = optimized.participants.associateBy { it.characterName },
                scheduledStartAtMillis = start?.toEpochMilliseconds(),
                scheduledEndAtMillis = raid.activeInstance?.endsAt?.toEpochMilliseconds()
            )
            taskManager.logger.log("RaidScheduler: ${if (lateJoin) "late-joined" else "dispatched"} ${raid.code}")
        } catch (e: Exception) {
            interruptedTasks.remove(raid.code)
            taskManager.logger.log("RaidScheduler: dispatch failed for ${raid.code}: ${e.message}")
        }
    }

    private fun finishRaid(raidCode: String, source: String) {
        val interrupted = interruptedTasks.remove(raidCode) ?: return
        taskManager.stopRaidAndRestore(raidCode, interrupted)
        taskManager.logger.log("RaidScheduler: restored tasks after $raidCode ended ($source)")
    }
}
