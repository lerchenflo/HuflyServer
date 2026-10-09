package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.absence.AbsenceService
import com.lerchenflo.hufly.server.account.model.Account
import com.lerchenflo.hufly.server.account.model.JoinRequestStatus
import com.lerchenflo.hufly.server.authentication.AuthService
import com.lerchenflo.hufly.server.authentication.erasedEmail
import com.lerchenflo.hufly.server.authentication.normalizeEmail
import com.lerchenflo.hufly.server.authentication.requireUsableEmail
import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.CodedException
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.picture.PictureKind
import com.lerchenflo.hufly.server.core.picture.PictureStore
import com.lerchenflo.hufly.server.core.picture.pictureUrl
import com.lerchenflo.hufly.server.core.picture.toStoredPicture
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.core.security.generatePassword
import com.lerchenflo.hufly.server.repository.AccountRepository
import com.lerchenflo.hufly.server.repository.DigestItemRepository
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.repository.JoinRequestRepository
import com.lerchenflo.hufly.server.repository.RefreshTokenRepository
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.TagRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.stable.StableDeletionService
import com.lerchenflo.hufly.server.tag.model.TagType
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

const val DELETED_USER_NAME = "Gelöschter Nutzer"

@Service
class UserService(
    private val userRepository: UserRepository,
    private val accountRepository: AccountRepository,
    private val joinRequestRepository: JoinRequestRepository,
    private val stableRepository: StableRepository,
    private val stableDeletionService: StableDeletionService,
    private val tagRepository: TagRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val horseRepository: HorseRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val digestItemRepository: DigestItemRepository,
    private val absenceService: AbsenceService,
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
        val now = clock.millis()
        val account = try {
            accountRepository.insert(
                Account(
                    email = normalizedEmail,
                    displayName = displayName,
                    hashedPassword = hashEncoder.encode(password),
                    mustChangePassword = true,
                    createdByStableId = requester.stableId,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } catch (e: DuplicateKeyException) {
            throw emailInUse()
        }
        val user = userRepository.insert(
            User(
                stableId = requester.stableId,
                email = normalizedEmail,
                displayName = displayName,
                phoneNumber = phoneNumber,
                profilePictureUrl = null,
                roleTagIds = roleTagIds,
                createdAt = now,
                updatedAt = now,
                updatedBy = requester.id,
                accountId = account.id,
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
        val normalizedEmail = requireFreeEmail(email, ownerId = target.accountId)
        requireRoleTags(requester.stableId, roleTagIds)
        if (normalizedEmail != target.email) changeLoginEmail(managedAccount(requester, target), normalizedEmail)
        return userRepository.save(
            target.copy(
                email = normalizedEmail,
                displayName = displayName,
                phoneNumber = phoneNumber,
                roleTagIds = roleTagIds,
                updatedAt = clock.millis(),
                updatedBy = requester.id,
            )
        )
    }

    fun deleteUser(requester: User, userId: ObjectId) {
        accessService.requireAdmin(requester)
        if (userId == requester.id) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "The admin cannot be deleted")
        val target = stableMember(requester, userId)
        eraseMembership(target, requester.id)
        val account = accountRepository.findById(target.accountId)
        // A login the stable created goes with the member; a self-registered person keeps it and can join elsewhere.
        if (account != null && account.createdByStableId == requester.stableId) eraseAccount(account)
    }

    /**
     * Self-service deletion of the login (app and website), with or without a stable. The admin of a stable with
     * other members must stay (only the operator can remove them); an admin alone in the stable deletes it with them.
     */
    fun deleteOwnAccount(account: Account, password: String) {
        if (!hashEncoder.matches(password, account.hashedPassword)) {
            throw CodedException(HttpStatus.BAD_REQUEST, "WRONG_PASSWORD", "Password is wrong")
        }
        val membership = userRepository.findFirstByAccountIdAndDeletedFalse(account.id)
        if (membership != null) {
            if (accessService.isAdmin(membership)) {
                val othersRemain = userRepository.findByStableIdAndDeletedFalse(membership.stableId).any { it.id != membership.id }
                if (othersRemain) {
                    throw CodedException(HttpStatus.CONFLICT, "STABLE_ADMIN", "The stable admin cannot delete the own account")
                }
                val stable = stableRepository.findById(membership.stableId)
                if (stable != null) stableDeletionService.deleteStable(stable.id, stable.name)
            } else {
                eraseMembership(membership, membership.id)
            }
        }
        eraseAccount(account)
    }

    /** The login is gone for good and its email free again; sessions, settings and pushes go with it. */
    private fun eraseAccount(account: Account) {
        accountRepository.save(
            account.copy(
                email = erasedEmail(account.id),
                displayName = DELETED_USER_NAME,
                hashedPassword = "",
                mustChangePassword = false,
                deleted = true,
                updatedAt = clock.millis(),
            )
        )
        refreshTokenRepository.deleteByUserId(account.id)
        userSettingsRepository.deleteByUserIdIn(listOf(account.id))
        digestItemRepository.deleteByUserIdIn(listOf(account.id))
        joinRequestRepository.findByAccountIdAndStatus(account.id, JoinRequestStatus.PENDING).forEach {
            joinRequestRepository.save(it.copy(status = JoinRequestStatus.WITHDRAWN, updatedAt = clock.millis()))
        }
    }

    /** The row stays (soft delete) so old entries still resolve, but nothing personal is kept. */
    private fun eraseMembership(target: User, actorId: ObjectId) {
        val now = clock.millis()
        userRepository.save(
            target.copy(
                email = erasedEmail(target.id),
                displayName = DELETED_USER_NAME,
                phoneNumber = null,
                profilePictureUrl = null,
                roleTagIds = emptyList(),
                deleted = true,
                updatedAt = now,
                updatedBy = actorId,
            )
        )
        horseRepository.findByStableIdAndDeletedFalse(target.stableId).filter { target.id in it.coRiderUserIds }.forEach {
            horseRepository.save(it.copy(coRiderUserIds = it.coRiderUserIds - target.id, updatedAt = now, updatedBy = actorId))
        }
        absenceService.removeUser(target.id, actorId)
        pictureStore.delete(PictureKind.USER, target.id)
    }

    /** USR-6: everyone sets the own profile picture, the admin also those of members (e.g. kids without phones). */
    fun setMyPicture(requester: User, upload: ByteArray): User = storePicture(requester, requester, upload)

    fun deleteMyPicture(requester: User): User = removePicture(requester, requester)

    fun setPicture(requester: User, userId: ObjectId, upload: ByteArray): User {
        accessService.requireAdmin(requester)
        return storePicture(requester, stableMember(requester, userId), upload)
    }

    fun deletePicture(requester: User, userId: ObjectId): User {
        accessService.requireAdmin(requester)
        return removePicture(requester, stableMember(requester, userId))
    }

    private fun storePicture(requester: User, target: User, upload: ByteArray): User {
        pictureStore.save(PictureKind.USER, target.id, toStoredPicture(upload))
        val now = clock.millis()
        return userRepository.save(
            target.copy(profilePictureUrl = pictureUrl(PictureKind.USER, target.id, now), updatedAt = now, updatedBy = requester.id)
        )
    }

    private fun removePicture(requester: User, target: User): User {
        pictureStore.delete(PictureKind.USER, target.id)
        return userRepository.save(target.copy(profilePictureUrl = null, updatedAt = clock.millis(), updatedBy = requester.id))
    }

    fun picture(requester: User, userId: ObjectId): ByteArray {
        val user = stableMember(requester, userId)
        return pictureStore.load(PictureKind.USER, user.id) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "No picture")
    }

    fun resetPassword(requester: User, userId: ObjectId): String {
        accessService.requireAdmin(requester)
        val account = managedAccount(requester, stableMember(requester, userId))
        val password = generatePassword()
        accountRepository.save(
            account.copy(hashedPassword = hashEncoder.encode(password), mustChangePassword = true, updatedAt = clock.millis())
        )
        refreshTokenRepository.deleteByUserId(account.id)
        return password
    }

    fun updateMe(requester: User, email: String, displayName: String, phoneNumber: String?): User {
        val normalizedEmail = requireFreeEmail(email, ownerId = requester.accountId)
        accessService.requireAccount(requester.accountId).let { account ->
            if (normalizedEmail != account.email || displayName != account.displayName) {
                saveAccount(account.copy(email = normalizedEmail, displayName = displayName, updatedAt = clock.millis()))
            }
        }
        return userRepository.save(
            requester.copy(
                email = normalizedEmail,
                displayName = displayName,
                phoneNumber = phoneNumber,
                updatedAt = clock.millis(),
                updatedBy = requester.id,
            )
        )
    }

    /** Answers 400, not 401, on a wrong old password: clients treat 401 as an expired session. */
    /** Ends every other session (USR-5); the device that changed the password stays logged in. */
    fun changePassword(account: Account, oldPassword: String, newPassword: String, currentSessionId: ObjectId?) {
        if (!hashEncoder.matches(oldPassword, account.hashedPassword)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Old password is wrong")
        }
        accountRepository.save(
            account.copy(hashedPassword = hashEncoder.encode(newPassword), mustChangePassword = false, updatedAt = clock.millis())
        )
        if (currentSessionId != null) {
            refreshTokenRepository.deleteByUserIdAndIdNot(account.id, currentSessionId)
        } else {
            refreshTokenRepository.deleteByUserId(account.id)
        }
    }

    /** Logins are unique across all stables (USR-4); erased accounts keep their anonymised address. */
    private fun requireFreeEmail(email: String, ownerId: ObjectId?): String {
        val normalized = normalizeEmail(email)
        requireUsableEmail(normalized)
        val existing = accountRepository.findByEmail(normalized)
        if (existing != null && existing.id != ownerId) throw emailInUse()
        return normalized
    }

    /** The admin may change only logins their stable created; self-registered members own their login. */
    private fun managedAccount(requester: User, target: User): Account {
        val account = accessService.requireAccount(target.accountId)
        if (account.createdByStableId != requester.stableId) {
            throw CodedException(HttpStatus.FORBIDDEN, "SELF_REGISTERED", "The member manages this login")
        }
        return account
    }

    private fun changeLoginEmail(account: Account, email: String) = saveAccount(account.copy(email = email, updatedAt = clock.millis()))

    private fun saveAccount(account: Account) = try {
        accountRepository.save(account)
    } catch (e: DuplicateKeyException) {
        throw emailInUse()
    }

    private fun emailInUse() = CodedException(HttpStatus.CONFLICT, AuthService.EMAIL_IN_USE, "Email already in use")

    /** 400 unless every id is a live USER_ROLE tag of [stableId]. */
    fun requireRoleTags(stableId: ObjectId, roleTagIds: List<ObjectId>) {
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
