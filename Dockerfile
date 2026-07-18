####
# Native runnable image of the portfolio-service application.
# Build stage
####
FROM quay.io/quarkus/ubi-quarkus-graalvmce-builder-image:jdk-21 AS build

WORKDIR /code

COPY gradle/ gradle/
COPY gradlew gradlew.bat build.gradle settings.gradle gradle.properties ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml
COPY lombok.config ./

RUN ./gradlew dependencies --no-daemon

COPY src/ src/

# Tests are skipped here (no Docker daemon for Testcontainers); run `./gradlew check` in CI instead.
# 7g heap / parallelism=2: 3500m OOMed during analysis (Graal needs >4.3GB; peak RSS ~6GB on 16GB Docker).
RUN ./gradlew build -Dquarkus.package.type=native -Dquarkus.native.container-build=false -Dquarkus.native.native-image-xmx=7g -Dquarkus.native.additional-build-args=--parallelism=2 -x test -x integrationTest --no-daemon

####
# Runtime stage - minimal image
####
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6

WORKDIR /app

RUN microdnf install -y curl-minimal \
    && microdnf clean all

COPY --from=build --chown=1001:root /code/build/*-runner ./application

RUN chmod +x ./application && \
    chown 1001:root ./application

USER 1001

EXPOSE $PORT

ENV QUARKUS_PROFILE=prod

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
  CMD curl -f http://localhost:${PORT:-8080}/q/health || exit 1

CMD ["sh", "-c", "./application -Dquarkus.http.host=0.0.0.0 -Dquarkus.http.port=${PORT:-8080}"]
