package com.lerchenflo.hufly.server.stable.model

/**
 * Start of each feeding (24 h "HH:mm"), named after the food plan's `MealSlot`s. The night slot runs over midnight
 * until the morning one starts.
 */
data class MealTimes(
    val morning: String = "06:00",
    val lunch: String = "12:00",
    val dinner: String = "18:00",
    val night: String = "22:00",
)

data class MealTimesResponse(
    val morning: String,
    val lunch: String,
    val dinner: String,
    val night: String,
    /** Of the stable document; epoch milliseconds. */
    val updatedAt: Long,
    val updatedBy: String,
)

fun Stable.toMealTimesResponse() = MealTimesResponse(
    morning = mealTimes.morning,
    lunch = mealTimes.lunch,
    dinner = mealTimes.dinner,
    night = mealTimes.night,
    updatedAt = updatedAt,
    updatedBy = updatedBy.toHexString(),
)
