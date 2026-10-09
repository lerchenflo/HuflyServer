package com.lerchenflo.hufly.server.user

import com.fasterxml.jackson.annotation.JsonIgnore
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.security.requireAuth
import com.lerchenflo.hufly.server.realtime.markAnswered
import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.user.model.UserSettingsResponse
import com.lerchenflo.hufly.server.user.model.toResponse
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

/** Settings belong to the login (account), so they work without a stable too. Each user reads and replaces only their own; the last write wins unless the client sends expectedUpdatedAt. */
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
        val account = accessService.requireAccount(requireAuth())
        return settingsRepository.findById(account.id).toResponse()
    }

    @PutMapping
    /** 409 carries the current settings so the client can merge without another GET. */
    fun putSettings(@Valid @RequestBody request: SettingsRequest): ResponseEntity<UserSettingsResponse> {
        val account = accessService.requireAccount(requireAuth())
        if (request.values.any { (key, value) -> key.length > 100 || value.length > 5000 }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Setting key or value too long")
        }
        // Mongo cannot store map keys with dots or a leading $ (it would answer 500, and clients retry 500s forever).
        if (request.values.keys.any { it.isEmpty() || '.' in it || it.startsWith('$') }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Setting key not allowed")
        }
        return when (val result = settingsService.put(account.id, request.values, request.condition())) {
            is UserSettingsService.PutResult.Saved -> {
                markAnswered(account.id)
                ResponseEntity.ok(result.settings.toResponse())
            }
            is UserSettingsService.PutResult.Conflict -> ResponseEntity.status(HttpStatus.CONFLICT).body(result.current.toResponse())
        }
    }
}
