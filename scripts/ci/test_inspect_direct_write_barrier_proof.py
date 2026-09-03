"""GR-12 direct-proof CLI contract tests (synthetic project, patched stages).

Pins the exit contract (0 all proven, 1 counterexample, 2 unsupported/infra),
the before/after report pair, determinism, atomic writes, and report safety.
"""
from __future__ import annotations

import json
import types

import pytest

import scripts.ci.inspect_direct_write_barrier_proof as proof_cli
from scripts.db_guard.declaration_scanner import DeclarationRange
from scripts.db_guard.mutation_observation import build_mutation_observation
from scripts.db_guard.policy_model import (
    BarrierMode,
    CallableKind,
    PolicyEntry,
)

_PROJECT = "app/src/main/java/com/example/A.kt"

_PROVEN_SOURCE = (
    "package com.example\n"
    "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
    "class Repo(dao: Dao, private val writeBarrier: DatabaseWriteBarrier) {\n"
    "    fun write(x: Int) {\n"
    '        writeBarrier.checkWritesAllowed("Repo.write")\n'
    "        dao.insert(x)\n"
    "    }\n"
    "}\n"
)

_COUNTEREXAMPLE_SOURCE = (
    "package com.example\n"
    "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
    "class Repo(dao: Dao, private val writeBarrier: DatabaseWriteBarrier) {\n"
    "    fun write(x: Int) {\n"
    "        dao.insert(x)\n"
    "    }\n"
    "}\n"
)

_UNSUPPORTED_SOURCE = (
    "package com.example\n"
    "class Repo(dao: Dao) {\n"
    "    fun write(x: Int) {\n"
    "        list.forEach { item ->\n"
    "            dao.insert(item)\n"
    "        }\n"
    "    }\n"
    "}\n"
)


def _policy_entry():
    return PolicyEntry(
        path=_PROJECT,
        owner_fqcn="com.example.Repo",
        kind=CallableKind.FUNCTION,
        method="write",
        receiver=None,
        parameter_types=("Int",),
        dao_accessor="dao",
        dao_fqcn="com.example.Dao",
        operation="insert",
        barrier_mode=BarrierMode.DIRECT,
        reason="test row",
        owner="@panospao7",
        linked_issue="MIT-003",
    )


def _observation(source: str):
    call_start = source.index("dao.insert")
    return build_mutation_observation(
        path=_PROJECT,
        owner_fqcn="com.example.Repo",
        kind="function",
        method="write",
        receiver=None,
        parameter_types=("Int",),
        source=source,
        call_start=call_start,
        call_end=call_start + len("dao.insert(x)"),
        dao_accessor="dao",
        dao_fqcn="com.example.Dao",
        operation="insert",
        mutation_kind="ROOM_ABSTRACT_INSERT",
        source_identity="com.example.Dao::dao#insert",
    )


def _declaration(source: str):
    body_open = source.index("{", source.index("fun write"))
    depth = 0
    closing = None
    for offset in range(body_open, len(source)):
        if source[offset] == "{":
            depth += 1
        elif source[offset] == "}":
            depth -= 1
            if depth == 0:
                closing = offset
                break
    assert closing is not None
    return DeclarationRange(
        path=_PROJECT,
        owner_fqcn="com.example.Repo",
        kind="function",
        start_line=source.count("\n", 0, body_open) + 1,
        end_line=source.count("\n", 0, closing) + 1,
        is_dao=False,
        is_abstract=False,
        body_start=body_open + 1,
        body_end=closing,
        callable_name="write",
        parameters=("Int",),
        source_start=source.index("fun write"),
        source_end=closing + 1,
    )


def _patch(monkeypatch, tmp_path, source: str, entries=None, observe=True):
    (tmp_path / _PROJECT).parent.mkdir(parents=True)
    (tmp_path / _PROJECT).write_text(source, encoding="utf-8")
    monkeypatch.setattr(
        proof_cli, "resolve_source_root_set", lambda root: (object(), [])
    )
    monkeypatch.setattr(
        proof_cli, "load_policy_v2", lambda policy: (entries or [_policy_entry()], None)
    )

    def _fake_scan(root, policy, structural, raw_query, mutation_observation_sink=None):
        if mutation_observation_sink is not None and observe:
            mutation_observation_sink.append(_observation(source))
        return types.SimpleNamespace(trusted=True)

    monkeypatch.setattr(proof_cli, "scan_db_access", _fake_scan)
    monkeypatch.setattr(
        proof_cli,
        "scan_production_declarations",
        lambda root, root_set=None: types.SimpleNamespace(
            helper_ranges=[_declaration(source)]
        ),
    )


class TestExitContract:
    def test_proven_entry_exits_zero(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _PROVEN_SOURCE)
        before, after, exit_code = proof_cli.build_direct_proof_shadow(str(tmp_path), None)
        assert exit_code == 0
        assert after["summary"] == {
            "directEntryCount": 1,
            "provenCount": 1,
            "counterexampleCount": 0,
            "unsupportedCount": 0,
            "infrastructureFailureCount": 0,
        }
        assert before["entries"][0]["legacyStatus"] == "PASS"
        assert after["entries"][0]["barrierForm"] == "DIRECT_CHECK"

    def test_counterexample_entry_exits_one(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _COUNTEREXAMPLE_SOURCE)
        before, after, exit_code = proof_cli.build_direct_proof_shadow(str(tmp_path), None)
        assert exit_code == 1
        assert before["entries"][0]["legacyStatus"] == "FAIL"
        entry = after["entries"][0]
        assert entry["proofStatus"] == "COUNTEREXAMPLE"
        assert entry["counterexampleNodeKinds"][0] == "ENTRY"
        assert entry["counterexampleNodeKinds"][-1] == "MUTATION"

    def test_unmodelable_callable_exits_two(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _UNSUPPORTED_SOURCE)
        _before, after, exit_code = proof_cli.build_direct_proof_shadow(str(tmp_path), None)
        assert exit_code == 2
        assert after["entries"][0]["proofStatus"] == "UNSUPPORTED"

    def test_unobserved_direct_row_exits_two(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _PROVEN_SOURCE, observe=False)
        before, after, exit_code = proof_cli.build_direct_proof_shadow(str(tmp_path), None)
        assert exit_code == 2
        assert before["entries"][0]["legacyStatus"] == "NOT_OBSERVED"
        assert after["entries"][0]["proofStatus"] == "UNSUPPORTED"

    def test_policy_failure_exits_two(self, monkeypatch, tmp_path):
        (tmp_path / _PROJECT).parent.mkdir(parents=True)
        (tmp_path / _PROJECT).write_text(_PROVEN_SOURCE, encoding="utf-8")
        monkeypatch.setattr(
            proof_cli, "resolve_source_root_set", lambda root: (object(), [])
        )
        monkeypatch.setattr(proof_cli, "load_policy_v2", lambda policy: (None, ["error"]))
        _before, after, exit_code = proof_cli.build_direct_proof_shadow(str(tmp_path), None)
        assert exit_code == 2
        assert after["infrastructure"]["failureReasons"] == [
            "DB_POLICY_SOURCE_EVIDENCE_INVALID"
        ]


class TestReportSafety:
    def test_deterministic_across_runs(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _PROVEN_SOURCE)
        first = proof_cli.build_direct_proof_shadow(str(tmp_path), None, target_sha="a" * 40)
        second = proof_cli.build_direct_proof_shadow(str(tmp_path), None, target_sha="a" * 40)
        assert first == second

    def test_no_raw_source_or_absolute_paths(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _PROVEN_SOURCE)
        before, after, _ = proof_cli.build_direct_proof_shadow(str(tmp_path), None)
        for report in (before, after):
            rendered = json.dumps(report)
            assert "dao.insert" not in rendered
            assert "checkWritesAllowed(\"Repo.write\")" not in rendered
            assert str(tmp_path) not in rendered

    def test_row_without_proof_result_is_encoded_not_dropped(self, monkeypatch, tmp_path):
        # A direct policy row whose callable is missing entirely must still
        # appear exactly once with an infrastructure failure (never silently
        # skipped), and take the exit-2 route.
        source = _PROVEN_SOURCE
        _patch(monkeypatch, tmp_path, source)
        monkeypatch.setattr(
            proof_cli,
            "scan_production_declarations",
            lambda root, root_set=None: types.SimpleNamespace(helper_ranges=[]),
        )
        before, after, exit_code = proof_cli.build_direct_proof_shadow(str(tmp_path), None)
        assert exit_code == 2
        assert before["summary"]["directEntryCount"] == 1
        assert after["summary"]["directEntryCount"] == 1
        assert after["entries"][0]["proofStatus"] == "INFRASTRUCTURE_FAILURE"


class TestCliMain:
    def test_main_writes_before_after_and_digest(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _PROVEN_SOURCE)
        output = tmp_path / "build" / "direct-proof-before.json"
        exit_code = proof_cli.main(
            ["--root", str(tmp_path), "--output", str(output), "--target-sha", "b" * 40]
        )
        assert exit_code == 0
        before = json.loads(output.read_text(encoding="utf-8"))
        after = json.loads((tmp_path / "build" / "direct-proof-before.json.after.json").read_text(encoding="utf-8"))
        assert before["schemaVersion"] == 1
        assert before["engine"] == "legacy-lexical"
        assert after["engine"] == "gr12-dominance-proof"
        assert after["targetSha"] == "b" * 40
        digest_text = (tmp_path / "build" / "direct-proof-before.json.after.json.sha256").read_text(encoding="utf-8")
        assert len(digest_text.split()[0]) == 64

    def test_main_survives_crash_with_exit_two(self, monkeypatch, tmp_path):
        (tmp_path / _PROJECT).parent.mkdir(parents=True)
        (tmp_path / _PROJECT).write_text(_PROVEN_SOURCE, encoding="utf-8")
        monkeypatch.setattr(
            proof_cli,
            "resolve_source_root_set",
            lambda root: (_ for _ in ()).throw(ValueError("boom")),
        )
        output = tmp_path / "build" / "direct-proof-crash.json"
        exit_code = proof_cli.main(["--root", str(tmp_path), "--output", str(output)])
        assert exit_code == 2
        report = json.loads(output.read_text(encoding="utf-8"))
        assert report["infrastructure"]["failureReasons"] == [
            "DB_DIRECT_BARRIER_REPORT_INVALID"
        ]
