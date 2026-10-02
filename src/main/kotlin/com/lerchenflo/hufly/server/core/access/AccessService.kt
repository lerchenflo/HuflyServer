package com.lerchenflo.hufly.server.core.access

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
    private val stableRepository: StableRepository,
    private val tagRepository: TagRepository,
) {
    /** Call with the id from `requireAuth()`. A deleted user keeps a valid access token for up to 15 minutes. */
    fun requester(userId: ObjectId): User =
        userRepository.findById(userId)?.takeUnless { it.deleted }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "User no longer exists")

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

    private fun forbidden() = ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed")
}
