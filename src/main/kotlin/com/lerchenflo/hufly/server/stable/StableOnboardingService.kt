package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.account.model.Account
import com.lerchenflo.hufly.server.account.model.JoinRequestStatus
import com.lerchenflo.hufly.server.authentication.AuthService
import com.lerchenflo.hufly.server.authentication.normalizeEmail
import com.lerchenflo.hufly.server.authentication.requireUsableEmail
import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.CodedException
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.repository.AccountRepository
import com.lerchenflo.hufly.server.repository.JoinRequestRepository
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.stable.model.Stable
import com.lerchenflo.hufly.server.stable.model.SubscriptionStatus
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service

/**
 * Creates a stable together with its single admin (BIZ-1, BIZ-2): by the operator website and the dev bootstrap
 * (with a new login), or by a registered account for itself.
 */
@Service
class StableOnboardingService(
    private val userRepository: UserRepository,
    private val accountRepository: AccountRepository,
    private val stableRepository: StableRepository,
    private val joinRequestRepository: JoinRequestRepository,
    private val hashEncoder: HashEncoder,
    private val clock: Clock,
) {
    data class CreatedStable(val stable: Stable, val admin: User)

    /** The operator sets the admin's first password, so the admin replaces it like every member; the dev bootstrap opts out. */
    fun createStable(
        stableName: String,
        adminEmail: String,
        adminDisplayName: String,
        adminPassword: String,
        mustChangePassword: Boolean = true,
    ): CreatedStable {
        val email = normalizeEmail(adminEmail)
        requireUsableEmail(email)
        if (accountRepository.findByEmail(email) != null) throw emailInUse()
        val now = clock.millis()
        val stableId = ObjectId.get()
        val account = try {
            accountRepository.insert(
                Account(
                    email = email,
                    displayName = adminDisplayName,
                    hashedPassword = hashEncoder.encode(adminPassword),
                    mustChangePassword = mustChangePassword,
                    createdByStableId = stableId,
                    createdAt = now,
                    updatedAt = now,
                )
            )
        } catch (e: DuplicateKeyException) {
            throw emailInUse()
        }
        return create(stableId, stableName, place = null, account)
    }

    /** Self-service: the account becomes the admin of a new stable; 409 `ALREADY_MEMBER` if it has a stable already. */
    fun createStableForAccount(account: Account, stableName: String, place: String?): CreatedStable {
        val created = create(ObjectId.get(), stableName, place, account)
        joinRequestRepository.findByAccountIdAndStatus(account.id, JoinRequestStatus.PENDING).forEach {
            joinRequestRepository.save(it.copy(status = JoinRequestStatus.WITHDRAWN, updatedAt = clock.millis()))
        }
        return created
    }

    /** The membership goes in first: it claims the account (one stable per account), so two parallel calls cannot both win. */
    private fun create(stableId: ObjectId, stableName: String, place: String?, account: Account): CreatedStable {
        val now = clock.millis()
        val admin = insertMembership(
            User(
                stableId = stableId,
                email = account.email,
                displayName = account.displayName,
                phoneNumber = null,
                profilePictureUrl = null,
                roleTagIds = emptyList(),
                createdAt = now,
                updatedAt = now,
                updatedBy = account.id,
                accountId = account.id,
            ).let { it.copy(updatedBy = it.id) }
        )
        val stable = try {
            stableRepository.save(
                Stable(
                    id = stableId,
                    name = stableName.trim(),
                    place = place?.trim()?.takeIf { it.isNotEmpty() },
                    inviteCode = newInviteCode(),
                    adminUserId = admin.id,
                    subscriptionStatus = SubscriptionStatus.TRIAL,
                    subscriptionValidUntil = null,
                    createdAt = now,
                    updatedAt = now,
                    updatedBy = admin.id,
                )
            )
        } catch (e: RuntimeException) {
            userRepository.save(admin.copy(deleted = true, updatedAt = clock.millis()))
            throw e
        }
        return CreatedStable(stable, admin)
    }

    private fun insertMembership(user: User): User = try {
        userRepository.insert(user)
    } catch (e: DuplicateKeyException) {
        throw alreadyMember()
    }

    fun newInviteCode(): String = generateSequence { InviteCodes.generate() }.first { !stableRepository.existsByInviteCode(it) }

    private fun emailInUse() = CodedException(HttpStatus.CONFLICT, AuthService.EMAIL_IN_USE, "Email already in use")

    companion object {
        const val ALREADY_MEMBER = "ALREADY_MEMBER"

        fun alreadyMember() = CodedException(HttpStatus.CONFLICT, ALREADY_MEMBER, "The account has a stable already")
    }
}
