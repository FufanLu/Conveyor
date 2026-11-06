// fulfillment-svc: Temporal workers for the order saga and the delivery promise. Built out in M4.
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
