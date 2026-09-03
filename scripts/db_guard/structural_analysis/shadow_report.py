"""Deterministic shadow-report assembly (shadow-only).

Turns per-callable structural analyses into the report dict defined in
docs/architecture/DB_STRUCTURAL_ANALYSIS_MODEL.md.  Every callable resolves
to exactly one of SUPPORTED, UNSUPPORTED_CONSERVATIVELY, or
INFRASTRUCTURE_FAILURE; a CFG construction failure is infrastructure, never
"unsupported source".  The report carries bounded coordinates only (path,
line) — never raw source, offsets, SQL, exception text, or absolute paths.
"""
from __future__ import annotations

from .cfg import build_callable_cfg
from .diagnostics import make_diagnostic
from .model import AnalysisStatus, StructuralAnalysisResult
from .parser import classify_callable_body
from .tokenizer import CallableBodyParse, parse_callable_body

__all__ = ["analyze_callable_structurally", "build_shadow_report"]

_REPORT_SCHEMA_VERSION = 1


def _infra_result(callable_key: str, path: str, line: int, code: str) -> StructuralAnalysisResult:
    return StructuralAnalysisResult(
        callable_key=callable_key,
        status=AnalysisStatus.INFRASTRUCTURE_FAILURE,
        graph=None,
        syntax_families=(),
        diagnostics=(make_diagnostic(code, path, line, callable_key=callable_key),),
        node_count=0,
        edge_count=0,
    )


def analyze_callable_structurally(
    masked_text: str,
    body_span,
    path: str,
    callable_key: str,
    mutation_sites=(),
    barrier_marker_fn=None,
) -> StructuralAnalysisResult:
    """Run parse -> markers -> CFG for one callable and return the result.

    ``barrier_marker_fn(parse, masked_text) -> tuple[BarrierMarker, ...]`` is
    injected so this module stays decoupled from marker extraction.
    """
    try:
        parse: CallableBodyParse = parse_callable_body(masked_text, body_span)
    except (TypeError, ValueError):
        return _infra_result(
            callable_key,
            path,
            getattr(body_span, "line", 1) or 1,
            "DB_STRUCTURAL_MODEL_REPORT_INVALID",
        )
    if parse.unsupported:
        classification = classify_callable_body(
            parse, path=path, callable_key=callable_key
        )
        return StructuralAnalysisResult(
            callable_key=callable_key,
            status=classification.status,
            graph=None,
            syntax_families=classification.syntax_families,
            diagnostics=classification.diagnostics,
            node_count=0,
            edge_count=0,
        )
    markers = barrier_marker_fn(parse, masked_text) if barrier_marker_fn else ()
    try:
        graph, cfg_diagnostics = build_callable_cfg(
            parse,
            tuple(mutation_sites),
            tuple(markers),
            path=path,
            callable_key=callable_key,
        )
    except (TypeError, ValueError):
        return _infra_result(
            callable_key,
            path,
            body_span.line,
            "DB_STRUCTURAL_MODEL_GRAPH_INVARIANT_FAILED",
        )
    classification = classify_callable_body(parse, path=path, callable_key=callable_key)
    return StructuralAnalysisResult(
        callable_key=callable_key,
        status=AnalysisStatus.SUPPORTED,
        graph=graph,
        syntax_families=classification.syntax_families,
        diagnostics=cfg_diagnostics,
        node_count=len(graph.nodes),
        edge_count=len(graph.edges),
    )


def _result_entry(result: StructuralAnalysisResult, include_graph: bool) -> dict:
    entry = {
        "callableKey": result.callable_key,
        "status": result.status.value,
        "nodeCount": result.node_count,
        "edgeCount": result.edge_count,
        "mutationCount": (
            sum(1 for node in result.graph.nodes if node.kind.value == "MUTATION")
            if result.graph is not None
            else 0
        ),
        "barrierMarkerCount": (
            sum(
                1
                for node in result.graph.nodes
                if node.kind.value in ("BARRIER_CALL", "BARRIER_SCOPE")
            )
            if result.graph is not None
            else 0
        ),
        "diagnostics": [
            {"code": diagnostic.code, "path": diagnostic.path, "line": diagnostic.line}
            for diagnostic in result.diagnostics
        ],
    }
    if include_graph and result.graph is not None:
        entry["graph"] = {
            "nodes": [
                {
                    "id": node.id,
                    "kind": node.kind.value,
                    "span": {
                        "start": node.span.start,
                        "end": node.span.end,
                        "line": node.span.line,
                        "column": node.span.column,
                    },
                }
                for node in result.graph.nodes
            ],
            "edges": [
                {
                    "id": edge.id,
                    "kind": edge.kind.value,
                    "source": edge.source_node_id,
                    "target": edge.target_node_id,
                }
                for edge in result.graph.edges
            ],
        }
    return entry


def build_shadow_report(
    results,
    target_sha: str | None = None,
    include_graphs: bool = False,
) -> dict:
    """Assemble the shadow report dict from StructuralAnalysisResult objects.

    Deterministic: callables sorted by callableKey; diagnostics already
    sorted by the model.  ``include_graphs`` adds the node/edge payloads for
    GR-12 consumption; the default CLI report omits them.
    """
    ordered = sorted(results, key=lambda item: item.callable_key)
    supported = sum(1 for item in ordered if item.status is AnalysisStatus.SUPPORTED)
    unsupported = sum(
        1 for item in ordered if item.status is AnalysisStatus.UNSUPPORTED_CONSERVATIVELY
    )
    infrastructure = sum(
        1 for item in ordered if item.status is AnalysisStatus.INFRASTRUCTURE_FAILURE
    )
    return {
        "schemaVersion": _REPORT_SCHEMA_VERSION,
        "reportOnly": True,
        "targetSha": target_sha,
        "summary": {
            "callableCount": len(ordered),
            "supportedCount": supported,
            "unsupportedCount": unsupported,
            "infrastructureFailureCount": infrastructure,
        },
        "callables": [_result_entry(item, include_graphs) for item in ordered],
    }
