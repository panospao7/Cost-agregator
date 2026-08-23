"""Characterization of the CURRENT pre-v2 blocked state of the active DB gate.

This is NOT an assertion that the block is correct; it only pins today's
observed behavior (exit code 2 with the single umbrella stderr line and the
controlled DB_POLICY_SOURCE_EVIDENCE_INVALID diagnostic in the findings JSON;
detailed codes such as SIGNATURE_MISSING stay internal). v2 activation belongs
to GR-07.
This file must be removed/updated when the gate goes green.
"""

import json
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]


# GR-04 triage aligned the test to the v2 report contract (pre-existing staleness, not a weakening).
def test_active_db_gate_reports_blocked_pre_v2(tmp_path):
    findings_output = tmp_path / "db_guard_findings.json"
    result = subprocess.run(
        [
            sys.executable,
            "scripts/verify_db_access_boundaries.py",
            "--fail-on-violation",
            "--ownership-policy",
            "config/guards/db_ownership_policy.yml",
            "--structural-exceptions",
            "config/guards/db_structural_exceptions.yml",
            "--structural-manifest",
            "config/guards/db_structural_exceptions_expected_methods.yml",
            "--findings-output",
            str(findings_output),
        ],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
    )
    combined = result.stdout + result.stderr
    assert result.returncode == 2, combined
    # Detailed codes never reach the streams: stderr carries exactly one
    # umbrella line and the controlled diagnostic lands in the findings JSON.
    assert combined.strip() == (
        "ERROR: DB access discovery infrastructure diagnostics present"
    )
    report = json.loads(findings_output.read_text(encoding="utf-8"))
    codes = [diagnostic.get("code") for diagnostic in report["diagnostics"]]
    assert codes == ["DB_POLICY_SOURCE_EVIDENCE_INVALID"]
