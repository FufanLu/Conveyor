// tracking-svc: the courier firehose. Handhelds post batches of positions and scans; ingest
// only appends them to Kafka (tracking.events, keyed by shipment). A persister writes them to
// DynamoDB (idempotent per (shipment, seq)), and every instance's live consumer pushes them to
// browsers over SSE. Shipments are created from the order events (OrderPlaced, then CONFIRMED).
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation(project(":libs:proto"))
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation(platform(libs.aws.sdk.bom))
    implementation("software.amazon.awssdk:dynamodb")
    implementation("software.amazon.awssdk:netty-nio-client")

    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation("org.awaitility:awaitility")
}
