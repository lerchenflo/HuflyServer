package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface UserRepository : Repository<User, ObjectId> {
    fun save(user: User): User
    fun findById(id: ObjectId): User?
    fun findByEmail(email: String): User?
    fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<User>
}
