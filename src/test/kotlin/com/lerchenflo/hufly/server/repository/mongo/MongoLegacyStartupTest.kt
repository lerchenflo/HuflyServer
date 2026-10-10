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
            }
        }
    }

    @Autowired lateinit var mongoTemplate: MongoTemplate

    @Test
    fun `legacy members get their accounts and the one-membership index`() {
        assertEquals(2L, mongoTemplate.getCollection("accounts").countDocuments())
        val index = mongoTemplate.getCollection("users").listIndexes().first { it.getString("name") == "one_membership_per_account" }
        assertEquals(true, index.getBoolean("unique"))
    }
}
