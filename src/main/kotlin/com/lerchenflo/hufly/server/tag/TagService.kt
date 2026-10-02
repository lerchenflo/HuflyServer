package com.lerchenflo.hufly.server.tag

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.repository.TagRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.tag.model.Tag
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

/** Only the admin manages tags (TAG-2). */
@Service
class TagService(
    private val tagRepository: TagRepository,
    private val accessService: AccessService,
    private val clock: Clock,
) {
    fun createTag(requester: User, name: String, type: TagType, color: String, permissions: Set<Permission>): Tag {
        accessService.requireAdmin(requester)
        requirePermissionsFit(type, permissions)
        return tagRepository.save(
            Tag(
                stableId = requester.stableId,
                name = name,
                type = type,
                color = color,
                permissions = permissions,
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    fun updateTag(requester: User, tagId: ObjectId, name: String, color: String, permissions: Set<Permission>): Tag {
        accessService.requireAdmin(requester)
        val tag = stableTag(requester, tagId)
        requirePermissionsFit(tag.type, permissions)
        return tagRepository.save(
            tag.copy(name = name, color = color, permissions = permissions, updatedAt = clock.instant(), updatedBy = requester.id)
        )
    }

    fun deleteTag(requester: User, tagId: ObjectId) {
        accessService.requireAdmin(requester)
        val tag = stableTag(requester, tagId)
        tagRepository.save(tag.copy(deleted = true, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    private fun requirePermissionsFit(type: TagType, permissions: Set<Permission>) {
        if (type != TagType.USER_ROLE && permissions.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Only USER_ROLE tags carry permissions")
        }
    }

    private fun stableTag(requester: User, tagId: ObjectId): Tag =
        tagRepository.findById(tagId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Tag not found")
}
