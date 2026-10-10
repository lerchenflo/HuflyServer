package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.account.model.Account
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException

class FakeAccountRepository : AccountRepository {
    val accounts = mutableListOf<Account>()

    override fun save(account: Account): Account {
        requireFreeEmail(account)
        accounts.removeIf { it.id == account.id }
        accounts += account
        return account
    }

    override fun insert(account: Account): Account {
        if (accounts.any { it.id == account.id }) throw DuplicateKeyException("_id")
        return save(account)
    }

    override fun findById(id: ObjectId): Account? = accounts.firstOrNull { it.id == id }

    override fun findByIdIn(ids: Collection<ObjectId>): List<Account> = accounts.filter { it.id in ids }

    override fun findByEmail(email: String): Account? = accounts.firstOrNull { it.email == email }

    private fun requireFreeEmail(account: Account) {
        if (accounts.any { it.email == account.email && it.id != account.id }) throw DuplicateKeyException("email")
    }
}
