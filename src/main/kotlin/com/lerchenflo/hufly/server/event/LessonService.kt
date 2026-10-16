package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.MILLIS_PER_HOUR
import com.lerchenflo.hufly.server.core.notification.LessonPlaceOffered
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.event.model.LessonOffer
import com.lerchenflo.hufly.server.repository.EventInvitationRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceRepository
import com.lerchenflo.hufly.server.repository.EventRepository
import com.lerchenflo.hufly.server.repository.LessonOfferRepository
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * The rules of lessons ([Event.maxParticipants] set): places, the waiting list and the leave lock, see [LessonPlacement].
 * Every change that can free or add a place runs through [movingUp]: whoever waited on a date that has not started and
 * has a place afterwards is offered it ([LessonOffer]) and pushed once, for their earliest such date.
 */
@Service
class LessonService(
    private val eventRepository: EventRepository,
    private val invitationRepository: EventInvitationRepository,
    private val occurrenceRepository: EventOccurrenceRepository,
    private val answerRepository: EventOccurrenceAnswerRepository,
    private val offerRepository: LessonOfferRepository,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
) {
    fun placement(event: Event) = LessonPlacement(
        event,
        invitationRepository.findByEventIdAndDeletedFalse(event.id),
        occurrenceRepository.findByEventIdAndDeletedFalse(event.id),
        answerRepository.findByEventIdAndDeletedFalse(event.id),
    )

    /**
     * 409 unless [invitation] may answer so for the date [key] (null: a single event): waiting people and decliners
     * without a free place cannot accept, accepted ones cannot leave once the leave lock has started.
     */
    fun requireAnswerAllowed(event: Event, invitation: EventInvitation, key: Long?, accepted: Boolean) {
        if (event.maxParticipants == null) return
        val placement = placement(event)
        val seat = placement.seatsOn(key)?.firstOrNull { it.invitation.id == invitation.id } ?: return
        if (accepted) {
            if (seat.isWaiting || (seat.status == InvitationStatus.DECLINED && placement.freePlaces(key) == 0)) throw conflict("Lesson: no free place")
            return
        }
        val lockHours = event.leaveLockHours?.takeIf { it > 0 } ?: return
        if (seat.status == InvitationStatus.ACCEPTED && clock.millis() >= placement.startOf(key) - lockHours * MILLIS_PER_HOUR) {
            throw conflict("Lesson: leaving is locked")
        }
    }

    /**
     * The dates a change touches: a single event, the date [occurrenceStartAt] of a series, or for a change to the
     * whole series its next [MOVE_UP_DATES] dates that have not started.
     */
    fun datesOf(event: Event, occurrenceStartAt: Long?): List<Long?> = when {
        event.recurrence == null -> listOf(null)
        occurrenceStartAt != null -> listOf(occurrenceStartAt)
        else -> placement(event).upcomingDates(clock.millis(), MOVE_UP_DATES)
    }

    /** Runs [change] on [event] and offers places to whoever moves up on [dates] by it. */
    fun <T> movingUp(actorUserId: ObjectId, event: Event, dates: List<Long?>, change: () -> T): T {
        val before = waiting(event, dates)
        return change().also { offerMovedUp(actorUserId, event.id, before) }
    }

    /** Who of [userIds] has a place on [dates]; an invitation push goes only to them, waiting people are not asked yet. */
    fun withPlace(event: Event, userIds: List<ObjectId>, dates: List<Long?>): List<ObjectId> {
        if (event.maxParticipants == null || dates.isEmpty()) return userIds
        val placement = placement(event)
        val placed = dates.flatMapTo(mutableSetOf()) { key -> placement.seatsOn(key).orEmpty().filter { it.hasPlace }.map { it.invitation.userId } }
        return userIds.filter { it in placed }
    }

    private fun waiting(event: Event, dates: List<Long?>): Map<Long?, Set<ObjectId>> {
        if (event.maxParticipants == null) return emptyMap()
        val placement = placement(event)
        return dates.associateWith { key -> placement.seatsOn(key).orEmpty().filter { it.isWaiting }.mapTo(mutableSetOf()) { it.invitation.id } }
    }

    private fun offerMovedUp(actorUserId: ObjectId, eventId: ObjectId, waitedBefore: Map<Long?, Set<ObjectId>>) {
        if (waitedBefore.values.all { it.isEmpty() }) return
        val event = eventRepository.findById(eventId)?.takeIf { !it.deleted && it.maxParticipants != null } ?: return
        val placement = placement(event)
        val now = clock.millis()
        val movedUp = waitedBefore.flatMap { (key, waited) ->
            if (waited.isEmpty() || !placement.isDate(key) || placement.startOf(key) <= now) return@flatMap emptyList()
            placement.seatsOn(key).orEmpty()
                .filter { it.invitation.id in waited && it.status == InvitationStatus.PENDING && it.hasPlace }
                .map { it.invitation to key }
        }
        movedUp.forEach { (invitation, key) -> offer(invitation, key, now) }
        movedUp.groupBy({ it.first.userId }, { it.second })
            .map { (userId, keys) -> userId to keys.minWith(nullsFirst(naturalOrder<Long>())) }
            .groupBy({ it.second }, { it.first })
            .forEach { (key, userIds) -> events.publishEvent(LessonPlaceOffered(event.stableId, actorUserId, event.id, userIds, key)) }
    }

    private fun offer(invitation: EventInvitation, key: Long?, now: Long) = retryOnDuplicate {
        val existing = offerRepository.findByInvitationIdAndOccurrenceStartAt(invitation.id, key)
        offerRepository.save(
            existing?.copy(offeredAt = now) ?: LessonOffer(
                stableId = invitation.stableId, eventId = invitation.eventId, invitationId = invitation.id,
                userId = invitation.userId, occurrenceStartAt = key, offeredAt = now,
            )
        )
    }

    private fun conflict(reason: String) = ResponseStatusException(HttpStatus.CONFLICT, reason)

    companion object {
        /** Changes to a whole series move people up on this many upcoming dates; later ones see their place without a push. */
        const val MOVE_UP_DATES = 10
    }
}
