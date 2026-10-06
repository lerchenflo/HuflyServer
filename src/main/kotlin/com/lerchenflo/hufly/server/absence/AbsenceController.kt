package com.lerchenflo.hufly.server.absence

import com.lerchenflo.hufly.server.absence.model.AbsenceResponse
import com.lerchenflo.hufly.server.absence.model.toAbsenceResponse
import com.lerchenflo.hufly.server.core.MAX_CLIENT_ID_LENGTH
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.requireValidVersionSyncRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

@RestController
@RequestMapping("/absences")
class AbsenceController(
    private val accessService: AccessService,
    private val absenceService: AbsenceService,
) {

    data class AbsenceRequest(
        val userId: String,
        val from: LocalDate,
        /** Inclusive. */
        val until: LocalDate,
        @field:Size(max = 500) val note: String = "",
        /** Only read on create. */
        @field:Size(min = 1, max = MAX_CLIENT_ID_LENGTH) val clientId: String? = null,
    ) {
        fun toData() = AbsenceService.AbsenceData(parseObjectId(userId), from, until, note)
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createAbsence(@Valid @RequestBody request: AbsenceRequest): AbsenceResponse {
        val requester = accessService.requester(requireAuth())
        return absenceService.createAbsence(requester, request.toData(), request.clientId).toAbsenceResponse()
    }

    @PutMapping("/{absenceId}")
    fun updateAbsence(@PathVariable absenceId: String, @Valid @RequestBody request: AbsenceRequest): AbsenceResponse {
        val requester = accessService.requester(requireAuth())
        return absenceService.updateAbsence(requester, parseObjectId(absenceId), request.toData()).toAbsenceResponse()
    }

    @DeleteMapping("/{absenceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteAbsence(@PathVariable absenceId: String) {
        val requester = accessService.requester(requireAuth())
        absenceService.deleteAbsence(requester, parseObjectId(absenceId))
    }

    @GetMapping("/sync")
    fun sync(
        @RequestParam(value = "since", defaultValue = "0") since: Long,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
    ): VersionSyncResponse<AbsenceResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidVersionSyncRequest(since, pageSize)
        return absenceService.sync(requester, since, pageSize)
    }
}
