package com.lerchenflo.hufly.server.horse

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.IdTimeStamp
import com.lerchenflo.hufly.server.core.sync.SyncResponse
import com.lerchenflo.hufly.server.core.sync.deltaSync
import com.lerchenflo.hufly.server.core.sync.requireValidSyncRequest
import com.lerchenflo.hufly.server.horse.model.HorseResponse
import com.lerchenflo.hufly.server.horse.model.Medication
import com.lerchenflo.hufly.server.horse.model.toHorseResponse
import com.lerchenflo.hufly.server.repository.HorseRepository
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
import java.time.LocalDate

@RestController
@RequestMapping("/horses")
class HorseController(
    private val accessService: AccessService,
    private val horseService: HorseService,
    private val horseRepository: HorseRepository,
) {

    data class MedicationRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        @field:Size(max = 200) val dosage: String = "",
        val from: LocalDate,
        val until: LocalDate? = null,
    )

    data class HorseRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        @field:Size(max = 5000) val description: String = "",
        @field:Size(max = 2000) val pictureUrl: String? = null,
        val birthDate: LocalDate? = null,
        @field:Size(max = 100) val breed: String = "",
        @field:Size(max = 100) val color: String = "",
        val ownerUserId: String? = null,
        @field:Size(max = 5000) val medicalNotes: String = "",
        @field:Size(max = 1000) val vetContact: String = "",
        @field:Valid @field:Size(max = 50) val medications: List<MedicationRequest> = emptyList(),
    ) {
        fun toData() = HorseService.HorseData(
            name = name,
            description = description,
            pictureUrl = pictureUrl,
            birthDate = birthDate,
            breed = breed,
            color = color,
            ownerUserId = ownerUserId?.let(::parseObjectId),
            medicalNotes = medicalNotes,
            vetContact = vetContact,
            medications = medications.map { Medication(it.name, it.dosage, it.from, it.until) },
        )
    }

    @PostMapping
    fun createHorse(@Valid @RequestBody request: HorseRequest): HorseResponse {
        val requester = accessService.requester(requireAuth())
        return horseService.createHorse(requester, request.toData()).toHorseResponse()
    }

    @PutMapping("/{horseId}")
    fun updateHorse(@PathVariable horseId: String, @Valid @RequestBody request: HorseRequest): HorseResponse {
        val requester = accessService.requester(requireAuth())
        return horseService.updateHorse(requester, parseObjectId(horseId), request.toData()).toHorseResponse()
    }

    @DeleteMapping("/{horseId}")
    fun deleteHorse(@PathVariable horseId: String) {
        val requester = accessService.requester(requireAuth())
        horseService.deleteHorse(requester, parseObjectId(horseId))
    }

    /** Every member may view horses (HOR-1); only editing is gated. */
    @PostMapping("/sync")
    fun sync(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @Valid @RequestBody clientEntries: List<@Valid IdTimeStamp>,
    ): SyncResponse<HorseResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidSyncRequest(page, pageSize, clientEntries)
        return deltaSync(
            horseRepository.findByStableIdAndDeletedFalse(requester.stableId), clientEntries, page, pageSize,
            id = { it.id.toHexString() }, updatedAt = { it.updatedAt }, toResponse = { it.toHorseResponse() },
        )
    }
}
