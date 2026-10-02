package com.lerchenflo.hufly.server.foodplan

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeFoodPlanRepository
import com.lerchenflo.hufly.server.repository.FakeHorseRepository
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
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

/** FOD-1..FOD-4: HTTP mapping, validation and sync. Business rules live in FoodPlanServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class FoodPlanControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository
    @Autowired lateinit var horseRepository: FakeHorseRepository
    @Autowired lateinit var foodPlanRepository: FakeFoodPlanRepository

    private val admin = testUser()
    private val rider = testUser()
    private val hay = testTag(type = TagType.FOOD)
    private val blitz = testHorse()

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        horseRepository.horses.clear()
        foodPlanRepository.plans.clear()
        userRepository.save(admin)
        userRepository.save(rider)
        tagRepository.save(hay)
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

    private fun planJson(name: String = "Standard", slot: String = "MORNING") =
        """{"name":"$name","entries":[{"slot":"$slot","foodTagId":"${hay.id.toHexString()}","amountComment":"2 Gabeln"}]}"""

    private fun createPlan(): String =
        objectMapper.readTree(call(HttpMethod.POST, "/foodplans", planJson()).andReturn().response.contentAsString)["id"].asString()

    @Test
    fun `create plan answers with the plan`() {
        call(HttpMethod.POST, "/foodplans", planJson()).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Standard") }
            jsonPath("$.entries[0].slot") { value("MORNING") }
            jsonPath("$.entries[0].foodTagId") { value(hay.id.toHexString()) }
            jsonPath("$.entries[0].amountComment") { value("2 Gabeln") }
        }
    }

    @Test
    fun `create plan with an unknown meal slot answers 400`() {
        call(HttpMethod.POST, "/foodplans", planJson(slot = "BRUNCH")).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create plan with a blank name answers 400`() {
        call(HttpMethod.POST, "/foodplans", planJson(name = "")).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create plan without FOODPLAN_EDIT answers 403`() {
        call(HttpMethod.POST, "/foodplans", planJson(), userId = rider.id).andExpect { status { isForbidden() } }
    }

    @Test
    fun `edit, copy and delete answer 200`() {
        val id = createPlan()

        call(HttpMethod.PUT, "/foodplans/$id", planJson(name = "Winter")).andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Winter") }
        }
        call(HttpMethod.POST, "/foodplans/$id/copy", """{"name":"Kopie"}""").andExpect {
            status { isOk() }
            jsonPath("$.name") { value("Kopie") }
        }
        call(HttpMethod.DELETE, "/foodplans/$id").andExpect { status { isOk() } }
    }

    @Test
    fun `assign plan answers with the horse`() {
        val id = createPlan()

        call(HttpMethod.PUT, "/horses/${blitz.id.toHexString()}/foodplan", """{"foodPlanId":"$id"}""").andExpect {
            status { isOk() }
            jsonPath("$.foodPlanId") { value(id) }
        }
        call(HttpMethod.PUT, "/horses/${blitz.id.toHexString()}/foodplan", """{"foodPlanId":null}""").andExpect {
            status { isOk() }
            jsonPath("$.foodPlanId") { value(null) }
        }
    }

    @Test
    fun `every member syncs the live plans of the own stable`() {
        val id = createPlan()
        call(HttpMethod.POST, "/foodplans", planJson()).andExpect { status { isOk() } }
        foodPlanRepository.save(foodPlanRepository.findById(ObjectId(id))!!.copy(id = ObjectId.get(), stableId = OTHER_STABLE_ID))

        call(HttpMethod.POST, "/foodplans/sync", "[]", userId = rider.id).andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries.length()") { value(2) }
        }
    }

    @Test
    fun `food plan routes with a malformed id answer 400`() {
        call(HttpMethod.DELETE, "/foodplans/nope").andExpect { status { isBadRequest() } }
        call(HttpMethod.PUT, "/horses/${blitz.id.toHexString()}/foodplan", """{"foodPlanId":"nope"}""").andExpect { status { isBadRequest() } }
    }
}
