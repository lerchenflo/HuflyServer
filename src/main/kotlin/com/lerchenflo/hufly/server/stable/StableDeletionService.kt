package com.lerchenflo.hufly.server.stable

import com.lerchenflo.hufly.server.core.picture.PictureKind
import com.lerchenflo.hufly.server.core.picture.PictureStore
import com.lerchenflo.hufly.server.repository.*
import org.bson.types.ObjectId
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Removes a stable for good (operator website): its users with their sessions, settings, held-back pushes and
 * pictures, then every collection's rows of the stable. Hard deletes, so no sync hints; the apps get 401.
 * The stable row goes last, so a run that fails halfway can simply be repeated.
 */
@Service
class StableDeletionService(
    private val stableRepository: StableRepository,
    private val userRepository: UserRepository,
    private val refreshTokenRepository: RefreshTokenRepository,
    private val userSettingsRepository: UserSettingsRepository,
    private val digestItemRepository: DigestItemRepository,
    private val horseRepository: HorseRepository,
    private val tagRepository: TagRepository,
    private val foodPlanRepository: FoodPlanRepository,
    private val horseLogRepository: HorseLogRepository,
    private val paddockRepository: PaddockRepository,
    private val horseGroupRepository: HorseGroupRepository,
    private val horseConflictRepository: HorseConflictRepository,
    private val paddockAssignmentRepository: PaddockAssignmentRepository,
    private val eventRepository: EventRepository,
    private val eventInvitationRepository: EventInvitationRepository,
    private val eventOccurrenceRepository: EventOccurrenceRepository,
    private val eventOccurrenceAnswerRepository: EventOccurrenceAnswerRepository,
    private val taskRepository: TaskRepository,
    private val taskOccurrenceRepository: TaskOccurrenceRepository,
    private val noteRepository: NoteRepository,
    private val absenceRepository: AbsenceRepository,
    private val pictureStore: PictureStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** [confirmName] must repeat the stable's name exactly, against deleting the wrong stable. */
    fun deleteStable(stableId: ObjectId, confirmName: String) {
        val stable = stableRepository.findById(stableId) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Stable not found")
        if (confirmName != stable.name) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Name does not match the stable")

        val userIds = userRepository.findByStableId(stableId).map { it.id }
        refreshTokenRepository.deleteByUserIdIn(userIds)
        userSettingsRepository.deleteByUserIdIn(userIds)
        digestItemRepository.deleteByUserIdIn(userIds)
        userIds.forEach { pictureStore.delete(PictureKind.USER, it) }
        horseRepository.findByStableId(stableId).forEach { pictureStore.delete(PictureKind.HORSE, it.id) }

        listOf(
            userRepository::deleteByStableId, horseRepository::deleteByStableId, tagRepository::deleteByStableId,
            foodPlanRepository::deleteByStableId, horseLogRepository::deleteByStableId, paddockRepository::deleteByStableId,
            horseGroupRepository::deleteByStableId, horseConflictRepository::deleteByStableId,
            paddockAssignmentRepository::deleteByStableId, eventRepository::deleteByStableId,
            eventInvitationRepository::deleteByStableId, eventOccurrenceRepository::deleteByStableId,
            eventOccurrenceAnswerRepository::deleteByStableId, taskRepository::deleteByStableId,
            taskOccurrenceRepository::deleteByStableId, noteRepository::deleteByStableId, absenceRepository::deleteByStableId,
        ).forEach { it(stableId) }
        stableRepository.deleteById(stableId)
        log.info("Stable {} \"{}\" deleted with {} users", stableId, stable.name, userIds.size)
    }
}
