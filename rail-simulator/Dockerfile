# rail-simulator is a test double for an external clearing rail, not one of
# .notes/ARCHITECTURE.md §7.1's real deployment services -- it has no entry
# there and is not counted in the Part 3 comparison. It still needs to run
# somewhere reachable from in-cluster pods for settlement-gateway to dispatch
# against, so it is deployed the same way as a plain, unhardened dependency
# (no PDB, no probes beyond a basic liveness check, one replica).
#   docker build -f rail-simulator/Dockerfile -t payments/rail-simulator:local .
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
COPY rail-simulator/target/rail-simulator-0.1.0-SNAPSHOT-exec.jar app.jar
EXPOSE 8085
ENTRYPOINT ["java", "-jar", "app.jar"]
