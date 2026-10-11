package com.lerchenflo.hufly.server.testdata

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** Collects what [type] logs, as "LEVEL message" lines. */
class LogCapture(type: Class<*>) {
    private val logger = LoggerFactory.getLogger(type) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    init {
        logger.addAppender(appender)
    }

    val lines: List<String> get() = appender.list.map { "${it.level} ${it.formattedMessage}" }

    fun close() {
        logger.detachAppender(appender)
    }
}
