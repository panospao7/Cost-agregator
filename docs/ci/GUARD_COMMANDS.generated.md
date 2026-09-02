<!-- GENERATED FILE — do not edit by hand. -->
<!-- Renderer: scripts/ci/generate_guard_docs.py (PR-GR-10D). -->
<!-- Inputs: scripts/ci/guard_registry.py execution schema (GR-10A), registry sourceScope fields (GR-10B), docs/ci/GUARD_EVIDENCE_INDEX.yml. -->
<!-- Regenerate: python scripts/ci/generate_guard_docs.py --root . -->

# Guard Commands — Generated Reference

Canonical command identity for every registered guard, compiled from
the registry execution schema (PR-GR-10A) via
`guard_execution_plan.compile_static_suite_plan`. Source-scope
classifications are the registry `sourceScope` values (PR-GR-10B),
kept in lockstep with `docs/ci/GR-10B_SOURCE_SCOPE_MATRIX.md` by
`scripts/ci/test_gr10b_source_scope_matrix.py`. This document is the
only place command identities are spelled out; other documents link
here instead of pasting commands.

Command identities are token lists: `<resolved-interpreter>` stands
for the runtime interpreter (never a bare `python`/`python3`), and
every path is repository-relative. Ratchet guards run under
`scripts/ci/guard_ratchet.py`; the outer identity carries the child
argv as repeated single-token `--command-arg=<value>` entries.

## source_provenance

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
- source-scope: production-kotlin-filtered
- engine: python-direct
- timeout-profile: standard (300s)
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_source_provenance_boundaries.py --root .
- documentation-anchor: docs/ci/guard-framework.md

## ui_dao

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
- source-scope: production-kotlin-filtered
- engine: python-direct
- timeout-profile: standard (300s)
- required-inputs: scripts/allowlists/ui_dao_allowlist.yml
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_ui_dao_boundaries.py --fail-on-violation
- documentation-anchor: docs/ci/guard-framework.md

## worker

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
- source-scope: production-kotlin-filtered
- engine: python-direct
- timeout-profile: standard (300s)
- required-inputs: scripts/allowlists/worker_allowlist.yml
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_worker_boundaries.py --fail-on-violation
- documentation-anchor: docs/ci/guard-framework.md

## receipt_link

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
- source-scope: production-kotlin-filtered
- engine: python-direct
- timeout-profile: standard (300s)
- required-inputs: scripts/allowlists/receipt_link_allowlist.yml
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_receipt_link_boundaries.py --fail-on-violation
- documentation-anchor: docs/ci/guard-framework.md

## import_lifecycle

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
- source-scope: production-kotlin-filtered
- engine: python-direct
- timeout-profile: standard (300s)
- required-inputs: scripts/allowlists/import_lifecycle_allowlist.yml
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_import_lifecycle_boundaries.py --fail-on-violation
- documentation-anchor: docs/ci/guard-framework.md

## cloud_payload

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
- source-scope: production-kotlin-filtered
- engine: python-direct
- timeout-profile: standard (300s)
- required-inputs: scripts/allowlists/cloud_payload_allowlist.yml
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_cloud_payload_boundaries.py --fail-on-violation
- documentation-anchor: docs/ci/guard-framework.md

## pii_logging

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
- source-scope: production-kotlin-filtered
- engine: python-direct
- timeout-profile: standard (300s)
- required-inputs: scripts/allowlists/pii_logging_allowlist.yml
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_pii_logging_boundaries.py --fail-on-violation
- documentation-anchor: docs/ci/guard-framework.md

## di_release

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
- source-scope: production-kotlin-filtered
- engine: python-direct
- timeout-profile: standard (300s)
- required-inputs: scripts/allowlists/di_release_allowlist.yml
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_di_release_boundaries.py --fail-on-violation
- documentation-anchor: docs/ci/guard-framework.md

## allowlist_compliance

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
- source-scope: repository-config
- engine: python-direct
- timeout-profile: standard (300s)
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_allowlist_compliance.py --fail-on-violation
- documentation-anchor: docs/ci/guard-policy.md

## migration_matrix

- mode: ratchet
- owner: @panospao7 (doc anchor: docs/ci/MIGRATION_TEST_PROCEDURE.md)
- source-scope: production-kotlin-targeted
- engine: python-ratchet
- timeout-profile: standard (300s)
- finding-protocol: 1
- baseline: config/baselines/migration_matrix.json
- output-contract: ratchet-baseline-v1;stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/ci/guard_ratchet.py --guard-name migration_matrix --baseline config/baselines/migration_matrix.json --finding-protocol=1 --fail-on-violation --ci-mode --command-arg=<resolved-interpreter> --command-arg=scripts/verify_migration_matrix.py --command-arg=--fail-on-violation
- ratchet-child-identity: <resolved-interpreter> scripts/verify_migration_matrix.py --fail-on-violation
- documentation-anchor: docs/ci/MIGRATION_TEST_PROCEDURE.md

## ignored_test_budget

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
- source-scope: test-source
- engine: python-direct
- timeout-profile: standard (300s)
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_ignored_test_budget.py --fail-on-violation --baseline 29
- documentation-anchor: docs/ci/guard-policy.md

## lint_baseline_policy

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
- source-scope: repository-config
- engine: python-direct
- timeout-profile: standard (300s)
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_lint_baseline_policy.py --fail-on-violation
- documentation-anchor: docs/ci/guard-policy.md

## time_boundaries

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
- source-scope: production-kotlin-all
- engine: python-direct
- timeout-profile: standard (300s)
- required-inputs: config/guards/time_boundary_exceptions.yml
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_time_boundaries.py --root . --allowlist config/guards/time_boundary_exceptions.yml --fail-on-violation
- documentation-anchor: docs/ci/guard-policy.md

## deprecation_escalations

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/DEPRECATION_ESCALATIONS.md)
- source-scope: production-kotlin-all
- engine: python-direct
- timeout-profile: standard (300s)
- required-inputs: docs/ci/DEPRECATION_ESCALATIONS.md
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/ci/verify_deprecation_escalations.py --root .
- documentation-anchor: docs/ci/DEPRECATION_ESCALATIONS.md

## db_artifact_sync

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/DB_POLICY_SIGNATURES.md)
- source-scope: repository-config
- engine: python-direct
- timeout-profile: artifact-sync (600s)
- required-inputs: config/guards/db_ownership_policy.legacy.yml, docs/ci/db-findings/GR-08-seeds.yml, config/guards/db_ownership_policy.signatures.candidate.yml, config/guards/db_ownership_policy.signatures.accounting.json
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/migrate_db_policy_signatures.py --verify --seed-rows docs/ci/db-findings/GR-08-seeds.yml
- documentation-anchor: docs/ci/DB_POLICY_SIGNATURES.md

## known_good_state

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/GR00-GR04_validation_checklist.md)
- source-scope: repository-config
- engine: python-direct
- timeout-profile: known-good-state (1200s)
- required-inputs: docs/ci/GR00-GR04_validation_checklist.md
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/ci/verify_known_good_state.py
- documentation-anchor: docs/ci/GR00-GR04_validation_checklist.md

## guard_docs_truth

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/GUARD_DOCUMENT_INDEX.yml)
- source-scope: repository-config
- engine: python-direct
- timeout-profile: standard (300s)
- required-inputs: docs/ci/GUARD_DOCUMENT_INDEX.yml, docs/ci/GUARD_EVIDENCE_INDEX.yml, docs/ci/GUARD_COMMANDS.generated.md, docs/ci/GUARD_STATUS.generated.md
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/ci/verify_guard_docs_truth.py --root .
- documentation-anchor: docs/ci/GUARD_DOCUMENT_INDEX.yml

## cancellation

- mode: ratchet
- owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
- source-scope: production-kotlin-filtered
- engine: python-ratchet
- timeout-profile: standard (300s)
- finding-protocol: 1
- baseline: config/baselines/cancellation.json
- required-inputs: scripts/allowlists/cancellation_allowlist.yml
- output-contract: ratchet-baseline-v1;stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/ci/guard_ratchet.py --guard-name cancellation --baseline config/baselines/cancellation.json --finding-protocol=1 --fail-on-violation --ci-mode --command-arg=<resolved-interpreter> --command-arg=scripts/verify_cancellation_boundaries.py
- ratchet-child-identity: <resolved-interpreter> scripts/verify_cancellation_boundaries.py
- documentation-anchor: docs/ci/guard-framework.md

## privacy

- mode: ratchet
- owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
- source-scope: production-kotlin-filtered
- engine: python-ratchet
- timeout-profile: standard (300s)
- finding-protocol: 1
- baseline: config/baselines/privacy.json
- output-contract: ratchet-baseline-v1;stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/ci/guard_ratchet.py --guard-name privacy --baseline config/baselines/privacy.json --finding-protocol=1 --fail-on-violation --ci-mode --command-arg=<resolved-interpreter> --command-arg=scripts/verify_privacy_boundaries.py --command-arg=--root --command-arg=.
- ratchet-child-identity: <resolved-interpreter> scripts/verify_privacy_boundaries.py --root .
- documentation-anchor: docs/ci/guard-policy.md

## db_access

- mode: ratchet
- owner: @panospao7 (doc anchor: docs/ci/GUARD_FINDING_PROTOCOL.md)
- source-scope: production-kotlin-all
- engine: python-ratchet
- timeout-profile: D4 (840s)
- finding-protocol: 2
- baseline: config/baselines/db_access_v2.json
- required-inputs: config/guards/db_ownership_policy.yml, config/guards/db_structural_exceptions.yml, config/guards/db_structural_exceptions_expected_methods.yml, config/guards/production_source_roots.yml
- output-contract: findings-report-v2;ratchet-baseline-v2;stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/ci/guard_ratchet.py --guard-name db_access --baseline config/baselines/db_access_v2.json --finding-protocol=2 --fail-on-violation --ci-mode --command-arg=<resolved-interpreter> --command-arg=scripts/verify_db_access_boundaries.py --command-arg=--fail-on-violation --command-arg=--structural-manifest --command-arg=config/guards/db_structural_exceptions_expected_methods.yml --command-arg=--ownership-policy --command-arg=config/guards/db_ownership_policy.yml --command-arg=--structural-exceptions --command-arg=config/guards/db_structural_exceptions.yml
- ratchet-child-identity: <resolved-interpreter> scripts/verify_db_access_boundaries.py --fail-on-violation --structural-manifest config/guards/db_structural_exceptions_expected_methods.yml --ownership-policy config/guards/db_ownership_policy.yml --structural-exceptions config/guards/db_structural_exceptions.yml
- documentation-anchor: docs/ci/GUARD_FINDING_PROTOCOL.md

## event_writers

- mode: ratchet
- owner: @panospao7 (doc anchor: docs/ci/guard-framework.md)
- source-scope: production-kotlin-filtered
- engine: python-ratchet
- timeout-profile: standard (300s)
- finding-protocol: 1
- baseline: config/baselines/event_writers.json
- output-contract: ratchet-baseline-v1;stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/ci/guard_ratchet.py --guard-name event_writers --baseline config/baselines/event_writers.json --finding-protocol=1 --fail-on-violation --ci-mode --command-arg=<resolved-interpreter> --command-arg=scripts/verify_event_writers.py --command-arg=--fail-on-violation
- ratchet-child-identity: <resolved-interpreter> scripts/verify_event_writers.py --fail-on-violation
- documentation-anchor: docs/ci/guard-framework.md

## money

- mode: ratchet
- owner: @panospao7 (doc anchor: docs/ci/guard-policy.md)
- source-scope: production-kotlin-filtered
- engine: python-ratchet
- timeout-profile: standard (300s)
- finding-protocol: 1
- baseline: config/baselines/money.json
- output-contract: ratchet-baseline-v1;stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/ci/guard_ratchet.py --guard-name money --baseline config/baselines/money.json --finding-protocol=1 --fail-on-violation --ci-mode --command-arg=<resolved-interpreter> --command-arg=scripts/verify_money_boundaries.py --command-arg=--root --command-arg=.
- ratchet-child-identity: <resolved-interpreter> scripts/verify_money_boundaries.py --root .
- documentation-anchor: docs/ci/guard-policy.md

## raw_money_aggregates

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md)
- source-scope: production-kotlin-filtered
- engine: python-direct
- timeout-profile: standard (300s)
- output-contract: stdout-human;exit:0=pass,1=violation,2=infra
- command-identity: <resolved-interpreter> scripts/verify_raw_money_aggregates.py --fail-on-violation
- documentation-anchor: docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md

## currency_guardrails_ps

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md)
- source-scope: external-tool
- engine: external
- suite-participation: excluded from the canonical suite plan (declared external; warning diagnostic E_ENGINE_EXTERNAL_SKIPPED); the Python runner bridge never executes it
- entrypoint: scripts/currency_guardrails.ps1
- arguments: -SourceDir app/src/main/java
- documentation-anchor: docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md

## release_artifact

- mode: blocking
- owner: @panospao7 (doc anchor: docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md)
- source-scope: artifact
- engine: external
- suite-participation: excluded from the canonical suite plan (declared external; warning diagnostic E_ENGINE_EXTERNAL_SKIPPED); the Python runner bridge never executes it
- entrypoint: scripts/verify_release_artifact.py
- arguments: --fail-on-violation
- documentation-anchor: docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md

Declared-external guards excluded from the compiled suite plan: currency_guardrails_ps, release_artifact.

Guard status and evidence state are maintained in docs/ci/GUARD_STATUS.generated.md.
