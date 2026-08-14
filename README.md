# payment-instruction-service

A payment instruction processing and exception handling service. It accepts ISO 20022 customer credit transfer initiations (`pain.001`), carries each instruction through validation, enrichment and routing, dispatches it to a clearing rail as `pacs.008`, correlates the returned `pacs.002`, and manages every instruction that fails through a structured repair and resubmission workflow. The interesting half is the exception path, not the happy path.

## Technology

- Java 21
- Maven multi-module
- Spring Boot 3.2 (services, from Phase 2 onward)
- PostgreSQL 16, Flyway
- Kafka API — Redpanda locally (from Phase 3 onward)
- JUnit 5, AssertJ, Testcontainers
- React 18 + TypeScript + Vite (dashboard, from Phase 9)

## Running the tests

Requires a JDK 21 and a local Docker daemon (Testcontainers starts real PostgreSQL 16 containers for the migration tests).

```
mvn clean verify
```

## Status

Phase 1 of 13 — domain core and schema
