"""Shadow-CLI contract tests (synthetic project, no real-tree dependency).

The CLI's correlation stages (roots, policy, D4 scan, declarations) are
patched at the module boundary; the tests pin the exit contract (0 supported,
1 unsupported, 2 infrastructure), corpus accounting, determinism, atomic
report writing, and report safety (no raw source, no absolute paths).
"""
from __future__ import annotations

import json
import os
import types

import pytest

import scripts.ci.inspect_db_structural_model as shadow_cli
from scripts.db_guard.declaration_scanner import DeclarationRange
from scripts.db_guard.mutation_observation import build_mutation_observation

_PROJECT = "app/src/main/java/com/example/A.kt"

_SUPPORTED_SOURCE = (
    "package com.example\n"
    "\n"
    "class Repo(dao: Dao) {\n"
    "    fun write(x: Int) {\n"
    "        dao.insert(x)\n"
    "    }\n"
    "}\n"
)

_UNSUPPORTED_SOURCE = (
    "package com.example\n"
    "\n"
    "class Repo(dao: Dao) {\n"
    "    fun write(x: Int) {\n"
    "        list.forEach { item ->\n"
    "            dao.insert(item)\n"
    "        }\n"
    "    }\n"
    "}\n"
)


def _policy_entry():
    return types.SimpleNamespace(
        path=_PROJECT,
        owner_fqcn="com.example.Repo",
        kind="function",
        method="write",
        receiver=None,
        parameter_types=("Int",),
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
    # Declaration-scanner convention: the body span excludes BOTH braces
    # (body_start = after "{", body_end = index of "}").
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


def _write_project(tmp_path, source: str):
    kotlin_file = tmp_path / _PROJECT
    kotlin_file.parent.mkdir(parents=True)
    kotlin_file.write_text(source, encoding="utf-8")


def _patch_happy_path(monkeypatch, tmp_path, source: str, entries=None):
    _write_project(tmp_path, source)
    monkeypatch.setattr(
        shadow_cli, "resolve_source_root_set", lambda root: (object(), [])
    )
    monkeypatch.setattr(
        shadow_cli, "load_policy_v2", lambda policy: (entries or [_policy_entry()], None)
    )

    def _fake_scan(root, policy, structural, raw_query, mutation_observation_sink=None):
        if mutation_observation_sink is not None:
            mutation_observation_sink.append(_observation(source))
        return types.SimpleNamespace(trusted=True)

    monkeypatch.setattr(shadow_cli, "scan_db_access", _fake_scan)
    monkeypatch.setattr(
        shadow_cli,
        "scan_production_declarations",
        lambda root, root_set=None: types.SimpleNamespace(
            helper_ranges=[_declaration(source)]
        ),
    )


class TestExitContract:
    def test_supported_callable_exits_zero(self, monkeypatch, tmp_path):
        _patch_happy_path(monkeypatch, tmp_path, _SUPPORTED_SOURCE)
        report, exit_code = shadow_cli.build_structural_shadow(str(tmp_path), None)
        assert exit_code == 0
        assert report["summary"]["supportedCount"] == 1
        assert report["corpus"]["observedCallableCount"] == 1
        assert report["corpus"]["unaccountedCallableKeys"] == []
        assert report["corpus"]["policyCallableCount"] == 1

    def test_unsupported_callable_exits_one(self, monkeypatch, tmp_path):
        _patch_happy_path(monkeypatch, tmp_path, _UNSUPPORTED_SOURCE)
        report, exit_code = shadow_cli.build_structural_shadow(str(tmp_path), None)
        assert exit_code == 1
        assert report["summary"]["unsupportedCount"] == 1
        assert report["callables"][0]["diagnostics"]

    def test_missing_declaration_exits_two(self, monkeypatch, tmp_path):
        _patch_happy_path(monkeypatch, tmp_path, _SUPPORTED_SOURCE)
        monkeypatch.setattr(
            shadow_cli,
            "scan_production_declarations",
            lambda root, root_set=None: types.SimpleNamespace(helper_ranges=[]),
        )
        report, exit_code = shadow_cli.build_structural_shadow(str(tmp_path), None)
        assert exit_code == 2
        assert report["corpus"]["unaccountedCallableKeys"]
        assert report["infrastructure"]["failureReasons"] == []

    def test_policy_failure_exits_two(self, monkeypatch, tmp_path):
        _write_project(tmp_path, _SUPPORTED_SOURCE)
        monkeypatch.setattr(
            shadow_cli, "resolve_source_root_set", lambda root: (object(), [])
        )
        monkeypatch.setattr(
            shadow_cli, "load_policy_v2", lambda policy: (None, ["error"])
        )
        report, exit_code = shadow_cli.build_structural_shadow(str(tmp_path), None)
        assert exit_code == 2
        assert report["infrastructure"]["failureReasons"] == [
            "DB_POLICY_SOURCE_EVIDENCE_INVALID"
        ]

    def test_duplicate_observations_collapse_to_one_entry(self, monkeypatch, tmp_path):
        # Multiple DAO mutations in one callable are expected; the CLI must
        # collapse them to exactly one report entry per callable key.
        _patch_happy_path(monkeypatch, tmp_path, _SUPPORTED_SOURCE)

        original = shadow_cli.scan_db_access

        def _double_scan(root, policy, structural, raw_query, mutation_observation_sink=None):
            original(root, policy, structural, raw_query, mutation_observation_sink)
            if mutation_observation_sink is not None:
                # A second, identical-key observation of the same callable.
                second = _observation(_SUPPORTED_SOURCE)
                mutation_observation_sink.append(
                    type(second)(
                        path=second.path,
                        callable_key=second.callable_key,
                        source_start=second.source_start,
                        source_end=second.source_end,
                        line=second.line,
                        column=second.column,
                        dao_accessor=second.dao_accessor,
                        dao_fqcn=second.dao_fqcn,
                        operation=second.operation,
                        mutation_kind=second.mutation_kind,
                        source_identity=second.source_identity,
                    )
                )
            return types.SimpleNamespace(trusted=True)

        monkeypatch.setattr(shadow_cli, "scan_db_access", _double_scan)
        report, exit_code = shadow_cli.build_structural_shadow(str(tmp_path), None)
        assert exit_code == 0
        assert report["summary"]["callableCount"] == 1
        keys = [entry["callableKey"] for entry in report["callables"]]
        assert len(keys) == len(set(keys))

    def test_root_failure_exits_two(self, monkeypatch, tmp_path):
        _write_project(tmp_path, _SUPPORTED_SOURCE)
        monkeypatch.setattr(
            shadow_cli,
            "resolve_source_root_set",
            lambda root: (None, [("DB_SOURCE_ROOT_UNDECLARED", {})]),
        )
        report, exit_code = shadow_cli.build_structural_shadow(str(tmp_path), None)
        assert exit_code == 2
        assert "DB_SOURCE_ROOT_UNDECLARED" in report["infrastructure"]["failureReasons"]


class TestReportSafety:
    def test_deterministic_across_runs(self, monkeypatch, tmp_path):
        _patch_happy_path(monkeypatch, tmp_path, _SUPPORTED_SOURCE)
        first, code_a = shadow_cli.build_structural_shadow(str(tmp_path), None, target_sha="a" * 40)
        second, code_b = shadow_cli.build_structural_shadow(str(tmp_path), None, target_sha="a" * 40)
        assert code_a == code_b == 0
        assert first == second

    def test_no_raw_source_or_absolute_paths(self, monkeypatch, tmp_path):
        _patch_happy_path(monkeypatch, tmp_path, _SUPPORTED_SOURCE)
        report, _ = shadow_cli.build_structural_shadow(str(tmp_path), None)
        rendered = json.dumps(report)
        assert "dao.insert" not in rendered
        assert str(tmp_path) not in rendered
        assert "com.example.Repo" in rendered or "callableKey" in rendered


class TestCliMain:
    def test_main_writes_report_and_returns_exit(self, monkeypatch, tmp_path):
        _patch_happy_path(monkeypatch, tmp_path, _SUPPORTED_SOURCE)
        output = tmp_path / "build" / "shadow.json"
        exit_code = shadow_cli.main(
            ["--root", str(tmp_path), "--output", str(output), "--target-sha", "b" * 40]
        )
        assert exit_code == 0
        report = json.loads(output.read_text(encoding="utf-8"))
        assert report["schemaVersion"] == 1
        assert report["reportOnly"] is True
        assert report["targetSha"] == "b" * 40

    def test_include_graphs_adds_payload(self, monkeypatch, tmp_path):
        _patch_happy_path(monkeypatch, tmp_path, _SUPPORTED_SOURCE)
        output = tmp_path / "build" / "shadow-graphs.json"
        exit_code = shadow_cli.main(
            ["--root", str(tmp_path), "--output", str(output), "--include-graphs"]
        )
        assert exit_code == 0
        report = json.loads(output.read_text(encoding="utf-8"))
        assert "graph" in report["callables"][0]

    def test_main_survives_infrastructure_exception(self, monkeypatch, tmp_path):
        _write_project(tmp_path, _SUPPORTED_SOURCE)
        monkeypatch.setattr(
            shadow_cli,
            "resolve_source_root_set",
            lambda root: (_ for _ in ()).throw(ValueError("boom")),
        )
        output = tmp_path / "build" / "shadow-fail.json"
        exit_code = shadow_cli.main(["--root", str(tmp_path), "--output", str(output)])
        assert exit_code == 2
        report = json.loads(output.read_text(encoding="utf-8"))
        assert report["infrastructure"]["failureReasons"] == [
            "DB_STRUCTURAL_MODEL_REPORT_INVALID"
        ]

    def test_main_maps_any_crash_to_exit_two(self, monkeypatch, tmp_path):
        # ISSUE-4: an unexpected exception type must take the exit-2
        # infrastructure route, never alias the exit-1 unsupported contract.
        _write_project(tmp_path, _SUPPORTED_SOURCE)
        monkeypatch.setattr(
            shadow_cli,
            "resolve_source_root_set",
            lambda root: (_ for _ in ()).throw(KeyError("unexpected")),
        )
        output = tmp_path / "build" / "shadow-crash.json"
        exit_code = shadow_cli.main(["--root", str(tmp_path), "--output", str(output)])
        assert exit_code == 2
        report = json.loads(output.read_text(encoding="utf-8"))
        assert report["reportOnly"] is True
        assert report["infrastructure"]["failureReasons"] == [
            "DB_STRUCTURAL_MODEL_REPORT_INVALID"
        ]
