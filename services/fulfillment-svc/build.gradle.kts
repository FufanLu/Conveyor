// fulfillment-svc: the order saga on Temporal. OrderPlaced (Kafka) starts one workflow per order
// (workflow id = order id, so a redelivered event cannot start a second one). The workflow
// reserves stock (inventory-svc, gRPC), authorizes payment (payment-mock), commits the stock,
// promises a delivery date and confirms the order (order-svc), compensating on every failure.
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation(project(":libs:proto"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    // RestClient.Builder bean: outbound calls carry the observation registry (traces, metrics)
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("io.grpc:grpc-netty")
    implementation(libs.temporal.sdk)
    implementation(libs.protobuf.java.util)

    testImplementation(libs.temporal.testing)
}
