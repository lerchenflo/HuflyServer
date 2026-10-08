package com.lerchenflo.hufly.server.absence

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeAbsenceRepository
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
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

/** Absences: HTTP mapping, validation and version sync. Business rules live in AbsenceServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class AbsenceControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository
    @Autowired lateinit var absenceRepository: FakeAbsenceRepository

    private val admin = testUser()
    private val anna = testUser()

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        absenceRepository.absences.clear()
        userRepository.save(admin)
        userRepository.save(anna)
        stableRepository.save(testStable(adminUserId = admin.id))
    }

    private fun call(method: HttpMethod, path: String, body: String? = null, userId: ObjectId = anna.id) =
        mockMvc.request(method, path) {
            header("Authorization", "Bearer ${jwtService.generateAccessToken(userId)}")
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    private fun absenceJson(from: Any = 20738, until: Any = 20744, note: String = "Urlaub") =
        """{"userId":"${anna.id.toHexString()}","from":$from,"until":$until,"note":"$note","clientId":"local-1"}"""

    private fun create(): String =
        objectMapper.readTree(call(HttpMethod.POST, "/absences", absenceJson()).andReturn().response.contentAsString)["id"].asString()

    @Test
    fun `create answers 201 with the AbsenceDto`() {
        call(HttpMethod.POST, "/absences", absenceJson()).andExpect {
            status { isCreated() }
            jsonPath("$.userId") { value(anna.id.toHexString()) }
            jsonPath("$.from") { value(20738) }
            jsonPath("$.until") { value(20744) }
            jsonPath("$.note") { value("Urlaub") }
            jsonPath("$.createdByUserId") { value(anna.id.toHexString()) }
            jsonPath("$.stableId") { value(anna.stableId.toHexString()) }
            jsonPath("$.updatedAt") { isNumber() }
            jsonPath("$.updatedBy") { value(anna.id.toHexString()) }
            jsonPath("$.version") { isNumber() }
            jsonPath("$.deleted") { value(false) }
        }
    }

    @Test
    fun `edit, delete with 204 and sync`() {
        val id = create()

        call(HttpMethod.PUT, "/absences/$id", absenceJson(note = "Krank")).andExpect {
            status { isOk() }
            jsonPath("$.note") { value("Krank") }
        }
        call(HttpMethod.GET, "/absences/sync?since=0", userId = admin.id).andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries[0].id") { value(id) }
        }
        call(HttpMethod.DELETE, "/absences/$id").andExpect { status { isNoContent() } }
        call(HttpMethod.GET, "/absences/sync?since=0", userId = admin.id).andExpect {
            jsonPath("$.deletedEntries[0]") { value(id) }
        }
    }

    @Test
    fun `invalid absences answer 400`() {
        call(HttpMethod.POST, "/absences", absenceJson(until = 20737)).andExpect { status { isBadRequest() } }
        call(HttpMethod.POST, "/absences", absenceJson(note = "x".repeat(501))).andExpect { status { isBadRequest() } }
        call(HttpMethod.POST, "/absences", absenceJson(from = "\"2026-10-12\"")).andExpect { status { isBadRequest() } }
        call(HttpMethod.GET, "/absences/sync?since=-1").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `absences need a token`() {
        mockMvc.request(HttpMethod.GET, "/absences/sync").andExpect { status { isUnauthorized() } }
    }
}
