package com.lerchenflo.hufly.server.notification.model

import java.time.DateTimeException
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/** Read from the synced user settings map; missing or unreadable values fall back to the defaults. */
data class NotificationPreferences(
    val enabled: Boolean = true,
    /** One push a day at [digestTime] instead of single pushes. */
    val digest: Boolean = false,
    val digestTime: LocalTime = LocalTime.of(7, 0),
    /** For dates in the text and the digest time. */
    val zone: ZoneId = ZoneId.of("Europe/Vienna"),
) {
    companion object {
        fun of(values: Map<String, String>?): NotificationPreferences {
            val defaults = NotificationPreferences()
            if (values == null) return defaults
            return NotificationPreferences(
                enabled = values["notificationsEnabled"] != "false",
                digest = values["notificationDigest"] == "true",
                digestTime = values["notificationDigestTime"]?.let {
                    try { LocalTime.parse(it) } catch (e: DateTimeParseException) { null }
                } ?: defaults.digestTime,
                zone = values["notificationTimeZone"]?.let {
                    try { ZoneId.of(it) } catch (e: DateTimeException) { null }
                } ?: defaults.zone,
            )
        }
    }
}
