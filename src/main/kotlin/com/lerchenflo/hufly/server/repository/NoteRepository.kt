package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.note.model.StableNote
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.mongodb.repository.Update
import org.springframework.data.repository.Repository

interface NoteRepository : Repository<StableNote, ObjectId> {
    fun save(note: StableNote): StableNote
    fun findById(id: ObjectId): StableNote?
    fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): StableNote?

    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<StableNote>

    /** Atomic, so concurrent readers never drop each other; returns 0 for a missing or deleted note. */
    @Query("{ '_id': ?0, 'deleted': false }")
    @Update("{ '\$addToSet': { 'readByUserIds': ?1 }, '\$set': { 'version': ?2 } }")
    fun addReader(noteId: ObjectId, userId: ObjectId, version: Long): Long
}
