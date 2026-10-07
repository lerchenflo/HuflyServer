package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.stable.model.Stable
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface StableRepository : Repository<Stable, ObjectId> {
    fun deleteById(id: ObjectId)
    fun save(stable: Stable): Stable
    fun findById(id: ObjectId): Stable?
    fun findByDeletedFalse(): List<Stable>
}
