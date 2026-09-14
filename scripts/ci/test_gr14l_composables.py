"""GR-14l: composable context admission pins and proof behavior.

Inside a @Composable callable, every non-launch lambda region (layout
content, onClick, remember blocks) executes in composition context —
admitted as context-inherited (never a guard source).  Launch-family
carriers keep their own admission rule (unknown receivers stay
async-uncertain even inside composables).

Consequence under the approved strictness model: a writer reached through
the Compose boundary proves iff it carries its own canonical scope;
unscoped writers on composable-reachable paths are DEFINITE
counterexamples.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import (
    PRODUCTION_TRANSPARENT_INLINE_METHODS,
    CallGraphBuilder,
)
from scripts.db_guard.mediation_analysis.models import ProofState
from scripts.db_guard.mediation_analysis.proof import MediationProver, MutationSubject


def _prove_write_row(source: str):
    corpus = {"app/src/main/java/com/example/GroupsScreen.kt": source}
    builder = CallGraphBuilder(_production_contract(), corpus)
    graph = builder.build()
    models = [m for m in builder.callables.values() if m.method == "writeRow"]
    assert len(models) == 1
    prover = MediationProver(
        builder,
        graph,
        registered_do_work_roots=frozenset(),
        ambiguous_worker_classes=frozenset(),
    )
    return prover.prove(
        MutationSubject(
            mutation_key="composable-admission-test",
            callable_key=models[0].key,
            barrier_mode="helper",
            site_start=source.index("dao.insert("),
        )
    )


_HEADER = (
    "package com.example\n"
    "import androidx.compose.runtime.Composable\n"
    "import androidx.compose.foundation.layout.Box\n"
    "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
    "class GroupsScreenRepo(\n"
    "    private val dao: Dao,\n"
    "    private val writeBarrier: DatabaseWriteBarrier,\n"
    ") {\n"
)

_FOOTER = "}\n"


class TestComposableContextAdmission:
    def test_set_content_admitted(self):
        assert "setContent" in PRODUCTION_TRANSPARENT_INLINE_METHODS

    def test_scoped_writer_behind_composable_boundary_proves(self):
        """SL-04 shape: a writer with its own canonical scope, reached
        through a @Composable layout content lambda, proves."""
        source = (
            _HEADER
            + "    @Composable\n"
            "    fun Screen(groupId: Long) {\n"
            "        Box {\n"
            "            GroupRow(groupId)\n"
            "        }\n"
            "    }\n"
            "\n"
            "    fun GroupRow(groupId: Long) {\n"
            "        writeBarrier.runWrite(\n"
            "            com.yourname.expensetracker.data.backup"
            ".DatabaseAccessOperation(\"g\")\n"
            "        ) {\n"
            "            writeRow(groupId)\n"
            "        }\n"
            "    }\n"
            "\n"
            "    private fun writeRow(id: Long) {\n"
            "        dao.insert(id)\n"
            "    }\n"
            + _FOOTER
        )
        proof = _prove_write_row(source)
        assert proof.proof_state is ProofState.PROVEN_HELPER

    def test_unscoped_writer_behind_composable_boundary_is_counterexample(
        self,
    ):
        """SL-05 shape: the strictness inversion extends to the Compose
        boundary — an unscoped writer behind a @Composable layout lambda
        is a DEFINITE counterexample."""
        source = (
            _HEADER
            + "    @Composable\n"
            "    fun Screen(groupId: Long) {\n"
            "        Box {\n"
            "            writeRow(groupId)\n"
            "        }\n"
            "    }\n"
            "\n"
            "    private fun writeRow(id: Long) {\n"
            "        dao.insert(id)\n"
            "    }\n"
            + _FOOTER
        )
        proof = _prove_write_row(source)
        assert (
            proof.proof_state is ProofState.COUNTEREXAMPLE_UNGUARDED_CALL_PATH
        )

    def test_unknown_launch_inside_composable_stays_uncertain(self):
        """Launch-family carriers keep their own admission rule even inside
        composables: an unknown-receiver launch stays async-uncertain."""
        source = (
            _HEADER
            + "    private val injectedScope = kotlin.coroutines.EmptyCoroutineContext\n"
            "    @Composable\n"
            "    fun Screen(groupId: Long) {\n"
            "        Box {\n"
            "            SomeScope.launch { writeRow(groupId) }\n"
            "        }\n"
            "    }\n"
            "\n"
            "    private fun writeRow(id: Long) {\n"
            "        dao.insert(id)\n"
            "    }\n"
            + _FOOTER
        )
        proof = _prove_write_row(source)
        assert (
            proof.proof_state is ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK
        )
