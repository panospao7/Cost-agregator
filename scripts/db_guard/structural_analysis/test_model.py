"""Shadow-only structural model tests (conservative, no proof claims).

GR-12 owns dominance. Pure unit tests for the shadow-only structural model
(no repo fixtures)."""
from __future__ import annotations

import sys
from dataclasses import FrozenInstanceError, fields
from pathlib import Path

import pytest

_ROOT = Path(__file__).resolve().parents[3]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from scripts.db_guard.mutation_observation import MutationObservation
from scripts.db_guard.structural_analysis.diagnostics import (
    DIAGNOSTIC_CODES,
    make_diagnostic,
)
from scripts.db_guard.structural_analysis.model import (
    AnalysisStatus,
    BarrierMarker,
    BarrierMarkerKind,
    CallableStructuralInput,
    ControlFlowGraph,
    EdgeKind,
    MutationSite,
    NodeKind,
    SourceSpan,
    StructuralAnalysisResult,
    StructuralDiagnostic,
    StructuralEdge,
    StructuralNode,
    SyntaxFamily,
)


def _span(start=0, end=5, line=1, column=1):
    return SourceSpan(start=start, end=end, line=line, column=column)


def _node(node_id="n1", kind=NodeKind.ENTRY):
    return StructuralNode(id=node_id, kind=kind, span=_span())


def _edge(edge_id="e1", kind=EdgeKind.NORMAL, source="n1", target="n2"):
    return StructuralEdge(id=edge_id, kind=kind, source_node_id=source, target_node_id=target)


def _graph(nodes=None, edges=None, key="k"):
    if nodes is None:
        nodes = (
            StructuralNode(id="a", kind=NodeKind.ENTRY, span=_span()),
            StructuralNode(id="b", kind=NodeKind.EXIT_NORMAL, span=_span()),
        )
    if edges is None:
        edges = ()
    return ControlFlowGraph(callable_key=key, nodes=nodes, edges=edges)


def _diag(code=DIAGNOSTIC_CODES[0], path="a/b.kt", line=1, **kwargs):
    return StructuralDiagnostic(code=code, path=path, line=line, **kwargs)


def test_frozen_dataclasses_reject_mutation():
    instances = [
        _span(),
        BarrierMarker(
            kind=BarrierMarkerKind.UNKNOWN_BARRIER_LIKE_CALL,
            span=_span(),
            receiver_fqcn=None,
            method="m",
        ),
        MutationSite(
            span=_span(),
            callable_key="k",
            dao_fqcn="d",
            operation="o",
            mutation_kind="mk",
            source_identity="si",
        ),
        _node(),
        _edge(),
        _graph(),
        _diag(),
        CallableStructuralInput(callable_key="k", path="a/b.kt", body_span=_span()),
        StructuralAnalysisResult(
            callable_key="k",
            status=AnalysisStatus.UNSUPPORTED_CONSERVATIVELY,
            graph=None,
        ),
    ]
    for inst in instances:
        with pytest.raises(FrozenInstanceError):
            setattr(inst, "_frozen_probe", 1)


def test_source_span_valid():
    s = _span(start=2, end=2, line=3, column=4)
    assert (s.start, s.end, s.line, s.column) == (2, 2, 3, 4)


def test_source_span_end_before_start_rejected():
    with pytest.raises(ValueError):
        _span(start=5, end=4)


def test_source_span_line_zero_rejected():
    with pytest.raises(ValueError):
        _span(line=0)


def test_source_span_column_zero_rejected():
    with pytest.raises(ValueError):
        _span(column=0)


def test_source_span_bool_offsets_rejected():
    with pytest.raises(TypeError):
        _span(start=True)
    with pytest.raises(TypeError):
        _span(end=False)


def test_source_span_non_int_rejected():
    with pytest.raises(TypeError):
        _span(start="0")
    with pytest.raises(TypeError):
        _span(end=1.5)
    with pytest.raises(TypeError):
        _span(line="1")
    with pytest.raises(TypeError):
        _span(column=None)


def test_enum_closed_membership():
    assert [e.value for e in NodeKind] == [
        "ENTRY",
        "EXIT_NORMAL",
        "EXIT_EXCEPTIONAL",
        "STATEMENT",
        "MUTATION",
        "BARRIER_CALL",
        "BARRIER_SCOPE",
        "BRANCH",
        "WHEN",
        "LOOP_HEADER",
        "LOOP_BODY",
        "TRY",
        "CATCH",
        "FINALLY",
        "RETURN",
        "THROW",
        "BREAK",
        "CONTINUE",
        "LAMBDA",
        "LOCAL_FUNCTION",
        "UNKNOWN_CONSTRUCT",
    ]
    assert [e.value for e in EdgeKind] == [
        "NORMAL",
        "TRUE_BRANCH",
        "FALSE_BRANCH",
        "WHEN_BRANCH",
        "LOOP_BODY",
        "LOOP_EXIT",
        "RETURN_EXIT",
        "THROW_EXIT",
        "EXCEPTION",
        "FINALLY",
        "LAMBDA_DEFERRED",
        "UNKNOWN",
    ]
    assert [e.value for e in BarrierMarkerKind] == [
        "DIRECT_CHECK",
        "DIRECT_SCOPE",
        "WORKER_GUARD_CANDIDATE",
        "UNKNOWN_BARRIER_LIKE_CALL",
    ]
    assert [e.value for e in SyntaxFamily] == [
        "BRACED_FUNCTION",
        "IF_ELSE",
        "WHEN",
        "LOOP",
        "TRY_FINALLY",
        "NESTED_LAMBDA",
        "LOCAL_FUNCTION",
        "EXPRESSION_BODY",
        "UNKNOWN_CONSTRUCT",
    ]
    assert [e.value for e in AnalysisStatus] == [
        "SUPPORTED",
        "UNSUPPORTED_CONSERVATIVELY",
        "INFRASTRUCTURE_FAILURE",
    ]
    with pytest.raises(ValueError):
        NodeKind("NOPE")
    with pytest.raises(ValueError):
        EdgeKind("NOPE")
    with pytest.raises(ValueError):
        BarrierMarkerKind("NOPE")
    with pytest.raises(ValueError):
        SyntaxFamily("NOPE")
    with pytest.raises(ValueError):
        AnalysisStatus("NOPE")


def test_barrier_marker_receiver_fqcn_rules():
    # GR-11 resolves no receiver types: None is the honest unresolved value
    # for every marker kind; empty strings are rejected.
    for kind in BarrierMarkerKind:
        marker = BarrierMarker(kind=kind, span=_span(), receiver_fqcn=None, method="m")
        assert marker.receiver_fqcn is None
    with pytest.raises((TypeError, ValueError)):
        BarrierMarker(
            kind=BarrierMarkerKind.DIRECT_CHECK,
            span=_span(),
            receiver_fqcn="",
            method="check",
        )


def test_barrier_marker_unknown_without_receiver_accepted():
    m = BarrierMarker(
        kind=BarrierMarkerKind.UNKNOWN_BARRIER_LIKE_CALL,
        span=_span(),
        receiver_fqcn=None,
        method="maybe",
    )
    assert m.receiver_fqcn is None


def test_barrier_marker_empty_method_rejected():
    with pytest.raises((TypeError, ValueError)):
        BarrierMarker(
            kind=BarrierMarkerKind.UNKNOWN_BARRIER_LIKE_CALL,
            span=_span(),
            receiver_fqcn=None,
            method="",
        )


def test_mutation_site_empty_string_rejected():
    base = dict(
        span=_span(),
        callable_key="k",
        dao_fqcn="d",
        operation="o",
        mutation_kind="mk",
        source_identity="si",
    )
    for field_name in ("callable_key", "dao_fqcn", "operation", "mutation_kind", "source_identity"):
        bad = dict(base)
        bad[field_name] = ""
        with pytest.raises((TypeError, ValueError)):
            MutationSite(**bad)


def test_structural_node_empty_id_rejected():
    with pytest.raises((TypeError, ValueError)):
        StructuralNode(id="", kind=NodeKind.STATEMENT, span=_span())


def test_structural_edge_empty_field_rejected():
    with pytest.raises((TypeError, ValueError)):
        StructuralEdge(id="", kind=EdgeKind.NORMAL, source_node_id="a", target_node_id="b")
    with pytest.raises((TypeError, ValueError)):
        StructuralEdge(id="e", kind=EdgeKind.NORMAL, source_node_id="", target_node_id="b")
    with pytest.raises((TypeError, ValueError)):
        StructuralEdge(id="e", kind=EdgeKind.NORMAL, source_node_id="a", target_node_id="")


def test_graph_duplicate_node_id_rejected():
    nodes = (_node("a", NodeKind.ENTRY), _node("a", NodeKind.EXIT_NORMAL))
    with pytest.raises(ValueError):
        _graph(nodes=nodes)


def test_graph_duplicate_edge_id_rejected():
    nodes = (_node("a", NodeKind.ENTRY), _node("b", NodeKind.EXIT_NORMAL))
    edges = (_edge("e", source="a", target="b"), _edge("e", source="b", target="a"))
    with pytest.raises(ValueError):
        _graph(nodes=nodes, edges=edges)


def test_graph_edge_missing_node_rejected():
    nodes = (_node("a", NodeKind.ENTRY), _node("b", NodeKind.EXIT_NORMAL))
    with pytest.raises(ValueError):
        _graph(nodes=nodes, edges=(_edge("e1", source="a", target="zzz"),))


def test_graph_zero_entry_rejected():
    nodes = (_node("a", NodeKind.STATEMENT), _node("b", NodeKind.EXIT_NORMAL))
    with pytest.raises(ValueError):
        _graph(nodes=nodes)


def test_graph_two_entries_rejected():
    nodes = (_node("a", NodeKind.ENTRY), _node("b", NodeKind.ENTRY))
    with pytest.raises(ValueError):
        _graph(nodes=nodes)


def test_graph_zero_exit_rejected():
    nodes = (_node("a", NodeKind.ENTRY), _node("b", NodeKind.STATEMENT))
    with pytest.raises(ValueError):
        _graph(nodes=nodes)


def test_graph_entry_plus_exit_no_edges_accepted():
    g = _graph()
    assert len(g.nodes) == 2
    assert len(g.edges) == 0


def test_graph_sorted_storage():
    n1 = StructuralNode(id="b", kind=NodeKind.EXIT_NORMAL, span=_span())
    n2 = StructuralNode(id="a", kind=NodeKind.ENTRY, span=_span())
    g = ControlFlowGraph(
        callable_key="k",
        nodes=(n1, n2),
        edges=(
            StructuralEdge(id="e2", kind=EdgeKind.NORMAL, source_node_id="a", target_node_id="b"),
            StructuralEdge(id="e1", kind=EdgeKind.NORMAL, source_node_id="a", target_node_id="a"),
        ),
    )
    assert [n.id for n in g.nodes] == ["a", "b"]
    assert [e.id for e in g.edges] == ["e1", "e2"]


def test_self_loop_allowed():
    nodes = (_node("a", NodeKind.ENTRY), _node("b", NodeKind.EXIT_NORMAL))
    edges = (_edge("e1", source="a", target="a"),)
    g = _graph(nodes=nodes, edges=edges)
    assert len(g.edges) == 1


def test_diagnostic_unknown_code_rejected():
    with pytest.raises(ValueError):
        StructuralDiagnostic(code="NOPE", path="a/b.kt", line=1)
    with pytest.raises(ValueError):
        make_diagnostic("NOPE", "a/b.kt", 1)


def test_diagnostic_absolute_paths_rejected():
    for bad in ("C:\\x\\y.kt", "C:/x.kt", "/x", "\\\\server\\share"):
        with pytest.raises(ValueError):
            StructuralDiagnostic(code=DIAGNOSTIC_CODES[0], path=bad, line=1)
        with pytest.raises(ValueError):
            make_diagnostic(DIAGNOSTIC_CODES[0], bad, 1)


def test_diagnostic_accepted_relative():
    d = make_diagnostic(
        DIAGNOSTIC_CODES[0],
        "src/main/Foo.kt",
        7,
        callable_key="ck",
        syntax_family=SyntaxFamily.LOOP,
    )
    assert d.path == "src/main/Foo.kt"
    assert d.line == 7
    assert d.callable_key == "ck"
    assert d.syntax_family == SyntaxFamily.LOOP


def test_diagnostic_codes_exact():
    assert tuple(DIAGNOSTIC_CODES) == (
        "DB_STRUCTURAL_MODEL_CALLABLE_UNRESOLVED",
        "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
        "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
        "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
        "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE",
        "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED",
        "DB_STRUCTURAL_MODEL_MUTATION_SITE_UNRESOLVED",
        "DB_STRUCTURAL_MODEL_BARRIER_FORM_UNRECOGNIZED",
        "DB_STRUCTURAL_MODEL_GRAPH_INVARIANT_FAILED",
        "DB_STRUCTURAL_MODEL_REPORT_INVALID",
    )


def test_result_supported_without_graph_rejected():
    with pytest.raises(ValueError):
        StructuralAnalysisResult(
            callable_key="k", status=AnalysisStatus.SUPPORTED, graph=None
        )


def test_result_non_supported_with_graph_rejected():
    g = _graph()
    with pytest.raises(ValueError):
        StructuralAnalysisResult(
            callable_key="k", status=AnalysisStatus.UNSUPPORTED_CONSERVATIVELY, graph=g
        )
    with pytest.raises(ValueError):
        StructuralAnalysisResult(
            callable_key="k", status=AnalysisStatus.INFRASTRUCTURE_FAILURE, graph=g
        )


def test_result_deduped_sorted():
    d1 = _diag(code=DIAGNOSTIC_CODES[2], path="b.kt", line=3)
    d2 = _diag(code=DIAGNOSTIC_CODES[0], path="a.kt", line=9)
    d3 = _diag(code=DIAGNOSTIC_CODES[0], path="a.kt", line=9)
    r = StructuralAnalysisResult(
        callable_key="k",
        status=AnalysisStatus.UNSUPPORTED_CONSERVATIVELY,
        graph=None,
        syntax_families=(SyntaxFamily.WHEN, SyntaxFamily.IF_ELSE, SyntaxFamily.WHEN),
        diagnostics=(d1, d2, d3),
    )
    assert r.syntax_families == (SyntaxFamily.IF_ELSE, SyntaxFamily.WHEN)
    assert r.diagnostics == tuple(sorted({d1, d2}, key=lambda d: (d.code, d.path, d.line)))


def test_from_observation():
    obs = MutationObservation(
        path="src/main/Dao.kt",
        callable_key="p|o|k|m|null|",
        source_start=10,
        source_end=20,
        line=2,
        column=3,
        dao_accessor="acc",
        dao_fqcn="com.example.Dao",
        operation="insert",
        mutation_kind="INSERT",
        source_identity="id",
    )
    site = MutationSite.from_observation(obs)
    assert (site.span.start, site.span.end, site.span.line, site.span.column) == (10, 20, 2, 3)
    assert site.callable_key == obs.callable_key
    assert site.dao_fqcn == obs.dao_fqcn
    assert site.operation == obs.operation
    assert site.mutation_kind == obs.mutation_kind
    assert site.source_identity == obs.source_identity
    wrapped = CallableStructuralInput.from_observation(obs)
    assert wrapped.callable_key == obs.callable_key
    assert wrapped.path == obs.path
    assert (wrapped.body_span.start, wrapped.body_span.end) == (10, 20)
    assert len(wrapped.mutation_sites) == 1
    assert wrapped.mutation_sites[0] == site
    assert wrapped.barrier_markers == ()


def test_no_raw_source_field():
    names = {f.name for f in fields(StructuralDiagnostic)}
    assert names == {"code", "path", "line", "callable_key", "syntax_family"}
    for name in names:
        lowered = name.lower()
        assert "source" not in lowered
        assert "text" not in lowered
        assert "snippet" not in lowered


def test_deterministic_ordering():
    sites = [
        MutationSite(
            span=SourceSpan(start=s, end=s + 1, line=1, column=1),
            callable_key="k%d" % s,
            dao_fqcn="d",
            operation="o",
            mutation_kind="mk",
            source_identity="si",
        )
        for s in (30, 10, 20)
    ]
    first = CallableStructuralInput(
        callable_key="k", path="a.kt", body_span=_span(), mutation_sites=tuple(sites)
    )
    second = CallableStructuralInput(
        callable_key="k",
        path="a.kt",
        body_span=_span(),
        mutation_sites=tuple(reversed(sites)),
    )
    assert first.mutation_sites == second.mutation_sites
    diags = [
        _diag(code=DIAGNOSTIC_CODES[1], path="b.kt", line=2),
        _diag(code=DIAGNOSTIC_CODES[0], path="a.kt", line=5),
    ]
    r1 = StructuralAnalysisResult(
        callable_key="k",
        status=AnalysisStatus.UNSUPPORTED_CONSERVATIVELY,
        graph=None,
        diagnostics=tuple(diags),
        syntax_families=(SyntaxFamily.WHEN, SyntaxFamily.IF_ELSE),
    )
    r2 = StructuralAnalysisResult(
        callable_key="k",
        status=AnalysisStatus.UNSUPPORTED_CONSERVATIVELY,
        graph=None,
        diagnostics=tuple(reversed(diags)),
        syntax_families=(SyntaxFamily.IF_ELSE, SyntaxFamily.WHEN),
    )
    assert r1.diagnostics == r2.diagnostics
    assert r1.syntax_families == r2.syntax_families
