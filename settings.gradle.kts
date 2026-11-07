pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets the build provision the JDK 25 toolchain on machines that do not have one installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "conveyor"

include(
    ":libs:platform",
    ":libs:proto",
    ":services:gateway",
    ":services:catalog-svc",
    ":services:order-svc",
    ":services:inventory-svc",
    ":services:fulfillment-svc",
    ":services:tracking-svc",
    ":services:payment-mock",
    ":deploy:cdk",
)
