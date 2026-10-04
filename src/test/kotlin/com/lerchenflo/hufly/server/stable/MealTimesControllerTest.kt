package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.stable.model.MealTimes
import com.lerchenflo.hufly.server.testdata.STABLE_ID
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
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Feeding times of the stable: every member reads, the admin sets them. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class MealTimesControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository

    private val admin = testUser()
    private val anna = testUser()

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        listOf(admin, anna).forEach { userRepository.save(it) }
        stableRepository.save(testStable(adminUserId = admin.id))
    }

    private fun call(method: HttpMethod, body: String? = null, userId: ObjectId = admin.id) =
        mockMvc.request(method, "/stables/me/mealtimes") {
            header("Authorization", "Bearer ${jwtService.generateAccessToken(userId)}")
            if (body != null) {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }
        }

    private fun times(morning: String = "05:30", lunch: String = "11:00", dinner: String = "17:00", night: String = "21:30") =
        """{"morning":"$morning","lunch":"$lunch","dinner":"$dinner","night":"$night"}"""

    @Test
    fun `a stable without own times answers the defaults`() {
        call(HttpMethod.GET, userId = anna.id).andExpect {
            status { isOk() }
            jsonPath("$.morning") { value("06:00") }
            jsonPath("$.lunch") { value("12:00") }
            jsonPath("$.dinner") { value("18:00") }
            jsonPath("$.night") { value("22:00") }
            jsonPath("$.updatedAt") { value(0) }
            jsonPath("$.updatedBy") { value(admin.id.toHexString()) }
        }
    }

    @Test
    fun `the admin sets the times and every member reads them`() {
        call(HttpMethod.PUT, times()).andExpect {
            status { isOk() }
            jsonPath("$.morning") { value("05:30") }
            jsonPath("$.updatedBy") { value(admin.id.toHexString()) }
            jsonPath("$.updatedAt") { isNumber() }
        }

        call(HttpMethod.GET, userId = anna.id).andExpect {
            jsonPath("$.morning") { value("05:30") }
            jsonPath("$.night") { value("21:30") }
        }
    }

    @Test
    fun `members cannot set the times`() {
        call(HttpMethod.PUT, times(), userId = anna.id).andExpect { status { isForbidden() } }

        assertEquals(MealTimes(), stableRepository.findById(STABLE_ID)!!.mealTimes)
    }

    @Test
    fun `times must be HH mm and strictly increasing within one day`() {
        listOf(
            times(night = "00:30"),
            times(lunch = "05:30"),
            times(morning = "6:00"),
            times(dinner = "24:00"),
            times(night = "21:60"),
            """{"morning":"05:30","lunch":"11:00","dinner":"17:00"}""",
        ).forEach { call(HttpMethod.PUT, it).andExpect { status { isBadRequest() } } }

        assertEquals(MealTimes(), stableRepository.findById(STABLE_ID)!!.mealTimes)
    }

    @Test
    fun `meal times need a token`() {
        mockMvc.request(HttpMethod.GET, "/stables/me/mealtimes").andExpect { status { isUnauthorized() } }
    }
}
