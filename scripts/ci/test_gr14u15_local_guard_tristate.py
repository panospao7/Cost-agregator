"""GR-14u15: the local guard proof is TRI-STATE and position-aware.

An unmodelable body must not be reported as an unguarded call path merely
because the GR-12 engine refused to model it — but the exemption only applies
where a canonical barrier call actually PRECEDES the mutation.  A mutation that
comes before any barrier stays "unguarded", so a second, genuinely unguarded
mutation in the same body is never silently excused.

The unmodelable case is the REAL one: `DatabaseWriteBarrier.runWrite` requires
an `operation` argument, so callers write `runWrite(op) { ... }`, which the
tokenizer's barrier-scope form does not accept (GR-14u16 target).
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import (
    _DirectSiteProver,
    _production_contract,
)
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder
from scripts.db_guard.direct_barrier_bridge import (
    callable_body_span,
    mutation_sites_from_observations,
    prove_callable_direct_barriers,
)
from scripts.db_guard.mutation_observation import MutationObservation

_PATH = "app/src/main/java/com/example/Repo.kt"
_SOURCE = (
    "package com.example\n"
    "\n"
    "import com.yourname.expensetracker.data.backup.DatabaseAccessOperation\n"
    "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
    "\n"
    "class Repo(private val dao: Dao) {\n"
    "    private val writeBarrier: DatabaseWriteBarrier = TODO()\n"
    "\n"
    "    suspend fun guardedViaRunWrite() {\n"
    "        writeBarrier.runWrite(DatabaseAccessOperation(\"runWrite\")) {\n"
    "            dao.delete()\n"
    "        }\n"
    "    }\n"
    "\n"
    "    suspend fun writeBeforeLateGuard() {\n"
    "        dao.delete()\n"
    "        writeBarrier.checkWritesAllowed(\"late\")\n"
    "    }\n"
    "\n"
    "    suspend fun writeBeforeLateRunWrite() {\n"
    "        dao.delete()\n"
    "        writeBarrier.runWrite(DatabaseAccessOperation(\"late\")) {\n"
    "            dao.delete()\n"
    "        }\n"
    "    }\n"
    "\n"
    "    suspend fun modeledWithGuard() {\n"
    "        writeBarrier.checkWritesAllowed(\"modeled\")\n"
    "        dao.delete()\n"
    "    }\n"
    "}\n"
)


def _setup():
    builder = CallGraphBuilder(_production_contract(), {_PATH: _SOURCE})
    builder.build()
    masked = builder.file_models[_PATH].masked
    keys = {model.method: key for key, model in builder.callables.items()}
    return builder, masked, keys


def _observation(callable_key: str, source_start: int) -> MutationObservation:
    return MutationObservation(
        path=_PATH,
        callable_key=callable_key,
        source_start=source_start,
        source_end=source_start + len("dao.delete()"),
        line=1,
        column=1,
        dao_accessor="dao",
        dao_fqcn="com.example.Dao",
        operation="delete",
        mutation_kind="dao_call",
        source_identity="Dao::com.example.Dao#delete()",
    )


def _mutation_offset(builder, masked, key):
    """The mutation offset INSIDE this callable (never another method's)."""
    model = builder.callables[key]
    return masked.index("dao.delete()", model.decl_start)


def _prover_for(builder, masked, key):
    offset = _mutation_offset(builder, masked, key)
    observation = _observation(key, offset)
    return _DirectSiteProver(builder, {key: [observation]}), observation


class TestLocalGuardTriState:
    def test_preceding_barrier_with_unmodelable_body_is_not_unguarded(self):
        """The Bug 2b case: guard present, body unmodelable, mutation covered."""
        builder, masked, keys = _setup()
        key = keys["guardedViaRunWrite"]
        prover, observation = _prover_for(builder, masked, key)

        assert prover.local_status(key, observation.source_start) == "unmodelable"

    def test_mutation_before_any_barrier_stays_unguarded(self):
        """Position matters: a LATER barrier does not cover this mutation.

        The body is deliberately UNMODELABLE (the trailing-lambda runWrite form)
        so this exercises the position filter itself: were the rule "a barrier
        exists anywhere in the body", this site would wrongly become
        "unmodelable" and an unguarded write would be excused.
        """
        builder, masked, keys = _setup()
        key = keys["writeBeforeLateRunWrite"]
        model = builder.callables[key]
        body = callable_body_span(masked, model)
        mutation = _mutation_offset(builder, masked, key)
        outcome = prove_callable_direct_barriers(
            masked,
            body,
            tuple(mutation_sites_from_observations([_observation(key, mutation)])),
            path=_PATH,
            callable_key=key,
        )

        assert outcome.diagnostics == ("DB_DIRECT_BARRIER_PROOF_UNSUPPORTED",)
        assert all(offset > mutation for offset in outcome.barrier_call_offsets)

        prover, observation = _prover_for(builder, masked, key)
        assert prover.local_status(key, observation.source_start) == "unguarded"

    def test_modelable_body_with_dominating_barrier_is_proven(self):
        builder, masked, keys = _setup()
        key = keys["modeledWithGuard"]
        prover, observation = _prover_for(builder, masked, key)

        assert prover.local_status(key, observation.source_start) == "proven"

    def test_unknown_callable_falls_back_to_todays_behaviour(self):
        """No recorded status must not silently suppress a counterexample."""
        builder, _masked, _keys = _setup()
        prover = _DirectSiteProver(builder, {})
        assert prover.local_status("no-such-callable", 0) == "unguarded"


class TestBarrierOffsetsSurviveAnUnmodelableBody:
    def test_runwrite_body_is_unmodelable_but_reports_its_barrier(self):
        builder, masked, keys = _setup()
        key = keys["guardedViaRunWrite"]
        model = builder.callables[key]
        body = callable_body_span(masked, model)
        mutation = _mutation_offset(builder, masked, key)
        outcome = prove_callable_direct_barriers(
            masked,
            body,
            tuple(mutation_sites_from_observations([_observation(key, mutation)])),
            path=_PATH,
            callable_key=key,
        )

        # The trailing-lambda canonical form is not yet accepted by the
        # tokenizer, so the body cannot be modeled ...
        assert outcome.diagnostics == ("DB_DIRECT_BARRIER_PROOF_UNSUPPORTED",)
        # ... but the barrier is still visible in the text, and it precedes the
        # mutation, which is what keeps this row out of the counterexamples.
        assert outcome.barrier_call_offsets
        assert all(offset < mutation for offset in outcome.barrier_call_offsets)

    def test_late_guard_offsets_are_after_the_mutation(self):
        builder, masked, keys = _setup()
        key = keys["writeBeforeLateGuard"]
        model = builder.callables[key]
        body = callable_body_span(masked, model)
        mutation = _mutation_offset(builder, masked, key)
        outcome = prove_callable_direct_barriers(
            masked,
            body,
            tuple(mutation_sites_from_observations([_observation(key, mutation)])),
            path=_PATH,
            callable_key=key,
        )

        # The only barrier sits AFTER the mutation, so it cannot excuse it.
        assert outcome.barrier_call_offsets
        assert all(offset > mutation for offset in outcome.barrier_call_offsets)
