# See intake-service/Dockerfile for why this builds from a host-built jar
# rather than a multi-stage in-container Maven build.
#   docker build -f exception-service/Dockerfile -t payments/exception-service:local .
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY exception-service/target/exception-service-0.1.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8084
ENTRYPOINT ["java", "-jar", "app.jar"]
