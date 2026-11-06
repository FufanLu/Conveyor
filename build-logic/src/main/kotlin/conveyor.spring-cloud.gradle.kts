// Adds the Spring Cloud release train BOM on top of a Spring Boot service.
plugins {
    id("conveyor.spring-service")
}

val catalog = the<VersionCatalogsExtension>().named("libs")

dependencies {
    implementation(platform(catalog.findLibrary("spring-cloud-dependencies").get()))
}
