# A7 — Python guard self-tests (read-only; no pytest/build run)

Auditor: scout (Sonnet), 2026-10-16. Persisted by orchestrator. No per-test durations exist → all runtime figures are estimates.

## 0. Method and confidence
- Read fully/largely: A1-slow.md; `guard_registry.py` test manifests; `test_gr14u5_*`, `test_gr14u57b_*`; `test_gr15_acceptance_gate.py` (80 lines); `test_guard_ratchet.py`/`_v2.py` docstrings; `test_verify_allowlist_compliance_fail_closed.py`; `test_finding_catalog_room_inventory.py`; `test_wave2_guard_wiring.py`; `test_db_guard_policy_v2_current_repo.py` header; `test_db_guard_policy_legacy_loaders.py` header; `test_migrate_db_policy_seed_rows.py` (docstring, helpers, all 212 names, GR-08g block 3900-4090); `test_validation_runner_protocol.py` (head + decorators).
- Name listings + targeted reads: `test_verify_db_access_boundaries.py`, `test_verify_time_boundaries.py`, `test_db_finding_triage.py`, `structural_analysis/test_tokenizer.py`, `test_guard_findings.py`. Production-side import greps across `scripts/**`, `config/`, `docs/ci`, `.github`, `app/build.gradle.kts`, FG gate doc.
- Names only: `test_capture_db_guard_evidence.py`, `test_db_guard_room_inventory.py`, `test_db_guard_sql_classifier.py`, `test_db_guard_scanner_d4.py`, `test_run_static_guard_suite.py`, `test_kotlin_callable_parser.py`, `test_verify_known_good_state.py`, `test_wave2_*`, `mediation_analysis/test_*`, other structural_analysis tests, other `verify_*` tests.
- Counts: 112 files, 3,526 `def test_`, 5,153 collected items; 1,933 items (37.5%) already parametrized (331 `parametrize` lines in 37 files). 28 skips = Windows-symlink skips (run on Linux) — not dead.

## 1. Summary
- **Mostly not near-duplicate.** False positives checked: `scripts/ci/test_result_freshness.py` is a production tool (no tests), tested by `test_test_result_freshness.py`; ratchet v1 still used by 5 guards; allowlist `_fail_closed` file is complementary; `test_verify_db_access_boundaries.py` (in-process) and `_v2.py` (CLI/real tree) both live; `policy_legacy.py` still imported.
- 23 `test_gr*` files (~146 tests, 2.8%) each pin a distinct prover defect — merge helps maintenance only.
- **Removable now, no proof lost:** ~150 (~140 in `test_migrate_db_policy_seed_rows.py` + ~10 constant/source-grep tests).
- **Removable with user decisions on unwired tooling:** v3 policy 15 + triage tool 105 + promote/v2-evidence CLIs 39 + subsumption proof 6 + mediation prover stack ~212 ≈ 380 → ceiling ~530 (10%).
- Parametrize-mergeable: ~60-90 functions (hygiene, no runtime).
- Runtime: deleting in-process tests saves only ~10-30 s; cost is subprocesses.
- **New runtime finding:** `scripts/ci/test_validation_runner_protocol.py` (47 functions → 330 items) tests `scripts/validation-runner.ps1` (not a guard); most cases spawn a `powershell` process (lines 25-30, 105, 135, 245, 286, 311, 386) → EST 130-330 s (18-45%) of guard_tests. Confirm with `--durations`; move to its own profile or batch argv matrices (473-666, 1038-1070) into one PowerShell call. Keep fail-closed cases.

## 2. Non-KEEP files
| File | Bucket | Evidence | Action | Saved | Risk |
|---|---|---|---|---|---|
| `scripts/test_migrate_db_policy_seed_rows.py` (212) | PARAMETRIZE → DELETE | 27 batches (GR-08a..p2) repeat the same 5 tests (exact identity + 4 near-miss) with hand-copied rows, all driving `policy_model.match_mutation` (`policy_model.py:163-189`), which has no direct test elsewhere | Add one parametrized `match_mutation` test (9 fields × mutate-one); delete ~135 per-batch tests; fold ~29 `test_real_tracked_*_seed_file_loads_with_exactly_N_rows` into one table (still fed via `verify_known_good_state.py:163/666`); keep closure/advisory tests | ~140 | low |
| `scripts/test_policy_v3_loader.py` (15) | DELETE (approval) | `policy_v3_loader.py`/`policy_v3_model.py` imported only by each other + test; never wired (PR-GR-15) | Ask if v3 still planned | 15 | low |
| `scripts/ci/test_db_finding_triage.py` (105) | DELETE (retire tool, approval) | `build_db_finding_triage.py` unreferenced by registry/suite/known_good/capture/gradle/workflows; offline GR-09 tool | Retire, or keep ~60 behavioural | 105 or ~40 | low |
| `scripts/ci/test_promote_db_policy_v2.py` (23), `test_verify_db_policy_v2_evidence.py` (16) | DELETE (approval) | Promotion done; no leg invokes them; `test_migrate_db_policy_seed_rows.py` imports `_collect_accounting_mutation_keys` from promote script (edit needed) | Retire if tooling finished | 39 | low-med |
| `scripts/test_lifecycle_scanner_subsumption.py` (6) | DELETE | One-time proof that retired Gradle scanners are subsumed; cited `GR-10A_COMMAND_AUTHORITY_MATRIX.md:32` | Delete + edit matrix row, or keep | 6 | low |
| Mediation prover stack: `ci/test_gr14*` (~141), `test_gr15_acceptance_gate.py` (15), `test_inspect_db_mediation_proof.py` (23), `mediation_analysis/test_*` (33) | KEEP or retire (decision) | Not imported by any active guard (only `PRODUCTION_TRANSPARENT_INLINE_METHODS` used by `direct_barrier_bridge.py:49`); only regression net for the prover | Decide if prover stays supported tooling; if kept, optional per-component merge | 0 or ~212 | med |
| `scripts/test_verify_time_boundaries.py` (78) | PARAMETRIZE | 5 identical schema-key tests (619-660), noncanonical path (727-767), exit-2 family (861-885) — each a subprocess | ~3 parametrize functions, same ids | 0 items (~25 functions) | low |
| `scripts/test_verify_db_access_boundaries.py` (228) | trim | Source-text gates: :5871, :5905, `TestNoExecutableAppSrcMainTopologyGate` (6239-6310); pins :5055, :666 | Delete ~6 source-text tests (keep file — registry-pinned) | ~6 | low |
| `scripts/ci/test_db_finding_triage.py` constants | DELETE | :186-224 assert own helper keys / constant = literal | Delete 4 | 4 | low |
| `scripts/ci/test_guard_findings.py` (119) | PARAMETRIZE | `*_never_echoes_hostile_*` family; unknown-guard near-twins (630-697) | Param ids only — privacy fail-closed, no deletion | 0 | low |
| `structural_analysis/test_tokenizer.py` (127) | PARAMETRIZE (optional) | carrier admitted/fails_closed snippets (750-1500) | ~40 into a table; never merge across fail-closed boundaries | 0 | low |
| `scripts/ci/test_validation_runner_protocol.py` (330) | RESTRUCTURE (runtime) | One PowerShell process per case | Own profile or batch per matrix | 0 items, EST 100-300 s | med |
| `scripts/test_migrate_db_policy_signatures.py` (85) | see A1 | | | | |

Naming hazard (no action): `scripts/ci/test_result_freshness.py` and `scripts/db_guard/mediation_analysis/test_fixtures.py` are production modules with `test_*` names.

## 3. Consolidation clusters
1. Seed-rows near-miss cluster — only large copy-paste cluster.
2. GR-14 prover pins (23 files → 2-3 by component) if kept; no count/runtime gain.
3. Ratchet v1/v2/occurrences cover different contracts — no merge.
4. `verify_*_boundaries` single-guard files — no merge.
5. Shared tmp-tree + subprocess helper → code savings only.
6. Source-text tests in db_access boundaries.
7. Already heavily parametrized (no action): sql_classifier (89 → 604 items), wave2 discovery files, room_inventory, db_access v2/boundaries.

## 4. PINNED and sole-proof
- **Registry-pinned (`guard_registry.py`):** test_verify_{source_provenance, ui_dao, worker, receipt_link, import_lifecycle, cloud_payload, pii_logging, di_release, allowlist_compliance, migration_matrix, ignored_test_budget, time, cancellation, privacy, db_access, event_writers, money, raw_money_aggregates}_*.py, `ci/test_verify_deprecation_escalations.py`, `test_migrate_db_policy_signatures.py`, `ci/test_verify_known_good_state.py`, `ci/test_verify_guard_docs_truth.py`.
- **Capture-pinned (`capture_db_guard_evidence.py:2915` `REQUIRED_RECURSIVE_GUARD_TESTS`):** ci/test_guard_findings, ci/test_guard_ratchet(_v2), test_kotlin_callable_parser, test_migrate_db_policy_signatures, test_db_guard_{room_inventory, sql_classifier, declaration_scanner, scanner_d4, policy_v2}, test_verify_db_access_{v2,boundaries}, structural_analysis/test_barrier_proof, guardrails/test_production_source_scope.
- **Doc/test-pinned:** `ci/test_gr10b_source_scope_matrix.py` (generate_guard_docs.py), `test_verify_db_access_v2.py` (GUARD_EVIDENCE_INDEX.yml), `ci/test_wave2_guard_wiring.py` (CI workflow wiring), `test_lifecycle_scanner_subsumption.py` (GR-10A matrix), gr14/gr15 (soft prose citations). FG gate doc cites no `test_*.py`.
- **Sole-proof fail-closed:** `test_verify_allowlist_compliance_fail_closed.py`; time_boundaries `*_exit_2`; ratchet `test_missing_baseline_is_error`, `test_guard_crash_is_error`, `test_timeout_exits_two`; ratchet_v2 `RATCHET_*_INVALID`; tokenizer/barrier_proof `*_fails_closed`/`*_hidden_site`; db_access `test_unbounded_*`, `test_brace_less_*`, `test_scan_unsupported_structural_operation_token_fails_closed`; all `*_never_echoes_hostile_*`; symlink/escape tests (Linux-only); runner-protocol malformed settings/lock/tampered request/unsafe filter; `test_candidate_signatures_rejected_by_v2_loader`; real-tracked seed-file row-count pins.

## 5. Recommended order
1. Measure: one `pytest scripts --durations=60` via validation-runner.
2. Seed-rows collapse (~140).
3. Delete meaningless constant/source-text tests.
4. Parametrize time_boundaries schema + hostile-echo family.
5. Runtime: isolate/batch runner-protocol tests; A1 session fixtures for migrate signatures.
6. User decisions on unwired tooling (v3, triage, promote/v2-evidence, subsumption, mediation prover) — reviewer-strict on final diff.
7. Optional gr14 per-component merge.

Not assessed (treat KEEP): capture_db_guard_evidence, room_inventory, sql_classifier, scanner_d4, run_static_guard_suite, kotlin_callable_parser, verify_known_good_state, wave2_*.
