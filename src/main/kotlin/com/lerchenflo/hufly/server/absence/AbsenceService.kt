package com.lerchenflo.hufly.server.absence

import com.lerchenflo.hufly.server.absence.model.Absence
import com.lerchenflo.hufly.server.absence.model.AbsenceResponse
import com.lerchenflo.hufly.server.absence.model.toAbsenceResponse
import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.idempotentCreate
import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.versionSync
import com.lerchenflo.hufly.server.repository.AbsenceRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** Everyone writes their own absences; others' need TASK_EDIT (or the admin). Every member sees all. No pushes. */
@Service
class AbsenceService(
    private val absenceRepository: AbsenceRepository,
    private val userRepository: UserRepository,
    private val accessService: AccessService,
    private val versionCounterService: VersionCounterService,
    private val clock: Clock,
) {
    data class AbsenceData(val userId: ObjectId, val from: Long, val until: Long, val note: String)

    fun createAbsence(requester: User, data: AbsenceData, clientId: String? = null): Absence {
        requireMayWrite(requester, data.userId)
        return idempotentCreate(clientId, { absenceRepository.findByStableIdAndClientId(requester.stableId, it) }) {
            validate(requester, data)
            save(
                Absence(
                    stableId = requester.stableId,
                    userId = data.userId,
                    from = data.from,
                    until = data.until,
                    note = data.note,
                    createdByUserId = requester.id,
                    updatedAt = clock.millis(),
                    updatedBy = requester.id,
                    clientId = clientId,
                )
            )
        }
    }

    fun updateAbsence(requester: User, absenceId: ObjectId, data: AbsenceData): Absence {
        val absence = ownAbsence(requester, absenceId)
        requireMayWrite(requester, absence.userId)
        requireMayWrite(requester, data.userId)
        validate(requester, data)
        return save(
            absence.copy(
                userId = data.userId, from = data.from, until = data.until, note = data.note,
                updatedAt = clock.millis(), updatedBy = requester.id,
            )
        )
    }

    fun deleteAbsence(requester: User, absenceId: ObjectId) {
        val absence = ownAbsence(requester, absenceId)
        requireMayWrite(requester, absence.userId)
        save(absence.copy(deleted = true, updatedAt = clock.millis(), updatedBy = requester.id))
    }

    /** Called when [userId] leaves the stable. */
    fun removeUser(userId: ObjectId, by: ObjectId) {
        absenceRepository.findByUserIdAndDeletedFalse(userId).forEach {
            save(it.copy(deleted = true, updatedAt = clock.millis(), updatedBy = by))
        }
    }

    fun sync(requester: User, since: Long, pageSize: Int): VersionSyncResponse<AbsenceResponse> {
        val watermark = versionCounterService.safeWatermark(SyncCollection.ABSENCES)
        val rows = absenceRepository.findVersionPage(requester.stableId, since, watermark, Limit.of(pageSize + 1))
        return versionSync(
            rows, since, pageSize,
            id = { it.id.toHexString() },
            version = { it.version },
            deleted = { it.deleted },
            visible = { true },
            toResponse = { it.toAbsenceResponse() },
        )
    }

    private fun requireMayWrite(requester: User, userId: ObjectId) {
        if (userId != requester.id) accessService.requirePermission(requester, Permission.TASK_EDIT)
    }

    private fun validate(requester: User, data: AbsenceData) {
        if (data.until < data.from) throw badRequest("End before start")
        val known = userRepository.findById(data.userId)?.let { it.stableId == requester.stableId && !it.deleted } == true
        if (!known) throw badRequest("Unknown user")
    }

    private fun ownAbsence(requester: User, id: ObjectId) =
        absenceRepository.findById(id)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Absence not found")

    private fun save(absence: Absence): Absence =
        versionCounterService.withVersion(SyncCollection.ABSENCES) { version -> absenceRepository.save(absence.copy(version = version)) }

    private fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)
}
