package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.notification.model.DigestItem
import com.lerchenflo.hufly.server.repository.DigestItemRepository
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs the derived deletes against a real MongoDB; the in-memory fakes cannot catch query errors. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoDigestItemRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var repository: DigestItemRepository

    @Test
    fun `items go by stable, or by account and stable`() {
        val anna = ObjectId.get()
        val ben = ObjectId.get()
        fun save(userId: ObjectId, stableId: ObjectId?, title: String) =
            repository.save(DigestItem(userId = userId, stableId = stableId, title = title, body = "", createdAt = 1L))
        save(anna, STABLE_ID, "anna here")
        save(anna, OTHER_STABLE_ID, "anna there")
        save(ben, OTHER_STABLE_ID, "ben there")
        save(ben, null, "ben old")

        assertEquals(1L, repository.deleteByUserIdAndStableId(anna, OTHER_STABLE_ID))
        assertEquals(1L, repository.deleteByStableId(OTHER_STABLE_ID))
        assertEquals(setOf("anna here", "ben old"), repository.findByCreatedAtBefore(2L).map { it.title }.toSet())
    }
}
