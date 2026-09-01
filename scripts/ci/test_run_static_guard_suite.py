#!/usr/bin/env python3
"""
test_run_static_guard_suite.py

Pytest tests for the static guard suite runner
(scripts/ci/run_static_guard_suite.py).

Tests verify:
  1. Later guards run after an earlier blocking failure.
  2. A warning violation does not fail the suite.
  3. A warning guard infra error fails the suite (exit 2).
  4. A blocking violation fails the suite (exit 1).
  5. stdout and stderr are captured to log files.
  6. Summary JSON is valid and deterministically ordered.
  7. Every manifest entry produces a log file.
  8. Missing command produces exit 2.
  9. All blocking guards pass produces exit 0.
  10. Per-guard timeout defaults to 1500s with GUARD_TIMEOUT_SECONDS env override.
  11. guard_tests resolves its interpreter portably (sys.executable).
  12. db_access ratchet carries a --timeout token derived from the suite
      budget: max(GUARD_TIMEOUT_SECONDS - 60, 600).
  13. PR-GR-10c time budgets: a guard over its expected_max_seconds is
      marked outcome "slow" (non-blocking; exit unaffected; failures are
      never masked), budgets are declared for every built-in guard, and
      custom JSON manifests may declare per-entry budgets.
  14. PR-GR-10b db_artifact_sync wiring: the guard is registered blocking
      in GUARD_REGISTRY with the migrate CLI as its script, present in
      the derived suite legs with a tokenized --verify argv consuming the
      reviewed seed input, and the registry validates cleanly.
  15. PR-GR-10A Slice 2 migration: the default suite legs are DERIVED from
      the registry execution schema via compile_static_suite_plan; the
      pre-migration hard-coded manifest is recorded as a fixture and every
      guard is proven semantically equal to its compiled plan (semantic
      command equality per the GR-10A plan definition).
  16. PR-GR-10A deliverable 4: every default-path suite run writes
      execution-plan.json + execution-plan.sha256 + effective-inputs.json
      (deterministic, repo-relative, path-free).
  17. Derivation fails closed (exit 2 semantics) on compile diagnostics and
      suite-order coverage gaps; the PEP 562 derived compatibility views
      keep pre-migration consumers working.
  18. verify_guard_registry.py owns plan validation: it never imports the
      suite, the suite carries no legacy manifest assignment, and the
      validator passes on the real tree.

Run:
  python -m pytest scripts/ci/test_run_static_guard_suite.py -v
"""

import ast
import hashlib
import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Dict, List, Tuple

import pytest

# Import the runner to inspect the derived suite plan directly
import importlib.util
_runner_spec = importlib.util.spec_from_file_location(
    "run_static_guard_suite",
    Path(__file__).resolve().parent / "run_static_guard_suite.py",
)
_runner = importlib.util.module_from_spec(_runner_spec)
_runner_spec.loader.exec_module(_runner)


# ── Helpers ─────────────────────────────────────────────────────────────────────

RUNNER_SCRIPT = Path(__file__).resolve().parent / "run_static_guard_suite.py"
CI_DIR = Path(__file__).resolve().parent
REPO_ROOT = CI_DIR.parent.parent


def _load_fresh_runner(tag: str):
    """Load a fresh runner module so import-time env resolution re-runs."""
    spec = importlib.util.spec_from_file_location(
        f"run_static_guard_suite_fresh_{tag}",
        RUNNER_SCRIPT,
    )
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _derived(module=None):
    """Derive the default suite legs/budgets/plans (asserting success)."""
    module = module if module is not None else _runner
    legs, budgets, plans, errors = module._derive_default_suite_plan(REPO_ROOT)
    assert not errors, errors
    return legs, budgets, plans


def _rel_token(token: str, root: Path) -> str:
    """Relativize an absolute token under ``root`` to a repo-relative spelling."""
    if os.path.isabs(token):
        root_str = str(root)
        try:
            contained = (
                os.path.commonpath(
                    [os.path.normcase(token), os.path.normcase(root_str)]
                )
                == os.path.normcase(root_str)
            )
        except ValueError:
            return token
        if contained:
            return os.path.relpath(token, root_str).replace(os.sep, "/")
    return token


def _relativize_argv(argv: List[str], root: Path) -> List[str]:
    relativized: List[str] = []
    for token in argv:
        if token.startswith("--command-arg="):
            relativized.append(
                "--command-arg=" + _rel_token(token.split("=", 1)[1], root)
            )
        else:
            relativized.append(_rel_token(token, root))
    return relativized


def _leg(name: str, module=None) -> Tuple[str, List[str], str]:
    """Return one derived suite leg with repo-relative path spellings."""
    legs, _budgets, _plans = _derived(module)
    for leg_name, command, mode in legs:
        if leg_name == name:
            return leg_name, _relativize_argv(command, REPO_ROOT), mode
    assert False, f"{name} not found in derived suite legs"


def _write_script(path: Path, content: str) -> None:
    """Write a mock guard script and make it executable."""
    path.write_text(content, encoding='utf-8')
    # Mark as executable on Unix; Windows handles .py via association
    if sys.platform != 'win32':
        os.chmod(path, 0o755)


def _write_manifest(path: Path, entries: List[dict]) -> None:
    """Write a JSON manifest file."""
    path.write_text(json.dumps(entries, indent=2), encoding='utf-8')


def _run_runner(manifest_path: Path, output_dir: Path) -> subprocess.CompletedProcess:
    """Run the guard suite runner with a custom manifest and output directory."""
    cmd = [
        sys.executable,
        str(RUNNER_SCRIPT),
        "--manifest", str(manifest_path),
        "--output-dir", str(output_dir),
    ]
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding='utf-8',
        errors='replace',
        timeout=60,
    )


# ── Tests ───────────────────────────────────────────────────────────────────────

class TestLaterGuardsRunAfterBlockingFailure:
    """Test 1: Later guards run after an earlier blocking failure."""

    def test_later_guards_run(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            # Guard A: blocking, exits 1 (violation)
            guard_a = scripts_dir / "guard_a.py"
            _write_script(guard_a, (
                "import sys\n"
                "print('guard_a stdout')\n"
                "print('guard_a stderr', file=sys.stderr)\n"
                "sys.exit(1)\n"
            ))

            # Guard B: blocking, exits 0
            guard_b = scripts_dir / "guard_b.py"
            _write_script(guard_b, (
                "import sys\n"
                "print('guard_b stdout')\n"
                "sys.exit(0)\n"
            ))

            manifest = [
                {"name": "guard_a", "command": [sys.executable, str(guard_a)], "mode": "blocking"},
                {"name": "guard_b", "command": [sys.executable, str(guard_b)], "mode": "blocking"},
            ]
            _write_manifest(manifest_path, manifest)

            result = _run_runner(manifest_path, out_dir)

            # Both guards should have produced log files
            assert (out_dir / "guard_a.log").exists(), "guard_a log missing"
            assert (out_dir / "guard_b.log").exists(), "guard_b did not run"

            # Summary JSON should exist
            assert (out_dir / "summary.json").exists()
            with open(out_dir / "summary.json", 'r', encoding='utf-8') as f:
                summary = json.load(f)

            assert summary["summary"]["total"] == 2
            assert summary["summary"]["failed_blocking"] == 1
            assert summary["summary"]["passed"] == 1

            # Suite should exit 1 (blocking violation)
            assert result.returncode == 1, f"Expected exit 1, got {result.returncode}"


class TestWarningViolationDoesNotFailSuite:
    """Test 2: A warning violation does not fail the suite."""

    def test_warning_violation_passes(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            # Guard W: warning, exits 1 (has violations but treated as warning)
            guard_w = scripts_dir / "guard_w.py"
            _write_script(guard_w, (
                "import sys\n"
                "print('violations found')\n"
                "sys.exit(1)\n"
            ))

            # Guard B: blocking, exits 0
            guard_b = scripts_dir / "guard_b.py"
            _write_script(guard_b, (
                "import sys\n"
                "print('all clear')\n"
                "sys.exit(0)\n"
            ))

            manifest = [
                {"name": "guard_w", "command": [sys.executable, str(guard_w)], "mode": "warning"},
                {"name": "guard_b", "command": [sys.executable, str(guard_b)], "mode": "blocking"},
            ]
            _write_manifest(manifest_path, manifest)

            result = _run_runner(manifest_path, out_dir)

            # Suite should exit 0 — warning violations don't cause failure
            assert result.returncode == 0, f"Expected exit 0, got {result.returncode}"

            # Verify summary
            with open(out_dir / "summary.json", 'r', encoding='utf-8') as f:
                summary = json.load(f)

            assert summary["summary"]["failed_blocking"] == 0
            assert summary["summary"]["warning_violations"] == 1
            assert summary["summary"]["passed"] == 1


class TestWarningInfraErrorFailsSuite:
    """Test 3: A warning guard infra error fails the suite (exit 2)."""

    def test_warning_infra_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            # Guard W: warning, exits 2 (infra error — script crash)
            guard_w = scripts_dir / "guard_w.py"
            _write_script(guard_w, (
                "import sys\n"
                "print('something went wrong internally')\n"
                "sys.exit(2)\n"
            ))

            manifest = [
                {"name": "guard_w", "command": [sys.executable, str(guard_w)], "mode": "warning"},
            ]
            _write_manifest(manifest_path, manifest)

            result = _run_runner(manifest_path, out_dir)

            # Suite should exit 2 — infra error
            assert result.returncode == 2, f"Expected exit 2, got {result.returncode}"

            with open(out_dir / "summary.json", 'r', encoding='utf-8') as f:
                summary = json.load(f)

            assert summary["summary"]["infra_errors"] == 1
            assert summary["summary"]["warning_violations"] == 0


class TestBlockingViolationFailsSuite:
    """Test 4: A blocking violation fails the suite (exit 1)."""

    def test_blocking_violation(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            # Guard A: blocking, exits 1
            guard_a = scripts_dir / "guard_a.py"
            _write_script(guard_a, (
                "import sys\n"
                "print('violation detected')\n"
                "sys.exit(1)\n"
            ))

            manifest = [
                {"name": "guard_a", "command": [sys.executable, str(guard_a)], "mode": "blocking"},
            ]
            _write_manifest(manifest_path, manifest)

            result = _run_runner(manifest_path, out_dir)

            assert result.returncode == 1, f"Expected exit 1, got {result.returncode}"

            with open(out_dir / "summary.json", 'r', encoding='utf-8') as f:
                summary = json.load(f)

            assert summary["summary"]["failed_blocking"] == 1
            assert summary["summary"]["total"] == 1


class TestStdoutStderrCapturedToLogFiles:
    """Test 5: stdout and stderr are captured to log files."""

    def test_output_captured(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            guard = scripts_dir / "my_guard.py"
            _write_script(guard, (
                "import sys\n"
                "print('hello stdout')\n"
                "print('hello stderr', file=sys.stderr)\n"
                "sys.exit(0)\n"
            ))

            manifest = [
                {"name": "my_guard", "command": [sys.executable, str(guard)], "mode": "blocking"},
            ]
            _write_manifest(manifest_path, manifest)

            result = _run_runner(manifest_path, out_dir)
            assert result.returncode == 0

            log_path = out_dir / "my_guard.log"
            assert log_path.exists()

            log_content = log_path.read_text(encoding='utf-8')
            assert "hello stdout" in log_content, f"stdout not found in log: {log_content}"
            assert "hello stderr" in log_content, f"stderr not found in log: {log_content}"


class TestSummaryJsonValidAndOrdered:
    """Test 6: Summary JSON is valid, contains expected keys, and results are in manifest order."""

    def test_summary_json_structure(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            guard_a = scripts_dir / "first.py"
            _write_script(guard_a, "import sys; print('first'); sys.exit(0)\n")

            guard_b = scripts_dir / "second.py"
            _write_script(guard_b, "import sys; print('second'); sys.exit(1)\n")

            manifest = [
                {"name": "first", "command": [sys.executable, str(guard_a)], "mode": "blocking"},
                {"name": "second", "command": [sys.executable, str(guard_b)], "mode": "blocking"},
            ]
            _write_manifest(manifest_path, manifest)

            result = _run_runner(manifest_path, out_dir)
            assert result.returncode == 1

            with open(out_dir / "summary.json", 'r', encoding='utf-8') as f:
                summary = json.load(f)

            # Top-level keys
            assert "timestamp" in summary
            assert "results" in summary
            assert "summary" in summary

            # Results in manifest order
            assert len(summary["results"]) == 2
            assert summary["results"][0]["name"] == "first"
            assert summary["results"][1]["name"] == "second"

            # Each result has required keys
            for r in summary["results"]:
                assert "name" in r
                assert "mode" in r
                assert "exit_code" in r
                assert "outcome" in r
                assert "duration_seconds" in r
                assert "log_path" in r
                assert "stdout_preview" in r
                assert "stderr_preview" in r

            # Summary counts
            assert summary["summary"]["total"] == 2
            assert summary["summary"]["passed"] == 1
            assert summary["summary"]["failed_blocking"] == 1

            # Summary MD should exist
            assert (out_dir / "summary.md").exists()


class TestEveryManifestEntryProducesLogFile:
    """Test 7: Every manifest entry produces a log file."""

    def test_every_entry_has_log(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            entries = []
            for i in range(5):
                name = f"guard_{i}"
                guard_path = scripts_dir / f"{name}.py"
                _write_script(guard_path, (
                    f"import sys; print('guard {i}'); sys.exit({i % 3})\n"
                ))
                entries.append({
                    "name": name,
                    "command": [sys.executable, str(guard_path)],
                    "mode": "blocking" if i % 2 == 0 else "warning",
                })

            _write_manifest(manifest_path, entries)
            result = _run_runner(manifest_path, out_dir)

            # Every guard must have a log file
            for i in range(5):
                name = f"guard_{i}"
                log_path = out_dir / f"{name}.log"
                assert log_path.exists(), f"Missing log for {name}"

            summary_path = out_dir / "summary.json"
            with open(summary_path, 'r', encoding='utf-8') as f:
                summary = json.load(f)
            assert summary["summary"]["total"] == 5


class TestMissingCommandProducesExit2:
    """Test 8: Missing command produces exit 2."""

    def test_missing_command(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            manifest = [
                {"name": "nonexistent", "command": ["nonexistent_command_xyz", "--flag"], "mode": "blocking"},
            ]
            _write_manifest(manifest_path, manifest)

            result = _run_runner(manifest_path, out_dir)

            assert result.returncode == 2, f"Expected exit 2, got {result.returncode}"

            with open(out_dir / "summary.json", 'r', encoding='utf-8') as f:
                summary = json.load(f)

            assert summary["summary"]["infra_errors"] == 1
            assert summary["summary"]["total"] == 1

            # Log file should still be created
            log_path = out_dir / "nonexistent.log"
            assert log_path.exists()
            log_content = log_path.read_text(encoding='utf-8')
            assert "COMMAND NOT FOUND" in log_content


class TestAllBlockingGuardsPassProducesExit0:
    """Test 9: All blocking guards pass produces exit 0."""

    def test_all_pass(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            entries = []
            for i in range(3):
                name = f"pass_{i}"
                guard_path = scripts_dir / f"{name}.py"
                _write_script(guard_path, "import sys; sys.exit(0)\n")
                entries.append({
                    "name": name,
                    "command": [sys.executable, str(guard_path)],
                    "mode": "blocking",
                })

            _write_manifest(manifest_path, entries)
            result = _run_runner(manifest_path, out_dir)

            assert result.returncode == 0, f"Expected exit 0, got {result.returncode}"

            with open(out_dir / "summary.json", 'r', encoding='utf-8') as f:
                summary = json.load(f)

            assert summary["summary"]["passed"] == 3
            assert summary["summary"]["failed_blocking"] == 0
            assert summary["summary"]["infra_errors"] == 0
            assert summary["summary"]["total"] == 3


class TestSummaryMdIsWritten:
    """Verify summary.md is generated with expected sections."""

    def test_summary_md(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            guard = scripts_dir / "g.py"
            _write_script(guard, "import sys; sys.exit(0)\n")

            manifest = [
                {"name": "g", "command": [sys.executable, str(guard)], "mode": "blocking"},
            ]
            _write_manifest(manifest_path, manifest)

            _run_runner(manifest_path, out_dir)

            md_path = out_dir / "summary.md"
            assert md_path.exists()

            md_content = md_path.read_text(encoding='utf-8')
            assert "# Static Guard Suite Summary" in md_content
            assert "## Overall" in md_content
            assert "## Details" in md_content


class TestGlobExpansionInCommands:
    """Verify that shell glob patterns in command args are expanded."""

    def test_glob_expansion(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            # Create mock test files that pytest would discover
            test_file = scripts_dir / "test_dummy_a.py"
            _write_script(test_file, (
                "def test_pass():\n"
                "    assert True\n"
            ))

            # Guard that uses pytest with a glob — pytest will find the test file
            manifest = [
                {
                    "name": "pytest_glob",
                    "command": [sys.executable, "-m", "pytest", f"{scripts_dir}/test_dummy_*.py", "-v", "--tb=short"],
                    "mode": "blocking",
                },
            ]
            _write_manifest(manifest_path, manifest)

            result = _run_runner(manifest_path, out_dir)

            # The expanded glob should make pytest find and run the test file
            # If glob expansion failed, pytest would get a literal "*" and error out
            log_path = out_dir / "pytest_glob.log"
            assert log_path.exists()

            log_content = log_path.read_text(encoding='utf-8')
            # Pytest should have found and passed the test
            assert "test_pass" in log_content or "PASSED" in log_content or "passed" in log_content.lower(), \
                f"Glob expansion may have failed. Log: {log_content[:500]}"

            # Should exit 0 because test passed
            assert result.returncode == 0, f"Expected exit 0, got {result.returncode}"


class TestOutputDirCreatedAutomatically:
    """Verify that the output directory is created if it doesn't exist."""

    def test_output_dir_created(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "deeply" / "nested" / "output"  # does not exist
            manifest_path = tmp_path / "manifest.json"

            guard = scripts_dir / "g.py"
            _write_script(guard, "import sys; sys.exit(0)\n")

            manifest = [
                {"name": "g", "command": [sys.executable, str(guard)], "mode": "blocking"},
            ]
            _write_manifest(manifest_path, manifest)

            result = _run_runner(manifest_path, out_dir)
            assert result.returncode == 0
            assert out_dir.exists()
            assert (out_dir / "summary.json").exists()


class TestArtifactGuardRejectedFromSourceSuite:
    """Verify release_artifact is NOT in the derived default suite legs.

    The Static Guards job runs from a source checkout and has no APK.
    The release_artifact verification runs in the release-check CI job
    after assembleRelease, not here.
    """

    def test_artifact_guard_not_in_manifest(self):
        legs, _budgets, _plans = _derived()
        guard_names = [name for name, _, _ in legs]
        assert "release_artifact" not in guard_names, (
            f"release_artifact should not be in the derived suite legs; "
            f"found guards: {guard_names}"
        )


class TestGuardTimeoutConfiguration:
    """Per-guard timeout defaults to 1500s and is env-overridable.

    The db_access full-tree D4 scan alone can take ~7-10 minutes and
    guard_tests (pytest over scripts/) can take longer, so the previous
    300s default timed out on slow machines. The GUARD_TIMEOUT_SECONDS
    environment variable overrides the default per environment.
    """

    ENV_VAR = "GUARD_TIMEOUT_SECONDS"

    def _load_fresh_runner(self):
        """Load a fresh runner module so import-time env resolution re-runs."""
        spec = importlib.util.spec_from_file_location(
            f"run_static_guard_suite_fresh_{id(self)}",
            RUNNER_SCRIPT,
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def test_default_timeout_is_1500(self, monkeypatch):
        monkeypatch.delenv(self.ENV_VAR, raising=False)
        fresh = self._load_fresh_runner()
        assert fresh.DEFAULT_GUARD_TIMEOUT_SECONDS == 1500
        assert fresh.GUARD_TIMEOUT_SECONDS == 1500

    def test_env_override_changes_timeout(self, monkeypatch):
        monkeypatch.setenv(self.ENV_VAR, "42")
        fresh = self._load_fresh_runner()
        assert fresh.GUARD_TIMEOUT_SECONDS == 42

    def test_env_override_invalid_value_falls_back(self, monkeypatch):
        monkeypatch.setenv(self.ENV_VAR, "not-a-number")
        fresh = self._load_fresh_runner()
        assert fresh.GUARD_TIMEOUT_SECONDS == 1500

    def test_env_override_non_positive_falls_back(self, monkeypatch):
        for bad in ("0", "-5"):
            monkeypatch.setenv(self.ENV_VAR, bad)
            fresh = self._load_fresh_runner()
            assert fresh.GUARD_TIMEOUT_SECONDS == 1500, (
                f"Expected fallback to default for {bad!r}"
            )

    def test_resolver_blank_value_falls_back(self, monkeypatch):
        monkeypatch.setenv(self.ENV_VAR, "   ")
        assert _runner._resolve_guard_timeout() == 1500

    def test_env_override_applies_to_real_run(self, monkeypatch):
        """End-to-end: the override governs the actual subprocess timeout."""
        monkeypatch.setenv(self.ENV_VAR, "1")
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            slow_guard = scripts_dir / "slow_guard.py"
            _write_script(slow_guard, "import time\ntime.sleep(5)\n")

            manifest = [
                {"name": "slow_guard", "command": [sys.executable, str(slow_guard)], "mode": "blocking"},
            ]
            _write_manifest(manifest_path, manifest)

            result = _run_runner(manifest_path, out_dir)

            assert result.returncode == 2, f"Expected exit 2, got {result.returncode}"
            with open(out_dir / "summary.json", 'r', encoding='utf-8') as f:
                summary = json.load(f)
            assert summary["summary"]["infra_errors"] == 1
            assert "Timeout after 1s" in summary["results"][0]["stdout_preview"], (
                f"Timeout message missing resolved override: "
                f"{summary['results'][0]['stdout_preview']!r}"
            )


class TestGuardTestsInterpreterPortable:
    """The guard_tests infrastructure leg must resolve its interpreter portably.

    The leg must run pytest under the interpreter running the suite
    (sys.executable) instead of a bare "python3" PATH lookup, which may not
    resolve on Windows. What is tested (pytest targets and flags) must
    remain unchanged.
    """

    def _guard_tests_command(self):
        for name, command, _ in _runner.SUITE_INFRASTRUCTURE_LEGS:
            if name == "guard_tests":
                return command
        assert False, "guard_tests not found in SUITE_INFRASTRUCTURE_LEGS"

    def test_interpreter_is_suite_interpreter(self):
        command = self._guard_tests_command()
        assert command[0] == sys.executable, (
            f"guard_tests must run under sys.executable, got: {command[0]!r}"
        )

    def test_pytest_targets_unchanged(self):
        command = self._guard_tests_command()
        assert command[1:] == [
            "-m", "pytest",
            "scripts/test_verify_*.py",
            "scripts/ci/test_*.py",
            "-v", "--tb=short",
        ], f"guard_tests pytest invocation changed: {command[1:]}"


# ── F2/D4 integration tests ───────────────────────────────────────────────────


# Import the registry to test its metadata
import importlib.util as _ilu
_reg_spec = _ilu.spec_from_file_location(
    "guard_registry",
    Path(__file__).resolve().parent / "guard_registry.py",
)
_reg = _ilu.module_from_spec(_reg_spec)
_reg_spec.loader.exec_module(_reg)


class TestDbAccessRegistryMetadata:
    """Verify the db_access guard registry entry carries F2/D4 metadata."""

    def test_finding_protocol_is_two(self):
        """db_access finding_protocol must be 2."""
        entry = _reg.GUARD_REGISTRY["db_access"]
        assert entry.get("finding_protocol") == 2, (
            f"Expected finding_protocol=2, got {entry.get('finding_protocol')}"
        )

    def test_fingerprint_schema_is_two(self):
        """db_access fingerprint_schema must be 2."""
        entry = _reg.GUARD_REGISTRY["db_access"]
        assert entry.get("fingerprint_schema") == 2, (
            f"Expected fingerprint_schema=2, got {entry.get('fingerprint_schema')}"
        )

    def test_report_command_points_to_verify_script(self):
        """db_access report_command must reference verify_db_access_boundaries.py."""
        entry = _reg.GUARD_REGISTRY["db_access"]
        cmd = entry.get("report_command", "")
        assert "verify_db_access_boundaries.py" in cmd, (
            f"report_command does not reference verify_db_access_boundaries.py: {cmd}"
        )

    def test_report_guard_metadata_has_env_and_flags(self):
        """db_access report_guard_metadata must declare env vars and flags."""
        entry = _reg.GUARD_REGISTRY["db_access"]
        meta = entry.get("report_guard_metadata", {})
        assert meta.get("env_file") == "COST_AGGREGATOR_GUARD_FINDINGS_FILE"
        assert meta.get("env_schema") == "COST_AGGREGATOR_GUARD_FINDINGS_SCHEMA"
        flags = meta.get("flags", [])
        assert "--fail-on-violation" in flags
        assert "--structural-manifest" in flags

    def test_baseline_migrated_to_v2(self):
        """db_access baseline must be the v2 (GR-09-migrated) file."""
        entry = _reg.GUARD_REGISTRY["db_access"]
        assert entry.get("baseline") == "config/baselines/db_access_v2.json"


class TestDbAccessSuiteCommandTokens:
    """Verify the DB guard derived leg uses protocol-v2 --command-arg tokens.

    The leg argv is the registry-derived compiled plan (PR-GR-10A Slice 2)
    with the suite's ratchet child-budget adapter policy applied; path
    tokens are relativized here for assertion readability.
    """

    def _db_access_command(self) -> List[str]:
        _name, command, _mode = _leg("db_access")
        return command

    def test_db_access_command_is_list_of_tokens(self):
        """The db_access leg must use a token list (never a shell string)."""
        command = self._db_access_command()
        assert isinstance(command, list), "command must be a list of tokens"

    def test_db_access_uses_guard_ratchet(self):
        """The db_access command must invoke guard_ratchet.py."""
        command = self._db_access_command()
        assert any("guard_ratchet.py" in tok for tok in command), (
            f"guard_ratchet.py not in command tokens: {command}"
        )

    def test_db_access_no_legacy_command_string(self):
        """The db_access leg must NOT use the legacy --command shell string."""
        command = self._db_access_command()
        # Must not have a "--command" token (legacy form)
        assert "--command" not in command, (
            f"db_access still uses legacy --command: {command}"
        )

    def test_db_access_uses_command_arg_tokens(self):
        """The db_access child interpreter must be the resolved runtime."""
        command = self._db_access_command()
        assert f"--command-arg={sys.executable}" in command, (
            f"resolved interpreter token not in command: {command}"
        )
        db_script_arg = None
        for tok in command:
            if "verify_db_access_boundaries.py" in tok:
                db_script_arg = tok
                break
        assert db_script_arg is not None, (
            f"verify_db_access_boundaries.py not in command tokens: {command}"
        )

    def test_db_access_no_bare_interpreter_token(self):
        """Canonical plans never emit bare python/python3 (GR-10A rule 2)."""
        command = self._db_access_command()
        assert "--command-arg=python" not in command, command
        assert "--command-arg=python3" not in command, command

    def test_db_access_explicit_protocol_v2_intent(self):
        """Registered protocol-v2 guards pass explicit protocol intent."""
        command = self._db_access_command()
        assert "--finding-protocol=2" in command, command

    def test_db_access_includes_structural_manifest(self):
        """The db_access command must include --structural-manifest."""
        command = self._db_access_command()
        assert "--command-arg=--structural-manifest" in command, (
            f"--structural-manifest not in command tokens: {command}"
        )

    def test_db_access_includes_canonical_policies(self):
        """The db_access command must include canonical policy paths."""
        command = self._db_access_command()
        cmd_str = " ".join(command)
        assert "db_ownership_policy.yml" in cmd_str, (
            f"ownership policy not in command: {command}"
        )
        assert "db_structural_exceptions.yml" in cmd_str, (
            f"structural exceptions not in command: {command}"
        )

    def test_db_access_baseline_path_is_v2(self):
        """The db_access ratchet command must pin the v2 baseline (GR-09)."""
        command = self._db_access_command()
        assert "--baseline" in command, (
            f"--baseline not in command tokens: {command}"
        )
        assert "config/baselines/db_access_v2.json" in command, (
            f"v2 baseline path not in command tokens: {command}"
        )
        assert "config/baselines/db_access.json" not in command, (
            f"legacy v1 baseline path still in command tokens: {command}"
        )

    def test_db_access_ratchet_timeout_token_present(self):
        """The db_access ratchet argv must carry a derived --timeout token.

        guard_ratchet.py enforces its own child-process timeout via its
        --timeout flag (default 300s), which is shorter than the db_access
        full-tree D4 scan (~7-10 minutes): the ratchet kills a healthy scan
        and exits 2. The suite must pass the derived budget as a
        ratchet-level ``--timeout=<seconds>`` token.

        The token must NOT be a --command-arg: verify_db_access_boundaries.py
        has no --timeout flag, so a child-level token would crash the child
        with an argparse error (exit 2).
        """
        command = self._db_access_command()
        timeout_tokens = [
            tok for tok in command if tok.startswith("--timeout=")
        ]
        assert len(timeout_tokens) == 1, (
            f"expected exactly one --timeout=<seconds> token: {command}"
        )
        raw_value = timeout_tokens[0].split("=", 1)[1]
        assert raw_value.isdigit(), (
            f"--timeout value must be a positive integer: "
            f"{timeout_tokens[0]!r}"
        )
        assert int(raw_value) >= 600, (
            f"--timeout must cover the ~7-10 min D4 scan "
            f"(floor 600s): {raw_value}"
        )
        assert "--command-arg=--timeout" not in command, (
            f"--timeout must be a ratchet-level flag, not a child arg "
            f"(the child guard has no --timeout flag): {command}"
        )

    def test_db_access_child_argv_is_authoritative_v2_cli(self):
        """The db_access child command must be the authoritative protocol-v2
        CLI argv: scripts/verify_db_access_boundaries.py invoked with
        --fail-on-violation, and the full command must never reference the
        retired legacy shadow-report flag or the archived legacy policy path.

        The ratchet consumes protocol-v2 reports only; the suite pins the v2
        baseline (config/baselines/db_access_v2.json, GR-09), while a legacy
        v1 baseline surfaces as the controlled RATCHET_V1_BASELINE_INCOMPATIBLE
        exit 2 (pinned in test_guard_ratchet_v2.py).
        """
        command = self._db_access_command()
        assert (
            "--command-arg=scripts/verify_db_access_boundaries.py"
            in command
        ), (
            "child command does not target "
            f"verify_db_access_boundaries.py: {command}"
        )
        assert "--command-arg=--fail-on-violation" in command, (
            f"child command lacks --fail-on-violation: {command}"
        )
        joined = " ".join(command)
        assert "--legacy-shadow-report" not in joined, (
            f"legacy shadow report referenced by db_access: {command}"
        )
        assert "legacy-shadow" not in joined, (
            f"legacy shadow artifact referenced by db_access: {command}"
        )
        assert "db_ownership_policy.legacy.yml" not in joined, (
            f"archived legacy policy referenced by db_access: {command}"
        )

    def test_db_access_mode_is_blocking(self):
        """The db_access leg must be blocking mode."""
        _name, _command, mode = _leg("db_access")
        assert mode == "blocking", f"Expected blocking, got {mode}"

    def test_all_other_guards_preserved(self):
        """All non-db_access guards must still be present and unchanged."""
        legs, _budgets, _plans = _derived()
        guard_names = [name for name, _, _ in legs]
        expected = [
            "guard_registry", "source_provenance", "ui_dao", "worker",
            "receipt_link", "import_lifecycle", "cloud_payload",
            "pii_logging", "di_release", "allowlist_compliance",
            "ignored_test_budget", "lint_baseline_policy", "time_boundaries",
            "deprecation_escalations", "db_artifact_sync",
            "cancellation", "privacy", "db_access", "event_writers",
            "money", "raw_money_aggregates", "migration_matrix", "guard_tests",
        ]
        for name in expected:
            assert name in guard_names, f"Guard '{name}' missing from derived legs"


class TestDbAccessRatchetChildTimeout:
    """The db_access ratchet's child budget is derived from the suite budget.

    guard_ratchet.py defaults its child timeout to 300s, which is shorter
    than the db_access full-tree D4 scan (~7-10 minutes); the ratchet kills
    a healthy scan and exits 2. The suite derives the ratchet's --timeout as
    max(GUARD_TIMEOUT_SECONDS - 60, 600):

    - 60s headroom keeps the child timeout inside the suite's per-guard
      budget (ratchet startup + baseline load + report parsing), so the
      child timeout fires before the suite's outer timeout whenever the
      suite budget is at least floor + headroom (660s).
    - The 600s floor keeps the child budget viable for the known scan cost
      even when the suite budget is lowered; below a 660s suite budget the
      suite's outer timeout is the effective bound.
    """

    ENV_VAR = "GUARD_TIMEOUT_SECONDS"
    HEADROOM = 60
    FLOOR = 600

    def _load_fresh_runner(self):
        """Load a fresh runner module so import-time env resolution re-runs."""
        return _load_fresh_runner(f"db_timeout_{id(self)}")

    def _db_access_command(self, module):
        legs, _budgets, _plans, errors = module._derive_default_suite_plan(REPO_ROOT)
        assert not errors, errors
        for name, command, _ in legs:
            if name == "db_access":
                return command
        assert False, "db_access not found in derived suite legs"

    def _db_access_child_timeout(self, module):
        command = self._db_access_command(module)
        tokens = [tok for tok in command if tok.startswith("--timeout=")]
        assert len(tokens) == 1, f"expected one --timeout token: {command}"
        return int(tokens[0].split("=", 1)[1])

    def test_default_derivation(self, monkeypatch):
        """Default suite budget 1500s → child budget 1440s."""
        monkeypatch.delenv(self.ENV_VAR, raising=False)
        fresh = self._load_fresh_runner()
        assert fresh.GUARD_TIMEOUT_SECONDS == 1500
        assert fresh._ratchet_child_timeout() == 1440
        assert self._db_access_child_timeout(fresh) == 1440

    def test_derivation_tracks_env_override(self, monkeypatch):
        """Child budget = suite budget - 60 while above the floor."""
        for env_value, expected in (("700", 640), ("2000", 1940), ("3000", 2940)):
            monkeypatch.setenv(self.ENV_VAR, env_value)
            fresh = self._load_fresh_runner()
            assert fresh.GUARD_TIMEOUT_SECONDS == int(env_value)
            actual = self._db_access_child_timeout(fresh)
            assert actual == expected, (
                f"GUARD_TIMEOUT_SECONDS={env_value}: expected {expected}, "
                f"got {actual}"
            )

    def test_floor_applies_when_suite_budget_low(self, monkeypatch):
        """Below floor + headroom (660s) the 600s floor wins."""
        for env_value in ("300", "100", "1"):
            monkeypatch.setenv(self.ENV_VAR, env_value)
            fresh = self._load_fresh_runner()
            actual = self._db_access_child_timeout(fresh)
            assert actual == self.FLOOR, (
                f"GUARD_TIMEOUT_SECONDS={env_value}: expected floor "
                f"{self.FLOOR}, got {actual}"
            )

    def test_boundary_at_floor_plus_headroom(self, monkeypatch):
        """At a 660s suite budget the derivation exactly meets the floor."""
        monkeypatch.setenv(self.ENV_VAR, "660")
        fresh = self._load_fresh_runner()
        assert fresh.GUARD_TIMEOUT_SECONDS == 660
        assert self._db_access_child_timeout(fresh) == 600

    def test_child_budget_fits_inside_suite_budget(self, monkeypatch):
        """For suite budgets >= 660s the child timeout stays nested inside
        the suite's per-guard timeout with the documented 60s headroom."""
        for env_value in ("660", "700", "1500", "3000"):
            monkeypatch.setenv(self.ENV_VAR, env_value)
            fresh = self._load_fresh_runner()
            child = self._db_access_child_timeout(fresh)
            assert child == fresh.GUARD_TIMEOUT_SECONDS - self.HEADROOM
            assert child < fresh.GUARD_TIMEOUT_SECONDS


# ── PR-GR-10c: per-guard time budgets ─────────────────────────────────────────


class TestGuardTimeBudgets:
    """Per-guard expected_max_seconds budgets are visibility-only.

    A guard that finishes above its budget is marked outcome "slow" — a
    non-blocking warning in the summary.  The exit code is unaffected and a
    violation/infra_error outcome is never masked by the budget (a budget
    must never flip a failing suite to green).
    """

    def _manifest(self, scripts_dir: Path, body: str, budget=None) -> list:
        guard = scripts_dir / "budgeted_guard.py"
        _write_script(guard, body)
        entry = {
            "name": "budgeted_guard",
            "command": [sys.executable, str(guard)],
            "mode": "blocking",
        }
        if budget is not None:
            entry["expected_max_seconds"] = budget
        return [entry]

    def _summary(self, out_dir: Path) -> dict:
        with open(out_dir / "summary.json", 'r', encoding='utf-8') as f:
            return json.load(f)

    def test_slow_outcome_when_over_budget_and_exit_unaffected(self):
        """A passing guard over its budget is marked slow; exit stays 0."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            _write_manifest(manifest_path, self._manifest(
                scripts_dir, "import time\ntime.sleep(0.3)\n", budget=0,
            ))
            result = _run_runner(manifest_path, out_dir)

            assert result.returncode == 0, (
                f"slow must never affect the exit code, got {result.returncode}"
            )
            summary = self._summary(out_dir)
            guard = summary["results"][0]
            assert guard["outcome"] == "slow"
            assert guard["budget_exceeded"] is True
            assert guard["expected_max_seconds"] == 0.0
            assert summary["summary"]["slow"] == 1
            # A slow guard is a warning, not a pass and not a failure.
            assert summary["summary"]["passed"] == 0
            assert summary["summary"]["failed_blocking"] == 0
            assert summary["summary"]["infra_errors"] == 0

    def test_pass_outcome_within_budget(self):
        """A guard under its budget keeps outcome pass with the budget recorded."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            _write_manifest(manifest_path, self._manifest(
                scripts_dir, "import sys; sys.exit(0)\n", budget=60,
            ))
            result = _run_runner(manifest_path, out_dir)

            assert result.returncode == 0
            summary = self._summary(out_dir)
            guard = summary["results"][0]
            assert guard["outcome"] == "pass"
            assert guard["budget_exceeded"] is False
            assert guard["expected_max_seconds"] == 60.0
            assert summary["summary"]["slow"] == 0
            assert summary["summary"]["passed"] == 1

    def test_violation_outcome_is_never_masked_by_budget(self):
        """A failing guard over its budget stays a violation (exit 1).

        Marking it slow would hide the violation from the outcome field and
        could flip failed_blocking — a fail-open change the budget feature
        must never make.
        """
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            _write_manifest(manifest_path, self._manifest(
                scripts_dir, "import time; time.sleep(0.3); import sys; sys.exit(1)\n",
                budget=0,
            ))
            result = _run_runner(manifest_path, out_dir)

            assert result.returncode == 1
            summary = self._summary(out_dir)
            guard = summary["results"][0]
            assert guard["outcome"] == "violation"
            assert guard["budget_exceeded"] is True
            assert summary["summary"]["failed_blocking"] == 1
            assert summary["summary"]["slow"] == 0

    def test_infra_error_outcome_is_never_masked_by_budget(self):
        """A crashing guard over its budget stays an infra error (exit 2)."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            _write_manifest(manifest_path, self._manifest(
                scripts_dir, "import time; time.sleep(0.3); import sys; sys.exit(2)\n",
                budget=0,
            ))
            result = _run_runner(manifest_path, out_dir)

            assert result.returncode == 2
            summary = self._summary(out_dir)
            guard = summary["results"][0]
            assert guard["outcome"] == "infra_error"
            assert guard["budget_exceeded"] is True
            assert summary["summary"]["infra_errors"] == 1
            assert summary["summary"]["slow"] == 0

    def test_guard_without_budget_is_never_slow(self):
        """No declared budget -> no budget check -> outcome can never be slow."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            _write_manifest(manifest_path, self._manifest(
                scripts_dir, "import time; time.sleep(0.3); import sys; sys.exit(0)\n",
                budget=None,
            ))
            result = _run_runner(manifest_path, out_dir)

            assert result.returncode == 0
            summary = self._summary(out_dir)
            guard = summary["results"][0]
            assert guard["outcome"] == "pass"
            assert guard["expected_max_seconds"] is None
            assert guard["budget_exceeded"] is False
            assert summary["summary"]["slow"] == 0

    def test_warning_mode_slow_guard_keeps_exit_zero(self):
        """A warning-mode guard over its budget is slow; exit stays 0."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            entry = self._manifest(
                scripts_dir, "import time; time.sleep(0.3); import sys; sys.exit(0)\n",
                budget=0,
            )[0]
            entry["mode"] = "warning"
            _write_manifest(manifest_path, [entry])
            result = _run_runner(manifest_path, out_dir)

            assert result.returncode == 0
            summary = self._summary(out_dir)
            assert summary["results"][0]["outcome"] == "slow"
            assert summary["summary"]["slow"] == 1

    def test_slow_section_written_to_summary_md(self):
        """The human summary carries a dedicated non-blocking slow section."""
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            scripts_dir = tmp_path / "scripts"
            scripts_dir.mkdir()
            out_dir = tmp_path / "output"
            manifest_path = tmp_path / "manifest.json"

            _write_manifest(manifest_path, self._manifest(
                scripts_dir, "import time; time.sleep(0.3)\n", budget=0,
            ))
            _run_runner(manifest_path, out_dir)

            md_content = (out_dir / "summary.md").read_text(encoding='utf-8')
            assert "## ⏱ Slow Guards (over time budget — non-blocking)" in md_content
            assert "budgeted_guard" in md_content
            assert "0s budget" in md_content

    def test_json_manifest_invalid_budget_rejected(self):
        """A negative or non-numeric expected_max_seconds fails the load."""
        bad_manifests = [
            [{"name": "g", "command": [sys.executable, "-c", "pass"],
              "mode": "blocking", "expected_max_seconds": -1}],
            [{"name": "g", "command": [sys.executable, "-c", "pass"],
              "mode": "blocking", "expected_max_seconds": "soon"}],
            [{"name": "g", "command": [sys.executable, "-c", "pass"],
              "mode": "blocking", "expected_max_seconds": True}],
        ]
        for entries in bad_manifests:
            with tempfile.TemporaryDirectory() as tmp:
                manifest_path = Path(tmp) / "manifest.json"
                _write_manifest(manifest_path, entries)
                with pytest.raises(ValueError):
                    _runner._load_manifest_from_json(manifest_path)

    def test_builtin_budgets_declared_for_every_derived_guard(self):
        """Every derived guard resolves a profile budget via the compiler;
        the known-expensive two carry headroom over their observed durations
        (db_access ~700s, guard_tests ~1500s)."""
        _legs, budgets, _plans = _derived()
        for name, _command, _mode in _legs:
            assert name in budgets, f"guard '{name}' has no time budget"
            assert budgets[name] > 0
        assert budgets["db_access"] >= 700
        assert budgets["guard_tests"] >= 1500

    def test_budget_comparison_uses_raw_duration_not_rounded(self):
        """The budget check compares the RAW duration.

        ``duration_seconds`` is rounded to 2 decimals for display; a budget
        of 0.003s must still flag a guard whose raw duration is 0.004s even
        though the rounded display value (0.0) would not.
        """
        class _FakeCompleted:
            returncode = 0
            stdout = "ok"
            stderr = ""

        real_run = _runner.subprocess.run
        real_monotonic = _runner.time.monotonic
        clock = {"now": 100.0}

        def fake_run(command, **kwargs):
            return _FakeCompleted()

        def fake_monotonic():
            clock["now"] += 0.004  # raw duration 0.004s per guard
            return clock["now"]

        _runner.subprocess.run = fake_run
        _runner.time.monotonic = fake_monotonic
        try:
            with tempfile.TemporaryDirectory() as tmp:
                out_dir = Path(tmp) / "output"
                out_dir.mkdir()
                result = _runner.run_guard(
                    "tiny", [sys.executable, "-c", "pass"], "blocking",
                    out_dir, Path(tmp), expected_max_seconds=0.003,
                )
        finally:
            _runner.subprocess.run = real_run
            _runner.time.monotonic = real_monotonic

        assert result["outcome"] == "slow"
        assert result["budget_exceeded"] is True
        assert result["duration_seconds"] == 0.0


# ── Infra-error stderr preview (bounded, redacted) ────────────────────────────


class TestInfraStderrPreview:
    """Infra-error legs carry a bounded, path-redacted stderr preview.

    ``stderr_preview`` is populated only on infra_error outcomes, is
    redacted (absolute path spellings become ``<path>``) and bounded to
    500 chars — observability without path/secret leakage.
    """

    def _run_guard(self, script_body: str, tmp_path: Path) -> dict:
        out_dir = tmp_path / "output"
        # exist_ok=True: this fixture is invoked multiple times per test on
        # the same tmp_path (pass leg + violation leg); a bare mkdir()
        # raises FileExistsError [WinError 183] on the second invocation.
        out_dir.mkdir(exist_ok=True)
        script = tmp_path / "probe_guard.py"
        _write_script(script, script_body)
        return _runner.run_guard(
            "probe_guard", [sys.executable, str(script)], "blocking",
            out_dir, tmp_path,
        )

    def test_infra_error_carries_redacted_stderr_preview(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            result = self._run_guard(
                "import sys\n"
                "sys.stderr.write('boom-marker: ' + __file__ + chr(10))\n"
                "sys.exit(2)\n",
                tmp_path,
            )
            assert result["outcome"] == "infra_error"
            preview = result["stderr_preview"]
            assert "boom-marker" in preview
            assert "<path>" in preview, preview
            assert str(tmp_path) not in preview
            assert "probe_guard.py" not in preview
            assert len(preview) <= 500

    def test_stderr_preview_is_bounded_to_500_chars(self):
        with tempfile.TemporaryDirectory() as tmp:
            result = self._run_guard(
                "import sys\n"
                "sys.stderr.write('y' * 2000 + chr(10))\n"
                "sys.exit(2)\n",
                Path(tmp),
            )
            assert result["outcome"] == "infra_error"
            preview = result["stderr_preview"]
            assert preview
            assert len(preview) <= 500

    def test_pass_and_violation_legs_have_empty_stderr_preview(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            passing = self._run_guard(
                "import sys\n"
                "sys.stderr.write('noisy but passing\\n')\n"
                "sys.exit(0)\n",
                tmp_path,
            )
            assert passing["outcome"] == "pass"
            assert passing["stderr_preview"] == ""
            violating = self._run_guard(
                "import sys\n"
                "sys.stderr.write('noisy violation\\n')\n"
                "sys.exit(1)\n",
                tmp_path,
            )
            assert violating["outcome"] == "violation"
            assert violating["stderr_preview"] == ""

    def test_timeout_leg_carries_redacted_stderr_preview(self, monkeypatch):
        # run_guard reads the module-level budget resolved at import time.
        monkeypatch.setattr(_runner, "GUARD_TIMEOUT_SECONDS", 1)
        with tempfile.TemporaryDirectory() as tmp:
            tmp_path = Path(tmp)
            out_dir = tmp_path / "output"
            out_dir.mkdir()
            script = tmp_path / "slow_guard.py"
            _write_script(script, "import time\ntime.sleep(5)\n")
            result = _runner.run_guard(
                "slow_guard", [sys.executable, str(script)], "blocking",
                out_dir, tmp_path,
            )
            assert result["outcome"] == "infra_error"
            # A timed-out child may have produced no stderr yet; the field
            # must exist and stay bounded either way.
            assert len(result["stderr_preview"]) <= 500


# ── PR-GR-10b: db_artifact_sync guard wiring ──────────────────────────────────


class TestDbArtifactSyncGuardWiring:
    """The PR-GR-10b artifact-sync tripwire is registered and wired.

    The guard's command runs the migrate CLI's --verify mode (tokenized
    argv derived from the registry execution schema) consuming the SAME
    reviewed seed input every generation run uses; the suite's per-guard
    timeout budget bounds the run.  Registry, derived legs, and filesystem
    must stay consistent so hand-edit drift of the tracked
    candidate/accounting artifacts fails every suite run and CI.
    """

    GUARD_NAME = "db_artifact_sync"
    GUARD_SCRIPT = "scripts/migrate_db_policy_signatures.py"
    GUARD_TESTS = "scripts/test_migrate_db_policy_signatures.py"
    SEED_INPUT = "docs/ci/db-findings/GR-08-seeds.yml"

    def _manifest_entry(self):
        for name, command, mode in _derived()[0]:
            if name == self.GUARD_NAME:
                return (name, _relativize_argv(command, REPO_ROOT), mode)
        assert False, f"{self.GUARD_NAME} not found in derived suite legs"

    def test_guard_is_registered_blocking_with_tests_field(self):
        entry = _reg.GUARD_REGISTRY[self.GUARD_NAME]
        assert entry["mode"] == "blocking"
        assert entry["script"] == self.GUARD_SCRIPT
        assert entry["tests"] == self.GUARD_TESTS
        assert entry["description"]

    def test_guard_files_exist_in_repository(self):
        entry = _reg.GUARD_REGISTRY[self.GUARD_NAME]
        repo_root = Path(__file__).resolve().parent.parent.parent
        assert (repo_root / entry["script"]).is_file()
        assert (repo_root / entry["tests"]).is_file()
        for policy in entry["policies"]:
            assert (repo_root / policy).is_file(), policy

    def test_guard_is_in_ci_manifest(self):
        name, _command, mode = self._manifest_entry()
        assert name == self.GUARD_NAME
        assert mode == "blocking"

    def test_manifest_command_is_tokenized_verify_argv(self):
        """The command is a token list running the migrate CLI --verify."""
        _name, command, _mode = self._manifest_entry()
        assert isinstance(command, list), "command must be a list of tokens"
        assert self.GUARD_SCRIPT in command, command
        assert "--verify" in command, command

    def test_manifest_command_consumes_reviewed_seed_input(self):
        """The verify argv passes the combined reviewed seed document.

        The R12 lesson: a seed-less regeneration can never match the
        seeded tracked artifacts (472 entries = 57 legacy-resolved + 415
        seed rows), so the tripwire MUST consume the same --seed-rows
        input as generation or it would report permanent drift.
        """
        _name, command, _mode = self._manifest_entry()
        assert "--seed-rows" in command, command
        seed_index = command.index("--seed-rows")
        assert command[seed_index + 1] == self.SEED_INPUT, command

    def test_manifest_command_writes_nothing(self):
        """The verify argv must not request artifact writes.

        --verify never writes the tracked artifacts; the argv must not
        contain --generate/--write-candidate/--check or an output target.
        """
        _name, command, _mode = self._manifest_entry()
        joined = command
        for forbidden in ("--generate", "--write-candidate", "--check"):
            assert forbidden not in joined, command
        assert "--output" not in joined, command
        assert "--accounting-out" not in joined, command
        assert "--write-accounting" not in joined, command

    def test_time_budget_declared(self):
        budgets = _runner.GUARD_TIME_BUDGETS
        assert self.GUARD_NAME in budgets
        assert budgets[self.GUARD_NAME] > 0

    def test_registry_consistency_validation_passes(self):
        repo_root = Path(__file__).resolve().parent.parent.parent
        assert _reg.validate_registry(str(repo_root)) == []


# ── PR-GR-10A Slice 2: registry-derived suite plan ──────────────────────────────

if str(CI_DIR) not in sys.path:
    sys.path.insert(0, str(CI_DIR))

import guard_execution_plan as gep  # noqa: E402
import verify_guard_registry as vgr  # noqa: E402

_INTERPRETER_PLACEHOLDER = "<resolved-interpreter>"
_RATCHET_GUARDS = frozenset({
    "cancellation", "privacy", "db_access", "event_writers", "money",
    "migration_matrix",
})
_INFRA_LEGS = frozenset({"guard_registry", "guard_tests"})

# Verbatim pre-migration GUARD_MANIFEST, recorded before the PR-GR-10A
# Slice 2 suite migration.  This fixture is the equivalence oracle: the
# registry-derived suite plan must be semantically equal to it for EVERY
# guard (semantic command equality per the GR-10A plan definition: same
# guard id, mode, executable identity, script, normalized child tokens,
# policies, baseline, protocol, timeout semantics, and required inputs —
# absolute path spelling may differ).
#
# The db_access --timeout token is the pre-migration derivation at the
# default suite budget: max(1500 - 60, 600) = 1440.  The comparison test
# freezes GUARD_TIMEOUT_SECONDS to the default so the derived token matches.
# The guard_tests interpreter token was SUITE_PYTHON (== sys.executable).
#
# Slice-3 addition: raw_money_aggregates is a PR-GR-10A Slice 3 NEW guard,
# absent from the verbatim pre-migration manifest.  It is pinned inline
# below at its registry-order position — directly after money, per the
# GUARD_REGISTRY execution order mirrored by SUITE_GUARD_ORDER — and is
# clearly marked so the verbatim pre-migration entries stay identifiable.
LEGACY_GUARD_MANIFEST_FIXTURE: List[Tuple[str, List[str], str]] = [
    ("guard_registry", ["python3", "scripts/ci/verify_guard_registry.py"], "blocking"),
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
    (
        "db_artifact_sync",
        [
            "python3", "scripts/migrate_db_policy_signatures.py",
            "--verify",
            "--seed-rows", "docs/ci/db-findings/GR-08-seeds.yml",
        ],
        "blocking",
    ),
    ("known_good_state", ["python3", "scripts/ci/verify_known_good_state.py"], "blocking"),
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
            "--timeout=1440",
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
    # ── Slice-3 addition (NOT verbatim pre-migration) ─────────────────────────
    # raw_money_aggregates is a PR-GR-10A Slice 3 NEW guard absent from the
    # pre-migration manifest.  It joins the canonical suite at its
    # registry-order position (directly after money), so the derived leg
    # sequence matches.  Semantics pinned from the raw_money_aggregates
    # registry execution section: engine python-direct, entrypoint
    # scripts/verify_raw_money_aggregates.py, arguments
    # ("--fail-on-violation",), mode blocking, no baseline (a direct guard).
    (
        "raw_money_aggregates",
        ["python3", "scripts/verify_raw_money_aggregates.py", "--fail-on-violation"],
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
    ("guard_tests", [sys.executable, "-m", "pytest", "scripts/test_verify_*.py", "scripts/ci/test_*.py", "-v", "--tb=short"], "blocking"),
]

# Verbatim pre-migration GUARD_TIME_BUDGETS (the named timeout profiles must
# reproduce these exactly via the compiler).
LEGACY_GUARD_TIME_BUDGETS_FIXTURE = {
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
    "db_artifact_sync": 600.0,
    "known_good_state": 1200.0,
    "cancellation": 300.0,
    "privacy": 300.0,
    "db_access": 840.0,
    "event_writers": 300.0,
    "money": 300.0,
    "migration_matrix": 300.0,
    "guard_tests": 1800.0,
}

# PR-GR-10A Slice 3 budget addition: the raw_money_aggregates leg itself is
# pinned inline in LEGACY_GUARD_MANIFEST_FIXTURE at its registry-order
# position (see the Slice-3 addition marker there), so the derived leg
# sequence compares equal without a separate command list.  Its standard
# timeout profile budget (300s) is recorded here so the derived budget
# table — which must cover every canonical guard — compares equal.
# Declared-external registry entries (currency_guardrails_ps,
# release_artifact) are deliberately NOT in the canonical suite plan by
# design (plan Step 5: "unless declared external").
POST_SLICE2_SUITE_BUDGET_ADDITIONS: Dict[str, float] = {
    "raw_money_aggregates": 300.0,
}


def _norm_token(token: str, root: Path) -> str:
    """Normalize one argv token into the semantic comparison form.

    Interpreter identities (bare python names or the resolved runtime) become
    a placeholder; absolute paths under the repo root become repo-relative.
    """
    if token in ("python", "python3", sys.executable):
        return _INTERPRETER_PLACEHOLDER
    if token.startswith("--command-arg="):
        return "--command-arg=" + _norm_token(token.split("=", 1)[1], root)
    return _rel_token(token, root)


def _semantic_ratchet(argv: List[str], root: Path, default_protocol: int) -> dict:
    """Semantic form of a ratchet outer argv (either spelling)."""
    parsed = {
        "kind": "ratchet",
        "ratchetScript": None,
        "guardName": None,
        "baseline": None,
        "protocol": default_protocol,
        "childTimeout": None,
        "failOnViolation": False,
        "ciMode": False,
        "childArgv": [],
    }
    assert argv[0] in ("python", "python3", sys.executable), f"not an interpreter: {argv[0]!r}"
    index = 1
    while index < len(argv):
        token = argv[index]
        if "guard_ratchet.py" in token:
            parsed["ratchetScript"] = os.path.basename(token)
            index += 1
        elif token == "--guard-name":
            parsed["guardName"] = argv[index + 1]
            index += 2
        elif token == "--baseline":
            parsed["baseline"] = _rel_token(argv[index + 1], root)
            index += 2
        elif token.startswith("--finding-protocol="):
            parsed["protocol"] = int(token.split("=", 1)[1])
            index += 1
        elif token.startswith("--timeout="):
            parsed["childTimeout"] = int(token.split("=", 1)[1])
            index += 1
        elif token == "--fail-on-violation":
            parsed["failOnViolation"] = True
            index += 1
        elif token == "--ci-mode":
            parsed["ciMode"] = True
            index += 1
        elif token == "--command":
            # Legacy shell string: the ratchet tokenizes it shell-free, so
            # whitespace splitting reproduces the executed child argv.
            parts = argv[index + 1].split()
            parsed["childArgv"] = [_norm_token(part, root) for part in parts]
            index += 2
        elif token.startswith("--command-arg="):
            parsed["childArgv"].append(_norm_token(token.split("=", 1)[1], root))
            index += 1
        else:
            raise AssertionError(f"unrecognized ratchet token: {token!r}")
    return parsed


def _semantic_direct(argv: List[str], root: Path) -> dict:
    """Semantic form of a direct-guard outer argv (either spelling)."""
    assert argv[0] in ("python", "python3", sys.executable), f"not an interpreter: {argv[0]!r}"
    return {
        "kind": "direct",
        "script": _rel_token(argv[1], root),
        "args": [_norm_token(token, root) for token in argv[2:]],
    }


def _fixture_protocol(guard_name: str) -> int:
    """The protocol the ratchet resolves for a pre-migration manifest entry
    (registry lookup, legacy default 1)."""
    return int(_reg.GUARD_REGISTRY[guard_name].get("finding_protocol", 1))


class TestDerivedPlanEqualsLegacyManifest:
    """PR-GR-10A Slice 2 equivalence proof against the recorded fixture."""

    def test_every_guard_semantically_equal_to_pre_migration_manifest(
        self, monkeypatch
    ):
        monkeypatch.delenv("GUARD_TIMEOUT_SECONDS", raising=False)
        fresh = _load_fresh_runner(f"equivalence_{id(self)}")
        legs, _budgets, _plans, errors = fresh._derive_default_suite_plan(REPO_ROOT)
        assert not errors, errors

        expected_manifest = list(LEGACY_GUARD_MANIFEST_FIXTURE)
        fixture_names = [
            name for name, _command, _mode in expected_manifest
        ]
        derived_names = [name for name, _command, _mode in legs]
        assert derived_names == fixture_names, (
            "derived suite legs must execute exactly the recorded guard "
            "sequence (the pre-migration manifest plus the pinned inline "
            "Slice-3 addition at its registry-order position), in order"
        )

        for (d_name, d_argv, d_mode), (f_name, f_argv, f_mode) in zip(
            legs, expected_manifest
        ):
            assert d_name == f_name
            assert d_mode == f_mode, f_name
            if f_name in _INFRA_LEGS:
                # Infrastructure legs are suite-owned and byte-identical.
                assert d_argv == f_argv, f_name
            elif f_name in _RATCHET_GUARDS:
                protocol = _fixture_protocol(f_name)
                derived = _semantic_ratchet(d_argv, REPO_ROOT, protocol)
                legacy = _semantic_ratchet(f_argv, REPO_ROOT, protocol)
                assert derived == legacy, (
                    f"{f_name}: derived plan differs from the pre-migration "
                    f"manifest:\n  derived={derived}\n  legacy={legacy}"
                )
            else:
                derived = _semantic_direct(d_argv, REPO_ROOT)
                legacy = _semantic_direct(f_argv, REPO_ROOT)
                assert derived == legacy, (
                    f"{f_name}: derived plan differs from the pre-migration "
                    f"manifest:\n  derived={derived}\n  legacy={legacy}"
                )

    def test_derived_budgets_equal_pre_migration_budgets(self, monkeypatch):
        """Named timeout profiles reproduce the pre-migration budget table."""
        monkeypatch.delenv("GUARD_TIMEOUT_SECONDS", raising=False)
        fresh = _load_fresh_runner(f"equivalence_budgets_{id(self)}")
        _legs, budgets, _plans, errors = fresh._derive_default_suite_plan(REPO_ROOT)
        assert not errors, errors
        expected_budgets = dict(LEGACY_GUARD_TIME_BUDGETS_FIXTURE)
        expected_budgets.update(POST_SLICE2_SUITE_BUDGET_ADDITIONS)
        assert budgets == expected_budgets

    def test_derived_plans_are_canonical_and_clean(self):
        """Every derived plan uses the resolved interpreter and tokenized
        argv — no bare python, no shell metacharacters, no legacy --command."""
        _legs, _budgets, plans = _derived()
        compilable = [
            guard_id for guard_id in _reg.GUARD_REGISTRY
            if guard_id not in vgr.declared_external_guard_ids()
        ]
        assert len(plans) == len(compilable)
        for plan in plans:
            argv = list(plan.outer_argv)
            if plan.child_argv is not None:
                argv.extend(plan.child_argv)
            for token in argv:
                assert token not in ("python", "python3"), (
                    f"{plan.guard_id}: bare interpreter token in canonical plan"
                )
                for metachar in (";", "|", "&", "`", "$", "\n", "\r"):
                    assert metachar not in token, (
                        f"{plan.guard_id}: shell metacharacter in token"
                    )
            assert "--command" not in plan.outer_argv


class TestSlice3SuiteAdditionsAndExternalExclusion:
    """PR-GR-10A Slice 3: the extracted raw_money_aggregates guard joins the
    canonical suite; declared-external registry entries are excluded from the
    suite plan by design and rejected by single-guard compilation."""

    def test_raw_money_aggregates_leg_is_blocking_with_canonical_command(self):
        # _derived() returns (legs, budgets, plans) and asserts no errors.
        legs, budgets, _plans = _derived()
        matching = [
            (name, command, mode) for name, command, mode in legs
            if name == "raw_money_aggregates"
        ]
        assert len(matching) == 1
        name, command, mode = matching[0]
        assert mode == "blocking"
        derived = _semantic_direct(command, REPO_ROOT)
        assert derived == {
            "kind": "direct",
            "script": "scripts/verify_raw_money_aggregates.py",
            "args": ["--fail-on-violation"],
        }
        assert budgets["raw_money_aggregates"] == 300.0

    def test_declared_external_guards_are_excluded_from_derived_legs(self):
        # _derived() returns (legs, budgets, plans) and asserts no errors.
        legs, _budgets, plans = _derived()
        leg_names = {name for name, _command, _mode in legs}
        plan_ids = {plan.guard_id for plan in plans}
        for external in ("currency_guardrails_ps", "release_artifact"):
            assert external in _reg.GUARD_REGISTRY
            assert external not in leg_names, external
            assert external not in plan_ids, external

    def test_external_exclusion_is_a_warning_diagnostic_not_an_error(self):
        context = gep.ExecutionContext(
            repo_root=str(REPO_ROOT),
            interpreter_path=sys.executable,
            ci_mode=False,
        )
        specs, load_diags = gep.load_guard_specs(gep.DEFAULT_REGISTRY_PATH)
        assert not [d for d in load_diags if d.severity == "error"]
        _plans, diags = gep.compile_static_suite_plan(context, specs=specs)
        for external in ("currency_guardrails_ps", "release_artifact"):
            warnings = [
                d for d in diags
                if d.guard_id == external
                and d.code == "E_ENGINE_EXTERNAL_SKIPPED"
            ]
            assert len(warnings) == 1, (external, warnings)
            assert warnings[0].severity == "warning"
        assert not [d for d in diags if d.severity == "error"], diags

    def test_external_guard_single_guard_compile_is_rejected(self):
        """The Python runner bridge cannot execute a declared-external guard:
        single-guard compilation fails closed with E_ENGINE_NOT_COMPILABLE."""
        context = gep.ExecutionContext(
            repo_root=str(REPO_ROOT),
            interpreter_path=sys.executable,
            ci_mode=False,
        )
        plan, diags = gep.compile_guard_plan("currency_guardrails_ps", context)
        assert plan is None
        assert any(d.code == "E_ENGINE_NOT_COMPILABLE" for d in diags)

    def test_validator_excludes_declared_external_from_required_plan(self):
        _legs, _budgets, plans = _derived()
        registry_order = list(_reg.GUARD_REGISTRY.keys())
        assert vgr.validate_compiled_suite_plan(plans, registry_order) == []
        # An external guard is NOT reported missing when absent from the plan.
        assert not any(
            "currency_guardrails_ps" in error
            for error in vgr.validate_compiled_suite_plan(plans, registry_order)
        )


class TestSuitePlanEvidence:
    """PR-GR-10A deliverable 4: plan evidence written per default-path run."""

    def _evidence(self, tmp_path):
        _legs, _budgets, plans = _derived()
        _runner._write_plan_evidence(plans, tmp_path)
        plan_bytes = (tmp_path / "execution-plan.json").read_bytes()
        plan_doc = json.loads(plan_bytes.decode("utf-8"))
        inputs_doc = json.loads(
            (tmp_path / "effective-inputs.json").read_text(encoding="utf-8")
        )
        return plan_bytes, plan_doc, inputs_doc

    def test_evidence_files_written_and_deterministic(self, tmp_path):
        plan_bytes, plan_doc, inputs_doc = self._evidence(tmp_path)
        assert plan_doc["schemaVersion"] == 1
        assert inputs_doc["schemaVersion"] == 1
        _runner._write_plan_evidence(_derived()[2], tmp_path)
        assert (tmp_path / "execution-plan.json").read_bytes() == plan_bytes
        assert not list(tmp_path.glob("*.tmp")), "atomic write left residue"

    def test_sha256_sidecar_matches_plan_bytes(self, tmp_path):
        plan_bytes, _plan_doc, _inputs_doc = self._evidence(tmp_path)
        sidecar = (tmp_path / "execution-plan.sha256").read_text(encoding="utf-8")
        digest = sidecar.split()[0]
        assert digest == hashlib.sha256(plan_bytes).hexdigest()
        assert "execution-plan.json" in sidecar

    def test_evidence_is_path_free(self, tmp_path):
        """No machine-specific absolute spellings (privacy: no user home).

        Covers BOTH evidence files: execution-plan.json (whose ratchet outer
        argv repeats the interpreter as --command-arg values — the R16-2d
        leak) and effective-inputs.json.
        """
        plan_bytes, _plan_doc, _inputs_doc = self._evidence(tmp_path)
        plan_text = plan_bytes.decode("utf-8")
        inputs_text = (tmp_path / "effective-inputs.json").read_text(
            encoding="utf-8"
        )
        for text in (plan_text, inputs_text):
            assert str(REPO_ROOT) not in text
            assert sys.executable not in text
            assert "\\Users" not in text
        # The ratchet interpreter repetition is normalized to the placeholder:
        # every --command-arg value is either the interpreter placeholder or
        # a repo-relative spelling — never another absolute path.
        plan_doc = json.loads(plan_text)
        for guard in plan_doc["guards"]:
            for token in guard["resolvedOuterArgv"]:
                if token.startswith("--command-arg="):
                    value = token.split("=", 1)[1]
                    assert (
                        value == "<resolved-interpreter>"
                        or not os.path.isabs(value)
                    ), token

    def test_per_guard_record_carries_deliverable_four_fields(self, tmp_path):
        _plan_bytes, plan_doc, _inputs_doc = self._evidence(tmp_path)
        by_id = {guard["guardId"]: guard for guard in plan_doc["guards"]}
        external = vgr.declared_external_guard_ids()
        assert set(by_id) == set(_reg.GUARD_REGISTRY) - external
        required_fields = {
            "guardId", "mode", "engine", "resolvedOuterArgv",
            "resolvedChildArgv", "interpreter", "timeoutSeconds",
            "requiredInputs", "inputHashes", "baselinePath",
            "baselineSha256", "findingProtocol",
        }
        for guard in plan_doc["guards"]:
            assert required_fields <= set(guard), guard["guardId"]
        db = by_id["db_access"]
        assert db["timeoutSeconds"] == 840  # D4 profile
        assert db["findingProtocol"] == 2
        assert db["baselinePath"] == "config/baselines/db_access_v2.json"
        assert db["baselineSha256"]
        assert db["resolvedChildArgv"][0] == _INTERPRETER_PLACEHOLDER
        direct = by_id["time_boundaries"]
        assert direct["resolvedChildArgv"] is None
        assert direct["findingProtocol"] is None
        assert direct["baselinePath"] is None
        assert direct["timeoutSeconds"] == 300  # standard profile

    def test_effective_inputs_hashes_match_files(self, tmp_path):
        _plan_bytes, _plan_doc, inputs_doc = self._evidence(tmp_path)
        by_id = {guard["guardId"]: guard for guard in inputs_doc["guards"]}
        db = by_id["db_access"]
        assert db["requiredInputs"] == [
            "config/guards/db_ownership_policy.yml",
            "config/guards/db_structural_exceptions.yml",
            "config/guards/db_structural_exceptions_expected_methods.yml",
            "config/guards/production_source_roots.yml",
        ]
        for relative, digest in db["inputHashes"].items():
            absolute = REPO_ROOT / relative
            assert digest == hashlib.sha256(absolute.read_bytes()).hexdigest()


class TestSuiteMainWritesEvidence:
    """main() writes plan evidence on the default path only."""

    @staticmethod
    def _fake_result(name, mode, output_dir, expected_max_seconds):
        return {
            "name": name,
            "mode": mode,
            "exit_code": 0,
            "outcome": "pass",
            "duration_seconds": 0.1,
            "expected_max_seconds": expected_max_seconds,
            "budget_exceeded": False,
            "log_path": str(Path(output_dir) / f"{name}.log"),
            "stdout_preview": "",
        }

    def test_main_writes_plan_evidence_and_summary(self, monkeypatch, tmp_path):
        out_dir = tmp_path / "out"

        def fake_run_guard(name, command, mode, output_dir, project_root,
                           expected_max_seconds=None):
            return self._fake_result(name, mode, output_dir, expected_max_seconds)

        monkeypatch.setattr(_runner, "run_guard", fake_run_guard)
        monkeypatch.setattr(
            sys, "argv", [str(RUNNER_SCRIPT), "--output-dir", str(out_dir)]
        )
        with pytest.raises(SystemExit) as excinfo:
            _runner.main()
        assert excinfo.value.code == 0
        for filename in (
            "execution-plan.json", "execution-plan.sha256",
            "effective-inputs.json", "summary.json", "summary.md",
        ):
            assert (out_dir / filename).exists(), filename

    def test_main_custom_manifest_writes_no_plan_evidence(self, monkeypatch, tmp_path):
        out_dir = tmp_path / "out"
        manifest_path = tmp_path / "manifest.json"
        _write_manifest(manifest_path, [
            {"name": "only", "command": [sys.executable, "-c", "pass"],
             "mode": "blocking"},
        ])

        def fake_run_guard(name, command, mode, output_dir, project_root,
                           expected_max_seconds=None):
            return self._fake_result(name, mode, output_dir, expected_max_seconds)

        monkeypatch.setattr(_runner, "run_guard", fake_run_guard)
        monkeypatch.setattr(sys, "argv", [
            str(RUNNER_SCRIPT), "--manifest", str(manifest_path),
            "--output-dir", str(out_dir),
        ])
        with pytest.raises(SystemExit) as excinfo:
            _runner.main()
        assert excinfo.value.code == 0
        assert (out_dir / "summary.json").exists()
        assert not (out_dir / "execution-plan.json").exists()


class TestDerivedSuitePlanFailClosed:
    """Derivation fails closed with bounded errors — no command-list fallback."""

    def test_compile_diagnostic_fails_derivation(self, monkeypatch):
        fresh = _load_fresh_runner(f"failclosed_compile_{id(self)}")

        def broken(context, specs=None):
            return (), (
                gep.PlanDiagnostic(
                    code="E_SYNTHETIC", guard_id="db_access",
                    context="bounded synthetic failure", severity="error",
                ),
            )

        monkeypatch.setattr(fresh, "compile_static_suite_plan", broken)
        legs, _budgets, plans, errors = fresh._derive_default_suite_plan(REPO_ROOT)
        assert legs == [] and plans == []
        assert errors and "E_SYNTHETIC" in errors[0]

    def test_suite_order_gap_fails_derivation(self, monkeypatch):
        fresh = _load_fresh_runner(f"failclosed_order_{id(self)}")
        monkeypatch.setattr(
            fresh, "SUITE_GUARD_ORDER", fresh.SUITE_GUARD_ORDER[:-1]
        )
        legs, _budgets, _plans, errors = fresh._derive_default_suite_plan(REPO_ROOT)
        assert legs == []
        assert any("E_SUITE_ORDER_GAP" in error for error in errors)
        assert any("migration_matrix" in error for error in errors)

    def test_unknown_order_name_fails_derivation(self, monkeypatch):
        fresh = _load_fresh_runner(f"failclosed_order2_{id(self)}")
        monkeypatch.setattr(
            fresh, "SUITE_GUARD_ORDER", fresh.SUITE_GUARD_ORDER + ("no_such_guard",)
        )
        legs, _budgets, _plans, errors = fresh._derive_default_suite_plan(REPO_ROOT)
        assert legs == []
        assert any("E_SUITE_ORDER_GAP" in error for error in errors)


class TestCompatDerivedManifestView:
    """PEP 562 derived compatibility views keep pre-migration consumers working.

    Mirrors the exact assertions the pre-migration consumers make
    (test_verify_known_good_state.py, test_verify_deprecation_escalations.py)
    against the derived view — updated to the derived-plan reality without
    weakening.
    """

    def test_known_good_state_legacy_command_exact(self):
        for name, command, mode in _runner.GUARD_MANIFEST:
            if name == "known_good_state":
                assert mode == "blocking"
                assert command == [
                    "python3", "scripts/ci/verify_known_good_state.py"
                ]
                break
        else:
            assert False, "known_good_state missing from compatibility view"

    def test_manifest_names_match_registry_exactly(self):
        infra_names = {"guard_tests", "guard_registry"}
        external_names = vgr.declared_external_guard_ids()
        manifest_names = {
            name for name, _command, _mode in _runner.GUARD_MANIFEST
            if name not in infra_names
        }
        expected = set(_reg.GUARD_REGISTRY) - external_names
        assert manifest_names == expected

    def test_deprecation_escalations_present(self):
        manifest_names = [name for name, _command, _mode in _runner.GUARD_MANIFEST]
        assert "deprecation_escalations" in manifest_names

    def test_time_budgets_view_known_good_state(self):
        budgets = _runner.GUARD_TIME_BUDGETS
        assert budgets["known_good_state"] >= 900

    def test_unknown_attribute_still_raises(self):
        with pytest.raises(AttributeError):
            _runner.definitely_not_a_real_attribute  # noqa: B018


class TestVerifyGuardRegistryDirection:
    """PR-GR-10A Step 5: the validator owns plan validation, never the suite."""

    def test_validator_never_imports_the_suite(self):
        source = (CI_DIR / "verify_guard_registry.py").read_text(encoding="utf-8")
        tree = ast.parse(source)
        imported = set()
        for node in ast.walk(tree):
            if isinstance(node, ast.Import):
                imported.update(alias.name for alias in node.names)
            elif isinstance(node, ast.ImportFrom):
                imported.add(node.module or "")
        assert not any(
            "run_static_guard_suite" in name for name in imported
        ), imported

    def test_suite_source_has_no_legacy_manifest_assignment(self):
        source = (CI_DIR / "run_static_guard_suite.py").read_text(encoding="utf-8")
        assert vgr.forbidden_manifest_assignments(source) == []
        assert vgr.forbidden_manifest_assignments("GUARD_MANIFEST = []\n") == [
            "GUARD_MANIFEST"
        ]
        assert vgr.forbidden_manifest_assignments(
            "GUARD_TIME_BUDGETS: Dict[str, float] = {}\n"
        ) == ["GUARD_TIME_BUDGETS"]
        assert vgr.forbidden_manifest_assignments("SUITE_GUARD_ORDER = ()\n") == []

    def test_suite_source_wires_the_compiler(self):
        source = (CI_DIR / "run_static_guard_suite.py").read_text(encoding="utf-8")
        assert vgr.suite_references_compiler(source)
        assert not vgr.suite_references_compiler("x = 1\n")

    def test_validator_passes_on_the_real_tree(self):
        result = subprocess.run(
            [sys.executable, str(CI_DIR / "verify_guard_registry.py"),
             "--root", str(REPO_ROOT)],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=180,
        )
        assert result.returncode == 0, result.stdout + result.stderr

    def test_compiled_plan_coverage_validation(self):
        _legs, _budgets, plans = _derived()
        registry_order = list(_reg.GUARD_REGISTRY.keys())
        assert vgr.validate_compiled_suite_plan(plans, registry_order) == []
        assert vgr.validate_compiled_suite_plan(plans[:-1], registry_order)
        assert vgr.validate_compiled_suite_plan([], registry_order)

    def test_compiled_plan_missing_ratchet_guard_is_reported(self):
        _legs, _budgets, plans = _derived()
        registry_order = list(_reg.GUARD_REGISTRY.keys())
        stripped = [plan for plan in plans if plan.guard_id != "db_access"]
        errors = vgr.validate_compiled_suite_plan(stripped, registry_order)
        assert any("db_access" in error and "missing" in error for error in errors)
