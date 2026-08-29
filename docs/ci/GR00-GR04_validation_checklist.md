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

## 3. Guard CLIs

| Command | Expected |
| --- | --- |
| `verify_production_source_roots` | exit 0 silent |
| `verify_db_access_boundaries --inventory-only ...` | exit 2 ON THIS WINDOWS WORKSTATION is EXPECTED pre-existing debt (350 DB_ROOM_QUERY_UNCLASSIFIABLE + 1 inheritance), byte-identical to base SHA 9b97e797, zero DB_SOURCE_ROOT_* codes; exit 0 only on Linux CI with os.O_DIRECTORY durability barrier |
| `migrate --check` | exit 1 (migration debt expected) |
| full gate with all four config flags | exit 0 (clean) / exit 1 (real findings) under the activated authoritative-v2 policy; v1 bytes archived at db_ownership_policy.legacy.yml (v1 rejection pinned loader-side) |

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
| Active DB gate | authoritative-v2, trusted exit 0 (clean) / exit 1 (real findings); v1 archived at db_ownership_policy.legacy.yml; ratchet v2 live (empty baseline) |
| Inventory-only | exit 2 on Windows pre-existing debt, trusted-equivalent to base |
| Migration | exit 1 resolved=9/99 |
| Meta-guard | exit 0 |
| Candidate | v2 472 entries byte-reproducible |
| Structural pin | 62 retained |

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
