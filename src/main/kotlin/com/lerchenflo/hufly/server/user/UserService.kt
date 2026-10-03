package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.authentication.normalizeEmail
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.picture.PictureKind
import com.lerchenflo.hufly.server.core.picture.PictureStore
import com.lerchenflo.hufly.server.core.picture.pictureUrl
import com.lerchenflo.hufly.server.core.picture.toStoredPicture
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.generatePassword
import com.lerchenflo.hufly.server.repository.RefreshTokenRepository
import com.lerchenflo.hufly.server.repository.TagRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

@Service
class UserService(
    private val userRepository: UserRepository,
    private val tagRepository: TagRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val pictureStore: PictureStore,
    private val accessService: AccessService,
    private val hashEncoder: HashEncoder,
    private val clock: Clock,
) {
    data class CreatedUser(val user: User, val generatedPassword: String)

    fun createUser(
        requester: User,
        email: String,
        displayName: String,
        phoneNumber: String?,
        roleTagIds: List<ObjectId>,
    ): CreatedUser {
        accessService.requireAdmin(requester)
        val normalizedEmail = requireFreeEmail(email, ownerId = null)
        requireRoleTags(requester.stableId, roleTagIds)
        val password = generatePassword()
        val now = clock.instant()
        val user = userRepository.save(
            User(
                stableId = requester.stableId,
                email = normalizedEmail,
                displayName = displayName,
                phoneNumber = phoneNumber,
                profilePictureUrl = null,
                hashedPassword = hashEncoder.encode(password),
                roleTagIds = roleTagIds,
                createdAt = now,
                updatedAt = now,
                updatedBy = requester.id,
            )
        )
        return CreatedUser(user, password)
    }

    fun updateUser(
        requester: User,
        userId: ObjectId,
        email: String,
        displayName: String,
        phoneNumber: String?,
        roleTagIds: List<ObjectId>,
    ): User {
        accessService.requireAdmin(requester)
        val target = stableMember(requester, userId)
        val normalizedEmail = requireFreeEmail(email, ownerId = target.id)
        requireRoleTags(requester.stableId, roleTagIds)
        return userRepository.save(
            target.copy(
                email = normalizedEmail,
                displayName = displayName,
                phoneNumber = phoneNumber,
                roleTagIds = roleTagIds,
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    fun deleteUser(requester: User, userId: ObjectId) {
        accessService.requireAdmin(requester)
        if (userId == requester.id) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The admin cannot be deleted")
        val target = stableMember(requester, userId)
        userRepository.save(target.copy(deleted = true, profilePictureUrl = null, updatedAt = clock.instant(), updatedBy = requester.id))
        refreshTokenRepository.deleteByUserId(target.id)
        pictureStore.delete(PictureKind.USER, target.id)
    }

    /** USR-6: everyone sets only the own profile picture. */
    fun setMyPicture(requester: User, upload: ByteArray): User {
        pictureStore.save(PictureKind.USER, requester.id, toStoredPicture(upload))
        val now = clock.instant()
        return userRepository.save(
            requester.copy(profilePictureUrl = pictureUrl(PictureKind.USER, requester.id, now), updatedAt = now, updatedBy = requester.id)
        )
    }

    fun deleteMyPicture(requester: User): User {
        pictureStore.delete(PictureKind.USER, requester.id)
        return userRepository.save(requester.copy(profilePictureUrl = null, updatedAt = clock.instant(), updatedBy = requester.id))
    }

    fun picture(requester: User, userId: ObjectId): ByteArray {
        val user = stableMember(requester, userId)
        return pictureStore.load(PictureKind.USER, user.id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No picture")
    }

    fun resetPassword(requester: User, userId: ObjectId): String {
        accessService.requireAdmin(requester)
        val target = stableMember(requester, userId)
        val password = generatePassword()
        userRepository.save(target.copy(hashedPassword = hashEncoder.encode(password)))
        refreshTokenRepository.deleteByUserId(target.id)
        return password
    }

    fun updateMe(requester: User, email: String, displayName: String, phoneNumber: String?): User {
        val normalizedEmail = requireFreeEmail(email, ownerId = requester.id)
        return userRepository.save(
            requester.copy(
                email = normalizedEmail,
                displayName = displayName,
                phoneNumber = phoneNumber,
                updatedAt = clock.instant(),
                updatedBy = requester.id,
            )
        )
    }

    /** Answers 400, not 401, on a wrong old password: clients treat 401 as an expired session. */
    /** Ends every other session (USR-5); the device that changed the password stays logged in. */
    fun changePassword(requester: User, oldPassword: String, newPassword: String, currentSessionId: ObjectId?) {
        if (!hashEncoder.matches(oldPassword, requester.hashedPassword)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Old password is wrong")
        }
        userRepository.save(requester.copy(hashedPassword = hashEncoder.encode(newPassword)))
        if (currentSessionId != null) {
            refreshTokenRepository.deleteByUserIdAndIdNot(requester.id, currentSessionId)
        } else {
            refreshTokenRepository.deleteByUserId(requester.id)
        }
    }

    /** Emails are unique across all stables, deleted users included (USR-4). */
    private fun requireFreeEmail(email: String, ownerId: ObjectId?): String {
        val normalized = normalizeEmail(email)
        val existing = userRepository.findByEmail(normalized)
        if (existing != null && existing.id != ownerId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Email already in use")
        }
        return normalized
    }

    private fun requireRoleTags(stableId: ObjectId, roleTagIds: List<ObjectId>) {
        val valid = roleTagIds.all { id ->
            tagRepository.findById(id)?.let { it.stableId == stableId && !it.deleted && it.type == TagType.USER_ROLE } == true
        }
        if (!valid) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown role tag")
    }

    /** 404 for users of other stables too, so ids from other stables are indistinguishable from unknown ones. */
    private fun stableMember(requester: User, userId: ObjectId): User =
        userRepository.findById(userId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "User not found")
}
