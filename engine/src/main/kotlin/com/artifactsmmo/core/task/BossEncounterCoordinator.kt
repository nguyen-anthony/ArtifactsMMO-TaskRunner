package com.artifactsmmo.core.task

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the rendezvous barrier between a boss fight initiator and its participants.
 *
 * Flow:
 *  1. [registerEncounter] is called at task-assignment time with the initiator name,
 *     participant names, and the monster code.
 *  2. Each participant runner calls [signalReady] when it is positioned on the boss tile
 *     and cooldown-free with a free inventory slot.
 *  3. The initiator runner calls [awaitParticipants] which suspends until all participants
 *     have signalled. Once all signals are collected, the initiator fires the fight API call.
 *  4. When the loop ends or is cancelled, [clearEncounter] closes all channels and
 *     removes the entry so waiting coroutines are released.
 */
class BossEncounterCoordinator {

    private data class EncounterEntry(
        val monsterCode: String,
        val initiatorName: String,
        val participantNames: List<String>,
        /** One Channel<Unit> per participant, keyed by participant character name. */
        val channels: Map<String, Channel<Unit>>
    )

    private val encounters = ConcurrentHashMap<String, EncounterEntry>()

    /**
     * Register an encounter before launching runners.
     * Called by [TaskManager.assignBossFight] on the main thread before coroutines start.
     */
    fun registerEncounter(initiatorName: String, participantNames: List<String>, monsterCode: String) {
        val channels = participantNames.associateWith { Channel<Unit>(capacity = 1) }
        encounters[initiatorName] = EncounterEntry(
            monsterCode = monsterCode,
            initiatorName = initiatorName,
            participantNames = participantNames,
            channels = channels
        )
    }

    /**
     * Suspend until all participants in the encounter have signalled ready.
     * Called by the initiator runner at the top of each fight-loop iteration.
     */
    suspend fun awaitParticipants(initiatorName: String) {
        val entry = encounters[initiatorName] ?: return
        for (participantName in entry.participantNames) {
            val channel = entry.channels[participantName] ?: continue
            channel.receive()
        }
    }

    /**
     * Signal that this participant is ready (positioned, cooldown-free, slot free).
     * Sends to the participant's own channel; the initiator's [awaitParticipants] drains it.
     * No-op if the character is not a participant in any active encounter.
     */
    suspend fun signalReady(participantName: String) {
        val initiatorName = encounterFor(participantName) ?: return
        val entry = encounters[initiatorName] ?: return
        val channel = entry.channels[participantName] ?: return
        channel.send(Unit)
    }

    /**
     * Close all channels for this encounter and remove it from the map.
     * Any coroutine suspended in [awaitParticipants] will receive a [ClosedReceiveChannelException]
     * and should handle it as a cancellation signal.
     * Called by the initiator runner on stop/cancel.
     */
    fun clearEncounter(initiatorName: String) {
        val entry = encounters.remove(initiatorName) ?: return
        for (channel in entry.channels.values) {
            channel.close()
        }
    }

    /**
     * Returns the initiator's character name if [participantName] is an active participant
     * in any registered encounter, or null if not.
     */
    fun encounterFor(participantName: String): String? {
        for ((initiatorName, entry) in encounters) {
            if (participantName in entry.participantNames) return initiatorName
        }
        return null
    }
}
