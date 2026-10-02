package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.authentication.normalizeEmail
import com.lerchenflo.hufly.server.core.security.HashEncoder
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.stable.model.Stable
import com.lerchenflo.hufly.server.stable.model.SubscriptionStatus
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Creates a first stable and its admin from BOOTSTRAP_* env vars, for local development until the onboarding
 * website exists. Does nothing unless all three are set, or when the email already exists.
 */
@Component
class StableBootstrap(
    private val userRepository: UserRepository,
    private val stableRepository: StableRepository,
    private val hashEncoder: HashEncoder,
    private val clock: Clock,
) : ApplicationRunner {

    @Value("\${bootstrap.stable-name:}") private var stableName: String = ""
    @Value("\${bootstrap.admin-email:}") private var adminEmail: String = ""
    @Value("\${bootstrap.admin-password:}") private var adminPassword: String = ""

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) = run(stableName, adminEmail, adminPassword)

    fun run(stableName: String, adminEmail: String, adminPassword: String) {
        if (stableName.isBlank() || adminEmail.isBlank() || adminPassword.isBlank()) return
        val email = normalizeEmail(adminEmail)
        if (userRepository.findByEmail(email) != null) return

        val now = clock.instant()
        val stableId = ObjectId.get()
        val adminId = ObjectId.get()
        userRepository.save(
            User(
                id = adminId,
                stableId = stableId,
                email = email,
                displayName = "Admin",
                phoneNumber = null,
                profilePictureUrl = null,
                hashedPassword = hashEncoder.encode(adminPassword),
                roleTagIds = emptyList(),
                createdAt = now,
                updatedAt = now,
                updatedBy = adminId,
            )
        )
        stableRepository.save(
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
        log.info("Bootstrapped stable '{}' with admin {}", stableName, email)
    }
}
