package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.event.model.LessonLock
import com.lerchenflo.hufly.server.repository.LessonLockRepository
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DuplicateKeyException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Runs the lock's atomic queries against a real MongoDB; the in-memory fake cannot catch them. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoLessonLockRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var repository: LessonLockRepository

    @Test
    fun `one holder per event, an expired lease is taken over once, only the holder lets go`() {
        val eventId = ObjectId.get()
        val holder = ObjectId.get()
        repository.insert(LessonLock(eventId, STABLE_ID, holder, until = 100))

        assertFailsWith<DuplicateKeyException> { repository.insert(LessonLock(eventId, STABLE_ID, ObjectId.get(), until = 200)) }
        assertEquals(0, repository.takeExpired(eventId, now = 100, token = ObjectId.get(), until = 200))

        val next = ObjectId.get()
        assertEquals(1, repository.takeExpired(eventId, now = 101, token = next, until = 200))
        assertEquals(0, repository.takeExpired(eventId, now = 101, token = ObjectId.get(), until = 200))

        assertEquals(0, repository.deleteByEventIdAndToken(eventId, holder))
        assertEquals(1, repository.deleteByEventIdAndToken(eventId, next))
        repository.insert(LessonLock(eventId, STABLE_ID, holder, until = 300))
    }

    @Test
    fun `deleting a stable's locks keeps the others`() {
        val ours = ObjectId.get()
        val theirs = ObjectId.get()
        repository.insert(LessonLock(ours, STABLE_ID, ObjectId.get(), until = 100))
        repository.insert(LessonLock(theirs, OTHER_STABLE_ID, ObjectId.get(), until = 100))

        repository.deleteByStableId(STABLE_ID)

        assertEquals(0, repository.takeExpired(ours, now = 101, token = ObjectId.get(), until = 200))
        assertEquals(1, repository.takeExpired(theirs, now = 101, token = ObjectId.get(), until = 200))
    }
}
