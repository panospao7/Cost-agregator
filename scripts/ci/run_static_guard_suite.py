#!/usr/bin/env python3
"""
Static Guard Suite Runner

Runs ALL guard scripts even if earlier ones fail. Produces structured output
(log files per guard, summary.json, summary.md) and a deterministic exit code.

Exit codes:
  0 — all blocking guards passed
  1 — one or more blocking guards had violations
  2 — infrastructure error (guard crashed, missing command, timeout, etc.)

Usage:
  python3 scripts/ci/run_static_guard_suite.py
  python3 scripts/ci/run_static_guard_suite.py --output-dir build/ci/static-guards
  python3 scripts/ci/run_static_guard_suite.py --manifest /path/to/custom_manifest.json
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

GUARD_MANIFEST: List[Tuple[str, List[str], str]] = [
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
    ("migration_matrix", ["python3", "scripts/verify_migration_matrix.py", "--fail-on-violation"], "blocking"),
    ("ignored_test_budget", ["python3", "scripts/verify_ignored_test_budget.py", "--fail-on-violation", "--baseline", "29"], "blocking"),
    ("lint_baseline_policy", ["python3", "scripts/verify_lint_baseline_policy.py", "--fail-on-violation", "--max-missing-translations", "2219"], "blocking"),
    ("release_artifact", ["python3", "scripts/verify_release_artifact.py", "--fail-on-violation"], "blocking"),

    # Ratchet-wrapped guards (was warning backlog; now blocking via ratchet)
    (
        "cancellation",
        [
            "python3", "scripts/ci/guard_ratchet.py",
            "--guard-name", "cancellation",
            "--command", "python3 scripts/verify_cancellation_boundaries.py",
            "--baseline", "config/baselines/cancellation.json",
            "--fail-on-violation",
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
        ],
        "blocking",
    ),
    (
        "db_access",
        [
            "python3", "scripts/ci/guard_ratchet.py",
            "--guard-name", "db_access",
            "--command", "python3 scripts/verify_db_access_boundaries.py --fail-on-violation",
            "--baseline", "config/baselines/db_access.json",
            "--fail-on-violation",
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
        ],
        "blocking",
    ),

    # Pytest — always runs
    ("guard_tests", ["python3", "-m", "pytest", "scripts/test_verify_*.py", "-v", "--tb=short"], "blocking"),
]

# Per-guard timeout in seconds
GUARD_TIMEOUT_SECONDS = 300

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


def _load_manifest_from_json(path: Path) -> List[Tuple[str, List[str], str]]:
    """Load a custom guard manifest from a JSON file.

    Expected format:
    [
      {"name": "...", "command": [...], "mode": "blocking|warning"},
      ...
    ]
    """
    with open(path, 'r', encoding='utf-8') as f:
        data = json.load(f)
    manifest: List[Tuple[str, List[str], str]] = []
    for entry in data:
        name = entry["name"]
        command = entry["command"]
        mode = entry["mode"]
        if mode not in ("blocking", "warning"):
            raise ValueError(f"Invalid mode '{mode}' for guard '{name}': must be 'blocking' or 'warning'")
        manifest.append((name, command, mode))
    return manifest


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
) -> Dict[str, Any]:
    """Execute a single guard and return a structured result dict."""
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

        if exit_code == 0:
            outcome = "pass"
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
        "log_path": str(log_path),
        "stdout_preview": stdout_preview,
    }


def compute_summary(results: List[Dict[str, Any]]) -> Dict[str, int]:
    """Derive aggregate counts from individual guard results."""
    total = len(results)
    passed = sum(1 for r in results if r["outcome"] == "pass")
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
        manifest = _load_manifest_from_json(args.manifest)
    else:
        manifest = GUARD_MANIFEST

    print(f"Project root: {project_root}")
    print(f"Output dir:  {output_dir}")
    print(f"Guards:      {len(manifest)}")
    print(f"{'='*70}")

    results: List[Dict[str, Any]] = []
    overall_start = time.monotonic()

    for name, command, mode in manifest:
        print(f"\n[{len(results)+1}/{len(manifest)}] {name} ({mode}) ... ", end='', flush=True)
        result = run_guard(name, command, mode, output_dir, project_root)
        results.append(result)

        outcome_label = {
            "pass": "PASS",
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
    print(f"  Failed blocking:  {summary['failed_blocking']}")
    print(f"  Warning viols:    {summary['warning_violations']}")
    print(f"  Infra errors:     {summary['infra_errors']}")
    print(f"  Exit code:        {determine_exit_code(summary)}")
    print(f"\n  Summary JSON: {output_dir / 'summary.json'}")
    print(f"  Summary MD:   {output_dir / 'summary.md'}")

    sys.exit(determine_exit_code(summary))


if __name__ == '__main__':
    main()
