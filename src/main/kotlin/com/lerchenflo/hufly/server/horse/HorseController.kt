package com.lerchenflo.hufly.server.horse

import com.lerchenflo.hufly.server.core.MAX_CLIENT_ID_LENGTH
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.picture.pictureResponse
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.IdTimeStamp
import com.lerchenflo.hufly.server.core.sync.SyncResponse
import com.lerchenflo.hufly.server.core.sync.deltaSync
import com.lerchenflo.hufly.server.core.sync.requireValidSyncRequest
import com.lerchenflo.hufly.server.foodplan.FoodPlanService
import com.lerchenflo.hufly.server.horse.model.HorseResponse
import com.lerchenflo.hufly.server.horse.model.Medication
import com.lerchenflo.hufly.server.horse.model.toHorseResponse
import com.lerchenflo.hufly.server.repository.HorseRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate

@RestController
@RequestMapping("/horses")
class HorseController(
    private val accessService: AccessService,
    private val horseService: HorseService,
    private val horseRepository: HorseRepository,
    private val foodPlanService: FoodPlanService,
) {

    data class AssignFoodPlanRequest(val foodPlanId: String?)

    data class MedicationRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        @field:Size(max = 200) val dosage: String = "",
        val from: LocalDate,
        val until: LocalDate? = null,
    )

    data class MedicationsRequest(
        @field:Valid @field:Size(max = 50) val medications: List<MedicationRequest>,
    )

    data class HorseRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        @field:Size(max = 5000) val description: String = "",
        val birthDate: LocalDate? = null,
        @field:Size(max = 100) val breed: String = "",
        @field:Size(max = 100) val color: String = "",
        val ownerUserId: String? = null,
        @field:Size(max = 5000) val medicalNotes: String = "",
        @field:Size(max = 1000) val vetContact: String = "",
        val foodPlanId: String? = null,
        @field:Size(max = 50) val coRiderUserIds: List<String> = emptyList(),
        /** Only read on create; edits go through PUT /horses/{id}/medications. */
        @field:Valid @field:Size(max = 50) val medications: List<MedicationRequest> = emptyList(),
        /** Only read on create. */
        @field:Size(min = 1, max = MAX_CLIENT_ID_LENGTH) val clientId: String? = null,
    ) {
        fun toData() = HorseService.HorseData(
            name = name,
            description = description,
            birthDate = birthDate,
            breed = breed,
            color = color,
            ownerUserId = ownerUserId?.let(::parseObjectId),
            medicalNotes = medicalNotes,
            vetContact = vetContact,
            foodPlanId = foodPlanId?.let(::parseObjectId),
            coRiderUserIds = coRiderUserIds.map(::parseObjectId),
        )
    }

    private fun List<MedicationRequest>.toMedications() = map { Medication(it.name, it.dosage, it.from, it.until) }

    @PostMapping
    fun createHorse(@Valid @RequestBody request: HorseRequest): HorseResponse {
        val requester = accessService.requester(requireAuth())
        return horseService.createHorse(requester, request.toData(), request.medications.toMedications(), request.clientId)
            .toHorseResponse(horseService.canSeeMedications(requester))
    }

    @PutMapping("/{horseId}")
    fun updateHorse(@PathVariable horseId: String, @Valid @RequestBody request: HorseRequest): HorseResponse {
        val requester = accessService.requester(requireAuth())
        return horseService.updateHorse(requester, parseObjectId(horseId), request.toData())
            .toHorseResponse(horseService.canSeeMedications(requester))
    }

    @PutMapping("/{horseId}/medications")
    fun updateMedications(@PathVariable horseId: String, @Valid @RequestBody request: MedicationsRequest): HorseResponse {
        val requester = accessService.requester(requireAuth())
        return horseService.updateMedications(requester, parseObjectId(horseId), request.medications.toMedications())
            .toHorseResponse(showMedications = true)
    }

    /** Needs FOODPLAN_EDIT, not HORSE_EDIT (FOD-3). Null removes the plan. */
    @PutMapping("/{horseId}/foodplan")
    fun assignFoodPlan(@PathVariable horseId: String, @RequestBody request: AssignFoodPlanRequest): HorseResponse {
        val requester = accessService.requester(requireAuth())
        return foodPlanService.assignPlan(requester, parseObjectId(horseId), request.foodPlanId?.let(::parseObjectId))
            .toHorseResponse(horseService.canSeeMedications(requester))
    }

    @PutMapping("/{horseId}/picture")
    fun setPicture(@PathVariable horseId: String, @RequestParam("picture") picture: MultipartFile): HorseResponse {
        val requester = accessService.requester(requireAuth())
        return horseService.setPicture(requester, parseObjectId(horseId), picture.bytes)
            .toHorseResponse(horseService.canSeeMedications(requester))
    }

    @DeleteMapping("/{horseId}/picture")
    fun deletePicture(@PathVariable horseId: String): HorseResponse {
        val requester = accessService.requester(requireAuth())
        return horseService.deletePicture(requester, parseObjectId(horseId))
            .toHorseResponse(horseService.canSeeMedications(requester))
    }

    @GetMapping("/{horseId}/picture")
    fun picture(@PathVariable horseId: String): ResponseEntity<ByteArray> {
        val requester = accessService.requester(requireAuth())
        return pictureResponse(horseService.picture(requester, parseObjectId(horseId)))
    }

    @DeleteMapping("/{horseId}")
    fun deleteHorse(@PathVariable horseId: String) {
        val requester = accessService.requester(requireAuth())
        horseService.deleteHorse(requester, parseObjectId(horseId))
    }

    /** Every member may view horses (HOR-1); medications only with HORSE_MEDICATION_VIEW. */
    @PostMapping("/sync")
    fun sync(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @Valid @RequestBody clientEntries: List<@Valid IdTimeStamp>,
    ): SyncResponse<HorseResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidSyncRequest(page, pageSize, clientEntries)
        val showMedications = horseService.canSeeMedications(requester)
        return deltaSync(
            horseRepository.findByStableIdAndDeletedFalse(requester.stableId), clientEntries, page, pageSize,
            id = { it.id.toHexString() }, updatedAt = { it.updatedAt }, toResponse = { it.toHorseResponse(showMedications) },
        )
    }
}
