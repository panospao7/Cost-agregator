"""CL-22 evidence workflow wiring; capture CLI rejection tests remain mandatory."""
from pathlib import Path

import yaml


ROOT = Path(__file__).resolve().parents[2]


def _workflow():
    # BaseLoader preserves the GitHub Actions key 'on' instead of coercing it
    # to YAML-1.1's boolean True.
    return yaml.load((ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8"),
                     Loader=yaml.BaseLoader)


def test_both_captures_receive_the_same_validated_caller_base():
    steps = _workflow()["jobs"]["evidence-gate"]["steps"]
    checkout = next(step for step in steps if step.get("uses", "").startswith("actions/checkout@"))
    assert checkout["with"]["fetch-depth"] == "0"
    pin = next(step for step in steps if step.get("id") == "sha")
    assert pin["env"]["BASE_REF"] == (
        "${{ github.event.pull_request.base.sha || github.event.before || inputs.evidence_base_ref }}")
    assert "^[0-9a-f]{40}$" in pin["run"]
    assert 'git cat-file -e "${BASE_REF}^{commit}" || exit 2' in pin["run"]
    assert 'git merge-base HEAD "$BASE_REF" > /dev/null || exit 2' in pin["run"]
    assert "EVIDENCE_BASE_REF_INVALID" in pin["run"]
    captures = [step["run"] for step in steps
                if step.get("name", "").startswith("Capture evidence (")]
    assert len(captures) == 2
    for command in captures:
        assert "--base-ref ${{ steps.sha.outputs.base_ref }}" in command
        assert "--expected-sha ${{ steps.sha.outputs.sha }}" in command
        assert "--allow-dirty" not in command


def test_manual_evidence_run_requires_an_explicit_base_pin():
    entry = _workflow()["on"]["workflow_dispatch"]["inputs"]["evidence_base_ref"]
    assert entry["required"] == "true"
    assert entry["type"] == "string"
