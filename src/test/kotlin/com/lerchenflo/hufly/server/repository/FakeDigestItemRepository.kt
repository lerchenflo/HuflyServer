package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.notification.model.DigestItem
import org.bson.types.ObjectId
import java.time.Instant

class FakeDigestItemRepository : DigestItemRepository {
    val items = mutableListOf<DigestItem>()

    override fun deleteByUserIdIn(userIds: Collection<ObjectId>): Long = items.count { it.userId in userIds }.toLong().also { items.removeIf { it.userId in userIds } }

    override fun save(item: DigestItem): DigestItem {
        items.removeIf { it.id == item.id }
        items += item
        return item
    }

    override fun findByCreatedAtBefore(time: Instant): List<DigestItem> = items.filter { it.createdAt < time }

    override fun deleteByIdIn(ids: Collection<ObjectId>): Long {
        val before = items.size
        items.removeIf { it.id in ids }
        return (before - items.size).toLong()
    }
}
