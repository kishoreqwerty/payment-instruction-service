# payment-instruction-service

A payment instruction processing and exception handling service. It accepts ISO 20022 customer credit transfer initiations (`pain.001`), carries each instruction through validation, enrichment and routing, dispatches it to a clearing rail as `pacs.008`, correlates the returned `pacs.002`, and manages every instruction that fails through a structured repair and resubmission workflow. The interesting half is the exception path, not the happy path.

## Technology

- Java 21
- Maven multi-module
- Spring Boot 3.2 (`intake-service`, from Phase 2)
- PostgreSQL 16, Flyway
- Kafka API — Redpanda locally (from Phase 3)
- JUnit 5, AssertJ, Testcontainers
- React 18 + TypeScript + Vite (dashboard, from Phase 9)

## Running the tests

Requires a JDK 21 and a local Docker daemon (Testcontainers starts real PostgreSQL 16 and Redpanda containers for the migration, outbox and integration tests).

```
mvn clean verify
```

## Running locally

`docker-compose up -d postgres redpanda`, then `docker-compose up redpanda-topics` (one-shot: creates `payments.received` and `payments.dlq` with their configured partition counts and retention; exits once done), then `mvn -pl intake-service spring-boot:run`. Redpanda Console is at `localhost:8090` for browsing topics.

## Status

Phase 3 of 13 — outbox and event stream. Every instruction `intake-service` writes now also writes a `core.outbox` row in the same transaction; a shared `OutboxPublisher` in `core` polls with `SKIP LOCKED`, produces to Redpanda (`payments.received`, 12 partitions, keyed by `instructionId`) strictly in `outbox_id` order, and marks rows published. Duplication under a crash is expected and proven by test; loss is not. `processing-service` and any business consumer are not built yet — nothing consumes `payments.received` except a test consumer. See `.notes/reports/PHASE-3-REPORT.md`.
