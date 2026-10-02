package com.lerchenflo.hufly.server.operator

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testUser
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import kotlin.test.Test

/** Without OPERATOR_* settings the operator area stays closed, also for app users. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class OperatorUnconfiguredTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository

    @Test
    fun `an app user cannot create stables through any default path`() {
        val admin = userRepository.save(testUser())
        stableRepository.save(testStable(adminUserId = admin.id))

        for (path in listOf("/api/stables", "/stables", "/operator/api/stables")) {
            mockMvc.post(path) {
                header("Authorization", "Bearer ${jwtService.generateAccessToken(admin.id)}")
                contentType = MediaType.APPLICATION_JSON
                content = """{"stableName":"X","adminEmail":"x@hufly.test","adminDisplayName":"X"}"""
            }.andExpect { status { is4xxClientError() } }
        }
    }
}
