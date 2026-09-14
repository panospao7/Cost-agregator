"""GR-14u57b: the GR-14f/GR-14j resolution-preservation rule must not count
canonical scopes as engine-carrier chains.

The V3 restore-internal scope (`RestoreInternalWriteScope.run`) shares its
method name `run` with the stdlib inline transparent method in
PRODUCTION_TRANSPARENT_INLINE_METHODS.  Before u57b, the preservation rule
counted a canonical_restore region as an admitted engine carrier, so a call
inside the restore scope whose exact resolution could not bind corpus
targets was converted to a conservative async_dispatch name-match fan-out
(the production restoreReceiptAssets L1253 34-target fiction).

The fix requires `region.carrier == "transparent"` for the inline-table
branch: canonical scopes (worker guard / direct barrier / restore-internal)
are guard SOURCES, not engine-carrier chains — a site inside one has no
carrier-escape concern and the preservation rule has nothing to preserve.
Nested ADMITTED transparent carriers inside a canonical scope still qualify
through their own carrier (the runGuarded+forEach shape is unchanged).

These pins drive the REAL production contract via CallGraphBuilder, like
test_gr14f_inline_carriers.py and test_gr14u25_restore_scope.py.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import (
    ResolutionState,
    CallGraphBuilder,
)

_RESTORE_SCOPE_DEFS = (
    "package com.yourname.expensetracker.data.backup\n"
    "\n"
    "class RestoreInternalWriteScope {\n"
    "    suspend fun <T> run(operation: String, block: suspend () -> T): T {\n"
    "        return block()\n"
    "    }\n"
    "}\n"
)


def _build(*sources: str) -> CallGraphBuilder:
    corpus = {
        "app/src/main/java/def%d.kt" % i: src
        for i, src in enumerate(sources, start=1)
    }
    builder = CallGraphBuilder(_production_contract(), corpus)
    builder.build()
    return builder


def _edge_for(builder: CallGraphBuilder, owner: str, method: str, callee: str):
    """The single edge from (owner, method) whose call NAME is `callee`."""
    edges = []
    for edge in builder.edges:
        model = builder.callables.get(edge.caller_key)
        if model is None or model.owner_fqcn != owner or model.method != method:
            continue
        call = None
        for record in builder.calls_by_callable.get(edge.caller_key, ()):
            if record.name_start == edge.name_start:
                call = record
                break
        if call is not None and call.name == callee:
            edges.append(edge)
    assert len(edges) == 1, (owner, method, callee, len(edges))
    return edges[0]


class TestPreservationRuleExcludesCanonicalScopes:
    def test_unbindable_call_inside_restore_scope_stays_off_carrier_path(self):
        # GR-14u57b Test A: a corpus interface with ZERO implementors inside
        # a canonical_restore scope.  The interface callee resolves
        # interface_dispatch (0 targets — no implementor enumeration), NOT
        # the async_dispatch name-match fiction the pre-u57b rule produced.
        builder = _build(
            _RESTORE_SCOPE_DEFS,
            "package com.example\n"
            "\n"
            "interface ReceiptDao {\n"
            "    fun update(id: Int)\n"
            "}\n",
            "package com.example\n"
            "\n"
            "import com.yourname.expensetracker.data.backup."
            "RestoreInternalWriteScope\n"
            "\n"
            "class Writer(\n"
            "    private val scope: com.yourname.expensetracker.data.backup."
            "RestoreInternalWriteScope,\n"
            "    private val dao: ReceiptDao,\n"
            ") {\n"
            "    suspend fun updateImagePath(id: Int) {\n"
            "        scope.run(\"updateImagePath\") {\n"
            "            dao.update(id)\n"
            "        }\n"
            "    }\n"
            "}\n",
        )
        edge = _edge_for(
            builder, "com.example.Writer", "updateImagePath", "update"
        )
        assert edge.state is ResolutionState.INTERFACE_DISPATCH, (
            edge.state.value,
            edge.targets,
        )
        assert edge.targets == ()
        assert edge.uncertain

    def test_stdlib_run_name_collision_does_not_create_async_fanout(self):
        # The corpus-shaped variant of the defect: the unbindable callee
        # shares its name with another corpus callable, so the pre-u57b
        # async_dispatch name-match fan-out would have manufactured a false
        # dispatch edge.  The canonical_restore region must stay OFF the
        # preservation-rule path: the edge keeps its honest uncertain
        # member-miss resolution.
        builder = _build(
            _RESTORE_SCOPE_DEFS,
            "package com.example\n"
            "\n"
            "import com.yourname.expensetracker.data.backup."
            "RestoreInternalWriteScope\n"
            "\n"
            "class Writer(\n"
            "    private val scope: com.yourname.expensetracker.data.backup."
            "RestoreInternalWriteScope\n"
            ") {\n"
            "    private val receipts = mutableMapOf<Int, Int>()\n"
            "\n"
            "    suspend fun updateImagePath(id: Int) {\n"
            "        scope.run(\"updateImagePath\") {\n"
            "            receipts.put(id, id)\n"
            "        }\n"
            "    }\n"
            "}\n"
            "\n"
            "class UnrelatedCache {\n"
            "    private val backing = mutableMapOf<Int, Int>()\n"
            "\n"
            "    fun put(key: Int) {\n"
            "        backing.put(key, key)\n"
            "    }\n"
            "}\n",
        )
        edge = _edge_for(
            builder, "com.example.Writer", "updateImagePath", "put"
        )
        assert edge.state is ResolutionState.UNRESOLVED_TARGET, (
            edge.state.value,
            edge.targets,
        )
        assert edge.state is not ResolutionState.ASYNC_DISPATCH
        # The ASYNC fiction must NOT be manufactured: the edge keeps the
        # honest uncertain member-miss resolution (its target set is the
        # UnrelatedCache.put name-match — the same evidence the historical
        # uncertain path produced), never an async_dispatch fan-out.
        assert all("UnrelatedCache" in t for t in edge.targets), edge.targets


class TestPreservationRuleKeepsNestedAdmittedCarriers:
    def test_transparent_carrier_inside_canonical_scope_still_preserves(self):
        # GR-14u57b Test B: a genuinely-admitted transparent carrier
        # (`forEach`, the runGuarded+forEach shape) nested INSIDE a
        # canonical_restore scope still qualifies through its OWN carrier —
        # the preservation rule still fires and the unbindable callee keeps
        # the conservative async_dispatch name-match edge (behavior
        # unchanged).
        builder = _build(
            _RESTORE_SCOPE_DEFS,
            "package com.example\n"
            "\n"
            "interface ReceiptDao {\n"
            "    fun update(id: Int)\n"
            "}\n",
            "package com.example\n"
            "\n"
            "import com.yourname.expensetracker.data.backup."
            "RestoreInternalWriteScope\n"
            "\n"
            "class BatchWriter(\n"
            "    private val scope: com.yourname.expensetracker.data.backup."
            "RestoreInternalWriteScope,\n"
            "    private val dao: ReceiptDao,\n"
            ") {\n"
            "    suspend fun importBatch(ids: List<Int>) {\n"
            "        scope.run(\"importBatch\") {\n"
            "            ids.forEach { id ->\n"
            "                dao.update(id)\n"
            "            }\n"
            "        }\n"
            "    }\n"
            "}\n",
        )
        edge = _edge_for(
            builder, "com.example.BatchWriter", "importBatch", "update"
        )
        assert edge.state is ResolutionState.ASYNC_DISPATCH, (
            edge.state.value,
            edge.targets,
        )
        # The name-match fan-out is present: the preservation rule fired.
        assert any(
            "ReceiptDao" in target for target in edge.targets
        ), edge.targets
