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

- Base structure of the repository (Annex E, ADR-064): three Maven modules (`workflow-core`, `workflow-adapters`, `workflow-app`), the liveness endpoint `GET /api/v1/health`, `deploy/Dockerfile` and `deploy/compose.yml`, `.env.example` and the `ci.yml` workflow. The saga is not implemented yet.
- `CHANGELOG.md`.
