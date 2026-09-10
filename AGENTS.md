# Cost-agregator Agent Rules

These rules apply to all OpenCode agents working in this repository.

## Project profile

This is an Android/Kotlin expense-tracking app using Clean Architecture, MVVM, Jetpack Compose, Room, Hilt, WorkManager, privacy controls, diagnostics, backups/exports, receipt/OCR flows, recurring rules, and currency/money logic.

Optimize for correctness, privacy safety, and minimal diffs.

## First files to read

Before non-trivial work, inspect the relevant architecture docs if present:

1. `CODEBASE_SEGMENTS.md`
2. `CODEBASE_INVENTORY.md`
3. `LEGAL_PATHS.md`
4. `ENGINE_INTERACTION_MAP.md`
5. Relevant files under `docs/`

Do not blindly grep the whole repo before checking segment/inventory docs.

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

Only one agent may run Gradle or compilation at a time.

Default compile owners:
- `tester-runtime`
- `ci-build-debugger`

Other agents must not start Gradle/test/build commands unless explicitly instructed.

Before running Gradle:
1. check whether another Gradle/test command is already running;
2. use a focused command first;
3. write output to a log file;
4. report command, exit code, and log path.

Prefer:
- `./gradlew :app:testDebugUnitTest --tests "*ClassName*" --console=plain`
- `./gradlew :app:compileDebugKotlin --console=plain`
- `./gradlew :app:check --console=plain`

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