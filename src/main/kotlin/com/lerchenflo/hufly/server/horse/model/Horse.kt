package com.lerchenflo.hufly.server.horse.model

import org.bson.types.ObjectId
import org.springframework.data.annotation.Id
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
    /** Set through the food plan feature, not through horse edits. */
    val foodPlanId: ObjectId?,
    val updatedAt: Instant,
    val updatedBy: ObjectId,
    val deleted: Boolean = false,
)
