"""GR-14u12 (§D7 Bug 1): D4 observation key vs graph callable key.

The D4 scanner records parameter types FULLY QUALIFIED (`android.net.Uri`), while
the graph parser records simple/normalized ones (`Uri`).  `_DirectSiteProver` is
keyed by the graph callable key, so a naive `observation.callable_key` lookup
missed 157/252 callables and silently disabled the direct proof (guarded writers
read `local=none`).

Pins the span-exact correlation used to re-key observations onto the graph
callable key: it must land on the graph key even though the spellings differ,
and must stay fail-closed (None) when the offset is in no declaration.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import (
    _correlate_subject_callable,
    _observations_by_graph_callable,
    _production_contract,
)
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder
from scripts.db_guard.mutation_observation import MutationObservation

_PATH = "app/src/main/java/com/example/Repo.kt"
_SOURCE = (
    "package com.example\n"
    "\n"
    "import android.net.Uri\n"
    "\n"
    "class Repo {\n"
    "    suspend fun purge(uri: Uri) {\n"
    "        dao.delete(uri)\n"
    "    }\n"
    "}\n"
)


class _Declaration:
    """Minimal stand-in for the scanner's declaration record."""

    def __init__(self, source_start, source_end, body_start, body_end):
        self.source_start = source_start
        self.source_end = source_end
        self.body_start = body_start
        self.body_end = body_end
        self.kind = "function"


def _setup():
    builder = CallGraphBuilder(_production_contract(), {_PATH: _SOURCE})
    builder.build()
    graph_key = next(
        k for k, m in builder.callables.items() if m.method == "purge"
    )
    return builder, graph_key


def _observation(source_start: int, param_types: str) -> MutationObservation:
    return MutationObservation(
        path=_PATH,
        # D4-style spelling: fully-qualified parameter types.
        callable_key="%s|com.example.Repo|function|purge|null|%s" % (_PATH, param_types),
        source_start=source_start,
        source_end=source_start + len("dao.delete(uri)"),
        line=1,
        column=1,
        dao_accessor="dao",
        dao_fqcn="com.example.Dao",
        operation="delete",
        mutation_kind="dao_call",
        source_identity="Dao::com.example.Dao#delete(android.net.Uri)",
    )


class TestObservationToGraphCallableCorrelation:
    def test_rekeying_uses_the_graph_key_not_the_d4_key(self):
        """The prover must receive observations keyed by the GRAPH key.

        This is the wiring pin: keying by ``observation.callable_key`` (the D4
        spelling) is exactly the Bug 1 defect, so the mapping key must equal the
        graph callable key and must NOT equal the D4 key.
        """
        builder, graph_key = _setup()
        masked = builder.file_models[_PATH].masked
        call_start = masked.index("dao.delete(uri)")
        declaration = _Declaration(
            source_start=masked.index("suspend fun purge"),
            source_end=len(_SOURCE),
            body_start=call_start - 20,
            body_end=len(_SOURCE),
        )
        index = {(_PATH, "com.example.Repo", "purge"): [declaration]}
        observation = _observation(call_start, "android.net.Uri")

        by_graph_key = _observations_by_graph_callable(builder, index, [observation])
        assert list(by_graph_key) == [graph_key]
        assert observation.callable_key not in by_graph_key
        assert by_graph_key[graph_key] == [observation]

    def test_uncorrelated_observation_keeps_its_d4_key(self):
        """Fail closed: an uncorrelated observation is never silently dropped."""
        builder, _graph_key = _setup()
        observation = _observation(999, "android.net.Uri")
        index = {(_PATH, "com.example.Repo", "purge"): [_Declaration(0, 10, None, None)]}
        by_graph_key = _observations_by_graph_callable(builder, index, [observation])
        assert list(by_graph_key) == [observation.callable_key]

    def test_fully_qualified_param_key_correlates_to_graph_key(self):
        builder, graph_key = _setup()
        # Sanity: the two key spellings really do differ.
        assert graph_key.endswith("|Uri")
        call_start = builder.file_models[_PATH].masked.index("dao.delete(uri)")
        declaration = _Declaration(
            source_start=builder.file_models[_PATH].masked.index("suspend fun purge"),
            source_end=len(_SOURCE),
            body_start=builder.file_models[_PATH].masked.index("dao.delete(uri)") - 20,
            body_end=len(_SOURCE),
        )
        index = {(_PATH, "com.example.Repo", "purge"): [declaration]}
        observation = _observation(call_start, "android.net.Uri")
        assert observation.callable_key.endswith("|android.net.Uri")

        assert _correlate_subject_callable(builder, index, observation) == graph_key

    def test_offset_outside_any_declaration_is_fail_closed(self):
        builder, _graph_key = _setup()
        declaration = _Declaration(0, 10, None, None)
        index = {(_PATH, "com.example.Repo", "purge"): [declaration]}
        observation = _observation(999, "android.net.Uri")
        assert _correlate_subject_callable(builder, index, observation) is None

    def test_unknown_owner_is_fail_closed(self):
        builder, _graph_key = _setup()
        call_start = builder.file_models[_PATH].masked.index("dao.delete(uri)")
        index = {(_PATH, "com.example.Repo", "purge"): []}
        observation = MutationObservation(
            path=_PATH,
            callable_key="%s|com.example.Other|function|purge|null|android.net.Uri" % _PATH,
            source_start=call_start,
            source_end=call_start + 16,
            line=1,
            column=1,
            dao_accessor="dao",
            dao_fqcn="com.example.Dao",
            operation="delete",
            mutation_kind="dao_call",
            source_identity="Dao::com.example.Dao#delete(android.net.Uri)",
        )
        assert _correlate_subject_callable(builder, index, observation) is None
