// Cross-cutting platform conventions shared by every service:
//   - X-Request-Id: accept a sane caller id or mint a UUIDv7, put it in the MDC, echo it back
//   - one access-log line per request (logger "conveyor.access")
//   - RFC 9457 problem+json errors that carry the request id (servlet services)
//   - shared actuator / metrics / logging defaults (conveyor-platform-defaults.yaml)
//   - security pieces for OAuth2 resource servers: Keycloak role mapping, 401/403 as problem+json
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
    compileOnly("org.springframework.security:spring-security-web")
    compileOnly("org.springframework.security:spring-security-config")
    compileOnly("org.springframework.security:spring-security-oauth2-resource-server")
    compileOnly("org.springframework.security:spring-security-oauth2-jose")

    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework:spring-webmvc")
    testImplementation("org.springframework:spring-webflux")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.springframework.security:spring-security-oauth2-resource-server")
    testImplementation("org.springframework.security:spring-security-oauth2-jose")

    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    // TestJwts: mint RS256 tokens and a matching decoder, so services test auth over real HTTP
    // without a running Keycloak.
    testFixturesApi("org.springframework.security:spring-security-oauth2-jose")
}
