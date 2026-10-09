package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.account.model.Account
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException

/**
 * Like production, every membership has a login: saving a user adds a matching account to [accounts] unless one
 * exists (tests that need a password or an account without a stable save the account themselves).
 */
class FakeUserRepository(val accounts: FakeAccountRepository = FakeAccountRepository()) : UserRepository {
    /** Clearing it also clears [accounts], since Spring tests share these fakes and reset them through `users.clear()`. */
    val users: MutableList<User> = object : ArrayList<User>() {
        override fun clear() {
            super.clear()
            accounts.accounts.clear()
        }
    }

    /** Saves [user] with a login whose password hash is [hashedPassword]. */
    fun saveWithLogin(user: User, hashedPassword: String): User {
        save(user)
        accounts.save(accounts.findById(user.accountId)!!.copy(hashedPassword = hashedPassword))
        return user
    }

    override fun findByStableId(stableId: ObjectId): List<User> = users.filter { it.stableId == stableId }
    override fun deleteByStableId(stableId: ObjectId): Long = users.count { it.stableId == stableId }.toLong().also { users.removeIf { it.stableId == stableId } }

    override fun save(user: User): User {
        users.removeIf { it.id == user.id }
        users += user
        if (accounts.findById(user.accountId) == null) {
            accounts.accounts += Account(
                id = user.accountId, email = user.email, displayName = user.displayName, hashedPassword = "unused",
                createdByStableId = user.stableId, createdAt = user.createdAt, updatedAt = user.updatedAt, deleted = user.deleted,
            )
        }
        return user
    }

    override fun findById(id: ObjectId): User? = users.firstOrNull { it.id == id }

    override fun insert(user: User): User {
        val accountTaken = !user.deleted && users.any { it.accountId == user.accountId && !it.deleted }
        if (accountTaken || users.any { it.id == user.id }) throw DuplicateKeyException("accountId")
        return save(user)
    }

    override fun findFirstByAccountIdAndDeletedFalse(accountId: ObjectId): User? =
        users.firstOrNull { it.accountId == accountId && !it.deleted }

    override fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<User> =
        users.filter { it.stableId == stableId && !it.deleted }
}
