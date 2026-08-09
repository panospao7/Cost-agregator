# Cost Aggregator — Exact CI Guardrails Recovery Directive

## Mission

Recover the CI/static-guardrail branch starting from:

- Branch: `atomicity-pr21-enforcement-final`
- Starting SHA: `e15fbd121d6450730f02646af2f5e810ff21a5ad`
- Failed run: `29129457251`
- Run date: July 10, 2026

Do not resume feature development yet.

The latest run failed in three places:

1. Static Guards exited `1`.
2. `:app:check` exited `1`.
3. `:app:testDebugUnitTest` exceeded 30 minutes.

`Release Check` passed. Migration proof was not executed for this feature-branch push because its workflow condition only allows main/master pushes or manual dispatch. ([github.com](https://github.com/panospao7/Cost-agregator/actions/runs/29129457251))

---

# 1. Non-negotiable operating rules

The orchestrator must enforce these rules:

1. Do not regenerate baselines blindly.
2. Do not enlarge allowlists simply to obtain green CI.
3. Do not increase the unit-test timeout before identifying where time is spent.
4. Do not skip or exclude failing tests without a checked-in debt record containing:
   - test class,
   - reason,
   - owner,
   - issue,
   - expiry,
   - evidence that the failure predates the current change.
5. Do not modify application architecture while diagnosing CI infrastructure.
6. Do not mark MIT-001–MIT-005 complete until an exact commit has green evidence.
7. Make one logical commit per recovery phase.
8. Preserve all command output under `build/ci/recovery/`.
9. Keep a recovery ledger at:

```text
docs/ci/CI_GUARDRAILS_RECOVERY_LEDGER.md
```

Each ledger entry must record:

```text
date
commit SHA
command
duration
exit code
first failure
root cause
files changed
verification command
result
```

---

# 2. Create the recovery branch

Run:

```bash
git fetch --all --prune
git checkout atomicity-pr21-enforcement-final
git reset --hard e15fbd121d6450730f02646af2f5e810ff21a5ad
git status --short
git rev-parse HEAD

git checkout -b ci-guardrails-recovery-e15fbd1
mkdir -p build/ci/recovery
```

The working tree must be clean before diagnosis.

Record:

```bash
java -version 2>&1 | tee build/ci/recovery/java-version.txt
python3 --version | tee build/ci/recovery/python-version.txt
./gradlew --version | tee build/ci/recovery/gradle-version.txt
git log --oneline -30 | tee build/ci/recovery/recent-commits.txt
```

If GitHub CLI authentication is available, retrieve the original logs:

```bash
gh run view 29129457251 \
  --repo panospao7/Cost-agregator \
  --log > build/ci/recovery/github-run-29129457251.log
```

Do not block recovery if those historical logs are unavailable.

---

# 3. Phase R1 — Produce a truthful failure inventory

## 3.1 Static suite

Create an isolated Python environment:

```bash
python3 -m venv .venv-ci
source .venv-ci/bin/activate
python -m pip install --upgrade pip
pip install pytest pyyaml
```

Run:

```bash
rm -rf build/ci/static-guards

python3 scripts/ci/verify_guard_registry.py \
  2>&1 | tee build/ci/recovery/guard-registry.log

python3 scripts/ci/run_static_guard_suite.py \
  2>&1 | tee build/ci/recovery/static-suite.log
```

Read the structured result:

```bash
cat build/ci/static-guards/summary.md
python3 - <<'PY'
import json
from pathlib import Path

data = json.loads(
    Path("build/ci/static-guards/summary.json").read_text(encoding="utf-8")
)
for result in data["results"]:
    if result["outcome"] != "pass":
        print(
            result["name"],
            result["outcome"],
            result["exit_code"],
            result["log_path"]
        )
PY
```

The suite already expands `scripts/test_verify_*.py` and `scripts/ci/test_*.py`; do not waste time implementing glob expansion again. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/e15fbd121d6450730f02646af2f5e810ff21a5ad/scripts/ci/run_static_guard_suite.py))

For every failed component, run its exact command directly and save its output.

Examples:

```bash
python3 scripts/verify_pii_logging_boundaries.py \
  --fail-on-violation \
  2>&1 | tee build/ci/recovery/pii-guard.log

python3 scripts/ci/guard_ratchet.py \
  --guard-name db_access \
  --command "python3 scripts/verify_db_access_boundaries.py --fail-on-violation" \
  --baseline config/baselines/db_access.json \
  --fail-on-violation \
  --ci-mode \
  2>&1 | tee build/ci/recovery/db-ratchet.log

python3 -m pytest \
  scripts/test_verify_*.py \
  scripts/ci/test_*.py \
  -v --tb=short \
  2>&1 | tee build/ci/recovery/guard-tests.log
```

## 3.2 Classify every static failure

Use exactly one classification:

### A. Real new violation

Fix the application code through the documented legal architecture path.

Do not update the baseline.

### B. False positive

Fix the guard parser and add:

- one negative fixture proving the false positive passes,
- one positive fixture proving the forbidden pattern still fails.

### C. Resolved baseline finding

The current ratchet exits `1` for either new **or resolved** findings. Therefore a legitimate reduction requires a reviewed local baseline update. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/e15fbd121d6450730f02646af2f5e810ff21a5ad/scripts/ci/guard_ratchet.py))

Verify the removal first, then run:

```bash
python3 scripts/ci/guard_ratchet.py \
  --guard-name GUARD_NAME \
  --command "EXACT GUARD COMMAND" \
  --baseline config/baselines/GUARD_NAME.json \
  --fail-on-violation \
  --update-baseline
```

Review the diff manually:

```bash
git diff -- config/baselines/
```

A baseline update is valid only when:

- current findings are a strict subset of the old findings,
- no renamed/reformatted fingerprint hides a new violation,
- no production violation was merely moved,
- the recovery ledger explains every removed fingerprint.

### D. Guard infrastructure failure

Fix the runner/registry/ratchet, add an infrastructure regression test, and retain fail-closed behavior.

## 3.3 Required R1 deliverable

Commit only the diagnostic ledger and any diagnostic-only CI improvements:

```text
chore(ci): capture exact guardrail recovery baseline
```

No architecture fixes belong in R1.

---

# 4. Phase R2 — Fix Static Guards first

Static Guards should be repaired before `:app:check`, because Gradle invokes the DB ratchet too and may be failing for the same baseline/fingerprint reason.

Required verification:

```bash
python3 scripts/ci/verify_guard_registry.py

python3 -m pytest \
  scripts/test_verify_*.py \
  scripts/ci/test_*.py \
  -v --tb=short

python3 scripts/ci/run_static_guard_suite.py
```

Acceptance criteria:

```text
guard registry exit: 0
guard pytest exit: 0
static suite exit: 0
infra errors: 0
blocking violations: 0
```

Improve CI observability while touching the suite:

1. Always append `summary.md` to `$GITHUB_STEP_SUMMARY`.
2. Upload individual guard logs even when the suite fails.
3. Print the names of failed guards to the console.
4. Preserve exit code `1` for violations and `2` for infrastructure errors.

Commit:

```text
fix(ci): make static guard suite truthful and green
```

---

# 5. Phase R3 — Isolate `:app:check`

First list the task graph:

```bash
./gradlew :app:check --dry-run \
  2>&1 | tee build/ci/recovery/app-check-dry-run.log
```

Then run the architecture tasks individually:

```bash
./gradlew :app:verifyRoomSchemaSnapshots \
  -PstrictRoomSchemas=true \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/room-schema.log

./gradlew :app:verifyNoIgnoredGrowth \
  -PmaxIgnoredTests=29 \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/ignored-growth.log

./gradlew :app:checkLifecycleBypasses \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/lifecycle-bypasses.log

./gradlew :app:checkLifecycleBypass \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/lifecycle-bypass.log

./gradlew :app:checkRawMoneyAggregates \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/raw-money.log

./gradlew :app:checkDirectTimeCalls \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/direct-time.log

./gradlew :app:verifyDbAccessBoundaries \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/db-gradle-guard.log
```

If a task name does not exist, confirm the real name using:

```bash
./gradlew :app:tasks --all
```

Finally:

```bash
./gradlew :app:check \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/app-check.log
```

Important: missing guard scripts, baselines, or policy files must fail. The current DB Gradle task warns and returns when `guard_ratchet.py` is missing, which is fail-open behavior and should be changed to `GradleException`. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/e15fbd121d6450730f02646af2f5e810ff21a5ad/app/build.gradle.kts))

Acceptance criteria:

```text
every individual verification task: exit 0
:app:check: exit 0
no missing-script warning
no silent skip
static DB ratchet and Gradle DB ratchet produce the same outcome
```

Commit:

```text
fix(ci): align Gradle check with canonical guard policy
```

---

# 6. Phase R4 — Diagnose the unit-test timeout correctly

Do not assume there is only one hanging test. The existing test ledger reports:

- thousands of tests,
- large existing failure families,
- `UncompletedCoroutinesError`,
- a JVM instrumentation-agent crash,
- missing migration assets,
- DataStore collisions,
- and a likely full-suite hang. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/e15fbd121d6450730f02646af2f5e810ff21a5ad/TEST_FAILURE_LEDGER.md))

## 6.1 Determine whether the delay is compilation or execution

Run separately:

```bash
./gradlew :app:kspDebugKotlin \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/ksp-debug.log

./gradlew :app:kspDebugUnitTestKotlin \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/ksp-unit.log

./gradlew :app:hiltJavaCompileDebugUnitTest \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/hilt-unit.log

./gradlew :app:compileDebugUnitTestKotlin \
  --no-daemon --stacktrace --info \
  2>&1 | tee build/ci/recovery/test-compile.log
```

Record duration for each.

## 6.2 Run test shards

Inventory test packages:

```bash
find app/src/test -type f -name '*Test.kt' \
  | sort > build/ci/recovery/unit-test-files.txt
```

Run independent shards:

```bash
./gradlew :app:testDebugUnitTest \
  --tests "com.yourname.expensetracker.architecture.*" \
  --no-daemon --stacktrace

./gradlew :app:testDebugUnitTest \
  --tests "com.yourname.expensetracker.data.*" \
  --no-daemon --stacktrace

./gradlew :app:testDebugUnitTest \
  --tests "com.yourname.expensetracker.domain.*" \
  --no-daemon --stacktrace

./gradlew :app:testDebugUnitTest \
  --tests "com.yourname.expensetracker.ui.*" \
  --no-daemon --stacktrace
```

Add more shards based on the actual package tree.

Each shard must produce:

```text
number executed
number passed
number failed
number skipped
duration
last class started
failure families
whether JVM exited normally
```

## 6.3 Decision rules

### If compilation consumes most of the 30 minutes

Optimize the workflow/cache first. Increase timeout only after recording measured clean-build duration.

### If the JVM agent crashes

Test controlled forking:

- one parallel fork,
- bounded `forkEvery`,
- separate large packages into CI matrix jobs.

Do not suppress the crash.

### If coroutine tests hang

Inspect for:

- unfinished child jobs,
- `first()` on flows that never emit,
- real delays inside `runTest`,
- unclosed dispatchers/executors,
- DataStore scopes not cancelled,
- application scopes surviving test completion.

### If hundreds of genuine failures remain

Do not call the unit gate green.

Create a separate test-stabilization workstream. Any temporary quarantine must be explicit, shrinking, owner-reviewed, expiring, and must not include release-critical privacy, migration, restore, worker, money, receipt, recurring, or import tests.

Commit unit fixes in small family-based commits, not one mass commit.

---

# 7. Phase R5 — Migration proof

The existing migration job is insufficient because:

1. It does not run on this feature-branch push.
2. It uses JVM-style `--tests` filtering with `connectedDebugAndroidTest`; verify whether that filtering actually selects the intended instrumentation tests. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/e15fbd121d6450730f02646af2f5e810ff21a5ad/.github/workflows/ci.yml))

Inventory migration tests:

```bash
find app/src/androidTest -type f \
  \( -iname '*Migration*Test.kt' -o -iname '*DatabaseMigration*.kt' \) \
  -print
```

Run the exact fully qualified instrumentation class through Android runner arguments, for example:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=FULLY_QUALIFIED_TEST_CLASS \
  --no-daemon --stacktrace
```

Modify migration CI so it runs on:

```text
pull_request to main/master
workflow_dispatch
main/master push
```

Migration acceptance criteria:

- real emulator execution occurred,
- intended migration classes were executed,
- v145→latest chain passes,
- representative non-empty data survives,
- fresh/latest and migrated/latest schemas are compared,
- report and XML artifacts are uploaded,
- missing tests/artifacts fail the job,
- exact tested SHA is recorded.

Commit:

```text
fix(ci): execute and prove Room migrations on pull requests
```

---

# 8. Phase R6 — Final CI structure

Required blocking jobs:

```text
Validate Workflow
Static Guards
Unit Tests
Lint & Check
Release Check
Migration Proof
```

Instrumented tests may remain a separate release-candidate gate only if that policy is explicitly documented.

Add command logging with `tee` while preserving the real exit code:

```bash
set -o pipefail
./gradlew ... 2>&1 | tee build/ci/command.log
```

Upload:

```text
static guard summary and per-guard logs
unit-test XML/HTML
Gradle console log
lint report
Room verification output
migration instrumentation XML/HTML
release verification report
```

Do not prioritize Node.js action warnings until the functional failures are resolved.

---

# 9. Phase R7 — Final verification order

Run locally in this exact order:

```bash
python3 scripts/ci/verify_guard_registry.py

python3 -m pytest \
  scripts/test_verify_*.py \
  scripts/ci/test_*.py \
  -v --tb=short

python3 scripts/ci/run_static_guard_suite.py

./gradlew :app:verifyRoomSchemaSnapshots \
  -PstrictRoomSchemas=true \
  --no-daemon --stacktrace

./gradlew :app:verifyNoIgnoredGrowth \
  -PmaxIgnoredTests=29 \
  --no-daemon --stacktrace

./gradlew :app:testDebugUnitTest \
  --no-daemon --stacktrace

./gradlew :app:lintDebug \
  --no-daemon --stacktrace

./gradlew :app:assembleDebug \
  --no-daemon --stacktrace

./gradlew :app:check \
  --no-daemon --stacktrace

./gradlew :app:assembleRelease \
  --no-daemon --stacktrace

python3 scripts/verify_release_artifact.py \
  --fail-on-violation
```

Then push one candidate SHA and manually dispatch migration proof for that same SHA if necessary.

Do not produce another commit while CI is running. The workflow uses `cancel-in-progress: true`, so newer pushes cancel older runs and destroy useful evidence. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/e15fbd121d6450730f02646af2f5e810ff21a5ad/.github/workflows/ci.yml))

---

# 10. Documentation reconciliation

Only after one complete green run:

## Update `docs/ci/LATEST_CI_VERIFICATION.md`

Record:

```text
exact SHA
branch
GitHub run ID
date
job results
guard counts
unit count/pass/fail/skip
migration versions tested
artifact names
known non-blocking limitations
```

The current document references SHA `422b8a6` and claims required jobs are blocking even though the later exact branch run failed. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/e15fbd121d6450730f02646af2f5e810ff21a5ad/docs/ci/LATEST_CI_VERIFICATION.md))

## Correct `CI_GUARDRAILS_BASELINE.md`

Remove or correct stale claims such as:

- “all complete” before green CI,
- old warning-mode counts,
- outdated workflow steps,
- claims that ratchet decreases are automatically promoted if the actual implementation requires a reviewed local baseline update.

The current baseline document contains historical descriptions that no longer match the final workflow and registry. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/e15fbd121d6450730f02646af2f5e810ff21a5ad/docs/ci/CI_GUARDRAILS_BASELINE.md))

## Update master tracker truthfully

Recommended status after recovery:

- MIT-001: DONE only if assemble, unit, lint and check are green and blocking.
- MIT-002: DONE only if registry, all guards and guard tests are green and blocking.
- MIT-003: PARTIAL unless every listed missing guard or accepted replacement exists.
- MIT-004: PARTIAL until real migration execution and schema/data proof run in CI.
- MIT-005: PARTIAL until ignored/failing test debt is measured and cannot grow.

Commit:

```text
docs(ci): record verified green guardrail integration
```

---

# 11. Required orchestrator reporting format

After every phase, report:

```text
PHASE:
START SHA:
END SHA:
FILES CHANGED:
COMMANDS RUN:
RESULTS:
ROOT CAUSE:
BASELINE/ALLOWLIST CHANGES:
TESTS ADDED:
REMAINING BLOCKERS:
NEXT PHASE:
```

Every “DONE” statement must point to:

- a command,
- an exit code,
- a commit SHA,
- and, where applicable, a GitHub Actions run.

---

# 12. Immediate first assignment

The first agent assignment must be:

```text
Run Phase R1 only.

Do not modify production code.
Do not update baselines.
Do not modify allowlists.

Produce:
1. build/ci/static-guards/summary.json
2. the names and logs of every failed guard
3. the first failing :app:check task
4. compile-vs-test timing for testDebugUnitTest
5. docs/ci/CI_GUARDRAILS_RECOVERY_LEDGER.md
6. a proposed root-cause-ranked R2 plan
```

After R1, the orchestrator should dispatch independent agents for:

```text
Agent A — static guard failure
Agent B — Gradle check failure
Agent C — unit compilation/timing diagnosis
Agent D — unit-test shard census
Agent E — migration-test inventory and execution proof
Agent F — reviewer checking that no baseline/allowlist was weakened
```

Agents A and B may share a root cause. Their changes must not be merged independently until the orchestrator compares the static and Gradle DB-ratchet outputs.