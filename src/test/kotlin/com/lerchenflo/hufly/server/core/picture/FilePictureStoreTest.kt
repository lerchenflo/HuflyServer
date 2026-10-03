package com.lerchenflo.hufly.server.core.picture

import org.bson.types.ObjectId
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

class FilePictureStoreTest {

    @TempDir lateinit var dir: Path

    @Test
    fun `saves, loads and deletes pictures per kind and id`() {
        val store = FilePictureStore(dir.resolve("images").toString())
        val id = ObjectId.get()

        store.save(PictureKind.HORSE, id, byteArrayOf(1, 2, 3))

        assertContentEquals(byteArrayOf(1, 2, 3), store.load(PictureKind.HORSE, id))
        assertNull(store.load(PictureKind.USER, id))

        store.delete(PictureKind.HORSE, id)
        assertNull(store.load(PictureKind.HORSE, id))
    }

    @Test
    fun `deleting a missing picture does nothing`() {
        FilePictureStore(dir.toString()).delete(PictureKind.USER, ObjectId.get())
    }
}
