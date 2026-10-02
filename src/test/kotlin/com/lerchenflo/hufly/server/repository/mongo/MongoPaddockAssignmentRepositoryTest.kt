package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.paddock.model.PaddockAssignment
import com.lerchenflo.hufly.server.repository.PaddockAssignmentRepository
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
import kotlin.test.Test
import kotlin.test.assertEquals

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoPaddockAssignmentRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var assignmentRepository: PaddockAssignmentRepository

    private fun assignment(version: Long, stableId: ObjectId = STABLE_ID) = PaddockAssignment(
        stableId = stableId,
        paddockId = ObjectId.get(),
        groupIds = emptyList(),
        horseIds = listOf(ObjectId.get()),
        startAt = Instant.EPOCH,
        endAt = null,
        comment = "",
        updatedAt = Instant.EPOCH,
        updatedBy = ObjectId.get(),
        version = version,
    )

    @Test
    fun `version page returns the own stable's rows after since up to the watermark, ascending and limited`() {
        listOf(5L, 1L, 3L, 2L, 4L).forEach { assignmentRepository.save(assignment(it)) }
        assignmentRepository.save(assignment(3, stableId = OTHER_STABLE_ID))

        val page = assignmentRepository.findVersionPage(STABLE_ID, since = 1, watermark = 4, limit = Limit.of(2))

        assertEquals(listOf(2L, 3L), page.map { it.version })
    }
}
