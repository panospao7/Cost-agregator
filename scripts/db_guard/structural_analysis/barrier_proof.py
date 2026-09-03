"""GR-12 shared direct write-barrier dominance proof (shadow-proof layer).

One pure proof API consumed by BOTH the v2 source-evidence check and the D4
scanner (single proof source; no second regex proof after activation).

Proof question, per direct-mode mutation site: does a canonical barrier check
DOMINATE the mutation on the modeled CFG, or is the mutation lexically inside
a canonical guarded scope?

Outcomes (fail closed):
  PROVEN                 exact canonical barrier dominates the mutation, or
                         the mutation is inside a canonical guarded scope
  COUNTEREXAMPLE         trusted CFG with a concrete unguarded path
  UNSUPPORTED            required receiver/control-flow semantics cannot be
                         proven safely (never silently "proven")
  INFRASTRUCTURE_FAILURE policy/model invariants failed

Privacy: results carry repository-relative paths, bounded identifiers, line
numbers and node kinds only — never raw source, SQL, or absolute paths.
"""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import re

from .model import ControlFlowGraph, EdgeKind, NodeKind, SourceSpan

__all__ = [
    "CanonicalBarrierContract",
    "CANONICAL_BARRIER_CONTRACT_V1",
    "ProofStatus",
    "DirectBarrierProofResult",
    "ReceiverTypeResolver",
    "BarrierCallSite",
    "canonical_barrier_call_sites",
    "prove_direct_barrier",
]

_PROOF_VERSION = 1

_CALL_RE = re.compile(
    r"\b(?P<receiver>[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*"
    r"(?P<method>[A-Za-z_][A-Za-z0-9_]*)\s*[\(\{]"
)
_PROP_RE = re.compile(
    r"\b(?:val|var)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*(?:<[^=\n>]*>)?\s*:\s*"
    r"(?P<type>[A-Za-z_][A-Za-z0-9_.<>?,\s]+?)\s*[=\s\n)]"
)
_IMPORT_RE = re.compile(r"\bimport\s+([\w.]+)")
_PACKAGE_RE = re.compile(r"\bpackage\s+([\w.]+)")


class CanonicalBarrierContract:
    """Typed, code-owned barrier API contract (no YAML allowlist).

    Any change requires a dedicated reviewed diff updating BOTH this class
    and docs/ci/db-structural/GR-12_CANONICAL_BARRIER_API.md.
    """

    __slots__ = ("contract_version", "receiver_fqcn", "direct_check_methods", "guarded_scope_methods")

    def __init__(
        self,
        *,
        contract_version: int,
        receiver_fqcn: str,
        direct_check_methods: tuple[str, ...],
        guarded_scope_methods: tuple[str, ...],
    ) -> None:
        if not isinstance(contract_version, int) or isinstance(contract_version, bool):
            raise TypeError("contract_version must be an int")
        if not isinstance(receiver_fqcn, str) or "." not in receiver_fqcn:
            raise ValueError("receiver_fqcn must be a dotted FQCN (bare names are never barriers)")
        for group, label in (
            (direct_check_methods, "direct_check_methods"),
            (guarded_scope_methods, "guarded_scope_methods"),
        ):
            if not isinstance(group, tuple) or not group:
                raise ValueError("%s must be a non-empty tuple" % (label,))
            for item in group:
                if not isinstance(item, str) or not item or not item.isidentifier():
                    raise ValueError("%s entries must be plain identifiers" % (label,))
        for name in direct_check_methods:
            if name in guarded_scope_methods:
                raise ValueError("a method cannot be both a check and a scope")
        object.__setattr__(self, "contract_version", contract_version)
        object.__setattr__(self, "receiver_fqcn", receiver_fqcn)
        object.__setattr__(self, "direct_check_methods", tuple(direct_check_methods))
        object.__setattr__(self, "guarded_scope_methods", tuple(guarded_scope_methods))

    def __eq__(self, other) -> bool:
        return isinstance(other, CanonicalBarrierContract) and (
            self.contract_version == other.contract_version
            and self.receiver_fqcn == other.receiver_fqcn
            and self.direct_check_methods == other.direct_check_methods
            and self.guarded_scope_methods == other.guarded_scope_methods
        )

    def __hash__(self) -> int:
        return hash(
            (
                self.contract_version,
                self.receiver_fqcn,
                self.direct_check_methods,
                self.guarded_scope_methods,
            )
        )


CANONICAL_BARRIER_CONTRACT_V1 = CanonicalBarrierContract(
    contract_version=1,
    receiver_fqcn="com.yourname.expensetracker.data.backup.DatabaseWriteBarrier",
    direct_check_methods=("checkWritesAllowed",),
    guarded_scope_methods=("runWrite",),
)


class ProofStatus(str, Enum):
    PROVEN = "PROVEN"
    COUNTEREXAMPLE = "COUNTEREXAMPLE"
    UNSUPPORTED = "UNSUPPORTED"
    INFRASTRUCTURE_FAILURE = "INFRASTRUCTURE_FAILURE"


@dataclass(frozen=True)
class DirectBarrierProofResult:
    """One proof outcome for one mutation site.  Bounded fields only."""

    callable_key: str
    mutation_key: str
    status: ProofStatus
    proof_version: int
    mutation_site: SourceSpan
    barrier_site: SourceSpan | None
    barrier_form: str | None  # DIRECT_CHECK | GUARDED_SCOPE
    counterexample_node_kinds: tuple[str, ...]
    counterexample_line_sequence: tuple[int, ...]
    diagnostic_code: str | None


@dataclass(frozen=True)
class BarrierCallSite:
    """One syntactic barrier-shaped call with its resolved receiver type."""

    span: SourceSpan
    receiver_name: str
    method: str
    receiver_fqcn: str | None
    receiver_resolution: str  # RESOLVED | UNRESOLVED | AMBIGUOUS | NOT_A_PROPERTY


_RECEIVER_RESOLVED = "RESOLVED"
_RECEIVER_UNRESOLVED = "UNRESOLVED"
_RECEIVER_AMBIGUOUS = "AMBIGUOUS"
_RECEIVER_NOT_A_PROPERTY = "NOT_A_PROPERTY"


class ReceiverTypeResolver:
    """Exact receiver-FQCN resolution from file source evidence.

    Resolves a receiver simple name to its declared type FQCN using the
    file's property declarations plus the import/package tables.  A bare
    spelling is never sufficient: the property's declared type must resolve
    through an exact import (or the file's own package) to an FQCN.
    """

    def __init__(self, masked_text: str) -> None:
        self._text = masked_text
        self._declarations: dict[str, list[str]] = {}
        for match in _PROP_RE.finditer(masked_text):
            name = match.group("name")
            declared = match.group("type").strip()
            first = re.split(r"[<>,?\s]", declared)[0] if declared else ""
            if first:
                self._declarations.setdefault(name, []).append(first)
        package = _PACKAGE_RE.search(masked_text)
        self._package = package.group(1) if package else None
        imports: dict[str, set[str]] = {}
        for match in _IMPORT_RE.finditer(masked_text):
            fqn = match.group(1)
            imports.setdefault(fqn.rsplit(".", 1)[-1], set()).add(fqn)
        self._imports = imports

    def resolve(self, receiver_name: str) -> tuple[str | None, str]:
        declared = self._declarations.get(receiver_name)
        if not declared:
            return None, _RECEIVER_NOT_A_PROPERTY
        if len(set(declared)) > 1:
            return None, _RECEIVER_AMBIGUOUS
        simple = declared[0]
        if "." in simple:
            return simple, _RECEIVER_RESOLVED
        if simple in self._imports:
            candidates = self._imports[simple]
            if len(candidates) > 1:
                return None, _RECEIVER_AMBIGUOUS
            return next(iter(candidates)), _RECEIVER_RESOLVED
        if self._package:
            return self._package + "." + simple, _RECEIVER_RESOLVED
        return None, _RECEIVER_UNRESOLVED


def canonical_barrier_call_sites(
    masked_text: str,
    body_span: SourceSpan,
    contract: CanonicalBarrierContract,
    resolver: ReceiverTypeResolver,
) -> tuple[BarrierCallSite, ...]:
    """All barrier-shaped call sites in the callable body with exact
    receiver resolution.  Text comes from masked source, so comments and
    strings never produce sites."""
    sites: list[BarrierCallSite] = []
    seen: set[tuple[int, int]] = set()
    for match in _CALL_RE.finditer(masked_text, body_span.start, body_span.end):
        span = (match.start(), match.end())
        if span in seen:
            continue
        seen.add(span)
        receiver_name = match.group("receiver")
        method = match.group("method")
        if (
            method not in contract.direct_check_methods
            and method not in contract.guarded_scope_methods
        ):
            continue
        fqcn, resolution = resolver.resolve(receiver_name)
        sites.append(
            BarrierCallSite(
                span=SourceSpan(
                    start=match.start(),
                    end=match.end(),
                    line=masked_text.count("\n", 0, match.start()) + 1,
                    column=match.start() - masked_text.rfind("\n", 0, match.start()),
                ),
                receiver_name=receiver_name,
                method=method,
                receiver_fqcn=fqcn,
                receiver_resolution=resolution,
            )
        )
    sites.sort(key=lambda item: (item.span.start, item.span.end))
    return tuple(sites)


def _resolve_fqcn_or_fail(resolver: ReceiverTypeResolver, receiver_name: str):
    fqcn, resolution = resolver.resolve(receiver_name)
    if resolution == _RECEIVER_AMBIGUOUS:
        return None, "DB_DIRECT_BARRIER_RECEIVER_UNRESOLVED"
    if resolution == _RECEIVER_NOT_A_PROPERTY or fqcn is None:
        # Same-name methods on an unresolvable receiver are not barriers.
        # AMBIGUOUS already failed above; NOT_A_PROPERTY receivers cannot
        # prove canonical identity.
        return None, "DB_DIRECT_BARRIER_RECEIVER_UNRESOLVED"
    return fqcn, None


def _dominators(graph: ControlFlowGraph, entry_id: str, reachable: set[str]) -> dict[str, set[str]]:
    preds: dict[str, list[str]] = {node.id: [] for node in graph.nodes}
    for edge in graph.edges:
        if edge.target_node_id in reachable and edge.source_node_id in reachable:
            preds[edge.target_node_id].append(edge.source_node_id)
    all_ids = {node.id for node in graph.nodes if node.id in reachable}
    dom = {node_id: set(all_ids) for node_id in all_ids}
    dom[entry_id] = {entry_id}
    changed = True
    while changed:
        changed = False
        for node_id in sorted(all_ids):
            if node_id == entry_id:
                continue
            pred_list = [p for p in preds[node_id] if p in dom]
            if not pred_list:
                continue
            new_dom = set(all_ids)
            for p in pred_list:
                new_dom &= dom[p]
            new_dom.add(node_id)
            if new_dom != dom[node_id]:
                dom[node_id] = new_dom
                changed = True
    return dom


def _reachable_ids(graph: ControlFlowGraph, entry_id: str) -> set[str]:
    successors: dict[str, list[str]] = {node.id: [] for node in graph.nodes}
    for edge in graph.edges:
        successors[edge.source_node_id].append(edge.target_node_id)
    seen = {entry_id}
    frontier = [entry_id]
    while frontier:
        for target in successors[frontier.pop()]:
            if target not in seen:
                seen.add(target)
                frontier.append(target)
    return seen


def _barrier_free_path(
    graph: ControlFlowGraph,
    entry_id: str,
    target_id: str,
    blocked: set[str],
    node_by_id: dict[str, object],
) -> list[str] | None:
    """Concrete ENTRY->target path avoiding ``blocked`` nodes (BFS)."""
    successors: dict[str, list[str]] = {node.id: [] for node in graph.nodes}
    for edge in graph.edges:
        successors[edge.source_node_id].append(edge.target_node_id)
    parents: dict[str, str | None] = {entry_id: None}
    frontier = [entry_id]
    while frontier:
        current = frontier.pop(0)
        if current == target_id:
            path = []
            cursor: str | None = current
            while cursor is not None:
                path.append(cursor)
                cursor = parents[cursor]
            return list(reversed(path))
        for target in successors[current]:
            if target in blocked or target in parents:
                continue
            parents[target] = current
            frontier.append(target)
    return None


def prove_direct_barrier(
    masked_text: str,
    body_span: SourceSpan,
    cfg: ControlFlowGraph,
    mutation_sites,
    contract: CanonicalBarrierContract,
    resolver: ReceiverTypeResolver,
    *,
    path: str,
    callable_key: str,
) -> tuple[tuple[DirectBarrierProofResult, ...], tuple[str, ...]]:
    """Prove (or refute / fail) direct-barrier dominance for each site.

    Returns (results sorted by mutation identity, diagnostic codes).  Raises
    nothing: infrastructure failures are encoded in the results.
    """
    node_by_id = {node.id: node for node in cfg.nodes}
    entries = [node for node in cfg.nodes if node.kind is NodeKind.ENTRY]
    if len(entries) != 1:
        diagnostics = ("DB_DIRECT_BARRIER_CONTRACT_INVALID",)
        return (
            DirectBarrierProofResult(
                callable_key=callable_key,
                mutation_key="*",
                status=ProofStatus.INFRASTRUCTURE_FAILURE,
                proof_version=_PROOF_VERSION,
                mutation_site=body_span,
                barrier_site=None,
                barrier_form=None,
                counterexample_node_kinds=(),
                counterexample_line_sequence=(),
                diagnostic_code="DB_DIRECT_BARRIER_CONTRACT_INVALID",
            ),
            diagnostics,
        )
    entry_id = entries[0].id

    # Canonical barrier call sites, with exact receiver resolution.
    sites = canonical_barrier_call_sites(masked_text, body_span, contract, resolver)
    canonical_check_spans: list[tuple[int, int]] = []
    canonical_scope_spans: list[tuple[int, int]] = []
    receiver_unresolved = False
    for call in sites:
        if call.receiver_resolution == "AMBIGUOUS":
            receiver_unresolved = True
            continue
        if call.receiver_fqcn is None:
            # UNRESOLVED / NOT_A_PROPERTY: a barrier-shaped call whose
            # receiver cannot be proven canonical is never a barrier, and
            # per the plan the direct writer takes the exit-2 route.
            receiver_unresolved = True
            continue
        if call.receiver_fqcn != contract.receiver_fqcn:
            # Same-name method on another receiver: not a barrier.
            continue
        if call.method in contract.direct_check_methods:
            canonical_check_spans.append((call.span.start, call.span.end))
        else:
            canonical_scope_spans.append((call.span.start, call.span.end))
    if receiver_unresolved:
        results = tuple(
            DirectBarrierProofResult(
                callable_key=callable_key,
                mutation_key=_mutation_identity(site),
                status=ProofStatus.UNSUPPORTED,
                proof_version=_PROOF_VERSION,
                mutation_site=site.span,
                barrier_site=None,
                barrier_form=None,
                counterexample_node_kinds=(),
                counterexample_line_sequence=(),
                diagnostic_code="DB_DIRECT_BARRIER_RECEIVER_UNRESOLVED",
            )
            for site in mutation_sites
        )
        return results, ("DB_DIRECT_BARRIER_RECEIVER_UNRESOLVED",)

    # Map canonical barrier call spans onto CFG barrier nodes: a canonical
    # call span must fall INSIDE the node's span (the DIRECT_CHECK region or
    # the attached marker span covers the call text).
    barrier_nodes: dict[str, str] = {}  # node_id -> form
    canonical_scope_node_spans: list[tuple[int, int]] = []
    for node in cfg.nodes:
        if node.kind is NodeKind.BARRIER_CALL:
            for span_start, span_end in canonical_check_spans:
                if node.span.start <= span_start and span_end <= node.span.end:
                    barrier_nodes[node.id] = "DIRECT_CHECK"
                    break
        elif node.kind is NodeKind.BARRIER_SCOPE:
            for span_start, span_end in canonical_scope_spans:
                if node.span.start <= span_start and span_end <= node.span.end:
                    barrier_nodes[node.id] = "GUARDED_SCOPE"
                    canonical_scope_node_spans.append((node.span.start, node.span.end))
                    break

    graph_node_ids = {node.id for node in cfg.nodes}
    reachable = _reachable_ids(cfg, entry_id) & graph_node_ids
    dom = _dominators(cfg, entry_id, reachable)

    results: list[DirectBarrierProofResult] = []
    diagnostics: list[str] = []
    for site in mutation_sites:
        mutation_nodes = [
            node for node in cfg.nodes
            if node.kind is NodeKind.MUTATION and node.span.start == site.span.start
        ]
        if len(mutation_nodes) != 1:
            results.append(
                DirectBarrierProofResult(
                    callable_key=callable_key,
                    mutation_key=_mutation_identity(site),
                    status=ProofStatus.INFRASTRUCTURE_FAILURE,
                    proof_version=_PROOF_VERSION,
                    mutation_site=site.span,
                    barrier_site=None,
                    barrier_form=None,
                    counterexample_node_kinds=(),
                    counterexample_line_sequence=(),
                    diagnostic_code="DB_DIRECT_BARRIER_CONTRACT_INVALID",
                )
            )
            diagnostics.append("DB_DIRECT_BARRIER_CONTRACT_INVALID")
            continue
        mutation = mutation_nodes[0]

        if mutation.id not in reachable:
            # Disconnected subgraph = inside a guarded lambda scope (the CFG
            # never wires scope children from the scope entry).  Provable
            # only when the mutation is lexically inside a canonical
            # GUARDED_SCOPE call's full statement span.
            scope_form = _scope_containing(canonical_scope_node_spans, site.span.start)
            if scope_form == "GUARDED_SCOPE":
                scope_span = next(
                    (s for s in canonical_scope_node_spans if s[0] <= site.span.start < s[1])
                )
                results.append(
                    DirectBarrierProofResult(
                        callable_key=callable_key,
                        mutation_key=_mutation_identity(site),
                        status=ProofStatus.PROVEN,
                        proof_version=_PROOF_VERSION,
                        mutation_site=site.span,
                        barrier_site=SourceSpan(
                            start=scope_span[0],
                            end=scope_span[1],
                            line=masked_text.count("\n", 0, scope_span[0]) + 1,
                            column=1,
                        ),
                        barrier_form="GUARDED_SCOPE",
                        counterexample_node_kinds=(),
                        counterexample_line_sequence=(),
                        diagnostic_code=None,
                    )
                )
            else:
                results.append(
                    DirectBarrierProofResult(
                        callable_key=callable_key,
                        mutation_key=_mutation_identity(site),
                        status=ProofStatus.UNSUPPORTED,
                        proof_version=_PROOF_VERSION,
                        mutation_site=site.span,
                        barrier_site=None,
                        barrier_form=None,
                        counterexample_node_kinds=(),
                        counterexample_line_sequence=(),
                        diagnostic_code="DB_DIRECT_BARRIER_PROOF_UNSUPPORTED",
                    )
                )
                diagnostics.append("DB_DIRECT_BARRIER_PROOF_UNSUPPORTED")
            continue

        dominating = [
            node_id for node_id, form in barrier_nodes.items()
            if node_id in dom.get(mutation.id, ()) and node_id != mutation.id
        ]
        if dominating:
            barrier_node = node_by_id[sorted(dominating)[0]]
            results.append(
                DirectBarrierProofResult(
                    callable_key=callable_key,
                    mutation_key=_mutation_identity(site),
                    status=ProofStatus.PROVEN,
                    proof_version=_PROOF_VERSION,
                    mutation_site=site.span,
                    barrier_site=barrier_node.span,
                    barrier_form=barrier_nodes[barrier_node.id],
                    counterexample_node_kinds=(),
                    counterexample_line_sequence=(),
                    diagnostic_code=None,
                )
            )
            continue

        blocked = set(barrier_nodes)
        witness = _barrier_free_path(cfg, entry_id, mutation.id, blocked, node_by_id)
        kinds_seq: tuple[str, ...] = ()
        lines_seq: tuple[int, ...] = ()
        if witness is not None:
            kinds_seq = tuple(str(node_by_id[n].kind.value) for n in witness)
            lines_seq = tuple(
                int(masked_text.count("\n", 0, node_by_id[n].span.start) + 1)
                for n in witness
            )
            status = ProofStatus.COUNTEREXAMPLE
            diagnostic = None
        else:
            # No barrier-free path exists yet no barrier dominated: the
            # graph disagrees with the path search — internal invariant.
            status = ProofStatus.INFRASTRUCTURE_FAILURE
            diagnostic = "DB_DIRECT_BARRIER_CONTRACT_INVALID"
            diagnostics.append("DB_DIRECT_BARRIER_CONTRACT_INVALID")
        results.append(
            DirectBarrierProofResult(
                callable_key=callable_key,
                mutation_key=_mutation_identity(site),
                status=status,
                proof_version=_PROOF_VERSION,
                mutation_site=site.span,
                barrier_site=None,
                barrier_form=None,
                counterexample_node_kinds=kinds_seq,
                counterexample_line_sequence=lines_seq,
                diagnostic_code=diagnostic,
            )
        )

    results.sort(key=lambda item: (item.mutation_key, item.mutation_site.start))
    return tuple(results), tuple(sorted(set(diagnostics)))


def _mutation_identity(site) -> str:
    getter = getattr(site, "callable_key", None)
    base = getter() if callable(getter) else (getter or "")
    return (
        base
        + "|"
        + getattr(site, "dao_accessor", "")
        + "|"
        + getattr(site, "dao_fqcn", "")
        + "|"
        + getattr(site, "operation", "")
    )


def _scope_containing(scope_spans, offset: int) -> str | None:
    for span_start, span_end in scope_spans:
        if span_start <= offset < span_end:
            return "GUARDED_SCOPE"
    return None
