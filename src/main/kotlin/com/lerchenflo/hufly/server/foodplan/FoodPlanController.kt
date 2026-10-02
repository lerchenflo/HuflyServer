package com.lerchenflo.hufly.server.foodplan

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.IdTimeStamp
import com.lerchenflo.hufly.server.core.sync.SyncResponse
import com.lerchenflo.hufly.server.core.sync.deltaSync
import com.lerchenflo.hufly.server.core.sync.requireValidSyncRequest
import com.lerchenflo.hufly.server.foodplan.model.FoodPlanEntry
import com.lerchenflo.hufly.server.foodplan.model.FoodPlanResponse
import com.lerchenflo.hufly.server.foodplan.model.MealSlot
import com.lerchenflo.hufly.server.foodplan.model.toFoodPlanResponse
import com.lerchenflo.hufly.server.repository.FoodPlanRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/foodplans")
class FoodPlanController(
    private val accessService: AccessService,
    private val foodPlanService: FoodPlanService,
    private val foodPlanRepository: FoodPlanRepository,
) {

    data class EntryRequest(
        val slot: MealSlot,
        @field:NotBlank val foodTagId: String,
        @field:Size(max = 500) val amountComment: String = "",
    )

    data class FoodPlanRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        @field:Valid @field:Size(max = 100) val entries: List<EntryRequest>,
    ) {
        fun toEntries() = entries.map { FoodPlanEntry(it.slot, parseObjectId(it.foodTagId), it.amountComment) }
    }

    data class CopyRequest(@field:NotBlank @field:Size(max = 100) val name: String)

    @PostMapping
    fun createPlan(@Valid @RequestBody request: FoodPlanRequest): FoodPlanResponse {
        val requester = accessService.requester(requireAuth())
        return foodPlanService.createPlan(requester, request.name, request.toEntries()).toFoodPlanResponse()
    }

    @PutMapping("/{planId}")
    fun updatePlan(@PathVariable planId: String, @Valid @RequestBody request: FoodPlanRequest): FoodPlanResponse {
        val requester = accessService.requester(requireAuth())
        return foodPlanService.updatePlan(requester, parseObjectId(planId), request.name, request.toEntries()).toFoodPlanResponse()
    }

    @PostMapping("/{planId}/copy")
    fun copyPlan(@PathVariable planId: String, @Valid @RequestBody request: CopyRequest): FoodPlanResponse {
        val requester = accessService.requester(requireAuth())
        return foodPlanService.copyPlan(requester, parseObjectId(planId), request.name).toFoodPlanResponse()
    }

    @DeleteMapping("/{planId}")
    fun deletePlan(@PathVariable planId: String) {
        val requester = accessService.requester(requireAuth())
        foodPlanService.deletePlan(requester, parseObjectId(planId))
    }

    /** Every member may view food plans. */
    @PostMapping("/sync")
    fun sync(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @Valid @RequestBody clientEntries: List<@Valid IdTimeStamp>,
    ): SyncResponse<FoodPlanResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidSyncRequest(page, pageSize, clientEntries)
        return deltaSync(
            foodPlanRepository.findByStableIdAndDeletedFalse(requester.stableId), clientEntries, page, pageSize,
            id = { it.id.toHexString() }, updatedAt = { it.updatedAt }, toResponse = { it.toFoodPlanResponse() },
        )
    }
}
