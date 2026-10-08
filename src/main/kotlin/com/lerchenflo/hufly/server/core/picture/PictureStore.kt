package com.lerchenflo.hufly.server.core.picture

import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class PictureKind(val route: String) {
    HORSE("horses"),
    USER("users"),
}

/** Clients load pictures from this url; the version changes with every upload so caches never serve an old one. */
fun pictureUrl(kind: PictureKind, id: ObjectId, uploadedAt: Long) =
    "/${kind.route}/${id.toHexString()}/picture?v=${uploadedAt}"

/** Urls are versioned, so clients may keep a picture for good. */
fun pictureResponse(jpeg: ByteArray): ResponseEntity<ByteArray> = ResponseEntity.ok()
    .contentType(MediaType.IMAGE_JPEG)
    .cacheControl(CacheControl.maxAge(365, TimeUnit.DAYS).cachePrivate())
    .body(jpeg)

interface PictureStore {
    fun save(kind: PictureKind, id: ObjectId, jpeg: ByteArray)
    fun load(kind: PictureKind, id: ObjectId): ByteArray?
    fun delete(kind: PictureKind, id: ObjectId)
}

/** One JPEG per entity in `pictures.dir` (a Docker volume in production). */
@Component
class FilePictureStore(@Value("\${pictures.dir}") private val dir: String) : PictureStore {

    private fun file(kind: PictureKind, id: ObjectId) = File(dir, "${kind.name.lowercase()}_${id.toHexString()}.jpg")

    override fun save(kind: PictureKind, id: ObjectId, jpeg: ByteArray) {
        File(dir).mkdirs()
        val target = file(kind, id)
        val temp = File(dir, "${target.name}.${UUID.randomUUID()}.tmp")
        temp.writeBytes(jpeg)
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun load(kind: PictureKind, id: ObjectId): ByteArray? = file(kind, id).takeIf { it.isFile }?.readBytes()

    override fun delete(kind: PictureKind, id: ObjectId) {
        file(kind, id).delete()
    }
}
