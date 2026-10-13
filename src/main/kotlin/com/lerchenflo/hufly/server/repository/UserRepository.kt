package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface UserRepository : Repository<User, ObjectId> {
    fun findByStableId(stableId: ObjectId): List<User>
    fun deleteByStableId(stableId: ObjectId): Long
    fun save(user: User): User
    fun findById(id: ObjectId): User?
    /** Fails with a duplicate key error if the account has a live membership in that stable already. */
    fun insert(user: User): User
    fun findByAccountIdAndDeletedFalse(accountId: ObjectId): List<User>
    fun findByAccountIdInAndDeletedFalse(accountIds: Collection<ObjectId>): List<User>
    fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<User>
}
