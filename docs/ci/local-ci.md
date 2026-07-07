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
# WARNING MODE — no --fail-on-violation (scaffold guard):
python3 scripts/verify_di_release_boundaries.py
python3 scripts/verify_allowlist_compliance.py --fail-on-violation
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
./gradlew :app:verifyNoIgnoredGrowth -PmaxIgnoredTests=310 --stacktrace

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

---

## DB access boundary tests

```bash
python -m pytest scripts/test_*.py -v
```

---

## CI job equivalents

| CI Job | Local command |
|--------|--------------|
| `validate-workflow` | `actionlint` |
| `unit-tests` (Gradle) | `./gradlew :app:testDebugUnitTest --stacktrace` |
| `unit-tests` (Schema) | `./gradlew :app:verifyRoomSchemaSnapshots -PstrictRoomSchemas=true --stacktrace` |
| `unit-tests` (Ignored tests) | `./gradlew :app:verifyNoIgnoredGrowth -PmaxIgnoredTests=310 --stacktrace` |
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
Ensure Python 3.8+ is installed and available as `python3`. On some systems use `python` instead.

### Gradle daemon issues
CI uses `--no-daemon`. For local runs, the daemon is fine, but if you encounter issues:
```bash
./gradlew --stop
./gradlew :app:clean :app:check --no-daemon --stacktrace
```
