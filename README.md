# payment-instruction-service

[![Build](https://github.com/kishoreqwerty/payment-instruction-service/actions/workflows/build.yml/badge.svg)](https://github.com/kishoreqwerty/payment-instruction-service/actions/workflows/build.yml)

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
- `exception-service` (from Phase 8): Spring Boot 3.2 web+actuator+data-jpa+security, spring-kafka, Flyway — owns the exception case lifecycle and the maker-checker repair workflow; HTTP Basic with in-memory users, a deliberate simplification documented in its own security config
- `ops-dashboard` (from Phase 9): React 18 + TypeScript + Vite — a separate `npm` project, not part of the Maven reactor. TanStack Query for server state, React Router, hand-written CSS (no component library, no client-side state library). The operator's client against `exception-service`'s API; nine small backend additions this phase found and fixed are documented in `.notes/reports/PHASE-9-REPORT.md`

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

`docker-compose up -d postgres redpanda`, then `docker-compose up redpanda-topics` (one-shot: creates `payments.received`, `payments.validated`, `payments.enriched`, `payments.routed`, `payments.sent`, `payments.settled`, `payments.exceptions`, `payments.repaired` and `payments.dlq` with their configured partition counts and retention; exits once done), then `mvn -pl intake-service spring-boot:run`, `mvn -pl processing-service spring-boot:run`, `mvn -pl settlement-gateway spring-boot:run` and `mvn -pl exception-service spring-boot:run`. Redpanda Console is at `localhost:8090` for browsing topics.

`exception-service` (port 8084) exposes two maker-checker pairs — field repair (`POST /v1/cases/{caseId}/repairs` to propose, `MAKER`; `POST /v1/repairs/{actionId}/approve`, `CHECKER`) and investigation confirm-sent (`POST /v1/cases/{caseId}/investigation/confirm-sent` to propose, `MAKER`; `POST /v1/investigation-confirmations/{confirmationId}/approve`, `CHECKER`) — plus `POST /v1/cases/{caseId}/retry` (static-data, `MAKER`), `POST /v1/cases/{caseId}/reject` and `POST /v1/cases/{caseId}/investigation/reject` (both `CHECKER`-only, no maker step), and the read API (`GET /v1/cases`, `/v1/cases/{caseId}`, `/v1/instructions/{instructionId}/timeline`, `/v1/instructions?uetr=|endToEndId=`, all roles). HTTP Basic against in-memory users (`viewer`/`maker1`/`maker2`/`checker1`/`checker2`/`dual1`, password `password` for all of them locally) — see `.notes/reports/PHASE-8-REPORT.md` for why that's a deliberate simplification and what production would use instead.

`ops-dashboard` (default port 5173) is the operator client for `exception-service`: `cd ops-dashboard && npm install && npm run dev`. It expects `exception-service` at `http://localhost:8084` (override with `VITE_API_BASE_URL`); sign in with any of the local users above. A pure `CHECKER` (not also a `MAKER`) lands on the approval queue instead of the exception queue — see `.notes/reports/PHASE-9-REPORT.md` §5 for why.

`rail-simulator` runs independently of the above — it needs neither Postgres, Redpanda nor `core` — via `mvn -pl rail-simulator spring-boot:run` (port 8085 by default). `POST /rail/{railId}/scenario` (`railId` one of `fedwire`, `sepa`, `ach_equiv`) loads a scenario YAML at runtime; `GET /rail/{railId}/received` lists what it's recorded. See `.notes/reports/PHASE-5-REPORT.md` for the full API and scenario-file shape. `settlement-gateway` dispatches to whatever base URL each rail is configured with (`payments.gateway.rail-base-urls.*`, `rail-simulator`'s address by default) and exposes `POST /callbacks/rail/{railId}/status` and `/return` for the rail to call back on. See `.notes/reports/PHASE-6-REPORT.md`.

### Known fragility: `rail-simulator`'s DROP scenario and the embedded Tomcat version

`ConnectionDropper` (`rail-simulator/src/main/java/.../dispatch/ConnectionDropper.java`) forces a true connection reset with no response, using reflection into `org.apache.catalina.connector.RequestFacade`'s internals — there is no supported Servlet-API way to do this. **Verified working against `tomcat-embed-core` 10.1.20**, the version pulled in transitively by this repo's pinned Spring Boot 3.2.5. The application fails fast at startup (`ConnectionDropper.verifyDropIsSupported`, an `@PostConstruct` check) if that internal shape isn't present, naming the Tomcat version it actually found — so a version bump that breaks this shows up as `rail-simulator` refusing to start, not as a DROP scenario silently degrading into a slow TIMEOUT. If `rail-simulator` won't start after a Spring Boot upgrade, check that message first.

### Note: `rail-simulator`'s packaged jar and why it has a classifier

`rail-simulator/pom.xml`'s `spring-boot-maven-plugin` repackage execution uses `<classifier>exec</classifier>`. Without it, repackaging overwrites the plain jar with the executable `BOOT-INF/classes/...` layout, which `settlement-gateway` (a test-scope dependency on `rail-simulator`, so its integration tests can dispatch against a real instance) cannot compile against. This only breaks under a full `mvn clean verify` — a partial `-pl settlement-gateway -am` build never runs `rail-simulator`'s own `package` phase, so it's easy to not notice locally. If a future module adds a similar test-scope dependency on `intake-service` or `processing-service`, it will need the same fix.

## Status

Phase 9 of 13 — operations dashboard. `ops-dashboard` is the screen an operator lives in: a dense exception queue (filterable on stage, reason code, repairability and assignee; sortable on age), a case detail view showing the instruction's current field values alongside a plain-language explanation of the ISO reason code, a repair form that only ever offers the allowlisted fields and makes unmistakably clear that submitting *proposes* a change rather than applying one, a top-level approval queue where a checker sees every pending proposal's diff without opening a case (and never sees an approve button on their own proposal — the server enforces this with a 403, the client never offers it), and an investigation-resolution flow visually distinct from field repair with its own maker-checker pair. Role gating throughout is a client-side usability affordance only; `exception-service`'s own `@PreAuthorize` checks and database constraints are the real control, and each screen says so in a comment.

Building a real client against Phase 8's API surfaced nine gaps in what it exposed — none worked around client-side, all fixed as small additive backend changes: the case list/detail now carry the instruction fields the dashboard needs (batched, not one lookup per row), `repairability` joined the case-list filters, two new `/pending` endpoints back the approval queue, `GET /v1/me` lets the client learn its own role (HTTP Basic carries no claims), `GET /v1/repairable-fields` sources the repair form's allowlist from the `RepairableField` enum itself rather than a second hand-maintained copy, CORS is now configured, 401/403 responses match the API's existing JSON error shape, and — the one gap that was a real pre-existing defect rather than something Phase 9-specific — the timeline now interleaves investigation confirm-sent actions, which Phase 8 had silently left out. See `.notes/reports/PHASE-9-REPORT.md` for the full account, including which of the nine gaps I'd call a bug fix versus a phase-9 addition, and the two things I built and would cut on a second pass.

Phase 8 (exception service and repair) previously described here in full — `exception-service` consumes `payments.exceptions` and owns the `exception_case`/`repair_action` lifecycle: two maker-checker workflows — field repair and investigation confirm-sent (both backed by their own database CHECK constraint) — a static-data retry path that keeps its case open across repeated failures, and investigation reject (`CHECKER`-only, no maker step). One open case per instruction is enforced by a partial unique index. A repaired instruction re-enters the pipeline via `payments.repaired`: exception-service transitions it only as far as `REPAIRED`, and `ValidationConsumer` (processing-service) performs the actual re-validation, `REPAIRED -> VALIDATED`. `repair_attempts` is a lifetime counter across an instruction's whole repair lineage, capped at three by default. The repairable-field allowlist is five fields: `debtorAgentBic` was dropped on review — a wrong debtor-side agent means the system's own reference data is wrong, which is a `STATIC_DATA` retry, not a single-instruction field repair. See `.notes/reports/PHASE-8-REPORT.md` for that phase's full account.
