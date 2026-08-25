#!/usr/bin/env python3
"""
test_verify_db_policy_v2_evidence.py -- Pytest suite for PR-GR-06 Slice 3.

Covers scripts/ci/verify_db_policy_v2_evidence.py:

  1. Happy path: trusted candidate -> exit 0 + deterministic report schema.
  2. Untrusted candidate -> exit 2 (report still written, trusted=false).
  3. Missing candidate file -> fail-closed exit 2, no report written.
  4. Output collision with the active policy / candidate rejected (exit 2).
  5-9. Shadow classification matrix: every one of the five closed classes
       (EXPECTED_LEGACY_OVERLOAD_UNION, CANDIDATE_GAP, LEGACY_STALE_ENTRY,
       PARSER_OR_RESOLVER_DEFECT, UNREVIEWED_DIFFERENCE) at least once via
       synthetic legacy reports + synthetic GR-05 accounting artifacts.
  10. The shadow comparison NEVER changes the v2 exit code.
  11. Byte-determinism across two runs.
  12. No absolute paths anywhere in the report.
  13. Unsupported legacy-report schema degrades boundedly (report-only).
  14. ``reviewed: false`` flag + GR-07 blocking note present.
  15. Tokenized-argv CLI invocation (subprocess, shell=False).
  16. Unreadable accounting artifact degrades to UNREVIEWED, exit unchanged.

Every filesystem test builds its own synthetic repository under ``tmp_path``
(manifest, raw-query policy, Kotlin sources, candidate, accounting artifact,
legacy report); no test scans the real repository or executes Gradle.

Run:
    python -m pytest scripts/ci/test_verify_db_policy_v2_evidence.py -v
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import verify_db_policy_v2_evidence as v2e  # noqa: E402

# ── Fixture constants ────────────────────────────────────────────────────────

MANIFEST_RELPATH = "config/guards/production_source_roots.yml"
MANIFEST_TEXT = (
    "schemaVersion: 1\n"
    "roots:\n"
    '  - module: ":app"\n'
    "    sourceSet: main\n"
    "    path: app/src/main/java\n"
)

RAW_QUERY_POLICY_RELPATH = "config/guards/db_raw_query_classification.yml"
RAW_QUERY_POLICY_TEXT = "version: 1\nmethods: []\n"

CANDIDATE_RELPATH = (
    "config/guards/db_ownership_policy.signatures.candidate.yml"
)
ACTIVE_POLICY_RELPATH = "config/guards/db_ownership_policy.yml"

REPO_KT = "app/src/main/java/com/example/Repo.kt"
DAO_KT = "app/src/main/java/com/example/data/GroupDao.kt"
OWNER = "com.example.Repo"

HAPPY_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
    }
}

data class Group(val id: Int)
"""

DAO_SOURCE = """\
package com.example.data

@Dao
interface GroupDao {
    @Insert
    fun insert(group: Group)
}

data class Group(val id: Int)
"""


def _candidate_text(operation="insert"):
    """A minimal valid v2 candidate with one entry matching HAPPY_SOURCE."""
    return (
        "schemaVersion: 2\n"
        "entries:\n"
        "- path: " + REPO_KT + "\n"
        "  ownerFqcn: " + OWNER + "\n"
        "  kind: function\n"
        "  method: insertGroup\n"
        "  receiver: null\n"
        "  parameterTypes:\n"
        "  - com.example.Group\n"
        "  daoAccessor: groupDao\n"
        "  daoFqcn: com.example.data.GroupDao\n"
        "  operation: " + operation + "\n"
        "  barrierMode: direct\n"
        "  reason: synthetic shadow fixture entry\n"
        "  owner: db-guard-tests\n"
        "  linkedIssue: GR06-S3-T\n"
    )


# ── Repository / artifact builders ───────────────────────────────────────────


def _write(root, rel, content):
    target = Path(root).joinpath(*rel.split("/"))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")
    return target


def _make_repo(tmp_path, candidate_operation="insert"):
    """Synthetic repo: manifest, raw-query policy, Kotlin sources, candidate."""
    _write(tmp_path, MANIFEST_RELPATH, MANIFEST_TEXT)
    _write(tmp_path, RAW_QUERY_POLICY_RELPATH, RAW_QUERY_POLICY_TEXT)
    _write(tmp_path, REPO_KT, HAPPY_SOURCE)
    _write(tmp_path, DAO_KT, DAO_SOURCE)
    _write(tmp_path, CANDIDATE_RELPATH, _candidate_text(candidate_operation))
    return tmp_path


def _mkey(
    method,
    params="com.example.Group",
    accessor="groupDao",
    fqcn="com.example.data.GroupDao",
    op="insert",
    path=REPO_KT,
    owner=OWNER,
):
    """Canonical MUTATION key (callable prefix + accessor|fqcn|operation)."""
    return "|".join([path, owner, "function", method, "null", params, accessor, fqcn, op])


def _resolved_record(index, mutation_keys):
    return {
        "action": "EMIT_CANDIDATE",
        "detail": "",
        "index": index,
        "mutationKeys": mutation_keys,
        "outcome": "RESOLVED",
        "status": None,
    }


def _unresolved_record(index, status="PARSER_UNCERTAIN"):
    return {
        "action": "REVIEW_DEBT",
        "detail": "synthetic unresolved row",
        "index": index,
        "mutationKeys": [],
        "outcome": "UNRESOLVED",
        "status": status,
    }


def _intent(path, symbol, operation, indices):
    return {
        "kind": "OBSERVED_BUT_UNRESOLVED",
        "path": path,
        "symbol": symbol,
        "operation": operation,
        "legacyIndices": list(indices),
    }


def _write_accounting(tmp_path, records, source_mutations=None):
    document = {
        "schema": "db-policy-migration-accounting",
        "version": 1,
        "candidateSha256": "a" * 64,
        "inputCount": len(records),
        "records": records,
        "sourceMutations": source_mutations or [],
        "sourcePolicyPath": ACTIVE_POLICY_RELPATH,
        "sourcePolicySha256": "b" * 64,
        "sourceTreeSha": "c" * 64,
    }
    path = _write(
        tmp_path,
        "build/accounting.json",
        json.dumps(document, indent=2, sort_keys=True) + "\n",
    )
    return str(path)


def _finding(path, owner, name, kind="function"):
    return {
        "rule": "DB_SYNTHETIC_RULE",
        "severity": "error",
        "path": path,
        "location": {"line": 1},
        "symbol": {
            "owner": owner,
            "name": name,
            "receiver": None,
            "parameters": [],
            "kind": kind,
        },
        "identity": {},
        "message": "synthetic legacy finding",
    }


def _write_legacy_report(
    tmp_path,
    findings,
    diagnostics=None,
    schema=v2e.LEGACY_REPORT_SCHEMA,
    schema_version=v2e.LEGACY_REPORT_SCHEMA_VERSION,
):
    document = {
        "schema": schema,
        "schema_version": schema_version,
        "guard": "db_access",
        "findings": findings,
        "diagnostics": diagnostics or [],
        "statistics": {},
    }
    path = _write(
        tmp_path,
        "build/legacy_shadow_report.json",
        json.dumps(document, indent=2, sort_keys=True) + "\n",
    )
    return str(path)


# ── Invocation helpers ───────────────────────────────────────────────────────


def _run(tmp_path, extra, output_name="build/shadow_report.json"):
    output = str(Path(tmp_path) / output_name)
    argv = ["--root", str(tmp_path), "--output", output] + list(extra)
    with pytest.raises(SystemExit) as excinfo:
        v2e.main(argv)
    return excinfo.value.code, output


def _read_report(path):
    with open(path, "r", encoding="utf-8") as handle:
        return json.load(handle)


def _deltas(report):
    return report["shadow_comparison"]["deltas_by_class"]


def _entries_of_class(report, class_name):
    return [
        entry
        for entry in report["shadow_comparison"]["entries"]
        if entry["class"] == class_name
    ]


# ===========================================================================
# 1-4. Core CLI contract
# ===========================================================================


def test_happy_path_trusted_exit_zero_and_report_schema(tmp_path, capsys):
    _make_repo(tmp_path)
    code, output = _run(tmp_path, [])
    assert code == 0
    assert capsys.readouterr().out.strip().endswith("DB_V2_SHADOW_TRUSTED")

    report = _read_report(output)
    assert report["schema"] == v2e.REPORT_SCHEMA_NAME
    assert report["version"] == 1
    assert report["trusted"] is True
    assert report["policy_path"] == CANDIDATE_RELPATH
    candidate_sha = hashlib.sha256(
        Path(tmp_path).joinpath(*CANDIDATE_RELPATH.split("/")).read_bytes()
    ).hexdigest()
    assert report["policy_sha256"] == candidate_sha
    assert isinstance(report["tree_sha256"], str)
    assert len(report["tree_sha256"]) == 64
    assert len(report["groups"]) == 1
    assert report["groups"][0]["trusted"] is True
    assert report["diagnostics"] == []
    assert report["mutation_key_count"] == 1
    assert report["policy_mutation_key_count"] == 1
    assert "shadow_comparison" not in report


def test_untrusted_candidate_exits_two_and_writes_untrusted_report(tmp_path):
    _make_repo(tmp_path, candidate_operation="delete")
    code, output = _run(tmp_path, [])
    assert code == 2
    report = _read_report(output)
    assert report["trusted"] is False
    assert len(report["groups"]) == 1
    assert report["groups"][0]["trusted"] is False
    codes = [d["code"] for d in report["groups"][0]["diagnostics"]]
    assert codes, "untrusted group must carry bounded diagnostics"


def test_missing_candidate_file_fails_closed_exit_two_no_report(tmp_path, capsys):
    _make_repo(tmp_path)
    code, output = _run(tmp_path, ["--policy", "build/does_not_exist.yml"])
    assert code == 2
    assert not os.path.exists(output)
    lines = capsys.readouterr().out.splitlines()
    assert lines
    assert all(
        line.split(" ")[0].startswith(("POLICY_ERROR_", "DB_")) for line in lines
    )


def test_output_collision_with_active_policy_and_candidate_rejected(tmp_path):
    _make_repo(tmp_path)
    active = str(Path(tmp_path).joinpath(*ACTIVE_POLICY_RELPATH.split("/")))
    _write(tmp_path, ACTIVE_POLICY_RELPATH, "active: true\n")

    code, _output = _run(tmp_path, ["--output", ACTIVE_POLICY_RELPATH])
    assert code == 2
    assert Path(active).read_text(encoding="utf-8") == "active: true\n"

    code, _output = _run(tmp_path, ["--output", CANDIDATE_RELPATH])
    assert code == 2


# ===========================================================================
# 5-9. Shadow classification matrix (all five classes)
# ===========================================================================


def test_shadow_expected_legacy_overload_union_classified(tmp_path):
    _make_repo(tmp_path)
    accounting = _write_accounting(
        tmp_path,
        [_resolved_record(0, [_mkey("insertGroup"), _mkey("removeAll", op="delete")])],
    )
    legacy = _write_legacy_report(
        tmp_path,
        [
            _finding(REPO_KT, OWNER, "insertGroup"),  # agreement: no delta
            _finding(REPO_KT, OWNER, "removeAll"),
        ],
    )
    code, output = _run(
        tmp_path, ["--legacy-shadow-report", legacy, "--accounting", accounting]
    )
    assert code == 0  # comparison never influences the v2 exit code
    report = _read_report(output)
    deltas = _deltas(report)
    assert deltas[v2e.CLASS_EXPECTED_LEGACY_OVERLOAD_UNION] == 1
    assert sum(deltas.values()) == 1
    entry = _entries_of_class(report, v2e.CLASS_EXPECTED_LEGACY_OVERLOAD_UNION)[0]
    assert entry["target"].endswith("|function|removeAll")
    assert entry["legacy_row_indices"] == [0]
    assert entry["reason"] == "accounting-row-resolved"
    assert report["shadow_comparison"]["gr07_blocked"] is False


def test_shadow_legacy_stale_entry_classified(tmp_path):
    _make_repo(tmp_path)
    accounting = _write_accounting(
        tmp_path,
        [
            _resolved_record(0, [_mkey("insertGroup")]),
            _unresolved_record(3),
        ],
        source_mutations=[
            _intent(REPO_KT, OWNER + "#purgeAll", "delete", [3]),
        ],
    )
    legacy = _write_legacy_report(
        tmp_path,
        [
            _finding(REPO_KT, OWNER, "insertGroup"),  # agreement: no delta
            _finding(REPO_KT, OWNER, "purgeAll"),
        ],
    )
    code, output = _run(
        tmp_path, ["--legacy-shadow-report", legacy, "--accounting", accounting]
    )
    assert code == 0
    report = _read_report(output)
    deltas = _deltas(report)
    assert deltas[v2e.CLASS_LEGACY_STALE_ENTRY] == 1
    assert sum(deltas.values()) == 1
    entry = _entries_of_class(report, v2e.CLASS_LEGACY_STALE_ENTRY)[0]
    assert entry["target"].endswith("|function|purgeAll")
    assert entry["legacy_row_indices"] == [3]
    assert entry["reason"] == "accounting-row-unresolved"


def test_shadow_candidate_gap_classified(tmp_path):
    _make_repo(tmp_path)
    accounting = _write_accounting(
        tmp_path,
        [_unresolved_record(2)],
        source_mutations=[
            _intent(REPO_KT, OWNER + "#insertGroup", "insert", [2]),
        ],
    )
    legacy = _write_legacy_report(tmp_path, [])  # callable absent from legacy
    code, output = _run(
        tmp_path, ["--legacy-shadow-report", legacy, "--accounting", accounting]
    )
    assert code == 0
    report = _read_report(output)
    deltas = _deltas(report)
    assert deltas[v2e.CLASS_CANDIDATE_GAP] == 1
    assert sum(deltas.values()) == 1
    entry = _entries_of_class(report, v2e.CLASS_CANDIDATE_GAP)[0]
    assert entry["target"].endswith("|function|insertGroup")
    assert entry["legacy_row_indices"] == [2]
    assert entry["reason"] == "accounting-row-unresolved"
    assert report["shadow_comparison"]["gr07_blocked"] is True


def test_shadow_parser_or_resolver_defect_classified(tmp_path):
    _make_repo(tmp_path, candidate_operation="delete")  # group verifies untrusted
    accounting = _write_accounting(
        tmp_path, [_resolved_record(0, [_mkey("insertGroup")])]
    )
    legacy = _write_legacy_report(tmp_path, [_finding(REPO_KT, OWNER, "insertGroup")])
    code, output = _run(
        tmp_path, ["--legacy-shadow-report", legacy, "--accounting", accounting]
    )
    assert code == 2  # v2 untrusted drives the exit code, not the comparison
    report = _read_report(output)
    deltas = _deltas(report)
    assert deltas[v2e.CLASS_PARSER_OR_RESOLVER_DEFECT] == 1
    assert sum(deltas.values()) == 1
    entry = _entries_of_class(report, v2e.CLASS_PARSER_OR_RESOLVER_DEFECT)[0]
    assert entry["reason"] == "v2-group-untrusted"
    assert entry["diagnostic_codes"]
    assert report["shadow_comparison"]["gr07_blocked"] is True


def test_shadow_unreviewed_difference_classified(tmp_path):
    _make_repo(tmp_path)
    accounting = _write_accounting(
        tmp_path, [_resolved_record(0, [_mkey("insertGroup")])]
    )

    # (a) Non-derivable legacy finding: no accounting row at all.
    legacy = _write_legacy_report(
        tmp_path,
        [
            _finding(REPO_KT, OWNER, "insertGroup"),  # agreement: no delta
            _finding(REPO_KT, OWNER, "mysteryMethod"),
        ],
    )
    code, output = _run(
        tmp_path, ["--legacy-shadow-report", legacy, "--accounting", accounting]
    )
    assert code == 0
    report = _read_report(output)
    deltas = _deltas(report)
    assert deltas[v2e.CLASS_UNREVIEWED_DIFFERENCE] == 1
    entry = _entries_of_class(report, v2e.CLASS_UNREVIEWED_DIFFERENCE)[0]
    assert entry["target"].endswith("|function|mysteryMethod")
    assert entry["reason"] == "legacy-row-not-derivable"
    assert entry["legacy_row_indices"] == []

    # (b) Trusted v2 group absent from legacy findings whose rows are all
    # RESOLVED: UNREVIEWED_DIFFERENCE, never CANDIDATE_GAP.
    legacy_empty = _write_legacy_report(
        tmp_path, [], schema_version=v2e.LEGACY_REPORT_SCHEMA_VERSION
    )
    code, output = _run(
        tmp_path,
        ["--legacy-shadow-report", legacy_empty, "--accounting", accounting],
        output_name="build/shadow_report_b.json",
    )
    assert code == 0
    report = _read_report(output)
    deltas = _deltas(report)
    assert deltas[v2e.CLASS_CANDIDATE_GAP] == 0
    assert deltas[v2e.CLASS_UNREVIEWED_DIFFERENCE] == 1
    entry = _entries_of_class(report, v2e.CLASS_UNREVIEWED_DIFFERENCE)[0]
    assert entry["target"].endswith("|function|insertGroup")
    assert entry["reason"] == "trusted-group-absent-from-legacy-findings"
    assert entry["legacy_row_indices"] == [0]


# ===========================================================================
# 10-16. Report-only guarantees, determinism, privacy, CLI shape
# ===========================================================================


def test_shadow_comparison_never_changes_v2_exit_code(tmp_path):
    _make_repo(tmp_path)
    accounting = _write_accounting(
        tmp_path,
        [_unresolved_record(2)],
        source_mutations=[
            _intent(REPO_KT, OWNER + "#insertGroup", "insert", [2]),
        ],
    )
    legacy = _write_legacy_report(tmp_path, [])

    code_without, _output = _run(tmp_path, [])
    code_with, _output = _run(
        tmp_path,
        ["--legacy-shadow-report", legacy, "--accounting", accounting],
        output_name="build/with_shadow.json",
    )
    assert code_without == code_with == 0

    _make_repo(tmp_path, candidate_operation="delete")
    bad_without, _output = _run(
        tmp_path, [], output_name="build/bad_without.json"
    )
    bad_with, _output = _run(
        tmp_path,
        ["--legacy-shadow-report", legacy, "--accounting", accounting],
        output_name="build/bad_with.json",
    )
    assert bad_without == bad_with == 2


def test_report_deterministic_across_two_runs(tmp_path):
    _make_repo(tmp_path)
    accounting = _write_accounting(
        tmp_path, [_resolved_record(0, [_mkey("insertGroup")])]
    )
    legacy = _write_legacy_report(tmp_path, [_finding(REPO_KT, OWNER, "insertGroup")])
    extra = ["--legacy-shadow-report", legacy, "--accounting", accounting]

    code_a, output_a = _run(tmp_path, extra, output_name="build/run_a.json")
    code_b, output_b = _run(tmp_path, extra, output_name="build/run_b.json")
    assert code_a == code_b == 0
    assert Path(output_a).read_bytes() == Path(output_b).read_bytes()


def test_report_contains_no_absolute_paths(tmp_path):
    _make_repo(tmp_path)
    accounting = _write_accounting(
        tmp_path, [_resolved_record(0, [_mkey("insertGroup")])]
    )
    legacy = _write_legacy_report(tmp_path, [_finding(REPO_KT, OWNER, "insertGroup")])
    code, output = _run(
        tmp_path, ["--legacy-shadow-report", legacy, "--accounting", accounting]
    )
    assert code == 0
    text = Path(output).read_text(encoding="utf-8")
    assert str(tmp_path) not in text
    assert "\\" not in text
    report = _read_report(output)

    def _walk(value):
        if isinstance(value, str):
            assert not os.path.isabs(value), value
        elif isinstance(value, dict):
            for item in value.values():
                _walk(item)
        elif isinstance(value, list):
            for item in value:
                _walk(item)

    _walk(report)


def test_legacy_report_schema_mismatch_is_bounded_and_report_only(tmp_path):
    _make_repo(tmp_path)
    legacy = _write_legacy_report(
        tmp_path,
        [_finding(REPO_KT, OWNER, "insertGroup")],
        schema="some-other-schema",
    )
    code, output = _run(tmp_path, ["--legacy-shadow-report", legacy])
    assert code == 0
    report = _read_report(output)
    section = report["shadow_comparison"]
    assert section["legacy_report"]["schema_supported"] is False
    assert (
        section["legacy_report"]["reason"]
        == v2e.COMPARE_LEGACY_SCHEMA_UNSUPPORTED
    )
    assert sum(section["deltas_by_class"].values()) == 0
    assert section["gr07_blocked"] is False


def test_reviewed_flag_false_and_gr07_block_note_present(tmp_path):
    _make_repo(tmp_path)
    legacy = _write_legacy_report(tmp_path, [])
    code, output = _run(tmp_path, ["--legacy-shadow-report", legacy])
    assert code == 0
    section = _read_report(output)["shadow_comparison"]
    assert section["reviewed"] is False
    notes = " ".join(section["notes"])
    assert "never influences the v2 exit code" in notes
    for blocking in (
        v2e.CLASS_CANDIDATE_GAP,
        v2e.CLASS_PARSER_OR_RESOLVER_DEFECT,
        v2e.CLASS_UNREVIEWED_DIFFERENCE,
    ):
        assert blocking in notes
    blocked = any(section["deltas_by_class"][name] > 0 for name in v2e.GR07_BLOCKING_CLASSES)
    assert section["gr07_blocked"] is blocked


def test_tokenized_argv_subprocess_no_shell(tmp_path):
    _make_repo(tmp_path)
    output = str(Path(tmp_path) / "build" / "subprocess_report.json")
    completed = subprocess.run(
        [
            sys.executable,
            str(Path(_SCRIPT_DIR) / "verify_db_policy_v2_evidence.py"),
            "--root",
            str(tmp_path),
            "--output",
            output,
        ],
        shell=False,
        capture_output=True,
        text=True,
    )
    assert completed.returncode == 0
    assert "DB_V2_SHADOW_TRUSTED" in completed.stdout
    report = _read_report(output)
    assert report["trusted"] is True


def test_unreadable_accounting_degrades_to_unreviewed_exit_unchanged(tmp_path):
    _make_repo(tmp_path)
    legacy = _write_legacy_report(tmp_path, [])
    missing_accounting = str(Path(tmp_path) / "build" / "missing_accounting.json")
    code, output = _run(
        tmp_path,
        [
            "--legacy-shadow-report",
            legacy,
            "--accounting",
            missing_accounting,
        ],
    )
    assert code == 0
    section = _read_report(output)["shadow_comparison"]
    assert section["accounting"]["status"] == v2e.COMPARE_ACCOUNTING_UNREADABLE
    assert section["accounting"]["sha256"] is None
    assert section["deltas_by_class"][v2e.CLASS_UNREVIEWED_DIFFERENCE] == 1
    assert section["entries"][0]["reason"] == (
        "trusted-group-absent-from-legacy-findings"
    )
