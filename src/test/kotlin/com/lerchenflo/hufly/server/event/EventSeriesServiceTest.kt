package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceFrequency
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.EventSplit
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.repository.FakeEventInvitationRepository
import com.lerchenflo.hufly.server.repository.FakeEventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.FakeEventOccurrenceRepository
import com.lerchenflo.hufly.server.repository.FakeEventRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.hours
import com.lerchenflo.hufly.server.testdata.millis
import com.lerchenflo.hufly.server.testdata.plusSeconds
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** EVT-10: event series, changes to single dates, per-date answers and invitations to single dates. */
class EventSeriesServiceTest {

    private val published = mutableListOf<Any>()
    private fun announced() = published.filterIsInstance<com.lerchenflo.hufly.server.core.notification.NotificationEvent>()

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val horseRepository = FakeHorseRepository()
    private val eventRepository = FakeEventRepository()
    private val invitationRepository = FakeEventInvitationRepository()
    private val occurrenceRepository = FakeEventOccurrenceRepository()
    private val answerRepository = FakeEventOccurrenceAnswerRepository()
    private val accessService = AccessService(userRepository, stableRepository, tagRepository)
    private val versionCounterService = VersionCounterService(FakeVersionCounterStore())
    private val eventService = EventService(
        eventRepository, invitationRepository, occurrenceRepository, answerRepository, userRepository, horseRepository,
        accessService, versionCounterService, clock, ApplicationEventPublisher { published += it },
    )
    private val service = EventOccurrenceService(
        eventService, eventRepository, invitationRepository, occurrenceRepository, answerRepository, horseRepository,
        accessService, versionCounterService, clock,
    )

    private val admin = testUser()
    private val teacherTag = testTag(permissions = setOf(Permission.EVENT_EDIT))
    private val teacher = testUser(roleTagIds = listOf(teacherTag.id))
    private val otherTeacher = testUser(roleTagIds = listOf(teacherTag.id))
    private val anna = testUser()
    private val ben = testUser()
    private val clara = testUser()
    private val blitz = testHorse()
    private val foreignHorse = testHorse(stableId = OTHER_STABLE_ID)

    // Tuesdays 16:00 UTC, four dates.
    private val first = millis("2026-10-13T16:00:00Z")
    private val second = first + days(7)
    private val weekly = Recurrence(RecurrenceFrequency.WEEKLY, 1, emptyList(), null, 4, "UTC")

    @BeforeTest
    fun setUp() {
        listOf(admin, teacher, otherTeacher, anna, ben, clara).forEach { userRepository.save(it) }
        tagRepository.save(teacherTag)
        listOf(blitz, foreignHorse).forEach { horseRepository.save(it) }
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(days(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private fun series(invitees: List<ObjectId> = listOf(anna.id), recurrence: Recurrence? = weekly) = eventService.createEvent(
        teacher, "Springstunde", "", first, first.plusSeconds(3600), listOf(blitz.id), invitees, recurrence = recurrence,
    )

    private fun invitationOf(event: Event, user: User): EventInvitation =
        invitationRepository.findByEventIdAndDeletedFalse(event.id).single { it.userId == user.id }

    private fun change(
        cancelled: Boolean = false,
        title: String? = null,
        description: String? = null,
        startAt: Long? = null,
        endAt: Long? = null,
        horseIds: List<ObjectId>? = null,
        removedUserIds: List<ObjectId>? = null,
    ) = EventOccurrenceChange(cancelled, title, description, startAt, endAt, horseIds, removedUserIds)

    // Series

    @Test
    fun `a series keeps its rule and an update without one makes it a single event`() {
        val event = series()
        assertEquals(weekly, eventRepository.findById(event.id)!!.recurrence)

        eventService.updateEvent(teacher, event.id, "Springstunde", "", first, first.plusSeconds(3600), listOf(blitz.id))

        assertNull(eventRepository.findById(event.id)!!.recurrence)
    }

    // Changes to single dates

    @Test
    fun `the same change twice keeps one row`() {
        val event = series()

        service.putOccurrence(teacher, event.id, second, change(title = "Dressur"))
        val again = service.putOccurrence(teacher, event.id, second, change(title = "Dressur"))

        assertEquals(listOf(again.id), occurrenceRepository.occurrences.map { it.id })
        assertEquals("Dressur", again.title)
    }

    @Test
    fun `a change replaces the whole previous change`() {
        val event = series()
        service.putOccurrence(teacher, event.id, second, change(title = "Dressur", cancelled = true))

        val restored = service.putOccurrence(teacher, event.id, second, change())

        assertEquals(false, restored.cancelled)
        assertNull(restored.title)
    }

    @Test
    fun `only the creator or the admin change dates`() {
        val event = series()

        service.putOccurrence(admin, event.id, second, change(cancelled = true))
        assertStatus(HttpStatus.FORBIDDEN) { service.putOccurrence(otherTeacher, event.id, second, change()) }
        assertStatus(HttpStatus.FORBIDDEN) { service.putOccurrence(anna, event.id, second, change()) }
        assertStatus(HttpStatus.NOT_FOUND) { service.putOccurrence(teacher, ObjectId.get(), second, change()) }
    }

    @Test
    fun `dates exist only on series and only where the rule produces them`() {
        val single = series(recurrence = null)
        val event = series()

        assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(teacher, single.id, first, change()) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(teacher, event.id, first + days(1), change()) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(teacher, event.id, first + days(28), change()) }
    }

    @Test
    fun `invalid changes answer 400`() {
        val event = series()
        val invalid = listOf(
            change(title = " "),
            change(title = "x".repeat(201)),
            change(description = "x".repeat(5001)),
            change(startAt = second),
            change(endAt = second),
            change(startAt = second, endAt = second),
            change(startAt = second, endAt = 32_503_680_000_001),
            change(horseIds = emptyList()),
            change(horseIds = listOf(foreignHorse.id)),
            change(horseIds = List(51) { blitz.id }),
        )

        invalid.forEach { assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(teacher, event.id, second, it) } }
    }

    // Per-date answers

    @Test
    fun `an invitee answers one date and may change the answer`() {
        val event = series()
        val invitation = invitationOf(event, anna)

        service.answer(anna, invitation.id, second, accepted = false)
        val answer = service.answer(anna, invitation.id, second, accepted = true)

        assertEquals(InvitationStatus.ACCEPTED, answer.status)
        assertEquals(listOf(answer.id), answerRepository.answers.map { it.id })
        assertEquals(InvitationStatus.PENDING, invitationOf(event, anna).status)
    }

    @Test
    fun `answering needs the own invitation to a series and a live date`() {
        val event = series()
        val invitation = invitationOf(event, anna)
        val single = series(recurrence = null)
        service.putOccurrence(teacher, event.id, second, change(cancelled = true))

        assertStatus(HttpStatus.FORBIDDEN) { service.answer(ben, invitation.id, first, true) }
        assertStatus(HttpStatus.NOT_FOUND) { service.answer(anna, ObjectId.get(), first, true) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.answer(anna, invitationOf(single, anna).id, first, true) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.answer(anna, invitation.id, first.plusSeconds(1), true) }
        assertStatus(HttpStatus.CONFLICT) { service.answer(anna, invitation.id, second, true) }
    }

    @Test
    fun `a date invitation is answered with the normal endpoint, not per date`() {
        val event = series()
        val dateInvitation = eventService.invite(teacher, event.id, listOf(clara.id), occurrenceStartAt = second)
            .single { it.userId == clara.id }

        assertStatus(HttpStatus.BAD_REQUEST) { service.answer(clara, dateInvitation.id, second, true) }
        assertEquals(InvitationStatus.ACCEPTED, eventService.respond(clara, dateInvitation.id, true).status)
    }

    @Test
    fun `a series invitation is answered per date only`() {
        val event = series()
        val invitation = invitationOf(event, anna)
        val dateAnswer = service.answer(anna, invitation.id, second, accepted = true)

        assertStatus(HttpStatus.BAD_REQUEST) { eventService.respond(anna, invitation.id, false) }

        assertEquals(invitation, invitationOf(event, anna))
        assertEquals(listOf(dateAnswer), answerRepository.answers)
    }

    @Test
    fun `once the series becomes a single event its invitations are answered as a whole again`() {
        val event = series()
        eventService.updateEvent(teacher, event.id, "Springstunde", "", first, first.plusSeconds(3600), listOf(blitz.id))

        assertEquals(InvitationStatus.ACCEPTED, eventService.respond(anna, invitationOf(event, anna).id, true).status)
    }

    @Test
    fun `another invitee's answer reaches the organiser`() {
        val event = series()

        service.answer(anna, invitationOf(event, anna).id, second, accepted = false)

        val page = service.syncAnswers(teacher, 0, 100)
        assertEquals(listOf("DECLINED"), page.updatedEntries.map { it.status.name })
    }

    // Series invitees taken off one date

    @Test
    fun `the organiser takes series invitees off one date and back on`() {
        val event = series(invitees = listOf(anna.id, ben.id))

        val removed = service.putOccurrence(teacher, event.id, second, change(removedUserIds = listOf(anna.id, anna.id)))
        assertEquals(listOf(anna.id), removed.removedUserIds)
        assertEquals(listOf(anna.id.toHexString()), service.syncOccurrences(anna, 0, 100).updatedEntries.single().removedUserIds)

        val restored = service.putOccurrence(teacher, event.id, second, change(title = "Dressur"))
        assertNull(restored.removedUserIds)
    }

    @Test
    fun `only series invitees and the creator can be taken off a date`() {
        val event = series(invitees = listOf(anna.id))
        eventService.invite(teacher, event.id, listOf(clara.id), occurrenceStartAt = second)

        assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(teacher, event.id, second, change(removedUserIds = listOf(ben.id))) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(teacher, event.id, second, change(removedUserIds = listOf(clara.id))) }
        assertStatus(HttpStatus.BAD_REQUEST) { service.putOccurrence(teacher, event.id, second, change(removedUserIds = listOf(ObjectId.get()))) }
        service.putOccurrence(teacher, event.id, second, change(removedUserIds = listOf(teacher.id)))
    }

    @Test
    fun `a user taken off a date cannot answer it, other dates stay open and old answers are kept`() {
        val event = series(invitees = listOf(anna.id))
        val invitation = invitationOf(event, anna)
        val earlier = service.answer(anna, invitation.id, second, accepted = true)
        published.clear()

        service.putOccurrence(teacher, event.id, second, change(removedUserIds = listOf(anna.id)))

        assertStatus(HttpStatus.BAD_REQUEST) { service.answer(anna, invitation.id, second, accepted = false) }
        assertEquals(listOf(earlier), answerRepository.answers)
        assertTrue(announced().isEmpty())
        service.answer(anna, invitation.id, first, accepted = false)

        service.putOccurrence(teacher, event.id, second, change())
        assertEquals(InvitationStatus.DECLINED, service.answer(anna, invitation.id, second, accepted = false).status)
    }

    // Invitations to single dates

    @Test
    fun `inviting to one date skips series invitees and users already invited to it`() {
        val event = series(invitees = listOf(anna.id))

        eventService.invite(teacher, event.id, listOf(anna.id, ben.id, teacher.id), occurrenceStartAt = second)
        eventService.invite(teacher, event.id, listOf(ben.id), occurrenceStartAt = second)

        val live = invitationRepository.findByEventIdAndDeletedFalse(event.id)
        assertEquals(listOf(anna.id to null, ben.id to second), live.map { it.userId to it.occurrenceStartAt })
    }

    @Test
    fun `a user invited to single dates may also be invited to the series`() {
        val event = series(invitees = emptyList())
        eventService.invite(teacher, event.id, listOf(ben.id), occurrenceStartAt = second)

        eventService.invite(teacher, event.id, listOf(ben.id))

        assertEquals(setOf(second, null), invitationRepository.findByEventIdAndDeletedFalse(event.id).map { it.occurrenceStartAt }.toSet())
    }

    @Test
    fun `date invitations need a series and a date of it`() {
        val single = series(recurrence = null)
        val event = series()

        assertStatus(HttpStatus.BAD_REQUEST) { eventService.invite(teacher, single.id, listOf(ben.id), occurrenceStartAt = first) }
        assertStatus(HttpStatus.BAD_REQUEST) { eventService.invite(teacher, event.id, listOf(ben.id), occurrenceStartAt = first.plusSeconds(60)) }
    }

    @Test
    fun `a date invitation shows the series and its date changes to the invitee`() {
        val event = series(invitees = emptyList())
        service.putOccurrence(teacher, event.id, second, change(title = "Dressur"))
        assertTrue(service.syncOccurrences(clara, 0, 100).updatedEntries.isEmpty())

        eventService.invite(teacher, event.id, listOf(clara.id), occurrenceStartAt = second)

        assertEquals(listOf(event.id.toHexString()), eventService.syncEvents(clara, 0, 100).updatedEntries.map { it.id })
        assertEquals(listOf("Dressur"), service.syncOccurrences(clara, 0, 100).updatedEntries.map { it.title })
    }

    @Test
    fun `a new invitee pulls existing date changes and answers from where they synced before`() {
        val event = series(invitees = listOf(anna.id))
        service.putOccurrence(teacher, event.id, second, change(title = "Dressur"))
        service.answer(anna, invitationOf(event, anna).id, second, accepted = true)
        val occurrencesBefore = service.syncOccurrences(ben, 0, 100).newVersion
        val answersBefore = service.syncAnswers(ben, 0, 100).newVersion

        eventService.invite(teacher, event.id, listOf(ben.id))

        assertEquals(1, service.syncOccurrences(ben, occurrencesBefore, 100).updatedEntries.size)
        assertEquals(1, service.syncAnswers(ben, answersBefore, 100).updatedEntries.size)
    }

    // Cascades

    @Test
    fun `deleting the series delivers tombstones for its dates and answers`() {
        val event = series()
        val occurrence = service.putOccurrence(teacher, event.id, second, change(title = "Dressur"))
        val answer = service.answer(anna, invitationOf(event, anna).id, second, accepted = true)
        val occurrencesBefore = service.syncOccurrences(teacher, 0, 100).newVersion
        val answersBefore = service.syncAnswers(teacher, 0, 100).newVersion

        eventService.deleteEvent(teacher, event.id)

        assertEquals(listOf(occurrence.id.toHexString()), service.syncOccurrences(teacher, occurrencesBefore, 100).deletedEntries)
        assertEquals(listOf(answer.id.toHexString()), service.syncAnswers(teacher, answersBefore, 100).deletedEntries)
    }

    @Test
    fun `removing an invitation removes its answers`() {
        val event = series()
        val invitation = invitationOf(event, anna)
        service.answer(anna, invitation.id, second, accepted = true)

        eventService.removeInvitation(teacher, invitation.id)

        assertTrue(answerRepository.findByInvitationIdAndDeletedFalse(invitation.id).isEmpty())
    }

    @Test
    fun `members who do not see the event get its dates as deleted`() {
        val event = series(invitees = listOf(anna.id))
        val occurrence = service.putOccurrence(teacher, event.id, second, change(title = "Dressur"))

        assertEquals(1, service.syncOccurrences(anna, 0, 100).updatedEntries.size)
        assertEquals(listOf(occurrence.id.toHexString()), service.syncOccurrences(ben, 0, 100).deletedEntries)
    }

    @Test
    fun `inviting to one date and answering a date announce that date`() {
        val event = series()
        eventService.invite(teacher, event.id, listOf(clara.id), occurrenceStartAt = second)
        val invitation = invitationOf(event, anna)
        service.answer(anna, invitation.id, second, accepted = false)
        service.answer(anna, invitation.id, second, accepted = false)

        assertEquals(
            listOf(
                com.lerchenflo.hufly.server.core.notification.EventInvited(STABLE_ID, teacher.id, event.id, listOf(clara.id), second),
                com.lerchenflo.hufly.server.core.notification.InvitationAnswered(STABLE_ID, anna.id, event.id, false, second),
            ),
            announced().drop(1),
        )
    }

    // The organiser takes part

    @Test
    fun `the organiser takes part in a whole series, not in single dates`() {
        val event = series(invitees = listOf(anna.id))

        eventService.invite(teacher, event.id, listOf(teacher.id), occurrenceStartAt = second)
        assertTrue(invitationRepository.findByEventIdAndDeletedFalse(event.id).none { it.userId == teacher.id })

        eventService.invite(teacher, event.id, listOf(teacher.id))
        assertEquals(InvitationStatus.ACCEPTED, invitationOf(event, teacher).status)
    }

    @Test
    fun `the organiser declines and re-accepts single dates and notifies nobody`() {
        val event = series(invitees = listOf(anna.id))
        eventService.invite(teacher, event.id, listOf(teacher.id))
        val own = invitationOf(event, teacher)
        published.clear()

        assertEquals(InvitationStatus.DECLINED, service.answer(teacher, own.id, second, accepted = false).status)
        assertEquals(InvitationStatus.ACCEPTED, service.answer(teacher, own.id, second, accepted = true).status)
        assertStatus(HttpStatus.BAD_REQUEST) { eventService.respond(teacher, own.id, false) }

        assertEquals(emptyList(), announced())
    }

    // "Diesen und alle folgenden": a split series takes over invitees' answers

    private val third = second + days(7)

    private fun splitOff(by: User = teacher, from: Event, at: Long = second, invitees: List<ObjectId> = emptyList()) =
        eventService.createEvent(
            by, "Springstunde neu", "", at, at.plusSeconds(3600), listOf(blitz.id), invitees,
            recurrence = weekly, splitFrom = EventSplit(from.id, at),
        )

    @Test
    fun `a split series stores where it came from`() {
        val old = series()

        val split = splitOff(from = old)

        assertEquals(old.id, split.splitFromEventId)
        assertEquals(second, split.splitFromOccurrenceStartAt)
    }

    @Test
    fun `a split needs a series of the own stable by the same creator or the admin`() {
        val old = series()
        val single = series(recurrence = null)
        val othersSeries = eventService.createEvent(
            otherTeacher, "X", "", first, first.plusSeconds(3600), listOf(blitz.id), emptyList(), recurrence = weekly,
        )

        assertStatus(HttpStatus.BAD_REQUEST) { splitOff(from = single) }
        assertStatus(HttpStatus.BAD_REQUEST) { splitOff(from = othersSeries) }
        assertStatus(HttpStatus.BAD_REQUEST) {
            eventService.createEvent(
                teacher, "X", "", second, second.plusSeconds(3600), listOf(blitz.id), emptyList(),
                recurrence = weekly, splitFrom = EventSplit(ObjectId.get(), second),
            )
        }
        eventService.deleteEvent(teacher, old.id)
        assertStatus(HttpStatus.BAD_REQUEST) { splitOff(from = old) }

        val adminSplit = splitOff(by = admin, from = othersSeries)
        assertEquals(othersSeries.id, adminSplit.splitFromEventId)
    }

    @Test
    fun `invitees take their answers from the split date on over to the new series without pushes`() {
        val old = series(invitees = listOf(anna.id))
        val oldInvitation = invitationOf(old, anna)
        service.answer(anna, oldInvitation.id, first, accepted = false)
        val secondAnswer = service.answer(anna, oldInvitation.id, second, accepted = false)
        clock.advance(hours(1))
        service.answer(anna, oldInvitation.id, third, accepted = true)
        val split = splitOff(from = old)
        published.clear()

        eventService.invite(teacher, split.id, listOf(anna.id, ben.id))

        val newInvitation = invitationOf(split, anna)
        val copied = answerRepository.findByInvitationIdAndDeletedFalse(newInvitation.id).sortedBy { it.occurrenceStartAt }
        assertEquals(listOf(second, third), copied.map { it.occurrenceStartAt })
        assertEquals(listOf(InvitationStatus.DECLINED, InvitationStatus.ACCEPTED), copied.map { it.status })
        assertEquals(secondAnswer.respondedAt, copied[0].respondedAt)
        assertEquals(split.id, copied[0].eventId)
        assertTrue(copied.all { it.version > 0 })
        assertEquals(
            listOf(com.lerchenflo.hufly.server.core.notification.EventInvited(STABLE_ID, teacher.id, split.id, listOf(ben.id), null)),
            announced(),
        )
    }

    @Test
    fun `invitees given on the split create also take their answers over`() {
        val old = series(invitees = listOf(anna.id))
        service.answer(anna, invitationOf(old, anna).id, second, accepted = false)
        published.clear()

        val split = splitOff(from = old, invitees = listOf(anna.id))

        val copied = answerRepository.findByInvitationIdAndDeletedFalse(invitationOf(split, anna).id)
        assertEquals(listOf(InvitationStatus.DECLINED), copied.map { it.status })
        assertEquals(emptyList(), announced())
    }

    @Test
    fun `a stand-in takes the answer to their date over`() {
        val old = series(invitees = listOf(anna.id))
        val oldDate = eventService.invite(teacher, old.id, listOf(clara.id), occurrenceStartAt = third).single { it.userId == clara.id }
        val answered = eventService.respond(clara, oldDate.id, true)
        val split = splitOff(from = old)
        published.clear()

        val newDate = eventService.invite(teacher, split.id, listOf(clara.id), occurrenceStartAt = third).single { it.userId == clara.id }

        assertEquals(InvitationStatus.ACCEPTED, newDate.status)
        assertEquals(answered.respondedAt, newDate.respondedAt)
        assertEquals(emptyList(), announced())
    }
}
