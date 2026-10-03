package com.lerchenflo.hufly.server.user

import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

sealed interface PutCondition {
    /** Last write wins. */
    data object None : PutCondition

    /** Save only if the stored version is still [updatedAt] (epoch millis); null means no settings saved yet. */
    data class Expect(val updatedAt: Long?) : PutCondition
}

/** Each user replaces only the own settings map, optionally guarded against overwriting another device's save. */
@Service
class UserSettingsService(
    private val settingsRepository: UserSettingsRepository,
    private val clock: Clock,
) {
    sealed interface PutResult {
        data class Saved(val settings: UserSettings) : PutResult
        data class Conflict(val current: UserSettings?) : PutResult
    }

    fun put(userId: ObjectId, values: Map<String, String>, condition: PutCondition): PutResult {
        val now = clock.instant()
        val settings = UserSettings(userId, values, now)
        return when (condition) {
            PutCondition.None -> PutResult.Saved(settingsRepository.save(settings))
            is PutCondition.Expect -> if (condition.updatedAt == null) {
                try {
                    PutResult.Saved(settingsRepository.insert(settings))
                } catch (e: DuplicateKeyException) {
                    PutResult.Conflict(settingsRepository.findById(userId))
                }
            } else {
                val replaced = settingsRepository.replaceIfUnchanged(userId, Instant.ofEpochMilli(condition.updatedAt), values, now)
                if (replaced == 1L) PutResult.Saved(settings) else PutResult.Conflict(settingsRepository.findById(userId))
            }
        }
    }
}
