package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.repository.TaskRepository
import com.lerchenflo.hufly.server.task.model.StableTask
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.domain.Limit
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs the derived and annotated queries against a real MongoDB; the in-memory fakes cannot catch query errors. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoTaskRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var taskRepository: TaskRepository

    private fun task(version: Long, stableId: ObjectId = STABLE_ID) = StableTask(
        stableId = stableId,
        title = "v$version",
        comment = "",
        dueAt = 0L,
        assigneeUserIds = emptyList(),
        horseIds = emptyList(),
        createdByUserId = ObjectId.get(),
        doneByUserId = null,
        doneAt = null,
        updatedAt = 0L,
        updatedBy = ObjectId.get(),
        version = version,
    )

    @Test
    fun `version page returns the own stable's rows after since up to the watermark, ascending and limited`() {
        listOf(5L, 1L, 3L, 2L, 4L).forEach { taskRepository.save(task(it)) }
        taskRepository.save(task(3, stableId = OTHER_STABLE_ID))

        val page = taskRepository.findVersionPage(STABLE_ID, since = 1, watermark = 4, limit = Limit.of(2))

        assertEquals(listOf(2L, 3L), page.map { it.version })
    }
}
