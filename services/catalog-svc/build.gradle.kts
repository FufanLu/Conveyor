// catalog-svc: product catalog and search. Read-heavy: two-level cache (Caffeine + Redis), OpenSearch. Built out in M1.
plugins {
    id("conveyor.spring-service")
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
}
