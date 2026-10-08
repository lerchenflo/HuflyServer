package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.authentication.model.DeviceType
import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.notification.model.PushPlatform
import org.bson.types.ObjectId
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.mongodb.repository.Update
import org.springframework.data.repository.Repository

interface RefreshTokenRepository : Repository<RefreshToken, ObjectId> {
    fun deleteByUserIdIn(userIds: Collection<ObjectId>): Long
    fun save(token: RefreshToken): RefreshToken
    fun findById(id: ObjectId): RefreshToken?
    fun findByUserId(userId: ObjectId): List<RefreshToken>
    fun deleteById(id: ObjectId)
    fun deleteByUserIdAndIdNot(userId: ObjectId, id: ObjectId): Long
    fun deleteByUserIdAndDeviceNameAndDeviceTypeAndDeviceIdIsNull(userId: ObjectId, deviceName: String, deviceType: DeviceType): Long
    fun deleteByUserIdAndDeviceId(userId: ObjectId, deviceId: String): Long
    fun findByHashedToken(hashedToken: String): RefreshToken?
    fun findByPreviousHashedToken(previousHashedToken: String): RefreshToken?
    fun deleteByHashedTokenOrPreviousHashedToken(hashedToken: String, previousHashedToken: String): Long
    fun deleteByUserId(userId: ObjectId): Long
    fun deleteByExpiresAtBefore(time: Long): Long

    /** Atomic: only the caller that still finds [oldHash] as the current token rotates; returns 1 for that caller. */
    @Query("{ 'hashedToken': ?0 }")
    @Update("{ '\$set': { 'hashedToken': ?1, 'previousHashedToken': ?0, 'encryptedToken': ?2, 'expiresAt': ?3, 'lastUsedAt': ?4 } }")
    fun rotate(oldHash: String, newHash: String, encryptedToken: String, expiresAt: Long, lastUsedAt: Long): Long

    fun findByUserIdAndPushTokenNotNull(userId: ObjectId): List<RefreshToken>

    @Query("{ '_id': ?0 }")
    @Update("{ '\$set': { 'pushToken': ?1, 'pushPlatform': ?2 } }")
    fun setPushToken(sessionId: ObjectId, token: String, platform: PushPlatform): Long

    /** An install that changed accounts must stop getting the old account's pushes. */
    @Query("{ 'pushToken': ?0, '_id': { '\$ne': ?1 } }")
    @Update("{ '\$unset': { 'pushToken': '', 'pushPlatform': '' } }")
    fun clearPushTokenElsewhere(token: String, sessionId: ObjectId): Long

    @Query("{ 'pushToken': ?0 }")
    @Update("{ '\$unset': { 'pushToken': '', 'pushPlatform': '' } }")
    fun clearPushToken(token: String): Long

    @Query("{ '_id': ?0 }")
    @Update("{ '\$unset': { 'pushToken': '', 'pushPlatform': '' } }")
    fun clearPushTokenOfSession(sessionId: ObjectId): Long
}
