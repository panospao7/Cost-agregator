# Latest CI Verification — Final Integration

## Commit
- SHA: `422b8a633a23304a8d4d3370e263000495edfff8`
- Branch: `atomicity-pr21-enforcement-final`
- CI Run: Expected to trigger on push to feature branch

## CI Jobs (Required)

| Job | Purpose | Blocking |
|-----|---------|----------|
| Validate Workflow | actionlint YAML validation | ✅ |
| Static Guards | 17 guards + ratchets + guard registry validation | ✅ |
| Unit Tests | Gradle testDebugUnitTest + Room schema + ignored-test count | ✅ |
| Lint & Check | lintDebug + assembleDebug + :app:check | ✅ |
| Release Check | assembleRelease + aapt2/apksigner verification | ✅ |
| Instrumented Tests | Emulator (Pixel_8a) | ✅ |

## Guard Status (17 guards)

| Guard | Mode | Baseline |
|---|---|---|
| guard_registry | blocking (validator) | — |
| source_provenance | blocking | — |
| ui_dao | blocking | — |
| worker | blocking | — |
| receipt_link | blocking | — |
| import_lifecycle | blocking | — |
| cloud_payload | blocking | — |
| pii_logging | blocking (strict-zero) | — |
| di_release | blocking | — |
| allowlist_compliance | blocking | — |
| ignored_test_budget | blocking | — |
| lint_baseline_policy | blocking | — |
| guard_tests | blocking (pytest) | — |
| cancellation | ratchet | 86 findings |
| privacy | ratchet | 1 finding |
| db_access | ratchet | 15 findings |
| event_writers | ratchet | 15 findings |
| money | ratchet | 0 findings |
| migration_matrix | ratchet | 0 findings |

## Branch Protection

Required checks to configure in GitHub Settings:
1. Validate Workflow
2. Static Guards
3. Unit Tests
4. Lint & Check
5. Release Check
6. Instrumented Tests (optional, emulator-dependent)

## Verification Commands

```bash
# All guard tests
python -m pytest scripts/test_verify_*.py scripts/ci/test_*.py -v

# Guard suite
python scripts/ci/run_static_guard_suite.py

# Registry validation
python scripts/ci/verify_guard_registry.py

# Gradle
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleRelease --stacktrace
```

## Local Verification Results (H7.3)

```
python -m pytest scripts/test_verify_*.py scripts/ci/test_*.py -v --tb=line
```

- **Date**: 2026-07-11
- **Result**: 133 passed, 1 failed
- **Failed**: `test_missing_denylist_is_fatal` — timeout (10s) during full source scan on Windows before reaching the denylist check. Pre-existing issue; not a regression from H7 documentation changes.
- **Total**: 134 tests collected across 14 test files.
```
