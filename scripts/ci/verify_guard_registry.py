#!/usr/bin/env python3
"""
VERIFY_GUARD_REGISTRY — Validates the guard registry and its compiled suite
plan.

Direction of authority (PR-GR-10A): the registry is the single source of
truth; this validator compiles the canonical suite plan FROM the registry
execution schema (guard_execution_plan.compile_static_suite_plan) and
validates that plan.  It NEVER imports the static suite runner, and the
static suite must not carry a legacy hard-coded manifest assignment.

Validates:
  1. Every registered guard has its required files (script, tests,
     allowlist, baseline, policies).
  2. Registry self-consistency (ratchet guards carry baselines, modes are
     recognized, no duplicate scripts).
  3. The registry compiles into the canonical suite plan: every active
     guard appears exactly once, in registry order; every ratchet plan
     carries a baseline, an explicit finding protocol, and a child argv;
     every required input exists under the repo root.
  4. The static suite no longer assigns a legacy hard-coded manifest and
     wires the registry-derived plan compiler.

Exit codes:
  0 — Registry valid and consistent with its compiled suite plan.
  1 — Inconsistencies or missing files found.

Usage:
  python3 scripts/ci/verify_guard_registry.py
  python3 scripts/ci/verify_guard_registry.py --root /path/to/project
"""

import argparse
import ast
import os
import sys
from typing import List, Set

# Ensure the script directory is importable for sibling modules
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from guard_execution_plan import (  # noqa: E402
    DEFAULT_REGISTRY_PATH,
    ExecutionContext,
    ExecutionPlan,
    compile_static_suite_plan,
    load_guard_specs,
    validate_guard_specs,
)
from guard_registry import GUARD_REGISTRY, validate_registry  # type: ignore

# Legacy hard-coded suite command lists whose assignment in production suite
# code is forbidden (the registry-derived plan is the only command authority).
LEGACY_MANIFEST_NAMES = frozenset({"GUARD_MANIFEST", "GUARD_TIME_BUDGETS"})

SUITE_RUNNER_FILENAME = "run_static_guard_suite.py"
COMPILER_MODULE_NAME = "guard_execution_plan"
COMPILER_SUITE_ENTRY = "compile_static_suite_plan"


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


def validate_compiled_suite_plan(
    plans: List[ExecutionPlan],
    registry_order: List[str],
) -> List[str]:
    """Validate the compiled canonical suite plan against the registry.

    Checks (plan Step 5): every active registry guard appears exactly once,
    in deterministic registry order; every ratchet plan carries a baseline,
    an explicit finding protocol, and a child argv.  Compilation itself
    already enforces repo-relative tokens, no shell strings, no unresolved
    templates, and required-input existence.

    Returns a list of bounded error strings (empty when valid).
    """
    errors: List[str] = []
    plan_ids = [plan.guard_id for plan in plans]

    duplicates = sorted({gid for gid in plan_ids if plan_ids.count(gid) > 1})
    for gid in duplicates:
        errors.append(f"compiled suite plan: guard '{gid}' appears more than once")

    missing = [gid for gid in registry_order if plan_ids.count(gid) == 0]
    for gid in missing:
        errors.append(f"compiled suite plan: registry guard '{gid}' is missing")
    extra = [gid for gid in plan_ids if gid not in set(registry_order)]
    for gid in extra:
        errors.append(f"compiled suite plan: unknown guard '{gid}' is present")

    if not missing and not extra and not duplicates and plan_ids != list(registry_order):
        errors.append(
            "compiled suite plan: guard order is not the deterministic "
            "registry order"
        )

    for plan in plans:
        is_ratchet = plan.mode == "ratchet" or plan.engine == "python-ratchet"
        if not is_ratchet:
            continue
        if not plan.baseline:
            errors.append(f"compiled suite plan: ratchet guard '{plan.guard_id}' has no baseline")
        if plan.protocol not in (1, 2):
            errors.append(
                f"compiled suite plan: ratchet guard '{plan.guard_id}' has no "
                f"explicit finding protocol"
            )
        if not plan.child_argv:
            errors.append(
                f"compiled suite plan: ratchet guard '{plan.guard_id}' has no child argv"
            )
    return errors


def forbidden_manifest_assignments(source: str) -> List[str]:
    """Return legacy manifest names ASSIGNED in the given suite source.

    AST-based: only actual assignments (Assign / AnnAssign / AugAssign)
    count, so documentation strings may still explain the migration.
    """
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return sorted(LEGACY_MANIFEST_NAMES)
    assigned: Set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.Assign):
            targets = node.targets
        elif isinstance(node, (ast.AnnAssign, ast.AugAssign)):
            targets = [node.target]
        else:
            continue
        for target in targets:
            if isinstance(target, ast.Name) and target.id in LEGACY_MANIFEST_NAMES:
                assigned.add(target.id)
    return sorted(assigned)


def suite_references_compiler(source: str) -> bool:
    """True when the suite source wires the registry-derived plan compiler."""
    try:
        tree = ast.parse(source)
    except SyntaxError:
        return False
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and node.module == COMPILER_MODULE_NAME:
            for alias in node.names:
                if alias.name == COMPILER_SUITE_ENTRY:
                    return True
        if isinstance(node, ast.Name) and node.id == COMPILER_SUITE_ENTRY:
            return True
    return False


def _validate_compiled_registry_plan(project_root: str) -> List[str]:
    """Compile the canonical suite plan from the registry and validate it."""
    errors: List[str] = []
    specs, load_diags = load_guard_specs(DEFAULT_REGISTRY_PATH)
    for diag in load_diags:
        if diag.severity == "error":
            errors.append(f"{diag.code} guard={diag.guard_id or '-'}: {diag.context}")
    structural_diags = validate_guard_specs(specs, repo_root=project_root)
    for diag in structural_diags:
        if diag.severity == "error":
            errors.append(f"{diag.code} guard={diag.guard_id or '-'}: {diag.context}")

    context = ExecutionContext(
        repo_root=os.path.abspath(project_root),
        interpreter_path=sys.executable,
        ci_mode=False,
    )
    plans, compile_diags = compile_static_suite_plan(context, specs=specs)
    for diag in compile_diags:
        if diag.severity == "error":
            errors.append(f"{diag.code} guard={diag.guard_id or '-'}: {diag.context}")

    errors.extend(validate_compiled_suite_plan(plans, list(GUARD_REGISTRY.keys())))
    return errors


def _validate_suite_source() -> List[str]:
    """Validate that production suite code no longer owns a command list."""
    errors: List[str] = []
    suite_path = os.path.join(SCRIPT_DIR, SUITE_RUNNER_FILENAME)
    if not os.path.isfile(suite_path):
        return [f"{SUITE_RUNNER_FILENAME}: suite runner source not found"]
    with open(suite_path, "r", encoding="utf-8") as handle:
        source = handle.read()
    for name in forbidden_manifest_assignments(source):
        errors.append(
            f"{SUITE_RUNNER_FILENAME}: legacy hard-coded '{name}' assignment "
            f"is forbidden (commands are registry-derived)"
        )
    if not suite_references_compiler(source):
        errors.append(
            f"{SUITE_RUNNER_FILENAME}: does not wire the registry-derived "
            f"plan compiler ({COMPILER_MODULE_NAME}.{COMPILER_SUITE_ENTRY})"
        )
    return errors


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Validate the guard registry against the filesystem and its compiled suite plan."
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

    # ── 1. File existence validation ────────────────────────────────────────
    print("--- File existence check ---")
    file_errors = validate_registry(project_root)
    if file_errors:
        for e in file_errors:
            print(f"REGISTRY ERROR: {e}")
        has_errors = True
    else:
        print("All registered files exist.")

    # ── 2. Self-consistency checks ──────────────────────────────────────────
    print()
    print("--- Self-consistency check ---")
    consistency_errors = _validate_registry_self_consistency()
    if consistency_errors:
        for e in consistency_errors:
            print(f"REGISTRY ERROR: {e}")
        has_errors = True
    else:
        print("Registry is internally consistent.")

    # ── 3. Registry → compiled canonical suite plan ─────────────────────────
    print()
    print("--- Registry to compiled suite plan check ---")
    plan_errors = _validate_compiled_registry_plan(project_root)
    if plan_errors:
        for e in plan_errors:
            print(f"REGISTRY ERROR: {e}")
        has_errors = True
    else:
        print(
            f"Registry compiles into the canonical suite plan "
            f"({len(GUARD_REGISTRY)} guards, registry order, baselines and "
            f"protocols explicit)."
        )

    # ── 4. Suite source: no legacy manifest, compiler wired ─────────────────
    print()
    print("--- Suite command-authority check ---")
    suite_errors = _validate_suite_source()
    if suite_errors:
        for e in suite_errors:
            print(f"REGISTRY ERROR: {e}")
        has_errors = True
    else:
        print(
            f"{SUITE_RUNNER_FILENAME} owns no hard-coded command list and "
            f"wires the registry-derived plan compiler."
        )

    # ── Summary ─────────────────────────────────────────────────────────────
    print()
    if has_errors:
        print("Guard registry: INVALID — errors detected (see above)")
        sys.exit(1)
    else:
        print("Guard registry: VALID and consistent with its compiled suite plan")
        sys.exit(0)


if __name__ == "__main__":
    main()
