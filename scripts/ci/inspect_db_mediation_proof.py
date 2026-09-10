"""GR-13 helper/worker mediation proof shadow CLI (read-only, report-only).

PR-GR-13 Step 6.  For every active-policy mutation with barrierMode
``helper`` or ``workerMediated`` the CLI builds the exact interprocedural
call graph over the declared production source roots, discovers and
registry-cross-checks every worker root, propagates guard contexts over
exact synchronous edges, and classifies each row with one closed proof
state.  It NEVER modifies policy, source, baseline, or any active gate
result, and it is NOT a ratchet child — the active D4 gate still applies no
local requirement to helper/worker rows (shadow-only PR).

DIRECT context at a helper call site is decided by the SAME shared GR-12
dominance engine the active gate uses (the bridge runs per-callable with
the callable's real mutation sites plus the mediation pseudo-sites), so no
second barrier proof exists.

Exit contract (per docs/guardrails/PR-GR-13_helper_worker_mediation_proof_plan.md):
  0  every helper/worker entry PROVEN
  1  valid analysis with one or more UNPROVEN / COUNTEREXAMPLE entries
  2  infrastructure or unsupported source uncertainty (invalid policy or
     roots, a policy row with no exact D4 observation, an uncorrelatable
     subject callable, any UNSUPPORTED_SOURCE / INFRASTRUCTURE result, any
     crash)

The report is deterministic JSON: sorted entries, bounded identity strings
and line numbers, no raw source, no absolute paths, no timestamps.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys

_PROJECT_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
)
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)

from scripts.db_guard.structural_analysis.barrier_proof import (  # noqa: E402
    CANONICAL_BARRIER_CONTRACT_V2,
    ProofStatus,
)
from scripts.db_guard.declaration_scanner import (  # noqa: E402
    scan_production_declarations,
)
from scripts.db_guard.direct_barrier_bridge import (  # noqa: E402
    callable_body_span,
    mutation_sites_from_observations,
    prove_callable_direct_barriers,
)
from scripts.db_guard.mutation_observation import MutationObservation  # noqa: E402
from scripts.db_guard.policy_v2_loader import load_policy_v2  # noqa: E402
from scripts.db_guard.source_roots import (  # noqa: E402
    collect_production_kotlin_files,
    resolve_source_root_set,
)
from scripts.db_guard.scanner import scan_db_access  # noqa: E402
from scripts.ci.inspect_db_structural_model import (  # noqa: E402
    _project_root_of,
    _split_callable_key,
    _span_of,
)
from scripts.db_guard.mediation_analysis.callgraph import (  # noqa: E402
    AnalysisContract,
    CallGraphBuilder,
    PRODUCTION_TRANSPARENT_INLINE_METHODS,
)
from scripts.db_guard.mediation_analysis.models import ProofState  # noqa: E402
from scripts.db_guard.mediation_analysis.proof import (  # noqa: E402
    MediationProver,
    MutationSubject,
    worst_subject_proof,
)
from scripts.db_guard.mediation_analysis.worker_recognition import (  # noqa: E402
    WORKER_REGISTRY_RELATIVE_PATH,
    discover_worker_roots,
    extract_registry_worker_fqcns,
    parse_worker_root_dispositions,
    validate_worker_root_dispositions,
)
from scripts.kotlin_callable_parser import mask_kotlin_source  # noqa: E402

__all__ = ["build_mediation_shadow", "main"]

_EXIT_ALL_PROVEN = 0
_EXIT_UNPROVEN = 1
_EXIT_INFRASTRUCTURE = 2

_HEADER_NAME_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*\(")
_BARRIER_TEXT_RE = re.compile(r"\b(checkWritesAllowed|runWrite)\s*\(")

_WORKER_GUARD_RECEIVER_FQCN = (
    "com.yourname.expensetracker.domain.workers.WorkerExecutionGuard"
)
_WORKER_GUARD_SCOPE_METHODS = ("runGuarded", "runGuardedWithContext")
_WORKER_BASE_FQCNS = ("androidx.work.CoroutineWorker",)

#: GR-14j reviewed structured-launch receivers (name-exact, closed set).
#: Evidence (tree-wide grep, batch manifest): every `launch` on these
#: receivers targets a class-owned CoroutineScope property tied to its
#: owner's lifetime (ViewModel.viewModelScope; service/work-tracker scopes
#: in NotificationCaptureService, TransactionClassifier,
#: NotificationCaptureGate, EmailReceiptIngestionService).  Launches on
#: ANY other receiver (GlobalScope, injected dispatchers, parameters)
#: stay async-uncertain.
_PRODUCTION_STRUCTURED_LAUNCH_RECEIVERS = (
    "viewModelScope",
    "scope",
    "workTracker",
    "serviceScope",
    "diagnosticScope",
    # GR-14o additions (census-evidenced class-owned scopes):
    # AppStartupCoordinator lifecycleScope, NotificationProcessingPipeline
    # asyncScope, RecommendationLifecycleManager applicationScope.
    "lifecycleScope",
    "asyncScope",
    "applicationScope",
)


def _production_contract() -> AnalysisContract:
    """The production contract, derived from the GR-12 + GR-13 records.

    The direct-scope receiver/methods come from the immutable
    ``CANONICAL_BARRIER_CONTRACT_V2`` (single source of truth); the worker
    guard identity comes from the recorded GR-13 worker-guard contract; the
    inline-carrier table comes from the reviewed GR-14f closed set
    (``PRODUCTION_TRANSPARENT_INLINE_METHODS`` — carrier classification
    only, never an authorization source).
    """
    return AnalysisContract(
        worker_guard_receiver_fqcn=_WORKER_GUARD_RECEIVER_FQCN,
        worker_guard_scope_methods=_WORKER_GUARD_SCOPE_METHODS,
        direct_scope_receiver_fqcn=CANONICAL_BARRIER_CONTRACT_V2.receiver_fqcn,
        direct_scope_methods=CANONICAL_BARRIER_CONTRACT_V2.guarded_scope_methods,
        worker_base_fqcns=_WORKER_BASE_FQCNS,
        transparent_scope_methods=tuple(
            wrapper.method
            for wrapper in CANONICAL_BARRIER_CONTRACT_V2.transparent_scope_wrappers
        ),
        transparent_inline_methods=PRODUCTION_TRANSPARENT_INLINE_METHODS,
        structured_launch_receivers=_PRODUCTION_STRUCTURED_LAUNCH_RECEIVERS,
    )


def _sha256_of_file(path: str) -> str | None:
    try:
        with open(path, "rb") as handle:
            return hashlib.sha256(handle.read()).hexdigest()
    except OSError:
        return None


class _DirectSiteProver:
    """GR-12 dominance proofs for helper call sites (lazy, per callable).

    A call site carries DIRECT context when the shared GR-12 engine proves
    the site dominated by a canonical direct-barrier check/scope.  The proof
    consumes the callable's REAL mutation sites plus every mediation
    pseudo-site requested for that callable, so a lambda hiding a real
    mutation is never modeled opaque.  Results are recorded for BOTH sets of
    offsets: subjects query by mutation offset, edges query by name offset.
    """

    def __init__(self, builder: CallGraphBuilder, observations_by_callable) -> None:
        self._builder = builder
        self._observations = observations_by_callable
        self._requested: dict[str, set[int]] = {}
        self._results: dict[str, dict[int, bool]] = {}

    def request(self, callable_key: str, site_start: int) -> None:
        self._requested.setdefault(callable_key, set()).add(site_start)

    def _compute(self, callable_key: str) -> None:
        if callable_key in self._results:
            return
        self._results[callable_key] = {}
        model = self._builder.callables.get(callable_key)
        if model is None or model.body_start < 0:
            return
        masked = self._builder.file_models[model.file].masked
        body_span = callable_body_span(masked, model)
        if body_span is None:
            return
        sites = list(mutation_sites_from_observations(
            self._observations.get(callable_key, ())
        ))
        for offset in sorted(self._requested.get(callable_key, ())):
            sites.append(
                _pseudo_site(masked, callable_key, model.file, offset)
            )
        if not sites:
            return
        outcome = prove_callable_direct_barriers(
            masked,
            body_span,
            tuple(sites),
            path=model.file,
            callable_key=callable_key,
        )
        proven: dict[int, bool] = {}
        # Record BOTH the requested pseudo-site offsets (mediation call edges)
        # and the callable's REAL mutation-site offsets.  Mediation subjects ask
        # by ``observation.source_start`` (the mutation site), which is never an
        # edge name offset, so reading back only the requested offsets left a
        # bare dominating ``checkWritesAllowed`` invisible ("none" instead of
        # "direct") for every guarded writer whose guard is not a runWrite scope.
        offsets = set(self._requested.get(callable_key, ()))
        offsets.update(site.span.start for site in sites)
        for offset in sorted(offsets):
            result = outcome.result_for_site_start(offset)
            proven[offset] = result.status == ProofStatus.PROVEN
        self._results[callable_key] = proven

    def proven_sites(self, callable_key: str) -> dict[int, bool]:
        self._compute(callable_key)
        return self._results.get(callable_key, {})

    def as_callback(self):
        """Callback for the propagation: precomputed results only."""

        def _prove(callable_key: str, site_start: int) -> bool:
            return self._results.get(callable_key, {}).get(site_start, False)

        return _prove


def _pseudo_site(masked: str, callable_key: str, path: str, offset: int):
    from scripts.db_guard.structural_analysis.model import MutationSite, SourceSpan

    line = masked.count("\n", 0, offset) + 1
    return MutationSite(
        span=SourceSpan(start=offset, end=offset + 1, line=line, column=1),
        callable_key=callable_key,
        dao_fqcn="mediation.pseudo",
        operation="call_site",
        mutation_kind="call_site",
        source_identity="mediation.pseudo#call_site",
    )


def _correlate_subject_callable(builder: CallGraphBuilder, declaration_index, observation):
    """Exactly one graph callable containing the observed mutation offset."""
    identity = _split_callable_key(observation.callable_key)
    if identity is None:
        return None
    path, owner_fqcn, _kind, method = identity
    candidates = declaration_index.get((path, owner_fqcn, method), [])
    containing = [
        declaration
        for declaration in candidates
        if _span_of(declaration)[0] <= observation.source_start < _span_of(declaration)[1]
    ]
    if len(containing) != 1:
        return None
    declaration = containing[0]
    matches = []
    for key, model in builder.callables.items():
        if model.file != path or model.method != method or model.owner_fqcn != owner_fqcn:
            continue
        start = model.decl_start if model.decl_start is not None else -1
        end = model.decl_end if model.decl_end is not None else -1
        if start <= observation.source_start < max(end, model.body_end):
            matches.append(key)
    if len(matches) == 1:
        return matches[0]
    return None


def _observations_by_graph_callable(builder, declaration_index, observations):
    """Observations keyed by the GRAPH callable key (span-exact correlation).

    The D4 scanner's ``observation.callable_key`` spells parameter types FULLY
    QUALIFIED (``android.net.Uri``), while the graph parser normalizes them
    (``Uri``).  The mediation direct prover is keyed by the graph key, so a
    direct D4-key lookup silently disabled the GR-12 proof for most callables
    (GR-14u11 Bug 1: 157/252 observed callables) and left guarded writers at
    ``local=none``.  Correlation is offset-based, so it is exact and
    overload-safe.  Uncorrelated observations keep their D4 key (fail closed,
    never silently dropped).
    """
    by_graph_key: dict[str, list[MutationObservation]] = {}
    for observation in observations:
        graph_key = None
        if builder is not None:
            graph_key = _correlate_subject_callable(
                builder, declaration_index, observation
            )
        by_graph_key.setdefault(
            graph_key if graph_key is not None else observation.callable_key, []
        ).append(observation)
    return by_graph_key


def build_mediation_shadow(
    root: str,
    policy_path_value: str | None,
    target_sha: str | None = None,
    worker_dispositions_path_value: str | None = None,
) -> tuple[dict, int]:
    """Run the GR-13 shadow analysis; returns (report, exit_code).

    Raises nothing by contract: infrastructure failures are encoded in the
    report and the exit code.

    ``worker_dispositions_path_value`` selects the tracked worker-root
    dispositions config.  ``None`` uses the default path and tolerates its
    absence (legacy behavior); an explicit path that does not exist fails
    closed (GR13_DISPOSITION_SOURCE_UNAVAILABLE).
    """
    project_root = _project_root_of(root)
    policy_file = (
        policy_path_value
        if policy_path_value and os.path.isabs(policy_path_value)
        else os.path.join(project_root, policy_path_value or "config/guards/db_ownership_policy.yml")
    )
    worker_dispositions_file = (
        worker_dispositions_path_value
        if worker_dispositions_path_value
        and os.path.isabs(worker_dispositions_path_value)
        else os.path.join(
            project_root,
            worker_dispositions_path_value
            or "config/guards/worker_root_dispositions.yml",
        )
    )
    worker_dispositions_explicit = worker_dispositions_path_value is not None
    failure_reasons: list[str] = []

    root_set, root_diagnostics = resolve_source_root_set(project_root)
    if root_set is None or root_diagnostics:
        failure_reasons.extend(sorted({code for code, _ in root_diagnostics}))
    policy_entries = None
    if not failure_reasons:
        policy_entries, _policy_errors = load_policy_v2(policy_file)
        if policy_entries is None:
            failure_reasons.append("DB_POLICY_SOURCE_EVIDENCE_INVALID")

    helper_worker_entries = []
    if policy_entries is not None:
        helper_worker_entries = [
            entry
            for entry in policy_entries
            if entry.barrier_mode.value in ("helper", "workerMediated")
        ]

    observations: list[MutationObservation] = []
    scan_finding_count = 0
    scan_diagnostic_codes: list[str] = []
    if not failure_reasons:
        scan_result = scan_db_access(
            root,
            policy_entries,
            os.path.join(project_root, "config", "guards", "db_structural_exceptions.yml"),
            os.path.join(project_root, "config", "guards", "db_raw_query_classification.yml"),
            mutation_observation_sink=observations,
        )
        scan_finding_count = len(getattr(scan_result, "findings", ()))
        scan_diagnostic_codes = sorted(
            {diagnostic.code for diagnostic in getattr(scan_result, "diagnostics", ())}
        )

    # Mutation-key correlation (same contract as the GR-12 shadow CLI).
    obs_by_mutation_key: dict[str, list[MutationObservation]] = {}
    for observation in observations:
        mutation_key = (
            observation.callable_key
            + "|"
            + observation.dao_accessor
            + "|"
            + observation.dao_fqcn
            + "|"
            + observation.operation
        )
        obs_by_mutation_key.setdefault(mutation_key, []).append(observation)

    # Per-invocation file-text cache shared by the declaration index and the
    # graph corpus.  NOT a module global: two runs in one process (the PR-02
    # determinism contract) must never read another run's stale tree text.
    builder_file_text_cache: dict[str, str] = {}

    # Declaration index for span-containment correlation.
    declaration_index: dict[tuple[str, str, str], list] = {}
    if not failure_reasons:
        scan = scan_production_declarations(root, root_set=root_set)
        for declaration in scan.helper_ranges:
            if declaration.kind != "function" or declaration.source_start is None:
                continue
            path = declaration.path
            if path not in builder_file_text_cache:
                with open(
                    os.path.join(project_root, path), "r", encoding="utf-8"
                ) as handle:
                    builder_file_text_cache[path] = handle.read()
            masked = mask_kotlin_source(builder_file_text_cache[path])
            header_end = (
                declaration.body_start
                if declaration.body_start is not None
                else declaration.source_end
            )
            match = _HEADER_NAME_RE.search(masked[declaration.source_start:header_end])
            if not match:
                continue
            declaration_index.setdefault(
                (declaration.path, declaration.owner_fqcn, match.group(1)), []
            ).append(declaration)

    # Corpus for the exact call graph.
    builder = None
    if not failure_reasons:
        files, files_diagnostics = collect_production_kotlin_files(
            project_root, root_set
        )
        if files_diagnostics:
            failure_reasons.extend(
                sorted({code for code, _context in files_diagnostics})
            )
        else:
            corpus: dict[str, str] = {}
            for relative in files:
                path = relative
                if path not in builder_file_text_cache:
                    with open(
                        os.path.join(project_root, path), "r", encoding="utf-8"
                    ) as handle:
                        builder_file_text_cache[path] = handle.read()
                corpus[path] = builder_file_text_cache[path]
            builder = CallGraphBuilder(_production_contract(), corpus)

    graph = builder.build() if builder is not None else None

    # Worker discovery + registry cross-check + tracked dispositions.
    discovery = None
    disposition_records = ()
    disposition_error_codes: tuple[str, ...] = ()
    disposition_path_recorded = None
    if graph is not None:
        registry_path = WORKER_REGISTRY_RELATIVE_PATH
        registry_text = builder_file_text_cache.get(registry_path)
        registered_fqcns = None
        if registry_text is not None:
            registered_fqcns, _unresolved_refs = extract_registry_worker_fqcns(
                registry_text, registry_path, builder
            )
        if os.path.isfile(worker_dispositions_file):
            with open(
                worker_dispositions_file, "r", encoding="utf-8"
            ) as handle:
                disposition_records, disposition_error_codes = (
                    parse_worker_root_dispositions(handle.read())
                )
            disposition_path_recorded = os.path.relpath(
                worker_dispositions_file, project_root
            ).replace(os.sep, "/")
        elif worker_dispositions_explicit:
            disposition_error_codes = ("GR13_DISPOSITION_SOURCE_UNAVAILABLE",)
        if not disposition_error_codes and disposition_records:
            disposition_error_codes = validate_worker_root_dispositions(
                disposition_records,
                builder.worker_classes(),
                registered_fqcns,
            )
        if disposition_error_codes:
            failure_reasons.extend(disposition_error_codes)
            disposition_records = ()
        discovery = discover_worker_roots(
            builder,
            registered_fqcns,
            tuple(record.worker_fqcn for record in disposition_records),
        )

    # Subjects: every helper/worker policy row, correlated to one callable.
    # A mutation key observed at several call sites (same callable, same
    # dao accessor, same operation — e.g. retry paths that repeat one
    # insert) becomes ONE SUBJECT PER SITE; the sites are proven
    # independently and the row combines them with worst-state semantics
    # (a row is proven only when every one of its sites is proven).
    subject_groups: list[list[MutationSubject]] = []
    uncorrelated_mutation_keys: list[str] = []
    subject_callables: dict[str, str] = {}
    for entry in helper_worker_entries:
        mutation_key = entry.mutation_key().canonical_key()
        matches = obs_by_mutation_key.get(mutation_key, [])
        matches = sorted(matches, key=lambda observation: observation.source_start)
        graph_keys = [
            _correlate_subject_callable(builder, declaration_index, observation)
            for observation in matches
        ] if matches else []
        # Fail closed: every site must correlate, and all sites of one
        # mutation key must land in the same graph callable (they share the
        # exact callable identity, so a split means the correlation itself
        # is unreliable).
        correlated = (
            bool(matches)
            and all(key is not None for key in graph_keys)
            and len(set(graph_keys)) == 1
        )
        if not correlated:
            subject_groups.append(
                [
                    MutationSubject(
                        mutation_key=mutation_key,
                        callable_key=entry.callable_key().canonical_key(),
                        barrier_mode=entry.barrier_mode.value,
                        site_start=None,
                    )
                ]
            )
            uncorrelated_mutation_keys.append(mutation_key)
            continue
        graph_key = graph_keys[0]
        subject_callables[mutation_key] = graph_key
        subject_groups.append(
            [
                MutationSubject(
                    mutation_key=mutation_key,
                    callable_key=graph_key,
                    barrier_mode=entry.barrier_mode.value,
                    site_start=observation.source_start,
                    line=observation.line,
                )
                for observation in matches
            ]
        )

    # Pre-request every call site needing a GR-12 direct proof.
    #
    # The prover is keyed by the GRAPH callable key, so the observations must be
    # re-keyed off the D4 canonical key (see
    # ``_observations_by_graph_callable``).
    direct_prover = (
        _DirectSiteProver(
            builder, _observations_by_graph_callable(builder, declaration_index, observations)
        )
        if graph is not None
        else None
    )
    if graph is not None and direct_prover is not None:
        # Bounded prefilter: only callables whose masked body mentions a
        # canonical barrier method can ever gain direct context.  Every
        # exact edge of such a callable is proven, not only edges whose
        # direct target is a subject: guard context flows TRANSITIVELY
        # (caller -> intermediate helper -> subject), so restricting the
        # requests to subject-targeting edges silently under-proved the
        # intermediate hops and leaked "none" into subjects whose barrier
        # sat two or more hops upstream (GR-14e diagnosis; the
        # per-callable lazy bridge keeps the added cost bounded).
        for key, model in builder.callables.items():
            masked = builder.file_models[model.file].masked
            if model.body_start < 0 or model.body_end < 0:
                continue
            if not _BARRIER_TEXT_RE.search(masked[model.body_start:model.body_end]):
                continue
            for edge in graph.edges:
                if edge.caller_key != key or edge.uncertain:
                    continue
                direct_prover.request(key, edge.name_start)
    if direct_prover is not None:
        for key in list(direct_prover._requested):
            direct_prover.proven_sites(key)

    prover = None
    if graph is not None and discovery is not None:
        # recognized roots = registry-registered OR explicitly dispositioned
        prover = MediationProver(
            builder,
            graph,
            registered_do_work_roots=(
                frozenset(discovery.do_work_roots_registered)
                | frozenset(discovery.do_work_roots_dispositioned)
            ),
            ambiguous_worker_classes=frozenset(discovery.ambiguous_worker_classes),
            direct_site_prover=(
                direct_prover.as_callback() if direct_prover is not None else None
            ),
        )

    entry_rows: list[dict] = []
    summary_counts: dict[str, int] = {}
    subject_count = 0
    for group in subject_groups:
        subject_count += len(group)
        if prover is None:
            proof_state = ProofState.INFRASTRUCTURE_FAILURE
            proof = None
            row_key = group[0].mutation_key
            row_callable = group[0].callable_key
            row_mode = group[0].barrier_mode
        else:
            proofs = [prover.prove(subject) for subject in group]
            # One policy row per mutation key: the worst site decides the
            # row's proof state and supplies its reason/path evidence.
            proof = worst_subject_proof(proofs)
            proof_state = proof.proof_state
            row_key = proof.mutation_key
            row_callable = proof.callable_key
            row_mode = proof.barrier_mode
        state_value = proof_state.value
        summary_counts[state_value] = summary_counts.get(state_value, 0) + 1
        row = {
            "mutationKey": row_key,
            "callableKey": row_callable,
            "barrierMode": row_mode,
            "proofStatus": state_value,
        }
        if proof is not None:
            row["reasonCode"] = proof.reason_code
            row["decidingResolution"] = (
                proof.deciding_resolution.value
                if proof.deciding_resolution is not None
                else None
            )
            row["localGuard"] = proof.local_guard
            row["boundedPath"] = list(proof.bounded_path)
            row["reachingRootKinds"] = list(proof.reaching_root_kinds)
            row["convertibleToDirect"] = proof.convertible_to_direct
        entry_rows.append(row)
    entry_rows.sort(key=lambda row: row["mutationKey"])

    edge_counts: dict[str, int] = {}
    if graph is not None:
        for edge in graph.edges:
            edge_counts[edge.state.value] = edge_counts.get(edge.state.value, 0) + 1

    worker_root_inventory = []
    if discovery is not None:
        for worker_root in discovery.roots:
            worker_root_inventory.append(
                {
                    "workerFqcn": worker_root.workerFqcn,
                    "doWorkKey": worker_root.doWorkKey,
                    "registered": worker_root.registered,
                    "dispositioned": worker_root.dispositioned,
                }
            )
        registry_mismatches = sorted(
            set(discovery.unregistered_worker_classes)
            | set(discovery.ambiguous_worker_classes)
            | set(discovery.registry_missing_worker_classes)
            | set(discovery.registry_unresolved_refs)
        )
        worker_diagnostics = list(discovery.diagnostics)
    else:
        registry_mismatches = []
        worker_diagnostics = ["GR13_WORKER_DISCOVERY_UNAVAILABLE"]

    unproven_inventory = [
        row
        for row in entry_rows
        if not row["proofStatus"].startswith("proven")
    ]

    active_policy_sha = _sha256_of_file(policy_file)
    manifest_path = os.path.join(project_root, "config/guards/production_source_roots.yml")
    manifest_sha = _sha256_of_file(manifest_path)

    report = {
        "schemaVersion": 1,
        "reportOnly": True,
        "engine": "gr13-mediation-proof",
        "targetSha": target_sha,
        "activePolicySha256": active_policy_sha,
        "sourceRootManifestSha256": manifest_sha,
        "workerGuardContract": {
            "receiverFqcn": _WORKER_GUARD_RECEIVER_FQCN,
            "scopeMethods": list(_WORKER_GUARD_SCOPE_METHODS),
            "directBarrierReceiverFqcn": CANONICAL_BARRIER_CONTRACT_V2.receiver_fqcn,
        },
        "summary": {
            "helperWorkerEntryCount": len(helper_worker_entries),
            "subjectCount": subject_count,
            "uncorrelatedSubjectCount": len(uncorrelated_mutation_keys),
            "proofStates": dict(sorted(summary_counts.items())),
            "callEdgeStates": dict(sorted(edge_counts.items())),
            "workerRootCount": len(worker_root_inventory),
            "workerRootDispositionCount": len(disposition_records),
            "workerRegistryMismatches": registry_mismatches,
            "scanFindingCount": scan_finding_count,
            "scanDiagnosticCodes": scan_diagnostic_codes,
        },
        "entries": entry_rows,
        "workerRootInventory": worker_root_inventory,
        "workerRootDispositions": {
            "path": disposition_path_recorded,
            "applied": [
                {
                    "workerFqcn": record.worker_fqcn,
                    "disposition": record.disposition,
                }
                for record in disposition_records
            ],
        },
        "unprovenInventory": [
            {
                "mutationKey": row["mutationKey"],
                "proofStatus": row["proofStatus"],
                "reasonCode": row.get("reasonCode", ""),
            }
            for row in unproven_inventory
        ],
        "diagnostics": worker_diagnostics,
        "infrastructure": {"failureReasons": sorted(set(failure_reasons))},
    }

    if failure_reasons or summary_counts.get("infrastructure_failure"):
        exit_code = _EXIT_INFRASTRUCTURE
    elif summary_counts.get("unsupported_source"):
        exit_code = _EXIT_INFRASTRUCTURE
    elif unproven_inventory:
        exit_code = _EXIT_UNPROVEN
    else:
        exit_code = _EXIT_ALL_PROVEN
    return report, exit_code


def _write_report_atomic(path: str, report: dict) -> None:
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(report, handle, indent=2)
        handle.write("\n")
    os.replace(temporary, path)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="GR-13 helper/worker mediation proof shadow (read-only)."
    )
    parser.add_argument("--root", default=_PROJECT_ROOT)
    parser.add_argument("--policy", default=None)
    parser.add_argument("--worker-dispositions", default=None)
    parser.add_argument("--output", required=True)
    parser.add_argument("--target-sha", default=None)
    args = parser.parse_args(argv)

    try:
        report, exit_code = build_mediation_shadow(
            args.root,
            args.policy,
            target_sha=args.target_sha,
            worker_dispositions_path_value=args.worker_dispositions,
        )
    except Exception:  # noqa: BLE001 - the exit-2 route is the contract
        report = {
            "schemaVersion": 1,
            "reportOnly": True,
            "engine": "gr13-mediation-proof",
            "summary": {
                "helperWorkerEntryCount": 0,
                "subjectCount": 0,
                "proofStates": {"infrastructure_failure": 1},
            },
            "entries": [],
            "infrastructure": {"failureReasons": ["DB_MEDIATION_REPORT_INVALID"]},
        }
        exit_code = _EXIT_INFRASTRUCTURE
    _write_report_atomic(args.output, report)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
