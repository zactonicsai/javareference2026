# syntax=docker/dockerfile:1

# One version for the server image and the provider's compile-time dependencies.
ARG KEYCLOAK_VERSION=26.8.2

# ---------------------------------------------------------------------------
# Stage 1: compile the provider jar (Java code + acme-base theme) with Maven.
# No JDK/Maven needed on the host.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS provider-build
ARG KEYCLOAK_VERSION
WORKDIR /build
COPY provider/pom.xml .
COPY provider/src ./src
# The cache mount keeps ~/.m2 between builds without baking it into a layer.
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -Dkeycloak.version=${KEYCLOAK_VERSION} clean package

# ---------------------------------------------------------------------------
# Stage 2: add the provider and run "kc.sh build" so the provider is registered
# at image build time (faster, reproducible startup; enables "start --optimized").
# ---------------------------------------------------------------------------
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION} AS keycloak-build
# Build-time options. They must match what the container is started with.
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true
COPY --from=provider-build --chown=keycloak:keycloak \
     /build/target/acme-keycloak-provider.jar /opt/keycloak/providers/
RUN /opt/keycloak/bin/kc.sh build

# ---------------------------------------------------------------------------
# Stage 3: final image = stock Keycloak + the pre-built /opt/keycloak.
# Still runs as the unprivileged "keycloak" user of the base image.
# ---------------------------------------------------------------------------
FROM quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}
COPY --from=keycloak-build /opt/keycloak/ /opt/keycloak/
ENV KC_DB=postgres \
    KC_HEALTH_ENABLED=true
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
