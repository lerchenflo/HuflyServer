package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.authentication.model.DeviceType
import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.mongodb.repository.Update
import org.springframework.data.repository.Repository
import java.time.Instant

interface RefreshTokenRepository : Repository<RefreshToken, ObjectId> {
    fun save(token: RefreshToken): RefreshToken
    fun findById(id: ObjectId): RefreshToken?
    fun findByUserId(userId: ObjectId): List<RefreshToken>
    fun deleteById(id: ObjectId)
    fun deleteByUserIdAndIdNot(userId: ObjectId, id: ObjectId): Long
    fun deleteByUserIdAndDeviceNameAndDeviceType(userId: ObjectId, deviceName: String, deviceType: DeviceType): Long
    fun findByHashedToken(hashedToken: String): RefreshToken?
    fun findByPreviousHashedToken(previousHashedToken: String): RefreshToken?
    fun deleteByHashedTokenOrPreviousHashedToken(hashedToken: String, previousHashedToken: String): Long
    fun deleteByUserId(userId: ObjectId): Long
    fun deleteByExpiresAtBefore(time: Instant): Long

    /** Atomic: only the caller that still finds [oldHash] as the current token rotates; returns 1 for that caller. */
    @Query("{ 'hashedToken': ?0 }")
    @Update("{ '\$set': { 'hashedToken': ?1, 'previousHashedToken': ?0, 'encryptedToken': ?2, 'expiresAt': ?3, 'lastUsedAt': ?4 } }")
    fun rotate(oldHash: String, newHash: String, encryptedToken: String, expiresAt: Instant, lastUsedAt: Instant): Long
}
