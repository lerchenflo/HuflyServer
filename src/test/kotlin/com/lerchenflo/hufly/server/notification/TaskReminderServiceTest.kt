package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.absence.model.Absence
import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceFrequency
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import com.lerchenflo.hufly.server.repository.FakeAbsenceRepository
import com.lerchenflo.hufly.server.repository.FakeRefreshTokenRepository
import com.lerchenflo.hufly.server.repository.FakeSentReminderRepository
import com.lerchenflo.hufly.server.repository.FakeTaskOccurrenceRepository
import com.lerchenflo.hufly.server.repository.FakeTaskRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.repository.FakeUserSettingsRepository
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.task.model.TaskOccurrence
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.epochDay
import com.lerchenflo.hufly.server.testdata.hours
import com.lerchenflo.hufly.server.testdata.millis
import com.lerchenflo.hufly.server.testdata.minutes
import com.lerchenflo.hufly.server.testdata.testUser
import com.lerchenflo.hufly.server.user.model.User
import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId
import java.util.concurrent.Executor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Task due reminders (decided 2026-10-13): `taskReminderMinutes` before `dueAt`, once per user and due time. */
class TaskReminderServiceTest {

    // Friday 9 October 2026, 16:00 in Vienna (CEST).
    private val clock = MutableClock(millis("2026-10-09T14:00:00Z"))
    private val userRepository = FakeUserRepository()
    private val settingsRepository = FakeUserSettingsRepository()
    private val sessions = FakeRefreshTokenRepository()
    private val taskRepository = FakeTaskRepository()
    private val occurrenceRepository = FakeTaskOccurrenceRepository()
    private val absenceRepository = FakeAbsenceRepository()
    private val sentReminders = FakeSentReminderRepository()
    private val android = FakePushSender(PushPlatform.ANDROID)
    private val pushService = PushService(sessions, listOf(android), Executor { it.run() }, clock)
    private val stableRepository = com.lerchenflo.hufly.server.repository.FakeStableRepository()
    private val service = TaskReminderService(
        taskRepository, occurrenceRepository, sentReminders,
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

    private fun task(
        dueAt: Long? = inAnHour,
        assignees: List<User> = listOf(ben),
        recurrence: Recurrence? = null,
        rotates: Boolean = false,
    ) = taskRepository.save(
        StableTask(stableId = STABLE_ID, title = "Misten", comment = "", dueAt = dueAt, assigneeUserIds = assignees.map { it.id },
            horseIds = emptyList(), recurrence = recurrence, rotatesAssignees = rotates, createdByUserId = anna.id,
            doneByUserId = null, doneAt = null, updatedAt = clock.millis(), updatedBy = anna.id)
    )

    private val daily = Recurrence(RecurrenceFrequency.DAILY, 1, emptyList(), null, null, "Europe/Vienna")

    private fun occurrence(task: StableTask, key: Long, change: (TaskOccurrence) -> TaskOccurrence) = occurrenceRepository.save(
        change(TaskOccurrence(stableId = STABLE_ID, taskId = task.id, occurrenceDueAt = key, updatedAt = clock.millis(), updatedBy = anna.id))
    )

    private fun reminder(task: StableTask, dueAt: Long, occurrenceAt: Long? = null) = PushMessage(
        "Erinnerung: ${task.title}",
        "Fällig ${if (dueAt == inAnHour) "Fr. 09.10. 17:00" else "?"}",
        buildMap {
            put("type", "task_reminder")
            put("taskId", task.id.toHexString())
            if (occurrenceAt != null) put("occurrenceAt", occurrenceAt.toString())
            put("stableId", STABLE_ID.toHexString())
        },
    )

    @Test
    fun `a task reminds its assignees an hour before it is due, once`() {
        val task = task(assignees = listOf(ben, clara))

        service.sendDue()
        clock.advance(minutes(1))
        service.sendDue()

        assertEquals(listOf(reminder(task, inAnHour)), sentTo(ben))
        assertEquals(listOf(reminder(task, inAnHour)), sentTo(clara))
        assertTrue(sentTo(anna).isEmpty())
    }

    @Test
    fun `each user's taskReminderMinutes sets the lead time, 0 turns reminders off`() {
        settings(clara, "taskReminderMinutes" to "120")
        settings(dora, "taskReminderMinutes" to "0")
        task(dueAt = inAnHour + minutes(30), assignees = listOf(ben, clara, dora))

        service.sendDue()
        assertEquals(listOf(0, 1, 0), listOf(ben, clara, dora).map { sentTo(it).size })

        clock.advance(minutes(30))
        service.sendDue()
        assertEquals(listOf(1, 1, 0), listOf(ben, clara, dora).map { sentTo(it).size })
    }

    @Test
    fun `unreadable lead times fall back to an hour`() {
        settings(ben, "taskReminderMinutes" to "lots")
        settings(clara, "taskReminderMinutes" to "5000")
        task(assignees = listOf(ben, clara))

        service.sendDue()

        assertEquals(listOf(1, 1), listOf(ben, clara).map { sentTo(it).size })
    }

    @Test
    fun `done, deleted, undated and past tasks remind nobody`() {
        task().let { taskRepository.save(it.copy(doneAt = clock.millis(), doneByUserId = ben.id)) }
        task().let { taskRepository.save(it.copy(deleted = true)) }
        task(dueAt = null)
        task(dueAt = clock.millis() - minutes(1))

        service.sendDue()

        assertTrue(android.sent.isEmpty())
    }

    @Test
    fun `assignees who left the stable or belong to another stable get nothing`() {
        task(assignees = listOf(gone, foreigner))

        service.sendDue()

        assertTrue(android.sent.isEmpty())
    }

    @Test
    fun `muted reminders and disabled notifications send nothing, digest users get it at once`() {
        settings(ben, "notificationMutedTypes" to "task_reminder")
        settings(clara, "notificationsEnabled" to "false")
        settings(dora, "notificationDigest" to "true")
        task(assignees = listOf(ben, clara, dora))

        service.sendDue()

        assertEquals(listOf(0, 0, 1), listOf(ben, clara, dora).map { sentTo(it).size })
    }

    @Test
    fun `an assignee absent on the due date is skipped`() {
        absenceRepository.save(
            Absence(stableId = STABLE_ID, userId = ben.id, from = epochDay("2026-10-08"), until = epochDay("2026-10-09"), note = "",
                createdByUserId = ben.id, updatedAt = clock.millis(), updatedBy = ben.id)
        )
        task(assignees = listOf(ben, clara))

        service.sendDue()

        assertEquals(listOf(0, 1), listOf(ben, clara).map { sentTo(it).size })
    }

    @Test
    fun `a moved due time reminds again`() {
        val task = task()
        service.sendDue()

        taskRepository.save(task.copy(dueAt = inAnHour + minutes(30)))
        clock.advance(minutes(30))
        service.sendDue()

        assertEquals(2, sentTo(ben).size)
    }

    @Test
    fun `a series reminds the date due next, with its key`() {
        val task = task(dueAt = inAnHour - days(3), recurrence = daily)

        service.sendDue()

        assertEquals(listOf(reminder(task, inAnHour, occurrenceAt = inAnHour)), sentTo(ben))
    }

    @Test
    fun `a rotating series reminds only the assignee whose turn it is`() {
        // Dates since the first: 0 ben, 1 clara, 2 ben, 3 clara (today).
        task(dueAt = inAnHour - days(3), assignees = listOf(ben, clara), recurrence = daily, rotates = true)

        service.sendDue()

        assertEquals(listOf(0, 1), listOf(ben, clara).map { sentTo(it).size })
    }

    @Test
    fun `a stand-in replaces the series' assignees for that date`() {
        val task = task(dueAt = inAnHour - days(1), recurrence = daily)
        occurrence(task, inAnHour) { it.copy(assigneeUserIds = listOf(clara.id)) }

        service.sendDue()

        assertEquals(listOf(0, 1), listOf(ben, clara).map { sentTo(it).size })
    }

    @Test
    fun `cancelled and ticked dates remind nobody`() {
        val cancelled = task(dueAt = inAnHour - days(1), recurrence = daily)
        occurrence(cancelled, inAnHour) { it.copy(cancelled = true) }
        val ticked = task(dueAt = inAnHour - days(1), recurrence = daily)
        occurrence(ticked, inAnHour) { it.copy(doneAt = clock.millis(), doneByUserId = ben.id) }

        service.sendDue()

        assertTrue(android.sent.isEmpty())
    }

    @Test
    fun `a date moved into the next hour reminds at its new time, one moved away does not`() {
        val tomorrow = inAnHour + days(1)
        val movedHere = task(dueAt = tomorrow, recurrence = daily)
        occurrence(movedHere, tomorrow) { it.copy(dueAt = inAnHour) }
        val movedAway = task(dueAt = inAnHour - days(1), recurrence = daily)
        occurrence(movedAway, inAnHour) { it.copy(dueAt = inAnHour + hours(5)) }

        service.sendDue()

        assertEquals(listOf(reminder(movedHere, inAnHour, occurrenceAt = tomorrow)), sentTo(ben))
    }

    @Test
    fun `the title names the stable for an assignee with several stables`() {
        stableRepository.save(com.lerchenflo.hufly.server.testdata.testStable(adminUserId = anna.id))
        userRepository.save(testUser(email = ben.email, stableId = OTHER_STABLE_ID).copy(accountId = ben.accountId))
        task(assignees = listOf(ben, clara))

        service.sendDue()

        assertEquals(listOf("Hof Lerchenfeld: Erinnerung: Misten"), sentTo(ben).map { it.title })
        assertEquals(listOf("Erinnerung: Misten"), sentTo(clara).map { it.title })
    }

    @Test
    fun `a series that cannot be expanded is skipped, the other tasks still remind`() {
        task(dueAt = inAnHour - days(1), recurrence = daily.copy(timeZone = "Mars/Olympus"))
        val fine = task()

        service.sendDue()

        assertEquals(listOf(reminder(fine, inAnHour)), sentTo(ben))
    }

    @Test
    fun `reminder records go a day after their due time`() {
        task()
        service.sendDue()

        clock.advance(days(1) + hours(2))
        service.sendDue()

        assertTrue(sentReminders.reminders.isEmpty())
    }
}
