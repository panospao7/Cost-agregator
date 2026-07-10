#!/usr/bin/env python3
"""
VERIFY_GUARD_REGISTRY — Validates that all registered guards are present and
consistent with the CI manifest.

Validates:
  1. Every registered guard has its required files (script, tests, allowlist, baseline).
  2. The guard registry and CI manifest (run_static_guard_suite.py) are consistent.
  3. No duplicate scripts, no missing descriptions, no unrecognized modes.

Exit codes:
  0 — Registry valid and consistent with CI manifest.
  1 — Inconsistencies or missing files found.

Usage:
  python3 scripts/ci/verify_guard_registry.py
  python3 scripts/ci/verify_guard_registry.py --root /path/to/project
"""

import argparse
import os
import sys
from typing import Set

# Ensure the script directory is importable for sibling modules
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from guard_registry import GUARD_REGISTRY, validate_registry  # type: ignore


def _extract_manifest_names() -> Set[str]:
    """Extract guard names from the CI manifest (run_static_guard_suite.py).

    Returns a set of guard names (excluding the "guard_tests" meta-entry).
    """
    manifest_path = os.path.join(SCRIPT_DIR, "run_static_guard_suite.py")
    if not os.path.exists(manifest_path):
        print(f"ERROR: CI manifest not found: {manifest_path}", file=sys.stderr)
        return set()

    # Parse the GUARD_MANIFEST list from the suite runner
    import importlib.util
    spec = importlib.util.spec_from_file_location(
        "run_static_guard_suite", manifest_path
    )
    module = importlib.util.module_from_spec(spec)
    try:
        spec.loader.exec_module(module)  # type: ignore
    except Exception as e:
        print(f"ERROR: Failed to load CI manifest: {e}", file=sys.stderr)
        return set()

    manifest = getattr(module, "GUARD_MANIFEST", [])
    # Exclude infrastructure entries that are not guards themselves
    _INFRA_NAMES = {"guard_tests", "guard_registry"}
    return {entry[0] for entry in manifest if entry[0] not in _INFRA_NAMES}


def _validate_registry_self_consistency() -> list:
    """Validate internal consistency of the registry (not file existence).

    Returns list of error strings.
    """
    errors = []
    for name, guard in GUARD_REGISTRY.items():
        # Ratchet guards must have a baseline
        if guard.get("mode") == "ratchet":
            if not guard.get("baseline"):
                errors.append(
                    f"{name}: ratchet mode requires a baseline, but none is registered"
                )
        # Blocking guards with baselines: warn but do not error
        # (e.g., pii_logging maintains a baseline for reference/tracking)
        if guard.get("mode") == "blocking" and guard.get("baseline"):
            print(
                f"NOTE: {name} is a blocking guard but has a baseline "
                f"'{guard['baseline']}' registered. This is allowed for tracking "
                f"purposes but ensures the baseline is not used for ratchet enforcement.",
                file=sys.stderr,
            )
    return errors


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Validate the guard registry against the filesystem and CI manifest."
    )
    parser.add_argument(
        "--root",
        type=str,
        default=None,
        help="Project root directory (default: auto-detect from script location).",
    )
    args = parser.parse_args()

    if args.root:
        project_root = os.path.abspath(args.root)
    else:
        # Auto-detect: go two levels up from scripts/ci/
        project_root = os.path.abspath(os.path.join(SCRIPT_DIR, "..", ".."))

    print(f"Project root: {project_root}")
    print(f"Registered guards: {len(GUARD_REGISTRY)}")
    print()

    has_errors = False

    # ── 1. File existence validation ────────────────────────────────────────────
    print("--- File existence check ---")
    file_errors = validate_registry(project_root)
    if file_errors:
        for e in file_errors:
            print(f"REGISTRY ERROR: {e}")
        has_errors = True
    else:
        print("All registered files exist.")

    # ── 2. Self-consistency checks ──────────────────────────────────────────────
    print()
    print("--- Self-consistency check ---")
    consistency_errors = _validate_registry_self_consistency()
    if consistency_errors:
        for e in consistency_errors:
            print(f"REGISTRY ERROR: {e}")
        has_errors = True
    else:
        print("Registry is internally consistent.")

    # ── 3. Registry vs CI manifest consistency ──────────────────────────────────
    print()
    print("--- Registry vs CI manifest check ---")
    registry_names = set(GUARD_REGISTRY.keys())
    manifest_names = _extract_manifest_names()

    if not manifest_names:
        print("REGISTRY ERROR: Could not extract guard names from CI manifest")
        has_errors = True
    else:
        missing = registry_names - manifest_names
        extra = manifest_names - registry_names

        if missing:
            for m in sorted(missing):
                print(f"REGISTRY GAP: '{m}' in registry but NOT in CI manifest")
            has_errors = True

        if extra:
            for e in sorted(extra):
                print(f"REGISTRY EXTRA: '{e}' in CI manifest but NOT in registry")
            has_errors = True

        if not missing and not extra:
            print(f"Registry ({len(registry_names)} guards) and CI manifest "
                  f"({len(manifest_names)} guards) are consistent.")

    # ── 4. Summary ──────────────────────────────────────────────────────────────
    print()
    if has_errors:
        print("Guard registry: INVALID — errors detected (see above)")
        sys.exit(1)
    else:
        print("Guard registry: VALID and consistent with CI manifest")
        sys.exit(0)


if __name__ == "__main__":
    main()
