"""GR-14u24: interface dispatch with EXACTLY ONE corpus implementor and ZERO
anonymous implementor sites dispatches exact.

Pre-GR-14u24 every interface-typed member call stayed INTERFACE_DISPATCH
(uncertain) unconditionally.  The §4.D4 chain: with the complete named
implementor walk (GR-14u21) and anonymous implementor site counting
(GR-14u23), a single-implementor interface has exactly one runtime target in
the corpus, so the dispatch is decidable.

Soundness pins (must stay UNCERTAIN — these are the fail-OPEN traps):
  * a second anonymous `object : Iface` site blocks exactness even when the
    named enumeration finds one implementor (the WorkerLeaseRegistry shape);
  * a SECOND NAMED implementor blocks exactness;
  * overloads on the single implementor block exactness (static overload
    selection is unknown);
  * an anonymous implementor of a SUB-interface blocks exactness for the
    super-interface (transitive application of the GR-14u23 counts).

Proving pins (RED pre-fix):
  * single named implementor, zero anonymous sites -> EXACT edge;
  * single implementor that does not override (inherits the member) -> EXACT
    edge to the inherited member.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder

_PATH = "app/src/main/java/com/example/Disp.kt"

_IFACE = "com.example.Iface"


def _edges(builder: CallGraphBuilder, caller_method: str, callee: str):
    out = []
    for key, model in builder.callables.items():
        if model.method != caller_method:
            continue
        for edge in builder.edges:
            if edge.caller_key == key and edge.targets:
                for t in edge.targets:
                    if t.split("|")[-4] == callee or t.split("|")[3] == callee:
                        out.append(edge)
    return out


def _dispatch_state(builder: CallGraphBuilder, caller_method: str, callee: str) -> str:
    found = _edges(builder, caller_method, callee)
    assert found, "no dispatch edge found for %s -> %s" % (caller_method, callee)
    return found[0].state.value


def _build(*sources: str) -> CallGraphBuilder:
    corpus = {
        _PATH.replace("Disp.kt", "Disp%d.kt" % i): src
        for i, src in enumerate(sources, start=1)
    }
    builder = CallGraphBuilder(_production_contract(), corpus)
    builder.build()
    return builder


_DEFS = (
    "package com.example\n"
    "\n"
    "interface Iface {\n"
    "    fun go()\n"
    "}\n"
    "\n"
    "open class Base {\n"
    "    open fun go() {}\n"
    "}\n"
    "\n"
    "class OnlyImpl : Iface {\n"
    "    override fun go() {}\n"
    "}\n"
    "\n"
    "class SecondImpl : Iface {\n"
    "    override fun go() {}\n"
    "}\n"
    "\n"
    "class InheritingImpl : Base(), Iface\n"
)


class TestSingleImplementorExactness:
    def test_single_named_implementor_dispatches_exact(self):
        # NOTE: dedicated defs WITHOUT SecondImpl (which _DEFS carries for the
        # two-implementor pin below).
        builder = _build(
            "package com.example\n"
            "\n"
            "interface Iface {\n"
            "    fun go()\n"
            "}\n"
            "\n"
            "class OnlyImpl : Iface {\n"
            "    override fun go() {}\n"
            "}\n"
            "\n"
            "class Caller {\n"
            "    fun exercise(c: Iface) {\n"
            "        c.go()\n"
            "    }\n"
            "}\n",
        )
        assert _dispatch_state(builder, "exercise", "go") == "exact_synchronous"

    def test_second_anonymous_site_keeps_dispatch_uncertain(self):
        builder = _build(
            _DEFS,
            "package com.example\n"
            "\n"
            "class Caller {\n"
            "    private val extra = object : Iface {\n"
            "        override fun go() {}\n"
            "    }\n"
            "\n"
            "    fun exercise(c: Iface) {\n"
            "        c.go()\n"
            "    }\n"
            "}\n",
        )
        assert _dispatch_state(builder, "exercise", "go") == "interface_dispatch"

    def test_second_named_implementor_keeps_dispatch_uncertain(self):
        builder = _build(
            _DEFS,
            "package com.example\n"
            "\n"
            "class Caller {\n"
            "    fun exercise(c: Iface) {\n"
            "        c.go()\n"
            "    }\n"
            "}\n",
        )
        # OnlyImpl AND SecondImpl exist in _DEFS: two named implementors.
        assert _dispatch_state(builder, "exercise", "go") == "interface_dispatch"

    def test_single_inheriting_implementor_is_exact_to_the_inherited_member(self):
        builder = _build(
            "package com.example\n"
            "\n"
            "interface Iface {\n"
            "    fun go()\n"
            "}\n"
            "\n"
            "open class Base {\n"
            "    open fun go() {}\n"
            "}\n"
            "\n"
            "class InheritingImpl : Base(), Iface\n"
            "\n"
            "class Caller {\n"
            "    fun exercise(c: Iface) {\n"
            "        c.go()\n"
            "    }\n"
            "}\n",
        )
        assert _dispatch_state(builder, "exercise", "go") == "exact_synchronous"

    def test_overloads_on_the_implementor_stay_uncertain(self):
        builder = _build(
            "package com.example\n"
            "\n"
            "interface Iface {\n"
            "    fun send(value: Int)\n"
            "}\n"
            "\n"
            "class OnlyImpl : Iface {\n"
            "    override fun send(value: Int) {}\n"
            "    fun send(label: String) {}\n"
            "}\n"
            "\n"
            "class Caller {\n"
            "    fun exercise(c: Iface) {\n"
            "        c.send(7)\n"
            "    }\n"
            "}\n",
        )
        assert _dispatch_state(builder, "exercise", "send") == "interface_dispatch"

    def test_anonymous_subinterface_implementor_keeps_super_interface_uncertain(self):
        builder = _build(
            "package com.example\n"
            "\n"
            "interface Iface {\n"
            "    fun go()\n"
            "}\n"
            "\n"
            "interface SubIface : Iface\n"
            "\n"
            "class OnlyImpl : Iface {\n"
            "    override fun go() {}\n"
            "}\n"
            "\n"
            "class Caller {\n"
            "    private val extra = object : SubIface {\n"
            "        override fun go() {}\n"
            "    }\n"
            "\n"
            "    fun exercise(c: Iface) {\n"
            "        c.go()\n"
            "    }\n"
            "}\n",
        )
        assert _dispatch_state(builder, "exercise", "go") == "interface_dispatch"
