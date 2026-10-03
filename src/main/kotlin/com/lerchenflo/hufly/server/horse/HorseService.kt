package com.lerchenflo.hufly.server.horse

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.horse.model.Horse
import com.lerchenflo.hufly.server.horse.model.Medication
import com.lerchenflo.hufly.server.repository.FoodPlanRepository
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.LocalDate

/**
 * Adding and removing horses is admin-only (TAG-6); editing needs HORSE_EDIT, medications HORSE_MEDICATION_EDIT.
 * An edit only changes the food plan with FOODPLAN_EDIT; otherwise the sent id is ignored, so offline edits never fail on it.
 */
@Service
class HorseService(
    private val horseRepository: HorseRepository,
    private val userRepository: UserRepository,
    private val foodPlanRepository: FoodPlanRepository,
    private val accessService: AccessService,
    private val clock: Clock,
) {
    data class HorseData(
        val name: String,
        val description: String,
        val pictureUrl: String?,
        val birthDate: LocalDate?,
        val breed: String,
        val color: String,
        val ownerUserId: ObjectId?,
        val medicalNotes: String,
        val vetContact: String,
        val foodPlanId: ObjectId? = null,
    )

    fun createHorse(requester: User, data: HorseData, medications: List<Medication>): Horse {
        accessService.requireAdmin(requester)
        validate(requester, data)
        validateMedications(medications)
        return horseRepository.save(
            Horse(
                stableId = requester.stableId,
                name = data.name,
                description = data.description,
                pictureUrl = data.pictureUrl,
                birthDate = data.birthDate,
                breed = data.breed,
                color = data.color,
                ownerUserId = data.ownerUserId,
                medicalNotes = data.medicalNotes,
                vetContact = data.vetContact,
                medications = medications,
                foodPlanId = requireStablePlan(requester, data.foodPlanId),
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    fun updateHorse(requester: User, horseId: ObjectId, data: HorseData): Horse {
        accessService.requirePermission(requester, Permission.HORSE_EDIT)
        val horse = stableHorse(requester, horseId)
        validate(requester, data)
        val foodPlanId = if (Permission.FOODPLAN_EDIT in accessService.effectivePermissions(requester)) {
            requireStablePlan(requester, data.foodPlanId)
        } else {
            horse.foodPlanId
        }
        return horseRepository.save(
            horse.copy(
                name = data.name,
                description = data.description,
                pictureUrl = data.pictureUrl,
                birthDate = data.birthDate,
                breed = data.breed,
                color = data.color,
                ownerUserId = data.ownerUserId,
                medicalNotes = data.medicalNotes,
                vetContact = data.vetContact,
                foodPlanId = foodPlanId,
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    /** Medications are edited separately so horse edits by members who cannot see them never overwrite them. */
    fun updateMedications(requester: User, horseId: ObjectId, medications: List<Medication>): Horse {
        accessService.requirePermission(requester, Permission.HORSE_MEDICATION_EDIT)
        val horse = stableHorse(requester, horseId)
        validateMedications(medications)
        return horseRepository.save(horse.copy(medications = medications, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    fun canSeeMedications(requester: User): Boolean =
        Permission.HORSE_MEDICATION_VIEW in accessService.effectivePermissions(requester)

    fun deleteHorse(requester: User, horseId: ObjectId) {
        accessService.requireAdmin(requester)
        val horse = stableHorse(requester, horseId)
        horseRepository.save(horse.copy(deleted = true, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    private fun validate(requester: User, data: HorseData) {
        data.ownerUserId?.let { ownerId ->
            val owner = userRepository.findById(ownerId)
            if (owner == null || owner.deleted || owner.stableId != requester.stableId) throw badRequest("Unknown owner")
        }
    }

    private fun requireStablePlan(requester: User, planId: ObjectId?): ObjectId? = planId?.also {
        val plan = foodPlanRepository.findById(it)
        if (plan == null || plan.deleted || plan.stableId != requester.stableId) throw badRequest("Unknown food plan")
    }

    private fun validateMedications(medications: List<Medication>) {
        if (medications.any { it.until != null && it.until < it.from }) throw badRequest("Medication ends before it starts")
    }

    private fun stableHorse(requester: User, horseId: ObjectId): Horse =
        horseRepository.findById(horseId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Horse not found")

    private fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)
}
