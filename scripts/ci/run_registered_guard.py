#!/usr/bin/env python3
"""
RUN_REGISTERED_GUARD — PR-GR-10A Slice 2: the one runtime bridge between the
registry-derived execution plan and process execution.

This adapter is NOT a new guard.  It is the single execution bridge used by
direct/Gradle/suite callers to run one registered guard exactly the way the
canonical plan says it must run:

    guard_registry -> guard_execution_plan (compile) -> this adapter

Flow (docs/guardrails/PR-GR-10A_canonical_command_ownership_plan.md,
deliverable 3):

  1. load and validate the registry (structural validation via the compiler);
  2. compile the guard plan (compile_guard_plan);
  3. required-input existence is validated by compilation (fail-closed
     before any child process is launched);
  4. execute the outer argv with ``subprocess.run(shell=False)``;
  5. preserve exact exit codes (0/1/2 pass through unchanged);
  6. write a safe machine-readable summary when --output-summary is given;
  7. never create or update a baseline (the compiled argv carries --ci-mode
     and never --update-baseline/--propose-baseline);
  8. reject unknown guard IDs, unsupported execution contexts, and compile
     diagnostics with exit 2.

Exit codes (universal mapping):
  0 — pass; 1 — violation; 2 — infrastructure/configuration failure.
  A child exit outside {0, 1, 2} (signal, unexpected code) maps to 2; the
  raw child exit is recorded in the summary as ``childExitCode``.

Summary contract (safe machine-readable, bounded):
  {schemaVersion, guardId, context, ciMode, exitCode, childExitCode,
   outcome, durationSeconds, failureCodes, timestamp}
  ``failureCodes`` contains controlled diagnostic constants only.  The
  summary never contains argv, file paths, stdout/stderr content,
  environment dumps, or secrets.

Timeout policy: the adapter imposes NO process timeout.  Callers own
timeouts (the suite's GUARD_TIMEOUT_SECONDS, Gradle timeouts); the plan's
``timeout_seconds`` is named-profile visibility-budget semantics, never a
kill signal.

Test-only overrides (plan rule 7): ``--input-override KEY=PATH`` replaces a
declared required input for local testing.  Overrides are typed (declared
input key only), must be root-contained, and are rejected outright in
--ci-mode by the compiler.  ``--registry`` (a test-only registry module
override) is rejected together with --ci-mode.

Purity contract: library functions never call ``sys.exit`` — they return
exit codes.  Only the CLI adapter (``main``) exits.

Usage:
  python scripts/ci/run_registered_guard.py \
      --guard-id db_access --context direct --root . --ci-mode \
      --output-summary build/guard-debug/gr10a/db-direct-summary.json
"""

import argparse
import json
import os
import subprocess
import sys
import tempfile
import time
from contextlib import suppress
from datetime import datetime, timezone
from typing import Any, Callable, Dict, Optional, Tuple

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guard_execution_plan import (  # noqa: E402
    DEFAULT_REGISTRY_PATH,
    ExecutionContext,
    ExecutionPlan,
    PlanDiagnostic,
    compile_guard_plan,
    load_guard_specs,
    normalize_test_overrides,
    validate_guard_specs,
)

__all__ = [
    "CONTEXT_VOCABULARY",
    "EXIT_PASS",
    "EXIT_VIOLATION",
    "EXIT_INFRA",
    "OUTCOME_PASS",
    "OUTCOME_VIOLATION",
    "OUTCOME_INFRA",
    "SUMMARY_SCHEMA_VERSION",
    "compile_registered_plan",
    "execute_plan",
    "run_registered_guard",
    "write_run_summary",
]


# ── Controlled vocabulary and constants ─────────────────────────────────────────

CONTEXT_VOCABULARY = ("suite", "gradle", "direct")

EXIT_PASS = 0
EXIT_VIOLATION = 1
EXIT_INFRA = 2

OUTCOME_PASS = "pass"
OUTCOME_VIOLATION = "violation"
OUTCOME_INFRA = "infra_error"

SUMMARY_SCHEMA_VERSION = 1

# Adapter-controlled diagnostic codes (controlled constants only — never
# free text; diagnostic context strings are printed, never persisted).
E_ADAPTER_UNSUPPORTED_CONTEXT = "E_ADAPTER_UNSUPPORTED_CONTEXT"
E_ADAPTER_INVALID_ROOT = "E_ADAPTER_INVALID_ROOT"
E_ADAPTER_BAD_OVERRIDE = "E_ADAPTER_BAD_OVERRIDE"
E_ADAPTER_DUPLICATE_OVERRIDE = "E_ADAPTER_DUPLICATE_OVERRIDE"
E_ADAPTER_REGISTRY_IN_CI = "E_ADAPTER_REGISTRY_IN_CI"
E_ADAPTER_SUMMARY_WRITE_FAILED = "E_ADAPTER_SUMMARY_WRITE_FAILED"
E_ADAPTER_LAUNCH_FAILED = "E_ADAPTER_LAUNCH_FAILED"

Runner = Callable[[Any, str], int]


# ── Small helpers ───────────────────────────────────────────────────────────────


def _outcome_for_exit(exit_code: int) -> str:
    """Map an adapter exit code to the bounded outcome vocabulary."""
    if exit_code == EXIT_PASS:
        return OUTCOME_PASS
    if exit_code == EXIT_VIOLATION:
        return OUTCOME_VIOLATION
    return OUTCOME_INFRA


def _print_diagnostics(diags: Tuple[PlanDiagnostic, ...]) -> None:
    """Print bounded structured diagnostics to stderr.

    Only controlled codes, guard ids, and the compiler's bounded context
    strings (repo-relative spellings / fixed text) are printed — never
    absolute paths, exception messages, or stack traces.
    """
    for diag in diags:
        if diag.severity == "error":
            print(
                f"GUARD_PLAN_ERROR: {diag.code} "
                f"guard={diag.guard_id or '-'}: {diag.context}",
                file=sys.stderr,
            )


def _print_adapter_error(code: str) -> None:
    print(f"GUARD_RUNNER_ERROR: {code}", file=sys.stderr)


# ── Compilation ─────────────────────────────────────────────────────────────────


def compile_registered_plan(
    guard_id: str,
    root: str,
    *,
    ci_mode: bool = False,
    input_overrides: Optional[Dict[str, str]] = None,
    registry_path: Optional[str] = None,
    interpreter_path: Optional[str] = None,
) -> Tuple[Optional[ExecutionPlan], Tuple[str, ...], Tuple[PlanDiagnostic, ...]]:
    """Load + validate the registry and compile one guard's plan.

    Returns ``(plan, failure_codes, diagnostics)``.  ``plan`` is None on any
    failure; ``failure_codes`` holds the controlled diagnostic codes only.
    Required-input existence is enforced here (fail-closed) before any
    process is launched.
    """
    specs, load_diags = load_guard_specs(registry_path or DEFAULT_REGISTRY_PATH)
    # Structural registry validation (vocabulary, templates, duplicates).
    # On-disk validation of THIS guard's entrypoint/inputs/baseline happens
    # inside compile_guard_plan; whole-tree file validation is the registry
    # validator's role, not a single guard run's blast radius.
    structural_diags = validate_guard_specs(specs)

    context = ExecutionContext(
        repo_root=os.path.abspath(root),
        interpreter_path=(
            os.path.abspath(interpreter_path) if interpreter_path else sys.executable
        ),
        ci_mode=ci_mode,
        test_only_overrides=normalize_test_overrides(dict(input_overrides or {})),
    )
    plan, compile_diags = compile_guard_plan(guard_id, context, specs=specs)

    diags: Tuple[PlanDiagnostic, ...] = tuple(
        list(load_diags) + list(structural_diags) + list(compile_diags)
    )
    errors = tuple(d for d in diags if d.severity == "error")
    if errors:
        return None, tuple(sorted({d.code for d in errors})), diags
    if plan is None:  # defensive: compiler returned no plan without a diagnostic
        return None, ("E_ADAPTER_COMPILE_FAILED",), diags
    return plan, (), diags


# ── Execution ───────────────────────────────────────────────────────────────────


def execute_plan(
    plan: ExecutionPlan,
    root: str,
    runner: Optional[Runner] = None,
) -> Tuple[int, Optional[int], float]:
    """Execute the plan's outer argv with ``shell=False``.

    Returns ``(exit_code, child_exit_code_or_None, duration_seconds)``.
    Child exits 0/1/2 are preserved exactly; any other child exit maps to
    EXIT_INFRA (universal mapping) with the raw code recorded for the
    summary.  No timeout is imposed (callers own timeouts).
    """
    start = time.monotonic()
    argv = list(plan.outer_argv)
    if runner is not None:
        child_exit = int(runner(argv, root))
        duration = time.monotonic() - start
    else:
        try:
            completed = subprocess.run(
                argv,
                shell=False,
                cwd=str(root),
            )
        except (FileNotFoundError, PermissionError, OSError):
            # Bounded diagnostic only — never the exception message (it may
            # carry filesystem paths).
            _print_adapter_error(E_ADAPTER_LAUNCH_FAILED)
            return EXIT_INFRA, None, time.monotonic() - start
        duration = time.monotonic() - start
        child_exit = completed.returncode
    if child_exit in (EXIT_PASS, EXIT_VIOLATION, EXIT_INFRA):
        return child_exit, child_exit, duration
    return EXIT_INFRA, child_exit, duration


# ── Summary ─────────────────────────────────────────────────────────────────────


def write_run_summary(summary: Dict[str, Any], output_path: str) -> None:
    """Atomically write the machine-readable run summary JSON."""
    text = json.dumps(summary, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    destination = os.path.abspath(output_path)
    parent = os.path.dirname(destination)
    os.makedirs(parent, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(
        dir=parent, prefix=os.path.basename(destination) + ".", suffix=".tmp"
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        os.replace(tmp_name, destination)
    except BaseException:
        with suppress(OSError):
            os.remove(tmp_name)
        raise


# ── Library entry point ─────────────────────────────────────────────────────────


def run_registered_guard(
    guard_id: str,
    context_kind: str,
    root: str,
    *,
    ci_mode: bool = False,
    output_summary: Optional[str] = None,
    input_overrides: Optional[Dict[str, str]] = None,
    registry_path: Optional[str] = None,
    interpreter_path: Optional[str] = None,
    runner: Optional[Runner] = None,
) -> int:
    """Run one registered guard from its compiled plan; return the exit code.

    Library entry point — NEVER calls ``sys.exit``.  Unknown guard ids,
    unsupported contexts, and compile diagnostics yield EXIT_INFRA (2) with
    controlled failure codes; the child's 0/1/2 exits are preserved exactly.
    Never creates or updates a baseline.
    """
    failure_codes = []
    context_recorded = context_kind if context_kind in CONTEXT_VOCABULARY else "unknown"
    if context_kind not in CONTEXT_VOCABULARY:
        failure_codes.append(E_ADAPTER_UNSUPPORTED_CONTEXT)
        _print_adapter_error(E_ADAPTER_UNSUPPORTED_CONTEXT)

    root_abs = os.path.abspath(root) if isinstance(root, str) and root else ""
    if not root_abs or not os.path.isdir(root_abs):
        failure_codes.append(E_ADAPTER_INVALID_ROOT)
        _print_adapter_error(E_ADAPTER_INVALID_ROOT)

    if registry_path and ci_mode:
        # Fail closed: a registry override must never be combinable with
        # production CI enforcement.
        failure_codes.append(E_ADAPTER_REGISTRY_IN_CI)
        _print_adapter_error(E_ADAPTER_REGISTRY_IN_CI)

    overrides: Dict[str, str] = {}
    for key, value in dict(input_overrides or {}).items():
        if key in overrides:
            failure_codes.append(E_ADAPTER_DUPLICATE_OVERRIDE)
            _print_adapter_error(E_ADAPTER_DUPLICATE_OVERRIDE)
        overrides[key] = os.path.abspath(value)

    exit_code = EXIT_INFRA
    child_exit: Optional[int] = None
    duration = 0.0
    diags: Tuple[PlanDiagnostic, ...] = ()

    if not failure_codes:
        plan, codes, diags = compile_registered_plan(
            guard_id,
            root_abs,
            ci_mode=ci_mode,
            input_overrides=overrides,
            registry_path=registry_path,
            interpreter_path=interpreter_path,
        )
        if plan is None:
            failure_codes.extend(codes)
            _print_diagnostics(diags)
        else:
            exit_code, child_exit, duration = execute_plan(plan, root_abs, runner=runner)

    summary = {
        "schemaVersion": SUMMARY_SCHEMA_VERSION,
        "guardId": guard_id,
        "context": context_recorded,
        "ciMode": bool(ci_mode),
        "exitCode": exit_code,
        "childExitCode": child_exit,
        "outcome": _outcome_for_exit(exit_code),
        "durationSeconds": round(duration, 3),
        "failureCodes": sorted(set(failure_codes)),
        "timestamp": datetime.now(timezone.utc).isoformat(),
    }
    if output_summary:
        try:
            write_run_summary(summary, output_summary)
        except OSError:
            # Requested evidence could not be written: infrastructure
            # failure (exit 2 fails loudly; a violation is never silently
            # converted into a pass).
            _print_adapter_error(E_ADAPTER_SUMMARY_WRITE_FAILED)
            return EXIT_INFRA
    return exit_code


# ── CLI adapter ─────────────────────────────────────────────────────────────────


def _parse_input_override(raw: str) -> Tuple[str, str]:
    """Parse a single KEY=PATH override token."""
    key, separator, value = raw.partition("=")
    key = key.strip()
    value = value.strip()
    if not separator or not key or not value:
        raise ValueError("expected KEY=PATH")
    return key, value


def main(argv: Optional[list] = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Run one registered guard from its registry-derived execution "
            "plan (PR-GR-10A canonical command ownership)."
        )
    )
    parser.add_argument("--guard-id", required=True, help="Registered guard id.")
    parser.add_argument(
        "--context",
        required=True,
        choices=CONTEXT_VOCABULARY,
        help="Execution context identity: suite, gradle, or direct.",
    )
    parser.add_argument("--root", required=True, help="Repository root directory.")
    parser.add_argument(
        "--ci-mode",
        action="store_true",
        help="Production CI enforcement (rejects test-only overrides).",
    )
    parser.add_argument(
        "--output-summary",
        default=None,
        help="Optional path for the safe machine-readable run summary JSON.",
    )
    parser.add_argument(
        "--input-override",
        action="append",
        default=None,
        metavar="KEY=PATH",
        help=(
            "Test-only typed input override: replace the declared "
            "repo-relative required input KEY with PATH (absolute, or "
            "relative to the current directory; must stay inside --root). "
            "Repeatable. Rejected in --ci-mode."
        ),
    )
    parser.add_argument(
        "--registry",
        default=None,
        help=(
            "Test-only registry module path override. Rejected together "
            "with --ci-mode."
        ),
    )
    args = parser.parse_args(argv)

    overrides: Dict[str, str] = {}
    for raw in args.input_override or []:
        try:
            key, value = _parse_input_override(raw)
        except ValueError:
            _print_adapter_error(E_ADAPTER_BAD_OVERRIDE)
            return EXIT_INFRA
        if key in overrides:
            _print_adapter_error(E_ADAPTER_DUPLICATE_OVERRIDE)
            return EXIT_INFRA
        overrides[key] = value

    return run_registered_guard(
        args.guard_id,
        args.context,
        args.root,
        ci_mode=args.ci_mode,
        output_summary=args.output_summary,
        input_overrides=overrides or None,
        registry_path=args.registry,
    )


if __name__ == "__main__":
    sys.exit(main())
