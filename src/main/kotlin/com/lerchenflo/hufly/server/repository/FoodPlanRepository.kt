package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.foodplan.model.FoodPlan
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface FoodPlanRepository : Repository<FoodPlan, ObjectId> {
    fun deleteByStableId(stableId: ObjectId): Long
    fun save(plan: FoodPlan): FoodPlan
    fun findById(id: ObjectId): FoodPlan?
    fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): FoodPlan?
    fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<FoodPlan>
}
