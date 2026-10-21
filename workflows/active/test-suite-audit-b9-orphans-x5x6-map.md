# B9 orphan cleanup + X5/X6 map (scout 2026-10-20; user approved "B9 orphan cleanup" and "X5/X6 reword")

## Orphans
- DELETE scripts/db_guard/mediation_analysis/fixtures/ (≈100 .kt + manifests + README + worker_proof_manifest.yml): no reader in scripts/ (test_parser_spans uses inline strings). Orchestrator deletes after a final `grep -r "mediation_analysis[/\\]fixtures" scripts` is clean.
- DELETE config/guards/worker_root_dispositions.yml: no reader; header cites already-deleted worker_recognition.py; not in GUARD_DOCUMENT_INDEX.yml.
- KEEP (orchestrator decision): docs/ci/db-mediation/GR-14_OWNER_ACCEPTANCE_REGISTRY.yml and GR-13_*.yml — owner-acceptance/historical evidence cross-referenced by GR-14*/GR-15 docs; deleting would dangle doc refs for no gain.
- KEEP: CallGraphBuilder / callgraph.py (still imported by direct_barrier_bridge.py:49, test_transparent_inline_methods_pin.py:29, test_parser_spans.py:26).
- Nothing to do: policy_v3 remnants (none live), GUARD_DOCUMENT_INDEX.yml implementation pointers (all live), app/build.gradle.kts comments (all live).

## X5/X6 — scripts/verify_db_access_boundaries.py (text only; exit codes, constants, error codes unchanged)
Replace every pointer to the removed scripts/ci/promote_db_policy_v2.py with neutral wording pointing to docs/ci/DB_TRIAGE_PROCEDURE.md (live tooling: scripts/ci/generate_db_baseline_v2.py, scripts/migrate_db_policy_signatures.py):
- :11-12 module docstring; :421 comment; :632 docstring; :647-652 stderr `DB_V2_ACTIVE_POLICY_NOT_V2` message (fixed, bounded, path-free string apart from the doc pointer); :3568 docstring; :3697 comment.
- No test pins these strings (writer re-greps scripts/test_verify_db_access_boundaries.py for DB_V2_ACTIVE_POLICY_NOT_V2 substring checks). Do NOT edit scripts/ci/test_generate_db_baseline_v2.py frozen legacy strings.
- Also: scripts/allowlists/cancellation_allowlist.yml header already fixed (18 remain).

Validation: pytest-subset scripts/test_verify_db_access_boundaries.py and scripts/db_guard; registered-guard db_access; guard_docs_truth; static-guards at final gate.
