package com.lerchenflo.hufly.server.notification.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

/** A push held back for a user who chose the daily digest. */
@Document("digestItems")
data class DigestItem(
    @Id val id: ObjectId = ObjectId.get(),
    val userId: ObjectId,
    val title: String,
    val body: String,
    @Indexed val createdAt: Instant,
)
