package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.foodplan.model.FoodPlan
import org.bson.types.ObjectId

class FakeFoodPlanRepository : FoodPlanRepository {
    val plans = mutableListOf<FoodPlan>()

    override fun deleteByStableId(stableId: ObjectId): Long = plans.count { it.stableId == stableId }.toLong().also { plans.removeIf { it.stableId == stableId } }

    override fun save(plan: FoodPlan): FoodPlan {
        plans.requireUniqueClientId(plan, { it.id }, { it.stableId }, { it.clientId })
        plans.removeIf { it.id == plan.id }
        plans += plan
        return plan
    }

    override fun findById(id: ObjectId): FoodPlan? = plans.firstOrNull { it.id == id }

    override fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): FoodPlan? =
        plans.firstOrNull { it.stableId == stableId && it.clientId == clientId }

    override fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<FoodPlan> =
        plans.filter { it.stableId == stableId && !it.deleted }
}
