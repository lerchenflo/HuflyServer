package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException
import java.time.Instant

class FakeUserSettingsRepository : UserSettingsRepository {
    val settings = mutableMapOf<ObjectId, UserSettings>()

    override fun save(settings: UserSettings): UserSettings {
        this.settings[settings.userId] = settings
        return settings
    }

    override fun findById(userId: ObjectId): UserSettings? = settings[userId]

    override fun insert(settings: UserSettings): UserSettings {
        if (settings.userId in this.settings) throw DuplicateKeyException("E11000 duplicate key: _id")
        return save(settings)
    }

    /** Mongo stores epoch millis, so versions compare at millisecond precision. */
    override fun replaceIfUnchanged(userId: ObjectId, expected: Instant, values: Map<String, String>, updatedAt: Instant): Long {
        val stored = settings[userId] ?: return 0
        if (stored.updatedAt.toEpochMilli() != expected.toEpochMilli()) return 0
        save(UserSettings(userId, values, updatedAt))
        return 1
    }
}
