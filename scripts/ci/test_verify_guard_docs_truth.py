#!/usr/bin/env python3
"""
test_verify_guard_docs_truth.py — PR-GR-10D tests.

Two layers:

1. Fixture tests: a minimal synthetic repository (tiny registry, document
   index, evidence index, renderer-produced generated docs) proves each
   closed-contract check fails closed on mutation and passes on the honest
   fixture.
2. Live grounding tests: the real repository's contract holds — the live
   validator passes, the tracked generated documents are byte-reproducible
   across two renders, the evidence index carries the GATE-00R double-capture
   record, and the guard_docs_truth guard is wired into the registry/suite.

Run:
    python -m pytest scripts/ci/test_verify_guard_docs_truth.py -v
"""

from __future__ import annotations

import os
import sys

import pytest

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
_PROJECT_ROOT = os.path.dirname(os.path.dirname(_SCRIPT_DIR))
for _path in (_SCRIPT_DIR, _PROJECT_ROOT):
    if _path not in sys.path:
        sys.path.insert(0, _path)

import generate_guard_docs  # noqa: E402
import verify_guard_docs_truth as vgt  # noqa: E402
from guard_registry import GUARD_REGISTRY  # noqa: E402
from run_static_guard_suite import SUITE_GUARD_ORDER  # noqa: E402

REPO_ROOT = _PROJECT_ROOT

_SHA = "a" * 40
_DIGEST = "b" * 64
_MANIFEST_DIGEST = "c" * 64

_FIXTURE_REGISTRY = '''#!/usr/bin/env python3
GUARD_REGISTRY = {
    "fixture_guard": {
        "script": "scripts/fixture_guard.py",
        "sourceScope": "repository-config",
        "tests": None,
        "mode": "blocking",
        "baseline": None,
        "allowlist": None,
        "policies": None,
        "description": "Fixture guard for the docs-truth validator tests.",
        "execution": {
            "engine": "python-direct",
            "entrypoint": "scripts/fixture_guard.py",
            "arguments": ("--root", "."),
            "mode": "blocking",
            "requiredInputs": (),
            "timeoutProfile": "standard",
            "outputContract": "stdout-human;exit:0=pass,1=violation,2=infra",
            "testManifest": "none",
            "documentationAnchor": "docs/ci/anchor.md",
        },
    },
}
'''

_DOCUMENT_INDEX = """\
schemaVersion: 1
documents:
  - id: anchor-doc
    path: docs/ci/anchor.md
    classification: CURRENT_ARCHITECTURE
    authorityType: explanatory
    owner: "@tester"
    lastReviewedSha: {sha}
    currentStateSource:
      registry: scripts/ci/guard_registry.py
      implementation: scripts/fixture_guard.py
      evidence: docs/ci/GUARD_EVIDENCE_INDEX.yml
    historicalClaimsAllowed: false
  - id: hist-doc
    path: docs/ci/hist.md
    classification: HISTORICAL_RECORD
    authorityType: audit-snapshot
    owner: "@tester"
    lastReviewedSha: {sha}
    currentStateSource:
      registry: scripts/ci/guard_registry.py
      evidence: docs/ci/GUARD_EVIDENCE_INDEX.yml
    historicalClaimsAllowed: true
  - id: plan-doc
    path: docs/ci/plan.md
    classification: PLAN_OR_BACKLOG
    authorityType: plan
    owner: "@tester"
    lastReviewedSha: {sha}
    currentStateSource:
      registry: scripts/ci/guard_registry.py
    historicalClaimsAllowed: true
""".format(sha=_SHA)

_EVIDENCE_INDEX = """\
schemaVersion: 1
records:
  - evidenceId: gate-00r-{sha}
    targetSha: {sha}
    targetTreeSha: {tree}
    baseSha: {base}
    mergeBaseSha: {base}
    workingTreeClean: true
    captureRuns:
      - runId: run-01
        semanticDigestSha256: {digest}
        artifactManifestSha256: {manifest}
      - runId: run-02
        semanticDigestSha256: {digest}
        artifactManifestSha256: {manifest}
    reproducible: true
    guardResults:
      fixture_guard:
        directExit: 0
        trusted: true
        findingCount: 0
        blockingDiagnosticCount: 0
        advisoryDiagnosticCount: 0
        staticSuiteOutcome: PASS
    staticSuite:
      exit: 0
      legsTotal: 1
      passed: 1
      violations: 0
      infraErrors: 0
      legOutcomes:
        fixture_guard: PASS
    status: COMPLETE
""".format(sha=_SHA, tree="d" * 40, base="e" * 40,
           digest=_DIGEST, manifest=_MANIFEST_DIGEST)

_ANCHOR_DOC = """\
# Fixture anchor

The fixture guard's current-state authority. No claims here.
"""

_HIST_DOC = """\
# Frozen fixture audit

> **Historical record** — as-of SHA {sha}; scope: the fixture audit only;
> it is not evidence for current HEAD. Current authority:
> docs/ci/GUARD_EVIDENCE_INDEX.yml.

DONE and GREEN claims below are historical wording permitted here.
""".format(sha=_SHA)

_PLAN_DOC = """\
# Fixture plan

This plan describes future work for the fixture guard.
"""


def _build_fixture(root: str) -> None:
    """Create the minimal honest fixture repository."""
    os.makedirs(os.path.join(root, "scripts", "ci"), exist_ok=True)
    os.makedirs(os.path.join(root, "docs", "ci"), exist_ok=True)
    with open(os.path.join(root, "scripts", "ci", "guard_registry.py"),
              "w", encoding="utf-8", newline="\n") as handle:
        handle.write(_FIXTURE_REGISTRY)
    with open(os.path.join(root, "scripts", "fixture_guard.py"),
              "w", encoding="utf-8", newline="\n") as handle:
        handle.write("# fixture guard entrypoint\n")
    with open(os.path.join(root, "docs", "ci", "GUARD_DOCUMENT_INDEX.yml"),
              "w", encoding="utf-8", newline="\n") as handle:
        handle.write(_DOCUMENT_INDEX)
    with open(os.path.join(root, "docs", "ci", "GUARD_EVIDENCE_INDEX.yml"),
              "w", encoding="utf-8", newline="\n") as handle:
        handle.write(_EVIDENCE_INDEX)
    with open(os.path.join(root, "docs", "ci", "anchor.md"),
              "w", encoding="utf-8", newline="\n") as handle:
        handle.write(_ANCHOR_DOC)
    with open(os.path.join(root, "docs", "ci", "hist.md"),
              "w", encoding="utf-8", newline="\n") as handle:
        handle.write(_HIST_DOC)
    with open(os.path.join(root, "docs", "ci", "plan.md"),
              "w", encoding="utf-8", newline="\n") as handle:
        handle.write(_PLAN_DOC)
    commands, status = generate_guard_docs.render_guard_documents(root)
    with open(os.path.join(root, "docs", "ci", "GUARD_COMMANDS.generated.md"),
              "w", encoding="utf-8", newline="\n") as handle:
        handle.write(commands)
    with open(os.path.join(root, "docs", "ci", "GUARD_STATUS.generated.md"),
              "w", encoding="utf-8", newline="\n") as handle:
        handle.write(status)


def _write(root: str, rel: str, text: str) -> None:
    with open(os.path.join(root, rel), "w", encoding="utf-8",
              newline="\n") as handle:
        handle.write(text)


def _read(root: str, rel: str) -> str:
    with open(os.path.join(root, rel), "r", encoding="utf-8") as handle:
        return handle.read()


def _codes(violations):
    return {code for code, _path, _detail in violations}


# ── 1. Fixture: the honest fixture passes ───────────────────────────────────────


def test_honest_fixture_passes(tmp_path):
    _build_fixture(str(tmp_path))
    violations, _ = vgt.run_validator(str(tmp_path))
    assert violations == [], violations


def test_missing_indexed_doc_fails(tmp_path):
    _build_fixture(str(tmp_path))
    os.unlink(os.path.join(str(tmp_path), "docs", "ci", "hist.md"))
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_DOC_MISSING" in _codes(violations)


def test_abbreviated_target_sha_fails(tmp_path):
    _build_fixture(str(tmp_path))
    _write(str(tmp_path), "docs/ci/GUARD_EVIDENCE_INDEX.yml",
           _EVIDENCE_INDEX.replace(_SHA, _SHA[:12]))
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_EVIDENCE_RECORD_INVALID" in _codes(violations)


def test_single_capture_run_cannot_be_complete(tmp_path):
    _build_fixture(str(tmp_path))
    text = _read(str(tmp_path), "docs/ci/GUARD_EVIDENCE_INDEX.yml")
    # Drop the second capture run.
    lines = [ln for ln in text.splitlines()
             if "run-02" not in ln]
    _write(str(tmp_path), "docs/ci/GUARD_EVIDENCE_INDEX.yml",
           "\n".join(lines) + "\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_EVIDENCE_RECORD_INVALID" in _codes(violations)


def test_differing_run_digests_cannot_be_complete(tmp_path):
    _build_fixture(str(tmp_path))
    text = _read(str(tmp_path), "docs/ci/GUARD_EVIDENCE_INDEX.yml")
    text = text.replace(_DIGEST, "f" * 64, 1)
    _write(str(tmp_path), "docs/ci/GUARD_EVIDENCE_INDEX.yml", text)
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_EVIDENCE_RECORD_INVALID" in _codes(violations)


def test_missing_manifest_digest_fails_for_complete_record(tmp_path):
    _build_fixture(str(tmp_path))
    text = _read(str(tmp_path), "docs/ci/GUARD_EVIDENCE_INDEX.yml")
    text = text.replace("        artifactManifestSha256: " + _MANIFEST_DIGEST
                        + "\n", "", 1)
    _write(str(tmp_path), "docs/ci/GUARD_EVIDENCE_INDEX.yml", text)
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_EVIDENCE_RECORD_INVALID" in _codes(violations)


def test_unknown_guard_id_in_evidence_fails(tmp_path):
    _build_fixture(str(tmp_path))
    text = _read(str(tmp_path), "docs/ci/GUARD_EVIDENCE_INDEX.yml")
    text = text.replace("      fixture_guard:", "      not_a_guard:")
    _write(str(tmp_path), "docs/ci/GUARD_EVIDENCE_INDEX.yml", text)
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_EVIDENCE_RECORD_INVALID" in _codes(violations)


def test_unsupported_status_claim_fails(tmp_path):
    _build_fixture(str(tmp_path))
    _write(str(tmp_path), "docs/ci/anchor.md",
           _ANCHOR_DOC + "\nStatus: DONE\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_CLAIM_UNSUPPORTED" in _codes(violations)


def test_status_claim_with_evidence_sha_passes(tmp_path):
    _build_fixture(str(tmp_path))
    _write(str(tmp_path), "docs/ci/anchor.md",
           _ANCHOR_DOC
           + "\nStatus: VERIFIED at gate-00r-" + _SHA + "\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert violations == [], violations


def test_unqualified_v1_active_claim_fails(tmp_path):
    _build_fixture(str(tmp_path))
    _write(str(tmp_path), "docs/ci/anchor.md",
           _ANCHOR_DOC
           + "\nThe v1 policy remains the ACTIVE gate until v2 activation.\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_DB_STATE_STALE_CLAIM" in _codes(violations)


def test_baseline_authorization_claim_fails(tmp_path):
    _build_fixture(str(tmp_path))
    _write(str(tmp_path), "docs/ci/anchor.md",
           _ANCHOR_DOC + "\nThe baseline authorizes this write.\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_BASELINE_AUTHORIZATION_CLAIM" in _codes(violations)


def test_negated_baseline_wording_passes(tmp_path):
    _build_fixture(str(tmp_path))
    _write(str(tmp_path), "docs/ci/anchor.md",
           _ANCHOR_DOC
           + "\nThe baseline authorizes nothing; it is debt only.\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert violations == [], violations


def test_historical_wording_requires_banner(tmp_path):
    _build_fixture(str(tmp_path))
    _write(str(tmp_path), "docs/ci/hist.md",
           "# Frozen fixture audit\n\nDONE and GREEN everywhere.\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_HISTORICAL_UNMARKED" in _codes(violations)


def test_plan_presenting_active_work_fails(tmp_path):
    _build_fixture(str(tmp_path))
    _write(str(tmp_path), "docs/ci/plan.md",
           _PLAN_DOC + "\nThe fixture guard is now active.\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_PLAN_PRESENTS_ACTIVE" in _codes(violations)


def test_generated_doc_drift_fails(tmp_path):
    _build_fixture(str(tmp_path))
    _write(str(tmp_path), "docs/ci/GUARD_COMMANDS.generated.md",
           _read(str(tmp_path), "docs/ci/GUARD_COMMANDS.generated.md")
           + "\nhand edit\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_GENERATED_NOT_REPRODUCIBLE" in _codes(violations)


def test_unknown_path_reference_fails(tmp_path):
    _build_fixture(str(tmp_path))
    _write(str(tmp_path), "docs/ci/anchor.md",
           _ANCHOR_DOC + "\nSee config/guards/does_not_exist.yml.\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_UNKNOWN_PATH_REFERENCE" in _codes(violations)


def test_status_block_drift_fails(tmp_path):
    _build_fixture(str(tmp_path))
    index = _read(str(tmp_path), "docs/ci/GUARD_DOCUMENT_INDEX.yml")
    index = index.replace(
        "  - id: anchor-doc\n    path: docs/ci/anchor.md",
        "  - id: anchor-doc\n    path: docs/ci/anchor.md\n"
        "    generatedSections:\n      - guard-status-summary",
    )
    _write(str(tmp_path), "docs/ci/GUARD_DOCUMENT_INDEX.yml", index)
    _write(str(tmp_path), "docs/ci/anchor.md",
           _ANCHOR_DOC
           + "\n<!-- GUARD_STATUS:BEGIN fixture_guard -->\n"
           "Status: VERIFIED_AT_SHA\n"
           "<!-- GUARD_STATUS:END fixture_guard -->\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_STATUS_BLOCK_DRIFT" in _codes(violations)


def test_unknown_evidence_reference_fails(tmp_path):
    _build_fixture(str(tmp_path))
    fake_sha = "9" * 40
    _write(str(tmp_path), "docs/ci/anchor.md",
           _ANCHOR_DOC + "\nEvidence: gate-00r-" + fake_sha + "\n")
    violations, _ = vgt.run_validator(str(tmp_path))
    assert "E_EVIDENCE_REF_INVALID" in _codes(violations)


def test_renderer_is_deterministic(tmp_path):
    _build_fixture(str(tmp_path))
    first = generate_guard_docs.render_guard_documents(str(tmp_path))
    second = generate_guard_docs.render_guard_documents(str(tmp_path))
    assert first == second


# ── 2. Live repository grounding ────────────────────────────────────────────────


def test_live_validator_passes():
    violations, _ = vgt.run_validator(REPO_ROOT)
    assert violations == [], violations


def test_live_generated_docs_byte_reproducible_twice():
    commands, status = generate_guard_docs.render_guard_documents(REPO_ROOT)
    with open(os.path.join(REPO_ROOT, "docs", "ci",
                           "GUARD_COMMANDS.generated.md"),
              "r", encoding="utf-8") as handle:
        assert handle.read() == commands
    with open(os.path.join(REPO_ROOT, "docs", "ci",
                           "GUARD_STATUS.generated.md"),
              "r", encoding="utf-8") as handle:
        assert handle.read() == status
    again = generate_guard_docs.render_guard_documents(REPO_ROOT)
    assert again == (commands, status)


def test_live_gate_00r_record_supports_verified_at_sha():
    import yaml

    with open(os.path.join(REPO_ROOT, "docs", "ci",
                           "GUARD_EVIDENCE_INDEX.yml"),
              "r", encoding="utf-8") as handle:
        index = yaml.safe_load(handle)
    current = [r for r in index["records"]
               if r.get("status") == "COMPLETE" and r.get("reproducible")]
    assert len(current) == 1
    record = current[0]
    assert record["targetSha"] == (
        "565018c5eed61fae4351cb59342dc5c274eb27e7"
    )
    runs = record["captureRuns"]
    assert len(runs) == 2
    digests = {r["semanticDigestSha256"] for r in runs}
    assert len(digests) == 1
    db = record["guardResults"]["db_access"]
    assert db["directExit"] == 0
    assert db["trusted"] is True
    assert db["findingCount"] == 0
    assert db["advisoryDiagnosticCount"] == 20
    assert db["blockingDiagnosticCount"] == 0
    assert db["ratchetExit"] == 0


def test_guard_docs_truth_is_registered_repository_config():
    entry = GUARD_REGISTRY["guard_docs_truth"]
    assert entry["sourceScope"] == "repository-config"
    assert entry["mode"] == "blocking"
    assert entry["tests"] == "scripts/ci/test_verify_guard_docs_truth.py"
    execution = entry["execution"]
    assert execution["engine"] == "python-direct"
    assert execution["documentationAnchor"] == "docs/ci/GUARD_DOCUMENT_INDEX.yml"


def test_guard_docs_truth_is_in_suite_order():
    assert "guard_docs_truth" in SUITE_GUARD_ORDER


def test_historical_docs_carry_visible_banners():
    import yaml

    with open(os.path.join(REPO_ROOT, "docs", "ci",
                           "GUARD_DOCUMENT_INDEX.yml"),
              "r", encoding="utf-8") as handle:
        index = yaml.safe_load(handle)
    for entry in index["documents"]:
        if entry["classification"] != "HISTORICAL_RECORD":
            continue
        with open(os.path.join(REPO_ROOT, entry["path"]),
                  "r", encoding="utf-8") as handle:
            head = "\n".join(handle.read().splitlines()[:15])
        assert "historical record" in head.lower(), entry["path"]


if __name__ == "__main__":
    sys.exit(pytest.main([__file__, "-v", "--tb=short"]))
