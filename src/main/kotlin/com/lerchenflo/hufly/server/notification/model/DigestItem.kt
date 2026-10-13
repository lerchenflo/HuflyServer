package com.lerchenflo.hufly.server.notification.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/** A push held back for a user who chose the daily digest. */
@Document("digestItems")
data class DigestItem(
    @Id val id: ObjectId = ObjectId.get(),
    /** The recipient's account id. */
    val userId: ObjectId,
    /** The stable the push is about; null for items collected before 2026-10-13. */
    @Indexed val stableId: ObjectId? = null,
    val title: String,
    val body: String,
    @Indexed val createdAt: Long,
)
