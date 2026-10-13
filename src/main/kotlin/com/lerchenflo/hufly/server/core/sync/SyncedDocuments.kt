package com.lerchenflo.hufly.server.core.sync

import org.bson.types.ObjectId

/** A document of a small collection, delta-synced by [IdTimeStamp]s, see [deltaSync]. */
interface TimestampSynced {
    val id: ObjectId
    val updatedAt: Long
}

/** A document of a growing collection, synced by its version counter, see [versionSync]. */
interface VersionSynced {
    val id: ObjectId
    val version: Long
    val deleted: Boolean
}
