package com.lerchenflo.hufly.server.stable.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

enum class SubscriptionStatus { TRIAL, ACTIVE, EXPIRED }

@Document("stables")
data class Stable(
    @Id val id: ObjectId = ObjectId.get(),
    val name: String,
    /** Optional place (Ort), so stables with the same name can be told apart. */
    val place: String? = null,
    /**
     * Normalized invite code (see `InviteCodes`): whoever has it can ask to join. Only the admin sees it. Never
     * written as null, so the partial unique index only covers stables that have one.
     */
    @Indexed(unique = true, partialFilter = "{ 'inviteCode': { '\$exists': true } }") val inviteCode: String? = null,
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
