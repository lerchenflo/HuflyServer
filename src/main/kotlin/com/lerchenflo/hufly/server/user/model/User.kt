package com.lerchenflo.hufly.server.user.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

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
    val createdAt: Instant,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
)
