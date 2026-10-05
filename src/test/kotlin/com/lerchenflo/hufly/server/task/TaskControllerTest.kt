package com.lerchenflo.hufly.server.task

import com.lerchenflo.hufly.server.core.security.JwtService
import com.lerchenflo.hufly.server.repository.FakeRepositoryConfig
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeTaskRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.tag.model.TagType
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

/** TSK-1..TSK-4, OFF-2: HTTP mapping, validation and version sync. Business rules live in TaskServiceTest. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(FakeRepositoryConfig::class)
class TaskControllerTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var jwtService: JwtService
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var userRepository: FakeUserRepository
    @Autowired lateinit var stableRepository: FakeStableRepository
    @Autowired lateinit var tagRepository: FakeTagRepository
    @Autowired lateinit var taskRepository: FakeTaskRepository

    private val admin = testUser()
    private val anna = testUser()

    @BeforeTest
    fun setUp() {
        userRepository.users.clear()
        stableRepository.stables.clear()
        tagRepository.tags.clear()
        taskRepository.tasks.clear()
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

    private fun taskJson(title: String = "Misten") =
        """{"title":"$title","comment":"Box 3","dueAt":1790000000000,"assigneeUserIds":["${anna.id.toHexString()}"],"horseIds":[]}"""

    private fun createTask(): String {
        val body = call(HttpMethod.POST, "/tasks", taskJson()).andReturn().response.contentAsString
        return objectMapper.readTree(body)["id"].asString()
    }

    @Test
    fun `create task answers with the task and its version`() {
        call(HttpMethod.POST, "/tasks", taskJson()).andExpect {
            status { isOk() }
            jsonPath("$.title") { value("Misten") }
            jsonPath("$.dueAt") { value(1790000000000) }
            jsonPath("$.assigneeUserIds[0]") { value(anna.id.toHexString()) }
            jsonPath("$.doneByUserId") { value(null) }
            jsonPath("$.horseIds.length()") { value(0) }
            jsonPath("$.version") { isNumber() }
        }
    }

    @Test
    fun `create task with a blank title answers 400`() {
        call(HttpMethod.POST, "/tasks", taskJson(title = "")).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create task as member without TASK_EDIT answers 403`() {
        call(HttpMethod.POST, "/tasks", taskJson(), userId = anna.id).andExpect { status { isForbidden() } }
    }

    @Test
    fun `edit task answers with the updated task`() {
        val id = createTask()

        call(HttpMethod.PUT, "/tasks/$id", taskJson(title = "Füttern")).andExpect {
            status { isOk() }
            jsonPath("$.title") { value("Füttern") }
        }
    }

    @Test
    fun `assignee ticks the task done`() {
        val id = createTask()

        call(HttpMethod.POST, "/tasks/$id/done", """{"done":true}""", userId = anna.id).andExpect {
            status { isOk() }
            jsonPath("$.doneByUserId") { value(anna.id.toHexString()) }
            jsonPath("$.doneAt") { isNumber() }
        }
    }

    @Test
    fun `delete task answers 200 and comes as deleted in the sync`() {
        val id = createTask()

        call(HttpMethod.DELETE, "/tasks/$id").andExpect { status { isOk() } }

        call(HttpMethod.GET, "/tasks/sync?since=0", userId = anna.id).andExpect {
            jsonPath("$.updatedEntries.length()") { value(0) }
            jsonPath("$.deletedEntries[0]") { value(id) }
        }
    }

    @Test
    fun `sync answers own tasks with newVersion and moreEntries`() {
        createTask()
        createTask()

        call(HttpMethod.GET, "/tasks/sync?since=0&page_size=1", userId = anna.id).andExpect {
            status { isOk() }
            jsonPath("$.updatedEntries.length()") { value(1) }
            jsonPath("$.newVersion") { isNumber() }
            jsonPath("$.moreEntries") { value(true) }
        }
    }

    @Test
    fun `sync rejects a negative since and an oversized page`() {
        call(HttpMethod.GET, "/tasks/sync?since=-1").andExpect { status { isBadRequest() } }
        call(HttpMethod.GET, "/tasks/sync?page_size=5000").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `create task with an out of range due date answers 400`() {
        call(HttpMethod.POST, "/tasks", taskJson().replace("1790000000000", "9000000000000000000")).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `task routes with a malformed id answer 400`() {
        call(HttpMethod.DELETE, "/tasks/nope").andExpect { status { isBadRequest() } }
    }

    @Test
    fun `a task carries its category, a PUT without it clears it and a malformed id answers 400`() {
        val feeding = testTag(type = TagType.TASK_CATEGORY)
        tagRepository.save(feeding)
        val withCategory = taskJson().dropLast(1) + ""","categoryTagId":"${feeding.id.toHexString()}"}"""

        val body = call(HttpMethod.POST, "/tasks", withCategory).andExpect {
            status { isOk() }
            jsonPath("$.categoryTagId") { value(feeding.id.toHexString()) }
        }.andReturn().response.contentAsString
        val id = objectMapper.readTree(body)["id"].asString()

        call(HttpMethod.PUT, "/tasks/$id", taskJson()).andExpect {
            status { isOk() }
            jsonPath("$.categoryTagId") { value(null) }
        }
        call(HttpMethod.POST, "/tasks", taskJson().dropLast(1) + ""","categoryTagId":"nope"}""").andExpect { status { isBadRequest() } }
    }
}
