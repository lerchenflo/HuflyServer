package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.notification.EventInvited
import com.lerchenflo.hufly.server.core.notification.LessonPlaceOffered
import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceFrequency
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.event.model.LessonFields
import com.lerchenflo.hufly.server.repository.FakeEventInvitationRepository
import com.lerchenflo.hufly.server.repository.FakeEventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.FakeEventOccurrenceRepository
import com.lerchenflo.hufly.server.repository.FakeEventRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeLessonLockRepository
import com.lerchenflo.hufly.server.repository.FakeLessonOfferRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.testdata.STABLE_ID
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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
    private val offerRepository = FakeLessonOfferRepository()
    private val publisher = ApplicationEventPublisher { published += it }
    private val lessons = LessonService(eventRepository, invitationRepository, occurrenceRepository, answerRepository, offerRepository, LessonLocks(FakeLessonLockRepository(), clock), clock, publisher)
    private val eventService = EventService(
        eventRepository, invitationRepository, occurrenceRepository, answerRepository, userRepository, horseRepository, tagRepository,
        accessService, versionCounterService, clock, publisher, lessons,
    )
    private val timeouts = LessonOfferTimeouts(offerRepository, eventRepository, lessons, eventService, clock)
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
    private val dora = testUser()
    private val blitz = testHorse()

    // Clock: 2026-10-03T10:00Z. Single lessons and the first date of a series start on 2026-10-13 16:00Z.
    private val start = millis("2026-10-13T16:00:00Z")
    private val second = start + days(7)
    private val third = second + days(7)
    private val fourth = third + days(7)
    private val weekly = Recurrence(RecurrenceFrequency.WEEKLY, 1, emptyList(), null, 4, "UTC")

    @BeforeTest
    fun setUp() {
        listOf(admin, teacher, anna, ben, clara, dora).forEach { userRepository.save(it) }
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
        answerHours: Int? = null,
    ) = eventService.createEvent(
        teacher, "Springstunde", "", start, start + hours(1), listOf(blitz.id), invitees.map { it.id }, recurrence = recurrence,
        lesson = fields(maxParticipants, leaveLockHours, answerHours, invitees),
    )

    private fun fields(maxParticipants: Int, leaveLockHours: Int? = null, answerHours: Int? = null, order: List<User> = listOf(anna, ben, clara, dora)) =
        LessonFields(maxParticipants = maxParticipants, leaveLockHours = leaveLockHours, answerHours = answerHours, participantOrder = order.map { it.id })

    private fun update(event: Event, lesson: LessonFields) =
        eventService.updateEvent(teacher, event.id, event.title, event.description, event.startAt, event.endAt, event.horseIds, event.recurrence, lesson)

    private fun offered() = published.filterIsInstance<LessonPlaceOffered>()

    private fun offer(event: Event, by: User, user: User, key: Long? = null) = LessonPlaceOffered(STABLE_ID, by.id, event.id, listOf(user.id), key)

    private fun offers() = offerRepository.offers.map { it.userId to it.occurrenceStartAt }

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

    // Moving up

    @Test
    fun `a decline offers the place to the first one waiting`() {
        val event = lesson(invitees = listOf(anna, ben, clara, dora))
        published.clear()

        respond(event, anna, accepted = false)

        assertEquals(listOf(offer(event, anna, clara)), offered())
        assertEquals(listOf(clara.id to clock.millis()), offerRepository.offers.map { it.userId to it.offeredAt })
        assertEquals(InvitationStatus.ACCEPTED, respond(event, clara, accepted = true).status)
    }

    @Test
    fun `inviting pushes only invitees with a place and offers nothing`() {
        val event = lesson()
        eventService.invite(teacher, event.id, listOf(dora.id))

        assertEquals(listOf(listOf(anna.id, ben.id)), published.filterIsInstance<EventInvited>().map { it.userIds })
        assertEquals(emptyList(), offers())
    }

    @Test
    fun `moving or deleting a lesson tells only who accepted, never who waits`() {
        val event = lesson()
        respond(event, anna, accepted = true)
        published.clear()

        update(event.copy(startAt = start + hours(1), endAt = start + hours(2)), fields(maxParticipants = 2, order = listOf(anna, ben, clara)))
        eventService.deleteEvent(teacher, event.id)

        assertEquals(listOf(listOf(anna.id), listOf(anna.id)), published.filterIsInstance<com.lerchenflo.hufly.server.core.notification.EventChanged>().map { it.userIds })
    }

    @Test
    fun `removing an invitation offers its place`() {
        val event = lesson()
        published.clear()

        eventService.removeInvitation(teacher, invitationOf(event, anna).id)

        assertEquals(listOf(offer(event, teacher, clara)), offered())
    }

    @Test
    fun `more places offer them to the ones waiting, in order`() {
        val event = lesson(invitees = listOf(anna, ben, clara, dora))
        published.clear()

        update(event, fields(maxParticipants = 3))

        assertEquals(listOf(offer(event, teacher, clara)), offered())
    }

    @Test
    fun `a new order offers places to who moves ahead, who falls back just waits`() {
        val event = lesson()
        published.clear()

        update(event, fields(maxParticipants = 2, order = listOf(clara, anna, ben)))

        assertEquals(listOf(offer(event, teacher, clara)), offered())
        assertRefused("Lesson: no free place") { respond(event, ben, accepted = true) }
    }

    @Test
    fun `a decline on one date of a series offers the place on that date only`() {
        val event = lesson(recurrence = weekly)
        published.clear()

        answerDate(event, anna, second, accepted = false)

        assertEquals(listOf(offer(event, anna, clara, second)), offered())
        assertEquals(listOf(clara.id to second), offers())
    }

    @Test
    fun `taking someone off a date offers their place on that date`() {
        val event = lesson(recurrence = weekly)
        published.clear()

        occurrenceService.putOccurrence(teacher, event.id, third, EventOccurrenceChange(false, null, null, null, null, null, removedUserIds = listOf(anna.id)))

        assertEquals(listOf(offer(event, teacher, clara, third)), offered())
    }

    @Test
    fun `more places on a series offer every upcoming date but push once, for the earliest`() {
        val event = lesson(recurrence = weekly)
        clock.advance(start - clock.millis())
        published.clear()

        update(event, fields(maxParticipants = 3))

        assertEquals(listOf(offer(event, teacher, clara, second)), offered())
        assertEquals(listOf(clara.id to second, clara.id to third, clara.id to fourth), offers())
    }

    @Test
    fun `a date that started is left alone`() {
        val event = lesson()
        clock.advance(start - clock.millis())
        published.clear()

        respond(event, anna, accepted = false)

        assertEquals(emptyList(), offered())
        assertEquals(emptyList(), offers())
    }

    // Answer time

    @Test
    fun `an offer left open for answerHours is declined and offered to the next one`() {
        val event = lesson(invitees = listOf(anna, ben, clara, dora), answerHours = 12)
        respond(event, anna, accepted = false)
        published.clear()

        clock.advance(hours(12) - 1)
        timeouts.expireOffers()
        assertEquals(InvitationStatus.PENDING, invitationOf(event, clara).status)

        clock.advance(1)
        timeouts.expireOffers()

        val clarasInvitation = invitationOf(event, clara)
        assertEquals(listOf(InvitationStatus.DECLINED, clock.millis(), clara.id), listOf(clarasInvitation.status, clarasInvitation.respondedAt, clarasInvitation.updatedBy))
        assertEquals(listOf(offer(event, clara, dora)), offered())
        assertEquals(listOf(dora.id to null), offers())
    }

    @Test
    fun `an open offer on a series date is declined for that date`() {
        val event = lesson(recurrence = weekly, invitees = listOf(anna, ben, clara, dora), answerHours = 12)
        answerDate(event, anna, second, accepted = false)
        published.clear()

        clock.advance(hours(12))
        timeouts.expireOffers()

        val answer = answerRepository.findByInvitationIdAndOccurrenceStartAt(invitationOf(event, clara).id, second)!!
        assertEquals(listOf(InvitationStatus.DECLINED, clock.millis(), clara.id), listOf(answer.status, answer.respondedAt, answer.updatedBy))
        assertEquals(InvitationStatus.PENDING, invitationOf(event, clara).status)
        assertEquals(listOf(offer(event, clara, dora, second)), offered())
    }

    @Test
    fun `an answered offer is dropped`() {
        val event = lesson(answerHours = 12)
        respond(event, anna, accepted = false)
        respond(event, clara, accepted = true)

        clock.advance(hours(12))
        timeouts.expireOffers()

        assertEquals(InvitationStatus.ACCEPTED, invitationOf(event, clara).status)
        assertEquals(emptyList(), offers())
    }

    @Test
    fun `without answerHours an offer stays open until the start and then ends without an answer`() {
        val event = lesson()
        respond(event, anna, accepted = false)

        clock.advance(start - 1 - clock.millis())
        timeouts.expireOffers()
        assertEquals(listOf(clara.id to null), offers())

        clock.advance(1)
        timeouts.expireOffers()
        assertEquals(emptyList(), offers())
        assertEquals(InvitationStatus.PENDING, invitationOf(event, clara).status)
    }

    @Test
    fun `with answerHours the start ends an open offer as declined`() {
        val event = lesson(answerHours = 336)
        respond(event, anna, accepted = false)

        clock.advance(start - clock.millis())
        timeouts.expireOffers()

        assertEquals(InvitationStatus.DECLINED, invitationOf(event, clara).status)
        assertEquals(emptyList(), offers())
    }

    @Test
    fun `an offer ends without an answer when its invitation or event is gone`() {
        val removed = lesson(answerHours = 1)
        respond(removed, anna, accepted = false)
        eventService.removeInvitation(teacher, invitationOf(removed, clara).id)
        val deleted = lesson(answerHours = 1)
        respond(deleted, anna, accepted = false)
        eventService.deleteEvent(teacher, deleted.id)

        clock.advance(hours(1))
        timeouts.expireOffers()

        assertEquals(emptyList(), offers())
    }

    @Test
    fun `an offer ends without an answer when its user falls back to waiting`() {
        val event = lesson(answerHours = 1)
        respond(event, anna, accepted = false)
        update(event, fields(maxParticipants = 1, answerHours = 1))

        clock.advance(hours(1))
        timeouts.expireOffers()

        assertEquals(emptyList(), offers())
        assertEquals(InvitationStatus.PENDING, invitationOf(event, clara).status)
    }

    // At the same moment

    /**
     * Runs [first] on its own thread and holds it at its first save, after its checks; runs [second] meanwhile and lets
     * [first] go once [second] is done, or after a moment if [second] waits for [first].
     */
    private fun race(first: () -> Any, second: () -> Any): Pair<Result<Any>, Result<Any>> {
        val firstSaving = CountDownLatch(1)
        val secondDone = CountDownLatch(1)
        val hold = {
            if (Thread.currentThread().name == "first" && firstSaving.count > 0) {
                firstSaving.countDown()
                secondDone.await(300, TimeUnit.MILLISECONDS)
            }
        }
        invitationRepository.beforeSave = hold
        answerRepository.beforeSave = hold
        var firstResult: Result<Any>? = null
        val firstThread = thread(name = "first") { firstResult = runCatching(first) }
        assertTrue(firstSaving.await(5, TimeUnit.SECONDS))
        val secondResult = runCatching(second)
        secondDone.countDown()
        firstThread.join()
        return firstResult!! to secondResult
    }

    @Test
    fun `two taking the last free place at the same moment, one gets it and the other is told no`() {
        val event = lesson(maxParticipants = 1, invitees = listOf(anna, ben))
        respond(event, anna, accepted = false)
        respond(event, ben, accepted = false)

        val (won, lost) = race({ respond(event, anna, accepted = true) }, { respond(event, ben, accepted = true) })

        assertEquals(InvitationStatus.ACCEPTED, (won.getOrThrow() as EventInvitation).status)
        assertRefused("Lesson: no free place") { lost.getOrThrow() }
        assertEquals(InvitationStatus.DECLINED, invitationOf(event, ben).status)
    }

    @Test
    fun `two taking the last free place on a series date at the same moment, one gets it and the other is told no`() {
        val event = lesson(maxParticipants = 1, recurrence = weekly, invitees = listOf(anna, ben))
        answerDate(event, anna, second, accepted = false)
        answerDate(event, ben, second, accepted = false)

        val (won, lost) = race({ answerDate(event, anna, second, accepted = true) }, { answerDate(event, ben, second, accepted = true) })

        assertEquals(InvitationStatus.ACCEPTED, (won.getOrThrow() as EventOccurrenceAnswer).status)
        assertRefused("Lesson: no free place") { lost.getOrThrow() }
        assertEquals(InvitationStatus.DECLINED, answerRepository.findByInvitationIdAndOccurrenceStartAt(invitationOf(event, ben).id, second)!!.status)
    }

    @Test
    fun `an offer accepted while it times out stays accepted`() {
        val event = lesson(maxParticipants = 1, invitees = listOf(anna, ben), answerHours = 12)
        respond(event, anna, accepted = false)
        val offered = invitationOf(event, ben)
        respond(event, ben, accepted = true)

        eventService.declineUnanswered(event, offered, null)

        assertEquals(InvitationStatus.ACCEPTED, invitationOf(event, ben).status)
    }

    @Test
    fun `an offer whose user fell back to waiting while it times out is not declined`() {
        val event = lesson(answerHours = 12)
        respond(event, anna, accepted = false)
        val offered = invitationOf(event, clara)
        update(event, fields(maxParticipants = 1, answerHours = 12))

        eventService.declineUnanswered(event, offered, null)

        assertEquals(InvitationStatus.PENDING, invitationOf(event, clara).status)
    }

    // Members who leave

    @Test
    fun `a member who leaves loses every invitation and answer, and the next one waiting moves up`() {
        val single = lesson()
        respond(single, anna, accepted = true)
        val series = lesson(recurrence = weekly)
        answerDate(series, anna, second, accepted = true)
        published.clear()

        eventService.removeMember(anna.id, admin.id)

        assertEquals(emptyList(), invitationRepository.invitations.filter { it.userId == anna.id && !it.deleted })
        assertEquals(emptyList(), answerRepository.answers.filter { it.userId == anna.id && !it.deleted })
        assertEquals(listOf(single.id, series.id), offered().map { it.eventId })
        assertEquals(setOf(clara.id), offered().flatMap { it.userIds }.toSet())
        assertEquals(emptyList(), published.filterIsInstance<com.lerchenflo.hufly.server.core.notification.EventChanged>())
    }
}
