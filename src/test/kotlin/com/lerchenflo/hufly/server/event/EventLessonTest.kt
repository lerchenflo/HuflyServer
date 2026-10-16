package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceFrequency
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.event.model.LessonFields
import com.lerchenflo.hufly.server.repository.FakeEventInvitationRepository
import com.lerchenflo.hufly.server.repository.FakeEventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.FakeEventOccurrenceRepository
import com.lerchenflo.hufly.server.repository.FakeEventRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.hours
import com.lerchenflo.hufly.server.testdata.millis
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import com.lerchenflo.hufly.server.user.model.User
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Lessons with places and a waiting list (client schema 37; supersede EVT-7). */
class EventLessonTest {

    private val published = mutableListOf<Any>()

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val horseRepository = FakeHorseRepository()
    private val eventRepository = FakeEventRepository()
    private val invitationRepository = FakeEventInvitationRepository()
    private val occurrenceRepository = FakeEventOccurrenceRepository()
    private val answerRepository = FakeEventOccurrenceAnswerRepository()
    private val accessService = AccessService(userRepository, userRepository.accounts, stableRepository, tagRepository)
    private val versionCounterService = VersionCounterService(FakeVersionCounterStore())
    private val lessons = LessonService(invitationRepository, occurrenceRepository, answerRepository, clock)
    private val eventService = EventService(
        eventRepository, invitationRepository, occurrenceRepository, answerRepository, userRepository, horseRepository, tagRepository,
        accessService, versionCounterService, clock, ApplicationEventPublisher { published += it }, lessons,
    )
    private val occurrenceService = EventOccurrenceService(
        eventService, eventRepository, invitationRepository, occurrenceRepository, answerRepository, horseRepository,
        accessService, versionCounterService, clock, lessons,
    )

    private val admin = testUser()
    private val teacherTag = testTag(permissions = setOf(Permission.EVENT_EDIT))
    private val teacher = testUser(roleTagIds = listOf(teacherTag.id))
    private val anna = testUser()
    private val ben = testUser()
    private val clara = testUser()
    private val blitz = testHorse()

    // Clock: 2026-10-03T10:00Z. Single lessons and the first date of a series start on 2026-10-13 16:00Z.
    private val start = millis("2026-10-13T16:00:00Z")
    private val second = start + days(7)
    private val weekly = Recurrence(RecurrenceFrequency.WEEKLY, 1, emptyList(), null, 4, "UTC")

    @BeforeTest
    fun setUp() {
        listOf(admin, teacher, anna, ben, clara).forEach { userRepository.save(it) }
        tagRepository.save(teacherTag)
        horseRepository.save(blitz)
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(days(1))
    }

    private fun lesson(
        maxParticipants: Int = 2,
        leaveLockHours: Int? = null,
        recurrence: Recurrence? = null,
        invitees: List<User> = listOf(anna, ben, clara),
    ) = eventService.createEvent(
        teacher, "Springstunde", "", start, start + hours(1), listOf(blitz.id), invitees.map { it.id }, recurrence = recurrence,
        lesson = LessonFields(maxParticipants = maxParticipants, leaveLockHours = leaveLockHours, participantOrder = invitees.map { it.id }),
    )

    private fun invitationOf(event: Event, user: User): EventInvitation =
        invitationRepository.findByEventIdAndDeletedFalse(event.id).single { it.userId == user.id }

    private fun respond(event: Event, user: User, accepted: Boolean) = eventService.respond(user, invitationOf(event, user).id, accepted)

    private fun answerDate(event: Event, user: User, key: Long, accepted: Boolean) =
        occurrenceService.answer(user, invitationOf(event, user).id, key, accepted)

    private fun assertRefused(reason: String, block: () -> Unit) {
        val e = assertFailsWith<ResponseStatusException> { block() }
        assertEquals(HttpStatus.CONFLICT to reason, e.statusCode to e.reason)
    }

    // Answers

    @Test
    fun `someone on the waiting list cannot accept, someone with a place can`() {
        val event = lesson()

        assertRefused("Lesson: no free place") { respond(event, clara, accepted = true) }
        assertEquals(InvitationStatus.ACCEPTED, respond(event, ben, accepted = true).status)
    }

    @Test
    fun `a decliner comes back only into a free place`() {
        val event = lesson()
        respond(event, anna, accepted = false)

        assertRefused("Lesson: no free place") { respond(event, anna, accepted = true) }

        respond(event, clara, accepted = false)
        assertEquals(InvitationStatus.ACCEPTED, respond(event, anna, accepted = true).status)
    }

    @Test
    fun `accepted participants cannot leave from leaveLockHours before the start, open ones always can`() {
        val event = lesson(maxParticipants = 3, leaveLockHours = 24)
        respond(event, anna, accepted = true)
        respond(event, ben, accepted = true)

        clock.advance(start - hours(24) - 1 - clock.millis())
        respond(event, anna, accepted = false)

        clock.advance(1)
        assertRefused("Lesson: leaving is locked") { respond(event, ben, accepted = false) }
        assertEquals(InvitationStatus.DECLINED, respond(event, clara, accepted = false).status)
    }

    @Test
    fun `without a leave lock accepted participants leave until the end`() {
        val event = lesson(leaveLockHours = 0)
        respond(event, anna, accepted = true)
        clock.advance(start - clock.millis())

        assertEquals(InvitationStatus.DECLINED, respond(event, anna, accepted = false).status)
    }

    @Test
    fun `the organiser removes an accepted participant inside the leave lock`() {
        val event = lesson(leaveLockHours = 24)
        respond(event, anna, accepted = true)
        clock.advance(start - clock.millis())

        eventService.removeInvitation(teacher, invitationOf(event, anna).id)

        assertEquals(listOf(ben.id, clara.id), invitationRepository.findByEventIdAndDeletedFalse(event.id).map { it.userId })
    }

    @Test
    fun `each date of a series has its own places and waiting list`() {
        val event = lesson(recurrence = weekly)
        answerDate(event, anna, start, accepted = false)

        assertEquals(InvitationStatus.ACCEPTED, answerDate(event, clara, start, accepted = true).status)
        assertRefused("Lesson: no free place") { answerDate(event, clara, second, accepted = true) }
    }

    @Test
    fun `the leave lock of a moved date counts from its own start`() {
        val event = lesson(leaveLockHours = 24, recurrence = weekly)
        answerDate(event, anna, second, accepted = true)
        occurrenceService.putOccurrence(teacher, event.id, second, EventOccurrenceChange(false, null, null, start + days(2), start + days(2) + hours(1), null))

        clock.advance(start + days(1) - clock.millis())
        assertRefused("Lesson: leaving is locked") { answerDate(event, anna, second, accepted = false) }
    }

    @Test
    fun `a date invitation of a lesson series follows the date's places`() {
        val event = lesson(maxParticipants = 1, recurrence = weekly, invitees = listOf(anna))
        eventService.invite(teacher, event.id, listOf(ben.id), occurrenceStartAt = second)
        val benOnSecond = invitationRepository.findByEventIdAndDeletedFalse(event.id).single { it.userId == ben.id }

        assertRefused("Lesson: no free place") { eventService.respond(ben, benOnSecond.id, accepted = true) }
        answerDate(event, anna, second, accepted = false)
        assertEquals(InvitationStatus.ACCEPTED, eventService.respond(ben, benOnSecond.id, accepted = true).status)
    }
}
