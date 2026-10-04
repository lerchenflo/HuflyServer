package com.lerchenflo.hufly.server.tag.model

data class TagResponse(
    val id: String,
    val stableId: String,
    val name: String,
    val type: TagType,
    val color: String,
    val permissions: Set<Permission>,
    val defaultIntervalDays: Int?,
    val updatedAt: Long,
    val updatedBy: String,
)

fun Tag.toTagResponse() = TagResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    name = name,
    type = type,
    color = color,
    permissions = permissions,
    defaultIntervalDays = defaultIntervalDays,
    updatedAt = updatedAt.toEpochMilli(),
    updatedBy = updatedBy.toHexString(),
)
