package com.artifactsmmo.server.api

import com.artifactsmmo.core.task.ActionHelper
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

@Serializable data class IngredientDto(val code: String, val perCraft: Int, val have: Int) {
    /** Short of one craft (0 if at least one craft's worth is owned). */
    val missing: Int get() = (perCraft - have).coerceAtLeast(0)
}
@Serializable data class NpcBuyDto(val npc: String, val item: String, val currency: String, val priceEach: Int, val quantity: Int)
@Serializable
data class CraftableDto(
    val code: String,
    val name: String,
    val skill: String,
    val level: Int,
    val maxCraftable: Int,
    val ingredients: List<IngredientDto>,
    val npcBuy: List<NpcBuyDto>,
)

/** Crafting skills the game accepts as `craft_skill` (no fishing: fish are cooked under cooking). */
val CRAFT_SKILLS = listOf("weaponcrafting", "gearcrafting", "jewelrycrafting", "cooking", "woodcutting", "mining", "alchemy")

/** Engine result → API shape. Order is preserved (craftable now first). */
fun List<ActionHelper.CraftableItemInfo>.toCraftableDtos(): List<CraftableDto> = map { info ->
    CraftableDto(
        code = info.item.code,
        name = info.item.name,
        skill = info.item.craft?.skill.orEmpty(),
        level = info.item.craft?.level ?: info.item.level,
        maxCraftable = info.maxCraftable,
        ingredients = info.ingredients.map { IngredientDto(it.code, it.quantity, info.ingredientAvailable[it.code] ?: 0) },
        npcBuy = info.npcPurchasesNeeded.map { NpcBuyDto(it.npcCode, it.itemCode, it.currency, it.priceEach, it.quantityNeeded) },
    )
}

/**
 * GET /api/craftable?character=&skill=&all=
 *  - `character` (optional): count that character's inventory + gold plus the bank;
 *    omitted = bank only ("any eligible").
 *  - `skill`: a crafting skill or `all` (default).
 *  - `all=true`: include recipes above the character's skill level ("Show all").
 * Recipes with max 0 are included, with their missing ingredients.
 */
fun Route.craftableRoutes(b: ApiBackend) {
    get("/craftable") {
        val e = b.engine ?: return@get call.respond(HttpStatusCode.ServiceUnavailable, ApiError("engine not running"))
        val q = call.request.queryParameters
        val skills = q["skill"]?.takeIf { it != "all" }?.let(::listOf) ?: CRAFT_SKILLS
        skills.firstOrNull { it !in CRAFT_SKILLS }?.let {
            return@get call.respond(HttpStatusCode.BadRequest, ApiError("unknown crafting skill '$it'"))
        }
        val char = q["character"]?.takeIf { it.isNotBlank() }?.let { name ->
            if (name !in e.characters) return@get call.respond(HttpStatusCode.NotFound, ApiError("unknown character"))
            e.helper.refreshCharacter(name)
        }
        val recipes = e.helper.listCraftableRecipes(char, skills, includeAboveLevel = q["all"] == "true")
        call.respond(recipes.toCraftableDtos())
    }
}
