package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.tag.model.Tag
import org.bson.types.ObjectId

class FakeTagRepository : TagRepository {
    val tags = mutableListOf<Tag>()

    override fun deleteByStableId(stableId: ObjectId): Long = tags.count { it.stableId == stableId }.toLong().also { tags.removeIf { it.stableId == stableId } }

    override fun save(tag: Tag): Tag {
        tags.requireUniqueClientId(tag, { it.id }, { it.stableId }, { it.clientId })
        tags.removeIf { it.id == tag.id }
        tags += tag
        return tag
    }

    override fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<Tag> =
        tags.filter { it.stableId == stableId && !it.deleted }

    override fun findById(id: ObjectId): Tag? = tags.firstOrNull { it.id == id }

    override fun findByStableIdAndClientId(stableId: ObjectId, clientId: String): Tag? =
        tags.firstOrNull { it.stableId == stableId && it.clientId == clientId }
}
