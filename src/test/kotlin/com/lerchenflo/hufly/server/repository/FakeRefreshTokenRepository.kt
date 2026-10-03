package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.authentication.model.DeviceType
import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import org.bson.types.ObjectId
import java.time.Instant

class FakeRefreshTokenRepository : RefreshTokenRepository {
    val tokens = mutableListOf<RefreshToken>()

    override fun save(token: RefreshToken): RefreshToken {
        tokens.removeIf { it.id == token.id }
        tokens += token
        return token
    }

    override fun findById(id: ObjectId): RefreshToken? = tokens.firstOrNull { it.id == id }

    override fun findByUserId(userId: ObjectId): List<RefreshToken> = tokens.filter { it.userId == userId }

    override fun deleteById(id: ObjectId) {
        tokens.removeIf { it.id == id }
    }

    override fun deleteByUserIdAndIdNot(userId: ObjectId, id: ObjectId): Long {
        val before = tokens.size
        tokens.removeIf { it.userId == userId && it.id != id }
        return (before - tokens.size).toLong()
    }

    override fun deleteByUserIdAndDeviceNameAndDeviceTypeAndDeviceIdIsNull(userId: ObjectId, deviceName: String, deviceType: DeviceType): Long {
        val before = tokens.size
        tokens.removeIf { it.userId == userId && it.deviceName == deviceName && it.deviceType == deviceType && it.deviceId == null }
        return (before - tokens.size).toLong()
    }

    override fun deleteByUserIdAndDeviceId(userId: ObjectId, deviceId: String): Long {
        val before = tokens.size
        tokens.removeIf { it.userId == userId && it.deviceId == deviceId }
        return (before - tokens.size).toLong()
    }

    override fun findByHashedToken(hashedToken: String): RefreshToken? = tokens.firstOrNull { it.hashedToken == hashedToken }

    override fun findByPreviousHashedToken(previousHashedToken: String): RefreshToken? =
        tokens.firstOrNull { it.previousHashedToken == previousHashedToken }

    override fun deleteByHashedTokenOrPreviousHashedToken(hashedToken: String, previousHashedToken: String): Long {
        val before = tokens.size
        tokens.removeIf { it.hashedToken == hashedToken || it.previousHashedToken == previousHashedToken }
        return (before - tokens.size).toLong()
    }

    override fun deleteByUserId(userId: ObjectId): Long {
        val before = tokens.size
        tokens.removeIf { it.userId == userId }
        return (before - tokens.size).toLong()
    }

    override fun rotate(oldHash: String, newHash: String, encryptedToken: String, expiresAt: Instant, lastUsedAt: Instant): Long {
        val row = tokens.firstOrNull { it.hashedToken == oldHash } ?: return 0
        save(row.copy(hashedToken = newHash, previousHashedToken = oldHash, encryptedToken = encryptedToken, expiresAt = expiresAt, lastUsedAt = lastUsedAt))
        return 1
    }

    override fun deleteByExpiresAtBefore(time: Instant): Long {
        val before = tokens.size
        tokens.removeIf { it.expiresAt.isBefore(time) }
        return (before - tokens.size).toLong()
    }
}
