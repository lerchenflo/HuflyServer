package com.lerchenflo.hufly.server.notification

import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.core.logging.LogUser
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.notification.model.PushMessage
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import com.lerchenflo.hufly.server.repository.FakeRefreshTokenRepository
import com.lerchenflo.hufly.server.testdata.LogCapture
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.minutes
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Push tokens live on the session, so ending a session stops its pushes. */
class PushServiceTest {

    private val clock = MutableClock()
    private val sessions = FakeRefreshTokenRepository()
    private val android = FakePushSender(PushPlatform.ANDROID)
    private val ios = FakePushSender(PushPlatform.IOS)
    private val pushService = PushService(sessions, listOf(android, ios), Executor { it.run() }, clock)

    private val anna = ObjectId.get()
    private val ben = ObjectId.get()
    private val message = PushMessage("Neuer Aushang", "Hufschmied kommt", mapOf("type" to "note"))
    private val logs = LogCapture(PushService::class.java)

    @AfterTest
    fun detachLogs() {
        logs.close()
    }

    private fun session(userId: ObjectId, expiresIn: Long = days(30)) = sessions.save(
        RefreshToken(userId = userId, hashedToken = ObjectId.get().toHexString(), expiresAt = clock.millis().plus(expiresIn), createdAt = clock.millis())
    )

    @Test
    fun `a registered token gets the user's pushes on its platform`() {
        val phone = session(anna)
        val tablet = session(anna)
        pushService.register(anna, phone.id, PushPlatform.ANDROID, "fcm-1")
        pushService.register(anna, tablet.id, PushPlatform.IOS, "apns-1")

        pushService.send(anna, message)

        assertEquals(listOf("fcm-1" to message), android.sent)
        assertEquals(listOf("apns-1" to message), ios.sent)
    }

    @Test
    fun `a token moves to the session that registers it last, so another account on the phone gets nothing`() {
        val annaSession = session(anna)
        val benSession = session(ben)
        pushService.register(anna, annaSession.id, PushPlatform.ANDROID, "shared-phone")

        pushService.register(ben, benSession.id, PushPlatform.ANDROID, "shared-phone")
        pushService.send(anna, message)

        assertEquals(emptyList(), android.sent)
        assertNull(sessions.findById(annaSession.id)!!.pushToken)
    }

    @Test
    fun `unregistering, ending or expiring the session stops pushes`() {
        val phone = session(anna)
        pushService.register(anna, phone.id, PushPlatform.ANDROID, "fcm-1")
        pushService.unregister(anna, phone.id)
        pushService.send(anna, message)

        val expired = session(anna, expiresIn = minutes(1))
        pushService.register(anna, expired.id, PushPlatform.ANDROID, "fcm-2")
        clock.advance(minutes(2))
        pushService.send(anna, message)

        assertEquals(emptyList(), android.sent)
    }

    @Test
    fun `tokens the provider rejects are removed`() {
        val phone = session(anna)
        pushService.register(anna, phone.id, PushPlatform.ANDROID, "stale")
        android.invalidTokens += "stale"

        pushService.send(anna, message)

        assertNull(sessions.findById(phone.id)!!.pushToken)
    }

    @Test
    fun `only the own live session registers a token`() {
        val bens = session(ben)
        assertEquals(
            HttpStatus.NOT_FOUND,
            assertFailsWith<ResponseStatusException> { pushService.register(anna, bens.id, PushPlatform.ANDROID, "x") }.statusCode,
        )
        assertEquals(
            HttpStatus.NOT_FOUND,
            assertFailsWith<ResponseStatusException> { pushService.register(anna, ObjectId.get(), PushPlatform.ANDROID, "x") }.statusCode,
        )
    }

    // Logging, to see on the server why a phone gets nothing; never the push's text, it holds names.

    @Test
    fun `every push attempt is logged with type, account, platform and session`() {
        val phone = session(anna)
        val tablet = session(anna)
        val stale = session(anna)
        pushService.register(anna, phone.id, PushPlatform.ANDROID, "fcm-1")
        pushService.register(anna, tablet.id, PushPlatform.IOS, "apns-1")
        pushService.register(anna, stale.id, PushPlatform.IOS, "apns-stale")
        ios.invalidTokens += "apns-stale"

        pushService.send(anna, message)

        assertEquals(
            setOf(
                "INFO Push note to ${LogUser(anna)} sent (ANDROID, session ${phone.id})",
                "INFO Push note to ${LogUser(anna)} sent (IOS, session ${tablet.id})",
                "WARN Push note to ${LogUser(anna)}: token rejected (IOS, session ${stale.id}), removed",
            ),
            logs.lines.toSet(),
        )
        assertTrue(logs.lines.none { "Neuer Aushang" in it || "Hufschmied" in it })
    }

    @Test
    fun `a push without a device and a failed push are logged`() {
        pushService.send(ben, message)
        val phone = session(anna)
        pushService.register(anna, phone.id, PushPlatform.IOS, "apns-1")
        ios.failingTokens += "apns-1"

        pushService.send(anna, message)

        assertEquals(
            listOf(
                "INFO Push note to ${LogUser(ben)}: no device",
                "WARN Push note to ${LogUser(anna)} failed (IOS, session ${phone.id})",
            ),
            logs.lines,
        )
    }
}
