package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.user.model.UserSettings
import com.lerchenflo.hufly.server.user.model.UserSettingsResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Clock

/** Each user reads and replaces only their own settings; the last write wins. */
@RestController
@RequestMapping("/users/me/settings")
class UserSettingsController(
    private val accessService: AccessService,
    private val settingsRepository: UserSettingsRepository,
    private val clock: Clock,
) {

    data class SettingsRequest(@field:Size(max = 100) val values: Map<String, String>)

    @GetMapping
    fun getSettings(): UserSettingsResponse {
        val requester = accessService.requester(requireAuth())
        val settings = settingsRepository.findById(requester.id)
        return UserSettingsResponse(settings?.values ?: emptyMap(), settings?.updatedAt?.toEpochMilli())
    }

    @PutMapping
    fun putSettings(@Valid @RequestBody request: SettingsRequest): UserSettingsResponse {
        val requester = accessService.requester(requireAuth())
        if (request.values.any { (key, value) -> key.length > 100 || value.length > 5000 }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Setting key or value too long")
        }
        val saved = settingsRepository.save(UserSettings(requester.id, request.values, clock.instant()))
        return UserSettingsResponse(saved.values, saved.updatedAt.toEpochMilli())
    }
}
