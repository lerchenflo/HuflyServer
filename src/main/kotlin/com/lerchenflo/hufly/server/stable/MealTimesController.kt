package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.stable.model.MealTimes
import com.lerchenflo.hufly.server.stable.model.MealTimesResponse
import com.lerchenflo.hufly.server.stable.model.toMealTimesResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

/** Feeding times of the own stable: every member reads, only the admin sets them (last write wins). */
@RestController
@RequestMapping("/stables/me/mealtimes")
class MealTimesController(
    private val accessService: AccessService,
    private val stableLookupService: StableLookupService,
    private val stableRepository: StableRepository,
    private val clock: Clock,
) {
    data class MealTimesRequest(
        @field:Pattern(regexp = TIME_PATTERN) val morning: String,
        @field:Pattern(regexp = TIME_PATTERN) val lunch: String,
        @field:Pattern(regexp = TIME_PATTERN) val dinner: String,
        @field:Pattern(regexp = TIME_PATTERN) val night: String,
    )

    @GetMapping
    fun getMealTimes(): MealTimesResponse {
        val requester = accessService.requester(requireAuth())
        return stableLookupService.getById(requester.stableId).toMealTimesResponse()
    }

    @PutMapping
    fun putMealTimes(@Valid @RequestBody request: MealTimesRequest): MealTimesResponse {
        val requester = accessService.requester(requireAuth())
        accessService.requireAdmin(requester)
        // Zero-padded "HH:mm" sorts like the time itself.
        if (!(request.morning < request.lunch && request.lunch < request.dinner && request.dinner < request.night)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Meal times must increase from morning to night")
        }
        val stable = stableLookupService.getById(requester.stableId)
        return stableRepository.save(
            stable.copy(
                mealTimes = MealTimes(request.morning, request.lunch, request.dinner, request.night),
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        ).toMealTimesResponse()
    }
}

private const val TIME_PATTERN = "^([01]\\d|2[0-3]):[0-5]\\d$"
