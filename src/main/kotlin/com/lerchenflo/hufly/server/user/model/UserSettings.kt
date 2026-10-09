package com.lerchenflo.hufly.server.user.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.mapping.Document

/** Opaque key-value map owned by the app (OFF-5, OFF-6); the server never interprets it. */
@Document("userSettings")
data class UserSettings(
    /** The account id: settings belong to the login. */
    @Id val userId: ObjectId,
    val values: Map<String, String>,
    val updatedAt: Long,
)

data class UserSettingsResponse(
    val values: Map<String, String>,
    /** Epoch milliseconds; null before the first save. */
    val updatedAt: Long?,
)

fun UserSettings?.toResponse() = UserSettingsResponse(this?.values ?: emptyMap(), this?.updatedAt)
