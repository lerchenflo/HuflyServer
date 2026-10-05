package com.lerchenflo.hufly.server.task

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTaskOccurrenceRepository
import com.lerchenflo.hufly.server.repository.FakeTaskRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
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

/** TSK-5: HTTP mapping of task series, date changes and ticks. Rules live in TaskSeriesServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class TaskSeriesControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var taskRepository: FakeTaskRepository
    @Autowired lateinit var occurrenceRepository: FakeTaskOccurrenceRepository

    private val admin = testUser()
    private val anna = testUser()

    // 2026-10-05 07:00 UTC, daily.
    private val first = 1791190800000L
    private val second = first + 86_400_000L
    private val daily = """{"frequency":"DAILY","interval":1,"weekdays":[],"until":"2026-10-31","count":null,"timeZone":"UTC"}"""

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        taskRepository.tasks.clear()
        occurrenceRepository.occurrences.clear()
        listOf(admin, anna).forEach { userRepository.save(it) }
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

    private fun taskJson(recurrence: String? = daily) =
        """{"title":"Misten","comment":"","dueAt":$first,"assigneeUserIds":["${anna.id.toHexString()}"],"horseIds":[]""" +
            (recurrence?.let { ""","recurrence":$it""" } ?: "") + "}"

    private fun createSeries(): String =
        objectMapper.readTree(call(HttpMethod.POST, "/tasks", taskJson()).andReturn().response.contentAsString)["id"].asString()

    private val occurrenceJson = """{"cancelled":false,"title":null,"comment":"Mehr Heu","dueAt":${second + 3_600_000},"horseIds":null}"""

    @Test
    fun `a series answers with its rule, an update without one clears it`() {
        call(HttpMethod.POST, "/tasks", taskJson()).andExpect {
            status { isOk() }
            jsonPath("$.recurrence.frequency") { value("DAILY") }
            jsonPath("$.recurrence.until") { value("2026-10-31") }
            jsonPath("$.recurrence.weekdays.length()") { value(0) }
        }
        val taskId = taskRepository.tasks.single().id.toHexString()
        call(HttpMethod.PUT, "/tasks/$taskId", taskJson(recurrence = null)).andExpect {
            status { isOk() }
            jsonPath("$.recurrence") { value(null) }
        }
    }

    @Test
    fun `an until before the first date answers 400`() {
        call(HttpMethod.POST, "/tasks", taskJson(recurrence = """{"frequency":"DAILY","until":"2026-10-04","timeZone":"UTC"}"""))
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a date change and a tick answer the date and sync`() {
        val taskId = createSeries()

        call(HttpMethod.PUT, "/tasks/$taskId/occurrences/$second", occurrenceJson).andExpect {
            status { isOk() }
            jsonPath("$.taskId") { value(taskId) }
            jsonPath("$.occurrenceDueAt") { value(second) }
            jsonPath("$.comment") { value("Mehr Heu") }
            jsonPath("$.dueAt") { value(second + 3_600_000) }
            jsonPath("$.doneByUserId") { value(null) }
        }
        call(HttpMethod.POST, "/tasks/$taskId/occurrences/$second/done", """{"done":true}""", userId = anna.id).andExpect {
            status { isOk() }
            jsonPath("$.doneByUserId") { value(anna.id.toHexString()) }
            jsonPath("$.doneAt") { isNumber() }
            jsonPath("$.comment") { value("Mehr Heu") }
        }
        call(HttpMethod.GET, "/taskoccurrences/sync?since=0", userId = anna.id).andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries.length()") { value(1) }
        }
    }

    @Test
    fun `ticking the whole series answers 400`() {
        val taskId = createSeries()

        call(HttpMethod.POST, "/tasks/$taskId/done", """{"done":true}""", userId = anna.id).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `invalid date requests answer 400 or 403`() {
        val taskId = createSeries()

        call(HttpMethod.PUT, "/tasks/$taskId/occurrences/${second + 1}", occurrenceJson).andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, "/tasks/$taskId/occurrences/99999999999999999", occurrenceJson).andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, "/tasks/$taskId/occurrences/$second", occurrenceJson, userId = anna.id).andExpect { status { isForbidden() } }
        call(HttpMethod.GET, "/taskoccurrences/sync?since=-1").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a date carries its own assignees`() {
        val taskId = createSeries()
        val body = occurrenceJson.replace("\"horseIds\":null", "\"horseIds\":null,\"assigneeUserIds\":[\"${admin.id.toHexString()}\"]")

        call(HttpMethod.PUT, "/tasks/$taskId/occurrences/$second", body).andExpect {
            status { isOk() }
            jsonPath("$.assigneeUserIds[0]") { value(admin.id.toHexString()) }
        }
        call(HttpMethod.PUT, "/tasks/$taskId/occurrences/$second", occurrenceJson).andExpect {
            jsonPath("$.assigneeUserIds") { value(null) }
        }
        call(HttpMethod.PUT, "/tasks/$taskId/occurrences/$second", body.replace(admin.id.toHexString(), "nope"))
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `an undated task answers dueAt null and a repeating one without a date answers 400`() {
        val undated = """{"title":"Sattelkammer","comment":"","dueAt":null,"assigneeUserIds":["${anna.id.toHexString()}"],"horseIds":[]}"""
        call(HttpMethod.POST, "/tasks", undated).andExpect {
            status { isOk() }
            jsonPath("$.dueAt") { value(null) }
        }
        call(HttpMethod.POST, "/tasks", taskJson().replace(""""dueAt":$first""", """"dueAt":null""")).andExpect {
            status { isBadRequest() }
        }
        val id = createSeries()
        call(HttpMethod.PUT, "/tasks/$id", taskJson(recurrence = null).replace(""""dueAt":$first""", """"dueAt":null""")).andExpect {
            status { isOk() }
            jsonPath("$.dueAt") { value(null) }
        }
    }
}
