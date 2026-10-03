package com.lerchenflo.hufly.server.paddock

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeHorseConflictRepository
import com.lerchenflo.hufly.server.repository.FakeHorseGroupRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakePaddockAssignmentRepository
import com.lerchenflo.hufly.server.repository.FakePaddockRepository
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

/** Paddock planning: HTTP mapping, validation and sync. Business rules live in PaddockServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class PaddockControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository
    @Autowired lateinit var horseRepository: FakeHorseRepository
    @Autowired lateinit var paddockRepository: FakePaddockRepository
    @Autowired lateinit var groupRepository: FakeHorseGroupRepository
    @Autowired lateinit var conflictRepository: FakeHorseConflictRepository
    @Autowired lateinit var assignmentRepository: FakePaddockAssignmentRepository

    private val admin = testUser()
    private val rider = testUser()
    private val blitz = testHorse()
    private val donner = testHorse()

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        horseRepository.horses.clear()
        paddockRepository.paddocks.clear()
        groupRepository.groups.clear()
        conflictRepository.conflicts.clear()
        assignmentRepository.assignments.clear()
        userRepository.save(admin)
        userRepository.save(rider)
        horseRepository.save(blitz)
        horseRepository.save(donner)
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

    private fun idOf(method: HttpMethod, path: String, body: String) =
        objectMapper.readTree(call(method, path, body).andReturn().response.contentAsString)["id"].asString()

    @Test
    fun `paddock create, edit, delete and sync`() {
        val id = idOf(HttpMethod.POST, "/paddocks", """{"name":"Große Koppel","description":""}""")

        call(HttpMethod.PUT, "/paddocks/$id", """{"name":"Kleine Koppel","description":"x"}""").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Kleine Koppel") }
        }
        call(HttpMethod.POST, "/paddocks/sync", "[]", userId = rider.id).andExpect {
            jsonPath("$.updatedEntries[0].id") { value(id) }
        }
        call(HttpMethod.DELETE, "/paddocks/$id").andExpect { status { isOk() } }
    }

    @Test
    fun `group create and sync`() {
        val id = idOf(HttpMethod.POST, "/horsegroups", """{"name":"Wallache","horseIds":["${blitz.id.toHexString()}"]}""")

        call(HttpMethod.POST, "/horsegroups/sync", "[]", userId = rider.id).andExpect {
            jsonPath("$.updatedEntries[0].id") { value(id) }
            jsonPath("$.updatedEntries[0].horseIds[0]") { value(blitz.id.toHexString()) }
        }
        call(HttpMethod.PUT, "/horsegroups/$id", """{"name":"Alle","horseIds":[]}""").andExpect { status { isOk() } }
        call(HttpMethod.DELETE, "/horsegroups/$id").andExpect { status { isOk() } }
    }

    @Test
    fun `conflict create, edit reason, duplicate answers 409, sync`() {
        val body = """{"firstHorseId":"${blitz.id.toHexString()}","secondHorseId":"${donner.id.toHexString()}","reason":"Beißt"}"""
        val id = idOf(HttpMethod.POST, "/horseconflicts", body)

        call(HttpMethod.POST, "/horseconflicts", body).andExpect { status { isConflict() } }
        call(HttpMethod.PUT, "/horseconflicts/$id", """{"reason":"Tritt"}""").andExpect {
            jsonPath("$.reason") { value("Tritt") }
        }
        call(HttpMethod.POST, "/horseconflicts/sync", "[]", userId = rider.id).andExpect {
            jsonPath("$.updatedEntries.length()") { value(1) }
        }
        call(HttpMethod.DELETE, "/horseconflicts/$id").andExpect { status { isOk() } }
    }

    @Test
    fun `assignment create, edit, delete and version sync`() {
        val paddockId = idOf(HttpMethod.POST, "/paddocks", """{"name":"Koppel","description":""}""")
        val body = """{"paddockId":"$paddockId","groupIds":[],"horseIds":["${blitz.id.toHexString()}"],"startAt":1790000000000,"endAt":null,"comment":""}"""

        call(HttpMethod.POST, "/paddockassignments", body).andExpect {
            status { isOk() }
            jsonPath("$.horseIds[0]") { value(blitz.id.toHexString()) }
            jsonPath("$.version") { isNumber() }
        }
        val id = assignmentRepository.assignments.single().id.toHexString()
        call(HttpMethod.PUT, "/paddockassignments/$id", body.replace("\"comment\":\"\"", "\"comment\":\"kurz\"")).andExpect {
            jsonPath("$.comment") { value("kurz") }
        }
        call(HttpMethod.GET, "/paddockassignments/sync?since=0", userId = rider.id).andExpect {
            jsonPath("$.updatedEntries[0].id") { value(id) }
        }
        call(HttpMethod.DELETE, "/paddockassignments/$id").andExpect { status { isOk() } }
    }

    @Test
    fun `writes without PADDOCK_PLAN answer 403`() {
        call(HttpMethod.POST, "/paddocks", """{"name":"X","description":""}""", userId = rider.id).andExpect { status { isForbidden() } }
    }

    @Test
    fun `validation errors answer 400`() {
        call(HttpMethod.POST, "/paddocks", """{"name":"","description":""}""").andExpect { status { isBadRequest() } }
        call(HttpMethod.DELETE, "/horsegroups/nope").andExpect { status { isBadRequest() } }
        call(HttpMethod.GET, "/paddockassignments/sync?since=-1").andExpect { status { isBadRequest() } }
        call(HttpMethod.POST, "/paddockassignments", """{"paddockId":"nope","horseIds":[],"startAt":9000000000000000000}""")
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `400 bodies carry a code and a message`() {
        call(HttpMethod.POST, "/paddockassignments", """{"paddockId":"${ObjectId.get().toHexString()}","horseIds":[],"startAt":0}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("UNKNOWN_PADDOCK") }
            jsonPath("$.message") { isString() }
        }
    }

    @Test
    fun `assignment responses list the single horses`() {
        val paddockId = idOf(HttpMethod.POST, "/paddocks", """{"name":"Koppel"}""")

        call(HttpMethod.POST, "/paddockassignments", """{"paddockId":"$paddockId","horseIds":["${blitz.id.toHexString()}"],"startAt":0}""").andExpect {
            status { isOk() }
            jsonPath("$.singleHorseIds[0]") { value(blitz.id.toHexString()) }
        }
    }
}
