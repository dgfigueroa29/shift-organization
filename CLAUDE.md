# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Snapshot

Shift Organization is a Kotlin/Ktor MVP for a serverless AWS property-management backend. It is a Gradle multi-module
project, but **only the `shared` module is currently populated**. The six `lambda-*` modules declared in
`settings.gradle.kts` (`lambda-properties`, `lambda-bookings`, `lambda-recurring-events`, `lambda-notifications`,
`lambda-health`, `lambda-recurring-processor`) are planned deployment units — new code intended for a Lambda should be
created under those exact names so it lines up with the deployment plan.

## Spec is the Source of Truth

Specification files in `.kiro/specs/shift-organization-mvp/` are the behavior contract; when code and docs disagree, *
*follow the spec and bring code into line**.

- `requirements.md` — functional requirements (referenced by ID throughout `tasks.md`)
- `design.md` — architecture, data flow, schemas, REST endpoints, env-var matrix, and the 23 numbered **correctness
  properties** that drive property-based tests
- `tasks.md` — intended implementation order with a wave-based dependency graph at the bottom (consult it before picking
  up new work). Tasks marked `[ ]*` are optional/skippable for a faster MVP.

`AGENTS.md` mirrors the repository guidelines; treat it and this file as consistent — update both if conventions change.

## Build & Test Commands (JDK 17)

```bash
./gradlew build                # compile all included modules + run tests + assemble
./gradlew test                 # full JUnit 5 suite
./gradlew :shared:test         # only the shared module
./gradlew clean                # wipe build/ outputs
```

Run a single test class:

```bash
./gradlew :shared:test --tests "com.shiftorganization.shared.auth.CognitoAuthTest"
```

Run a single test method:

```bash
./gradlew :shared:test --tests "com.shiftorganization.shared.auth.CognitoAuthTest.valid JWT produces correct UserPrincipal"
```

Once `lambda-*` modules are populated, each one applies `shadowJar` to produce a fat JAR (artifact names: `properties`,
`bookings`, `recurring-events`, `notifications`, `health`, `recurring-processor`). GraalVM native image is wired as an
optional optimisation behind `-Pnative`.

## Architecture (the big picture)

The system is decomposed by **functional concern** into independent Lambdas that share a single `shared` library:

```
API Gateway → 5 HTTP Lambdas (properties, bookings, recurring-events, notifications, health)
EventBridge (cron) → recurring-event-processor Lambda
SNS → notifications-handler fan-out → SES email delivery

Persistence split:
  RDS PostgreSQL → properties, bookings  (with btree_gist EXCLUDE constraint for no-overlap)
  DynamoDB       → recurring_events, workflow_state  (optimistic locking via `version`)
  OpenSearch     → properties search index (async, best-effort sync from PropertyService)
  Cognito        → JWT issuance + `custom:role` claim (ADMIN | OWNER | TENANT)
```

Each Lambda's entry point installs a Ktor `Application.module()` with the same plugin stack: `cognitoJwt`
Authentication, `ContentNegotiation` (JSON), `CorrelationIdPlugin`, `StatusPages`. Domain exceptions in
`shared/exception/` are translated to HTTP status codes by `StatusPagesConfig`.

### Cross-cutting patterns that are easy to miss

- **Booking conflict detection is database-enforced.** The `no_overlap` exclusion constraint in
  `V1__create_properties_and_bookings.sql` is the real guard — the in-app `findConflicts` query is a UX-friendly
  pre-check. Conflict handling must run inside a `REPEATABLE READ` transaction and translate the constraint violation to
  `HTTP 409 ConflictException` with the conflicting IDs in the body.
- **Two-phase writes use compensating actions, not distributed transactions.** RecurringEvent creation writes to
  DynamoDB first, then registers an EventBridge rule; if EventBridge fails, the DynamoDB record is deleted. Workflow
  progress is tracked in the `workflow_state` DynamoDB table via `WorkflowStateRepository` with
  `ConditionExpression: version = :v` for optimistic locking.
- **OpenSearch sync is fire-and-forget** via `CoroutineScope.launch` with a 5-second deadline; failures are logged but
  never fail the originating HTTP response.
- **SES delivery failures are silently swallowed** (logged only) to protect the caller flow. This is Property 19 in
  `design.md`.
- **Authorization is two-layered:** `cognitoJwt` validates the token and builds `UserPrincipal`; route handlers call
  `ApplicationCall.requireRole(...)` which throws `ForbiddenException`. Ownership checks (e.g. property updates) are
  enforced inside the service, *after* role check, by comparing `principal.userId` to `ownerId`.
- **Correlation IDs flow through MDC.** `CorrelationIdPlugin` reads or generates `X-Correlation-Id`, attaches it to MDC
  and the response header. Every log entry and error response body must include it.
- **Configuration is read once at module load via `EnvironmentConfig`**, which fails fast on missing required vars. Some
  vars (`COGNITO_USER_POOL_ID`, `SES_SENDER_ADDRESS`) are optional and only required by specific Lambdas — use
  `requireOptional(name)` to surface a clear error at the call site rather than letting a `null` propagate. The
  env-var-to-Lambda matrix lives in `design.md` § Environment Variables.

## Testing Conventions

- **Dual approach:** property-based tests (jqwik / kotest-property, **min 100 iterations**) for universal invariants;
  example-based JUnit 5 + Mockito tests for happy-path scenarios; Testcontainers for integration tests touching real
  PostgreSQL / DynamoDB Local / OpenSearch.
- Each property test maps to one of the 23 numbered properties in `design.md`. Use the existing naming pattern when
  adding more: `Feature: shift-organization-mvp, Property N: <description>`.
- Tests live at the matching package path under `shared/src/test/kotlin` and are named `*Test.kt`. H2 is the in-memory
  DB used in unit-level repository tests.
- Prefer focused, behavior-driven test names (backticks allowed): `` `valid JWT produces correct UserPrincipal` ``.

## Code Conventions

- Package layout under `com.shiftorganization.shared.{auth, config, db, domain, exception, model, plugins, search}` —
  keep new code there until a `lambda-*` module is bootstrapped.
- 4-space indent, `PascalCase` types, `camelCase` members. No formatter is configured; mirror surrounding style.
- Request/response payloads are `@Serializable` data classes in `shared/model/`; domain types live in `shared/domain/`.
- New PostgreSQL schema changes go in `shared/src/main/resources/db/migration/V<n>__<desc>.sql` (Flyway).
