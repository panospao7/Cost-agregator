# Local CI Reproduction

> **Purpose**: Run the same checks locally that the CI pipeline runs, before pushing.
> **Related**: `CI_GUARDRAILS_BASELINE.md` — full inventory of all guardrails.

> **Quickstart**: For a concise pre-push checklist and CI pipeline overview, see `docs/ci/developer-quickstart.md`.

---

## Introduction

Developers should run these commands *before* pushing to catch issues early. The CI pipeline at `.github/workflows/ci.yml` runs many of these same checks. Running them locally avoids wasted CI cycles and speeds up feedback.

Commands are grouped by category. Run the "Fast pre-push checks" section for a quick validation pass; run individual sections as needed.

---

## Prerequisites

- **JDK 17** (Temurin recommended)
- **Python 3.8+** (for Python guard scripts)
- **PowerShell** (for `currency_guardrails.ps1` on Windows) or **pwsh** (cross-platform)
- **`kotlin`** on `PATH` (for `.kts` guard scripts)
- **`actionlint`** (for workflow validation):
  ```bash
  # macOS
  brew install actionlint

  # Or download from GitHub releases:
  # https://github.com/rhysd/actionlint/releases
  ```

---

## Fast pre-push checks

```bash
# Workflow validation
actionlint

# Core Gradle checks
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:lintDebug --stacktrace
./gradlew :app:check --stacktrace
```

---

## Python guard scripts

```bash
python3 scripts/verify_event_writers.py --fail-on-violation
python3 scripts/verify_privacy_boundaries.py --root .
python3 scripts/verify_money_boundaries.py --root .
python3 scripts/verify_source_provenance_boundaries.py --root .
python3 scripts/verify_db_access_boundaries.py --fail-on-violation
python3 scripts/verify_cancellation_boundaries.py
python3 scripts/verify_ui_dao_boundaries.py --fail-on-violation
python3 scripts/verify_worker_boundaries.py --fail-on-violation
python3 scripts/verify_receipt_link_boundaries.py --fail-on-violation
python3 scripts/verify_import_lifecycle_boundaries.py --fail-on-violation
python3 scripts/verify_cloud_payload_boundaries.py --fail-on-violation
# WARNING MODE — no --fail-on-violation (52 pre-existing violations):
python3 scripts/verify_pii_logging_boundaries.py
python3 scripts/verify_di_release_boundaries.py --fail-on-violation
python3 scripts/verify_allowlist_compliance.py --fail-on-violation
python3 scripts/verify_time_boundaries.py --root . --allowlist config/guards/time_boundary_exceptions.yml --fail-on-violation
python3 scripts/verify_migration_matrix.py --fail-on-violation
# WARNING MODE — no --fail-on-violation (31 pre-existing @Ignore annotations):
python3 scripts/verify_ignored_test_budget.py
python -m pytest scripts/test_*.py -v
```

---

## PowerShell guardrails (Windows / pwsh)

```powershell
pwsh scripts/currency_guardrails.ps1 -SourceDir app/src/main/java -ProjectRoot .
```

---

## Room schema verification

```bash
./gradlew :app:verifyRoomSchemaSnapshots -PstrictRoomSchemas=true --stacktrace
```

---

## Ignored test budget

```bash
# Gradle guard (checks count doesn't exceed threshold)
./gradlew :app:verifyNoIgnoredGrowth -PmaxIgnoredTests=29 --stacktrace

# Python guard (validates reasons, categorizes, checks denylist)
python3 scripts/verify_ignored_test_budget.py
```

---

## Architecture guard tests

Runs all tests whose class name contains `ArchitectureGuard`:

```bash
./gradlew :app:testDebugUnitTest --tests "*ArchitectureGuard*" --stacktrace
```

For the full set of architecture tests (including guards, contracts, seeded violations):

```bash
./gradlew :app:testDebugUnitTest \
  --tests "*ArchitectureGuard*" \
  --tests "*GuardSeededViolation*" \
  --tests "*MoneyBoundaryGuard*" \
  --tests "*CancellationPropagation*" \
  --tests "*CancellationSafe*" \
  --tests "*Engine5PrimitiveGuard*" \
  --tests "*ExpenseDaoMutationAccess*" \
  --stacktrace
```

---

## Instrumented tests (emulator required)

Requires a running Android emulator (API 34, Google APIs):

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

### Migration tests on emulator

The `DatabaseMigrationMatrixTest` requires an emulator. See the full procedure at:
- **`docs/ci/MIGRATION_TEST_PROCEDURE.md`**

---

## Release verification

Requires Android SDK build-tools (aapt2 + apksigner):

```bash
# Build and verify the release APK
./gradlew :app:assembleRelease --stacktrace
python3 scripts/verify_release_artifact.py --fail-on-violation
```

Or verify a specific APK:
```bash
python3 scripts/verify_release_artifact.py --apk path/to/release.apk --fail-on-violation
```

The script auto-detects aapt2/apksigner from `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or common SDK paths. Requires build-tools 34+.

---

## DB access boundary tests

```bash
python -m pytest scripts/test_*.py -v
```

---

## DB access boundary Gradle guard (PR-GR-01)

The `:app:verifyDbAccessBoundaries` Gradle task **fails closed**. Before running
the ratchet it validates every required input; a missing / non-regular /
unreadable / outside-repository path is a `GradleException` — never a warning
or a silent skip.

### Required inputs (canonical defaults)

| Required input | Canonical repo path |
|---|---|
| Ratchet wrapper | `scripts/ci/guard_ratchet.py` |
| DB guard scanner | `scripts/verify_db_access_boundaries.py` |
| Ratchet baseline | `config/baselines/db_access.json` |
| Ownership policy | `config/guards/db_ownership_policy.yml` |
| Structural exceptions | `config/guards/db_structural_exceptions.yml` |
| Structural manifest | `config/guards/db_structural_exceptions_expected_methods.yml` |
| Source-root manifest | `config/guards/production_source_roots.yml` |

### Invocation

```bash
./gradlew :app:verifyDbAccessBoundaries --stacktrace
```

- The Python interpreter defaults to `python3` and can be overridden with
  `-PpythonExecutable=/path/to/python3`. A preflight `pythonExecutable --version`
  runs first; failure to launch Python (or a non-zero `--version` exit) is an
  infrastructure error — distinct from the ratchet's own exit 2. The Python
  contract mirror (`scripts/ci/gradle_db_guard_inputs.py`) surfaces it as a
  controlled `GradleDbGuardInputError` exception (code `python_preflight`); the
  Gradle task reports the same condition by failing the task with a
  `GradleException`. The ratchet itself reserves exit 2 for its own
  infrastructure failures (missing/malformed baseline, unlaunchable child
  command, unexpected child exit, or an exit-1 guard that emits no parseable
  fingerprints).
- The ratchet is invoked with repeatable single-token `--command-arg=<value>`
  arguments (argument list, `shell=False`) and `--ci-mode` — never a shell
  string with embedded paths.  Every ratchet child argument is encoded as one
  `--command-arg=<value>` list token (including option-like child values such
  as `--fail-on-violation`, `--ownership-policy`, `--structural-exceptions`,
  and `--structural-manifest`), so argparse can never re-parse them as the
  ratchet's own flags.
- Test-only path overrides (never used in production CI):
  `-PdbGuardRatchetPath=...`, `-PdbGuardScriptPath=...`,
  `-PdbGuardBaselinePath=...`, `-PdbGuardOwnershipPolicyPath=...`,
  `-PdbGuardStructuralExceptionsPath=...`,
  `-PdbGuardStructuralManifestPath=...`,
  `-PdbGuardSourceRootsManifestPath=...`.
  Relative override paths resolve against the repository root (`rootDir`),
  consistent with the canonical defaults; absolute overrides are used as-is.

### Failure messages

Exit 1 (policy violations — new or stale/resolved findings when
`--fail-on-violation` is enabled) directs developers to the canonical DB
write-ownership sources of truth:

- `config/guards/db_ownership_policy.yml`
- `config/guards/db_structural_exceptions.yml`
- `docs/DB_WRITE_OWNERSHIP.md`

The legacy `config/db_access_allowlist.yml` is **superseded** and is never
referenced by the guard.

### Contract tests

The Python helper `scripts/ci/gradle_db_guard_inputs.py` is the **contract
mirror** of the Gradle task's input validation. Invalid inputs are reported
through three distinct channels:

- **Python contract helper** — for any invalid input (outside the repository
  root, missing, non-regular, unreadable, or a failed Python preflight) the
  helper raises `GradleDbGuardInputError` with a controlled, machine-readable
  `code` (`outside_root`, `not_found`, `not_regular`, `not_readable`,
  `python_preflight`) — never a warning or a silent skip.
- **Ratchet process** — once inputs pass, the inner ratchet reports through
  its own process exit codes:
  - exit 0 — no new findings and no stale/resolved baseline entries (pass);
  - exit 1 — policy violation: new or stale/resolved findings detected when
    `--fail-on-violation` is enabled;
  - exit 2 — infrastructure/configuration failure (unlaunchable child
    command, malformed or unreadable baseline, unexpected child exit, or an
    exit-1 guard with no parseable fingerprints).
- **Gradle task** — the task converts the same invalid inputs into a
  `GradleException`, failing the task (fail closed); an infrastructure
  condition (for example the Python preflight) also fails the task, so a
  broken local setup can never degrade into a warning.

The parity tests in
`scripts/ci/test_gradle_db_guard_contract.py` assert that the seven required
inputs, the override property names, and the inner ratchet command
construction in `app/build.gradle.kts` stay in sync with that mirror, and that
the policy/manifest arguments are always passed explicitly (never gated on
override properties). They also assert every ratchet child argument is encoded
as a single `--command-arg=<value>` token and that relative override paths
resolve against the repository root. Whenever the Gradle validation changes,
update the mirror and keep the parity tests green.

```bash
python3 -m pytest scripts/ci/test_gradle_db_guard_contract.py -v
```

---

## CI job equivalents

| CI Job | Local command |
|--------|--------------|
| `validate-workflow` | `actionlint` |
| `unit-tests` (Gradle) | `./gradlew :app:testDebugUnitTest --stacktrace` |
| `unit-tests` (Schema) | `./gradlew :app:verifyRoomSchemaSnapshots -PstrictRoomSchemas=true --stacktrace` |
| `unit-tests` (Ignored tests) | `./gradlew :app:verifyNoIgnoredGrowth -PmaxIgnoredTests=29 --stacktrace` |
| `unit-tests` (PowerShell) | See **PowerShell guardrails** section above |
| `static-guards` | See **Python guard scripts** section above |
| `lint-and-check` | `./gradlew :app:lintDebug :app:assembleDebug :app:check --stacktrace` |
| `instrumented-tests` | `./gradlew :app:connectedDebugAndroidTest --stacktrace` |

---

## Troubleshooting

### `kotlin: command not found`
The `.kts` guard scripts invoked via Gradle tasks (`checkLifecycleBypasses`, `checkRawMoneyAggregates`, `checkDirectTimeCalls`) require `kotlin` on `PATH`. Install the Kotlin compiler:
```bash
# macOS
brew install kotlin

# Or via SDKMAN
sdk install kotlin
```

### `actionlint: command not found`
Install actionlint:
```bash
brew install actionlint
```
Or download the binary from [GitHub releases](https://github.com/rhysd/actionlint/releases).

### `python3: command not found`
Ensure Python 3.8+ is installed and available as `python3`. On some systems use `python` instead. The `verifyDbAccessBoundaries` Gradle task accepts `-PpythonExecutable=/path/to/python3` to point at the interpreter; its preflight `--version` check fails the task with a `GradleException` when Python cannot be launched. The Python contract mirror (`scripts/ci/gradle_db_guard_inputs.py`) surfaces the same condition as a `GradleDbGuardInputError` exception (code `python_preflight`) — a Python exception, not a ratchet exit code; the ratchet uses exit 2 only for its own infrastructure failures.

### Gradle daemon issues
CI uses `--no-daemon`. For local runs, the daemon is fine, but if you encounter issues:
```bash
./gradlew --stop
./gradlew :app:clean :app:check --no-daemon --stacktrace
```
