package com.lerchenflo.hufly.server.core

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ClockConfig {
    @Bean
    fun clock(): Clock = Clock { System.currentTimeMillis() }
}
