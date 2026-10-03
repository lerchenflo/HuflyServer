package com.lerchenflo.hufly.server.core.picture

import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** HOR-2, USR-6: uploads are stored upright, square, at most 1024 px and as JPEG. */
class PicturesTest {

    private fun image(width: Int, height: Int, type: Int = BufferedImage.TYPE_INT_RGB, paint: (BufferedImage) -> Unit = {}) =
        BufferedImage(width, height, type).also(paint)

    private fun encode(image: BufferedImage, format: String): ByteArray =
        ByteArrayOutputStream().also { ImageIO.write(image, format, it) }.toByteArray()

    private fun decode(bytes: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(bytes))

    /** Left half red, right half blue. */
    private fun halves(width: Int, height: Int) = image(width, height) {
        val g = it.createGraphics()
        g.color = Color.RED
        g.fillRect(0, 0, width / 2, height)
        g.color = Color.BLUE
        g.fillRect(width / 2, 0, width - width / 2, height)
        g.dispose()
    }

    private fun isJpeg(bytes: ByteArray) = (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xFF) == 0xD8

    private fun isReddish(rgb: Int) = (rgb shr 16 and 0xFF) > 200 && (rgb and 0xFF) < 60

    /** Inserts an EXIF APP1 segment with [orientation] right after the JPEG start marker. */
    private fun withExifOrientation(jpeg: ByteArray, orientation: Int): ByteArray {
        val tiff = byteArrayOf(
            0x4D, 0x4D, 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, // big endian, first directory at 8
            0x00, 0x01, // one entry
            0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation.toByte(), 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, // no next directory
        )
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val segment = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return jpeg.copyOfRange(0, 2) + segment + jpeg.copyOfRange(2, jpeg.size)
    }

    private fun assertBadRequest(block: () -> Unit) {
        assertEquals(HttpStatus.BAD_REQUEST, assertFailsWith<ResponseStatusException> { block() }.statusCode)
    }

    @Test
    fun `a large png becomes a centered 1024 px square jpeg`() {
        val stored = toStoredPicture(encode(halves(2400, 1200), "png"))

        assertTrue(isJpeg(stored))
        val picture = decode(stored)
        assertEquals(1024, picture.width)
        assertEquals(1024, picture.height)
        assertTrue(isReddish(picture.getRGB(10, 512)))
    }

    @Test
    fun `small pictures are cropped but never upscaled`() {
        val picture = decode(toStoredPicture(encode(image(300, 200), "png")))

        assertEquals(200, picture.width)
        assertEquals(200, picture.height)
    }

    @Test
    fun `transparent pngs get a white background`() {
        val picture = decode(toStoredPicture(encode(image(50, 50, BufferedImage.TYPE_INT_ARGB), "png")))

        val rgb = picture.getRGB(25, 25)
        assertTrue(listOf(16, 8, 0).all { (rgb shr it and 0xFF) > 240 })
    }

    @Test
    fun `exif orientation is applied before cropping`() {
        val sideways = withExifOrientation(encode(halves(200, 100), "jpg"), orientation = 6)

        val picture = decode(toStoredPicture(sideways))

        // Rotated 90 degrees clockwise: the red half moves to the top.
        assertTrue(isReddish(picture.getRGB(50, 5)))
        assertTrue(!isReddish(picture.getRGB(50, 95)))
    }

    @Test
    fun `garbage and empty uploads answer 400`() {
        assertBadRequest { toStoredPicture(ByteArray(0)) }
        assertBadRequest { toStoredPicture("not an image".toByteArray()) }
    }

    @Test
    fun `uploads over 10 MB answer 400`() {
        assertBadRequest { toStoredPicture(ByteArray(MAX_PICTURE_UPLOAD_BYTES + 1)) }
    }

    @Test
    fun `pictures with more than 40 megapixels answer 400 before decoding`() {
        assertBadRequest { toStoredPicture(encode(image(7000, 7000, BufferedImage.TYPE_BYTE_BINARY), "png")) }
    }
}

/** A small valid PNG for upload tests elsewhere. */
fun testPng(width: Int = 40, height: Int = 30): ByteArray =
    ByteArrayOutputStream().also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
