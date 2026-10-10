package com.lerchenflo.hufly.server.foodplan.model

data class FoodPlanEntryResponse(
    val slot: MealSlot,
    val foodTagId: String,
    val amountComment: String,
)

data class FoodPlanResponse(
    val id: String,
    val stableId: String,
    val name: String,
    val entries: List<FoodPlanEntryResponse>,
    val updatedAt: Long,
    val updatedBy: String,
    /** Null for plans created before the creator was stored. */
    val createdByUserId: String?,
)

fun FoodPlan.toFoodPlanResponse() = FoodPlanResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    name = name,
    entries = entries.map { FoodPlanEntryResponse(it.slot, it.foodTagId.toHexString(), it.amountComment) },
    updatedAt = updatedAt,
    updatedBy = updatedBy.toHexString(),
    createdByUserId = createdByUserId?.toHexString(),
)
