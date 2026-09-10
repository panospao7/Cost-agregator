"""GR-14u5: self-scoped helper precedence (D1 row-classification pin).

A helper whose mutation sits inside the writer's own canonical direct
scope (``DatabaseWriteBarrier.runWrite``) proves INDEPENDENT of its
callers: with zero detected inbound call sites it is ``PROVEN_HELPER``,
not a zero-inbound external entry.  This is the D1 shape —
``ReviewQueueRepository.recoverStuckReviews`` guards its own mutation with
``writeBarrier.runWrite { ... }`` and its only caller lives in a class
``init {}`` block the parser cannot see, so it registers zero-inbound and
was misreported as ``unproven_external_entry``.

The exemption added in ``MediationProver.prove`` is deliberately scoped to
the ZERO-INBOUND case only.  An uncertain inbound edge still stops the
proof (the GR-14f inline-carrier invariant), so the fail-closed neighbours
below cannot silently regress.  Pinned against the REAL production
contract.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder
from scripts.db_guard.mediation_analysis.models import ProofState
from scripts.db_guard.mediation_analysis.proof import MediationProver, MutationSubject

_FILE = "app/src/main/java/com/example/SelfScopedRepo.kt"


def _prove_write_row(source: str):
    """Prove the single ``writeRow`` mutation (``dao.insert``) as a helper."""
    corpus = {_FILE: source}
    builder = CallGraphBuilder(_production_contract(), corpus)
    graph = builder.build()
    models = [
        model for model in builder.callables.values() if model.method == "writeRow"
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
            mutation_key="self-scoped-helper-test",
            callable_key=models[0].key,
            barrier_mode="helper",
            site_start=source.index("dao.insert("),
        )
    )


_HEADER = (
    "package com.example\n"
    "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
    "class SelfScopedRepo(\n"
    "    private val dao: Dao,\n"
    "    private val writeBarrier: DatabaseWriteBarrier,\n"
    ") {\n"
)


class TestSelfScopedHelperZeroInbound:
    def test_self_scoped_helper_with_zero_callers_proves(self):
        """Defect I: a self-guarded writer needs no caller to prove it."""
        source = (
            _HEADER
            + "    private fun writeRow(x: Int) {\n"
            "        writeBarrier.runWrite {\n"
            "            dao.insert(x)\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        proof = _prove_write_row(source)
        assert proof.proof_state is ProofState.PROVEN_HELPER
        assert proof.reason_code != "GR13_ZERO_INBOUND_CALL_SITES"
        assert proof.local_guard == "direct"

    def test_non_self_scoped_helper_with_zero_callers_stays_external_entry(self):
        """The exemption is self-scope-only: a bare writer still fails closed."""
        source = (
            "package com.example\n"
            "class PlainRepo(private val dao: Dao) {\n"
            "    private fun writeRow(x: Int) {\n"
            "        dao.insert(x)\n"
            "    }\n"
            "}\n"
        )
        proof = _prove_write_row(source)
        assert proof.proof_state is ProofState.UNPROVEN_EXTERNAL_ENTRY
        assert proof.reason_code == "GR13_ZERO_INBOUND_CALL_SITES"


class TestSelfScopedHelperInvariants:
    def test_self_scoped_helper_with_uncertain_caller_stays_unproven(self):
        """Step-5 invariant: an uncertain inbound edge still stops the proof.

        Co-locates the guard proven by
        ``test_gr14f_inline_carriers.py::
        test_inline_carrier_site_keeps_uncertain_evidence_when_unbindable``:
        the zero-inbound exemption must NOT widen into the uncertain case.
        """
        source = (
            _HEADER
            + "    fun guardedWrite(x: Int) {\n"
            "        mystery().let { helper ->\n"
            "            helper.writeRow(x)\n"
            "        }\n"
            "    }\n"
            "\n"
            "    private fun writeRow(x: Int) {\n"
            "        writeBarrier.runWrite {\n"
            "            dao.insert(x)\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        proof = _prove_write_row(source)
        assert proof.proof_state in (
            ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK,
            ProofState.UNPROVEN_AMBIGUOUS_CALL,
        )
        assert proof.reason_code != "GR13_ZERO_INBOUND_CALL_SITES"

    def test_guarded_caller_path_still_proves(self):
        """Ordinary exact-inbound proof path is untouched by the exemption."""
        source = (
            _HEADER
            + "    fun guardedWrite(x: Int) {\n"
            "        writeBarrier.runWrite {\n"
            "            writeRow(x)\n"
            "        }\n"
            "    }\n"
            "\n"
            "    private fun writeRow(x: Int) {\n"
            "        dao.insert(x)\n"
            "    }\n"
            "}\n"
        )
        proof = _prove_write_row(source)
        assert proof.proof_state is ProofState.PROVEN_HELPER
