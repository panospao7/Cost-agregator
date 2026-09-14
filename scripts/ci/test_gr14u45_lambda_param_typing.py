"""GR-14u45: lambda-parameter typing for admitted carriers (engine).

Two fail-closed binding shapes added to receiver resolution:
  * Shape A (callee signature): the innermost enclosing ADMITTED transparent
    lambda region's carrier call resolves to corpus callable(s) whose LAST
    declared parameter is a function type; the lambda's k-th parameter binds
    to the k-th component type when arities match and the component resolves
    to exactly one corpus FQCN or known external.
  * Shape B (receiver generic): a collection-style carrier name whose
    receiver's declared type has exactly ONE explicit type argument binds
    arity-1 lambda parameters to it.

Soundness pins (must stay UNBOUND — the fail-OPEN traps):
  * arity mismatch (2-param lambda vs 1-component function type);
  * generic component (`suspend (T) -> T` — T must never bind);
  * async-carrier lambda parameters (only ADMITTED transparent regions bind);
  * two distinct candidate signatures among the carrier's resolved targets;
  * receiver generic with no explicit type argument;
  * lambda passed as a named non-trailing argument (no region, no binding).

Proving pins (RED pre-fix):
  * shape A: `recorder.runOperation("X") { run -> run.event(...) }` binds
    `run` to the interface handle type -> EXACT edge;
  * shape B: `assets.forEach { it.initialize() }` binds `it` to the set's
    element type -> EXACT edge (implicit `it` form).
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder

_PATH = "app/src/main/java/com/example/Lp.kt"


def _build(*sources: str) -> CallGraphBuilder:
    corpus = {
        _PATH.replace("Lp.kt", "Lp%d.kt" % i): src
        for i, src in enumerate(sources, start=1)
    }
    builder = CallGraphBuilder(_production_contract(), corpus)
    builder.build()
    return builder


def _edge_state(builder: CallGraphBuilder, caller_method: str, callee: str) -> str:
    states = set()
    found = False
    for edge in builder.edges:
        model = builder.callables.get(edge.caller_key)
        if model is None or model.method != caller_method:
            continue
        call = None
        for record in builder.calls_by_callable.get(edge.caller_key, ()):
            if record.name_start == edge.name_start:
                call = record
                break
        if call is None or call.name != callee:
            continue
        found = True
        states.add(edge.state.value)
    assert found, "no edge found for %s -> %s" % (caller_method, callee)
    assert len(states) == 1, states
    return next(iter(states))


_POSITIVE_A = (
    "package com.example\n"
    "\n"
    "interface Handle {\n"
    "    fun event(stage: String)\n"
    "}\n"
    "\n"
    "class HandleImpl : Handle {\n"
    "    override fun event(stage: String) {}\n"
    "}\n"
    "\n"
    "interface Recorder {\n"
    "    fun <T> runOperation(operationType: String, block: suspend (Handle) -> T): T\n"
    "}\n"
    "\n"
    "class RecorderImpl : Recorder {\n"
    "    override fun <T> runOperation(operationType: String, block: suspend (Handle) -> T): T {\n"
    "        @Suppress(\"UNCHECKED_CAST\")\n"
    "        return block(HandleImpl()) as T\n"
    "    }\n"
    "}\n"
    "\n"
    "fun exercise(recorder: Recorder) {\n"
    "    recorder.runOperation(\"X\") { run ->\n"
    "        run.event(\"STARTED\")\n"
    "    }\n"
    "}\n"
)

_POSITIVE_B = (
    "package com.example\n"
    "\n"
    "interface Initializer {\n"
    "    fun initialize()\n"
    "}\n"
    "\n"
    "class InitializerImpl : Initializer {\n"
    "    override fun initialize() {}\n"
    "}\n"
    "\n"
    "fun exercise(assets: Set<Initializer>) {\n"
    "    assets.forEach { it.initialize() }\n"
    "}\n"
)

_ARITY = _POSITIVE_A.replace(
    "recorder.runOperation(\"X\") { run ->",
    "recorder.runOperation(\"X\") { run, extra ->",
).replace("fun exercise(recorder: Recorder)", "fun exercise(recorder: Recorder)")

_GENERIC = (
    "package com.example\n"
    "\n"
    "interface Handle {\n"
    "    fun event(stage: String)\n"
    "}\n"
    "\n"
    "interface Recorder {\n"
    "    fun <T> runOperation(operationType: String, block: suspend (T) -> T): T\n"
    "}\n"
    "\n"
    "fun exercise(recorder: Recorder) {\n"
    "    recorder.runOperation(\"X\") { run ->\n"
    "        run.event(\"STARTED\")\n"
    "    }\n"
    "}\n"
)

_TWO_SIGNATURES = (
    "package com.example\n"
    "\n"
    "interface HandleA {\n"
    "    fun event(stage: String)\n"
    "}\n"
    "\n"
    "interface HandleB {\n"
    "    fun event(stage: String)\n"
    "}\n"
    "\n"
    "interface Recorder {\n"
    "    fun <T> runOperation(operationType: String, block: suspend (HandleA) -> T): T\n"
    "    fun <T> runOperation(operationId: Int, block: suspend (HandleB) -> T): T\n"
    "}\n"
    "\n"
    "fun exercise(recorder: Recorder) {\n"
    "    recorder.runOperation(\"X\") { run ->\n"
    "        run.event(\"STARTED\")\n"
    "    }\n"
    "}\n"
)

_ASYNC = (
    "package com.example\n"
    "\n"
    "interface Handle {\n"
    "    fun event(stage: String)\n"
    "}\n"
    "\n"
    "class Scope {\n"
    "    fun launchWorker(block: suspend (Handle) -> Unit) {}\n"
    "}\n"
    "\n"
    "fun exercise(scope: Scope) {\n"
    "    scope.launchWorker { worker ->\n"
    "        worker.event(\"STARTED\")\n"
    "    }\n"
    "}\n"
)

_NO_TYPE_ARG = (
    "package com.example\n"
    "\n"
    "interface Initializer {\n"
    "    fun initialize()\n"
    "}\n"
    "\n"
    "fun exercise(assets: Set<*>) {\n"
    "    assets.forEach { it.initialize() }\n"
    "}\n"
)

_NAMED_ARG = (
    "package com.example\n"
    "\n"
    "interface Handle {\n"
    "    fun event(stage: String)\n"
    "}\n"
    "\n"
    "class HandleImpl : Handle {\n"
    "    override fun event(stage: String) {}\n"
    "}\n"
    "\n"
    "interface Recorder {\n"
    "    fun <T> runOperation(operationType: String, block: suspend (Handle) -> T): T\n"
    "}\n"
    "\n"
    "fun exercise(recorder: Recorder) {\n"
    "    recorder.runOperation(operationType = \"X\", block = { run ->\n"
    "        run.event(\"STARTED\")\n"
    "    })\n"
    "}\n"
)


class TestLambdaParamTypingPositives:
    def test_shape_a_callee_signature_binds_lambda_param(self):
        builder = _build(_POSITIVE_A)
        assert _edge_state(builder, "exercise", "event") == "exact_synchronous"

    def test_shape_b_receiver_generic_binds_implicit_it(self):
        builder = _build(_POSITIVE_B)
        assert _edge_state(builder, "exercise", "initialize") == "exact_synchronous"


class TestLambdaParamTypingFailClosed:
    def test_arity_mismatch_stays_unbound(self):
        builder = _build(_ARITY)
        assert _edge_state(builder, "exercise", "event") == "unresolved_target"

    def test_generic_component_stays_unbound(self):
        builder = _build(_GENERIC)
        assert _edge_state(builder, "exercise", "event") == "unresolved_target"

    def test_two_distinct_signatures_stay_unbound(self):
        builder = _build(_TWO_SIGNATURES)
        assert _edge_state(builder, "exercise", "event") == "unresolved_target"

    def test_async_carrier_lambda_param_stays_unbound(self):
        builder = _build(_ASYNC)
        assert _edge_state(builder, "exercise", "event") == "async_dispatch"

    def test_no_explicit_type_argument_stays_unbound(self):
        builder = _build(_NO_TYPE_ARG)
        assert _edge_state(builder, "exercise", "initialize") == "unresolved_target"

    def test_named_non_trailing_argument_stays_unbound(self):
        builder = _build(_NAMED_ARG)
        assert _edge_state(builder, "exercise", "event") == "unresolved_target"
