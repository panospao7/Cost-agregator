# Cost-agregator — Claude Code rules (lightweight port of AGENTS.md)

Android/Kotlin expense tracker. Clean Architecture, MVVM, Compose, Room,
Hilt, WorkManager, privacy fail-closed. Read current schema/counts from source
and architecture inventory; never rely on frozen numbers in this entry point.
Optimize for correctness, privacy safety, minimal diffs.

`AGENTS.md` is the full rulebook. This file is the Claude entry point: follow
AGENTS.md router, invariants, and workflow modes; specifics below adapt them to Claude.

## Orchestration (main agent = orchestrator)

- You never bulk-edit directly on multi-file/risky work. Delegate discovery to
  `scout`, root-cause to `debugger`, final gate to `reviewer-strict` via Agent.
  An explicit user no-subagent instruction takes precedence: work locally and
  use a separate fresh reviewer session for independent acceptance.
- Planner-first when: multi-layer, 5+ files, workers, privacy/security, Room/
  migrations, money/currency, receipt/recurring lifecycles, guards, permissions.
- Resume cross-session work from `workflows/active/<id>-handoff.md`, not chat history.

## First reads (by reference, never paste big maps inline)

1. `docs/architecture/CODEBASE_SEGMENTS.md` — segment ownership
2. `docs/architecture/CODEBASE_INVENTORY.md` — snapshot inventory
3. `docs/architecture/LEGAL_PATHS.md` — ONE legal path per operation
4. `docs/architecture/ENGINE_INTERACTION_MAP.md` — engine impact matrix
5. `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` (FG-00..FG-27) — cite path + FG-ID only

Do NOT preload full 50–180KB maps. Scope via router, then read exact files.

## Validation — sole validation-runner owner

- ONLY `validation-runner` runs validation, serially, via:
  `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile <profile>`
- Never `./gradlew` directly. Never two validations in parallel — poll the run ID;
  RUNNING is not failure. Missing/stale/errored results are never PASS.
- Other agents must NOT run builds/tests/guards. They review, author tests,
  or diagnose persisted logs, then request the runner via the orchestrator.
- With delegation disabled, the main agent explicitly assumes the execution-only
  validation-runner role. Never create a second validation owner/process.
- Prefer `targeted-unit-test`, then named shard; full `unit-tests` / `app-check` /
  `static-guards` only when the gate requires it. Migration changes → migration tests.

## Cost posture / model routing

- Discovery: `scout` (Haiku) for UI/docs/file checks; override to Sonnet for
  DB/money/lifecycle slices (Room, DAOs, migrations, coordinators, currency).
- Implementation: Sonnet. Debugging: Sonnet, escalate to Opus when cross-layer
  or stalled after 1 iteration.
- `reviewer-strict` (Opus) ONLY for risky areas: workers, privacy/security,
  Room/migrations, money, lifecycles, guards, cross-module diffs. Cheap review
  otherwise. Max 2 review + 2 debug iterations per slice, then hand back PARTIAL/BLOCKED.

## Release 1.0 assurance

- Use `docs/prompts/release-1.0-assurance/README.md` for the post-Wave-5
  assurance campaign and phase prompts.
- Pin Claude 5.5 profiles explicitly: Haiku `medium` for bounded discovery,
  Sonnet `high` for diagnosis and implementation, Opus `xhigh` for cross-app
  assurance, and Opus `max` only for final acceptance.
- Do not run a broad audit on a dirty or partially validated Wave 5 tree.
- Regression review comes before horizontal/vertical revalidation. Do not let
  discovery and final acceptance be performed by the same reviewer.

## Lifecycle / privacy / Room invariants (from AGENTS.md, binding)

- Expense writes → transaction lifecycle coordinator → `ExpenseDao.insertAtomic()` only.
- Receipts → receipt lifecycle services; recurring → recurring rule coordinator.
- No direct DAO writes from forbidden layers; no duplicated business rules.
- Workers: `WorkerExecutionGuard` + barriers; never swallow `CancellationException`.
- Privacy fail-closed; diagnostics use bounded reason codes only — never raw
  notification/OCR/receipt text, paths, SQL messages, stack traces, payloads.
- Schema change → version bump + migration + snapshots + migration tests. No
  destructive migration without explicit approval.

## Stop and ask before

Deleting/ignoring tests, weakening guards, destructive migrations, reviving
deprecated unsafe APIs, out-of-scope behavior changes, broad refactors,
removing diagnostics, direct-DAO lifecycle shortcuts. No commit/push/merge
unless explicitly asked. No `git reset/clean/checkout -- .`/`rm -rf` without approval.
