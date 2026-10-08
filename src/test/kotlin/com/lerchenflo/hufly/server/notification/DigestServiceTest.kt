package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.notification.model.DigestItem
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import com.lerchenflo.hufly.server.repository.FakeDigestItemRepository
import com.lerchenflo.hufly.server.repository.FakeRefreshTokenRepository
import com.lerchenflo.hufly.server.repository.FakeUserSettingsRepository
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.millis
import com.lerchenflo.hufly.server.testdata.minusSeconds
import com.lerchenflo.hufly.server.testdata.minutes
import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId
import java.util.concurrent.Executor
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The daily digest collects a user's pushes and sends them once their digest time has passed. */
class DigestServiceTest {

    // 06:30 in Vienna (CEST).
    private val clock = MutableClock(millis("2026-10-06T04:30:00Z"))
    private val sessions = FakeRefreshTokenRepository()
    private val settingsRepository = FakeUserSettingsRepository()
    private val digestRepository = FakeDigestItemRepository()
    private val android = FakePushSender(PushPlatform.ANDROID)
    private val pushService = PushService(sessions, listOf(android), Executor { it.run() }, clock)
    private val service = DigestService(digestRepository, settingsRepository, pushService, clock)

    private val anna = ObjectId.get()

    @BeforeTest
    fun setUp() {
        val session = sessions.save(
            RefreshToken(userId = anna, hashedToken = "h", expiresAt = clock.millis() + days(30), createdAt = clock.millis())
        )
        pushService.register(anna, session.id, PushPlatform.ANDROID, "anna-phone")
        settingsRepository.save(UserSettings(anna, mapOf("notificationDigest" to "true"), clock.millis()))
    }

    private fun item(title: String, at: Long) = digestRepository.save(DigestItem(userId = anna, title = title, body = "$title body", createdAt = at))

    @Test
    fun `nothing goes out before the digest time, everything collected until then goes out after it`() {
        item("Neuer Aushang", millis("2026-10-05T18:00:00Z"))
        item("Einladung: Springstunde", millis("2026-10-06T04:00:00Z"))

        service.sendDue()
        assertTrue(android.sent.isEmpty())

        clock.advance(minutes(31))
        item("Neue Aufgabe: Misten", clock.millis())
        service.sendDue()

        assertEquals(
            listOf("anna-phone" to PushMessage("2 Neuigkeiten", "Neuer Aushang\nEinladung: Springstunde", mapOf("type" to "digest"))),
            android.sent,
        )
        assertEquals(listOf("Neue Aufgabe: Misten"), digestRepository.items.map { it.title })
    }

    @Test
    fun `a single item keeps its own text, many are cut after four lines, the time is the user's`() {
        settingsRepository.save(UserSettings(anna, mapOf("notificationDigest" to "true", "notificationDigestTime" to "06:00"), clock.millis()))
        item("Neuer Aushang", millis("2026-10-06T03:00:00Z"))

        service.sendDue()
        assertEquals(PushMessage("Neuer Aushang", "Neuer Aushang body", mapOf("type" to "digest")), android.sent.single().second)

        (1..6).forEach { item("Aushang $it", clock.millis().minusSeconds(60)) }
        clock.advance(days(1))
        service.sendDue()
        assertEquals("6 Neuigkeiten", android.sent.last().second.title)
        assertEquals("Aushang 1\nAushang 2\nAushang 3\nAushang 4\n… und 2 weitere", android.sent.last().second.body)
        assertTrue(digestRepository.items.isEmpty())
    }
}
