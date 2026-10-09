package com.lerchenflo.hufly.server.account.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/**
 * A login (email + password), separate from stable membership ([com.lerchenflo.hufly.server.user.model.User]).
 * Tokens, sessions, push tokens and settings belong to the account. While a person can be in one stable only, the
 * membership has the same id as its account; see `TODO.md` for what changes with several stables per account.
 */
@Document("accounts")
data class Account(
    @Id val id: ObjectId = ObjectId.get(),
    /** Unique; stored lowercase and trimmed, see [com.lerchenflo.hufly.server.authentication.normalizeEmail]. */
    @Indexed(unique = true) val email: String,
    /** The name given at registration; copied into the membership on joining. */
    val displayName: String,
    val hashedPassword: String,
    /** Set while the password is one an admin or the operator generated; only the user sees it. */
    val mustChangePassword: Boolean = false,
    /**
     * The stable whose admin (or the operator for it) created this login; null for self-registered accounts. Only
     * that stable's admin may reset the password or change the email of the login.
     */
    val createdByStableId: ObjectId? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
)
