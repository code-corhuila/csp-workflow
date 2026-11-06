# csp-workflow

> Business process orchestration (saga)

Part of the **Cinesync Platform** distributed system — team `cinesync-platform`, Group 1.
Governance and documentation live in [`csp-docs`](https://github.com/code-corhuila/csp-docs).

## Purpose

`csp-workflow` orchestrates the business processes that cross several domains, as sagas: every step has a compensation, the state is
persisted after every step and the compensations run in reverse order (norm 5.8, Annex E). The decisions are in `csp-docs`: the stack
([ADR-064](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-064-workflow-java-spring-boot-maven.md)),
the place of the state
([ADR-065](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-065-workflow-saga-state-in-postgresql.md))
and the first saga with its contract
([ADR-066](https://github.com/code-corhuila/csp-docs/blob/main/05-architecture/decisions/records/ADR-066-workflow-first-saga-book-seats.md),
`07-api/contracts/openapi/workflow-service.yaml`). Today the saga `book-seats` runs end to end with an in-memory store.

## Structure

```
workflow-core/       domain and application, no Spring
workflow-adapters/   HTTP in, participants out, state out
workflow-app/        Spring Boot application (composition root)
deploy/              Dockerfile and compose.yml
```

## Build and run

Java 21 and Maven.

```bash
mvn -B verify
java -jar workflow-app/target/workflow-app-0.1.0.jar
curl http://localhost:8086/api/v1/health
```

The variables are listed in `.env.example`. `JWT_PUBLIC_KEY` (or `JWT_PUBLIC_KEY_FILE`) and `SERVICE_TOKEN` (or `SERVICE_TOKEN_FILE`) are required: the service refuses to start without them. For local work `csp-infra-postgres/scripts/dev-keys.sh` writes both.

## API

`POST /api/v1/sagas/book-seats` and `GET /api/v1/sagas/{id}` follow `07-api/contracts/openapi/workflow-service.yaml` in `csp-docs`. The state of the sagas lives in schema `workflow` of PostgreSQL (ADR-065, amended). `db/` holds its Flyway migrations (`migrations/`) and their reversions (`rollbacks/`, run by an operator from the highest version down). Apply them before the service starts with `docker compose -f deploy/compose.yml --profile tooling run --rm workflow-db-migrate`. The service itself reads `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`; `deploy/compose.yml` fills them from `WORKFLOW_APP_USER` and `WORKFLOW_APP_PASSWORD`, and `SERVICE_TOKEN` from `WORKFLOW_SERVICE_TOKEN`, because those names already belong to other services in the shared platform environment. Its login (`workflow_app`) must exist before the migrations run; the migration `V005` makes it a member of the role `workflow_writer`, which `V003` creates. When the service starts it finishes the sagas the previous process left running (`RunBookSeatsSaga.recover`): it repeats the step that was begun, which is safe because every call carries the key `<sagaId>:<step>`, or finishes the compensation that was under way. It runs once at startup and is not a scheduled job (norm 4.3); a saga that fails to recover is logged for a person to look at.

## Branching

Three permanent branches. **None of them accepts a direct commit** — you enter through a child
branch and leave through a Pull Request.

```
develop  <--PR--  feat/... fix/... chore/...
qa       <--PR--  qa/...
main     <--PR--  release/...  hotfix/...
```

Promotion happens **by re-application** (`git cherry-pick -x`), never by merging one permanent
branch into another: `merge develop -> qa` and `merge qa -> main` do not exist in this model.

`main` requires **1 approval from `ariel5253`**. On `develop` and `qa` the team sets its own review
rule.

Full policy: `00-governance/branching-policy.md` in `csp-docs`.
