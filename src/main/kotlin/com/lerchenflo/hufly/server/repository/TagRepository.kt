package com.lerchenflo.hufly.server.repository

import com.lerchenflo.hufly.server.tag.model.Tag
import org.bson.types.ObjectId
import org.springframework.data.repository.Repository

interface TagRepository : Repository<Tag, ObjectId> {
    fun save(tag: Tag): Tag
    fun findById(id: ObjectId): Tag?
    fun findByStableIdAndDeletedFalse(stableId: ObjectId): List<Tag>
}
