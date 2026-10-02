FROM eclipse-temurin:25-jdk-alpine AS build
WORKDIR /app

COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon

COPY src src
RUN ./gradlew clean bootJar --no-daemon


FROM eclipse-temurin:25-jre-alpine
ARG TZ=Europe/Vienna
WORKDIR /app

RUN apk add --no-cache tzdata && \
    ln -sf /usr/share/zoneinfo/$TZ /etc/localtime && \
    echo "$TZ" > /etc/timezone

COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
