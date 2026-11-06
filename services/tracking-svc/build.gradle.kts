// tracking-svc: reactive ingest of courier scan/GPS events into DynamoDB, pushed to browsers over SSE. Built out in M5.
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
}
