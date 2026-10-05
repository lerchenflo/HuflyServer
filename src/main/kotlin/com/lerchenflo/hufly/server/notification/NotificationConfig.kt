package com.lerchenflo.hufly.server.notification

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@Configuration
class NotificationConfig {
    @Bean(PUSH_EXECUTOR, destroyMethod = "shutdown")
    fun pushExecutor(): ExecutorService = Executors.newVirtualThreadPerTaskExecutor()
}
