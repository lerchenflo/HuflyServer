package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.horse.model.Horse
import org.bson.types.ObjectId

class FakeHorseRepository : HorseRepository {
    val horses = mutableListOf<Horse>()

    override fun save(horse: Horse): Horse {
        horses.removeIf { it.id == horse.id }
        horses += horse
        return horse
    }

    override fun findById(id: ObjectId): Horse? = horses.firstOrNull { it.id == id }

    override fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<Horse> =
        horses.filter { it.stableId == stableId && !it.deleted }
}
