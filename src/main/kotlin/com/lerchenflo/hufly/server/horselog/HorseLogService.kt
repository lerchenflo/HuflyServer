package com.lerchenflo.hufly.server.horselog

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.idempotentCreate
import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.versionSync
import com.lerchenflo.hufly.server.horselog.model.HorseLogEntry
import com.lerchenflo.hufly.server.horselog.model.HorseLogEntryResponse
import com.lerchenflo.hufly.server.horselog.model.toHorseLogEntryResponse
import com.lerchenflo.hufly.server.repository.HorseLogRepository
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.repository.TagRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** Writing needs HORSE_LOG_WRITE, or HORSE_EDIT_OWN for entries of own horses; every member reads the whole log. */
@Service
class HorseLogService(
    private val logRepository: HorseLogRepository,
    private val horseRepository: HorseRepository,
    private val tagRepository: TagRepository,
    private val userRepository: UserRepository,
    private val accessService: AccessService,
    private val versionCounterService: VersionCounterService,
    private val clock: Clock,
) {
    data class LogData(
        val horseId: ObjectId,
        val activityTagId: ObjectId,
        val startAt: Instant,
        val endAt: Instant?,
        val doneByUserId: ObjectId?,
        val comment: String,
        val nextDueAt: LocalDate?,
    )

    fun createEntry(requester: User, data: LogData, clientId: String? = null): HorseLogEntry {
        requireWriter(requester, listOf(data.horseId))
        return idempotentCreate(clientId, { logRepository.findByStableIdAndClientId(requester.stableId, it) }) {
            validate(requester, data)
            val now = clock.instant()
            save(
                HorseLogEntry(
                    stableId = requester.stableId,
                    horseId = data.horseId,
                    activityTagId = data.activityTagId,
                    startAt = data.startAt,
                    endAt = data.endAt,
                    doneByUserId = data.doneByUserId,
                    comment = data.comment,
                    nextDueAt = data.nextDueAt,
                    createdAt = now,
                    updatedAt = now,
                    updatedBy = requester.id,
                    clientId = clientId,
                )
            )
        }
    }

    fun updateEntry(requester: User, entryId: ObjectId, data: LogData): HorseLogEntry {
        val entry = stableEntry(requester, entryId)
        requireWriter(requester, listOf(entry.horseId, data.horseId))
        validate(requester, data)
        return save(
            entry.copy(
                horseId = data.horseId,
                activityTagId = data.activityTagId,
                startAt = data.startAt,
                endAt = data.endAt,
                doneByUserId = data.doneByUserId,
                comment = data.comment,
                nextDueAt = data.nextDueAt,
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    fun deleteEntry(requester: User, entryId: ObjectId) {
        val entry = stableEntry(requester, entryId)
        requireWriter(requester, listOf(entry.horseId))
        save(entry.copy(deleted = true, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    fun sync(requester: User, since: Long, pageSize: Int): VersionSyncResponse<HorseLogEntryResponse> {
        val watermark = versionCounterService.safeWatermark(SyncCollection.HORSE_LOG)
        val rows = logRepository.findVersionPage(requester.stableId, since, watermark, Limit.of(pageSize + 1))
        return versionSync(
            rows, since, pageSize,
            id = { it.id.toHexString() },
            version = { it.version },
            deleted = { it.deleted },
            visible = { true },
            toResponse = { it.toHorseLogEntryResponse() },
        )
    }

    private fun save(entry: HorseLogEntry): HorseLogEntry =
        versionCounterService.withVersion(SyncCollection.HORSE_LOG) { version -> logRepository.save(entry.copy(version = version)) }

    private fun validate(requester: User, data: LogData) {
        val horseOk = horseRepository.findById(data.horseId)?.let { it.stableId == requester.stableId && !it.deleted } == true
        val tagOk = tagRepository.findById(data.activityTagId)
            ?.let { it.stableId == requester.stableId && !it.deleted && it.type == TagType.ACTIVITY } == true
        val doerOk = data.doneByUserId == null ||
            userRepository.findById(data.doneByUserId)?.let { it.stableId == requester.stableId && !it.deleted } == true
        if (!horseOk || !tagOk || !doerOk) throw badRequest("Unknown horse, activity or user")
        if (data.endAt != null && data.endAt < data.startAt) throw badRequest("End before start")
    }

    private fun requireWriter(requester: User, horseIds: List<ObjectId>) {
        val ownerIds = horseIds.map { id -> horseRepository.findById(id)?.takeIf { it.stableId == requester.stableId }?.ownerUserId }
        accessService.requireHorsePermission(requester, Permission.HORSE_LOG_WRITE, ownerIds)
    }

    private fun stableEntry(requester: User, entryId: ObjectId): HorseLogEntry =
        logRepository.findById(entryId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Log entry not found")

    private fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)
}
