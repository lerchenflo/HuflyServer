package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.notification.model.DigestItem
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository
import java.time.Instant

interface DigestItemRepository : Repository<DigestItem, ObjectId> {
    fun deleteByUserIdIn(userIds: Collection<ObjectId>): Long
    fun save(item: DigestItem): DigestItem
    fun findByCreatedAtBefore(time: Instant): List<DigestItem>
    fun deleteByIdIn(ids: Collection<ObjectId>): Long
}
