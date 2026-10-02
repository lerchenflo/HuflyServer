package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.repository.StableRepository
import com.lerchenflo.hufly.server.stable.model.Stable
import org.bson.types.ObjectId
import org.springframework.stereotype.Service

@Service
class StableLookupService(
    private val stableRepository: StableRepository,
) {
    fun getById(id: ObjectId): Stable =
        stableRepository.findById(id) ?: error("Stable $id missing")
}
