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
    },

    "pii_logging": {
        "script": "scripts/verify_pii_logging_boundaries.py",
        "tests": "scripts/test_verify_pii_logging_boundaries.py",
        "mode": "blocking",
        "baseline": "config/baselines/pii_logging.json",
        "allowlist": "scripts/allowlists/pii_logging_allowlist.yml",
        "policies": None,
        "description": "PII logging boundary — strict-zero PII in logs, "
                       "diagnostics, exceptions, and persisted results",
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
    },

    "db_access": {
        "script": "scripts/verify_db_access_boundaries.py",
        "tests": "scripts/test_verify_db_access_boundaries.py",
        "mode": "ratchet",
        "baseline": "config/baselines/db_access.json",
        "allowlist": "config/db_access_allowlist.yml",
        "policies": [
            "config/guards/db_ownership_policy.yml",
            "config/guards/db_structural_exceptions.yml",
        ],
        "description": "DB access boundary — global write/read/restore barrier "
                       "with ownership policy and structural exceptions",
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
    },
}

# ── Infrastructure entry (not a guard, but tracked for completeness) ────────────
# "release_artifact" guard runs in a separate CI job (release-check) after
# assembleRelease. It is NOT included in the static guard suite.
# "guard_tests" is the pytest runner for guard test files, also not a guard.

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
