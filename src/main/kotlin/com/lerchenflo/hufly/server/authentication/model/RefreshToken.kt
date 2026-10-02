package com.lerchenflo.hufly.server.authentication.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/** One row per device session. Only the SHA-256 hash of the token is stored. */
@Document("refreshTokens")
data class RefreshToken(
    @Id val id: ObjectId = ObjectId.get(),
    val userId: ObjectId,
    @Indexed(unique = true) val hashedToken: String,
    @Indexed(expireAfter = "0s") val expiresAt: Instant,
    val createdAt: Instant,
)
