import org.springframework.boot.gradle.tasks.bundling.BootJar

// A deployable Spring Boot service. Every service gets the platform library (request ids,
// access log, RFC 9457 errors, shared actuator/metrics defaults) and the service contract tests.
plugins {
    id("conveyor.java-conventions")
    id("org.springframework.boot")
}

dependencies {
    implementation(project(":libs:platform"))
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-micrometer-metrics")
    // Traces: Micrometer Observation -> OpenTelemetry -> OTLP (Jaeger locally, X-Ray/ADOT in AWS).
    implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(testFixtures(project(":libs:platform")))
}

springBoot {
    buildInfo {
        // Keep build-info.properties reproducible so the build cache keeps working.
        excludes = setOf("time")
    }
}

tasks.named<BootJar>("bootJar") {
    archiveFileName = "${project.name}.jar"
}

tasks.named<Jar>("jar") {
    enabled = false
}
