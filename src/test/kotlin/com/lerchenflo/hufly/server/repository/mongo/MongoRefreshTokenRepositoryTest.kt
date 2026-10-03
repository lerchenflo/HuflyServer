package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.authentication.model.DeviceType
import com.lerchenflo.hufly.server.authentication.model.RefreshToken
import com.lerchenflo.hufly.server.repository.RefreshTokenRepository
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoRefreshTokenRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var repository: RefreshTokenRepository
    @Autowired lateinit var mongoTemplate: MongoTemplate

    private val later = Instant.parse("2026-11-01T00:00:00Z")

    private fun session(hash: String) =
        repository.save(RefreshToken(userId = ObjectId.get(), hashedToken = hash, expiresAt = Instant.parse("2026-10-31T00:00:00Z"), createdAt = Instant.EPOCH))

    @Test
    fun `rotate swaps the current hash in place, once`() {
        val row = session("h1")

        assertEquals(1, repository.rotate("h1", "h2", "enc2", later, later))
        assertEquals(0, repository.rotate("h1", "h3", "enc3", later, later))

        val rotated = repository.findByHashedToken("h2")!!
        assertEquals(row.id, rotated.id)
        assertEquals("h1", rotated.previousHashedToken)
        assertEquals("enc2", rotated.encryptedToken)
        assertEquals(later, rotated.expiresAt)
        assertEquals(row.id, repository.findByPreviousHashedToken("h1")!!.id)
    }

    @Test
    fun `logout lookup deletes by current or previous hash`() {
        session("a1")
        repository.rotate("a1", "a2", "enc", later, later)

        assertEquals(1, repository.deleteByHashedTokenOrPreviousHashedToken("a1", "a1"))
        assertNull(repository.findByHashedToken("a2"))
    }

    @Test
    fun `times are stored as epoch millis`() {
        session("t1")
        repository.rotate("t1", "t2", "enc", later, later)

        val raw = mongoTemplate.getCollection("refreshTokens").find(org.bson.Document("hashedToken", "t2")).first()!!
        assertEquals(later.toEpochMilli(), raw["expiresAt"])
        assertEquals(0L, raw["createdAt"])
        assertEquals(later, repository.findByHashedToken("t2")!!.expiresAt)
    }

    @Test
    fun `cleanup query deletes only expired sessions`() {
        repository.save(RefreshToken(userId = ObjectId.get(), hashedToken = "old", expiresAt = Instant.parse("2026-01-01T00:00:00Z"), createdAt = Instant.EPOCH))
        repository.save(RefreshToken(userId = ObjectId.get(), hashedToken = "new", expiresAt = Instant.parse("2027-01-01T00:00:00Z"), createdAt = Instant.EPOCH))

        repository.deleteByExpiresAtBefore(Instant.parse("2026-06-01T00:00:00Z"))

        assertNull(repository.findByHashedToken("old"))
        assertEquals("new", repository.findByHashedToken("new")!!.hashedToken)
    }

    @Test
    fun `device and keep-current deletes match only the intended rows`() {
        val anna = ObjectId.get()
        fun device(hash: String, name: String, type: DeviceType, id: String? = null) = repository.save(
            RefreshToken(
                userId = anna, hashedToken = hash, expiresAt = later, createdAt = Instant.EPOCH, deviceName = name, deviceType = type, deviceId = id,
            )
        )
        val phone = device("d1", "Pixel 7", DeviceType.ANDROID)
        device("d2", "iPad", DeviceType.IOS)
        device("d3", "iPad", DeviceType.ANDROID)
        device("d4", "iPad", DeviceType.IOS, id = "install-1")

        assertEquals(1, repository.deleteByUserIdAndDeviceNameAndDeviceTypeAndDeviceIdIsNull(anna, "iPad", DeviceType.IOS))
        assertEquals(1, repository.deleteByUserIdAndDeviceId(anna, "install-1"))
        assertEquals(1, repository.deleteByUserIdAndIdNot(anna, phone.id))
        assertEquals(listOf(phone.id), repository.findByUserId(anna).map { it.id })
    }
}
