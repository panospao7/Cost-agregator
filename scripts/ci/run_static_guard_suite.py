#!/usr/bin/env python3
"""
Static Guard Suite Runner

Runs ALL guard scripts even if earlier ones fail. Produces structured output
(log files per guard, summary.json, summary.md) and a deterministic exit code.

Exit codes:
  0 — all blocking guards passed
  1 — one or more blocking guards had violations
  2 — infrastructure error (guard crashed, missing command, timeout, etc.)

Time budgets (PR-GR-10c): each guard may declare an optional
expected_max_seconds (registry-derived guards resolve theirs from the named
timeout profiles via the plan compiler; custom JSON manifests via a
per-entry "expected_max_seconds" key).  A guard that finishes above its
budget is marked outcome "slow" in the summary — a non-blocking warning;
the exit code is unaffected and a failing guard keeps its
violation/infra_error outcome.

Command authority (PR-GR-10A Slice 2): the default guard legs are DERIVED
from the registry execution schema via
guard_execution_plan.compile_static_suite_plan.  This runner owns no guard
commands — only guard order/filter (SUITE_GUARD_ORDER), its two
infrastructure legs (registry integrity gate first, guard test runner
last), output-dir handling, summary rendering, the suite-level subprocess
timeout, and the ratchet child-budget adapter policy.  Every default-path
run writes execution-plan.json, execution-plan.sha256, and
effective-inputs.json evidence for the compiled plan.  Custom --manifest
files remain a test-only input.

Usage:
  python3 scripts/ci/run_static_guard_suite.py
  python3 scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards
  python3 scripts/ci/run_static_guard_suite.py --manifest /path/to/custom_manifest.json

Environment:
  GUARD_TIMEOUT_SECONDS — optional per-guard timeout override, in seconds
  (positive integer). Default: 1500 (25 minutes). The db_access full-tree
  D4 scan alone can take ~7-10 minutes and guard_tests (pytest over
  scripts/) can take longer, so the default carries headroom for CI
  variance. Unset, blank, non-numeric, or non-positive values fall back
  to the built-in default.

The db_access ratchet (guard_ratchet.py) enforces its own child-process
timeout via its --timeout flag (default 300s). That default is shorter
than the db_access full-tree D4 scan (~7-10 minutes), so the ratchet would
kill a healthy scan and exit 2. The suite therefore passes the ratchet a
derived child budget: max(GUARD_TIMEOUT_SECONDS - 60, 600) — 60s of
headroom keeps the child timeout inside the suite's per-guard budget, and
the 600s floor keeps the budget viable for the known scan cost.
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from contextlib import suppress
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


# ── Interpreter and suite-level timeout policy ──────────────────────────────────
# Suite legs are (name, command_parts, mode) tuples; mode is "blocking" or
# "warning".  A warning guard that exits 1 records a warning but doesn't fail
# the suite; a blocking guard that exits 1 fails it.  Guard COMMANDS are
# registry-owned (see the derived-plan machinery below); the suite owns only
# its infrastructure legs and runtime policy.

# Interpreter used to build infrastructure-leg commands. Prefer the
# interpreter that is running this suite (sys.executable) so commands stay
# portable on hosts where a bare "python3" may not resolve (e.g. the Windows
# Store alias).  _resolve_python() remains the runtime safety net for custom
# JSON manifests that still use "python3"/"python" literals.
SUITE_PYTHON = sys.executable

# ── Timeout budget ──────────────────────────────────────────────────────────────
# Suite-level per-guard timeout in seconds.
# The db_access full-tree D4 scan alone can take ~7-10 minutes and guard_tests
# (pytest over scripts/) can take longer, so the default carries generous
# headroom for CI variance. Override per environment with the
# GUARD_TIMEOUT_SECONDS environment variable (positive integer seconds);
# unset, blank, non-numeric, or non-positive values fall back to the default.
# Defined before the derived-plan machinery below because the db_access
# ratchet leg embeds a child budget derived from it (see
# _ratchet_child_timeout).
DEFAULT_GUARD_TIMEOUT_SECONDS = 1500
GUARD_TIMEOUT_SECONDS_ENV_VAR = "GUARD_TIMEOUT_SECONDS"


def _resolve_guard_timeout() -> int:
    """Resolve the per-guard timeout from the environment.

    Reads GUARD_TIMEOUT_SECONDS (seconds). Falls back to
    DEFAULT_GUARD_TIMEOUT_SECONDS when unset, blank, non-numeric, or
    non-positive.
    """
    raw = os.environ.get(GUARD_TIMEOUT_SECONDS_ENV_VAR)
    if raw is None or not raw.strip():
        return DEFAULT_GUARD_TIMEOUT_SECONDS
    try:
        value = int(raw.strip())
    except ValueError:
        return DEFAULT_GUARD_TIMEOUT_SECONDS
    return value if value > 0 else DEFAULT_GUARD_TIMEOUT_SECONDS


GUARD_TIMEOUT_SECONDS = _resolve_guard_timeout()

# The db_access ratchet (guard_ratchet.py) enforces its own child-process
# timeout via its --timeout flag (default 300s). That default is shorter than
# the db_access full-tree D4 scan (~7-10 minutes), so the ratchet kills a
# healthy scan and exits 2 ("could not execute the guard command (timeout..)").
# The suite therefore derives the ratchet's child budget from its own
# per-guard budget:
#
#   child_budget = max(GUARD_TIMEOUT_SECONDS - 60, 600)
#
# - 60s headroom covers the ratchet's own work (interpreter startup, baseline
#   load, report parsing) so the child timeout always fires before the
#   suite's outer timeout whenever the suite budget is at least
#   floor + headroom (660s).
# - The 600s floor keeps the child budget viable for the known D4 scan cost
#   (~7-10 minutes) even when the suite budget is lowered. When the suite
#   budget is below 660s the floor exceeds the derivation and the suite's
#   outer timeout is the effective bound (deliberate trade-off: prefer a
#   viable child budget over a strictly nested one).
RATCHET_CHILD_TIMEOUT_HEADROOM_SECONDS = 60
RATCHET_CHILD_TIMEOUT_FLOOR_SECONDS = 600


def _ratchet_child_timeout() -> int:
    """Derive the guard_ratchet --timeout (child budget) for db_access.

    See the comment above RATCHET_CHILD_TIMEOUT_HEADROOM_SECONDS for the
    derivation rationale.
    """
    return max(
        GUARD_TIMEOUT_SECONDS - RATCHET_CHILD_TIMEOUT_HEADROOM_SECONDS,
        RATCHET_CHILD_TIMEOUT_FLOOR_SECONDS,
    )


# ── Registry-derived execution plan (PR-GR-10A Slice 2) ────────────────────────
# The default guard legs are compiled from the registry execution schema
# (guard_registry.GUARD_REGISTRY[*]["execution"]) by the pure plan compiler.
# This runner owns NO guard commands: only guard order/filter, its two
# infrastructure legs, output-dir handling, summary rendering, the
# suite-level subprocess timeout, and the ratchet child-budget adapter
# policy below.  There is no fallback to any hand-maintained command list.

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guard_execution_plan import (  # noqa: E402
    ExecutionContext,
    ExecutionPlan,
    canonicalize_plan_for_comparison,
    compile_static_suite_plan,
)

# Suite-owned infrastructure legs (not registry guards — they are suite
# meta-infrastructure and stay suite-owned): the registry integrity gate
# must run FIRST and the guard test runner runs LAST.  Commands are
# byte-identical to the pre-migration manifest.
SUITE_INFRASTRUCTURE_LEGS: List[Tuple[str, List[str], str]] = [
    ("guard_registry", ["python3", "scripts/ci/verify_guard_registry.py"], "blocking"),
    (
        "guard_tests",
        [SUITE_PYTHON, "-m", "pytest", "scripts/test_verify_*.py", "scripts/ci/test_*.py", "-v", "--tb=short"],
        "blocking",
    ),
]

# Visibility-only time budgets for the infrastructure legs (registry-derived
# guards resolve theirs from the named timeout profiles via the compiler —
# plan.timeout_seconds).
SUITE_INFRASTRUCTURE_TIME_BUDGETS: Dict[str, float] = {
    "guard_registry": 300.0,
    "guard_tests": 1800.0,
}

# Suite-owned guard execution order (names only — command construction is
# registry-owned).  Preserves the pre-migration execution order.  Derivation
# fails closed when a compiled guard is missing from this list or the list
# names a guard the registry no longer compiles, so registry/suite drift can
# never silently drop a guard from enforcement.
SUITE_GUARD_ORDER: Tuple[str, ...] = (
    "source_provenance", "ui_dao", "worker", "receipt_link",
    "import_lifecycle", "cloud_payload", "pii_logging", "di_release",
    "allowlist_compliance", "ignored_test_budget", "lint_baseline_policy",
    "time_boundaries", "deprecation_escalations", "db_artifact_sync",
    "known_good_state", "cancellation", "privacy", "db_access",
    "event_writers", "money", "migration_matrix",
)

# Ratchet child-budget adapter policy: only the db_access ratchet leg
# carries a derived --timeout=<seconds> child budget (the pre-migration
# manifest did exactly this; the ratchet's 300s default kills the ~7-10 min
# full-tree D4 scan).  The token is a RATCHET-level flag, never a
# --command-arg: the child guard script has no --timeout flag.  The
# derivation stays keyed to the suite's per-guard budget (see
# _ratchet_child_timeout) so the default child budget remains 1440s.
RATCHET_CHILD_BUDGET_GUARDS = frozenset({"db_access"})

# Registry plan mode → suite leg mode.  Ratchet-wrapped guards execute as
# blocking legs (a ratchet violation must fail the suite); "policy" has no
# suite mapping and fails derivation closed.
_SUITE_LEG_MODE = {
    "blocking": "blocking",
    "ratchet": "blocking",
    "warning": "warning",
}

# Controlled derivation-failure codes (bounded; never free-form paths).
E_SUITE_ORDER_GAP = "E_SUITE_ORDER_GAP"
E_SUITE_LEG_MODE = "E_SUITE_LEG_MODE"


def _execution_argv_for_plan(plan: ExecutionPlan) -> List[str]:
    """Outer argv for a compiled plan with suite adapter policy applied."""
    argv = list(plan.outer_argv)
    if (
        plan.guard_id in RATCHET_CHILD_BUDGET_GUARDS
        and plan.child_argv is not None
        and len(argv) >= 4
        and argv[2] == "--guard-name"
    ):
        argv.insert(4, f"--timeout={_ratchet_child_timeout()}")
    return argv


def _derive_default_suite_plan(
    project_root: Path,
) -> Tuple[List[Tuple[str, List[str], str]], Dict[str, float], List[ExecutionPlan], List[str]]:
    """Compile the registry-derived suite plan into suite execution legs.

    Returns ``(legs, budgets, plans, errors)``:

      legs    [(name, argv, mode)] — registry integrity gate first, the
              compiled guards in SUITE_GUARD_ORDER, guard test runner last;
      budgets {name: visibility budget seconds} — named timeout profiles
              resolved by the compiler (plan.timeout_seconds) plus the
              infrastructure-leg budgets;
      plans   the compiled ExecutionPlan values (the evidence authority);
      errors  bounded failure strings; non-empty means fail closed (exit 2)
              with NO fallback to any hand-maintained command list.
    """
    context = ExecutionContext(
        repo_root=str(project_root),
        interpreter_path=SUITE_PYTHON,
        ci_mode=False,
    )
    plans, diags = compile_static_suite_plan(context)
    errors = [
        f"{diag.code} guard={diag.guard_id or '-'}: {diag.context}"
        for diag in diags
        if diag.severity == "error"
    ]
    if errors:
        return [], {}, [], errors

    by_id = {plan.guard_id: plan for plan in plans}
    for guard_id in SUITE_GUARD_ORDER:
        if guard_id not in by_id:
            errors.append(
                f"{E_SUITE_ORDER_GAP} guard={guard_id}: registry guard is "
                f"missing from SUITE_GUARD_ORDER coverage"
            )
    for plan in plans:
        if plan.guard_id not in SUITE_GUARD_ORDER:
            errors.append(
                f"{E_SUITE_ORDER_GAP} guard={plan.guard_id}: compiled guard "
                f"is not placed in SUITE_GUARD_ORDER"
            )
    if errors:
        return [], {}, [], errors

    legs: List[Tuple[str, List[str], str]] = [SUITE_INFRASTRUCTURE_LEGS[0]]
    budgets: Dict[str, float] = dict(SUITE_INFRASTRUCTURE_TIME_BUDGETS)
    for guard_id in SUITE_GUARD_ORDER:
        plan = by_id[guard_id]
        leg_mode = _SUITE_LEG_MODE.get(plan.mode)
        if leg_mode is None:
            errors.append(
                f"{E_SUITE_LEG_MODE} guard={guard_id}: no suite leg mapping "
                f"for plan mode {plan.mode!r}"
            )
            continue
        legs.append((guard_id, _execution_argv_for_plan(plan), leg_mode))
        budgets[guard_id] = float(plan.timeout_seconds)
    if errors:
        return [], {}, [], errors
    legs.append(SUITE_INFRASTRUCTURE_LEGS[1])
    return legs, budgets, list(plans), []


# ── Pre-migration compatibility view (PEP 562) ──────────────────────────────────
# The legacy module-level command lists no longer exist.  Pre-migration
# consumers (guard wiring tests) can still read them as DERIVED views
# rendered in the legacy spelling: bare "python3" interpreter token and
# repo-relative path tokens (the infrastructure legs pass through verbatim).
# These views are never executed — main() executes the compiled plan argv —
# and derivation failures raise AttributeError (fail closed).

_COMPAT_VIEW_CACHE: Dict[str, Any] = {}


def _legacy_view_token(token: str, repo_root: Path) -> str:
    if token == SUITE_PYTHON:
        return "python3"
    if os.path.isabs(token):
        try:
            return (
                Path(token).resolve().relative_to(repo_root.resolve()).as_posix()
            )
        except ValueError:
            return token
    return token


def _legacy_view_argv(argv: List[str], repo_root: Path) -> List[str]:
    rendered: List[str] = []
    for token in argv:
        if token.startswith("--command-arg="):
            value = _legacy_view_token(token.split("=", 1)[1], repo_root)
            rendered.append(f"--command-arg={value}")
        else:
            rendered.append(_legacy_view_token(token, repo_root))
    return rendered


def _legacy_compat_view(name: str) -> Any:
    if "legs" not in _COMPAT_VIEW_CACHE:
        project_root = _find_project_root()
        legs, budgets, _plans, errors = _derive_default_suite_plan(project_root)
        if errors:
            raise AttributeError(
                "registry-derived suite plan failed to compile; legacy "
                "compatibility view unavailable: " + "; ".join(errors)
            )
        infra_names = {leg_name for leg_name, _argv, _mode in SUITE_INFRASTRUCTURE_LEGS}
        manifest = [
            (
                leg_name,
                argv if leg_name in infra_names else _legacy_view_argv(argv, project_root),
                mode,
            )
            for leg_name, argv, mode in legs
        ]
        _COMPAT_VIEW_CACHE["legs"] = manifest
        _COMPAT_VIEW_CACHE["budgets"] = dict(budgets)
    return _COMPAT_VIEW_CACHE["legs" if name == "GUARD_MANIFEST" else "budgets"]


def __getattr__(name: str) -> Any:
    """PEP 562 derived compatibility views (see the block comment above)."""
    if name in ("GUARD_MANIFEST", "GUARD_TIME_BUDGETS"):
        return _legacy_compat_view(name)
    raise AttributeError(f"module {__name__!r} has no attribute {name!r}")


# ── Plan evidence output (PR-GR-10A deliverable 4) ──────────────────────────────

EVIDENCE_PLAN_JSON = "execution-plan.json"
EVIDENCE_PLAN_SHA256 = "execution-plan.sha256"
EVIDENCE_INPUTS_JSON = "effective-inputs.json"
EVIDENCE_SCHEMA_VERSION = 1


def _sha256_file(path: str) -> Optional[str]:
    """Streaming sha256 hex digest; None when the file cannot be read."""
    digest = hashlib.sha256()
    try:
        with open(path, "rb") as handle:
            for chunk in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(chunk)
    except OSError:
        return None
    return digest.hexdigest()


def _atomic_write_bytes(path: Path, data: bytes) -> None:
    fd, tmp_name = tempfile.mkstemp(
        dir=str(path.parent), prefix=path.name + ".", suffix=".tmp"
    )
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(data)
        os.replace(tmp_name, str(path))
    except BaseException:
        with suppress(OSError):
            os.remove(tmp_name)
        raise


def _plan_evidence_payload(
    plans: List[ExecutionPlan],
) -> Tuple[Dict[str, Any], Dict[str, Any]]:
    """Build the execution-plan.json and effective-inputs.json payloads.

    Privacy: argv and paths are recorded in repo-relative canonical spelling
    with the interpreter as a placeholder token — machine-specific absolute
    spellings (which embed the user home) are deliberately excluded.  The
    interpreter is recorded as a bounded name+version identity.  Only
    content hashes and controlled fields are persisted; no secrets, no
    environment dumps, no raw source.
    """
    interpreter_identity = (
        f"{os.path.basename(SUITE_PYTHON)} "
        f"{sys.version_info.major}.{sys.version_info.minor}."
        f"{sys.version_info.micro}"
    )
    plan_guards: List[Dict[str, Any]] = []
    input_guards: List[Dict[str, Any]] = []
    for plan in plans:
        canonical = canonicalize_plan_for_comparison(plan)
        required_inputs = list(canonical["requiredInputs"])
        input_hashes = {
            relative: _sha256_file(absolute)
            for relative, absolute in zip(required_inputs, plan.resolved_required_inputs)
        }
        plan_guards.append({
            "guardId": plan.guard_id,
            "mode": plan.mode,
            "engine": plan.engine,
            "resolvedOuterArgv": list(canonical["outerArgv"]),
            "resolvedChildArgv": (
                list(canonical["childArgv"])
                if canonical["childArgv"] is not None
                else None
            ),
            "interpreter": interpreter_identity,
            "timeoutSeconds": plan.timeout_seconds,
            "requiredInputs": required_inputs,
            "inputHashes": dict(input_hashes),
            "baselinePath": canonical["baseline"],
            "baselineSha256": (
                _sha256_file(plan.baseline) if plan.baseline else None
            ),
            "findingProtocol": plan.protocol,
        })
        input_guards.append({
            "guardId": plan.guard_id,
            "requiredInputs": required_inputs,
            "inputHashes": dict(input_hashes),
        })
    plan_payload = {"schemaVersion": EVIDENCE_SCHEMA_VERSION, "guards": plan_guards}
    inputs_payload = {"schemaVersion": EVIDENCE_SCHEMA_VERSION, "guards": input_guards}
    return plan_payload, inputs_payload


def _write_plan_evidence(plans: List[ExecutionPlan], output_dir: Path) -> None:
    """Write execution-plan.json + execution-plan.sha256 + effective-inputs.json.

    The JSON bytes are deterministic (sorted keys, no timestamp) so two
    captures of the same plan are byte-identical and diffable.
    """
    plan_payload, inputs_payload = _plan_evidence_payload(plans)
    plan_bytes = (
        json.dumps(plan_payload, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    ).encode("utf-8")
    inputs_bytes = (
        json.dumps(inputs_payload, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    ).encode("utf-8")
    _atomic_write_bytes(output_dir / EVIDENCE_PLAN_JSON, plan_bytes)
    _atomic_write_bytes(output_dir / EVIDENCE_INPUTS_JSON, inputs_bytes)
    digest = hashlib.sha256(plan_bytes).hexdigest()
    _atomic_write_bytes(
        output_dir / EVIDENCE_PLAN_SHA256,
        f"{digest}  {EVIDENCE_PLAN_JSON}\n".encode("utf-8"),
    )


# Maximum length of stdout preview stored in summary JSON
STDOUT_PREVIEW_MAX_CHARS = 500


# ── Helpers ─────────────────────────────────────────────────────────────────────

def _find_project_root() -> Path:
    """Return the project root directory (two levels up from this script)."""
    return Path(__file__).resolve().parent.parent.parent


def _expand_globs(command: List[str], cwd: Path) -> List[str]:
    """Expand shell-style glob patterns in command arguments.

    Uses pathlib glob so it works cross-platform without shell=True.
    Matches are returned as absolute paths when the original pattern was
    absolute, or as relative-to-cwd paths when the original was relative.
    """
    expanded: List[str] = []
    cwd_resolved = cwd.resolve()
    for arg in command:
        if any(ch in arg for ch in "*?["):
            path = Path(arg)
            was_absolute = path.is_absolute()
            if not was_absolute:
                path = cwd / path
            parent = path.parent
            pattern = path.name
            if parent.exists():
                matches = sorted(parent.glob(pattern))
                if matches:
                    for m in matches:
                        resolved = m.resolve()
                        if was_absolute:
                            expanded.append(str(resolved))
                        else:
                            try:
                                rel = resolved.relative_to(cwd_resolved)
                                expanded.append(str(rel))
                            except ValueError:
                                # Not under project root (e.g. temp dir in tests)
                                expanded.append(str(resolved))
                    continue
            # Fall through: keep the literal arg
            expanded.append(arg)
        else:
            expanded.append(arg)
    return expanded


def _load_manifest_from_json(path: Path) -> Tuple[List[Tuple[str, List[str], str]], Dict[str, float]]:
    """Load a custom guard manifest from a JSON file.

    Expected format:
    [
      {"name": "...", "command": [...], "mode": "blocking|warning"},
      ...
    ]

    Each entry may optionally declare ``expected_max_seconds`` (a number
    >= 0) — its PR-GR-10c time budget; entries without one get no budget
    and can never be marked slow.
    """
    with open(path, 'r', encoding='utf-8') as f:
        data = json.load(f)
    manifest: List[Tuple[str, List[str], str]] = []
    budgets: Dict[str, float] = {}
    for entry in data:
        name = entry["name"]
        command = entry["command"]
        mode = entry["mode"]
        if mode not in ("blocking", "warning"):
            raise ValueError(f"Invalid mode '{mode}' for guard '{name}': must be 'blocking' or 'warning'")
        raw_budget = entry.get("expected_max_seconds")
        if raw_budget is not None:
            if isinstance(raw_budget, bool) or not isinstance(raw_budget, (int, float)) or raw_budget < 0:
                raise ValueError(
                    f"Invalid expected_max_seconds {raw_budget!r} for guard '{name}': "
                    "must be a number >= 0"
                )
            budgets[name] = float(raw_budget)
        manifest.append((name, command, mode))
    return manifest, budgets


def _resolve_python(command: List[str]) -> List[str]:
    """Resolve the Python interpreter in a command for cross-platform compatibility.

    On CI (Ubuntu), ``python3`` is available. On Windows, ``python3`` may
    map to a non-functional Microsoft Store alias. We use ``sys.executable``
    as the safe fallback, which works on all platforms.
    """
    if not command:
        return command

    exe = command[0]
    # Only resolve known Python entry-point names
    if exe not in ("python3", "python"):
        return command

    # On Windows, the Microsoft Store app execution alias for python3.exe
    # may appear on PATH but fail at runtime. Always prefer sys.executable.
    if sys.platform == "win32":
        return [sys.executable] + command[1:]

    # On Linux/macOS: use python3 if available, fall back to python, then sys.executable
    if shutil.which(exe) is not None:
        return command

    alt = "python" if exe == "python3" else "python3"
    if shutil.which(alt) is not None:
        return [alt] + command[1:]

    return [sys.executable] + command[1:]


# ── Core runner ─────────────────────────────────────────────────────────────────

def run_guard(
    name: str,
    command: List[str],
    mode: str,
    output_dir: Path,
    project_root: Path,
    expected_max_seconds: Optional[float] = None,
) -> Dict[str, Any]:
    """Execute a single guard and return a structured result dict.

    ``expected_max_seconds`` (PR-GR-10c) is the guard's optional time
    budget.  Exceeding it marks a PASSING guard's outcome as ``slow`` — a
    non-blocking warning in the summary; the exit code is unaffected and a
    violation/infra_error outcome is never masked by the budget.  The
    comparison uses the raw (unrounded) duration.
    """
    log_path = output_dir / f"{name}.log"

    start = time.monotonic()
    duration = 0.0
    exit_code = -1
    outcome = "infra_error"
    stdout_preview = ""

    try:
        # Resolve Python interpreter for cross-platform compatibility
        resolved_command = _resolve_python(command)

        # Expand glob patterns (e.g. for pytest test file patterns)
        expanded_command = _expand_globs(resolved_command, project_root)

        result = subprocess.run(
            expanded_command,
            capture_output=True,
            text=True,
            encoding='utf-8',
            errors='replace',
            timeout=GUARD_TIMEOUT_SECONDS,
            cwd=str(project_root),
        )
        duration = time.monotonic() - start
        exit_code = result.returncode

        # Write log file
        log_path.write_text(
            (result.stdout or '') +
            ('\n\n--- STDERR ---\n' + result.stderr if result.stderr else ''),
            encoding='utf-8',
            errors='replace',
        )

        budget_exceeded = (
            expected_max_seconds is not None
            and duration > expected_max_seconds
        )
        if exit_code == 0:
            # PR-GR-10c: a passing guard over its time budget is marked
            # "slow" (non-blocking visibility).  Failure outcomes keep
            # their authoritative violation/infra_error semantics — a
            # budget must never mask a real failure or change the exit.
            outcome = "slow" if budget_exceeded else "pass"
        elif exit_code == 1:
            outcome = "violation"
        else:
            outcome = "infra_error"

        stdout_preview = (result.stdout or '')[:STDOUT_PREVIEW_MAX_CHARS]

    except FileNotFoundError:
        duration = time.monotonic() - start
        exit_code = -1
        outcome = "infra_error"
        stdout_preview = f"Command not found: {command[0]}"
        log_path.write_text(
            f"COMMAND NOT FOUND: {' '.join(command)}\n",
            encoding='utf-8',
        )

    except subprocess.TimeoutExpired:
        duration = time.monotonic() - start
        exit_code = -1
        outcome = "infra_error"
        stdout_preview = f"Timeout after {GUARD_TIMEOUT_SECONDS}s"
        log_path.write_text(
            f"TIMEOUT: {' '.join(command)}\n",
            encoding='utf-8',
        )

    except Exception as exc:
        duration = time.monotonic() - start
        exit_code = -1
        outcome = "infra_error"
        stdout_preview = f"Infrastructure error: {exc}"
        log_path.write_text(
            f"INFRASTRUCTURE ERROR: {exc}\n",
            encoding='utf-8',
        )

    return {
        "name": name,
        "mode": mode,
        "exit_code": exit_code,
        "outcome": outcome,
        "duration_seconds": round(duration, 2),
        "expected_max_seconds": expected_max_seconds,
        "budget_exceeded": (
            expected_max_seconds is not None
            and duration > expected_max_seconds
        ),
        "log_path": str(log_path),
        "stdout_preview": stdout_preview,
    }


def compute_summary(results: List[Dict[str, Any]]) -> Dict[str, int]:
    """Derive aggregate counts from individual guard results.

    ``slow`` (PR-GR-10c) counts guards that passed but exceeded their
    declared time budget.  Slow guards are a non-blocking warning: they are
    deliberately NOT counted as passed, and ``determine_exit_code`` never
    looks at them, so the suite exit is unaffected.
    """
    total = len(results)
    passed = sum(1 for r in results if r["outcome"] == "pass")
    slow = sum(1 for r in results if r["outcome"] == "slow")
    failed_blocking = sum(
        1 for r in results
        if r["outcome"] == "violation" and r["mode"] == "blocking"
    )
    warning_violations = sum(
        1 for r in results
        if r["outcome"] == "violation" and r["mode"] == "warning"
    )
    infra_errors = sum(1 for r in results if r["outcome"] == "infra_error")
    return {
        "total": total,
        "passed": passed,
        "slow": slow,
        "failed_blocking": failed_blocking,
        "warning_violations": warning_violations,
        "infra_errors": infra_errors,
    }


def determine_exit_code(summary: Dict[str, int]) -> int:
    """Determine the suite exit code from the summary.

    0 = all blocking passed, no infra errors
    1 = one or more blocking violations (and no infra errors)
    2 = one or more infrastructure errors
    """
    if summary["infra_errors"] > 0:
        return 2
    if summary["failed_blocking"] > 0:
        return 1
    return 0


def write_summary_json(results: List[Dict[str, Any]], summary: Dict[str, int], output_dir: Path) -> None:
    """Write the machine-readable summary.json."""
    timestamp = datetime.now(timezone.utc).isoformat()
    payload = {
        "timestamp": timestamp,
        "results": results,
        "summary": summary,
    }
    json_path = output_dir / "summary.json"
    json_path.write_text(
        json.dumps(payload, indent=2, ensure_ascii=False),
        encoding='utf-8',
    )


def write_summary_md(results: List[Dict[str, Any]], summary: Dict[str, int], output_dir: Path) -> None:
    """Write the human-readable summary.md."""
    lines: List[str] = []
    timestamp = datetime.now(timezone.utc).isoformat()

    lines.append("# Static Guard Suite Summary")
    lines.append("")
    lines.append(f"**Timestamp:** {timestamp}")
    lines.append("")
    lines.append("## Overall")
    lines.append("")
    lines.append(f"| Metric | Count |")
    lines.append(f"| ------ | ----- |")
    lines.append(f"| Total guards | {summary['total']} |")
    lines.append(f"| Passed | {summary['passed']} |")
    lines.append(f"| Slow (over budget) | {summary['slow']} |")
    lines.append(f"| Failed blocking | {summary['failed_blocking']} |")
    lines.append(f"| Warning violations | {summary['warning_violations']} |")
    lines.append(f"| Infra errors | {summary['infra_errors']} |")
    lines.append("")

    lines.append("## Details")
    lines.append("")
    lines.append("| Guard | Mode | Exit | Outcome | Duration |")
    lines.append("| ----- | ---- | ---- | ------- | -------- |")
    for r in results:
        outcome_icon = {
            "pass": "✅",
            "slow": "⏱",
            "violation": "❌",
            "infra_error": "💥",
        }.get(r["outcome"], "❓")
        lines.append(
            f"| {r['name']} | {r['mode']} | {r['exit_code']} | "
            f"{outcome_icon} {r['outcome']} | {r['duration_seconds']:.1f}s |"
        )
    lines.append("")

    if summary["failed_blocking"] > 0:
        lines.append("## ❌ Blocking Violations")
        lines.append("")
        for r in results:
            if r["outcome"] == "violation" and r["mode"] == "blocking":
                lines.append(f"- **{r['name']}** — see `{r['log_path']}`")
        lines.append("")

    if summary["warning_violations"] > 0:
        lines.append("## ⚠️ Warning Violations (backlog)")
        lines.append("")
        for r in results:
            if r["outcome"] == "violation" and r["mode"] == "warning":
                lines.append(f"- **{r['name']}** — see `{r['log_path']}`")
        lines.append("")

    if summary["slow"] > 0:
        lines.append("## ⏱ Slow Guards (over time budget — non-blocking)")
        lines.append("")
        for r in results:
            if r["outcome"] == "slow":
                budget = r.get("expected_max_seconds")
                budget_text = (
                    f"{budget:g}s" if budget is not None else "no budget"
                )
                lines.append(
                    f"- **{r['name']}** — {r['duration_seconds']:.1f}s "
                    f"exceeds its {budget_text} budget"
                )
        lines.append("")

    if summary["infra_errors"] > 0:
        lines.append("## 💥 Infrastructure Errors")
        lines.append("")
        for r in results:
            if r["outcome"] == "infra_error":
                lines.append(f"- **{r['name']}** — `{r['stdout_preview'][:200]}`")
        lines.append("")

    md_path = output_dir / "summary.md"
    md_path.write_text("\n".join(lines) + "\n", encoding='utf-8')


# ── Main ────────────────────────────────────────────────────────────────────────

def main() -> None:
    parser = argparse.ArgumentParser(
        description='Static Guard Suite Runner — runs all guard scripts and produces a summary.'
    )
    parser.add_argument(
        '--output-dir',
        type=Path,
        default=None,
        help='Output directory for logs and summaries (default: build/ci/static-guards/)',
    )
    parser.add_argument(
        '--manifest',
        type=Path,
        default=None,
        help='JSON file with a custom guard manifest (for testing). '
             'Expected format: [{"name": "...", "command": [...], "mode": "..."}, ...]',
    )
    args = parser.parse_args()

    project_root = _find_project_root()
    output_dir = args.output_dir or (project_root / "build" / "ci" / "static-guards")

    # Create output directory
    output_dir.mkdir(parents=True, exist_ok=True)

    # Load the execution legs: a custom JSON manifest (test-only input) or,
    # by default, the registry-derived suite plan compiled from the registry
    # execution schema.  Derivation failures are infrastructure errors
    # (exit 2) — there is no fallback to any hand-maintained command list.
    plans: Optional[List[ExecutionPlan]] = None
    if args.manifest:
        print(f"Loading custom manifest from: {args.manifest}")
        manifest, guard_budgets = _load_manifest_from_json(args.manifest)
    else:
        manifest, guard_budgets, plans, derive_errors = _derive_default_suite_plan(project_root)
        if derive_errors:
            print(
                "REGISTRY-DERIVED SUITE PLAN FAILED (exit 2); refusing to "
                "execute guards from any hand-maintained command list:"
            )
            for derive_error in derive_errors:
                print(f"  {derive_error}")
            sys.exit(2)
        _write_plan_evidence(plans, output_dir)

    print(f"Project root: {project_root}")
    print(f"Output dir:  {output_dir}")
    print(f"Guards:      {len(manifest)}")
    print(f"{'='*70}")

    results: List[Dict[str, Any]] = []
    overall_start = time.monotonic()

    for name, command, mode in manifest:
        print(f"\n[{len(results)+1}/{len(manifest)}] {name} ({mode}) ... ", end='', flush=True)
        result = run_guard(
            name, command, mode, output_dir, project_root,
            expected_max_seconds=guard_budgets.get(name),
        )
        results.append(result)

        outcome_label = {
            "pass": "PASS",
            "slow": "SLOW (over budget)",
            "violation": "VIOLATION" if mode == "blocking" else "WARNING",
            "infra_error": "INFRA_ERROR",
        }.get(result["outcome"], "UNKNOWN")
        print(f"{outcome_label} ({result['duration_seconds']:.1f}s)")

    overall_duration = time.monotonic() - overall_start

    summary = compute_summary(results)
    write_summary_json(results, summary, output_dir)
    write_summary_md(results, summary, output_dir)

    print(f"\n{'='*70}")
    print(f"Suite complete in {overall_duration:.1f}s")
    print(f"  Total:            {summary['total']}")
    print(f"  Passed:           {summary['passed']}")
    print(f"  Slow (over budget): {summary['slow']}")
    print(f"  Failed blocking:  {summary['failed_blocking']}")
    print(f"  Warning viols:    {summary['warning_violations']}")
    print(f"  Infra errors:     {summary['infra_errors']}")
    print(f"  Exit code:        {determine_exit_code(summary)}")
    print(f"\n  Summary JSON: {output_dir / 'summary.json'}")
    print(f"  Summary MD:   {output_dir / 'summary.md'}")
    if plans is not None:
        print(f"  Plan JSON:    {output_dir / EVIDENCE_PLAN_JSON}")
        print(f"  Plan SHA256:  {output_dir / EVIDENCE_PLAN_SHA256}")
        print(f"  Inputs JSON:  {output_dir / EVIDENCE_INPUTS_JSON}")

    sys.exit(determine_exit_code(summary))


if __name__ == '__main__':
    main()
