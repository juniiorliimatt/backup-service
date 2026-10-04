# syntax=docker/dockerfile:1

# Versão de Java fixada e única em todo o monorepo (workbox-api, budget-service, notes-service,
# forza-telemetry-service e esta imagem) — ver java.toolchain.languageVersion em build.gradle.
# Mude em todos juntos se atualizar.
ARG JAVA_VERSION=25

FROM eclipse-temurin:${JAVA_VERSION}-jdk-alpine AS build
WORKDIR /app
COPY gradlew build.gradle settings.gradle ./
COPY gradle gradle
# Cache mount evita rebaixar a distribuição do Gradle e re-resolver as dependências a cada build.
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon dependencies || true
COPY . .
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon bootJar -x test

FROM eclipse-temurin:${JAVA_VERSION}-jre-alpine
WORKDIR /app
# pg_dump 18 = mesma versão major do servidor (postgres:18): pg_dump mais antigo que o servidor recusa o dump.
# openssl cifra o arquivo (AES-256-CBC + PBKDF2) quando o admin informa uma senha na tela.
RUN apk add --no-cache postgresql18-client openssl \
    && addgroup -S app && adduser -S app -G app \
    && mkdir -p /backups && chown app:app /backups
COPY --from=build /app/build/libs/*.jar app.jar
USER app
# 8084 = API REST. /backups é a pasta dos arquivos (o compose liga ./backups do host aqui).
EXPOSE 8084
VOLUME /backups
ENTRYPOINT ["java", "-jar", "app.jar"]
