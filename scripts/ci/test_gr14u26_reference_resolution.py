"""GR-14u26: receiver-aware `::` function-reference resolution.

A bound member reference (`viewModel::confirmQuickApprove`) is currently emitted
as ONE uncertain FUNCTION_REFERENCE edge carrying name-matched targets, whatever
its receiver.  With the receiver typed (`viewModel: ReviewViewModel`) and the
member unique in that type, the reference binds to exactly one corpus callable,
so the edge can be exact.

This is why the reference records carried no receiver at all before: the
separate `::` scan (`_REFERENCE_RE`) captured only the name.  The fix captures
the receiver expression too, and resolves it through the same receiver-typing
machinery the invocation path already uses (GR-14u20/u22).

The rule must stay FAIL-CLOSED: no receiver, an unresolved receiver, a member
that is absent, generic, or overloaded in its owner, or a receiver that is not a
corpus type all keep today's uncertain name-matched edge.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder

_PATH = "app/src/main/java/com/example/Screen.kt"


def _build(source: str) -> CallGraphBuilder:
    builder = CallGraphBuilder(_production_contract(), {_PATH: source})
    builder.build()
    return builder


def _ref_edge(builder: CallGraphBuilder, method: str, ref_name: str):
    """The edge produced for one `::` reference site in `method`."""
    for key, model in builder.callables.items():
        if model.method != method:
            continue
        for edge in builder.edges:
            if edge.caller_key != key:
                continue
            if edge.name_start is None:
                continue
            # match by the recorded call record's name
            for call in builder.calls_by_callable.get(key, ()):
                if call.name == ref_name and call.name_start == edge.name_start:
                    return edge
    return None


_VM = "package com.example\n\nclass Vm {\n    fun save() {}\n}\n"

_SCREEN = (
    "package com.example\n"
    "\n"
    "class Screen {\n"
    "    fun render(vm: Vm) {\n"
    "        val act = vm::save\n"
    "        act()\n"
    "    }\n"
    "}\n"
)


class TestBoundReferenceResolution:
    def test_typed_receiver_with_unique_member_resolves_exactly(self):
        builder = _build(_VM + "\n" + _SCREEN)
        edge = _ref_edge(builder, "render", "save")
        assert edge is not None, "no reference edge recorded"
        assert edge.state.value == "exact_synchronous", edge.state.value
        assert len(edge.targets) == 1
        assert edge.targets[0].split("|")[1] == "com.example.Vm"

    def test_it_is_exact_and_not_uncertain(self):
        builder = _build(_VM + "\n" + _SCREEN)
        edge = _ref_edge(builder, "render", "save")
        assert edge.uncertain is False

    def test_bare_reference_stays_uncertain(self):
        """`::save` with no receiver could be a top-level function: fail closed."""
        builder = _build(
            "package com.example\n"
            "\n"
            "fun save() {}\n"
            "\n"
            "class Screen {\n"
            "    fun render() {\n"
            "        val act = ::save\n"
            "        act()\n"
            "    }\n"
            "}\n"
        )
        edge = _ref_edge(builder, "render", "save")
        assert edge is not None
        assert edge.state.value == "function_reference", edge.state.value
        assert edge.uncertain is True

    def test_overloaded_member_stays_uncertain(self):
        """Two members named the same in the receiver type: not decidable here."""
        builder = _build(
            "package com.example\n"
            "\n"
            "class Vm {\n"
            "    fun save() {}\n"
            "    fun save(force: Boolean) {}\n"
            "}\n"
            "\n"
            "class Screen {\n"
            "    fun render(vm: Vm) {\n"
            "        val act = vm::save\n"
            "        act()\n"
            "    }\n"
            "}\n"
        )
        edge = _ref_edge(builder, "render", "save")
        assert edge is not None
        assert edge.state.value == "function_reference", edge.state.value
        assert edge.uncertain is True

    def test_unresolved_receiver_stays_uncertain(self):
        builder = _build(
            "package com.example\n"
            "\n"
            "class Screen {\n"
            "    fun render(vm: SomethingUnknown) {\n"
            "        val act = vm::save\n"
            "        act()\n"
            "    }\n"
            "}\n"
        )
        edge = _ref_edge(builder, "render", "save")
        assert edge is not None
        assert edge.state.value == "function_reference", edge.state.value
        assert edge.uncertain is True

    def test_member_absent_from_typed_receiver_stays_uncertain(self):
        builder = _build(
            "package com.example\n"
            "\n"
            "class Vm {\n"
            "    fun other() {}\n"
            "}\n"
            "\n"
            "class Screen {\n"
            "    fun render(vm: Vm) {\n"
            "        val act = vm::save\n"
            "        act()\n"
            "    }\n"
            "}\n"
        )
        edge = _ref_edge(builder, "render", "save")
        assert edge is not None
        assert edge.state.value == "function_reference", edge.state.value
        assert edge.uncertain is True

    def test_this_reference_resolves_to_the_owner(self):
        builder = _build(
            "package com.example\n"
            "\n"
            "class Screen {\n"
            "    fun save() {}\n"
            "    fun render() {\n"
            "        val act = this::save\n"
            "        act()\n"
            "    }\n"
            "}\n"
        )
        edge = _ref_edge(builder, "render", "save")
        assert edge is not None
        assert edge.state.value == "exact_synchronous", edge.state.value
        assert edge.targets[0].split("|")[1] == "com.example.Screen"
