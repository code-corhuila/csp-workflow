# Changelog

All notable changes of `csp-workflow` are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the versions follow
[Semantic Versioning](https://semver.org/). A release is a `release/<version>` branch cut from `main` and filled with the commits of `qa`,
re-applied with `git cherry-pick -x` (numerals 6.2.3, 10 and 11 of the course norm); it reaches `main` by pull request, never by merging `qa`,
and is tagged `v<version>` once it is merged.

## [Unreleased]

### Changed

- The README and the CODEOWNERS comments carry the identity of the project (Cinesync Platform, team `cinesync-platform`, Group 1, documentation in `csp-docs`) instead of the template identity. The owner of `*` is unchanged.

### Added

- Booking participant (HU-CONCESSIONS-004, ADR-035, ADR-066): `HttpBookingParticipant` implements the port with the internal operations `POST /internal/workflow/holds`, `.../reservations/{id}/confirm` and `.../release`. Every call carries the credential of the workflow (`ServiceToken`, read again at every call from `SERVICE_TOKEN_FILE` or fixed in `SERVICE_TOKEN`), `Idempotency-Key: <sagaId>:<step>` and the `X-Correlation-Id` of the request, with a bounded timeout and retries with full jitter only on network errors, `429` and `5xx` (`RetryingHttp`). A refusal or an unreachable service becomes a `ParticipantFailedException` that keeps only the status and the error code. Not composed yet: the wiring and the configuration come with the endpoints.
- Security and error layer of the HTTP entry (HU-CONCESSIONS-004, ADR-066): `Rs256Verifier` (RS256 only, `exp` and `sub` required, 30 seconds of leeway, `aud` as a string or a list), `AuthFilter` (401 `UNAUTHORIZED` without token, 401 `INVALID_TOKEN` for an invalid, expired or non-UUID-subject token, 403 `FORBIDDEN` for a service credential, and the user id read from the `sub`; `/health` needs no token), `CorrelationFilter` (reuses or creates `X-Correlation-Id`, one access line per request) and `ErrorHandler` with the common envelope. Not composed yet: the saga endpoints and the wiring come in the next pull request.
- Domain `saga` and the orchestrator of the saga `book-seats` (HU-CONCESSIONS-004, ADR-066): `SagaInstance` with its state machine (`RUNNING`, `COMPLETED`, `COMPENSATED`, `FAILED`), the ports (start and get a saga, the Booking participant, the saga store, the id generator) and `RunBookSeatsSaga`, which holds the seats, confirms the reservation, saves the state before and after every step, sends the key `<sagaId>:<step>` to every call, releases the hold if the confirmation fails and ends `FAILED` if the release fails. The same user, key and request return the first saga and run nothing. No adapter yet: the participant, the store and the HTTP entry come in the next pull requests.
- Base structure of the repository (Annex E, ADR-064): three Maven modules (`workflow-core`, `workflow-adapters`, `workflow-app`), the liveness endpoint `GET /api/v1/health`, `deploy/Dockerfile` and `deploy/compose.yml`, `.env.example` and the `ci.yml` workflow. The saga is not implemented yet.
- `CHANGELOG.md`.
