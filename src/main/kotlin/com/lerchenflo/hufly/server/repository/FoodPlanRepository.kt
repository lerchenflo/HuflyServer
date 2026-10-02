package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.foodplan.model.FoodPlan
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface FoodPlanRepository : Repository<FoodPlan, ObjectId> {
    fun save(plan: FoodPlan): FoodPlan
    fun findById(id: ObjectId): FoodPlan?
    fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<FoodPlan>
}
