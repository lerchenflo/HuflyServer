package com.lerchenflo.hufly.server.core.access

import com.lerchenflo.hufly.server.account.model.Account
import com.lerchenflo.hufly.server.core.CodedException
import com.lerchenflo.hufly.server.repository.AccountRepository
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.TagRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** Who the requester is and what they may do. The stable admin bypasses role tags (TAG-4). */
@Service
class AccessService(
    private val userRepository: UserRepository,
    private val accountRepository: AccountRepository,
    private val stableRepository: StableRepository,
    private val tagRepository: TagRepository,
) {
    /**
     * The requester's stable membership; call with the id from `requireAuth()`. An account without a stable answers
     * 403 `NO_STABLE`, so it never reaches stable data. A deleted account keeps a valid access token for up to 15
     * minutes and answers 401.
     */
    fun requester(accountId: ObjectId): User {
        val account = requireAccount(accountId)
        return userRepository.findFirstByAccountIdAndDeletedFalse(account.id)
            ?: throw CodedException(HttpStatus.FORBIDDEN, NO_STABLE, "Not a member of a stable")
    }

    /** For account-level endpoints (sessions, push tokens, settings, password) that work without a stable too. */
    fun requireAccount(accountId: ObjectId): Account =
        accountRepository.findById(accountId)?.takeUnless { it.deleted }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Account no longer exists")

    fun isAdmin(user: User): Boolean = stableRepository.findById(user.stableId)?.adminUserId == user.id

    fun effectivePermissions(user: User): Set<Permission> {
        if (isAdmin(user)) return Permission.entries.toSet()
        return tagRepository.findByStableIdAndDeletedFalse(user.stableId)
            .filter { it.type == TagType.USER_ROLE && it.id in user.roleTagIds }
            .flatMap { it.permissions }
            .flatMapTo(mutableSetOf()) { listOfNotNull(it, it.impliedView) }
    }

    fun requireAdmin(user: User) {
        if (!isAdmin(user)) throw forbidden()
    }

    fun requirePermission(user: User, permission: Permission) {
        if (permission !in effectivePermissions(user)) throw forbidden()
    }

    /**
     * [permission] covers every horse; HORSE_EDIT_OWN covers only horses the requester owns, so it needs at least one
     * owner id in [horseOwnerIds] and every one of them the requester's.
     */
    fun hasHorsePermission(user: User, permission: Permission, horseOwnerIds: Collection<ObjectId?>): Boolean {
        val permissions = effectivePermissions(user)
        return permission in permissions ||
            Permission.HORSE_EDIT_OWN in permissions && horseOwnerIds.isNotEmpty() && horseOwnerIds.all { it == user.id }
    }

    fun requireHorsePermission(user: User, permission: Permission, horseOwnerIds: Collection<ObjectId?>) {
        if (!hasHorsePermission(user, permission, horseOwnerIds)) throw forbidden()
    }

    private fun forbidden() = ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed")

    companion object {
        const val NO_STABLE = "NO_STABLE"
    }
}
