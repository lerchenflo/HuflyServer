package com.lerchenflo.hufly.server.user.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

@Document("users")
data class User(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val stableId: ObjectId,
    /** Unique across all stables. Stored lowercase and trimmed, see [com.lerchenflo.hufly.server.authentication.normalizeEmail]. */
    @Indexed(unique = true) val email: String,
    val displayName: String,
    val phoneNumber: String?,
    val profilePictureUrl: String?,
    val hashedPassword: String,
    val roleTagIds: List<ObjectId>,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    /** Set while the password is one the admin generated; only the user sees it (`GET /users/me`). */
    val mustChangePassword: Boolean = false,
)
