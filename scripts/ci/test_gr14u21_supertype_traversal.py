"""GR-14u21: inherit/override enumeration must traverse ALL supertypes.

`_inherits_from` walked only the FIRST resolvable corpus supertype at each hop,
so it returned False whenever the base was listed as a non-first supertype:

    class Impl : Other, Iface { override fun go() {} }

Two measured consequences on the production corpus:

  * `_override_targets` under-counts implementors (11 (interface, method) pairs).
    One of them is a FALSE UNIQUE that would make an "exactly one implementor =>
    exact dispatch" rule unsound: `WorkerDrainController.requestStopAndAwaitDrain`
    -> engine reports 1 target, the complete walk finds 2
    (NoOpWorkerDrainController AND WorkerLeaseRegistryImpl).

  * `_has_override_named` can return False although an override exists, which lets
    _resolve_invocation fall through to an EXACT edge on an overridden member —
    an under-approximation, i.e. a fail-OPEN direction error.

Both are the same root cause and both call sites are override enumeration only.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder

_PATH = "app/src/main/java/com/example/Impl.kt"


def _build(source: str) -> CallGraphBuilder:
    builder = CallGraphBuilder(_production_contract(), {_PATH: source})
    builder.build()
    return builder


_SOURCE = (
    "package com.example\n"
    "\n"
    "interface Iface {\n"
    "    fun go()\n"
    "}\n"
    "\n"
    "open class Other {\n"
    "    open fun go() {}\n"
    "}\n"
    "\n"
    "// Iface is the SECOND supertype: the first-hop walk never reaches it.\n"
    "class Impl : Other(), Iface {\n"
    "    override fun go() {}\n"
    "}\n"
)

_IFACE = "com.example.Iface"
_IMPL = "com.example.Impl"


class TestCompleteSupertypeTraversal:
    def test_inherits_from_finds_a_non_first_supertype(self):
        builder = _build(_SOURCE)
        assert builder._inherits_from(_IMPL, _IFACE) is True

    def test_override_targets_finds_the_non_first_supertype_impl(self):
        builder = _build(_SOURCE)
        targets = builder._override_targets(_IFACE, "go")
        owners = {k.split("|")[1] for k in targets}
        assert owners == {_IMPL}, targets

    def test_has_override_named_finds_a_non_first_supertype_override(self):
        builder = _build(_SOURCE)
        assert builder._has_override_named("com.example.Other", "go") is True

    def test_unrelated_owner_is_not_an_implementor(self):
        builder = _build(
            "package com.example\n"
            "\n"
            "interface Iface {\n"
            "    fun go()\n"
            "}\n"
            "\n"
            "class Unrelated\n"
        )
        assert builder._inherits_from("com.example.Unrelated", _IFACE) is False

    def test_diamond_reachability_terminates(self):
        """A diamond reaches the base by two paths; the walk must not loop."""
        builder = _build(
            "package com.example\n"
            "\n"
            "interface A {\n"
            "    fun go()\n"
            "}\n"
            "\n"
            "interface B : A\n"
            "\n"
            "class X : B, A {\n"
            "    override fun go() {}\n"
            "}\n"
        )
        assert builder._inherits_from("com.example.X", "com.example.A") is True
        assert builder._inherits_from("com.example.B", "com.example.A") is True
        assert builder._inherits_from("com.example.A", "com.example.X") is False
