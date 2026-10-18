package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeEventInvitationRepository
import com.lerchenflo.hufly.server.repository.FakeEventRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request
import tools.jackson.databind.ObjectMapper
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** EVT-1..EVT-8: HTTP mapping, validation and version sync. Business rules live in EventServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class EventControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository
    @Autowired lateinit var horseRepository: FakeHorseRepository
    @Autowired lateinit var eventRepository: FakeEventRepository
    @Autowired lateinit var invitationRepository: FakeEventInvitationRepository

    private val admin = testUser()
    private val anna = testUser()
    private val ben = testUser()
    private val blitz = testHorse()

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        horseRepository.horses.clear()
        eventRepository.events.clear()
        invitationRepository.invitations.clear()
        listOf(admin, anna, ben).forEach { userRepository.save(it) }
        horseRepository.save(blitz)
        stableRepository.save(testStable(adminUserId = admin.id))
    }

    private fun call(method: HttpMethod, path: String, body: String? = null, userId: ObjectId = admin.id) =
        mockMvc.request(method, path) {
            header("Authorization", "Bearer ${jwtService.generateAccessToken(userId)}")
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    private fun eventJson(title: String = "Reitstunde", endAt: String = "1790003600000") =
        """{"title":"$title","description":"Halle","startAt":1790000000000,"endAt":$endAt,"horseIds":["${blitz.id.toHexString()}"],""" +
            """"inviteeUserIds":["${anna.id.toHexString()}"]}"""

    private fun createEvent(): String =
        objectMapper.readTree(call(HttpMethod.POST, "/events", eventJson()).andReturn().response.contentAsString)["id"].asString()

    @Test
    fun `create event answers with the event`() {
        call(HttpMethod.POST, "/events", eventJson()).andExpect {
            status { isOk() }
            jsonPath("$.title") { value("Reitstunde") }
            jsonPath("$.creatorUserId") { value(admin.id.toHexString()) }
            jsonPath("$.horseIds[0]") { value(blitz.id.toHexString()) }
            jsonPath("$.version") { isNumber() }
        }
    }

    @Test
    fun `invitee syncs event and invitation, then accepts`() {
        val eventId = createEvent()

        call(HttpMethod.GET, "/events/sync?since=0", userId = anna.id).andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries[0].id") { value(eventId) }
        }
        val invitations = objectMapper.readTree(
            call(HttpMethod.GET, "/eventinvitations/sync?since=0", userId = anna.id).andReturn().response.contentAsString,
        )
        val invitationId = invitations["updatedEntries"][0]["id"].asString()

        call(HttpMethod.POST, "/eventinvitations/$invitationId/answer", """{"accepted":true}""", userId = anna.id).andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ACCEPTED") }
            jsonPath("$.respondedAt") { isNumber() }
        }
    }

    @Test
    fun `invite, remove invitation, edit and delete`() {
        val eventId = createEvent()

        call(HttpMethod.POST, "/events/$eventId/invitations", """{"userIds":["${ben.id.toHexString()}"]}""").andExpect { status { isOk() } }
        val benInvitation = invitationRepository.invitations.single { it.userId == ben.id && !it.deleted }.id.toHexString()
        call(HttpMethod.DELETE, "/eventinvitations/$benInvitation").andExpect { status { isOk() } }
        call(HttpMethod.PUT, "/events/$eventId", eventJson(title = "Springen")).andExpect {
            jsonPath("$.title") { value("Springen") }
        }
        call(HttpMethod.DELETE, "/events/$eventId").andExpect { status { isOk() } }
    }

    @Test
    fun `member without EVENT_EDIT cannot create`() {
        call(HttpMethod.POST, "/events", eventJson(), userId = anna.id).andExpect { status { isForbidden() } }
    }

    @Test
    fun `validation errors answer 400`() {
        call(HttpMethod.POST, "/events", eventJson(title = "")).andExpect { status { isBadRequest() } }
        call(HttpMethod.POST, "/events", eventJson(endAt = "9000000000000000000")).andExpect { status { isBadRequest() } }
        call(HttpMethod.DELETE, "/events/nope").andExpect { status { isBadRequest() } }
        call(HttpMethod.GET, "/eventinvitations/sync?since=-1").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `retried create with the same clientId answers 200 with the same event`() {
        val body = eventJson().replace("{\"title\"", "{\"clientId\":\"local-1\",\"title\"")
        val first = objectMapper.readTree(call(HttpMethod.POST, "/events", body).andReturn().response.contentAsString)["id"].asString()

        call(HttpMethod.POST, "/events", body).andExpect {
            status { isOk() }
            jsonPath("$.id") { value(first) }
        }
    }

    @Test
    fun `clientId longer than 64 characters answers 400`() {
        val body = eventJson().replace("{\"title\"", "{\"clientId\":\"${"x".repeat(65)}\",\"title\"")

        call(HttpMethod.POST, "/events", body).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `invite answers the live invitations of the event`() {
        val eventId = createEvent()

        call(HttpMethod.POST, "/events/$eventId/invitations", """{"userIds":["${ben.id.toHexString()}"]}""").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].eventId") { value(eventId) }
        }
    }

    // Who sees the participants (client 2026-10-18)

    @Test
    fun `an event stores whether invitees see who accepted, a body without it means yes`() {
        val created = call(HttpMethod.POST, "/events", lessonJson(""""participantsVisible":false""")).andExpect {
            status { isOk() }
            jsonPath("$.participantsVisible") { value(false) }
        }
        val eventId = objectMapper.readTree(created.andReturn().response.contentAsString)["id"].asString()
        assertEquals(false, eventRepository.events.single().participantsVisible)

        call(HttpMethod.PUT, "/events/$eventId", eventJson()).andExpect {
            status { isOk() }
            jsonPath("$.participantsVisible") { value(true) }
        }
        assertEquals(true, eventRepository.events.single().participantsVisible)
    }

    // Lessons (client schema 37; supersede EVT-7)

    private fun lessonJson(lesson: String) =
        """{"title":"Springstunde","startAt":1790000000000,"endAt":1790003600000,"horseIds":["${blitz.id.toHexString()}"],$lesson}"""

    private fun eventType() = tagRepository.save(testTag(type = TagType.EVENT_TYPE))

    @Test
    fun `a lesson stores its type, places, lock, answer time and order and answers them`() {
        val type = eventType()
        val lesson = """"eventTypeId":"${type.id.toHexString()}","maxParticipants":4,"leaveLockHours":24,"answerHours":12,""" +
            """"participantOrder":["${ben.id.toHexString()}","${anna.id.toHexString()}"]"""

        call(HttpMethod.POST, "/events", lessonJson(lesson)).andExpect {
            status { isOk() }
            jsonPath("$.eventTypeId") { value(type.id.toHexString()) }
            jsonPath("$.maxParticipants") { value(4) }
            jsonPath("$.leaveLockHours") { value(24) }
            jsonPath("$.answerHours") { value(12) }
            jsonPath("$.participantOrder[0]") { value(ben.id.toHexString()) }
            jsonPath("$.participantOrder[1]") { value(anna.id.toHexString()) }
        }
        val stored = eventRepository.events.single()
        assertEquals(listOf(ben.id, anna.id), stored.participantOrder)
        assertEquals(type.id, stored.eventTypeId)
    }

    @Test
    fun `a plain event answers null lesson fields and an empty order`() {
        call(HttpMethod.POST, "/events", eventJson()).andExpect {
            status { isOk() }
            jsonPath("$.eventTypeId") { value(null) }
            jsonPath("$.maxParticipants") { value(null) }
            jsonPath("$.leaveLockHours") { value(null) }
            jsonPath("$.answerHours") { value(null) }
            jsonPath("$.participantOrder") { isEmpty() }
        }
    }

    @Test
    fun `an edit without the lesson fields clears them`() {
        val created = call(HttpMethod.POST, "/events", lessonJson(""""maxParticipants":4,"leaveLockHours":24,"answerHours":12,"participantOrder":["${anna.id.toHexString()}"]"""))
        val eventId = objectMapper.readTree(created.andReturn().response.contentAsString)["id"].asString()

        call(HttpMethod.PUT, "/events/$eventId", eventJson()).andExpect {
            status { isOk() }
            jsonPath("$.maxParticipants") { value(null) }
            jsonPath("$.leaveLockHours") { value(null) }
            jsonPath("$.answerHours") { value(null) }
            jsonPath("$.participantOrder") { isEmpty() }
        }
        val stored = eventRepository.events.single()
        assertEquals(listOf(null, null, null), listOf(stored.maxParticipants, stored.leaveLockHours, stored.answerHours))
        assertTrue(stored.participantOrder.isEmpty())
    }

    @Test
    fun `lesson fields out of range answer 400`() {
        val eventId = createEvent()
        val outOfRange = listOf(
            "maxParticipants" to "0", "maxParticipants" to "100",
            "leaveLockHours" to "-1", "leaveLockHours" to "337",
            "answerHours" to "-1", "answerHours" to "337",
            "participantOrder" to (1..501).joinToString(",", "[", "]") { "\"${anna.id.toHexString()}\"" },
            "participantOrder" to "[\"nope\"]",
            "eventTypeId" to "\"nope\"",
        )
        outOfRange.forEach { (field, value) ->
            call(HttpMethod.POST, "/events", lessonJson(""""$field":$value""")).andExpect { status { isBadRequest() } }
            call(HttpMethod.PUT, "/events/$eventId", lessonJson(""""$field":$value""")).andExpect { status { isBadRequest() } }
        }
        call(HttpMethod.POST, "/events", lessonJson(""""maxParticipants":99,"leaveLockHours":336,"answerHours":0""")).andExpect { status { isOk() } }
        call(HttpMethod.PUT, "/events/$eventId", lessonJson(""""maxParticipants":1,"leaveLockHours":0,"answerHours":336""")).andExpect { status { isOk() } }
    }
}
