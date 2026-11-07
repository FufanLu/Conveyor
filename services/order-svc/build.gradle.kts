// order-svc: places orders idempotently on 16 logical shards over two Postgres databases, with a
// transactional outbox (Debezium -> Kafka orders.events) and an OpenSearch read model for
// operations queries (CQRS).
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation(project(":libs:proto"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // RestClient.Builder bean: outbound calls carry the observation registry (traces, metrics)
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.flywaydb:flyway-core")
    implementation(libs.opensearch.java)
    implementation(libs.resilience4j.circuitbreaker)
    implementation(libs.resilience4j.micrometer)
    implementation("org.apache.httpcomponents.client5:httpclient5")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation(libs.archunit.junit5)
}
