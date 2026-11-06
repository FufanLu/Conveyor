// catalog-svc: products by SKU through a two-level cache (Caffeine + Redis) with a Bloom filter
// against penetration, Postgres as the system of record, OpenSearch for search and facets,
// writes guarded by Keycloak roles and HTTP preconditions (ETag / If-Match).
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("com.github.ben-manes.caffeine:caffeine")
    implementation(libs.opensearch.java)
    implementation("org.apache.httpcomponents.client5:httpclient5")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation(libs.archunit.junit5)
}
