package com.artifactsmmo.gui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.artifactsmmo.client.models.Character

/**
 * Side panel showing detailed stats for a single character.
 * Sections: Skills, Equipment, Inventory, Combat Stats — each collapsible.
 */
@Composable
fun CharacterDetailPanel(
    character: Character,
    onClose: () -> Unit,
    onAssignTask: () -> Unit,
    onStopTask: () -> Unit,
    onOpenBank: () -> Unit = {},
    onOpenInventory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        tonalElevation = 2.dp,
        shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ────────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = character.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Lv ${character.level}  •  ${character.hp}/${character.maxHp} HP  •  ${character.gold} gold",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedButton(
                        onClick = onStopTask,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Stop")
                    }
                    OutlinedButton(onClick = onOpenBank) { Text("Bank") }
                    OutlinedButton(onClick = onOpenInventory) { Text("Inventory") }
                    Button(onClick = onAssignTask) {
                        Text("Assign Task")
                    }
                    TextButton(onClick = onClose) {
                        Text("Close")
                    }
                }
            }

            // Level XP bar
            XpBar(
                xp = character.xp,
                maxXp = character.maxXp,
                label = "Combat Lv ${character.level}",
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp)
            )

            HorizontalDivider()

            // ── Scrollable content ────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SkillsSection(character)
                EquipmentSection(character)
                InventorySection(character)
                CombatStatsSection(character)
            }
        }
    }
}

// ── Collapsible section wrapper ────────────────────────────────────────────────

@Composable
private fun DetailSection(
    title: String,
    defaultExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(defaultExpanded) }
    val arrowAngle by animateFloatAsState(if (expanded) 90f else 0f, label = "arrow")

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "▶",
                    modifier = Modifier.rotate(arrowAngle),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    content = content
                )
            }
        }
    }
}

// ── XP bar ────────────────────────────────────────────────────────────────────

@Composable
private fun XpBar(xp: Int, maxXp: Int, label: String, modifier: Modifier = Modifier) {
    val fraction = if (maxXp > 0) (xp.toFloat() / maxXp).coerceIn(0f, 1f) else 0f
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$xp / $maxXp",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
    }
}

// ── Skills section ────────────────────────────────────────────────────────────

@Composable
private fun SkillsSection(c: Character) {
    DetailSection(title = "Skills") {
        data class Skill(val name: String, val level: Int, val xp: Int, val maxXp: Int)

        val skills = listOf(
            Skill("Mining",           c.miningLevel,          c.miningXp,          c.miningMaxXp),
            Skill("Woodcutting",      c.woodcuttingLevel,      c.woodcuttingXp,      c.woodcuttingMaxXp),
            Skill("Fishing",          c.fishingLevel,          c.fishingXp,          c.fishingMaxXp),
            Skill("Weaponcrafting",   c.weaponcraftingLevel,   c.weaponcraftingXp,   c.weaponcraftingMaxXp),
            Skill("Gearcrafting",     c.gearcraftingLevel,     c.gearcraftingXp,     c.gearcraftingMaxXp),
            Skill("Jewelrycrafting",  c.jewelrycraftingLevel,  c.jewelrycraftingXp,  c.jewelrycraftingMaxXp),
            Skill("Cooking",          c.cookingLevel,          c.cookingXp,          c.cookingMaxXp),
            Skill("Alchemy",          c.alchemyLevel,          c.alchemyXp,          c.alchemyMaxXp),
        )

        for (skill in skills) {
            XpBar(
                xp = skill.xp,
                maxXp = skill.maxXp,
                label = "${skill.name}  Lv ${skill.level}"
            )
        }
    }
}

// ── Equipment section ─────────────────────────────────────────────────────────

@Composable
private fun EquipmentSection(c: Character) {
    DetailSection(title = "Equipment", defaultExpanded = false) {
        data class Slot(val label: String, val code: String, val qty: Int = 0)

        val slots = listOf(
            Slot("Weapon",     c.weaponSlot),
            Slot("Rune",       c.runeSlot),
            Slot("Shield",     c.shieldSlot),
            Slot("Helmet",     c.helmetSlot),
            Slot("Body",       c.bodyArmorSlot),
            Slot("Legs",       c.legArmorSlot),
            Slot("Boots",      c.bootsSlot),
            Slot("Ring 1",     c.ring1Slot),
            Slot("Ring 2",     c.ring2Slot),
            Slot("Amulet",     c.amuletSlot),
            Slot("Artifact 1", c.artifact1Slot),
            Slot("Artifact 2", c.artifact2Slot),
            Slot("Artifact 3", c.artifact3Slot),
            Slot("Utility 1",  c.utility1Slot,  c.utility1SlotQuantity),
            Slot("Utility 2",  c.utility2Slot,  c.utility2SlotQuantity),
            Slot("Bag",        c.bagSlot),
        )

        for (slot in slots) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = slot.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(90.dp)
                )
                val display = when {
                    slot.code.isBlank() -> "—"
                    slot.qty > 0        -> "${slot.code}  ×${slot.qty}"
                    else                -> slot.code
                }
                Text(
                    text = display,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (slot.code.isNotBlank()) FontWeight.Medium else FontWeight.Normal,
                    color = if (slot.code.isNotBlank())
                        MaterialTheme.colorScheme.onSurface
                    else
                        MaterialTheme.colorScheme.outlineVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ── Inventory section ─────────────────────────────────────────────────────────

@Composable
private fun InventorySection(c: Character) {
    val items = c.inventory.filter { it.code.isNotBlank() }
    val used = items.sumOf { it.quantity }

    DetailSection(title = "Inventory  ($used / ${c.inventoryMaxItems})") {
        if (items.isEmpty()) {
            Text(
                text = "Empty",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        } else {
            for (item in items.sortedByDescending { it.quantity }) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = item.code,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "×${item.quantity}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Combat stats section ──────────────────────────────────────────────────────

@Composable
private fun CombatStatsSection(c: Character) {
    DetailSection(title = "Combat Stats", defaultExpanded = false) {

        @Composable
        fun StatRow(label: String, value: String, highlight: Boolean = false) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (highlight) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (highlight) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // HP bar
        XpBar(xp = c.hp, maxXp = c.maxHp, label = "HP")
        Spacer(Modifier.height(4.dp))

        StatRow("Speed",          "${c.speed}")
        StatRow("Haste",          "${c.haste}")
        StatRow("Critical Strike","${c.criticalStrike}%")
        StatRow("Initiative",     "${c.initiative}")
        StatRow("Wisdom",         "${c.wisdom}")
        StatRow("Prospecting",    "${c.prospecting}")
        StatRow("Threat",         "${c.threat}")

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        StatRow("DMG (base)",    "${c.dmg}%",       highlight = c.dmg > 0)
        StatRow("ATK Fire",      "${c.attackFire}",  highlight = c.attackFire > 0)
        StatRow("ATK Earth",     "${c.attackEarth}", highlight = c.attackEarth > 0)
        StatRow("ATK Water",     "${c.attackWater}", highlight = c.attackWater > 0)
        StatRow("ATK Air",       "${c.attackAir}",   highlight = c.attackAir > 0)
        StatRow("DMG Fire",      "${c.dmgFire}%",    highlight = c.dmgFire > 0)
        StatRow("DMG Earth",     "${c.dmgEarth}%",   highlight = c.dmgEarth > 0)
        StatRow("DMG Water",     "${c.dmgWater}%",   highlight = c.dmgWater > 0)
        StatRow("DMG Air",       "${c.dmgAir}%",     highlight = c.dmgAir > 0)

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        StatRow("RES Fire",      "${c.resFire}%",    highlight = c.resFire > 0)
        StatRow("RES Earth",     "${c.resEarth}%",   highlight = c.resEarth > 0)
        StatRow("RES Water",     "${c.resWater}%",   highlight = c.resWater > 0)
        StatRow("RES Air",       "${c.resAir}%",     highlight = c.resAir > 0)
    }
}
