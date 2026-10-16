package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.MILLIS_PER_HOUR
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.repository.EventInvitationRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** The rules of lessons ([Event.maxParticipants] set): places, the waiting list and the leave lock, see [LessonPlacement]. */
@Service
class LessonService(
    private val invitationRepository: EventInvitationRepository,
    private val occurrenceRepository: EventOccurrenceRepository,
    private val answerRepository: EventOccurrenceAnswerRepository,
    private val clock: Clock,
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

    private fun conflict(reason: String) = ResponseStatusException(HttpStatus.CONFLICT, reason)
}
