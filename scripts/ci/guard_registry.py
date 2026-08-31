#!/usr/bin/env python3
"""
GUARD_REGISTRY — Canonical registry of all architecture guards.

Every guard in this registry must:
- Be present in the source tree
- Have a corresponding test file (unless explicitly excluded)
- Follow the guard template
- Have a registered allowlist (if applicable)
- Be listed in the CI manifest

This is the SINGLE SOURCE OF TRUTH for which guards exist in the project.
The CI manifest (run_static_guard_suite.py) and the registry validator both
derive their guard lists from this registry.

Guard modes:
  "blocking" — A violation exits 1 and fails CI.
  "ratchet"  — Blocking, but with a growth-enforcing baseline via guard_ratchet.py.
  "warning"  — A violation records a warning but does not fail CI.
  "policy"   — Meta-guard that validates other guard infrastructure.

Execution schema (PR-GR-10A Slice 1 — additive):
  Every active entry carries an "execution" section that owns HOW the guard
  runs: engine, entrypoint, token arguments (never shell strings), mode,
  requiredInputs, timeoutProfile (named semantics from the suite's time
  budgets), outputContract, ratchet metadata (ratchet guards only),
  testManifest (test files or the literal "none" for documented absence),
  and a documentationAnchor.  Paths are repository-relative and resolved only
  by the execution context (scripts/ci/guard_execution_plan.py).  The
  per-guard argv/timeout/baseline values are transcribed from the static
  suite's GUARD_MANIFEST / GUARD_TIME_BUDGETS — migration of truth, not
  invention.  Existing fields above are kept intact.
"""

import os
from typing import Any, Dict

GUARD_REGISTRY: Dict[str, Dict[str, Any]] = {
    # ── Blocking guards (direct execution) ──────────────────────────────────────

    "source_provenance": {
        "script": "scripts/verify_source_provenance_boundaries.py",
        "tests": None,  # No dedicated test file yet
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,
        "policies": None,
        "description": "Source provenance boundary enforcement — validates "
                       "that all source files belong to expected packages",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_source_provenance_boundaries.py",
            "arguments": ("--root", "."),
            "mode": "blocking",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": "none",
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    },

    "ui_dao": {
        "script": "scripts/verify_ui_dao_boundaries.py",
        "tests": "scripts/test_verify_ui_dao_boundaries.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": "scripts/allowlists/ui_dao_allowlist.yml",
        "policies": None,
        "description": "UI layer DAO access boundary — prevents UI from "
                       "directly accessing DAO methods",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_ui_dao_boundaries.py",
            "arguments": ("--fail-on-violation",),
            "mode": "blocking",
            "requiredInputs": ("scripts/allowlists/ui_dao_allowlist.yml",),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_verify_ui_dao_boundaries.py",),
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    },

    "worker": {
        "script": "scripts/verify_worker_boundaries.py",
        "tests": "scripts/test_verify_worker_boundaries.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": "scripts/allowlists/worker_allowlist.yml",
        "policies": None,
        "description": "Worker boundary enforcement — validates WorkerExecutionGuard "
                       "usage, write barrier semantics, and cancellation propagation",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_worker_boundaries.py",
            "arguments": ("--fail-on-violation",),
            "mode": "blocking",
            "requiredInputs": ("scripts/allowlists/worker_allowlist.yml",),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_verify_worker_boundaries.py",),
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    },

    "receipt_link": {
        "script": "scripts/verify_receipt_link_boundaries.py",
        "tests": "scripts/test_verify_receipt_link_boundaries.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": "scripts/allowlists/receipt_link_allowlist.yml",
        "policies": None,
        "description": "Receipt link boundary — enforces receipt-expense link "
                       "creation only through approved coordinators",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_receipt_link_boundaries.py",
            "arguments": ("--fail-on-violation",),
            "mode": "blocking",
            "requiredInputs": ("scripts/allowlists/receipt_link_allowlist.yml",),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_verify_receipt_link_boundaries.py",),
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    },

    "import_lifecycle": {
        "script": "scripts/verify_import_lifecycle_boundaries.py",
        "tests": "scripts/test_verify_import_lifecycle_boundaries.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": "scripts/allowlists/import_lifecycle_allowlist.yml",
        "policies": None,
        "description": "Import lifecycle boundary — validates import operations "
                       "go through canonical ImportCoordinator",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_import_lifecycle_boundaries.py",
            "arguments": ("--fail-on-violation",),
            "mode": "blocking",
            "requiredInputs": ("scripts/allowlists/import_lifecycle_allowlist.yml",),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_verify_import_lifecycle_boundaries.py",),
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    },

    "cloud_payload": {
        "script": "scripts/verify_cloud_payload_boundaries.py",
        "tests": "scripts/test_verify_cloud_payload_boundaries.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": "scripts/allowlists/cloud_payload_allowlist.yml",
        "policies": None,
        "description": "Cloud payload boundary — ensures PreparedCloudPayload "
                       "precedes network body construction",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_cloud_payload_boundaries.py",
            "arguments": ("--fail-on-violation",),
            "mode": "blocking",
            "requiredInputs": ("scripts/allowlists/cloud_payload_allowlist.yml",),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_verify_cloud_payload_boundaries.py",),
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    },

    "pii_logging": {
        "script": "scripts/verify_pii_logging_boundaries.py",
        "tests": "scripts/test_verify_pii_logging_boundaries.py",
        "mode": "blocking",
        "baseline": None,  # PII is strict-zero, no ratchet needed
        "allowlist": "scripts/allowlists/pii_logging_allowlist.yml",
        "policies": None,
        "description": "PII logging boundary — strict-zero PII in logs, "
                       "diagnostics, exceptions, and persisted results",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_pii_logging_boundaries.py",
            "arguments": ("--fail-on-violation",),
            "mode": "blocking",
            "requiredInputs": ("scripts/allowlists/pii_logging_allowlist.yml",),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_verify_pii_logging_boundaries.py",),
            "documentationAnchor": "docs/ci/guard-policy.md",
        },
    },

    "di_release": {
        "script": "scripts/verify_di_release_boundaries.py",
        "tests": "scripts/test_verify_di_release_boundaries.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": "scripts/allowlists/di_release_allowlist.yml",
        "policies": None,
        "description": "DI release boundary — validates Hilt module bindings "
                       "for release vs debug configurations",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_di_release_boundaries.py",
            "arguments": ("--fail-on-violation",),
            "mode": "blocking",
            "requiredInputs": ("scripts/allowlists/di_release_allowlist.yml",),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_verify_di_release_boundaries.py",),
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    },

    "allowlist_compliance": {
        "script": "scripts/verify_allowlist_compliance.py",
        "tests": "scripts/test_verify_allowlist_compliance.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,  # Meta-guard: validates other allowlists
        "policies": None,
        "description": "Allowlist compliance — validates that all allowlist "
                       "entries have required reason, owner, and expiry fields",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_allowlist_compliance.py",
            "arguments": ("--fail-on-violation",),
            "mode": "blocking",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_verify_allowlist_compliance.py",),
            "documentationAnchor": "docs/ci/guard-policy.md",
        },
    },

    "migration_matrix": {
        "script": "scripts/verify_migration_matrix.py",
        "tests": "scripts/test_verify_migration_matrix.py",
        "mode": "ratchet",
        "baseline": "config/baselines/migration_matrix.json",
        "allowlist": None,
        "policies": None,
        "description": "Migration matrix validator — ensures Room schema "
                       "migrations are consistent and complete; ratcheted "
                       "to block new missing-migration gaps",
        "execution": {
            "engine": "python-ratchet",
            "entrypoint": "scripts/verify_migration_matrix.py",
            "arguments": (),
            "mode": "ratchet",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "ratchet-baseline-v1;stdout-human;exit:0=pass,1=violation,2=infra",
            "ratchet": {
                "baselinePath": "config/baselines/migration_matrix.json",
                "findingProtocol": 1,
                "fingerprintSchema": 1,
                "childArgumentTemplate": ("{entrypoint}", "--fail-on-violation"),
                "ciRestrictions": ("no-update-baseline", "no-propose-baseline"),
            },
            "testManifest": ("scripts/test_verify_migration_matrix.py",),
            "documentationAnchor": "docs/ci/MIGRATION_TEST_PROCEDURE.md",
        },
    },

    "ignored_test_budget": {
        "script": "scripts/verify_ignored_test_budget.py",
        "tests": "scripts/test_verify_ignored_test_budget.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,
        "policies": None,
        "description": "Ignored test budget — enforces a maximum count of "
                       "@Ignore-annotated tests with a configurable baseline",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_ignored_test_budget.py",
            "arguments": ("--fail-on-violation", "--baseline", "29"),
            "mode": "blocking",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_verify_ignored_test_budget.py",),
            "documentationAnchor": "docs/ci/guard-policy.md",
        },
    },

    "lint_baseline_policy": {
        "script": "scripts/verify_lint_baseline_policy.py",
        "tests": None,  # No dedicated test file
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,
        "policies": None,
        "description": "Lint baseline policy — validates that the lint baseline "
                       "only contains allowed issue types (MissingTranslation)",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_lint_baseline_policy.py",
            "arguments": ("--fail-on-violation",),
            "mode": "blocking",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": "none",
            "documentationAnchor": "docs/ci/guard-policy.md",
        },
    },

    "time_boundaries": {
        "script": "scripts/verify_time_boundaries.py",
        "tests": "scripts/test_verify_time_boundaries.py",
        "mode": "blocking",
        "baseline": None,  # No baseline for time violations — strict zero
        "allowlist": "config/guards/time_boundary_exceptions.yml",
        "policies": [
            "config/guards/time_boundary_exceptions.yml",
        ],
        "description": "Time boundary guard (G-TIME-01) — detects direct "
                       "wall-clock APIs (System.currentTimeMillis, "
                       "System.nanoTime, Date(), Calendar.getInstance(), "
                       "Instant.now(), LocalDate.now(), LocalDateTime.now(), "
                       "OffsetDateTime.now(), ZonedDateTime.now(), "
                       "Clock.systemDefaultZone(), Clock.systemUTC()) outside "
                       "exact clock-adapter exceptions. No baselines, no "
                       "broad source-line exemptions.",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_time_boundaries.py",
            "arguments": (
                "--root", ".",
                "--allowlist", "config/guards/time_boundary_exceptions.yml",
                "--fail-on-violation",
            ),
            "mode": "blocking",
            "requiredInputs": ("config/guards/time_boundary_exceptions.yml",),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_verify_time_boundaries.py",),
            "documentationAnchor": "docs/ci/guard-policy.md",
        },
    },

    "deprecation_escalations": {
        "script": "scripts/ci/verify_deprecation_escalations.py",
        "tests": "scripts/ci/test_verify_deprecation_escalations.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,
        "policies": None,
        "description": "Deprecation escalation guard (PR-GR-10a) — every "
                       "@Deprecated(DeprecationLevel.ERROR) site in "
                       "production Kotlin must have a matching entry in the "
                       "tracked changelog docs/ci/DEPRECATION_ESCALATIONS.md "
                       "(file + symbol + date + reason + migration target) "
                       "before landing; stale entries are flagged for cleanup",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/ci/verify_deprecation_escalations.py",
            "arguments": ("--root", "."),
            "mode": "blocking",
            "requiredInputs": ("docs/ci/DEPRECATION_ESCALATIONS.md",),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/ci/test_verify_deprecation_escalations.py",),
            "documentationAnchor": "docs/ci/DEPRECATION_ESCALATIONS.md",
        },
    },

    "db_artifact_sync": {
        "script": "scripts/migrate_db_policy_signatures.py",
        "tests": "scripts/test_migrate_db_policy_signatures.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,
        "policies": [
            "config/guards/db_ownership_policy.legacy.yml",
            "docs/ci/db-findings/GR-08-seeds.yml",
            "config/guards/db_ownership_policy.signatures.candidate.yml",
            "config/guards/db_ownership_policy.signatures.accounting.json",
        ],
        "description": "DB artifact sync tripwire (PR-GR-10b) — the migrate "
                       "CLI's --verify mode regenerates the tracked "
                       "candidate/accounting artifacts IN MEMORY from the "
                       "same reviewed inputs (--seed-rows) and exits 1 when "
                       "the tracked files drift: policy entries byte-exact, "
                       "accounting stable sections byte-exact, coverage "
                       "semantics (R12 contract), and the fold-derived "
                       "distribution. Makes hand-edit drift visible in "
                       "every suite run and CI; never writes the tracked "
                       "artifacts",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/migrate_db_policy_signatures.py",
            "arguments": (
                "--verify",
                "--seed-rows", "docs/ci/db-findings/GR-08-seeds.yml",
            ),
            "mode": "blocking",
            "requiredInputs": (
                "config/guards/db_ownership_policy.legacy.yml",
                "docs/ci/db-findings/GR-08-seeds.yml",
                "config/guards/db_ownership_policy.signatures.candidate.yml",
                "config/guards/db_ownership_policy.signatures.accounting.json",
            ),
            "timeoutProfile": "artifact-sync",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_migrate_db_policy_signatures.py",),
            "documentationAnchor": "docs/ci/DB_POLICY_SIGNATURES.md",
        },
    },

    "known_good_state": {
        "script": "scripts/ci/verify_known_good_state.py",
        "tests": "scripts/ci/test_verify_known_good_state.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,
        "policies": None,
        "description": "Known-good state scorecard (PR-GR-10e/10f) — "
                       "executes the section-7 rows of "
                       "docs/ci/GR00-GR04_validation_checklist.md against "
                       "the live repository (active DB gate accepted with "
                       "20 x DB_SIGNATURE_UNRESOLVED advisories, "
                       "inventory-only platform durability branch, "
                       "migration fold truth 99/57/42, source-roots "
                       "meta-guard silent exit 0, candidate v2 "
                       "byte-reproducible with 472 entries, structural "
                       "manifest pin 64, plus the OPTIONAL PR-GR-10f "
                       "test-result freshness row: SKIP when never "
                       "stamped, FAIL on stale/SHA drift) and exits 1 on "
                       "any drift from the documented known-good state "
                       "(exit 2 infrastructure). Deliberately expensive: "
                       "runs the real gate, inventory, and migration "
                       "fold — meant for explicit suite/orchestrator "
                       "runs, not per-commit loops",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/ci/verify_known_good_state.py",
            "arguments": (),
            "mode": "blocking",
            "requiredInputs": ("docs/ci/GR00-GR04_validation_checklist.md",),
            "timeoutProfile": "known-good-state",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/ci/test_verify_known_good_state.py",),
            "documentationAnchor": "docs/ci/GR00-GR04_validation_checklist.md",
        },
    },

    # ── Ratchet-wrapped guards (growth-enforcing baseline via guard_ratchet.py) ──

    "cancellation": {
        "script": "scripts/verify_cancellation_boundaries.py",
        "tests": "scripts/test_verify_cancellation_boundaries.py",
        "mode": "ratchet",
        "baseline": "config/baselines/cancellation.json",
        "allowlist": "scripts/allowlists/cancellation_allowlist.yml",
        "policies": None,
        "description": "Cancellation boundary guard — detects unsafe "
                       "CancellationException handling in suspend/worker paths",
        "execution": {
            "engine": "python-ratchet",
            "entrypoint": "scripts/verify_cancellation_boundaries.py",
            "arguments": (),
            "mode": "ratchet",
            "requiredInputs": ("scripts/allowlists/cancellation_allowlist.yml",),
            "timeoutProfile": "standard",
            "outputContract": "ratchet-baseline-v1;stdout-human;exit:0=pass,1=violation,2=infra",
            "ratchet": {
                "baselinePath": "config/baselines/cancellation.json",
                "findingProtocol": 1,
                "fingerprintSchema": 1,
                "childArgumentTemplate": ("{entrypoint}",),
                "ciRestrictions": ("no-update-baseline", "no-propose-baseline"),
            },
            "testManifest": ("scripts/test_verify_cancellation_boundaries.py",),
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    },

    "privacy": {
        "script": "scripts/verify_privacy_boundaries.py",
        "tests": None,  # No dedicated test file
        "mode": "ratchet",
        "baseline": "config/baselines/privacy.json",
        "allowlist": None,
        "policies": None,
        "description": "Privacy boundary enforcement — cloud redaction, "
                       "privacy gate, pseudonym, and export guards (G1–G14)",
        "execution": {
            "engine": "python-ratchet",
            "entrypoint": "scripts/verify_privacy_boundaries.py",
            "arguments": (),
            "mode": "ratchet",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "ratchet-baseline-v1;stdout-human;exit:0=pass,1=violation,2=infra",
            "ratchet": {
                "baselinePath": "config/baselines/privacy.json",
                "findingProtocol": 1,
                "fingerprintSchema": 1,
                "childArgumentTemplate": ("{entrypoint}", "--root", "."),
                "ciRestrictions": ("no-update-baseline", "no-propose-baseline"),
            },
            "testManifest": "none",
            "documentationAnchor": "docs/ci/guard-policy.md",
        },
    },

    "db_access": {
        "script": "scripts/verify_db_access_boundaries.py",
        "tests": "scripts/test_verify_db_access_boundaries.py",
        "mode": "ratchet",
        "baseline": "config/baselines/db_access_v2.json",
        "allowlist": None,  # Legacy config/db_access_allowlist.yml is superseded
        "policies": [
            "config/guards/db_ownership_policy.yml",
            "config/guards/db_structural_exceptions.yml",
            "config/guards/db_structural_exceptions_expected_methods.yml",
            "config/guards/production_source_roots.yml",
        ],
        "finding_protocol": 2,
        "fingerprint_schema": 2,
        "report_command": "scripts/verify_db_access_boundaries.py",
        "report_guard_metadata": {
            "env_file": "COST_AGGREGATOR_GUARD_FINDINGS_FILE",
            "env_schema": "COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA",
            "flags": ["--fail-on-violation", "--structural-manifest"],
        },
        "description": "DB access boundary — global write/read/restore barrier "
                       "with ownership policy, structural exceptions, and the "
                       "structural expected-methods manifest. Protocol v2: "
                       "structured findings via report file, never stdout.",
        "execution": {
            "engine": "python-ratchet",
            "entrypoint": "scripts/verify_db_access_boundaries.py",
            "arguments": (),
            "mode": "ratchet",
            "requiredInputs": (
                "config/guards/db_ownership_policy.yml",
                "config/guards/db_structural_exceptions.yml",
                "config/guards/db_structural_exceptions_expected_methods.yml",
                "config/guards/production_source_roots.yml",
            ),
            "timeoutProfile": "D4",
            "outputContract": "findings-report-v2;ratchet-baseline-v2;stdout-human;exit:0=pass,1=violation,2=infra",
            "ratchet": {
                "baselinePath": "config/baselines/db_access_v2.json",
                "findingProtocol": 2,
                "fingerprintSchema": 2,
                "childArgumentTemplate": (
                    "{entrypoint}",
                    "--fail-on-violation",
                    "--structural-manifest",
                    "config/guards/db_structural_exceptions_expected_methods.yml",
                    "--ownership-policy",
                    "config/guards/db_ownership_policy.yml",
                    "--structural-exceptions",
                    "config/guards/db_structural_exceptions.yml",
                ),
                "ciRestrictions": ("no-update-baseline", "no-propose-baseline"),
            },
            "testManifest": ("scripts/test_verify_db_access_boundaries.py",),
            "documentationAnchor": "docs/ci/GUARD_FINDING_PROTOCOL.md",
        },
    },

    "event_writers": {
        "script": "scripts/verify_event_writers.py",
        "tests": None,  # No dedicated test file
        "mode": "ratchet",
        "baseline": "config/baselines/event_writers.json",
        "allowlist": None,
        "policies": None,
        "description": "Event writer boundary — ensures diagnostic/lifecycle "
                       "event construction only through canonical writers",
        "execution": {
            "engine": "python-ratchet",
            "entrypoint": "scripts/verify_event_writers.py",
            "arguments": (),
            "mode": "ratchet",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "ratchet-baseline-v1;stdout-human;exit:0=pass,1=violation,2=infra",
            "ratchet": {
                "baselinePath": "config/baselines/event_writers.json",
                "findingProtocol": 1,
                "fingerprintSchema": 1,
                "childArgumentTemplate": ("{entrypoint}", "--fail-on-violation"),
                "ciRestrictions": ("no-update-baseline", "no-propose-baseline"),
            },
            "testManifest": "none",
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    },

    "money": {
        "script": "scripts/verify_money_boundaries.py",
        "tests": None,  # No dedicated test file
        "mode": "ratchet",
        "baseline": "config/baselines/money.json",
        "allowlist": None,
        "policies": None,
        "description": "Money boundary guard — enforces safe currency conversion, "
                       "aggregation, and spending trend rules (G-MONEY-10–21)",
        "execution": {
            "engine": "python-ratchet",
            "entrypoint": "scripts/verify_money_boundaries.py",
            "arguments": (),
            "mode": "ratchet",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "ratchet-baseline-v1;stdout-human;exit:0=pass,1=violation,2=infra",
            "ratchet": {
                "baselinePath": "config/baselines/money.json",
                "findingProtocol": 1,
                "fingerprintSchema": 1,
                "childArgumentTemplate": ("{entrypoint}", "--root", "."),
                "ciRestrictions": ("no-update-baseline", "no-propose-baseline"),
            },
            "testManifest": "none",
            "documentationAnchor": "docs/ci/guard-policy.md",
        },
    },

    # PR-GR-10A Slice 3 — EXTRACTED_AND_REGISTERED: the retired Gradle KTS
    # inline scanner checkRawMoneyAggregates, extracted 1:1 (no rule widened,
    # narrowed, added, or removed) into a standalone canonical guard with
    # stable rule IDs G-MONEY-RAW-01..07.  Disposition evidence:
    # docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md (inline money scanner row).
    "raw_money_aggregates": {
        "script": "scripts/verify_raw_money_aggregates.py",
        "tests": "scripts/test_verify_raw_money_aggregates.py",
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,
        "policies": None,
        "description": "Raw money aggregate boundary (G-MONEY-RAW-01..07) — "
                       "flags raw Double financial aggregates "
                       "(sumOf { it.amount/effectiveAmount/normalizedAmount }, "
                       "sumBy { it.amount.toInt() }, `total: Double`, "
                       "`var total = 0.0` sum accumulators) outside the "
                       "MoneyAggregate primitive surface; extracted 1:1 from "
                       "the retired Gradle KTS inline scanner "
                       "checkRawMoneyAggregates (PR-E23)",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_raw_money_aggregates.py",
            "arguments": ("--fail-on-violation",),
            "mode": "blocking",
            "ruleIds": (
                "G-MONEY-RAW-01", "G-MONEY-RAW-02", "G-MONEY-RAW-03",
                "G-MONEY-RAW-04", "G-MONEY-RAW-05", "G-MONEY-RAW-06",
                "G-MONEY-RAW-07",
            ),
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": ("scripts/test_verify_raw_money_aggregates.py",),
            "documentationAnchor": "docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md",
        },
    },

    # ── Declared-external enforcement entries (PR-GR-10A Slice 3) ───────────────
    # REGISTERED_EXTERNAL_ENGINE dispositions: genuinely non-Python / non-suite
    # proof surfaces.  They are registry-declared (owner, command, scope,
    # artifacts, CI job) so no enforcement path stays invisible, but they are
    # NOT compiled into the canonical suite plan and are never executed by the
    # Python runner bridge (compile_static_suite_plan skips declared-external
    # engines; run_registered_guard rejects them with exit 2).  Disposition
    # evidence: docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md.

    "currency_guardrails_ps": {
        "script": "scripts/currency_guardrails.ps1",
        "tests": None,  # PowerShell proof surface; no pytest harness (documented absence)
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,
        "policies": None,
        "description": "Currency guardrails (PowerShell, CI unit-tests job) — "
                       "check 1 (blocking): raw sumOf { ... effectiveAmount } "
                       "occurrences without a preceding // SAFE: marker; "
                       "checks 2-3 (advisory report only): deprecated single-arg "
                       "CurrencyFormatter.format(amount) calls and \"EUR\" "
                       "hardcodes.  Genuinely non-Python proof surface; the "
                       "blocking check-1 family overlaps the registered "
                       "raw_money_aggregates guard (G-MONEY-RAW-02)",
        "execution": {
            "engine": "external",
            "entrypoint": "scripts/currency_guardrails.ps1",
            "arguments": ("-SourceDir", "app/src/main/java"),
            "mode": "blocking",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human-report;exit:0=pass,1=check1-violation,2=infra",
            "testManifest": "none",
            "documentationAnchor": "docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md",
        },
    },

    "release_artifact": {
        "script": "scripts/verify_release_artifact.py",
        "tests": None,  # No dedicated test file (documented absence)
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,
        "policies": None,
        "description": "Release artifact verifier — validates the assembled "
                       "release APK (signing, package, version) after "
                       "assembleRelease in the separate release-check CI job; "
                       "explicitly outside the static guard suite",
        "execution": {
            "engine": "external",
            "entrypoint": "scripts/verify_release_artifact.py",
            "arguments": ("--fail-on-violation",),
            "mode": "blocking",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": "none",
            "documentationAnchor": "docs/ci/GR-10A_COMMAND_AUTHORITY_MATRIX.md",
        },
    },
}

# ── Registry validation ─────────────────────────────────────────────────────────


def validate_registry(project_root: str = ".") -> list:
    """Verify all registered guards exist and have required files.

    Args:
        project_root: Root directory for resolving relative paths.

    Returns:
        List of error message strings. Empty list means valid.
    """
    errors = []

    for name, guard in GUARD_REGISTRY.items():
        script_path = os.path.join(project_root, guard["script"])
        if not os.path.exists(script_path):
            errors.append(f"{name}: script not found: {guard['script']}")

        # Validate test file if registered (None = explicitly excluded)
        if guard.get("tests") is not None:
            test_path = os.path.join(project_root, guard["tests"])
            if not os.path.exists(test_path):
                errors.append(f"{name}: test file not found: {guard['tests']}")

        # Validate allowlist if registered
        if guard.get("allowlist") is not None:
            allowlist_path = os.path.join(project_root, guard["allowlist"])
            if not os.path.exists(allowlist_path):
                errors.append(f"{name}: allowlist not found: {guard['allowlist']}")

        # Validate baseline if registered
        if guard.get("baseline") is not None:
            baseline_path = os.path.join(project_root, guard["baseline"])
            if not os.path.exists(baseline_path):
                errors.append(f"{name}: baseline not found: {guard['baseline']}")

        # Validate policy files if registered
        for policy_path in (guard.get("policies") or []):
            full_policy = os.path.join(project_root, policy_path)
            if not os.path.exists(full_policy):
                errors.append(f"{name}: policy file not found: {policy_path}")

        # Validate mode is recognized
        if guard.get("mode") not in ("blocking", "ratchet", "warning", "policy"):
            errors.append(f"{name}: unrecognized mode '{guard.get('mode')}'")

        # Validate description is non-empty
        if not guard.get("description"):
            errors.append(f"{name}: missing description")

    # Check for duplicate scripts (no two guards should share the same script)
    seen_scripts = {}
    for name, guard in GUARD_REGISTRY.items():
        script = guard["script"]
        if script in seen_scripts:
            errors.append(
                f"{name}: script conflict with '{seen_scripts[script]}' "
                f"(both use '{script}')"
            )
        seen_scripts[script] = name

    return errors


# ── CLI entry point ─────────────────────────────────────────────────────────────

if __name__ == "__main__":
    import sys

    project_root = sys.argv[1] if len(sys.argv) > 1 else "."
    errs = validate_registry(project_root)
    if errs:
        for e in errs:
            print(f"REGISTRY ERROR: {e}")
        sys.exit(1)
    print(f"Guard registry: {len(GUARD_REGISTRY)} guards registered and valid")
    sys.exit(0)
