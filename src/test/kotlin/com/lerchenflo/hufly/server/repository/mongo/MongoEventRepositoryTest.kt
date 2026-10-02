package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import com.lerchenflo.hufly.server.event.model.InvitationStatus
import com.lerchenflo.hufly.server.repository.EventInvitationRepository
import com.lerchenflo.hufly.server.repository.EventRepository
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
class MongoEventRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var eventRepository: EventRepository
    @Autowired lateinit var invitationRepository: EventInvitationRepository

    private val creator = ObjectId.get()

    private fun event(version: Long, stableId: ObjectId = STABLE_ID) = Event(
        stableId = stableId,
        creatorUserId = creator,
        title = "v$version",
        description = "",
        startAt = Instant.EPOCH,
        endAt = Instant.EPOCH,
        horseIds = emptyList(),
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
        updatedBy = creator,
        version = version,
    )

    private fun invitation(version: Long, userId: ObjectId, stableId: ObjectId = STABLE_ID) = EventInvitation(
        stableId = stableId,
        eventId = ObjectId.get(),
        userId = userId,
        status = InvitationStatus.PENDING,
        invitedAt = Instant.EPOCH,
        respondedAt = null,
        updatedAt = Instant.EPOCH,
        updatedBy = creator,
        version = version,
    )

    @Test
    fun `event version page and creator lookup`() {
        listOf(5L, 1L, 3L, 2L, 4L).forEach { eventRepository.save(event(it)) }
        eventRepository.save(event(3, stableId = OTHER_STABLE_ID))

        assertEquals(listOf(2L, 3L), eventRepository.findVersionPage(STABLE_ID, 1, 4, Limit.of(2)).map { it.version })
        assertEquals(6, eventRepository.findByCreatorUserIdAndDeletedFalse(creator).size)
    }

    @Test
    fun `invitation version page and user lookup`() {
        val anna = ObjectId.get()
        listOf(5L, 1L, 3L, 2L, 4L).forEach { invitationRepository.save(invitation(it, anna)) }
        invitationRepository.save(invitation(3, anna, stableId = OTHER_STABLE_ID).copy(deleted = true))

        assertEquals(listOf(2L, 3L), invitationRepository.findVersionPage(STABLE_ID, 1, 4, Limit.of(2)).map { it.version })
        assertEquals(5, invitationRepository.findByUserIdAndDeletedFalse(anna).size)
    }
}
