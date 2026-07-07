# Developer Quickstart — CI Static Guardrails

## Before pushing
Run these checks locally. Most take <10 seconds.

### Fast pre-push (required)
```bash
actionlint                                          # Validate CI workflows
./gradlew :app:assembleDebug --stacktrace           # Compile
./gradlew :app:check --stacktrace                   # All wired checks + lint + tests
```

### Static guard scripts
```bash
python3 scripts/verify_privacy_boundaries.py --root .
python3 scripts/verify_db_access_boundaries.py --fail-on-violation
python3 scripts/verify_event_writers.py --fail-on-violation
python3 scripts/verify_money_boundaries.py --root .
python3 scripts/verify_source_provenance_boundaries.py --root .
python3 scripts/verify_cancellation_boundaries.py
python3 scripts/verify_ui_dao_boundaries.py --fail-on-violation
python3 scripts/verify_worker_boundaries.py --fail-on-violation
python3 scripts/verify_receipt_link_boundaries.py --fail-on-violation
python3 scripts/verify_import_lifecycle_boundaries.py --fail-on-violation
python3 scripts/verify_cloud_payload_boundaries.py --fail-on-violation
python3 scripts/verify_pii_logging_boundaries.py
python3 scripts/verify_di_release_boundaries.py
python3 scripts/verify_migration_matrix.py --fail-on-violation
python3 scripts/verify_ignored_test_budget.py
python3 scripts/verify_allowlist_compliance.py --fail-on-violation
python -m pytest scripts/test_*.py -v
```

### If you add a new guard
1. Copy `scripts/guard_template.py`
2. Implement `scan_file()` with your detection logic
3. Create allowlist in `scripts/allowlists/`
4. Add tests in `scripts/test_verify_*.py`
5. Add to CI in `.github/workflows/ci.yml` (static-guards job)
6. Document in `docs/ci/CI_GUARDRAILS_BASELINE.md` and `docs/ci/guard-framework.md`
7. Register allowlist in `scripts/verify_allowlist_compliance.py` YAML_ALLOWLISTS

### If a guard flags your code
1. Read the violation message — it includes file:line and suggested fix
2. Fix the code if it's a real issue
3. If the code is safe, add an entry to the guard's allowlist YAML with: reason, owner, expires
4. Do NOT remove or @Ignore guard tests

### Allowlist entry format
```yaml
- rule: G-EXAMPLE-01
  path: app/src/main/java/com/example/File.kt
  symbol: SomeClass.someMethod  
  reason: "Why this is safe"
  owner: "@github-handle"
  expires: "2027-01-01"  # or "permanent"
  linked_issue: "MIT-###"
```

### CI pipeline
| Job | Purpose | Blocking? |
|-----|---------|-----------|
| validate-workflow | Lint CI YAML | ✅ |
| static-guards | Python guard scripts | ✅ (with --fail-on-violation) |
| unit-tests | Gradle unit tests + schema + ignored count | ✅ |
| lint-and-check | Lint + assemble + :app:check | ✅ |
| instrumented-tests | Android instrumented tests (emulator) | ❌ (flaky) |

### Need help?
- Guard framework docs: `docs/ci/guard-framework.md`
- Baseline inventory: `docs/ci/CI_GUARDRAILS_BASELINE.md`
- Local CI guide: `docs/ci/local-ci.md`
