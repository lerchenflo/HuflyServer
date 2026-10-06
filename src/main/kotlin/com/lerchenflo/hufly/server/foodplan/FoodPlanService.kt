package com.lerchenflo.hufly.server.foodplan

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.idempotentCreate
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

/**
 * Every write, including assigning plans to horses, needs FOODPLAN_EDIT. HORSE_EDIT_OWN holders who own a horse create and copy plans,
 * edit plans used only by their own horses (or unused ones they created) and assign plans to their own horses; deleting stays FOODPLAN_EDIT.
 */
@Service
class FoodPlanService(
    private val foodPlanRepository: FoodPlanRepository,
    private val horseRepository: HorseRepository,
    private val tagRepository: TagRepository,
    private val accessService: AccessService,
    private val clock: Clock,
) {
    fun createPlan(requester: User, name: String, entries: List<FoodPlanEntry>, clientId: String? = null): FoodPlan {
        requireMayCreate(requester)
        return idempotentCreate(clientId, { foodPlanRepository.findByStableIdAndClientId(requester.stableId, it) }) {
            requireFoodTags(requester, entries)
            foodPlanRepository.save(
                FoodPlan(
                    stableId = requester.stableId, name = name, entries = entries, updatedAt = clock.instant(), updatedBy = requester.id,
                    clientId = clientId, createdByUserId = requester.id,
                )
            )
        }
    }

    fun updatePlan(requester: User, planId: ObjectId, name: String, entries: List<FoodPlanEntry>): FoodPlan {
        val plan = stablePlan(requester, planId)
        // An unused plan counts as its creator's.
        val ownerIds = horseRepository.findByFoodPlanIdAndDeletedFalse(plan.id).map { it.ownerUserId }.ifEmpty { listOf(plan.createdByUserId) }
        accessService.requireHorsePermission(requester, Permission.FOODPLAN_EDIT, ownerIds)
        requireFoodTags(requester, entries)
        return foodPlanRepository.save(plan.copy(name = name, entries = entries, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    fun copyPlan(requester: User, planId: ObjectId, name: String): FoodPlan {
        requireMayCreate(requester)
        val plan = stablePlan(requester, planId)
        return foodPlanRepository.save(
            plan.copy(id = ObjectId.get(), name = name, updatedAt = clock.instant(), updatedBy = requester.id, clientId = null, createdByUserId = requester.id)
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
        val horse = horseRepository.findById(horseId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Horse not found")
        accessService.requireHorsePermission(requester, Permission.FOODPLAN_EDIT, listOf(horse.ownerUserId))
        planId?.let { stablePlan(requester, it) }
        return horseRepository.save(horse.copy(foodPlanId = planId, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    private fun requireMayCreate(requester: User) {
        val ownHorses = horseRepository.findByStableIdAndDeletedFalse(requester.stableId).filter { it.ownerUserId == requester.id }
        accessService.requireHorsePermission(requester, Permission.FOODPLAN_EDIT, ownHorses.map { it.ownerUserId })
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
