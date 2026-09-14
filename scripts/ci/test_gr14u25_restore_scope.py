"""GR-14u25: Contract V3 — `RestoreInternalWriteScope.run` is the third
canonical scope form (mode-gated, restore-internal).

The restore flow MUST write the restore database during restore windows; the
V2 contract cannot express that (a canonical `checkWritesAllowed` throws in
restore modes by design).  Production therefore grew a second sanctioned
guard form — `RestoreInternalWriteScope.run(operation) { ... }`, which
`require()`s mode ∈ {ASSETS_RESTORING, RESTORE_VERIFYING} — and the GR-14u24
projection surfaced exactly this shape as a counterexample
(DatabaseBackupRepositoryImpl.restoreReceiptAssets).

V3 (this batch) makes the form modelable:
  * contract: `restore_scope_receiver_fqcn` / `restore_scope_methods`
    (receiver-exact, like the worker guard), version bump 2 -> 3, V1/V2
    objects untouched;
  * carriers: `canonical_restore` region kind — receiver-exact admission with
    fall-through, because the scope method name `run` is ALSO a stdlib inline
    transparent method (every other receiver keeps today's transparent
    treatment);
  * proof: local context `restore_internal` -> ProofState
    PROVEN_RESTORE_INTERNAL (helper mode), including propagation through
    exact call edges out of the scope; the zero-inbound self-scoped
    exemption (GR-14u5) covers it like `direct`.

Latent by design on the live board: the only production call site sits behind
an interface-dispatch edge that GR-14u24 (HELD) has not made exact yet, so
every existing row keeps its current state (byte-identical board expected).
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import (
    AnalysisContract,
    CallGraphBuilder,
)
from scripts.db_guard.mediation_analysis.models import ProofState
from scripts.db_guard.mediation_analysis.proof import MediationProver, MutationSubject
from scripts.db_guard.structural_analysis.barrier_proof import (
    CANONICAL_BARRIER_CONTRACT_V2,
    CANONICAL_BARRIER_CONTRACT_V3,
)

_SCOPE = "com.yourname.expensetracker.data.backup.RestoreInternalWriteScope"

_DEFS = (
    "package com.yourname.expensetracker.data.backup\n"
    "\n"
    "class RestoreInternalWriteScope {\n"
    "    suspend fun <T> run(operation: String, block: suspend () -> T): T {\n"
    "        return block()\n"
    "    }\n"
    "}\n"
)

_STORE = (
    "package com.example\n"
    "\n"
    "class Store {\n"
    "    private val backing = mutableMapOf<Int, Int>()\n"
    "\n"
    "    fun put(value: Int) {\n"
    "        backing.put(value, value)\n"
    "    }\n"
    "}\n"
)


def _build(*sources: str):
    corpus = {
        "app/src/main/java/def%d.kt" % i: src
        for i, src in enumerate(sources, start=1)
    }
    builder = CallGraphBuilder(_production_contract(), corpus)
    graph = builder.build()
    return builder, graph


def _prove(builder, graph, method: str, needle: str, mode: str = "helper"):
    for key, model in builder.callables.items():
        if model.method != method:
            continue
        masked = builder.file_models[model.file].masked
        site = masked.find(needle)
        assert site >= 0, needle
        prover = MediationProver(builder, graph)
        return prover.prove(
            MutationSubject(
                mutation_key="pin:%s" % method,
                callable_key=key,
                barrier_mode=mode,
                site_start=site,
            )
        )
    raise AssertionError("callable not found: %s" % method)


class TestContractV3:
    def test_v3_pins_the_restore_scope_form(self):
        assert CANONICAL_BARRIER_CONTRACT_V3.contract_version == 3
        assert (
            CANONICAL_BARRIER_CONTRACT_V3.restore_scope_receiver_fqcn == _SCOPE
        )
        assert CANONICAL_BARRIER_CONTRACT_V3.restore_scope_methods == ("run",)
        # V2 fields are inherited unchanged.
        assert (
            CANONICAL_BARRIER_CONTRACT_V3.receiver_fqcn
            == CANONICAL_BARRIER_CONTRACT_V2.receiver_fqcn
        )
        assert (
            CANONICAL_BARRIER_CONTRACT_V3.direct_check_methods
            == CANONICAL_BARRIER_CONTRACT_V2.direct_check_methods
        )
        assert (
            CANONICAL_BARRIER_CONTRACT_V3.guarded_scope_methods
            == CANONICAL_BARRIER_CONTRACT_V2.guarded_scope_methods
        )

    def test_v1_and_v2_are_untouched(self):
        assert CANONICAL_BARRIER_CONTRACT_V2.contract_version == 2
        assert not hasattr(CANONICAL_BARRIER_CONTRACT_V2, "__dict__") or (
            CANONICAL_BARRIER_CONTRACT_V2.restore_scope_methods == ()
        )
        assert CANONICAL_BARRIER_CONTRACT_V2.restore_scope_receiver_fqcn is None

    def test_production_contract_wires_v3(self):
        contract = _production_contract()
        assert contract.restore_scope_receiver_fqcn == _SCOPE
        assert contract.restore_scope_methods == ("run",)

    def test_restore_methods_may_overlap_transparent_inline(self):
        # `run` is a stdlib inline transparent method name; restore admission
        # is receiver-exact, so the overlap is intentional and must validate.
        contract = _production_contract()
        assert "run" in contract.restore_scope_methods
        assert "run" in contract.transparent_inline_methods

    def test_restore_methods_stay_disjoint_from_direct_and_worker(self):
        try:
            AnalysisContract(
                worker_guard_receiver_fqcn="w.WorkerGuard",
                worker_guard_scope_methods=("run",),
                direct_scope_receiver_fqcn="b.Barrier",
                direct_scope_methods=("run",),
                restore_scope_receiver_fqcn=_SCOPE,
                restore_scope_methods=("run",),
            )
        except ValueError:
            pass
        else:
            raise AssertionError("overlapping guard method names must be rejected")


class TestRestoreScopeCarrierAndProof:
    def test_receiver_exact_run_is_a_canonical_restore_carrier(self):
        builder, _ = _build(
            _DEFS,
            _STORE,
            "package com.example\n"
            "\n"
            "import com.yourname.expensetracker.data.backup."
            "RestoreInternalWriteScope\n"
            "\n"
            "class Writer(private val scope: "
            "com.yourname.expensetracker.data.backup."
            "RestoreInternalWriteScope) {\n"
            "    private val store = com.example.Store()\n"
            "\n"
            "    suspend fun writeRestored(value: Int) {\n"
            "        scope.run(\"writeRestored\") {\n"
            "            store.put(value)\n"
            "        }\n"
            "    }\n"
            "}\n",
        )
        regions = [
            r
            for key, rs in builder.lambda_regions_by_callable.items()
            if key.split("|")[3] == "writeRestored"
            for r in rs
        ]
        assert [r.carrier for r in regions] == ["canonical_restore"]

    def test_stdlib_run_on_other_receivers_stays_transparent(self):
        builder, _ = _build(
            _DEFS,
            _STORE,
            "package com.example\n"
            "\n"
            "class Plain {\n"
            "    private val store = com.example.Store()\n"
            "\n"
            "    fun writePlain(value: Int) {\n"
            "        store.run {\n"
            "            store.put(value)\n"
            "        }\n"
            "    }\n"
            "}\n",
        )
        regions = [
            r
            for key, rs in builder.lambda_regions_by_callable.items()
            if key.split("|")[3] == "writePlain"
            for r in rs
        ]
        assert [r.carrier for r in regions] == ["transparent"]

    def test_mutation_inside_restore_scope_proves_restore_internal(self):
        builder, graph = _build(
            _DEFS,
            _STORE,
            "package com.example\n"
            "\n"
            "class Writer(\n"
            "    private val scope: com.yourname.expensetracker.data.backup."
            "RestoreInternalWriteScope\n"
            ") {\n"
            "    private val store = com.example.Store()\n"
            "\n"
            "    suspend fun writeRestored(value: Int) {\n"
            "        scope.run(\"writeRestored\") {\n"
            "            store.put(value)\n"
            "        }\n"
            "    }\n"
            "}\n",
        )
        proof = _prove(builder, graph, "writeRestored", "store.put(")
        assert proof.proof_state is ProofState.PROVEN_RESTORE_INTERNAL, (
            proof.proof_state,
            proof.reason_code,
        )

    def test_scope_context_propagates_to_the_callee(self):
        # The callee mutation is reached ONLY through an exact member edge
        # out of the restore scope: the entry context must prove it too.
        # NB the callee method is `write`, not `put`: a method named `put`
        # holding `backing.put(...)` name-matches ITSELF through the
        # unresolved stdlib receiver, which makes the subject's own mutation
        # site tier-5 uncertain before context can be considered.
        builder2, graph2 = _build(
            _DEFS,
            "package com.example\n"
            "\n"
            "class Sink {\n"
            "    private val backing = mutableMapOf<Int, Int>()\n"
            "\n"
            "    fun write(value: Int) {\n"
            "        backing.put(value, value)\n"
            "    }\n"
            "}\n"
            "\n"
            "class Writer(\n"
            "    private val scope: com.yourname.expensetracker.data.backup."
            "RestoreInternalWriteScope,\n"
            "    private val sink: com.example.Sink,\n"
            ") {\n"
            "    suspend fun writeRestored(value: Int) {\n"
            "        scope.run(\"writeRestored\") {\n"
            "            sink.write(value)\n"
            "        }\n"
            "    }\n"
            "}\n",
        )
        proof = _prove(builder2, graph2, "write", "backing.put(")
        assert proof.proof_state is ProofState.PROVEN_RESTORE_INTERNAL, (
            proof.proof_state,
            proof.reason_code,
        )

    def test_unguarded_mutation_without_the_scope_stays_unproven_or_worse(self):
        # Soundness: the restore scope must not fabricate proof anywhere
        # else — a mutation with no scope and no guard never proves.
        builder, graph = _build(
            _DEFS,
            _STORE,
            "package com.example\n"
            "\n"
            "class Rogue {\n"
            "    private val store = com.example.Store()\n"
            "\n"
            "    fun writeRogue(value: Int) {\n"
            "        store.put(value)\n"
            "    }\n"
            "}\n",
        )
        proof = _prove(builder, graph, "writeRogue", "store.put(")
        assert proof.proof_state not in (
            ProofState.PROVEN_HELPER,
            ProofState.PROVEN_WORKER_MEDIATED,
            ProofState.PROVEN_RESTORE_INTERNAL,
        )
