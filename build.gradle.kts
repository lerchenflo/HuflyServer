plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.spring") version "2.4.20"
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.lerchenflo.hufly"
version = "0.0.1-SNAPSHOT"
description = "Hufly server"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-data-mongodb")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
    implementation("tools.jackson.module:jackson-module-kotlin")

    // Push notifications: FCM for Android, APNs for iOS (like SchneaggchatV3server)
    implementation("com.google.firebase:firebase-admin:9.9.0")
    implementation("com.eatthepath:pushy:0.15.6")

    implementation("io.jsonwebtoken:jjwt-api:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.13.0")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.13.0")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-mongodb")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Docker Desktop on macOS without the /var/run/docker.sock symlink; Mongo integration tests skip without Docker.
    val desktopSocket = File(System.getProperty("user.home"), ".docker/run/docker.sock")
    if (System.getenv("DOCKER_HOST") == null && desktopSocket.exists()) {
        environment("DOCKER_HOST", "unix://${desktopSocket.absolutePath}")
    }
}
