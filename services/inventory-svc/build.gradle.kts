// inventory-svc: sellable stock per fulfillment center (FC). Reservations hold stock for the
// order saga over gRPC. The contended counter lives in Redis (one Lua script per SKU line,
// all of a SKU's FCs in one hash slot); Postgres keeps the durable facts (on-hand stock and
// reservation rows, contention-free inserts); a reconciler repairs drift between the two.
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation(project(":libs:proto"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-grpc-server")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("io.grpc:grpc-inprocess")
    testImplementation(libs.archunit.junit5)
}
