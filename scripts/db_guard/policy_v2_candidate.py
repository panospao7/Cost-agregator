"""Machine generation of schema-valid v2 DB ownership policy candidates
from legacy v1 entries (PR-GR-02).

This module converts each legacy v1 policy entry into either a
``ResolvedRow`` carrying a schema-valid v2 :class:`PolicyEntry` candidate,
or an ``UnresolvedRow`` carrying one closed migration status plus a bounded
detail string.  It is row-level evidence machinery only:

* it NEVER activates policy — candidates are inert data until a human
  reviews them and writes a v2 document that passes the ordinary loader,
  evidence verification, and review gates;
* unresolved rows are visible debt, NOT authorization — nothing is
  authorized by failing to migrate an entry;
* no legacy field may leak into emitted entries — v2 candidates are built
  exclusively from explicit v2 fields derived from source evidence, never
  by copying legacy payloads through.

Every outcome is expressed inside the closed ``MIGRATION_STATUSES``
vocabulary; unknown reason codes cannot be constructed because
``UnresolvedRow`` fails closed on any status outside the set.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Iterable

try:  # package mode: imported as ``scripts.db_guard.policy_v2_candidate``
    from ..db_policy_signature import SignatureError, normalize_type_text
    from ..kotlin_callable_parser import (
        CallableDeclaration,
        OwnerDeclaration,
        find_callable_declarations,
        find_owner_declarations,
        resolve_callable,
    )
except ImportError:  # pragma: no cover - flat mode: standalone tools put ``scripts`` on sys.path
    from db_policy_signature import SignatureError, normalize_type_text
    from kotlin_callable_parser import (
        CallableDeclaration,
        OwnerDeclaration,
        find_callable_declarations,
        find_owner_declarations,
        resolve_callable,
    )

from .policy_model import PolicyEntry

__all__ = [
    "STATUS_RESOLVED",
    "STATUS_OWNER_MISSING",
    "STATUS_OWNER_AMBIGUOUS",
    "STATUS_CALLABLE_MISSING",
    "STATUS_CALLABLE_AMBIGUOUS",
    "STATUS_CALLABLE_KIND_UNSUPPORTED",
    "STATUS_DAO_IDENTITY_UNRESOLVED",
    "STATUS_DAO_TARGET_AMBIGUOUS",
    "STATUS_MUTATION_PAIR_MISSING",
    "STATUS_BARRIER_MODE_UNRESOLVED",
    "MIGRATION_STATUSES",
    "MAX_UNRESOLVED_DETAIL_LENGTH",
    "ResolvedRow",
    "UnresolvedRow",
    "MigrationResult",
    "convert_barrier_mode",
    "resolve_owner",
    "resolve_callable_for_entry",
    "ACCOUNTING_SCHEMA_NAME",
    "ACCOUNTING_SCHEMA_VERSION",
    "ACCOUNTING_OUTCOMES",
    "OUTCOME_RESOLVED",
    "OUTCOME_UNRESOLVED",
    "ACCOUNTING_ACTIONS",
    "ACTION_EMIT_CANDIDATE",
    "ACTION_REVIEW_DEBT",
    "SOURCE_MUTATION_COVERAGE_KINDS",
    "COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW",
    "COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY",
    "COVERAGE_OBSERVED_BUT_UNRESOLVED",
    "COVERAGE_UNRESOLVED_ANALYZER_INPUT",
    "AccountingRecord",
    "SourceMutationCoverage",
    "AccountingArtifact",
    "build_accounting_artifact",
    "production_source_manifest_digest",
]

# ── Closed migration status vocabulary ────────────────────────────────────────
# Plain string constants; the set is closed.  Reason-code fields must contain
# members of MIGRATION_STATUSES only — never free-form text.

STATUS_RESOLVED = "RESOLVED"
STATUS_OWNER_MISSING = "OWNER_MISSING"
STATUS_OWNER_AMBIGUOUS = "OWNER_AMBIGUOUS"
STATUS_CALLABLE_MISSING = "CALLABLE_MISSING"
STATUS_CALLABLE_AMBIGUOUS = "CALLABLE_AMBIGUOUS"
STATUS_CALLABLE_KIND_UNSUPPORTED = "CALLABLE_KIND_UNSUPPORTED"
STATUS_DAO_IDENTITY_UNRESOLVED = "DAO_IDENTITY_UNRESOLVED"
STATUS_DAO_TARGET_AMBIGUOUS = "DAO_TARGET_AMBIGUOUS"
STATUS_MUTATION_PAIR_MISSING = "MUTATION_PAIR_MISSING"
STATUS_BARRIER_MODE_UNRESOLVED = "BARRIER_MODE_UNRESOLVED"

MIGRATION_STATUSES = frozenset(
    {
        STATUS_RESOLVED,
        STATUS_OWNER_MISSING,
        STATUS_OWNER_AMBIGUOUS,
        STATUS_CALLABLE_MISSING,
        STATUS_CALLABLE_AMBIGUOUS,
        STATUS_CALLABLE_KIND_UNSUPPORTED,
        STATUS_DAO_IDENTITY_UNRESOLVED,
        STATUS_DAO_TARGET_AMBIGUOUS,
        STATUS_MUTATION_PAIR_MISSING,
        STATUS_BARRIER_MODE_UNRESOLVED,
    }
)

# Hard upper bound for ``UnresolvedRow.detail``; details carry counts and
# controlled codes only, so this bound is generous and never a reason to
# truncate structured context.
MAX_UNRESOLVED_DETAIL_LENGTH = 200


# ── Row model ─────────────────────────────────────────────────────────────────


@dataclass(frozen=True)
class ResolvedRow:
    """One legacy entry fully migrated into a schema-valid v2 candidate."""

    index: int
    entry: PolicyEntry


@dataclass(frozen=True)
class UnresolvedRow:
    """One legacy entry that could not be migrated — visible debt only.

    ``status`` must be a member of ``MIGRATION_STATUSES`` (and never
    carries authorization); ``detail`` is bounded structured context
    (counts / controlled codes), never raw payloads.
    """

    index: int
    legacy_class: str
    legacy_method: str
    status: str
    detail: str

    def __post_init__(self) -> None:
        # Widened (append-only contract) to MIGRATION_STATUSES_EXTENDED below:
        # a pure superset adding only controlled constants (PARSER_UNCERTAIN
        # plus the PR-GR-05 Slice 2 / Slice 5 plan-required statuses appended
        # beneath the frozen block).
        if self.status not in MIGRATION_STATUSES_EXTENDED:
            raise ValueError("unresolved row status outside MIGRATION_STATUSES")
        if len(self.detail) > MAX_UNRESOLVED_DETAIL_LENGTH:
            raise ValueError("unresolved row detail exceeds length bound")


@dataclass(frozen=True)
class MigrationResult:
    """Outcome of migrating a batch of legacy entries, row by row.

    ``emission_indices`` (PR-GR-05 Slice 4, refined in Slice 5) is the
    dedupe crosswalk: an ascending, key-sorted tuple of ``(canonical
    mutation key, source legacy indices)`` pairs recording EVERY legacy
    index whose emission produced that key — including indices whose
    emission was folded into the lowest-index row under the refined
    one-entry-per-key contract (same canonical key AND the same
    authorization metadata ``(barrierMode, owner, linkedIssue)``;
    free-text ``reason`` differences fold away) and therefore has no
    :class:`ResolvedRow` of its own.  A GENUINE authorization-metadata
    conflict (same key, differing barrierMode/owner/linkedIssue) emits
    NOTHING: every participating index becomes an
    ``AUTHORIZATION_METADATA_CONFLICT`` debt row and the conflicted key
    never enters the crosswalk.  Results built without the crosswalk
    (direct constructions) leave the default empty tuple, and every
    consumer falls back to deriving per-index keys from ``resolved``
    alone.
    """

    resolved: tuple[ResolvedRow, ...]
    unresolved: tuple[UnresolvedRow, ...]
    input_count: int
    emission_indices: tuple[tuple[str, tuple[int, ...]], ...] = ()

    def __post_init__(self) -> None:
        if not isinstance(self.emission_indices, tuple):
            raise ValueError("emission indices must be a tuple of pairs")
        seen_keys: set = set()
        for pair in self.emission_indices:
            if not isinstance(pair, tuple) or len(pair) != 2:
                raise ValueError(
                    "emission indices must be (key, indices) pairs"
                )
            key, indices = pair
            if not isinstance(key, str) or not key or key in seen_keys:
                raise ValueError(
                    "emission index keys must be unique non-empty strings"
                )
            seen_keys.add(key)
            if not isinstance(indices, tuple) or any(
                isinstance(i, bool) or not isinstance(i, int)
                for i in indices
            ):
                raise ValueError(
                    "emission index sources must be tuples of integers"
                )
            if list(indices) != sorted(set(indices)):
                raise ValueError(
                    "emission index sources must be ascending and unique"
                )
        keys = [pair[0] for pair in self.emission_indices]
        if keys != sorted(keys):
            raise ValueError("emission indices must be sorted by key")


# ── Barrier-mode conversion ───────────────────────────────────────────────────


def convert_barrier_mode(entry_mapping, has_direct_barrier=None) -> tuple[
    str | None, str | None
]:
    """Convert legacy ``barrier_required``/``barrier_via`` fields to a v2
    barrierMode string under the closed evidence-aware rule (PR-GR-05
    Slice 2).

    Returns ``(mode, status)`` where exactly one element is ``None`` and
    ``mode`` — when present — is one of the closed v2 ``BarrierMode``
    strings (``"direct"``, ``"helper"``, ``"workerMediated"``).  The mode
    is METADATA ONLY: it records how the legacy row classified its own
    write protection and is never presented as control-flow proof.

    Classification table (exact legacy shapes only; every other shape
    fails closed as visible debt):

    * non-mapping input, a missing/non-bool ``barrier_required``, or a
      present-but-malformed ``barrier_via`` (non-string, empty, or
      whitespace-only) -> ``(None, STATUS_BARRIER_MODE_UNRESOLVED)``;
    * non-empty ``barrier_via`` string together with
      ``barrier_required is True`` -> unresolved: the legacy truthfulness
      rule — mediation and a direct-barrier-required claim cannot both be
      true;
    * non-empty ``barrier_via`` string referencing
      ``WorkerExecutionGuard`` with ``barrier_required is False``
      -> ``("workerMediated", None)`` — the exact legacy worker-mediated
      classification (the shape every mediated row of the active policy
      uses);
    * any other non-empty ``barrier_via`` string with
      ``barrier_required is False`` -> ``("helper", None)``;
    * no usable ``barrier_via`` with ``barrier_required is False``
      -> ``("helper", None)`` — the exact legacy helper classification
      (write protection via a private helper, not a direct barrier call);
    * no usable ``barrier_via`` with ``barrier_required is True``
      -> decided by ``has_direct_barrier``: ``True`` means exact local
      direct-barrier syntax was proven before every resolved mutation and
      yields ``("direct", None)``; ``False`` disproves it and downgrades
      the row to ``("helper", None)``; ``None`` (no evidence available)
      leaves ``(None, STATUS_BARRIER_MODE_UNRESOLVED)`` instead of
      inventing a mode.

    ``has_direct_barrier`` is optional precomputed evidence produced by the
    shared scanner machinery (:func:`policy_parsing._barrier_before_line`
    semantics over the resolved callable body and mutation positions — see
    :func:`_callable_direct_barrier_evidence`); this function never
    interprets source text itself.
    """
    required = entry_mapping.get("barrier_required") if isinstance(entry_mapping, Mapping) else None
    via = entry_mapping.get("barrier_via") if isinstance(entry_mapping, Mapping) else None
    if not isinstance(required, bool):
        # Missing or non-boolean: never an exact legacy shape.
        return (None, STATUS_BARRIER_MODE_UNRESOLVED)
    if via is not None:
        if not isinstance(via, str) or not via.strip():
            # Present but malformed: fail closed instead of guessing.
            return (None, STATUS_BARRIER_MODE_UNRESOLVED)
        if required:
            # Mediation claim + direct-barrier-required claim cannot both
            # be true (legacy truthfulness rule).
            return (None, STATUS_BARRIER_MODE_UNRESOLVED)
        if "WorkerExecutionGuard" in via:
            return ("workerMediated", None)
        return ("helper", None)
    if required:
        # Direct claim: only source-evidence proof may emit "direct";
        # disproof downgrades to helper; no evidence stays debt.
        if has_direct_barrier is True:
            return ("direct", None)
        if has_direct_barrier is False:
            return ("helper", None)
        return (None, STATUS_BARRIER_MODE_UNRESOLVED)
    return ("helper", None)


def _barrier_mode_needs_source_evidence(entry_mapping) -> bool:
    """True for the single :func:`convert_barrier_mode` shape that source
    evidence can still resolve: a REAL ``barrier_required=True`` with NO
    ``barrier_via`` value (absent key or explicit null).  Every other
    unresolved shape is contradictory/malformed metadata and stays debt
    regardless of what the source shows.
    """
    if not isinstance(entry_mapping, Mapping):
        return False
    if entry_mapping.get("barrier_required") is not True:
        return False
    return entry_mapping.get("barrier_via") is None


# ── Owner resolution ──────────────────────────────────────────────────────────


def resolve_owner(
    masked_text: str,
    legacy_class: str,
    path_label: str,
) -> tuple[OwnerDeclaration | None, str | None, str | None]:
    """Resolve exactly one owner declaration for a legacy class name.

    Owners come from ``find_owner_declarations(masked_text)``.  When
    ``legacy_class`` is dotted it is matched against the full parsed owner
    FQCN; otherwise it is matched against the simple name
    (``o.owner.rsplit(".", 1)[-1]``).  Selection is all-or-nothing:

    * zero matches  -> ``(None, STATUS_OWNER_MISSING, None)``;
    * many matches  -> ``(None, STATUS_OWNER_AMBIGUOUS, "count=N")`` —
      a bounded count detail; candidates are never disambiguated by
      first/last selection;
    * exactly one   -> ``(declaration, None, declaration.owner)``.

    ``path_label`` is accepted for call-site symmetry with the row driver
    and is not interpreted here.  Parser failures propagate as controlled
    ``ParserError`` codes so callers can record debt per row.
    """
    if not isinstance(legacy_class, str) or not legacy_class:
        raise TypeError("legacy_class must be a non-empty string")
    declarations = find_owner_declarations(masked_text)
    if "." in legacy_class:
        matches = [o for o in declarations if o.owner == legacy_class]
    else:
        matches = [
            o for o in declarations if o.owner.rsplit(".", 1)[-1] == legacy_class
        ]
    if not matches:
        return (None, STATUS_OWNER_MISSING, None)
    if len(matches) == 1:
        owner = matches[0]
        return (owner, None, owner.owner)
    return (None, STATUS_OWNER_AMBIGUOUS, "count=%d" % len(matches))


# ── Callable resolution ───────────────────────────────────────────────────────


def resolve_callable_for_entry(
    owner_decl: OwnerDeclaration,
    masked_text: str,
    method: str,
    receiver: str | None,
    parameter_types: Iterable[str],
) -> tuple[CallableDeclaration | None, str | None]:
    """Resolve exactly one member ``fun`` declaration for a legacy entry.

    Kind gate: the shared parser discovers only member ``fun``
    declarations, so this resolver can only ever support the FUNCTION
    kind; constructors and property accessors simply never match and
    surface as missing rather than being fabricated.

    Identity discovery: the emitted identity is ALWAYS the selected
    declaration's own parser-discovered signature (owner FQCN, receiver,
    ordered parameter types).  The legacy ``receiver``/``parameters``
    fields are HINTS ONLY — they may disambiguate true overloads but are
    never copied into the emitted candidate and can never fabricate a
    match:

    * candidates come from ``find_callable_declarations(masked_text,
      owner_decl, tolerate_unresolved_types=True)`` filtered to
      ``signature.function_name == method`` with declaration status
      ``RESOLVED_EXACTLY``; sibling declarations retained under the
      parser's tolerant ``TYPE_UNRESOLVED`` status (PR-GR-05 Slice 3)
      never become candidates and never create overload ambiguity;
    * a non-null legacy receiver hint narrows candidates to those whose
      normalized receiver equals it (None-safe: null-receiver funs cannot
      match a non-null hint);
    * zero candidates with a same-name declaration retained under the
      tolerant ``TYPE_UNRESOLVED`` status
                                        -> ``(None,
      STATUS_PARSER_UNSUPPORTED)`` — the target callable exists but its
      own signature types cannot be resolved exactly, so the row becomes
      explicit debt instead of silent success or whole-owner
      PARSER_UNCERTAIN;
    * zero candidates otherwise           -> ``(None,
      STATUS_CALLABLE_MISSING)`` — including non-``fun`` kinds, which the
      parser never discovers;
    * exactly one candidate               -> ``(declaration, None)`` even
      when the legacy hints are empty or wrong: its own parsed signature
      is authoritative;
    * several candidates (true overloads) -> the parameter hint must pick
      EXACTLY ONE declaration by normalized ordered-parameter equality
      (``allow_vararg=True``); picking zero or several leaves ``(None,
      STATUS_CALLABLE_AMBIGUOUS)``.  First/last selection never happens,
      so sibling-overload bodies are never used for mutation evidence —
      only the single-declaration or hint-disambiguated callable is;
    * a legacy hint that cannot be normalized at all fails closed as
      ``(None, STATUS_CALLABLE_KIND_UNSUPPORTED)`` instead of being
      silently ignored or raised past the per-row debt boundary.
    """
    params_in = tuple(parameter_types)
    try:
        recv_norm = (
            normalize_type_text(receiver) if receiver is not None else None
        )
        params_norm = tuple(
            normalize_type_text(p, allow_vararg=True) for p in params_in
        )
    except (SignatureError, TypeError):
        # An un-normalizable legacy hint can never prove an exact match;
        # fail closed rather than ignoring input.
        return (None, STATUS_CALLABLE_KIND_UNSUPPORTED)
    discovered = find_callable_declarations(
        masked_text, owner_decl, tolerate_unresolved_types=True
    )
    decls = [
        d
        for d in discovered
        if d.signature.function_name == method
        and d.status == "RESOLVED_EXACTLY"
    ]
    if receiver is not None:
        decls = [
            d
            for d in decls
            if d.signature.receiver is not None
            and normalize_type_text(d.signature.receiver) == recv_norm
        ]
    if len(decls) == 1:
        # Single same-name member fun: its OWN parsed signature is the
        # emitted identity; legacy hints are neither required nor copied.
        return (decls[0], None)
    if len(decls) > 1:
        # True same-name overloads: only an exact normalized parameter match
        # may select one (parser-emitted parameter_types are already
        # normalized by FunctionSignature, so equality is normalized-equality).
        if params_in:
            matches = [
                d for d in decls if d.signature.parameter_types == params_norm
            ]
            if len(matches) == 1:
                return (matches[0], None)
        return (None, STATUS_CALLABLE_AMBIGUOUS)
    # Zero exactly-resolved candidates.  A same-name declaration retained
    # under the tolerant type-resolution status means the target callable
    # exists but its own signature types cannot be resolved EXACTLY (name +
    # receiver + ordered normalized parameter types all resolvable): name
    # that explicitly instead of silent success or generic missing debt.
    if any(
        d.signature.function_name == method and d.status == "TYPE_UNRESOLVED"
        for d in discovered
    ):
        return (None, STATUS_PARSER_UNSUPPORTED)
    return (None, STATUS_CALLABLE_MISSING)


# ── Append-only status vocabulary extension (PR-GR-02) ───────────────────────
#
# New statuses are NEVER inserted next to the original constants above; the
# original block is frozen.  Extensions live at this append point only.

STATUS_PARSER_UNCERTAIN = "PARSER_UNCERTAIN"

# (PR-GR-05 Slice 2) Plan-required statuses, appended at the documented
# extension point only.  SOURCE_ROOT_UNRESOLVED names a production source
# root that cannot be resolved for an entry's path (still debt-only).
# PARSER_UNSUPPORTED is emitted since PR-GR-05 Slice 3 by
# ``resolve_callable_for_entry`` when the matched callable itself carries
# the shared parser's tolerant ``TYPE_UNRESOLVED`` status: the target can
# not be proven exactly resolvable, so the row becomes explicit debt
# instead of silent success or a whole-owner PARSER_UNCERTAIN.  Adding
# them to the closed vocabulary keeps every emitter inside the same
# controlled constants instead of free-form text.
STATUS_SOURCE_ROOT_UNRESOLVED = "SOURCE_ROOT_UNRESOLVED"
STATUS_PARSER_UNSUPPORTED = "PARSER_UNSUPPORTED"

# (PR-GR-05 Slice 5) Plan-required status, appended at the documented
# extension point only.  AUTHORIZATION_METADATA_CONFLICT names a genuine
# same-key emission disagreement — differing barrierMode, owner, or
# linkedIssue across legacy rows whose emissions share one canonical
# mutation key.  Every participating index becomes debt with this status
# (bounded detail: key tail + index counts only) and NOTHING emits for the
# conflicted key; adding it to the closed vocabulary keeps the new outcome
# inside the same controlled constants instead of free-form text.
STATUS_AUTHORIZATION_METADATA_CONFLICT = "AUTHORIZATION_METADATA_CONFLICT"

#: Closed-vocabulary extension: the original migration statuses plus the
#: parser-uncertainty status used when the evidence machinery itself cannot
#: be trusted (masking failure, loader rejection, unexpected parser errors),
#: the PR-GR-05 Slice 2 plan-required statuses above, and the PR-GR-05
#: Slice 5 authorization-metadata-conflict status.  New validations use
#: this set; the original ``MIGRATION_STATUSES`` frozenset above stays
#: untouched for existing consumers, and ``UnresolvedRow`` now validates
#: against this extended set (pure widening by controlled constants — see
#: the comment at its ``__post_init__``).
MIGRATION_STATUSES_EXTENDED = MIGRATION_STATUSES | {
    STATUS_PARSER_UNCERTAIN,
    STATUS_SOURCE_ROOT_UNRESOLVED,
    STATUS_PARSER_UNSUPPORTED,
    STATUS_AUTHORIZATION_METADATA_CONFLICT,
}

# Shared-parser imports for the appended machinery only; the header import
# block above is frozen by the append-only contract.
try:  # package mode: imported as ``scripts.db_guard.policy_v2_candidate``
    from ..kotlin_callable_parser import (
        ParserError,
        canonical_source_path,
        mask_kotlin_source,
    )
except ImportError:  # pragma: no cover - flat mode: standalone tools put ``scripts`` on sys.path
    from kotlin_callable_parser import (
        ParserError,
        canonical_source_path,
        mask_kotlin_source,
    )

import os
from pathlib import Path

from .dao_accessors import find_dao_declarations
from .policy_parsing import (
    _barrier_before_line,
    _extract_mutation_matches,
    _interface_name_to_room_accessor,
    _resolve_dao_identity,
    build_class_scope_dao_var_map,
    build_dao_var_map,
)
from .policy_v2_loader import build_policy_entry
from .source_roots import (
    DB_SOURCE_ROOT_UNDECLARED,
    SourceRoot,
    SourceRootSet,
    collect_production_kotlin_files,
    is_declared_production_path,
    resolve_source_root_set,
)
from .declaration_scanner import declared_root_pairs


def _declared_relative_root_set(repo_root):
    """Resolve the effective declared production root set, re-anchored.

    Parity twin of the Slice D helpers in ``policy_v2_evidence`` /
    ``policy_legacy``: ``resolve_source_root_set`` returns manifest-declared
    roots as repository-relative POSIX paths but its implicit conventional
    fallback carries each root as an ABSOLUTE native-separator path anchored
    at its enclosing project; absolute roots are re-anchored here with the
    exact parity convention of ``declaration_scanner.declared_root_pairs``
    so membership checks and tree walks against repository-relative policy
    paths stay possible.  Returns ``(SourceRootSet, ())`` on success or
    ``(None, diagnostics)`` fail closed; an absolute root that cannot be
    anchored is dropped so none of its paths can ever authorize anything.
    """
    root_set, diagnostics = resolve_source_root_set(repo_root)
    if root_set is None or diagnostics:
        return None, diagnostics
    anchored = None
    normalized = []
    for root in root_set.roots:
        path = root.path
        if os.path.isabs(path):
            if anchored is None:
                anchored = iter(declared_root_pairs(repo_root, root_set))
            pair = next(anchored, None)
            if pair is None:
                continue
            anchor, base = pair
            try:
                path = os.path.relpath(base, anchor).replace(os.sep, "/")
            except ValueError:
                continue
        normalized.append(
            SourceRoot(module=root.module, source_set=root.source_set, path=path)
        )
    if not normalized:
        return None, (
            (DB_SOURCE_ROOT_UNDECLARED, {"reason": "no-conventional-root"}),
        )
    return SourceRootSet(roots=tuple(normalized)), ()


# ── DAO FQCN index ────────────────────────────────────────────────────────────


def build_dao_fqcn_index(repo_root) -> dict[str, tuple[str, ...]]:
    """Index every Room DAO interface under ``repo_root``'s declared
    production tree.

    Resolves the DECLARED production source-root set for ``repo_root``
    (the manifest-backed root set when the checked-in manifest exists, the
    implicit conventional single root otherwise), walks every declared root
    via ``collect_production_kotlin_files`` in deterministic order (manifest
    root order, each root's files sorted by repository-relative path),
    masks each ``.kt`` file before structural scanning, and resolves
    ``@Dao`` interfaces via the shared DAO scanner.  Each DAO simple name
    becomes its Room-generated accessor name (``ExpenseGroupDao`` ->
    ``expenseGroupDao``); several FQCNs can share an accessor name only when
    DAO simple names genuinely collide across packages, which downstream
    resolution reports as ambiguity instead of guessing.

    Files that cannot be read, masked, canonically pathed, or scanned are
    skipped silently and bounded (no failure accumulation, no retained
    paths, text, or exception details); a declared tree that cannot be
    walked at all yields an empty index rather than a partial one.  Returns
    ``{accessor: (fqcn, ...)}`` with sorted FQCN tuples.
    """
    root_set, _diagnostics = _declared_relative_root_set(repo_root)
    if root_set is None:
        return {}
    relative_names, walk_diagnostics = collect_production_kotlin_files(
        repo_root, root_set
    )
    if walk_diagnostics:
        # Fail closed, silently and bounded: an unreadable declared tree
        # yields no index instead of a partial one.
        return {}
    index: dict[str, set] = {}
    for relative_posix in relative_names:
        try:
            text = (
                Path(repo_root) / Path(*relative_posix.split("/"))
            ).read_text(encoding="utf-8")
            masked = mask_kotlin_source(text)
            canonical = canonical_source_path(relative_posix)
            for dao in find_dao_declarations(masked, canonical):
                simple = dao.fqcn.rsplit(".", 1)[-1]
                accessor = _interface_name_to_room_accessor(simple)
                index.setdefault(accessor, set()).add(dao.fqcn)
        except Exception:
            # Unreadable/unparseable files are skipped silently and
            # bounded: nothing about them is retained.
            continue
    return {
        accessor: tuple(sorted(fqcns))
        for accessor, fqcns in index.items()
    }


# ── Merged DAO variable maps ──────────────────────────────────────────────────


def _merged_dao_var_maps(owner_decl, decl, masked_text, all_callables) -> dict:
    """Build the merged class-scope + method-scope DAO variable map.

    Class scope: the owner slice ``masked_text[body_start:body_end]`` is
    scanned with EVERY member callable's char span excluded, mapped to
    slice-relative line indices using the GR-01 technique — newline
    counting anchored at ``owner.body_start``, clamped to the slice — plus
    ``decl``'s own span, so method-local aliases can never leak into the
    class map.  ``all_callables`` holds only the owner's DIRECT members
    (``find_callable_declarations`` skips ``fun`` declarations inside
    nested named classes/objects), so every owner declaration fully
    contained in the owner body — other than the owner itself — is
    rescanned and its callable spans join the same exclusion set, exactly
    like the fixed GR-01 evidence mapping.  Without this, a method-local
    DAO alias inside a nested owner would survive the class-scope scan and
    could overwrite a same-named class property in the DAO variable map.
    Spans are expanded in sorted offset order so the mapping stays
    deterministic; since PR-GR-05 Slice 3 inner owners are rescanned with
    ``tolerate_unresolved_types=True``, so an inner declaration with
    unresolvable project-local types still contributes its exclusion span
    instead of failing unrelated rows; any OTHER parser failure on an inner
    owner still propagates and fails closed upstream as one controlled
    ``PARSER_UNCERTAIN`` row.
    Method scope: a plain line scan of ``decl.body``.  The merged map is
    class first, updated by the method map, so the tightest scope wins.
    """
    owner_slice = masked_text[owner_decl.body_start : owner_decl.body_end]
    slice_lines = owner_slice.splitlines()
    base_line = masked_text.count("\n", 0, owner_decl.body_start)
    spans = [(c.start_offset, c.end_offset) for c in all_callables]
    spans.append((decl.start_offset, decl.end_offset))
    for inner in find_owner_declarations(masked_text):
        if inner == owner_decl:
            continue
        if (
            owner_decl.body_start <= inner.body_start
            and inner.body_end <= owner_decl.body_end
        ):
            spans.extend(
                (d.start_offset, d.end_offset)
                for d in find_callable_declarations(
                    masked_text, inner, tolerate_unresolved_types=True
                )
            )
    excluded = set()
    for start_offset, end_offset in sorted(spans):
        first = masked_text.count("\n", 0, start_offset) - base_line
        last = masked_text.count("\n", 0, end_offset - 1) - base_line
        excluded.update(
            ln for ln in range(first, last + 1) if 0 <= ln < len(slice_lines)
        )
    class_map = build_class_scope_dao_var_map(
        slice_lines,
        0,
        len(slice_lines) - 1,
        excluded_line_numbers=excluded,
    )
    method_lines = decl.body.splitlines() if isinstance(decl.body, str) else []
    method_map = build_dao_var_map(method_lines)
    merged = dict(class_map)
    merged.update(method_map)
    return merged


# ── Mutation resolution ───────────────────────────────────────────────────────


def resolve_mutations_for_callable(
    decl, owner_decl, masked_text, all_callables, dao_index
):
    """Resolve every DAO mutation pair in ``decl.body`` to concrete targets.

    Returns ``(triples, first_failure, mutation_linenos)`` where ``triples``
    is a list of deduplicated ``(accessor, fqcn, operation)`` tuples,
    ``first_failure`` is the FIRST controlled failure status encountered
    (or ``None``), and ``mutation_linenos`` is the ascending tuple of
    DISTINCT 0-based body-relative line numbers on which any DAO mutation
    call was extracted (positions preserved from the same shared extraction
    pass; consumed by :func:`_callable_direct_barrier_evidence`):

    * receiver identity neither present in ``dao_index`` nor resolvable
      through the merged DAO map      -> ``DAO_IDENTITY_UNRESOLVED``;
    * accessor mapping to several DAO FQCNs -> ``DAO_TARGET_AMBIGUOUS``.

    All pairs are examined — a failure never short-circuits emission of
    other provable triples — but the caller treats ANY failure as row debt.
    """
    merged = _merged_dao_var_maps(owner_decl, decl, masked_text, all_callables)
    body_text = decl.body if isinstance(decl.body, str) else ""
    matches = _extract_mutation_matches(body_text, var_map=merged)
    pairs = []
    pair_seen = set()
    mutation_linenos = set()
    for match in matches:
        mutation_linenos.add(match["lineno"])
        pair = (match["dao"], match["op"])
        if pair not in pair_seen:
            pairs.append(pair)
            pair_seen.add(pair)
    triples = []
    triple_seen = set()
    first_failure = None
    for identity, operation in pairs:
        accessor = (
            identity
            if identity in dao_index
            else _resolve_dao_identity(identity, merged)
        )
        if accessor is None or accessor not in dao_index:
            if first_failure is None:
                first_failure = STATUS_DAO_IDENTITY_UNRESOLVED
            continue
        fqcns = dao_index[accessor]
        if len(fqcns) > 1:
            if first_failure is None:
                first_failure = STATUS_DAO_TARGET_AMBIGUOUS
            continue
        triple = (accessor, fqcns[0], operation)
        if triple not in triple_seen:
            triple_seen.add(triple)
            triples.append(triple)
    return (triples, first_failure, tuple(sorted(mutation_linenos)))


def _callable_direct_barrier_evidence(text, decl, relative_mutation_linenos):
    """Precompute the shared scanner's direct-barrier verdict for one callable.

    Reuses :func:`policy_parsing._barrier_before_line` — the exact machinery
    behind the legacy scanner's MISSING_WRITE_BARRIER gate — so migration
    never invents a second barrier matcher.  For EVERY distinct extracted
    mutation line, the callable must show a real unqualified
    ``writeBarrier.checkWritesAllowed(...)`` / ``writeBarrier.runWrite(...)``
    call strictly between the fun declaration line and that mutation line
    (statefully comment/string-masked, receiver-aware).  Returns ``True``
    only when every mutation is so preceded; any disproof — or an empty
    position set, or a bodyless declaration — returns ``False`` so a
    ``barrier_required=true`` row can never claim ``direct`` without full
    per-mutation proof.

    ``text`` is the full raw source text the declaration offsets refer to;
    ``relative_mutation_linenos`` are 0-based line indices within
    ``decl.body`` as reported by :func:`resolve_mutations_for_callable`.
    Masking preserves offsets and newline positions, so counting newlines
    over either the raw or the masked text yields identical line numbers.
    """
    body = decl.body if isinstance(decl.body, str) else ""
    relative = tuple(sorted(set(relative_mutation_linenos)))
    if not body or not relative:
        return False
    lines = text.split("\n")
    fun_start = text.count("\n", 0, decl.start_offset)
    # ``decl.body`` is the tail slice of the declaration span, so the body
    # starts exactly at ``end_offset - len(body)``.
    body_base_line = text.count("\n", 0, decl.end_offset - len(body))
    for relative_lineno in relative:
        if not _barrier_before_line(
            lines, fun_start, body_base_line + relative_lineno + 1
        ):
            return False
    return True


# ── v2 candidate construction ─────────────────────────────────────────────────


def build_v2_entry_dict(
    index,
    legacy_entry,
    path,
    owner_fqcn,
    kind_str,
    method,
    receiver,
    parameter_types,
    dao_accessor,
    dao_fqcn,
    operation,
    barrier_mode,
) -> dict:
    """Build one fresh v2 candidate mapping with EXACTLY the 13 entry keys.

    ``schemaVersion`` is deliberately absent — it belongs to the document
    level, never to an entry.  No legacy key is ever copied through: only
    the three bounded metadata strings (``reason``/``owner``/
    ``linkedIssue``) are carried over, and only when they are already
    strings; non-string legacy values never reach the candidate.  For
    ``linkedIssue`` both legacy key spellings are accepted — snake_case
    ``linked_issue`` (the v1 form) preferred, camelCase ``linkedIssue``
    as fallback.
    ``index`` is accepted for call-site symmetry and is not interpreted.
    """
    legacy_reason = (
        legacy_entry.get("reason") if isinstance(legacy_entry, Mapping) else None
    )
    legacy_owner = (
        legacy_entry.get("owner") if isinstance(legacy_entry, Mapping) else None
    )
    legacy_linked_issue = None
    if isinstance(legacy_entry, Mapping):
        # Legacy v1 entries carry snake_case ``linked_issue``; accept both
        # spellings, preferring the snake_case form, and take only the
        # first non-empty string value.
        for linked_key in ("linked_issue", "linkedIssue"):
            linked_value = legacy_entry.get(linked_key)
            if isinstance(linked_value, str) and linked_value:
                legacy_linked_issue = linked_value
                break
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": kind_str,
        "method": method,
        "receiver": receiver,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": barrier_mode,
        "reason": (
            legacy_reason
            if isinstance(legacy_reason, str) and legacy_reason
            else "Migrated legacy ownership entry"
        ),
        "owner": legacy_owner if isinstance(legacy_owner, str) else "",
        "linkedIssue": (
            legacy_linked_issue if isinstance(legacy_linked_issue, str) else ""
        ),
    }


# ── Legacy v1 -> v2 row migration ─────────────────────────────────────────────


def migrate_policy(legacy_entries, repo_root, dao_index=None) -> MigrationResult:
    """Migrate legacy v1 policy entries into v2 candidate rows, row by row.

    Every input row produces outcomes expressed inside the closed extended
    status vocabulary; per-row debt never raises out of the batch:

    * non-mapping entry                        -> PARSER_UNCERTAIN;
    * barrier metadata outside the exact legacy
      shapes (missing/non-bool required, malformed
      via, mediated via together with
      barrier_required=true)                   -> BARRIER_MODE_UNRESOLVED;
    * barrier_required=false rows classify helper,
      or workerMediated when ``barrier_via``
      references WorkerExecutionGuard;
    * barrier_required=true rows emit direct only
      with shared-scanner proof of a real
      writeBarrier call before every resolved
      mutation, and downgrade to helper otherwise;
      with no callable body to prove against they
      stay BARRIER_MODE_UNRESOLVED;
    * path outside approved roots / unreadable -> OWNER_MISSING;
    * masking failure / parser failure /
      loader rejection                         -> PARSER_UNCERTAIN;
    * owner/callable resolution failures       -> the shared resolvers'
      OWNER_/CALLABLE_ statuses;
    * matched callable itself carries the
      parser's tolerant TYPE_UNRESOLVED
      status (PR-GR-05 Slice 3)                -> PARSER_UNSUPPORTED;
    * DAO identity/target failures             -> the DAO_* statuses;
    * no extractable mutation                  -> MUTATION_PAIR_MISSING;
    * extracted mutations exist but none is
      authorized by the legacy ``daos`` list   -> MUTATION_PAIR_MISSING
      ("no mutation matches legacy daos").

    Legacy authorization-intent cross-check (DAO resolution rule 5): once
    mutation resolution succeeds, the resolved ``(accessor, fqcn,
    operation)`` triples are filtered to those whose accessor appears in
    the expected set derived from the legacy entry's ``daos`` list — each
    string member mapped through the Room accessor naming rule
    (:func:`_interface_name_to_room_accessor`, idempotent on names that
    are already accessors); a missing/non-list ``daos`` field, or one with
    no string members, authorizes NOTHING.  An empty filtered set means
    the legacy row authorizes none of the evidence-derived mutations, so
    the whole entry becomes debt and NO row is emitted — non-matching
    accessors are not authorized by this legacy row and must never leak
    into candidates.  Filtering is by accessor ONLY, never by operation:
    evidence-derived operations stay authoritative, so multi-operation
    splits keep emitting multiple rows sharing the callable key.

    Resolved rows carry schema-valid :class:`PolicyEntry` candidates built
    exclusively from source evidence plus the three bounded legacy metadata
    strings.  The emitted callable identity — method name, receiver,
    ordered parameter types — is the selected declaration's
    parser-discovered signature: legacy ``signature`` fields act only as
    resolution hints and are never copied into candidates.  Rows remain
    inert data until a human writes and loads a real
    v2 document through the ordinary gates.  ``dao_index`` defaults to a
    freshly built :func:`build_dao_fqcn_index` over ``repo_root``.

    Dedupe-by-key at emission (PR-GR-05 Slice 4, refined in Slice 5): the
    result carries ONE entry per unique canonical mutation key.  When
    several legacy rows authorize the same callable+DAO+operation, the
    LOWEST-INDEX emission keeps its :class:`ResolvedRow` verbatim (its
    ``reason`` text survives) and every later emission with the SAME
    authorization metadata — ``(barrierMode, owner, linkedIssue)``;
    free-text ``reason`` differences fold away — folds into the
    ``emission_indices`` crosswalk (key -> all source legacy indices), so
    the accounting model still ties every legacy index to the shared key.
    A later emission with the same key but DIFFERING barrierMode, owner,
    or linkedIssue is a genuine AUTHORIZATION_METADATA_CONFLICT: EVERY
    participating index becomes one closed-vocabulary UNRESOLVED debt row
    (bounded detail: key tail + index counts only), nothing emits for the
    key, and the key never enters the crosswalk.  Conflicts are decided
    end-of-batch so a late disagreement deterministically retracts the
    earlier emissions of the same key; an index poisoned by any conflict
    loses all of its emissions (exactly one outcome per index).  Each
    index's candidates are all built before any is committed, so an index
    contributes all of its emissions or none.
    """
    if dao_index is None:
        dao_index = build_dao_fqcn_index(repo_root)
    # Declared production source roots are resolved ONCE per batch; every
    # per-entry path gate below membership-checks against this set.  A
    # resolution failure leaves ``root_set`` None so every path fails the
    # gate closed (same OWNER_MISSING debt as an out-of-root path).
    root_set, _root_diagnostics = _declared_relative_root_set(repo_root)
    unresolved_rows = []
    # PR-GR-05 Slice 5 dedupe state.  Emission decisions are DEFERRED to
    # end of batch: ``key_groups`` maps each canonical mutation key to its
    # first-seen (lowest-index) candidate entry, the authorization metadata
    # tuple ``(barrierMode value, owner, linkedIssue)`` every folded
    # emission must repeat, the ascending unique source indices, and a
    # conflict flag raised when any later emission of the key disagrees on
    # that metadata.  ``emission_order`` records every (index, key)
    # emission in loop order so kept rows preserve the historical
    # first-emission ordering; ``index_identities`` remembers each
    # emitting index's legacy class/method for conflict debt rows.
    key_groups: dict = {}
    emission_order: list = []
    index_identities: dict = {}
    for index, entry in enumerate(legacy_entries):
        if not isinstance(entry, Mapping):
            unresolved_rows.append(
                UnresolvedRow(
                    index, "", "", STATUS_PARSER_UNCERTAIN, "entry not a mapping"
                )
            )
            continue
        raw_class = entry.get("class")
        legacy_class = raw_class if isinstance(raw_class, str) else ""
        raw_method = entry.get("method")
        legacy_method = raw_method if isinstance(raw_method, str) else ""

        # Barrier gate, phase 1: every metadata-decidable shape resolves or
        # fails closed here.  Exactly one shape defers — a REAL
        # ``barrier_required=True`` with no ``barrier_via`` value — because
        # its mode depends on direct-barrier syntax that only the resolved
        # callable body can prove (phase 2 after mutation resolution).
        mode, barrier_status = convert_barrier_mode(entry)
        barrier_deferred = False
        if barrier_status is not None:
            if _barrier_mode_needs_source_evidence(entry):
                barrier_deferred = True
            else:
                unresolved_rows.append(
                    UnresolvedRow(
                        index, legacy_class, legacy_method, barrier_status, ""
                    )
                )
                continue

        path = entry.get("path")
        if root_set is None or not is_declared_production_path(root_set, path):
            unresolved_rows.append(
                UnresolvedRow(
                    index,
                    legacy_class,
                    legacy_method,
                    STATUS_OWNER_MISSING,
                    "path outside approved roots",
                )
            )
            continue

        try:
            with open(
                os.path.join(repo_root, path), "r", encoding="utf-8"
            ) as handle:
                text = handle.read()
        except OSError:
            unresolved_rows.append(
                UnresolvedRow(
                    index,
                    legacy_class,
                    legacy_method,
                    STATUS_OWNER_MISSING,
                    "source unreadable",
                )
            )
            continue
        try:
            masked_text = mask_kotlin_source(text)
        except Exception:
            unresolved_rows.append(
                UnresolvedRow(
                    index,
                    legacy_class,
                    legacy_method,
                    STATUS_PARSER_UNCERTAIN,
                    "mask failed",
                )
            )
            continue

        # An empty legacy class name can never match an owner; the shared
        # resolver rejects it with TypeError, so it is recorded as
        # OWNER_MISSING debt here instead (same closed outcome, no raise).
        if not legacy_class:
            unresolved_rows.append(
                UnresolvedRow(
                    index,
                    legacy_class,
                    legacy_method,
                    STATUS_OWNER_MISSING,
                    "",
                )
            )
            continue

        try:
            owner_decl, owner_status, owner_detail = resolve_owner(
                masked_text, legacy_class, path
            )
            if owner_status is not None or owner_decl is None:
                unresolved_rows.append(
                    UnresolvedRow(
                        index,
                        legacy_class,
                        legacy_method,
                        owner_status,
                        owner_detail or "",
                    )
                )
                continue

            signature = entry.get("signature")
            signature = signature if isinstance(signature, Mapping) else {}
            receiver = signature.get("receiver")
            raw_parameters = signature.get("parameters")
            parameter_types = (
                tuple(raw_parameters) if isinstance(raw_parameters, list) else ()
            )

            decl, callable_status = resolve_callable_for_entry(
                owner_decl,
                masked_text,
                legacy_method,
                receiver,
                parameter_types,
            )
            if callable_status is not None or decl is None:
                unresolved_rows.append(
                    UnresolvedRow(
                        index, legacy_class, legacy_method, callable_status, ""
                    )
                )
                continue

            all_callables = find_callable_declarations(
                masked_text, owner_decl, tolerate_unresolved_types=True
            )
        except ParserError:
            unresolved_rows.append(
                UnresolvedRow(
                    index,
                    legacy_class,
                    legacy_method,
                    STATUS_PARSER_UNCERTAIN,
                    "parser failed",
                )
            )
            continue

        try:
            triples, mutation_failure, mutation_linenos = (
                resolve_mutations_for_callable(
                    decl, owner_decl, masked_text, all_callables, dao_index
                )
            )
        except ParserError:
            # Nested-owner rescans inside the merged DAO map parse fun
            # signatures the outer owner scan skipped, so a parser failure
            # can first surface here; it fails closed as one controlled
            # PARSER_UNCERTAIN row instead of escaping the batch.
            unresolved_rows.append(
                UnresolvedRow(
                    index,
                    legacy_class,
                    legacy_method,
                    STATUS_PARSER_UNCERTAIN,
                    "parser failed",
                )
            )
            continue
        if mutation_failure is not None:
            unresolved_rows.append(
                UnresolvedRow(
                    index, legacy_class, legacy_method, mutation_failure, ""
                )
            )
            continue
        if not triples:
            unresolved_rows.append(
                UnresolvedRow(
                    index,
                    legacy_class,
                    legacy_method,
                    STATUS_MUTATION_PAIR_MISSING,
                    "",
                )
            )
            continue

        # Barrier gate, phase 2 (deferred shape only): the row claimed
        # barrier_required=true with no mediation via, so its mode comes
        # from the shared scanner's direct-barrier verdict over the now-
        # resolved callable body and every extracted mutation position.
        # Proof emits ``direct``; disproof downgrades to ``helper``.  With
        # concrete boolean evidence this shape always resolves, so the
        # debt branch below is defensive fail-closed armor only.
        if barrier_deferred:
            mode, barrier_status = convert_barrier_mode(
                entry,
                has_direct_barrier=_callable_direct_barrier_evidence(
                    text, decl, mutation_linenos
                ),
            )
            if barrier_status is not None:
                unresolved_rows.append(
                    UnresolvedRow(
                        index,
                        legacy_class,
                        legacy_method,
                        barrier_status,
                        "",
                    )
                )
                continue

        # Legacy authorization-intent cross-check (DAO resolution rule 5):
        # a legacy row authorizes ONLY the DAOs named by its ``daos``
        # list.  Accessor-only filtering — never by operation, so
        # multi-operation splits keep sharing the callable key.  An empty
        # authorized set fails the whole entry closed; non-matching
        # accessors are not authorized by this legacy row and must never
        # be emitted.
        raw_daos = entry.get("daos")
        legacy_dao_names = (
            [dao for dao in raw_daos if isinstance(dao, str)]
            if isinstance(raw_daos, list)
            else []
        )
        expected_accessors = {
            _interface_name_to_room_accessor(str(dao))
            for dao in legacy_dao_names
        }
        authorized_triples = [
            (accessor, fqcn, operation)
            for accessor, fqcn, operation in triples
            if accessor in expected_accessors
        ]
        if not authorized_triples:
            unresolved_rows.append(
                UnresolvedRow(
                    index,
                    legacy_class,
                    legacy_method,
                    STATUS_MUTATION_PAIR_MISSING,
                    "no mutation matches legacy daos",
                )
            )
            continue

        # Build EVERY authorized candidate for this index before committing
        # anything: an index contributes all of its emissions or none, so a
        # late loader rejection can never leave a half-emitted index (which
        # would contradict the accounting model's one-outcome-per-index
        # invariant).
        built_for_index = []
        for accessor, fqcn, operation in authorized_triples:
            candidate = build_v2_entry_dict(
                index,
                entry,
                path,
                owner_decl.owner,
                "function",
                legacy_method,
                # Emitted identity is the parser-discovered signature of
                # the selected declaration — never the legacy hints.
                decl.signature.receiver,
                decl.signature.parameter_types,
                accessor,
                fqcn,
                operation,
                mode,
            )
            built, loader_errors = build_policy_entry(candidate, index)
            if built is None or loader_errors:
                built_for_index = None
                break
            built_for_index.append(built)
        if built_for_index is None:
            unresolved_rows.append(
                UnresolvedRow(
                    index,
                    legacy_class,
                    legacy_method,
                    STATUS_PARSER_UNCERTAIN,
                    "loader rejected row",
                )
            )
            continue

        # Dedupe-by-key at emission (PR-GR-05 Slice 4 plan contract,
        # refined in Slice 5): the batch carries ONE entry per unique
        # canonical mutation key.  Emissions are only RECORDED here; the
        # fold/conflict decision is materialized after the loop, because a
        # conflict discovered late must retract the earlier emissions of
        # the same key.  A key folds when every emission repeats the SAME
        # authorization metadata (barrierMode, owner, linkedIssue) — the
        # lowest-index entry is kept verbatim, free-text ``reason``
        # differences fold away — while a metadata disagreement marks the
        # whole key conflicted: every participating index becomes debt and
        # nothing emits for it.
        index_identities[index] = (legacy_class, legacy_method)
        for built in built_for_index:
            key = built.mutation_key().canonical_key()
            metadata = (
                built.barrier_mode.value,
                built.owner,
                built.linked_issue,
            )
            group = key_groups.get(key)
            if group is None:
                key_groups[key] = {
                    # First-seen emission of this key = lowest-index
                    # emitter's candidate; kept verbatim on fold.
                    "entry": built,
                    "metadata": metadata,
                    "indices": [index],
                    "conflict": False,
                }
            else:
                if group["metadata"] != metadata:
                    group["conflict"] = True
                if index not in group["indices"]:
                    group["indices"].append(index)
            emission_order.append((index, key))

    # ── End-of-batch fold/conflict materialization (PR-GR-05 Slice 5) ────
    conflicted_indices = set()
    for group in key_groups.values():
        if group["conflict"]:
            conflicted_indices.update(group["indices"])
    conflict_keys_by_index: dict = {}
    for key in sorted(key_groups):
        if key_groups[key]["conflict"]:
            for conflicted_index in key_groups[key]["indices"]:
                conflict_keys_by_index.setdefault(conflicted_index, []).append(
                    key
                )
    resolved_rows = []
    emission_indices: dict = {}
    for emission_index, key in emission_order:
        group = key_groups[key]
        if group["conflict"]:
            continue
        surviving = [
            i for i in group["indices"] if i not in conflicted_indices
        ]
        if not surviving or surviving[0] != emission_index:
            # Folded into the lowest surviving index's verbatim entry, or
            # this emitter was poisoned by a conflict on another key.
            continue
        resolved_rows.append(ResolvedRow(emission_index, group["entry"]))
        emission_indices[key] = tuple(surviving)
    for poisoned_index in sorted(conflicted_indices):
        legacy_class, legacy_method = index_identities[poisoned_index]
        keys = conflict_keys_by_index[poisoned_index]
        # Bounded structured context ONLY: index counts plus the tail
        # segment of the lexicographically smallest conflicted key (an
        # operation name).  No payloads, no reason text, no full keys.
        detail = "conflictingIndices=%d keyTail=%s" % (
            len(key_groups[keys[0]]["indices"]),
            keys[0].rsplit("|", 1)[-1][:60],
        )
        if len(keys) > 1:
            detail += " conflictingKeys=%d" % len(keys)
        unresolved_rows.append(
            UnresolvedRow(
                poisoned_index,
                legacy_class,
                legacy_method,
                STATUS_AUTHORIZATION_METADATA_CONFLICT,
                detail,
            )
        )
    # Exactly one outcome per index is an accounting invariant; sorting
    # merges the mid-loop debt rows with the end-of-batch conflict rows
    # into one deterministic ascending sequence.
    unresolved_rows.sort(key=lambda row: row.index)

    emission_map = tuple(
        (key, tuple(indices))
        for key, indices in sorted(emission_indices.items())
    )
    return MigrationResult(
        tuple(resolved_rows),
        tuple(unresolved_rows),
        len(legacy_entries),
        emission_map,
    )


# ── Duplicate detection over migrated rows ────────────────────────────────────


def find_duplicate_mutation_keys(result):
    """Return sorted unique canonical mutation keys carried by 2+ rows.

    Since PR-GR-05 Slice 5 folds every same-metadata re-authorization at
    the source (keeping the lowest-index entry verbatim) and converts
    metadata disagreements into AUTHORIZATION_METADATA_CONFLICT debt rows,
    a properly migrated batch yields an empty tuple.  A non-empty result
    therefore names a LEAKED contradiction — same canonical key carried by
    2+ resolved rows, which the migration path no longer produces — and
    the function survives purely as defense-in-depth: the CLI still fails
    hard (exit 2, no candidate write) rather than ever emitting a
    colliding candidate.
    """
    counts: dict[str, int] = {}
    for row in result.resolved:
        key = row.entry.mutation_key().canonical_key()
        counts[key] = counts.get(key, 0) + 1
    return tuple(sorted(key for key, count in counts.items() if count >= 2))


# ── Accounting model (PR-GR-05 Slice 1) ──────────────────────────────────────
#
# Deterministic bookkeeping tying EVERY legacy v1 row to the v2 candidate
# entries it produced (or the closed debt status it ended with).  This is
# review evidence ONLY: it never activates policy, never authorizes
# anything, and carries identity fields, controlled constants, canonical
# mutation keys, and counts — never raw payloads or absolute paths.
#
# Append-only extension point: new outcomes/actions/coverage kinds join the
# closed frozensets below; previously released constants stay frozen.

import hashlib

ACCOUNTING_SCHEMA_NAME = "db-policy-migration-accounting"
ACCOUNTING_SCHEMA_VERSION = 1

OUTCOME_RESOLVED = "RESOLVED"
OUTCOME_UNRESOLVED = "UNRESOLVED"
ACCOUNTING_OUTCOMES = frozenset({OUTCOME_RESOLVED, OUTCOME_UNRESOLVED})

#: Controlled reviewed-action codes (append-only).  Action fields behave
#: like reason codes: controlled constants only, never free-form text.
ACTION_EMIT_CANDIDATE = "EMIT_CANDIDATE"
ACTION_REVIEW_DEBT = "REVIEW_DEBT"
ACCOUNTING_ACTIONS = frozenset({ACTION_EMIT_CANDIDATE, ACTION_REVIEW_DEBT})

COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW = "COVERED_BY_RESOLVED_LEGACY_ROW"
COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY = "OBSERVED_NOT_IN_LEGACY_POLICY"
COVERAGE_OBSERVED_BUT_UNRESOLVED = "OBSERVED_BUT_UNRESOLVED"
COVERAGE_UNRESOLVED_ANALYZER_INPUT = "UNRESOLVED_ANALYZER_INPUT"
SOURCE_MUTATION_COVERAGE_KINDS = frozenset(
    {
        COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW,
        COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY,
        COVERAGE_OBSERVED_BUT_UNRESOLVED,
        COVERAGE_UNRESOLVED_ANALYZER_INPUT,
    }
)

MAX_ACCOUNTING_DETAIL_LENGTH = MAX_UNRESOLVED_DETAIL_LENGTH
MAX_ACCOUNTING_ACTION_LENGTH = 200
MAX_COVERAGE_SYMBOL_LENGTH = 200

_SHA256_HEX_LENGTH = 64
_SHA256_HEX_DIGITS = frozenset("0123456789abcdef")


def _validate_repo_relative_posix_path(path, label):
    """Validate a repository-relative POSIX path; ``ValueError`` on violation.

    Rejects non-strings, emptiness, backslashes, drive/colon syntax,
    leading slashes, and empty/``.``/``..`` segments — every shape that
    could smuggle an absolute or escaping path into an artifact.
    """
    if not isinstance(path, str) or not path:
        raise ValueError("%s must be a non-empty string" % (label,))
    if "\\" in path or ":" in path or path.startswith("/"):
        raise ValueError(
            "%s must be a repository-relative posix path" % (label,)
        )
    segments = path.split("/")
    if any(segment in ("", ".", "..") for segment in segments):
        raise ValueError(
            "%s must not contain empty, '.', or '..' segments" % (label,)
        )
    return path


def _validate_sha256_hex(value, label):
    """Validate a lowercase 64-character sha256 hex digest."""
    if (
        not isinstance(value, str)
        or len(value) != _SHA256_HEX_LENGTH
        or any(char not in _SHA256_HEX_DIGITS for char in value)
    ):
        raise ValueError(
            "%s must be a lowercase %d-character sha256 hex digest"
            % (label, _SHA256_HEX_LENGTH)
        )
    return value


def _validate_bounded_text(value, label, bound):
    """Validate a bounded plain string; over-bound input fails closed."""
    if not isinstance(value, str) or len(value) > bound:
        raise ValueError(
            "%s must be a string of at most %d characters" % (label, bound)
        )
    return value


@dataclass(frozen=True)
class AccountingRecord:
    """Per-legacy-row accounting outcome (exactly one record per row).

    ``outcome`` is ``RESOLVED`` or ``UNRESOLVED``.  A RESOLVED record
    carries >=1 canonical mutation key and no debt status; an UNRESOLVED
    record carries a closed-set debt status (never ``RESOLVED``), zero
    keys, and bounded structured detail.  ``action`` is a controlled
    reviewed-action constant, never free-form text.
    """

    index: int
    outcome: str
    status: str | None = None
    detail: str = ""
    action: str = ""
    mutation_keys: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if isinstance(self.index, bool) or not isinstance(self.index, int):
            raise ValueError("accounting record index must be an integer")
        if self.outcome not in ACCOUNTING_OUTCOMES:
            raise ValueError("accounting record outcome outside the closed set")
        if not isinstance(self.mutation_keys, tuple) or any(
            not isinstance(key, str) or not key for key in self.mutation_keys
        ):
            raise ValueError(
                "accounting record mutation keys must be a tuple of"
                " non-empty strings"
            )
        if list(self.mutation_keys) != sorted(set(self.mutation_keys)):
            raise ValueError(
                "accounting record mutation keys must be sorted and unique"
            )
        if self.outcome == OUTCOME_RESOLVED:
            if not self.mutation_keys:
                raise ValueError(
                    "resolved accounting record carries no mutation key"
                )
            if self.status is not None and self.status != STATUS_RESOLVED:
                raise ValueError(
                    "resolved accounting record carries a debt status"
                )
        else:
            if self.mutation_keys:
                raise ValueError(
                    "unresolved accounting record carries mutation keys"
                )
            if (
                self.status is None
                or self.status not in MIGRATION_STATUSES_EXTENDED
                or self.status == STATUS_RESOLVED
            ):
                raise ValueError(
                    "unresolved accounting record status outside the closed set"
                )
        _validate_bounded_text(
            self.detail,
            "accounting record detail",
            MAX_ACCOUNTING_DETAIL_LENGTH,
        )
        if self.action not in ACCOUNTING_ACTIONS:
            raise ValueError("accounting record action outside the closed set")
        _validate_bounded_text(
            self.action,
            "accounting record action",
            MAX_ACCOUNTING_ACTION_LENGTH,
        )

    def to_dict(self):
        """Deterministic JSON mapping; insertion order equals sorted order."""
        return {
            "action": self.action,
            "detail": self.detail,
            "index": self.index,
            "mutationKeys": list(self.mutation_keys),
            "outcome": self.outcome,
            "status": self.status,
        }


@dataclass(frozen=True)
class SourceMutationCoverage:
    """One observed source-tree mutation and its legacy-row coverage.

    ``path`` is repository-relative POSIX; ``symbol`` is bounded;
    ``legacy_indices`` is ascending/unique.  A
    ``COVERED_BY_RESOLVED_LEGACY_ROW`` entry must name at least one legacy
    index; observation-only kinds may carry none.
    """

    kind: str
    path: str
    symbol: str
    operation: str | None = None
    legacy_indices: tuple[int, ...] = ()

    def __post_init__(self) -> None:
        if self.kind not in SOURCE_MUTATION_COVERAGE_KINDS:
            raise ValueError(
                "source mutation coverage kind outside the closed set"
            )
        _validate_repo_relative_posix_path(self.path, "coverage path")
        if not isinstance(self.symbol, str) or not self.symbol:
            raise ValueError("coverage symbol must be a non-empty string")
        _validate_bounded_text(
            self.symbol, "coverage symbol", MAX_COVERAGE_SYMBOL_LENGTH
        )
        if self.operation is not None and (
            not isinstance(self.operation, str) or not self.operation
        ):
            raise ValueError(
                "coverage operation must be None or a non-empty string"
            )
        if not isinstance(self.legacy_indices, tuple) or any(
            isinstance(i, bool) or not isinstance(i, int)
            for i in self.legacy_indices
        ):
            raise ValueError(
                "coverage legacy indices must be a tuple of integers"
            )
        if list(self.legacy_indices) != sorted(set(self.legacy_indices)):
            raise ValueError(
                "coverage legacy indices must be ascending and unique"
            )
        if (
            self.kind == COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW
            and not self.legacy_indices
        ):
            raise ValueError(
                "covered-by-resolved coverage requires at least one legacy index"
            )

    def to_dict(self):
        """Deterministic JSON mapping; insertion order equals sorted order."""
        return {
            "kind": self.kind,
            "legacyIndices": list(self.legacy_indices),
            "operation": self.operation,
            "path": self.path,
            "symbol": self.symbol,
        }


@dataclass(frozen=True)
class AccountingArtifact:
    """Immutable accounting evidence for one migration batch.

    Self-consistency is enforced at construction: records cover exactly
    ``range(input_count)`` in ascending order, source mutations are sorted
    by ``(path, symbol, operation)``, hashes are lowercase sha256 hex, and
    every path is repository-relative POSIX.  Crosswalk validation against
    candidate entries happens in :func:`build_accounting_artifact`.
    """

    schema_version: int = ACCOUNTING_SCHEMA_VERSION
    source_policy_path: str = ""
    source_policy_sha256: str = ""
    source_tree_sha: str = ""
    candidate_sha256: str | None = None
    input_count: int = 0
    records: tuple[AccountingRecord, ...] = ()
    source_mutations: tuple[SourceMutationCoverage, ...] = ()

    def __post_init__(self) -> None:
        if (
            isinstance(self.schema_version, bool)
            or not isinstance(self.schema_version, int)
            or self.schema_version != ACCOUNTING_SCHEMA_VERSION
        ):
            raise ValueError("unsupported accounting schema version")
        _validate_repo_relative_posix_path(
            self.source_policy_path, "source_policy_path"
        )
        _validate_sha256_hex(
            self.source_policy_sha256, "source_policy_sha256"
        )
        _validate_sha256_hex(self.source_tree_sha, "source_tree_sha")
        if self.candidate_sha256 is not None:
            _validate_sha256_hex(
                self.candidate_sha256, "candidate_sha256"
            )
        if (
            isinstance(self.input_count, bool)
            or not isinstance(self.input_count, int)
            or self.input_count < 0
        ):
            raise ValueError("artifact input_count must be a non-negative integer")
        if not isinstance(self.records, tuple) or any(
            not isinstance(record, AccountingRecord)
            for record in self.records
        ):
            raise ValueError(
                "artifact records must be a tuple of AccountingRecord"
            )
        if sorted(record.index for record in self.records) != list(
            range(self.input_count)
        ):
            raise ValueError(
                "accounting records must cover exactly range(input_count)"
            )
        if not isinstance(self.source_mutations, tuple) or any(
            not isinstance(coverage, SourceMutationCoverage)
            for coverage in self.source_mutations
        ):
            raise ValueError(
                "artifact source mutations must be a tuple of"
                " SourceMutationCoverage"
            )
        ordering = [
            (coverage.path, coverage.symbol, coverage.operation or "")
            for coverage in self.source_mutations
        ]
        if ordering != sorted(ordering):
            raise ValueError(
                "artifact source mutations must be sorted by"
                " (path, symbol, operation)"
            )

    def to_dict(self):
        """Deterministic JSON mapping with ``schema``/``version`` headers.

        Keys are inserted in sorted order so serialization is byte-stable
        whether or not the JSON encoder also sorts keys.
        """
        return {
            "candidateSha256": self.candidate_sha256,
            "inputCount": self.input_count,
            "records": [record.to_dict() for record in self.records],
            "schema": ACCOUNTING_SCHEMA_NAME,
            "sourceMutations": [
                coverage.to_dict() for coverage in self.source_mutations
            ],
            "sourcePolicyPath": self.source_policy_path,
            "sourcePolicySha256": self.source_policy_sha256,
            "sourceTreeSha": self.source_tree_sha,
            "version": self.schema_version,
        }


def build_accounting_artifact(
    result,
    candidate_entries,
    *,
    source_policy_path,
    source_policy_sha256,
    source_tree_sha,
    candidate_sha256=None,
    source_mutations=(),
) -> AccountingArtifact:
    """Assemble the accounting artifact for one migration batch.

    Derives exactly one :class:`AccountingRecord` per legacy index from
    ``result`` (merging the canonical mutation keys of multi-operation
    splits that share an index) and enforces, failing closed with
    ``ValueError``:

    * records cover exactly ``range(result.input_count)`` — a missing,
      repeated-unresolved, out-of-range, or resolved-AND-unresolved index
      is rejected as contradictory rather than silently normalized;
    * bijective crosswalk: the union of record mutation keys equals the
      set of canonical keys of ``candidate_entries``, and every key maps
      to at least one legacy index;
    * deterministic ordering: records ascending by index, source mutations
      by ``(path, symbol, operation)``;
    * every field/path/hash bound re-validated by the frozen dataclasses.

    Per-index keys come from ``result.resolved`` merged with the Slice 4
    dedupe crosswalk (``result.emission_indices``): every legacy index
    whose emission produced a key maps to that key, including indices
    whose identical emission was folded into an earlier row and therefore
    has no resolved row of its own.  A crosswalk index that also carries
    an unresolved row trips the resolved-AND-unresolved rejection above.
    """
    resolved_keys_by_index: dict = {}
    for row in result.resolved:
        resolved_keys_by_index.setdefault(row.index, set()).add(
            row.entry.mutation_key().canonical_key()
        )
    for key, indices in result.emission_indices:
        for index in indices:
            resolved_keys_by_index.setdefault(index, set()).add(key)
    unresolved_row_by_index: dict = {}
    for row in result.unresolved:
        if row.index in resolved_keys_by_index:
            raise ValueError(
                "legacy index has both resolved and unresolved outcomes"
            )
        if row.index in unresolved_row_by_index:
            raise ValueError("legacy index has repeated unresolved outcomes")
        unresolved_row_by_index[row.index] = row
    covered_indices = set(resolved_keys_by_index) | set(unresolved_row_by_index)
    expected_indices = set(range(result.input_count))
    if covered_indices != expected_indices:
        raise ValueError(
            "accounting records do not cover exactly range(input_count):"
            " missing=%d unexpected=%d"
            % (
                len(expected_indices - covered_indices),
                len(covered_indices - expected_indices),
            )
        )
    records = []
    for index in range(result.input_count):
        if index in resolved_keys_by_index:
            records.append(
                AccountingRecord(
                    index=index,
                    outcome=OUTCOME_RESOLVED,
                    status=None,
                    detail="",
                    action=ACTION_EMIT_CANDIDATE,
                    mutation_keys=tuple(
                        sorted(resolved_keys_by_index[index])
                    ),
                )
            )
        else:
            row = unresolved_row_by_index[index]
            records.append(
                AccountingRecord(
                    index=index,
                    outcome=OUTCOME_UNRESOLVED,
                    status=row.status,
                    detail=row.detail,
                    action=ACTION_REVIEW_DEBT,
                    mutation_keys=(),
                )
            )
    candidate_key_set = {
        entry.mutation_key().canonical_key() for entry in candidate_entries
    }
    indices_by_key: dict = {}
    for record in records:
        for key in record.mutation_keys:
            indices_by_key.setdefault(key, []).append(record.index)
    if set(indices_by_key) - candidate_key_set:
        raise ValueError(
            "record mutation keys absent from candidate entries"
        )
    if candidate_key_set - set(indices_by_key):
        raise ValueError(
            "candidate mutation keys not covered by any legacy record"
        )
    for key, indices in indices_by_key.items():
        if not indices:  # structurally impossible; kept explicit fail-closed
            raise ValueError(
                "mutation key maps to no legacy index"
            )
    ordered_mutations = tuple(
        sorted(
            source_mutations,
            key=lambda coverage: (
                coverage.path,
                coverage.symbol,
                coverage.operation or "",
            ),
        )
    )
    return AccountingArtifact(
        schema_version=ACCOUNTING_SCHEMA_VERSION,
        source_policy_path=_validate_repo_relative_posix_path(
            source_policy_path, "source_policy_path"
        ),
        source_policy_sha256=_validate_sha256_hex(
            source_policy_sha256, "source_policy_sha256"
        ),
        source_tree_sha=_validate_sha256_hex(
            source_tree_sha, "source_tree_sha"
        ),
        candidate_sha256=candidate_sha256,
        input_count=result.input_count,
        records=tuple(records),
        source_mutations=ordered_mutations,
    )


def production_source_manifest_digest(repo_root):
    """Deterministic sha256 over the declared production Kotlin manifest.

    Digests the newline-joined, sorted, repository-relative POSIX paths of
    every production Kotlin file in the declared source-root set (the same
    walk used for DAO discovery).  This fingerprints tree SHAPE — which
    sources were analyzed — not file contents.  Returns ``None`` when the
    declared tree cannot be resolved or walked so callers can omit
    accounting instead of inventing a digest.  Accounting metadata only;
    never authorizes anything.
    """
    root_set, _diagnostics = _declared_relative_root_set(repo_root)
    if root_set is None:
        return None
    relative_names, walk_diagnostics = collect_production_kotlin_files(
        repo_root, root_set
    )
    if walk_diagnostics:
        return None
    return hashlib.sha256(
        "\n".join(sorted(relative_names)).encode("utf-8")
    ).hexdigest()
