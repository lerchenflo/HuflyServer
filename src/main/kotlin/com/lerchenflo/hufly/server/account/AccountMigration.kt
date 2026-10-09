package com.lerchenflo.hufly.server.account

import com.lerchenflo.hufly.server.stable.InviteCodes
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component

/**
 * Moves logins out of `users` into `accounts` (same id, so tokens and sessions stay valid) and gives every stable an
 * invite code. Runs while the context starts, before the web server accepts requests; idempotent. A failure stops
 * the startup: a membership saved without its account would lose the password for good.
 */
@Component
class AccountMigration(
    private val mongoTemplate: MongoTemplate,
    @Value("\${migration.accounts.enabled:true}") private val enabled: Boolean,
) : InitializingBean {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterPropertiesSet() {
        if (enabled) migrate()
    }

    fun migrate() {
        val users = mongoTemplate.getCollection("users")
        val legacy = Document("hashedPassword", Document("\$exists", true))
        val pending = users.countDocuments(legacy)
        if (pending > 0) {
            users.aggregate(
                listOf(
                    Document("\$match", legacy),
                    Document(
                        "\$project",
                        Document("_id", 1).append("email", 1).append("displayName", 1).append("hashedPassword", 1)
                            .append("mustChangePassword", Document("\$ifNull", listOf("\$mustChangePassword", false)))
                            .append("createdByStableId", "\$stableId")
                            .append("createdAt", 1).append("updatedAt", 1)
                            .append("deleted", Document("\$ifNull", listOf("\$deleted", false))),
                    ),
                    Document(
                        "\$merge",
                        Document("into", "accounts").append("on", "_id").append("whenMatched", "keepExisting").append("whenNotMatched", "insert"),
                    ),
                )
            ).toCollection()
            users.updateMany(legacy, Document("\$unset", Document("hashedPassword", "").append("mustChangePassword", "")))
            log.info("Moved {} logins into accounts", pending)
        }
        users.updateMany(
            Document("accountId", Document("\$exists", false)),
            listOf(Document("\$set", Document("accountId", "\$_id"))),
        )
        if (users.listIndexes().any { it.getString("name") == "email" }) users.dropIndex("email")
        addInviteCodes()

        val accountIds = mongoTemplate.getCollection("accounts").distinct("_id", ObjectId::class.java).toSet()
        val orphans = users.distinct("accountId", ObjectId::class.java).count { it !in accountIds }
        check(orphans == 0) { "$orphans memberships without an account" }
    }

    private fun addInviteCodes() {
        val missing = Query(Criteria.where("inviteCode").exists(false))
        mongoTemplate.find(missing, Document::class.java, "stables").forEach { stable ->
            val code = generateSequence { InviteCodes.generate() }
                .first { !mongoTemplate.exists(Query(Criteria.where("inviteCode").`is`(it)), "stables") }
            mongoTemplate.updateFirst(
                Query(Criteria.where("_id").`is`(stable["_id"]).and("inviteCode").exists(false)),
                Update().set("inviteCode", code),
                "stables",
            )
        }
    }
}
