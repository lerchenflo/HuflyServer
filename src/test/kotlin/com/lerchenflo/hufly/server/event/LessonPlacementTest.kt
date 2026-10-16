package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceFrequency
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.event.model.InvitationStatus.ACCEPTED
import com.lerchenflo.hufly.server.event.model.InvitationStatus.DECLINED
import com.lerchenflo.hufly.server.event.model.InvitationStatus.PENDING
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.millis
import org.bson.types.ObjectId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Lessons (client schema 37): who has a place and who waits, exactly like the client's `placed`. */
class LessonPlacementTest {

    private val organiser = ObjectId.get()
    private val anna = ObjectId.get()
    private val ben = ObjectId.get()
    private val clara = ObjectId.get()
    private val dora = ObjectId.get()
    private val start = millis("2026-11-02T16:00:00Z")
    private val week = 7 * 24 * 3600_000L

    private fun lesson(maxParticipants: Int? = 2, order: List<ObjectId> = emptyList(), weekly: Boolean = false) = Event(
        stableId = STABLE_ID, creatorUserId = organiser, title = "Springstunde", description = "", startAt = start,
        endAt = start + 3600_000, horseIds = listOf(ObjectId.get()), createdAt = 0, updatedAt = 0, updatedBy = organiser,
        recurrence = if (weekly) Recurrence(RecurrenceFrequency.WEEKLY, 1, emptyList(), null, null, "Europe/Vienna") else null,
        maxParticipants = maxParticipants, participantOrder = order,
    )

    private var invitedAt = 0L

    private fun invitation(event: Event, userId: ObjectId, status: InvitationStatus = PENDING, occurrenceStartAt: Long? = null) = EventInvitation(
        stableId = STABLE_ID, eventId = event.id, userId = userId, status = status, invitedAt = ++invitedAt, respondedAt = null,
        occurrenceStartAt = occurrenceStartAt, updatedAt = 0, updatedBy = organiser,
    )

    private fun answer(invitation: EventInvitation, key: Long, status: InvitationStatus) = EventOccurrenceAnswer(
        stableId = STABLE_ID, eventId = invitation.eventId, invitationId = invitation.id, userId = invitation.userId,
        occurrenceStartAt = key, status = status, respondedAt = 0, updatedAt = 0, updatedBy = invitation.userId,
    )

    private fun removed(event: Event, key: Long, vararg userIds: ObjectId) = EventOccurrence(
        stableId = STABLE_ID, eventId = event.id, occurrenceStartAt = key, cancelled = false, title = null, description = null,
        startAt = null, endAt = null, horseIds = null, updatedAt = 0, updatedBy = organiser, removedUserIds = userIds.toList(),
    )

    /** user to waiting position, 0 for a place, null for declined. */
    private fun seats(placement: LessonPlacement, key: Long?) =
        placement.seatsOn(key)!!.associate { it.invitation.userId to if (it.status == DECLINED) null else it.waitingPosition ?: 0 }

    @Test
    fun `accepted keep their places, open ones fill the rest in invitation order, the others wait`() {
        val event = lesson(maxParticipants = 2)
        val invitations = listOf(invitation(event, anna), invitation(event, ben, ACCEPTED), invitation(event, clara), invitation(event, dora))

        val placement = LessonPlacement(event, invitations, emptyList(), emptyList())

        assertEquals(mapOf(anna to 0, ben to 0, clara to 1, dora to 2), seats(placement, null))
        assertEquals(0, placement.freePlaces(null))
    }

    @Test
    fun `accepted keep places over the limit and nobody pending gets one then`() {
        val event = lesson(maxParticipants = 1)
        val invitations = listOf(invitation(event, anna, ACCEPTED), invitation(event, ben, ACCEPTED), invitation(event, clara))

        assertEquals(mapOf(anna to 0, ben to 0, clara to 1), seats(LessonPlacement(event, invitations, emptyList(), emptyList()), null))
    }

    @Test
    fun `the participant order comes first, invitees missing from it follow in invitation order`() {
        val event = lesson(maxParticipants = 2, order = listOf(dora, ObjectId.get(), clara))
        val invitations = listOf(invitation(event, anna), invitation(event, ben), invitation(event, clara), invitation(event, dora))

        assertEquals(mapOf(dora to 0, clara to 0, anna to 1, ben to 2), seats(LessonPlacement(event, invitations, emptyList(), emptyList()), null))
    }

    @Test
    fun `the organiser taking part and decliners take no place and do not wait`() {
        val event = lesson(maxParticipants = 1)
        val invitations = listOf(invitation(event, organiser, ACCEPTED), invitation(event, anna, DECLINED), invitation(event, ben), invitation(event, clara))

        val placement = LessonPlacement(event, invitations, emptyList(), emptyList())

        assertEquals(mapOf(anna to null, ben to 0, clara to 1), seats(placement, null))
        assertEquals(0, placement.freePlaces(null))
    }

    @Test
    fun `free places count accepted and placed open invitees`() {
        val event = lesson(maxParticipants = 3)
        val invitations = listOf(invitation(event, anna, ACCEPTED), invitation(event, ben), invitation(event, clara, DECLINED))

        assertEquals(1, LessonPlacement(event, invitations, emptyList(), emptyList()).freePlaces(null))
    }

    @Test
    fun `a plain event has no placement`() {
        val event = lesson(maxParticipants = null)

        assertNull(LessonPlacement(event, listOf(invitation(event, anna)), emptyList(), emptyList()).seatsOn(null))
    }

    @Test
    fun `each date of a series is placed on its own answers, removals and date invitations`() {
        val event = lesson(maxParticipants = 2, weekly = true)
        val second = start + week
        val annaSeries = invitation(event, anna)
        val benSeries = invitation(event, ben)
        val claraSeries = invitation(event, clara)
        val doraOnSecond = invitation(event, dora, occurrenceStartAt = second)
        val benOnSecond = invitation(event, ben, ACCEPTED, occurrenceStartAt = second)
        val placement = LessonPlacement(
            event,
            listOf(annaSeries, benSeries, claraSeries, doraOnSecond, benOnSecond),
            listOf(removed(event, second, anna)),
            listOf(answer(annaSeries, start, DECLINED), answer(claraSeries, second, ACCEPTED)),
        )

        assertEquals(mapOf(anna to null, ben to 0, clara to 0), seats(placement, start))
        // Anna is taken off the second date; Ben's date invitation is ignored since he is invited to the series.
        assertEquals(mapOf(ben to 0, clara to 0, dora to 1), seats(placement, second))
        assertEquals(listOf(benSeries.id, claraSeries.id, doraOnSecond.id), placement.seatsOn(second)!!.map { it.invitation.id })
    }

    @Test
    fun `a series invitation without an answer for the date is open there`() {
        val event = lesson(maxParticipants = 1, weekly = true)
        val anna = invitation(event, anna, ACCEPTED)

        assertEquals(PENDING, LessonPlacement(event, listOf(anna), emptyList(), emptyList()).seatsOn(start)!!.single().status)
    }
}
