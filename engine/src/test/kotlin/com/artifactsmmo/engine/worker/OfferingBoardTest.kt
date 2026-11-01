package com.artifactsmmo.engine.worker

import com.artifactsmmo.client.ArtifactsApiException
import com.artifactsmmo.client.models.CharacterSeasonalOfferings
import com.artifactsmmo.client.models.DailySeasonalOfferings
import com.artifactsmmo.client.models.OfferingFavor
import com.artifactsmmo.client.models.OfferingRequirement
import com.artifactsmmo.client.models.SeasonalOffering
import com.artifactsmmo.client.models.SelectedOffering
import com.artifactsmmo.core.offering.AcquirePlan
import com.artifactsmmo.core.offering.AcquireStep
import com.artifactsmmo.core.task.StepResult
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OfferingBoardTest {

    private val combat = SeasonalOffering("c_21", "combat", "monster", 21, 30,
        OfferingRequirement("wolf_hair", 10), OfferingFavor("monster", "wolf"))
    private val mining = SeasonalOffering("m_10", "mining", "resource", 10, 20,
        OfferingRequirement("iron_ore", 20), OfferingFavor("resource", "iron_rocks"))
    private val daily = DailySeasonalOfferings("2026-11-01", "2026-11-02T00:00:00Z", offerings = listOf(combat, mining))

    private val ops = object : OfferingOps {
        override suspend fun status(character: String): CharacterSeasonalOfferings {
            if (character == "broken") throw ArtifactsApiException(451, "member only")
            return CharacterSeasonalOfferings(daily.date, daily.expiresAt, offerings = daily.offerings,
                selectedOfferings = listOf(SelectedOffering("c_21", "combat", "completed", rewardXp = 50,
                    requirement = combat.requirement, favor = combat.favor)),
                completedCategories = listOf("combat"))
        }
        override suspend fun accept(character: String, offeringId: String) = error("unused")
        override suspend fun complete(character: String, offeringId: String) = error("unused")
        override suspend fun level(character: String, category: String) = if (category == "combat") 25 else 12
        override suspend fun plan(character: String, code: String, quantity: Int, favor: OfferingFavor) = when (code) {
            "wolf_hair" -> AcquirePlan.Infeasible("cannot beat wolf")
            else -> AcquirePlan.Next(AcquireStep.Gather("iron_rocks", "mining", "iron_ore"), 600.0)
        }
        override suspend fun goToAltar(character: String, carryCode: String?, carryQuantity: Int, status: (String) -> Unit) = Unit
        override suspend fun work(character: String, step: AcquireStep, prepare: Boolean, status: (String) -> Unit): StepResult = error("unused")
    }

    private val board = OfferingBoard(ops, { daily }, { listOf("Ront", "broken") })

    @Test
    fun overviewReportsLevelsSelectionsAndPerCharacterErrors() = runTest {
        val o = board.overview()

        assertEquals(listOf(combat, mining), o.offerings)
        val ront = o.characters.single { it.name == "Ront" }
        assertEquals(mapOf("combat" to 25, "mining" to 12), ront.levels)
        assertEquals(listOf(SelectedOfferingRow("c_21", "combat", "completed", 50)), ront.selected)
        assertEquals("Seasonal offerings require a membership", o.characters.single { it.name == "broken" }.error)
    }

    @Test
    fun previewDescribesTheNextStepOrWhyItIsInfeasible() = runTest {
        assertEquals(OfferingPreview("m_10", "Ront", "next", nextStep = "mining iron_rocks for iron_ore", estimatedSeconds = 600.0),
            board.preview("Ront", "m_10"))
        assertEquals("cannot beat wolf", board.preview("Ront", "c_21").reason)
        assertEquals("infeasible", board.preview("Ront", "nope").kind)
    }
}
