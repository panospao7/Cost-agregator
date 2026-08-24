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

---

## Re-validation round 2 (2026-08-24) — after commit `882b1bdc` "repair absolute-root anchoring and validation-finding fallout"

Checkout: clean (checklist doc now committed). HEAD `882b1bdcbc63873146a1c668326706f4ac345dd2`.

### Full sweep re-run

`python -m pytest scripts -q` -> exit 1; **357 failed / 1860 passed / 22 skipped** in 283 s
(previous round: 372/1841/22). NOT near-green; checklist sections 2–6 were therefore not re-run.

What the repair fixed (15 tests):

- All 13 previously failing `scripts/ci/test_capture_db_guard_evidence.py` cases (fixture
  membership matching + dirty-path runners).
- `scripts/test_db_guard_policy_v2_current_repo.py::test_candidate_signatures_rejected_by_v2_loader`
  (premise updated for the v2 candidate).
- Scanner/evidence edge cases (`test_missing_empty_and_unreadable_production_roots_fail_closed`,
  `test_invalid_context_at_scan_level_emits_controlled_diagnostic_and_clears_ranges`,
  `test_undeclared_kotlin_root_fails_closed_without_partial_results`,
  `test_path_outside_approved_roots_reports_path_outside_roots`).

### NEW root cause #2 (Windows-specific): drive-relative anchor

The tuple comparison is fixed (`tuple(parts[-3:]) == ...`) in BOTH copies, but the anchor is
rebuilt with a bare join whose first part is a bare drive letter:

```python
anchor_parts = parts[:-4]              # ['C:', 'Users', ..., '<project>']
return os.path.join(*anchor_parts)     # -> 'C:Users\\...'  (drive-RELATIVE!)
```

Live verification: `_absolute_root_anchor("C:\\...\\tmpXXX\\app\\src\\main\\java")` returns
`'C:Users\\panos\\AppData\\Local\\Temp\\tmpXXX'`. Consequences:

- `_declared_root_files`: walked files raise `ValueError` in `relative_to(anchor)` ->
  `unreadable=True`, files list empty -> inventory fails closed again with
  `('DB_ROOM_SOURCE_EMPTY', 'DB_ROOM_SOURCE_UNREADABLE')`.
- v2 evidence failures CHANGED SHAPE: `_declared_relative_root_set` no longer yields
  `source-roots-unresolved`; instead every policy path is reported as
  `POLICY_ERROR_V2_EVIDENCE_PATH_OUTSIDE_ROOTS` (observed:
  `test_garbage_kotlin_file_returns_controlled_errors_without_raising`,
  `test_two_dao_fqcns_behind_one_accessor_report_dao_ambiguous`, etc.).

Affected locations (kept "in exact parity", both must change together):
`scripts/db_guard/room_inventory.py::_absolute_root_anchor` and
`scripts/db_guard/declaration_scanner.py::_absolute_root_anchor`.
Suggested shape: preserve the separator after a drive-letter component (e.g.
`anchor_parts[0] + os.sep` when it matches `^[A-Za-z]:$`) or build via
`pathlib.PureWindowsPath(*anchor_parts)` semantics.

Evidence that this is platform-specific: the repair commit ADDED regression tests
`test_absolute_conventional_java_root_anchors` (room_inventory) /
`test_absolute_conventional_kotlin_root_anchors` (declaration_scanner); both FAIL on this
Windows workstation precisely at the anchor step, while Linux CI (no drive letters,
`parts[0] == ''` or `/`) would pass. This explains any CI-green/local-red divergence.

### Failure breakdown after round 2

| File | Failed |
| --- | --- |
| scripts/test_db_guard_room_inventory.py | 107 |
| scripts/test_verify_db_access_v2.py | 66 |
| scripts/test_db_guard_declaration_scanner.py | 51 |
| scripts/test_migrate_db_policy_signatures.py | 32 |
| scripts/test_verify_db_access_boundaries.py | 23 |
| scripts/test_db_guard_policy_v2_evidence.py | 22 |
| scripts/test_db_guard_source_roots.py | 16 |
| scripts/ci/test_gradle_db_guard_contract.py | 13 |
| scripts/test_kotlin_callable_parser.py | 9 |
| scripts/test_db_guard_scanner_d4.py | 5 |
| scripts/test_db_guard_policy_v2.py | 4 |
| scripts/ci/test_guard_ratchet_v2.py | 4 |
| scripts/test_db_guard_source_roots_integration.py | 2 |
| scripts/ci/test_capture_db_guard_evidence.py | 1 |
| scripts/ci/test_verify_production_source_roots.py | 1 |
| scripts/ci/test_guard_findings.py | 1 |

### Spot-check beyond the conditional scope

`verify_db_access_boundaries --inventory-only` re-run: exit 2, diagnostics exactly
`350 DB_ROOM_QUERY_UNCLASSIFIABLE + 1 DB_DAO_INHERITANCE_UNRESOLVED`, zero
`DB_SOURCE_ROOT_*` codes — production manifest-relative path unaffected by root cause #2.

### Round-2 follow-ups

1. Fix the drive-relative join in BOTH `_absolute_root_anchor` copies (parity), plus a
   Windows-drive regression case (`C:` first component).
2. Re-run full sweep; expect the ~350 cascade to collapse once anchoring succeeds on Windows.
3. Then execute checklist sections 2–6 per Revision 1 (including Section 5 two-run gate —
   checkout is now clean/committed).

---

## Re-validation round 3 (2026-08-24) — after commit `8eb97c43` "make absolute-root anchoring platform-universal (POSIX/UNC/drive)"

HEAD `8eb97c4370389007c9a9ee93eaba1fbb76e49a8c`; checkout clean.

### Full sweep

`python -m pytest scripts -q` -> exit 1; **151 failed / 2069 passed / 23 skipped** in 336 s
(round 2: 357/1860/22; round 1: 372/1841/22). NOT green/near-green -> checklist sections 2–6
NOT re-run per the stated condition. The single-cascade era is over: residue is now ~10
independent drift families.

### Drive-relative bug: FIXED and verified

Manual repro now discovers DAO + mutator with repository-relative POSIX canonical paths.
New platform-universal anchor handles POSIX `/`, UNC `\\`, and drive letters `C:\`.
Two Windows-only regression tests were added by the commit; one passes, one fails (below).

### Section-3 production CLIs re-verified (round 3)

| CLI | Result |
| --- | --- |
| `verify_production_source_roots --root . --manifest config/guards/production_source_roots.yml` | exit 0, silent |
| `verify_db_access_boundaries --inventory-only` | exit 2; exactly 350 DB_ROOM_QUERY_UNCLASSIFIABLE + 1 DB_DAO_INHERITANCE_UNRESOLVED; zero DB_SOURCE_ROOT_* |
| `migrate_db_policy_signatures --check` | exit 1; input=99 resolved=9 unresolved=90 duplicateMutationKeys=0 (exact) |
| full gate (four config flags) | exit 2; stderr exactly 1 umbrella line; diagnostics exactly `[DB_POLICY_SOURCE_EVIDENCE_INVALID]` |

### Residue breakdown (151) with mechanisms

| Family | Count | Observed mechanism (representative evidence) |
| --- | --- | --- |
| scripts/test_verify_db_access_v2.py | 66 | Fixture helper `_policy()` writes v1-shaped YAML (`class:/daos:/signature.parameters/barrier_required`). Pre-scanner gate `verify_db_access_boundaries.py:3409–3437` runs `verify_ownership_policy_source_evidence(...)` BEFORE scanner matching; any failure -> umbrella `DB_POLICY_SOURCE_EVIDENCE_INVALID` (empty context). Tests expect scanner-level diagnostics (`DB_DAO_SCOPE_UNRESOLVED`, findings, etc.). Contract drift: fixtures vs mandatory v2 evidence pre-gate. |
| scripts/test_db_guard_room_inventory.py | 17 | Mixed: `test_anchor_unc_shape_windows_only` FAILS (`_absolute_root_anchor("\\\\server\\share\\proj\\mod\\src\\main\\java")` returns `\\...\proj`, test expects `\\...\proj\mod` — java-tail cut is `[:-4]` elsewhere, so either the new UNC branch or the new test is inconsistent); manifest-vs-implicit inventory equality; fixture scans leaking real-repo paths (ExpenseDao count 5 vs 1); dump-room-mutators blocked when ANY diagnostic present. |
| scripts/test_db_guard_source_roots.py | 16 | Diagnostics context schema drift: actual includes extra key `{'reason': 'absolute'}` where tests expect exact `{'field': 'path', 'index': 0}`; `resolve_manifest_absent_falls_back_to_app_conventional_root` expects RELATIVE `('app/src/main/java',)` but implementation returns ABSOLUTE native path (design change vs stale test); loader returns `bool` where profile object expected (`AttributeError: 'bool' object has no attribute 'code'`). |
| scripts/ci/test_gradle_db_guard_contract.py | 13 | Generated Gradle task no longer contains `"python3"` (`assert '"python3"' in task` fails against current `app/build.gradle.kts` text) — build-script vs contract-test drift. |
| scripts/test_kotlin_callable_parser.py | 9 | Masking off-by-one whitespace (`val a =     ` vs `val a =    `); `TypeError: '>' not supported between int and str` in nesting-limit boundary (`_body_end-{-}` parametrization); parser error-sanitization drift. |
| scripts/test_db_guard_policy_v2_evidence.py | 5 | Unexpected `AttributeError` inside per-group evidence processing surfaced as `POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN` with `exc_type: 'AttributeError'` (never-raise wrapper catching a real bug). |
| scripts/test_db_guard_scanner_d4.py | 5 | `AttributeError: 'GuardDiagnostic' object has no attribute 'location'` — reporting API rename vs test expectations. |
| scripts/ci/test_guard_ratchet_v2.py | 4 | `RATCHET_V2_REPORT_INVALID: guard=db_access code=INVALID_JSON` when child runs `--fail-on-violation` scenarios (expected findings JSON missing/unparsable). |
| scripts/test_migrate_db_policy_signatures.py | 4 | Cascade of the v2-evidence AttributeError family above. |
| scripts/test_db_guard_policy_v2.py | 4 | Loader strictness drift: entries parsed successfully where tests expect None/exactly-one rejection. |
| Others (boundaries 2, source_roots_integration 2, declaration_scanner 1, capture 1, guard_findings 1, verify_production_source_roots 1) | 8 | Singles, same drift themes. |

### Round-3 assessment for the fix loop

1. No single cascade remains; each family needs its own decision: update stale fixtures/tests
   (v1-shaped policies, relative-root expectations, `"python3"` assertion, GuardDiagnostic.location,
   masking whitespace) OR adjust implementation (UNC anchor consistency, AttributeError in v2
   evidence processing, ratchet INVALID_JSON handling, source_roots diagnostics context keys).
2. Highest-suspicion REAL code bugs: (a) `AttributeError` inside
   `policy_v2_evidence` group processing; (b) UNC java-anchor inconsistency
   (`test_anchor_unc_shape_windows_only`); (c) ratchet v2 INVALID_JSON path;
   (d) parser nesting-limit `int` vs `str` comparison.
3. Production gates (Section 3 CLIs) remain exact-match healthy throughout rounds 1–3.
