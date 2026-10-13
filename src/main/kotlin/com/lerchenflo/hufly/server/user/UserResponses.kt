package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.account.model.Account
import com.lerchenflo.hufly.server.repository.AccountRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.user.model.User
import com.lerchenflo.hufly.server.user.model.UserResponse
import com.lerchenflo.hufly.server.user.model.toUserResponse
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

/** Builds [UserResponse]s, which need each member's login for `loginManagedByStable`. */
@Service
class UserResponses(private val accountRepository: AccountRepository, private val userRepository: UserRepository) {

    fun of(user: User): UserResponse = of(listOf(user)).getValue(user)

    /** One account and one membership lookup for all [users]; answers by user. */
    fun of(users: List<User>): Map<User, UserResponse> {
        val accounts = accountRepository.findByIdIn(users.map { it.accountId }.distinct()).associateBy { it.id }
        val createdHere = users.filter { accounts[it.accountId]?.createdByStableId == it.stableId }.map { it.accountId }.distinct()
        val memberships = if (createdHere.isEmpty()) emptyMap()
        else userRepository.findByAccountIdInAndDeletedFalse(createdHere).groupBy { it.accountId }
        return users.associateWith {
            it.toUserResponse(stableManagesLogin(it.stableId, accounts[it.accountId], memberships[it.accountId].orEmpty()))
        }
    }
}

/**
 * The stable that created a login manages it (password reset, login email, erasing it with the member) only while
 * the login has no live membership in another stable; from then on it belongs to the person.
 */
fun stableManagesLogin(stableId: ObjectId, account: Account?, liveMemberships: List<User>): Boolean =
    account?.createdByStableId == stableId && liveMemberships.all { it.stableId == stableId }

/**
 * Call after the account's memberships changed: its row in the stable that created the login gets a new `updatedAt`,
 * so that stable's delta sync sends the possibly flipped `loginManagedByStable` again.
 */
fun restampCreatingMembership(accountId: ObjectId, accountRepository: AccountRepository, userRepository: UserRepository, now: Long) {
    val stableId = accountRepository.findById(accountId)?.createdByStableId ?: return
    val row = userRepository.findByAccountIdAndDeletedFalse(accountId).firstOrNull { it.stableId == stableId } ?: return
    userRepository.save(row.copy(updatedAt = now))
}
