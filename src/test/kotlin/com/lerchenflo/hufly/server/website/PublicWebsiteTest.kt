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
        mockMvc.get("/konto-loeschen.html").andExpect { status { isOk() } }
        mockMvc.get("/assets/site.css").andExpect { status { isOk() } }
    }

    @Test
    fun `the site uses the app icon and its colours`() {
        // Gold of the horseshoe in the client's app_icon.xml
        mockMvc.get("/favicon.svg").andExpect {
            status { isOk() }
            content { string(org.hamcrest.Matchers.containsString("#E8B04B")) }
        }
        mockMvc.get("/assets/apple-touch-icon.png").andExpect {
            status { isOk() }
            content { contentTypeCompatibleWith(MediaType.IMAGE_PNG) }
        }
        // Primary of the client's HuflyLightColors, generated from the icon
        mockMvc.get("/assets/site.css").andExpect {
            content { string(org.hamcrest.Matchers.containsString("--primary: #1F6392")) }
        }
    }

    @Test
    fun `in-page links scroll smoothly unless the visitor asks for reduced motion`() {
        mockMvc.get("/assets/site.css").andExpect {
            content { string(org.hamcrest.Matchers.containsString("scroll-behavior: smooth")) }
            content { string(org.hamcrest.Matchers.containsString("prefers-reduced-motion: no-preference")) }
        }
    }

    @Test
    fun `the API stays protected`() {
        mockMvc.get("/users/me").andExpect { status { isUnauthorized() } }
    }
}
