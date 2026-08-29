# See intake-service/Dockerfile for why this builds from a host-built jar
# rather than a multi-stage in-container Maven build.
#   docker build -f settlement-gateway/Dockerfile -t payments/settlement-gateway:local .
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY settlement-gateway/target/settlement-gateway-0.1.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
