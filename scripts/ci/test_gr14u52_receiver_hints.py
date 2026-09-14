"""GR-14u52: barrier-marker receiver hints for synthetic anon members.

Unit-level pins for the hint mechanism (the CB fixtures run the callgraph
edge layer and pass identically at base — the proof-layer expression is
board-pinned; these tests pin the RESOLVER and the accessor):

  1. ReceiverTypeResolver with a hint resolves a non-property name to the
     hinted exact FQCN (gap-filling).
  2. Declaration beats hint: a `val barrier: OtherType` declaration in the
     text wins over a hint for the same name (hints never override).
  3. A non-dotted hint value is ignored (the NOT_A_PROPERTY path is
     unchanged — hints must be exact FQCN strings).
  4. captured_binding_types returns {} for a non-synthetic callable key
     (the enclosing_key gate).
  5. captured_binding_types omits receivers the callgraph cannot resolve
     (fail closed — no hint is emitted for an unknown name).
  6. captured_binding_types builds hints from receiver_fqcn_for_call (the
     callgraph's own truth): a member PARAM shadows an enclosing capture
     and the hint reflects the PARAM's type.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder
from scripts.db_guard.structural_analysis.barrier_proof import ReceiverTypeResolver
from scripts.kotlin_callable_parser import mask_kotlin_source

_PATH = "app/src/main/java/com/example/Rh.kt"


def _build(*sources: str) -> CallGraphBuilder:
    corpus = {
        _PATH.replace("Rh.kt", "Rh%d.kt" % i): src
        for i, src in enumerate(sources, start=1)
    }
    builder = CallGraphBuilder(_production_contract(), corpus)
    builder.build()
    return builder


class TestReceiverTypeResolverHints:
    def test_hint_fills_gap_for_non_property_name(self):
        masked = mask_kotlin_source("fun f() {\n    barrier.check(\"x\")\n}\n")
        resolver = ReceiverTypeResolver(
            masked, hints={"barrier": "com.example.DatabaseWriteBarrier"}
        )
        fqcn, state = resolver.resolve("barrier")
        assert state == "RESOLVED"
        assert fqcn == "com.example.DatabaseWriteBarrier"

    def test_declaration_beats_hint(self):
        masked = mask_kotlin_source(
            "val barrier: com.example.OtherType = OtherType()\n"
            "fun f() {\n    barrier.check(\"x\")\n}\n"
        )
        resolver = ReceiverTypeResolver(
            masked, hints={"barrier": "com.example.DatabaseWriteBarrier"}
        )
        fqcn, state = resolver.resolve("barrier")
        assert state == "RESOLVED"
        assert fqcn == "com.example.OtherType"

    def test_non_dotted_hint_ignored(self):
        masked = mask_kotlin_source("fun f() {\n    x.check(\"y\")\n}\n")
        resolver = ReceiverTypeResolver(masked, hints={"x": "Simple"})
        fqcn, state = resolver.resolve("x")
        assert fqcn is None
        assert state == "NOT_A_PROPERTY"


class TestCapturedBindingTypes:
    def test_empty_for_non_synthetic_callable(self):
        sources = (
            "package com.example\n"
            "\n"
            "class Dao {\n"
            "    fun insert(x: Int) {}\n"
            "}\n"
            "\n"
            "class Holder(val dao: Dao) {\n"
            "    fun exercise() {\n"
            "        dao.insert(1)\n"
            "    }\n"
            "}\n"
        )
        builder = _build(sources)
        key = [k for k in builder.callables if k.split("|")[3] == "exercise"][0]
        assert builder.captured_binding_types(key) == {}

    def test_unresolvable_capture_omitted(self):
        sources = (
            "package com.example\n"
            "\n"
            "interface Target {\n"
            "    val name: String\n"
            "}\n"
            "\n"
            "fun exercise() {\n"
            "    val target = object : Target {\n"
            "        override val name = \"t\"\n"
            "        fun run() {\n"
            "            mysteryThing.check(\"x\")\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        builder = _build(sources)
        key = [k for k in builder.callables if k.split("|")[3] == "run" and "#anon" in k][0]
        hints = builder.captured_binding_types(key)
        assert "mysteryThing" not in hints

    def test_member_param_shadows_enclosing_capture(self):
        # ISSUE-1: hints come from receiver_fqcn_for_call — a member PARAM
        # shadows an enclosing capture of the same name, and the hint
        # reflects the PARAM's type (the callgraph's own resolution).
        sources = (
            "package com.example\n"
            "\n"
            "class OuterDao {\n"
            "    fun insert(x: Int) {}\n"
            "}\n"
            "\n"
            "class InnerDao {\n"
            "    fun insert(x: Int) {}\n"
            "}\n"
            "\n"
            "interface Target {\n"
            "    val name: String\n"
            "}\n"
            "\n"
            "fun exercise(outerDao: OuterDao) {\n"
            "    val target = object : Target {\n"
            "        override val name = \"t\"\n"
            "        fun run(outerDao: InnerDao) {\n"
            "            outerDao.insert(1)\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        builder = _build(sources)
        key = [k for k in builder.callables if k.split("|")[3] == "run" and "#anon" in k][0]
        hints = builder.captured_binding_types(key)
        assert hints.get("outerDao") == "com.example.InnerDao"

    def test_hints_from_capture_typing_still_resolve(self):
        # The u49 capture path remains a hint source when nothing shadows
        # it: the member captures the enclosing fun's val param.
        sources = (
            "package com.example\n"
            "\n"
            "class Dao {\n"
            "    fun insert(x: Int) {}\n"
            "}\n"
            "\n"
            "interface Target {\n"
            "    val name: String\n"
            "}\n"
            "\n"
            "fun exercise(dao: Dao) {\n"
            "    val target = object : Target {\n"
            "        override val name = \"t\"\n"
            "        fun run() {\n"
            "            dao.insert(1)\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        builder = _build(sources)
        key = [k for k in builder.callables if k.split("|")[3] == "run" and "#anon" in k][0]
        hints = builder.captured_binding_types(key)
        assert hints.get("dao") == "com.example.Dao"
