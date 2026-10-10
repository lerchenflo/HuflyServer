package com.lerchenflo.hufly.server.account

import com.lerchenflo.hufly.server.stable.InviteCodes
import org.bson.Document
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.data.mongodb.MongoDatabaseFactory
import org.springframework.stereotype.Component

/**
 * Moves logins out of `users` into `accounts` (same id, so tokens and sessions stay valid) and gives every stable an
 * invite code. Runs while the context starts, before the web server accepts requests; idempotent. A failure stops
 * the startup: a membership saved without its account would lose the password for good. Works on the raw database
 * and runs before `mongoTemplate`, whose index creation needs the migrated data (unique `users.accountId`).
 */
@Component
class AccountMigration(
    private val databaseFactory: MongoDatabaseFactory,
    @Value("\${migration.accounts.enabled:true}") private val enabled: Boolean,
) : InitializingBean {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun afterPropertiesSet() {
        if (enabled) migrate()
    }

    fun migrate() {
        val database = databaseFactory.mongoDatabase
        val users = database.getCollection("users")
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

        val accountIds = database.getCollection("accounts").distinct("_id", ObjectId::class.java).toSet()
        val orphans = users.distinct("accountId", ObjectId::class.java).count { it !in accountIds }
        check(orphans == 0) { "$orphans memberships without an account" }
    }

    private fun addInviteCodes() {
        val stables = databaseFactory.mongoDatabase.getCollection("stables")
        val missing = Document("inviteCode", Document("\$exists", false))
        stables.find(missing).forEach { stable ->
            val code = generateSequence { InviteCodes.generate() }
                .first { stables.countDocuments(Document("inviteCode", it)) == 0L }
            stables.updateOne(Document("_id", stable["_id"]).append("inviteCode", Document("\$exists", false)), Document("\$set", Document("inviteCode", code)))
        }
    }

    companion object {
        @Bean
        @JvmStatic
        fun migrateBeforeMongoTemplate() = BeanFactoryPostProcessor { beanFactory ->
            if (beanFactory.containsBeanDefinition("mongoTemplate")) {
                val template = beanFactory.getBeanDefinition("mongoTemplate")
                template.setDependsOn(*template.dependsOn.orEmpty(), "accountMigration")
            }
        }
    }
}
