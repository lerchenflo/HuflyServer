package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.stable.model.MealTimes
import com.lerchenflo.hufly.server.stable.model.Stable
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException

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

    override fun findByInviteCodeAndDeletedFalse(inviteCode: String): Stable? =
        stables.firstOrNull { it.inviteCode == inviteCode && !it.deleted }

    override fun existsByInviteCode(inviteCode: String): Boolean = stables.any { it.inviteCode == inviteCode }

    override fun setNameAndPlace(id: ObjectId, name: String, place: String?, updatedAt: Long, updatedBy: ObjectId): Long {
        val stable = findById(id) ?: return 0
        save(stable.copy(name = name, place = place, updatedAt = updatedAt, updatedBy = updatedBy))
        return 1
    }

    override fun setMealTimes(id: ObjectId, mealTimes: MealTimes, updatedAt: Long, updatedBy: ObjectId): Long {
        val stable = findById(id) ?: return 0
        save(stable.copy(mealTimes = mealTimes, updatedAt = updatedAt, updatedBy = updatedBy))
        return 1
    }

    override fun setInviteCode(id: ObjectId, inviteCode: String): Long {
        if (stables.any { it.inviteCode == inviteCode && it.id != id }) throw DuplicateKeyException("inviteCode")
        val stable = findById(id) ?: return 0
        save(stable.copy(inviteCode = inviteCode))
        return 1
    }
}
