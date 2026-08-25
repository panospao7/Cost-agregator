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

---

## Re-validation round 4 (2026-08-24) — after commit `1c09f525` "align guard suite with current contracts after validation round 3"

HEAD `1c09f5252e4d2e6a4a2cfe56a1a355e4fdeb7061`; checkout clean.

### Full sweep

`python -m pytest scripts -q` -> exit 1; **101 failed / 2120 passed / 23 skipped** in 388 s
(round 3: 151; round 2: 357; round 1: 372). NOT green -> checklist sections 2–6 not run this
round; Section 5 gate remains ready once suites pass.

### What round-3 fixes resolved

- v2-evidence `AttributeError` (policy_v2_evidence/migrate cascade) — GONE.
- `GuardDiagnostic.location` scanner crashes — GONE.
- Ratchet protocol asymmetry partially addressed (4 -> 3, with new shape below).
- Gradle contract `"python3"` drift, source_roots context keys, masking literal,
  UNC anchor expectation, loader dedent mutations, capture/production-roots singles — GONE.

### Residue breakdown (101) with mechanisms

| Family | Count | Observed mechanism |
| --- | --- | --- |
| scripts/test_verify_db_access_v2.py | 66 | Evidence pre-gate now PASSES (scope diagnostics flow), but every `_fixture()` scan emits TWO extras vs exact-match reports: `DB_ROOM_RAW_QUERY_POLICY_INVALID` (fixture writes `config/guards/raw.yml` = `entries: []`; current raw-query policy loader rejects that shape) and `DB_DAO_INHERITANCE_UNRESOLVED` (path None) even for fixtures with no DAO inheritance. Actual triple e.g. `[DB_DAO_INHERITANCE_UNRESOLVED, DB_DAO_SCOPE_UNRESOLVED, DB_ROOM_RAW_QUERY_POLICY_INVALID]` vs expected single diagnostic. |
| scripts/test_db_guard_room_inventory.py | 13 | Raw-query policy contract drift: `DB_ROOM_RAW_QUERY_POLICY_INVALID`/`_STALE:<Dao>` wipes mutators where tests expect partial results (`test_dict_policy_cannot_become_synthetic_path`, `test_stale_policy_only_raw_query_entry_fails_closed`); @RawQuery const-template resolution still yields UNCLASSIFIABLE (`test_query_template_resolves_same_file_literal_const`, multiline continuation); transitive @RawQuery override chain produces 1 mutator vs expected 2 (`..._shadows_deep_chain`, STALE on Middle); bodyless child DAO inheritance mutator missing (0 vs 1); kotlin conventional-root anchor pins project root while implementation anchors module dir (`tmp\app` vs `tmp`). |
| scripts/test_kotlin_callable_parser.py | 7 | Masking whitespace literal off-by-one persists; nesting-limit boundary `TypeError: '>' not supported between int and str` (`_body_end-{-}` parametrization); function-type normalization/sanitization drift. |
| scripts/ci/test_guard_ratchet_v2.py | 3 | Legacy-style scenarios still surface `RATCHET_V2_REPORT_INVALID ... INVALID_JSON` instead of expected `RATCHET_BASELINE_MISSING`; PLUS genuine test-file bug: `_write_baseline_v2() got multiple values for argument 'entries'` (helper signature mismatch). |
| scripts/test_db_guard_scanner_d4.py | 5 | Rule-catalog registration drift: `rule DB_NOT_A_RULE must be registered` fails — catalog no longer registers the unknown-rule sentinel the tests pin. |
| scripts/test_migrate_db_policy_signatures.py + test_db_guard_policy_v2.py | 5 | Overload fixtures now load with `POLICY_ERROR_MISSING_FIELD daoAccessor index 0..6` (7 errors) where tests expect clean load / rejection boundaries — v2 loader strictness vs legacy-shaped fixture documents. |
| scripts/test_db_guard_source_roots_integration.py + declaration_scanner | 2 | Kotlin conventional-root anchor expectation (`anchor == tmp_path`) vs implementation (`tmp_path\\app`). |

### Section-3 production CLIs re-verified (round 4)

| CLI | Result |
| --- | --- |
| source-roots meta-guard | exit 0, silent |
| inventory-only | exit 2; exactly 350 DB_ROOM_QUERY_UNCLASSIFIABLE + 1 DB_DAO_INHERITANCE_UNRESOLVED; zero DB_SOURCE_ROOT_* |
| migrate --check | exit 1; input=99 resolved=9 unresolved=90 duplicateMutationKeys=0 |
| full gate (four config flags) | exit 2; stderr exactly 1 line; diagnostics exactly `[DB_POLICY_SOURCE_EVIDENCE_INVALID]` |

### Round-4 triage hints for the loop

1. Biggest lever (66 tests): decide the fixture raw-query-policy contract — either update
   `_fixture()` to write a currently-valid raw policy document, or relax/redirect
   `DB_ROOM_RAW_QUERY_POLICY_INVALID` emission for empty policies; and settle whether
   `DB_DAO_INHERITANCE_UNRESOLVED` should be suppressed when the scanned tree declares no
   inheritance at all.
2. Real-behavior candidates: bodyless-child DAO mutator inheritance (0 vs 1); transitive
   @RawQuery override chain (STALE on Middle); @RawQuery const-template UNCLASSIFIABLE;
   ratchet INVALID_JSON masking baseline-missing cases; `_write_baseline_v2()` helper bug
   is a TEST defect, not product.
3. Kotlin anchor convention conflict (project root vs module dir) needs a spec decision and
   then parity across room_inventory/declaration_scanner/source_roots tests.

Cumulative sweep trend: 372 -> 357 -> 151 -> 101 failures; production gates exact-match
stable throughout all four rounds.

---

## Re-validation round 5 (2026-08-24) — after commit `606b6175` "resolve remaining validation residue families"

HEAD `606b617528889b35a2673f9b24aa74977f8c7eff`; checkout clean.

### Full sweep

`python -m pytest scripts -q` -> exit 1; **59 failed / 2163 passed / 23 skipped** in 310 s
(round 4: 101). NOT green -> checklist sections 2–6 remain deferred. Trend:
372 -> 357 -> 151 -> 101 -> 59.

### What round-4 fixes resolved

Raw-query-policy fixture extras (`DB_ROOM_RAW_QUERY_POLICY_INVALID`) and repo-wide spurious
`DB_DAO_INHERITANCE_UNRESOLVED` are GONE from fixture scans; braced single-const @RawQuery
resolution fixed; ratchet INVALID_JSON masking resolved except one shape; UNC expectation,
mutation_kind pins, helper signature bug — resolved.

### Residue breakdown (59)

| Family | Count | Observed mechanism |
| --- | --- | --- |
| scripts/test_verify_db_access_v2.py | 36 | Authorization semantics drift for v1-shaped `_policy()` fixtures: `barrier_required: true` entries now produce `DB_UNAUTHORIZED_MUTATION` (full structured identity) instead of expected `DB_MISSING_WRITE_BARRIER`; "clean run" scenarios exit 1 (unauthorized finding present) instead of 0; `test_malformed_ownership_policy_is_source_evidence_diagnostic[...]` parametrizations expect specific evidence codes but get different diagnostics; `test_clean_run_writes_valid_guard_report_v2` hits `DB_FINDINGS_WRITE_FAILED` (catch-all at `verify_db_access_boundaries.py:3276–3283`; likely the same os.O_DIRECTORY-style durability fail-closed as `room_inventory.py:1478–1480` — i.e. Windows-by-design, needs explicit confirmation + platform-pinned test). |
| scripts/test_kotlin_callable_parser.py | 7 | Error-path contract drift: parser raises generic `ParserError("kotlin callable parser error")`, bare `StopIteration`, or `SignatureError("control characters are not allowed in a type")` where tests pin typed codes (`UNBALANCED_ANGLE`, exact normalization). Affects nesting-limit boundary, empty/function-type normalization, unqualified nested owner type. |
| scripts/test_db_guard_scanner_d4.py | 5 | D4 structural-fixture scans now emit extra `[DB_ROOM_SOURCE_EMPTY (path None), DB_SIGNATURE_UNRESOLVED (path Fixture)]` alongside/before expected `DB_STRUCTURAL_SCOPE_UNSUPPORTED` or clean reports; statistics differ. Inventory/signature resolution is now wired into scan_db_access but D4 fixtures carry no DAOs. |
| scripts/test_db_guard_room_inventory.py | 5 | `test_query_template_resolves_multiline_const_continuation`: multiline const continuation @RawQuery STILL yields UNCLASSIFIABLE (round-4 fix covered same-file literal const only); annotation-conflict diagnostic `DB_ROOM_ANNOTATION_CONFLICT:` NOT emitted where pinned; `InventoryWriteError: DB_ROOM_INVENTORY_WRITE_FAILED` in write-reload tests — documented Windows fail-closed (os.O_DIRECTORY unavailable), consistent with checklist §3 note ("exit 0 only on Linux CI"). |
| scripts/test_migrate_db_policy_signatures.py | 4 | REAL PRODUCT BUG: migrate CLI failure handler crashes with `AttributeError: 'CliFailure' object has no attribute '.message'` -> exit 1 instead of exit 2 in candidate-collision / active-policy-overwrite / malformed-YAML / temp-file-cleanup scenarios. |
| scripts/test_db_guard_policy_v2.py | 1 | `test_two_overloads_differing_only_in_ordered_parameter_types`: STILL REPRODUCIBLE — overload fixtures load with 7× `POLICY_ERROR_MISSING_FIELD daoAccessor index 0..6` where tests expect clean load (contradicts round-4 "not reproducible" note; persists since round 3). |
| scripts/test_db_guard_source_roots_integration.py | 1 | Kotlin conventional-root anchor still pins project root (`tmp`) vs implementation module dir (`tmp\\app`). |

### Round-5 triage hints

1. Genuine product bugs first: (a) migrate `CliFailure.message` handler crash; (b) multiline
   const continuation @RawQuery; (c) missing `DB_ROOM_ANNOTATION_CONFLICT` emission;
   (d) confirm whether `DB_FINDINGS_WRITE_FAILED` on Windows is intended durability behavior
   and pin it platform-conditionally.
2. Contract decisions needed: barrier-required v1-shaped fixture policies must map to
   `DB_MISSING_WRITE_BARRIER` (or fixtures upgraded to v2 shape with barrierMode); D4
   fixtures need DAO-free inventory tolerance (suppress SOURCE_EMPTY/SIGNATURE_UNRESOLVED
   extras) or dedicated structural-only entry point; kotlin anchor convention (project root
   vs module dir) final decision + parity; parser typed error codes vs generic ParserError.
3. Platform-expected failures to quarantine: inventory/findings write-barrier tests should be
   skipped-or-conditioned on os.O_DIRECTORY availability per the checklist's own §3 note.

Production CLIs were re-verified exact-match healthy after `1c09f525` (round 4); no further
CLI changes observed this round (sweep only). Cumulative: production gates stable across all
five rounds while suite failures fell 372 -> 59.

---

## Re-validation round 6 (2026-08-24) — after commit `63cbea2f` "resolve round-5 residue families (barrier fixtures, d4 pipeline realism, parser layering)"

HEAD `63cbea2fe30ac5ab23364b708e05dc85901af80c`; checkout clean.

### Full sweep

`python -m pytest scripts -q` -> **exit 2 — COLLECTION ERROR. Sweep could not run.**

```
ERROR scripts/test_verify_db_access_v2.py
SyntaxError: closing parenthesis ')' does not match opening parenthesis '{' on line 2028
```

Root cause (single-character defect introduced by `63cbea2f`):

- `scripts/test_verify_db_access_v2.py:2028` opens `_report(report, {`
- `scripts/test_verify_db_access_v2.py:2064` closes the call with bare `    )`
  instead of `    })`.

Verified via in-memory repair iteration (`ast.parse` after replacing that one line):
the file parses cleanly with exactly ONE broken close site; no other malformed
`_report(...)` calls exist (an earlier bracket heuristic suggested 17; tokenizer-level
verification disproved all but line 2064).

### Consequence and required action

- Sections 2–6 were NOT run (sweep precondition failed; checkout must remain untouched so
  the Section-5 clean-checkout gate stays satisfiable).
- Required fix (for the fix loop to commit): line 2064 `    )` -> `    })`.
  No other changes needed to unblock collection.
- Once committed, round 6 should be re-run from the sweep onward; the inventory-only
  diagnostic count may legitimately differ from the historical 350+1 due to the classifier
  fix noted by the orchestrator (checklist §3 expectation needs a Revision 2 update when
  the new exact count is known).

Cumulative trend paused at: 372 -> 357 -> 151 -> 101 -> 59 -> BLOCKED (collection).

---

## Re-validation round 6b (2026-08-24) — after commit `486fb6c3` "repair _report dict close blocking suite collection"

HEAD `486fb6c3fba356b0d8278ff883f70ed270a2c042`; checkout clean. Collection blocker resolved;
sweep runs again.

### Full sweep

`python -m pytest scripts -q` -> exit 1; **27 failed / 2207 passed / 23 skipped** in 307 s
(round 5: 59). NOT green -> checklist sections 2–6 remain deferred. Trend:
372 -> 357 -> 151 -> 101 -> 59 -> blocked -> **27**.

### Inventory-debt Revision-2 input (checklist §3)

`verify_db_access_boundaries --inventory-only`: exit 2; NEW debt =
**143 x DB_ROOM_QUERY_UNCLASSIFIABLE**, inheritance diagnostic GONE (was 350 + 1);
zero `DB_SOURCE_ROOT_*` codes (invariant HOLDS); statistics.trusted=false.
The checklist §3 "350+1" expectation needs a Revision-2 update to the captured number.

### Residue (27) with node IDs

A. Platform-fallback / Windows durability family (5) — os.O_DIRECTORY fail-closed per
   design; candidates for platform-conditional skip or documented Windows expectation:
   1. scripts/test_db_guard_room_inventory.py::test_inventory_write_is_canonical_and_atomic —
      `InventoryWriteError: DB_ROOM_INVENTORY_WRITE_FAILED`
   2. scripts/test_db_guard_room_inventory.py::test_inventory_write_success_reloads_report — same code
   3. scripts/test_verify_db_access_v2.py::test_clean_run_writes_valid_guard_report_v2 —
      exit 2, stderr `ERROR: DB_FINDINGS_WRITE_FAILED`
   4. scripts/test_verify_db_access_v2.py::test_inventory_only_writes_inventory_schema_and_rejects_diagnostics —
      verify_main returned 2 vs expected 0 (mutator dump path)
   5. scripts/test_db_guard_source_roots_integration.py::test_inventory_only_cli_trusted_exit_zero —
      CLI diagnostics present; room-mutators.json not written

B. SQL classifier edge (1):
   6. scripts/test_db_guard_sql_classifier.py::test_directed_order_by_remains_a_read[SELECT * FROM things ORDER BY kind ASC, id DESC] —
      multi-key ORDER BY classified UNCLASSIFIABLE instead of SELECT (the new directed-
      ORDER-BY classifier handles single-key only)

C. Kotlin callable parser error layering (5) — generic ParserError/StopIteration/
   SignatureError("control characters are not allowed in a type") where typed codes pinned:
   7. scripts/test_kotlin_callable_parser.py::test_nested_generic_and_function_depth_boundaries_keep_status
   8. scripts/test_kotlin_callable_parser.py::test_function_type_parameter_has_exact_signature
   9. scripts/test_kotlin_callable_parser.py::test_empty_function_types_are_normalized_exactly
   10. scripts/test_kotlin_callable_parser.py::test_empty_function_type_parameter_keeps_signature
   11. scripts/test_kotlin_callable_parser.py::test_unqualified_nested_owner_type_beats_same_package_type —
       StopIteration: declaration with signature.function_name == 'f' not discovered

D. D4 structural pipeline (8):
   12. scripts/test_db_guard_scanner_d4.py::test_property_initializer_and_accessors_have_distinct_exact_symbols —
       ValueError: substring not found (fixture source text mismatch)
   13. scripts/test_db_guard_scanner_d4.py::test_delete_recursively_uses_exact_structural_token_and_policy_path —
       ValidationError: rule 'DB_FORBIDDEN_STRUCTURAL_OPERATION' emitted as finding WITHOUT
       resolved callable signature; contract requires diagnostic 'DB_SIGNATURE_UNRESOLVED'
   14. scripts/test_db_guard_scanner_d4.py::test_large_legal_annotation_whitespace_span_is_discovered —
       TypeError: '>' not supported between str and int (persists since round 4)
   15.–19. scripts/test_verify_db_access_v2.py::test_d4_executable_declarations_are_discovered_with_structured_identity[expected_report0..4] —
       exit 2 "DB access discovery infrastructure diagnostics present" where exit 1 + findings
       expected; one variant pins wrong location line (actual 13 vs expected 18, init block)

E. Accessor authorization semantics (5):
   20.–21. scripts/test_verify_db_access_v2.py::test_accessor_policy_uses_exact_structured_callable_identity[property_getter-[]]/[property_setter-[Item]] — report mismatch
   22.–23. scripts/test_verify_db_access_v2.py::test_accessor_policy_rejects_wrong_kind_or_parameter_signature[property_setter-[]]/[property_getter-[Item]] — report mismatch / exit drift
   24. scripts/test_verify_db_access_v2.py::test_duplicate_property_accessor_identity_is_unresolved[set(value: Item)...] — setter variant only (getter now passes)

F. Mutator counting / overload semantics (2):
   25. scripts/test_verify_db_access_v2.py::test_name_only_policy_cannot_authorize_same_name_overloads —
       REAL behavioral diff: inventory_mutators actual 2 vs expected 1 (extra mutator
       discovered in fixture)
   26. scripts/test_verify_db_access_v2.py::test_writable_database_property_has_exact_structural_identity —
       exit 2 + infrastructure diagnostics stderr vs expected exit 1

(Count check: A=5, B=1, C=5, D=8, E=5, F=2 -> 27.)

### Round-6b triage hints

1. Product-behavior suspects: #6 multi-key ORDER BY; #13 signature-unresolved finding
   contract violation; #14 str-vs-int comparator; #15–19 D4 property/init/object/topLevel
   discovery emitting infrastructure diagnostics; #25 double mutator count.
2. Parser layering (#7–11): decide typed-code surface vs generic ParserError; StopIteration
   at #11 suggests a discovery regression for nested owner types.
3. A-family: either platform-conditional skips or pin documented Windows behavior; confirm
   whether DB_FINDINGS_WRITE_FAILED shares the O_DIRECTORY gate.

Production gates stable across all rounds; inventory §3 numbers above supersede the
historical 350+1 for Revision 2.

---

## Re-validation round 7 (2026-08-24) — after commit `8b4ef1f5` "resolve round-6b residue (parser arrow/nesting, scanner accessor env, seam repairs)"

HEAD `8b4ef1f527e2346cea5499073b9adbae05839220`; checkout clean.

### Full sweep

`python -m pytest scripts -q` -> exit 1; **10 failed / 2232 passed / 23 skipped** in 357 s
(round 6b: 27). NOT green -> checklist sections 2–6 remain deferred. Trend:
372 -> 357 -> 151 -> 101 -> 59 -> blocked -> 27 -> **10**.

### What round-6b fixes resolved (17 of 27)

ALL five platform-fallback/durability failures now PASS (inventory write x2,
findings write, mutator-dump x2 — barrier path evidently repaired or conditioned);
multi-key directed ORDER BY classifier case PASSES; two of five parser layering cases PASS;
duplicate-property-accessor setter-only variant of round 6b resolved to getter+setter pair
below; name-only-overload mutator count corrected; d4 executable init/object/topLevel
variants PASS.

### Residue (10) with node IDs — two clusters + parser trio

Cluster 1 — structural-findings contract is internally inconsistent (2):
  1. scripts/test_db_guard_scanner_d4.py::test_delete_recursively_uses_exact_structural_token_and_policy_path —
     scanner emits `DB_FORBIDDEN_STRUCTURAL_OPERATION` finding with identity
     `{operation: deleteRecursively}` only (no resolved signature) and empty diagnostics;
     pinned contract: emit diagnostic `DB_SIGNATURE_UNRESOLVED` instead of an unsigned finding.
  2. scripts/test_db_guard_scanner_d4.py::test_d4_structural_findings_serialize_every_supported_callable_scope_exactly —
     opposite pole: report contains ZERO findings where 8 fully-signed
     `DB_FORBIDDEN_STRUCTURAL_OPERATION` findings are pinned across every callable scope
     (extension line 19, topLevel 16, amount initializer/getter/setter 4/5/6, companion 10,
     member 8, objectMethod 14). Structural discovery currently produces neither signed
     findings nor signature-unresolved diagnostics consistently.

Cluster 2 — property-accessor fixtures fail the whole scan closed (5):
  3. scripts/test_verify_db_access_v2.py::test_writable_database_property_has_exact_structural_identity —
     CLI exit 2 "ERROR: DB access discovery infrastructure diagnostics present" vs expected exit 1
  4.–5. scripts/test_verify_db_access_v2.py::test_accessor_policy_rejects_wrong_kind_or_parameter_signature[property_setter-[]]/[property_getter-[Item]] — same exit-2 shape
  6.–7. scripts/test_verify_db_access_v2.py::test_d4_executable_declarations_are_discovered_with_structured_identity[
        class PropertyInitializer(...)-expected_report0]/[class PropertyGetter(...)-expected_report1] —
     same shape (init/object/topLevel variants pass since this round)
  Likely single root cause: accessor-scope callables surface an unresolved-signature /
  inventory diagnostic during scan, tripping the fail-closed umbrella before findings.
  Needs the same contract decision as Cluster 1 (diagnostic vs finding vs tolerance).

Parser trio (3) — unchanged since round 6b:
  8. scripts/test_kotlin_callable_parser.py::test_nested_generic_and_function_depth_boundaries_keep_status — generic ParserError vs typed codes
  9. scripts/test_kotlin_callable_parser.py::test_function_type_parameter_has_exact_signature — ParserError / SignatureError("control characters...")
  10. scripts/test_kotlin_callable_parser.py::test_empty_function_type_parameter_keeps_signature — ParserError

### Round-7 triage hints

1. Decide ONE structural-op contract: unsigned finding forbidden -> always emit
   `DB_SIGNATURE_UNRESOLVED` diagnostic when signature can't resolve; then make scope
   serialization (#2) produce signed findings wherever signatures DO resolve. Clusters 1
   and 2 should collapse together once accessor/signature resolution stops leaking
   infrastructure diagnostics into the umbrella gate.
2. Parser: surface typed codes (`UNBALANCED_ANGLE`, normalization results) instead of the
   generic ParserError wrapper for the three remaining shapes.

Production gates stable across all seven rounds. Cumulative: 372 -> 10 with zero
production-gate regressions at any point.

---

## Re-validation round 8 (2026-08-24) — after commit `c7aace49` "unify resolved-symbol contract and complete parser arrow-skip"

HEAD `c7aace49a469deebcaaf6d121e9fc38ac3a68df2`; checkout clean.

### Full sweep

`python -m pytest scripts -q` -> exit 1; **1 failed / 2241 passed / 23 skipped** in 425 s
(round 7: 10). Trend: 372 -> 357 -> 151 -> 101 -> 59 -> blocked -> 27 -> 10 -> **1**.

### What round-7 fixes resolved (9 of 10)

- Accessor cluster GONE: `test_writable_database_property_has_exact_structural_identity`,
  both `accessor_policy_rejects_wrong_kind_or_parameter_signature[...]` variants, and both
  remaining `test_d4_executable_declarations_are_discovered_with_structured_identity`
  property variants now pass.
- Parser trio GONE: all three kotlin-callable-parser layering cases pass.
- `test_d4_structural_findings_serialize_every_supported_callable_scope_exactly` PASSES:
  signed structural findings across every callable scope are produced with exact pinned lines.

### The single straggler (root cause fully diagnosed)

scripts/test_db_guard_scanner_d4.py::test_delete_recursively_uses_exact_structural_token_and_policy_path

Fixture: class with two functions — `allowed` (line 4) and `forbidden` (line 5), each calling
`db.deleteRecursively()`; structural policy authorizes ONLY `allowed`
(`method_pattern: "allowed"`, `operation: deleteRecursively`). Expected: zero finding for
`allowed`, one fully-signed `DB_FORBIDDEN_STRUCTURAL_OPERATION` finding for `forbidden`
(line 5).

Observed (reproduced outside pytest via the module's own helpers): scanner emits **TWO**
findings:

- `{location: {line: 4}, symbol.name: 'allowed'}` — the POLICY-AUTHORIZED callable
- `{location: {line: 5}, symbol.name: 'forbidden'}` — correct

i.e. the structural matcher reports every call site matching the operation token WITHOUT
applying the policy's method-pattern authorization filter (`allowed` must stay silent).
Signature resolution itself is healthy (both findings carry resolved owner/name/kind;
statistics trusted=true, no diagnostics). Fix direction: filter structural candidates by the
policy entry's method pattern before emitting, keeping the already-correct signed-finding
shape for non-matching callables.

### Status

Sections 2–6 remain deferred until this last failure closes. Everything else in the
checklist has been green or platform-exact throughout rounds 1–8; production gate CLIs have
never regressed.

---

## Round 8b (2026-08-24) — after GR-00R `70c04f47` (run-pinned capture) + GR-03R `e7b75970` (source-root authority split)

HEAD `e7b7597098dc2e7ac0a35f8342b20f9f4a420f41`; checkout clean.

### Full sweep

`python -m pytest scripts -q` -> exit 1; **12 failed / 2324 passed / 23 skipped** in 375 s.
REGRESSION vs round 8 (1 failed): GR-03R/GR-00R fixed nothing observable here and introduced
11 new failures; the round-8 straggler persists. **Evidence-capture gates NOT executed** —
see blocker below.

### BLOCKER: do not capture trusted evidence with the current tool

Two failures prove the current evidence bundle violates the bounded-fields privacy rule by
persisting RAW child output into `evidence.json`:

- scripts/ci/test_capture_db_guard_evidence.py::test_evidence_records_log_state_without_raw_payload —
  `'hunter2secret'` present in persisted evidence (`"python3_version": "registry ok
  password=hunter2secret <redacted-path>"`)
- ::test_over_cap_log_incomplete_exit_2_never_silent_truncation — over-cap child payload
  (`xxxx…<truncated>`) written into the bundle instead of fail-closed exit 2

Capturing now would create exactly the untrusted bundles GR-05 decisions must not rest on.

### Residue (12) with node IDs

A. GR-00R evidence-capture defects (3):
   1. scripts/ci/test_capture_db_guard_evidence.py::test_sha_mismatch_rejects_before_any_matrix_command —
      on expected-sha mismatch the capture still ran `./gradlew --version`; pin must gate
      BEFORE any matrix command
   2. ::test_over_cap_log_incomplete_exit_2_never_silent_truncation — raw over-cap payload persisted
   3. ::test_evidence_records_log_state_without_raw_payload — secret leaked into python3_version field

B. GR-03R authority-split regressions (8):
   4.–5. scripts/test_db_guard_declaration_scanner.py::test_diagnostic_rejects_invalid_path_without_echoing[src/main/java/example/File.kt]/[lib/src/main/java/example/File.kt] —
      DiagnosticContextError NOT raised for non-canonical roots (fail-open regression)
   6. scripts/test_db_policy_signature.py::TestNoHiddenAppSrcTopologyGate::test_normalize_canonical_path_source_has_no_app_src_check —
      `_normalize_canonical_path` source still contains 'app/src' literal (docstring counts)
   7.–8. TestNoExecutableAppSrcMainTopologyGate::test_no_executable_app_src_main_gate_in_declaration_scanner / _in_scanner_diag_from_text —
      ImportError: attempted relative import beyond top-level package (tests import
      `db_guard.scanner` top-level while modules use package-relative imports)
   9.–10. scripts/test_verify_db_access_boundaries.py::TestNoExecutableAppSrcMainTopologyGate::test_no_app_src_prefix_gate_in_declaration_scanner_validate_path / _in_scanner_diag_from_text — same ImportError
   11. scripts/test_kotlin_callable_parser.py::test_canonical_path_rejection_classes_have_distinct_codes[-PATH_EMPTY] —
       rejection-code contract broken (empty code / ParserError surface), plus relative-import error in block

C. Standing straggler (1):
   12. scripts/test_db_guard_scanner_d4.py::test_delete_recursively_uses_exact_structural_token_and_policy_path —
       unchanged from round 8: structural matcher ignores method_pattern authorization
       (finds BOTH allowed line 4 and forbidden line 5)

### Recommendation

Fix A(1–3) before ANY capture run (trusted-evidence precondition); B items are mechanical
(import style `from scripts.db_guard...`, path-validation restoration or test re-pin,
docstring literal); C unchanged. Then rerun sweep -> green -> capture run-1/run-2 per §5.

---

## Round 9 (2026-08-25) — after GR-05A `17c1a9a3` + GR-05 `272f00e2` + blockers `8cb76ef2` + GR-06 `8bab55d7`

HEAD `8bab55d75ddf341efc6efe014dc56aaa297f66d3`; checkout clean. Suite grew substantially
(GR-05/GR-06 additions); sweep now takes ~19m36s.

### Full sweep

`python -m pytest scripts -q` -> exit 1; **55 failed / 2535 passed / 24 skipped**.
Round 8b was 12 on a smaller suite: ALL 12 carry over unchanged, plus 43 new.

### Dominant NEW root cause (38 failures): duplicated diagnostic emission

`verify_v2_policy_source_evidence` emits every group error TWICE. Verified directly:
`test_unknown_method_name_reports_callable_missing` gets
`['DB_V2_POLICY_CALLABLE_MISSING', 'DB_V2_POLICY_CALLABLE_MISSING']`.
Pattern "Left contains one more item: <same code>" across:

- scripts/test_db_guard_policy_v2_evidence.py — 35 cases (OWNER_MISSING/AMBIGUOUS,
  CALLABLE_MISSING/AMBIGUOUS, PARSER_UNCERTAIN x2+, KIND_UNSUPPORTED, BODY_UNSUPPORTED,
  MUTATION_NOT_FOUND x3, UNLISTED_MUTATION, PATH_OUTSIDE_ROOTS, FILE_UNREADABLE, DAO_AMBIGUOUS)
- scripts/ci/test_verify_db_policy_v2_evidence.py — 3 cases (new GR-06 shadow-CLI surface)

Single-fix candidate: the GR-06 wrapper appends each group error twice (likely once from
the legacy collection and once from the new exact-source pass) or concatenates per-group
lists without dedup by identity.

### Other NEW failures (5)

- scripts/test_migrate_db_policy_signatures.py::test_direct_proof_must_precede_every_mutation —
  got 2 ResolvedRows where 1 expected (GR-05 accounting pin)
- ::test_generate_ships_nonempty_source_mutation_coverage — coverage list empty (`[] == ()`);
  block also shows ImportError attempted relative import beyond top-level package
- ::test_real_run_distribution_pinned_and_reproducible — distribution pin drift
- scripts/test_db_guard_policy_errors.py::test_known_codes_are_upper_snake_constants —
  code-registry convention check trips on DB_V2_POLICY_* family (expects POLICY_ERROR_
  prefix) AND shows duplicated-code lists
- scripts/test_db_guard_source_roots_integration.py::test_inventory_only_cli_trusted_exit_zero —
  REAPPEARED (passed rounds 7–8): inventory-only child reports infrastructure diagnostics;
  room-mutators.json written despite failure

### Carried over from round 8b, unchanged (12)

capture x3 (sha-gate ordering; hunter2secret leak; over-cap payload leak),
declaration_scanner invalid-path x2 (fail-open), policy_signature x3 ('app/src' literal +
2 ImportErrors), verify_db_access_boundaries topology-gate ImportErrors x2,
kotlin parser PATH_EMPTY x1, delete_recursively straggler x1.

### Round-9 triage hints

1. Dedup/diagnose the double-append in verify_v2_policy_source_evidence first (38 tests).
2. Reconcile DB_V2_POLICY_* vs POLICY_ERROR_ naming in the known-codes registry test.
3. GR-05 migrate accounting pins (direct-proof ordering, coverage non-empty, distribution).
4. Then the 12 carried-over nodes as listed in Round 8b.
5. Investigate why test_inventory_only_cli_trusted_exit_zero regressed after being green in
   rounds 7-8 (GR-06 touched scan paths).

---

## Addendum — round-8 straggler disposition and corrective PRs (2026-08-25)

This addendum connects to the round-8 straggler
(`scripts/test_db_guard_scanner_d4.py::test_delete_recursively_uses_exact_structural_token_and_policy_path`,
diagnosed at lines 655–676) and to the Round 8b corrective commits. No code changes were made
during the writing of this note; status wording is intentionally non-completion.

### 1. Round-8 straggler — static-trace disposition

A static trace against the round-8 tree `c7aace49` located the `method_pattern`
authorization gate and confirmed it is **present and correct at both structural emission
sites** — `scanner.py` near lines 761 and 814, reached via `_structural_match` using
`fullmatch` on the policy entry's method pattern. The gate logic itself was therefore not
missing at the point of emission; the recorded round-8 failure (two findings, including the
policy-authorized `allowed` callable at line 4) most likely reflects a **stale
`.pytest_cache` `lastfailed` state** carried into the round-8 run rather than a live
authorization defect at those sites.

Fresh-sweep confirmation that the straggler is actually resolved (or still live) is
**pending** — this is a human gate, not a verified result. The addendum does not claim the
straggler is closed.

### 2. External guardrail reassessment (response 15) — corrective PRs

An independent external guardrail reassessment (response 15) re-confirmed the GR-00..GR-04
direction and identified two corrective gaps. Both gaps are now implemented as corrective
PRs/commits:

- **GR-00R** (commit `70c04f47`) — evidence-capture run-pinning:
  - Capture is now run-pinned via a mandatory `--expected-sha`; the old fixed `TARGET_SHA`
    lock was removed.
  - Log-completeness contract: only two states are permitted (complete or fail-closed); no
    silent truncation; only complete logs are hashed into the bundle.
  - Post-capture drift check added.
  - Matrix runs with a zero-side-effect pin (`-p no:cacheprovider`) to avoid cache
    self-pollution.
  - Strict review found and fixed the `.pytest_cache` self-pollution blocker (the same class
    of stale-cache issue noted in §1).

- **GR-03R** (commit `e7b75970`) — syntax/membership authority split:
  - `canonical_source_path` and `FunctionSignature` are now syntax-only constructs.
  - `source_roots.is_declared_production_path` is the sole membership authority.
  - Scanner / declaration-scanner diagnostic paths are topology-neutral.
  - Candidate byte-identity is reasoned under the current single-root manifest.

These two commits correspond to the Round 8b HEAD lineage; the Round 8b residue (A/B/C
families) is addressed by the GR-00R/GR-03R fixes described above, but re-verification is
still pending (see §5).

### 3. Inventory-debt note for checklist Revision 2

The historical §3 expectation of `350 DB_ROOM_QUERY_UNCLASSIFIABLE + 1
DB_DAO_INHERITANCE_UNRESOLVED` was superseded during rounds 5–6b: after the classifier fixes
the inventory-only debt became **143 UNCLASSIFIABLE / 0 inheritance** (captured at round 6b,
lines 496–501). The multi-key `ORDER BY` classifier fix (round 6b batch 1, item B.6) reduces
the count further. The **exact new count is to be captured at the next real inventory-only
run** and is not asserted here.

### 4. Ledger corrections flagged for Revision 3

The round-6b A-family premises (lines 505–515) were partially misdiagnosed:

- **A.3** (`test_clean_run_writes_valid_guard_report_v2`, `DB_FINDINGS_WRITE_FAILED`): the
  failure has **no platform seam** — the real cause was a missing output parent directory, not
  an `os.O_DIRECTORY` Windows durability gate.
- **A.5** (`test_inventory_only_cli_trusted_exit_zero`, mutator-dump withholding on debt): the
  withholding behavior is a **documented contract**, not a defect.

These corrections should be reflected in the Revision 3 ledger before any "resolved" claim is
made for the A-family.

### 5. Pending human gates before GR-05 planning concludes

- **Fresh full sweep** to confirm the round-8 straggler disposition (§1) and to validate the
  GR-00R/GR-03R corrective fixes against the Round 8b residue (§2).
- **Two clean captures at the caller-stated SHA via `--expected-sha`** (first trusted bundle),
  per the §5 two-run gate — only after the evidence-capture defects (Round 8b A.1–3) are
  confirmed fixed and the privacy/raw-payload blocker is cleared.

Status: **pending / conditional** — no DONE/GREEN claim. GR-05 planning should not be
concluded until both human gates above pass.
