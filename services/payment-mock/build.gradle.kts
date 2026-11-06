// payment-mock: payment authorizer with injectable failures and latency, to exercise saga compensation. Built out in M4.
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
