package com.lerchenflo.hufly.server.repository

import org.bson.types.ObjectId
import org.springframework.dao.DuplicateKeyException

/** Mirrors the unique partial index on (stableId, clientId) of the Mongo collections. */
fun <T> List<T>.requireUniqueClientId(item: T, id: (T) -> ObjectId, stableId: (T) -> ObjectId, clientId: (T) -> String?) {
    val wanted = clientId(item) ?: return
    if (any { id(it) != id(item) && stableId(it) == stableId(item) && clientId(it) == wanted }) {
        throw DuplicateKeyException("E11000 duplicate key: clientId $wanted")
    }
}
