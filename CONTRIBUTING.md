# Contributing

Thanks for your interest! Bug reports, feature ideas, and pull requests are all
welcome. This is a small, single-maintainer project, so a little process goes a long
way.

## Before you start

- **Read the decision log** at [`docs/plan/README.md`](docs/plan/README.md). The
  project is built phase by phase with design docs in `docs/plan/`, and most
  architectural choices (sidecar-per-session, Flyway-only schema changes, NDJSON
  protocol, provider capability gating, …) have already been decided there. PRs that
  relitigate a settled decision without new information will likely be declined.
- **For anything non-trivial, open an issue first** and describe what you want to
  change and why. Larger features typically get a short design doc in `docs/plan/`
  before implementation.
- [`CLAUDE.md`](CLAUDE.md) is the day-to-day handbook: build commands, database setup,
  run/stop scripts, environment gotchas (WSL, macOS), UI style rules, and operational
  limits. It applies to human contributors just as much as to coding agents.

## Development setup

Follow the [Quick start](README.md#quick-start) in the README. For iterating:

```bash
docker compose up -d                                          # Postgres
mvn -Dskip.installnodenpm -Dskip.npm -DexcludedGroups=integration test   # fast backend unit tests, no DB
cd sidecar && npm test          # Claude sidecar
cd sidecar-codex && npm test    # Codex sidecar
cd frontend && npm run dev      # Vite dev server on :5173, proxies to :8080
```

`mvn clean verify` runs everything including the DB-backed integration tests
(requires the compose Postgres). No Maven installed? The bundled `./mvnw` accepts the
same arguments (CI uses it).

## Pull requests

- Keep PRs focused; one concern per PR.
- CI must pass. It runs the DB-less backend unit tests, typecheck + tests for both
  sidecars, the protocol-sync guard (`scripts/check-protocol-sync.mjs` — the two
  sidecars' `protocol.ts`/`stdio.ts` must stay in sync), and a full frontend build.
- Schema changes go through Flyway migrations only
  (`src/main/resources/db/migration/`), never manual DDL. Never edit an
  already-committed migration — add a new one.
- UI changes must follow the visual-style rules in `CLAUDE.md` (icon vocabulary in
  `frontend/src/icons.ts`, color tokens in all three theme blocks, no hardcoded hex,
  no emoji in the DOM) and work in both dark and light themes.
- Commit messages: imperative summary line ("Add X", "Fix Y"), body explaining the
  why when it isn't obvious.

## License

By contributing, you agree that your contributions will be licensed under the
[Apache License 2.0](LICENSE).
