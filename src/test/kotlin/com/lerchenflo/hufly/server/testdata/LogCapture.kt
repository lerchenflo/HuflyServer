package com.lerchenflo.hufly.server.testdata

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.lerchenflo.hufly.server.core.logging.LOG_USER
import org.slf4j.LoggerFactory

/** Collects what [type] logs from [level] up, as "LEVEL message" lines. */
class LogCapture(type: Class<*>, level: Level = Level.INFO) {
    private val logger = LoggerFactory.getLogger(type) as Logger
    private val appender = object : ListAppender<ILoggingEvent>() {
        // Keeps the MDC of the logging thread; read later, it would be the test thread's.
        override fun append(event: ILoggingEvent) {
            event.prepareForDeferredProcessing()
            super.append(event)
        }
    }.apply { start() }
    private val levelBefore = logger.level

    init {
        logger.level = level
        logger.addAppender(appender)
    }

    val lines: List<String> get() = appender.list.map { "${it.level} ${it.formattedMessage}" }

    /** The account id of each line's user column, null without a login. */
    val users: List<String?> get() = appender.list.map { it.mdcPropertyMap[LOG_USER] }

    fun close() {
        logger.detachAppender(appender)
        logger.level = levelBefore
    }
}
