// inventory-svc: multi-FC stock, bucketed Redis reservations, Postgres ledger, gRPC API. Built out in M3.
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
