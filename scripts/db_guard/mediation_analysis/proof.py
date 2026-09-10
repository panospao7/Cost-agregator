"""GR-13 mediation proof: guard-context propagation and decision trees.

Shadow-only (PR-GR-13, plan Steps 4-5 proof algorithm).  For every
helper/workerMediated policy mutation the prover decides one closed
``ProofState``:

  * helper — every production path into the mutation carries
    ``DIRECT_BARRIER`` or ``WORKER_GUARD`` context;
  * workerMediated — every production path originates at a discovered,
    registry-registered ``doWork`` root and enters a canonical
    ``WorkerExecutionGuard`` scope before the mutation.

Context model (immutable analysis facts only):

  * a canonical scope lambda (GR-12 direct barrier, or the canonical worker
    guard) contributes its context to every statement inside its span; a
    waived worker scope (read-only backup waiver) contributes none;
  * context flows through exact synchronous call edges: a callee entered
    with context ``c`` executes its body with ``c`` unless a new local
    scope changes it;
  * propagation is a worklist fixed point over ACYCLIC regions only —
    EXACT-edge recursion/SCCs are detected explicitly first and any
    subject whose ancestor closure contains an exact-edge cycle is
    ``UNPROVEN_RECURSION`` (never silently iterated); cycles formed only
    by uncertain name-matched edges are not recursion — they stop the
    proof in the uncertainty tier instead;
  * uncertain edges (interface/virtual dispatch, unresolved receivers,
    function references, async/escaping lambdas) stop the proof;
  * an unguarded resolved path from a definite non-``UNKNOWN_EXTERNAL``
    root is a counterexample; dead private entry chains are honest
    ``UNPROVEN_EXTERNAL_ENTRY`` results.

For the production tree, DIRECT context at a call site can additionally be
established by the GR-12 dominance engine (a proven
``checkWritesAllowed``/``runWrite`` context dominating the site); the prover
accepts an injected site-prover callback so the SAME shared proof engine
decides it (fixtures use none — their barrier is a scope lambda).

No raw source text is stored anywhere in the proof results.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from .callgraph import CallEdge, CallGraph, CallGraphBuilder
from .models import (
    ProofState,
    ResolutionState,
    RootKind,
)

__all__ = [
    "MutationSubject",
    "SubjectProof",
    "MediationProver",
    "worst_subject_proof",
]

_UNCERTAIN_ASYNC_STATES = frozenset({
    ResolutionState.ASYNC_DISPATCH,
    ResolutionState.ESCAPING_LAMBDA,
})
_UNCERTAIN_AMBIGUOUS_STATES = frozenset({
    ResolutionState.AMBIGUOUS_TARGET,
    ResolutionState.UNRESOLVED_TARGET,
    ResolutionState.FUNCTION_REFERENCE,
    ResolutionState.VIRTUAL_DISPATCH,
    ResolutionState.INTERFACE_DISPATCH,
    ResolutionState.UNSUPPORTED_SYNTAX,
})
_EXACT_STATES = frozenset({
    ResolutionState.EXACT_SYNCHRONOUS,
    ResolutionState.EXACT_CANONICAL_SCOPE,
})
_MAX_PATH_HOPS = 12

# Closed worst-state severity for combining one policy row's per-site
# proofs: the row is proven only when EVERY site is proven; a definite
# counterexample dominates an honest unknown (a known violation must never
# be re-labeled as mere uncertainty); unsupported source and infrastructure
# failure (the fail-closed tiers) dominate everything.
_PROOF_STATE_SEVERITY: dict[ProofState, int] = {
    ProofState.PROVEN_HELPER: 0,
    ProofState.PROVEN_WORKER_MEDIATED: 0,
    ProofState.UNPROVEN_EXTERNAL_ENTRY: 1,
    ProofState.UNPROVEN_AMBIGUOUS_CALL: 1,
    ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK: 1,
    ProofState.UNPROVEN_RECURSION: 1,
    ProofState.COUNTEREXAMPLE_UNGUARDED_CALL_PATH: 2,
    ProofState.COUNTEREXAMPLE_NON_WORKER_ROOT: 2,
    ProofState.COUNTEREXAMPLE_OUTSIDE_WORKER_SCOPE: 2,
    ProofState.UNSUPPORTED_SOURCE: 3,
    ProofState.INFRASTRUCTURE_FAILURE: 4,
}


def worst_subject_proof(proofs: tuple[SubjectProof, ...] | list[SubjectProof]) -> SubjectProof:
    """The worst of one policy row's per-site proofs (ties: first site).

    Deterministic: a strictly more severe state replaces the current worst,
    so among equal states the earliest site (callers pass proofs in site
    order) decides the row's reason/path evidence.
    """
    if not proofs:
        raise ValueError("worst_subject_proof requires at least one proof")
    worst = proofs[0]
    for proof in proofs[1:]:
        if _PROOF_STATE_SEVERITY[proof.proof_state] > _PROOF_STATE_SEVERITY[worst.proof_state]:
            worst = proof
    return worst


@dataclass(frozen=True)
class MutationSubject:
    """One policy mutation to prove: its callable and exact site evidence."""

    mutation_key: str
    callable_key: str
    barrier_mode: str  # helper | workerMediated
    site_start: int | None  # None = no exact D4 observation (fail closed)
    line: int = 0


@dataclass(frozen=True)
class SubjectProof:
    """The deterministic proof result for one mutation subject."""

    mutation_key: str
    callable_key: str
    barrier_mode: str
    proof_state: ProofState
    reason_code: str
    deciding_resolution: ResolutionState | None
    local_guard: str  # none|direct|worker|waived|uncertain:<state>
    bounded_path: tuple[str, ...] = ()   # callable keys, root-first, bounded
    reaching_root_kinds: tuple[str, ...] = ()
    convertible_to_direct: bool = False
    registered_root: bool = False


@dataclass
class _NodeFacts:
    """Per-callable propagation facts (bounded, deterministic)."""

    entry_contexts: set[str] = field(default_factory=set)
    # One deterministic predecessor per delivered context: ctx -> (caller, edge)
    predecessor: dict[str, tuple[str, CallEdge]] = field(default_factory=dict)


class MediationProver:
    """Propagates guard contexts over the exact graph and proves subjects."""

    def __init__(
        self,
        builder: CallGraphBuilder,
        graph: CallGraph,
        registered_do_work_roots: frozenset[str] = frozenset(),
        ambiguous_worker_classes: frozenset[str] = frozenset(),
        direct_site_prover=None,
    ) -> None:
        self.builder = builder
        self.graph = graph
        self.registered_do_work_roots = frozenset(registered_do_work_roots)
        self.ambiguous_worker_classes = frozenset(ambiguous_worker_classes)
        self._direct_site_prover = direct_site_prover
        self._reverse_exact: dict[str, list[CallEdge]] = {}
        self._reverse_all: dict[str, list[CallEdge]] = {}
        for edge in graph.edges:
            for target in edge.targets:
                self._reverse_all.setdefault(target, []).append(edge)
                if not edge.uncertain:
                    self._reverse_exact.setdefault(target, []).append(edge)
        for target in self._reverse_all:
            self._reverse_all[target].sort(
                key=lambda edge: (edge.caller_key, edge.file, edge.name_start)
            )
        for target in self._reverse_exact:
            self._reverse_exact[target].sort(
                key=lambda edge: (edge.caller_key, edge.file, edge.name_start)
            )
        self._reachability: set[str] = set()
        self._facts: dict[str, _NodeFacts] = {}
        self._ancestor_root_cache: dict[str, tuple[str, ...]] = {}
        # Recursion is an EXACT-edge property: uncertain name-matched edges
        # can close false cycles, and those cases belong to the uncertainty
        # tier, not here.
        self._cyclic = frozenset(graph.scc_exact.cyclic_keys)
        self._compute_propagation()

    # ── local site context ──

    def _local_site_context(self, callable_key: str, site_start: int) -> tuple[str, ResolutionState | None]:
        """(context, uncertain-state) at one site, innermost lambda first.

        Context: none|direct|worker (a waived worker scope contributes none
        but is reported via the returned waiver flag through context
        ``waived``→none and a dedicated marker in ``local_guard`` strings).
        """
        regions = self.builder.lambda_regions_by_callable.get(callable_key, ())
        def _walk(region_list):
            best = None
            for region in region_list:
                if region.start <= site_start < region.end:
                    if best is None or region.start > best.start:
                        best = region
            if best is None:
                return "none", None, False
            if best.carrier == "canonical_worker":
                if best.waived:
                    return "none", None, True
                return "worker", None, False
            if best.carrier == "canonical_direct":
                return "direct", None, False
            if best.carrier == "transparent":
                rest = tuple(r for r in region_list if r is not best)
                return _walk(rest)
            if best.carrier == "escaping":
                return "none", ResolutionState.ESCAPING_LAMBDA, False
            if best.carrier == "async":
                return "none", ResolutionState.ASYNC_DISPATCH, False
            if best.carrier == "unresolved_scope":
                return "none", ResolutionState.UNRESOLVED_TARGET, False
            return "none", None, False
        context, uncertain, waived = _walk(regions)
        if context == "none" and uncertain is None and not waived:
            if self._direct_site_prover is not None and self._direct_site_prover(
                callable_key, site_start
            ):
                context = "direct"
        return context, uncertain, waived

    # ── propagation ──

    def _compute_propagation(self) -> None:
        """Worklist fixed point over acyclic regions; roots enter with none."""
        adjacency: dict[str, list[CallEdge]] = {}
        for edge in self.graph.edges:
            if edge.uncertain or edge.caller_key in self._cyclic:
                continue
            for target in edge.targets:
                if target in self.builder.callables and target not in self._cyclic:
                    adjacency.setdefault(edge.caller_key, []).append(
                        CallEdge(
                            caller_key=edge.caller_key,
                            state=edge.state,
                            line=edge.line,
                            file=edge.file,
                            name_start=edge.name_start,
                            targets=(target,),
                            context_kind=edge.context_kind,
                        )
                    )
        for caller in adjacency:
            adjacency[caller].sort(
                key=lambda edge: (edge.file, edge.name_start)
            )
        facts: dict[str, _NodeFacts] = {
            key: _NodeFacts() for key in self.builder.callables
        }
        worklist: list[str] = []
        for key, kind in sorted(self.graph.roots.items()):
            if key in self._cyclic:
                continue
            node = facts[key]
            if "none" not in node.entry_contexts:
                node.entry_contexts.add("none")
                worklist.append(key)
        # Deterministic worklist: pop smallest key with pending change.
        pending = set(worklist)
        while pending:
            key = min(pending)
            pending.discard(key)
            caller_facts = facts[key]
            for edge in adjacency.get(key, ()):
                for target in edge.targets:
                    local, _uncertain, _waived = self._edge_local_context(edge)
                    delivered: set[str]
                    if local == "none":
                        delivered = set(caller_facts.entry_contexts)
                    elif local == "direct":
                        delivered = {"direct"}
                    else:
                        delivered = {"worker"}
                    target_facts = facts[target]
                    changed = False
                    for ctx in sorted(delivered):
                        if ctx not in target_facts.entry_contexts:
                            target_facts.entry_contexts.add(ctx)
                            if ctx not in target_facts.predecessor:
                                target_facts.predecessor[ctx] = (key, edge)
                            changed = True
                    if changed:
                        pending.add(target)
        self._facts = facts
        # Reachability from roots over ALL edges (uncertainty included).
        reach: set[str] = set()
        stack = [key for key in sorted(self.graph.roots) if key not in self._cyclic]
        reach.update(stack)
        reverse_all: dict[str, list[str]] = {}
        for edge in self.graph.edges:
            for target in edge.targets:
                if target in self.builder.callables:
                    reverse_all.setdefault(edge.caller_key, []).append(target)
        while stack:
            key = stack.pop()
            for target in reverse_all.get(key, ()):
                if target not in reach and target not in self._cyclic:
                    reach.add(target)
                    stack.append(target)
        self._reachability = reach

    def _edge_local_context(self, edge: CallEdge) -> tuple[str, ResolutionState | None, bool]:
        """(local context, uncertainty, waived) at one edge's call site."""
        site = edge.name_start
        context, uncertain = "none", None
        waived = False
        regions = self.builder.lambda_regions_by_callable.get(edge.caller_key, ())
        def _walk(region_list):
            nonlocal context, uncertain, waived
            best = None
            for region in region_list:
                if region.start <= site < region.end:
                    if best is None or region.start > best.start:
                        best = region
            if best is None:
                context = "none"
                return
            if best.carrier == "canonical_worker":
                if best.waived:
                    waived = True
                    context = "none"
                else:
                    context = "worker"
                return
            if best.carrier == "canonical_direct":
                context = "direct"
                return
            if best.carrier == "transparent":
                _walk(tuple(r for r in region_list if r is not best))
                return
            context = "none"
            return
        _walk(regions)
        if context == "none" and not waived and self._direct_site_prover is not None:
            if self._direct_site_prover(edge.caller_key, site):
                context = "direct"
        return context, uncertain, waived

    # ── ancestor roots ──

    def _exact_ancestor_root_kinds(self, callable_key: str) -> tuple[str, ...]:
        """Root kinds reaching the callable over exact edges (definite)."""
        cached = self._ancestor_root_cache.get(callable_key)
        if cached is not None:
            return cached
        seen: set[str] = set()
        stack = [callable_key]
        kinds: set[str] = set()
        while stack:
            key = stack.pop()
            if key in seen:
                continue
            seen.add(key)
            if key in self.graph.roots:
                kinds.add(self.graph.roots[key].value)
            for edge in self._reverse_exact.get(key, ()):
                if edge.caller_key not in seen:
                    stack.append(edge.caller_key)
        result = tuple(sorted(kinds))
        self._ancestor_root_cache[callable_key] = result
        return result

    def _ancestor_closure(self, callable_key: str) -> set[str]:
        """All callables that can reach the key over ANY edge (incl. uncertain)."""
        seen: set[str] = {callable_key}
        stack = [callable_key]
        while stack:
            key = stack.pop()
            for edge in self._reverse_all.get(key, ()):
                if edge.caller_key not in seen:
                    seen.add(edge.caller_key)
                    stack.append(edge.caller_key)
        return seen

    def _reconstruct_path(self, callable_key: str, context: str) -> tuple[str, ...]:
        """Bounded root-first callable path delivering ``context``."""
        keys: list[str] = [callable_key]
        current = callable_key
        hops = 0
        while hops < _MAX_PATH_HOPS:
            facts = self._facts.get(current)
            if facts is None:
                break
            pred = facts.predecessor.get(context)
            if pred is None:
                break
            caller, _edge = pred
            if caller in keys:
                break
            keys.append(caller)
            current = caller
            hops += 1
        return tuple(reversed(keys))

    # ── subject proof ──

    def prove(self, subject: MutationSubject) -> SubjectProof:
        mode = subject.barrier_mode
        if mode not in ("helper", "workerMediated"):
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=ProofState.INFRASTRUCTURE_FAILURE,
                reason_code="GR13_UNKNOWN_BARRIER_MODE",
                deciding_resolution=None,
                local_guard="none",
            )
        # The no-observation check precedes the graph-key lookup: a policy
        # row without an exact D4 observation is honest UNSUPPORTED_SOURCE
        # regardless of whether its canonical callable key resolves to a
        # graph model (the graph key only exists for correlated subjects).
        if subject.site_start is None:
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=ProofState.UNSUPPORTED_SOURCE,
                reason_code="GR13_NO_D4_OBSERVATION",
                deciding_resolution=None,
                local_guard="none",
            )
        model = self.builder.callables.get(subject.callable_key)
        if model is None:
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=ProofState.INFRASTRUCTURE_FAILURE,
                reason_code="GR13_SUBJECT_CALLABLE_UNRESOLVED",
                deciding_resolution=None,
                local_guard="none",
            )
        if model.body_start < 0:
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=ProofState.UNSUPPORTED_SOURCE,
                reason_code="GR13_CALLABLE_BODY_UNMODELABLE",
                deciding_resolution=None,
                local_guard="none",
            )
        local, local_uncertain, local_waived = self._local_site_context(
            subject.callable_key, subject.site_start
        )
        local_guard = "waived" if local_waived else local

        # 1. Recursion: any EXACT-edge cycle in the ancestor closure stops
        # the proof (uncertain-edge cycles are uncertainty, tier 5).
        closure = self._ancestor_closure(subject.callable_key)
        if closure & self._cyclic:
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=ProofState.UNPROVEN_RECURSION,
                reason_code="GR13_RECURSIVE_CALL_REGION",
                deciding_resolution=ResolutionState.RECURSIVE_UNSUPPORTED,
                local_guard=local_guard,
            )
        # 2. Identity ambiguity (duplicate owner FQCN among subjects' roots).
        owner = model.owner_fqcn
        is_ambiguous_owner = owner in self.builder._duplicate_owner_fqcns
        if is_ambiguous_owner:
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=ProofState.UNPROVEN_AMBIGUOUS_CALL,
                reason_code="GR13_AMBIGUOUS_CALLABLE_IDENTITY",
                deciding_resolution=ResolutionState.AMBIGUOUS_TARGET,
                local_guard=local_guard,
                reaching_root_kinds=(RootKind.UNKNOWN_EXTERNAL.value,),
            )
        # Definite ancestor root kinds over exact edges (reported on every
        # outcome; doWork subjects add their own registration fact).
        ancestor_kinds = self._exact_ancestor_root_kinds(subject.callable_key)
        if mode == "workerMediated" and model.method == "doWork":
            ancestor_kinds = tuple(
                sorted(set(ancestor_kinds) | {RootKind.WORKER_DO_WORK.value})
            )
        # 3. Local uncertainty (site inside an async/escaping/unresolved lambda).
        if local_uncertain is not None:
            if local_uncertain in _UNCERTAIN_ASYNC_STATES:
                state = ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK
            else:
                state = ProofState.UNPROVEN_AMBIGUOUS_CALL
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=state,
                reason_code="GR13_SITE_INSIDE_UNRESOLVED_LAMBDA",
                deciding_resolution=local_uncertain,
                local_guard=local_guard,
                reaching_root_kinds=ancestor_kinds,
            )
        # 4. Inbound-edge facts.
        exact_in = self._reverse_exact.get(subject.callable_key, ())
        uncertain_in = [
            edge
            for edge in self._reverse_all.get(subject.callable_key, ())
            if edge.uncertain
        ]
        uncertain_reaching = list(uncertain_in)
        for edge in self.graph.edges:
            if not edge.uncertain:
                continue
            if any(target in closure for target in edge.targets):
                uncertain_reaching.append(edge)
        # Deduplicate deterministically.
        seen_edges: set[tuple] = set()
        deduped_uncertain: list[CallEdge] = []
        for edge in uncertain_reaching:
            marker = (edge.caller_key, edge.file, edge.name_start, edge.state.value)
            if marker in seen_edges:
                continue
            seen_edges.add(marker)
            deduped_uncertain.append(edge)
        deduped_uncertain.sort(
            key=lambda edge: (edge.state.value, edge.caller_key, edge.name_start)
        )
        if not exact_in and not uncertain_in:
            if mode == "workerMediated" and model.method == "doWork":
                pass  # the root path starts at doWork itself
            else:
                return SubjectProof(
                    mutation_key=subject.mutation_key,
                    callable_key=subject.callable_key,
                    barrier_mode=mode,
                    proof_state=ProofState.UNPROVEN_EXTERNAL_ENTRY,
                    reason_code="GR13_ZERO_INBOUND_CALL_SITES",
                    deciding_resolution=ResolutionState.EXTERNAL_ENTRY,
                    local_guard=local_guard,
                )
        # 5. Uncertain edges reaching the subject stop the proof.
        if deduped_uncertain:
            first = deduped_uncertain[0]
            if first.state in _UNCERTAIN_ASYNC_STATES:
                state = ProofState.UNPROVEN_ASYNC_OR_ESCAPING_CALLBACK
            elif first.state in _UNCERTAIN_AMBIGUOUS_STATES:
                state = ProofState.UNPROVEN_AMBIGUOUS_CALL
            else:
                state = ProofState.UNPROVEN_AMBIGUOUS_CALL
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=state,
                reason_code="GR13_UNCERTAIN_CALL_EDGE",
                deciding_resolution=first.state,
                local_guard=local_guard,
                reaching_root_kinds=ancestor_kinds,
            )
        # 6. No exact production path (S empty) — honest external entry.
        subject_facts = self._facts.get(subject.callable_key)
        entry_contexts: set[str] = set()
        if subject_facts is not None:
            entry_contexts = set(subject_facts.entry_contexts)
        if not entry_contexts and not (
            mode == "workerMediated" and model.method == "doWork"
        ):
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=ProofState.UNPROVEN_EXTERNAL_ENTRY,
                reason_code="GR13_NO_EXACT_PRODUCTION_PATH",
                deciding_resolution=ResolutionState.EXTERNAL_ENTRY,
                local_guard=local_guard,
            )
        # 7. Effective context at the mutation per path.
        effective: set[str] = set()
        if local == "direct":
            effective = {"direct"}
        elif local == "worker":
            effective = {"worker"}
        else:
            effective = set(entry_contexts)
        convertible = local == "direct" and mode == "helper" and "none" in entry_contexts
        # 8. doWork roots that are neither registry-registered nor
        # explicitly dispositioned (tracked worker-root dispositions) are
        # definite non-worker roots (the recognition cross-check fails;
        # never silently trusted).  Ambiguous worker identity was already
        # handled by the ambiguity tier.
        if (
            mode == "workerMediated"
            and model.method == "doWork"
            and subject.callable_key not in self.registered_do_work_roots
        ):
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=ProofState.COUNTEREXAMPLE_NON_WORKER_ROOT,
                reason_code="GR13_WORKER_ROOT_NOT_REGISTERED",
                deciding_resolution=ResolutionState.EXACT_SYNCHRONOUS,
                local_guard=local_guard,
                bounded_path=(subject.callable_key,),
                reaching_root_kinds=ancestor_kinds,
            )
        non_worker_roots = tuple(
            kind
            for kind in ancestor_kinds
            if kind != RootKind.WORKER_DO_WORK.value
        )
        unknown_only = ancestor_kinds == (RootKind.UNKNOWN_EXTERNAL.value,)
        if mode == "helper":
            if "none" in effective:
                if unknown_only and ancestor_kinds:
                    return SubjectProof(
                        mutation_key=subject.mutation_key,
                        callable_key=subject.callable_key,
                        barrier_mode=mode,
                        proof_state=ProofState.UNPROVEN_EXTERNAL_ENTRY,
                        reason_code="GR13_UNGUARDED_PATH_FROM_UNKNOWN_ROOT",
                        deciding_resolution=ResolutionState.EXACT_SYNCHRONOUS,
                        local_guard=local_guard,
                        bounded_path=self._reconstruct_path(
                            subject.callable_key, "none"
                        ),
                        reaching_root_kinds=ancestor_kinds,
                        convertible_to_direct=convertible,
                    )
                return SubjectProof(
                    mutation_key=subject.mutation_key,
                    callable_key=subject.callable_key,
                    barrier_mode=mode,
                    proof_state=ProofState.COUNTEREXAMPLE_UNGUARDED_CALL_PATH,
                    reason_code="GR13_UNGUARDED_CALL_PATH",
                    deciding_resolution=ResolutionState.EXACT_SYNCHRONOUS,
                    local_guard=local_guard,
                    bounded_path=self._reconstruct_path(
                        subject.callable_key, "none"
                    ),
                    reaching_root_kinds=ancestor_kinds,
                    convertible_to_direct=convertible,
                )
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=ProofState.PROVEN_HELPER,
                reason_code="GR13_ALL_PATHS_GUARDED",
                deciding_resolution=ResolutionState.EXACT_CANONICAL_SCOPE,
                local_guard=local_guard,
                bounded_path=self._reconstruct_path(
                    subject.callable_key,
                    "worker" if "worker" in effective else "direct",
                ),
                reaching_root_kinds=ancestor_kinds,
            )
        # workerMediated mode.
        if non_worker_roots:
            deciding = ResolutionState.EXACT_SYNCHRONOUS
            if non_worker_roots == (RootKind.UNKNOWN_EXTERNAL.value,) and ancestor_kinds:
                state = ProofState.UNPROVEN_EXTERNAL_ENTRY
                reason = "GR13_UNGUARDED_PATH_FROM_UNKNOWN_ROOT"
            else:
                state = ProofState.COUNTEREXAMPLE_NON_WORKER_ROOT
                reason = "GR13_NON_WORKER_ROOT_PATH"
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=state,
                reason_code=reason,
                deciding_resolution=deciding,
                local_guard=local_guard,
                bounded_path=self._reconstruct_path(
                    subject.callable_key, "none"
                ),
                reaching_root_kinds=ancestor_kinds,
            )
        if "none" in effective or "direct" in effective or not effective:
            deciding = ResolutionState.EXACT_CANONICAL_SCOPE if (
                local_waived or local == "direct"
            ) else ResolutionState.EXACT_SYNCHRONOUS
            return SubjectProof(
                mutation_key=subject.mutation_key,
                callable_key=subject.callable_key,
                barrier_mode=mode,
                proof_state=ProofState.COUNTEREXAMPLE_OUTSIDE_WORKER_SCOPE,
                reason_code="GR13_MUTATION_OUTSIDE_WORKER_SCOPE",
                deciding_resolution=deciding,
                local_guard=local_guard,
                bounded_path=self._reconstruct_path(
                    subject.callable_key, "none" if "none" in effective else "direct"
                ),
                reaching_root_kinds=ancestor_kinds,
            )
        return SubjectProof(
            mutation_key=subject.mutation_key,
            callable_key=subject.callable_key,
            barrier_mode=mode,
            proof_state=ProofState.PROVEN_WORKER_MEDIATED,
            reason_code="GR13_ALL_PATHS_WORKER_GUARDED",
            deciding_resolution=ResolutionState.EXACT_CANONICAL_SCOPE,
            local_guard=local_guard,
            bounded_path=self._reconstruct_path(subject.callable_key, "worker"),
            reaching_root_kinds=ancestor_kinds,
            registered_root=True,
        )

