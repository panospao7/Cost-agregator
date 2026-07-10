#!/usr/bin/env python3
"""
GUARD_RATCHET -- Enforces no-growth baselines for architecture guards.

Runs a guard script, fingerprints its violations, and compares against
a stored baseline. Reports new, resolved, and unchanged findings.

Exit codes:
  0 -- no new findings (pass, even if old ones remain)
  1 -- new or resolved findings detected (policy violation)
  2 -- infrastructure error (guard crash, missing baseline, etc.)

Usage:
  python scripts/ci/guard_ratchet.py \
    --guard-name cancellation \
    --command "python3 scripts/verify_cancellation_boundaries.py" \
    --baseline config/baselines/cancellation.json \
    --fail-on-violation
"""

import argparse
import json
import re
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional, Set, Tuple

# Ensure stdout/stderr can handle Unicode on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")

# ------------------------------------------------------------------
# Fingerprint extraction
# ------------------------------------------------------------------

# Pattern for a line containing .kt:NNN or .java:NNN
_PATH_LINE_RE = re.compile(r'(.+\.(?:kt|java):\d+)')

# Pattern to extract a bracketed rule/reason code:  [CODE]
_BRACKET_RULE_RE = re.compile(r'^\s*\[([A-Z].+?)\]\s*$')

# Pattern for standard single-line:  RULE_ID path:line ...
# e.g., "G-CANCEL-01 app/src/.../File.kt:123 description"
_STANDARD_RE = re.compile(
    r'^([A-Z][A-Z0-9_-]{2,40})\s+'
    r'(.+\.(?:kt|java):\d+)'
)

# Pattern for money guard:  Lnnn [G-MONEY-NN]
_MONEY_RULE_RE = re.compile(
    r'^\s*L(\d+)\s+\[(G-MONEY-\d+)\]\s'
)

# Pattern to extract just the path from a money header line
_MONEY_PATH_HEADER_RE = re.compile(
    r'^(?:FAIL|PASS)\s+(.+):$'
)


def _find_project_root() -> Path:
    """Return the project root directory (current working directory)."""
    return Path.cwd().resolve()


def _normalize_path(path_line: str, project_root: Path) -> str:
    """Normalize a path:line string to be relative to project root.

    Handles absolute Windows/Linux paths, already-relative paths, and
    paths outside the project root (kept as-is except for backslash
    normalization).
    """
    if ":" in path_line:
        colon_idx = path_line.rfind(":")
        file_path = path_line[:colon_idx]
        line_num = path_line[colon_idx + 1:]
    else:
        file_path = path_line
        line_num = ""

    p = Path(file_path)
    if not p.is_absolute():
        p = project_root / p

    resolved = p.resolve() if p.exists() else p

    try:
        rel = resolved.relative_to(project_root.resolve())
        result = str(rel).replace("\\", "/")
    except (ValueError, OSError):
        # Not under project root -- keep as-is but normalise separators
        result = file_path.replace("\\", "/")

    if line_num:
        return f"{result}:{line_num}"
    return result


def _make_fingerprint(rule_id: str, path_line: str, project_root: Path) -> str:
    """Create a normalized fingerprint from a rule_id and path:line.

    Line numbers are stripped for stable fingerprints — a blank line added
    above a violation must not create a false positive.
    """
    normalized = _normalize_path(path_line.strip(), project_root)
    # Strip line number for stable fingerprints
    if ":" in normalized:
        file_path = normalized.rsplit(":", 1)[0]
    else:
        file_path = normalized
    return f"{rule_id} {file_path}"


def _try_extract_from_line(
    line: str, project_root: Path
) -> Optional[Tuple[str, str]]:
    """Try to extract (rule_id, normalized_path:line) from a single line.

    Handles these same-line formats:
      * Standard:   ``G-CANCEL-01 path:line description``
      * Bracketed:  ``[G5] path:line``  or  ``[CODE] path:line``
      * Dash-sep:   ``[TYPE] Name -- path:line``  (event_writers)
    """
    stripped = line.strip()
    if not stripped:
        return None

    # -- Format A: Bracketed rule-id prefix  [CODE] ... ---------------------
    if stripped.startswith("["):
        # Find the closing bracket
        rb = stripped.find("]", 1)
        if rb < 1:
            return None
        rule_id = stripped[1:rb].strip()
        if not rule_id or not rule_id[0].isupper():
            return None

        rest = stripped[rb + 1:].lstrip()

        # Try to find a dash-separator (em-dash, en-dash, or hyphen)
        # that indicates an entity name before the path
        dash_sep = re.search(r'\s[—–-]\s', rest)
        if dash_sep:
            rest = rest[dash_sep.end():]

        pm = _PATH_LINE_RE.search(rest)
        if pm:
            path_line = pm.group(1)
            return (rule_id, _normalize_path(path_line, project_root))
        return None

    # -- Format B: Standard bare rule_id + path ------------------------------
    sm = _STANDARD_RE.match(stripped)
    if sm:
        rule_id = sm.group(1)
        path_line = sm.group(2)
        return (rule_id, _normalize_path(path_line, project_root))

    return None


def extract_fingerprints(stdout: str, project_root: Optional[Path] = None) -> List[str]:
    """Extract sorted, unique fingerprints from guard script stdout.

    Handles multiple guard output formats:

    * **Same-line** (cancellation, privacy, event_writers):
      ``RULE_ID path:line``, ``[CODE] path:line``, or
      ``[TYPE] Name -- path:line``.

    * **Two-line** (db_access):
      Line N: ``[REASON_CODE]``, Line N+1: ``path:line``.

    * **Money** (two-line):
      ``FAIL path:`` then ``Lnnn [G-MONEY-NN] description``.

    Returns a sorted list of unique fingerprints.
    """
    if project_root is None:
        project_root = _find_project_root()

    fingerprints: Set[str] = set()
    lines = stdout.splitlines()
    n = len(lines)

    # Track the last-seen file path for money guard
    last_money_path: Optional[str] = None

    for i, line in enumerate(lines):
        stripped = line.strip()
        if not stripped:
            continue

        # -- Money guard: detect file-path header  "FAIL path:" ---------------
        mp = _MONEY_PATH_HEADER_RE.match(stripped)
        if mp:
            last_money_path = mp.group(1)
            continue

        # -- Money guard: rule line  "Lnnn [G-MONEY-NN] ..." -----------------
        if last_money_path is not None:
            mm = _MONEY_RULE_RE.match(stripped)
            if mm:
                line_num = mm.group(1)
                rule_id = mm.group(2)
                path_line = f"{last_money_path}:{line_num}"
                fingerprints.add(
                    _make_fingerprint(rule_id, path_line, project_root)
                )
                continue

        # -- Same-line format (standard, bracketed, dash-separated) -----------
        same_line = _try_extract_from_line(line, project_root)
        if same_line is not None:
            rule_id, norm_path = same_line
            # Strip line number for stable fingerprints
            if ":" in norm_path:
                file_path = norm_path.rsplit(":", 1)[0]
            else:
                file_path = norm_path
            fingerprints.add(f"{rule_id} {file_path}")
            continue

        # -- Two-line format (db_access): standalone path:line, rule above ---
        pm = _PATH_LINE_RE.match(stripped)
        if pm and i > 0:
            path_line = pm.group(1)
            prev_stripped = lines[i - 1].strip()
            rm = _BRACKET_RULE_RE.match(prev_stripped)
            if rm:
                rule_id = rm.group(1).strip()
                if rule_id:
                    fingerprints.add(
                        _make_fingerprint(rule_id, path_line, project_root)
                    )
                    continue

    return sorted(fingerprints)


# ------------------------------------------------------------------
# Baseline I/O
# ------------------------------------------------------------------

def load_baseline(path: Path, guard_name: Optional[str] = None) -> Optional[Dict]:
    """Load a baseline JSON file.  Returns None if the file is missing.

    When *guard_name* is provided, validates structure and exits 2 on
    malformed or mismatched data (fingerprints not a list, duplicates,
    wrong guard name, unparseable JSON).
    """
    if not path.exists():
        return None
    try:
        with open(path, "r", encoding="utf-8") as f:
            data = json.load(f)
    except json.JSONDecodeError as e:
        print(f"ERROR: Malformed baseline JSON in {path}: {e}", file=sys.stderr)
        sys.exit(2)

    if guard_name is not None:
        # Validate guard name matches
        if data.get("guard") != guard_name:
            print(
                f"ERROR: Baseline guard name mismatch in {path}: "
                f"expected '{guard_name}', got '{data.get('guard')}'",
                file=sys.stderr,
            )
            sys.exit(2)

    fingerprints = data.get("fingerprints")
    if not isinstance(fingerprints, list):
        print(
            f"ERROR: Baseline 'fingerprints' is not a list in {path}",
            file=sys.stderr,
        )
        sys.exit(2)

    # Check for duplicate fingerprints
    if len(fingerprints) != len(set(fingerprints)):
        print(
            f"ERROR: Baseline contains duplicate fingerprints in {path}",
            file=sys.stderr,
        )
        sys.exit(2)

    return data


def save_baseline(path: Path, guard_name: str, fingerprints: List[str]) -> None:
    """Write (or overwrite) a baseline JSON file."""
    baseline = {
        "guard": guard_name,
        "generated": datetime.now(timezone.utc).isoformat(),
        "fingerprints": fingerprints,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        json.dump(baseline, f, indent=2, ensure_ascii=False)
        f.write("\n")


# ------------------------------------------------------------------
# Comparison
# ------------------------------------------------------------------

def compare_fingerprints(
    baseline_fps: List[str], current_fps: List[str]
) -> Tuple[List[str], List[str], List[str]]:
    """Compare baseline and current fingerprint lists.

    Returns:
        (new, resolved, unchanged) -- each sorted.
    """
    bset = set(baseline_fps)
    cset = set(current_fps)

    new = sorted(cset - bset)
    resolved = sorted(bset - cset)
    unchanged = sorted(bset & cset)
    return new, resolved, unchanged


# ------------------------------------------------------------------
# Guard execution
# ------------------------------------------------------------------

def run_guard_command(
    command: str, cwd: Path, timeout: int = 300
) -> Tuple[int, str, str]:
    """Execute a shell command and return (exit_code, stdout, stderr).

    Exit code -1 signals an infrastructure error (timeout, not-found, ...).
    """
    try:
        result = subprocess.run(
            command,
            shell=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            cwd=str(cwd),
        )
        return result.returncode, (result.stdout or ""), (result.stderr or "")
    except subprocess.TimeoutExpired:
        return -1, "", f"Timeout after {timeout}s"
    except FileNotFoundError:
        return -1, "", f"Command not found: {command.split()[0]}"
    except Exception as exc:
        return -1, "", f"Infrastructure error: {exc}"


# ------------------------------------------------------------------
# Reporting
# ------------------------------------------------------------------

def print_report(
    guard_name: str,
    baseline_count: int,
    current_count: int,
    new: List[str],
    resolved: List[str],
    unchanged: List[str],
) -> str:
    """Print a human-readable report to stdout.  Returns the status label."""
    print(f"Guard: {guard_name}")
    print(f"Baseline: {baseline_count} findings")
    print(f"Current:  {current_count} findings")
    print(f"  NEW: {len(new)}")
    for fp in new:
        print(f"    {fp}")
    print(f"  RESOLVED: {len(resolved)}")
    for fp in resolved:
        print(f"    {fp}")
    print(f"  UNCHANGED: {len(unchanged)}")

    if new:
        status = "FAIL"
        msg = f"{len(new)} new findings detected"
    elif resolved:
        status = "DECREASED"
        msg = f"{len(resolved)} findings resolved"
    else:
        status = "PASS"
        msg = "no new or resolved findings"

    print(f"Status: {status} -- {msg}")
    return status


def write_summary_json(
    path: Path,
    guard_name: str,
    new: List[str],
    resolved: List[str],
    unchanged: List[str],
    status: str,
    exit_code: int,
) -> None:
    """Write a machine-readable summary JSON."""
    payload = {
        "guard": guard_name,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "status": status,
        "exit_code": exit_code,
        "counts": {
            "baseline": len(resolved) + len(unchanged),
            "current": len(new) + len(unchanged),
            "new": len(new),
            "resolved": len(resolved),
            "unchanged": len(unchanged),
        },
        "new": new,
        "resolved": resolved,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=2, ensure_ascii=False)
        f.write("\n")


# ------------------------------------------------------------------
# Main
# ------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Guard Ratchet -- enforces no-growth baselines for architecture guards."
    )
    parser.add_argument(
        "--guard-name", required=True, help="Human-readable guard name for reporting."
    )
    parser.add_argument(
        "--command", required=True, help="Shell command to run the guard script."
    )
    parser.add_argument(
        "--baseline", required=True, help="Path to baseline JSON file."
    )
    parser.add_argument(
        "--fail-on-violation",
        action="store_true",
        help="Exit with code 1 when new findings are detected.",
    )
    parser.add_argument(
        "--update-baseline",
        action="store_true",
        help="Rewrite the baseline with current findings (only if count "
        "decreased or stayed the same; rejects if count increased).",
    )
    parser.add_argument(
        "--ci-mode",
        action="store_true",
        help="CI mode: disables --update-baseline and enforces stricter policies.",
    )
    parser.add_argument(
        "--timeout", type=int, default=300, help="Command timeout in seconds."
    )
    parser.add_argument(
        "--output-summary",
        type=Path,
        default=None,
        help="Write a machine-readable summary JSON to this path.",
    )
    args = parser.parse_args()

    # -- CI mode: reject baseline updates ----------------------------------------
    if args.ci_mode and args.update_baseline:
        print("ERROR: Baseline updates prohibited in CI mode", file=sys.stderr)
        sys.exit(2)

    project_root = _find_project_root()
    baseline_path = Path(args.baseline)
    if not baseline_path.is_absolute():
        baseline_path = project_root / baseline_path

    # -- 1. Run the guard command ------------------------------------------------
    guard_exit, stdout, stderr = run_guard_command(
        args.command, project_root, args.timeout
    )

    if guard_exit < 0:
        print(f"Guard ratchet error: {stderr}", file=sys.stderr)
        sys.exit(2)

    if guard_exit == 0:
        # Guard passed — findings should be empty or structured
        pass
    elif guard_exit == 1:
        # Guard found violations — parse findings
        if not stdout.strip():
            # Exit 1 with no output = infrastructure error
            print("Guard exited 1 but produced no parseable findings", file=sys.stderr)
            sys.exit(2)
    elif guard_exit == 2:
        # Guard infrastructure error
        print(f"Guard exited with infrastructure error (code 2)", file=sys.stderr)
        sys.exit(2)
    else:
        # Unknown exit code
        print(f"Guard exited with unknown code {guard_exit}", file=sys.stderr)
        sys.exit(2)

    # Print guard stdout for logging (but strip trailing newlines)
    if stdout.strip():
        print(stdout.strip())

    # -- 2. Extract fingerprints -------------------------------------------------
    current_fps = extract_fingerprints(stdout, project_root)

    # If guard exited 1 but no fingerprints could be parsed, that's an
    # infrastructure error (output format changed, guard broken, etc.).
    if guard_exit == 1 and len(current_fps) == 0:
        print(f"Guard '{args.guard_name}' exited 1 but produced no parseable findings", file=sys.stderr)
        print(f"Raw stdout: {stdout[:500]}", file=sys.stderr)
        sys.exit(2)

    # -- 3. Load baseline --------------------------------------------------------
    baseline_data = load_baseline(baseline_path, args.guard_name)
    if baseline_data is None:
        print(f"ERROR: Baseline file not found: {baseline_path}", file=sys.stderr)
        sys.exit(2)

    baseline_fps = baseline_data.get("fingerprints", [])

    # -- 4. Compare --------------------------------------------------------------
    new, resolved, unchanged = compare_fingerprints(baseline_fps, current_fps)

    # -- 5. Report ---------------------------------------------------------------
    status = print_report(
        args.guard_name,
        len(baseline_fps),
        len(current_fps),
        new,
        resolved,
        unchanged,
    )

    # -- 6. Determine final exit code (before summary JSON for consistency)   ----
    if args.fail_on_violation:
        final_exit = 1 if (new or resolved) else 0
    else:
        final_exit = 0

    # -- 7. Summary JSON (optional) ----------------------------------------------
    summary_path = args.output_summary
    if summary_path is not None:
        write_summary_json(
            summary_path,
            args.guard_name,
            new,
            resolved,
            unchanged,
            status,
            final_exit,
        )

    # -- 8. Update baseline (optional) -------------------------------------------
    if args.update_baseline:
        if len(new) > 0:
            print(
                "ERROR: Cannot update baseline -- findings increased. "
                "Fix the new findings or review before updating.",
                file=sys.stderr,
            )
            sys.exit(1 if args.fail_on_violation else 2)
        save_baseline(baseline_path, args.guard_name, current_fps)
        print(f"Baseline updated: {len(current_fps)} findings")
        # After successful update, reset final_exit (maintenance mode)
        final_exit = 0

    # -- 9. Exit -----------------------------------------------------------
    sys.exit(final_exit)


if __name__ == "__main__":
    main()
