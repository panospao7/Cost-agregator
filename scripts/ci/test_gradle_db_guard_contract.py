#!/usr/bin/env python3
"""
test_gradle_db_guard_contract.py

Static/contract tests for PR-GR-01: the Gradle ``:app:verifyDbAccessBoundaries``
task must fail closed, validate its required inputs, preflight the Python
interpreter, invoke the ratchet with an argument list (``--command-arg``), and
direct developers to the canonical DB write-ownership policy.

Three kinds of tests:

  1. STATIC SOURCE CONTRACT (no Gradle execution)

     Parses ``app/build.gradle.kts`` and asserts the task:
       * lists every required input (ratchet, guard script, baseline,
         ownership policy, structural exceptions, structural manifest);
       * rejects missing / non-regular / unreadable / outside-root inputs with
         ``GradleException`` (never ``logger.warn`` or an early return);
       * supports ``-PpythonExecutable`` (default ``python3``) with a
         ``--version`` preflight treated as an infrastructure error;
       * uses repeatable single-token ``--command-arg=<value>`` arguments and
         passes ``--ci-mode`` (the legacy ``--command "<shell string>"`` form
         is not used; a split ``--command-arg <value>`` pair would let
         argparse re-parse option-like child values as the ratchet's own
         flags and abort with "expected one argument");
       * resolves relative override paths against the repository root
         (``rootDir``), never against the Gradle project directory;
       * exposes the test-only overrides ``dbGuardRatchetPath``,
         ``dbGuardScriptPath``, ``dbGuardBaselinePath`` plus the
         policy/manifest overrides, with production defaults;
       * failure messages reference only canonical policy paths and never the
         superseded ``config/db_access_allowlist.yml``;
       * ALWAYS passes all six resolved input paths to the inner ratchet
         command — the policy/manifest arguments are never gated on override
         properties, so production CI uses the explicit canonical defaults
         (see the parity tests below).

  2. RATCHET CONTRACT (behavioral, temporary fixtures only)

     Runs ``scripts/ci/guard_ratchet.py`` directly with ``--command-arg`` and
     asserts:
       * missing Python / unlaunchable command -> exit 2 (infrastructure);
       * child exit 1 with new findings         -> exit 1 (policy violation);
       * child exit 2                           -> exit 2 (infrastructure);
       * child unexpected exit                  -> exit 2 (infrastructure);
       * child exit 1 with unparseable output   -> exit 2 with a single bounded
         ``RATCHET_UNPARSEABLE_GUARD_OUTPUT`` diagnostic whose ``--guard-name``
         is sanitized (non ``[A-Za-z0-9_.-]`` chars become ``_``, capped at 80);
       * malformed baseline                     -> exit 2 (infrastructure);
       * non-dict baseline, non-string/duplicate fingerprints, guard-name
         mismatch, unreadable baseline         -> exit 2 (infrastructure);
       * legacy ``--command`` with shell metacharacters runs shell-free and
         cannot inject commands, while still executing valid commands; a
         quoted path containing spaces parses into a single token (only the
         syntactic surrounding quotes are removed) and executes successfully;
       * successful invocation                  -> exit 0.

  3. INPUT-VALIDATION HELPER CONTRACT (behavioral, temporary fixtures only)

     Exercises ``scripts/ci/gradle_db_guard_inputs.py`` — the Python contract
     mirror of the Gradle task's input validation — against real temporary
     files and a real interpreter, so behavior is proven without relying only
     on source-string assertions:
       * missing file -> rejected;
       * directory path -> rejected (not a regular file);
       * outside-root path -> rejected;
       * unreadable path -> rejected where the platform supports it;
       * relative override resolves against the repository root (rootDir),
         mirroring the Gradle relative-override contract;
       * ``resolve_db_guard_path`` returns the canonical resolved path
         (``Path.resolve()``), mirroring the Gradle task's ``canonicalFile``;
       * a candidate whose textual root/case differs from the real repository
         root — same relative file — is accepted, mirroring Gradle's
         case-insensitive ``startsWith(..., ignoreCase = true)``;
       * a symlink inside the root that escapes to a file outside the root is
         rejected as ``outside_root``;
       * failed Python preflight -> rejected (infrastructure error);
       * successful preflight -> passes.

CONTRACT MIRROR & PARITY REQUIREMENT
------------------------------------

``scripts/ci/gradle_db_guard_inputs.py`` is the **contract mirror** of the
Gradle task's input validation (the task keeps its inline implementation in
``app/build.gradle.kts``; behavior is unchanged).

Whenever the Gradle validation changes — a required input path, an override
property name, or how the inner ratchet command is constructed — you MUST:

  1. update the contract mirror (``gradle_db_guard_inputs.py``); and
  2. keep the parity tests green:
     - ``test_parity_task_inputs_and_overrides_match_contract_mirror``
     - ``test_parity_command_always_passes_all_required_input_paths``
     - ``test_parity_default_paths_paired_with_exact_override_properties``
     - ``test_command_construction_uses_single_token_command_arg``

The parity tests read ``app/build.gradle.kts`` directly and assert that the
six required input paths and override property names correspond exactly to
``gradle_db_guard_inputs.DEFAULT_DB_GUARD_INPUTS``, and that the three
policy/manifest arguments are ALWAYS present in the constructed command path
(never gated on override properties).

Run:
    python -m pytest scripts/ci/test_gradle_db_guard_contract.py -v
"""

import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import List, Optional, Tuple

# Make this directory importable so the sibling contract helper
# (gradle_db_guard_inputs.py) can be imported regardless of how pytest runs.
_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import pytest  # noqa: E402

from gradle_db_guard_inputs import (  # noqa: E402
    DEFAULT_DB_GUARD_INPUTS,
    GradleDbGuardInputError,
    preflight_python_executable,
    resolve_db_guard_path,
    validate_db_guard_inputs,
)

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
GRADLE_BUILD = REPO_ROOT / "app" / "build.gradle.kts"
RATCHET_SCRIPT = Path(__file__).resolve().parent / "guard_ratchet.py"

# The canonical required-input set enforced by the Gradle task.
REQUIRED_INPUTS = [
    "scripts/ci/guard_ratchet.py",
    "scripts/verify_db_access_boundaries.py",
    "config/baselines/db_access.json",
    "config/guards/db_ownership_policy.yml",
    "config/guards/db_structural_exceptions.yml",
    "config/guards/db_structural_exceptions_expected_methods.yml",
]

# Test-only override properties the task must expose (defaults used in CI).
OVERRIDE_PROPERTIES = [
    "dbGuardRatchetPath",
    "dbGuardScriptPath",
    "dbGuardBaselinePath",
    "dbGuardOwnershipPolicyPath",
    "dbGuardStructuralExceptionsPath",
    "dbGuardStructuralManifestPath",
]

FORBIDDEN_LEGACY_REFERENCE = "config/db_access_allowlist.yml"


# ---------------------------------------------------------------------------
# Static source inspection helpers
# ---------------------------------------------------------------------------


def _gradle_build_text() -> str:
    if not GRADLE_BUILD.is_file():
        raise AssertionError(f"missing app/build.gradle.kts at {GRADLE_BUILD}")
    return GRADLE_BUILD.read_text(encoding="utf-8")


def _verify_task_text() -> str:
    """Return the text of the verifyDbAccessBoundaries task block only."""
    text = _gradle_build_text()
    start_marker = 'tasks.register("verifyDbAccessBoundaries")'
    if start_marker not in text:
        raise AssertionError("verifyDbAccessBoundaries task not found in app/build.gradle.kts")
    start = text.index(start_marker)
    end_marker = 'tasks.named("check")'
    end = text.index(end_marker, start)
    return text[start:end]


# ---------------------------------------------------------------------------
# Static source contract tests
# ---------------------------------------------------------------------------


def test_task_lists_all_required_inputs() -> None:
    task = _verify_task_text()
    for rel in REQUIRED_INPUTS:
        assert rel in task, f"required input not declared in task: {rel}"


def test_required_input_files_exist_in_repo() -> None:
    for rel in REQUIRED_INPUTS:
        candidate = REPO_ROOT / rel
        assert candidate.is_file(), f"required input missing from repo: {rel}"


def test_missing_input_fails_closed_with_gradle_exception() -> None:
    task = _verify_task_text()
    assert "GradleException" in task
    assert "logger.warn" not in task, "task must never warn-and-return"
    assert "return@doLast" not in task, "task must never silently skip"
    # Each required-input failure mode must be rejected with a hard error.
    assert "not found" in task
    assert "isFile" in task or "regular file" in task
    assert "canRead()" in task or "not readable" in task


def test_outside_root_path_rejected() -> None:
    task = _verify_task_text()
    assert "canonicalFile" in task
    assert "startsWith" in task
    assert "outside the repository root" in task


def test_python_executable_property_with_preflight() -> None:
    task = _verify_task_text()
    assert "pythonExecutable" in task
    assert '"python3"' in task
    assert "--version" in task
    assert "infrastructure error" in task.lower()


def test_uses_command_arg_list_and_ci_mode() -> None:
    task = _verify_task_text()
    assert "--command-arg" in task
    assert "--ci-mode" in task
    # The legacy shell-string form (`--command "..."`) must not be used.
    assert '"--command"' not in task
    assert "'--command'" not in task


def test_exposes_test_only_override_properties() -> None:
    task = _verify_task_text()
    for prop in OVERRIDE_PROPERTIES:
        assert prop in task, f"missing override property: {prop}"


def test_failure_message_uses_canonical_policy_paths() -> None:
    task = _verify_task_text()
    assert "db_ownership_policy.yml" in task
    assert "db_structural_exceptions.yml" in task
    assert "docs/DB_WRITE_OWNERSHIP.md" in task
    assert FORBIDDEN_LEGACY_REFERENCE not in task, (
        "failure messages must not reference the superseded legacy allowlist"
    )


def test_infrastructure_exit_message_mentions_baseline() -> None:
    task = _verify_task_text()
    assert "config/baselines/db_access.json" in task
    assert "infrastructure error" in task.lower()


def _command_construction_region(task: str) -> str:
    """Return the inner ratchet command-construction block only.

    The block runs from the mutable argument-list declaration to the exec
    invocation.  It must contain no conditionals: every required input is
    appended unconditionally.
    """
    start_marker = "val commandArgs = mutableListOf<String>()"
    end_marker = "val result = exec {"
    if start_marker not in task:
        raise AssertionError("command construction block not found in task")
    start = task.index(start_marker)
    if end_marker not in task:
        raise AssertionError("exec invocation not found in task")
    end = task.index(end_marker, start)
    return task[start:end]


def test_parity_task_inputs_and_overrides_match_contract_mirror() -> None:
    """Parity: Gradle task inputs/overrides must match the contract mirror.

    Reads ``app/build.gradle.kts`` directly and verifies every required input
    path and override property name from
    ``gradle_db_guard_inputs.DEFAULT_DB_GUARD_INPUTS`` is present in the
    ``verifyDbAccessBoundaries`` task source.  Any drift fails this gate.
    """
    task = _verify_task_text()
    for default_rel, override_prop in DEFAULT_DB_GUARD_INPUTS:
        assert default_rel in task, (
            f"Gradle task no longer declares required input '{default_rel}' "
            f"from DEFAULT_DB_GUARD_INPUTS"
        )
        assert override_prop in task, (
            f"Gradle task no longer exposes override property '{override_prop}' "
            f"from DEFAULT_DB_GUARD_INPUTS"
        )


def test_parity_command_always_passes_all_required_input_paths() -> None:
    """Parity: the inner ratchet command always passes all six inputs.

    The policy/manifest arguments must NOT be gated on override properties —
    production CI must always pass the resolved canonical paths so the inner
    guard can never silently fall back to a different file.  All six inputs
    are passed explicitly and unconditionally in the command construction.
    """
    task = _verify_task_text()
    command_region = _command_construction_region(task)

    # Ratchet, guard script, and baseline are always passed explicitly.
    assert "ratchetFile.absolutePath" in command_region
    assert "guardFile.absolutePath" in command_region
    assert "baselineFile.absolutePath" in command_region

    # Policy/manifest inputs are always present in the constructed command.
    for flag, file_ref in (
        ("--ownership-policy", "ownershipPolicyFile.absolutePath"),
        ("--structural-exceptions", "structuralExceptionsFile.absolutePath"),
        ("--structural-manifest", "structuralManifestFile.absolutePath"),
    ):
        assert flag in command_region, f"missing inner guard argument: {flag}"
        assert file_ref in command_region, f"missing resolved path: {file_ref}"

    # The construction block is unconditional — no `if` may gate any input.
    assert "if (" not in command_region, (
        "command construction must not gate required inputs behind conditionals"
    )


def test_parity_default_paths_paired_with_exact_override_properties() -> None:
    """Parity: each default path is paired with its exact override property.

    Every ``(default_rel, override_prop)`` pair from
    ``gradle_db_guard_inputs.DEFAULT_DB_GUARD_INPUTS`` must appear as one
    ``resolveDbGuardPath("<default_rel>", "<override_prop>")`` call in the
    ``verifyDbAccessBoundaries`` task.  This asserts the exact pairing rather
    than merely that both strings occur somewhere in the source — a re-ordered
    or mis-paired resolve call fails this gate.
    """
    task = _verify_task_text()
    for default_rel, override_prop in DEFAULT_DB_GUARD_INPUTS:
        pair_pattern = (
            r"resolveDbGuardPath\s*\(\s*"
            + re.escape(f'"{default_rel}"')
            + r"\s*,\s*"
            + re.escape(f'"{override_prop}"')
            + r"\s*\)"
        )
        assert re.search(pair_pattern, task), (
            f"Gradle task must pair default input '{default_rel}' with "
            f"override property '{override_prop}' in a single "
            f'resolveDbGuardPath("{default_rel}", "{override_prop}") call'
        )


def test_command_construction_uses_single_token_command_arg() -> None:
    """Every ratchet child argument must be a single ``--command-arg=<value>`` token.

    GR-01 regression: a split ``--command-arg <value>`` pair lets argparse
    re-parse option-like child values (``--fail-on-violation``,
    ``--ownership-policy``, ``--structural-exceptions``,
    ``--structural-manifest``) as the ratchet's own flags and abort with
    "expected one argument".  The task must encode every child argument as
    ``--command-arg=<value>`` (single list token).
    """
    command_region = _command_construction_region(_verify_task_text())

    # The standalone flag form must not be used inside the construction block.
    assert '"--command-arg"' not in command_region, (
        "standalone --command-arg token found; every child argument must be "
        "encoded as --command-arg=<value> (single list token)"
    )

    # Every option-like child flag is encoded as a single --command-arg= token.
    for child_flag in (
        "--fail-on-violation",
        "--ownership-policy",
        "--structural-exceptions",
        "--structural-manifest",
    ):
        assert f'"--command-arg={child_flag}"' in command_region, (
            f"child argument '{child_flag}' must be encoded as "
            f'--command-arg={child_flag} (single list token)'
        )

    # Child executable and resolved paths are encoded as single tokens too.
    assert '"--command-arg=$pythonExecutable"' in command_region
    assert '"--command-arg=${guardFile.absolutePath}"' in command_region
    assert '"--command-arg=${ownershipPolicyFile.absolutePath}"' in command_region
    assert '"--command-arg=${structuralExceptionsFile.absolutePath}"' in command_region
    assert '"--command-arg=${structuralManifestFile.absolutePath}"' in command_region

    # The ratchet's own flags (--baseline, --fail-on-violation, --ci-mode)
    # remain outside the --command-arg tokens.
    assert '"--baseline"' in command_region
    assert '"--ci-mode"' in command_region


def test_relative_overrides_resolve_against_repository_root() -> None:
    """Relative override paths must resolve against rootDir, not projectDir.

    ``file(override)`` in Gradle resolves relative paths against the project
    directory (``app/``).  The task must distinguish absolute overrides and
    resolve relative overrides against ``rootDir`` (the repository root) so
    test-only overrides are consistent with the canonical defaults.
    """
    task = _verify_task_text()
    assert "isAbsolute" in task, (
        "task must distinguish absolute overrides from relative ones"
    )
    assert '"$rootDir/$override"' in task, (
        "relative overrides must resolve against rootDir (repository root)"
    )
    assert '"$rootDir/$defaultRel"' in task, (
        "canonical defaults must also resolve against rootDir"
    )


# ---------------------------------------------------------------------------
# Ratchet behavioral contract (--command-arg path, temporary fixtures only)
# ---------------------------------------------------------------------------


def _write_guard_script(path: Path, stdout: str, exit_code: int = 0) -> None:
    content = (
        "import sys\n"
        f"print({stdout!r})\n"
        f"sys.exit({exit_code})\n"
    )
    path.write_text(content, encoding="utf-8")


def _write_baseline(path: Path, guard_name: str, fingerprints: List[str]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    baseline = {
        "guard": guard_name,
        "generated": "2026-08-07T00:00:00",
        "fingerprints": fingerprints,
    }
    with open(path, "w", encoding="utf-8") as f:
        json.dump(baseline, f, indent=2)
        f.write("\n")


def _run_ratchet_args(
    guard_name: str,
    command_args: List[str],
    baseline: Path,
    extra_args: Optional[List[str]] = None,
    cwd: Optional[Path] = None,
) -> subprocess.CompletedProcess:
    """Run the ratchet using repeatable single-token --command-arg=<value> tokens.

    Every child argument is encoded as one ``--command-arg=<value>`` list
    token (never a split ``--command-arg <value>`` pair) so argparse can never
    re-parse option-like child values as the ratchet's own flags.
    """
    cmd = [
        sys.executable,
        str(RATCHET_SCRIPT),
        "--guard-name", guard_name,
        "--baseline", str(baseline),
    ]
    for arg in command_args:
        cmd += [f"--command-arg={arg}"]
    if extra_args:
        cmd.extend(extra_args)
    return subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
        cwd=str(cwd) if cwd else None,
    )


def test_successful_invocation_exits_zero(tmp_path: Path) -> None:
    """Child exits 0 -> ratchet exits 0 (PASS)."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "PASS: no violations\n", 0)
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert "PASS" in result.stdout


def test_command_arg_single_token_with_option_like_child_flags(
    tmp_path: Path,
) -> None:
    """Option-like child flags encoded as --command-arg=<value> stay child args.

    GR-01 regression: encoding a child flag as a separate token
    (``--command-arg --fail-on-violation``) makes argparse re-parse it as the
    ratchet's own flag and abort with "expected one argument" (exit 2).  With
    the single-token form (``--command-arg=--fail-on-violation``) the
    option-like values stay inside the child argument list, the ratchet's own
    ``--fail-on-violation`` / ``--ci-mode`` flags stay its own, and the child
    runs successfully.
    """
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "PASS: no violations\n", 0)
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])

    cmd = [
        sys.executable,
        str(RATCHET_SCRIPT),
        "--guard-name", "db_access",
        "--baseline", str(baseline),
        # Child args encoded as single --command-arg=<value> tokens,
        # including option-like values.
        "--command-arg=" + sys.executable,
        "--command-arg=" + str(guard_py),
        "--command-arg=--fail-on-violation",
        "--command-arg=--ownership-policy",
        "--command-arg=--structural-exceptions",
        "--command-arg=--structural-manifest",
        "--ci-mode",
        "--fail-on-violation",
    ]
    result = subprocess.run(
        cmd,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
        cwd=str(tmp_path),
    )
    combined = result.stdout + result.stderr
    assert result.returncode == 0, combined
    assert "PASS" in result.stdout
    assert "expected one argument" not in combined


def test_child_exit_one_with_new_findings_exits_one(tmp_path: Path) -> None:
    """Child exits 1 with a new finding -> ratchet exits 1 (policy violation)."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(
        guard_py,
        "[UNALLOWLISTED_CLASS]\napp/src/main/java/com/example/New.kt:42\n",
        1,
    )
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [
        "UNALLOWLISTED_CLASS app/src/main/java/com/example/Old.kt",
    ])

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 1, result.stdout + result.stderr
    assert "FAIL" in result.stdout
    assert "NEW: 1" in result.stdout


def test_child_exit_two_is_infrastructure_error(tmp_path: Path) -> None:
    """Child exits 2 -> ratchet exits 2 (infrastructure error)."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "infra boom\n", 2)
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr


def test_child_unexpected_exit_is_infrastructure_error(tmp_path: Path) -> None:
    """Child exits with an unknown code -> ratchet exits 2 (infrastructure)."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "weird\n", 42)
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "unknown code" in result.stderr


def test_child_exit_one_without_parseable_findings_is_infrastructure_error(
    tmp_path: Path,
) -> None:
    """Child exits 1 but emits no parseable findings -> ratchet exits 2."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "some unstructured error output\n", 1)
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "no parseable findings" in result.stderr


def test_unparseable_diagnostic_sanitizes_guard_name(tmp_path: Path) -> None:
    """The unparseable diagnostic must carry a bounded, sanitized guard name.

    GR-01: ``--guard-name`` is interpolated into the
    ``RATCHET_UNPARSEABLE_GUARD_OUTPUT`` diagnostic.  Non
    ``[A-Za-z0-9_.-]`` characters (including newlines) must be replaced with
    ``_`` and the result capped at 80 characters, so a hostile or malformed
    guard name can never turn the diagnostic into multiple unbounded lines or
    inject extra payloads.
    """
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "some unstructured error output\n", 1)
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])

    raw_name = "evil\nGUARD\rLINE" + ("x" * 100) + "\nINJECTED"
    expected = re.sub(r"[^A-Za-z0-9_.-]", "_", raw_name)[:80]

    result = _run_ratchet_args(
        raw_name,
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "RATCHET_UNPARSEABLE_GUARD_OUTPUT" in result.stderr
    assert f"guard={expected}" in result.stderr
    # Exactly one bounded diagnostic line, with no injected payload.
    diag_lines = [
        ln for ln in result.stderr.splitlines()
        if "RATCHET_UNPARSEABLE_GUARD_OUTPUT" in ln
    ]
    assert len(diag_lines) == 1
    assert "\n" not in diag_lines[0]
    assert "INJECTED" not in result.stderr
    assert len(expected) <= 80


def test_missing_python_is_infrastructure_error(tmp_path: Path) -> None:
    """Unlaunchable interpreter (--command-arg) -> ratchet exits 2."""
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])

    result = _run_ratchet_args(
        "db_access",
        ["definitely_missing_python_9f3c1c_xyz"],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "Guard ratchet error" in result.stderr


def test_malformed_baseline_is_infrastructure_error(tmp_path: Path) -> None:
    """Malformed baseline JSON -> ratchet exits 2 (infrastructure)."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "PASS: no violations\n", 0)
    baseline = tmp_path / "malformed.json"
    baseline.write_text("{ this is not valid json", encoding="utf-8")

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "Malformed baseline" in result.stderr


def test_baseline_top_level_non_dict_is_infrastructure_error(tmp_path: Path) -> None:
    """Baseline whose top-level JSON value is not an object -> exit 2."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "PASS: no violations\n", 0)
    baseline = tmp_path / "bad.json"
    baseline.write_text("[]", encoding="utf-8")

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "must be an object" in result.stderr


def test_baseline_missing_fingerprints_is_infrastructure_error(tmp_path: Path) -> None:
    """Baseline without a 'fingerprints' key -> exit 2."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "PASS: no violations\n", 0)
    baseline = tmp_path / "bad.json"
    baseline.write_text(
        json.dumps({"guard": "db_access"}), encoding="utf-8"
    )

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "'fingerprints' is not a list" in result.stderr


def test_baseline_non_string_fingerprint_is_infrastructure_error(tmp_path: Path) -> None:
    """Baseline containing a non-string fingerprint -> exit 2."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "PASS: no violations\n", 0)
    baseline = tmp_path / "bad.json"
    baseline.write_text(
        json.dumps({"guard": "db_access", "fingerprints": ["ok", 42]}),
        encoding="utf-8",
    )

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "non-empty strings" in result.stderr


def test_baseline_duplicate_fingerprints_is_infrastructure_error(tmp_path: Path) -> None:
    """Baseline with duplicate fingerprints -> exit 2."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "PASS: no violations\n", 0)
    baseline = tmp_path / "bad.json"
    baseline.write_text(
        json.dumps({"guard": "db_access", "fingerprints": ["a", "a"]}),
        encoding="utf-8",
    )

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "duplicate" in result.stderr.lower()


def test_baseline_guard_name_mismatch_is_infrastructure_error(tmp_path: Path) -> None:
    """Baseline whose 'guard' field does not match --guard-name -> exit 2."""
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "PASS: no violations\n", 0)
    baseline = tmp_path / "bad.json"
    _write_baseline(baseline, "some_other_guard", [])

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "guard name mismatch" in result.stderr


def test_baseline_unreadable_is_infrastructure_error(tmp_path: Path) -> None:
    """Unreadable baseline file -> exit 2 (skipped where not portable)."""
    if os.name == "nt" or (hasattr(os, "geteuid") and os.geteuid() == 0):
        pytest.skip("file permission checks are not portable in this environment")
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "PASS: no violations\n", 0)
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])
    baseline.chmod(0)

    result = _run_ratchet_args(
        "db_access",
        [sys.executable, str(guard_py)],
        baseline,
        extra_args=["--ci-mode", "--fail-on-violation"],
        cwd=tmp_path,
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "Could not read baseline file" in result.stderr


def test_command_and_command_arg_conflict_is_error(tmp_path: Path) -> None:
    """Using --command and --command-arg together must fail (exit 2).

    Per GR-01 the child argument is encoded as a single ``--command-arg=<value>``
    token; supplying it alongside the legacy ``--command`` shell string must
    still be rejected as a conflict before any child command runs.
    """
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])

    result = subprocess.run(
        [
            sys.executable, str(RATCHET_SCRIPT),
            "--guard-name", "db_access",
            "--command", "echo hi",
            "--command-arg=echo",
            "--baseline", str(baseline),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
        cwd=str(tmp_path),
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "not both" in result.stderr


def test_missing_command_and_command_arg_is_error(tmp_path: Path) -> None:
    """Neither --command nor --command-arg -> ratchet must fail (exit 2)."""
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])

    result = subprocess.run(
        [
            sys.executable, str(RATCHET_SCRIPT),
            "--guard-name", "db_access",
            "--baseline", str(baseline),
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
        cwd=str(tmp_path),
    )
    assert result.returncode == 2, result.stdout + result.stderr
    assert "one of --command or --command-arg is required" in result.stderr


def test_legacy_command_metacharacters_are_shell_free(tmp_path: Path) -> None:
    """Legacy --command must run shell-free (no injection) while executing.

    The legacy compatibility form is parsed by the ratchet's cross-platform,
    shell-free tokenizer (which removes only syntactic surrounding quotes) and
    executed with ``shell=False``.  Shell metacharacters such as ``;``, ``&&``,
    ``||``, ``|`` and ``>`` must be inert tokens passed to the child — never
    commands a shell could interpret.  We prove it behaviorally: the mock
    guard still runs (exit 0 / PASS) and no injected command output or
    redirection file appears.
    """
    guard_py = tmp_path / "mock_guard.py"
    _write_guard_script(guard_py, "PASS: no violations\n", 0)
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])

    # `python3` is resolved to sys.executable by the ratchet, so the command
    # string itself never embeds a path that may contain spaces.  The
    # metacharacters must be inert tokens, not shell operators.
    metachar_command = (
        f"python3 {guard_py} ; echo INJECTED && echo MORE || echo NOPE | cat > out.txt"
    )

    result = subprocess.run(
        [
            sys.executable, str(RATCHET_SCRIPT),
            "--guard-name", "db_access",
            "--command", metachar_command,
            "--baseline", str(baseline),
            "--ci-mode",
            "--fail-on-violation",
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
        cwd=str(tmp_path),
    )
    combined = result.stdout + result.stderr
    # Valid execution preserved: the mock guard ran and the ratchet passed.
    assert result.returncode == 0, combined
    assert "PASS" in result.stdout
    # No command injection: none of the injected commands executed.
    assert "INJECTED" not in combined
    assert "MORE" not in combined
    assert "NOPE" not in combined
    # No shell redirection happened either.
    assert not (tmp_path / "out.txt").exists()


def test_legacy_command_quoted_path_with_spaces_runs_shell_free(
    tmp_path: Path,
) -> None:
    """Legacy --command must execute a quoted path containing spaces (GR-01 final).

    The legacy compatibility form is parsed with a cross-platform, shell-free
    tokenizer that removes only syntactic surrounding quotes.  A quoted
    Windows-style path containing spaces must become a single child token
    (``"C:\\dir with spaces\\mock guard.py"`` -> ``C:\\dir with spaces\\mock
    guard.py``) and execute successfully with ``shell=False`` -- the path is
    never split at the space and never reinterpreted by a shell.  Shell
    metacharacters embedded in the same command string stay inert argument
    tokens: no injected command runs and no redirection file is created.
    """
    spaced_dir = tmp_path / "dir with spaces"
    spaced_dir.mkdir(parents=True)
    guard_py = spaced_dir / "mock guard.py"
    _write_guard_script(guard_py, "PASS: no violations\n", 0)
    baseline = tmp_path / "baseline.json"
    _write_baseline(baseline, "db_access", [])

    # Quoted path containing spaces (Windows-style), plus inert metacharacters.
    command = (
        f'python3 "{guard_py}" ; echo INJECTED && echo MORE '
        f"|| echo NOPE | cat > out.txt"
    )

    result = subprocess.run(
        [
            sys.executable, str(RATCHET_SCRIPT),
            "--guard-name", "db_access",
            "--command", command,
            "--baseline", str(baseline),
            "--ci-mode",
            "--fail-on-violation",
        ],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=30,
        cwd=str(tmp_path),
    )
    combined = result.stdout + result.stderr
    # The quoted path with spaces executed successfully (exit 0 / PASS).
    assert result.returncode == 0, combined
    assert "PASS" in result.stdout
    # Shell metacharacters were not interpreted: no injected command ran.
    assert "INJECTED" not in combined
    assert "MORE" not in combined
    assert "NOPE" not in combined
    # No shell redirection happened either.
    assert not (tmp_path / "out.txt").exists()


# ---------------------------------------------------------------------------
# Input-validation helper behavioral contract (no Gradle execution)
# ---------------------------------------------------------------------------


def test_helper_missing_file_is_rejected(tmp_path: Path) -> None:
    """A required input that does not exist must fail the contract check."""
    root = tmp_path
    inputs: List[Tuple[str, Optional[str]]] = [
        ("scripts/ci/guard_ratchet.py", None)
    ]
    with pytest.raises(GradleDbGuardInputError) as exc_info:
        validate_db_guard_inputs(root, inputs)
    assert exc_info.value.code == "not_found"


def _write_all_valid_inputs_except(root: Path, missing_rel: str) -> None:
    """Create every canonical required input except ``missing_rel``.

    Mirrors the canonical default-rel set from
    ``DEFAULT_DB_GUARD_INPUTS`` so a single missing input can be isolated.
    """
    for rel, _prop in DEFAULT_DB_GUARD_INPUTS:
        if rel == missing_rel:
            continue
        target = root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("x", encoding="utf-8")


def _assert_only_input_missing_is_rejected(root: Path, missing_rel: str) -> None:
    """Run full validation with exactly ``missing_rel`` absent -> not_found."""
    inputs: List[Tuple[str, Optional[str]]] = [
        (rel, None) for rel, _prop in DEFAULT_DB_GUARD_INPUTS
    ]
    with pytest.raises(GradleDbGuardInputError) as exc_info:
        validate_db_guard_inputs(root, inputs)
    assert exc_info.value.code == "not_found"


def test_helper_missing_ratchet_is_rejected(tmp_path: Path) -> None:
    """Missing ratchet wrapper -> controlled 'not_found' code."""
    root = tmp_path
    _write_all_valid_inputs_except(root, "scripts/ci/guard_ratchet.py")
    _assert_only_input_missing_is_rejected(root, "scripts/ci/guard_ratchet.py")


def test_helper_missing_guard_script_is_rejected(tmp_path: Path) -> None:
    """Missing guard script -> controlled 'not_found' code."""
    root = tmp_path
    _write_all_valid_inputs_except(root, "scripts/verify_db_access_boundaries.py")
    _assert_only_input_missing_is_rejected(
        root, "scripts/verify_db_access_boundaries.py"
    )


def test_helper_missing_baseline_is_rejected(tmp_path: Path) -> None:
    """Missing ratchet baseline -> controlled 'not_found' code."""
    root = tmp_path
    _write_all_valid_inputs_except(root, "config/baselines/db_access.json")
    _assert_only_input_missing_is_rejected(root, "config/baselines/db_access.json")


def test_helper_missing_ownership_policy_is_rejected(tmp_path: Path) -> None:
    """Missing ownership policy -> controlled 'not_found' code."""
    root = tmp_path
    _write_all_valid_inputs_except(root, "config/guards/db_ownership_policy.yml")
    _assert_only_input_missing_is_rejected(root, "config/guards/db_ownership_policy.yml")


def test_helper_missing_structural_exceptions_is_rejected(tmp_path: Path) -> None:
    """Missing structural exceptions -> controlled 'not_found' code."""
    root = tmp_path
    _write_all_valid_inputs_except(root, "config/guards/db_structural_exceptions.yml")
    _assert_only_input_missing_is_rejected(
        root, "config/guards/db_structural_exceptions.yml"
    )


def test_helper_missing_structural_manifest_is_rejected(tmp_path: Path) -> None:
    """Missing structural manifest -> controlled 'not_found' code."""
    root = tmp_path
    _write_all_valid_inputs_except(
        root, "config/guards/db_structural_exceptions_expected_methods.yml"
    )
    _assert_only_input_missing_is_rejected(
        root, "config/guards/db_structural_exceptions_expected_methods.yml"
    )


def test_helper_directory_path_is_rejected(tmp_path: Path) -> None:
    """A directory must fail the regular-file requirement."""
    root = tmp_path
    (root / "some_dir").mkdir()
    inputs: List[Tuple[str, Optional[str]]] = [("some_dir", None)]
    with pytest.raises(GradleDbGuardInputError) as exc_info:
        validate_db_guard_inputs(root, inputs)
    assert exc_info.value.code == "not_regular"


def test_helper_outside_root_path_is_rejected(tmp_path: Path) -> None:
    """An absolute override pointing outside the repository root must fail."""
    root = tmp_path
    outside = tmp_path.parent / ("outside-" + root.name)
    outside.mkdir(exist_ok=True)
    inputs: List[Tuple[str, Optional[str]]] = [
        ("scripts/ci/guard_ratchet.py", str(outside / "guard_ratchet.py"))
    ]
    with pytest.raises(GradleDbGuardInputError) as exc_info:
        validate_db_guard_inputs(root, inputs)
    assert exc_info.value.code == "outside_root"


def test_helper_accepts_candidate_with_differently_cased_root(
    tmp_path: Path,
) -> None:
    """A candidate whose textual root/case differs is accepted (GR-01 parity).

    The Gradle task's containment check compares canonical paths with
    ``startsWith(..., ignoreCase = true)`` on every platform, so the contract
    mirror must accept a candidate whose textual root/case differs from the
    *real* repository root while preserving the same relative file — never
    raising ``outside_root``.

    The real temporary repository root is passed as ``root`` and the candidate
    is supplied as an absolute override whose root portion differs only in
    case.  This deliberately avoids the weak variant of the test where the
    same variable is uppercased and used for both the root and the candidate.
    """
    root = tmp_path / "repo"
    target = root / "config" / "baselines" / "db_access.json"
    target.parent.mkdir(parents=True)
    target.write_text("{}", encoding="utf-8")

    # Same relative file, but the candidate's textual root portion differs in
    # case from the real repository root passed as ``root``.
    cased_candidate = (
        Path(str(root).upper()) / "config" / "baselines" / "db_access.json"
    )

    resolved = resolve_db_guard_path(
        root,
        "config/baselines/db_access.json",
        str(cased_candidate),
    )
    # Accepted as Gradle does: same canonical relative file, never outside_root.
    assert str(resolved).lower() == str(target.resolve()).lower()
    if os.name == "nt":
        # Case-insensitive filesystem: the differently-cased text resolves to
        # the exact same on-disk canonical file as the original target.
        assert resolved == target.resolve()


def test_helper_relative_override_resolves_against_repository_root(
    tmp_path: Path,
) -> None:
    """A relative override must resolve against the repository root.

    Mirrors the Gradle contract (GR-01): relative override paths resolve
    against ``rootDir`` — the repository root — exactly like the canonical
    defaults, never against the Gradle project directory (``app/``).  A
    relative override ``alt/baseline.json`` must therefore resolve to
    ``<root>/alt/baseline.json``, not ``<root>/app/alt/baseline.json``.
    """
    root = tmp_path
    (root / "alt").mkdir(parents=True)
    baseline = root / "alt" / "baseline.json"
    baseline.write_text("{}", encoding="utf-8")

    resolved = validate_db_guard_inputs(
        root,
        [("config/baselines/db_access.json", "alt/baseline.json")],
    )
    assert resolved["config/baselines/db_access.json"] == baseline


def test_helper_resolve_returns_canonical_path(tmp_path: Path) -> None:
    """``resolve_db_guard_path`` must return the canonical resolved path.

    GR-01 review: the mirror must return exactly what the Gradle task's
    ``canonicalFile`` returns — the canonicalized path, never the raw
    candidate.  A symlinked path must be resolved to its target before it is
    returned.  Platforms that cannot create symlinks (e.g. Windows without
    Developer Mode / elevated privileges) skip the symlink assertions; the
    differently-cased text assertions run on every platform.
    """
    root = tmp_path
    target = root / "real" / "guard_ratchet.py"
    target.parent.mkdir(parents=True)
    target.write_text("x", encoding="utf-8")

    # Differently-cased path text must still satisfy the containment check:
    # the Gradle task compares canonical paths with startsWith(...,
    # ignoreCase = true) on every platform, so the mirror must too.
    # Path.resolve() only folds case where the filesystem is case-insensitive
    # (e.g. Windows); on case-sensitive filesystems the differently-cased text
    # names a different location, so exact canonical equality with the
    # on-disk target is asserted only where supported.
    cased_root = Path(str(root).upper())
    cased_resolved = resolve_db_guard_path(cased_root, "real/guard_ratchet.py", None)
    expected_cased = cased_root.resolve() / "real" / "guard_ratchet.py"
    assert str(cased_resolved).lower() == str(expected_cased).lower()
    if os.name == "nt":
        # Case-insensitive filesystem: the differently-cased text resolves to
        # the same on-disk canonical file as the original target.
        assert cased_resolved == target.resolve()

    link = root / "linked" / "guard_ratchet.py"
    link.parent.mkdir(parents=True)
    try:
        link.symlink_to(target)
    except (OSError, NotImplementedError):
        pytest.skip("symlink creation is not supported in this environment")

    resolved = resolve_db_guard_path(root, "linked/guard_ratchet.py", None)
    # Canonical form: the symlink is resolved to its target.
    assert resolved == link.resolve()
    assert resolved == target.resolve()
    # The raw candidate path (through the symlink) must not be returned.
    assert resolved != link


def test_helper_symlink_escaping_root_is_rejected(tmp_path: Path) -> None:
    """A symlink inside the root escaping to an outside file is rejected.

    ``resolve_db_guard_path`` canonicalizes the candidate (``Path.resolve()``
    follows symlinks) before the in-repo containment check, mirroring the
    Gradle task's ``canonicalFile``.  A symlink placed *inside* the repository
    root that points at a real file *outside* the root must therefore fail
    with ``outside_root`` even though the raw candidate path text is inside
    the root.  Skipped only where symlink creation is unsupported or
    permission-denied (e.g. Windows without Developer Mode / elevation).
    """
    root = tmp_path / "repo"
    root.mkdir(parents=True)
    outside = tmp_path / "outside"
    outside.mkdir(parents=True)
    outside_target = outside / "guard_ratchet.py"
    outside_target.write_text("x", encoding="utf-8")

    # Symlink *inside* the repository root pointing at the outside file.
    link = root / "escaped_guard_ratchet.py"
    try:
        link.symlink_to(outside_target)
    except (OSError, NotImplementedError) as exc:
        pytest.skip(
            "symlink creation is not supported or is permission-denied on "
            f"platform '{os.name}': {exc.__class__.__name__}"
        )

    with pytest.raises(GradleDbGuardInputError) as exc_info:
        resolve_db_guard_path(root, "escaped_guard_ratchet.py", None)
    assert exc_info.value.code == "outside_root"


def test_helper_unreadable_path_is_rejected(tmp_path: Path) -> None:
    """An unreadable required input must fail where the platform allows."""
    if os.name == "nt" or (hasattr(os, "geteuid") and os.geteuid() == 0):
        pytest.skip("file permission checks are not portable in this environment")
    root = tmp_path
    target = root / "guard_ratchet.py"
    target.write_text("x", encoding="utf-8")
    target.chmod(0)
    inputs: List[Tuple[str, Optional[str]]] = [("guard_ratchet.py", None)]
    with pytest.raises(GradleDbGuardInputError) as exc_info:
        validate_db_guard_inputs(root, inputs)
    assert exc_info.value.code == "not_readable"


def test_helper_failed_python_preflight_is_rejected() -> None:
    """A non-launchable Python interpreter must fail the preflight check."""
    with pytest.raises(GradleDbGuardInputError) as exc_info:
        preflight_python_executable("definitely_missing_python_9f3c1c_xyz")
    assert exc_info.value.code == "python_preflight"


def test_helper_python_preflight_nonzero_exit_is_rejected(tmp_path: Path) -> None:
    """An interpreter that launches but exits non-zero must fail preflight."""
    if os.name == "nt":
        pytest.skip("cannot directly execute a .py file on Windows")
    fake_python = tmp_path / "fake_python"
    fake_python.write_text(
        "#!/usr/bin/env python3\nimport sys\nsys.exit(7)\n",
        encoding="utf-8",
    )
    if os.name == "posix":
        fake_python.chmod(0o755)
    with pytest.raises(GradleDbGuardInputError) as exc_info:
        preflight_python_executable(str(fake_python))
    assert exc_info.value.code == "python_preflight"


def test_helper_successful_python_preflight_passes() -> None:
    """A real Python interpreter must pass the preflight check."""
    preflight_python_executable(sys.executable)  # must not raise


def test_helper_all_required_inputs_valid_passes(tmp_path: Path) -> None:
    """All canonical required inputs present and valid -> validation passes."""
    root = tmp_path
    for rel, _prop in DEFAULT_DB_GUARD_INPUTS:
        target = root / rel
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("x", encoding="utf-8")

    resolved = validate_db_guard_inputs(root)
    assert sorted(resolved) == sorted(rel for rel, _prop in DEFAULT_DB_GUARD_INPUTS)
