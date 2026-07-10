#!/usr/bin/env python3
"""
test_guard_ratchet.py

Pytest tests for the guard ratchet (scripts/ci/guard_ratchet.py).

Tests verify:
  1. No new findings exits 0 (ratchet passes).
  2. New finding detected exits 1 (growth blocked).
  3. Missing baseline is infra error (exit 2).
  4. Guard crash is infra error (exit 2).
  5. Resolved findings reported correctly.
  6. --update-baseline on decreased count succeeds.
  7. --update-baseline on increased count fails.
  8. Output fingerprints are sorted and valid.
  9. Guard with zero violations works (empty baseline).
 10. CI mode rejects --update-baseline.
 11. Resolved entries cause exit 1 with --fail-on-violation.

Run:
    python -m pytest scripts/ci/test_guard_ratchet.py -v
"""

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import List


# -- Helpers ----------------------------------------------------------------------

RATCHET_SCRIPT = Path(__file__).resolve().parent / "guard_ratchet.py"


def _write_guard_script(path: Path, stdout: str, exit_code: int = 0) -> None:
    """Write a mock guard script that prints `stdout` and exits with `exit_code`."""
    content = f'''#!/usr/bin/env python3
import sys
print({stdout!r})
sys.exit({exit_code})
'''
    path.write_text(content, encoding="utf-8")
    if sys.platform != "win32":
        os.chmod(path, 0o755)


def _write_baseline(path: Path, guard_name: str, fingerprints: List[str]) -> None:
    """Write a baseline JSON file."""
    path.parent.mkdir(parents=True, exist_ok=True)
    baseline = {
        "guard": guard_name,
        "generated": "2026-07-10T00:00:00",
        "fingerprints": fingerprints,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(baseline, f, indent=2)
        f.write("\n")


def _run_ratchet(
    guard_name: str,
    command: str,
    baseline: Path,
    extra_args: List[str] = None,
    cwd: Path = None,
) -> subprocess.CompletedProcess:
    """Run the ratchet and return the CompletedProcess."""
    cmd = [
        sys.executable,
        str(RATCHET_SCRIPT),
        "--guard-name", guard_name,
        "--command", command,
        "--baseline", str(baseline),
    ]
    if extra_args:
        cmd.extend(extra_args)
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
    )


def _fingerprints(stdout_lines: List[str]) -> List[str]:
    """Parse fingerprints from ratchet stdout (lines starting with 4 spaces)."""
    fps = []
    for line in stdout_lines:
        stripped = line.strip()
        if stripped and not stripped.startswith(("Guard:", "Baseline:", "Current:",
                                                  "NEW:", "RESOLVED:", "UNCHANGED:",
                                                  "Status:", "ERROR:")):
            if not stripped.startswith("--") and stripped:
                fps.append(stripped)
    return fps


# -- Tests ------------------------------------------------------------------------


def test_no_new_findings_exits_zero(tmp_path: Path) -> None:
    """Ratchet exits 0 when current findings match baseline exactly."""
    guard_out = (
        "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10 some description\n"
        "G-CANCEL-02 app/src/main/java/com/example/Bar.kt:20 another description\n"
    )
    fingerprints = [
        "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10",
        "G-CANCEL-02 app/src/main/java/com/example/Bar.kt:20",
    ]

    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, guard_out, exit_code=1)

    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "test", fingerprints)

    result = _run_ratchet(
        "test",
        f"{sys.executable} {guard_py}",
        baseline,
        cwd=tmp_path,
    )

    assert result.returncode == 0, (
        f"Expected exit 0, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "PASS" in result.stdout


def test_new_finding_detected_exits_one(tmp_path: Path) -> None:
    """Ratchet exits 1 when a new finding appears (--fail-on-violation)."""
    guard_out = (
        "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10 desc\n"
        "G-CANCEL-03 app/src/main/java/com/example/Baz.kt:30 new violation\n"
    )
    baseline_fps = [
        "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10",
    ]

    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, guard_out, exit_code=1)

    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "test", baseline_fps)

    result = _run_ratchet(
        "test",
        f"{sys.executable} {guard_py}",
        baseline,
        extra_args=["--fail-on-violation"],
        cwd=tmp_path,
    )

    assert result.returncode == 1, (
        f"Expected exit 1, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "FAIL" in result.stdout
    assert "NEW: 1" in result.stdout


def test_missing_baseline_is_error(tmp_path: Path) -> None:
    """Ratchet exits 2 when the baseline file does not exist."""
    guard_out = "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10 desc\n"

    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, guard_out, exit_code=1)

    baseline = tmp_path / "nonexistent.json"

    result = _run_ratchet(
        "test",
        f"{sys.executable} {guard_py}",
        baseline,
        cwd=tmp_path,
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )


def test_guard_crash_is_error(tmp_path: Path) -> None:
    """Ratchet exits 2 when the guard command crashes (exit != 0,1)."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "some output", exit_code=2)

    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "test", [])

    result = _run_ratchet(
        "test",
        f"{sys.executable} {guard_py}",
        baseline,
        cwd=tmp_path,
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )


def test_resolved_findings_reported(tmp_path: Path) -> None:
    """Resolved findings are correctly identified and reported."""
    guard_out = "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10 desc\n"
    baseline_fps = [
        "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10",
        "G-CANCEL-02 app/src/main/java/com/example/Bar.kt:20",
    ]

    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, guard_out, exit_code=1)

    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "test", baseline_fps)

    result = _run_ratchet(
        "test",
        f"{sys.executable} {guard_py}",
        baseline,
        cwd=tmp_path,
    )

    assert "RESOLVED: 1" in result.stdout
    assert "UNCHANGED: 1" in result.stdout
    assert "NEW: 0" in result.stdout
    # Exit 0 when findings decreased and no new findings (decreased is good = pass)
    assert result.returncode == 0, (
        f"Expected exit 0, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )


def test_update_baseline_on_decreased_succeeds(tmp_path: Path) -> None:
    """--update-baseline succeeds when count decreases; baseline is rewritten."""
    guard_out = "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10 desc\n"
    baseline_fps = [
        "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10",
        "G-CANCEL-02 app/src/main/java/com/example/Bar.kt:20",
    ]

    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, guard_out, exit_code=1)

    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "test", baseline_fps)

    result = _run_ratchet(
        "test",
        f"{sys.executable} {guard_py}",
        baseline,
        extra_args=["--update-baseline"],
        cwd=tmp_path,
    )

    # Should exit 0 (decreased = pass) on success
    assert result.returncode == 0, (
        f"Expected exit 0, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )

    # Verify baseline was updated
    with open(baseline, "r", encoding="utf-8") as f:
        updated = json.load(f)
    assert len(updated["fingerprints"]) == 1
    assert updated["fingerprints"][0] == "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10"


def test_update_baseline_on_increased_fails(tmp_path: Path) -> None:
    """--update-baseline fails when count increases (exit 2 infra error)."""
    guard_out = (
        "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10 desc\n"
        "G-CANCEL-02 app/src/main/java/com/example/Bar.kt:20 desc\n"
    )
    baseline_fps = [
        "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10",
    ]

    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, guard_out, exit_code=1)

    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "test", baseline_fps)

    result = _run_ratchet(
        "test",
        f"{sys.executable} {guard_py}",
        baseline,
        extra_args=["--update-baseline"],
        cwd=tmp_path,
    )

    # Should fail because findings increased
    assert result.returncode in (1, 2), (
        f"Expected exit 1 or 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "Cannot update baseline" in result.stderr


def test_fingerprints_sorted(tmp_path: Path) -> None:
    """Fingerprint output is sorted deterministically."""
    guard_out = (
        "G-CANCEL-03 app/src/main/java/com/example/Ccc.kt:30 desc\n"
        "G-CANCEL-01 app/src/main/java/com/example/Aaa.kt:10 desc\n"
        "G-CANCEL-02 app/src/main/java/com/example/Bbb.kt:20 desc\n"
    )

    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, guard_out, exit_code=1)

    # Add extra fingerprint to baseline (should resolve)
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "test", [
        "G-CANCEL-01 app/src/main/java/com/example/Aaa.kt:10",
        "G-CANCEL-02 app/src/main/java/com/example/Bbb.kt:20",
        "G-CANCEL-03 app/src/main/java/com/example/Ccc.kt:30",
        "G-CANCEL-04 app/src/main/java/com/example/Ddd.kt:40",
    ])

    result = _run_ratchet(
        "test",
        f"{sys.executable} {guard_py}",
        baseline,
        cwd=tmp_path,
    )

    assert result.returncode == 0

    # Extract lines under NEW and RESOLVED (they start with indented fingerprint)
    lines = result.stdout.splitlines()
    in_new = False
    in_resolved = False
    new_fps = []
    resolved_fps = []
    for line in lines:
        if line.startswith("  NEW:"):
            in_new = True
            in_resolved = False
            continue
        if line.startswith("  RESOLVED:"):
            in_new = False
            in_resolved = True
            continue
        if line.startswith("  UNCHANGED:"):
            in_new = False
            in_resolved = False
            continue
        if in_new and line.startswith("    "):
            new_fps.append(line.strip())
        if in_resolved and line.startswith("    "):
            resolved_fps.append(line.strip())

    # Resolved should be sorted
    assert resolved_fps == sorted(resolved_fps), f"Not sorted: {resolved_fps}"
    # New should be empty (no new findings)
    assert new_fps == []


def test_zero_violations_empty_baseline(tmp_path: Path) -> None:
    """Guard with zero violations and empty baseline exits 0."""
    guard_out = ""  # No violations

    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, guard_out, exit_code=0)

    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "test", [])

    result = _run_ratchet(
        "test",
        f"{sys.executable} {guard_py}",
        baseline,
        cwd=tmp_path,
    )

    assert result.returncode == 0, (
        f"Expected exit 0, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "PASS" in result.stdout
    assert "NEW: 0" in result.stdout
    assert "RESOLVED: 0" in result.stdout
    assert "UNCHANGED: 0" in result.stdout


def test_infra_error_on_command_not_found(tmp_path: Path) -> None:
    """Guard that exits non-zero with empty output should exit 2.

    A guard that crashes or fails to execute (e.g. script not found,
    syntax error) may produce no violation output while still returning
    a non-zero exit code.  The ratchet must treat this as an
    infrastructure error, not as "zero violations".
    """
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "test", [])

    # python -c "import sys; sys.exit(1)" exits 1 with empty stdout
    # This simulates a guard that failed to run properly.
    command = f'{sys.executable} -c "import sys; sys.exit(1)"'
    result = _run_ratchet(
        "test",
        command,
        baseline,
        cwd=tmp_path,
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )


def test_ci_mode_rejects_update_baseline(tmp_path: Path) -> None:
    """--ci-mode and --update-baseline together should exit 2."""
    guard_out = "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10 desc\n"
    baseline_fps = [
        "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10",
        "G-CANCEL-02 app/src/main/java/com/example/Bar.kt:20",
    ]

    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, guard_out, exit_code=1)

    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "test", baseline_fps)

    result = _run_ratchet(
        "test",
        f"{sys.executable} {guard_py}",
        baseline,
        extra_args=["--update-baseline", "--ci-mode"],
        cwd=tmp_path,
    )

    assert result.returncode == 2, (
        f"Expected exit 2, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "Baseline updates prohibited in CI mode" in result.stderr


def test_resolved_entry_exits_one(tmp_path: Path) -> None:
    """Resolved entries with --fail-on-violation should exit 1 (policy violation)."""
    guard_out = "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10 desc\n"
    baseline_fps = [
        "G-CANCEL-01 app/src/main/java/com/example/Foo.kt:10",
        "G-CANCEL-02 app/src/main/java/com/example/Bar.kt:20",
    ]

    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, guard_out, exit_code=1)

    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "test", baseline_fps)

    result = _run_ratchet(
        "test",
        f"{sys.executable} {guard_py}",
        baseline,
        extra_args=["--fail-on-violation"],
        cwd=tmp_path,
    )

    assert result.returncode == 1, (
        f"Expected exit 1, got {result.returncode}\n"
        f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
    )
    assert "FAIL" in result.stdout
    assert "resolved entries remain in baseline" in result.stdout
    assert "RESOLVED: 1" in result.stdout
