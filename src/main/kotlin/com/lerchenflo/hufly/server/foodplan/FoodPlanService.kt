package com.lerchenflo.hufly.server.foodplan

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.foodplan.model.FoodPlan
import com.lerchenflo.hufly.server.foodplan.model.FoodPlanEntry
import com.lerchenflo.hufly.server.horse.model.Horse
import com.lerchenflo.hufly.server.repository.FoodPlanRepository
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.repository.TagRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

/** Every write, including assigning plans to horses, needs FOODPLAN_EDIT. */
@Service
class FoodPlanService(
    private val foodPlanRepository: FoodPlanRepository,
    private val horseRepository: HorseRepository,
    private val tagRepository: TagRepository,
    private val accessService: AccessService,
    private val clock: Clock,
) {
    fun createPlan(requester: User, name: String, entries: List<FoodPlanEntry>): FoodPlan {
        accessService.requirePermission(requester, Permission.FOODPLAN_EDIT)
        requireFoodTags(requester, entries)
        return foodPlanRepository.save(
            FoodPlan(stableId = requester.stableId, name = name, entries = entries, updatedAt = clock.instant(), updatedBy = requester.id)
        )
    }

    fun updatePlan(requester: User, planId: ObjectId, name: String, entries: List<FoodPlanEntry>): FoodPlan {
        accessService.requirePermission(requester, Permission.FOODPLAN_EDIT)
        val plan = stablePlan(requester, planId)
        requireFoodTags(requester, entries)
        return foodPlanRepository.save(plan.copy(name = name, entries = entries, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    fun copyPlan(requester: User, planId: ObjectId, name: String): FoodPlan {
        accessService.requirePermission(requester, Permission.FOODPLAN_EDIT)
        val plan = stablePlan(requester, planId)
        return foodPlanRepository.save(
            plan.copy(id = ObjectId.get(), name = name, updatedAt = clock.instant(), updatedBy = requester.id)
        )
    }

    /** Horses that used the plan lose it, so clients stop showing a deleted plan. */
    fun deletePlan(requester: User, planId: ObjectId) {
        accessService.requirePermission(requester, Permission.FOODPLAN_EDIT)
        val plan = stablePlan(requester, planId)
        val now = clock.instant()
        foodPlanRepository.save(plan.copy(deleted = true, updatedAt = now, updatedBy = requester.id))
        horseRepository.findByFoodPlanIdAndDeletedFalse(plan.id).forEach { horse ->
            horseRepository.save(horse.copy(foodPlanId = null, updatedAt = now, updatedBy = requester.id))
        }
    }

    /** [planId] null removes the plan from the horse. */
    fun assignPlan(requester: User, horseId: ObjectId, planId: ObjectId?): Horse {
        accessService.requirePermission(requester, Permission.FOODPLAN_EDIT)
        val horse = horseRepository.findById(horseId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Horse not found")
        planId?.let { stablePlan(requester, it) }
        return horseRepository.save(horse.copy(foodPlanId = planId, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    private fun requireFoodTags(requester: User, entries: List<FoodPlanEntry>) {
        val valid = entries.all { entry ->
            tagRepository.findById(entry.foodTagId)
                ?.let { it.stableId == requester.stableId && !it.deleted && it.type == TagType.FOOD } == true
        }
        if (!valid) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown food tag")
    }

    private fun stablePlan(requester: User, planId: ObjectId): FoodPlan =
        foodPlanRepository.findById(planId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Food plan not found")
}
