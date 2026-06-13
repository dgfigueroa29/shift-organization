# Repository Guidelines

## Project Structure & Module Organization

This is a Gradle multi-module Kotlin backend. The root files are `build.gradle.kts`, `settings.gradle.kts`, and
`gradle/libs.versions.toml`. `shared/` is the only populated module in this workspace; its code lives under
`shared/src/main/kotlin/com/shiftorganization/shared/{auth,config,db,domain,exception,model,plugins}` and its tests
under `shared/src/test/kotlin`. The `lambda-*` modules listed in `settings.gradle.kts` are planned deployment units, so
keep new code aligned with those names and treat `build/` as generated output.

## Specification Sources

Use `.kiro/specs/shift-organization-mvp/requirements.md` as the behavior contract, `design.md` for architecture and data
flow, and `tasks.md` for the intended implementation order. When code and docs differ, follow the spec files first and
update code to match the MVP requirements.

## Build, Test, and Development Commands

Run Gradle from the repo root with JDK 17:

- `./gradlew build` compiles all included modules, runs tests, and assembles artifacts.
- `./gradlew test` runs the full JUnit 5 suite.
- `./gradlew :shared:test` runs only the shared module tests.
- `./gradlew clean` removes generated build outputs when you need a fresh build.

## Coding Style & Naming Conventions

Follow idiomatic Kotlin and the existing package layout under `com.shiftorganization.shared`. Use 4-space indentation,
`PascalCase` for classes and objects, and `camelCase` for functions, properties, and local variables. Keep file names
aligned with the primary type or feature they contain, such as `CognitoAuth.kt` or `StatusPagesConfigTest.kt`. No
formatter or linter is configured here, so mirror surrounding style and keep imports, blank lines, and naming
consistent.

## Testing Guidelines

Tests use JUnit 5, Ktor test utilities, and Mockito/Kotlin Mockito. Place tests in `shared/src/test/kotlin` with the
same package path as the production code and name them `*Test.kt`. Prefer focused, behavior-driven names such as
`valid JWT produces correct UserPrincipal`. Keep test fixtures small and isolate external dependencies with mocks or
test doubles.

## CI/CD Pipeline

GitHub Actions (`.github/workflows/ci-cd.yaml`):

- **Build job** runs on every push/PR: `compileKotlin` → `test` → `shadowJar`
- **Deploy job** runs on `main` (→dev), `release/*` (→staging), or `workflow_dispatch` (any env)
- Deployment uses SAM CLI: `sam validate` → `sam package` → `sam deploy`
- Shadow JARs are built in CI and passed to the deploy job via artifacts

### Required Repository Secrets

| Secret | Purpose |
|---|---|
| `AWS_ACCESS_KEY_ID` | IAM access key for SAM deploy |
| `AWS_SECRET_ACCESS_KEY` | IAM secret key for SAM deploy |
| `SAM_S3_BUCKET` | S3 bucket for SAM deployment artifacts |

The IAM user must have permissions to deploy CloudFormation stacks, create/update Lambda functions, and manage all resources defined in `iac/sam/template.yaml`.

## Commit & Pull Request Guidelines

Use short, imperative commit subjects and keep each commit scoped to one change. For pull requests, include a concise
summary, the modules touched, the verification you ran (`./gradlew test`, `./gradlew :shared:test`, etc.), and any logs
or screenshots needed to explain behavioral changes. Reference the relevant `.kiro` requirement IDs when the change
implements a specific spec item.
