"""GR-14u27: a self-guarded mutation proves on its own evidence.

The GR-12 dominance engine can prove that a canonical direct barrier dominates a
mutation *inside the writer's own body* (that is what ``local_guard == "direct"``
means).  Such a mutation is guarded on every execution — the barrier is
evaluated at the mutation site against app-global maintenance state — so the
identity of the caller cannot change the outcome.

Step 7 of the prover already encodes exactly that (``effective == {"direct"}``
when the local guard is ``direct``).  Before GR-14u27, steps 5 and 6 returned
"unproven" first: an inbound uncertain edge (step 5) or the absence of a
discoverable exact production path (step 6) stopped the proof even though the
local evidence was sufficient on its own.

These pins drive the PRODUCTION mechanism (the dominance-prover callback), which
the fixture corpus cannot reach — the fixture harness wires no direct-site
prover.  HP-24 / HP-25 cover the region-carrier form end to end; this file
covers the prover form.
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(
    os.path.abspath(__file__)))))

from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder
from scripts.db_guard.mediation_analysis.models import ProofState
from scripts.db_guard.mediation_analysis.proof import (
    MediationProver,
    MutationSubject,
)
from scripts.db_guard.mediation_analysis import test_fixtures

_PATH = "app/src/main/java/t/Fixture.kt"

# `writeRow` owns the mutation; its ONLY caller reaches it through a lambda
# whose carrier is unknown, i.e. an uncertain (async) inbound edge.  There is
# therefore no exact production path either.
_SOURCE = """
package t

class Store {
    fun put(key: Int, value: Int) {}
}

class Target {
    private val store = Store()

    fun writeRow(id: Int) {
        store.put(id, id)
    }

    fun exercise(dispatcher: (block: () -> Unit) -> Unit, id: Int) {
        dispatcher { writeRow(id) }
    }
}
"""


def _contract():
    """The fixture contract is the engine's own production-shaped one."""
    from scripts.db_guard.mediation_analysis.fixture_runner import (
        FIXTURE_CONTRACT,
    )

    return FIXTURE_CONTRACT


def _builder(source: str = _SOURCE) -> CallGraphBuilder:
    builder = CallGraphBuilder(_contract(), {_PATH: source})
    return builder


def _setup(source: str = _SOURCE):
    builder = _builder(source)
    graph = builder.build()
    model = next(m for m in builder.callables.values() if m.method == "writeRow")
    masked = builder.file_models[model.file].masked
    site = masked.index("put", model.body_start)
    return builder, graph, model, site


def _prover(builder, graph, *, proven: bool | None, status: str | None):
    """A prover with a stub dominance callback (``None`` = not wired)."""
    if proven is None:
        return MediationProver(
            builder, graph,
            registered_do_work_roots=frozenset(),
            ambiguous_worker_classes=frozenset(),
        )
    return MediationProver(
        builder, graph,
        registered_do_work_roots=frozenset(),
        ambiguous_worker_classes=frozenset(),
        direct_site_prover=lambda _key, _site: proven,
        direct_site_status=lambda _key, _site: status,
    )


def _prove(mode="helper", *, proven=True, status="proven"):
    builder, graph, model, site = _setup()
    prover = _prover(builder, graph, proven=proven, status=status)
    return prover.prove(MutationSubject(
        mutation_key="u27", callable_key=model.key,
        barrier_mode=mode, site_start=site,
    ))


def test_proven_local_guard_proves_despite_uncertain_caller():
    proof = _prove()
    assert proof.proof_state is ProofState.PROVEN_HELPER, proof.proof_state


def test_proven_local_guard_reports_direct_and_all_paths_guarded():
    proof = _prove()
    assert proof.local_guard == "direct"
    assert proof.reason_code == "GR13_ALL_PATHS_GUARDED"


def test_no_uncertain_caller_is_not_required_for_the_proof():
    """Same site, but with NO caller at all — must stay proven too."""
    builder, graph, model, site = _setup("""
package t

class Store {
    fun put(key: Int, value: Int) {}
}

class Target {
    private val store = Store()

    fun writeRow(id: Int) {
        store.put(id, id)
    }
}
""")
    prover = _prover(builder, graph, proven=True, status="proven")
    proof = prover.prove(MutationSubject(
        mutation_key="u27", callable_key=model.key,
        barrier_mode="helper", site_start=site,
    ))
    assert proof.proof_state is ProofState.PROVEN_HELPER, proof.proof_state


def test_unguarded_locally_stays_unproven_async():
    """FAIL CLOSED: no proven local guard => the uncertain caller still blocks."""
    proof = _prove(proven=False, status="unguarded")
    assert proof.proof_state is ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK, (
        proof.proof_state
    )


def test_unmodelable_local_guard_stays_unproven():
    """`unmodelable` is absence of evidence, not evidence of a guard."""
    proof = _prove(proven=False, status="unmodelable")
    assert proof.proof_state is ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK, (
        proof.proof_state
    )


def test_without_a_direct_prover_the_row_stays_unproven():
    builder, graph, model, site = _setup()
    prover = _prover(builder, graph, proven=None, status=None)
    proof = prover.prove(MutationSubject(
        mutation_key="u27", callable_key=model.key,
        barrier_mode="helper", site_start=site,
    ))
    assert proof.proof_state is ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK, (
        proof.proof_state
    )


def test_worker_mode_does_not_use_the_helper_bypass():
    """The bypass is helper-only; worker mediation keeps its own rules."""
    proof = _prove(mode="workerMediated")
    assert proof.proof_state is not ProofState.PROVEN_HELPER, proof.proof_state
    assert proof.proof_state is ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK, (
        proof.proof_state
    )
