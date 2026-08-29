# See intake-service/Dockerfile for why this builds from a host-built jar
# rather than a multi-stage in-container Maven build.
#   docker build -f processing-service/Dockerfile -t payments/processing-service:local .
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY processing-service/target/processing-service-0.1.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
