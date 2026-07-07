# Latest CI Verification — PR13

## Commit
- SHA: `564077512ec11d19bb58f210f5b5750f2b4fe855`
- Branch: `atomicity-pr21-enforcement-final`
- Trigger: Push to feature branch (broadened in PR10)

## CI Jobs

| Job | Status | Notes |
|-----|--------|-------|
| validate-workflow | ✅ Expected | actionlint v1.7.7 (pinned), validates all workflow YAML |
| static-guards | ✅ Expected | 14 Python guards + pytest, 3 in warning mode |
| unit-tests | ✅ Expected | Gradle testDebugUnitTest + Room schema + ignored-test count |
| lint-and-check | ✅ Expected | lintDebug + assembleDebug + :app:check |
| instrumented-tests | ⚠️ Non-blocking | Emulator tests, continue-on-error |

## Guard Status

| Guard | Mode | Pre-existing violations |
|---|---|---|
| privacy_boundaries | Blocking | — |
| db_access_boundaries | Blocking | — |
| event_writers | Blocking | — |
| money_boundaries | Blocking | — |
| source_provenance_boundaries | Blocking | — |
| cancellation_boundaries | Warning | ~248 |
| ui_dao_boundaries | Blocking | 3 |
| worker_boundaries | Blocking | 16 |
| receipt_link_boundaries | Blocking | 0 |
| import_lifecycle_boundaries | Blocking | 0 |
| cloud_payload_boundaries | Blocking | 0 |
| pii_logging_boundaries | Warning | ~51 |
| di_release_boundaries | Blocking | 1 |
| migration_matrix | Blocking | 0 |
| ignored_test_budget | Blocking | baseline 29 |
| allowlist_compliance | Blocking | — |

## Verification Commands
```bash
# Local verification (run before push)
actionlint
python -m pytest scripts/test_verify_*.py -v
python scripts/verify_migration_matrix.py --fail-on-violation
python scripts/verify_allowlist_compliance.py --fail-on-violation
```

## Last Updated
2026-07-07 — PR13
