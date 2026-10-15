package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.Repository

interface EventRepository : Repository<Event, ObjectId> {
    fun deleteByStableId(stableId: ObjectId): Long
    fun save(event: Event): Event
    fun findById(id: ObjectId): Event?
    fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): Event?
    fun findByCreatorUserIdAndDeletedFalse(creatorUserId: ObjectId): List<Event>
    fun findByIdIn(ids: Collection<ObjectId>): List<Event>

    /** Live single events of every stable starting in (from, to]. */
    @Query("{ 'deleted': false, 'recurrence': null, 'startAt': { '\$gt': ?0, '\$lte': ?1 } }")
    fun findSingleStarting(from: Long, to: Long): List<Event>

    /** Live series of every stable whose first date is at or before [to]. */
    @Query("{ 'deleted': false, 'recurrence': { '\$ne': null }, 'startAt': { '\$lte': ?0 } }")
    fun findSeriesStartingBy(to: Long): List<Event>

    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<Event>
}

interface EventInvitationRepository : Repository<EventInvitation, ObjectId> {
    fun deleteByStableId(stableId: ObjectId): Long
    fun save(invitation: EventInvitation): EventInvitation
    fun findById(id: ObjectId): EventInvitation?
    fun findByEventIdAndDeletedFalse(eventId: ObjectId): List<EventInvitation>
    fun findByUserIdAndDeletedFalse(userId: ObjectId): List<EventInvitation>
    fun findByEventIdInAndDeletedFalse(eventIds: Collection<ObjectId>): List<EventInvitation>

    @Query(value = "{ 'stableId': ?0, 'version': { '\$gt': ?1, '\$lte': ?2 } }", sort = "{ 'version': 1 }")
    fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<EventInvitation>
}
