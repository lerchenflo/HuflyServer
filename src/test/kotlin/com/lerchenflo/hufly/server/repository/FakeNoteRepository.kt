package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.note.model.StableNote
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import java.time.Instant
import java.time.LocalDate

class FakeNoteRepository : NoteRepository {
    val notes = mutableListOf<StableNote>()

    override fun save(note: StableNote): StableNote {
        notes.requireUniqueClientId(note, { it.id }, { it.stableId }, { it.clientId })
        notes.removeIf { it.id == note.id }
        notes += note
        return note
    }

    override fun findById(id: ObjectId): StableNote? = notes.firstOrNull { it.id == id }

    override fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): StableNote? =
        notes.firstOrNull { it.stableId == stableId && it.clientId == clientId }

    override fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<StableNote> =
        notes.filter { it.stableId == stableId && it.version > since && it.version <= watermark }
            .sortedBy { it.version }
            .take(limit.max())

    override fun addReader(noteId: ObjectId, userId: ObjectId, version: Long): Long {
        val note = notes.firstOrNull { it.id == noteId && !it.deleted } ?: return 0
        save(note.copy(readByUserIds = (note.readByUserIds + userId).distinct(), version = version))
        return 1
    }

    override fun updateContent(
        noteId: ObjectId, title: String, body: String, pinned: Boolean, visibleUntil: LocalDate?, updatedAt: Instant, updatedBy: ObjectId, version: Long,
    ): Long {
        val note = notes.firstOrNull { it.id == noteId && !it.deleted } ?: return 0
        save(note.copy(title = title, body = body, pinned = pinned, visibleUntil = visibleUntil, updatedAt = updatedAt, updatedBy = updatedBy, version = version))
        return 1
    }
}
