// order-svc: order API on sharded Postgres with a transactional outbox (Debezium -> Kafka). Built out in M2.
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
