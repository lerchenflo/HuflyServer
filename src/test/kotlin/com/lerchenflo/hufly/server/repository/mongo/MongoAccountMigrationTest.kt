package com.lerchenflo.hufly.server.repository.mongo

import com.lerchenflo.hufly.server.account.AccountMigration
import com.lerchenflo.hufly.server.repository.AccountRepository
import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.repository.UserRepository
import com.lerchenflo.hufly.server.stable.InviteCodes
import org.bson.Document
import org.bson.types.ObjectId
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Logins from before accounts existed move into `accounts` with the same id, so sessions and tokens stay valid. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MongoAccountMigrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")
    }

    @Autowired lateinit var mongoTemplate: MongoTemplate
    @Autowired lateinit var migration: AccountMigration
    @Autowired lateinit var accountRepository: AccountRepository
    @Autowired lateinit var userRepository: UserRepository
    @Autowired lateinit var stableRepository: StableRepository

    private val stableId = ObjectId.get()
    private val annaId = ObjectId.get()
    private val erasedId = ObjectId.get()

    private fun legacyUser(id: ObjectId, email: String, deleted: Boolean, mustChange: Boolean?) = Document("_id", id)
        .append("stableId", stableId).append("email", email).append("displayName", "Anna").append("phoneNumber", null)
        .append("profilePictureUrl", null).append("hashedPassword", "hash-$email").append("roleTagIds", java.util.ArrayList<ObjectId>())
        .append("createdAt", 1L).append("updatedAt", 2L).append("updatedBy", id).append("deleted", deleted)
        .apply { if (mustChange != null) append("mustChangePassword", mustChange) }

    private fun seedLegacyData() {
        val users = mongoTemplate.getCollection("users")
        users.drop()
        mongoTemplate.getCollection("accounts").drop()
        mongoTemplate.getCollection("stables").drop()
        users.insertMany(listOf(legacyUser(annaId, "anna@hufly.test", false, true), legacyUser(erasedId, "deleted-x@deleted.invalid", true, null)))
        users.createIndex(Document("email", 1), com.mongodb.client.model.IndexOptions().name("email").unique(true))
        mongoTemplate.getCollection("stables").insertOne(
            Document("_id", stableId).append("name", "Hof").append("adminUserId", annaId).append("subscriptionStatus", "TRIAL")
                .append("subscriptionValidUntil", null).append("createdAt", 1L).append("updatedAt", 2L).append("updatedBy", annaId)
                .append("deleted", false)
        )
    }

    @Test
    fun `moves logins into accounts with the same id and strips them from the memberships`() {
        seedLegacyData()

        migration.migrate()

        val anna = accountRepository.findById(annaId)!!
        assertEquals("anna@hufly.test", anna.email)
        assertEquals("hash-anna@hufly.test", anna.hashedPassword)
        assertTrue(anna.mustChangePassword)
        assertEquals(stableId, anna.createdByStableId)
        assertFalse(anna.deleted)
        assertTrue(accountRepository.findById(erasedId)!!.deleted)
        assertFalse(accountRepository.findById(erasedId)!!.mustChangePassword)

        val raw = mongoTemplate.getCollection("users").find(Document("_id", annaId)).first()!!
        assertFalse(raw.containsKey("hashedPassword"))
        assertFalse(raw.containsKey("mustChangePassword"))
        assertEquals(annaId, userRepository.findById(annaId)!!.accountId)
        assertFalse(mongoTemplate.getCollection("users").listIndexes().any { it.getString("name") == "email" })
    }

    @Test
    fun `gives every stable an invite code and can run again without changes`() {
        seedLegacyData()

        migration.migrate()
        val code = stableRepository.findById(stableId)!!.inviteCode
        migration.migrate()

        assertNotNull(InviteCodes.normalize(code!!))
        assertEquals(code, stableRepository.findById(stableId)!!.inviteCode)
        assertEquals(2L, mongoTemplate.getCollection("accounts").countDocuments())
        assertEquals("hash-anna@hufly.test", accountRepository.findById(annaId)!!.hashedPassword)
    }
}
