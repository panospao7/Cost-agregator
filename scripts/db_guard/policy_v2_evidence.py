"""Exact v2 source-evidence verification per PR-01.

Verifies that every mutation declared in a v2 policy document is backed by
exact source evidence in the production Kotlin tree, and that no unlisted
mutations exist in the verified callables:

* every entry's ``path`` must resolve under a declared production source
  root (the manifest-backed root set, or an explicitly provided
  ``SourceRootSet``) and be readable;
* each entry's owner class and callable must be located exactly once via
  the shared Kotlin callable parser (no sibling overloads — an ambiguous
  or missing declaration fails closed);
* callable bodies are masked before structural scanning so string literals
  and comments cannot forge evidence;
* DAO receivers are resolved through the shared parsing helpers and their
  identities must match the entry's declared FQCN exactly;
* declared mutations must be found in the body, and any mutation present
  in the body but absent from the policy is reported as unlisted;
* verification is per member: every entry in a group must find its own
  ``(dao_accessor, operation)`` pair in the verified callable body — not
  just the group representative's pair;
* the class-scope DAO map contains property/field-level declarations only:
  every member callable's declaration span is excluded from the owner
  slice — including every callable declared inside a named class/object
  nested within the owner body — so method-local aliases of sibling
  methods or of nested-owner members can never authorize another
  method's receiver; the slice spans the FULL owner declaration (header
  included), so constructor-parameter properties
  (``class Repo(private val dao: RawNotificationDao, ...)``) resolve
  exactly like body-level property declarations (GR-08a: a header-only
  property such as ``dao`` never matches the ``\\w+Dao`` naming convention,
  so missing it silently dropped every real ``dao.*`` mutation from the
  evidence);
* ``barrierMode`` metadata is locally consistency-checked (PR-GR-06
  Slice 1): a ``direct`` entry must show exact local direct-barrier syntax
  before EVERY mutation in its own callable body; ``helper`` and
  ``workerMediated`` entries carry no local requirement here.  No
  dominance/reachability claims are made and mediation is never inferred;
* when a Room inventory is provided, every group member's declared
  ``daoFqcn`` is cross-checked against the RESOLVED identity of the
  accessor evidenced at the mutation site (Plan Step-1 #8): an accessor
  whose inventory DAO FQCN set does not contain the declared FQCN fails
  the group with the controlled ``DB_V2_POLICY_DAO_FQCN_MISMATCH`` code.
  Without an inventory the check cannot run — callable bodies yield
  accessor-scoped identities only, never reliable DAO FQCNs — and that
  documented limitation stays.

There are no fallbacks: parser uncertainty, unsupported kinds, unreadable
files, and unresolved DAO accessors all surface as controlled
``DB_V2_POLICY_*`` codes from ``policy_errors`` (the closed vocabulary,
registered one-to-one as catalog diagnostics). Per-group processing never
raises: any unexpected exception between path validation and mutation
checking is converted into one controlled ``DB_V2_POLICY_PARSER_UNCERTAIN``
finding carrying only the relative path and the exception class name, and
verification continues with the next group. Context stays bounded —
controlled codes, target names, counts — never raw payloads.

GR-06 repair: callable discovery runs under the shared parser's PR-GR-05
tolerant type-resolution semantics (``tolerate_unresolved_types=True`` at
every ``find_callable_declarations`` call site, matching the approved
candidate generator).  A SIBLING declaration whose parameter/receiver types
the closed-world resolver cannot resolve no longer aborts its whole file as
``DB_V2_POLICY_PARSER_UNCERTAIN``: it is retained with status
``TYPE_UNRESOLVED`` and can never match or authorize anything, because every
evidence gate filters to ``RESOLVED_EXACTLY`` declarations at
match_mutation-grade resolution exactly like the candidate generator.  A
TARGET whose every same-name declaration is retained under that status fails
closed with the distinct controlled ``DB_V2_POLICY_SIGNATURE_UNRESOLVED``
code (never ``PARSER_UNCERTAIN``, never a silent pass).  Every other failure
family (masking, structure, signature grammar) stays fatal in both modes.

The result is an :class:`EvidenceResult`: deterministic frozen dataclasses
grouped by canonical callable key, with per-group trust, sorted mutation
keys, bounded diagnostics, and a JSON-ready ``to_dict()`` that carries
repository-relative paths only.
"""
from __future__ import annotations

import os

from dataclasses import dataclass

from ..kotlin_callable_parser import (
    ParserError,
    canonical_source_path,
    find_owner_declarations,
    find_callable_declarations,
    mask_kotlin_source,
    resolve_callable,
)
from ..db_policy_signature import (
    SignatureError,
    normalize_type_text,
)
from .policy_model import (
    BarrierMode,
    CallableKind,
    PolicyEntry,
    match_mutation,
)
from .policy_errors import (
    KNOWN_POLICY_ERROR_CODES,
    PolicyError,
    DB_V2_POLICY_PATH_OUTSIDE_ROOTS,
    DB_V2_POLICY_FILE_UNREADABLE,
    DB_V2_POLICY_OWNER_MISSING,
    DB_V2_POLICY_OWNER_AMBIGUOUS,
    DB_V2_POLICY_CALLABLE_MISSING,
    DB_V2_POLICY_CALLABLE_AMBIGUOUS,
    DB_V2_POLICY_KIND_UNSUPPORTED,
    DB_V2_POLICY_PARSER_UNCERTAIN,
    DB_V2_POLICY_BODY_UNSUPPORTED,
    DB_V2_POLICY_DAO_ACCESSOR_UNRESOLVED,
    DB_V2_POLICY_DAO_FQCN_MISMATCH,
    DB_V2_POLICY_MUTATION_NOT_FOUND,
    DB_V2_POLICY_UNLISTED_MUTATION,
    DB_V2_POLICY_DAO_AMBIGUOUS,
    DB_V2_POLICY_BARRIER_METADATA_INCONSISTENT,
    DB_V2_POLICY_SIGNATURE_UNRESOLVED,
)
from .policy_parsing import (
    _barrier_before_line,
    build_class_scope_dao_var_map,
    build_dao_var_map,
    _extract_mutation_matches,
    _interface_name_to_room_accessor,
    _resolve_dao_identity,
)
from .source_roots import (
    DB_SOURCE_ROOT_UNDECLARED,
    SourceRoot,
    SourceRootSet,
    is_declared_production_path,
    resolve_source_root_set,
)
from .declaration_scanner import (
    build_project_type_index,
    declared_root_pairs,
)

__all__ = [
    "verify_v2_policy_source_evidence",
    "EvidenceResult",
    "CallableGroupResult",
    "EvidenceDiagnostic",
    "PolicyError",
    "DB_V2_POLICY_PATH_OUTSIDE_ROOTS",
    "DB_V2_POLICY_FILE_UNREADABLE",
    "DB_V2_POLICY_OWNER_MISSING",
    "DB_V2_POLICY_OWNER_AMBIGUOUS",
    "DB_V2_POLICY_CALLABLE_MISSING",
    "DB_V2_POLICY_CALLABLE_AMBIGUOUS",
    "DB_V2_POLICY_KIND_UNSUPPORTED",
    "DB_V2_POLICY_PARSER_UNCERTAIN",
    "DB_V2_POLICY_BODY_UNSUPPORTED",
    "DB_V2_POLICY_DAO_ACCESSOR_UNRESOLVED",
    "DB_V2_POLICY_DAO_FQCN_MISMATCH",
    "DB_V2_POLICY_MUTATION_NOT_FOUND",
    "DB_V2_POLICY_UNLISTED_MUTATION",
    "DB_V2_POLICY_DAO_AMBIGUOUS",
    "DB_V2_POLICY_BARRIER_METADATA_INCONSISTENT",
    "DB_V2_POLICY_SIGNATURE_UNRESOLVED",
]


# ---------------------------------------------------------------------------
# Evidence result model (PR-GR-06 Slice 1) — frozen and deterministic
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class EvidenceDiagnostic:
    """One bounded, controlled v2-evidence diagnostic for one group.

    ``code`` must be a member of the closed ``KNOWN_POLICY_ERROR_CODES``
    vocabulary (fail closed on anything else); ``context`` is a sorted
    tuple of ``(key, value)`` pairs so the diagnostic is immutable and
    deterministically orderable. Values are bounded scalars only —
    relative paths, FQCNs, method names, counts, controlled status
    constants — never raw source or payloads.
    """

    code: str
    context: tuple = ()

    def __post_init__(self):
        if self.code not in KNOWN_POLICY_ERROR_CODES:
            raise ValueError("unknown policy error code")
        if not isinstance(self.context, tuple) or any(
            not isinstance(item, tuple)
            or len(item) != 2
            or not isinstance(item[0], str)
            for item in self.context
        ):
            raise TypeError("context must be a tuple of (str, value) pairs")

    @classmethod
    def from_policy_error(cls, error):
        """Build one diagnostic from a ``PolicyError`` (sorted context)."""
        return cls(code=error.code, context=tuple(sorted(error.context.items())))

    @property
    def context_dict(self):
        """The bounded context as a mapping (deterministic insertion order)."""
        return dict(self.context)

    def to_dict(self):
        """JSON-ready bounded rendering with sorted context keys."""
        return {"code": self.code, "context": {k: v for k, v in self.context}}


@dataclass(frozen=True)
class CallableGroupResult:
    """Verification outcome for ONE callable group (one canonical key).

    ``trusted`` is True only when the group produced zero diagnostics.
    ``mutation_keys`` holds the DISTINCT actual mutation keys evidenced in
    the verified body as sorted ``"<resolved_accessor>|<operation>"``
    strings (bodies do not yield DAO FQCNs reliably, so actual keys stay
    accessor-scoped); ``policy_keys`` holds the group's declared mutation
    keys as sorted ``MutationKey.canonical_key()`` strings. Both tuples
    are sorted so the result is deterministic.
    """

    callable_key_canonical: str
    trusted: bool
    mutation_keys: tuple
    policy_keys: tuple
    diagnostics: tuple

    def to_dict(self):
        """JSON-ready rendering; paths inside keys are repo-relative."""
        return {
            "callable_key": self.callable_key_canonical,
            "trusted": self.trusted,
            "mutation_keys": list(self.mutation_keys),
            "policy_keys": list(self.policy_keys),
            "diagnostics": [d.to_dict() for d in self.diagnostics],
        }


@dataclass(frozen=True)
class EvidenceResult:
    """Whole-batch verification outcome across all callable groups.

    ``trusted`` is True only when NO group and no batch-level stage
    produced a diagnostic. ``groups`` is sorted by canonical callable key;
    ``diagnostics`` aggregates every group's diagnostics in group order;
    a failed declared-root-set resolution carries its single batch-level
    diagnostic here with empty groups. ``mutation_key_count`` counts
    DISTINCT actual keys across all groups; ``policy_mutation_key_count``
    counts DISTINCT declared policy mutation keys across all entries.
    """

    trusted: bool
    groups: tuple
    diagnostics: tuple
    mutation_key_count: int
    policy_mutation_key_count: int

    def to_dict(self):
        """JSON-ready rendering with deterministic ordering throughout."""
        return {
            "trusted": self.trusted,
            "groups": [g.to_dict() for g in self.groups],
            "diagnostics": [d.to_dict() for d in self.diagnostics],
            "mutation_key_count": self.mutation_key_count,
            "policy_mutation_key_count": self.policy_mutation_key_count,
        }


def _declared_relative_root_set(repo_root):
    """Resolve the effective declared production root set, re-anchored.

    ``resolve_source_root_set`` returns manifest-declared roots as
    repository-relative POSIX paths, but its implicit conventional fallback
    (synthetic fixtures and embedders without a manifest) carries each root
    as an ABSOLUTE native-separator path anchored at its enclosing project.
    Absolute roots are re-anchored here with the exact parity convention of
    ``declaration_scanner.declared_root_pairs`` so membership checks against
    repository-relative policy paths stay possible.  Returns
    ``(SourceRootSet, ())`` on success or ``(None, diagnostics)`` fail
    closed; an absolute root that cannot be anchored is dropped so none of
    its paths can ever authorize anything.
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


def _inventory_dao_fqcn_index(room_inventory):
    """Index the inventory's DAO identity data by Room accessor name.

    Implements the exact accessor->FQCN relationship of the Room inventory:
    every inventoried ``@Dao`` declaration is a
    :class:`~scripts.db_guard.dao_accessors.DaoId` carrying its
    fully-qualified name, and a DAO's Room-generated database accessor is
    its simple name with the first character lowercased
    (``ExpenseGroupDao`` -> ``expenseGroupDao``) — the same shared rule
    :func:`policy_parsing._interface_name_to_room_accessor` applies to
    property declarations and
    :func:`policy_v2_candidate.build_dao_fqcn_index` applies to freshly
    scanned trees.  Returns ``{accessor: frozenset(fqcn)}``; several FQCNs
    share one accessor only when DAO simple names genuinely collide across
    packages, which downstream resolution reports as ambiguity instead of
    guessing.  An inventory carrying no DAO identity data yields an empty
    index so every accessor cross-check fails closed; identity elements
    without a usable FQCN contribute nothing rather than guessing.
    """
    daos = getattr(room_inventory, "daos", None)
    if not daos:
        return {}
    index = {}
    for dao in daos:
        fqcn = getattr(dao, "fqcn", None)
        if not isinstance(fqcn, str) or not fqcn:
            continue
        simple = fqcn.rsplit(".", 1)[-1]
        accessor = _interface_name_to_room_accessor(simple)
        index.setdefault(accessor, set()).add(fqcn)
    return {accessor: frozenset(fqcns) for accessor, fqcns in index.items()}


def verify_v2_policy_source_evidence(
    entries, repo_root, source_roots=None, room_inventory=None
):
    """Verify v2 policy entries against exact production source evidence.

    The verification is staged; each stage fails closed with exactly one
    controlled ``DB_V2_POLICY_*`` code per failing group and never falls
    back to weaker evidence.

    Stage 1 — grouping and per-group file/owner resolution:

    1. Group entries by ``entry.callable_key().canonical_key()`` so every
       distinct callable identity is verified once.
    2. Iterate groups in sorted canonical-key order for determinism; use
       the first entry of each group as its representative.
     3. Resolve the DECLARED production source-root set: an explicitly
        provided ``source_roots`` ``SourceRootSet`` is used verbatim;
        otherwise the manifest-backed set is resolved via
        ``resolve_source_root_set``.  A resolution failure fails closed as
        exactly one bounded ``DB_V2_POLICY_PARSER_UNCERTAIN`` batch-level
        diagnostic (reason ``source-roots-unresolved`` plus the controlled
        diagnostic codes), and each path outside the declared roots yields
        ``DB_V2_POLICY_PATH_OUTSIDE_ROOTS``.
    4. Read the file as UTF-8 relative to ``repo_root``; any ``OSError``
       yields ``DB_V2_POLICY_FILE_UNREADABLE``.
    5. Mask string literals/comments (``mask_kotlin_source``) so they
       cannot forge evidence, then locate owner declarations whose
       ``owner`` equals the entry's ``owner_fqcn`` exactly:
       zero matches -> ``DB_V2_POLICY_OWNER_MISSING``;
       more than one -> ``DB_V2_POLICY_OWNER_AMBIGUOUS``;
       a non-``RESOLVED_EXACTLY`` parser status ->
       ``DB_V2_POLICY_PARSER_UNCERTAIN``.

    Stage 2 — kind gate + callable resolution + body extraction +
    DAO/mutation completeness: only ``FUNCTION`` entries are verifiable;
    discovery runs under the parser's tolerant type-resolution semantics
    (GR-06), so sibling ``TYPE_UNRESOLVED`` debt never poisons an exact
    target while a target retained under that status fails with the distinct
    ``DB_V2_POLICY_SIGNATURE_UNRESOLVED`` code; the representative entry's
    callable must resolve to exactly one braced declaration, and
    ``_check_mutations`` then verifies EVERY group
    member's own ``(dao_accessor, operation)`` pair against that body (not
    just the representative's) while reporting any unlisted mutation
    present in the body.  When ``room_inventory`` is provided, its DAO
    identity data additionally cross-checks every member's declared
    ``daoFqcn`` against the inventory FQCN set of the accessor resolved at
    the mutation site; a set not containing the declared FQCN fails the
    group with ``DB_V2_POLICY_DAO_FQCN_MISMATCH`` (Plan Step-1 #8).  The
    policy-level accessor ambiguity gate keeps its historical precedence,
    so an accessor backed by several same-simple-name DAOs still reports
    ``DB_V2_POLICY_DAO_AMBIGUOUS`` rather than a guessed identity.

    Stage 3 — barrierMode metadata local consistency (PR-GR-06 Slice 1):
    for an otherwise-clean group whose entries declare ``barrierMode:
    direct``, every mutation line in the verified body must be preceded by
    exact local direct-barrier syntax (shared ``_barrier_before_line``
    machinery); otherwise one ``DB_V2_POLICY_BARRIER_METADATA_INCONSISTENT``
    diagnostic marks the group untrusted.  ``helper``/``workerMediated``
    groups carry no local requirement.  No dominance/reachability claims.

    Per-group processing never raises: any unexpected exception between
    path validation and mutation checking is converted into one
    ``DB_V2_POLICY_PARSER_UNCERTAIN`` finding with bounded context
    (relative path, exception class name), and verification continues with
    the next group.

    Error context stays bounded: relative paths, FQCNs, counts, and
    controlled status codes only — never raw source or payloads.

    Args:
        entries: Sequence of :class:`~scripts.db_guard.policy_model.PolicyEntry`
            loaded from a v2 policy document.
        repo_root: Repository root used to resolve canonical source paths.
        source_roots: Optional explicit :class:`~scripts.db_guard.source_roots.SourceRootSet`
            used verbatim instead of resolving the manifest-backed declared
            root set; when omitted the declared set is resolved from
            ``repo_root`` (existing behavior).
        room_inventory: Optional Room inventory whose DAO identity data
            (``daos`` tuple of ``DaoId`` FQCNs) cross-checks each group
            member's declared ``daoFqcn`` against the resolved identity of
            the accessor evidenced at the mutation site; a mismatch emits
            ``DB_V2_POLICY_DAO_FQCN_MISMATCH`` for that group.  When
            omitted, the check cannot run — bodies yield accessor-scoped
            identities only, never reliable DAO FQCNs — and that documented
            limitation stays (a pure daoFqcn swap over an otherwise exact
            body is not detectable without inventory ground truth).

    Returns:
        :class:`EvidenceResult` — deterministic frozen model with one
        :class:`CallableGroupResult` per canonical callable key (sorted),
        per-group trust (zero diagnostics for that group), aggregated
        bounded diagnostics, and distinct actual/policy mutation-key
        counts.  A failed declared-root-set resolution yields an untrusted
        result with empty groups and exactly one batch-level
        ``DB_V2_POLICY_PARSER_UNCERTAIN`` diagnostic for the whole batch.
    """
    groups = {}
    for entry in entries:
        groups.setdefault(entry.callable_key().canonical_key(), []).append(entry)
    policy_mutation_keys = sorted(
        {entry.mutation_key().canonical_key() for entry in entries}
    )
    if not groups:
        return EvidenceResult(
            trusted=True,
            groups=(),
            diagnostics=(),
            mutation_key_count=0,
            policy_mutation_key_count=0,
        )
    if source_roots is not None:
        root_set, root_diagnostics = source_roots, ()
    else:
        root_set, root_diagnostics = _declared_relative_root_set(repo_root)
    if root_set is None:
        # Fail closed with one bounded parser-uncertain batch diagnostic:
        # without a resolved declared root set no path can be authorized.
        # Context carries the controlled reason plus the controlled
        # diagnostic codes only — never raw paths or exception text.
        batch_diagnostic = EvidenceDiagnostic.from_policy_error(
            PolicyError(
                DB_V2_POLICY_PARSER_UNCERTAIN,
                {
                    "reason": "source-roots-unresolved",
                    "codes": ",".join(
                        sorted({code for code, _context in root_diagnostics})
                    ),
                },
            )
        )
        return EvidenceResult(
            trusted=False,
            groups=(),
            diagnostics=(batch_diagnostic,),
            mutation_key_count=0,
            policy_mutation_key_count=len(policy_mutation_keys),
        )
    group_results = []
    actual_key_union = set()
    # Plan Step-1 #8: the inventory DAO identity index is built once per
    # batch (None when no inventory was provided, keeping the documented
    # no-inventory limitation) and threaded unchanged into every group.
    dao_fqcn_index = (
        _inventory_dao_fqcn_index(room_inventory)
        if room_inventory is not None
        else None
    )
    # GR-07 hardening step A: the project-wide type index is built ONCE per
    # verification run over the SAME declared production roots this batch
    # already resolved, then threaded into every callable discovery below
    # (the target scan and the nested-owner exclusion rescans alike).  A
    # parameter/receiver type declared in another production file now
    # resolves through a unique simple-name match; an ambiguous simple name
    # still fails closed as TYPE_UNRESOLVED debt.  Deterministic: same tree
    # -> same index -> same resolutions.
    project_types = build_project_type_index(
        declared_root_pairs(repo_root, root_set)
    )
    for canonical_key in sorted(groups):
        group = groups[canonical_key]
        entry = group[0]
        ck = entry.callable_key()
        group_errors = []
        group_actual_keys = ()
        try:
            if not is_declared_production_path(root_set, ck.path):
                group_errors.append(
                    PolicyError(
                        DB_V2_POLICY_PATH_OUTSIDE_ROOTS,
                        {"path": ck.path},
                    )
                )
            else:
                abs_path = os.path.join(repo_root, ck.path)
                try:
                    with open(abs_path, "r", encoding="utf-8") as handle:
                        text = handle.read()
                except OSError:
                    group_errors.append(
                        PolicyError(
                            DB_V2_POLICY_FILE_UNREADABLE,
                            {"path": ck.path},
                        )
                    )
                else:
                    masked = mask_kotlin_source(text)
                    owners = [
                        o
                        for o in find_owner_declarations(masked)
                        if o.owner == ck.owner_fqcn
                    ]
                    if len(owners) == 0:
                        group_errors.append(
                            PolicyError(
                                DB_V2_POLICY_OWNER_MISSING,
                                {"owner_fqcn": ck.owner_fqcn, "path": ck.path},
                            )
                        )
                    elif len(owners) > 1:
                        group_errors.append(
                            PolicyError(
                                DB_V2_POLICY_OWNER_AMBIGUOUS,
                                {"owner_fqcn": ck.owner_fqcn, "count": len(owners)},
                            )
                        )
                    else:
                        owner = owners[0]
                        if owner.status != "RESOLVED_EXACTLY":
                            group_errors.append(
                                PolicyError(
                                    DB_V2_POLICY_PARSER_UNCERTAIN,
                                    {
                                        "owner_fqcn": ck.owner_fqcn,
                                        "status": owner.status,
                                    },
                                )
                            )
                        elif ck.kind != CallableKind.FUNCTION:
                            group_errors.append(
                                PolicyError(
                                    DB_V2_POLICY_KIND_UNSUPPORTED,
                                    {"kind": ck.kind.value, "path": ck.path},
                                )
                            )
                        else:
                            group_errors, group_actual_keys = _verify_callable_group(
                                group, ck, owner, masked, text, group_errors,
                                dao_fqcn_index, project_types,
                            )
        except Exception as exc:
            group_errors.append(
                PolicyError(
                    DB_V2_POLICY_PARSER_UNCERTAIN,
                    {"path": ck.path, "exc_type": type(exc).__name__},
                )
            )
        diagnostics = tuple(
            EvidenceDiagnostic.from_policy_error(e) for e in group_errors
        )
        actual_key_union.update(group_actual_keys)
        group_results.append(
            CallableGroupResult(
                callable_key_canonical=canonical_key,
                trusted=not diagnostics,
                mutation_keys=tuple(sorted(group_actual_keys)),
                policy_keys=tuple(
                    sorted({g.mutation_key().canonical_key() for g in group})
                ),
                diagnostics=diagnostics,
            )
        )
    all_diagnostics = tuple(
        d for g in group_results for d in g.diagnostics
    )
    return EvidenceResult(
        trusted=not all_diagnostics,
        groups=tuple(group_results),
        diagnostics=all_diagnostics,
        mutation_key_count=len(actual_key_union),
        policy_mutation_key_count=len(policy_mutation_keys),
    )


def _verify_callable_group(group, ck, owner, masked, text, group_errors,
                           dao_fqcn_index=None, project_types=None):
    """Stages 2-3 for one group whose owner and kind already resolved.

    Discovers the owner's member callables under the parser's tolerant
    type-resolution semantics (GR-06: a sibling retained with
    ``TYPE_UNRESOLVED`` status can never match or authorize) and the GR-07
    step-A project-wide type index (``None`` keeps the pure single-file
    closed world), then resolves the representative callable to exactly one
    braced declaration (fail-closed on missing/ambiguous/unsupported
    statuses; a target whose every same-name declaration is type-unresolved
    fails with the distinct ``DB_V2_POLICY_SIGNATURE_UNRESOLVED`` code), then
    runs ``_check_mutations`` for DAO resolution, both-direction mutation
    completeness, the inventory-backed daoFqcn cross-check (``None`` keeps
    the documented no-inventory limitation), plus the barrierMode metadata
    local-consistency gate.
    Returns ``(group_errors, actual_mutation_keys)``; appends at most one
    controlled diagnostic per failing stage, preserving the historical
    emission order and early-return semantics.
    """
    callables = find_callable_declarations(
        masked, owner, tolerate_unresolved_types=True,
        project_types=project_types,
    )
    status = resolve_callable(
        callables,
        ck.owner_fqcn,
        ck.method,
        ck.receiver,
        ck.parameter_types,
    )
    if status == "METHOD_MISSING":
        group_errors.append(
            PolicyError(
                DB_V2_POLICY_CALLABLE_MISSING,
                {"method": ck.method, "path": ck.path},
            )
        )
        return group_errors, ()
    if status == "AMBIGUOUS_OVERLOAD":
        group_errors.append(
            PolicyError(
                DB_V2_POLICY_CALLABLE_AMBIGUOUS,
                {
                    "method": ck.method,
                    "count": len(
                        [
                            d
                            for d in callables
                            if d.owner == owner.owner
                            and d.signature.function_name == ck.method
                        ]
                    ),
                },
            )
        )
        return group_errors, ()
    if status != "RESOLVED_EXACTLY":
        # GR-06 repair: tolerant discovery retains same-name declarations
        # whose own parameter/receiver types cannot be resolved exactly
        # instead of aborting the whole file.  When EVERY same-name
        # declaration of the owner carries that ``TYPE_UNRESOLVED`` status,
        # the named target exists but its signature is unresolvable debt:
        # one distinct controlled code -- never the generic parser-uncertain
        # family, never CALLABLE_MISSING, never a silent pass.  Any
        # resolvable same-name spelling keeps the historical
        # SIGNATURE_UNSUPPORTED -> PARSER_UNCERTAIN mapping so genuine
        # overload mismatches stay pinned exactly as before.
        same_name = [
            d
            for d in callables
            if d.owner == owner.owner
            and d.signature.function_name == ck.method
        ]
        if status == "SIGNATURE_UNSUPPORTED" and same_name and all(
            d.status == "TYPE_UNRESOLVED" for d in same_name
        ):
            group_errors.append(
                PolicyError(
                    DB_V2_POLICY_SIGNATURE_UNRESOLVED,
                    {"method": ck.method, "status": "TYPE_UNRESOLVED"},
                )
            )
            return group_errors, ()
        group_errors.append(
            PolicyError(
                DB_V2_POLICY_PARSER_UNCERTAIN,
                {"method": ck.method, "status": status},
            )
        )
        return group_errors, ()
    try:
        params_norm = tuple(
            normalize_type_text(p, allow_vararg=True) for p in ck.parameter_types
        )
        recv_norm = (
            normalize_type_text(ck.receiver) if ck.receiver is not None else None
        )
    except SignatureError:
        group_errors.append(
            PolicyError(
                DB_V2_POLICY_PARSER_UNCERTAIN,
                {"method": ck.method, "status": "SIGNATURE_ERROR"},
            )
        )
        return group_errors, ()
    matches = [
        d
        for d in callables
        if d.owner == owner.owner
        and d.signature.function_name == ck.method
        and d.signature.receiver == recv_norm
        and d.signature.parameter_types == params_norm
        and d.status == "RESOLVED_EXACTLY"
    ]
    if len(matches) != 1:
        group_errors.append(
            PolicyError(
                (
                    DB_V2_POLICY_CALLABLE_AMBIGUOUS
                    if len(matches) > 1
                    else DB_V2_POLICY_CALLABLE_MISSING
                ),
                {"method": ck.method, "count": len(matches)},
            )
        )
        return group_errors, ()
    decl = matches[0]
    if decl.body is None or decl.status != "RESOLVED_EXACTLY":
        group_errors.append(
            PolicyError(
                DB_V2_POLICY_BODY_UNSUPPORTED,
                {"method": ck.method},
            )
        )
        return group_errors, ()
    actual_keys = _check_mutations(
        group, ck, owner, masked, text, decl, callables, group_errors,
        dao_fqcn_index, project_types,
    )
    return group_errors, actual_keys


def _callable_body_slice_line_indices(masked, owner, callables, line_count,
                                      project_types=None):
    """Map member callable spans to line indices within the owner slice.

    Returns the set of 0-based line indices (relative to
    ``masked[owner.start_offset:owner.body_end].splitlines()`` — the full
    owner declaration, header included, matching the class-scope DAO map's
    slice) covered by any member callable declaration's char span in
    ``masked``, so a class-scope scan can skip entire method declarations
    and see only property/field-level declarations.  Indices outside the
    slice are clamped away; the source is read with universal newlines, so
    counting ``"\\n"`` matches ``splitlines()`` indexing.

    ``callables`` holds only the owner's DIRECT members:
    ``find_callable_declarations`` skips ``fun`` declarations inside nested
    named classes/objects, so their spans are collected here separately.
    Every owner declaration fully contained in ``owner``'s body (other than
    ``owner`` itself) is rescanned and its callable spans join the same
    exclusion set.  Without this, a method-local DAO alias inside a nested
    owner would survive the class-scope scan and could overwrite a
    same-named class property in the DAO variable map.  Spans are expanded
    in sorted offset order so the mapping stays deterministic.  Inner
    discovery runs under the same tolerant type-resolution semantics as
    primary discovery (GR-06): retained ``TYPE_UNRESOLVED`` spans join the
    exclusion set — an exclusion-only use, so extra retained spans can only
    shrink the class-scope property scan, never widen authorization — while
    every other parser failure family still propagates and fails closed
    upstream as one controlled ``DB_V2_POLICY_PARSER_UNCERTAIN`` finding.
    """
    base_line = masked.count("\n", 0, owner.start_offset)
    spans = [(c.start_offset, c.end_offset) for c in callables]
    for inner in find_owner_declarations(masked):
        if inner == owner:
            continue
        if (
            owner.body_start <= inner.body_start
            and inner.body_end <= owner.body_end
        ):
            spans.extend(
                (d.start_offset, d.end_offset)
                for d in find_callable_declarations(
                    masked, inner, tolerate_unresolved_types=True,
                    project_types=project_types,
                )
            )
    excluded = set()
    for start_offset, end_offset in sorted(spans):
        first = masked.count("\n", 0, start_offset) - base_line
        last = masked.count("\n", 0, end_offset - 1) - base_line
        excluded.update(
            ln for ln in range(first, last + 1) if 0 <= ln < line_count
        )
    return excluded


def _callable_direct_barrier_evidence(text, decl, relative_mutation_linenos):
    """Direct-barrier verdict for one verified callable (GR-05 approach).

    Reuses :func:`policy_parsing._barrier_before_line` — the exact machinery
    behind the legacy scanner's MISSING_WRITE_BARRIER gate and PR-GR-05's
    ``_callable_direct_barrier_evidence`` — so v2 evidence never invents a
    second barrier matcher.  For EVERY distinct extracted mutation line, the
    callable must show a real unqualified
    ``writeBarrier.checkWritesAllowed(...)`` / ``writeBarrier.runWrite(...)``
    call strictly between the fun declaration line and that mutation line
    (statefully comment/string-masked, receiver-aware).  Returns ``True``
    only when every mutation is so preceded; any disproof — or an empty
    position set, or a bodyless declaration — returns ``False`` so a
    ``barrierMode: direct`` claim can never pass without full per-mutation
    proof.

    ``text`` is the source text the declaration offsets refer to (masking
    preserves offsets and newline positions, so raw and masked texts yield
    identical line numbers); ``relative_mutation_linenos`` are 0-based line
    indices within ``decl.body`` as reported by
    :func:`policy_parsing._extract_mutation_matches`.
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


def _barrier_metadata_consistent(group, ck, text, decl, mutation_linenos):
    """Local-consistency check of the group's ``barrierMode`` metadata.

    Plan rule (PR-GR-06 Slice 1): a ``direct`` claim requires exact local
    direct-barrier syntax before EVERY mutation in the callable body;
    ``helper`` / ``workerMediated`` carry no local requirement here (their
    mediation proof is a later slice — nothing is asserted beyond "direct
    is not falsely claimed").  If ANY group member claims ``direct``, full
    per-mutation proof is required (fail closed on mixed-mode groups).
    No dominance/reachability claims are made.
    """
    if not any(m.barrier_mode is BarrierMode.DIRECT for m in group):
        return True
    return _callable_direct_barrier_evidence(text, decl, mutation_linenos)


def _check_mutations(group, ck, owner, masked, text, decl, callables, errors,
                     dao_fqcn_index=None, project_types=None):
    """Stage 3: DAO accessor resolution + both-direction mutation completeness
    + inventory-backed daoFqcn cross-check + barrierMode metadata local
    consistency.

    The required-pair check runs for EVERY member of ``group``, not just the
    representative ``ck``: each member's own ``(dao_accessor, operation)``
    pair must be found among the body's resolved mutation pairs (via the
    merged class/method DAO map and the ``_resolve_dao_identity``
    fallback), otherwise ``DB_V2_POLICY_MUTATION_NOT_FOUND`` is emitted with
    that member's operation/accessor.  The class-scope DAO map is built from
    the FULL owner declaration slice (header included, so constructor-
    parameter properties resolve — GR-08a) with every member callable's
    declaration span excluded — including callables declared inside nested
    named classes/objects within the owner body — so only property/field-
    level DAO declarations remain and sibling methods' or nested-owner
    members' local aliases can never leak into scope.  A member's
    ``dao_accessor`` may spell the source property alias (``dao``) rather
    than the derived Room accessor identity (``rawNotificationDao``): the
    accessor spelling is resolved through the SAME merged map before the
    required-pair, ambiguity, and unlisted comparisons, so the alias bridge
    closes the spelling gap without widening coverage to any other DAO.

    When ``dao_fqcn_index`` is not ``None`` (a Room inventory was provided),
    every member's declared ``daoFqcn`` is cross-checked against the
    inventory FQCN set of the accessor resolved at the mutation site (the
    same merged-map resolution that evidenced the required pair); a set not
    containing the declared FQCN proves the policy misidentifies the DAO
    behind a correctly-evidenced accessor and emits one controlled
    ``DB_V2_POLICY_DAO_FQCN_MISMATCH`` for the group (Plan Step-1 #8).
    ``None`` keeps the documented no-inventory limitation: bodies yield
    accessor-scoped identities only, so no FQCN ground truth exists.

    Returns the sorted tuple of DISTINCT actual mutation keys evidenced in
    the body as ``"<resolved_accessor>|<operation>"`` strings (empty when
    extraction itself failed before any key could be resolved).  Emission
    order and early-return semantics are unchanged: required-pair failure,
    then accessor ambiguity, then daoFqcn mismatch, then unlisted mutation;
    the barrierMode metadata gate runs last and only for an otherwise-clean
    group, so the historical one-controlled-code-per-failing-group contract
    is preserved.
    """
    body_lines = decl.body.splitlines()
    # GR-08a: the class-scope slice spans the FULL owner declaration —
    # header included — so constructor-parameter properties
    # (``class Repo(private val dao: RawNotificationDao, ...)``) enter the
    # class-scope DAO map exactly like body-level property declarations
    # (the legacy scanner's class map has always spanned the declaration
    # line).  Sliced from ``body_start`` alone, a header-only property such
    # as ``dao`` was invisible; because ``dao`` never matches the
    # ``\w+Dao`` naming convention either, every real ``dao.*`` mutation in
    # the body silently vanished from the evidence.  The header can contain
    # no method body and no nested owner, so method-local aliases of
    # siblings or nested owners still cannot leak into the class map.
    owner_slice = masked[owner.start_offset:owner.body_end]
    slice_lines = owner_slice.splitlines()
    class_map = build_class_scope_dao_var_map(
        slice_lines,
        0,
        len(slice_lines) - 1,
        excluded_line_numbers=_callable_body_slice_line_indices(
            masked, owner, callables, len(slice_lines), project_types,
        ),
    )
    method_map = build_dao_var_map(body_lines)
    merged = dict(class_map)
    merged.update(method_map)
    matches = _extract_mutation_matches(decl.body, var_map=merged)
    pairs = []
    pair_seen = set()
    mutation_linenos = set()
    for match in matches:
        mutation_linenos.add(match["lineno"])
        pair = (match["dao"], match["op"])
        if pair not in pair_seen:
            pairs.append(pair)
            pair_seen.add(pair)

    def _resolved(identity, accessor):
        if identity == accessor:
            return accessor
        return _resolve_dao_identity(identity, merged)

    def _accessor_identity(accessor):
        # GR-08a: a policy row may spell the SOURCE property/local alias
        # (``dao``) instead of the derived Room accessor identity
        # (``rawNotificationDao``).  Resolving the accessor spelling through
        # the SAME scoped map the extraction used keeps both sides
        # comparable without guessing; a spelling with no mapping and no
        # ``\w+Dao`` shape stays itself, so unknown accessors keep failing
        # closed exactly as before.
        resolved = _resolve_dao_identity(accessor, merged)
        return accessor if resolved is None else resolved

    listed = {(g.dao_accessor, g.operation) for g in group}
    listed_identities = {
        (_accessor_identity(g.dao_accessor), g.operation) for g in group
    }
    actual = set()
    ambiguous = False
    accessor_fqcns = {}
    for g in group:
        accessor_fqcns.setdefault(g.dao_accessor, set()).add(g.dao_fqcn)
    # Ambiguity is a property of the resolved DAO identity, not of one
    # spelling: two daoFqcn values behind the alias ``dao`` and two behind
    # ``rawNotificationDao`` describe the same unresolvable accessor and
    # must take the same DB_V2_POLICY_DAO_AMBIGUOUS path.
    identity_fqcns = {}
    for accessor, fqcns in accessor_fqcns.items():
        identity_fqcns.setdefault(
            _accessor_identity(accessor), set()
        ).update(fqcns)
    for (identity, op) in pairs:
        # Resolve every body pair's identity canonically (scoped map first,
        # then the ``\w+Dao`` naming convention).  ``ck`` is a CallableKey
        # and carries no dao_accessor; the ambiguity/unlisted checks compare
        # against EVERY listed accessor, not the representative's.
        resolved = _resolve_dao_identity(identity, merged)
        if resolved is None:
            continue
        if len(identity_fqcns.get(resolved, ())) > 1:
            ambiguous = True
        actual.add((resolved, op))
    actual_keys = tuple(sorted(f"{acc}|{op}" for (acc, op) in actual))

    for m in group:
        accessor_identity = _accessor_identity(m.dao_accessor)
        required_found = any(
            op == m.operation
            and (
                _resolved(identity, m.dao_accessor) == m.dao_accessor
                or _resolved(identity, accessor_identity) == accessor_identity
            )
            for (identity, op) in pairs
        )
        if not required_found:
            errors.append(PolicyError(
                DB_V2_POLICY_MUTATION_NOT_FOUND,
                {"operation": m.operation, "dao_accessor": m.dao_accessor},
            ))
            return actual_keys

    if ambiguous:
        errors.append(PolicyError(DB_V2_POLICY_DAO_AMBIGUOUS, {"method": ck.method}))
        return actual_keys

    # Plan Step-1 #8: with Room inventory identity data, the declared
    # daoFqcn of EVERY group member is cross-checked against the RESOLVED
    # identity of the accessor evidenced at the mutation site.  The accessor
    # is resolved through the same merged map that authorized the required
    # pair (idempotent for direct ``*Dao`` accessors, alias-resolving for
    # method-local/database-chain locals), then looked up in the inventory's
    # accessor -> DAO FQCN set.  A missing set — or one not containing the
    # declared daoFqcn — proves the policy misidentifies the DAO behind a
    # correctly-evidenced accessor: exactly one controlled
    # DB_V2_POLICY_DAO_FQCN_MISMATCH marks the whole group untrusted.  A
    # multi-FQCN set (two DAOs sharing a simple name across packages) passes
    # when it contains the declared FQCN; disambiguation beyond containment
    # would be guessing, and the policy-level ambiguity gate above already
    # fails any group claiming several FQCNs behind one accessor.
    if dao_fqcn_index is not None:
        for m in group:
            site_accessor = _resolve_dao_identity(m.dao_accessor, merged)
            if site_accessor is None:
                site_accessor = m.dao_accessor
            site_fqcns = dao_fqcn_index.get(site_accessor)
            if site_fqcns is None or m.dao_fqcn not in site_fqcns:
                errors.append(PolicyError(
                    DB_V2_POLICY_DAO_FQCN_MISMATCH,
                    {
                        "method": ck.method,
                        "dao_accessor": m.dao_accessor,
                        "dao_fqcn": m.dao_fqcn,
                    },
                ))
                return actual_keys

    # A body pair counts as listed when SOME group row declares its
    # operation behind an accessor spelling that resolves — through the same
    # scoped map — to the pair's resolved DAO identity (GR-08a alias
    # bridge).  A pair on any OTHER DAO identity stays unlisted: the bridge
    # only closes the spelling gap, it never widens coverage.
    unlisted = sorted({
        op
        for (acc, op) in actual
        if acc is not None
        and (acc, op) not in listed
        and (acc, op) not in listed_identities
    })
    if unlisted:
        errors.append(PolicyError(
            DB_V2_POLICY_UNLISTED_MUTATION,
            {"method": ck.method, "count": len(unlisted)},
        ))
        return actual_keys

    # Stage 3 (PR-GR-06 Slice 1): barrierMode metadata local consistency —
    # evaluated only for an otherwise-clean group so a failing group keeps
    # exactly one controlled diagnostic at its furthest stage reached.
    if not errors and not _barrier_metadata_consistent(
        group, ck, text, decl, mutation_linenos
    ):
        errors.append(PolicyError(
            DB_V2_POLICY_BARRIER_METADATA_INCONSISTENT,
            {"method": ck.method},
        ))
    return actual_keys
