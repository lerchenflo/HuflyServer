package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.user.model.UserSettingsResponse
import com.lerchenflo.hufly.server.user.model.toResponse
import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/** Each user reads and replaces only their own settings; the last write wins unless the client sends expectedUpdatedAt. */
@RestController
@RequestMapping("/users/me/settings")
class UserSettingsController(
    private val accessService: AccessService,
    private val settingsRepository: UserSettingsRepository,
    private val settingsService: UserSettingsService,
) {

    class SettingsRequest(@field:Size(max = 100) val values: Map<String, String>) {
        /** The version the client edited (epoch millis); an explicit null means it saw no settings yet. */
        var expectedUpdatedAt: Long? = null
            set(value) {
                field = value
                expectsVersion = true
            }

        /** Distinguishes an absent expectedUpdatedAt (last write wins) from an explicit null. */
        @JsonIgnore
        var expectsVersion = false
            private set

        fun condition() = if (expectsVersion) PutCondition.Expect(expectedUpdatedAt) else PutCondition.None
    }

    @GetMapping
    fun getSettings(): UserSettingsResponse {
        val requester = accessService.requester(requireAuth())
        return settingsRepository.findById(requester.id).toResponse()
    }

    @PutMapping
    /** 409 carries the current settings so the client can merge without another GET. */
    fun putSettings(@Valid @RequestBody request: SettingsRequest): ResponseEntity<UserSettingsResponse> {
        val requester = accessService.requester(requireAuth())
        if (request.values.any { (key, value) -> key.length > 100 || value.length > 5000 }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Setting key or value too long")
        }
        return when (val result = settingsService.put(requester.id, request.values, request.condition())) {
            is UserSettingsService.PutResult.Saved -> ResponseEntity.ok(result.settings.toResponse())
            is UserSettingsService.PutResult.Conflict -> ResponseEntity.status(HttpStatus.CONFLICT).body(result.current.toResponse())
        }
    }
}
