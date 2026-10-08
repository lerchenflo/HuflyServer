package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.notification.model.DigestItem
import com.lerchenflo.hufly.server.notification.model.NotificationPreferences
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.repository.DigestItemRepository
import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import kotlin.time.Instant

/** Sends each digest user what was collected before their latest digest time (in their zone) as one push. */
@Service
class DigestService(
    private val digestRepository: DigestItemRepository,
    private val settingsRepository: UserSettingsRepository,
    private val pushService: PushService,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "PT5M", initialDelayString = "PT1M")
    fun sendDue() {
        val now = clock.millis()
        digestRepository.findByCreatedAtBefore(now).groupBy { it.userId }.forEach { (userId, items) ->
            val preferences = NotificationPreferences.of(settingsRepository.findById(userId)?.values)
            val cutoff = latestDigestTime(now, preferences)
            val due = items.filter { it.createdAt < cutoff }.sortedBy { it.createdAt }
            if (due.isEmpty()) return@forEach
            if (preferences.enabled) pushService.send(userId, summary(due))
            digestRepository.deleteByIdIn(due.map { it.id })
        }
    }

    private fun latestDigestTime(now: Long, preferences: NotificationPreferences): Long {
        val zone = preferences.zone
        val date = Instant.fromEpochMilliseconds(now).toLocalDateTime(zone).date
        val today = LocalDateTime(date, preferences.digestTime).toInstant(zone).toEpochMilliseconds()
        if (today <= now) return today
        return LocalDateTime(date.minus(1, DateTimeUnit.DAY), preferences.digestTime).toInstant(zone).toEpochMilliseconds()
    }

    private fun summary(items: List<DigestItem>): PushMessage {
        val data = mapOf("type" to "digest")
        if (items.size == 1) return PushMessage(items.single().title, items.single().body, data)
        val lines = items.take(MAX_LINES).map { it.title } + listOfNotNull(
            "… und ${items.size - MAX_LINES} weitere".takeIf { items.size > MAX_LINES }
        )
        return PushMessage("${items.size} Neuigkeiten", lines.joinToString("\n"), data)
    }

    companion object {
        private const val MAX_LINES = 4
    }
}
