package com.lerchenflo.hufly.server.website

import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import kotlin.test.Test

/** The public sales website needs no login; the API still does. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class PublicWebsiteTest {

    @Autowired lateinit var mockMvc: MockMvc

    @Test
    fun `landing page is public`() {
        mockMvc.get("/").andExpect {
            status { isOk() }
            forwardedUrl("index.html")
        }
        mockMvc.get("/index.html").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.TEXT_HTML) }
        }
    }

    @Test
    fun `legal pages and assets are public`() {
        mockMvc.get("/impressum.html").andExpect { status { isOk() } }
        mockMvc.get("/datenschutz.html").andExpect { status { isOk() } }
        mockMvc.get("/assets/site.css").andExpect { status { isOk() } }
    }

    @Test
    fun `the API stays protected`() {
        mockMvc.get("/users/me").andExpect { status { isUnauthorized() } }
    }
}
