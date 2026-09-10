# Cost Aggregator — Branch Recovery Analysis

## Current position

**Branch:** `atomicity-pr21-enforcement-final`  
**HEAD:** `e15fbd121d6450730f02646af2f5e810ff21a5ad`  
**Last updated:** July 10, 2026

This branch’s recent work is primarily **CI/static architecture enforcement**, not application feature development.

The work progressed through:

1. Initial CI guardrail PRs 1–9.
2. PR10–PR15 for CI evidence, migration tests, ignored-test handling, and DI/release checks.
3. Corrective PRs A–F.
4. Guard-infrastructure work G1–G4.
5. Finalization work H1–H7.
6. Commit `e15fbd1`, intended to repair final gate defects.

The architecture being enforced is:

- Mutating DAOs must be owned by approved repositories/coordinators.
- UI and ViewModels must not write through DAOs.
- Writes must respect maintenance/write barriers.
- State changes and lifecycle events must be transactionally consistent.
- Workers must use their execution guard, lease, barrier, and run ledger.
- Privacy-sensitive values must not enter logs or raw diagnostics.
- Existing architecture debt may be ratcheted, but no new debt may be introduced.

## Important documentation conflict

The July 10 final-integration documentation says all CI guardrail phases are complete and describes 17 active guards. However, the actual HEAD workflow run failed. Therefore, the CI run—not the completion wording—is the current source of truth. ([github.com](https://github.com/panospao7/Cost-agregator/commit/c09de8b90b54e33fb330421cd7a668efdafae7e1))

The older master tracker is also stale relative to this branch: it still describes MIT-001–MIT-005 as largely TODO, while substantial implementation landed afterward. It should not be updated until the branch has a genuinely green verification run.

## Actual CI result at HEAD

Workflow run `#443`, triggered on July 10, 2026, failed after 30 minutes 47 seconds. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29129457251))

| Job | Result |
|---|---|
| Validate Workflow | Passed |
| Static Guards | Failed |
| Unit Tests | Timed out at 30 minutes |
| Lint & Check | Failed during `:app:check` |
| Release Check | Passed |
| Instrumented Tests | Not meaningful for this feature-branch push |
| Migration Proof | Not meaningfully proven on this feature-branch push |

The Node.js 20 deprecation messages are warnings, not the cause of these failures. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29129457251))

## Failure 1 — Static Guards

The unified static suite exited with code 1, meaning at least one blocking guard or guard-test component failed. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29129457251/job/86481872392))

The suite includes guard pytest using:

```text
python3 -m pytest scripts/test_verify_*.py scripts/ci/test_*.py -v --tb=short
```

That command is stored as an argument list in the runner. Verify whether the runner explicitly expands glob patterns before invoking subprocesses. If it does not, pytest receives literal `*` paths and guard tests can fail even though the same command works in a shell. ([github.com](https://github.com/panospao7/Cost-agregator/blob/e15fbd121d6450730f02646af2f5e810ff21a5ad/scripts/ci/run_static_guard_suite.py))

First inspect:

```bash
python3 scripts/ci/run_static_guard_suite.py
cat build/ci/static-guards/summary.json
find build/ci/static-guards -type f -maxdepth 2 -print
```

Then run every failed component directly.

Do **not** regenerate baselines or enlarge allowlists merely to make this pass.

## Failure 2 — `:app:check`

Lint and `assembleDebug` apparently completed; the failure annotation is specifically on:

```bash
./gradlew :app:check --stacktrace
```

([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29129457251/job/86481872421))

Commit `e15fbd1` changed the Gradle DB-access task from the raw detector to the ratchet wrapper. That was directionally correct, but `:app:check` still failed. Possible causes include:

1. The DB ratchet still detects a new or stale fingerprint.
2. Another Gradle-wired architecture task fails.
3. Gradle and the Python suite invoke a guard differently.
4. A baseline path/fingerprint differs between local and Linux execution.
5. A task treats missing scripts/configuration as a warning instead of failing consistently.

Run:

```bash
./gradlew :app:check --no-daemon --stacktrace --info \
  | tee app-check.log
```

Find the first failing task:

```bash
grep -nE "FAILED|Execution failed for task|What went wrong" app-check.log
```

Then execute only that task until fixed.

## Failure 3 — Unit-test timeout

The Unit Tests job timed out while running:

```bash
./gradlew :app:testDebugUnitTest --no-daemon --stacktrace
```

No normal unit-test or Room verification reports were produced because the first step never completed. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29129457251/job/86481872394))

This must be treated as either:

- a hanging/deadlocked test,
- a very slow test suite,
- expensive Hilt/KSP compilation,
- uncontrolled coroutine/dispatcher work,
- or CI timeout configuration that is too small.

Do not simply increase the timeout before identifying where time is spent.

Run locally:

```bash
./gradlew :app:testDebugUnitTest \
  --no-daemon --stacktrace --info \
  | tee unit-tests.log
```

If it hangs, divide tests by package or class:

```bash
./gradlew :app:testDebugUnitTest --tests "*ArchitectureGuard*"
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
./gradlew :app:testDebugUnitTest --tests "*Migration*"
./gradlew :app:testDebugUnitTest --tests "*Worker*"
./gradlew :app:testDebugUnitTest --tests "*Receipt*"
./gradlew :app:testDebugUnitTest --tests "*Recurring*"
```

Identify the final test started before progress stops. Check for `runTest`, unfinished flows, real delays, unclosed executors, `first()` waiting forever, and tests relying on `Dispatchers.Main`.

## Migration-proof issue

Commit `e15fbd1` added an emulator migration job, but its condition runs only for `main`/`master` pushes or manual dispatch. A push to `atomicity-pr21-enforcement-final` therefore does not prove the migration gate. ([github.com](https://github.com/panospao7/Cost-agregator/commit/e15fbd121d6450730f02646af2f5e810ff21a5ad))

Also, the command filters tests using:

```bash
./gradlew :app:connectedDebugAndroidTest --tests "*DatabaseMigration*"
```

Confirm that `--tests` is supported for this connected Android-test task. Android instrumentation filtering normally uses instrumentation-runner arguments rather than JVM test filtering.

The migration gate should run on pull requests or be manually dispatched for the exact HEAD SHA.

## Recommended recovery sequence

### Phase 1 — Capture exact failures

```bash
git checkout atomicity-pr21-enforcement-final
git pull
git rev-parse HEAD

python3 scripts/ci/verify_guard_registry.py
python3 scripts/ci/run_static_guard_suite.py
./gradlew :app:check --no-daemon --stacktrace --info
./gradlew :app:testDebugUnitTest --no-daemon --stacktrace --info
```

### Phase 2 — Fix static-suite control plane

1. Read `build/ci/static-guards/summary.json`.
2. Identify the exact failing guard.
3. Confirm pytest glob expansion.
4. Confirm every ratchet uses the same baseline and fingerprint implementation.
5. Confirm strict guards genuinely have zero findings.
6. Add a regression test for each infrastructure defect.

### Phase 3 — Fix `:app:check`

Compare the failing Gradle task with the equivalent static-suite invocation. They must produce identical policy outcomes.

### Phase 4 — Isolate unit-test hang

Find the exact test/class causing the timeout. Fix lifecycle cleanup or split excessively expensive test groups into separate CI jobs.

### Phase 5 — Prove migrations

Run the migration instrumentation tests manually for HEAD, then modify CI so migration proof runs on the intended PR/release gate.

### Phase 6 — Final truthful verification

Require one clean run of:

- Validate Workflow
- Static Guards
- Unit Tests
- Lint & Check
- Release Check
- Migration Proof

Only after that should `LATEST_CI_VERIFICATION.md`, `CI_GUARDRAILS_BASELINE.md`, and MIT-001–MIT-005 be marked complete.

## Immediate next task

Start with **Static Guards**, because:

- it fails quickly,
- it may expose a guard-runner defect,
- and the same underlying guard may also explain the `:app:check` failure.

The branch is best described as:

> Guardrail infrastructure substantially implemented, but final integration remains RED because static guards fail, `:app:check` fails, unit tests time out, and migration proof has not been demonstrated for the exact branch HEAD.