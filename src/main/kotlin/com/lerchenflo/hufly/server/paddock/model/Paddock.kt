package com.lerchenflo.hufly.server.paddock.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant

@Document("paddocks")
data class Paddock(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val stableId: ObjectId,
    val name: String,
    val description: String,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
)

/** Horses that usually go out together. */
@Document("horseGroups")
data class HorseGroup(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val stableId: ObjectId,
    val name: String,
    val horseIds: List<ObjectId>,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
)

/** Two horses that should not share a paddock. Clients only warn; planners can override. */
@Document("horseConflicts")
data class HorseConflict(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val stableId: ObjectId,
    val firstHorseId: ObjectId,
    val secondHorseId: ObjectId,
    val reason: String,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
)

/** Who is on which paddock when. [horseIds] is the group horses plus single horses, fixed at save time. */
@Document("paddockAssignments")
@CompoundIndex(def = "{'stableId': 1, 'version': 1}")
data class PaddockAssignment(
    @Id val id: ObjectId = ObjectId.get(),
    val stableId: ObjectId,
    val paddockId: ObjectId,
    val groupIds: List<ObjectId>,
    val horseIds: List<ObjectId>,
    val startAt: Instant,
    val endAt: Instant?,
    val comment: String,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    val version: Long = 0,
)
