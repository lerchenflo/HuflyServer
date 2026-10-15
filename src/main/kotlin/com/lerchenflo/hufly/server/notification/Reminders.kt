package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.notification.model.NotificationPreferences
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.repository.AbsenceRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.user.model.User
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import kotlin.time.Instant

/** What the reminder jobs share: who may get a reminder, absences, and the push itself. */
@Component
class Reminders(
    private val userRepository: UserRepository,
    private val settingsRepository: UserSettingsRepository,
    private val absenceRepository: AbsenceRepository,
    private val pushService: PushService,
    private val pushTitles: PushTitles,
) {
    /** Settings of one job run, read once per account. */
    class Run internal constructor() {
        internal val preferences = mutableMapOf<ObjectId, NotificationPreferences>()
    }

    fun run() = Run()

    /** Live members of [stableId] among [userIds] that take [type] pushes, with their settings. */
    fun recipients(run: Run, userIds: List<ObjectId>, stableId: ObjectId, type: String): List<Pair<User, NotificationPreferences>> =
        userIds.distinct()
            .mapNotNull { id -> userRepository.findById(id)?.takeIf { it.stableId == stableId && !it.deleted } }
            .map { user ->
                user to run.preferences.getOrPut(user.accountId) {
                    NotificationPreferences.of(settingsRepository.findById(user.accountId)?.values)
                }
            }
            .filter { (_, preferences) -> preferences.enabled && !preferences.mutes(type) }

    /** Whether [user] has an absence on the day of [at] in [zone]. */
    fun isAbsent(user: User, at: Long, zone: TimeZone): Boolean {
        val day = Instant.fromEpochMilliseconds(at).toLocalDateTime(zone).date.toEpochDays()
        return absenceRepository.findByUserIdAndDeletedFalse(user.id).any { day in it.from..it.until }
    }

    /** At once, also to digest users. [data] gets the type and the stable added. */
    fun send(user: User, type: String, title: String, body: String, data: Map<String, String>) {
        val payload = mapOf("type" to type) + data + ("stableId" to user.stableId.toHexString())
        pushService.send(user.accountId, PushMessage(pushTitles.of(user, title), body, payload))
    }

    companion object {
        /** Records of sent reminders are dropped this long after their time. */
        const val KEEP_SENT_MILLIS = 24 * 60 * 60 * 1000L
        const val WINDOW_MILLIS = NotificationPreferences.MAX_REMINDER_MINUTES * 60_000L
    }
}
