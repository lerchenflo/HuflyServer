package com.lerchenflo.hufly.server.foodplan.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

enum class MealSlot { MORNING, LUNCH, DINNER, NIGHT }

data class FoodPlanEntry(
    val slot: MealSlot,
    val foodTagId: ObjectId,
    val amountComment: String,
)

/** Shared by several horses (FOD-1). A one-off change means copying the plan and editing the copy. */
@Document("foodPlans")
@CompoundIndex(name = "stableId_clientId", def = "{'stableId': 1, 'clientId': 1}", unique = true, partialFilter = "{'clientId': {\$type: 'string'}}")
data class FoodPlan(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val stableId: ObjectId,
    val name: String,
    val entries: List<FoodPlanEntry>,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    /** The client's local id, see [com.lerchenflo.hufly.server.core.idempotentCreate]. */
    val clientId: String? = null,
)
