"""GR-12 proof-to-D4 bridge: the single shared direct-barrier proof core.

PR-GR-12 Step 7 replaces the legacy lexical
``writeBarrier``-text-earlier-in-the-callable authorization with the shared
GR-11/GR-12 CFG dominance proof.  This module is the ONE proof pipeline every
control plane consumes:

  * the D4 scanner's active direct-mode authorization gate,
  * the v2 policy source-evidence ``barrierMode`` consistency gate,
  * the shadow comparison CLI (``inspect_direct_write_barrier_proof.py``),

and — transitively through those — the normal DB CLI report, the ratchet
child report, the static suite, the Gradle DB task, and GATE-00R captures.
A second regex/lexical barrier proof is never used for authorization after
activation (plan artifact 3: "Proof-to-D4 bridge").

The proof core is deterministic and fails closed:

  * every consumer drives the SAME ``prove_direct_barrier`` engine with the
    SAME ``CANONICAL_BARRIER_CONTRACT_V2``;
  * a mutation with no exact per-site proof result is ``UNSUPPORTED``
    (``DB_DIRECT_BARRIER_PROOF_UNSUPPORTED``), never a silent pass;
  * infrastructure failures surface as ``DB_DIRECT_BARRIER_CONTRACT_INVALID``
    blocking diagnostics, never as findings or clean results;
  * proof inputs/outputs carry repository-relative paths and bounded
    identifiers/line numbers only — no raw source, SQL, or absolute paths.
"""
from __future__ import annotations

from dataclasses import dataclass, field

from ..kotlin_callable_parser import mask_kotlin_source
from .structural_analysis.barrier_proof import (
    CANONICAL_BARRIER_CONTRACT_V2,
    DirectBarrierProofResult,
    ProofStatus,
    ReceiverTypeResolver,
    _PROOF_VERSION,
    _mutation_identity,
    admit_transparent_scope_candidates,
    canonical_barrier_call_sites,
    prove_direct_barrier,
)
from .structural_analysis.barrier_markers import collect_barrier_markers
from .structural_analysis.cfg import build_callable_cfg
from .structural_analysis.model import MutationSite, SourceSpan
from .structural_analysis.shadow_report import _default_opacity_predicate
from .structural_analysis.tokenizer import parse_callable_body
from .mediation_analysis.callgraph import PRODUCTION_TRANSPARENT_INLINE_METHODS

__all__ = [
    "CallableDirectBarrierProof",
    "callable_body_span",
    "mutation_sites_from_observations",
    "prove_callable_direct_barriers",
    "prove_evidence_callable",
]

#: GR-14u56c reviewed OWNED-receiver spellings for the `async` inline
#: carrier (name-exact, closed set).  Evidence standard identical to the
#: GR-14j structured-launch receivers: a class-owned CoroutineScope
#: property tied to its owner's lifetime.  Empty today — the production
#: census found ZERO receiver-qualified `async` calls (all 25 sites are
#: receiverless `async {`); any future receiver form must be added here
#: after its own reviewed census, never by widening the gate.
_PRODUCTION_ASYNC_OWNED_RECEIVERS: tuple[str, ...] = ()

#: Sentinel for the carrier-span walk: "this region is gated OUT".
_SKIP = object()


def _unsupported_result(site: MutationSite, callable_key: str) -> DirectBarrierProofResult:
    return DirectBarrierProofResult(
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


def _infrastructure_results(
    sites: tuple[MutationSite, ...], callable_key: str
) -> tuple[DirectBarrierProofResult, ...]:
    return tuple(
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
        for site in sites
    )


@dataclass
class CallableDirectBarrierProof:
    """Per-callable proof bundle keyed by exact mutation-site start offset.

    ``result_for_site_start`` is the only lookup consumers may use: a site
    without an exact per-site result fails closed to the callable-wide
    infrastructure failure when one exists, otherwise to a synthesized
    ``UNSUPPORTED`` result.  It can never return ``PROVEN`` by omission.
    """

    results: tuple[DirectBarrierProofResult, ...] = ()
    callable_wide_failure: DirectBarrierProofResult | None = None
    has_canonical_barrier: bool = False
    # Start offsets of every canonical barrier call site in the body, computed
    # from masked text alone (no CFG).  Available even when the body cannot be
    # modeled, where it is the only evidence that a guard exists at all
    # (GR-14u15).  NOT a proof: presence never implies dominance.
    barrier_call_offsets: tuple[int, ...] = ()
    diagnostics: tuple[str, ...] = ()
    _by_site_start: dict[int, DirectBarrierProofResult] = field(
        default_factory=dict, init=False, repr=False, compare=False
    )

    def __post_init__(self) -> None:
        self._by_site_start = {
            result.mutation_site.start: result for result in self.results
        }

    def result_for_site_start(self, site_start: int) -> DirectBarrierProofResult:
        result = self._by_site_start.get(site_start)
        if result is not None:
            return result
        if self.callable_wide_failure is not None:
            return self.callable_wide_failure
        return DirectBarrierProofResult(
            callable_key="",
            mutation_key="",
            status=ProofStatus.UNSUPPORTED,
            proof_version=_PROOF_VERSION,
            mutation_site=SourceSpan(start=0, end=0, line=1, column=1),
            barrier_site=None,
            barrier_form=None,
            counterexample_node_kinds=(),
            counterexample_line_sequence=(),
            diagnostic_code="DB_DIRECT_BARRIER_PROOF_UNSUPPORTED",
        )


def callable_body_span(masked: str, declaration) -> SourceSpan | None:
    """Exact modelable body span of one production declaration, or None.

    Prefers the declaration scanner's exact braced-body offsets; falls back
    to the captured-expression start for expression-bodied declarations.
    ``None`` means the callable has no conservatively modelable body span
    (the proof layer answers UNSUPPORTED, never a guessed pass).
    """
    body_start = getattr(declaration, "body_start", None)
    body_end = getattr(declaration, "body_end", None)
    if body_start is not None and body_end is not None:
        start, end = body_start, body_end
    else:
        source_start = getattr(declaration, "source_start", None)
        source_end = getattr(declaration, "source_end", None)
        if source_start is None or source_end is None:
            return None
        # Lazy import: inspect_db_structural_model imports the scanner, and
        # the scanner imports this bridge — a module-level import here would
        # be a circular-import failure at scan start.
        from ..ci.inspect_db_structural_model import _expression_body_start

        expression_start = _expression_body_start(masked, source_start, source_end)
        if expression_start is None:
            return None
        start, end = expression_start, source_end
    return SourceSpan(
        start=start,
        end=end,
        line=masked.count("\n", 0, start) + 1,
        column=1,
    )


def mutation_sites_from_observations(
    observations,
) -> tuple[MutationSite, ...]:
    """Deterministic MutationSite tuple from D4-resolved observations."""
    sites = [MutationSite.from_observation(item) for item in observations]
    sites.sort(key=lambda site: (site.span.start, site.span.end))
    return tuple(sites)


def prove_callable_direct_barriers(
    masked: str,
    body_span: SourceSpan | None,
    mutation_sites,
    *,
    path: str,
    callable_key: str,
    opacity_sites=None,
    receiver_hints: dict[str, str] | None = None,
) -> CallableDirectBarrierProof:
    """Prove every mutation site of one callable; never raises.

    Runs the exact GR-11/GR-12 pipeline: opacity gate -> body parse ->
    barrier markers -> receiver resolution + transparent-scope admission ->
    CFG construction -> ``prove_direct_barrier``.  All sites of the callable
    must be passed: a lambda hiding any other row's mutation must never be
    modeled opaque, so partial site sets are caller bugs, not optimizations.

    ``opacity_sites`` optionally overrides which sites drive the opacity
    predicate (the proof itself still runs over every ``mutation_sites`` entry).
    The mediation caller passes the callable's REAL mutation sites here while
    still passing its pseudo-sites in ``mutation_sites`` so results exist for
    their offsets: a pseudo-site is a call-edge offset, not a mutation, and
    letting it force a lambda to be modeled can flip the whole callable to
    UNSUPPORTED and hide a dominating barrier (GR-14u13).  Defaults to
    ``mutation_sites`` — unchanged for every other caller.
    """
    sites = tuple(sorted(mutation_sites, key=lambda site: (site.span.start, site.span.end)))
    if not sites:
        return CallableDirectBarrierProof()
    if body_span is None:
        return CallableDirectBarrierProof(
            results=tuple(_unsupported_result(site, callable_key) for site in sites),
            has_canonical_barrier=False,
            diagnostics=("DB_DIRECT_BARRIER_PROOF_UNSUPPORTED",),
        )

    gate_sites = (
        sites
        if opacity_sites is None
        else tuple(sorted(opacity_sites, key=lambda site: (site.span.start, site.span.end)))
    )

    # Barrier call sites come from masked text alone, so this evidence survives
    # a body the tokenizer refuses to model.
    barrier_offsets = tuple(
        sorted(
            {
                site.span.start
                for site in canonical_barrier_call_sites(
                    masked,
                    body_span,
                    CANONICAL_BARRIER_CONTRACT_V2,
                    ReceiverTypeResolver(masked, hints=receiver_hints),
                )
            }
        )
    )

    opacity = _default_opacity_predicate(masked, body_span, gate_sites)
    parse_result = parse_callable_body(
        masked,
        body_span,
        lambda_opacity_predicate=opacity,
        transparent_scope_methods=CANONICAL_BARRIER_CONTRACT_V2.transparent_scope_methods,
        # GR-14u33: closed inline-carrier names make guarded bodies
        # parseable over ordinary stdlib lambda idioms.  Parseability only:
        # admission below still refuses every name outside the canonical
        # scope contract, so mutations inside carrier lambdas stay
        # disconnected (fail closed).
        transparent_inline_methods=PRODUCTION_TRANSPARENT_INLINE_METHODS,
    )
    if parse_result.unsupported:
        return CallableDirectBarrierProof(
            results=tuple(_unsupported_result(site, callable_key) for site in sites),
            has_canonical_barrier=bool(barrier_offsets),
            barrier_call_offsets=barrier_offsets,
            diagnostics=("DB_DIRECT_BARRIER_PROOF_UNSUPPORTED",),
        )
    markers = collect_barrier_markers(parse_result, masked)
    resolver = ReceiverTypeResolver(masked, hints=receiver_hints)
    admitted = admit_transparent_scope_candidates(
        parse_result, CANONICAL_BARRIER_CONTRACT_V2, resolver
    )

    def _walk_carrier_spans(regions, async_owned_receivers=()):
        for region in regions:
            if (
                region.kind.value == "TRANSPARENT_SCOPE"
                and (region.scope_method or "") in PRODUCTION_TRANSPARENT_INLINE_METHODS
            ):
                # GR-14u56c receiver-evidence gate: the `async` carrier joins
                # the enclosing flow only when its syntactic receiver is
                # empty (kotlinx.coroutines.async on the enclosing scope) or
                # one of the reviewed owned-receiver spellings;
                # `coroutineScope` is receiverless by nature (a
                # receiver-qualified spelling is a different, unmodeled
                # method).  Fail closed: an unreviewed receiver keeps the
                # carrier's span OUT of the admitted set, so its lambda body
                # builds as a disconnected scope.
                receiver = region.scope_receiver
                if (region.scope_method or "") == "async":
                    if receiver is not None and receiver not in async_owned_receivers:
                        receiver = _SKIP
                elif (region.scope_method or "") == "coroutineScope":
                    if receiver is not None:
                        receiver = _SKIP
                if receiver is not _SKIP:
                    yield (region.span.start, region.span.end)
            yield from _walk_carrier_spans(
                region.children, async_owned_receivers
            )

    # GR-14u33: inline-carrier regions (closed reviewed name set) execute
    # their lambda body inline, so their children join the enclosing flow.
    # This restores the pre-u33 effective semantics, where such statements
    # were modeled as opaque sequence leaves and their inner writes were
    # dominated by any preceding barrier; now the children are individually
    # modeled instead of hidden.  Name-exact from the closed set only.
    #
    # GR-14u56c receiver-evidence gate: `async` (and receiverless-by-design
    # `coroutineScope`) joined the closed set, but `async` is transparent
    # ONLY when the syntactic receiver is empty or one of the reviewed
    # owned-receiver spellings — a `GlobalScope.async { }` lambda is a
    # genuinely detached dispatch and must never join the enclosing flow.
    # Mirrors the mediation layer's `_lambda_regions` async gate.
    admitted = frozenset(
        admitted
        | set(
            _walk_carrier_spans(
                parse_result.regions,
                async_owned_receivers=_PRODUCTION_ASYNC_OWNED_RECEIVERS,
            )
        )
    )
    try:
        cfg, _cfg_diagnostics = build_callable_cfg(
            parse_result,
            sites,
            markers,
            path=path,
            callable_key=callable_key,
            admitted_transparent_spans=admitted,
        )
    except (TypeError, ValueError):
        return CallableDirectBarrierProof(
            results=_infrastructure_results(sites, callable_key),
            has_canonical_barrier=False,
            diagnostics=("DB_DIRECT_BARRIER_CONTRACT_INVALID",),
        )

    results, proof_diagnostics = prove_direct_barrier(
        masked,
        body_span,
        cfg,
        sites,
        CANONICAL_BARRIER_CONTRACT_V2,
        resolver,
        path=path,
        callable_key=callable_key,
    )
    # The engine reports whole-callable graph invariant failures as one
    # "*" result; keep it aside so per-site lookups fail closed to it.
    callable_wide_failure = next(
        (result for result in results if result.mutation_key == "*"), None
    )
    per_site_results = tuple(
        result for result in results if result.mutation_key != "*"
    )
    has_canonical_barrier = any(
        site.receiver_fqcn == CANONICAL_BARRIER_CONTRACT_V2.receiver_fqcn
        and (
            site.method in CANONICAL_BARRIER_CONTRACT_V2.direct_check_methods
            or site.method in CANONICAL_BARRIER_CONTRACT_V2.guarded_scope_methods
        )
        for site in canonical_barrier_call_sites(
            masked, body_span, CANONICAL_BARRIER_CONTRACT_V2, resolver
        )
    )
    return CallableDirectBarrierProof(
        results=per_site_results,
        callable_wide_failure=callable_wide_failure,
        has_canonical_barrier=has_canonical_barrier,
        barrier_call_offsets=barrier_offsets,
        diagnostics=tuple(proof_diagnostics),
    )


def prove_evidence_callable(
    text: str,
    decl,
    mutation_matches,
    *,
    path: str,
    callable_key: str,
) -> CallableDirectBarrierProof:
    """Proof path for the v2 policy source-evidence verifier.

    ``decl`` is a ``find_callable_declarations`` result that Stage 3 already
    gated to ``status == "RESOLVED_EXACTLY"`` with a braced body captured as
    the declaration tail slice ending at ``end_offset``.  ``mutation_matches``
    are ``_extract_mutation_matches`` dicts (body-relative ``start``/``end``
    offsets plus resolved ``dao``/``op`` identity).  Masking preserves
    offsets, so raw-text offsets are used unchanged on the masked copy.
    """
    body = decl.body if isinstance(decl.body, str) else ""
    if not body:
        return CallableDirectBarrierProof(
            diagnostics=("DB_DIRECT_BARRIER_PROOF_UNSUPPORTED",)
        )
    try:
        masked = mask_kotlin_source(text)
        body_start = decl.end_offset - len(body)
        body_span = SourceSpan(
            start=body_start,
            end=decl.end_offset,
            line=masked.count("\n", 0, body_start) + 1,
            column=1,
        )
        sites = []
        for match in mutation_matches:
            start = body_start + int(match["start"])
            end = body_start + int(match["end"])
            dao = str(match.get("dao") or "")
            operation = str(match.get("op") or "")
            sites.append(
                MutationSite(
                    span=SourceSpan(
                        start=start,
                        end=end,
                        line=masked.count("\n", 0, start) + 1,
                        column=1,
                    ),
                    callable_key=callable_key,
                    dao_fqcn=dao,
                    operation=operation,
                    # Evidence extracts the exact invoked DAO method; the
                    # inventory-derived classification is D4's input, not
                    # the evidence verifier's.
                    mutation_kind=operation,
                    source_identity=f"{dao}#{operation}",
                )
            )
    except (TypeError, ValueError):
        return CallableDirectBarrierProof(
            diagnostics=("DB_DIRECT_BARRIER_PROOF_UNSUPPORTED",)
        )
    return prove_callable_direct_barriers(
        masked,
        body_span,
        tuple(sites),
        path=path,
        callable_key=callable_key,
    )
