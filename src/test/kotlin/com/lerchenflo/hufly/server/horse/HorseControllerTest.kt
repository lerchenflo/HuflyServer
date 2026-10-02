package com.lerchenflo.hufly.server.horse

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.testHorse
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.horse.model.Medication
import com.lerchenflo.hufly.server.tag.model.Permission
import java.time.LocalDate
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
import java.time.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test

/** HOR-1..HOR-5, OFF-2: HTTP mapping, validation and sync. Business rules live in HorseServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class HorseControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository
    @Autowired lateinit var horseRepository: FakeHorseRepository

    private val admin = testUser()
    private val rider = testUser()
    private val medicTag = testTag(permissions = setOf(Permission.HORSE_MEDICATION_VIEW))
    private val medic = testUser(roleTagIds = listOf(medicTag.id))
    private val aspirin = Medication("Aspirin", "1x", LocalDate.of(2026, 9, 1), null)

    private val horseJson = """{"name":"Blitz","description":"Brav","pictureUrl":null,"birthDate":"2015-04-01","breed":"Haflinger",
        |"color":"Fuchs","ownerUserId":null,"medicalNotes":"","vetContact":"Dr. Huf",
        |"medications":[{"name":"Aspirin","dosage":"1x","from":"2026-09-01","until":null}]}""".trimMargin()

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        horseRepository.horses.clear()
        userRepository.save(admin)
        userRepository.save(rider)
        userRepository.save(medic)
        tagRepository.save(medicTag)
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

    @Test
    fun `create horse answers with the horse, dates as ISO strings`() {
        call(HttpMethod.POST, "/horses", horseJson).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Blitz") }
            jsonPath("$.birthDate") { value("2015-04-01") }
            jsonPath("$.medications[0].from") { value("2026-09-01") }
            jsonPath("$.foodPlanId") { value(null) }
            jsonPath("$.updatedAt") { isNumber() }
        }
    }

    @Test
    fun `create horse with a blank name answers 400`() {
        call(HttpMethod.POST, "/horses", horseJson.replace("\"Blitz\"", "\"\"")).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create horse with a malformed owner id answers 400`() {
        call(HttpMethod.POST, "/horses", horseJson.replace("\"ownerUserId\":null", "\"ownerUserId\":\"nope\""))
            .andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create horse as member answers 403`() {
        call(HttpMethod.POST, "/horses", horseJson, userId = rider.id).andExpect { status { isForbidden() } }
    }

    @Test
    fun `edit horse answers with the updated horse`() {
        val horse = horseRepository.save(testHorse())

        call(HttpMethod.PUT, "/horses/${horse.id.toHexString()}", horseJson.replace("Blitz", "Donner")).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Donner") }
        }
    }

    @Test
    fun `delete horse answers 200`() {
        val horse = horseRepository.save(testHorse())

        call(HttpMethod.DELETE, "/horses/${horse.id.toHexString()}").andExpect { status { isOk() } }
    }

    @Test
    fun `every member syncs the live horses of the own stable`() {
        val horse = horseRepository.save(testHorse(updatedAt = Instant.ofEpochMilli(5)))
        horseRepository.save(testHorse(stableId = OTHER_STABLE_ID))
        val deleted = horseRepository.save(testHorse(deleted = true))

        call(HttpMethod.POST, "/horses/sync", """[{"id":"${deleted.id.toHexString()}","timeStamp":"0"}]""", userId = rider.id).andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries.length()") { value(1) }
            jsonPath("$.updatedEntries[0].id") { value(horse.id.toHexString()) }
            jsonPath("$.deletedEntries[0]") { value(deleted.id.toHexString()) }
        }
    }

    @Test
    fun `horse sync rejects an oversized page`() {
        call(HttpMethod.POST, "/horses/sync?page_size=5000", "[]").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `sync hides medications without HORSE_MEDICATION_VIEW but shows notes and vet`() {
        horseRepository.save(testHorse().copy(medications = listOf(aspirin), medicalNotes = "Allergie", vetContact = "Dr. Huf"))

        call(HttpMethod.POST, "/horses/sync", "[]", userId = rider.id).andExpect {
            jsonPath("$.updatedEntries[0].medications") { value(null) }
            jsonPath("$.updatedEntries[0].medicalNotes") { value("Allergie") }
            jsonPath("$.updatedEntries[0].vetContact") { value("Dr. Huf") }
        }
    }

    @Test
    fun `sync shows medications with HORSE_MEDICATION_VIEW`() {
        horseRepository.save(testHorse().copy(medications = listOf(aspirin)))

        call(HttpMethod.POST, "/horses/sync", "[]", userId = medic.id).andExpect {
            jsonPath("$.updatedEntries[0].medications[0].name") { value("Aspirin") }
        }
    }

    @Test
    fun `medication update answers with the horse`() {
        val horse = horseRepository.save(testHorse())

        call(HttpMethod.PUT, "/horses/${horse.id.toHexString()}/medications",
            """{"medications":[{"name":"Aspirin","dosage":"1x","from":"2026-09-01"}]}""").andExpect {
            status { isOk() }
            jsonPath("$.medications[0].name") { value("Aspirin") }
        }
    }
}
