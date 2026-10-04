package com.lerchenflo.hufly.server.realtime

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testUser
import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.messaging.MessageHeaders
import org.springframework.messaging.converter.StringMessageConverter
import org.springframework.messaging.simp.stomp.StompFrameHandler
import org.springframework.messaging.simp.stomp.StompHeaders
import org.springframework.messaging.simp.stomp.StompSession
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import java.lang.reflect.Type
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Live hints over STOMP: connected members of a stable learn which collection changed and then sync it. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class RealtimeIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")

        const val CHANGES = "/user/queue/changes"
    }

    @LocalServerPort var port: Int = 0
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var horseRepository: HorseRepository
    @Autowired lateinit var settingsRepository: UserSettingsRepository

    private class Inbox : StompFrameHandler {
        val messages = LinkedBlockingQueue<String>()
        override fun getPayloadType(headers: StompHeaders): Type = String::class.java
        override fun handleFrame(headers: StompHeaders, payload: Any?) {
            messages += payload as String
        }
        fun next(): String? = messages.poll(3, TimeUnit.SECONDS)
        fun nothing(): Boolean = messages.poll(500, TimeUnit.MILLISECONDS) == null
    }

    private fun connect(userId: ObjectId?): StompSession {
        val client = WebSocketStompClient(StandardWebSocketClient()).apply {
            messageConverter = object : StringMessageConverter() {
                override fun supportsMimeType(headers: MessageHeaders?) = true
            }
        }
        val headers = WebSocketHttpHeaders()
        if (userId != null) headers.setBearerAuth(jwtService.generateAccessToken(userId))
        return client.connectAsync("ws://localhost:$port/ws", headers, object : StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS)
    }

    private fun subscribe(session: StompSession, destination: String = CHANGES): Inbox {
        val inbox = Inbox()
        session.subscribe(destination, inbox)
        Thread.sleep(300)
        return inbox
    }

    @Test
    fun `members of the stable get a hint when a horse changes, other stables do not`() {
        val users = listOf(testUser(), testUser(), testUser(stableId = OTHER_STABLE_ID)).map { userRepository.save(it) }
        val (anna, ben, foreigner) = users.map { subscribe(connect(it.id)) }

        horseRepository.save(testHorse())

        assertEquals("""{"type":"changed","collection":"horses"}""", anna.next())
        assertEquals("""{"type":"changed","collection":"horses"}""", ben.next())
        assertTrue(foreigner.nothing())
    }

    @Test
    fun `settings hints go only to their owner`() {
        val annaUser = userRepository.save(testUser())
        val benUser = userRepository.save(testUser())
        val anna = subscribe(connect(annaUser.id))
        val ben = subscribe(connect(benUser.id))

        settingsRepository.save(UserSettings(annaUser.id, mapOf("theme" to "dark"), Instant.EPOCH))

        assertEquals("""{"type":"changed","collection":"usersettings"}""", anna.next())
        assertTrue(ben.nothing())
    }

    @Test
    fun `the broker agrees to heart-beats a client asks for`() {
        val connected = CompletableFuture<StompHeaders>()
        val client = WebSocketStompClient(StandardWebSocketClient()).apply {
            taskScheduler = ThreadPoolTaskScheduler().apply { initialize() }
        }
        val handshake = WebSocketHttpHeaders().apply {
            setBearerAuth(jwtService.generateAccessToken(userRepository.save(testUser()).id))
        }
        val connect = StompHeaders().apply { heartbeat = longArrayOf(10000, 10000) }

        client.connectAsync(URI("ws://localhost:$port/ws"), handshake, connect, object : StompSessionHandlerAdapter() {
            override fun afterConnected(session: StompSession, connectedHeaders: StompHeaders) {
                connected.complete(connectedHeaders)
            }
        })

        assertEquals(listOf(10000L, 10000L), connected.get(5, TimeUnit.SECONDS).heartbeat?.toList())
    }

    @Test
    fun `handshake without a token is rejected`() {
        assertFailsWith<Exception> { connect(null) }
    }

    @Test
    fun `subscribing outside the own user queue closes the session`() {
        val session = connect(userRepository.save(testUser()).id)

        subscribe(session, "/topic/stable/${OTHER_STABLE_ID.toHexString()}")
        Thread.sleep(500)

        assertFalse(session.isConnected)
    }

    @Test
    fun `sending frames is not allowed`() {
        val session = connect(userRepository.save(testUser()).id)

        session.send("/app/anything", "hello")
        Thread.sleep(500)

        assertFalse(session.isConnected)
    }
}
