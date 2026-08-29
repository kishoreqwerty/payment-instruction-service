# Built from a host-built jar (mvn package), not a multi-stage in-container
# Maven build -- this project's only other deployment shape (docker-compose)
# already runs every service as a host process, so there was no existing
# containerized build to match; building from the jar keeps kind image
# builds fast and avoids re-resolving the whole reactor's dependencies
# inside Docker on every change. Build context is the repo root:
#   docker build -f intake-service/Dockerfile -t payments/intake-service:local .
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY intake-service/target/intake-service-0.1.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8081
ENTRYPOINT ["java", "-jar", "app.jar"]
