package com.lerchenflo.hufly.server.authentication.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * One row per device session. Only SHA-256 hashes of tokens are stored. [previousHashedToken] and [rotatedAt] let a
 * client that lost the refresh response retry with its old token for a short grace period.
 */
@Document("refreshTokens")
data class RefreshToken(
    @Id val id: ObjectId = ObjectId.get(),
    val userId: ObjectId,
    @Indexed(unique = true) val hashedToken: String,
    @Indexed(sparse = true) val previousHashedToken: String? = null,
    val rotatedAt: Instant? = null,
    @Indexed(expireAfter = "0s") val expiresAt: Instant,
    val createdAt: Instant,
)
