package com.lerchenflo.hufly.server.core.picture

import org.bson.types.ObjectId

class FakePictureStore : PictureStore {
    val pictures = mutableMapOf<Pair<PictureKind, ObjectId>, ByteArray>()

    override fun save(kind: PictureKind, id: ObjectId, jpeg: ByteArray) {
        pictures[kind to id] = jpeg
    }

    override fun load(kind: PictureKind, id: ObjectId): ByteArray? = pictures[kind to id]

    override fun delete(kind: PictureKind, id: ObjectId) {
        pictures.remove(kind to id)
    }
}
