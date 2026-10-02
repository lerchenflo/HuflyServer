package com.lerchenflo.hufly.server.horselog

import com.lerchenflo.hufly.server.core.MAX_EPOCH_MILLIS
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.requireValidVersionSyncRequest
import com.lerchenflo.hufly.server.horselog.model.HorseLogEntryResponse
import com.lerchenflo.hufly.server.horselog.model.toHorseLogEntryResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate

@RestController
@RequestMapping("/horselog")
class HorseLogController(
    private val accessService: AccessService,
    private val horseLogService: HorseLogService,
) {

    data class LogEntryRequest(
        @field:NotBlank val horseId: String,
        @field:NotBlank val activityTagId: String,
        /** Epoch milliseconds. */
        @field:Min(0) @field:Max(MAX_EPOCH_MILLIS) val startAt: Long,
        @field:Min(0) @field:Max(MAX_EPOCH_MILLIS) val endAt: Long? = null,
        val doneByUserId: String? = null,
        @field:Size(max = 5000) val comment: String = "",
        val nextDueAt: LocalDate? = null,
    ) {
        fun toData() = HorseLogService.LogData(
            horseId = parseObjectId(horseId),
            activityTagId = parseObjectId(activityTagId),
            startAt = Instant.ofEpochMilli(startAt),
            endAt = endAt?.let(Instant::ofEpochMilli),
            doneByUserId = doneByUserId?.let(::parseObjectId),
            comment = comment,
            nextDueAt = nextDueAt,
        )
    }

    @PostMapping
    fun createEntry(@Valid @RequestBody request: LogEntryRequest): HorseLogEntryResponse {
        val requester = accessService.requester(requireAuth())
        return horseLogService.createEntry(requester, request.toData()).toHorseLogEntryResponse()
    }

    @PutMapping("/{entryId}")
    fun updateEntry(@PathVariable entryId: String, @Valid @RequestBody request: LogEntryRequest): HorseLogEntryResponse {
        val requester = accessService.requester(requireAuth())
        return horseLogService.updateEntry(requester, parseObjectId(entryId), request.toData()).toHorseLogEntryResponse()
    }

    @DeleteMapping("/{entryId}")
    fun deleteEntry(@PathVariable entryId: String) {
        val requester = accessService.requester(requireAuth())
        horseLogService.deleteEntry(requester, parseObjectId(entryId))
    }

    @GetMapping("/sync")
    fun sync(
        @RequestParam(value = "since", defaultValue = "0") since: Long,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
    ): VersionSyncResponse<HorseLogEntryResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidVersionSyncRequest(since, pageSize)
        return horseLogService.sync(requester, since, pageSize)
    }
}
