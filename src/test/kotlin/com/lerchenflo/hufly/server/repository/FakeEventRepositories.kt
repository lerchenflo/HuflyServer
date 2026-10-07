package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.event.model.Event
import com.lerchenflo.hufly.server.event.model.EventInvitation
import org.bson.types.ObjectId
import org.springframework.data.domain.Limit

class FakeEventRepository : EventRepository {
    val events = mutableListOf<Event>()

    override fun deleteByStableId(stableId: ObjectId): Long = events.count { it.stableId == stableId }.toLong().also { events.removeIf { it.stableId == stableId } }

    override fun save(event: Event): Event {
        events.requireUniqueClientId(event, { it.id }, { it.stableId }, { it.clientId })
        events.removeIf { it.id == event.id }
        events += event
        return event
    }

    override fun findById(id: ObjectId): Event? = events.firstOrNull { it.id == id }

    override fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): Event? =
        events.firstOrNull { it.stableId == stableId && it.clientId == clientId }

    override fun findByCreatorUserIdAndDeletedFalse(creatorUserId: ObjectId): List<Event> =
        events.filter { it.creatorUserId == creatorUserId && !it.deleted }

    override fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<Event> =
        events.filter { it.stableId == stableId && it.version > since && it.version <= watermark }
            .sortedBy { it.version }
            .take(limit.max())
}

class FakeEventInvitationRepository : EventInvitationRepository {
    val invitations = mutableListOf<EventInvitation>()

    override fun deleteByStableId(stableId: ObjectId): Long = invitations.count { it.stableId == stableId }.toLong().also { invitations.removeIf { it.stableId == stableId } }

    override fun save(invitation: EventInvitation): EventInvitation {
        invitations.removeIf { it.id == invitation.id }
        invitations += invitation
        return invitation
    }

    override fun findById(id: ObjectId): EventInvitation? = invitations.firstOrNull { it.id == id }

    override fun findByEventIdAndDeletedFalse(eventId: ObjectId): List<EventInvitation> =
        invitations.filter { it.eventId == eventId && !it.deleted }

    override fun findByUserIdAndDeletedFalse(userId: ObjectId): List<EventInvitation> =
        invitations.filter { it.userId == userId && !it.deleted }

    override fun findVersionPage(stableId: ObjectId, since: Long, watermark: Long, limit: Limit): List<EventInvitation> =
        invitations.filter { it.stableId == stableId && it.version > since && it.version <= watermark }
            .sortedBy { it.version }
            .take(limit.max())
}
