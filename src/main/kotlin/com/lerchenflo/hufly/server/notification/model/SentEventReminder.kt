package com.lerchenflo.hufly.server.notification.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/** An event start a member was reminded of, so each one reminds once. */
@Document("sentEventReminders")
@CompoundIndex(name = "event_start_user", def = "{'eventId': 1, 'startAt': 1, 'userId': 1}", unique = true)
data class SentEventReminder(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val stableId: ObjectId,
    val eventId: ObjectId,
    /** The effective start; a moved date reminds again. */
    @Indexed val startAt: Long,
    /** The membership. */
    val userId: ObjectId,
)
