package com.lerchenflo.hufly.server.user.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/** A stable membership. Login data lives in [com.lerchenflo.hufly.server.account.model.Account]. */
@Document("users")
@CompoundIndex(name = "one_membership_per_account", def = "{ 'accountId': 1 }", unique = true, partialFilter = "{ 'deleted': false }")
data class User(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val stableId: ObjectId,
    /** The account's email, copied for display; the account is the source. */
    val email: String,
    val displayName: String,
    val phoneNumber: String?,
    val profilePictureUrl: String?,
    val roleTagIds: List<ObjectId>,
    val createdAt: Long,
    val updatedAt: Long,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    /** The login of this membership (equal to [id] for logins from before accounts existed). */
    val accountId: ObjectId = id,
)
