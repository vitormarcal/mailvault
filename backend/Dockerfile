# Stage 1: Build the application
FROM amazoncorretto:24-alpine AS builder

WORKDIR /app/backend

# Copy Gradle files + wrapper to cache dependencies
COPY ./backend/gradle ./gradle
COPY ./backend/gradlew ./gradlew
COPY ./backend/settings.gradle.kts ./settings.gradle.kts
COPY ./backend/build.gradle.kts ./build.gradle.kts

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon build -x test || true

# Copy source code
COPY ./backend/src ./src

RUN ./gradlew --no-daemon clean build -PskipIntegrationTests=true

# Stage 2: Final Corretto image (without build toolchain)
FROM amazoncorretto:24-alpine

WORKDIR /app

# Copy only the final JAR
COPY --from=builder /app/backend/build/libs/*.jar server.jar

# Application settings
# Access credentials are created on first access to / (initial setup).
VOLUME /config
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "server.jar", "--spring.config.additional-location=file:/config/"]
