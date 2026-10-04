package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.idempotentCreate
import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.recurrence.requireOccurrence
import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.versionSync
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.EventInvitationResponse
import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.event.model.EventResponse
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.event.model.toEventInvitationResponse
import com.lerchenflo.hufly.server.event.model.toEventResponse
import com.lerchenflo.hufly.server.repository.EventInvitationRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceRepository
import com.lerchenflo.hufly.server.repository.EventRepository
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.Instant

/**
 * Creating needs EVENT_EDIT; changing an event or its invitations needs EVENT_EDIT and being its creator or the admin.
 * Invitees, the creator and EVENT_VIEW holders see an event with its whole invitation list.
 *
 * Visibility depends on invitations, so changing who is invited re-stamps the event and all its invitations, date changes and per-date answers:
 * a new invitee then syncs the older rows, and a removed one gets them as deleted.
 */
@Service
class EventService(
    private val eventRepository: EventRepository,
    private val invitationRepository: EventInvitationRepository,
    private val occurrenceRepository: EventOccurrenceRepository,
    private val answerRepository: EventOccurrenceAnswerRepository,
    private val userRepository: UserRepository,
    private val horseRepository: HorseRepository,
    private val accessService: AccessService,
    private val versionCounterService: VersionCounterService,
    private val clock: Clock,
) {
    fun createEvent(
        requester: User,
        title: String,
        description: String,
        startAt: Instant,
        endAt: Instant,
        horseIds: List<ObjectId>,
        inviteeUserIds: List<ObjectId>,
        clientId: String? = null,
        recurrence: Recurrence? = null,
    ): Event {
        accessService.requirePermission(requester, Permission.EVENT_EDIT)
        return idempotentCreate(clientId, { eventRepository.findByStableIdAndClientId(requester.stableId, it) }) {
            val invitees = inviteeUserIds.distinct() - requester.id
            validate(requester, startAt, endAt, horseIds)
            requireOwnUsers(requester, invitees)
            requireInvitationCap(invitees.size)
            val now = clock.instant()
            val event = saveEvent(
                Event(
                    stableId = requester.stableId,
                    creatorUserId = requester.id,
                    title = title,
                    description = description,
                    startAt = startAt,
                    endAt = endAt,
                    horseIds = horseIds.distinct(),
                    recurrence = recurrence,
                    createdAt = now,
                    updatedAt = now,
                    updatedBy = requester.id,
                    clientId = clientId,
                )
            )
            invitees.forEach { saveInvitation(newInvitation(requester, event, it)) }
            event
        }
    }

    fun updateEvent(
        requester: User,
        eventId: ObjectId,
        title: String,
        description: String,
        startAt: Instant,
        endAt: Instant,
        horseIds: List<ObjectId>,
        recurrence: Recurrence? = null,
    ): Event {
        val event = managedEvent(requester, eventId)
        validate(requester, startAt, endAt, horseIds)
        return saveEvent(
            event.copy(
                title = title,
                description = description,
                startAt = startAt,
                endAt = endAt,
                horseIds = horseIds.distinct(),
                recurrence = recurrence,
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    fun deleteEvent(requester: User, eventId: ObjectId) {
        val event = managedEvent(requester, eventId)
        val now = clock.instant()
        invitationRepository.findByEventIdAndDeletedFalse(event.id).forEach {
            saveInvitation(it.copy(deleted = true, updatedAt = now, updatedBy = requester.id))
        }
        occurrenceRepository.findByEventIdAndDeletedFalse(event.id).forEach {
            saveOccurrence(it.copy(deleted = true, updatedAt = now, updatedBy = requester.id))
        }
        answerRepository.findByEventIdAndDeletedFalse(event.id).forEach {
            saveAnswer(it.copy(deleted = true, updatedAt = now, updatedBy = requester.id))
        }
        saveEvent(event.copy(deleted = true, updatedAt = now, updatedBy = requester.id))
    }

    /**
     * Already invited users and the creator are skipped. With [occurrenceStartAt] the users are invited to that one
     * date of the series; users invited to the whole series are skipped then. Answers the live invitations afterwards.
     */
    fun invite(requester: User, eventId: ObjectId, userIds: List<ObjectId>, occurrenceStartAt: Instant? = null): List<EventInvitation> {
        val event = managedEvent(requester, eventId)
        if (occurrenceStartAt != null) event.recurrence.requireOccurrence(event.startAt, occurrenceStartAt)
        requireOwnUsers(requester, userIds)
        val live = invitationRepository.findByEventIdAndDeletedFalse(event.id)
        val skipped = live.filter { it.occurrenceStartAt == null || it.occurrenceStartAt == occurrenceStartAt }.map { it.userId }.toSet()
        val newUserIds = userIds.distinct().filter { it !in skipped && it != event.creatorUserId }
        if (newUserIds.isNotEmpty()) {
            requireInvitationCap(live.size + newUserIds.size)
            newUserIds.forEach { saveInvitation(newInvitation(requester, event, it, occurrenceStartAt)) }
            restamp(event)
        }
        return invitationRepository.findByEventIdAndDeletedFalse(event.id)
    }

    fun removeInvitation(requester: User, invitationId: ObjectId) {
        val invitation = liveInvitation(requester, invitationId)
        val event = managedEvent(requester, invitation.eventId)
        val now = clock.instant()
        saveInvitation(invitation.copy(deleted = true, updatedAt = now, updatedBy = requester.id))
        answerRepository.findByInvitationIdAndDeletedFalse(invitation.id).forEach {
            saveAnswer(it.copy(deleted = true, updatedAt = now, updatedBy = requester.id))
        }
        restamp(event)
    }

    fun respond(requester: User, invitationId: ObjectId, accepted: Boolean): EventInvitation {
        val invitation = liveInvitation(requester, invitationId)
        if (invitation.userId != requester.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not your invitation")
        if (invitation.occurrenceStartAt == null && eventRepository.findById(invitation.eventId)?.recurrence != null) {
            throw badRequest("Answer the dates of a series one by one")
        }
        val now = clock.instant()
        return saveInvitation(
            invitation.copy(
                status = if (accepted) InvitationStatus.ACCEPTED else InvitationStatus.DECLINED,
                respondedAt = now,
                updatedAt = now,
                updatedBy = requester.id,
            )
        )
    }

    fun syncEvents(requester: User, since: Long, pageSize: Int): VersionSyncResponse<EventResponse> {
        val seesAll = seesAll(requester)
        val invitedEventIds = invitedEventIds(requester)
        val watermark = versionCounterService.safeWatermark(SyncCollection.EVENTS)
        val rows = eventRepository.findVersionPage(requester.stableId, since, watermark, Limit.of(pageSize + 1))
        return versionSync(
            rows, since, pageSize,
            id = { it.id.toHexString() },
            version = { it.version },
            deleted = { it.deleted },
            visible = { seesAll || it.creatorUserId == requester.id || it.id in invitedEventIds },
            toResponse = { it.toEventResponse() },
        )
    }

    fun syncInvitations(requester: User, since: Long, pageSize: Int): VersionSyncResponse<EventInvitationResponse> {
        val seesAll = seesAll(requester)
        val visibleEventIds = invitedEventIds(requester) + eventRepository.findByCreatorUserIdAndDeletedFalse(requester.id).map { it.id }
        val watermark = versionCounterService.safeWatermark(SyncCollection.EVENT_INVITATIONS)
        val rows = invitationRepository.findVersionPage(requester.stableId, since, watermark, Limit.of(pageSize + 1))
        return versionSync(
            rows, since, pageSize,
            id = { it.id.toHexString() },
            version = { it.version },
            deleted = { it.deleted },
            visible = { seesAll || it.eventId in visibleEventIds },
            toResponse = { it.toEventInvitationResponse() },
        )
    }

    private fun seesAll(requester: User) = Permission.EVENT_VIEW in accessService.effectivePermissions(requester)

    /** Like the invitation sync: EVENT_VIEW, the creator and every invitee (also to single dates) see an event's rows. */
    internal fun eventVisibility(requester: User): (ObjectId) -> Boolean {
        if (seesAll(requester)) return { true }
        val visible = invitedEventIds(requester) + eventRepository.findByCreatorUserIdAndDeletedFalse(requester.id).map { it.id }
        return { it in visible }
    }

    private fun invitedEventIds(requester: User) =
        invitationRepository.findByUserIdAndDeletedFalse(requester.id).mapTo(mutableSetOf()) { it.eventId }

    private fun restamp(event: Event) {
        saveEvent(event)
        invitationRepository.findByEventIdAndDeletedFalse(event.id).forEach { saveInvitation(it) }
        occurrenceRepository.findByEventIdAndDeletedFalse(event.id).forEach { saveOccurrence(it) }
        answerRepository.findByEventIdAndDeletedFalse(event.id).forEach { saveAnswer(it) }
    }

    private fun newInvitation(requester: User, event: Event, userId: ObjectId, occurrenceStartAt: Instant? = null): EventInvitation {
        val now = clock.instant()
        return EventInvitation(
            stableId = event.stableId,
            eventId = event.id,
            userId = userId,
            status = InvitationStatus.PENDING,
            invitedAt = now,
            respondedAt = null,
            occurrenceStartAt = occurrenceStartAt,
            updatedAt = now,
            updatedBy = requester.id,
        )
    }

    private fun saveEvent(event: Event): Event =
        versionCounterService.withVersion(SyncCollection.EVENTS) { version -> eventRepository.save(event.copy(version = version)) }

    private fun saveInvitation(invitation: EventInvitation): EventInvitation =
        versionCounterService.withVersion(SyncCollection.EVENT_INVITATIONS) { version ->
            invitationRepository.save(invitation.copy(version = version))
        }

    internal fun saveOccurrence(occurrence: EventOccurrence): EventOccurrence =
        versionCounterService.withVersion(SyncCollection.EVENT_OCCURRENCES) { version ->
            occurrenceRepository.save(occurrence.copy(version = version))
        }

    internal fun saveAnswer(answer: EventOccurrenceAnswer): EventOccurrenceAnswer =
        versionCounterService.withVersion(SyncCollection.EVENT_OCCURRENCE_ANSWERS) { version ->
            answerRepository.save(answer.copy(version = version))
        }

    /** EVENT_EDIT plus being the creator or the admin (who has every permission anyway). */
    internal fun managedEvent(requester: User, eventId: ObjectId): Event {
        accessService.requirePermission(requester, Permission.EVENT_EDIT)
        val event = eventRepository.findById(eventId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        if (event.creatorUserId != requester.id && !accessService.isAdmin(requester)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator or the admin can change this event")
        }
        return event
    }

    internal fun liveInvitation(requester: User, invitationId: ObjectId): EventInvitation =
        invitationRepository.findById(invitationId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Invitation not found")

    private fun validate(requester: User, startAt: Instant, endAt: Instant, horseIds: List<ObjectId>) {
        if (endAt < startAt) throw badRequest("End before start")
        val horsesOk = horseIds.isNotEmpty() &&
            horseIds.all { id -> horseRepository.findById(id)?.let { it.stableId == requester.stableId && !it.deleted } == true }
        if (!horsesOk) throw badRequest("An event needs at least one horse of the stable (EVT-5)")
    }

    private fun requireOwnUsers(requester: User, userIds: List<ObjectId>) {
        val valid = userIds.all { id -> userRepository.findById(id)?.let { it.stableId == requester.stableId && !it.deleted } == true }
        if (!valid) throw badRequest("Unknown user")
    }

    /** EVT-7 sets no participant limit; this technical cap only bounds the work of re-stamping. */
    private fun requireInvitationCap(liveInvitations: Int) {
        if (liveInvitations > MAX_INVITATIONS_PER_EVENT) throw badRequest("At most $MAX_INVITATIONS_PER_EVENT invitations per event")
    }

    private fun badRequest(reason: String) = ResponseStatusException(HttpStatus.BAD_REQUEST, reason)
}

const val MAX_INVITATIONS_PER_EVENT = 500
