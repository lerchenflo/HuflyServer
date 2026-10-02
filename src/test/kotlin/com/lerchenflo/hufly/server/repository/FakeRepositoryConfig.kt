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
    fun fakeTaskRepository() = FakeTaskRepository()

    @Bean @Primary
    fun fakeVersionCounterStore() = com.lerchenflo.hufly.server.core.sync.FakeVersionCounterStore()
}
