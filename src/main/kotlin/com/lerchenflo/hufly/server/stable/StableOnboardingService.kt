package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.authentication.normalizeEmail
import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.stable.model.Stable
import com.lerchenflo.hufly.server.stable.model.SubscriptionStatus
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/** Creates a stable together with its single admin (BIZ-1, BIZ-2). Used by the operator website and the dev bootstrap. */
@Service
class StableOnboardingService(
    private val userRepository: UserRepository,
    private val stableRepository: StableRepository,
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
        if (userRepository.findByEmail(email) != null) throw ResponseStatusException(HttpStatus.CONFLICT, "Email already in use")

        val now = clock.millis()
        val stableId = ObjectId.get()
        val adminId = ObjectId.get()
        val admin = userRepository.save(
            User(
                id = adminId,
                stableId = stableId,
                email = email,
                displayName = adminDisplayName,
                phoneNumber = null,
                profilePictureUrl = null,
                hashedPassword = hashEncoder.encode(adminPassword),
                roleTagIds = emptyList(),
                createdAt = now,
                updatedAt = now,
                updatedBy = adminId,
                mustChangePassword = mustChangePassword,
            )
        )
        val stable = stableRepository.save(
            Stable(
                id = stableId,
                name = stableName,
                adminUserId = adminId,
                subscriptionStatus = SubscriptionStatus.TRIAL,
                subscriptionValidUntil = null,
                createdAt = now,
                updatedAt = now,
                updatedBy = adminId,
            )
        )
        return CreatedStable(stable, admin)
    }
}
