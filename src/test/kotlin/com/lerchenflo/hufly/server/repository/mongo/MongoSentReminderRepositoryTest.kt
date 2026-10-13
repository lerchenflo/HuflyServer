package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.notification.model.SentReminder
import com.lerchenflo.hufly.server.repository.SentReminderRepository
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DuplicateKeyException
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Runs the derived queries and the unique index against a real MongoDB; the in-memory fakes cannot catch them. */
@SpringBootTest(properties = ["spring.data.mongodb.auto-index-creation=true"])
@Testcontainers(disabledWithoutDocker = true)
class MongoSentReminderRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var repository: SentReminderRepository
    @Autowired lateinit var mongoTemplate: MongoTemplate

    @Test
    fun `one reminder per task, due time and user, old ones and a stable's ones can be deleted`() {
        val taskId = ObjectId.get()
        val userId = ObjectId.get()
        repository.insert(SentReminder(stableId = STABLE_ID, taskId = taskId, dueAt = 100L, userId = userId))
        assertFailsWith<DuplicateKeyException> { repository.insert(SentReminder(stableId = STABLE_ID, taskId = taskId, dueAt = 100L, userId = userId)) }
        repository.insert(SentReminder(stableId = STABLE_ID, taskId = taskId, dueAt = 200L, userId = userId))
        repository.insert(SentReminder(stableId = OTHER_STABLE_ID, taskId = ObjectId.get(), dueAt = 300L, userId = userId))

        assertEquals(1L, repository.deleteByDueAtBefore(200L))
        assertEquals(1L, repository.deleteByStableId(OTHER_STABLE_ID))
        assertEquals(1L, mongoTemplate.getCollection("sentReminders").countDocuments())
    }
}
