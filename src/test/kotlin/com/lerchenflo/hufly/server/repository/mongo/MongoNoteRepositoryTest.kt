package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.note.model.StableNote
import com.lerchenflo.hufly.server.repository.NoteRepository
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.epochDay
import com.lerchenflo.hufly.server.testdata.millis
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
class MongoNoteRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var noteRepository: NoteRepository

    private fun note(version: Long, stableId: ObjectId = STABLE_ID, deleted: Boolean = false) = StableNote(
        stableId = stableId,
        title = "Hufschmied",
        body = "",
        pinned = false,
        visibleUntil = epochDay(2026, 10, 9),
        createdByUserId = ObjectId.get(),
        createdAt = 0L,
        updatedAt = 0L,
        updatedBy = ObjectId.get(),
        deleted = deleted,
        version = version,
    )

    @Test
    fun `version page returns the own stable's rows after since up to the watermark, ascending and limited`() {
        listOf(5L, 1L, 3L, 2L, 4L).forEach { noteRepository.save(note(it)) }
        noteRepository.save(note(3, stableId = OTHER_STABLE_ID))

        val page = noteRepository.findVersionPage(STABLE_ID, since = 1, watermark = 4, limit = Limit.of(2))

        assertEquals(listOf(2L, 3L), page.map { it.version })
        assertEquals(epochDay(2026, 10, 9), page.first().visibleUntil)
    }

    @Test
    fun `adding a reader is atomic, idempotent and skips deleted notes`() {
        val note = noteRepository.save(note(1))
        val anna = ObjectId.get()

        assertEquals(1, noteRepository.addReader(note.id, anna, 7))
        assertEquals(1, noteRepository.addReader(note.id, anna, 8))
        assertEquals(listOf(anna), noteRepository.findById(note.id)!!.readByUserIds)
        assertEquals(8, noteRepository.findById(note.id)!!.version)

        val gone = noteRepository.save(note(2, deleted = true))
        assertEquals(0, noteRepository.addReader(gone.id, anna, 9))
    }

    @Test
    fun `editing the content is atomic, keeps the readers and skips deleted notes`() {
        val note = noteRepository.save(note(1))
        val anna = ObjectId.get()
        val editor = ObjectId.get()
        noteRepository.addReader(note.id, anna, 2)

        val at = millis("2026-10-05T10:00:00.123Z")
        assertEquals(1, noteRepository.updateContent(note.id, "Neu", "Text", true, epochDay(1969, 12, 31), at, editor, 3))

        val stored = noteRepository.findById(note.id)!!
        assertEquals(listOf(anna), stored.readByUserIds)
        assertEquals(listOf("Neu", "Text", true), listOf(stored.title, stored.body, stored.pinned))
        assertEquals(epochDay(1969, 12, 31), stored.visibleUntil)
        assertEquals(at, stored.updatedAt)
        assertEquals(editor to 3L, stored.updatedBy to stored.version)
        assertEquals(1, noteRepository.updateContent(note.id, "Neu", "Text", true, null, at, editor, 4))
        assertEquals(null, noteRepository.findById(note.id)!!.visibleUntil)

        val gone = noteRepository.save(note(5, deleted = true))
        assertEquals(0, noteRepository.updateContent(gone.id, "x", "", false, null, at, editor, 6))
    }
}
