package com.lerchenflo.hufly.server.event

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeEventInvitationRepository
import com.lerchenflo.hufly.server.repository.FakeEventOccurrenceAnswerRepository
import com.lerchenflo.hufly.server.repository.FakeEventOccurrenceRepository
import com.lerchenflo.hufly.server.repository.FakeEventRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
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

/** EVT-10: HTTP mapping of series, date changes, per-date answers and date invitations. Rules live in EventSeriesServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class EventSeriesControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var horseRepository: FakeHorseRepository
    @Autowired lateinit var eventRepository: FakeEventRepository
    @Autowired lateinit var invitationRepository: FakeEventInvitationRepository
    @Autowired lateinit var occurrenceRepository: FakeEventOccurrenceRepository
    @Autowired lateinit var answerRepository: FakeEventOccurrenceAnswerRepository

    private val admin = testUser()
    private val anna = testUser()
    private val ben = testUser()
    private val blitz = testHorse()

    // Tuesday 2026-10-13 16:00 UTC; the second date is a week later.
    private val first = 1791907200000L
    private val second = first + 7 * 86_400_000L
    private val weekly = """{"frequency":"WEEKLY","interval":1,"weekdays":["TUESDAY"],"until":null,"count":4,"timeZone":"UTC"}"""

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        horseRepository.horses.clear()
        eventRepository.events.clear()
        invitationRepository.invitations.clear()
        occurrenceRepository.occurrences.clear()
        answerRepository.answers.clear()
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

    private fun eventJson(recurrence: String? = weekly) =
        """{"title":"Springstunde","description":"","startAt":$first,"endAt":${first + 3_600_000},""" +
            """"horseIds":["${blitz.id.toHexString()}"],"inviteeUserIds":["${anna.id.toHexString()}"]""" +
            (recurrence?.let { ""","recurrence":$it""" } ?: "") + "}"

    private fun createSeries(): String =
        objectMapper.readTree(call(HttpMethod.POST, "/events", eventJson()).andReturn().response.contentAsString)["id"].asString()

    private fun occurrenceJson(title: String? = "\"Dressur\"", horseIds: String = "null") =
        """{"cancelled":false,"title":$title,"description":null,"startAt":null,"endAt":null,"horseIds":$horseIds}"""

    @Test
    fun `a series answers and syncs with its rule, an update without one clears it`() {
        val eventId = createSeries()

        call(HttpMethod.GET, "/events/sync?since=0").andExpect {
            jsonPath("$.updatedEntries[0].recurrence.frequency") { value("WEEKLY") }
            jsonPath("$.updatedEntries[0].recurrence.weekdays[0]") { value("TUESDAY") }
            jsonPath("$.updatedEntries[0].recurrence.count") { value(4) }
            jsonPath("$.updatedEntries[0].recurrence.until") { value(null) }
            jsonPath("$.updatedEntries[0].recurrence.timeZone") { value("UTC") }
        }
        call(HttpMethod.PUT, "/events/$eventId", eventJson(recurrence = null)).andExpect {
            status { isOk() }
            jsonPath("$.recurrence") { value(null) }
        }
    }

    @Test
    fun `an invalid rule answers 400`() {
        call(HttpMethod.POST, "/events", eventJson(recurrence = """{"frequency":"MONTHLY","timeZone":"UTC"}"""))
            .andExpect { status { isBadRequest() } }
        call(HttpMethod.POST, "/events", eventJson(recurrence = """{"frequency":"DAILY","timeZone":"Nowhere/City"}"""))
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a date change answers the change and syncs`() {
        val eventId = createSeries()

        call(HttpMethod.PUT, "/events/$eventId/occurrences/$second", occurrenceJson()).andExpect {
            status { isOk() }
            jsonPath("$.eventId") { value(eventId) }
            jsonPath("$.occurrenceStartAt") { value(second) }
            jsonPath("$.cancelled") { value(false) }
            jsonPath("$.title") { value("Dressur") }
            jsonPath("$.description") { value(null) }
            jsonPath("$.horseIds") { value(null) }
            jsonPath("$.version") { isNumber() }
        }
        call(HttpMethod.GET, "/eventoccurrences/sync?since=0", userId = anna.id).andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries[0].title") { value("Dressur") }
        }
    }

    @Test
    fun `invalid date changes answer 400`() {
        val eventId = createSeries()
        val tooManyHorses = List(51) { "\"${blitz.id.toHexString()}\"" }.joinToString(",", "[", "]")

        call(HttpMethod.PUT, "/events/$eventId/occurrences/${second + 1}", occurrenceJson()).andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, "/events/$eventId/occurrences/-1", occurrenceJson()).andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, "/events/$eventId/occurrences/soon", occurrenceJson()).andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, "/events/$eventId/occurrences/$second", occurrenceJson(horseIds = tooManyHorses)).andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, "/events/$eventId/occurrences/$second", occurrenceJson(horseIds = """["nope"]""")).andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, "/events/$eventId/occurrences/$second", occurrenceJson(), userId = anna.id).andExpect { status { isForbidden() } }
    }

    @Test
    fun `a date change takes series invitees off the date`() {
        val eventId = createSeries()
        val body = """{"cancelled":false,"title":null,"description":null,"startAt":null,"endAt":null,"horseIds":null,"removedUserIds":["${anna.id.toHexString()}"]}"""

        call(HttpMethod.PUT, "/events/$eventId/occurrences/$second", body).andExpect {
            status { isOk() }
            jsonPath("$.removedUserIds[0]") { value(anna.id.toHexString()) }
        }
        call(HttpMethod.PUT, "/events/$eventId/occurrences/$second", occurrenceJson()).andExpect {
            status { isOk() }
            jsonPath("$.removedUserIds") { value(null) }
        }
        val unknown = body.replace(anna.id.toHexString(), ben.id.toHexString())
        call(HttpMethod.PUT, "/events/$eventId/occurrences/$second", unknown).andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, "/events/$eventId/occurrences/$second", body.replace(anna.id.toHexString(), "nope")).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `an invitee answers one date and the organiser syncs it`() {
        createSeries()
        val invitationId = invitationRepository.invitations.single { it.userId == anna.id }.id.toHexString()

        call(HttpMethod.PUT, "/eventinvitations/$invitationId/occurrences/$second/answer", """{"accepted":false}""", userId = anna.id).andExpect {
            status { isOk() }
            jsonPath("$.invitationId") { value(invitationId) }
            jsonPath("$.userId") { value(anna.id.toHexString()) }
            jsonPath("$.occurrenceStartAt") { value(second) }
            jsonPath("$.status") { value("DECLINED") }
            jsonPath("$.respondedAt") { isNumber() }
        }
        call(HttpMethod.GET, "/eventoccurrenceanswers/sync?since=0").andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries[0].status") { value("DECLINED") }
        }
    }

    @Test
    fun `inviting to one date answers invitations carrying the date`() {
        val eventId = createSeries()

        call(HttpMethod.POST, "/events/$eventId/invitations", """{"userIds":["${ben.id.toHexString()}"],"occurrenceStartAt":$second}""").andExpect {
            status { isOk() }
            jsonPath("$[?(@.userId == '${ben.id.toHexString()}')].occurrenceStartAt") { value(second) }
            jsonPath("$[?(@.userId == '${anna.id.toHexString()}')].occurrenceStartAt") { value(null) }
        }
    }

    @Test
    fun `sync endpoints validate their parameters and need a token`() {
        call(HttpMethod.GET, "/eventoccurrences/sync?since=-1").andExpect { status { isBadRequest() } }
        call(HttpMethod.GET, "/eventoccurrenceanswers/sync?page_size=0").andExpect { status { isBadRequest() } }
        mockMvc.request(HttpMethod.GET, "/eventoccurrences/sync").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `a split create stores its origin, both fields or none`() {
        val oldId = createSeries()
        val second = first + 7 * 86_400_000L
        fun splitJson(fields: String) =
            """{"title":"Neu","startAt":$second,"endAt":${second + 3_600_000},"horseIds":["${blitz.id.toHexString()}"],""" +
                """"recurrence":$weekly$fields}"""

        call(HttpMethod.POST, "/events", splitJson(""","splitFromEventId":"$oldId","splitFromOccurrenceStartAt":$second""")).andExpect {
            status { isOk() }
            jsonPath("$.splitFromEventId") { value(oldId) }
            jsonPath("$.splitFromOccurrenceStartAt") { value(second) }
        }
        call(HttpMethod.POST, "/events", splitJson(""","splitFromEventId":"$oldId"""")).andExpect { status { isBadRequest() } }
        call(HttpMethod.POST, "/events", splitJson(""","splitFromOccurrenceStartAt":$second""")).andExpect { status { isBadRequest() } }
        call(HttpMethod.POST, "/events", splitJson("")).andExpect {
            status { isOk() }
            jsonPath("$.splitFromEventId") { value(null) }
        }
    }
}
