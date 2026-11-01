package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.DailySeasonalOfferings
import com.artifactsmmo.client.models.SeasonalOffering
import com.artifactsmmo.core.offering.AcquirePlan
import com.artifactsmmo.core.offering.AcquireStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable

/** Read model for the Offerings tab: today's rotation, per-character progress, and feasibility previews. */
class OfferingBoard(
    private val ops: OfferingOps,
    private val daily: suspend () -> DailySeasonalOfferings,
    private val characters: () -> List<String>,
) {
    suspend fun overview(): OfferingOverview {
        val rotation = daily()
        val categories = rotation.offerings.map { it.category }.distinct()
        val rows = coroutineScope {
            characters().map { name -> async { characterRow(name, categories) } }.awaitAll()
        }
        return OfferingOverview(rotation.date, rotation.expiresAt, rotation.xpRewardPercent, rotation.offerings, rows)
    }

    private suspend fun characterRow(name: String, categories: List<String>): CharacterOfferingRow = try {
        val st = ops.status(name)
        CharacterOfferingRow(
            name = name,
            levels = ops.levels(name, categories),
            selected = st.selectedOfferings.map { SelectedOfferingRow(it.offeringId, it.category, it.status, it.rewardXp) },
            completedCategories = st.completedCategories,
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: ArtifactsApiException) {
        CharacterOfferingRow(name, error = explain(e))
    } catch (e: Exception) {
        CharacterOfferingRow(name, error = e.message ?: e::class.simpleName)
    }

    /** Can [character] obtain [offeringId]'s requirement right now? May run the gear optimizer (slow). */
    suspend fun preview(character: String, offeringId: String): OfferingPreview {
        val offering = daily().offerings.firstOrNull { it.id == offeringId }
            ?: return OfferingPreview(offeringId, character, "infeasible", reason = "not in today's rotation")
        return preview(character, offering)
    }

    private suspend fun preview(character: String, offering: SeasonalOffering): OfferingPreview {
        val req = offering.requirement
        return when (val plan = ops.plan(character, req.code, req.quantity, offering.favor)) {
            AcquirePlan.Ready -> OfferingPreview(offering.id, character, "ready", estimatedSeconds = 0.0)
            is AcquirePlan.Next -> OfferingPreview(offering.id, character, "next",
                nextStep = describe(plan.step), estimatedSeconds = plan.estimatedSeconds)
            is AcquirePlan.Infeasible -> OfferingPreview(offering.id, character, "infeasible", reason = plan.reason)
        }
    }

    companion object {
        fun describe(step: AcquireStep): String = when (step) {
            is AcquireStep.Gather -> "${step.skill} ${step.resourceCode} for ${step.itemCode}"
            is AcquireStep.Fight -> "fight ${step.monsterCode} for ${step.itemCode}"
            is AcquireStep.Craft -> "craft ${step.crafts}x ${step.itemCode} (${step.skill})"
        }

        /** Human-readable reason for an API failure (membership, no rotation). */
        fun explain(e: ArtifactsApiException): String = when (e.errorCode) {
            451 -> "Seasonal offerings require a membership"
            575 -> "No seasonal offerings available"
            else -> e.message
        }
    }
}

@Serializable
data class OfferingOverview(
    val date: String,
    val expiresAt: String,
    val xpRewardPercent: Int,
    val offerings: List<SeasonalOffering>,
    val characters: List<CharacterOfferingRow>,
)

@Serializable
data class CharacterOfferingRow(
    val name: String,
    /** Category -> combat/skill level. */
    val levels: Map<String, Int> = emptyMap(),
    val selected: List<SelectedOfferingRow> = emptyList(),
    val completedCategories: List<String> = emptyList(),
    val error: String? = null,
)

@Serializable
data class SelectedOfferingRow(val offeringId: String, val category: String, val status: String, val rewardXp: Int = 0)

@Serializable
data class OfferingPreview(
    val offeringId: String,
    val character: String,
    /** `ready` (requirement already owned), `next` (obtainable), or `infeasible`. */
    val kind: String,
    val nextStep: String? = null,
    val estimatedSeconds: Double? = null,
    val reason: String? = null,
)
