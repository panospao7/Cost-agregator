"""Unit tests for the GR-13 callable/owner span parser regressions.

Covers the two production-tree defect classes found in the Step 5
correlation triage:

  * expression bodies (``= withContext(...) { ... }``) must span their whole
    lambda — a truncated span hides the mutation call sites inside it and
    made D4 observation offsets fall outside every graph span;
  * brace-less type declarations (``object Success : Outcome``) must become
    header-only owners that contain nothing — an adopted next-body brace
    nested later callables under a wrong owner FQCN.

Run directly with: python test_parser_spans.py
(also runnable under pytest from the repository root)
"""

import os
import sys

_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)
if _ROOT not in sys.path:
    sys.path.insert(0, _ROOT)

from scripts.db_guard.mediation_analysis import callgraph  # noqa: E402


class TestExpressionBodySpans:
    def test_withcontext_lambda_spans_whole_body(self):
        source = (
            "package com.example\n"
            "\n"
            "class Repo(private val dao: Dao) {\n"
            "    suspend fun add(x: Int): Result = withContext(io) {\n"
            "        writeBarrier.checkWritesAllowed(\"Repo.add\")\n"
            "        val group = dao.getById(x)\n"
            "            ?: return@withContext Result.Error(x)\n"
            "        dao.insert(group)\n"
            "        Result.Ok(group)\n"
            "    }\n"
            "\n"
            "    suspend fun other(x: Int) {\n"
            "        dao.touch(x)\n"
            "    }\n"
            "}\n"
        )
        model = callgraph.parse_file_model("a.kt", source)
        adds = [c for c in model.callables if c.method == "add"]
        assert len(adds) == 1
        add = adds[0]
        # The whole withContext lambda is the body: the body end must sit at
        # or after the LAST statement's offsets, not at the first `val`.
        insert_offset = source.index("dao.insert")
        assert add.body_start <= insert_offset < add.body_end
        other_offset = source.index("dao.touch")
        assert other_offset >= add.body_end
        assert add.decl_start <= insert_offset < add.decl_end

    def test_comparison_operators_do_not_move_depth(self):
        source = (
            "package com.example\n"
            "\n"
            "class Math {\n"
            "    fun max(a: Int, b: Int) = if (a <= b) b else a\n"
            "\n"
            "    fun next(x: Int): Int {\n"
            "        return x + 1\n"
            "    }\n"
            "}\n"
        )
        model = callgraph.parse_file_model("a.kt", source)
        methods = {c.method: c for c in model.callables}
        assert set(methods) == {"max", "next"}
        # `max` must not swallow `next`: a comparison `<=` is not a generic
        # bracket and must not leak depth past the expression end.
        assert methods["max"].decl_end <= source.index("fun next")
        assert methods["next"].owner_fqcn.endswith("Math")

    def test_trailing_lambda_generics_and_index(self):
        source = (
            "package com.example\n"
            "\n"
            "class Repo {\n"
            "    fun pick(items: List<Int>): Int = items.filter { it > 1 }\n"
            "        .first()\n"
            "\n"
            "    fun rest(): Int = 0\n"
            "}\n"
        )
        model = callgraph.parse_file_model("a.kt", source)
        methods = {c.method: c for c in model.callables}
        assert set(methods) == {"pick", "rest"}
        assert methods["pick"].body_start <= source.index("it > 1")
        assert methods["pick"].decl_end <= source.index("fun rest")

    def test_bodyless_fun_does_not_adopt_next_body(self):
        source = (
            "package com.example\n"
            "\n"
            "abstract class Base {\n"
            "    abstract fun doWork(): Result\n"
            "\n"
            "    fun concrete(): Result {\n"
            "        return Result()\n"
            "    }\n"
            "}\n"
        )
        model = callgraph.parse_file_model("a.kt", source)
        concrete = [c for c in model.callables if c.method == "concrete"]
        do_work = [c for c in model.callables if c.method == "doWork"]
        assert len(concrete) == 1
        assert concrete[0].owner_fqcn.endswith("Base")
        assert len(do_work) == 1
        assert do_work[0].body_start < 0  # bodyless: no body span


class TestBraceLessOwners:
    def test_sealed_brace_less_subtypes_header_only(self):
        source = (
            "package com.example\n"
            "\n"
            "class BankApi {\n"
            "    private sealed interface Outcome {\n"
            "        object Success : Outcome\n"
            "        object ReauthRequired : Outcome\n"
            "        object Failed : Outcome\n"
            "    }\n"
            "\n"
            "    private suspend fun refreshToken(): Outcome {\n"
            "        return Outcome.Success\n"
            "    }\n"
            "}\n"
        )
        model = callgraph.parse_file_model("a.kt", source)
        refresh = [c for c in model.callables if c.method == "refreshToken"]
        assert len(refresh) == 1
        # The function after three brace-less objects belongs to BankApi,
        # not to the last brace-less object.
        assert refresh[0].owner_fqcn.endswith("BankApi")
        fqcms = {o.fqcn.rsplit(".", 1)[-1] for o in model.owners}
        assert {"Success", "ReauthRequired", "Failed"} <= fqcms
        # Header-only: none of the brace-less subtypes contains the fun.
        failed = [o for o in model.owners if o.simple_name == "Failed"][0]
        failed_end = source.index("object Failed") + len("object Failed : Outcome")
        assert failed_end >= source.index("object Failed")

    def test_brace_less_data_class_does_not_own_following_fun(self):
        source = (
            "package com.example\n"
            "\n"
            "class Notifications {\n"
            "    data class Snapshot(\n"
            "        val id: Long,\n"
            "        val title: String\n"
            "    )\n"
            "\n"
            "    suspend fun save(notification: String): Long {\n"
            "        return 1\n"
            "    }\n"
            "}\n"
        )
        model = callgraph.parse_file_model("a.kt", source)
        saves = [c for c in model.callables if c.method == "save"]
        assert len(saves) == 1
        assert saves[0].owner_fqcn.endswith("Notifications")
        snapshot = [o for o in model.owners if o.simple_name == "Snapshot"]
        assert len(snapshot) == 1

    def test_nested_brace_less_data_class_chain(self):
        source = (
            "package com.example\n"
            "\n"
            "class Outer {\n"
            "    private class Handle(\n"
            "        val id: Long\n"
            "    )\n"
            "\n"
            "    private data class Args(val id: Long)\n"
            "\n"
            "    private suspend fun terminal(id: Long) {\n"
            "        println(id)\n"
            "    }\n"
            "}\n"
        )
        model = callgraph.parse_file_model("a.kt", source)
        terminal = [c for c in model.callables if c.method == "terminal"]
        assert len(terminal) == 1
        # Both brace-less nested classes stay header-only, so `terminal`
        # nests under the real owner (Outer), not under Args or Handle.
        assert terminal[0].owner_fqcn.endswith("Outer")

    def test_braced_owner_still_contains_its_members(self):
        source = (
            "package com.example\n"
            "\n"
            "class Guard {\n"
            "    fun run(x: Int) {\n"
            "        println(x)\n"
            "    }\n"
            "\n"
            "    object Nested {\n"
            "        fun help(y: Int) {\n"
            "            println(y)\n"
            "        }\n"
            "    }\n"
            "}\n"
        )
        model = callgraph.parse_file_model("a.kt", source)
        run = [c for c in model.callables if c.method == "run"][0]
        help_fun = [c for c in model.callables if c.method == "help"][0]
        assert run.owner_fqcn.endswith("Guard")
        assert help_fun.owner_fqcn.endswith("Guard.Nested")


def main() -> int:
    failures = 0
    for name, obj in sorted(globals().items()):
        if name.startswith("Test") and isinstance(obj, type):
            instance = obj()
            for method_name in sorted(dir(instance)):
                if method_name.startswith("test_"):
                    try:
                        getattr(instance, method_name)()
                        print("PASS: %s.%s" % (name, method_name))
                    except AssertionError as error:
                        failures += 1
                        print("FAIL: %s.%s: %s" % (name, method_name, error))
    if failures:
        print("FAILED: %d" % failures)
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
