# Build locally hosted CSS; no CDN or Node runtime in the final image.
FROM node:24-alpine AS ui
WORKDIR /build
COPY package.json ./
RUN npm install --no-audit --no-fund
COPY frontend ./frontend
COPY src/main/resources/static ./src/main/resources/static
RUN npm run build:css

# Maven compiles for Java 25 and executes the security tests.
FROM maven:3.9.11-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
COPY --from=ui /build/src/main/resources/static/assets/app.css ./src/main/resources/static/assets/app.css
RUN mvn --batch-mode --no-transfer-progress clean verify

FROM eclipse-temurin:25-jre-alpine
RUN addgroup -S app && adduser -S -G app app
WORKDIR /app
COPY --from=build --chown=app:app /build/target/secure-groups-1.0.0.jar /app/app.jar
USER app:app
ENV SERVER_ADDRESS=0.0.0.0
EXPOSE 8080 8443
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
