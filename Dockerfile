# syntax=docker/dockerfile:1.7
# One Dockerfile for every service: `docker build --build-arg SERVICE=gateway-service .`
# Stages: (1) dashboard build, (2) Maven build of the whole reactor, (3) slim JRE runtime.

# ---- 1. dashboard (TypeScript/React -> static assets served by the gateway) ----
FROM node:22-alpine AS dashboard
WORKDIR /dash
COPY dashboard/package*.json ./
RUN npm ci --no-audit --no-fund
COPY dashboard/ ./
# vite.config.ts writes into ../gateway-service/src/main/resources/static
RUN mkdir -p /gateway-service/src/main/resources && npm run build

# ---- 2. Java build (all modules, tests run in CI not in the image build) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
COPY contracts/pom.xml contracts/
COPY astro-core/pom.xml astro-core/
COPY ingest-service/pom.xml ingest-service/
COPY propagation-service/pom.xml propagation-service/
COPY analytics-service/pom.xml analytics-service/
COPY gateway-service/pom.xml gateway-service/
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -e dependency:go-offline -DskipTests || true
COPY . .
COPY --from=dashboard /gateway-service/src/main/resources/static gateway-service/src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 mvn -q -B -DskipTests package

# ---- 3. runtime ----
FROM eclipse-temurin:21-jre-alpine AS runtime
ARG SERVICE
ENV SERVICE=${SERVICE} \
    JAVA_OPTS="-XX:MaxRAMPercentage=65 -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom"
# libstdc++: Kafka Streams' RocksDB state store is a native library linked against it; without it the
# analytics Streams client dies on first state-store access (UnsatisfiedLinkError, seen 2026-09-10).
# /data/cache (ingest) and /data/streams (analytics) are volume mount points: create them owned by
# the runtime user so a fresh (or still-empty) named volume inherits that ownership instead of root's.
RUN apk add --no-cache libstdc++ \
 && addgroup -S app && adduser -S app -G app \
 && mkdir -p /data/cache /data/streams && chown -R app:app /data
WORKDIR /app
COPY --from=build /src/${SERVICE}/target/${SERVICE}-*.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD wget -qO- http://127.0.0.1:${PORT:-8080}/actuator/health/readiness | grep -q '"UP"' || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
