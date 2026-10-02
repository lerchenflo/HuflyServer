package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.foodplan.model.FoodPlan
import org.bson.types.ObjectId

class FakeFoodPlanRepository : FoodPlanRepository {
    val plans = mutableListOf<FoodPlan>()

    override fun save(plan: FoodPlan): FoodPlan {
        plans.removeIf { it.id == plan.id }
        plans += plan
        return plan
    }

    override fun findById(id: ObjectId): FoodPlan? = plans.firstOrNull { it.id == id }

    override fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<FoodPlan> =
        plans.filter { it.stableId == stableId && !it.deleted }
}
