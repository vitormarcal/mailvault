# Etapa 1: build da aplicacao
FROM eclipse-temurin:24-jdk-alpine AS builder

WORKDIR /app/backend

# Copia os arquivos de build primeiro para aproveitar cache
COPY ./backend/gradlew ./gradlew
COPY ./backend/gradle ./gradle
COPY ./backend/settings.gradle.kts ./settings.gradle.kts
COPY ./backend/build.gradle.kts ./build.gradle.kts

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon dependencies || true

# Copia o codigo fonte e gera o jar
COPY ./backend/src ./src
RUN ./gradlew --no-daemon clean bootJar

# Etapa 2: imagem final para runtime
FROM eclipse-temurin:24-jdk-alpine

WORKDIR /app

COPY --from=builder /app/backend/build/libs/*.jar app.jar

VOLUME /config
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar", "--spring.config.additional-location=file:/config/"]
