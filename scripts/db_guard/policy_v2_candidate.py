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
        # a pure superset adding only the controlled PARSER_UNCERTAIN constant.
        if self.status not in MIGRATION_STATUSES_EXTENDED:
            raise ValueError("unresolved row status outside MIGRATION_STATUSES")
        if len(self.detail) > MAX_UNRESOLVED_DETAIL_LENGTH:
            raise ValueError("unresolved row detail exceeds length bound")


@dataclass(frozen=True)
class MigrationResult:
    """Outcome of migrating a batch of legacy entries, row by row."""

    resolved: tuple[ResolvedRow, ...]
    unresolved: tuple[UnresolvedRow, ...]
    input_count: int


# ── Barrier-mode conversion ───────────────────────────────────────────────────


def convert_barrier_mode(entry_mapping) -> tuple[str | None, str | None]:
    """Convert legacy ``barrier_required``/``barrier_via`` fields to a v2
    barrierMode string under the closed rule.

    Returns ``(mode, status)`` where exactly one element is ``None``:

    * ``barrier_required is True`` and ``barrier_via`` is falsy (absent,
      ``None``, or empty) -> ``("direct", None)``;
    * everything else — ``False``, a present ``via``, contradictory types
      (non-bool truthy flags, non-string via values, non-mapping input) ->
      ``(None, STATUS_BARRIER_MODE_UNRESOLVED)``.

    The rule is deliberately closed: only the exact representable case
    resolves; every other shape becomes visible, unactivated debt instead
    of an invented mode.
    """
    required = entry_mapping.get("barrier_required") if isinstance(entry_mapping, Mapping) else None
    via = entry_mapping.get("barrier_via") if isinstance(entry_mapping, Mapping) else None
    if required is True and not via:
        return ("direct", None)
    return (None, STATUS_BARRIER_MODE_UNRESOLVED)


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
      owner_decl)`` filtered to ``signature.function_name == method``
      with declaration status ``RESOLVED_EXACTLY``;
    * a non-null legacy receiver hint narrows candidates to those whose
      normalized receiver equals it (None-safe: null-receiver funs cannot
      match a non-null hint);
    * zero candidates                     -> ``(None,
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
    decls = [
        d
        for d in find_callable_declarations(masked_text, owner_decl)
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
    if not decls:
        return (None, STATUS_CALLABLE_MISSING)
    if len(decls) == 1:
        # Single same-name member fun: its OWN parsed signature is the
        # emitted identity; legacy hints are neither required nor copied.
        return (decls[0], None)
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


# ── Append-only status vocabulary extension (PR-GR-02) ───────────────────────
#
# New statuses are NEVER inserted next to the original constants above; the
# original block is frozen.  Extensions live at this append point only.

STATUS_PARSER_UNCERTAIN = "PARSER_UNCERTAIN"

#: Closed-vocabulary extension: the original migration statuses plus the
#: parser-uncertainty status used when the evidence machinery itself cannot
#: be trusted (masking failure, loader rejection, unexpected parser errors).
#: New validations use this set; the original ``MIGRATION_STATUSES`` frozenset
#: above stays untouched for existing consumers, and ``UnresolvedRow`` now
#: validates against this extended set (pure widening by one controlled
#: constant — see the comment at its ``__post_init__``).
MIGRATION_STATUSES_EXTENDED = MIGRATION_STATUSES | {STATUS_PARSER_UNCERTAIN}

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
    _interface_name_to_room_accessor,
    _resolve_dao_identity,
    build_class_scope_dao_var_map,
    build_dao_var_map,
    extract_mutation_pairs,
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
    deterministic; a parser failure on an inner owner propagates and fails
    closed upstream as one controlled ``PARSER_UNCERTAIN`` row.
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
                for d in find_callable_declarations(masked_text, inner)
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

    Returns ``(triples, first_failure)`` where ``triples`` is a list of
    deduplicated ``(accessor, fqcn, operation)`` tuples and
    ``first_failure`` is the FIRST controlled failure status encountered
    (or ``None``):

    * receiver identity neither present in ``dao_index`` nor resolvable
      through the merged DAO map      -> ``DAO_IDENTITY_UNRESOLVED``;
    * accessor mapping to several DAO FQCNs -> ``DAO_TARGET_AMBIGUOUS``.

    All pairs are examined — a failure never short-circuits emission of
    other provable triples — but the caller treats ANY failure as row debt.
    """
    merged = _merged_dao_var_maps(owner_decl, decl, masked_text, all_callables)
    body_text = decl.body if isinstance(decl.body, str) else ""
    pairs = extract_mutation_pairs(body_text, merged)
    triples = []
    seen = set()
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
        if triple not in seen:
            seen.add(triple)
            triples.append(triple)
    return (triples, first_failure)


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
    * barrier mode not exactly representable   -> BARRIER_MODE_UNRESOLVED;
    * path outside approved roots / unreadable -> OWNER_MISSING;
    * masking failure / parser failure /
      loader rejection                         -> PARSER_UNCERTAIN;
    * owner/callable resolution failures       -> the shared resolvers'
      OWNER_/CALLABLE_ statuses;
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
    """
    if dao_index is None:
        dao_index = build_dao_fqcn_index(repo_root)
    # Declared production source roots are resolved ONCE per batch; every
    # per-entry path gate below membership-checks against this set.  A
    # resolution failure leaves ``root_set`` None so every path fails the
    # gate closed (same OWNER_MISSING debt as an out-of-root path).
    root_set, _root_diagnostics = _declared_relative_root_set(repo_root)
    resolved_rows = []
    unresolved_rows = []
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

        # Barrier gate FIRST: only the exactly representable case proceeds.
        mode, barrier_status = convert_barrier_mode(entry)
        if barrier_status is not None:
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

            all_callables = find_callable_declarations(masked_text, owner_decl)
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
            triples, mutation_failure = resolve_mutations_for_callable(
                decl, owner_decl, masked_text, all_callables, dao_index
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
                unresolved_rows.append(
                    UnresolvedRow(
                        index,
                        legacy_class,
                        legacy_method,
                        STATUS_PARSER_UNCERTAIN,
                        "loader rejected row",
                    )
                )
                break
            resolved_rows.append(ResolvedRow(index, built))

    return MigrationResult(
        tuple(resolved_rows), tuple(unresolved_rows), len(legacy_entries)
    )


# ── Duplicate detection over migrated rows ────────────────────────────────────


def find_duplicate_mutation_keys(result):
    """Return sorted unique canonical mutation keys carried by 2+ rows."""
    counts: dict[str, int] = {}
    for row in result.resolved:
        key = row.entry.mutation_key().canonical_key()
        counts[key] = counts.get(key, 0) + 1
    return tuple(sorted(key for key, count in counts.items() if count >= 2))
