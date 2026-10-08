package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.absence.model.Absence
import com.lerchenflo.hufly.server.repository.AbsenceRepository
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.epochDay
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

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoAbsenceRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var absenceRepository: AbsenceRepository

    private val userId = ObjectId.get()

    private fun absence(version: Long, stableId: ObjectId = STABLE_ID, deleted: Boolean = false) = Absence(
        stableId = stableId,
        userId = userId,
        from = epochDay(1969, 12, 30),
        until = epochDay(2026, 10, 18),
        note = "",
        createdByUserId = userId,
        updatedAt = 0L,
        updatedBy = userId,
        deleted = deleted,
        version = version,
    )

    @Test
    fun `version page returns the own stable's rows after since up to the watermark, ascending and limited`() {
        listOf(5L, 1L, 3L, 2L, 4L).forEach { absenceRepository.save(absence(it)) }
        absenceRepository.save(absence(3, stableId = OTHER_STABLE_ID))

        val page = absenceRepository.findVersionPage(STABLE_ID, since = 1, watermark = 4, limit = Limit.of(2))

        assertEquals(listOf(2L, 3L), page.map { it.version })
        assertEquals(epochDay(1969, 12, 30), page.first().from)
    }

    @Test
    fun `finds the live absences of a user`() {
        val live = absenceRepository.save(absence(1))
        absenceRepository.save(absence(2, deleted = true))

        assertEquals(listOf(live.id), absenceRepository.findByUserIdAndDeletedFalse(userId).map { it.id })
    }
}
