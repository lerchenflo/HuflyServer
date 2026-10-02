package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId

class FakeUserSettingsRepository : UserSettingsRepository {
    val settings = mutableMapOf<ObjectId, UserSettings>()

    override fun save(settings: UserSettings): UserSettings {
        this.settings[settings.userId] = settings
        return settings
    }

    override fun findById(userId: ObjectId): UserSettings? = settings[userId]
}
