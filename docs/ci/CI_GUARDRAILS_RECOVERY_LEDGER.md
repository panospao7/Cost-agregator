# CI Guardrails Recovery Ledger

> **Purpose**: Tracks recovery attempts for CI guard suite failures. Each recovery phase (R1, R2, ...) is recorded as an atomic entry with evidence, root cause, and next-phase action items.
> **Status**: R1 partial — not green

---

## R1 — Baseline Capture and First Failure Analysis

| Field | Value |
|-------|-------|
| **Date** | 2026-08-05 |
| **Branch** | `atomicity-pr21-enforcement-final` |
| **SHA** | `e15fbd121d6450730f02646af2f5e810ff21a5ad` |
| **Session** | `ses_02d2d9399ffehlG2Fpf1wVSnzi` |
| **Overall status** | **R1 partial** — not green |

### Commands Executed

#### 1. Guard Registry Verification

```
python scripts/ci/verify_guard_registry.py
```

| Field | Value |
|-------|-------|
| Result | **PASS** (exit 0) |
| Evidence | 17 registered guards; all registered files exist; registry internally consistent; registry and CI manifest (17 guards) are consistent |

#### 2. Static Guard Suite

```
python scripts/ci/run_static_guard_suite.py
```

| Field | Value |
|-------|-------|
| Result | **FAIL** (exit 2) |
| Duration | 82.9s |
| Total guards | 19 |
| Passed | 13 |
| Failed blocking | 0 |
| Warning violations | 0 |
| Infrastructure errors | **6** |

##### Passed (13)

| Guard | Duration |
|-------|----------|
| guard_registry | 0.1s |
| source_provenance | 13.4s |
| ui_dao | 0.2s |
| worker | 0.3s |
| receipt_link | 1.1s |
| import_lifecycle | 20.2s |
| cloud_payload | 0.3s |
| pii_logging | 1.1s |
| di_release | 1.6s |
| allowlist_compliance | 0.2s |
| ignored_test_budget | 8.3s |
| lint_baseline_policy | 0.2s |
| guard_tests | 34.2s |

##### Infrastructure Errors (6)

| Guard | Duration | Error message |
|-------|----------|---------------|
| cancellation | 0.4s | `Guard exited with unknown code 9009` |
| privacy | 0.3s | `Guard exited with unknown code 9009` |
| db_access | 0.3s | `Guard exited with unknown code 9009` |
| event_writers | 0.3s | `Guard exited with unknown code 9009` |
| money | 0.2s | `Guard exited with unknown code 9009` |
| migration_matrix | 0.3s | `Guard exited with unknown code 9009` |

#### 3. Gradle Dry-Run and Unit Tests

| Command | Result |
|---------|--------|
| `./gradlew :app:check --dry-run --stacktrace` | **NOT COMPLETED** — session aborted before output |
| `./gradlew :app:testDebugUnitTest --no-daemon --stacktrace --info` | **NOT COMPLETED** — session aborted before output |

### Log Paths

| Log | Path |
|-----|------|
| Guard registry verification | `build/ci/recovery/guard-registry.log` |
| Static guard suite | `build/ci/recovery/static-suite.log` |
| Individual guard logs | `build/ci/static-guards/<guard_name>.log` |
| Suite summary (JSON) | `build/ci/static-guards/summary.json` |
| Suite summary (MD) | `build/ci/static-guards/summary.md` |
| Gradle dry-run | `build/ci/recovery/app-check-dry-run.log` (empty) |
| Gradle unit tests | `build/ci/recovery/unit-tests.log` (empty) |

### Root Cause Analysis

**Exit code 9009** on Windows indicates **"command not found"** at the shell level.

All 6 failing guards are ratchet-wrapped guards that invoke their inner command via `guard_ratchet.py`. The ratchet uses `subprocess.run(..., shell=True)` to execute the inner command, which contains a literal `python3` token. On Windows:

- `python3` resolves to `C:\Users\panos\AppData\Local\Microsoft\WindowsApps\python3.exe` — a Windows Store execution alias stub, not a real Python interpreter.
- When invoked through `cmd.exe` (via `shell=True`), this stub returns exit code **9009** (command not found).
- The 13 passing guards also use `python3` in their command list, but the suite engine executes them as a list (not through `shell=True`), so `python3.exe` resolves correctly.

**Key code path**: `scripts/ci/guard_ratchet.py` → `run_guard_command()` (line 331) → `subprocess.run(command, shell=True, ...)` — the `shell=True` causes `cmd.exe` to look up `python3` and hit the broken alias stub.

### Files Changed

None during R1.

### Recovery Hypothesis

The ratchet command `subprocess.run(..., shell=True)` in `guard_ratchet.py` passes a string command containing `python3` to `cmd.exe`. On Windows, `python3` resolves to the WindowsApps execution alias stub (not a real Python interpreter), producing exit code 9009.

**Fix direction for R2**:
- Replace `python3` with `sys.executable` in ratchet command strings, or
- Replace `shell=True` with `shell=False` and pass a list to `subprocess.run()`, or
- Use a platform-aware `python` resolution (e.g., `shutil.which("python3")` or `shutil.which("python")`).

### Next Phase

**R2: Ratchet subprocess fix** — Patch `guard_ratchet.py` and/or `run_static_guard_suite.py` to resolve `python3` via `sys.executable` or equivalent, eliminating the `shell=True` + WindowsApps alias failure mode. Add regression test coverage.

### What Is NOT Complete

- R1 partial: only baseline captured; recovery not attempted
- Gradle dry-run timing: **pending**
- Unit-test timing: **pending**
- PR acceptance: **not green**
- Final CI verification: **blocked** on R2 fix

---

<!-- Recovery entries above. New phases should be prepended. -->
