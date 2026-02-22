# Etapa 1: Build da aplicação
FROM amazoncorretto:24-alpine AS builder

WORKDIR /app/backend

# Copie os arquivos do Gradle + wrapper para cache de dependencias
COPY ./backend/gradle ./gradle
COPY ./backend/gradlew ./gradlew
COPY ./backend/settings.gradle.kts ./settings.gradle.kts
COPY ./backend/build.gradle.kts ./build.gradle.kts

RUN chmod +x ./gradlew
RUN ./gradlew --no-daemon build -x test || true

# Copie o código
COPY ./backend/src ./src

RUN ./gradlew --no-daemon clean build -PskipIntegrationTests=true

# Etapa 2: Imagem final com Corretto (sem toolchain de build)
FROM amazoncorretto:24-alpine

WORKDIR /app

# Copie só o jar final
COPY --from=builder /app/backend/build/libs/*.jar server.jar

# Configurações da aplicação
VOLUME /config
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "server.jar", "--spring.config.additional-location=file:/config/"]
