package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import org.bson.types.ObjectId

/**
 * Who accepted which dates of [event], read like the client reads it: a single event by the invitation's status, a
 * series date by a date invitation's status or by the series invitee's answer for it (else the series invitation's
 * own status, the organiser's "Ich nehme auch teil"), unless they were taken off it. Cancelled dates and answers to
 * dates the rule no longer has count for nobody. Takes the live rows of the event.
 */
class EventParticipation(
    private val event: Event,
    private val invitations: List<EventInvitation>,
    occurrences: List<EventOccurrence>,
    answers: List<EventOccurrenceAnswer>,
) {
    private val changes = occurrences.associateBy { it.occurrenceStartAt }
    private val answers = answers.groupBy { it.invitationId }
    private val duration = event.endAt - event.startAt

    /** Users who accepted the date keyed [key]; for a single event its start. */
    fun acceptedOn(key: Long): List<ObjectId> = invitations.filter { accepts(it, key) }.map { it.userId }.distinct()

    /** Users who accepted a date that is not over at [now]. */
    fun acceptedUpcoming(now: Long): List<ObjectId> =
        invitations.filter { acceptsUpcoming(it, now) }.map { it.userId }.distinct()

    fun acceptsUpcoming(invitation: EventInvitation, now: Long): Boolean {
        val recurrence = event.recurrence ?: return accepts(invitation, event.startAt) && event.endAt > now
        val keys = invitation.occurrenceStartAt?.let(::listOf) ?: run {
            val answered = answers[invitation.id].orEmpty().map { it.occurrenceStartAt }
            // Without an answer the series invitation's status counts; enough upcoming dates to pass every exception.
            val unanswered = if (invitation.status != InvitationStatus.ACCEPTED) emptySequence() else
                recurrence.starts(event.startAt).filter { !isOver(it, now) }.take(answered.size + changes.size + 1)
            answered + unanswered
        }
        return keys.any { !isOver(it, now) && accepts(invitation, it) && recurrence.isOccurrence(event.startAt, it) }
    }

    private fun accepts(invitation: EventInvitation, key: Long): Boolean {
        if (event.recurrence == null) return invitation.occurrenceStartAt == null && invitation.status == InvitationStatus.ACCEPTED
        val change = changes[key]
        if (change?.cancelled == true) return false
        if (invitation.occurrenceStartAt != null) return invitation.occurrenceStartAt == key && invitation.status == InvitationStatus.ACCEPTED
        if (change?.removedUserIds?.contains(invitation.userId) == true) return false
        val answer = answers[invitation.id]?.firstOrNull { it.occurrenceStartAt == key }
        return (answer?.status ?: invitation.status) == InvitationStatus.ACCEPTED
    }

    private fun isOver(key: Long, now: Long) = (changes[key]?.endAt ?: (key + duration)) <= now
}
