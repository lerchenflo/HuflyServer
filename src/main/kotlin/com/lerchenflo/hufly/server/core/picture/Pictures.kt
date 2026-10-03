package com.lerchenflo.hufly.server.core.picture

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

const val MAX_PICTURE_UPLOAD_BYTES = 10 * 1024 * 1024
private const val MAX_PICTURE_PIXELS = 40_000_000L
private const val STORED_PICTURE_SIDE = 1024
private const val JPEG_QUALITY = 0.85f

/**
 * Turns an upload (JPEG, PNG, GIF, BMP) into the stored form: upright, center-cropped square,
 * at most [STORED_PICTURE_SIDE] px, JPEG. Answers 400 for anything that is not a decodable picture.
 */
fun toStoredPicture(upload: ByteArray): ByteArray {
    if (upload.isEmpty() || upload.size > MAX_PICTURE_UPLOAD_BYTES) throw invalidPicture()
    val upright = applyExifOrientation(decode(upload), readJpegExifOrientation(upload))
    return encodeJpeg(squareDownscaled(upright))
}

/** Reads the size from the header first so a small file cannot unpack into gigabytes of pixels. */
private fun decode(upload: ByteArray): BufferedImage {
    val stream = ImageIO.createImageInputStream(ByteArrayInputStream(upload)) ?: throw invalidPicture()
    stream.use {
        val reader = ImageIO.getImageReaders(stream).asSequence().firstOrNull() ?: throw invalidPicture()
        try {
            reader.input = stream
            if (reader.getWidth(0).toLong() * reader.getHeight(0) > MAX_PICTURE_PIXELS) throw invalidPicture()
            return reader.read(0)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            throw invalidPicture()
        } finally {
            reader.dispose()
        }
    }
}

private fun squareDownscaled(image: BufferedImage): BufferedImage {
    val side = minOf(image.width, image.height)
    var current = image.getSubimage((image.width - side) / 2, (image.height - side) / 2, side, side)
    var currentSide = side
    val target = minOf(side, STORED_PICTURE_SIDE)
    // Halving step by step keeps bilinear scaling from skipping pixels on big photos.
    do {
        currentSide = maxOf(currentSide / 2, target)
        current = draw(current, currentSide)
    } while (currentSide > target)
    return current
}

/** Also flattens transparency onto white, since JPEG has no alpha. */
private fun draw(source: BufferedImage, side: Int): BufferedImage {
    val output = BufferedImage(side, side, BufferedImage.TYPE_INT_RGB)
    val g = output.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
    g.color = Color.WHITE
    g.fillRect(0, 0, side, side)
    g.drawImage(source, 0, 0, side, side, null)
    g.dispose()
    return output
}

private fun encodeJpeg(image: BufferedImage): ByteArray {
    val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
    val output = ByteArrayOutputStream()
    ImageIO.createImageOutputStream(output).use { stream ->
        writer.output = stream
        val params = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = JPEG_QUALITY
        }
        writer.write(null, IIOImage(image, null, null), params)
        writer.dispose()
    }
    return output.toByteArray()
}

private fun invalidPicture() = ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid picture")
