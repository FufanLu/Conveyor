// Root project: no code of its own. Shared build logic lives in build-logic/ as convention plugins:
//   conveyor.java-conventions  - JDK 25 toolchain, javac -Werror, Error Prone, Spotless, JaCoCo, JUnit
//   conveyor.spring-service    - a deployable Spring Boot service on top of the Java conventions
//   conveyor.spring-cloud      - adds the Spring Cloud BOM (only the gateway needs it today)

plugins {
    base
}
