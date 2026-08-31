# GR-00..GR-04 Human Validation Checklist

Status: pending human execution (do NOT mark DONE/GREEN anywhere).

## 1. Full Python sweep

```powershell
python -m pytest scripts -q
```

Catches anything across all batches (~400+ tests).

## 2. Per-batch targeted suites

### GR-00 evidence capture

```powershell
pytest scripts/ci/test_capture_db_guard_evidence.py -v
```

### GR-01 v2 model + legacy parity

```powershell
python -m pytest scripts/test_db_guard_policy_v2.py scripts/test_db_guard_policy_v2_evidence.py scripts/test_db_guard_policy_errors.py scripts/test_db_guard_policy_legacy_loaders.py scripts/test_db_guard_policy_v2_current_repo.py -v
python -m pytest scripts/test_verify_db_access_boundaries.py scripts/test_verify_db_access_v2.py -v
```

(extraction must be behavior-identical)

### GR-02 candidate generation

```powershell
pytest scripts/test_migrate_db_policy_signatures.py scripts/test_db_guard_room_inventory.py -v
python scripts/migrate_db_policy_signatures.py --check
```

Expect exit 1, input=99 resolved=9 unresolved=90.

Reproducibility:

```powershell
python scripts/migrate_db_policy_signatures.py --write-candidate --output build/tmp-regen.yml --report build/tmp-report.json
fc /b build/tmp-regen.yml config/guards/db_ownership_policy.signatures.candidate.yml
```

(byte-identical)

### GR-03 source roots

```powershell
pytest scripts/test_db_guard_source_roots.py scripts/ci/test_verify_production_source_roots.py scripts/test_db_guard_source_roots_integration.py scripts/test_db_guard_declaration_scanner.py scripts/test_db_guard_scanner_d4.py -v
python scripts/ci/verify_production_source_roots.py --root . --manifest config/guards/production_source_roots.yml
```

Expect exit 0, silent.

### GR-04 decoupling

```powershell
pytest scripts/test_verify_db_access_boundaries.py scripts/test_verify_db_access_v2.py -v
```

### Test-result freshness stamp (PR-GR-10f)

Gradle's JUnit XML does not record the commit the tests ran against, so stale
result XMLs from an earlier round can be mis-read as fresh failures (the R12
lesson: round-11 XMLs under app/build/test-results were consumed as round-12
failures, wasting a round). Before consuming any test-result XML, run the
stamp workflow: run tests -> write the stamp -> run --check:

```powershell
python scripts/ci/test_result_freshness.py --write --suite-name testDebugUnitTest
python scripts/ci/test_result_freshness.py --check
```

`--write` atomically records {schemaVersion, commitSha, treeSha,
completedAtUtc, suiteName} at app/build/test-results/.freshness-stamp.json
immediately after the test run; `--check` exits 0 (fresh: stamp exists,
matches HEAD, is within --max-age-hours (default 24), and no XML under the
results directory is newer than the stamp), 1 (stale-mismatch: stamp
missing, SHA drift, expired, or an XML newer than the stamp — the results
must NOT be consumed), or 2 (infrastructure). The known-good-state scorecard
carries this as an optional 7th row (test_result_freshness): SKIP
(non-blocking) when no stamp exists, PASS when fresh, FAIL on stale/SHA
drift.

## 3. Guard CLIs

| Command | Expected |
| --- | --- |
| `verify_production_source_roots` | exit 0 silent |
| `verify_db_access_boundaries --inventory-only ...` | exit 2 ON THIS WINDOWS WORKSTATION is EXPECTED pre-existing debt (350 DB_ROOM_QUERY_UNCLASSIFIABLE + 1 inheritance), byte-identical to base SHA 9b97e797, zero DB_SOURCE_ROOT_* codes; exit 0 only on Linux CI with os.O_DIRECTORY durability barrier |
| `migrate --check` | exit 1 (migration debt expected) |
| full gate with all four config flags | exit 0 (clean) / exit 1 (real findings) under the activated authoritative-v2 policy; v1 bytes archived at db_ownership_policy.legacy.yml (v1 rejection pinned loader-side). Post-GR-05 REAL-TREE expectation (Revision 2): the gate ACCEPTS the current tree — exit 0, trusted=true, 0 findings, exactly 20 x DB_SIGNATURE_UNRESOLVED advisory diagnostics (GR-09 documented advisory truth, 588623d1); the blocked exit-2 DB_POLICY_SOURCE_EVIDENCE_INVALID wording describes the ARCHIVED-v1 fixture path only (pinned by the passing fixture test) |

## 4. Kotlin / Gradle

```powershell
./gradlew :app:testDebugUnitTest --tests "*DbGuardPolicyFixtureTest*" --console=plain
./gradlew :app:compileDebugKotlin --console=plain
./gradlew :app:testDebugUnitTest --console=plain
./gradlew :app:check --console=plain
```

Scope note: DbGuardPolicyFixtureTest rewritten in GR-04; app/build.gradle.kts gained 7th required input dbGuardSourceRootsManifestPath (parity asserted Python-side in scripts/ci/test_gradle_db_guard_contract.py).

## 5. GR-00 two-run evidence gate (standing human gate)

Only on a clean committed checkout: two capture runs to build/guard-evidence/<SHA>/run-1 and run-2; `fc /b` semantic-summary.json pair. Pass = both exit 0, byte-identical summaries, empty infrastructure_warnings, DB gate observed blocked as child result. Only then may docs/ci/DB_GUARD_HARDENING_LEDGER.md be marked complete.

## 6. Audits & preservation

```powershell
git grep -nE "PINNED_OWNERSHIP_ENTRY_COUNT|ownership_entries|ownership_count=" -- scripts config docs
```

(allowed hits: rejection tests/docs only; non-test scripts must contain no QUOTED "ownership_entries" counts-key literal — unquoted identifier uses such as _read_ownership_entries_for_evidence are permitted ownership-evidence flow)

```powershell
git grep -n "app/src/main/java" -- scripts
```

(remaining hits must be non-DB guards/fixtures/docs/data; DB-guard production modules may reference the literal root path via the shared contract/legacy constants — the audit's intent is that no EXECUTABLE root-selection bypasses source_roots.py, for which the meta-guard exit 0 above is the authoritative check)

```powershell
git diff cf07b04b~1 --exit-code -- config/baselines/db_access.json config/guards/db_ownership_policy.yml config/guards/db_structural_exceptions.yml app/src/main
```

(must be silent for these four paths; the signatures candidate is intentionally excluded — it changed v1 -> v2 in GR-02, and its integrity is instead pinned by byte-reproducible regeneration per Section 2)

## 7. Known-good state summary

| Aspect | State |
| --- | --- |
| Active DB gate | authoritative-v2, trusted exit 0 (clean) / exit 1 (real findings); v1 archived at db_ownership_policy.legacy.yml; ratchet v2 live (empty baseline). Post-GR-05 real tree (Revision 2): accepted — exit 0, trusted, 0 findings, 20 x DB_SIGNATURE_UNRESOLVED advisory |
| Inventory-only | exit 2 on Windows pre-existing debt, trusted-equivalent to base |
| Migration | exit 1 resolved=57/99 unresolved=42 (fold truth) |
| Meta-guard | exit 0 |
| Candidate | v2 472 entries byte-reproducible |
| Structural pin | 64 retained (60 expected + 4 fixtures) |
| Test-result freshness | optional stamp row (PR-GR-10f): SKIP when never stamped (non-blocking); PASS when the stamp matches HEAD, is within max age, and no XML is newer than it; FAIL on stale/SHA drift |

Closing note: if all sections pass, branch gr-00-local is fully validated for PR/merge and GR-05 can start. If anything fails, route output back to the orchestrator fix loop.

## Revision 1 (post first validation run)

Date: 2026-08-24. Status: pending re-validation.

Corrections applied from the first validation findings:

1. Section 2: GR-02 reproducibility command fixed — the tool requires
   `--output` (no positional output path); `--report` added for the JSON
   findings report.
2. Section 6: audit grep notes aligned with the modules' actual rules — only
   QUOTED `ownership_entries` counts-key literals are banned in non-test
   scripts (unquoted ownership-evidence identifier uses are permitted), and
   DB-guard production modules may reference the literal root path via the
   shared contract/legacy constants; the meta-guard exit 0 remains the
   authoritative no-executable-bypass check.
3. Section 6: preservation baseline corrected — the signatures candidate
   intentionally changed v1 -> v2 in GR-02, so cf07b04b~1 silence now covers
   only db_access.json, db_ownership_policy.yml, db_structural_exceptions.yml,
   and app/src/main; candidate integrity is pinned by byte-reproducible
   regeneration (Section 2).
4. Tuple-shape fixes in scripts/test_db_guard_policy_v2_current_repo.py — the
   signatures candidate has been a valid v2 document since GR-02, so its stale
   rejection assertions were replaced with acceptance assertions (9 entries,
   zero errors) and the v1-rejection intent was re-pinned against the active
   v1 policy.

## Revision 2 (post GR-05 real-tree gate verification)

Date: 2026-08-30 (recorded retroactively; verified by direct CLI run in
validation round 12). Status: pending re-validation.

Post-GR-05 real-tree state change: the full gate (all four config flags)
run against the REAL production tree no longer matches a clean/real-findings
dichotomy only — the gate ACCEPTS the current tree with advisory
diagnostics. Corrections applied:

1. Section 3: the "full gate with all four config flags" row now records
   the post-GR-05 real-tree expectation — active gate exit 0, trusted=true,
   0 findings, exactly 20 x DB_SIGNATURE_UNRESOLVED advisory diagnostics
   (matching the GR-09 documented advisory truth at `588623d1`). The
   blocked exit-2 `DB_POLICY_SOURCE_EVIDENCE_INVALID` wording describes the
   ARCHIVED-v1 fixture path only (pinned by the passing fixture test).
2. Section 7: the "Active DB gate" row records the accepted-with-advisories
   real-tree state (exit 0, trusted, 0 findings, 20 x
   DB_SIGNATURE_UNRESOLVED advisory) alongside the clean/real-findings
   dichotomy.

## Revision 3 (post v2 activation)

Date: 2026-08-30. Status: pending re-validation.

Post-activation state change (GR-07/GR-08): the active DB ownership gate now
runs the authoritative v2 policy and correctly exits 0, so the pre-activation
"blocked" truth pinned by this checklist no longer holds. Corrections applied:

1. `scripts/test_db_guard_active_policy_blocked.py` (the GR-01-era
   characterization of the blocked state — exit 2 plus the single
   DB_POLICY_SOURCE_EVIDENCE_INVALID umbrella diagnostic) was DELETED. It was
   flagged obsolete since GR-08a; the activated truth is pinned by the v2
   characterization in `scripts/test_db_guard_scanner_d4.py` and by
   `test_current_db_gate_activated_policy_real_config_pipeline` in
   `scripts/test_verify_db_access_boundaries.py`. Its references were removed
   from the Section 2 GR-01 and GR-04 pytest command lines.
2. Section 3: the "full gate with all four config flags" expectation was
   updated from the blocked state (exit 2, single umbrella stderr line) to
   the activated truth (exit 0 clean / exit 1 on real findings).
3. Section 7: the "Active DB gate" row was updated to the activated truth —
   authoritative-v2, trusted exit 0 (clean) / exit 1 (real findings); v1
   archived at `config/guards/db_ownership_policy.legacy.yml`; ratchet v2
   live with an empty baseline. The "Candidate" row was updated 9 -> 472
   entries (post-GR-08 truth; the PR-GR-05 55-key fold remains pinned as
   migration accounting over the archived v1 input).
4. `scripts/test_db_guard_policy_v2_current_repo.py`: the v1-rejection
   boundary was repointed from the (now v2) active path to the archive —
   `test_archived_v1_policy_rejected_by_v2_loader` loads
   `config/guards/db_ownership_policy.legacy.yml` — and the candidate
   acceptance pin was updated 55 -> 472 entries.

## Revision 4 (post PR-GR-10f stale-result guard)

Date: 2026-08-31. Status: pending re-validation.

1. Section 2 gained the test-result freshness stamp workflow (PR-GR-10f):
   run tests -> `scripts/ci/test_result_freshness.py --write` -> `--check`
   before consuming any XML under app/build/test-results (the R12
   stale-round-11 lesson: stale XMLs were mis-read as fresh failures).
2. Section 7 gained the optional `test_result_freshness` row: SKIP when no
   stamp exists (workflow never adopted, non-blocking), PASS when fresh,
   FAIL on stale/SHA drift. The known-good-state scorecard now renders
   seven rows (six pinned + this optional one) and its summary line carries
   a skip count.

## Revision 5 (post GR-10f §7 stale-cell corrections)

Date: 2026-08-31. Status: pending re-validation.

Two Section 7 cells flagged stale by the GR-10f coder were corrected to the
current fold truth (the same truth already pinned by the known-good-state
scorecard and the guard registry description: migration fold truth 99/57/42,
structural manifest pin 64):

1. Section 7: the "Migration" row was updated from the stale
   `exit 1 resolved=9/99` to the current fold truth — exit 1,
   resolved=57/99, unresolved=42 (99 inputs total).
2. Section 7: the "Structural pin" row was updated from the stale
   `62 retained` to the current structural_entries truth — 64 retained
   (60 expected + 4 fixtures).
