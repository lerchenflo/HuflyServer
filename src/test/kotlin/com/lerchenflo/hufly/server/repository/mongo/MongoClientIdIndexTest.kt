package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.horselog.model.HorseLogEntry
import com.lerchenflo.hufly.server.repository.HorseLogRepository
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

/** The unique partial index on (stableId, clientId) makes concurrent create retries race-safe. */
@SpringBootTest(properties = ["spring.data.mongodb.auto-index-creation=true"])
@Testcontainers(disabledWithoutDocker = true)
class MongoClientIdIndexTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var logRepository: HorseLogRepository

    private fun entry(clientId: String?, stableId: ObjectId = STABLE_ID) = HorseLogEntry(
        stableId = stableId,
        horseId = ObjectId.get(),
        activityTagId = ObjectId.get(),
        startAt = 0L,
        endAt = null,
        doneByUserId = null,
        comment = "",
        nextDueAt = null,
        createdAt = 0L,
        updatedAt = 0L,
        updatedBy = ObjectId.get(),
        clientId = clientId,
    )

    @Test
    fun `a clientId is unique per stable, entries without one are not limited`() {
        val first = logRepository.save(entry("c1"))
        logRepository.save(entry("c1", stableId = OTHER_STABLE_ID))
        repeat(2) { logRepository.save(entry(null)) }

        assertFailsWith<DuplicateKeyException> { logRepository.save(entry("c1")) }
        assertEquals(first.id, logRepository.findByStableIdAndClientId(STABLE_ID, "c1")!!.id)
    }
}
