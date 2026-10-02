package com.lerchenflo.hufly.server.tag.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

enum class TagType { USER_ROLE, FOOD, ACTIVITY }

/** Grantable through USER_ROLE tags. Adding and removing horses is admin-only and never grantable (TAG-6). */
enum class Permission {
    HORSE_EDIT,
    FOODPLAN_EDIT,
    HORSE_LOG_WRITE,
    PADDOCK_PLAN,
    EVENT_VIEW,
    EVENT_CREATE_INVITE,
    TASK_CREATE_ASSIGN,
}

@Document("tags")
data class Tag(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val stableId: ObjectId,
    val name: String,
    val type: TagType,
    val color: String,
    /** Only meaningful for [TagType.USER_ROLE]. */
    val permissions: Set<Permission>,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
)
