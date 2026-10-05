package com.lerchenflo.hufly.server.core.sync

import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Component

/** Collections that grow over time and sync by version instead of IdTimeStamp lists. */
enum class SyncCollection(val key: String) {
    EVENTS("events"),
    EVENT_INVITATIONS("eventInvitations"),
    HORSE_LOG("horseLog"),
    TASKS("tasks"),
    PADDOCK_ASSIGNMENTS("paddockAssignments"),
    EVENT_OCCURRENCES("eventOccurrences"),
    EVENT_OCCURRENCE_ANSWERS("eventOccurrenceAnswers"),
    TASK_OCCURRENCES("taskOccurrences"),
    NOTES("notes"),
}

@Document("counters")
data class VersionCounter(@Id val id: String, val seq: Long)

interface VersionCounterStore {
    fun increment(collection: SyncCollection): Long
    fun current(collection: SyncCollection): Long
}

@Component
class MongoVersionCounterStore(private val mongoTemplate: MongoTemplate) : VersionCounterStore {

    override fun increment(collection: SyncCollection): Long =
        mongoTemplate.findAndModify(
            Query(Criteria.where("_id").`is`(collection.key)),
            Update().inc("seq", 1),
            FindAndModifyOptions.options().returnNew(true).upsert(true),
            VersionCounter::class.java,
        )!!.seq

    override fun current(collection: SyncCollection): Long =
        mongoTemplate.findById(collection.key, VersionCounter::class.java)?.seq ?: 0
}
