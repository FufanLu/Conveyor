// Cross-cutting platform conventions shared by every service:
//   - X-Request-Id: accept a sane caller id or mint a UUIDv7, put it in the MDC, echo it back
//   - one access-log line per request (logger "conveyor.access")
//   - RFC 9457 problem+json errors that carry the request id (servlet services)
//   - shared actuator / metrics / logging defaults (conveyor-platform.yaml)
// Test fixtures hold the service contract test every service runs against itself.
plugins {
    id("conveyor.java-conventions")
    `java-test-fixtures`
}

dependencies {
    api("org.springframework.boot:spring-boot-autoconfigure")
    api("org.slf4j:slf4j-api")
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("org.springframework:spring-webflux")
    compileOnly("jakarta.servlet:jakarta.servlet-api")

    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework:spring-webmvc")
    testImplementation("org.springframework:spring-webflux")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("io.projectreactor:reactor-test")

    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
}
