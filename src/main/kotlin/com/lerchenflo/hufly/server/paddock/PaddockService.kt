package com.lerchenflo.hufly.server.paddock

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.versionSync
import com.lerchenflo.hufly.server.paddock.model.HorseConflict
import com.lerchenflo.hufly.server.paddock.model.HorseGroup
import com.lerchenflo.hufly.server.paddock.model.Paddock
import com.lerchenflo.hufly.server.paddock.model.PaddockAssignment
import com.lerchenflo.hufly.server.paddock.model.PaddockAssignmentResponse
import com.lerchenflo.hufly.server.paddock.model.toPaddockAssignmentResponse
import com.lerchenflo.hufly.server.repository.HorseConflictRepository
import com.lerchenflo.hufly.server.repository.HorseGroupRepository
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.repository.PaddockAssignmentRepository
import com.lerchenflo.hufly.server.repository.PaddockRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant

/** Every write needs PADDOCK_PLAN; every member reads. Conflicts never block an assignment. */
@Service
class PaddockService(
    private val paddockRepository: PaddockRepository,
    private val groupRepository: HorseGroupRepository,
    private val conflictRepository: HorseConflictRepository,
    private val assignmentRepository: PaddockAssignmentRepository,
    private val horseRepository: HorseRepository,
    private val accessService: AccessService,
    private val versionCounterService: VersionCounterService,
    private val clock: Clock,
) {
    // Paddocks

    fun createPaddock(requester: User, name: String, description: String): Paddock {
        requirePlanner(requester)
        return paddockRepository.save(
            Paddock(stableId = requester.stableId, name = name, description = description, updatedAt = clock.instant(), updatedBy = requester.id)
        )
    }

    fun updatePaddock(requester: User, paddockId: ObjectId, name: String, description: String): Paddock {
        requirePlanner(requester)
        val paddock = ownPaddock(requester, paddockId) ?: throw notFound()
        return paddockRepository.save(paddock.copy(name = name, description = description, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    fun deletePaddock(requester: User, paddockId: ObjectId) {
        requirePlanner(requester)
        val paddock = ownPaddock(requester, paddockId) ?: throw notFound()
        paddockRepository.save(paddock.copy(deleted = true, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    // Groups

    fun createGroup(requester: User, name: String, horseIds: List<ObjectId>): HorseGroup {
        requirePlanner(requester)
        requireOwnHorses(requester, horseIds)
        return groupRepository.save(
            HorseGroup(stableId = requester.stableId, name = name, horseIds = horseIds.distinct(), updatedAt = clock.instant(), updatedBy = requester.id)
        )
    }

    fun updateGroup(requester: User, groupId: ObjectId, name: String, horseIds: List<ObjectId>): HorseGroup {
        requirePlanner(requester)
        val group = ownGroup(requester, groupId) ?: throw notFound()
        requireOwnHorses(requester, horseIds)
        return groupRepository.save(group.copy(name = name, horseIds = horseIds.distinct(), updatedAt = clock.instant(), updatedBy = requester.id))
    }

    fun deleteGroup(requester: User, groupId: ObjectId) {
        requirePlanner(requester)
        val group = ownGroup(requester, groupId) ?: throw notFound()
        groupRepository.save(group.copy(deleted = true, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    // Conflicts

    fun createConflict(requester: User, firstHorseId: ObjectId, secondHorseId: ObjectId, reason: String): HorseConflict {
        requirePlanner(requester)
        if (firstHorseId == secondHorseId) throw badRequest("A horse cannot conflict with itself")
        requireOwnHorses(requester, listOf(firstHorseId, secondHorseId))
        val pair = setOf(firstHorseId, secondHorseId)
        if (conflictRepository.findByStableIdAndDeletedFalse(requester.stableId).any { setOf(it.firstHorseId, it.secondHorseId) == pair }) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Conflict already exists")
        }
        return conflictRepository.save(
            HorseConflict(
                stableId = requester.stableId,
                firstHorseId = firstHorseId,
                secondHorseId = secondHorseId,
                reason = reason,
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    fun updateConflictReason(requester: User, conflictId: ObjectId, reason: String): HorseConflict {
        requirePlanner(requester)
        val conflict = ownConflict(requester, conflictId)
        return conflictRepository.save(conflict.copy(reason = reason, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    fun deleteConflict(requester: User, conflictId: ObjectId) {
        requirePlanner(requester)
        val conflict = ownConflict(requester, conflictId)
        conflictRepository.save(conflict.copy(deleted = true, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    // Assignments

    fun createAssignment(
        requester: User,
        paddockId: ObjectId,
        groupIds: List<ObjectId>,
        horseIds: List<ObjectId>,
        startAt: Instant,
        endAt: Instant?,
        comment: String,
    ): PaddockAssignment {
        requirePlanner(requester)
        val resolved = resolveAssignment(requester, paddockId, groupIds, horseIds, startAt, endAt)
        return saveAssignment(
            PaddockAssignment(
                stableId = requester.stableId,
                paddockId = paddockId,
                groupIds = groupIds.distinct(),
                horseIds = resolved,
                startAt = startAt,
                endAt = endAt,
                comment = comment,
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    fun updateAssignment(
        requester: User,
        assignmentId: ObjectId,
        paddockId: ObjectId,
        groupIds: List<ObjectId>,
        horseIds: List<ObjectId>,
        startAt: Instant,
        endAt: Instant?,
        comment: String,
    ): PaddockAssignment {
        requirePlanner(requester)
        val assignment = ownAssignment(requester, assignmentId)
        val resolved = resolveAssignment(requester, paddockId, groupIds, horseIds, startAt, endAt)
        return saveAssignment(
            assignment.copy(
                paddockId = paddockId,
                groupIds = groupIds.distinct(),
                horseIds = resolved,
                startAt = startAt,
                endAt = endAt,
                comment = comment,
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    fun deleteAssignment(requester: User, assignmentId: ObjectId) {
        requirePlanner(requester)
        val assignment = ownAssignment(requester, assignmentId)
        saveAssignment(assignment.copy(deleted = true, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    fun syncAssignments(requester: User, since: Long, pageSize: Int): VersionSyncResponse<PaddockAssignmentResponse> {
        val watermark = versionCounterService.safeWatermark(SyncCollection.PADDOCK_ASSIGNMENTS)
        val rows = assignmentRepository.findVersionPage(requester.stableId, since, watermark, Limit.of(pageSize + 1))
        return versionSync(
            rows, since, pageSize,
            id = { it.id.toHexString() },
            version = { it.version },
            deleted = { it.deleted },
            visible = { true },
            toResponse = { it.toPaddockAssignmentResponse() },
        )
    }

    /** Group horses plus single horses, in that order, without duplicates. */
    private fun resolveAssignment(
        requester: User,
        paddockId: ObjectId,
        groupIds: List<ObjectId>,
        horseIds: List<ObjectId>,
        startAt: Instant,
        endAt: Instant?,
    ): List<ObjectId> {
        if (ownPaddock(requester, paddockId) == null) throw badRequest("Unknown paddock")
        val groups = groupIds.map { ownGroup(requester, it) ?: throw badRequest("Unknown group") }
        requireOwnHorses(requester, horseIds)
        if (endAt != null && endAt < startAt) throw badRequest("End before start")
        val resolved = (groups.flatMap { it.horseIds } + horseIds).distinct()
        if (resolved.isEmpty()) throw badRequest("No horses")
        return resolved
    }

    private fun saveAssignment(assignment: PaddockAssignment): PaddockAssignment =
        versionCounterService.withVersion(SyncCollection.PADDOCK_ASSIGNMENTS) { version ->
            assignmentRepository.save(assignment.copy(version = version))
        }

    private fun requirePlanner(requester: User) = accessService.requirePermission(requester, Permission.PADDOCK_PLAN)

    private fun requireOwnHorses(requester: User, horseIds: List<ObjectId>) {
        val valid = horseIds.all { id -> horseRepository.findById(id)?.let { it.stableId == requester.stableId && !it.deleted } == true }
        if (!valid) throw badRequest("Unknown horse")
    }

    private fun ownPaddock(requester: User, id: ObjectId) =
        paddockRepository.findById(id)?.takeIf { it.stableId == requester.stableId && !it.deleted }

    private fun ownGroup(requester: User, id: ObjectId) =
        groupRepository.findById(id)?.takeIf { it.stableId == requester.stableId && !it.deleted }

    private fun ownConflict(requester: User, id: ObjectId) =
        conflictRepository.findById(id)?.takeIf { it.stableId == requester.stableId && !it.deleted } ?: throw notFound()

    private fun ownAssignment(requester: User, id: ObjectId) =
        assignmentRepository.findById(id)?.takeIf { it.stableId == requester.stableId && !it.deleted } ?: throw notFound()

    private fun notFound() = ResponseStatusException(HttpStatus.NOT_FOUND, "Not found")

    private fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)
}
