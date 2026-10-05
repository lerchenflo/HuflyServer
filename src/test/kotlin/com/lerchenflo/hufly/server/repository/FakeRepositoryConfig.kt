package com.lerchenflo.hufly.server.repository

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

/** Replaces the Mongo repositories in Spring tests, since unit test runs have no Mongo instance. */
@TestConfiguration
class FakeRepositoryConfig {
    @Bean @Primary
    fun fakeUserRepository() = FakeUserRepository()

    @Bean @Primary
    fun fakeRefreshTokenRepository() = FakeRefreshTokenRepository()

    @Bean @Primary
    fun fakeStableRepository() = FakeStableRepository()

    @Bean @Primary
    fun fakeTagRepository() = FakeTagRepository()

    @Bean @Primary
    fun fakeHorseRepository() = FakeHorseRepository()

    @Bean @Primary
    fun fakeFoodPlanRepository() = FakeFoodPlanRepository()

    @Bean @Primary
    fun fakeHorseLogRepository() = FakeHorseLogRepository()

    @Bean @Primary
    fun fakePaddockRepository() = FakePaddockRepository()

    @Bean @Primary
    fun fakeHorseGroupRepository() = FakeHorseGroupRepository()

    @Bean @Primary
    fun fakeHorseConflictRepository() = FakeHorseConflictRepository()

    @Bean @Primary
    fun fakePaddockAssignmentRepository() = FakePaddockAssignmentRepository()

    @Bean @Primary
    fun fakeUserSettingsRepository() = FakeUserSettingsRepository()

    @Bean @Primary
    fun fakeEventRepository() = FakeEventRepository()

    @Bean @Primary
    fun fakeEventInvitationRepository() = FakeEventInvitationRepository()

    @Bean @Primary
    fun fakeTaskRepository() = FakeTaskRepository()

    @Bean @Primary
    fun fakeEventOccurrenceRepository() = FakeEventOccurrenceRepository()

    @Bean @Primary
    fun fakeEventOccurrenceAnswerRepository() = FakeEventOccurrenceAnswerRepository()

    @Bean @Primary
    fun fakeTaskOccurrenceRepository() = FakeTaskOccurrenceRepository()

    @Bean @Primary
    fun fakeNoteRepository() = FakeNoteRepository()

    @Bean @Primary
    fun fakeDigestItemRepository() = FakeDigestItemRepository()

    @Bean @Primary
    fun fakeVersionCounterStore() = com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore()

    @Bean @Primary
    fun fakePictureStore() = com.lerchenflo.hufly.server.core.picture.FakePictureStore()
}
