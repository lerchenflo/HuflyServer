package com.lerchenflo.hufly.server.horse.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.CompoundIndex
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import java.time.Instant
import java.time.LocalDate

data class Medication(
    val name: String,
    val dosage: String,
    val from: LocalDate,
    val until: LocalDate?,
)

@Document("horses")
@CompoundIndex(name = "stableId_clientId", def = "{'stableId': 1, 'clientId': 1}", unique = true, partialFilter = "{'clientId': {\$type: 'string'}}")
data class Horse(
    @Id val id: ObjectId = ObjectId.get(),
    @Indexed val stableId: ObjectId,
    val name: String,
    val description: String,
    val pictureUrl: String?,
    val birthDate: LocalDate?,
    val breed: String,
    val color: String,
    val ownerUserId: ObjectId?,
    val medicalNotes: String,
    val vetContact: String,
    val medications: List<Medication>,
    /** Only changed by members with FOODPLAN_EDIT. */
    val foodPlanId: ObjectId?,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
    /** The client's local id, see [com.lerchenflo.hufly.server.core.idempotentCreate]. */
    val clientId: String? = null,
    /** Reitbeteiligungen; no extra rights. */
    val coRiderUserIds: List<ObjectId> = emptyList(),
)
