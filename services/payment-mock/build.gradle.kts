// payment-mock: a stand-in payment authorizer with deterministic triggers and runtime fault
// injection (declines, errors, latency, asynchronous "pending" outcomes with a callback), so the
// fulfillment saga's compensation paths can be exercised on demand.
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
