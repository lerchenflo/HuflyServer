package com.lerchenflo.hufly.server.repository.mongo

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
import java.util.Date
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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

        assertEquals(1, repository.rotate("h1", "h2", "enc2", later))
        assertEquals(0, repository.rotate("h1", "h3", "enc3", later))

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
        repository.rotate("a1", "a2", "enc", later)

        assertEquals(1, repository.deleteByHashedTokenOrPreviousHashedToken("a1", "a1"))
        assertNull(repository.findByHashedToken("a2"))
    }

    @Test
    fun `expiresAt is a BSON date, so the TTL index works`() {
        session("t1")

        val raw = mongoTemplate.getCollection("refreshTokens").find(org.bson.Document("hashedToken", "t1")).first()!!
        assertIs<Date>(raw["expiresAt"])
    }
}
