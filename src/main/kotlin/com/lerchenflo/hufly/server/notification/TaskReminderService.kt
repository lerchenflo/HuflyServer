package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.notification.model.NotificationPreferences
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.SentReminder
import com.lerchenflo.hufly.server.repository.AbsenceRepository
import com.lerchenflo.hufly.server.repository.SentReminderRepository
import com.lerchenflo.hufly.server.repository.TaskOccurrenceRepository
import com.lerchenflo.hufly.server.repository.TaskRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.task.model.TaskOccurrence
import com.lerchenflo.hufly.server.task.model.rotationAssignee
import com.lerchenflo.hufly.server.user.model.User
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import kotlin.time.Instant

/**
 * Reminds the effective assignees of open tasks and series dates `taskReminderMinutes` before they are due, at once
 * also for digest users, skipping users absent that day. [SentReminder] keeps it to one push per user and due time.
 */
@Service
class TaskReminderService(
    private val taskRepository: TaskRepository,
    private val occurrenceRepository: TaskOccurrenceRepository,
    private val userRepository: UserRepository,
    private val settingsRepository: UserSettingsRepository,
    private val absenceRepository: AbsenceRepository,
    private val sentReminders: SentReminderRepository,
    private val pushService: PushService,
    private val pushTitles: PushTitles,
    private val clock: Clock,
) {
    private data class DueDate(val task: StableTask, val title: String, val dueAt: Long, val occurrenceAt: Long?, val assignees: List<ObjectId>)

    @Scheduled(fixedDelayString = "PT1M", initialDelayString = "PT1M")
    fun sendDue() {
        val now = clock.millis()
        sentReminders.deleteByDueAtBefore(now - DAY_MILLIS)
        val preferences = mutableMapOf<ObjectId, NotificationPreferences>()
        dueDates(now, now + NotificationPreferences.MAX_TASK_REMINDER_MINUTES * 60_000L).forEach { date ->
            date.assignees.distinct()
                .mapNotNull { id -> userRepository.findById(id)?.takeIf { it.stableId == date.task.stableId && !it.deleted } }
                .forEach { user ->
                    val userPreferences = preferences.getOrPut(user.accountId) {
                        NotificationPreferences.of(settingsRepository.findById(user.accountId)?.values)
                    }
                    remind(user, date, userPreferences, now)
                }
        }
    }

    private fun dueDates(now: Long, until: Long): List<DueDate> {
        val single = taskRepository.findOpenSingleDue(now, until).map { DueDate(it, it.title, it.dueAt!!, null, it.assigneeUserIds) }
        val moved = occurrenceRepository.findMovedDue(now, until)
        val series = (taskRepository.findSeriesStartingBy(until) + taskRepository.findByIdIn(moved.map { it.taskId }.toSet()))
            .filter { !it.deleted && it.recurrence != null && it.dueAt != null }
            .distinctBy { it.id }
        val changes = occurrenceRepository.findByTaskIdInAndDeletedFalse(series.map { it.id }).groupBy { it.taskId }
        return single + series.flatMap { task -> seriesDates(task, changes[task.id].orEmpty().associateBy { it.occurrenceDueAt }, now, until) }
    }

    private fun seriesDates(task: StableTask, changes: Map<Long, TaskOccurrence>, now: Long, until: Long): List<DueDate> {
        val keys = task.recurrence!!.starts(task.dueAt!!).takeWhile { it <= until }.filter { it > now }.toSet() + changes.keys
        return keys.mapNotNull { key ->
            val change = changes[key]
            val dueAt = change?.dueAt ?: key
            if (dueAt <= now || dueAt > until || change?.cancelled == true || change?.doneAt != null) return@mapNotNull null
            val assignees = change?.assigneeUserIds ?: task.rotationAssignee(key)?.let(::listOf) ?: task.assigneeUserIds
            DueDate(task, change?.title ?: task.title, dueAt, key, assignees)
        }
    }

    private fun remind(user: User, date: DueDate, preferences: NotificationPreferences, now: Long) {
        if (!preferences.enabled || preferences.mutes(TYPE)) return
        val remindAt = preferences.taskReminderAt(date.dueAt) ?: return
        if (now < remindAt || isAbsent(user, date.dueAt, preferences.zone)) return
        try {
            sentReminders.insert(SentReminder(stableId = date.task.stableId, taskId = date.task.id, dueAt = date.dueAt, userId = user.id))
        } catch (e: DuplicateKeyException) {
            return
        }
        val data = buildMap {
            put("type", TYPE)
            put("taskId", date.task.id.toHexString())
            if (date.occurrenceAt != null) put("occurrenceAt", date.occurrenceAt.toString())
            put("stableId", user.stableId.toHexString())
        }
        pushService.send(user.accountId, PushMessage(pushTitles.of(user, "Erinnerung: ${date.title}"), "Fällig ${formatPushTime(date.dueAt, preferences.zone)}", data))
    }

    private fun isAbsent(user: User, dueAt: Long, zone: TimeZone): Boolean {
        val day = Instant.fromEpochMilliseconds(dueAt).toLocalDateTime(zone).date.toEpochDays()
        return absenceRepository.findByUserIdAndDeletedFalse(user.id).any { day in it.from..it.until }
    }

    companion object {
        const val TYPE = "task_reminder"
        private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
