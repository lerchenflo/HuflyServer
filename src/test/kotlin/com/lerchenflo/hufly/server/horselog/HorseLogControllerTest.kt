package com.lerchenflo.hufly.server.horselog

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeHorseLogRepository
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

/** HOR-5: HTTP mapping, validation and version sync. Business rules live in HorseLogServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class HorseLogControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository
    @Autowired lateinit var horseRepository: FakeHorseRepository
    @Autowired lateinit var logRepository: FakeHorseLogRepository

    private val admin = testUser()
    private val rider = testUser()
    private val vaccination = testTag(type = TagType.ACTIVITY)
    private val blitz = testHorse()

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        horseRepository.horses.clear()
        logRepository.entries.clear()
        userRepository.save(admin)
        userRepository.save(rider)
        tagRepository.save(vaccination)
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

    private fun entryJson(startAt: String = "1790000000000") =
        """{"horseId":"${blitz.id.toHexString()}","activityTagId":"${vaccination.id.toHexString()}","startAt":$startAt,""" +
            """"endAt":null,"doneByUserId":"${rider.id.toHexString()}","comment":"Influenza","nextDueAt":"2027-10-01"}"""

    @Test
    fun `create entry answers with the entry`() {
        call(HttpMethod.POST, "/horselog", entryJson()).andExpect {
            status { isOk() }
            jsonPath("$.horseId") { value(blitz.id.toHexString()) }
            jsonPath("$.startAt") { value(1790000000000) }
            jsonPath("$.nextDueAt") { value("2027-10-01") }
            jsonPath("$.version") { isNumber() }
        }
    }

    @Test
    fun `create entry without HORSE_LOG_WRITE answers 403`() {
        call(HttpMethod.POST, "/horselog", entryJson(), userId = rider.id).andExpect { status { isForbidden() } }
    }

    @Test
    fun `create entry with an out of range start answers 400`() {
        call(HttpMethod.POST, "/horselog", entryJson(startAt = "9000000000000000000")).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `edit and delete answer 200, every member syncs`() {
        val id = objectMapper.readTree(call(HttpMethod.POST, "/horselog", entryJson()).andReturn().response.contentAsString)["id"].asString()

        call(HttpMethod.PUT, "/horselog/$id", entryJson().replace("Influenza", "Tetanus")).andExpect {
            status { isOk() }
            jsonPath("$.comment") { value("Tetanus") }
        }
        call(HttpMethod.DELETE, "/horselog/$id").andExpect { status { isOk() } }
        call(HttpMethod.GET, "/horselog/sync?since=0", userId = rider.id).andExpect {
            status { isOk() }
            jsonPath("$.deletedEntries[0]") { value(id) }
            jsonPath("$.moreEntries") { value(false) }
        }
    }

    @Test
    fun `sync rejects a negative since and malformed ids answer 400`() {
        call(HttpMethod.GET, "/horselog/sync?since=-1").andExpect { status { isBadRequest() } }
        call(HttpMethod.DELETE, "/horselog/nope").andExpect { status { isBadRequest() } }
    }
}
