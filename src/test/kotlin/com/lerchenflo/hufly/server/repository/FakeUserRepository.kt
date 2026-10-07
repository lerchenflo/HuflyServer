package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId

class FakeUserRepository : UserRepository {
    val users = mutableListOf<User>()

    override fun findByStableId(stableId: ObjectId): List<User> = users.filter { it.stableId == stableId }
    override fun deleteByStableId(stableId: ObjectId): Long = users.count { it.stableId == stableId }.toLong().also { users.removeIf { it.stableId == stableId } }

    override fun save(user: User): User {
        users.removeIf { it.id == user.id }
        users += user
        return user
    }

    override fun findById(id: ObjectId): User? = users.firstOrNull { it.id == id }

    override fun findByEmail(email: String): User? = users.firstOrNull { it.email == email }

    override fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<User> =
        users.filter { it.stableId == stableId && !it.deleted }
}
