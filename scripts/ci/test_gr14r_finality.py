"""GR-14r: finality-aware dispatch pins.

Kotlin finality — a final class cannot be subclassed, so a member call on
a final-class-typed receiver dispatches to exactly the implementation
found in scope.  Open/abstract receivers keep VIRTUAL_DISPATCH (CB-07);
interface receivers keep INTERFACE_DISPATCH (CB-06).  Any change to the
finality rule requires a dedicated reviewed diff with fixture coverage
(fixture CB-15) and a shadow before/after delta.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import (
    AnalysisContract,
    CallGraphBuilder,
    ResolutionState,
)


def _build(source: str):
    corpus = {"app/src/main/java/com/example/Finality.kt": source}
    builder = CallGraphBuilder(_production_contract(), corpus)
    return builder, builder.build()


def test_finality_detection_pins():
    E = chr(10)
    src = (
        "package com.example" + E
        + "open class OpenBase { open fun go() {} }" + E
        + "class FinalDerived : OpenBase() { override fun go() {} }" + E
        + "object Singleton { fun go() {} }" + E
        + "abstract class AbsBase { abstract fun act() }" + E
    )
    builder = CallGraphBuilder(_production_contract(), {"t.kt": src})
    finality = {ofq: own.is_final_class for ofq, own in builder.owners.items()}
    assert finality["com.example.OpenBase"] is False
    assert finality["com.example.FinalDerived"] is True
    assert finality["com.example.Singleton"] is True
    assert finality["com.example.AbsBase"] is False


def test_final_class_override_member_resolves_exact():
    E = chr(10)
    src = (
        "package com.example" + E
        + "interface Sink { fun accept(value: Int) }" + E
        + "class FinalSink : Sink { override fun accept(value: Int) {} }" + E
        + "class Caller(private val sink: FinalSink) {" + E
        + "    fun exercise(value: Int) { sink.accept(value) }" + E
        + "}" + E
    )
    builder, graph = _build(src)
    edges = [
        e
        for e in graph.edges
        if e.uncertain is False
        and e.state is ResolutionState.EXACT_SYNCHRONOUS
        and any(t.split("|")[3] == "accept" for t in e.targets)
    ]
    assert edges, "final-class override member must resolve exactly"


def test_open_class_override_member_stays_virtual():
    E = chr(10)
    src = (
        "package com.example" + E
        + "open class Base { open fun describe(): String = \"base\" }" + E
        + "class Derived : Base() { override fun describe(): String = \"d\" }" + E
        + "class Caller(private val instance: Base) {" + E
        + "    fun exercise(): String { return instance.describe() }" + E
        + "}" + E
    )
    builder, graph = _build(src)
    virtual = [
        e
        for e in graph.edges
        if e.state is ResolutionState.VIRTUAL_DISPATCH
        and any(t.split("|")[3] == "describe" for t in e.targets)
    ]
    assert virtual, "open-class receiver must keep virtual uncertainty"
