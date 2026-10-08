package com.lerchenflo.hufly.server.user.model

/** Timestamps are epoch milliseconds, the same values clients send back in sync requests. */
data class UserResponse(
    val id: String,
    val stableId: String,
    val email: String,
    val displayName: String,
    val phoneNumber: String?,
    val profilePictureUrl: String?,
    val roleTagIds: List<String>,
    val updatedAt: Long,
    val updatedBy: String,
)

fun User.toUserResponse() = UserResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    email = email,
    displayName = displayName,
    phoneNumber = phoneNumber,
    profilePictureUrl = profilePictureUrl,
    roleTagIds = roleTagIds.map { it.toHexString() },
    updatedAt = updatedAt,
    updatedBy = updatedBy.toHexString(),
)
