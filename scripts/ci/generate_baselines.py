#!/usr/bin/env python3
"""
generate_baselines.py -- Bootstrap baseline generation for the guard ratchet.

Runs each warning guard, extracts fingerprints using guard_ratchet.py's
extraction logic, and writes the baseline JSON files under
config/baselines/.

Guards covered (all five former warning-mode guards):
  - cancellation:  scripts/verify_cancellation_boundaries.py
  - privacy:       scripts/verify_privacy_boundaries.py --root .
  - db_access:     scripts/verify_db_access_boundaries.py --fail-on-violation
  - event_writers: scripts/verify_event_writers.py --fail-on-violation
  - money:         scripts/verify_money_boundaries.py --root .

Usage:
  python scripts/ci/generate_baselines.py
  python scripts/ci/generate_baselines.py --output-dir config/baselines
"""

import argparse
import subprocess
import sys
from pathlib import Path
from typing import List, Tuple

# Import fingerprint extraction from the ratchet module
_SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(_SCRIPT_DIR))

from guard_ratchet import extract_fingerprints, _find_project_root, save_baseline


# -- Guard definitions (name, command_parts, output_baseline_name) ----------------
GUARDS: List[Tuple[str, List[str], str]] = [
    (
        "cancellation",
        ["python3", "scripts/verify_cancellation_boundaries.py"],
        "cancellation.json",
    ),
    (
        "privacy",
        ["python3", "scripts/verify_privacy_boundaries.py", "--root", "."],
        "privacy.json",
    ),
    (
        "db_access",
        ["python3", "scripts/verify_db_access_boundaries.py", "--fail-on-violation"],
        "db_access.json",
    ),
    (
        "event_writers",
        ["python3", "scripts/verify_event_writers.py", "--fail-on-violation"],
        "event_writers.json",
    ),
    (
        "money",
        ["python3", "scripts/verify_money_boundaries.py", "--root", "."],
        "money.json",
    ),
]


def _resolve_python(command: List[str]) -> List[str]:
    """Resolve 'python3'/'python' to sys.executable on Windows."""
    import shutil

    if not command:
        return command
    exe = command[0]
    if exe not in ("python3", "python"):
        return command
    if sys.platform == "win32":
        return [sys.executable] + command[1:]
    if shutil.which(exe) is not None:
        return command
    alt = "python" if exe == "python3" else "python3"
    if shutil.which(alt) is not None:
        return [alt] + command[1:]
    return [sys.executable] + command[1:]


def run_guard(command: List[str], project_root: Path, timeout: int = 300) -> str:
    """Run a guard script and return its stdout.  Exits on infrastructure error."""
    resolved = _resolve_python(command)
    try:
        result = subprocess.run(
            resolved,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            cwd=str(project_root),
        )
        # Guard scripts may exit 0 or 1 (if violations present); both are fine.
        # Only infra errors (exit < 0 or > 1) should abort.
        if result.returncode < 0 or result.returncode > 1:
            print(
                f"Guard {command} exited with code {result.returncode}",
                file=sys.stderr,
            )
            if result.stderr:
                print(result.stderr, file=sys.stderr)
            sys.exit(2)
        return result.stdout or ""
    except subprocess.TimeoutExpired:
        print(f"Timeout running: {command}", file=sys.stderr)
        sys.exit(2)
    except FileNotFoundError:
        print(f"Command not found: {command[0]}", file=sys.stderr)
        sys.exit(2)
    except Exception as exc:
        print(f"Error running guard: {exc}", file=sys.stderr)
        sys.exit(2)


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Generate baseline JSON files for the guard ratchet."
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=None,
        help="Directory for baseline files (default: config/baselines/).",
    )
    args = parser.parse_args()

    project_root = _find_project_root()
    output_dir = args.output_dir or (project_root / "config" / "baselines")
    output_dir.mkdir(parents=True, exist_ok=True)

    for name, command, filename in GUARDS:
        print(f"Generating baseline for: {name} ...", end=" ", flush=True)
        stdout = run_guard(command, project_root)
        fingerprints = extract_fingerprints(stdout, project_root)
        baseline_path = output_dir / filename
        save_baseline(baseline_path, name, fingerprints)
        print(f"{len(fingerprints)} findings -> {baseline_path}")

    print("\nAll baselines generated successfully.")


if __name__ == "__main__":
    main()
