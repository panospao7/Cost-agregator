"""Conservative intraprocedural CFG construction (shadow-only).

Builds one :class:`ControlFlowGraph` per SUPPORTED parsed callable body.
Edges are SYNTACTICALLY POSSIBLE paths, not proven execution: loops get
body/exit edges, every TRY gets conservative exceptional edges to each
CATCH, and a BARRIER_SCOPE's outgoing edge is UNKNOWN because lambda
execution is never assumed synchronous.  Dominance is NOT computed here —
that is GR-12's proof step.

Mutation sites and barrier markers attach by exact span containment.  A site
that no modeled region contains is reported as
DB_STRUCTURAL_MODEL_MUTATION_SITE_UNRESOLVED and attached to ENTRY so it
stays reachable — never silently dropped.

Construction raises ValueError on any internal invariant violation; the
caller (shadow report) classifies that as INFRASTRUCTURE_FAILURE.
"""
from __future__ import annotations

from .diagnostics import make_diagnostic
from .model import (
    EdgeKind,
    MutationSite,
    NodeKind,
    SourceSpan,
    StructuralDiagnostic,
    StructuralEdge,
    StructuralNode,
    BarrierMarker,
    ControlFlowGraph,
)
from .tokenizer import CallableBodyParse, RegionKind

__all__ = ["build_callable_cfg"]

_REGION_NODE_KIND = {
    RegionKind.STATEMENT: NodeKind.STATEMENT,
    RegionKind.IF: NodeKind.BRANCH,
    RegionKind.WHEN: NodeKind.WHEN,
    RegionKind.LOOP: NodeKind.LOOP_HEADER,
    RegionKind.TRY: NodeKind.TRY,
    RegionKind.CATCH: NodeKind.CATCH,
    RegionKind.FINALLY: NodeKind.FINALLY,
    RegionKind.RETURN: NodeKind.RETURN,
    RegionKind.THROW: NodeKind.THROW,
    RegionKind.BREAK: NodeKind.BREAK,
    RegionKind.CONTINUE: NodeKind.CONTINUE,
    RegionKind.ACCESSOR: NodeKind.STATEMENT,
    RegionKind.BARRIER_SCOPE: NodeKind.BARRIER_SCOPE,
    RegionKind.DIRECT_CHECK: NodeKind.BARRIER_CALL,
}


class _Builder:
    def __init__(self, text_len: int, body_span: SourceSpan) -> None:
        self.nodes: list[StructuralNode] = []
        self.edges: list[StructuralEdge] = []
        self.node_by_span: dict[tuple[int, int], str] = {}
        self.text_len = text_len
        self.body_span = body_span
        self.exit_normal: str | None = None
        self.exit_exceptional: str | None = None
        self._counter = 0
        # GR-14b: (start, end) spans of contract-admitted transparent-scope
        # regions.  Non-admitted candidates build as disconnected scopes.
        self.admitted_transparent: frozenset = frozenset()

    def add(self, kind: NodeKind, span: SourceSpan) -> str:
        self._counter += 1
        node_id = "n%03d" % self._counter
        self.nodes.append(StructuralNode(id=node_id, kind=kind, span=span))
        self.node_by_span.setdefault((span.start, span.end), node_id)
        return node_id

    def edge(self, kind: EdgeKind, src: str, dst: str) -> None:
        self._counter += 1
        self.edges.append(
            StructuralEdge(
                id="e%03d" % self._counter, kind=kind, source_node_id=src, target_node_id=dst
            )
        )

    def span_at(self, offset: int) -> SourceSpan:
        return SourceSpan(start=offset, end=offset, line=1, column=1)

    def ensure_exit_normal(self) -> str:
        if self.exit_normal is None:
            span = self.body_span
            self.exit_normal = self.add(
                NodeKind.EXIT_NORMAL,
                SourceSpan(
                    start=span.end, end=span.end, line=span.line, column=span.column
                ),
            )
        return self.exit_normal

    def ensure_exit_exceptional(self) -> str:
        if self.exit_exceptional is None:
            span = self.body_span
            self.exit_exceptional = self.add(
                NodeKind.EXIT_EXCEPTIONAL,
                SourceSpan(
                    start=span.end, end=span.end, line=span.line, column=span.column
                ),
            )
        return self.exit_exceptional


def _innermost_owner(builder: _Builder, start: int, end: int) -> str | None:
    best: tuple[int, str] | None = None
    for (node_start, node_end), node_id in builder.node_by_span.items():
        if node_start <= start and end <= node_end:
            width = node_end - node_start
            if best is None or width < best[0]:
                best = (width, node_id)
    return best[1] if best is not None else None


def _attach_site(
    builder: _Builder,
    start: int,
    end: int,
    kind: NodeKind,
    diagnostics: list[StructuralDiagnostic],
    unresolved_code: str,
    path: str,
    site_line: int,
) -> None:
    owner = _innermost_owner(builder, start, end)
    if owner is None:
        diagnostics.append(
            make_diagnostic(unresolved_code, path, site_line)
        )
        owner = builder.nodes[0].id
    builder.edge(EdgeKind.NORMAL, owner, builder.add(kind, builder.span_at(start)))


def _build_regions(
    builder: _Builder,
    regions,
    in_lambda: bool,
    loop_ctx: dict | None,
    in_transparent: bool = False,
) -> tuple[list[str], list[str], list[tuple[EdgeKind, str]]]:
    """Build a statement sequence.

    Returns (first_node_ids, last_normal_node_ids, dangling) where dangling
    holds (edge_kind, source_node_id) pairs that must connect to the sequence
    join (the next sibling's entry, the enclosing join, or the exit).
    ``in_transparent`` marks a sequence directly/structurally inside an
    admitted transparent scope: RETURN and LAMBDA_RETURN are lambda-local
    there and hand their exit to the continuation via dangling edges.
    """
    entries: list[tuple[list[str], list[str], list[tuple[EdgeKind, str]]]] = []
    for region in regions:
        entries.append(_build_region(builder, region, in_lambda, loop_ctx, in_transparent))
    non_empty = [
        item for item in entries if item[0] or item[2]
    ]
    firsts: list[str] = []
    lasts: list[str] = []
    dangling: list[tuple[EdgeKind, str]] = []
    for index, (region_firsts, region_lasts, region_dangling) in enumerate(non_empty):
        if index == 0:
            firsts.extend(region_firsts)
        else:
            for prev_last in non_empty[index - 1][1]:
                for first in region_firsts:
                    builder.edge(EdgeKind.NORMAL, prev_last, first)
        if index == len(non_empty) - 1:
            lasts.extend(region_lasts)
            dangling.extend(region_dangling)
        else:
            for kind, src in region_dangling:
                for first in non_empty[index + 1][0]:
                    builder.edge(kind, src, first)
    return firsts, lasts, dangling


def _build_region(
    builder: _Builder,
    region,
    in_lambda: bool,
    loop_ctx: dict | None,
    in_transparent: bool = False,
) -> tuple[list[str], list[str], list[tuple[EdgeKind, str]]]:
    # GR-14b: lambda-local return inside an admitted transparent scope.  The
    # wrapper lambda completes, so the return hands control to the
    # continuation after the scope statement (a dangling RETURN_EXIT edge).
    if region.kind == RegionKind.LAMBDA_RETURN:
        node = builder.add(NodeKind.RETURN, region.span)
        if in_transparent:
            return ([node], [], [(EdgeKind.RETURN_EXIT, node)])
        builder.edge(EdgeKind.RETURN_EXIT, node, builder.ensure_exit_normal())
        return ([node], [], [])

    # GR-14b: contract-admitted synchronous transparent scope.  The body is
    # WIRED into the caller's flow (scope -> children -> continuation) so a
    # canonical check dominating the scope call site also dominates every
    # mutation inside the lambda body.  Non-admitted candidates build exactly
    # like a v1 BARRIER_SCOPE: children stay disconnected from the scope
    # entry (fail closed).
    if region.kind == RegionKind.TRANSPARENT_SCOPE:
        scope = builder.add(NodeKind.BARRIER_SCOPE, region.span)
        admitted = (region.span.start, region.span.end) in builder.admitted_transparent
        scope_firsts, scope_lasts, scope_dangling = _build_regions(
            builder, list(region.children), True, loop_ctx, admitted
        )
        _ = scope_firsts
        if not admitted:
            for last in scope_lasts:
                builder.edge(EdgeKind.UNKNOWN, last, scope)
            return ([scope], [], [(EdgeKind.UNKNOWN, scope)] + scope_dangling)
        for first in scope_firsts:
            builder.edge(EdgeKind.NORMAL, scope, first)
        return (
            [scope],
            [],
            [(EdgeKind.NORMAL, last) for last in scope_lasts] + scope_dangling,
        )

    kind = _REGION_NODE_KIND.get(region.kind)
    if kind is None:
        if region.kind in (RegionKind.BLOCK, RegionKind.WHEN_BRANCH):
            # Structural wrappers: the children carry the flow.
            return _build_regions(
                builder, list(region.children), in_lambda, loop_ctx, in_transparent
            )
        return ([], [], [])

    if region.kind == RegionKind.IF:
        branch = builder.add(kind, region.span)
        dangling: list[tuple[EdgeKind, str]] = []
        lasts: list[str] = []
        for child_index, child in enumerate(region.children):
            child_firsts, child_lasts, child_dangling = _build_regions(
                builder, [child], in_lambda, loop_ctx, in_transparent
            )
            edge_kind = EdgeKind.TRUE_BRANCH if child_index == 0 else EdgeKind.FALSE_BRANCH
            for first in child_firsts:
                builder.edge(edge_kind, branch, first)
            lasts.extend(child_lasts)
            dangling.extend(child_dangling)
        if len(region.children) < 2:
            dangling.append((EdgeKind.FALSE_BRANCH, branch))
        return ([branch], lasts, dangling)

    if region.kind == RegionKind.WHEN:
        when_node = builder.add(kind, region.span)
        dangling = []
        lasts = []
        for child in region.children:
            child_firsts, child_lasts, child_dangling = _build_regions(
                builder, [child], in_lambda, loop_ctx, in_transparent
            )
            for first in child_firsts:
                builder.edge(EdgeKind.WHEN_BRANCH, when_node, first)
            lasts.extend(child_lasts)
            dangling.extend(child_dangling)
        return ([when_node], lasts, dangling)

    if region.kind == RegionKind.LOOP:
        header = builder.add(kind, region.span)
        inner_ctx = {"header": header}
        body_firsts, body_lasts, body_dangling = _build_regions(
            builder, list(region.children), in_lambda, inner_ctx, in_transparent
        )
        for first in body_firsts:
            builder.edge(EdgeKind.LOOP_BODY, header, first)
        for last in body_lasts:
            builder.edge(EdgeKind.NORMAL, last, header)
        _ = body_dangling
        return ([header], [], [(EdgeKind.LOOP_EXIT, header)])

    if region.kind == RegionKind.TRY:
        try_node = builder.add(kind, region.span)
        lasts: list[str] = []
        catch_nodes: list[str] = []
        finally_node: str | None = None
        body_firsts: list[str] = []
        for child in region.children:
            if child.kind == RegionKind.TRY:
                body_firsts, body_lasts, body_dangling = _build_regions(
                    builder, list(child.children), in_lambda, loop_ctx, in_transparent
                )
                for first in body_firsts:
                    builder.edge(EdgeKind.NORMAL, try_node, first)
                lasts.extend(body_lasts)
                _ = body_dangling
            elif child.kind == RegionKind.CATCH:
                catch_node = builder.add(NodeKind.CATCH, child.span)
                catch_nodes.append(catch_node)
                builder.edge(EdgeKind.EXCEPTION, try_node, catch_node)
                catch_firsts, catch_lasts, catch_dangling = _build_regions(
                    builder, list(child.children), in_lambda, loop_ctx, in_transparent
                )
                for first in catch_firsts:
                    builder.edge(EdgeKind.NORMAL, catch_node, first)
                lasts.extend(catch_lasts)
                _ = catch_dangling
            elif child.kind == RegionKind.FINALLY:
                finally_node = builder.add(NodeKind.FINALLY, child.span)
                fin_firsts, fin_lasts, fin_dangling = _build_regions(
                    builder, list(child.children), in_lambda, loop_ctx, in_transparent
                )
                for first in fin_firsts:
                    builder.edge(EdgeKind.NORMAL, finally_node, first)
                lasts.extend(fin_lasts)
                _ = fin_dangling
        for node in [try_node] + catch_nodes:
            if finally_node is not None:
                builder.edge(EdgeKind.FINALLY, node, finally_node)
        return ([try_node], lasts, [])

    if region.kind == RegionKind.BARRIER_SCOPE:
        scope = builder.add(kind, region.span)
        scope_firsts, scope_lasts, scope_dangling = _build_regions(
            builder, list(region.children), True, loop_ctx, False
        )
        _ = scope_firsts
        for last in scope_lasts:
            builder.edge(EdgeKind.UNKNOWN, last, scope)
        return ([scope], [], [(EdgeKind.UNKNOWN, scope)] + scope_dangling)

    if region.kind == RegionKind.BLOCK:
        return _build_regions(builder, list(region.children), in_lambda, loop_ctx, in_transparent)

    if region.kind == RegionKind.RETURN:
        if region.children:
            # `return try/if/when ...`: the wrapped construct carries the
            # flow; its normal completion is the return.
            child_firsts, child_lasts, child_dangling = _build_regions(
                builder, list(region.children), in_lambda, loop_ctx, in_transparent
            )
            return (
                child_firsts,
                [],
                [(EdgeKind.RETURN_EXIT, last) for last in child_lasts]
                + list(child_dangling),
            )
        node = builder.add(kind, region.span)
        if in_transparent:
            # A bare `return` inside an admitted transparent scope is
            # lambda-local: hand the exit to the scope continuation.
            return ([node], [], [(EdgeKind.RETURN_EXIT, node)])
        builder.edge(EdgeKind.RETURN_EXIT, node, builder.ensure_exit_normal())
        return ([node], [], [])

    if region.kind == RegionKind.THROW:
        node = builder.add(kind, region.span)
        builder.edge(EdgeKind.THROW_EXIT, node, builder.ensure_exit_exceptional())
        return ([node], [], [])

    if region.kind == RegionKind.BREAK:
        node = builder.add(kind, region.span)
        # The loop join is not materialized in GR-11; a break targets the
        # normal exit — a conservative over-approximation that adds no
        # entry->mutation path (GR-12 only reasons over those).
        builder.edge(EdgeKind.LOOP_EXIT, node, builder.ensure_exit_normal())
        return ([node], [], [])

    if region.kind == RegionKind.CONTINUE:
        node = builder.add(kind, region.span)
        if loop_ctx is not None and "header" in loop_ctx:
            builder.edge(EdgeKind.NORMAL, node, loop_ctx["header"])
        return ([node], [], [])

    if region.kind == RegionKind.STATEMENT and region.children:
        # A construct-valued local declaration (`val x = when { ... }`):
        # the wrapped construct carries the flow.
        return _build_regions(builder, list(region.children), in_lambda, loop_ctx, in_transparent)

    node = builder.add(kind, region.span)
    return ([node], [node], [])


def build_callable_cfg(
    parse: CallableBodyParse,
    mutation_sites: tuple[MutationSite, ...] | list[MutationSite],
    barrier_markers: tuple[BarrierMarker, ...] | list[BarrierMarker],
    *,
    path: str,
    callable_key: str,
    admitted_transparent_spans: frozenset = frozenset(),
) -> tuple[ControlFlowGraph, tuple[StructuralDiagnostic, ...]]:
    """Build the CFG for one SUPPORTED parsed callable body.

    ``admitted_transparent_spans`` holds the (start, end) spans of
    contract-admitted TRANSPARENT_SCOPE regions (exact receiver/import
    resolution happens in the proof layer, never here).  Non-admitted
    candidates build as disconnected scopes — fail closed.

    Returns (graph, diagnostics).  Raises ValueError when an internal graph
    invariant fails — the caller classifies that as INFRASTRUCTURE_FAILURE,
    never as an unsupported-source result.
    """
    if parse.unsupported:
        raise ValueError("cannot build a CFG for an unsupported callable body")
    if not isinstance(admitted_transparent_spans, frozenset):
        raise TypeError("admitted_transparent_spans must be a frozenset")
    builder = _Builder(0, parse.body_span)
    builder.admitted_transparent = admitted_transparent_spans
    diagnostics: list[StructuralDiagnostic] = []
    entry = builder.add(
        NodeKind.ENTRY,
        SourceSpan(
            start=parse.body_span.start,
            end=parse.body_span.start,
            line=parse.body_span.line,
            column=parse.body_span.column,
        ),
    )
    firsts, lasts, dangling = _build_regions(builder, list(parse.regions), False, None)
    for first in firsts:
        builder.edge(EdgeKind.NORMAL, entry, first)
    exit_normal = builder.ensure_exit_normal()
    for last in lasts:
        builder.edge(EdgeKind.NORMAL, last, exit_normal)
    for edge_kind, source in dangling:
        builder.edge(edge_kind, source, exit_normal)
    for site in mutation_sites:
        _attach_site(
            builder,
            site.span.start,
            site.span.end,
            NodeKind.MUTATION,
            diagnostics,
            "DB_STRUCTURAL_MODEL_MUTATION_SITE_UNRESOLVED",
            path,
            site.span.line,
        )
    for marker in barrier_markers:
        if (marker.span.start, marker.span.end) in builder.node_by_span:
            continue
        _attach_site(
            builder,
            marker.span.start,
            marker.span.end,
            NodeKind.BARRIER_CALL,
            diagnostics,
            "DB_STRUCTURAL_MODEL_BARRIER_FORM_UNRECOGNIZED",
            path,
            marker.span.line,
        )
    graph = ControlFlowGraph(
        callable_key=callable_key,
        nodes=tuple(builder.nodes),
        edges=tuple(builder.edges),
    )
    return graph, tuple(diagnostics)
