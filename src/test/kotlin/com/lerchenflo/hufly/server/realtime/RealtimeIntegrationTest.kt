package com.lerchenflo.hufly.server.realtime

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.event.model.EventOccurrence
import com.lerchenflo.hufly.server.event.model.EventOccurrenceAnswer
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.paddock.model.HorseGroup
import com.lerchenflo.hufly.server.repository.EventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.EventOccurrenceRepository
import com.lerchenflo.hufly.server.repository.HorseGroupRepository
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.TaskOccurrenceRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.task.model.TaskOccurrence
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.epochDay
import com.lerchenflo.hufly.server.testdata.millis
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
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
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.messaging.WebSocketStompClient
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.lang.reflect.Type
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CompletableFuture
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
    @Autowired lateinit var stableRepository: StableRepository
    @Autowired lateinit var groupRepository: HorseGroupRepository
    @Autowired lateinit var eventOccurrenceRepository: EventOccurrenceRepository
    @Autowired lateinit var answerRepository: EventOccurrenceAnswerRepository
    @Autowired lateinit var taskOccurrenceRepository: TaskOccurrenceRepository
    @Autowired lateinit var noteRepository: com.lerchenflo.hufly.server.repository.NoteRepository
    @Autowired lateinit var absenceRepository: com.lerchenflo.hufly.server.repository.AbsenceRepository
    @Autowired lateinit var noteService: com.lerchenflo.hufly.server.note.NoteService

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
    fun `dates of series send hints under the client's collection names`() {
        val stableId = ObjectId.get()
        val inbox = subscribe(connect(userRepository.save(testUser(stableId = stableId)).id))
        val user = ObjectId.get()
        val at = millis("2026-10-20T16:00:00Z")

        eventOccurrenceRepository.save(
            EventOccurrence(stableId = stableId, eventId = ObjectId.get(), occurrenceStartAt = at, cancelled = true, title = null,
                description = null, startAt = null, endAt = null, horseIds = null, updatedAt = at, updatedBy = user)
        )
        answerRepository.save(
            EventOccurrenceAnswer(stableId = stableId, eventId = ObjectId.get(), invitationId = ObjectId.get(), userId = user,
                occurrenceStartAt = at, status = InvitationStatus.DECLINED, respondedAt = at, updatedAt = at, updatedBy = user)
        )
        taskOccurrenceRepository.save(TaskOccurrence(stableId = stableId, taskId = ObjectId.get(), occurrenceDueAt = at, updatedAt = at, updatedBy = user))

        assertEquals(hint("eventoccurrences"), inbox.next())
        assertEquals(hint("eventoccurrenceanswers"), inbox.next())
        assertEquals(hint("taskoccurrences"), inbox.next())
    }

    @Test
    fun `notes and their atomic read marks send hints under the collection name note`() {
        val stableId = ObjectId.get()
        val reader = userRepository.save(testUser(stableId = stableId))
        val inbox = subscribe(connect(reader.id))
        val at = millis("2026-10-05T08:00:00Z")
        val note = noteRepository.save(
            com.lerchenflo.hufly.server.note.model.StableNote(stableId = stableId, title = "Hufschmied", body = "", pinned = false,
                visibleUntil = null, createdByUserId = ObjectId.get(), createdAt = at, updatedAt = at, updatedBy = ObjectId.get())
        )
        assertEquals(hint("note"), inbox.next())

        noteService.markRead(reader, note.id)

        assertEquals(hint("note"), inbox.next())
    }

    @Test
    fun `absences send hints under the collection name absence`() {
        val stableId = ObjectId.get()
        val member = userRepository.save(testUser(stableId = stableId))
        val inbox = subscribe(connect(member.id))

        absenceRepository.save(
            com.lerchenflo.hufly.server.absence.model.Absence(
                stableId = stableId, userId = member.id, from = epochDay(2026, 10, 12), until = epochDay(2026, 10, 12),
                note = "", createdByUserId = member.id, updatedAt = 0L, updatedBy = member.id,
            )
        )

        assertEquals(hint("absence"), inbox.next())
    }

    @Test
    fun `settings hints go only to their owner`() {
        val annaUser = userRepository.save(testUser())
        val benUser = userRepository.save(testUser())
        val anna = subscribe(connect(annaUser.id))
        val ben = subscribe(connect(benUser.id))

        settingsRepository.save(UserSettings(annaUser.id, mapOf("theme" to "dark"), 0L))

        assertEquals("""{"type":"changed","collection":"usersettings"}""", anna.next())
        assertTrue(ben.nothing())
    }

    private val http = HttpClient.newHttpClient()

    private fun call(method: String, path: String, userId: ObjectId, sessionId: ObjectId?, body: String? = null): Int {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
            .header("Authorization", "Bearer ${jwtService.generateAccessToken(userId, sessionId)}")
            .header("Content-Type", "application/json")
            .method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
            .build()
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
    }

    private fun adminOfNewStable(): Pair<ObjectId, ObjectId> {
        val stableId = ObjectId.get()
        val admin = userRepository.save(testUser(stableId = stableId))
        stableRepository.save(testStable(id = stableId, adminUserId = admin.id))
        return admin.id to stableId
    }

    private fun hint(collection: String, origin: ObjectId? = null) =
        if (origin == null) """{"type":"changed","collection":"$collection"}"""
        else """{"type":"changed","collection":"$collection","originSessionId":"${origin.toHexString()}"}"""

    @Test
    fun `the hint of the document a request answers names the requesting session for every member`() {
        val (adminId, stableId) = adminOfNewStable()
        val member = userRepository.save(testUser(stableId = stableId))
        val session = ObjectId.get()
        val adminInbox = subscribe(connect(adminId))
        val memberInbox = subscribe(connect(member.id))

        val status = call("POST", "/tags", adminId, session, """{"name":"Hufschmied","type":"ACTIVITY","color":"#8d6e63"}""")

        assertEquals(200, status)
        assertEquals(hint("tags", session), adminInbox.next())
        assertEquals(hint("tags", session), memberInbox.next())
    }

    @Test
    fun `side effects of a request carry no origin`() {
        val (adminId, stableId) = adminOfNewStable()
        val horse = horseRepository.save(testHorse(stableId = stableId))
        groupRepository.save(HorseGroup(stableId = stableId, name = "Wallache", horseIds = listOf(horse.id), updatedAt = 0L, updatedBy = adminId))
        val session = ObjectId.get()
        val inbox = subscribe(connect(adminId))

        val status = call("DELETE", "/horses/${horse.id.toHexString()}", adminId, session)

        assertEquals(200, status)
        assertEquals(setOf(hint("horses", session), hint("horsegroups")), setOf(inbox.next(), inbox.next()))
    }

    @Test
    fun `a token without a session sends no origin`() {
        val (adminId, _) = adminOfNewStable()
        val inbox = subscribe(connect(adminId))

        call("POST", "/tags", adminId, null, """{"name":"Hafer","type":"FOOD","color":"#8d6e63"}""")

        assertEquals(hint("tags"), inbox.next())
    }

    @Test
    fun `a guarded settings save tells the owner's devices and names the session`() {
        val owner = userRepository.save(testUser())
        settingsRepository.save(UserSettings(owner.id, mapOf("theme" to "dark"), 1000))
        val session = ObjectId.get()
        val inbox = subscribe(connect(owner.id))

        val status = call("PUT", "/users/me/settings", owner.id, session, """{"values":{"theme":"light"},"expectedUpdatedAt":1000}""")

        assertEquals(200, status)
        assertEquals(hint("usersettings", session), inbox.next())
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
