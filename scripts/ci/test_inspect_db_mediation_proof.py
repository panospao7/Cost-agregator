"""GR-13 shadow-CLI contract tests (synthetic project, no real-tree dependency).

Covers the plan's protocol-and-determinism matrix (PR-01..PR-08 fixture
rows) plus the multi-site subject combine:

  PR-01  report contains no raw source and no absolute paths;
  PR-02  two runs are byte-identical;
  PR-03  proof states are the closed vocabulary only;
  PR-04  malformed policy or source roots exit 2;
  PR-05  valid unproven analysis exits 1;
  PR-06  unsupported source exits 2;
  PR-07  the normal DB scanner contract is untouched (reportOnly, no
         project writes, identical scan results before/after);
  PR-08  ratchet/baseline inputs are untouched (no project writes);
  multi-site  one mutation key observed at several call sites becomes one
         subject per site, combined per policy row with worst-state
         semantics.
"""
from __future__ import annotations

import json
import os
import types

import pytest

import scripts.ci.inspect_db_mediation_proof as shadow_cli
from scripts.ci.inspect_db_structural_model import _span_of
from scripts.db_guard.declaration_scanner import DeclarationRange
from scripts.db_guard.mediation_analysis.callgraph import AnalysisContract
from scripts.db_guard.mediation_analysis.models import ProofState
from scripts.db_guard.mediation_analysis.proof import (
    MutationSubject,
    SubjectProof,
    worst_subject_proof,
)
from scripts.db_guard.mutation_observation import build_mutation_observation
from scripts.db_guard.policy_model import BarrierMode, CallableKind, PolicyEntry
from scripts.db_guard.scanner import scan_db_access

_PROJECT = "app/src/main/java/com/example/HelperRepo.kt"

# Proven corpus: the only path into the helper mutation crosses a canonical
# direct-barrier scope, so the single policy row proves and the CLI exits 0.
_PROVEN_SOURCE = (
    "package com.example\n"
    "\n"
    "class HelperRepo(private val dao: Dao) {\n"
    "    fun guardedWrite(x: Int) {\n"
    "        withWriteBarrier {\n"
    "            writeRow(x)\n"
    "        }\n"
    "    }\n"
    "\n"
    "    private fun writeRow(x: Int) {\n"
    "        dao.insert(x)\n"
    "    }\n"
    "}\n"
)

# Counterexample corpus: an unguarded public caller reaches the same
# helper, so the row is a definite counterexample and the CLI exits 1.
_COUNTEREXAMPLE_SOURCE = (
    "package com.example\n"
    "\n"
    "class HelperRepo(private val dao: Dao) {\n"
    "    fun guardedWrite(x: Int) {\n"
    "        withWriteBarrier {\n"
    "            writeRow(x)\n"
    "        }\n"
    "    }\n"
    "\n"
    "    fun unguardedWrite(x: Int) {\n"
    "        writeRow(x)\n"
    "    }\n"
    "\n"
    "    private fun writeRow(x: Int) {\n"
    "        dao.insert(x)\n"
    "    }\n"
    "}\n"
)

# Multi-site corpus: one mutation key (writeRow -> dao.insert) observed at
# TWO call sites with different local guard context.  The row must combine
# the per-site proofs with worst-state semantics (counterexample wins).
_MULTISITE_SOURCE = (
    "package com.example\n"
    "\n"
    "class HelperRepo(private val dao: Dao) {\n"
    "    fun publicWrite(x: Int) {\n"
    "        writeRow(x)\n"
    "    }\n"
    "\n"
    "    private fun writeRow(x: Int) {\n"
    "        withWriteBarrier {\n"
    "            dao.insert(x)\n"
    "        }\n"
    "        dao.insert(x)\n"
    "    }\n"
    "}\n"
)

# Multi-site, all sites proven: both sites sit inside canonical scopes.
_MULTISITE_PROVEN_SOURCE = (
    "package com.example\n"
    "\n"
    "class HelperRepo(private val dao: Dao) {\n"
    "    fun guardedWrite(x: Int) {\n"
    "        withWriteBarrier {\n"
    "            writeRow(x)\n"
    "        }\n"
    "    }\n"
    "\n"
    "    private fun writeRow(x: Int) {\n"
    "        withWriteBarrier {\n"
    "            dao.insert(x)\n"
    "        }\n"
    "        withWriteBarrier {\n"
    "            dao.insert(x)\n"
    "        }\n"
    "    }\n"
    "}\n"
)

# Worker corpus (GR-14d): a doWork root of a CoroutineWorker subclass whose
# only mutation sits inside the canonical worker-guard lambda.  The class is
# intentionally absent from any registry; a tracked disposition is what
# recognizes its root for mediation proof.
_WORKER_PROJECT = "app/src/main/java/com/example/IntakeWorker.kt"

_WORKER_SOURCE = (
    "package com.example\n"
    "\n"
    "open class CoroutineWorker {\n"
    "    abstract fun doWork(): Result\n"
    "}\n"
    "\n"
    "class WorkerExecutionGuard {\n"
    "    fun runGuarded(block: () -> Unit) = block()\n"
    "}\n"
    "\n"
    "class IntakeWorker(private val dao: Dao) : CoroutineWorker() {\n"
    "    private val guard = WorkerExecutionGuard()\n"
    "\n"
    "    override fun doWork(): Result {\n"
    "        guard.runGuarded { dao.insert(1) }\n"
    "        return Result.success()\n"
    "    }\n"
    "}\n"
)

_CONTRACT = AnalysisContract(
    worker_guard_receiver_fqcn="com.example.WorkerExecutionGuard",
    worker_guard_scope_methods=("runGuarded",),
    direct_scope_receiver_fqcn="",
    direct_scope_methods=("withWriteBarrier",),
    direct_scope_allow_receiverless=True,
    worker_base_fqcns=("com.example.CoroutineWorker",),
)

_IDENTITY = {
    "path": _PROJECT,
    "owner_fqcn": "com.example.HelperRepo",
    "kind": CallableKind.FUNCTION,
    "method": "writeRow",
    "receiver": None,
    "parameter_types": ("Int",),
}


def _policy_entry(method="writeRow", mode=BarrierMode.HELPER):
    return PolicyEntry(
        path=_IDENTITY["path"],
        owner_fqcn=_IDENTITY["owner_fqcn"],
        kind=_IDENTITY["kind"],
        method=method,
        receiver=_IDENTITY["receiver"],
        parameter_types=_IDENTITY["parameter_types"],
        dao_accessor="dao",
        dao_fqcn="com.example.Dao",
        operation="insert",
        barrier_mode=mode,
        reason="test",
        owner="@test",
        linked_issue="GR-14",
    )


def _worker_policy_entry():
    return PolicyEntry(
        path=_WORKER_PROJECT,
        owner_fqcn="com.example.IntakeWorker",
        kind=CallableKind.FUNCTION,
        method="doWork",
        receiver=None,
        parameter_types=(),
        dao_accessor="dao",
        dao_fqcn="com.example.Dao",
        operation="insert",
        barrier_mode=BarrierMode.WORKER_MEDIATED,
        reason="test",
        owner="@test",
        linked_issue="GR-14d",
    )


def _observations(source: str):
    """One observation per dao.insert call site (same mutation key)."""
    results = []
    start = 0
    while True:
        call_start = source.find("dao.insert", start)
        if call_start < 0:
            break
        results.append(
            build_mutation_observation(
                path=_IDENTITY["path"],
                owner_fqcn=_IDENTITY["owner_fqcn"],
                kind=_IDENTITY["kind"],
                method=_IDENTITY["method"],
                receiver=_IDENTITY["receiver"],
                parameter_types=_IDENTITY["parameter_types"],
                source=source,
                call_start=call_start,
                call_end=call_start + len("dao.insert(x)"),
                dao_accessor="dao",
                dao_fqcn="com.example.Dao",
                operation="insert",
                mutation_kind="ROOM_ABSTRACT_INSERT",
                source_identity="com.example.Dao::dao#insert",
            )
        )
        start = call_start + 1
    return results


def _declaration(source: str, method: str = "writeRow", parameters=("Int",),
                 path=None, owner_fqcn=None, header_text=None):
    header = source.index(header_text or "fun %s" % method)
    body_open = source.index("{", header)
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
        path=path or _PROJECT,
        owner_fqcn=owner_fqcn or _IDENTITY["owner_fqcn"],
        kind="function",
        start_line=source.count("\n", 0, header) + 1,
        end_line=source.count("\n", 0, closing) + 1,
        is_dao=False,
        is_abstract=False,
        body_start=body_open + 1,
        body_end=closing,
        callable_name=method,
        parameters=parameters,
        source_start=header,
        source_end=closing + 1,
    )


def _write_project(tmp_path, source: str):
    kotlin_file = tmp_path / _PROJECT
    kotlin_file.parent.mkdir(parents=True)
    kotlin_file.write_text(source, encoding="utf-8")
    baseline = tmp_path / "config" / "baselines"
    baseline.mkdir(parents=True, exist_ok=True)
    (baseline / "db_access_v2.json").write_text("{}\n", encoding="utf-8")


def _patch(monkeypatch, tmp_path, source: str, entries, observations=None):
    _write_project(tmp_path, source)
    observations = _observations(source) if observations is None else observations
    monkeypatch.setattr(
        shadow_cli, "resolve_source_root_set", lambda root: (object(), [])
    )
    monkeypatch.setattr(
        shadow_cli, "load_policy_v2", lambda policy: (entries, None)
    )
    monkeypatch.setattr(
        shadow_cli, "_production_contract", lambda: _CONTRACT
    )

    def _fake_scan(root, policy, structural, raw_query, mutation_observation_sink=None):
        if mutation_observation_sink is not None:
            mutation_observation_sink.extend(observations)
        return types.SimpleNamespace(findings=(), diagnostics=())

    monkeypatch.setattr(shadow_cli, "scan_db_access", _fake_scan)

    def _fake_declarations(root, root_set=None):
        return types.SimpleNamespace(
            helper_ranges=[
                _declaration(source, method)
                for method in ("writeRow", "guardedWrite", "publicWrite",
                               "unguardedWrite")
                if ("fun %s" % method) in source
            ]
        )

    monkeypatch.setattr(
        shadow_cli, "scan_production_declarations", _fake_declarations
    )
    monkeypatch.setattr(
        shadow_cli,
        "collect_production_kotlin_files",
        lambda root, root_set: ([_PROJECT], []),
    )
    return observations


def _tree_snapshot(root):
    snapshot = {}
    for base, _dirs, files in os.walk(root):
        for name in files:
            path = os.path.join(base, name)
            with open(path, "rb") as handle:
                snapshot[os.path.relpath(path, root)] = handle.read()
    return snapshot


class TestProtocolRows:
    def test_pr01_report_has_no_raw_source(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _PROVEN_SOURCE, [_policy_entry()])
        report, _exit = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        dump = json.dumps(report)
        assert "dao.insert" not in dump
        assert "withWriteBarrier" not in dump
        assert str(tmp_path) not in dump
        assert "com.example.HelperRepo" in dump  # identity strings remain

    def test_pr02_two_runs_byte_identical(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _PROVEN_SOURCE, [_policy_entry()])
        report_a, exit_a = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        report_b, exit_b = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        assert exit_a == exit_b == 0
        assert json.dumps(report_a, indent=2) == json.dumps(report_b, indent=2)

    def test_pr03_proof_states_are_closed_vocabulary(self, monkeypatch, tmp_path):
        _patch(
            monkeypatch,
            tmp_path,
            _COUNTEREXAMPLE_SOURCE,
            [_policy_entry()],
        )
        report, _exit = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        vocabulary = {state.value for state in ProofState}
        assert len(vocabulary) == 11  # closed set, no OTHER escape hatch
        for row in report["entries"]:
            assert row["proofStatus"] in vocabulary
        for state in report["summary"]["proofStates"]:
            assert state in vocabulary

    def test_pr04_malformed_policy_exits_two(self, monkeypatch, tmp_path):
        _write_project(tmp_path, _PROVEN_SOURCE)
        monkeypatch.setattr(
            shadow_cli, "resolve_source_root_set", lambda root: (object(), [])
        )
        monkeypatch.setattr(
            shadow_cli, "load_policy_v2", lambda policy: (None, ["err"])
        )
        report, exit_code = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        assert exit_code == 2
        assert report["infrastructure"]["failureReasons"] == [
            "DB_POLICY_SOURCE_EVIDENCE_INVALID"
        ]

    def test_pr04_malformed_roots_exit_two(self, monkeypatch, tmp_path):
        _write_project(tmp_path, _PROVEN_SOURCE)
        monkeypatch.setattr(
            shadow_cli,
            "resolve_source_root_set",
            lambda root: (None, [("DB_SOURCE_ROOT_INVALID", "x")]),
        )
        report, exit_code = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        assert exit_code == 2
        assert report["infrastructure"]["failureReasons"] == [
            "DB_SOURCE_ROOT_INVALID"
        ]

    def test_pr05_unproven_valid_analysis_exits_one(self, monkeypatch, tmp_path):
        _patch(
            monkeypatch,
            tmp_path,
            _COUNTEREXAMPLE_SOURCE,
            [_policy_entry()],
        )
        report, exit_code = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        assert exit_code == 1
        row = report["entries"][0]
        assert row["proofStatus"] == "counterexample_unguarded_call_path"
        assert row["boundedPath"]

    def test_pr06_unsupported_source_exits_two(self, monkeypatch, tmp_path):
        # A policy row without any D4 observation is unsupported source.
        _patch(
            monkeypatch,
            tmp_path,
            _PROVEN_SOURCE,
            [_policy_entry(method="neverObserved")],
        )
        report, exit_code = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        assert exit_code == 2
        row = report["entries"][0]
        assert row["proofStatus"] == "unsupported_source"
        assert row["reasonCode"] == "GR13_NO_D4_OBSERVATION"

    def test_pr07_pr08_shadow_run_touches_nothing(self, monkeypatch, tmp_path):
        source = _PROVEN_SOURCE
        entries = [_policy_entry()]
        observations = _patch(monkeypatch, tmp_path, source, entries)
        # The normal DB scanner contract, measured before the shadow run.
        before = scan_db_access(str(tmp_path), entries, None, None)
        before_observations = list(observations)
        snapshot = _tree_snapshot(tmp_path)
        report, _exit = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        assert report["reportOnly"] is True
        # PR-08/PR-07: no project file (incl. baselines) written or changed.
        assert _tree_snapshot(tmp_path) == snapshot
        # PR-07: the normal scanner returns the same findings afterwards.
        after = scan_db_access(str(tmp_path), entries, None, None)
        assert len(after.findings) == len(before.findings)
        assert list(observations) == before_observations

    def test_pr07_scan_finding_count_mirrors_scan(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _PROVEN_SOURCE, [_policy_entry()])
        report, _exit = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        assert report["summary"]["scanFindingCount"] == 0
        assert report["summary"]["helperWorkerEntryCount"] == 1
        assert report["summary"]["subjectCount"] == 1


class TestMultiSiteCombine:
    def test_one_subject_per_site_worst_state_wins(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _MULTISITE_SOURCE, [_policy_entry()])
        report, exit_code = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        assert exit_code == 1
        assert report["summary"]["helperWorkerEntryCount"] == 1
        assert report["summary"]["subjectCount"] == 2
        assert report["summary"]["uncorrelatedSubjectCount"] == 0
        assert len(report["entries"]) == 1
        row = report["entries"][0]
        # The bare site's unguarded public path is a definite counterexample
        # even though the other site sits inside a canonical scope.
        assert row["proofStatus"] == "counterexample_unguarded_call_path"

    def test_all_sites_proven_row_proven(self, monkeypatch, tmp_path):
        _patch(
            monkeypatch,
            tmp_path,
            _MULTISITE_PROVEN_SOURCE,
            [_policy_entry()],
        )
        report, exit_code = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        assert exit_code == 0
        assert report["summary"]["subjectCount"] == 2
        assert report["entries"][0]["proofStatus"] == "proven_helper"

    def test_uncorrelated_row_stays_single_siteless_subject(
        self, monkeypatch, tmp_path
    ):
        _patch(monkeypatch, tmp_path, _PROVEN_SOURCE, [_policy_entry()])
        # Drop the observations: the row must fail closed as unsupported.
        monkeypatch.setattr(
            shadow_cli,
            "scan_db_access",
            lambda root, policy, structural, raw_query, mutation_observation_sink=None: (
                types.SimpleNamespace(findings=(), diagnostics=())
            ),
        )
        report, exit_code = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        assert exit_code == 2
        assert report["summary"]["uncorrelatedSubjectCount"] == 1
        assert report["entries"][0]["proofStatus"] == "unsupported_source"


class TestWorstSubjectProof:
    @staticmethod
    def _proof(state: ProofState) -> SubjectProof:
        return SubjectProof(
            mutation_key="k",
            callable_key="c",
            barrier_mode="helper",
            proof_state=state,
            reason_code="R",
            deciding_resolution=None,
            local_guard="none",
        )

    def test_proven_loses_to_counterexample(self):
        worst = worst_subject_proof(
            [self._proof(ProofState.PROVEN_HELPER), self._proof(
                ProofState.COUNTEREXAMPLE_UNGUARDED_CALL_PATH)]
        )
        assert worst.proof_state is ProofState.COUNTEREXAMPLE_UNGUARDED_CALL_PATH

    def test_counterexample_beats_unproven(self):
        worst = worst_subject_proof(
            [self._proof(ProofState.UNPROVEN_RECURSION), self._proof(
                ProofState.COUNTEREXAMPLE_NON_WORKER_ROOT)]
        )
        assert worst.proof_state is ProofState.COUNTEREXAMPLE_NON_WORKER_ROOT

    def test_unsupported_beats_unproven(self):
        worst = worst_subject_proof(
            [self._proof(ProofState.UNPROVEN_AMBIGUOUS_CALL), self._proof(
                ProofState.UNSUPPORTED_SOURCE)]
        )
        assert worst.proof_state is ProofState.UNSUPPORTED_SOURCE

    def test_infrastructure_is_worst(self):
        worst = worst_subject_proof(
            [self._proof(ProofState.INFRASTRUCTURE_FAILURE), self._proof(
                ProofState.UNSUPPORTED_SOURCE)]
        )
        assert worst.proof_state is ProofState.INFRASTRUCTURE_FAILURE

    def test_tie_keeps_first_site(self):
        first = self._proof(ProofState.UNPROVEN_RECURSION)
        second = self._proof(ProofState.UNPROVEN_RECURSION)
        assert worst_subject_proof([first, second]) is first

    def test_requires_at_least_one_proof(self):
        with pytest.raises(ValueError):
            worst_subject_proof([])


class TestSubjectIdentity:
    def test_subjects_correlate_to_graph_callable(self, monkeypatch, tmp_path):
        _patch(monkeypatch, tmp_path, _PROVEN_SOURCE, [_policy_entry()])
        report, exit_code = shadow_cli.build_mediation_shadow(str(tmp_path), None)
        assert exit_code == 0
        row = report["entries"][0]
        assert row["callableKey"].endswith("HelperRepo|function|writeRow|null|Int")
        assert row["reachingRootKinds"] == ["public_or_protected_external"]
        assert row["localGuard"] == "none"
        assert row["boundedPath"]


class TestWorkerRootDispositions:
    """GR-14d: tracked dispositions recognize intentionally unscheduled
    worker doWork roots for mediation proof (never a runtime change)."""

    def _patch_worker(self, monkeypatch, tmp_path):
        kotlin_file = tmp_path / _WORKER_PROJECT
        kotlin_file.parent.mkdir(parents=True)
        kotlin_file.write_text(_WORKER_SOURCE, encoding="utf-8")
        baseline = tmp_path / "config" / "baselines"
        baseline.mkdir(parents=True, exist_ok=True)
        (baseline / "db_access_v2.json").write_text("{}\n", encoding="utf-8")
        call_start = _WORKER_SOURCE.find("dao.insert")
        observation = build_mutation_observation(
            path=_WORKER_PROJECT,
            owner_fqcn="com.example.IntakeWorker",
            kind=CallableKind.FUNCTION,
            method="doWork",
            receiver=None,
            parameter_types=(),
            source=_WORKER_SOURCE,
            call_start=call_start,
            call_end=call_start + len("dao.insert(1)"),
            dao_accessor="dao",
            dao_fqcn="com.example.Dao",
            operation="insert",
            mutation_kind="ROOM_ABSTRACT_INSERT",
            source_identity="com.example.Dao::dao#insert",
        )
        monkeypatch.setattr(
            shadow_cli, "resolve_source_root_set", lambda root: (object(), [])
        )
        monkeypatch.setattr(
            shadow_cli,
            "load_policy_v2",
            lambda policy: ([_worker_policy_entry()], None),
        )
        monkeypatch.setattr(
            shadow_cli, "_production_contract", lambda: _CONTRACT
        )

        def _fake_scan(root, policy, structural, raw_query,
                       mutation_observation_sink=None):
            if mutation_observation_sink is not None:
                mutation_observation_sink.append(observation)
            return types.SimpleNamespace(findings=(), diagnostics=())

        monkeypatch.setattr(shadow_cli, "scan_db_access", _fake_scan)

        def _fake_declarations(root, root_set=None):
            return types.SimpleNamespace(
                helper_ranges=[_declaration(
                    _WORKER_SOURCE, "doWork", parameters=(),
                    path=_WORKER_PROJECT,
                    owner_fqcn="com.example.IntakeWorker",
                    header_text="override fun doWork",
                )]
            )

        monkeypatch.setattr(
            shadow_cli, "scan_production_declarations", _fake_declarations
        )
        monkeypatch.setattr(
            shadow_cli,
            "collect_production_kotlin_files",
            lambda root, root_set: ([_WORKER_PROJECT], []),
        )

    def _write_dispositions(self, tmp_path, fqcn="com.example.IntakeWorker"):
        path = tmp_path / "worker_root_dispositions.yml"
        path.write_text(
            "schemaVersion: 1\n"
            "dispositions:\n"
            "- workerFqcn: %s\n"
            "  disposition: COORDINATOR_DRIVEN_ONE_SHOT\n"
            "  reason: coordinator-driven one-shot; not startup-scheduled\n"
            % fqcn,
            encoding="utf-8",
        )
        return str(path)

    def test_dispositioned_worker_root_proves(self, monkeypatch, tmp_path):
        self._patch_worker(monkeypatch, tmp_path)
        dispositions = self._write_dispositions(tmp_path)
        report, exit_code = shadow_cli.build_mediation_shadow(
            str(tmp_path), None,
            worker_dispositions_path_value=dispositions,
        )
        assert exit_code == 0
        assert report["summary"]["proofStates"] == {
            "proven_worker_mediated": 1
        }
        row = report["entries"][0]
        assert row["proofStatus"] == "proven_worker_mediated"
        applied = report["workerRootDispositions"]["applied"]
        assert [item["workerFqcn"] for item in applied] == [
            "com.example.IntakeWorker"
        ]
        inventory_row = next(
            item
            for item in report["workerRootInventory"]
            if item["workerFqcn"] == "com.example.IntakeWorker"
        )
        assert inventory_row["registered"] is False
        assert inventory_row["dispositioned"] is True

    def test_undispositioned_worker_root_is_counterexample(
        self, monkeypatch, tmp_path
    ):
        self._patch_worker(monkeypatch, tmp_path)
        report, exit_code = shadow_cli.build_mediation_shadow(
            str(tmp_path), None
        )
        assert exit_code == 1
        row = report["entries"][0]
        assert row["proofStatus"] == "counterexample_non_worker_root"
        assert row["reasonCode"] == "GR13_WORKER_ROOT_NOT_REGISTERED"
        assert report["workerRootDispositions"]["applied"] == []

    def test_unknown_disposition_worker_fails_closed(
        self, monkeypatch, tmp_path
    ):
        self._patch_worker(monkeypatch, tmp_path)
        dispositions = self._write_dispositions(
            tmp_path, fqcn="com.example.NotAWorker"
        )
        report, exit_code = shadow_cli.build_mediation_shadow(
            str(tmp_path), None,
            worker_dispositions_path_value=dispositions,
        )
        assert exit_code == 2
        assert (
            "GR13_DISPOSITION_UNKNOWN_WORKER"
            in report["infrastructure"]["failureReasons"]
        )

    def test_explicit_missing_dispositions_file_fails_closed(
        self, monkeypatch, tmp_path
    ):
        self._patch_worker(monkeypatch, tmp_path)
        report, exit_code = shadow_cli.build_mediation_shadow(
            str(tmp_path), None,
            worker_dispositions_path_value=str(
                tmp_path / "does-not-exist.yml"
            ),
        )
        assert exit_code == 2
        assert (
            "GR13_DISPOSITION_SOURCE_UNAVAILABLE"
            in report["infrastructure"]["failureReasons"]
        )
