package com.lerchenflo.hufly.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.data.mongodb.repository.config.EnableMongoRepositories

@SpringBootApplication
@EnableMongoRepositories(basePackages = ["com.lerchenflo.hufly.server.repository"])
class HuflyServerApplication

fun main(args: Array<String>) {
    runApplication<HuflyServerApplication>(*args)
}
