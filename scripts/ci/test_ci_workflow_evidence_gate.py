#!/usr/bin/env python3
"""Contract tests pinning the PR-GR-10d evidence-gate CI stage.

PR-GR-10d wires the §5 two-run DB-guard evidence gate (docs/ci/
GR00-GR04_validation_checklist.md) into .github/workflows/ci.yml: on every
PR, the ``evidence-gate`` job must run scripts/ci/capture_db_guard_evidence.py
twice at the caller-pinned ``--expected-sha`` (run-1/run-2), compare the two
deterministic ``semantic-summary.json`` files for byte equality, and publish
both bundles as artifacts unconditionally. These tests pin that wiring by
text so the stage cannot silently drift; workflow YAML *syntax* itself is
validated separately by the actionlint ``validate-workflow`` job.

Stdlib-only by design (no pyyaml dependency), mirroring the repo's
text-pin contract-test style.
"""

import re
from pathlib import Path

import pytest

_SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = _SCRIPT_DIR.parent.parent
WORKFLOW_PATH = REPO_ROOT / ".github" / "workflows" / "ci.yml"

CAPTURE_TOOL = "scripts/ci/capture_db_guard_evidence.py"


def _workflow_text() -> str:
    if not WORKFLOW_PATH.is_file():
        pytest.fail(f"missing CI workflow file: {WORKFLOW_PATH}")
    return WORKFLOW_PATH.read_text(encoding="utf-8")


def _job_block(text: str, job_name: str) -> str:
    """Return the top-level ``jobs:`` block for ``job_name``.

    A job block starts at its two-space-indented ``<name>:`` key and ends
    before the next two-space-indented job key (or end of file). Job-internal
    keys are indented deeper, so this reliably isolates one job.
    """
    lines = text.splitlines()
    start = None
    for i, line in enumerate(lines):
        if line == f"  {job_name}:":
            start = i
            break
    if start is None:
        pytest.fail(f"job '{job_name}' not found in {WORKFLOW_PATH.name}")
    end = len(lines)
    for j in range(start + 1, len(lines)):
        if re.fullmatch(r"  [A-Za-z][A-Za-z0-9_-]*:", lines[j]):
            end = j
            break
    return "\n".join(lines[start:end])


def _capture_command_lines(block: str) -> list:
    """The workflow ``run:`` lines invoking the capture tool (comments excluded)."""
    return [
        line for line in block.splitlines()
        if line.strip().startswith("run:") and CAPTURE_TOOL in line
    ]


def test_evidence_gate_job_exists_after_compile():
    """The gate runs after the compile job, parallel to the test/guard jobs."""
    block = _job_block(_workflow_text(), "evidence-gate")
    assert re.search(r"^    needs: \[validate-workflow, compile\]$", block, re.M), (
        "evidence-gate must need validate-workflow and compile"
    )


def test_evidence_gate_runs_on_every_pr():
    """No job-level ``if:`` may gate the evidence gate off pull_request runs.

    Job-level keys sit at four-space indent; step-level ``if:`` (the always()
    artifact uploads) is indented deeper and is not affected.
    """
    block = _job_block(_workflow_text(), "evidence-gate")
    assert not re.search(r"^    if:", block, re.M)


def test_evidence_gate_two_run_capture_structure():
    """Exactly two capture invocations: run-1 and run-2, both caller-pinned."""
    block = _job_block(_workflow_text(), "evidence-gate")
    commands = _capture_command_lines(block)
    assert len(commands) == 2, (
        "the two-run protocol requires exactly two capture invocations"
    )
    for command in commands:
        assert "--root ." in command
        assert "--expected-sha" in command
        assert "--out build/guard-evidence/" in command
    assert sum("/run-1" in command for command in commands) == 1
    assert sum("/run-2" in command for command in commands) == 1


def test_evidence_gate_pin_is_caller_stated_from_rev_parse_head():
    """The run pin must be stated by the caller from an explicit
    ``git rev-parse HEAD`` step — the tool never derives it silently."""
    block = _job_block(_workflow_text(), "evidence-gate")
    assert 'echo "sha=$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"' in block


def test_evidence_gate_compares_semantic_summaries_byte_identical():
    """The two runs are gated on byte-equal deterministic semantic summaries."""
    block = _job_block(_workflow_text(), "evidence-gate")
    assert "cmp -s" in block, "the comparison must be a byte-equality check"
    assert "run-1/semantic-summary.json" in block
    assert "run-2/semantic-summary.json" in block


def test_evidence_gate_uploads_both_bundles_always():
    """Both bundles publish as artifacts even when an earlier step failed."""
    block = _job_block(_workflow_text(), "evidence-gate")
    assert "name: evidence-run-1" in block
    assert "name: evidence-run-2" in block
    always_steps = re.findall(r"^        if: always\(\)$", block, re.M)
    assert len(always_steps) == 2


def test_evidence_gate_disables_pytest_cacheprovider_for_children():
    """Zero-side-effect pin: no pytest child inside the capture may materialize
    an untracked .pytest_cache/ — the capture's post-matrix ``git status``
    re-check fails closed on any untracked drift, and .pytest_cache/ is not
    git-ignored. The static suite's guard_tests child does not disable it
    itself, so the job env must."""
    block = _job_block(_workflow_text(), "evidence-gate")
    assert re.search(r"^      PYTEST_ADDOPTS: .*no:cacheprovider", block, re.M)
