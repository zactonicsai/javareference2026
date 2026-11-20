import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.1.3"
}

group = "com.appsteam"
version = (findProperty("appVersion") as String?) ?: "1.0.0"
description = "Apps Team Launchpad — SSO landing page with group-restricted project pages and service status"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of((findProperty("javaVersion") as String).toInt())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot's BOM through Gradle's native platform support (no dependency-management plugin needed)
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-client")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-micrometer-metrics")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

springBoot {
    buildInfo() // exposes build version/time on /actuator/info and the admin page
}

tasks.bootJar {
    archiveFileName = "launchpad.jar"
}
