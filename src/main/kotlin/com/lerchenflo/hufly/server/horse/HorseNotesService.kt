package com.lerchenflo.hufly.server.horse

import com.lerchenflo.hufly.server.core.Clock
import com.lerchenflo.hufly.server.core.access.AccessService
import com.lerchenflo.hufly.server.core.badRequest
import com.lerchenflo.hufly.server.horse.model.Horse
import com.lerchenflo.hufly.server.horse.model.HorseNote
import com.lerchenflo.hufly.server.horse.model.HorseNoteArea
import com.lerchenflo.hufly.server.horse.model.PaddockRules
import com.lerchenflo.hufly.server.repository.HorseRepository
import com.lerchenflo.hufly.server.repository.PaddockRepository
import com.lerchenflo.hufly.server.tag.model.Permission
import com.lerchenflo.hufly.server.user.model.User
import org.bson.types.ObjectId
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * A horse's notes and paddock rules, every member reads them with the horse. Notes are written per area: each area a
 * change touches needs its right, so a member who may only write feeding notes cannot drop the riding ones.
 * HORSE_EDIT_OWN covers every area and the paddock rules of own horses, like the other horse rights.
 */
@Service
class HorseNotesService(
    private val horseRepository: HorseRepository,
    private val paddockRepository: PaddockRepository,
    private val accessService: AccessService,
    private val clock: Clock,
) {
    fun updateNotes(requester: User, horseId: ObjectId, notes: List<HorseNote>): Horse {
        val horse = stableHorse(requester, horseId)
        validate(notes)
        val changedAreas = HorseNoteArea.entries.filter { area ->
            horse.notes.filter { it.area == area }.toSet() != notes.filter { it.area == area }.toSet()
        }
        if (changedAreas.isEmpty() && horse.notes == notes) return horse
        changedAreas.forEach { accessService.requireHorsePermission(requester, it.permission, listOf(horse.ownerUserId)) }
        return horseRepository.save(horse.copy(notes = notes, updatedAt = clock.millis(), updatedBy = requester.id))
    }

    fun updatePaddockRules(requester: User, horseId: ObjectId, rules: PaddockRules): Horse {
        val horse = stableHorse(requester, horseId)
        accessService.requireHorsePermission(requester, Permission.PADDOCK_PLAN, listOf(horse.ownerUserId))
        val paddockIds = rules.allowedPaddockIds.distinct()
        val known = paddockIds.all { id -> paddockRepository.findById(id)?.let { it.stableId == requester.stableId && !it.deleted } == true }
        if (!known) throw badRequest("Unknown paddock")
        val stored = rules.copy(allowedPaddockIds = paddockIds).orNullWhenEmpty()
        return horseRepository.save(horse.copy(paddockRules = stored, updatedAt = clock.millis(), updatedBy = requester.id))
    }

    private fun validate(notes: List<HorseNote>) {
        if (notes.size > MAX_NOTES) throw badRequest("At most $MAX_NOTES notes per horse")
        if (notes.map { it.id }.distinct().size != notes.size) throw badRequest("Duplicate note id")
        if (notes.any { it.text.isBlank() || it.text.length > MAX_TEXT }) throw badRequest("A note needs 1 to $MAX_TEXT characters")
    }

    private val HorseNoteArea.permission
        get() = when (this) {
            HorseNoteArea.GENERAL, HorseNoteArea.RIDING -> Permission.HORSE_EDIT
            HorseNoteArea.FEEDING -> Permission.FOODPLAN_EDIT
            HorseNoteArea.PADDOCK -> Permission.PADDOCK_PLAN
        }

    private fun stableHorse(requester: User, horseId: ObjectId): Horse =
        horseRepository.findById(horseId)?.takeIf { it.stableId == requester.stableId && !it.deleted }
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Horse not found")

    private companion object {
        const val MAX_NOTES = 50
        const val MAX_TEXT = 1000
    }
}
