# syntax=docker/dockerfile:1.7
# Multi-stage: build with the Gradle wrapper on a JDK 25 image, run on a JRE 25 image as a non-root user.
# Build locally:   docker build -t launchpad:dev .
# Build in Azure:  ../scripts/build-image.sh   (az acr build — no local Docker/JDK required)

FROM eclipse-temurin:25-jdk AS build
WORKDIR /workspace
# Wrapper + build files first so the dependency download layer is cached between source changes
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --no-daemon --version
COPY src ./src
ARG APP_VERSION=1.0.0
RUN ./gradlew --no-daemon -PappVersion=${APP_VERSION} clean bootJar -x test \
 && java -Djarmode=tools -jar build/libs/launchpad.jar extract --layers --destination extracted

FROM eclipse-temurin:25-jre
LABEL org.opencontainers.image.title="launchpad" \
      org.opencontainers.image.description="Apps Team Launchpad (Spring Boot, Keycloak SSO)" \
      org.opencontainers.image.source="aks-sso-platform/03-apps-keycloak/launchpad"
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseZGC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom" \
    SERVER_PORT=8080 \
    MANAGEMENT_SERVER_PORT=8081
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --home /app --shell /usr/sbin/nologin app \
 && mkdir -p /app /tmp && chown -R app:app /app
WORKDIR /app
# Layered copy: dependencies change rarely, application code often
COPY --from=build --chown=app:app /workspace/extracted/dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/extracted/application/ ./
USER 10001:10001
EXPOSE 8080 8081
ENTRYPOINT ["java", "-jar", "launchpad.jar"]
