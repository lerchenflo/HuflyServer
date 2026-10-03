package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.mongodb.repository.Update
import org.springframework.data.repository.Repository
import java.time.Instant

interface UserSettingsRepository : Repository<UserSettings, ObjectId> {
    fun save(settings: UserSettings): UserSettings
    fun findById(userId: ObjectId): UserSettings?

    /** Throws DuplicateKeyException when the user already has settings. */
    fun insert(settings: UserSettings): UserSettings

    /** Atomic: replaces only while [expected] is still the stored version; returns 1 then, else 0. */
    @Query("{ '_id': ?0, 'updatedAt': ?1 }")
    @Update("{ '\$set': { 'values': ?2, 'updatedAt': ?3 } }")
    fun replaceIfUnchanged(userId: ObjectId, expected: Instant, values: Map<String, String>, updatedAt: Instant): Long
}
