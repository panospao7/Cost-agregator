"""GR-13 canonical worker-scope recognition and worker-root discovery.

Shadow-only (PR-GR-13, plan Step 5).  Implements the recorded contract in
``docs/ci/db-mediation/GR-13_WORKER_GUARD_CONTRACT.md``:

  * the canonical worker guard is receiver-exact — a ``runGuarded``-shaped
    call on any other receiver is never a canonical guard (enforced by the
    call-graph resolver through the analysis contract);
  * a worker root is the ``doWork`` override of a corpus class whose resolved
    supertype chain reaches a known worker base class; discovery enumerates
    the corpus, it never assumes a fixed list;
  * discovery is cross-checked against ``WorkerRegistry.kt`` (the registry's
    worker-class references resolved through its own imports); every
    mismatch is reported as a bounded diagnostic and marks the affected
    root invalid — never a silent pass;
  * a worker guard scope whose ``WorkerGuardRequest`` arguments explicitly
    declare the read-only-backup waiver (``allowDuringBackupExport = true``
    and ``requiresDatabaseWrite = false``) provides NO write context (the
    scope's write-guard precondition was waived); a checkpoint is a check,
    not a scope, and is never admitted;
  * the legacy regex script (``scripts/verify_worker_boundaries.py``) is NOT
    authority and is never consulted here.

All outputs are bounded identity strings — no raw source text.
"""
from __future__ import annotations

import re
from dataclasses import dataclass

from .callgraph import CallGraphBuilder
from .models import WorkerRoot

__all__ = [
    "WORKER_REGISTRY_RELATIVE_PATH",
    "WorkerDiscovery",
    "WorkerDiscoveryResult",
    "WorkerGuardScopeRecord",
    "discover_worker_roots",
    "enumerate_worker_guard_scopes",
    "extract_registry_worker_fqcns",
]

WORKER_REGISTRY_RELATIVE_PATH = (
    "app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRegistry.kt"
)

_REGISTRY_CLASS_REF_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\s*(?:::\s*class\b|\.\s*schedule\s*\(|\.::class)")
_SCHEDULE_REF_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\s*\.\s*schedule\s*\(")
_CLASS_java_RE = re.compile(r"\b([A-Z][A-Za-z0-9_]*)\s*::\s*class\b")


@dataclass(frozen=True)
class WorkerDiscoveryResult:
    """Bounded worker-root inventory plus registry cross-check facts."""

    roots: tuple[WorkerRoot, ...]            # per doWork override, name-sorted
    worker_classes: tuple[str, ...]          # discovered worker FQCNs (name-sorted)
    ambiguous_worker_classes: tuple[str, ...]  # duplicate-FQCN declarations
    unregistered_worker_classes: tuple[str, ...]
    registry_missing_worker_classes: tuple[str, ...]
    registry_unresolved_refs: tuple[str, ...]
    do_work_roots_registered: tuple[str, ...]
    do_work_roots_unregistered: tuple[str, ...]
    diagnostics: tuple[str, ...]


@dataclass(frozen=True)
class WorkerGuardScopeRecord:
    """One canonical worker-guard scope lambda (bounded coordinates)."""

    callable_key: str
    file: str
    line: int
    method: str
    lambda_start: int
    lambda_end: int
    waived: bool


def discover_worker_roots(
    builder: CallGraphBuilder,
    registered_worker_fqcns: tuple[str, ...] | None,
) -> WorkerDiscoveryResult:
    """Enumerate worker classes/doWork roots and cross-check the registry.

    ``registered_worker_fqcns`` is the registry-derived FQCN set (or None
    when no registry source is available — every discovered worker is then
    reported unregistered, never silently trusted).
    """
    contract = builder.contract
    worker_classes = builder.worker_classes()
    ambiguous = tuple(
        sorted(
            fqcn
            for fqcn in worker_classes
            if fqcn in builder._duplicate_owner_fqcns
        )
    )
    resolved_registry: set[str] = set()
    registry_unresolved: list[str] = []
    if registered_worker_fqcns is None:
        registry_unresolved.append("WORKER_REGISTRY_SOURCE_UNAVAILABLE")
    else:
        resolved_registry = set(registered_worker_fqcns)

    registered_set = resolved_registry
    unregistered = tuple(
        sorted(fqcn for fqcn in worker_classes if fqcn not in registered_set)
    )
    registry_missing = tuple(
        sorted(
            fqcn
            for fqcn in registered_set
            if fqcn in builder.owners and fqcn not in worker_classes
        )
    )

    diagnostics: list[str] = []
    for fqcn in ambiguous:
        diagnostics.append("GR13_WORKER_FQCN_DUPLICATE")
    for fqcn in unregistered:
        diagnostics.append("GR13_WORKER_NOT_REGISTERED")
    for fqcn in registry_missing:
        diagnostics.append("GR13_REGISTRY_ENTRY_WITHOUT_WORKER_CLASS")
    for _code in registry_unresolved:
        diagnostics.append("GR13_WORKER_REGISTRY_UNRESOLVED")

    roots: list[WorkerRoot] = []
    do_registered: list[str] = []
    do_unregistered: list[str] = []
    for key in sorted(builder.callables):
        model = builder.callables[key]
        if model.method != "doWork" or model.owner_fqcn not in set(worker_classes):
            continue
        ambiguous_root = model.owner_fqcn in set(ambiguous)
        registered = (
            model.owner_fqcn in registered_set and not ambiguous_root
        )
        roots.append(
            WorkerRoot(
                workerFqcn=model.owner_fqcn,
                doWorkKey=key,
                registered=registered,
            )
        )
        if registered:
            do_registered.append(key)
        else:
            do_unregistered.append(key)
    return WorkerDiscoveryResult(
        roots=tuple(sorted(roots, key=lambda root: root.doWorkKey)),
        worker_classes=tuple(sorted(worker_classes)),
        ambiguous_worker_classes=ambiguous,
        unregistered_worker_classes=unregistered,
        registry_missing_worker_classes=registry_missing,
        registry_unresolved_refs=tuple(sorted(set(registry_unresolved))),
        do_work_roots_registered=tuple(sorted(do_registered)),
        do_work_roots_unregistered=tuple(sorted(do_unregistered)),
        diagnostics=tuple(sorted(set(diagnostics)),
        ),
    )


def enumerate_worker_guard_scopes(
    builder: CallGraphBuilder,
) -> tuple[WorkerGuardScopeRecord, ...]:
    """Every canonical worker-guard scope lambda in the corpus (bounded)."""
    records: list[WorkerGuardScopeRecord] = []
    for key in sorted(builder.lambda_regions_by_callable):
        for region in builder.lambda_regions_by_callable[key]:
            if region.carrier != "canonical_worker":
                continue
            model = builder.callables[key]
            records.append(
                WorkerGuardScopeRecord(
                    callable_key=key,
                    file=model.file,
                    line=builder.file_models[model.file].masked.count(
                        "\n", 0, region.start
                    )
                    + 1,
                    method=region.method,
                    lambda_start=region.start,
                    lambda_end=region.end,
                    waived=region.waived,
                )
            )
    records.sort(key=lambda record: (record.file, record.lambda_start))
    return tuple(records)


def extract_registry_worker_fqcns(
    registry_text: str,
    registry_path: str,
    builder: CallGraphBuilder,
) -> tuple[tuple[str, ...], tuple[str, ...]]:
    """Worker FQCNs referenced by the registry file, via its own imports.

    Returns (resolved FQCNs, unresolved simple names).  A registry reference
    resolves only when the simple name binds to exactly one corpus class in
    the registry file's scope (its imports/package) — ambiguity fails closed
    and is reported unresolved.
    """
    registry_model = None
    for path, model in builder.file_models.items():
        if path == registry_path:
            registry_model = model
            break
    if registry_model is None:
        return (), ()
    referenced: set[str] = set()
    unresolved: set[str] = set()
    masked = registry_model.masked
    for pattern in (_SCHEDULE_REF_RE, _CLASS_java_RE):
        for match in pattern.finditer(masked):
            simple = match.group(1)
            fqcn, origin = builder._resolve_type(registry_model, simple)
            if origin == "corpus":
                referenced.add(fqcn)
            else:
                unresolved.add(simple)
    return tuple(sorted(referenced)), tuple(sorted(unresolved))
