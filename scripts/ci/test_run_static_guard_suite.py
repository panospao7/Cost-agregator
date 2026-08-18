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

    def test_baseline_still_v1_non_migrated(self):
        """db_access baseline must still be the v1 (non-migrated) file."""
        entry = _reg.GUARD_REGISTRY["db_access"]
        assert entry.get("baseline") == "config/baselines/db_access.json"


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
