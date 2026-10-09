package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.core.notification.EventInvited
import com.lerchenflo.hufly.server.core.notification.InvitationAnswered
import com.lerchenflo.hufly.server.core.notification.NotePosted
import com.lerchenflo.hufly.server.core.notification.TaskAssigned
import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceFrequency
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.note.model.StableNote
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import com.lerchenflo.hufly.server.repository.FakeDigestItemRepository
import com.lerchenflo.hufly.server.repository.FakeEventRepository
import com.lerchenflo.hufly.server.repository.FakeNoteRepository
import com.lerchenflo.hufly.server.repository.FakeRefreshTokenRepository
import com.lerchenflo.hufly.server.repository.FakeTaskRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.repository.FakeUserSettingsRepository
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.hours
import com.lerchenflo.hufly.server.testdata.millis
import com.lerchenflo.hufly.server.testdata.testUser
import com.lerchenflo.hufly.server.user.model.User
import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId
import java.util.concurrent.Executor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Push notifications (user 2026-10-05): sent always, socket or not, with German text from the server. */
class NotificationServiceTest {

    private val clock = MutableClock(millis("2026-10-05T08:00:00Z"))
    private val userRepository = FakeUserRepository()
    private val settingsRepository = FakeUserSettingsRepository()
    private val sessions = FakeRefreshTokenRepository()
    private val noteRepository = FakeNoteRepository()
    private val eventRepository = FakeEventRepository()
    private val taskRepository = FakeTaskRepository()
    private val digestRepository = FakeDigestItemRepository()
    private val stableRepository = com.lerchenflo.hufly.server.repository.FakeStableRepository()
    private val android = FakePushSender(PushPlatform.ANDROID)
    private val pushService = PushService(sessions, listOf(android), Executor { it.run() }, clock)
    private val service = NotificationService(
        userRepository, userRepository.accounts, stableRepository, settingsRepository, noteRepository, eventRepository, taskRepository, pushService, digestRepository, clock,
    )

    private val anna = testUser(email = "anna@hufly.test")
    private val ben = testUser(email = "ben@hufly.test")
    private val clara = testUser(email = "clara@hufly.test")
    private val gone = testUser(email = "gone@hufly.test", deleted = true)
    private val foreigner = testUser(email = "foreign@hufly.test", stableId = OTHER_STABLE_ID)

    // Friday 9 October 2026, 17:00 in Vienna (CEST).
    private val lesson = millis("2026-10-09T15:00:00Z")

    @BeforeTest
    fun setUp() {
        listOf(anna, ben, clara, gone, foreigner).forEach {
            userRepository.save(it)
            val session = sessions.save(
                RefreshToken(userId = it.id, hashedToken = ObjectId.get().toHexString(), expiresAt = clock.millis() + days(30), createdAt = clock.millis())
            )
            pushService.register(it.id, session.id, PushPlatform.ANDROID, "token-${it.displayName}")
        }
    }

    private fun sentTo(user: User): List<PushMessage> = android.sent.filter { it.first == "token-${user.displayName}" }.map { it.second }

    private fun note() = noteRepository.save(
        StableNote(stableId = STABLE_ID, title = "Hufschmied kommt", body = "", pinned = false, visibleUntil = null,
            createdByUserId = anna.id, createdAt = clock.millis(), updatedAt = clock.millis(), updatedBy = anna.id)
    )

    private fun event(recurrence: Recurrence? = null) = eventRepository.save(
        Event(stableId = STABLE_ID, creatorUserId = anna.id, title = "Springstunde", description = "", startAt = lesson,
            endAt = lesson + hours(1), horseIds = emptyList(), recurrence = recurrence, createdAt = clock.millis(),
            updatedAt = clock.millis(), updatedBy = anna.id)
    )

    private fun task(dueAt: Long? = lesson) = taskRepository.save(
        StableTask(stableId = STABLE_ID, title = "Misten", comment = "", dueAt = dueAt, assigneeUserIds = listOf(ben.id),
            horseIds = emptyList(), createdByUserId = anna.id, doneByUserId = null, doneAt = null, updatedAt = clock.millis(), updatedBy = anna.id)
    )

    @Test
    fun `a join request tells the stable admin who asks`() {
        stableRepository.save(com.lerchenflo.hufly.server.testdata.testStable(adminUserId = anna.id))
        val login = userRepository.accounts.save(com.lerchenflo.hufly.server.testdata.testAccount(email = "neu@hufly.test"))
        val requestId = ObjectId.get()

        service.on(com.lerchenflo.hufly.server.core.notification.JoinRequested(STABLE_ID, login.id, requestId))

        val expected = PushMessage(
            "Beitrittsanfrage", "neu möchte Hof Lerchenfeld beitreten",
            mapOf("type" to "join_request", "joinRequestId" to requestId.toHexString()),
        )
        assertEquals(listOf(expected), sentTo(anna))
        assertTrue(sentTo(ben).isEmpty() && sentTo(clara).isEmpty())
    }

    @Test
    fun `an accepted request welcomes the new member on the devices of their login`() {
        stableRepository.save(com.lerchenflo.hufly.server.testdata.testStable(adminUserId = anna.id))
        val login = ObjectId.get()
        val session = sessions.save(
            RefreshToken(userId = login, hashedToken = ObjectId.get().toHexString(), expiresAt = clock.millis() + days(30), createdAt = clock.millis())
        )
        pushService.register(login, session.id, PushPlatform.ANDROID, "token-neu")
        val member = userRepository.save(testUser(email = "neu@hufly.test").copy(accountId = login))

        service.on(com.lerchenflo.hufly.server.core.notification.JoinAccepted(STABLE_ID, anna.id, member.id))

        val expected = PushMessage(
            "Willkommen im Stall", "Deine Anfrage für Hof Lerchenfeld wurde angenommen",
            mapOf("type" to "join_accepted", "stableId" to STABLE_ID.toHexString()),
        )
        assertEquals(listOf(expected), android.sent.filter { it.first == "token-neu" }.map { it.second })
    }

    @Test
    fun `a new note goes to every live member of the stable but its author`() {
        val note = note()

        service.on(NotePosted(STABLE_ID, anna.id, note.id))

        val expected = PushMessage("Neuer Aushang", "Hufschmied kommt", mapOf("type" to "note", "noteId" to note.id.toHexString()))
        assertEquals(listOf(expected), sentTo(ben))
        assertEquals(listOf(expected), sentTo(clara))
        assertTrue(sentTo(anna).isEmpty() && sentTo(gone).isEmpty() && sentTo(foreigner).isEmpty())
    }

    @Test
    fun `an invitation tells the invitees when and from whom`() {
        val event = event()

        service.on(EventInvited(STABLE_ID, anna.id, event.id, listOf(ben.id, anna.id, foreigner.id), occurrenceStartAt = null))

        assertEquals(
            listOf(PushMessage("Einladung: Springstunde", "Fr. 09.10. 17:00 · von anna", mapOf("type" to "event_invitation", "eventId" to event.id.toHexString()))),
            sentTo(ben),
        )
        assertTrue(sentTo(anna).isEmpty() && sentTo(foreigner).isEmpty() && sentTo(clara).isEmpty())
    }

    @Test
    fun `an invitation to one date of a series names that date`() {
        val event = event(Recurrence(RecurrenceFrequency.WEEKLY, 1, emptyList(), null, null, "Europe/Vienna"))
        val nextWeek = lesson + days(7)

        service.on(EventInvited(STABLE_ID, anna.id, event.id, listOf(ben.id), occurrenceStartAt = nextWeek))

        assertEquals(
            PushMessage("Einladung: Springstunde", "Fr. 16.10. 17:00 · von anna",
                mapOf("type" to "event_invitation", "eventId" to event.id.toHexString(), "occurrenceAt" to nextWeek.toString())),
            sentTo(ben).single(),
        )
    }

    @Test
    fun `an answer goes to the organiser`() {
        val event = event()

        service.on(InvitationAnswered(STABLE_ID, ben.id, event.id, accepted = true, occurrenceStartAt = null))
        service.on(InvitationAnswered(STABLE_ID, clara.id, event.id, accepted = false, occurrenceStartAt = null))

        assertEquals(listOf("ben hat zugesagt", "clara hat abgesagt"), sentTo(anna).map { it.title })
        assertEquals("Springstunde, Fr. 09.10. 17:00", sentTo(anna).first().body)
    }

    @Test
    fun `a task goes to its new assignees, with its date, none or the series`() {
        val single = task()
        val undated = task(dueAt = null)

        service.on(TaskAssigned(STABLE_ID, anna.id, single.id, listOf(ben.id, anna.id), occurrenceDueAt = null))
        service.on(TaskAssigned(STABLE_ID, anna.id, undated.id, listOf(ben.id), occurrenceDueAt = null))
        service.on(TaskAssigned(STABLE_ID, anna.id, single.id, listOf(clara.id), occurrenceDueAt = lesson))

        assertEquals(listOf("Neue Aufgabe: Misten" to "Fällig Fr. 09.10. 17:00", "Neue Aufgabe: Misten" to "Ohne Termin"), sentTo(ben).map { it.title to it.body })
        assertEquals("Vertretung: Misten" to "Am Fr. 09.10. 17:00", sentTo(clara).single().let { it.title to it.body })
        assertTrue(sentTo(anna).isEmpty())
    }

    @Test
    fun `deleted notes, events and tasks send nothing`() {
        val note = noteRepository.save(note().copy(deleted = true))
        val event = eventRepository.save(event().copy(deleted = true))

        service.on(NotePosted(STABLE_ID, anna.id, note.id))
        service.on(EventInvited(STABLE_ID, anna.id, event.id, listOf(ben.id), null))
        service.on(TaskAssigned(STABLE_ID, anna.id, ObjectId.get(), listOf(ben.id), null))

        assertTrue(android.sent.isEmpty())
    }

    @Test
    fun `muted users get nothing, digest users get it later in their own time zone`() {
        settingsRepository.save(UserSettings(ben.id, mapOf("notificationsEnabled" to "false"), clock.millis()))
        settingsRepository.save(UserSettings(clara.id, mapOf("notificationDigest" to "true", "notificationTimeZone" to "Europe/London"), clock.millis()))
        val event = event()

        service.on(EventInvited(STABLE_ID, anna.id, event.id, listOf(ben.id, clara.id), null))

        assertTrue(sentTo(ben).isEmpty() && sentTo(clara).isEmpty())
        val item = digestRepository.items.single()
        assertEquals(clara.id, item.userId)
        assertEquals("Einladung: Springstunde" to "Fr. 09.10. 16:00 · von anna", item.title to item.body)
    }
}
