package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.absence.model.Absence
import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceFrequency
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import com.lerchenflo.hufly.server.repository.FakeAbsenceRepository
import com.lerchenflo.hufly.server.repository.FakeEventInvitationRepository
import com.lerchenflo.hufly.server.repository.FakeEventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.FakeEventOccurrenceRepository
import com.lerchenflo.hufly.server.repository.FakeEventRepository
import com.lerchenflo.hufly.server.repository.FakeRefreshTokenRepository
import com.lerchenflo.hufly.server.repository.FakeSentEventReminderRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.repository.FakeUserSettingsRepository
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.epochDay
import com.lerchenflo.hufly.server.testdata.hours
import com.lerchenflo.hufly.server.testdata.millis
import com.lerchenflo.hufly.server.testdata.minutes
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testUser
import com.lerchenflo.hufly.server.user.model.User
import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId
import java.util.concurrent.Executor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Event reminders (decided 2026-10-14): `eventReminderMinutes` before the start, to who accepted, once per user and start. */
class EventReminderServiceTest {

    // Friday 9 October 2026, 16:00 in Vienna (CEST).
    private val clock = MutableClock(millis("2026-10-09T14:00:00Z"))
    private val userRepository = FakeUserRepository()
    private val settingsRepository = FakeUserSettingsRepository()
    private val sessions = FakeRefreshTokenRepository()
    private val eventRepository = FakeEventRepository()
    private val invitationRepository = FakeEventInvitationRepository()
    private val occurrenceRepository = FakeEventOccurrenceRepository()
    private val answerRepository = FakeEventOccurrenceAnswerRepository()
    private val absenceRepository = FakeAbsenceRepository()
    private val sentReminders = FakeSentEventReminderRepository()
    private val stableRepository = FakeStableRepository()
    private val android = FakePushSender(PushPlatform.ANDROID)
    private val pushService = PushService(sessions, listOf(android), Executor { it.run() }, clock)
    private val service = EventReminderService(
        eventRepository, invitationRepository, occurrenceRepository, answerRepository, sentReminders,
        Reminders(userRepository, settingsRepository, absenceRepository, pushService, PushTitles(userRepository, stableRepository)), clock,
    )

    private val anna = testUser(email = "anna@hufly.test")
    private val ben = testUser(email = "ben@hufly.test")
    private val clara = testUser(email = "clara@hufly.test")
    private val dora = testUser(email = "dora@hufly.test")
    private val gone = testUser(email = "gone@hufly.test", deleted = true)
    private val foreigner = testUser(email = "foreign@hufly.test", stableId = OTHER_STABLE_ID)

    // 17:00 in Vienna, one hour from now.
    private val inAnHour = millis("2026-10-09T15:00:00Z")
    private val daily = Recurrence(RecurrenceFrequency.DAILY, 1, emptyList(), null, null, "Europe/Vienna")

    @BeforeTest
    fun setUp() {
        listOf(anna, ben, clara, dora, gone, foreigner).forEach {
            userRepository.save(it)
            val session = sessions.save(
                RefreshToken(userId = it.id, hashedToken = ObjectId.get().toHexString(), expiresAt = clock.millis() + days(30), createdAt = clock.millis())
            )
            pushService.register(it.id, session.id, PushPlatform.ANDROID, "token-${it.displayName}")
        }
    }

    private fun sentTo(user: User): List<PushMessage> = android.sent.filter { it.first == "token-${user.displayName}" }.map { it.second }

    private fun settings(user: User, vararg values: Pair<String, String>) =
        settingsRepository.save(UserSettings(user.accountId, mapOf(*values), clock.millis()))

    private fun event(startAt: Long = inAnHour, recurrence: Recurrence? = null) = eventRepository.save(
        Event(stableId = STABLE_ID, creatorUserId = anna.id, title = "Springstunde", description = "", startAt = startAt,
            endAt = startAt + hours(1), horseIds = emptyList(), recurrence = recurrence, createdAt = clock.millis(),
            updatedAt = clock.millis(), updatedBy = anna.id)
    )

    private fun invite(event: Event, user: User, status: InvitationStatus = InvitationStatus.ACCEPTED, occurrenceStartAt: Long? = null) =
        invitationRepository.save(
            EventInvitation(stableId = STABLE_ID, eventId = event.id, userId = user.id, status = status, invitedAt = clock.millis(),
                respondedAt = null, occurrenceStartAt = occurrenceStartAt, updatedAt = clock.millis(), updatedBy = anna.id)
        )

    private fun answer(invitation: EventInvitation, key: Long, status: InvitationStatus = InvitationStatus.ACCEPTED) = answerRepository.save(
        EventOccurrenceAnswer(stableId = STABLE_ID, eventId = invitation.eventId, invitationId = invitation.id, userId = invitation.userId,
            occurrenceStartAt = key, status = status, respondedAt = clock.millis(), updatedAt = clock.millis(), updatedBy = invitation.userId)
    )

    private fun occurrence(event: Event, key: Long, change: (EventOccurrence) -> EventOccurrence) = occurrenceRepository.save(
        change(EventOccurrence(stableId = STABLE_ID, eventId = event.id, occurrenceStartAt = key, cancelled = false, title = null,
            description = null, startAt = null, endAt = null, horseIds = null, updatedAt = clock.millis(), updatedBy = anna.id))
    )

    private fun reminder(event: Event, title: String = event.title, occurrenceAt: Long? = null) = PushMessage(
        "Erinnerung: $title",
        "Beginn Fr. 09.10. 17:00",
        buildMap {
            put("type", "event_reminder")
            put("eventId", event.id.toHexString())
            if (occurrenceAt != null) put("occurrenceAt", occurrenceAt.toString())
            put("stableId", STABLE_ID.toHexString())
        },
    )

    @Test
    fun `an event reminds the invitees who accepted an hour before it starts, once`() {
        val event = event()
        invite(event, ben)
        invite(event, clara, InvitationStatus.PENDING)
        invite(event, dora, InvitationStatus.DECLINED)

        service.sendDue()
        clock.advance(minutes(1))
        service.sendDue()

        assertEquals(listOf(reminder(event)), sentTo(ben))
        assertTrue(sentTo(clara).isEmpty() && sentTo(dora).isEmpty() && sentTo(anna).isEmpty())
    }

    @Test
    fun `each user's eventReminderMinutes sets the lead time, 0 turns them off, the task setting does not count`() {
        settings(ben, "taskReminderMinutes" to "0")
        settings(clara, "eventReminderMinutes" to "120")
        settings(dora, "eventReminderMinutes" to "0")
        val event = event(startAt = inAnHour + minutes(30))
        listOf(ben, clara, dora).forEach { invite(event, it) }

        service.sendDue()
        assertEquals(listOf(0, 1, 0), listOf(ben, clara, dora).map { sentTo(it).size })

        clock.advance(minutes(30))
        service.sendDue()
        assertEquals(listOf(1, 1, 0), listOf(ben, clara, dora).map { sentTo(it).size })
    }

    @Test
    fun `deleted and past events remind nobody`() {
        invite(eventRepository.save(event().copy(deleted = true)), ben)
        invite(event(startAt = clock.millis() - minutes(1)), ben)

        service.sendDue()

        assertTrue(android.sent.isEmpty())
    }

    @Test
    fun `invitees who left the stable or belong to another stable get nothing`() {
        val event = event()
        invite(event, gone)
        invite(event, foreigner)

        service.sendDue()

        assertTrue(android.sent.isEmpty())
    }

    @Test
    fun `muted reminders and disabled notifications send nothing, digest users get it at once`() {
        settings(ben, "notificationMutedTypes" to "event_reminder")
        settings(clara, "notificationsEnabled" to "false")
        settings(dora, "notificationDigest" to "true")
        val event = event()
        listOf(ben, clara, dora).forEach { invite(event, it) }

        service.sendDue()

        assertEquals(listOf(0, 0, 1), listOf(ben, clara, dora).map { sentTo(it).size })
    }

    @Test
    fun `an invitee absent that day is skipped`() {
        absenceRepository.save(
            Absence(stableId = STABLE_ID, userId = ben.id, from = epochDay("2026-10-09"), until = epochDay("2026-10-09"), note = "",
                createdByUserId = ben.id, updatedAt = clock.millis(), updatedBy = ben.id)
        )
        val event = event()
        invite(event, ben)
        invite(event, clara)

        service.sendDue()

        assertEquals(listOf(0, 1), listOf(ben, clara).map { sentTo(it).size })
    }

    @Test
    fun `a moved start reminds again`() {
        val event = event()
        invite(event, ben)
        service.sendDue()

        eventRepository.save(event.copy(startAt = inAnHour + minutes(30), endAt = inAnHour + minutes(90)))
        clock.advance(minutes(30))
        service.sendDue()

        assertEquals(2, sentTo(ben).size)
    }

    @Test
    fun `a series reminds who accepted the date starting next, with its key`() {
        val event = event(startAt = inAnHour - days(3), recurrence = daily)
        answer(invite(event, ben, InvitationStatus.PENDING), inAnHour)
        answer(invite(event, clara, InvitationStatus.PENDING), inAnHour - days(1))
        invite(event, anna)

        service.sendDue()

        assertEquals(listOf(reminder(event, occurrenceAt = inAnHour)), sentTo(ben))
        assertEquals(listOf(reminder(event, occurrenceAt = inAnHour)), sentTo(anna))
        assertTrue(sentTo(clara).isEmpty())
    }

    @Test
    fun `a date invitation reminds for its own date only`() {
        val event = event(startAt = inAnHour - days(1), recurrence = daily)
        invite(event, ben, occurrenceStartAt = inAnHour)
        invite(event, clara, occurrenceStartAt = inAnHour + days(1))

        service.sendDue()

        assertEquals(listOf(reminder(event, occurrenceAt = inAnHour)), sentTo(ben))
        assertTrue(sentTo(clara).isEmpty())
    }

    @Test
    fun `cancelled dates remind nobody, users taken off a date are skipped`() {
        val cancelled = event(startAt = inAnHour - days(1), recurrence = daily)
        invite(cancelled, ben)
        occurrence(cancelled, inAnHour) { it.copy(cancelled = true) }
        val takenOff = event(startAt = inAnHour - days(1), recurrence = daily)
        invite(takenOff, clara)
        invite(takenOff, dora)
        occurrence(takenOff, inAnHour) { it.copy(removedUserIds = listOf(clara.id)) }

        service.sendDue()

        assertEquals(listOf(0, 0, 1), listOf(ben, clara, dora).map { sentTo(it).size })
    }

    @Test
    fun `a date moved into the next hour reminds at its new time with its own title, one moved away does not`() {
        val tomorrow = inAnHour + days(1)
        val movedHere = event(startAt = tomorrow, recurrence = daily)
        occurrence(movedHere, tomorrow) { it.copy(startAt = inAnHour, endAt = inAnHour + hours(1), title = "Springstunde früher") }
        val movedAway = event(startAt = inAnHour - days(1), recurrence = daily)
        occurrence(movedAway, inAnHour) { it.copy(startAt = inAnHour + hours(5), endAt = inAnHour + hours(6)) }
        invite(movedHere, ben)
        invite(movedAway, ben)

        service.sendDue()

        assertEquals(listOf(reminder(movedHere, title = "Springstunde früher", occurrenceAt = tomorrow)), sentTo(ben))
    }

    @Test
    fun `the title names the stable for an invitee with several stables`() {
        stableRepository.save(testStable(adminUserId = anna.id))
        userRepository.save(testUser(email = ben.email, stableId = OTHER_STABLE_ID).copy(accountId = ben.accountId))
        invite(event(), ben)

        service.sendDue()

        assertEquals(listOf("Hof Lerchenfeld: Erinnerung: Springstunde"), sentTo(ben).map { it.title })
    }

    @Test
    fun `a series that cannot be expanded is skipped, the other events still remind`() {
        invite(event(startAt = inAnHour - days(1), recurrence = daily.copy(timeZone = "Mars/Olympus")), ben)
        val fine = event()
        invite(fine, ben)

        service.sendDue()

        assertEquals(listOf(reminder(fine)), sentTo(ben))
    }

    @Test
    fun `reminder records go a day after the start`() {
        invite(event(), ben)
        service.sendDue()

        clock.advance(days(1) + hours(2))
        service.sendDue()

        assertTrue(sentReminders.reminders.isEmpty())
    }
}
