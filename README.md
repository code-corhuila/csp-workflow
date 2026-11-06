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
`07-api/contracts/openapi/workflow-service.yaml`). Today the repository holds the base structure and a liveness endpoint; the saga is not implemented yet.

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

The variables are listed in `.env.example`.

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
