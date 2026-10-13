package com.lerchenflo.hufly.server.notification.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document

/** A due time a member has heard about (reminder or assignment push), so each one reminds once. */
@Document("sentReminders")
@CompoundIndex(name = "task_due_user", def = "{'taskId': 1, 'dueAt': 1, 'userId': 1}", unique = true)
data class SentReminder(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val stableId: ObjectId,
    val taskId: ObjectId,
    /** The effective due time; a moved date reminds again. */
    @Indexed val dueAt: Long,
    /** The membership. */
    val userId: ObjectId,
)
