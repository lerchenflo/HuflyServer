package com.lerchenflo.hufly.server.core.logging

import ch.qos.logback.classic.pattern.ClassicConverter
import ch.qos.logback.classic.spi.ILoggingEvent
import com.lerchenflo.hufly.server.repository.AccountRepository
import org.bson.types.ObjectId
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/** MDC key of the requesting account, set by [com.lerchenflo.hufly.server.core.security.JwtAuthFilter]. */
const val LOG_USER = "user"

/** An account inside a log message, printed by its name: `log.info("Push to {}", LogUser(accountId))`. */
class LogUser(private val id: ObjectId) {
    override fun toString() = "{user:${id.toHexString()}}"
}

/**
 * Account names for the log, cached. Called by the converters of `logback-spring.xml`, which run on the log worker
 * thread, so a request never waits for a lookup. Shows the id while the name is unknown.
 */
object LogNames {
    private const val CACHE_MILLIS = 10 * 60 * 1000L
    private const val PAUSE_MILLIS = 30 * 1000L
    private val MENTION = Regex("""\{user:([0-9a-f]{24})}""")

    private class Cached(val name: String?, val at: Long)

    @Volatile
    var lookup: ((ObjectId) -> String?)? = null
    private val cache = ConcurrentHashMap<ObjectId, Cached>()
    @Volatile
    private var pausedUntil = 0L

    fun of(id: ObjectId): String = name(id) ?: id.toHexString()

    fun inMessage(message: String): String = MENTION.replace(message) { of(ObjectId(it.groupValues[1])) }

    fun clearCache() {
        cache.clear()
        pausedUntil = 0L
    }

    private fun name(id: ObjectId): String? {
        val now = System.currentTimeMillis()
        cache[id]?.takeIf { now - it.at < CACHE_MILLIS }?.let { return it.name }
        val lookup = lookup ?: return null
        if (now < pausedUntil) return null
        return try {
            lookup(id).also {
                if (cache.size > 5_000) cache.clear()
                cache[id] = Cached(it, now)
            }
        } catch (e: Exception) {
            // Database down: ids only for a while, so the log queue keeps moving.
            pausedUntil = now + PAUSE_MILLIS
            null
        }
    }
}

/** `%user`: the requesting account by name, "-" without a login. */
class UserConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String =
        event.mdcPropertyMap[LOG_USER]?.takeIf(ObjectId::isValid)?.let { LogNames.of(ObjectId(it)) } ?: "-"
}

/** `%userMsg`: the message with every [LogUser] replaced by the account's name. */
class MentionConverter : ClassicConverter() {
    override fun convert(event: ILoggingEvent): String = LogNames.inMessage(event.formattedMessage)
}

@Component
class LogNamesSetup(accountRepository: AccountRepository) {
    init {
        LogNames.lookup = { accountRepository.findById(it)?.displayName }
    }
}
