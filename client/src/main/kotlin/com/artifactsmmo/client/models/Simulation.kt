package com.artifactsmmo.client.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FakeCharacterRequest(
    val level: Int,
    @SerialName("weapon_slot") val weaponSlot: String? = null,
    @SerialName("rune_slot") val runeSlot: String? = null,
    @SerialName("shield_slot") val shieldSlot: String? = null,
    @SerialName("helmet_slot") val helmetSlot: String? = null,
    @SerialName("body_armor_slot") val bodyArmorSlot: String? = null,
    @SerialName("leg_armor_slot") val legArmorSlot: String? = null,
    @SerialName("boots_slot") val bootsSlot: String? = null,
    @SerialName("ring1_slot") val ring1Slot: String? = null,
    @SerialName("ring2_slot") val ring2Slot: String? = null,
    @SerialName("amulet_slot") val amuletSlot: String? = null,
    @SerialName("artifact1_slot") val artifact1Slot: String? = null,
    @SerialName("artifact2_slot") val artifact2Slot: String? = null,
    @SerialName("artifact3_slot") val artifact3Slot: String? = null,
    @SerialName("utility1_slot") val utility1Slot: String? = null,
    @SerialName("utility1_slot_quantity") val utility1SlotQuantity: Int? = null,
    @SerialName("utility2_slot") val utility2Slot: String? = null,
    @SerialName("utility2_slot_quantity") val utility2SlotQuantity: Int? = null
) {
    companion object {
        /**
         * Build a FakeCharacterRequest from a real Character's current equipment.
         */
        fun fromCharacter(char: Character): FakeCharacterRequest {
            return FakeCharacterRequest(
                level = char.level,
                weaponSlot = char.weaponSlot.ifEmpty { null },
                runeSlot = char.runeSlot.ifEmpty { null },
                shieldSlot = char.shieldSlot.ifEmpty { null },
                helmetSlot = char.helmetSlot.ifEmpty { null },
                bodyArmorSlot = char.bodyArmorSlot.ifEmpty { null },
                legArmorSlot = char.legArmorSlot.ifEmpty { null },
                bootsSlot = char.bootsSlot.ifEmpty { null },
                ring1Slot = char.ring1Slot.ifEmpty { null },
                ring2Slot = char.ring2Slot.ifEmpty { null },
                amuletSlot = char.amuletSlot.ifEmpty { null },
                artifact1Slot = char.artifact1Slot.ifEmpty { null },
                artifact2Slot = char.artifact2Slot.ifEmpty { null },
                artifact3Slot = char.artifact3Slot.ifEmpty { null },
                utility1Slot = char.utility1Slot.ifEmpty { null },
                utility1SlotQuantity = if (char.utility1Slot.isNotEmpty()) char.utility1SlotQuantity else null,
                utility2Slot = char.utility2Slot.ifEmpty { null },
                utility2SlotQuantity = if (char.utility2Slot.isNotEmpty()) char.utility2SlotQuantity else null
            )
        }
    }
}

@Serializable
data class CombatSimulationRequest(
    val characters: List<FakeCharacterRequest>,
    val monster: String,
    val iterations: Int
)

@Serializable
data class CombatSimulationResult(
    val result: String,
    val turns: Int
)

@Serializable
data class CombatSimulationData(
    val results: List<CombatSimulationResult>,
    val wins: Int,
    val losses: Int,
    val winrate: Double
)
