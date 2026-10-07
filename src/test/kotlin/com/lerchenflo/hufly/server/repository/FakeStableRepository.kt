package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.stable.model.Stable
import org.bson.types.ObjectId

class FakeStableRepository : StableRepository {
    val stables = mutableListOf<Stable>()

    override fun deleteById(id: ObjectId) { stables.removeIf { it.id == id } }

    override fun save(stable: Stable): Stable {
        stables.removeIf { it.id == stable.id }
        stables += stable
        return stable
    }

    override fun findById(id: ObjectId): Stable? = stables.firstOrNull { it.id == id }

    override fun findByDeletedFalse(): List<Stable> = stables.filter { !it.deleted }
}
