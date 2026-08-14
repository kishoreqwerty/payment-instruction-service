# payment-instruction-service

A payment instruction processing and exception handling service. It accepts ISO 20022 customer credit transfer initiations (`pain.001`), carries each instruction through validation, enrichment and routing, dispatches it to a clearing rail as `pacs.008`, correlates the returned `pacs.002`, and manages every instruction that fails through a structured repair and resubmission workflow. The interesting half is the exception path, not the happy path.

## Technology

- Java 21
- Maven multi-module
- Spring Boot 3.2 (`intake-service`, from Phase 2)
- PostgreSQL 16, Flyway
- Kafka API — Redpanda locally (from Phase 3 onward)
- JUnit 5, AssertJ, Testcontainers
- React 18 + TypeScript + Vite (dashboard, from Phase 9)

## Running the tests

Requires a JDK 21 and a local Docker daemon (Testcontainers starts real PostgreSQL 16 containers for the migration and integration tests).

```
mvn clean verify
```

## Running locally

`docker-compose up -d` starts PostgreSQL 16, then `mvn -pl intake-service spring-boot:run`.

## Status

Phase 2 of 13 — intake service. `POST /v1/instructions` validates a `pain.001` against its XSD, persists the raw bytes regardless of outcome, and resolves each submission to a new instruction (202), an identical-content retry (200), or a reference conflict (409) — same `(debtor_account, end_to_end_id)` with different content is rejected, not silently merged or dropped. See `.notes/reports/PHASE-2-REPORT.md`.
