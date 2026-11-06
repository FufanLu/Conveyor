// gateway: the only public entry point. Routes by resource (/api/v1/orders/** -> order-svc), so
// clients never see the service topology. Request ids, timeouts now; JWT (Keycloak), rate
// limiting and adaptive load shedding arrive in M1 and M7.
plugins {
    id("conveyor.spring-cloud")
}

dependencies {
    implementation("org.springframework.cloud:spring-cloud-starter-gateway-server-webflux")
}
