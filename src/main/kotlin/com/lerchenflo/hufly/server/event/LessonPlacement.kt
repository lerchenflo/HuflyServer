package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import org.bson.types.ObjectId

/** An invitee's answer for one date of a lesson; [waitingPosition] counts from 1, null with a place or declined. */
data class LessonSeat(val invitation: EventInvitation, val status: InvitationStatus, val waitingPosition: Int?) {
    val isWaiting get() = waitingPosition != null
    val hasPlace get() = status != InvitationStatus.DECLINED && !isWaiting
}

/**
 * Who has a place on which date of a lesson ([Event.maxParticipants] set), placed exactly like the client places them:
 * the participants of a date in [Event.participantOrder] (invitees missing from it last), accepted ones keep their
 * place, open ones fill the free places in order, the other open ones wait. The organiser taking part takes no place.
 * A date is keyed like its answers: null for a single event, else its original start. Takes the live rows of the event.
 */
class LessonPlacement(
    private val event: Event,
    private val invitations: List<EventInvitation>,
    occurrences: List<EventOccurrence>,
    answers: List<EventOccurrenceAnswer>,
) {
    private val changes = occurrences.associateBy { it.occurrenceStartAt }
    private val answers = answers.associateBy { it.invitationId to it.occurrenceStartAt }

    /** Null for a plain event. */
    fun seatsOn(key: Long?): List<LessonSeat>? {
        val maxParticipants = event.maxParticipants ?: return null
        val rank = event.participantOrder.withIndex().associate { (index, userId) -> userId to index }
        val ordered = participants(key).filter { it.first.userId != event.creatorUserId }
            .sortedBy { rank[it.first.userId] ?: Int.MAX_VALUE }
        var open = maxParticipants - ordered.count { it.second == InvitationStatus.ACCEPTED }
        var waiting = 0
        return ordered.map { (invitation, status) ->
            val waitingPosition = when {
                status != InvitationStatus.PENDING -> null
                open > 0 -> null.also { open-- }
                else -> ++waiting
            }
            LessonSeat(invitation, status, waitingPosition)
        }
    }

    /** Places nobody holds or is asked for; null for a plain event. */
    fun freePlaces(key: Long?): Int? {
        val maxParticipants = event.maxParticipants ?: return null
        return (maxParticipants - seatsOn(key).orEmpty().count { it.hasPlace }).coerceAtLeast(0)
    }

    /** The start of the date [key], moved or not. */
    fun startOf(key: Long?): Long = key?.let { changes[it]?.startAt ?: it } ?: event.startAt

    private fun participants(key: Long?): List<Pair<EventInvitation, InvitationStatus>> {
        val inOrder = invitations.sortedWith(compareBy({ it.invitedAt }, { it.id }))
        if (event.recurrence == null || key == null) return inOrder.filter { it.occurrenceStartAt == null }.map { it to it.status }
        val removed = changes[key]?.removedUserIds.orEmpty().toSet()
        val series = inOrder.filter { it.occurrenceStartAt == null && it.userId !in removed }
        val seriesUsers: Set<ObjectId> = series.mapTo(mutableSetOf()) { it.userId }
        return series.map { it to (answers[it.id to key]?.status ?: InvitationStatus.PENDING) } +
            inOrder.filter { it.occurrenceStartAt == key && it.userId !in seriesUsers }.map { it to it.status }
    }
}
