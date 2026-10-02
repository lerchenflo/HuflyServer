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
)

fun FoodPlan.toFoodPlanResponse() = FoodPlanResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    name = name,
    entries = entries.map { FoodPlanEntryResponse(it.slot, it.foodTagId.toHexString(), it.amountComment) },
    updatedAt = updatedAt.toEpochMilli(),
    updatedBy = updatedBy.toHexString(),
)
