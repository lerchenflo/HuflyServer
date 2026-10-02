package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface UserSettingsRepository : Repository<UserSettings, ObjectId> {
    fun save(settings: UserSettings): UserSettings
    fun findById(userId: ObjectId): UserSettings?
}
