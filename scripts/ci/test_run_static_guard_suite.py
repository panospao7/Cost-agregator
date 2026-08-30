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

Run:
  python -m pytest scripts/ci/test_run_static_guard_suite.py -v
"""

import json
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import List

import pytest

# Import the runner to inspect GUARD_MANIFEST directly
import importlib.util
_runner_spec = importlib.util.spec_from_file_location(
    "run_static_guard_suite",
    Path(__file__).resolve().parent / "run_static_guard_suite.py",
)
_runner = importlib.util.module_from_spec(_runner_spec)
_runner_spec.loader.exec_module(_runner)


# ── Helpers ─────────────────────────────────────────────────────────────────────

RUNNER_SCRIPT = Path(__file__).resolve().parent / "run_static_guard_suite.py"


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
    """Verify release_artifact is NOT in the default GUARD_MANIFEST.

    The Static Guards job runs from a source checkout and has no APK.
    The release_artifact verification runs in the release-check CI job
    after assembleRelease, not here.
    """

    def test_artifact_guard_not_in_manifest(self):
        guard_names = [name for name, _, _ in _runner.GUARD_MANIFEST]
        assert "release_artifact" not in guard_names, (
            f"release_artifact should not be in GUARD_MANIFEST; "
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
    """The guard_tests entry must resolve its interpreter portably.

    The manifest entry must run pytest under the interpreter running the
    suite (sys.executable) instead of a bare "python3" PATH lookup, which
    may not resolve on Windows. What is tested (pytest targets and flags)
    must remain unchanged.
    """

    def _guard_tests_command(self):
        for name, command, _ in _runner.GUARD_MANIFEST:
            if name == "guard_tests":
                return command
        assert False, "guard_tests not found in GUARD_MANIFEST"

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
    """Verify the DB guard suite entry uses protocol-v2 --command-arg tokens."""

    def test_db_access_command_is_list_of_tokens(self):
        """The db_access manifest entry must use --command-arg token list."""
        db_entry = None
        for name, command, mode in _runner.GUARD_MANIFEST:
            if name == "db_access":
                db_entry = (name, command, mode)
                break
        assert db_entry is not None, "db_access not found in GUARD_MANIFEST"
        _, command, _ = db_entry
        assert isinstance(command, list), "command must be a list of tokens"

    def test_db_access_uses_guard_ratchet(self):
        """The db_access command must invoke guard_ratchet.py."""
        for name, command, _ in _runner.GUARD_MANIFEST:
            if name == "db_access":
                assert any("guard_ratchet.py" in tok for tok in command), (
                    f"guard_ratchet.py not in command tokens: {command}"
                )
                return
        assert False, "db_access not found in GUARD_MANIFEST"

    def test_db_access_no_legacy_command_string(self):
        """The db_access entry must NOT use the legacy --command shell string."""
        for name, command, _ in _runner.GUARD_MANIFEST:
            if name == "db_access":
                # Must not have a "--command" token (legacy form)
                assert "--command" not in command, (
                    f"db_access still uses legacy --command: {command}"
                )
                return
        assert False, "db_access not found in GUARD_MANIFEST"

    def test_db_access_uses_command_arg_tokens(self):
        """The db_access entry must use --command-arg tokens."""
        for name, command, _ in _runner.GUARD_MANIFEST:
            if name == "db_access":
                assert "--command-arg=python" in command, (
                    f"--command-arg=python not in command: {command}"
                )
                db_script_arg = None
                for tok in command:
                    if "verify_db_access_boundaries.py" in tok:
                        db_script_arg = tok
                        break
                assert db_script_arg is not None, (
                    f"verify_db_access_boundaries.py not in command tokens: {command}"
                )
                return
        assert False, "db_access not found in GUARD_MANIFEST"

    def test_db_access_includes_structural_manifest(self):
        """The db_access command must include --structural-manifest."""
        for name, command, _ in _runner.GUARD_MANIFEST:
            if name == "db_access":
                assert "--command-arg=--structural-manifest" in command, (
                    f"--structural-manifest not in command tokens: {command}"
                )
                return
        assert False, "db_access not found in GUARD_MANIFEST"

    def test_db_access_includes_canonical_policies(self):
        """The db_access command must include canonical policy paths."""
        for name, command, _ in _runner.GUARD_MANIFEST:
            if name == "db_access":
                cmd_str = " ".join(command)
                assert "db_ownership_policy.yml" in cmd_str, (
                    f"ownership policy not in command: {command}"
                )
                assert "db_structural_exceptions.yml" in cmd_str, (
                    f"structural exceptions not in command: {command}"
                )
                return
        assert False, "db_access not found in GUARD_MANIFEST"

    def test_db_access_baseline_path_is_v2(self):
        """The db_access ratchet command must pin the v2 baseline (GR-09)."""
        for name, command, _ in _runner.GUARD_MANIFEST:
            if name == "db_access":
                assert "--baseline" in command, (
                    f"--baseline not in command tokens: {command}"
                )
                assert "config/baselines/db_access_v2.json" in command, (
                    f"v2 baseline path not in command tokens: {command}"
                )
                assert "config/baselines/db_access.json" not in command, (
                    f"legacy v1 baseline path still in command tokens: {command}"
                )
                return
        assert False, "db_access not found in GUARD_MANIFEST"

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
        for name, command, _ in _runner.GUARD_MANIFEST:
            if name == "db_access":
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
                return
        assert False, "db_access not found in GUARD_MANIFEST"

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
        for name, command, _ in _runner.GUARD_MANIFEST:
            if name == "db_access":
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
                return
        assert False, "db_access not found in GUARD_MANIFEST"

    def test_db_access_mode_is_blocking(self):
        """The db_access entry must be blocking mode."""
        for name, _, mode in _runner.GUARD_MANIFEST:
            if name == "db_access":
                assert mode == "blocking", f"Expected blocking, got {mode}"
                return
        assert False, "db_access not found in GUARD_MANIFEST"

    def test_all_other_guards_preserved(self):
        """All non-db_access guards must still be present and unchanged."""
        guard_names = [name for name, _, _ in _runner.GUARD_MANIFEST]
        expected = [
            "guard_registry", "source_provenance", "ui_dao", "worker",
            "receipt_link", "import_lifecycle", "cloud_payload",
            "pii_logging", "di_release", "allowlist_compliance",
            "ignored_test_budget", "lint_baseline_policy", "time_boundaries",
            "cancellation", "privacy", "db_access", "event_writers",
            "money", "migration_matrix", "guard_tests",
        ]
        for name in expected:
            assert name in guard_names, f"Guard '{name}' missing from manifest"


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
        spec = importlib.util.spec_from_file_location(
            f"run_static_guard_suite_fresh_db_timeout_{id(self)}",
            RUNNER_SCRIPT,
        )
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        return module

    def _db_access_command(self, module):
        for name, command, _ in module.GUARD_MANIFEST:
            if name == "db_access":
                return command
        assert False, "db_access not found in GUARD_MANIFEST"

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

    def test_builtin_budgets_declared_for_every_manifest_guard(self):
        """Every built-in guard declares a budget; the known-expensive two
        carry headroom over their observed durations (db_access ~700s,
        guard_tests ~1500s)."""
        budgets = _runner.GUARD_TIME_BUDGETS
        for name, _command, _mode in _runner.GUARD_MANIFEST:
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
