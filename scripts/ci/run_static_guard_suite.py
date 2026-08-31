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
expected_max_seconds (built-in guards via GUARD_TIME_BUDGETS; custom JSON
manifests via a per-entry "expected_max_seconds" key).  A guard that
finishes above its budget is marked outcome "slow" in the summary — a
non-blocking warning; the exit code is unaffected and a failing guard keeps
its violation/infra_error outcome.

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
import json
import os
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple


# ── Declarative guard manifest ──────────────────────────────────────────────────
# (name, command_parts, mode)
# mode: "blocking" or "warning"
# A warning guard that exits 1 records a warning but doesn't fail the suite.
# A blocking guard that exits 1 fails the suite.

# Interpreter used to build manifest commands. Prefer the interpreter that is
# running this suite (sys.executable) so guard commands stay portable on hosts
# where a bare "python3" may not resolve (e.g. the Windows Store alias).
# _resolve_python() remains the runtime safety net for manifest entries and
# custom JSON manifests that still use "python3"/"python" literals.
SUITE_PYTHON = sys.executable

# ── Timeout budget ──────────────────────────────────────────────────────────────
# Suite-level per-guard timeout in seconds.
# The db_access full-tree D4 scan alone can take ~7-10 minutes and guard_tests
# (pytest over scripts/) can take longer, so the default carries generous
# headroom for CI variance. Override per environment with the
# GUARD_TIMEOUT_SECONDS environment variable (positive integer seconds);
# unset, blank, non-numeric, or non-positive values fall back to the default.
# Defined before GUARD_MANIFEST because the db_access entry embeds a child
# budget derived from it (see _ratchet_child_timeout below).
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


GUARD_MANIFEST: List[Tuple[str, List[str], str]] = [
    # ── Registry integrity (must run first) ─────────────────────────────────────
    ("guard_registry", ["python3", "scripts/ci/verify_guard_registry.py"], "blocking"),

    # Blocking guards
    ("source_provenance", ["python3", "scripts/verify_source_provenance_boundaries.py", "--root", "."], "blocking"),
    ("ui_dao", ["python3", "scripts/verify_ui_dao_boundaries.py", "--fail-on-violation"], "blocking"),
    ("worker", ["python3", "scripts/verify_worker_boundaries.py", "--fail-on-violation"], "blocking"),
    ("receipt_link", ["python3", "scripts/verify_receipt_link_boundaries.py", "--fail-on-violation"], "blocking"),
    ("import_lifecycle", ["python3", "scripts/verify_import_lifecycle_boundaries.py", "--fail-on-violation"], "blocking"),
    ("cloud_payload", ["python3", "scripts/verify_cloud_payload_boundaries.py", "--fail-on-violation"], "blocking"),
    ("pii_logging", ["python3", "scripts/verify_pii_logging_boundaries.py", "--fail-on-violation"], "blocking"),
    ("di_release", ["python3", "scripts/verify_di_release_boundaries.py", "--fail-on-violation"], "blocking"),
    ("allowlist_compliance", ["python3", "scripts/verify_allowlist_compliance.py", "--fail-on-violation"], "blocking"),
    ("ignored_test_budget", ["python3", "scripts/verify_ignored_test_budget.py", "--fail-on-violation", "--baseline", "29"], "blocking"),
    ("lint_baseline_policy", ["python3", "scripts/verify_lint_baseline_policy.py", "--fail-on-violation"], "blocking"),
    (
        "time_boundaries",
        [
            "python3", "scripts/verify_time_boundaries.py",
            "--root", ".",
            "--allowlist", "config/guards/time_boundary_exceptions.yml",
            "--fail-on-violation",
        ],
        "blocking",
    ),
    ("deprecation_escalations", ["python3", "scripts/ci/verify_deprecation_escalations.py", "--root", "."], "blocking"),
    # PR-GR-10b artifact-sync tripwire: the migrate CLI's --verify mode
    # regenerates the tracked candidate/accounting artifacts IN MEMORY from
    # the SAME reviewed inputs (--seed-rows) and exits 1 when the tracked
    # files drift (hand-edit drift becomes visible in every suite run and
    # CI).  Tokenized argv per the GUARD_MANIFEST pattern; the suite's
    # per-guard timeout (GUARD_TIMEOUT_SECONDS budget) bounds the run.
    (
        "db_artifact_sync",
        [
            "python3", "scripts/migrate_db_policy_signatures.py",
            "--verify",
            "--seed-rows", "docs/ci/db-findings/GR-08-seeds.yml",
        ],
        "blocking",
    ),
    # PR-GR-10e known-good scorecard: executes the six section-7 rows of
    # docs/ci/GR00-GR04_validation_checklist.md (active DB gate accepted with
    # 20 advisories, inventory-only platform durability branch, migration
    # fold truth, source-roots meta-guard, candidate byte-reproducibility,
    # structural manifest pin).  Deliberately expensive — it re-runs the full
    # gate + inventory + migration fold — so its internal per-command
    # timeouts (see the script docstring) must stay inside this suite's
    # per-guard GUARD_TIMEOUT_SECONDS budget; raise the env override when
    # running against a cold tree.
    ("known_good_state", ["python3", "scripts/ci/verify_known_good_state.py"], "blocking"),

    # release_artifact verification runs in the release-check CI job after assembleRelease, not here

    # Ratchet-wrapped guards (was warning backlog; now blocking via ratchet)
    (
        "cancellation",
        [
            "python3", "scripts/ci/guard_ratchet.py",
            "--guard-name", "cancellation",
            "--command", "python3 scripts/verify_cancellation_boundaries.py",
            "--baseline", "config/baselines/cancellation.json",
            "--fail-on-violation",
            "--ci-mode",
        ],
        "blocking",
    ),
    (
        "privacy",
        [
            "python3", "scripts/ci/guard_ratchet.py",
            "--guard-name", "privacy",
            "--command", "python3 scripts/verify_privacy_boundaries.py --root .",
            "--baseline", "config/baselines/privacy.json",
            "--fail-on-violation",
            "--ci-mode",
        ],
        "blocking",
    ),
    (
        "db_access",
        [
            "python3", "scripts/ci/guard_ratchet.py",
            "--guard-name", "db_access",
            # Ratchet-level child budget derived from the suite budget; the
            # ratchet's 300s default kills the ~7-10 min full-tree D4 scan.
            # Must stay a ratchet flag, NOT a --command-arg: the child guard
            # script has no --timeout flag. See _ratchet_child_timeout().
            f"--timeout={_ratchet_child_timeout()}",
            "--command-arg=python",
            "--command-arg=scripts/verify_db_access_boundaries.py",
            "--command-arg=--fail-on-violation",
            "--command-arg=--structural-manifest",
            "--command-arg=config/guards/db_structural_exceptions_expected_methods.yml",
            "--command-arg=--ownership-policy",
            "--command-arg=config/guards/db_ownership_policy.yml",
            "--command-arg=--structural-exceptions",
            "--command-arg=config/guards/db_structural_exceptions.yml",
            "--baseline", "config/baselines/db_access_v2.json",
            "--fail-on-violation",
            "--ci-mode",
        ],
        "blocking",
    ),
    (
        "event_writers",
        [
            "python3", "scripts/ci/guard_ratchet.py",
            "--guard-name", "event_writers",
            "--command", "python3 scripts/verify_event_writers.py --fail-on-violation",
            "--baseline", "config/baselines/event_writers.json",
            "--fail-on-violation",
            "--ci-mode",
        ],
        "blocking",
    ),
    (
        "money",
        [
            "python3", "scripts/ci/guard_ratchet.py",
            "--guard-name", "money",
            "--command", "python3 scripts/verify_money_boundaries.py --root .",
            "--baseline", "config/baselines/money.json",
            "--fail-on-violation",
            "--ci-mode",
        ],
        "blocking",
    ),
    (
        "migration_matrix",
        [
            "python3", "scripts/ci/guard_ratchet.py",
            "--guard-name", "migration_matrix",
            "--command", "python3 scripts/verify_migration_matrix.py --fail-on-violation",
            "--baseline", "config/baselines/migration_matrix.json",
            "--fail-on-violation",
            "--ci-mode",
        ],
        "blocking",
    ),

    # Pytest — always runs
    ("guard_tests", [SUITE_PYTHON, "-m", "pytest", "scripts/test_verify_*.py", "scripts/ci/test_*.py", "-v", "--tb=short"], "blocking"),
]

# ── Per-guard time budgets (PR-GR-10c) ──────────────────────────────────────────
# Optional expected_max_seconds per guard, declared here per built-in guard
# name (custom JSON manifests may declare "expected_max_seconds" per entry
# instead).  A guard that finishes ABOVE its budget is marked outcome "slow"
# in the summary — a NON-BLOCKING warning: the exit code is unaffected and a
# failing guard keeps its violation/infra_error outcome (a budget must never
# mask a real failure).  Budgets are visibility only, never fail-closed.
#
# Initial budgets from observed full-suite durations: db_access ~700s and
# guard_tests ~1500s get ~20% headroom over their observations (guard_tests'
# budget sits above the default 1500s suite timeout, so it can only fire when
# GUARD_TIMEOUT_SECONDS is raised); every other guard is small (well under
# 300s observed) and gets a flat 300s ceiling.
GUARD_TIME_BUDGETS: Dict[str, float] = {
    "guard_registry": 300.0,
    "source_provenance": 300.0,
    "ui_dao": 300.0,
    "worker": 300.0,
    "receipt_link": 300.0,
    "import_lifecycle": 300.0,
    "cloud_payload": 300.0,
    "pii_logging": 300.0,
    "di_release": 300.0,
    "allowlist_compliance": 300.0,
    "ignored_test_budget": 300.0,
    "lint_baseline_policy": 300.0,
    "time_boundaries": 300.0,
    "deprecation_escalations": 300.0,
    # PR-GR-10b: the verify mode regenerates the artifact pair in memory
    # (full legacy-policy migration over the production tree, no coverage
    # scan); observed migration runs sit well under this ceiling.
    "db_artifact_sync": 600.0,
    # PR-GR-10e: the scorecard runs the full gate (~250s warm / ~700s cold)
    # plus inventory, migration fold, meta-guard, and candidate verify
    # (~180s combined warm).  1200s covers the warm path with headroom and
    # most cold paths; the budget is visibility-only (outcome "slow") and
    # never masks a real failure.
    "known_good_state": 1200.0,
    "cancellation": 300.0,
    "privacy": 300.0,
    "db_access": 840.0,
    "event_writers": 300.0,
    "money": 300.0,
    "migration_matrix": 300.0,
    "guard_tests": 1800.0,
}

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

    # Load manifest
    if args.manifest:
        print(f"Loading custom manifest from: {args.manifest}")
        manifest, guard_budgets = _load_manifest_from_json(args.manifest)
    else:
        manifest = GUARD_MANIFEST
        # PR-GR-10c: the built-in manifest declares its time budgets in
        # GUARD_TIME_BUDGETS (keyed by guard name).
        guard_budgets = dict(GUARD_TIME_BUDGETS)

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

    sys.exit(determine_exit_code(summary))


if __name__ == '__main__':
    main()
