# Cost-agregator Agent Rules

These rules apply to all AI agents working in this repository (OpenCode and Codex).

## Project profile

This is an Android/Kotlin expense-tracking app using Clean Architecture, MVVM, Jetpack Compose, Room, Hilt, WorkManager, privacy controls, diagnostics, backups/exports, receipt/OCR flows, recurring rules, and currency/money logic.

Optimize for correctness, privacy safety, and minimal diffs.

## Default mode: orchestration

Unless the request is a trivial localized edit, operate as an orchestrator:

- Confirm scope first (pipeline-local; no broad refactors).
- Delegate by name to the project subagents in `.codex/agents/` (`scout`,
  `planner`, `specialist-coder`, `tester-runtime`/`tester-static`,
  `reviewer-strict`, guardians, `debugger`, `documentor`, `validation-runner`).
- Planner-first for multi-layer, risky, or 5+ file work; strict review before validation.
- Only `validation-runner` runs builds/tests/guards (via `scripts/validation-runner.ps1`).
- Cross-session work resumes from `workflows/active/<id>-handoff.md`, not prior chat.

## First files to read

Before non-trivial work, inspect the relevant architecture docs if present:

1. `docs/architecture/CODEBASE_SEGMENTS.md` — segment ownership (39 segments, verified 2026-09-07)
2. `docs/architecture/CODEBASE_INVENTORY.md` — snapshot inventory (DB v148, 68 DAOs, 40 ViewModels)
3. `docs/architecture/LEGAL_PATHS.md` — ONE legal path per operation (enforced by CI guards — see "CI guard implementation (by reference)" below)
4. `docs/architecture/ENGINE_INTERACTION_MAP.md` — engine → pipeline impact matrix
5. Relevant files under `docs/` — on-demand only, do NOT preload big maps

Do not blindly grep the whole repo before checking segment/inventory docs.
Do NOT inject full 50-180KB maps into context. Use Router below to scope, then read exact files.

## CI guard implementation (by reference)

Refer by path + FG-ID from `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` (FG-00..FG-27); never paste gate content inline.

- Scripts: `scripts/verify_*.py` (20 files) + `scripts/ci/run_static_guard_suite.py`, `scripts/ci/guard_registry.py`, `scripts/ci/verify_guard_registry.py`, `scripts/ci/verify_guard_docs_truth.py`
- Config: `config/guards/`, `config/baselines/`, `config/db_access_allowlist.yml`, `config/release_block_denylist.yml`
- Fail-closed (FG-03): missing/skipped/unknown guard = infra failure (exit 2) = fail, never GREEN — a guard result is never assumed passing.
- No-weakening (FG-06/FG-07): no baseline growth, allowlist broadening, or exception additions without explicit human approval.
- Self-protection (FG-23): a PR must not weaken its own check.

## Router (lightweight signpost, <30 lines — read disk truth after scoping)

- Expense create/update/delete → `TransactionLifecycleCoordinator` → `ExpenseDao.insertAtomic()` only. See `docs/architecture/LEGAL_PATHS.md#expense-mutations`.
- Receipt scan/OCR → `ReceiptLifecycleCoordinator.processReceiptInput()` → `ReceiptRepository` (draft only) → `createExpenseAndLinkReceipt()` + `ReceiptLinkService`. See `LEGAL_PATHS.md#receipt-mutations`.
- Receipt matching → `ReceiptMatchLifecycleService` (MATCH_SUGGESTED/APPROVED/REJECTED/CLEARED). Segment 38.
- Recurring rules → `RecurringRuleLifecycleCoordinator` (single-writer) + `RecurringLifecycleEventWriter`. Segment 7.
- Notification/email/bank intake → `NotificationIntakeWorker` (Segment 12) / parser registry / review queue (Segment 3) → must funnel into `TransactionLifecycleCoordinator`.
- Workers → all 10 CoroutineWorkers run via `WorkerExecutionGuard` + `DatabaseWriteBarrier`. Check `ENGINE_INTERACTION_MAP.md` + `docs/workers/`.
- Money → `CurrencyConverter.convert()/convertAsOf()` → `MultiCurrencyRepository` → `MoneyAggregate/Builder`, `domain/core/money/`. CRITICAL blast radius.
- Groups/shared → `SharedExpenseManager` / `SharedExpenseDataPortAdapter` + `data/database/GroupTransactionCoordinator.kt` (atomic via `RoomDomainTransactionRunner`). Segments 24-25.
- Export/backup/restore → `AccountingExportPolicy` / `BackupVerifier` (TIER_1_EXACT) + restore barrier. Segment 18.
- AI/cloud → `HybridRouter` (Segment 20) + `PrivacyGate`/`CloudPayloadPolicy` (fail-closed, Segment 28).
- Rule: grep exact coordinator/service name first, read its file, then follow calls. Do not trust map method lists without reading source.

## General workflow

1. Understand the requested change.
2. Locate the owning module/segment.
3. Read surrounding code before editing.
4. Make the smallest correct change.
5. Add/update tests for behavior changes.
6. Run or recommend targeted validation.
7. Report files changed, validation status, and remaining risk.

Do not refactor unrelated code.

## Workflow modes

Use fast mode only for tiny low-risk edits.

Use standard mode for normal feature/bug work.

Use strict mode for:
- WorkManager/workers
- privacy/security/cloud AI/export/backup
- Room entities, DAOs, migrations, schema snapshots
- currency/money math
- transaction, receipt, recurring lifecycle
- static architecture guards
- permission behavior
- diagnostics/logging persistence
- cross-layer or cross-module changes
- changes touching 5+ files

Strict mode requires targeted tests and strict review.

## Imported external implementation plans

If the user provides a plan from GPT/another system:

1. Treat it as approved intent, not guaranteed truth.
2. First verify it against current source.
3. Do not re-plan from scratch unless the plan is stale or unsafe.
4. Execute one batch at a time.
5. Stop on mismatched files, architectural ambiguity, failed review, failed tests, privacy uncertainty, or schema surprises.
6. Do not mark milestones complete until code, tests, and review gates pass.

## Architecture invariants

### Lifecycle legal paths

Do not bypass established lifecycle coordinators/services.

- Expense mutations must go through the transaction lifecycle path/coordinator.
- Receipt mutations must go through receipt lifecycle services.
- Recurring rule mutations must go through the recurring rule lifecycle coordinator.
- Do not write directly to DAOs from forbidden layers.
- Do not duplicate business rules across UI/ViewModel/repository/domain layers.

If unsure, stop and ask for architecture review.

### Worker rules

For worker changes, preserve:

- `WorkerExecutionGuard` usage
- restore/write barrier semantics
- retry vs failure behavior
- idempotency across WorkManager retries
- cancellation propagation
- timeout handling
- structured diagnostics
- sanitized reason codes
- permission boundaries
- metrics only after actual success

Workers must not swallow `CancellationException`.

Timeouts must be intentionally handled or covered by guard policy.

Optional side effects must not block unrelated core DB work.

### Privacy rules

Never persist, log, or expose:

- raw notification text
- OCR text
- receipt text
- file paths from exceptions
- SQL exception messages
- stack traces
- arbitrary `e.message`
- user financial payloads

Diagnostics must use bounded structured fields:
- controlled reason/failure code
- exception class name when useful
- target name
- counts/booleans

Reason-code fields must contain controlled constants only.

Privacy/security paths must fail closed.

Privacy cleanup workers must be able to run so they can delete raw data. Do not gate cleanup on the raw-retention capability it enforces.

### Notification permission rules

Notification permission should gate notification posting only.

Do not globally block unrelated core work, such as receipt matching or DB repair/enrichment, just because notifications are denied.

Before posting optional notifications:
1. check permission locally;
2. catch/suppress `SecurityException`;
3. record safe diagnostic if needed;
4. increment notification metrics only after a real successful post.

### Room/database rules

If an edit changes Room schema:

- update database version;
- add migration;
- update schema snapshots if the project uses them;
- add or update migration tests;
- preserve data;
- do not use destructive migration unless explicitly approved.

DAO bulk cleanup should avoid materializing sensitive raw payloads into Kotlin memory when SQL update/delete can do the job.

### Money/currency rules

For money/currency logic:

- avoid floating-point money math unless existing code explicitly uses it safely;
- preserve rounding semantics;
- test boundary cases;
- do not mix display formatting with domain calculation.

## Testing guidance

Prefer targeted checks first.
The Gradle examples below describe underlying tasks only; agents must execute
them through `validation-runner`, not invoke Gradle directly.

Common targeted commands:

```bash
./gradlew :app:testDebugUnitTest --tests "*WorkerExecutionGuard*"
./gradlew :app:testDebugUnitTest --tests "*WorkerRunLogger*"
./gradlew :app:testDebugUnitTest --tests "*WorkerTerminalDiagnostic*"
./gradlew :app:testDebugUnitTest --tests "*DataRetention*"
./gradlew :app:testDebugUnitTest --tests "*ReceiptMatching*"
./gradlew :app:testDebugUnitTest --tests "*DailyBriefing*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
./gradlew :app:testDebugUnitTest --tests "*Migration*"
```

Useful broader checks:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:check
```

Ask before running expensive Gradle commands.

Do not claim tests passed unless they were actually run.

## Compilation / Gradle coordination

Only one validation process may run at a time.

The sole live validation owner is `validation-runner`. All Gradle, compilation,
test, lint, connected-test, and static-guard execution must go through
`scripts/validation-runner.ps1`. No agent may invoke those tools directly.

`tester-runtime` may author tests, and `ci-build-debugger` may diagnose/fix
failures from persisted validation logs, but neither may execute validation.

Before running Gradle:
1. check whether another Gradle/test command is already running;
2. use a focused command first;
3. write output to a log file;
4. report command, exit code, and log path.

The validation wrapper enforces this with a global lock, detached execution,
durable `result.json`/logs, bounded timeouts, a completion marker, and a
worktree fingerprint. A `RUNNING` result must be polled, never rerun. Missing,
unknown, timed-out, stale, or infrastructure-error results are never PASS.

Use targeted tests first, then named serial shards when broader evidence is
needed. `trusted-tests` is a fast structural gate, not a replacement for the
full suite. `legacy-tests` isolates ledgered hang suspects but never skips them
from `unit-tests` or normal shards. `app-check` does not replace the full
`static-guards` suite; run each when required.

Prefer runner profiles `targeted-unit-test`, `compile`, and, only when
required, `app-check`.

Do not run multiple Gradle commands in parallel.

## Review requirements

Use strict review for risky areas.

Reviewers should inspect:

- `git status`
- `git diff`
- changed files
- surrounding code
- relevant architecture docs
- affected tests
- privacy/security boundaries
- migration implications

A review fail blocks completion.

## Documentation rules

Docs must match the actual code state.

Do not mark PRs, MITs, architecture milestones, or status files as `DONE`, `GREEN`, or `complete` unless:

1. implementation is done;
2. relevant tests passed or are explicitly documented as not run;
3. reviewer gate passed;
4. any required guardian gate passed.

If work is partial, use wording like:

- pending
- partial
- conditional
- blocked
- near-complete

## Git and file safety

Never run destructive commands unless the user explicitly approves:

```bash
git reset
git clean
git checkout -- .
rm -rf
```

Do not edit:

- `.env`
- `.env.*`
- `*.pem`
- `*.key`
- `id_rsa*`
- generated build outputs
- unrelated binary files

Do not commit, push, merge, or rebase unless explicitly asked.

## Diff discipline

Keep diffs minimal.

Avoid:

- broad formatting-only changes
- unrelated renames
- opportunistic refactors
- deleting tests to make builds pass
- weakening architecture guards without explicit approval
- hiding failures by relaxing assertions

## Completion report format

Every implementation agent should report:

```markdown
Files touched:
- `path`

What changed:
- ...

Validation:
- command: ...
- result: PASS|FAIL|NOT RUN
- notes: ...

Risks / follow-up:
- ...
```

Every review agent should report:

```markdown
VERDICT: PASS | FAIL

Issues:
- None
```

or list concrete evidence-backed issues.

## Default cost posture

Be cost-effective by default:

- cheap scout for discovery;
- normal coder for implementation;
- fast reviewer for low-risk diffs;
- strict reviewer/guardians only for risky areas.

Do not use expensive strict workflows for trivial changes.
