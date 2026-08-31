#!/usr/bin/env python3
"""
test_gradle_registered_guard_contract.py

PR-GR-10A Slice 3 — contract tests pinning the NEW task command shape for the
Gradle guard wrappers migrated to the registered runner bridge
(``scripts/ci/run_registered_guard.py``):

  * ``:app:checkDirectTimeCalls``  -> guard id ``time_boundaries``
  * ``:app:checkRawMoneyAggregates`` -> guard id ``raw_money_aggregates``
    (EXTRACTED_AND_REGISTERED from the retired inline KTS scanner)

and the cross-task invariants of the migration:

  * every guard wrapper passes ``--context gradle`` and ``--ci-mode``;
  * NO Gradle guard task constructs a child command, ratchet argv, baseline
    or policy path list, or timeout arithmetic (registry + plan compiler own
    them);
  * the retired inline lifecycle scanner tasks (``checkLifecycleBypasses``,
    ``checkLifecycleBypass``) are gone from the build and from the ``check``
    lifecycle (SUBSUMED_AND_RETIRED — see
    scripts/test_lifecycle_scanner_subsumption.py for the proof);
  * the shared bridge helper executes a token list with ``shell=False``
    semantics (``commandLine``), never a shell string.

Run:
    python -m pytest scripts/ci/test_gradle_registered_guard_contract.py -v
"""

import re
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
GRADLE_BUILD = REPO_ROOT / "app" / "build.gradle.kts"
RUNNER_RELATIVE = "scripts/ci/run_registered_guard.py"

# (task name, registered guard id) pairs for every migrated wrapper.
MIGRATED_TASKS = [
    ("checkDirectTimeCalls", "time_boundaries"),
    ("checkRawMoneyAggregates", "raw_money_aggregates"),
]

RETIRED_TASKS = ["checkLifecycleBypasses", "checkLifecycleBypass"]


def _gradle_build_text() -> str:
    if not GRADLE_BUILD.is_file():
        raise AssertionError(f"missing app/build.gradle.kts at {GRADLE_BUILD}")
    return GRADLE_BUILD.read_text(encoding="utf-8")


def _task_text(task_name: str) -> str:
    """Return the source of one ``tasks.register("<name>")`` block."""
    build = _gradle_build_text()
    start_marker = f'tasks.register("{task_name}")'
    if start_marker not in build:
        raise AssertionError(f"{task_name} task not found in app/build.gradle.kts")
    start = build.index(start_marker)
    # The block ends at the next top-level task/wiring declaration.
    end_match = re.search(r"\ntasks\.(?:register|named)\(", build[start + 1:])
    end = start + 1 + end_match.start() if end_match else len(build)
    return build[start:end]


def _bridge_helper_text() -> str:
    """Return the shared runRegisteredGuardFromGradle helper source."""
    build = _gradle_build_text()
    marker = "fun runRegisteredGuardFromGradle"
    if marker not in build:
        raise AssertionError("registered-runner bridge helper not found")
    start = build.index(marker)
    end_match = re.search(r"\n(?:fun |android \{)", build[start + 1:])
    end = start + 1 + end_match.start() if end_match else len(build)
    return build[start:end]


# ---------------------------------------------------------------------------
# Per-task wrapper contract
# ---------------------------------------------------------------------------


def test_each_migrated_task_invokes_the_runner_bridge_for_its_guard() -> None:
    for task_name, guard_id in MIGRATED_TASKS:
        task = _task_text(task_name)
        assert RUNNER_RELATIVE in task, task_name
        assert f'guardId = "{guard_id}"' in task, (
            f"{task_name} must run the registered guard {guard_id}"
        )


def test_each_migrated_task_passes_ci_mode() -> None:
    for task_name, _guard_id in MIGRATED_TASKS:
        task = _task_text(task_name)
        assert 'extraArgs = listOf("--ci-mode")' in task, task_name


def test_each_migrated_task_validates_runner_and_preflights_python() -> None:
    for task_name, _guard_id in MIGRATED_TASKS:
        task = _task_text(task_name)
        assert "validateRegisteredRunnerInput(taskName, runnerFile)" in task, task_name
        assert "pythonPreflightOrThrow(taskName, pythonExecutable)" in task, task_name
        assert "val pythonExecutable = pythonInterpreter()" in task, task_name


def test_each_migrated_task_maps_exit_codes_fail_closed() -> None:
    for task_name, _guard_id in MIGRATED_TASKS:
        task = _task_text(task_name)
        assert "onViolation = {" in task, task_name
        assert "onInfra = {" in task, task_name
        assert "GradleException" in task, task_name


def test_no_migrated_task_constructs_a_child_command() -> None:
    """No wrapper owns ratchet argv, baseline flags, policy paths, or
    timeout arithmetic — the registry execution schema owns them."""
    for task_name, _guard_id in MIGRATED_TASKS:
        task = _task_text(task_name)
        assert "--command-arg" not in task, task_name
        assert '"--baseline"' not in task, task_name
        assert "--timeout" not in task, task_name
        assert "resolveDbGuardPath" not in task, task_name


# ---------------------------------------------------------------------------
# Cross-task migration invariants
# ---------------------------------------------------------------------------


def test_bridge_helper_uses_token_list_and_gradle_context() -> None:
    helper = _bridge_helper_text()
    assert '"--guard-id", guardId' in helper
    assert '"--context", "gradle"' in helper
    assert '"--root", rootDir.canonicalFile.absolutePath' in helper
    assert "commandLine(commandArgs)" in helper
    assert "isIgnoreExitValue = true" in helper
    # Universal exit mapping.
    assert "1 -> onViolation()" in helper
    assert "2 -> onInfra()" in helper
    assert "unexpected exit code" in helper


def test_retired_inline_lifecycle_tasks_are_gone() -> None:
    """SUBSUMED_AND_RETIRED: the inline lifecycle scanner tasks no longer
    exist anywhere in the build script."""
    build = _gradle_build_text()
    for task_name in RETIRED_TASKS:
        assert f'tasks.register("{task_name}")' not in build, task_name
        assert f'dependsOn("{task_name}")' not in build, task_name


def test_retired_lifecycle_scanner_internals_are_gone() -> None:
    """The retired scanners' inline rule tables and allowlists no longer
    exist in the build script (the canonical db_access guard owns them)."""
    build = _gradle_build_text()
    assert "expenseDao.updateCategory(" not in build
    assert "expenseDao\\.insert" not in build
    assert "srcDirForGuard" not in build
    assert "allowlistForGuard" not in build


def test_check_lifecycle_wires_exactly_the_registered_wrappers() -> None:
    """The check lifecycle wires the migrated wrappers and no retired task."""
    build = _gradle_build_text()
    wiring_blocks = re.findall(r'tasks\.named\("check"\)\s*\{([^}]*)\}', build)
    joined = "\n".join(wiring_blocks)
    for task_name, _guard_id in MIGRATED_TASKS:
        assert f'dependsOn("{task_name}")' in joined, task_name
    for task_name in RETIRED_TASKS:
        assert f'dependsOn("{task_name}")' not in joined, task_name


def test_db_wrapper_contract_is_pinned_in_the_db_contract_file() -> None:
    """Guard against accidental drift between the two contract files: the DB
    task must still exist and invoke the bridge (deep pins live in
    test_gradle_db_guard_contract.py)."""
    task = _task_text("verifyDbAccessBoundaries")
    assert RUNNER_RELATIVE in task
    assert 'guardId = "db_access"' in task
