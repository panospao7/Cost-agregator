"""GR-14f: production inline-carrier table pins and proof behavior.

Pins the closed ``PRODUCTION_TRANSPARENT_INLINE_METHODS`` set, its wiring
into the production and fixture contracts, and the engine-level proof
behavior against the REAL production contract (HP-13..HP-20 mirror the
fixture corpus; this module drives the direct prover).

Any member change to the inline set requires a reviewed diff with fixture
coverage (fixtures helper_proof HP-13..HP-20) and a shadow before/after
delta recorded in the batch manifest.
"""
from __future__ import annotations

import pytest

import scripts.ci.inspect_db_mediation_proof as shadow_cli
from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import (
    PRODUCTION_TRANSPARENT_INLINE_METHODS,
    AnalysisContract,
    CallGraphBuilder,
)
from scripts.db_guard.mediation_analysis.fixture_runner import FIXTURE_CONTRACT
from scripts.db_guard.mediation_analysis.models import ProofState
from scripts.db_guard.mediation_analysis.proof import MediationProver, MutationSubject


class TestInlineCarrierSet:
    def test_production_set_is_pinned(self):
        assert PRODUCTION_TRANSPARENT_INLINE_METHODS == (
            "let", "also", "apply", "run", "with", "takeIf", "takeUnless",
            "runCatching", "getOrElse", "onFailure", "use", "repeat",
            "forEach", "forEachIndexed", "map", "mapNotNull", "mapIndexed",
            "filter", "any", "none", "count", "first", "firstOrNull",
            "find", "associate", "associateBy", "buildSet", "buildList",
            "withLock", "withTimeout", "withTimeoutOrNull",
            "collect", "withPermit",
            # GR-14u56c: kotlinx structured suspend scopes.  `async` is
            # additionally gated by the receiver-evidence rule in
            # _lambda_regions (receiverless or a reviewed owned receiver
            # only) — membership here alone never admits it.
            "coroutineScope", "async",
            "runOperation", "runCatchingCancellable", "runPostCommitSafely",
            "safeRecordMatchEvent",
            "guardTerminal", "withBoundedTerminalWrite",
            "safeExecute",
            # GR-14u56d: CompositeGeocodingService.safeLookup (exactly-once
            # inline suspend wrapper, CancellationException rethrown — the
            # u54 safeExecute shape).
            "safeLookup",
            "setContent",
        )

    def test_production_contract_wires_the_set(self):
        contract = _production_contract()
        assert (
            contract.transparent_inline_methods
            == PRODUCTION_TRANSPARENT_INLINE_METHODS
        )

    def test_set_is_disjoint_from_authority_surfaces(self):
        contract = _production_contract()
        inline = set(contract.transparent_inline_methods)
        assert not inline & set(contract.worker_guard_scope_methods)
        assert not inline & set(contract.direct_scope_methods)
        assert not inline & set(contract.transparent_scope_methods)

    def test_fixture_contract_mirrors_production_set(self):
        assert (
            FIXTURE_CONTRACT.transparent_inline_methods
            == PRODUCTION_TRANSPARENT_INLINE_METHODS
        )

    def test_contract_rejects_bad_inline_sets(self):
        base = dict(
            worker_guard_receiver_fqcn="a.B",
            worker_guard_scope_methods=("runGuarded",),
            direct_scope_receiver_fqcn="c.D",
            direct_scope_methods=("runWrite",),
        )
        with pytest.raises(TypeError):
            AnalysisContract(**base, transparent_inline_methods=["let"])
        with pytest.raises(ValueError):
            AnalysisContract(**base, transparent_inline_methods=("has space",))
        with pytest.raises(ValueError):
            AnalysisContract(
                **base,
                transparent_scope_methods=("run",),
                transparent_inline_methods=("run",),
            )


# ── Engine behavior against the real production contract ────────────────────

_HEADER = (
    "package com.example\n"
    "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
    "class InlineCarrierRepo(\n"
    "    private val dao: Dao,\n"
    "    private val writeBarrier: DatabaseWriteBarrier,\n"
    ") {\n"
    "    fun guardedWrite(x: Int) {\n"
    "        writeBarrier.runWrite {\n"
    "            writeRow(x)\n"
    "        }\n"
    "    }\n"
    "\n"
)
_FOOTER = "}\n"


def _project(helper_body: str, extra_members: str = "") -> str:
    return (
        _HEADER
        + "    private fun writeRow(x: Int) {\n"
        + helper_body
        + "    }\n"
        + extra_members
        + _FOOTER
    )


def _prove_helper(source: str):
    corpus = {"app/src/main/java/com/example/InlineCarrierRepo.kt": source}
    builder = CallGraphBuilder(_production_contract(), corpus)
    graph = builder.build()
    models = [
        model
        for model in builder.callables.values()
        if model.method == "writeRow"
    ]
    assert len(models) == 1
    model = models[0]
    prover = MediationProver(
        builder,
        graph,
        registered_do_work_roots=frozenset(),
        ambiguous_worker_classes=frozenset(),
    )
    subject = MutationSubject(
        mutation_key="inline-carrier-test",
        callable_key=model.key,
        barrier_mode="helper",
        site_start=source.index("dao.insert("),
    )
    return prover.prove(subject)


class TestInlineCarrierProofBehavior:
    def test_let_carrier_proves_helper(self):
        proof = _prove_helper(
            _project(
                "        x.let { value ->\n"
                "            dao.insert(value)\n"
                "        }\n"
            )
        )
        assert proof.proof_state is ProofState.PROVEN_HELPER

    def test_structured_suspend_carriers_prove_helper(self):
        proof = _prove_helper(
            _project(
                "        withTimeout(1000) {\n"
                "            lock.withLock {\n"
                "                dao.insert(x)\n"
                "            }\n"
                "        }\n"
            )
        )
        assert proof.proof_state is ProofState.PROVEN_HELPER

    def test_project_recorder_carrier_proves_helper(self):
        proof = _prove_helper(
            _project(
                "        runOperation(\"repo.write\") {\n"
                "            dao.insert(x)\n"
                "        }\n"
            )
        )
        assert proof.proof_state is ProofState.PROVEN_HELPER

    def test_assigned_lambda_still_escapes(self):
        proof = _prove_helper(
            _project(
                "        val deferred = {\n"
                "            dao.insert(x)\n"
                "        }\n"
                "        deferred()\n"
            )
        )
        assert (
            proof.proof_state is ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK
        )
        assert proof.reason_code == "GR13_SITE_INSIDE_UNRESOLVED_LAMBDA"

    def test_unclosed_custom_carrier_stays_async(self):
        proof = _prove_helper(
            _project(
                "        runElsewhere {\n"
                "            dao.insert(x)\n"
                "        }\n",
                extra_members=(
                    "\n"
                    "    private fun runElsewhere(block: () -> Unit) {\n"
                    "        block()\n"
                    "    }\n"
                ),
            )
        )
        assert (
            proof.proof_state is ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK
        )
        assert proof.deciding_resolution is not None
        assert proof.deciding_resolution.value == "async_dispatch"

    def test_transparency_is_not_authorization(self):
        source = (
            "package com.example\n"
            "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
            "class InlineCarrierRepo(\n"
            "    private val dao: Dao,\n"
            "    private val writeBarrier: DatabaseWriteBarrier,\n"
            ") {\n"
            "    fun unguardedWrite(x: Int) {\n"
            "        writeRow(x)\n"
            "    }\n"
            "\n"
            "    private fun writeRow(x: Int) {\n"
            "        x.let { value ->\n"
            "            dao.insert(value)\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        proof = _prove_helper(source)
        assert (
            proof.proof_state is ProofState.COUNTEREXAMPLE_UNGUARDED_CALL_PATH
        )

    def test_inline_carrier_site_keeps_uncertain_evidence_when_unbindable(self):
        """GR-14f resolution-preservation rule: a call inside an inline
        carrier whose receiver cannot be exactly bound (untracked chain)
        must keep uncertain corpus evidence — the subject must NEVER degrade
        to a zero-inbound external-entry misdiagnosis.

        GR-14u27 note: the subject here is self-guarded (its mutation sits in
        the writer's own canonical scope), so the proof now succeeds on that
        local evidence rather than staying unproven.  The reachability
        assertion below is the invariant this test exists to protect and is
        unchanged: the uncertain inbound edge is still present."""
        source = (
            "package com.example\n"
            "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
            "class InlineCarrierRepo(\n"
            "    private val dao: Dao,\n"
            "    private val writeBarrier: DatabaseWriteBarrier,\n"
            ") {\n"
            "    fun guardedWrite(x: Int) {\n"
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
        corpus = {
            "app/src/main/java/com/example/InlineCarrierRepo.kt": source
        }
        builder = CallGraphBuilder(_production_contract(), corpus)
        graph = builder.build()
        models = [
            model
            for model in builder.callables.values()
            if model.method == "writeRow"
        ]
        assert len(models) == 1
        model = models[0]
        inbound_uncertain = [
            edge
            for edge in graph.edges
            if edge.uncertain and model.key in edge.targets
        ]
        assert inbound_uncertain, (
            "inline-carrier site lost corpus reachability for writeRow"
        )
        prover = MediationProver(
            builder,
            graph,
            registered_do_work_roots=frozenset(),
            ambiguous_worker_classes=frozenset(),
        )
        proof = prover.prove(
            MutationSubject(
                mutation_key="inline-carrier-preservation",
                callable_key=model.key,
                barrier_mode="helper",
                site_start=source.index("dao.insert("),
            )
        )
        assert proof.proof_state is ProofState.PROVEN_HELPER
        assert proof.local_guard == "direct"
        assert proof.reason_code != "GR13_ZERO_INBOUND_CALL_SITES"


# ── GR-14u56c: structured suspend scopes + async receiver-evidence gate ─────

def _coro_project(helper_body: str, extra_imports: str = "") -> str:
    return (
        "package com.example\n"
        "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
        + extra_imports
        + "class CoroScopeRepo(\n"
        "    private val dao: Dao,\n"
        "    private val writeBarrier: DatabaseWriteBarrier,\n"
        ") {\n"
        "    fun guardedWrite(x: Int) {\n"
        "        writeBarrier.runWrite {\n"
        "            writeRow(x)\n"
        "        }\n"
        "    }\n"
        "\n"
        "    private suspend fun writeRow(x: Int) {\n"
        + helper_body
        + "    }\n"
        "}\n"
    )


def _prove_coro_helper(source: str, extra_imports: str = ""):
    corpus = {"app/src/main/java/com/example/CoroScopeRepo.kt": source}
    builder = CallGraphBuilder(_production_contract(), corpus)
    graph = builder.build()
    models = [
        model
        for model in builder.callables.values()
        if model.method == "writeRow"
    ]
    assert len(models) == 1
    model = models[0]
    prover = MediationProver(
        builder,
        graph,
        registered_do_work_roots=frozenset(),
        ambiguous_worker_classes=frozenset(),
    )
    subject = MutationSubject(
        mutation_key="coro-scope-test",
        callable_key=model.key,
        barrier_mode="helper",
        site_start=source.index("dao.insert("),
    )
    return prover.prove(subject)


class TestStructuredSuspendScopes:
    def test_coroutinescope_carrier_proves_helper(self):
        proof = _prove_coro_helper(
            _coro_project(
                "        coroutineScope {\n"
                "            dao.insert(x)\n"
                "        }\n"
            ),
            extra_imports="import kotlinx.coroutines.coroutineScope\n",
        )
        assert proof.proof_state is ProofState.PROVEN_HELPER

    def test_async_inside_coroutinescope_proves_helper(self):
        proof = _prove_coro_helper(
            _coro_project(
                "        coroutineScope {\n"
                "            async {\n"
                "                dao.insert(x)\n"
                "            }\n"
                "        }\n"
            ),
            extra_imports=(
                "import kotlinx.coroutines.coroutineScope\n"
                "import kotlinx.coroutines.async\n"
            ),
        )
        assert proof.proof_state is ProofState.PROVEN_HELPER

    def test_async_on_unowned_receiver_stays_async(self):
        """The receiver-evidence gate: `unownedScope.async { }` is neither
        receiverless nor on the reviewed owned-receiver set, so the region
        keeps its default async uncertainty and the helper stays unproven
        (fail closed; the GlobalScope.async family)."""
        proof = _prove_coro_helper(
            _coro_project(
                "        coroutineScope {\n"
                "            unownedScope.async {\n"
                "                dao.insert(x)\n"
                "            }\n"
                "        }\n",
            ),
            extra_imports=(
                "import kotlinx.coroutines.coroutineScope\n"
                "import kotlinx.coroutines.async\n"
            ),
        )
        assert (
            proof.proof_state is ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK
        )

    def test_coroutinescope_receiver_form_stays_async(self):
        """`coroutineScope` is receiverless by nature; a receiver-qualified
        spelling is a different (unmodeled) method and keeps the default
        async uncertainty."""
        proof = _prove_coro_helper(
            _coro_project(
                "        someHolder.coroutineScope {\n"
                "            dao.insert(x)\n"
                "        }\n"
            )
        )
        assert (
            proof.proof_state is ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK
        )
