package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.horse.model.Horse
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface HorseRepository : Repository<Horse, ObjectId> {
    fun save(horse: Horse): Horse
    fun findById(id: ObjectId): Horse?
    fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<Horse>
}
