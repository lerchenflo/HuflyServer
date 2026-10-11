package com.lerchenflo.hufly.server.repository.mongo

import com.mongodb.client.MongoClients
import org.bson.Document
import org.bson.types.ObjectId
import org.junit.jupiter.api.BeforeAll
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import kotlin.test.Test
import kotlin.test.assertEquals

/** A database from before accounts starts up: the migration runs before Spring builds the unique membership index. */
@SpringBootTest(properties = ["spring.data.mongodb.auto-index-creation=true", "migration.accounts.enabled=true"])
@Testcontainers(disabledWithoutDocker = true)
class MongoLegacyStartupTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val mongo = MongoDBContainer("mongo:8")

        private val stableId = ObjectId.get()
        private val memberIds = listOf(ObjectId.get(), ObjectId.get())

        @BeforeAll
        @JvmStatic
        fun seedLegacyUsers() {
            MongoClients.create(mongo.connectionString).use { client ->
                val users = client.getDatabase("hufly-test").getCollection("users")
                users.insertMany(memberIds.mapIndexed { i, id ->
                    Document("_id", id).append("stableId", stableId).append("email", "member$i@hufly.test")
                        .append("displayName", "Member $i").append("hashedPassword", "hash-$i")
                        .append("roleTagIds", java.util.ArrayList<ObjectId>()).append("createdAt", 1L)
                        .append("updatedAt", 2L).append("updatedBy", id).append("deleted", false)
                })
                // The index of the one-stable-per-account days must give way to the per-stable one.
                users.createIndex(
                    Document("accountId", 1),
                    com.mongodb.client.model.IndexOptions().name("one_membership_per_account").unique(true)
                        .partialFilterExpression(Document("deleted", false).append("accountId", Document("\$exists", true))),
                )
            }
        }
    }

    @Autowired lateinit var mongoTemplate: MongoTemplate

    @Test
    fun `legacy members get their accounts and the per-stable membership index`() {
        assertEquals(2L, mongoTemplate.getCollection("accounts").countDocuments())
        val indexes = mongoTemplate.getCollection("users").listIndexes().toList()
        val index = indexes.first { it.getString("name") == "one_membership_per_stable" }
        assertEquals(true, index.getBoolean("unique"))
        assertEquals(Document("accountId", 1).append("stableId", 1), index.get("key", Document::class.java))
        assertEquals(null, indexes.firstOrNull { it.getString("name") == "one_membership_per_account" })
    }
}
