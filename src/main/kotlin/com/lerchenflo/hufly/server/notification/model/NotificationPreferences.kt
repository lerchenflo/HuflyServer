package com.lerchenflo.hufly.server.notification.model

import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone

/** Read from the synced user settings map; missing or unreadable values fall back to the defaults. */
data class NotificationPreferences(
    val enabled: Boolean = true,
    /** One push a day at [digestTime] instead of single pushes. */
    val digest: Boolean = false,
    val digestTime: LocalTime = LocalTime(7, 0),
    /** For dates in the text and the digest time. */
    val zone: TimeZone = TimeZone.of("Europe/Vienna"),
    /** Push types (`data.type`) the user never wants, not even in the digest. */
    val mutedTypes: Set<String> = emptySet(),
    /** Lead time of task due reminders; 0 turns them off. */
    val taskReminderMinutes: Int = 60,
) {
    /** When to remind of a task due at [dueAt], null when reminders are off. */
    fun taskReminderAt(dueAt: Long): Long? = if (taskReminderMinutes == 0) null else dueAt - taskReminderMinutes * 60_000L

    fun mutes(type: String?): Boolean = type != null && type !in UNMUTABLE_TYPES && type in mutedTypes

    companion object {
        /** The applicant waits for this answer. */
        private val UNMUTABLE_TYPES = setOf("join_accepted")
        const val MAX_TASK_REMINDER_MINUTES = 1440

        fun of(values: Map<String, String>?): NotificationPreferences {
            val defaults = NotificationPreferences()
            if (values == null) return defaults
            return NotificationPreferences(
                enabled = values["notificationsEnabled"] != "false",
                digest = values["notificationDigest"] == "true",
                digestTime = values["notificationDigestTime"]?.let {
                    try { LocalTime.parse(it) } catch (e: IllegalArgumentException) { null }
                } ?: defaults.digestTime,
                zone = values["notificationTimeZone"]?.let {
                    try { TimeZone.of(it) } catch (e: IllegalTimeZoneException) { null }
                } ?: defaults.zone,
                mutedTypes = values["notificationMutedTypes"].orEmpty().split(',').map { it.trim() }.filterTo(mutableSetOf()) { it.isNotEmpty() },
                taskReminderMinutes = values["taskReminderMinutes"]?.toIntOrNull()?.takeIf { it in 0..MAX_TASK_REMINDER_MINUTES }
                    ?: defaults.taskReminderMinutes,
            )
        }
    }
}
