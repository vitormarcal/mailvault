# Etapa 1: Build da aplicação
FROM gradle:9.3.1-jdk25-alpine AS builder

WORKDIR /app/backend

# Copie apenas os arquivos do Gradle (sem wrapper)
COPY ./backend/gradle ./gradle
COPY ./backend/settings.gradle.kts ./settings.gradle.kts
COPY ./backend/build.gradle.kts ./build.gradle.kts

RUN gradle --no-daemon build -x test || true

# Copie o código
COPY ./backend/src ./src

RUN gradle --no-daemon clean build -PskipIntegrationTests=true

# Etapa 2: Imagem final com Corretto (sem toolchain de build)
FROM amazoncorretto:25-alpine

WORKDIR /app

# Copie só o jar final
COPY --from=builder /app/backend/build/libs/*.jar server.jar

# Configurações da aplicação
VOLUME /config
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "server.jar", "--spring.config.additional-location=file:/config/"]
