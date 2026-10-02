package com.lerchenflo.hufly.server.paddock

import com.lerchenflo.hufly.server.core.MAX_EPOCH_MILLIS
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.parseObjectId
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.sync.IdTimeStamp
import com.lerchenflo.hufly.server.core.sync.SyncResponse
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.deltaSync
import com.lerchenflo.hufly.server.core.sync.requireValidSyncRequest
import com.lerchenflo.hufly.server.core.sync.requireValidVersionSyncRequest
import com.lerchenflo.hufly.server.paddock.model.HorseConflictResponse
import com.lerchenflo.hufly.server.paddock.model.HorseGroupResponse
import com.lerchenflo.hufly.server.paddock.model.PaddockAssignmentResponse
import com.lerchenflo.hufly.server.paddock.model.PaddockResponse
import com.lerchenflo.hufly.server.paddock.model.toHorseConflictResponse
import com.lerchenflo.hufly.server.paddock.model.toHorseGroupResponse
import com.lerchenflo.hufly.server.paddock.model.toPaddockAssignmentResponse
import com.lerchenflo.hufly.server.paddock.model.toPaddockResponse
import com.lerchenflo.hufly.server.repository.HorseConflictRepository
import com.lerchenflo.hufly.server.repository.HorseGroupRepository
import com.lerchenflo.hufly.server.repository.PaddockRepository
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** Paddocks, horse groups and conflicts sync by IdTimeStamp lists; assignments by version. */
@RestController
class PaddockController(
    private val accessService: AccessService,
    private val paddockService: PaddockService,
    private val paddockRepository: PaddockRepository,
    private val groupRepository: HorseGroupRepository,
    private val conflictRepository: HorseConflictRepository,
) {

    data class PaddockRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        @field:Size(max = 2000) val description: String = "",
    )

    data class GroupRequest(
        @field:NotBlank @field:Size(max = 100) val name: String,
        @field:Size(max = 200) val horseIds: List<String> = emptyList(),
    )

    data class ConflictRequest(
        @field:NotBlank val firstHorseId: String,
        @field:NotBlank val secondHorseId: String,
        @field:Size(max = 1000) val reason: String = "",
    )

    data class ConflictReasonRequest(@field:Size(max = 1000) val reason: String)

    data class AssignmentRequest(
        @field:NotBlank val paddockId: String,
        @field:Size(max = 50) val groupIds: List<String> = emptyList(),
        @field:Size(max = 200) val horseIds: List<String> = emptyList(),
        /** Epoch milliseconds. */
        @field:Min(0) @field:Max(MAX_EPOCH_MILLIS) val startAt: Long,
        @field:Min(0) @field:Max(MAX_EPOCH_MILLIS) val endAt: Long? = null,
        @field:Size(max = 2000) val comment: String = "",
    )

    // Paddocks

    @PostMapping("/paddocks")
    fun createPaddock(@Valid @RequestBody request: PaddockRequest): PaddockResponse {
        val requester = accessService.requester(requireAuth())
        return paddockService.createPaddock(requester, request.name, request.description).toPaddockResponse()
    }

    @PutMapping("/paddocks/{paddockId}")
    fun updatePaddock(@PathVariable paddockId: String, @Valid @RequestBody request: PaddockRequest): PaddockResponse {
        val requester = accessService.requester(requireAuth())
        return paddockService.updatePaddock(requester, parseObjectId(paddockId), request.name, request.description).toPaddockResponse()
    }

    @DeleteMapping("/paddocks/{paddockId}")
    fun deletePaddock(@PathVariable paddockId: String) {
        val requester = accessService.requester(requireAuth())
        paddockService.deletePaddock(requester, parseObjectId(paddockId))
    }

    @PostMapping("/paddocks/sync")
    fun syncPaddocks(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @Valid @RequestBody clientEntries: List<@Valid IdTimeStamp>,
    ): SyncResponse<PaddockResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidSyncRequest(page, pageSize, clientEntries)
        return deltaSync(
            paddockRepository.findByStableIdAndDeletedFalse(requester.stableId), clientEntries, page, pageSize,
            id = { it.id.toHexString() }, updatedAt = { it.updatedAt }, toResponse = { it.toPaddockResponse() },
        )
    }

    // Horse groups

    @PostMapping("/horsegroups")
    fun createGroup(@Valid @RequestBody request: GroupRequest): HorseGroupResponse {
        val requester = accessService.requester(requireAuth())
        return paddockService.createGroup(requester, request.name, request.horseIds.map(::parseObjectId)).toHorseGroupResponse()
    }

    @PutMapping("/horsegroups/{groupId}")
    fun updateGroup(@PathVariable groupId: String, @Valid @RequestBody request: GroupRequest): HorseGroupResponse {
        val requester = accessService.requester(requireAuth())
        return paddockService.updateGroup(requester, parseObjectId(groupId), request.name, request.horseIds.map(::parseObjectId))
            .toHorseGroupResponse()
    }

    @DeleteMapping("/horsegroups/{groupId}")
    fun deleteGroup(@PathVariable groupId: String) {
        val requester = accessService.requester(requireAuth())
        paddockService.deleteGroup(requester, parseObjectId(groupId))
    }

    @PostMapping("/horsegroups/sync")
    fun syncGroups(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @Valid @RequestBody clientEntries: List<@Valid IdTimeStamp>,
    ): SyncResponse<HorseGroupResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidSyncRequest(page, pageSize, clientEntries)
        return deltaSync(
            groupRepository.findByStableIdAndDeletedFalse(requester.stableId), clientEntries, page, pageSize,
            id = { it.id.toHexString() }, updatedAt = { it.updatedAt }, toResponse = { it.toHorseGroupResponse() },
        )
    }

    // Conflicts

    @PostMapping("/horseconflicts")
    fun createConflict(@Valid @RequestBody request: ConflictRequest): HorseConflictResponse {
        val requester = accessService.requester(requireAuth())
        return paddockService.createConflict(
            requester, parseObjectId(request.firstHorseId), parseObjectId(request.secondHorseId), request.reason,
        ).toHorseConflictResponse()
    }

    @PutMapping("/horseconflicts/{conflictId}")
    fun updateConflict(@PathVariable conflictId: String, @Valid @RequestBody request: ConflictReasonRequest): HorseConflictResponse {
        val requester = accessService.requester(requireAuth())
        return paddockService.updateConflictReason(requester, parseObjectId(conflictId), request.reason).toHorseConflictResponse()
    }

    @DeleteMapping("/horseconflicts/{conflictId}")
    fun deleteConflict(@PathVariable conflictId: String) {
        val requester = accessService.requester(requireAuth())
        paddockService.deleteConflict(requester, parseObjectId(conflictId))
    }

    @PostMapping("/horseconflicts/sync")
    fun syncConflicts(
        @RequestParam(value = "page", defaultValue = "0") page: Int,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
        @Valid @RequestBody clientEntries: List<@Valid IdTimeStamp>,
    ): SyncResponse<HorseConflictResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidSyncRequest(page, pageSize, clientEntries)
        return deltaSync(
            conflictRepository.findByStableIdAndDeletedFalse(requester.stableId), clientEntries, page, pageSize,
            id = { it.id.toHexString() }, updatedAt = { it.updatedAt }, toResponse = { it.toHorseConflictResponse() },
        )
    }

    // Assignments

    @PostMapping("/paddockassignments")
    fun createAssignment(@Valid @RequestBody request: AssignmentRequest): PaddockAssignmentResponse {
        val requester = accessService.requester(requireAuth())
        return paddockService.createAssignment(
            requester, parseObjectId(request.paddockId), request.groupIds.map(::parseObjectId), request.horseIds.map(::parseObjectId),
            Instant.ofEpochMilli(request.startAt), request.endAt?.let(Instant::ofEpochMilli), request.comment,
        ).toPaddockAssignmentResponse()
    }

    @PutMapping("/paddockassignments/{assignmentId}")
    fun updateAssignment(@PathVariable assignmentId: String, @Valid @RequestBody request: AssignmentRequest): PaddockAssignmentResponse {
        val requester = accessService.requester(requireAuth())
        return paddockService.updateAssignment(
            requester, parseObjectId(assignmentId), parseObjectId(request.paddockId), request.groupIds.map(::parseObjectId),
            request.horseIds.map(::parseObjectId), Instant.ofEpochMilli(request.startAt), request.endAt?.let(Instant::ofEpochMilli),
            request.comment,
        ).toPaddockAssignmentResponse()
    }

    @DeleteMapping("/paddockassignments/{assignmentId}")
    fun deleteAssignment(@PathVariable assignmentId: String) {
        val requester = accessService.requester(requireAuth())
        paddockService.deleteAssignment(requester, parseObjectId(assignmentId))
    }

    @GetMapping("/paddockassignments/sync")
    fun syncAssignments(
        @RequestParam(value = "since", defaultValue = "0") since: Long,
        @RequestParam(value = "page_size", defaultValue = "400") pageSize: Int,
    ): VersionSyncResponse<PaddockAssignmentResponse> {
        val requester = accessService.requester(requireAuth())
        requireValidVersionSyncRequest(since, pageSize)
        return paddockService.syncAssignments(requester, since, pageSize)
    }
}
