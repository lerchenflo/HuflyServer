package com.lerchenflo.hufly.server.core

import org.springframework.dao.DuplicateKeyException

/** Max length of the client's local id that makes a create retry-safe. */
const val MAX_CLIENT_ID_LENGTH = 64

/**
 * Offline clients retry a create whose response got lost. With a [clientId] the retry answers the entity that
 * [existing] finds (soft-deleted ones too) instead of creating a duplicate. Two racing first attempts are settled
 * by the unique index on (stableId, clientId): the loser reads the winner. Check permissions before calling this.
 */
fun <T : Any> idempotentCreate(clientId: String?, existing: (String) -> T?, create: () -> T): T {
    if (clientId == null) return create()
    existing(clientId)?.let { return it }
    return try {
        create()
    } catch (e: DuplicateKeyException) {
        existing(clientId) ?: throw e
    }
}
