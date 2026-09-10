"""Shadow-only structural model (conservative, no proof claims).

GR-12 owns dominance. This is a pure model layer: immutable records only,
no parsing and no CFG construction logic.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

from .diagnostics import DIAGNOSTIC_CODES

__all__ = [
    "SourceSpan",
    "NodeKind",
    "EdgeKind",
    "BarrierMarkerKind",
    "BarrierMarker",
    "MutationSite",
    "StructuralNode",
    "StructuralEdge",
    "ControlFlowGraph",
    "SyntaxFamily",
    "StructuralDiagnostic",
    "CallableStructuralInput",
    "AnalysisStatus",
    "StructuralAnalysisResult",
]

_DRIVE_RE = re.compile(r"^[A-Za-z]:")


def _require_non_empty_str(value: object, label: str) -> str:
    if not isinstance(value, str):
        raise TypeError("%s must be a non-empty string" % (label,))
    if not value:
        raise ValueError("%s must be a non-empty string" % (label,))
    return value


def _require_repo_relative_path(value: object, label: str) -> str:
    _require_non_empty_str(value, label)
    path = value
    assert isinstance(path, str)
    if _DRIVE_RE.match(path) or path.startswith("/") or path.startswith("\\"):
        raise ValueError("%s must be repository-relative" % (label,))
    return path


def _require_non_negative_int(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError("%s must be a non-negative int" % (label,))
    if value < 0:
        raise ValueError("%s must be a non-negative int" % (label,))
    return value


def _require_positive_int(value: object, label: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise TypeError("%s must be a positive int" % (label,))
    if value < 1:
        raise ValueError("%s must be >= 1" % (label,))
    return value


class NodeKind(str, Enum):
    ENTRY = "ENTRY"
    EXIT_NORMAL = "EXIT_NORMAL"
    EXIT_EXCEPTIONAL = "EXIT_EXCEPTIONAL"
    STATEMENT = "STATEMENT"
    MUTATION = "MUTATION"
    BARRIER_CALL = "BARRIER_CALL"
    BARRIER_SCOPE = "BARRIER_SCOPE"
    BRANCH = "BRANCH"
    WHEN = "WHEN"
    LOOP_HEADER = "LOOP_HEADER"
    LOOP_BODY = "LOOP_BODY"
    TRY = "TRY"
    CATCH = "CATCH"
    FINALLY = "FINALLY"
    RETURN = "RETURN"
    THROW = "THROW"
    BREAK = "BREAK"
    CONTINUE = "CONTINUE"
    LAMBDA = "LAMBDA"
    LOCAL_FUNCTION = "LOCAL_FUNCTION"
    UNKNOWN_CONSTRUCT = "UNKNOWN_CONSTRUCT"


class EdgeKind(str, Enum):
    NORMAL = "NORMAL"
    TRUE_BRANCH = "TRUE_BRANCH"
    FALSE_BRANCH = "FALSE_BRANCH"
    WHEN_BRANCH = "WHEN_BRANCH"
    LOOP_BODY = "LOOP_BODY"
    LOOP_EXIT = "LOOP_EXIT"
    RETURN_EXIT = "RETURN_EXIT"
    THROW_EXIT = "THROW_EXIT"
    EXCEPTION = "EXCEPTION"
    FINALLY = "FINALLY"
    LAMBDA_DEFERRED = "LAMBDA_DEFERRED"
    UNKNOWN = "UNKNOWN"


class BarrierMarkerKind(str, Enum):
    DIRECT_CHECK = "DIRECT_CHECK"
    DIRECT_SCOPE = "DIRECT_SCOPE"
    WORKER_GUARD_CANDIDATE = "WORKER_GUARD_CANDIDATE"
    UNKNOWN_BARRIER_LIKE_CALL = "UNKNOWN_BARRIER_LIKE_CALL"


class SyntaxFamily(str, Enum):
    BRACED_FUNCTION = "BRACED_FUNCTION"
    IF_ELSE = "IF_ELSE"
    WHEN = "WHEN"
    LOOP = "LOOP"
    TRY_FINALLY = "TRY_FINALLY"
    NESTED_LAMBDA = "NESTED_LAMBDA"
    LOCAL_FUNCTION = "LOCAL_FUNCTION"
    EXPRESSION_BODY = "EXPRESSION_BODY"
    UNKNOWN_CONSTRUCT = "UNKNOWN_CONSTRUCT"


class AnalysisStatus(str, Enum):
    SUPPORTED = "SUPPORTED"
    UNSUPPORTED_CONSERVATIVELY = "UNSUPPORTED_CONSERVATIVELY"
    INFRASTRUCTURE_FAILURE = "INFRASTRUCTURE_FAILURE"


@dataclass(frozen=True)
class SourceSpan:
    start: int
    end: int
    line: int
    column: int

    def __post_init__(self) -> None:
        _require_non_negative_int(self.start, "SourceSpan.start")
        _require_non_negative_int(self.end, "SourceSpan.end")
        if self.end < self.start:
            raise ValueError("SourceSpan.end must not precede start")
        _require_positive_int(self.line, "SourceSpan.line")
        _require_positive_int(self.column, "SourceSpan.column")


@dataclass(frozen=True)
class BarrierMarker:
    kind: BarrierMarkerKind
    span: SourceSpan
    receiver_fqcn: str | None
    method: str

    def __post_init__(self) -> None:
        if not isinstance(self.kind, BarrierMarkerKind):
            raise TypeError("BarrierMarker.kind must be a BarrierMarkerKind")
        if not isinstance(self.span, SourceSpan):
            raise TypeError("BarrierMarker.span must be a SourceSpan")
        # GR-11 resolves no receiver types: None is the honest "unresolved"
        # receiver for every marker kind; a resolved name is optional.
        if self.receiver_fqcn is not None:
            if not isinstance(self.receiver_fqcn, str):
                raise TypeError("BarrierMarker.receiver_fqcn must be a non-empty string or None")
            if not self.receiver_fqcn:
                raise ValueError("BarrierMarker.receiver_fqcn must be a non-empty string or None")
        _require_non_empty_str(self.method, "BarrierMarker.method")


@dataclass(frozen=True)
class MutationSite:
    span: SourceSpan
    callable_key: str
    dao_fqcn: str
    operation: str
    mutation_kind: str
    source_identity: str

    def __post_init__(self) -> None:
        if not isinstance(self.span, SourceSpan):
            raise TypeError("MutationSite.span must be a SourceSpan")
        _require_non_empty_str(self.callable_key, "MutationSite.callable_key")
        _require_non_empty_str(self.dao_fqcn, "MutationSite.dao_fqcn")
        _require_non_empty_str(self.operation, "MutationSite.operation")
        _require_non_empty_str(self.mutation_kind, "MutationSite.mutation_kind")
        _require_non_empty_str(self.source_identity, "MutationSite.source_identity")

    @classmethod
    def from_observation(cls, observation) -> MutationSite:
        from scripts.db_guard.mutation_observation import MutationObservation

        if not isinstance(observation, MutationObservation):
            raise TypeError("observation must be a MutationObservation")
        span = SourceSpan(
            start=observation.source_start,
            end=observation.source_end,
            line=observation.line,
            column=observation.column,
        )
        return cls(
            span=span,
            callable_key=observation.callable_key,
            dao_fqcn=observation.dao_fqcn,
            operation=observation.operation,
            mutation_kind=observation.mutation_kind,
            source_identity=observation.source_identity,
        )


@dataclass(frozen=True)
class StructuralNode:
    id: str
    kind: NodeKind
    span: SourceSpan

    def __post_init__(self) -> None:
        _require_non_empty_str(self.id, "StructuralNode.id")
        if not isinstance(self.kind, NodeKind):
            raise TypeError("StructuralNode.kind must be a NodeKind")
        if not isinstance(self.span, SourceSpan):
            raise TypeError("StructuralNode.span must be a SourceSpan")


@dataclass(frozen=True)
class StructuralEdge:
    id: str
    kind: EdgeKind
    source_node_id: str
    target_node_id: str

    def __post_init__(self) -> None:
        _require_non_empty_str(self.id, "StructuralEdge.id")
        if not isinstance(self.kind, EdgeKind):
            raise TypeError("StructuralEdge.kind must be an EdgeKind")
        _require_non_empty_str(self.source_node_id, "StructuralEdge.source_node_id")
        _require_non_empty_str(self.target_node_id, "StructuralEdge.target_node_id")


@dataclass(frozen=True)
class ControlFlowGraph:
    callable_key: str
    nodes: tuple[StructuralNode, ...]
    edges: tuple[StructuralEdge, ...]

    def __post_init__(self) -> None:
        _require_non_empty_str(self.callable_key, "ControlFlowGraph.callable_key")
        if not isinstance(self.nodes, (tuple, list)):
            raise TypeError("ControlFlowGraph.nodes must be a tuple of StructuralNode")
        if not isinstance(self.edges, (tuple, list)):
            raise TypeError("ControlFlowGraph.edges must be a tuple of StructuralEdge")
        for node in self.nodes:
            if not isinstance(node, StructuralNode):
                raise TypeError("ControlFlowGraph.nodes must be a tuple of StructuralNode")
        for edge in self.edges:
            if not isinstance(edge, StructuralEdge):
                raise TypeError("ControlFlowGraph.edges must be a tuple of StructuralEdge")
        node_ids = [node.id for node in self.nodes]
        if len(set(node_ids)) != len(node_ids):
            raise ValueError("ControlFlowGraph node ids must be unique")
        edge_ids = [edge.id for edge in self.edges]
        if len(set(edge_ids)) != len(edge_ids):
            raise ValueError("ControlFlowGraph edge ids must be unique")
        entries = [node for node in self.nodes if node.kind == NodeKind.ENTRY]
        if len(entries) != 1:
            raise ValueError("ControlFlowGraph must contain exactly one ENTRY node")
        exits = [
            node
            for node in self.nodes
            if node.kind in (NodeKind.EXIT_NORMAL, NodeKind.EXIT_EXCEPTIONAL)
        ]
        if not exits:
            raise ValueError("ControlFlowGraph must contain at least one exit node")
        known = set(node_ids)
        for edge in self.edges:
            if edge.source_node_id not in known or edge.target_node_id not in known:
                raise ValueError("ControlFlowGraph edge references unknown node id")
        object.__setattr__(self, "nodes", tuple(sorted(self.nodes, key=lambda n: n.id)))
        object.__setattr__(self, "edges", tuple(sorted(self.edges, key=lambda e: e.id)))


@dataclass(frozen=True)
class StructuralDiagnostic:
    code: str
    path: str
    line: int
    callable_key: str | None = None
    syntax_family: SyntaxFamily | None = None

    def __post_init__(self) -> None:
        if not isinstance(self.code, str) or self.code not in DIAGNOSTIC_CODES:
            raise ValueError("StructuralDiagnostic.code must be a known code")
        _require_repo_relative_path(self.path, "StructuralDiagnostic.path")
        _require_positive_int(self.line, "StructuralDiagnostic.line")
        if self.callable_key is not None:
            _require_non_empty_str(self.callable_key, "StructuralDiagnostic.callable_key")
        if self.syntax_family is not None and not isinstance(self.syntax_family, SyntaxFamily):
            raise TypeError("StructuralDiagnostic.syntax_family must be a SyntaxFamily or None")


@dataclass(frozen=True)
class CallableStructuralInput:
    callable_key: str
    path: str
    body_span: SourceSpan
    mutation_sites: tuple[MutationSite, ...] = ()
    barrier_markers: tuple[BarrierMarker, ...] = ()

    def __post_init__(self) -> None:
        _require_non_empty_str(self.callable_key, "CallableStructuralInput.callable_key")
        _require_repo_relative_path(self.path, "CallableStructuralInput.path")
        if not isinstance(self.body_span, SourceSpan):
            raise TypeError("CallableStructuralInput.body_span must be a SourceSpan")
        if not isinstance(self.mutation_sites, (tuple, list)):
            raise TypeError("CallableStructuralInput.mutation_sites must be a tuple of MutationSite")
        if not isinstance(self.barrier_markers, (tuple, list)):
            raise TypeError("CallableStructuralInput.barrier_markers must be a tuple of BarrierMarker")
        for site in self.mutation_sites:
            if not isinstance(site, MutationSite):
                raise TypeError("CallableStructuralInput.mutation_sites must be a tuple of MutationSite")
        for marker in self.barrier_markers:
            if not isinstance(marker, BarrierMarker):
                raise TypeError("CallableStructuralInput.barrier_markers must be a tuple of BarrierMarker")
        object.__setattr__(
            self,
            "mutation_sites",
            tuple(sorted(self.mutation_sites, key=lambda s: (s.span.start, s.span.end, s.callable_key))),
        )
        object.__setattr__(
            self,
            "barrier_markers",
            tuple(sorted(self.barrier_markers, key=lambda m: (m.span.start, m.span.end, m.kind.value))),
        )

    @classmethod
    def from_observation(cls, observation) -> CallableStructuralInput:
        site = MutationSite.from_observation(observation)
        return cls(
            callable_key=observation.callable_key,
            path=observation.path,
            body_span=site.span,
            mutation_sites=(site,),
            barrier_markers=(),
        )


@dataclass(frozen=True)
class StructuralAnalysisResult:
    callable_key: str
    status: AnalysisStatus
    graph: ControlFlowGraph | None
    syntax_families: tuple[SyntaxFamily, ...] = ()
    diagnostics: tuple[StructuralDiagnostic, ...] = ()
    node_count: int = 0
    edge_count: int = 0

    def __post_init__(self) -> None:
        _require_non_empty_str(self.callable_key, "StructuralAnalysisResult.callable_key")
        if not isinstance(self.status, AnalysisStatus):
            raise TypeError("StructuralAnalysisResult.status must be an AnalysisStatus")
        if self.status == AnalysisStatus.SUPPORTED:
            if not isinstance(self.graph, ControlFlowGraph):
                raise ValueError("SUPPORTED results require a graph")
        else:
            if self.graph is not None:
                raise ValueError("non-SUPPORTED results require graph None")
        if not isinstance(self.syntax_families, (tuple, list)):
            raise TypeError("StructuralAnalysisResult.syntax_families must be a tuple of SyntaxFamily")
        if not isinstance(self.diagnostics, (tuple, list)):
            raise TypeError("StructuralAnalysisResult.diagnostics must be a tuple of StructuralDiagnostic")
        for family in self.syntax_families:
            if not isinstance(family, SyntaxFamily):
                raise TypeError("StructuralAnalysisResult.syntax_families must be a tuple of SyntaxFamily")
        for diagnostic in self.diagnostics:
            if not isinstance(diagnostic, StructuralDiagnostic):
                raise TypeError("StructuralAnalysisResult.diagnostics must be a tuple of StructuralDiagnostic")
        _require_non_negative_int(self.node_count, "StructuralAnalysisResult.node_count")
        _require_non_negative_int(self.edge_count, "StructuralAnalysisResult.edge_count")
        deduped_families = sorted(set(self.syntax_families), key=lambda f: f.value)
        object.__setattr__(self, "syntax_families", tuple(deduped_families))
        seen: dict[tuple[object, ...], StructuralDiagnostic] = {}
        for diagnostic in self.diagnostics:
            seen.setdefault(
                (
                    diagnostic.code,
                    diagnostic.path,
                    diagnostic.line,
                    diagnostic.callable_key,
                    diagnostic.syntax_family,
                ),
                diagnostic,
            )
        object.__setattr__(
            self,
            "diagnostics",
            tuple(sorted(seen.values(), key=lambda d: (d.code, d.path, d.line))),
        )
