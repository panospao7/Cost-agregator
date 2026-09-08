"""GR-14j: structured coroutine-launch admission pins and proof behavior.

Pins the closed reviewed receiver set, its production wiring, and the
engine-level semantics against the REAL production contract:

* a launch on an admitted structured receiver is EXACT with context
  inherited (SL-01: guarded caller path proves the helper);
* a launch on an unreviewed receiver keeps the default async uncertainty
  (SL-02: fail closed);
* the strictness inversion (SL-03): with the launch edge exact, a
  mutation with no local canonical scope on a launch-reachable path from
  a public root is a DEFINITE counterexample.

Any change to the receiver set or the launch-method list requires a
dedicated reviewed diff with fixture coverage (fixtures helper_proof
SL-01..SL-03) and a shadow before/after delta.
"""
from __future__ import annotations

import pytest

import scripts.ci.inspect_db_mediation_proof as shadow_cli
from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import (
    PRODUCTION_TRANSPARENT_INLINE_METHODS,
    STRUCTURED_LAUNCH_METHODS,
    AnalysisContract,
    CallGraphBuilder,
)
from scripts.db_guard.mediation_analysis.models import ProofState
from scripts.db_guard.mediation_analysis.proof import MediationProver, MutationSubject


class TestStructuredLaunchAdmissionPins:
    def test_production_receiver_set_is_pinned(self):
        assert shadow_cli._PRODUCTION_STRUCTURED_LAUNCH_RECEIVERS == (
            "viewModelScope",
            "scope",
            "workTracker",
            "serviceScope",
            "diagnosticScope",
        )

    def test_production_contract_wires_receiver_set(self):
        contract = _production_contract()
        assert (
            contract.structured_launch_receivers
            == shadow_cli._PRODUCTION_STRUCTURED_LAUNCH_RECEIVERS
        )

    def test_launch_methods_closed_and_disjoint(self):
        assert STRUCTURED_LAUNCH_METHODS == ("launch",)
        assert not set(STRUCTURED_LAUNCH_METHODS) & set(
            PRODUCTION_TRANSPARENT_INLINE_METHODS
        )

    def test_contract_rejects_bad_receiver_sets(self):
        base = dict(
            worker_guard_receiver_fqcn="a.B",
            worker_guard_scope_methods=("runGuarded",),
            direct_scope_receiver_fqcn="c.D",
            direct_scope_methods=("runWrite",),
        )
        with pytest.raises(TypeError):
            AnalysisContract(**base, structured_launch_receivers=["x"])
        with pytest.raises(ValueError):
            AnalysisContract(**base, structured_launch_receivers=("has space",))


# ── Engine behavior against the real production contract ────────────────────


class _StructuredLaunchRepo:
    FILE = "app/src/main/java/com/example/StructuredLaunchRepo.kt"


def _prove_write_row(source: str):
    corpus = {_StructuredLaunchRepo.FILE: source}
    builder = CallGraphBuilder(_production_contract(), corpus)
    graph = builder.build()
    models = [
        m for m in builder.callables.values() if m.method == "writeRow"
    ]
    assert len(models) == 1
    prover = MediationProver(
        builder,
        graph,
        registered_do_work_roots=frozenset(),
        ambiguous_worker_classes=frozenset(),
    )
    return prover.prove(
        MutationSubject(
            mutation_key="structured-launch-test",
            callable_key=models[0].key,
            barrier_mode="helper",
            site_start=source.index("dao.insert("),
        )
    )


_HEADER = (
    "package com.example\n"
    "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
    "class StructuredLaunchRepo(\n"
    "    private val dao: Dao,\n"
    "    private val writeBarrier: DatabaseWriteBarrier,\n"
    ") {\n"
    "    private val viewModelScope = Scope()\n"
    "    class Scope { fun launch(block: () -> Unit) { block() } }\n"
)

_FOOTER = "}\n"


class TestStructuredLaunchProofBehavior:
    def test_guarded_caller_through_structured_launch_proves(self):
        source = (
            _HEADER
            + "    fun guardedWrite(x: Int) {\n"
            "        writeBarrier.runWrite(\n"
            "            com.yourname.expensetracker.data.backup"
            ".DatabaseAccessOperation(\"s\")\n"
            "        ) {\n"
            "            viewModelScope.launch { writeRow(x) }\n"
            "        }\n"
            "    }\n"
            "\n"
            "    private fun writeRow(x: Int) {\n"
            "        dao.insert(x)\n"
            "    }\n"
            + _FOOTER
        )
        proof = _prove_write_row(source)
        assert proof.proof_state is ProofState.PROVEN_HELPER

    def test_unknown_scope_launch_stays_uncertain(self):
        source = (
            _HEADER
            + "    private val injectedScope = Scope()\n"
            "    fun guardedWrite(x: Int) {\n"
            "        writeBarrier.runWrite(\n"
            "            com.yourname.expensetracker.data.backup"
            ".DatabaseAccessOperation(\"s\")\n"
            "        ) {\n"
            "            injectedScope.launch { writeRow(x) }\n"
            "        }\n"
            "    }\n"
            "\n"
            "    private fun writeRow(x: Int) {\n"
            "        dao.insert(x)\n"
            "    }\n"
            + _FOOTER
        )
        proof = _prove_write_row(source)
        assert (
            proof.proof_state is ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK
        )

    def test_launch_reachable_unguarded_is_counterexample(self):
        """The strictness inversion (SL-03)."""
        source = (
            "package com.example\n"
            "class UnguardedLaunchRepo(private val dao: Dao) {\n"
            "    private val viewModelScope = Scope()\n"
            "    class Scope { fun launch(block: () -> Unit) { block() } }\n"
            "    fun unguardedWrite(x: Int) {\n"
            "        viewModelScope.launch { writeRow(x) }\n"
            "    }\n"
            "\n"
            "    private fun writeRow(x: Int) {\n"
            "        dao.insert(x)\n"
            "    }\n"
            "}\n"
        )
        proof = _prove_write_row(source)
        assert (
            proof.proof_state is ProofState.COUNTEREXAMPLE_UNGUARDED_CALL_PATH
        )
