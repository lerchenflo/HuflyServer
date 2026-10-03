package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeEventInvitationRepository
import com.lerchenflo.hufly.server.repository.FakeEventRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
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
}
