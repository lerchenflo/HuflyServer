package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.authentication.normalizeEmail
import com.lerchenflo.hufly.server.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Creates a first stable and its admin from BOOTSTRAP_* env vars, for local development until the onboarding
 * website exists. Does nothing unless all three are set, or when the email already exists.
 */
@Component
class StableBootstrap(
    private val userRepository: UserRepository,
    private val onboarding: StableOnboardingService,
) : ApplicationRunner {

    @Value("\${bootstrap.stable-name:}") private var stableName: String = ""
    @Value("\${bootstrap.admin-email:}") private var adminEmail: String = ""
    @Value("\${bootstrap.admin-password:}") private var adminPassword: String = ""

    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) = run(stableName, adminEmail, adminPassword)

    fun run(stableName: String, adminEmail: String, adminPassword: String) {
        if (stableName.isBlank() || adminEmail.isBlank() || adminPassword.isBlank()) return
        if (userRepository.findByEmail(normalizeEmail(adminEmail)) != null) return
        onboarding.createStable(stableName, adminEmail, "Admin", adminPassword, mustChangePassword = false)
        log.info("Bootstrapped stable '{}' with admin {}", stableName, normalizeEmail(adminEmail))
    }
}
