// gateway: the only public entry point. Routes by resource (/api/v1/orders/** -> order-svc), so
// clients never see the service topology. Request ids, problem+json errors, upstream timeouts,
// and JWT validation (Keycloak) for every non-read request. Rate limiting and adaptive load
// shedding arrive in M7.
plugins {
    id("conveyor.spring-cloud")
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    // Token buckets for RequestRateLimiter live in Redis, so every gateway instance shares them.
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")

    testImplementation("org.springframework.security:spring-security-test")
    // The waiting room's test runs against a real Redis.
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.awaitility:awaitility")
}
