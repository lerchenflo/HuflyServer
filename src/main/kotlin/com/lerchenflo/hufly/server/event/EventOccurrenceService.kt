package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.MAX_EPOCH_MILLIS
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.recurrence.requireOccurrence
import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.versionSync
import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswerResponse
import com.lerchenflo.hufly.server.event.model.EventOccurrenceResponse
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.event.model.toEventOccurrenceAnswerResponse
import com.lerchenflo.hufly.server.event.model.toEventOccurrenceResponse
import com.lerchenflo.hufly.server.repository.EventInvitationRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceRepository
import com.lerchenflo.hufly.server.repository.EventRepository
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant

/** Null fields keep the series' value; all null with [cancelled] false restores the date. */
data class EventOccurrenceChange(
    val cancelled: Boolean,
    val title: String?,
    val description: String?,
    val startAt: Instant?,
    val endAt: Instant?,
    val horseIds: List<ObjectId>?,
)

/**
 * Single dates of an event series (EVT-10): changes by whoever may edit the event, per-date answers by invitees.
 * Both are upserts keyed by the date's original start, so retries are harmless. Visibility follows the event.
 */
@Service
class EventOccurrenceService(
    private val eventService: EventService,
    private val eventRepository: EventRepository,
    private val invitationRepository: EventInvitationRepository,
    private val occurrenceRepository: EventOccurrenceRepository,
    private val answerRepository: EventOccurrenceAnswerRepository,
    private val horseRepository: HorseRepository,
    private val accessService: AccessService,
    private val versionCounterService: VersionCounterService,
    private val clock: Clock,
) {
    fun putOccurrence(requester: User, eventId: ObjectId, occurrenceStartAt: Instant, change: EventOccurrenceChange): EventOccurrence {
        val event = eventService.managedEvent(requester, eventId)
        event.recurrence.requireOccurrence(event.startAt, occurrenceStartAt)
        validate(requester, change)
        return retryOnDuplicate {
            val existing = occurrenceRepository.findByEventIdAndOccurrenceStartAt(event.id, occurrenceStartAt)
            eventService.saveOccurrence(
                EventOccurrence(
                    id = existing?.id ?: ObjectId.get(),
                    stableId = event.stableId,
                    eventId = event.id,
                    occurrenceStartAt = occurrenceStartAt,
                    cancelled = change.cancelled,
                    title = change.title,
                    description = change.description,
                    startAt = change.startAt,
                    endAt = change.endAt,
                    horseIds = change.horseIds?.distinct(),
                    updatedAt = clock.instant(),
                    updatedBy = requester.id,
                )
            )
        }
    }

    fun answer(requester: User, invitationId: ObjectId, occurrenceStartAt: Instant, accepted: Boolean): EventOccurrenceAnswer {
        val invitation = eventService.liveInvitation(requester, invitationId)
        if (invitation.userId != requester.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not your invitation")
        if (invitation.occurrenceStartAt != null) throw badRequest("A date invitation is answered as a whole")
        val event = eventRepository.findById(invitation.eventId)?.takeIf { !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        event.recurrence.requireOccurrence(event.startAt, occurrenceStartAt)
        if (occurrenceRepository.findByEventIdAndOccurrenceStartAt(event.id, occurrenceStartAt)?.let { it.cancelled && !it.deleted } == true) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "This date is cancelled")
        }
        return retryOnDuplicate {
            val existing = answerRepository.findByInvitationIdAndOccurrenceStartAt(invitation.id, occurrenceStartAt)
            val now = clock.instant()
            eventService.saveAnswer(
                EventOccurrenceAnswer(
                    id = existing?.id ?: ObjectId.get(),
                    stableId = invitation.stableId,
                    eventId = invitation.eventId,
                    invitationId = invitation.id,
                    userId = invitation.userId,
                    occurrenceStartAt = occurrenceStartAt,
                    status = if (accepted) InvitationStatus.ACCEPTED else InvitationStatus.DECLINED,
                    respondedAt = now,
                    updatedAt = now,
                    updatedBy = requester.id,
                )
            )
        }
    }

    fun syncOccurrences(requester: User, since: Long, pageSize: Int): VersionSyncResponse<EventOccurrenceResponse> {
        val visible = eventService.eventVisibility(requester)
        val watermark = versionCounterService.safeWatermark(SyncCollection.EVENT_OCCURRENCES)
        return versionSync(
            occurrenceRepository.findVersionPage(requester.stableId, since, watermark, Limit.of(pageSize + 1)), since, pageSize,
            id = { it.id.toHexString() },
            version = { it.version },
            deleted = { it.deleted },
            visible = { visible(it.eventId) },
            toResponse = { it.toEventOccurrenceResponse() },
        )
    }

    fun syncAnswers(requester: User, since: Long, pageSize: Int): VersionSyncResponse<EventOccurrenceAnswerResponse> {
        val visible = eventService.eventVisibility(requester)
        val watermark = versionCounterService.safeWatermark(SyncCollection.EVENT_OCCURRENCE_ANSWERS)
        return versionSync(
            answerRepository.findVersionPage(requester.stableId, since, watermark, Limit.of(pageSize + 1)), since, pageSize,
            id = { it.id.toHexString() },
            version = { it.version },
            deleted = { it.deleted },
            visible = { visible(it.eventId) },
            toResponse = { it.toEventOccurrenceAnswerResponse() },
        )
    }

    private fun validate(requester: User, change: EventOccurrenceChange) {
        change.title?.let { if (it.isBlank() || it.length > 200) throw badRequest("Title must be 1 to 200 characters") }
        change.description?.let { if (it.length > 5000) throw badRequest("Description too long") }
        if ((change.startAt == null) != (change.endAt == null)) throw badRequest("Set both startAt and endAt or neither")
        if (change.startAt != null && change.endAt != null) {
            val range = Instant.EPOCH..Instant.ofEpochMilli(MAX_EPOCH_MILLIS)
            if (change.startAt !in range || change.endAt !in range) throw badRequest("Time out of range")
            if (change.endAt <= change.startAt) throw badRequest("End must be after start")
        }
        change.horseIds?.let { horseIds ->
            val valid = horseIds.isNotEmpty() && horseIds.size <= MAX_HORSES && horseIds.all { id ->
                horseRepository.findById(id)?.let { it.stableId == requester.stableId && !it.deleted } == true
            }
            if (!valid) throw badRequest("A date needs 1 to $MAX_HORSES horses of the stable")
        }
    }

    private fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)
}

/** Two concurrent first writes of the same date: the loser finds the winner's row on the second try. */
internal fun <T> retryOnDuplicate(block: () -> T): T = try {
    block()
} catch (e: DuplicateKeyException) {
    block()
}

private const val MAX_HORSES = 50
