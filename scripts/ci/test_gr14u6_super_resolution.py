"""GR-14u6: `super.<member>()` receiver-resolution pins.

Pins the Kotlin-correct `super` dispatch added to
``CallGraphBuilder.receiver_fqcn_for_call``/``_super_receiver_fqcn``:

* an external supertype (`Application`) means the super call LEAVES the corpus
  (``EXACT_SYNCHRONOUS`` + ``external``, no corpus targets) — so
  ``MainApplication.onCreate``'s ``super.onCreate()`` stops name-matching every
  ``onCreate`` in the tree (the dominant unproven_ambiguous taint);
* a corpus supertype declaring the member resolves EXACTLY to that member;
* an unresolvable supertype keeps the fail-closed ``UNRESOLVED_TARGET``
  name-match path (unchanged).

Pinned against the REAL production contract.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder
from scripts.db_guard.mediation_analysis.models import ResolutionState

_FILE = "app/src/main/java/com/example/SuperRepo.kt"


def _edges(source: str, caller_method: str, callee_name: str):
    corpus = {_FILE: source}
    builder = CallGraphBuilder(_production_contract(), corpus)
    graph = builder.build()
    matches = []
    for edge in graph.edges:
        caller = builder.callables.get(edge.caller_key)
        if caller is None or caller.method != caller_method:
            continue
        call = next(
            (
                c
                for c in builder.calls_by_callable.get(edge.caller_key, ())
                if c.name_start == edge.name_start
            ),
            None,
        )
        if call is None or call.name != callee_name:
            continue
        matches.append(edge)
    return builder, matches


class TestSuperReceiverResolution:
    def test_external_supertype_resolves_out_of_corpus(self):
        """super.onCreate() on an external base leaves the corpus (no taint)."""
        source = (
            "package com.example\n"
            "import android.app.Application\n"
            "class App : Application() {\n"
            "    override fun onCreate() {\n"
            "        super.onCreate()\n"
            "    }\n"
            "}\n"
            "class OtherScreen {\n"
            "    fun onCreate() { }\n"
            "}\n"
        )
        builder, matches = _edges(source, "onCreate", "onCreate")
        assert len(matches) == 1
        edge = matches[0]
        assert edge.state is ResolutionState.EXACT_SYNCHRONOUS
        assert edge.external is True
        assert edge.targets == ()
        # the name-match set that used to taint the tree is non-empty ...
        assert builder._name_match_targets("onCreate")

    def test_corpus_supertype_resolves_to_super_member(self):
        """super.writeRow() resolves to the corpus supertype's member."""
        source = (
            "package com.example\n"
            "open class Base {\n"
            "    fun writeRow(x: Int) { }\n"
            "}\n"
            "class Derived : Base() {\n"
            "    fun run(x: Int) {\n"
            "        super.writeRow(x)\n"
            "    }\n"
            "}\n"
        )
        builder, matches = _edges(source, "run", "writeRow")
        assert len(matches) == 1
        edge = matches[0]
        assert edge.state is ResolutionState.EXACT_SYNCHRONOUS
        assert edge.external is False
        assert len(edge.targets) == 1
        assert builder.callables[edge.targets[0]].owner_fqcn == "com.example.Base"

    def test_multi_level_corpus_chain_resolves_to_declaring_ancestor(self):
        """super.writeRow() skips a non-declaring middle supertype."""
        source = (
            "package com.example\n"
            "open class Grand {\n"
            "    fun writeRow(x: Int) { }\n"
            "}\n"
            "open class Mid : Grand() {\n"
            "}\n"
            "class Leaf : Mid() {\n"
            "    fun run(x: Int) {\n"
            "        super.writeRow(x)\n"
            "    }\n"
            "}\n"
        )
        builder, matches = _edges(source, "run", "writeRow")
        assert len(matches) == 1
        edge = matches[0]
        assert edge.state is ResolutionState.EXACT_SYNCHRONOUS
        assert edge.external is False
        assert len(edge.targets) == 1
        assert builder.callables[edge.targets[0]].owner_fqcn == "com.example.Grand"

    def test_qualified_super_stays_fail_closed(self):
        """super<Iface>.foo() is out of scope: never an exact corpus target."""
        source = (
            "package com.example\n"
            "interface Iface { fun writeRow(x: Int) }\n"
            "class Impl : Iface {\n"
            "    override fun writeRow(x: Int) { }\n"
            "    fun run(x: Int) {\n"
            "        super<Iface>.writeRow(x)\n"
            "    }\n"
            "}\n"
        )
        builder, matches = _edges(source, "run", "writeRow")
        for edge in matches:
            assert edge.state is not ResolutionState.EXACT_SYNCHRONOUS
            assert edge.external is False
            assert edge.uncertain is True

    def test_unresolvable_supertype_stays_fail_closed(self):
        """An unresolved supertype keeps the UNRESOLVED_TARGET name-match."""
        source = (
            "package com.example\n"
            "class X : Mystery() {\n"
            "    fun run() {\n"
            "        super.whatever()\n"
            "    }\n"
            "}\n"
            "class Other {\n"
            "    fun whatever() { }\n"
            "}\n"
        )
        builder, matches = _edges(source, "run", "whatever")
        assert len(matches) == 1
        edge = matches[0]
        assert edge.state is ResolutionState.UNRESOLVED_TARGET
        assert edge.uncertain is True
        assert set(edge.targets) == set(builder._name_match_targets("whatever"))
        assert edge.targets  # the name-match evidence is preserved

    def test_plain_this_receiver_unchanged(self):
        """Sanity: a non-super receiver path is untouched by the new branch."""
        source = (
            "package com.example\n"
            "class Repo {\n"
            "    private fun writeRow(x: Int) { }\n"
            "    fun run(x: Int) {\n"
            "        this.writeRow(x)\n"
            "    }\n"
            "}\n"
        )
        builder, matches = _edges(source, "run", "writeRow")
        assert len(matches) == 1
        assert matches[0].state is ResolutionState.EXACT_SYNCHRONOUS
        assert len(matches[0].targets) == 1
