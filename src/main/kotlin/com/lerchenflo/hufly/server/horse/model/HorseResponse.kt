package com.lerchenflo.hufly.server.horse.model

import java.time.LocalDate

/** Dates are ISO strings (`2015-04-01`), `updatedAt` is epoch milliseconds. */
data class HorseResponse(
    val id: String,
    val stableId: String,
    val name: String,
    val description: String,
    val pictureUrl: String?,
    val birthDate: LocalDate?,
    val breed: String,
    val color: String,
    val ownerUserId: String?,
    val medicalNotes: String,
    val vetContact: String,
    val medications: List<Medication>,
    val foodPlanId: String?,
    val updatedAt: Long,
    val updatedBy: String,
)

fun Horse.toHorseResponse() = HorseResponse(
    id = id.toHexString(),
    stableId = stableId.toHexString(),
    name = name,
    description = description,
    pictureUrl = pictureUrl,
    birthDate = birthDate,
    breed = breed,
    color = color,
    ownerUserId = ownerUserId?.toHexString(),
    medicalNotes = medicalNotes,
    vetContact = vetContact,
    medications = medications,
    foodPlanId = foodPlanId?.toHexString(),
    updatedAt = updatedAt.toEpochMilli(),
    updatedBy = updatedBy.toHexString(),
)
