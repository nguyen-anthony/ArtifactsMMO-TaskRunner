package com.artifactsmmo.client.gateway

/**
 * What the caller should do with an ArtifactsMMO error code. One table replaces the
 * `when (e.errorCode)` blocks that were scattered across executors.
 *
 * Codes from artifacts-docs `api_guide/response_codes.mdx`.
 */
sealed class ErrorAction {
    /** Transient; the transport already retried. Try the step again later. */
    data object Retry : ErrorAction()
    /** Character is busy/in cooldown; wait for the cooldown then retry the same step. */
    data object WaitCooldown : ErrorAction()
    /** Desired state already holds (e.g. already on that map); treat as success. */
    data object Benign : ErrorAction()
    /** World state differs from what the executor assumed; re-plan the step. */
    data class Replan(val reason: ReplanReason) : ErrorAction()
    /** This task cannot succeed; fail it (other tasks may still run). */
    data object FailTask : ErrorAction()
    /** Account-level problem (bad token). Pause every worker and alert the UI. */
    data object Fatal : ErrorAction()
}

enum class ReplanReason {
    INVENTORY_FULL, MISSING_ITEM, NOT_ENOUGH_GOLD, NOT_ENOUGH_HP, WRONG_LOCATION,
    NO_PATH, TASK_STATE, EQUIPMENT, BANK_FULL, CONDITION_NOT_MET, NOT_ACTIVE,
}

object ErrorPolicy {
    fun classify(code: Int): ErrorAction = when (code) {
        // Transport / generic
        -1, 429, 500, 502, 503, 504 -> ErrorAction.Retry
        461, 436 -> ErrorAction.Retry                      // bank / GE transaction in progress
        486, 499 -> ErrorAction.WaitCooldown                // locked / in cooldown
        // Already true
        490, 485 -> ErrorAction.Benign                      // already on map / already equipped
        // Re-plan
        497 -> ErrorAction.Replan(ReplanReason.INVENTORY_FULL)
        478, 480 -> ErrorAction.Replan(ReplanReason.MISSING_ITEM)
        492, 460 -> ErrorAction.Replan(ReplanReason.NOT_ENOUGH_GOLD)
        483 -> ErrorAction.Replan(ReplanReason.NOT_ENOUGH_HP)
        598 -> ErrorAction.Replan(ReplanReason.WRONG_LOCATION)
        595, 596 -> ErrorAction.Replan(ReplanReason.NO_PATH)
        474, 475, 487, 488, 489 -> ErrorAction.Replan(ReplanReason.TASK_STATE)
        484, 491 -> ErrorAction.Replan(ReplanReason.EQUIPMENT)
        462 -> ErrorAction.Replan(ReplanReason.BANK_FULL)
        496 -> ErrorAction.Replan(ReplanReason.CONDITION_NOT_MET)
        567, 564 -> ErrorAction.Replan(ReplanReason.NOT_ACTIVE) // raid not active / event gone
        // Account
        451, 452, 453, 454 -> ErrorAction.Fatal
        // Everything else (404, 422, 493 skill too low, 472/473/476 bad item, …)
        else -> ErrorAction.FailTask
    }
}
