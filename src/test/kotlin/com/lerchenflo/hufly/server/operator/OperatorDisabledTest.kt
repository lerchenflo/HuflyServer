package com.lerchenflo.hufly.server.operator

import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.util.Base64
import kotlin.test.Test

/** Without OPERATOR_* credentials, or with a too short password, nobody gets in. */
@SpringBootTest(properties = ["operator.path=/office-t3st", "operator.username=operator", "operator.password=short"])
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class OperatorDisabledTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `a too short operator password disables the operator area`() {
        val basic = "Basic " + Base64.getEncoder().encodeToString("operator:short".toByteArray())

        mockMvc.get("/office-t3st/api/stables") { header("Authorization", basic) }.andExpect { status { isUnauthorized() } }
    }

}
