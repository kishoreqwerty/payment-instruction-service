# payment-instruction-service

A payment instruction processing and exception handling service. It accepts ISO 20022 customer credit transfer initiations (`pain.001`), carries each instruction through validation, enrichment and routing, dispatches it to a clearing rail as `pacs.008`, correlates the returned `pacs.002`, and manages every instruction that fails through a structured repair and resubmission workflow. The interesting half is the exception path, not the happy path.

## Technology

- Java 21
- Maven multi-module
- Spring Boot 3.2 (`intake-service`, from Phase 2; `processing-service`, from Phase 4)
- PostgreSQL 16, Flyway
- Kafka API — Redpanda locally (from Phase 3); `spring-kafka` (from Phase 4)
- Caffeine (reference-data cache, from Phase 4)
- JUnit 5, AssertJ, Testcontainers
- React 18 + TypeScript + Vite (dashboard, from Phase 9)

## Running the tests

Requires a JDK 21 and a local Docker daemon (Testcontainers starts real PostgreSQL 16 and Redpanda containers for the migration, outbox and integration tests).

Start from a clean container state before relying on a result, especially before trusting a red run as a real regression rather than environment noise — a long-lived Testcontainers pair left over from an earlier session can degrade (timeouts, multi-minute waits on operations that normally take milliseconds) in ways that look exactly like a flaky or failing test:

```
docker compose down -v
docker ps -a --filter "label=org.testcontainers=true" -q | xargs -r docker rm -f
mvn clean verify
```

The first line tears down this repo's own `docker-compose.yml` stack, if it happens to be running locally. The second removes every Testcontainers-managed container regardless of age or which project started it — the normal case is that Testcontainers' own Ryuk reaper already cleaned these up when its owning JVM exited, so this is a no-op; it only matters when something has kept them alive longer than a single test run, which is exactly the condition that produced a degraded (not failed) run during Phase 4 — see `.notes/reports/PHASE-4-REPORT.md` §4.

## Running locally

`docker-compose up -d postgres redpanda`, then `docker-compose up redpanda-topics` (one-shot: creates `payments.received`, `payments.validated`, `payments.enriched`, `payments.routed`, `payments.exceptions` and `payments.dlq` with their configured partition counts and retention; exits once done), then `mvn -pl intake-service spring-boot:run` and `mvn -pl processing-service spring-boot:run`. Redpanda Console is at `localhost:8090` for browsing topics.

## Status

Phase 4 of 13 — processing pipeline. `processing-service` carries an instruction from `RECEIVED` through `VALIDATED`, `ENRICHED`, `ROUTED` (or into `EXCEPTION`) via three Kafka consumers, each idempotent, each writing state + audit event + outbox row in one transaction. Validation collects every rule violation, not just the first; enrichment resolves the correspondent and nostro account, applies rail cutoff and business-day rolling (a missed cutoff or a non-business date is normal operation, never a failure), and screens (no-op provider); routing selects from three fixture rails by currency, amount and urgency. `core.outbox`'s claiming query is now per-aggregate (an advisory lock, not just row-level `SKIP LOCKED`), `core`'s beans are reachable via real Spring Boot auto-configuration, and published outbox rows are pruned past a retention window. `exception-service` (Phase 8) and everything past routing are not built yet — nothing consumes `payments.exceptions` or `payments.routed` except test consumers. See `.notes/reports/PHASE-4-REPORT.md`.
