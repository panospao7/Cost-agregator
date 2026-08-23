# GR-00..GR-04 Validation Findings

Status: pending fixes (do NOT mark DONE/GREEN anywhere; several gates below are FAIL or BLOCKED).

Validation executed: 2026-08-23, workstation Windows / PowerShell 5.1, Python 3.13.2, pytest 9.0.2.
Worktree: `GR00-worktree`, branch `gr-00-local`, HEAD `247b21c4618690f4a48ccdc0eecc0cfc7450ef32`.
No code changes were made during validation. Raw logs: `%TEMP%\opencode\gr-validation\`.

## Verdict summary

| Checklist section | Command(s) | Result |
| --- | --- | --- |
| 1. Full Python sweep | `python -m pytest scripts -q` | **FAIL** — 372 failed / 1841 passed / 22 skipped (463 s) |
| 2. GR-00 evidence capture | `pytest scripts/ci/test_capture_db_guard_evidence.py -v` | **FAIL** — 14 failed / 114 passed / 8 skipped |
| 2. GR-01 v2 model + legacy parity | two pytest command lines | **FAIL** — 27 failed / 116 passed and 89 failed / 205 passed |
| 2. GR-02 suites | `pytest scripts/test_migrate_db_policy_signatures.py scripts/test_db_guard_room_inventory.py -v` | **FAIL** — 138 failed / 73 passed |
| 2. GR-02 CLI | `python scripts/migrate_db_policy_signatures.py --check` | **PASS** — exit 1, input=99 resolved=9 unresolved=90 duplicateMutationKeys=0 (exact match) |
| 2. GR-02 reproducibility | `--write-candidate --output build/tmp-regen.yml` + `fc /b` | **PASS** — byte-identical (`fc /b`: no differences) |
| 2. GR-03 suites | five pytest targets | **FAIL** — 77 failed / 200 passed / 9 skipped |
| 2. GR-03 CLI | `verify_production_source_roots --root . --manifest ...` | **PASS** — exit 0, silent |
| 2. GR-04 decoupling | three pytest targets | **FAIL** — 89 failed / 206 passed |
| 3. Guard CLIs (4 rows) | source roots / inventory-only / migrate --check / full gate | **PASS 4/4** — all match expected states exactly |
| 4. Gradle fixture test | `:app:testDebugUnitTest --tests "*DbGuardPolicyFixtureTest*"` | **PASS** — BUILD SUCCESSFUL, 73 tests PASSED, 0 failed |
| 4. compileDebugKotlin | `./gradlew :app:compileDebugKotlin` | **PASS** — BUILD SUCCESSFUL |
| 4. full unit-test suite + check | `testDebugUnitTest`, `:app:check` | **NOT RUN** — aborted by operator (note: both are JVM-only; no emulator required) |
| 5. Two-run evidence gate | capture run-1 | **BLOCKED** — dirty checkout → fail-closed rejection exit 2, no bundle written (by design); run-2 not attempted |
| 6. Audit grep 1 | pinned-count identifiers | Nuanced — hits are rejection tests/docs plus non-test unquoted identifiers in production script (see below) |
| 6. Audit grep 2 | `app/src/main/java` in scripts | 535 hits incl. DB-guard modules themselves (interpretation-dependent vs checklist note) |
| 6. Preservation diff | `git diff cf07b04b~1 --exit-code -- <5 paths>` | **FAIL** — exit 1; candidate file differs (v1→v2 rewrite); other four paths silent |

Closing note of the checklist requires ALL sections pass before PR/merge; this branch is NOT validated.

## Primary root cause of the 372 Python failures (platform-independent)

`_absolute_root_anchor()` compares a list slice against a tuple, which is always False in Python:

- `scripts/db_guard/room_inventory.py:133` and `:135`
- `scripts/db_guard/declaration_scanner.py:555` and `:557`

```python
parts = os.path.normpath(root_abs).split(os.sep)   # parts is a LIST
if parts[-3:] == ("src", "main", "java"):          # list != tuple -> ALWAYS False
```

Live verification: `_absolute_root_anchor("<tmp>\\app\\src\\main\\java")` returns `None`;
a minimal `build_room_inventory(tmp_fixture_root)` outside pytest returns
`diagnostics=('DB_ROOM_SOURCE_EMPTY', 'DB_ROOM_SOURCE_UNREADABLE')` with zero DAOs for a
valid single-DAO fixture.

Cascade:

1. Room inventory on synthetic fixtures fails closed (empty daos/methods/mutators).
2. `declaration_scanner.declared_root_pairs()` drops implicit absolute roots ->
   `policy_v2_evidence._declared_relative_root_set()` returns
   `(None, DB_SOURCE_ROOT_UNDECLARED)` -> every v2 policy-evidence group reports
   `POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN` with reason `source-roots-unresolved`.
3. Failure counts by file in the full sweep:

   | File | Failed |
   | --- | --- |
   | scripts/test_db_guard_room_inventory.py | 106 |
   | scripts/test_verify_db_access_v2.py | 66 |
   | scripts/test_db_guard_declaration_scanner.py | 53 |
   | scripts/test_migrate_db_policy_signatures.py | 32 |
   | scripts/test_verify_db_access_boundaries.py | 23 |
   | scripts/test_db_guard_policy_v2_evidence.py | 22 |
   | scripts/test_db_guard_source_roots.py | 16 |
   | scripts/ci/test_capture_db_guard_evidence.py | 14 |
   | scripts/ci/test_gradle_db_guard_contract.py | 13 |
   | scripts/test_kotlin_callable_parser.py | 9 |
   | scripts/test_db_guard_scanner_d4.py | 5 |
   | scripts/test_db_guard_policy_v2.py | 4 |
   | scripts/ci/test_guard_ratchet_v2.py | 4 |
   | scripts/test_db_guard_source_roots_integration.py | 2 |
   | scripts/ci/test_verify_production_source_roots.py | 1 |
   | scripts/test_db_guard_policy_v2_current_repo.py | 1 |
   | scripts/ci/test_guard_findings.py | 1 |
   | Total | 372 |

Real-repo CLIs are unaffected because the manifest declares RELATIVE roots
(`config/guards/production_source_roots.yml` -> `path: app/src/main/java`), so the broken
absolute-root branch never executes in production scans. This explains why Section 3 guard
CLIs match every expected state while the unit suites are red.

### Secondary failure families (GR-00 suite, 14 failures)

- Test-fixture defect A: `ConfigurableFakeRunner.__call__` matches commands via exact
  element membership (`"verify_db_access_boundaries.py" in argv`) while command matrices
  pass repo-relative prefixed tokens (`"scripts/verify_db_access_boundaries.py"`), so those
  branches never fire and the fallthrough returns exit 0 where fixtures expect blocked
  codes. Verified by instrumenting the runner (spy): db-cli call returned `(0, 'ok')`.
- Test-fixture defect B: ad-hoc runners returning rc=2 for ALL commands (including
  `git status`) trip the dirty-checkout rejection path, which intentionally writes NO
  bundle; tests then fail reading a missing `evidence.json`
  (`FileNotFoundError ...\run-1\commands\00-leaky.log` family).

## Per-command details

- Full sweep: exit 1; summary line `372 failed, 1841 passed, 22 skipped in 463.33s`.
- GR-01a: exit 1. Notable passes/fails:
  - PASS: `scripts/test_db_guard_active_policy_blocked.py::test_active_db_gate_reports_blocked_pre_v2`
    (gate characterization holds: exit 2, one umbrella stderr line,
    diagnostics `[DB_POLICY_SOURCE_EVIDENCE_INVALID]`).
  - FAIL: `scripts/test_db_guard_policy_v2_current_repo.py::test_candidate_signatures_rejected_by_v2_loader`
    — the committed v2 candidate now LOADS successfully under the v2 loader; the test
    premise ("v1-shaped signatures candidate must not be accepted") is stale relative to
    the regenerated v2 candidate.
- GR-01b: exit 1 (89 failed / 205 passed).
- GR-02 CLI: exit 1 with `input=99 resolved=9 unresolved=90 duplicateMutationKeys=0`;
  unresolved breakdown BARRIER_MODE_UNRESOLVED=47 CALLABLE_MISSING=8 DAO_IDENTITY_UNRESOLVED=7
  PARSER_UNCERTAIN=28 — matches the checklist expectation exactly.
- Checklist drift: `--write-candidate build/tmp-regen.yml` errors with
  `unrecognized arguments`; current CLI requires `--output`. Executed as
  `--write-candidate --output build/tmp-regen.yml` (exit 1 = outstanding debt; file still
  written) then `fc /b` -> byte-identical to
  `config/guards/db_ownership_policy.signatures.candidate.yml`.
- GR-03 CLI: exit 0, zero output lines — matches "exit 0, silent".
- Section 3 inventory-only:
  `python scripts/verify_db_access_boundaries.py --inventory-only --findings-output <temp>`
  -> exit 2; stderr exactly `ERROR: DB access discovery infrastructure diagnostics present`;
  findings JSON diagnostics exactly `350 DB_ROOM_QUERY_UNCLASSIFIABLE` +
  `1 DB_DAO_INHERITANCE_UNRESOLVED`; zero `DB_SOURCE_ROOT_*` codes;
  `statistics.trusted=false`; 0 findings. Matches expected Windows pre-existing debt.
- Section 3 full gate (all four config flags):
  `--fail-on-violation --ownership-policy config/guards/db_ownership_policy.yml
  --structural-exceptions config/guards/db_structural_exceptions.yml
  --structural-manifest config/guards/db_structural_exceptions_expected_methods.yml
  --raw-query-policy config/guards/db_raw_query_classification.yml`
  -> exit 2; stderr exactly one umbrella line; diagnostics exactly
  `['DB_POLICY_SOURCE_EVIDENCE_INVALID']`; 0 findings; trusted=false.
  Blocked for the known POLICY reason, never structural-count — as expected.
- Section 4 environment blocker: worktree lacks `local.properties`; first Gradle run failed
  `SDK location not found`. Re-ran with session env var only
  `ANDROID_HOME=C:\Users\panos\AppData\Local\Android\Sdk` (no repo files created).
  - `DbGuardPolicyFixtureTest`: BUILD SUCCESSFUL in 6m31s; 73 PASSED, 0 FAILED
    (includes `manifest — structural exceptions has exactly 62 entries`).
  - `compileDebugKotlin`: BUILD SUCCESSFUL (up-to-date).
  - Full `:app:testDebugUnitTest` and `:app:check`: NOT RUN (operator aborted; concern was
    emulator availability — neither task requires an emulator).
- Section 5: run-1
  `python scripts/ci/capture_db_guard_evidence.py --root . --out build/guard-evidence/<SHA>/run-1`
  -> exit 2 with only an empty `run-1/commands/` directory and no bundle. Cause: porcelain
  status non-empty due to untracked `?? docs/ci/GR00-GR04_validation_checklist.md` ->
  dirty-rejection writes nothing by design. Gate precondition ("clean committed checkout")
  NOT met; run-2 not attempted. Validation artifacts were removed afterwards.
- Section 6 audits:
  - Grep 1 (pinned-count identifiers): hits are rejection tests + `docs/DB_WRITE_OWNERSHIP.md`,
    PLUS non-test unquoted occurrences in `scripts/verify_db_access_boundaries.py`
    (`_read_ownership_entries_for_evidence` at line 3286; comments/local uses at lines
    708, 944, 3409–3426). The module's own documented rule forbids only QUOTED key literals
    in non-test scripts; the checklist note ("rejection tests/docs only") reads stricter.
    Judgment needed on which rule governs.
  - Grep 2: 535 total hits across scripts (top: declaration-scanner tests 96,
    cancellation allowlist 54, room-inventory tests 49, boundaries tests 40, ratchet tests 35+...),
    including DB-guard production modules (`db_guard/policy_legacy.py` 12,
    `db_guard/source_roots.py` 5, `verify_db_access_boundaries.py` 19). Whether this satisfies
    "(remaining hits must be non-DB guards/fixtures/docs/data)" depends on interpretation;
    DB-guard sources do still reference the literal root path.
  - Preservation diff vs `cf07b04b~1`: exit 1. Only
    `config/guards/db_ownership_policy.signatures.candidate.yml` differs (full v1->v2
    schema rewrite: `schemaVersion: 2`, ownerFqcn/kind/parameterTypes/barrierMode fields,
    9 entries). `config/baselines/db_access.json`, `db_ownership_policy.yml`,
    `db_structural_exceptions.yml`, `app/src/main` are silent. Either the audit baseline SHA
    or the "(must be silent)" expectation conflicts with the intentional GR-02 v2 candidate
    evolution.

## Known-good state scorecard (checklist section 7 vs observed)

| Aspect | Expected | Observed |
| --- | --- | --- |
| Active DB gate | blocked exit 2 DB_POLICY_SOURCE_EVIDENCE_INVALID only | MATCH |
| Inventory-only | exit 2 Windows debt 350+1, no DB_SOURCE_ROOT_* | MATCH |
| Migration | exit 1 resolved=9/99 | MATCH |
| Meta-guard | exit 0 | MATCH (source-roots verifier silent exit 0) |
| Candidate | v2, 9 entries byte-reproducible | MATCH (byte-identical regen) |
| Structural pin | 62 retained | MATCH (Kotlin fixture asserts exactly 62) |

## Risks / follow-up

1. Fix the duplicated `_absolute_root_anchor` list-vs-tuple comparison (both files) and add a
   regression test; expect most of the 372 failures to flip.
2. Reconcile `test_capture_db_guard_evidence.py` fixtures (membership matching, dirty-path
   runners) OR the runner contract they pin.
3. Update checklist drift items: `--write-candidate --output ...` syntax; audit-3 base SHA /
   expectation given the intentional v2 candidate rewrite; clarify audit-grep rules.
4. Commit or remove the untracked checklist doc before re-running the Section 5 gate.
5. Re-run Section 4 remaining tasks (no emulator needed) after Python fixes land.
