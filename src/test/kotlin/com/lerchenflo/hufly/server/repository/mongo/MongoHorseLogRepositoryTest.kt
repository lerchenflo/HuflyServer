package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.horselog.model.HorseLogEntry
import com.lerchenflo.hufly.server.repository.HorseLogRepository
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
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoHorseLogRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var logRepository: HorseLogRepository

    private fun entry(version: Long, stableId: ObjectId = STABLE_ID) = HorseLogEntry(
        stableId = stableId,
        horseId = ObjectId.get(),
        activityTagId = ObjectId.get(),
        startAt = Instant.EPOCH,
        endAt = null,
        doneByUserId = null,
        comment = "",
        nextDueAt = LocalDate.of(2027, 1, 1),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        updatedBy = ObjectId.get(),
        version = version,
    )

    @Test
    fun `version page returns the own stable's rows after since up to the watermark, ascending and limited`() {
        listOf(5L, 1L, 3L, 2L, 4L).forEach { logRepository.save(entry(it)) }
        logRepository.save(entry(3, stableId = OTHER_STABLE_ID))

        val page = logRepository.findVersionPage(STABLE_ID, since = 1, watermark = 4, limit = Limit.of(2))

        assertEquals(listOf(2L, 3L), page.map { it.version })
        assertEquals(LocalDate.of(2027, 1, 1), page.first().nextDueAt)
    }
}
