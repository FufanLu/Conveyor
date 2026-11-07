# One image per service, from its Spring Boot jar (./gradlew :services:<name>:bootJar first):
#   docker build -f deploy/docker/Dockerfile --build-arg SERVICE=order-svc -t conveyor/order-svc:dev .
# The jar is split into layers (dependencies change rarely, the application often), so a code
# change rebuilds and pushes one small layer, not 80 MB.
FROM eclipse-temurin:25-jre AS layers
ARG SERVICE
WORKDIR /work
COPY services/${SERVICE}/build/libs/${SERVICE}.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination extracted

FROM eclipse-temurin:25-jre
ARG SERVICE
LABEL org.opencontainers.image.source="https://github.com/FufanLu/Conveyor" \
      org.opencontainers.image.title="conveyor-${SERVICE}"
WORKDIR /app
COPY --from=layers /work/extracted/dependencies/ ./
COPY --from=layers /work/extracted/spring-boot-loader/ ./
COPY --from=layers /work/extracted/snapshot-dependencies/ ./
COPY --from=layers /work/extracted/application/ ./
USER 1000:1000
# Heap from the container's memory limit; the rest is metaspace, threads, direct buffers.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError"
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
