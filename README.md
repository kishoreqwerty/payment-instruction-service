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
- `rail-simulator` (from Phase 5): Spring Boot 3.2 web+actuator, JAXB, SnakeYAML — a standalone rail stand-in, no dependency on `core` or any other module

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

`rail-simulator` runs independently of the above — it needs neither Postgres, Redpanda nor `core` — via `mvn -pl rail-simulator spring-boot:run` (port 8085 by default). `POST /rail/{railId}/scenario` (`railId` one of `fedwire`, `sepa`, `ach_equiv`) loads a scenario YAML at runtime; `GET /rail/{railId}/received` lists what it's recorded. See `.notes/reports/PHASE-5-REPORT.md` for the full API and scenario-file shape.

### Known fragility: `rail-simulator`'s DROP scenario and the embedded Tomcat version

`ConnectionDropper` (`rail-simulator/src/main/java/.../dispatch/ConnectionDropper.java`) forces a true connection reset with no response, using reflection into `org.apache.catalina.connector.RequestFacade`'s internals — there is no supported Servlet-API way to do this. **Verified working against `tomcat-embed-core` 10.1.20**, the version pulled in transitively by this repo's pinned Spring Boot 3.2.5. The application fails fast at startup (`ConnectionDropper.verifyDropIsSupported`, an `@PostConstruct` check) if that internal shape isn't present, naming the Tomcat version it actually found — so a version bump that breaks this shows up as `rail-simulator` refusing to start, not as a DROP scenario silently degrading into a slow TIMEOUT. If `rail-simulator` won't start after a Spring Boot upgrade, check that message first.

## Status

Phase 5 of 13 — rail simulator. `rail-simulator` is a controllable, standalone stand-in for Fedwire, SEPA and an ACH-equivalent clearing rail: `POST /rail/{railId}/payments` accepts a pacs.008 (202/400), `GET /rail/{railId}/payments/{uetr}` answers the KNOWN/UNKNOWN dispatch-ambiguity question from `.notes/ARCHITECTURE.md` §6.4, and confirmations/returns (pacs.002/pacs.004) are delivered asynchronously to a configured callback URL. Behavior is entirely scenario-driven YAML — matchers on amount, currency, creditor account, debtor agent BIC, a per-rail request counter and a catch-all, resolving to accept/reject/timeout/drop plus a confirmation outcome — loadable and fully resettable at runtime, with three independently-stateful rails sharing the same code. No dependency on `core`, no database; state is in memory and a restart is a legitimate reset. `processing-service` is unchanged from Phase 4. `settlement-gateway` (Phase 6) and everything past routing/dispatch are not built yet. See `.notes/reports/PHASE-5-REPORT.md`.
