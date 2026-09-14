"""GR-14u23: anonymous `object : Iface { }` implementors must be counted.

The owner table holds ZERO anonymous entries (probe_anon.py over the
production corpus: 0 owners that look anonymous), so implementor enumeration
over named owners alone under-counts wherever an anonymous object implements
a corpus interface.  Measured instances exist: 12 `object : PrivacyGate`
sites and one `object : WorkerLeaseRegistry` (RestoreMaintenanceMode.kt:36),
which makes WorkerLeaseRegistry's named-only count of 1 a FALSE UNIQUE.

Any future "exactly one corpus implementor => exact dispatch" rule (the
§4.D4 step c) that ignores anonymous sites would prove a dispatch that has
two runtime targets — the fail-OPEN direction.  This batch adds the counting
capability only; nothing consumes it yet (latent by design, 0 board rows
expected), so the exactness rule can be built on a complete enumeration.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder

_PATH = "app/src/main/java/com/example/Anon.kt"

_IFACE = "com.example.Iface"
_OTHER = "com.example.Other"


def _build(*sources: str) -> CallGraphBuilder:
    corpus = {
        _PATH.replace("Anon.kt", "Anon%d.kt" % i): src
        for i, src in enumerate(sources, start=1)
    }
    builder = CallGraphBuilder(_production_contract(), corpus)
    builder.build()
    return builder


_DEPS = (
    "package com.example\n"
    "\n"
    "interface Iface {\n"
    "    fun go()\n"
    "}\n"
    "\n"
    "interface Other {\n"
    "    fun hop()\n"
    "}\n"
    "\n"
    "class Dep(val value: Int)\n"
)


class TestAnonymousImplementorCounting:
    def test_anonymous_object_in_property_initializer_is_counted(self):
        builder = _build(
            _DEPS,
            "package com.example\n"
            "\n"
            "class Holder {\n"
            "    private val gate = object : Iface {\n"
            "        override fun go() {}\n"
            "    }\n"
            "}\n",
        )
        assert builder.anonymous_implementor_counts.get(_IFACE) == 1

    def test_named_object_declaration_is_not_counted_as_anonymous(self):
        builder = _build(
            _DEPS,
            "package com.example\n"
            "\n"
            "object NamedImpl : Iface {\n"
            "    override fun go() {}\n"
            "}\n",
        )
        assert builder.anonymous_implementor_counts.get(_IFACE, 0) == 0

    def test_multi_supertype_site_counts_each_corpus_supertype(self):
        builder = _build(
            _DEPS,
            "package com.example\n"
            "\n"
            "class Holder {\n"
            "    private val both = object : Iface, Other {\n"
            "        override fun go() {}\n"
            "        override fun hop() {}\n"
            "    }\n"
            "}\n",
        )
        assert builder.anonymous_implementor_counts.get(_IFACE) == 1
        assert builder.anonymous_implementor_counts.get(_OTHER) == 1

    def test_constructor_arguments_do_not_break_the_count(self):
        builder = _build(
            _DEPS,
            "package com.example\n"
            "\n"
            "class Holder(dep: Dep) {\n"
            "    private val gate = object : Iface(dep.value) {\n"
            "        override fun go() {}\n"
            "    }\n"
            "}\n",
        )
        assert builder.anonymous_implementor_counts.get(_IFACE) == 1

    def test_external_supertype_is_not_counted(self):
        builder = _build(
            _DEPS,
            "package com.example\n"
            "\n"
            "class Holder {\n"
            "    private val r = object : Runnable {\n"
            "        override fun run() {}\n"
            "    }\n"
            "}\n",
        )
        assert builder.anonymous_implementor_counts == {}

    def test_two_sites_count_two_implementors(self):
        builder = _build(
            _DEPS,
            "package com.example\n"
            "\n"
            "class Holder {\n"
            "    private val a = object : Iface {\n"
            "        override fun go() {}\n"
            "    }\n"
            "    private val b = object : Iface {\n"
            "        override fun go() {}\n"
            "    }\n"
            "}\n",
        )
        assert builder.anonymous_implementor_counts.get(_IFACE) == 2

    def test_interface_extension_is_not_an_implementor(self):
        # IfaceB extends Iface: an INTERFACE extending the target interface is
        # not an implementor of it — only classes/objects (named or anonymous)
        # can be dispatched to.
        builder = _build(
            _DEPS,
            "package com.example\n"
            "\n"
            "interface IfaceB : Iface {\n"
            "    fun extra()\n"
            "}\n",
        )
        assert builder.anonymous_implementor_counts == {}
