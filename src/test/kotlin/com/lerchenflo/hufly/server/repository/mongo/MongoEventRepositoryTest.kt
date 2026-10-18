package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.core.recurrence.Recurrence
import com.lerchenflo.hufly.server.core.recurrence.RecurrenceFrequency
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
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
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
    @Autowired lateinit var mongoTemplate: MongoTemplate

    private val creator = ObjectId.get()

    private fun event(version: Long, stableId: ObjectId = STABLE_ID) = Event(
        stableId = stableId,
        creatorUserId = creator,
        title = "v$version",
        description = "",
        startAt = 0L,
        endAt = 0L,
        horseIds = emptyList(),
        createdAt = 0L,
        updatedAt = 0L,
        updatedBy = creator,
        version = version,
    )

    private fun invitation(version: Long, userId: ObjectId, stableId: ObjectId = STABLE_ID) = EventInvitation(
        stableId = stableId,
        eventId = ObjectId.get(),
        userId = userId,
        status = InvitationStatus.PENDING,
        invitedAt = 0L,
        respondedAt = null,
        updatedAt = 0L,
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
    fun `an event stored before participantsVisible shows its participants`() {
        val hidden = eventRepository.save(event(1).copy(participantsVisible = false))
        val older = eventRepository.save(event(2).copy(participantsVisible = false))
        mongoTemplate.updateFirst(Query(Criteria.where("_id").`is`(older.id)), Update().unset("participantsVisible"), Event::class.java)

        assertEquals(false, eventRepository.findById(hidden.id)?.participantsVisible)
        assertEquals(true, eventRepository.findById(older.id)?.participantsVisible)
    }

    @Test
    fun `invitation version page and user lookup`() {
        val anna = ObjectId.get()
        listOf(5L, 1L, 3L, 2L, 4L).forEach { invitationRepository.save(invitation(it, anna)) }
        invitationRepository.save(invitation(3, anna, stableId = OTHER_STABLE_ID).copy(deleted = true))

        assertEquals(listOf(2L, 3L), invitationRepository.findVersionPage(STABLE_ID, 1, 4, Limit.of(2)).map { it.version })
        assertEquals(5, invitationRepository.findByUserIdAndDeletedFalse(anna).size)
    }

    @Test
    fun `reminder queries find single events starting in the window and series started by its end`() {
        val window = 1_000_000L
        val weekly = Recurrence(RecurrenceFrequency.WEEKLY, 1, emptyList(), null, null, "UTC")
        fun save(title: String, startAt: Long, recurrence: Recurrence? = null, deleted: Boolean = false) =
            eventRepository.save(event(0, OTHER_STABLE_ID).copy(creatorUserId = ObjectId.get(), title = title, startAt = startAt, endAt = startAt, recurrence = recurrence, deleted = deleted))
        save("at start", window)
        val inside = save("inside", window + 50)
        save("at end", window + 100)
        save("after", window + 101)
        save("deleted", window + 50, deleted = true)
        save("series", window + 50, weekly)
        save("old series", 0, weekly)
        save("later series", window + 101, weekly)
        save("deleted series", 0, weekly, deleted = true)

        assertEquals(setOf("inside", "at end"), eventRepository.findSingleStarting(window, window + 100).map { it.title }.toSet())
        assertEquals(setOf("series", "old series"), eventRepository.findSeriesStartingBy(window + 100).map { it.title }.toSet())
        assertEquals(listOf("inside"), eventRepository.findByIdIn(listOf(inside.id)).map { it.title })
    }

    @Test
    fun `invitations load by their events`() {
        val event = ObjectId.get()
        invitationRepository.save(invitation(1, ObjectId.get(), OTHER_STABLE_ID).copy(eventId = event))
        invitationRepository.save(invitation(2, ObjectId.get(), OTHER_STABLE_ID).copy(eventId = event, deleted = true))
        invitationRepository.save(invitation(3, ObjectId.get(), OTHER_STABLE_ID))

        assertEquals(listOf(1L), invitationRepository.findByEventIdInAndDeletedFalse(listOf(event)).map { it.version })
    }
}
