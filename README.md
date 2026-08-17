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
- `settlement-gateway` (from Phase 6): Spring Boot 3.2 web+actuator+data-jpa, spring-kafka, Flyway, JAXB — dispatches `pacs.008` and correlates `pacs.002`/`pacs.004`

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

`docker-compose up -d postgres redpanda`, then `docker-compose up redpanda-topics` (one-shot: creates `payments.received`, `payments.validated`, `payments.enriched`, `payments.routed`, `payments.sent`, `payments.settled`, `payments.exceptions` and `payments.dlq` with their configured partition counts and retention; exits once done), then `mvn -pl intake-service spring-boot:run`, `mvn -pl processing-service spring-boot:run` and `mvn -pl settlement-gateway spring-boot:run`. Redpanda Console is at `localhost:8090` for browsing topics.

`rail-simulator` runs independently of the above — it needs neither Postgres, Redpanda nor `core` — via `mvn -pl rail-simulator spring-boot:run` (port 8085 by default). `POST /rail/{railId}/scenario` (`railId` one of `fedwire`, `sepa`, `ach_equiv`) loads a scenario YAML at runtime; `GET /rail/{railId}/received` lists what it's recorded. See `.notes/reports/PHASE-5-REPORT.md` for the full API and scenario-file shape. `settlement-gateway` dispatches to whatever base URL each rail is configured with (`payments.gateway.rail-base-urls.*`, `rail-simulator`'s address by default) and exposes `POST /callbacks/rail/{railId}/status` and `/return` for the rail to call back on. See `.notes/reports/PHASE-6-REPORT.md`.

### Known fragility: `rail-simulator`'s DROP scenario and the embedded Tomcat version

`ConnectionDropper` (`rail-simulator/src/main/java/.../dispatch/ConnectionDropper.java`) forces a true connection reset with no response, using reflection into `org.apache.catalina.connector.RequestFacade`'s internals — there is no supported Servlet-API way to do this. **Verified working against `tomcat-embed-core` 10.1.20**, the version pulled in transitively by this repo's pinned Spring Boot 3.2.5. The application fails fast at startup (`ConnectionDropper.verifyDropIsSupported`, an `@PostConstruct` check) if that internal shape isn't present, naming the Tomcat version it actually found — so a version bump that breaks this shows up as `rail-simulator` refusing to start, not as a DROP scenario silently degrading into a slow TIMEOUT. If `rail-simulator` won't start after a Spring Boot upgrade, check that message first.

### Note: `rail-simulator`'s packaged jar and why it has a classifier

`rail-simulator/pom.xml`'s `spring-boot-maven-plugin` repackage execution uses `<classifier>exec</classifier>`. Without it, repackaging overwrites the plain jar with the executable `BOOT-INF/classes/...` layout, which `settlement-gateway` (a test-scope dependency on `rail-simulator`, so its integration tests can dispatch against a real instance) cannot compile against. This only breaks under a full `mvn clean verify` — a partial `-pl settlement-gateway -am` build never runs `rail-simulator`'s own `package` phase, so it's easy to not notice locally. If a future module adds a similar test-scope dependency on `intake-service` or `processing-service`, it will need the same fix.

## Status

Phase 7 of 13 — ambiguity resolution. `AmbiguityResolver` is the scheduled job (`settlement-gateway`, every 2 minutes by default) that actually moves a `SENT_UNCONFIRMED` instruction: it claims candidates with a two-key Postgres advisory lock (safe across multiple gateway replicas), queries the rail's own opinion via `RailStatusClient`, and applies the branch table from `.notes/ARCHITECTURE.md` §6.4 — a known outcome resolves straight to `SENT`, `SETTLED`, or `EXCEPTION`; an `UNKNOWN` only redispatches (same UETR, incrementing `attempt_no`, back through the existing dispatch path with no special-casing) after **two consecutive** observations, never one; a redispatch cap, an inconclusive-query window, or a `dispatch_record` stuck at `PENDING` past a threshold all route to `INVESTIGATION`, the new terminal-for-automation state with no automated exit. Every counter is durable (`core.reconciliation_state`, one row per instruction, not in-memory) so replicas and restarts see the same state. The double-payment trial — 1,000 ambiguous dispatches driven through the real rail-simulator, mixed on `recordBeforeTimeout` — reaches `SETTLED` or `INVESTIGATION` for every instruction with zero double deliveries; the trial itself caught two real concurrency bugs during development (a candidate-starvation ordering bug and a transaction-rollback bug), both fixed and detailed in `.notes/reports/PHASE-7-REPORT.md`. See that report for the full account, the resolution-distribution table, and the judgment calls (why two observations not one or three, the redispatch cap, the grace period).
