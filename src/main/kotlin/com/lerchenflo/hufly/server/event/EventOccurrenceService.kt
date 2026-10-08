package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.MAX_EPOCH_MILLIS
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.notification.InvitationAnswered
import com.lerchenflo.hufly.server.core.recurrence.requireOccurrence
import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.versionSync
import com.lerchenflo.hufly.server.event.model.Event
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

/** Null fields keep the series' value; all null with [cancelled] false restores the date. */
data class EventOccurrenceChange(
    val cancelled: Boolean,
    val title: String?,
    val description: String?,
    val startAt: Long?,
    val endAt: Long?,
    val horseIds: List<ObjectId>?,
    val removedUserIds: List<ObjectId>? = null,
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
    fun putOccurrence(requester: User, eventId: ObjectId, occurrenceStartAt: Long, change: EventOccurrenceChange): EventOccurrence {
        val event = eventService.managedEvent(requester, eventId)
        event.recurrence.requireOccurrence(event.startAt, occurrenceStartAt)
        validate(requester, change)
        val removedUserIds = change.removedUserIds?.distinct()?.takeIf { it.isNotEmpty() }
        removedUserIds?.let { requireSeriesInvitees(event, it) }
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
                    removedUserIds = removedUserIds,
                    updatedAt = clock.millis(),
                    updatedBy = requester.id,
                )
            )
        }
    }

    fun answer(requester: User, invitationId: ObjectId, occurrenceStartAt: Long, accepted: Boolean): EventOccurrenceAnswer {
        val invitation = eventService.liveInvitation(requester, invitationId)
        if (invitation.userId != requester.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not your invitation")
        if (invitation.occurrenceStartAt != null) throw badRequest("A date invitation is answered as a whole")
        val event = eventRepository.findById(invitation.eventId)?.takeIf { !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        event.recurrence.requireOccurrence(event.startAt, occurrenceStartAt)
        val occurrence = occurrenceRepository.findByEventIdAndOccurrenceStartAt(event.id, occurrenceStartAt)?.takeUnless { it.deleted }
        if (occurrence?.cancelled == true) throw ResponseStatusException(HttpStatus.CONFLICT, "This date is cancelled")
        if (occurrence?.removedUserIds?.contains(invitation.userId) == true) throw badRequest("Not invited to this date")
        val status = if (accepted) InvitationStatus.ACCEPTED else InvitationStatus.DECLINED
        val before = answerRepository.findByInvitationIdAndOccurrenceStartAt(invitation.id, occurrenceStartAt)?.takeUnless { it.deleted }?.status
        val saved = retryOnDuplicate {
            val existing = answerRepository.findByInvitationIdAndOccurrenceStartAt(invitation.id, occurrenceStartAt)
            val now = clock.millis()
            eventService.saveAnswer(
                EventOccurrenceAnswer(
                    id = existing?.id ?: ObjectId.get(),
                    stableId = invitation.stableId,
                    eventId = invitation.eventId,
                    invitationId = invitation.id,
                    userId = invitation.userId,
                    occurrenceStartAt = occurrenceStartAt,
                    status = status,
                    respondedAt = now,
                    updatedAt = now,
                    updatedBy = requester.id,
                )
            )
        }
        if (before != status && invitation.userId != event.creatorUserId) eventService.announce(InvitationAnswered(invitation.stableId, requester.id, event.id, accepted, occurrenceStartAt))
        return saved
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
            val range = 0L..MAX_EPOCH_MILLIS
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

    /** The creator takes part through their own invitation, so they may always be listed. */
    private fun requireSeriesInvitees(event: Event, userIds: List<ObjectId>) {
        val seriesInvitees = invitationRepository.findByEventIdAndDeletedFalse(event.id)
            .filter { it.occurrenceStartAt == null }.mapTo(mutableSetOf()) { it.userId } + event.creatorUserId
        if (!seriesInvitees.containsAll(userIds)) throw badRequest("Not invited to the series")
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
