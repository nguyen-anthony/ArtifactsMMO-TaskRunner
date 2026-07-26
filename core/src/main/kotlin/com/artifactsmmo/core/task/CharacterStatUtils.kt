package com.artifactsmmo.core.task

import com.artifactsmmo.client.models.Character
import com.artifactsmmo.client.models.Item

/**
 * Returns a copy of this [Character] with stats modified by removing [removedItem]'s
 * effects and adding [addedItem]'s effects. Pass null for either to apply only one side.
 *
 * Used by [GearOptimizer] to build a modified character for local simulation without
 * making any API calls.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
fun Character.applyItemDelta(removedItem: Item?, addedItem: Item?): Character {
    var maxHp            = this.maxHp
    var resFire          = this.resFire
    var resEarth         = this.resEarth
    var resWater         = this.resWater
    var resAir           = this.resAir
    var dmg              = this.dmg
    var dmgFire          = this.dmgFire
    var dmgEarth         = this.dmgEarth
    var dmgWater         = this.dmgWater
    var dmgAir           = this.dmgAir
    var criticalStrike   = this.criticalStrike
    var haste            = this.haste
    var initiative       = this.initiative
    var threat           = this.threat
    var wisdom           = this.wisdom
    var prospecting      = this.prospecting
    var inventoryMaxItems = this.inventoryMaxItems

    fun applyEffects(item: Item, sign: Int) {
        for (effect in item.effects) {
            when (effect.code) {
                "hp"              -> maxHp             += effect.value * sign
                "res_fire"        -> resFire            += effect.value * sign
                "res_earth"       -> resEarth           += effect.value * sign
                "res_water"       -> resWater           += effect.value * sign
                "res_air"         -> resAir             += effect.value * sign
                "dmg"             -> dmg                += effect.value * sign
                "dmg_fire"        -> dmgFire            += effect.value * sign
                "dmg_earth"       -> dmgEarth           += effect.value * sign
                "dmg_water"       -> dmgWater           += effect.value * sign
                "dmg_air"         -> dmgAir             += effect.value * sign
                "critical_strike" -> criticalStrike     += effect.value * sign
                "haste"           -> haste              += effect.value * sign
                "initiative"      -> initiative         += effect.value * sign
                "threat"          -> threat             += effect.value * sign
                "wisdom"          -> wisdom             += effect.value * sign
                "prospecting"     -> prospecting        += effect.value * sign
                "inventory_space" -> inventoryMaxItems  += effect.value * sign
            }
        }
    }

    removedItem?.let { applyEffects(it, -1) }
    addedItem?.let   { applyEffects(it, +1) }

    return this.copy(
        maxHp             = maxHp,
        resFire           = resFire,
        resEarth          = resEarth,
        resWater          = resWater,
        resAir            = resAir,
        dmg               = dmg,
        dmgFire           = dmgFire,
        dmgEarth          = dmgEarth,
        dmgWater          = dmgWater,
        dmgAir            = dmgAir,
        criticalStrike    = criticalStrike,
        haste             = haste,
        initiative        = initiative,
        threat            = threat,
        wisdom            = wisdom,
        prospecting       = prospecting,
        inventoryMaxItems = inventoryMaxItems
    )
}
