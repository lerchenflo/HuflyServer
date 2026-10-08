package com.lerchenflo.hufly.server.note

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.notification.NotePosted
import com.lerchenflo.hufly.server.core.notification.NotificationEvent
import com.lerchenflo.hufly.server.core.security.MutableClock
import com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.note.model.StableNote
import com.lerchenflo.hufly.server.repository.FakeNoteRepository
import com.lerchenflo.hufly.server.repository.FakeStableRepository
import com.lerchenflo.hufly.server.repository.FakeTagRepository
import com.lerchenflo.hufly.server.repository.FakeUserRepository
import com.lerchenflo.hufly.server.repository.NoteRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.testdata.OTHER_STABLE_ID
import com.lerchenflo.hufly.server.testdata.STABLE_ID
import com.lerchenflo.hufly.server.testdata.days
import com.lerchenflo.hufly.server.testdata.epochDay
import com.lerchenflo.hufly.server.testdata.hours
import com.lerchenflo.hufly.server.testdata.testStable
import com.lerchenflo.hufly.server.testdata.testTag
import com.lerchenflo.hufly.server.testdata.testUser
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Stable notes ("Aushänge", client decision 2026-10-05): NOTE_WRITE posts, everyone reads, only author and admin see readers. */
class NoteServiceTest {

    private val clock = MutableClock()
    private val userRepository = FakeUserRepository()
    private val stableRepository = FakeStableRepository()
    private val tagRepository = FakeTagRepository()
    private val noteRepository = FakeNoteRepository()
    private val accessService = AccessService(userRepository, stableRepository, tagRepository)
    private val versionCounterService = VersionCounterService(FakeVersionCounterStore())
    private val published = mutableListOf<Any>()
    private val events = ApplicationEventPublisher { published += it }
    private val noteService = NoteService(noteRepository, accessService, versionCounterService, events, clock)

    private val admin = testUser()
    private val writerTag = testTag(permissions = setOf(Permission.NOTE_WRITE))
    private val writer = testUser(roleTagIds = listOf(writerTag.id))
    private val anna = testUser()
    private val ben = testUser()
    private val foreigner = testUser(stableId = OTHER_STABLE_ID)

    private val data = NoteService.NoteData("  Hufschmied kommt  ", "Am Freitag ab 8 Uhr", pinned = true, visibleUntil = epochDay(2026, 10, 9))

    @BeforeTest
    fun setUp() {
        listOf(admin, writer, anna, ben, foreigner).forEach { userRepository.save(it) }
        tagRepository.save(writerTag)
        stableRepository.save(testStable(adminUserId = admin.id))
        clock.advance(days(1))
    }

    private fun assertStatus(status: HttpStatus, block: () -> Unit) {
        assertEquals(status, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    private fun stored(id: ObjectId) = noteRepository.findById(id)!!

    private fun post(): StableNote = noteService.createNote(writer, data)

    @Test
    fun `a member with NOTE_WRITE posts a note with a trimmed title`() {
        val note = post()

        val stored = stored(note.id)
        assertEquals(STABLE_ID, stored.stableId)
        assertEquals("Hufschmied kommt", stored.title)
        assertEquals("Am Freitag ab 8 Uhr", stored.body)
        assertTrue(stored.pinned)
        assertEquals(epochDay(2026, 10, 9), stored.visibleUntil)
        assertEquals(writer.id, stored.createdByUserId)
        assertEquals(clock.millis(), stored.createdAt)
        assertEquals(emptyList(), stored.readByUserIds)
        assertTrue(stored.version > 0)
    }

    @Test
    fun `posting, editing and deleting need NOTE_WRITE, the admin needs no tag`() {
        assertStatus(HttpStatus.FORBIDDEN) { noteService.createNote(anna, data) }
        val note = noteService.createNote(admin, data)
        assertStatus(HttpStatus.FORBIDDEN) { noteService.updateNote(anna, note.id, data) }
        assertStatus(HttpStatus.FORBIDDEN) { noteService.deleteNote(anna, note.id) }
    }

    @Test
    fun `any writer edits any note of the stable without touching author, creation time or readers`() {
        val note = post()
        noteService.markRead(anna, note.id)
        clock.advance(hours(1))

        noteService.updateNote(admin, note.id, NoteService.NoteData("Hufschmied verschoben", "", pinned = false, visibleUntil = null))

        val stored = stored(note.id)
        assertEquals("Hufschmied verschoben", stored.title)
        assertEquals(false, stored.pinned)
        assertEquals(null, stored.visibleUntil)
        assertEquals(writer.id, stored.createdByUserId)
        assertEquals(note.createdAt, stored.createdAt)
        assertEquals(listOf(anna.id), stored.readByUserIds)
        assertEquals(admin.id, stored.updatedBy)
        assertEquals(clock.millis(), stored.updatedAt)
    }

    @Test
    fun `deleting soft deletes, a deleted or foreign note is not found`() {
        val note = post()
        noteService.deleteNote(writer, note.id)

        assertTrue(stored(note.id).deleted)
        assertStatus(HttpStatus.NOT_FOUND) { noteService.deleteNote(writer, note.id) }
        assertStatus(HttpStatus.NOT_FOUND) { noteService.updateNote(writer, note.id, data) }
        assertStatus(HttpStatus.NOT_FOUND) { noteService.markRead(anna, note.id) }
        val other = post()
        assertStatus(HttpStatus.NOT_FOUND) { noteService.markRead(foreigner, other.id) }
        assertStatus(HttpStatus.NOT_FOUND) { noteService.markRead(anna, ObjectId.get()) }
    }

    @Test
    fun `a retried create with the same clientId answers the first note`() {
        val first = noteService.createNote(writer, data, clientId = "n1")

        assertEquals(first.id, noteService.createNote(writer, data, clientId = "n1").id)
        assertEquals(1, noteRepository.notes.size)
    }

    @Test
    fun `marking read adds the reader once, bumps the version and announces the change`() {
        val note = post()

        val read = noteService.markRead(anna, note.id)
        assertEquals(listOf(anna.id), stored(note.id).readByUserIds)
        assertTrue(read.version > note.version)
        assertEquals(stored(note.id), published.filterIsInstance<AfterSaveEvent<*>>().single().source)

        noteService.markRead(anna, note.id)
        noteService.markRead(writer, note.id)
        assertEquals(listOf(anna.id), stored(note.id).readByUserIds)
        assertEquals(read.version, stored(note.id).version)
        assertEquals(1, published.filterIsInstance<AfterSaveEvent<*>>().size)
    }

    @Test
    fun `only author and admin see every reader, others at most themselves`() {
        val note = post()
        noteService.markRead(anna, note.id)
        val read = noteService.markRead(ben, note.id)

        assertEquals(listOf(anna.id, ben.id).map { it.toHexString() }, noteService.toResponse(writer, read).readByUserIds)
        assertEquals(listOf(anna.id, ben.id).map { it.toHexString() }, noteService.toResponse(admin, read).readByUserIds)
        assertEquals(listOf(ben.id.toHexString()), noteService.toResponse(ben, read).readByUserIds)
        val otherWriter = testUser(roleTagIds = listOf(writerTag.id)).also { userRepository.save(it) }
        assertEquals(emptyList(), noteService.toResponse(otherWriter, read).readByUserIds)
    }

    @Test
    fun `sync answers every note of the own stable with the reader list filtered per requester`() {
        val note = post()
        noteService.markRead(anna, note.id)
        val foreign = noteRepository.save(note.copy(id = ObjectId.get(), stableId = OTHER_STABLE_ID))
        val deleted = post().also { noteService.deleteNote(writer, it.id) }

        val forBen = noteService.sync(ben, 0, 100)
        assertEquals(listOf(note.id.toHexString()), forBen.updatedEntries.map { it.id })
        assertEquals(emptyList(), forBen.updatedEntries.single().readByUserIds)
        assertEquals(listOf(deleted.id.toHexString()), forBen.deletedEntries)
        assertEquals(listOf(anna.id.toHexString()), noteService.sync(writer, 0, 100).updatedEntries.single().readByUserIds)
        assertEquals(listOf(foreign.id.toHexString()), noteService.sync(foreigner, 0, 100).updatedEntries.map { it.id })
    }

    @Test
    fun `posting a note announces it for notifications, edits and retries do not`() {
        val note = noteService.createNote(writer, data, clientId = "n1")
        noteService.createNote(writer, data, clientId = "n1")
        noteService.updateNote(writer, note.id, data)

        assertEquals(listOf(NotePosted(STABLE_ID, writer.id, note.id)), published.filterIsInstance<NotificationEvent>())
    }

    @Test
    fun `an edit keeps a read mark that lands between its load and its save`() {
        val note = post()
        // Ben's read mark arrives right after the edit loaded the note.
        var raced = false
        val racing = object : NoteRepository by noteRepository {
            override fun findById(id: ObjectId) = noteRepository.findById(id).also {
                if (!raced) noteRepository.addReader(id, ben.id, 999).also { raced = true }
            }
        }
        val service = NoteService(racing, accessService, versionCounterService, events, clock)

        val edited = service.updateNote(writer, note.id, NoteService.NoteData("Neu", "", pinned = false, visibleUntil = null))

        assertEquals(listOf(ben.id), stored(note.id).readByUserIds)
        assertEquals("Neu", stored(note.id).title)
        assertEquals(stored(note.id), edited)
        assertEquals(stored(note.id), published.filterIsInstance<AfterSaveEvent<*>>().last().source)
    }
}
