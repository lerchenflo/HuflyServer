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

    @Test
    fun `reminder queries find open single tasks due in the window and series started by its end`() {
        val window = 1_000_000L
        val daily = com.lerchenflo.hufly.server.core.recurrence.Recurrence(
            com.lerchenflo.hufly.server.core.recurrence.RecurrenceFrequency.DAILY, 1, emptyList(), null, null, "Europe/Vienna",
        )
        fun save(title: String, dueAt: Long?, change: (StableTask) -> StableTask = { it }) =
            taskRepository.save(change(task(0).copy(title = title, dueAt = dueAt)))
        save("at start", window)
        save("inside", window + 50)
        save("at end", window + 100)
        save("after", window + 150)
        save("done", window + 50) { it.copy(doneAt = 1L, doneByUserId = ObjectId.get()) }
        save("deleted", window + 50) { it.copy(deleted = true) }
        save("undated", null)
        save("series", window + 50) { it.copy(recurrence = daily) }
        save("old series", 0L) { it.copy(recurrence = daily) }
        save("later series", window + 150) { it.copy(recurrence = daily) }
        save("deleted series", window) { it.copy(recurrence = daily, deleted = true) }

        assertEquals(setOf("inside", "at end"), taskRepository.findOpenSingleDue(window, window + 100).map { it.title }.toSet())
        assertEquals(setOf("series", "old series"), taskRepository.findSeriesStartingBy(window + 100).map { it.title }.toSet())
    }
}
