package com.lerchenflo.hufly.server.note

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.idempotentCreate
import com.lerchenflo.hufly.server.core.notification.NotePosted
import com.lerchenflo.hufly.server.core.sync.SyncCollection
import com.lerchenflo.hufly.server.core.sync.VersionCounterService
import com.lerchenflo.hufly.server.core.sync.VersionSyncResponse
import com.lerchenflo.hufly.server.core.sync.versionSync
import com.lerchenflo.hufly.server.note.model.NoteResponse
import com.lerchenflo.hufly.server.note.model.StableNote
import com.lerchenflo.hufly.server.note.model.toNoteResponse
import com.lerchenflo.hufly.server.repository.NoteRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.user.model.User
import org.bson.Document
import org.bson.types.ObjectId
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Limit
import org.springframework.data.mongodb.core.mapping.event.AfterSaveEvent
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock
import java.time.LocalDate

/** Writing needs NOTE_WRITE (any note of the stable); every member reads and marks notes read. */
@Service
class NoteService(
    private val noteRepository: NoteRepository,
    private val accessService: AccessService,
    private val versionCounterService: VersionCounterService,
    private val events: ApplicationEventPublisher,
    private val clock: Clock,
) {
    data class NoteData(val title: String, val body: String, val pinned: Boolean, val visibleUntil: LocalDate?)

    fun createNote(requester: User, data: NoteData, clientId: String? = null): StableNote {
        accessService.requirePermission(requester, Permission.NOTE_WRITE)
        return idempotentCreate(clientId, { noteRepository.findByStableIdAndClientId(requester.stableId, it) }) {
            val now = clock.instant()
            save(
                StableNote(
                    stableId = requester.stableId,
                    title = requireTitle(data.title),
                    body = data.body,
                    pinned = data.pinned,
                    visibleUntil = data.visibleUntil,
                    createdByUserId = requester.id,
                    createdAt = now,
                    updatedAt = now,
                    updatedBy = requester.id,
                    clientId = clientId,
                )
            ).also { events.publishEvent(NotePosted(it.stableId, requester.id, it.id)) }
        }
    }

    fun updateNote(requester: User, noteId: ObjectId, data: NoteData): StableNote {
        accessService.requirePermission(requester, Permission.NOTE_WRITE)
        val note = stableNote(requester, noteId)
        val title = requireTitle(data.title)
        val updated = versionCounterService.withVersion(SyncCollection.NOTES) { version ->
            noteRepository.updateContent(note.id, title, data.body, data.pinned, data.visibleUntil, clock.instant(), requester.id, version)
        }
        return announceAtomicSave(note.id, updated)
    }

    fun deleteNote(requester: User, noteId: ObjectId) {
        accessService.requirePermission(requester, Permission.NOTE_WRITE)
        val note = stableNote(requester, noteId)
        save(note.copy(deleted = true, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    /** The author needs no mark. Leaves updatedAt alone: reading is not an edit. */
    fun markRead(requester: User, noteId: ObjectId): StableNote {
        val note = stableNote(requester, noteId)
        if (requester.id == note.createdByUserId || requester.id in note.readByUserIds) return note
        val added = versionCounterService.withVersion(SyncCollection.NOTES) { version ->
            noteRepository.addReader(note.id, requester.id, version)
        }
        return announceAtomicSave(note.id, added)
    }

    /** Atomic updates fire no Mongo save event, so announce them like one for the realtime hints. */
    private fun announceAtomicSave(noteId: ObjectId, matched: Long): StableNote {
        val saved = noteRepository.findById(noteId)?.takeIf { matched == 1L && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found")
        events.publishEvent(AfterSaveEvent(saved, Document(), "notes"))
        return saved
    }

    fun sync(requester: User, since: Long, pageSize: Int): VersionSyncResponse<NoteResponse> {
        val isAdmin = accessService.isAdmin(requester)
        val watermark = versionCounterService.safeWatermark(SyncCollection.NOTES)
        val rows = noteRepository.findVersionPage(requester.stableId, since, watermark, Limit.of(pageSize + 1))
        return versionSync(
            rows, since, pageSize,
            id = { it.id.toHexString() },
            version = { it.version },
            deleted = { it.deleted },
            visible = { true },
            toResponse = { it.toNoteResponse(requester.id, isAdmin || it.createdByUserId == requester.id) },
        )
    }

    fun toResponse(requester: User, note: StableNote): NoteResponse =
        note.toNoteResponse(requester.id, note.createdByUserId == requester.id || accessService.isAdmin(requester))

    private fun save(note: StableNote): StableNote =
        versionCounterService.withVersion(SyncCollection.NOTES) { version -> noteRepository.save(note.copy(version = version)) }

    private fun requireTitle(title: String): String =
        title.trim().takeIf { it.length in 1..MAX_TITLE_LENGTH } ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid title")

    private fun stableNote(requester: User, noteId: ObjectId): StableNote =
        noteRepository.findById(noteId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Note not found")
}

internal const val MAX_TITLE_LENGTH = 200
