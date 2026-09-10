"""GR-14u7 (§D5): `_DirectSiteProver` must expose REAL mutation-site offsets.

Pins the §D5 fix.  The GR-12 direct-barrier proof is already *computed* over the
callable's real mutation sites, but `_DirectSiteProver._compute` previously
recorded results ONLY for offsets explicitly `request()`ed (edge name offsets).
Mediation subjects ask by `observation.source_start` (the mutation site), so a
writer whose only guard is a dominating bare `checkWritesAllowed` was reported
as `local == "none"` — fail-closed noise instead of `direct`.

These pins assert by the D4 mutation offset, and separately assert the
underlying GR-12 proof really does mark that site PROVEN (so a pass cannot come
from a mis-resolved barrier).
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import (
    _DirectSiteProver,
    _production_contract,
)
from scripts.db_guard.direct_barrier_bridge import (
    callable_body_span,
    mutation_sites_from_observations,
    prove_callable_direct_barriers,
)
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder
from scripts.db_guard.mutation_observation import MutationObservation
from scripts.db_guard.structural_analysis.barrier_proof import ProofStatus

_PATH = "app/src/main/java/com/example/Repo.kt"
_MUTATION = "dao.deleteAll()"


def _build(method_source: str):
    source = (
        "package com.example\n"
        "import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier\n"
        "class Repo(\n"
        "    private val dao: ThingDao,\n"
        "    private val writeBarrier: DatabaseWriteBarrier\n"
        ") {\n"
        + method_source
        + "}\n"
    )
    builder = CallGraphBuilder(_production_contract(), {_PATH: source})
    builder.build()
    masked = builder.file_models[_PATH].masked
    key = next(k for k, m in builder.callables.items() if m.method == "purge")
    offset = masked.index(_MUTATION)
    observation = MutationObservation(
        path=_PATH,
        callable_key=key,
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
    return builder, masked, key, offset, observation


def _raw_proof(builder, masked, key, observation) -> bool:
    """The underlying GR-12 proof for the callable's real mutation site."""
    model = builder.callables[key]
    body_span = callable_body_span(masked, model)
    outcome = prove_callable_direct_barriers(
        masked,
        body_span,
        mutation_sites_from_observations((observation,)),
        path=_PATH,
        callable_key=key,
    )
    result = outcome.result_for_site_start(observation.source_start)
    return result.status == ProofStatus.PROVEN


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


class TestDirectSiteProverExposesMutationOffsets:
    def test_dominating_bare_check_is_proven_at_the_mutation_offset(self):
        """The positive case: guard first ⇒ the real mutation offset is proven."""
        builder, masked, key, offset, observation = _build(_GUARD_FIRST)
        assert _raw_proof(builder, masked, key, observation), (
            "fixture precondition: GR-12 must prove the dominated site"
        )
        prover = _DirectSiteProver(builder, {key: [observation]})
        assert prover.proven_sites(key).get(offset) is True
        assert prover.as_callback()(key, offset) is True

    def test_guard_after_the_mutation_is_not_proven(self):
        """Non-dominating guard stays fail-closed (no over-proving)."""
        builder, masked, key, offset, observation = _build(_GUARD_AFTER)
        assert _raw_proof(builder, masked, key, observation) is False
        prover = _DirectSiteProver(builder, {key: [observation]})
        assert prover.proven_sites(key).get(offset, False) is False

    def test_unguarded_writer_is_not_proven(self):
        """No barrier at all: the offset is simply absent / False."""
        builder, masked, key, offset, observation = _build(_NO_GUARD)
        assert _raw_proof(builder, masked, key, observation) is False
        prover = _DirectSiteProver(builder, {key: [observation]})
        assert prover.proven_sites(key).get(offset, False) is False

    def test_observation_under_a_different_key_is_fail_closed(self):
        """A key mismatch must not prove (observations are exact-keyed)."""
        builder, masked, key, offset, observation = _build(_GUARD_FIRST)
        prover = _DirectSiteProver(builder, {"some.other.Callable#purge": [observation]})
        assert prover.proven_sites(key).get(offset, False) is False
