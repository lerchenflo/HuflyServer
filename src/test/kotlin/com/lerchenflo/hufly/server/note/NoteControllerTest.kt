package com.lerchenflo.hufly.server.note

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeNoteRepository
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

/** Stable notes: HTTP mapping, validation and version sync. Business rules live in NoteServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class NoteControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository
    @Autowired lateinit var noteRepository: FakeNoteRepository

    private val admin = testUser()
    private val anna = testUser()

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        noteRepository.notes.clear()
        userRepository.save(admin)
        userRepository.save(anna)
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

    private fun noteJson(title: String = "Hufschmied", body: String = "Freitag", visibleUntil: String = "\"2026-10-09\"") =
        """{"title":"$title","body":"$body","pinned":true,"visibleUntil":$visibleUntil,"clientId":"local-1"}"""

    private fun createNote(): String =
        objectMapper.readTree(call(HttpMethod.POST, "/notes", noteJson()).andReturn().response.contentAsString)["id"].asString()

    @Test
    fun `create note answers the NoteDto`() {
        call(HttpMethod.POST, "/notes", noteJson()).andExpect {
            status { isOk() }
            jsonPath("$.id") { isString() }
            jsonPath("$.stableId") { isString() }
            jsonPath("$.title") { value("Hufschmied") }
            jsonPath("$.body") { value("Freitag") }
            jsonPath("$.pinned") { value(true) }
            jsonPath("$.visibleUntil") { value("2026-10-09") }
            jsonPath("$.createdByUserId") { value(admin.id.toHexString()) }
            jsonPath("$.createdAt") { isNumber() }
            jsonPath("$.readByUserIds.length()") { value(0) }
            jsonPath("$.updatedAt") { isNumber() }
            jsonPath("$.updatedBy") { value(admin.id.toHexString()) }
        }
    }

    @Test
    fun `invalid title, body or date answers 400`() {
        listOf(noteJson(title = " "), noteJson(title = "x".repeat(201)), noteJson(body = "x".repeat(5001)), noteJson(visibleUntil = "\"9.10.2026\""))
            .forEach { call(HttpMethod.POST, "/notes", it).andExpect { status { isBadRequest() } } }
    }

    @Test
    fun `a member marks a note read with an empty body and sees only themselves as reader`() {
        val id = createNote()

        call(HttpMethod.POST, "/notes/$id/read", "{}", userId = anna.id).andExpect {
            status { isOk() }
            jsonPath("$.readByUserIds[0]") { value(anna.id.toHexString()) }
        }
        call(HttpMethod.POST, "/notes/$id/read", userId = anna.id).andExpect { status { isOk() } }
        call(HttpMethod.GET, "/notes/sync?since=0", userId = admin.id).andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries[0].readByUserIds[0]") { value(anna.id.toHexString()) }
            jsonPath("$.newVersion") { isNumber() }
            jsonPath("$.moreEntries") { value(false) }
        }
    }

    @Test
    fun `edit, delete and their errors`() {
        val id = createNote()

        call(HttpMethod.PUT, "/notes/$id", noteJson(title = "Neu", visibleUntil = "null")).andExpect {
            status { isOk() }
            jsonPath("$.title") { value("Neu") }
            jsonPath("$.visibleUntil") { value(null) }
        }
        call(HttpMethod.PUT, "/notes/$id", noteJson(), userId = anna.id).andExpect { status { isForbidden() } }
        call(HttpMethod.DELETE, "/notes/$id").andExpect { status { isOk() } }
        call(HttpMethod.DELETE, "/notes/$id").andExpect { status { isNotFound() } }
        call(HttpMethod.GET, "/notes/sync?since=0", userId = anna.id).andExpect { jsonPath("$.deletedEntries[0]") { value(id) } }
        call(HttpMethod.DELETE, "/notes/nope").andExpect { status { isBadRequest() } }
        call(HttpMethod.GET, "/notes/sync?since=-1").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `notes need a token`() {
        mockMvc.request(HttpMethod.GET, "/notes/sync").andExpect { status { isUnauthorized() } }
    }
}
