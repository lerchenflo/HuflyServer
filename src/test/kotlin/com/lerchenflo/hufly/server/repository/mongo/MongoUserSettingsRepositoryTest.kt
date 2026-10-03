package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.repository.UserSettingsRepository
import com.lerchenflo.hufly.server.user.model.UserSettings
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DuplicateKeyException
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The conditional writes behind expectedUpdatedAt must be atomic in Mongo, not check-then-save. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoUserSettingsRepositoryTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var repository: UserSettingsRepository

    private val first = Instant.parse("2026-10-03T10:00:00.123Z")
    private val second = Instant.parse("2026-10-03T10:00:05.456Z")

    @Test
    fun `insert fails when settings exist`() {
        val anna = ObjectId.get()
        repository.insert(UserSettings(anna, mapOf("theme" to "dark"), first))

        assertFailsWith<DuplicateKeyException> { repository.insert(UserSettings(anna, mapOf("theme" to "light"), second)) }
    }

    @Test
    fun `replace only matches the expected version`() {
        val anna = ObjectId.get()
        repository.save(UserSettings(anna, mapOf("theme" to "dark"), first))

        assertEquals(0, repository.replaceIfUnchanged(anna, second, mapOf("theme" to "x"), second))
        assertEquals(1, repository.replaceIfUnchanged(anna, first, mapOf("theme" to "light"), second))

        val stored = repository.findById(anna)!!
        assertEquals(mapOf("theme" to "light"), stored.values)
        assertEquals(second, stored.updatedAt)
    }
}
