"""Characterization of the CURRENT pre-v2 blocked state of the active DB gate.

This is NOT an assertion that the block is correct; it only pins today's
observed behavior (exit code 2 with SIGNATURE_MISSING and
DB_POLICY_SOURCE_EVIDENCE_INVALID). v2 activation belongs to GR-07.
This file must be removed/updated when the gate goes green.
"""

import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[1]


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
    assert "SIGNATURE_MISSING" in combined
    assert "DB_POLICY_SOURCE_EVIDENCE_INVALID" in combined
