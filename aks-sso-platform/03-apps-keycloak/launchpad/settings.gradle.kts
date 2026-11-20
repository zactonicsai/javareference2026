plugins {
    // Lets Gradle download a JDK 25 toolchain when the machine has none (not needed inside the Docker build)
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "launchpad"
