#!/usr/bin/env python3
"""
test_run_registered_guard.py

Pytest tests for the PR-GR-10A Slice 2 registered execution adapter
(scripts/ci/run_registered_guard.py) — the one runtime bridge between the
registry-derived execution plan and process execution.

Coverage:
  1. Happy path per engine: python-direct (real subprocess, exit 0) and
     python-ratchet (end-to-end through a copied guard_ratchet.py with an
     empty protocol-v1 baseline).
  2. Exit-code preservation: child 0/1/2 pass through exactly; an
     unexpected child exit maps to infra (2) with the raw code recorded.
  3. Fail-closed configuration: unknown guard, compile diagnostics, missing
     required inputs, unsupported context, invalid root, overrides in CI
     mode, overrides outside the root, unknown override keys, registry
     override in CI mode — all exit 2 with controlled failure codes.
  4. Summary contract: bounded shape, controlled codes only, no paths, no
     argv, no stdout content.
  5. Baseline safety: the adapter never creates or updates a baseline (the
     compiled argv carries no --update-baseline/--propose-baseline and the
     baseline file stays byte-identical across runs).

Run:
  python -m pytest scripts/ci/test_run_registered_guard.py -v
"""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Dict, Tuple

import pytest

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import run_registered_guard as rgr  # noqa: E402

CI_DIR = Path(_SCRIPT_DIR)
ADAPTER_SCRIPT = CI_DIR / "run_registered_guard.py"

SUMMARY_KEYS = {
    "schemaVersion", "guardId", "context", "ciMode", "exitCode",
    "childExitCode", "outcome", "durationSeconds", "failureCodes",
    "timestamp",
}


# ── Fixture helpers ─────────────────────────────────────────────────────────────


def _write_registry(path: Path, guards: dict) -> str:
    """Write a temporary registry module and return its path."""
    lines = ["GUARD_REGISTRY = {"]
    for guard_id, entry in guards.items():
        lines.append(f"    {guard_id!r}: {entry!r},")
    lines.append("}")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return str(path)


def _direct_entry(**execution_overrides) -> dict:
    """A minimal grounded python-direct registry entry."""
    entry = {
        "script": "scripts/verify_mini.py",
        "tests": None,
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,
        "policies": None,
        "description": "mini direct guard fixture",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/verify_mini.py",
            "arguments": ("--allowlist", "config/mini_allowlist.yml"),
            "mode": "blocking",
            "requiredInputs": ("config/mini_allowlist.yml",),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": "none",
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    }
    entry["execution"].update(execution_overrides)
    return entry


def _ratchet_entry() -> dict:
    """A minimal grounded python-ratchet registry entry (protocol v1)."""
    return {
        "script": "scripts/verify_mini_ratchet.py",
        "tests": None,
        "mode": "ratchet",
        "baseline": "config/baselines/mini.json",
        "allowlist": None,
        "policies": None,
        "description": "mini ratchet guard fixture",
        "execution": {
            "engine": "python-ratchet",
            "entrypoint": "scripts/verify_mini_ratchet.py",
            "arguments": (),
            "mode": "ratchet",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": (
                "ratchet-baseline-v1;stdout-human;exit:0=pass,1=violation,2=infra"
            ),
            "ratchet": {
                "baselinePath": "config/baselines/mini.json",
                "findingProtocol": 1,
                "fingerprintSchema": 1,
                "childArgumentTemplate": ("{entrypoint}",),
                "ciRestrictions": ("no-update-baseline", "no-propose-baseline"),
            },
            "testManifest": "none",
            "documentationAnchor": "docs/ci/guard-framework.md",
        },
    }


def _make_tree(root: Path) -> Path:
    """Create the minimal file tree the mini guard fixtures need."""
    (root / "scripts" / "ci").mkdir(parents=True, exist_ok=True)
    (root / "config" / "baselines").mkdir(parents=True, exist_ok=True)
    (root / "docs" / "ci").mkdir(parents=True, exist_ok=True)
    (root / "config" / "mini_allowlist.yml").write_text(
        "allow: []\n", encoding="utf-8"
    )
    (root / "docs" / "ci" / "guard-framework.md").write_text(
        "# guard framework\n", encoding="utf-8"
    )
    # The compiled ratchet plan resolves guard_ratchet.py against the context
    # repo root, so the fixture tree carries a copy of the real ratchet.
    shutil.copy2(CI_DIR / "guard_ratchet.py", root / "scripts" / "ci" / "guard_ratchet.py")
    shutil.copy2(CI_DIR / "guard_findings.py", root / "scripts" / "ci" / "guard_findings.py")
    return root


def _make_direct_fixture(
    tmp_path: Path, guard_body: str, **execution_overrides
) -> Tuple[Path, str]:
    root = _make_tree(tmp_path / "root")
    (root / "scripts" / "verify_mini.py").write_text(guard_body, encoding="utf-8")
    registry = _write_registry(
        tmp_path / "registry.py", {"mini": _direct_entry(**execution_overrides)}
    )
    return root, registry


def _make_ratchet_fixture(tmp_path: Path, child_body: str) -> Tuple[Path, str]:
    root = _make_tree(tmp_path / "root")
    (root / "scripts" / "verify_mini_ratchet.py").write_text(
        child_body, encoding="utf-8"
    )
    (root / "config" / "baselines" / "mini.json").write_text(
        json.dumps({"guard": "mini_ratchet", "fingerprints": []}), encoding="utf-8"
    )
    registry = _write_registry(
        tmp_path / "registry.py", {"mini_ratchet": _ratchet_entry()}
    )
    return root, registry


def _read_summary(path: Path) -> Dict:
    return json.loads(path.read_text(encoding="utf-8"))


# ── 1. Happy path per engine ────────────────────────────────────────────────────


class TestDirectEngineHappyPath:
    def test_exit0_pass_with_summary(self, tmp_path):
        root, registry = _make_direct_fixture(
            tmp_path, "import sys\nprint('mini ok')\nsys.exit(0)\n"
        )
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "mini", "direct", str(root),
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 0
        summary = _read_summary(summary_path)
        assert summary["exitCode"] == 0
        assert summary["outcome"] == "pass"
        assert summary["failureCodes"] == []
        assert summary["guardId"] == "mini"
        assert summary["context"] == "direct"
        assert summary["ciMode"] is False
        assert summary["childExitCode"] == 0

    def test_exit1_violation_preserved(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(1)\n")
        code = rgr.run_registered_guard(
            "mini", "direct", str(root), registry_path=registry,
        )
        assert code == 1

    def test_exit2_infra_preserved(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(2)\n")
        code = rgr.run_registered_guard(
            "mini", "direct", str(root), registry_path=registry,
        )
        assert code == 2

    def test_unexpected_child_exit_maps_to_infra(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(3)\n")
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "mini", "direct", str(root),
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 2  # universal mapping: only 0/1/2 pass through
        summary = _read_summary(summary_path)
        assert summary["exitCode"] == 2
        assert summary["childExitCode"] == 3
        assert summary["outcome"] == "infra_error"

    def test_outer_argv_is_the_compiled_plan(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        captured = {}

        def runner(argv, cwd):
            captured["argv"] = list(argv)
            captured["cwd"] = cwd
            return 0

        code = rgr.run_registered_guard(
            "mini", "direct", str(root), registry_path=registry, runner=runner,
        )
        assert code == 0
        argv = captured["argv"]
        assert argv[0] == sys.executable
        assert argv[1] == os.path.normpath(str(root / "scripts" / "verify_mini.py"))
        assert argv[2:] == [
            "--allowlist",
            os.path.normpath(str(root / "config" / "mini_allowlist.yml")),
        ]
        assert captured["cwd"] == str(root)

    def test_context_recorded_for_suite_and_gradle(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        for context in ("suite", "gradle"):
            summary_path = tmp_path / f"summary-{context}.json"
            code = rgr.run_registered_guard(
                "mini", context, str(root),
                output_summary=str(summary_path), registry_path=registry,
            )
            assert code == 0
            assert _read_summary(summary_path)["context"] == context


class TestRatchetEngineHappyPath:
    def test_ratchet_pass_exit0_end_to_end(self, tmp_path):
        root, registry = _make_ratchet_fixture(
            tmp_path, "import sys\nsys.exit(0)\n"
        )
        baseline = root / "config" / "baselines" / "mini.json"
        before = baseline.read_bytes()
        code = rgr.run_registered_guard(
            "mini_ratchet", "direct", str(root), registry_path=registry,
        )
        assert code == 0
        assert baseline.read_bytes() == before, "baseline must be untouched"

    def test_ratchet_violation_exit1_end_to_end(self, tmp_path):
        root, registry = _make_ratchet_fixture(
            tmp_path,
            "import sys\n"
            "print('G-MINI-01 app/src/Foo.kt:1 synthetic finding')\n"
            "sys.exit(0)\n",
        )
        baseline = root / "config" / "baselines" / "mini.json"
        before = baseline.read_bytes()
        code = rgr.run_registered_guard(
            "mini_ratchet", "direct", str(root), registry_path=registry,
        )
        assert code == 1
        assert baseline.read_bytes() == before, "baseline must be untouched"

    def test_ratchet_outer_argv_shape_and_no_baseline_flags(self, tmp_path):
        root, registry = _make_ratchet_fixture(
            tmp_path, "import sys\nsys.exit(0)\n"
        )
        captured = {}

        def runner(argv, cwd):
            captured["argv"] = list(argv)
            return 0

        code = rgr.run_registered_guard(
            "mini_ratchet", "direct", str(root),
            registry_path=registry, runner=runner,
        )
        assert code == 0
        argv = captured["argv"]
        assert argv[0] == sys.executable
        assert argv[1] == os.path.normpath(
            str(root / "scripts" / "ci" / "guard_ratchet.py")
        )
        assert "--guard-name" in argv and "mini_ratchet" in argv
        assert "--finding-protocol=1" in argv
        assert "--ci-mode" in argv
        assert "--fail-on-violation" in argv
        assert "--baseline" in argv
        assert f"--command-arg={sys.executable}" in argv
        assert f"--command-arg={os.path.normpath(str(root / 'scripts' / 'verify_mini_ratchet.py'))}" in argv
        # Never create/update a baseline (responsibility 7).
        assert not any(tok.startswith("--update-baseline") for tok in argv)
        assert not any(tok.startswith("--propose-baseline") for tok in argv)
        assert "--command" not in argv


# ── 2. Fail-closed configuration ────────────────────────────────────────────────


class TestFailClosedConfiguration:
    def test_unknown_guard_exit2(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "no_such_guard", "direct", str(root),
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 2
        summary = _read_summary(summary_path)
        assert summary["failureCodes"] == ["E_UNKNOWN_GUARD"]
        assert summary["childExitCode"] is None

    def test_compile_diagnostic_shell_string_exit2(self, tmp_path):
        root, registry = _make_direct_fixture(
            tmp_path, "import sys\nsys.exit(0)\n",
            arguments=("run.sh; rm -rf build",),
        )
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "mini", "direct", str(root),
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 2
        assert _read_summary(summary_path)["failureCodes"] == ["E_UNSAFE_TOKEN"]

    def test_missing_required_input_exit2(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        (root / "config" / "mini_allowlist.yml").unlink()
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "mini", "direct", str(root),
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 2
        assert _read_summary(summary_path)["failureCodes"] == ["E_INPUT_UNRESOLVED"]

    def test_unsupported_context_exit2(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "mini", "workflow", str(root),
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 2
        summary = _read_summary(summary_path)
        assert summary["failureCodes"] == ["E_ADAPTER_UNSUPPORTED_CONTEXT"]

    def test_invalid_root_exit2(self, tmp_path):
        code = rgr.run_registered_guard(
            "mini", "direct", str(tmp_path / "does_not_exist"),
        )
        assert code == 2

    def test_override_rejected_in_ci_mode(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        override = root / "config" / "mini_allowlist.yml"
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "mini", "direct", str(root), ci_mode=True,
            input_overrides={"config/mini_allowlist.yml": str(override)},
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 2
        assert _read_summary(summary_path)["failureCodes"] == [
            "E_TEST_OVERRIDE_IN_CI"
        ]

    def test_override_outside_root_exit2(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        outside = tmp_path / "outside" / "mini_allowlist.yml"
        outside.parent.mkdir(parents=True, exist_ok=True)
        outside.write_text("allow: [outside]\n", encoding="utf-8")
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "mini", "direct", str(root),
            input_overrides={"config/mini_allowlist.yml": str(outside)},
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 2
        assert _read_summary(summary_path)["failureCodes"] == [
            "E_OVERRIDE_OUTSIDE_ROOT"
        ]

    def test_override_unknown_key_exit2(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "mini", "direct", str(root),
            input_overrides={"config/not_a_declared_input.yml": str(root / "config" / "mini_allowlist.yml")},
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 2
        assert _read_summary(summary_path)["failureCodes"] == [
            "E_OVERRIDE_UNKNOWN_KEY"
        ]

    def test_registry_override_rejected_in_ci_mode(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "mini", "direct", str(root), ci_mode=True,
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 2
        assert _read_summary(summary_path)["failureCodes"] == [
            "E_ADAPTER_REGISTRY_IN_CI"
        ]

    def test_duplicate_override_key_exit2(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        # The library deduplicates dict keys, so exercise the CLI path where
        # a repeated --input-override is detectable.
        result = subprocess.run(
            [
                sys.executable, str(ADAPTER_SCRIPT),
                "--guard-id", "mini", "--context", "direct",
                "--root", str(root), "--registry", registry,
                "--input-override",
                f"config/mini_allowlist.yml={root / 'config' / 'mini_allowlist.yml'}",
                "--input-override",
                f"config/mini_allowlist.yml={root / 'config' / 'mini_allowlist.yml'}",
            ],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120,
        )
        assert result.returncode == 2
        assert "E_ADAPTER_DUPLICATE_OVERRIDE" in result.stderr

    def test_malformed_override_cli_exit2(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        result = subprocess.run(
            [
                sys.executable, str(ADAPTER_SCRIPT),
                "--guard-id", "mini", "--context", "direct",
                "--root", str(root), "--registry", registry,
                "--input-override", "not-a-key-value-pair",
            ],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120,
        )
        assert result.returncode == 2
        assert "E_ADAPTER_BAD_OVERRIDE" in result.stderr

    def test_override_applies_when_permitted(self, tmp_path):
        root, registry = _make_direct_fixture(
            tmp_path,
            "import sys\n"
            "print(open(sys.argv[2]).read().strip())\n"
            "sys.exit(0)\n",
        )
        override = root / "overrides" / "mini_allowlist.yml"
        override.parent.mkdir(parents=True, exist_ok=True)
        override.write_text("allow: [override-marker]\n", encoding="utf-8")
        result = subprocess.run(
            [
                sys.executable, str(ADAPTER_SCRIPT),
                "--guard-id", "mini", "--context", "direct",
                "--root", str(root), "--registry", registry,
                "--input-override",
                f"config/mini_allowlist.yml={override}",
            ],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120,
        )
        assert result.returncode == 0, result.stdout + result.stderr
        assert "override-marker" in result.stdout


# ── 3. Summary contract ─────────────────────────────────────────────────────────


class TestSummaryContract:
    def test_summary_shape_is_bounded(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "mini", "direct", str(root),
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 0
        summary = _read_summary(summary_path)
        assert set(summary) == SUMMARY_KEYS
        assert summary["schemaVersion"] == 1

    def test_summary_never_contains_paths_or_argv(self, tmp_path):
        root, registry = _make_direct_fixture(
            tmp_path, "import sys\nprint('stdout content')\nsys.exit(0)\n"
        )
        summary_path = tmp_path / "summary.json"
        code = rgr.run_registered_guard(
            "mini", "direct", str(root),
            output_summary=str(summary_path), registry_path=registry,
        )
        assert code == 0
        text = summary_path.read_text(encoding="utf-8")
        assert str(root) not in text
        assert sys.executable not in text
        assert "verify_mini.py" not in text
        assert "stdout content" not in text

    def test_summary_write_failure_returns_infra(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        # Parent exists but is a file: the summary write must fail.
        blocker = tmp_path / "blocker"
        blocker.write_text("not a directory\n", encoding="utf-8")
        code = rgr.run_registered_guard(
            "mini", "direct", str(root),
            output_summary=str(blocker / "summary.json"), registry_path=registry,
        )
        assert code == 2

    def test_library_returns_int_and_never_exits(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        code = rgr.run_registered_guard(
            "no_such_guard", "direct", str(root), registry_path=registry,
        )
        assert isinstance(code, int)
        assert code == 2


# ── 4. CLI adapter ──────────────────────────────────────────────────────────────


class TestCliAdapter:
    def test_cli_happy_path_exit0_with_summary(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        summary_path = tmp_path / "summary.json"
        result = subprocess.run(
            [
                sys.executable, str(ADAPTER_SCRIPT),
                "--guard-id", "mini", "--context", "direct",
                "--root", str(root), "--registry", registry,
                "--output-summary", str(summary_path),
            ],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120,
        )
        assert result.returncode == 0, result.stdout + result.stderr
        assert _read_summary(summary_path)["exitCode"] == 0

    def test_cli_unknown_guard_exit2(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        result = subprocess.run(
            [
                sys.executable, str(ADAPTER_SCRIPT),
                "--guard-id", "no_such_guard", "--context", "direct",
                "--root", str(root), "--registry", registry,
            ],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120,
        )
        assert result.returncode == 2
        assert "E_UNKNOWN_GUARD" in result.stderr

    def test_cli_unsupported_context_exit2(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        result = subprocess.run(
            [
                sys.executable, str(ADAPTER_SCRIPT),
                "--guard-id", "mini", "--context", "workflow",
                "--root", str(root), "--registry", registry,
            ],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120,
        )
        assert result.returncode == 2  # argparse choices failure

    def test_cli_ci_mode_with_registry_override_exit2(self, tmp_path):
        root, registry = _make_direct_fixture(tmp_path, "import sys\nsys.exit(0)\n")
        result = subprocess.run(
            [
                sys.executable, str(ADAPTER_SCRIPT),
                "--guard-id", "mini", "--context", "direct",
                "--root", str(root), "--registry", registry, "--ci-mode",
            ],
            capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=120,
        )
        assert result.returncode == 2
        assert "E_ADAPTER_REGISTRY_IN_CI" in result.stderr


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v", "--tb=short"]))
