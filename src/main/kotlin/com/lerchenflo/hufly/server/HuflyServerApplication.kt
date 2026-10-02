package com.lerchenflo.hufly.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableMongoRepositories(basePackages = ["com.lerchenflo.hufly.server.repository"])
@EnableScheduling
class HuflyServerApplication

fun main(args: Array<String>) {
    runApplication<HuflyServerApplication>(*args)
}
