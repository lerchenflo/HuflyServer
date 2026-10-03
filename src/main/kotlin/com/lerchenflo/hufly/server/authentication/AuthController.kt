package com.lerchenflo.hufly.server.authentication

import com.lerchenflo.hufly.server.authentication.model.DeviceType
import com.lerchenflo.hufly.server.authentication.model.UNKNOWN_DEVICE_NAME
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.core.security.LoginGuard
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/auth")
class AuthController(
    private val authService: AuthService,
    private val loginGuard: LoginGuard,
) {

    data class LoginRequest(
        @field:NotBlank @field:Size(max = 254) val email: String,
        @field:NotBlank @field:Size(max = 200) val password: String,
        @field:Size(max = 100) val deviceName: String? = null,
        val deviceType: DeviceType? = null,
        /** Stable per app install; device names collide (every iPhone is "iPhone"). */
        @field:Size(min = 1, max = 64) val deviceId: String? = null,
    ) {
        fun device() = AuthService.Device(
            name = deviceName?.takeIf { it.isNotBlank() }?.trim() ?: UNKNOWN_DEVICE_NAME,
            type = deviceType ?: DeviceType.OTHER,
            id = deviceId,
        )
    }

    data class RefreshRequest(
        @field:NotBlank @field:Size(max = 2000) val refreshToken: String,
    )

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest, servletRequest: HttpServletRequest): AuthService.TokenPair {
        val email = normalizeEmail(request.email)
        val ip = servletRequest.remoteAddr
        loginGuard.checkLogin(email, ip)
        val tokens = try {
            authService.login(email, request.password, request.device())
        } catch (e: ResponseStatusException) {
            if (e.statusCode == HttpStatus.UNAUTHORIZED) loginGuard.loginFailed(email, ip)
            throw e
        }
        loginGuard.loginSucceeded(email)
        return tokens
    }

    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): AuthService.TokenPair =
        authService.refresh(request.refreshToken)

    /** Always answers 200 so a client can finish logging out even with an already invalid token. */
    @PostMapping("/logout")
    fun logout(@Valid @RequestBody request: RefreshRequest) {
        authService.logout(request.refreshToken)
    }

    /** Ends every session of the requester, on all devices (USR-5). */
    @PostMapping("/logout-all")
    fun logoutAll() {
        authService.logoutEverywhere(requireAuth())
    }
}
