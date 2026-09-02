<!-- GENERATED FILE — do not edit by hand. -->
<!-- Renderer: scripts/ci/generate_guard_docs.py (PR-GR-10D). This renderer owns every GUARD_STATUS block below; manual edits inside the markers fail scripts/ci/verify_guard_docs_truth.py. -->
<!-- Inputs: docs/ci/GUARD_EVIDENCE_INDEX.yml, scripts/ci/guard_registry.py, docs/ci/GUARD_DOCUMENT_INDEX.yml. -->
<!-- Status vocabulary: IMPLEMENTED_UNVERIFIED | VERIFIED_AT_SHA | PARTIAL | BLOCKED | HISTORICAL | SUPERSEDED | PLANNED -->

# Guard Status — Generated Reference

Current state of every registered guard, derived only from the
tracked evidence index (docs/ci/GUARD_EVIDENCE_INDEX.yml). A guard
is `VERIFIED_AT_SHA` only when a COMPLETE, reproducible evidence
record at an exact SHA covers it; guards without a covering record
are `IMPLEMENTED_UNVERIFIED` (code/config exist, but no qualifying
exact-SHA evidence is recorded here). Counts are copied from the
evidence record only — never from prose.

## Current evidence record: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7

- target SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
- capture runs: 2 (semantic digests identical: True)
- DB gate (db_access): exit 0, trusted, 0 findings, 20 advisory diagnostics, 0 blocking diagnostics; ratchet exit 0
- static suite: exit 2 — 23 legs: 21 pass, 1 violation (ui_dao, G-UI-DAO-01), 1 infra error (guard_tests)
- gradle: :app:verifyDbAccessBoundaries INFRASTRUCTURE, :app:check --dry-run INFRASTRUCTURE, :app:compileDebugKotlin PASS

## Per-guard status

<!-- GUARD_STATUS:BEGIN source_provenance -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#source_provenance
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
<!-- GUARD_STATUS:END source_provenance -->

<!-- GUARD_STATUS:BEGIN ui_dao -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: VIOLATION (exit 1 — G-UI-DAO-01: 1 finding(s) at the verified SHA)
Canonical command reference: GUARD_COMMANDS.generated.md#ui_dao
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
<!-- GUARD_STATUS:END ui_dao -->

<!-- GUARD_STATUS:BEGIN worker -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#worker
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
<!-- GUARD_STATUS:END worker -->

<!-- GUARD_STATUS:BEGIN receipt_link -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#receipt_link
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
<!-- GUARD_STATUS:END receipt_link -->

<!-- GUARD_STATUS:BEGIN import_lifecycle -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#import_lifecycle
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
<!-- GUARD_STATUS:END import_lifecycle -->

<!-- GUARD_STATUS:BEGIN cloud_payload -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#cloud_payload
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
<!-- GUARD_STATUS:END cloud_payload -->

<!-- GUARD_STATUS:BEGIN pii_logging -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#pii_logging
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
<!-- GUARD_STATUS:END pii_logging -->

<!-- GUARD_STATUS:BEGIN di_release -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#di_release
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
<!-- GUARD_STATUS:END di_release -->

<!-- GUARD_STATUS:BEGIN allowlist_compliance -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#allowlist_compliance
Scope: repository-config
Owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
<!-- GUARD_STATUS:END allowlist_compliance -->

<!-- GUARD_STATUS:BEGIN migration_matrix -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Debt: 0 unchanged ratchet findings (baseline 0) — recorded debt is never authorization
Canonical command reference: GUARD_COMMANDS.generated.md#migration_matrix
Scope: production-kotlin-targeted
Owner: @panospao7 (doc anchor: docs/ci/MIGRATION_TEST_PROCEDURE.md)
<!-- GUARD_STATUS:END migration_matrix -->

<!-- GUARD_STATUS:BEGIN ignored_test_budget -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#ignored_test_budget
Scope: test-source
Owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
<!-- GUARD_STATUS:END ignored_test_budget -->

<!-- GUARD_STATUS:BEGIN lint_baseline_policy -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#lint_baseline_policy
Scope: repository-config
Owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
<!-- GUARD_STATUS:END lint_baseline_policy -->

<!-- GUARD_STATUS:BEGIN time_boundaries -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS (exit 0, 0 findings)
Canonical command reference: GUARD_COMMANDS.generated.md#time_boundaries
Scope: production-kotlin-all
Owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
<!-- GUARD_STATUS:END time_boundaries -->

<!-- GUARD_STATUS:BEGIN deprecation_escalations -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#deprecation_escalations
Scope: production-kotlin-all
Owner: @panospao7 (doc anchor: docs/ci/DEPRECATION_ESCALATIONS.md)
<!-- GUARD_STATUS:END deprecation_escalations -->

<!-- GUARD_STATUS:BEGIN db_artifact_sync -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#db_artifact_sync
Scope: repository-config
Owner: @panospao7 (doc anchor: docs/ci/DB_POLICY_SIGNATURES.md)
<!-- GUARD_STATUS:END db_artifact_sync -->

<!-- GUARD_STATUS:BEGIN known_good_state -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Canonical command reference: GUARD_COMMANDS.generated.md#known_good_state
Scope: repository-config
Owner: @panospao7 (doc anchor: docs/ci/GR00-GR04_validation_checklist.md)
<!-- GUARD_STATUS:END known_good_state -->

<!-- GUARD_STATUS:BEGIN guard_docs_truth -->
Status: IMPLEMENTED_UNVERIFIED
Reason: no qualifying exact-SHA evidence record in docs/ci/GUARD_EVIDENCE_INDEX.yml covers this guard
Canonical command reference: GUARD_COMMANDS.generated.md#guard_docs_truth
Scope: repository-config
Owner: @panospao7 (doc anchor: docs/ci/GUARD_DOCUMENT_INDEX.yml)
<!-- GUARD_STATUS:END guard_docs_truth -->

<!-- GUARD_STATUS:BEGIN cancellation -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Debt: 86 unchanged ratchet findings (baseline 86) — recorded debt is never authorization
Canonical command reference: GUARD_COMMANDS.generated.md#cancellation
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
<!-- GUARD_STATUS:END cancellation -->

<!-- GUARD_STATUS:BEGIN privacy -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Debt: 1 unchanged ratchet findings (baseline 1) — recorded debt is never authorization
Canonical command reference: GUARD_COMMANDS.generated.md#privacy
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
<!-- GUARD_STATUS:END privacy -->

<!-- GUARD_STATUS:BEGIN db_access -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS (exit 0, trusted, 0 findings, 20 advisory diagnostics, 0 blocking diagnostics — advisory diagnostics are reported, never authorization; ratchet exit 0 (baseline config/baselines/db_access_v2.json: 0 new, 0 resolved, 0 expired))
Debt: 0 unchanged ratchet findings (baseline 0) — recorded debt is never authorization
Gradle leg: INFRASTRUCTURE
Canonical command reference: GUARD_COMMANDS.generated.md#db_access
Scope: production-kotlin-all
Owner: @panospao7 (doc anchor: docs/ci/GUARD_FINDING_PROTOCOL.md)
<!-- GUARD_STATUS:END db_access -->

<!-- GUARD_STATUS:BEGIN event_writers -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Debt: 16 unchanged ratchet findings (baseline 16) — recorded debt is never authorization
Canonical command reference: GUARD_COMMANDS.generated.md#event_writers
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
<!-- GUARD_STATUS:END event_writers -->

<!-- GUARD_STATUS:BEGIN money -->
Status: VERIFIED_AT_SHA
Evidence: gate-00r-565018c5eed61fae4351cb59342dc5c274eb27e7
Verified SHA: 565018c5eed61fae4351cb59342dc5c274eb27e7
Outcome: PASS
Debt: 0 unchanged ratchet findings (baseline 0) — recorded debt is never authorization
Canonical command reference: GUARD_COMMANDS.generated.md#money
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
<!-- GUARD_STATUS:END money -->

<!-- GUARD_STATUS:BEGIN raw_money_aggregates -->
Status: IMPLEMENTED_UNVERIFIED
Reason: no qualifying exact-SHA evidence record in docs/ci/GUARD_EVIDENCE_INDEX.yml covers this guard
Canonical command reference: GUARD_COMMANDS.generated.md#raw_money_aggregates
Scope: production-kotlin-filtered
Owner: @panospao7 (doc anchor: docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md)
<!-- GUARD_STATUS:END raw_money_aggregates -->

<!-- GUARD_STATUS:BEGIN currency_guardrails_ps -->
Status: IMPLEMENTED_UNVERIFIED
Reason: no qualifying exact-SHA evidence record in docs/ci/GUARD_EVIDENCE_INDEX.yml covers this guard
Canonical command reference: GUARD_COMMANDS.generated.md#currency_guardrails_ps
Scope: external-tool
Owner: @panospao7 (doc anchor: docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md)
<!-- GUARD_STATUS:END currency_guardrails_ps -->

<!-- GUARD_STATUS:BEGIN release_artifact -->
Status: IMPLEMENTED_UNVERIFIED
Reason: no qualifying exact-SHA evidence record in docs/ci/GUARD_EVIDENCE_INDEX.yml covers this guard
Canonical command reference: GUARD_COMMANDS.generated.md#release_artifact
Scope: artifact
Owner: @panospao7 (doc anchor: docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md)
<!-- GUARD_STATUS:END release_artifact -->

Historical bundles are marked `HISTORICAL` in the evidence index and are never current-state authority.
