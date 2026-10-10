package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.account.model.Account
import com.lerchenflo.hufly.server.repository.AccountRepository
import com.lerchenflo.hufly.server.user.model.User
import com.lerchenflo.hufly.server.user.model.UserResponse
import com.lerchenflo.hufly.server.user.model.toUserResponse
import org.springframework.stereotype.Service

/** Builds [UserResponse]s, which need each member's login for `loginManagedByStable`. */
@Service
class UserResponses(private val accountRepository: AccountRepository) {

    fun of(user: User): UserResponse = user.toUserResponse(managedByStable(user, accountRepository.findById(user.accountId)))

    /** One account lookup for all [users]; answers by user. */
    fun of(users: List<User>): Map<User, UserResponse> {
        val accounts = accountRepository.findByIdIn(users.map { it.accountId }.distinct()).associateBy { it.id }
        return users.associateWith { it.toUserResponse(managedByStable(it, accounts[it.accountId])) }
    }

    /** Same rule as `UserService.managedAccount`. */
    private fun managedByStable(user: User, account: Account?) = account?.createdByStableId == user.stableId
}
