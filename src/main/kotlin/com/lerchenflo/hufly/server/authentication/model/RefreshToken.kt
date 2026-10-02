package com.lerchenflo.hufly.server.authentication.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/**
 * One row per device session, rotated in place like SchneaggchatV3server: `/auth/refresh` swaps [hashedToken] to
 * the new token's hash and keeps the presented one in [previousHashedToken]. A client that never received the
 * response retries with the old token and gets the current one back from [encryptedToken], so the old token works
 * until the client uses the new one. Tokens are stored only hashed or encrypted.
 */
@Document("refreshTokens")
data class RefreshToken(
    @Id val id: ObjectId = ObjectId.get(),
    val userId: ObjectId,
    @Indexed(unique = true) val hashedToken: String,
    @Indexed(sparse = true) val previousHashedToken: String? = null,
    /** The current token, AES-GCM encrypted; null until the first rotation. */
    val encryptedToken: String? = null,
    /** Slides forward on every rotation. Expired rows are removed by `RefreshTokenCleanup`. */
    @Indexed val expiresAt: Instant,
    val createdAt: Instant,
)
