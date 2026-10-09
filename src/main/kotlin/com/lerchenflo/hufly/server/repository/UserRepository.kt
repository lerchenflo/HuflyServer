package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface UserRepository : Repository<User, ObjectId> {
    fun findByStableId(stableId: ObjectId): List<User>
    fun deleteByStableId(stableId: ObjectId): Long
    fun save(user: User): User
    fun findById(id: ObjectId): User?
    /** Fails with a duplicate key error if the account has a live membership already (one stable per account for now). */
    fun insert(user: User): User
    fun findFirstByAccountIdAndDeletedFalse(accountId: ObjectId): User?
    fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<User>
}
