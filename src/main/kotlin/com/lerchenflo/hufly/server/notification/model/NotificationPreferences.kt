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
) {
    companion object {
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
            )
        }
    }
}
