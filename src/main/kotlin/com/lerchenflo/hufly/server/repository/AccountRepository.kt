package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.account.model.Account
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface AccountRepository : Repository<Account, ObjectId> {
    fun save(account: Account): Account
    /** Fails with a duplicate key error if the email is taken meanwhile. */
    fun insert(account: Account): Account
    fun findById(id: ObjectId): Account?
    fun findByIdIn(ids: Collection<ObjectId>): List<Account>
    fun findByEmail(email: String): Account?
}
