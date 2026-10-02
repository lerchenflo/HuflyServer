package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.versionSync
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.EventInvitationResponse
import com.lerchenflo.hufly.server.event.model.EventResponse
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.event.model.toEventInvitationResponse
import com.lerchenflo.hufly.server.event.model.toEventResponse
import com.lerchenflo.hufly.server.repository.EventInvitationRepository
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
 * Visibility depends on invitations, so changing who is invited re-stamps the event and all its invitations:
 * a new invitee then syncs the older rows, and a removed one gets them as deleted.
 */
@Service
class EventService(
    private val eventRepository: EventRepository,
    private val invitationRepository: EventInvitationRepository,
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
    ): Event {
        accessService.requirePermission(requester, Permission.EVENT_EDIT)
        validate(requester, startAt, endAt, horseIds)
        requireOwnUsers(requester, inviteeUserIds)
        requireInvitationCap(inviteeUserIds.distinct().size)
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
                createdAt = now,
                updatedAt = now,
                updatedBy = requester.id,
            )
        )
        inviteeUserIds.distinct().forEach { saveInvitation(newInvitation(requester, event, it)) }
        return event
    }

    fun updateEvent(
        requester: User,
        eventId: ObjectId,
        title: String,
        description: String,
        startAt: Instant,
        endAt: Instant,
        horseIds: List<ObjectId>,
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
        saveEvent(event.copy(deleted = true, updatedAt = now, updatedBy = requester.id))
    }

    /** Already invited users are skipped. */
    fun invite(requester: User, eventId: ObjectId, userIds: List<ObjectId>) {
        val event = managedEvent(requester, eventId)
        requireOwnUsers(requester, userIds)
        val alreadyInvited = invitationRepository.findByEventIdAndDeletedFalse(event.id).map { it.userId }.toSet()
        val newUserIds = userIds.distinct().filter { it !in alreadyInvited }
        if (newUserIds.isEmpty()) return
        requireInvitationCap(alreadyInvited.size + newUserIds.size)
        newUserIds.forEach { saveInvitation(newInvitation(requester, event, it)) }
        restamp(event)
    }

    fun removeInvitation(requester: User, invitationId: ObjectId) {
        val invitation = liveInvitation(requester, invitationId)
        val event = managedEvent(requester, invitation.eventId)
        saveInvitation(invitation.copy(deleted = true, updatedAt = clock.instant(), updatedBy = requester.id))
        restamp(event)
    }

    fun respond(requester: User, invitationId: ObjectId, accepted: Boolean): EventInvitation {
        val invitation = liveInvitation(requester, invitationId)
        if (invitation.userId != requester.id) throw ResponseStatusException(HttpStatus.FORBIDDEN, "Not your invitation")
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

    private fun invitedEventIds(requester: User) =
        invitationRepository.findByUserIdAndDeletedFalse(requester.id).mapTo(mutableSetOf()) { it.eventId }

    private fun restamp(event: Event) {
        saveEvent(event)
        invitationRepository.findByEventIdAndDeletedFalse(event.id).forEach { saveInvitation(it) }
    }

    private fun newInvitation(requester: User, event: Event, userId: ObjectId): EventInvitation {
        val now = clock.instant()
        return EventInvitation(
            stableId = event.stableId,
            eventId = event.id,
            userId = userId,
            status = InvitationStatus.PENDING,
            invitedAt = now,
            respondedAt = null,
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

    /** EVENT_EDIT plus being the creator or the admin (who has every permission anyway). */
    private fun managedEvent(requester: User, eventId: ObjectId): Event {
        accessService.requirePermission(requester, Permission.EVENT_EDIT)
        val event = eventRepository.findById(eventId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found")
        if (event.creatorUserId != requester.id && !accessService.isAdmin(requester)) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator or the admin can change this event")
        }
        return event
    }

    private fun liveInvitation(requester: User, invitationId: ObjectId): EventInvitation =
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
