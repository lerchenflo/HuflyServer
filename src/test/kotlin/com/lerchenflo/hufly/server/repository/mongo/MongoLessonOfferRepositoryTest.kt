package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.event.model.LessonOffer
import com.lerchenflo.hufly.server.repository.LessonOfferRepository
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
import kotlin.test.assertNull

/** Runs the derived queries and the unique index against a real MongoDB; the in-memory fakes cannot catch them. */
@SpringBootTest(properties = ["spring.data.mongodb.auto-index-creation=true"])
@Testcontainers(disabledWithoutDocker = true)
class MongoLessonOfferRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var repository: LessonOfferRepository

    private fun offer(invitationId: ObjectId, occurrenceStartAt: Long?, stableId: ObjectId = STABLE_ID) =
        LessonOffer(stableId = stableId, eventId = ObjectId.get(), invitationId = invitationId, userId = ObjectId.get(), occurrenceStartAt = occurrenceStartAt, offeredAt = 1L)

    @Test
    fun `one offer per invitation and date, a single event's keyed null`() {
        val single = ObjectId.get()
        val series = ObjectId.get()
        val singleOffer = repository.save(offer(single, null))
        assertFailsWith<DuplicateKeyException> { repository.save(offer(single, null)) }
        repository.save(offer(series, 100L))
        repository.save(offer(series, 200L))
        repository.save(offer(ObjectId.get(), null, stableId = OTHER_STABLE_ID))

        assertEquals(singleOffer.id, repository.findByInvitationIdAndOccurrenceStartAt(single, null)?.id)
        assertEquals(200L, repository.findByInvitationIdAndOccurrenceStartAt(series, 200L)?.occurrenceStartAt)
        assertNull(repository.findByInvitationIdAndOccurrenceStartAt(series, null))
        assertEquals(1L, repository.deleteByStableId(OTHER_STABLE_ID))
        repository.delete(singleOffer)
        assertEquals(listOf(100L, 200L), repository.findAll().map { it.occurrenceStartAt }.sortedBy { it })
    }
}
