"""Pins for the closed inline-carrier set and the live direct-barrier bridge.

Survivor of the retired GR-14 mediation-proof tests.  It keeps only the
assertions that guard LIVE production code (``direct_barrier_bridge`` and the
``PRODUCTION_TRANSPARENT_INLINE_METHODS`` table the bridge consumes), and it
does so without ``CallGraphBuilder``, the mediation prover or any fixture
runner:

* exact pin of ``PRODUCTION_TRANSPARENT_INLINE_METHODS`` (was GR-14f
  ``test_production_set_is_pinned``);
* the set stays disjoint from the canonical barrier contract's authority
  surfaces (was GR-14f ``test_set_is_disjoint_from_authority_surfaces``,
  re-expressed against ``CANONICAL_BARRIER_CONTRACT_V2``, the contract
  ``direct_barrier_bridge`` actually reads);
* bridge proof pins (were GR-14u7 ``_raw_proof`` cases and GR-14u15 cases).

Any member change to the inline set needs a reviewed diff.  Carrier
classification is parseability only, never an authorization source.
"""
from __future__ import annotations

from types import SimpleNamespace

from scripts.db_guard.direct_barrier_bridge import (
    callable_body_span,
    mutation_sites_from_observations,
    prove_callable_direct_barriers,
)
from scripts.db_guard.mediation_analysis.callgraph import (
    PRODUCTION_TRANSPARENT_INLINE_METHODS,
)
from scripts.db_guard.mutation_observation import MutationObservation
from scripts.db_guard.structural_analysis.barrier_proof import (
    CANONICAL_BARRIER_CONTRACT_V2,
    ProofStatus,
)
from scripts.kotlin_callable_parser import mask_kotlin_source

_UNSUPPORTED = "DB_DIRECT_BARRIER_PROOF_UNSUPPORTED"


# -- (1)/(2) closed inline-carrier set ---------------------------------------


class TestInlineCarrierSet:
    def test_production_set_is_pinned(self):
        # Copied verbatim from GR-14f test_production_set_is_pinned.
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
            # GR-14u56e: NominatimGeocodingService.withRateLimit (exactly-
            # once inline suspend wrapper; block invoked exactly once
            # inside an already-admitted withLock scope).
            "withRateLimit",
            "setContent",
        )

    def test_set_is_disjoint_from_canonical_barrier_authority_surfaces(self):
        inline = set(PRODUCTION_TRANSPARENT_INLINE_METHODS)
        assert not inline & set(CANONICAL_BARRIER_CONTRACT_V2.direct_check_methods)
        assert not inline & set(CANONICAL_BARRIER_CONTRACT_V2.guarded_scope_methods)
        assert not inline & set(
            CANONICAL_BARRIER_CONTRACT_V2.transparent_scope_methods
        )

    def test_contract_v2_has_no_restore_scope(self):
        """Replaces the retired gr14u25 V2 pin: V2 (live D4 gate) has no restore scope."""
        assert CANONICAL_BARRIER_CONTRACT_V2.restore_scope_receiver_fqcn is None, (
            "V2 must not carry a restore scope receiver; restore fields would "
            "widen write authorization"
        )
        assert CANONICAL_BARRIER_CONTRACT_V2.restore_scope_methods == (), (
            "V2 must not carry restore scope methods; restore fields would "
            "widen write authorization"
        )


# -- (3)-(6) live bridge pins (no CallGraphBuilder) --------------------------

_PATH = "app/src/main/java/com/example/Repo.kt"
_CALLABLE_KEY = "com.example.Repo#purge"
_MUTATION = "dao.deleteAll()"


def _source(method_source: str) -> str:
    return (
        "package com.example\n"
        "import com.yourname.expensetracker.data.backup.DatabaseAccessOperation\n"
        "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
        "class Repo(\n"
        "    private val dao: ThingDao,\n"
        "    private val writeBarrier: DatabaseWriteBarrier\n"
        ") {\n"
        + method_source
        + "}\n"
    )


def _prove(method_source: str):
    """(outcome, mutation offset) for the ``purge`` callable of one snippet."""
    masked = mask_kotlin_source(_source(method_source))
    decl = masked.index("fun purge")
    open_brace = masked.index("{", masked.index(")", decl))
    depth = 0
    close_brace = open_brace
    for index in range(open_brace, len(masked)):
        if masked[index] == "{":
            depth += 1
        elif masked[index] == "}":
            depth -= 1
            if depth == 0:
                close_brace = index
                break
    # Declaration-scanner convention: body_start is the first character after
    # the opening brace, body_end the index of the matching closing brace.
    declaration = SimpleNamespace(body_start=open_brace + 1, body_end=close_brace)
    offset = masked.index(_MUTATION, open_brace)
    observation = MutationObservation(
        path=_PATH,
        callable_key=_CALLABLE_KEY,
        source_start=offset,
        source_end=offset + len(_MUTATION),
        line=masked.count("\n", 0, offset) + 1,
        column=1,
        dao_accessor="dao",
        dao_fqcn="com.example.ThingDao",
        operation="deleteAll",
        mutation_kind="dao_call",
        source_identity="ThingDao::com.example.ThingDao#deleteAll()",
    )
    outcome = prove_callable_direct_barriers(
        masked,
        callable_body_span(masked, declaration),
        mutation_sites_from_observations((observation,)),
        path=_PATH,
        callable_key=_CALLABLE_KEY,
    )
    return outcome, offset


_GUARD_FIRST = (
    "    suspend fun purge() {\n"
    '        writeBarrier.checkWritesAllowed("Repo.purge")\n'
    f"        {_MUTATION}\n"
    "    }\n"
)
_GUARD_AFTER = (
    "    suspend fun purge() {\n"
    f"        {_MUTATION}\n"
    '        writeBarrier.checkWritesAllowed("Repo.purge")\n'
    "    }\n"
)
_NO_GUARD = "    suspend fun purge() {\n" f"        {_MUTATION}\n" "    }\n"
# The tokenizer still refuses the parenthesised-lambda ``runWrite(op, { ... })``
# shape, so the body cannot be modeled even though a canonical barrier call is
# plainly present (GR-14u15 / GR-14u16).
_UNMODELABLE_GUARD_FIRST = (
    "    suspend fun purge() {\n"
    '        writeBarrier.runWrite(DatabaseAccessOperation("op"), {\n'
    f"            {_MUTATION}\n"
    "        })\n"
    "    }\n"
)
_MUTATION_THEN_LATE_UNMODELABLE_GUARD = (
    "    suspend fun purge() {\n"
    f"        {_MUTATION}\n"
    '        writeBarrier.runWrite(DatabaseAccessOperation("late"), {\n'
    f"            {_MUTATION}\n"
    "        })\n"
    "    }\n"
)


class TestDirectBarrierBridgeOffsets:
    def test_dominating_bare_check_is_proven_at_the_mutation_offset(self):
        # GR-14u7: guard first => the real mutation offset is PROVEN.
        outcome, offset = _prove(_GUARD_FIRST)
        result = outcome.result_for_site_start(offset)
        assert result.status == ProofStatus.PROVEN

    def test_guard_after_the_mutation_is_not_proven(self):
        # GR-14u7: a non-dominating guard stays fail-closed.
        outcome, offset = _prove(_GUARD_AFTER)
        result = outcome.result_for_site_start(offset)
        assert result.status != ProofStatus.PROVEN

    def test_unguarded_writer_is_not_proven(self):
        # GR-14u7: no barrier at all.
        outcome, offset = _prove(_NO_GUARD)
        result = outcome.result_for_site_start(offset)
        assert result.status != ProofStatus.PROVEN

    def test_unmodelable_run_write_lambda_form_is_unsupported_but_visible(self):
        # GR-14u15: the guard is present but the body cannot be modeled.
        outcome, offset = _prove(_UNMODELABLE_GUARD_FIRST)
        assert outcome.diagnostics == (_UNSUPPORTED,)
        assert outcome.has_canonical_barrier
        assert outcome.barrier_call_offsets
        # The barrier precedes the mutation (keeps the row out of the
        # counterexamples), yet the site itself is never reported PROVEN.
        assert all(barrier < offset for barrier in outcome.barrier_call_offsets)
        assert outcome.result_for_site_start(offset).status != ProofStatus.PROVEN

    def test_late_guard_does_not_excuse_an_earlier_mutation(self):
        # GR-14u15: the only barrier sits AFTER the first mutation.
        outcome, offset = _prove(_MUTATION_THEN_LATE_UNMODELABLE_GUARD)
        assert outcome.diagnostics == (_UNSUPPORTED,)
        assert outcome.barrier_call_offsets
        assert all(barrier > offset for barrier in outcome.barrier_call_offsets)
        assert outcome.result_for_site_start(offset).status != ProofStatus.PROVEN
