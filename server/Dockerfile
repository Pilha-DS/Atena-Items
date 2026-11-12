# Build
FROM gradle:8.13-jdk21 AS build

WORKDIR /src
COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY gradlew ./
COPY src ./src

RUN chmod +x gradlew && ./gradlew --no-daemon installDist

# Runtime
FROM eclipse-temurin:21-jre

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --uid 10001 jotter

WORKDIR /app
COPY --from=build /src/build/install/jotter-server/ ./

RUN mkdir -p /data && chown -R jotter:jotter /data
USER jotter

ENV JOTTER_DATA_DIR=/data \
    JOTTER_PORT=8080 \
    JOTTER_HOST=0.0.0.0

EXPOSE 8080
VOLUME ["/data"]

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${JOTTER_PORT}/health" || exit 1

ENTRYPOINT ["/app/bin/jotter-server"]
