package com.lerchenflo.hufly.server.stable.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

enum class SubscriptionStatus { TRIAL, ACTIVE, EXPIRED }

@Document("stables")
data class Stable(
    @Id val id: ObjectId = ObjectId.get(),
    val name: String,
    val adminUserId: ObjectId,
    val subscriptionStatus: SubscriptionStatus,
    val subscriptionValidUntil: Long?,
    /** Stables saved before meal times existed read as the defaults. */
    val mealTimes: MealTimes = MealTimes(),
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
)
