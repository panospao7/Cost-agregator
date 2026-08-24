"""Exact v2 source-evidence verification per PR-01.

Verifies that every mutation declared in a v2 policy document is backed by
exact source evidence in the production Kotlin tree, and that no unlisted
mutations exist in the verified callables:

* every entry's ``path`` must resolve under a declared production source
  root (the manifest-backed root set) and be readable;
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
  method's receiver;
* ``barrierMode`` is metadata-only: it is never inferred or validated
  against source here.

There are no fallbacks: parser uncertainty, unsupported kinds, unreadable
files, and unresolved DAO accessors all surface as controlled
``PolicyError`` codes from ``policy_errors`` (the closed
``POLICY_ERROR_V2_EVIDENCE_*`` vocabulary). Per-group processing never
raises: any unexpected exception between path validation and mutation
checking is converted into one controlled
``POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN`` finding carrying only the
relative path and the exception class name, and verification continues
with the next group. Context stays bounded — controlled codes, target
names, counts — never raw payloads.
"""
from __future__ import annotations

import os

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
    CallableKind,
    PolicyEntry,
    match_mutation,
)
from .policy_errors import (
    PolicyError,
    POLICY_ERROR_V2_EVIDENCE_PATH_OUTSIDE_ROOTS,
    POLICY_ERROR_V2_EVIDENCE_FILE_UNREADABLE,
    POLICY_ERROR_V2_EVIDENCE_OWNER_MISSING,
    POLICY_ERROR_V2_EVIDENCE_OWNER_AMBIGUOUS,
    POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING,
    POLICY_ERROR_V2_EVIDENCE_CALLABLE_AMBIGUOUS,
    POLICY_ERROR_V2_EVIDENCE_KIND_UNSUPPORTED,
    POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN,
    POLICY_ERROR_V2_EVIDENCE_BODY_UNSUPPORTED,
    POLICY_ERROR_V2_EVIDENCE_DAO_ACCESSOR_UNRESOLVED,
    POLICY_ERROR_V2_EVIDENCE_DAO_FQCN_MISMATCH,
    POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND,
    POLICY_ERROR_V2_EVIDENCE_UNLISTED_MUTATION,
    POLICY_ERROR_V2_EVIDENCE_DAO_AMBIGUOUS,
)
from .source_roots import (
    DB_SOURCE_ROOT_UNDECLARED,
    SourceRoot,
    SourceRootSet,
    is_declared_production_path,
    resolve_source_root_set,
)
from .declaration_scanner import declared_root_pairs
from .policy_parsing import (
    build_class_scope_dao_var_map,
    build_dao_var_map,
    extract_mutation_pairs,
    _resolve_dao_identity,
)

__all__ = [
    "verify_v2_policy_source_evidence",
    "PolicyError",
    "POLICY_ERROR_V2_EVIDENCE_PATH_OUTSIDE_ROOTS",
    "POLICY_ERROR_V2_EVIDENCE_FILE_UNREADABLE",
    "POLICY_ERROR_V2_EVIDENCE_OWNER_MISSING",
    "POLICY_ERROR_V2_EVIDENCE_OWNER_AMBIGUOUS",
    "POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING",
    "POLICY_ERROR_V2_EVIDENCE_CALLABLE_AMBIGUOUS",
    "POLICY_ERROR_V2_EVIDENCE_KIND_UNSUPPORTED",
    "POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN",
    "POLICY_ERROR_V2_EVIDENCE_BODY_UNSUPPORTED",
    "POLICY_ERROR_V2_EVIDENCE_DAO_ACCESSOR_UNRESOLVED",
    "POLICY_ERROR_V2_EVIDENCE_DAO_FQCN_MISMATCH",
    "POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND",
    "POLICY_ERROR_V2_EVIDENCE_UNLISTED_MUTATION",
    "POLICY_ERROR_V2_EVIDENCE_DAO_AMBIGUOUS",
]


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


def verify_v2_policy_source_evidence(entries, repo_root, room_inventory=None):
    """Verify v2 policy entries against exact production source evidence.

    The verification is staged; each stage fails closed with exactly one
    controlled ``POLICY_ERROR_V2_EVIDENCE_*`` code per failing group and
    never falls back to weaker evidence.

    Stage 1 — grouping and per-group file/owner resolution (implemented):

    1. Group entries by ``entry.callable_key().canonical_key()`` so every
       distinct callable identity is verified once.
    2. Iterate groups in sorted canonical-key order for determinism; use
       the first entry of each group as its representative.
     3. Resolve the DECLARED production source-root set once up front
        (manifest-backed via ``resolve_source_root_set``); a resolution
        failure fails closed as exactly one bounded
        ``POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN`` finding (reason
        ``source-roots-unresolved`` plus the controlled diagnostic codes),
        and each path outside the declared roots yields
        ``POLICY_ERROR_V2_EVIDENCE_PATH_OUTSIDE_ROOTS``.
    4. Read the file as UTF-8 relative to ``repo_root``; any ``OSError``
       yields ``POLICY_ERROR_V2_EVIDENCE_FILE_UNREADABLE``.
    5. Mask string literals/comments (``mask_kotlin_source``) so they
       cannot forge evidence, then locate owner declarations whose
       ``owner`` equals the entry's ``owner_fqcn`` exactly:
       zero matches -> ``POLICY_ERROR_V2_EVIDENCE_OWNER_MISSING``;
       more than one -> ``POLICY_ERROR_V2_EVIDENCE_OWNER_AMBIGUOUS``;
       a non-``RESOLVED_EXACTLY`` parser status ->
       ``POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN``.

    Stage 2 — kind gate + callable resolution + body extraction +
    DAO/mutation completeness (implemented): only ``FUNCTION`` entries are
    verifiable; the representative entry's callable must resolve to
    exactly one braced declaration, and ``_check_mutations`` then verifies
    EVERY group member's own ``(dao_accessor, operation)`` pair against
    that body (not just the representative's) while reporting any unlisted
    mutation present in the body.

    Per-group processing never raises: any unexpected exception between
    path validation and mutation checking is converted into one
    ``POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN`` finding with bounded
    context (relative path, exception class name), and verification
    continues with the next group.

    Error context stays bounded: relative paths, FQCNs, counts, and
    controlled status codes only — never raw source or payloads.

    Args:
        entries: Sequence of :class:`~scripts.db_guard.policy_model.PolicyEntry`
            loaded from a v2 policy document.
        repo_root: Repository root used to resolve canonical source paths.
        room_inventory: Optional Room inventory used to cross-check DAO
            identities; when omitted, identity checks rely on parsed
            declarations only.

    Returns:
        Tuple of :class:`~scripts.db_guard.policy_errors.PolicyError`
        findings, one per failing group at the furthest stage reached —
        except a failed declared-root-set resolution, which yields exactly
        one ``POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN`` finding for the
        whole batch.
    """
    errors = []
    groups = {}
    for entry in entries:
        groups.setdefault(entry.callable_key().canonical_key(), []).append(entry)
    if not groups:
        return ()
    root_set, root_diagnostics = _declared_relative_root_set(repo_root)
    if root_set is None:
        # Fail closed with one bounded parser-uncertain finding: without a
        # resolved declared root set no path can be authorized.  Context
        # carries the controlled reason plus the controlled diagnostic
        # codes only — never raw paths or exception text.
        return (
            PolicyError(
                POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN,
                {
                    "reason": "source-roots-unresolved",
                    "codes": ",".join(
                        sorted({code for code, _context in root_diagnostics})
                    ),
                },
            ),
        )
    for canonical_key in sorted(groups):
        group = groups[canonical_key]
        entry = group[0]
        ck = entry.callable_key()
        try:
            if not is_declared_production_path(root_set, ck.path):
                errors.append(
                    PolicyError(
                        POLICY_ERROR_V2_EVIDENCE_PATH_OUTSIDE_ROOTS,
                        {"path": ck.path},
                    )
                )
                continue
            abs_path = os.path.join(repo_root, ck.path)
            try:
                with open(abs_path, "r", encoding="utf-8") as handle:
                    text = handle.read()
            except OSError:
                errors.append(
                    PolicyError(
                        POLICY_ERROR_V2_EVIDENCE_FILE_UNREADABLE,
                        {"path": ck.path},
                    )
                )
                continue
            masked = mask_kotlin_source(text)
            owners = [
                o for o in find_owner_declarations(masked) if o.owner == ck.owner_fqcn
            ]
            if len(owners) == 0:
                errors.append(
                    PolicyError(
                        POLICY_ERROR_V2_EVIDENCE_OWNER_MISSING,
                        {"owner_fqcn": ck.owner_fqcn, "path": ck.path},
                    )
                )
                continue
            if len(owners) > 1:
                errors.append(
                    PolicyError(
                        POLICY_ERROR_V2_EVIDENCE_OWNER_AMBIGUOUS,
                        {"owner_fqcn": ck.owner_fqcn, "count": len(owners)},
                    )
                )
                continue
            owner = owners[0]
            if owner.status != "RESOLVED_EXACTLY":
                errors.append(
                    PolicyError(
                        POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN,
                        {"owner_fqcn": ck.owner_fqcn, "status": owner.status},
                    )
                )
                continue
            if ck.kind != CallableKind.FUNCTION:
                errors.append(
                    PolicyError(
                        POLICY_ERROR_V2_EVIDENCE_KIND_UNSUPPORTED,
                        {"kind": ck.kind.value, "path": ck.path},
                    )
                )
                continue
            callables = find_callable_declarations(masked, owner)
            status = resolve_callable(
                callables,
                ck.owner_fqcn,
                ck.method,
                ck.receiver,
                ck.parameter_types,
            )
            if status == "METHOD_MISSING":
                errors.append(
                    PolicyError(
                        POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING,
                        {"method": ck.method, "path": ck.path},
                    )
                )
                continue
            if status == "AMBIGUOUS_OVERLOAD":
                errors.append(
                    PolicyError(
                        POLICY_ERROR_V2_EVIDENCE_CALLABLE_AMBIGUOUS,
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
                continue
            if status != "RESOLVED_EXACTLY":
                errors.append(
                    PolicyError(
                        POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN,
                        {"method": ck.method, "status": status},
                    )
                )
                continue
            try:
                params_norm = tuple(
                    normalize_type_text(p, allow_vararg=True)
                    for p in ck.parameter_types
                )
                recv_norm = (
                    normalize_type_text(ck.receiver)
                    if ck.receiver is not None
                    else None
                )
            except SignatureError:
                errors.append(
                    PolicyError(
                        POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN,
                        {"method": ck.method, "status": "SIGNATURE_ERROR"},
                    )
                )
                continue
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
                errors.append(
                    PolicyError(
                        (
                            POLICY_ERROR_V2_EVIDENCE_CALLABLE_AMBIGUOUS
                            if len(matches) > 1
                            else POLICY_ERROR_V2_EVIDENCE_CALLABLE_MISSING
                        ),
                        {"method": ck.method, "count": len(matches)},
                    )
                )
                continue
            decl = matches[0]
            if decl.body is None or decl.status != "RESOLVED_EXACTLY":
                errors.append(
                    PolicyError(
                        POLICY_ERROR_V2_EVIDENCE_BODY_UNSUPPORTED,
                        {"method": ck.method},
                    )
                )
                continue
            _check_mutations(group, ck, owner, masked, decl, callables, errors)
        except Exception as exc:
            errors.append(
                PolicyError(
                    POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN,
                    {"path": ck.path, "exc_type": type(exc).__name__},
                )
            )
            continue
    return tuple(errors)


def _callable_body_slice_line_indices(masked, owner, callables, line_count):
    """Map member callable spans to line indices within the owner slice.

    Returns the set of 0-based line indices (relative to
    ``masked[owner.body_start:owner.body_end].splitlines()``) covered by any
    member callable declaration's char span in ``masked``, so a class-scope
    scan can skip entire method declarations and see only property/field-
    level declarations.  Indices outside the slice are clamped away; the
    source is read with universal newlines, so counting ``"\\n"`` matches
    ``splitlines()`` indexing.

    ``callables`` holds only the owner's DIRECT members:
    ``find_callable_declarations`` skips ``fun`` declarations inside nested
    named classes/objects, so their spans are collected here separately.
    Every owner declaration fully contained in ``owner``'s body (other than
    ``owner`` itself) is rescanned and its callable spans join the same
    exclusion set.  Without this, a method-local DAO alias inside a nested
    owner would survive the class-scope scan and could overwrite a
    same-named class property in the DAO variable map.  Spans are expanded
    in sorted offset order so the mapping stays deterministic.  A parser
    failure on an inner owner propagates and fails closed upstream as one
    controlled ``POLICY_ERROR_V2_EVIDENCE_PARSER_UNCERTAIN`` finding.
    """
    base_line = masked.count("\n", 0, owner.body_start)
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
                for d in find_callable_declarations(masked, inner)
            )
    excluded = set()
    for start_offset, end_offset in sorted(spans):
        first = masked.count("\n", 0, start_offset) - base_line
        last = masked.count("\n", 0, end_offset - 1) - base_line
        excluded.update(
            ln for ln in range(first, last + 1) if 0 <= ln < line_count
        )
    return excluded


def _check_mutations(group, ck, owner, masked, decl, callables, errors):
    """Stage 3: DAO accessor resolution + both-direction mutation completeness.

    The required-pair check runs for EVERY member of ``group``, not just the
    representative ``ck``: each member's own ``(dao_accessor, operation)``
    pair must be found among the body's resolved mutation pairs (via the
    merged class/method DAO map and the ``_resolve_dao_identity``
    fallback), otherwise ``POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND`` is
    emitted with that member's operation/accessor.  The class-scope DAO map
    is built from the owner slice with every member callable's declaration
    span excluded — including callables declared inside nested named
    classes/objects within the owner body — so only property/field-level
    DAO declarations remain and sibling methods' or nested-owner members'
    local aliases can never leak into scope.
    """
    body_lines = decl.body.splitlines()
    owner_slice = masked[owner.body_start:owner.body_end]
    slice_lines = owner_slice.splitlines()
    class_map = build_class_scope_dao_var_map(
        slice_lines,
        0,
        len(slice_lines) - 1,
        excluded_line_numbers=_callable_body_slice_line_indices(
            masked, owner, callables, len(slice_lines)
        ),
    )
    method_map = build_dao_var_map(body_lines)
    merged = dict(class_map)
    merged.update(method_map)
    pairs = extract_mutation_pairs(decl.body, merged)

    def _resolved(identity, accessor):
        if identity == accessor:
            return accessor
        return _resolve_dao_identity(identity, merged)

    for m in group:
        required_found = any(
            op == m.operation
            and _resolved(identity, m.dao_accessor) == m.dao_accessor
            for (identity, op) in pairs
        )
        if not required_found:
            errors.append(PolicyError(
                POLICY_ERROR_V2_EVIDENCE_MUTATION_NOT_FOUND,
                {"operation": m.operation, "dao_accessor": m.dao_accessor},
            ))
            return

    listed = {(g.dao_accessor, g.operation) for g in group}
    actual = set()
    ambiguous = False
    accessor_fqcns = {}
    for g in group:
        accessor_fqcns.setdefault(g.dao_accessor, set()).add(g.dao_fqcn)
    for (identity, op) in pairs:
        # Resolve every body pair's identity canonically (scoped map first,
        # then the ``\w+Dao`` naming convention).  ``ck`` is a CallableKey
        # and carries no dao_accessor; the ambiguity/unlisted checks compare
        # against EVERY listed accessor, not the representative's.
        resolved = _resolve_dao_identity(identity, merged)
        if resolved is None:
            continue
        if len(accessor_fqcns.get(resolved, ())) > 1:
            ambiguous = True
        actual.add((resolved, op))
    if ambiguous:
        errors.append(PolicyError(POLICY_ERROR_V2_EVIDENCE_DAO_AMBIGUOUS, {"method": ck.method}))
        return
    unlisted = sorted({op for (acc, op) in actual if acc is not None and (acc, op) not in listed})
    if unlisted:
        errors.append(PolicyError(
            POLICY_ERROR_V2_EVIDENCE_UNLISTED_MUTATION,
            {"method": ck.method, "count": len(unlisted)},
        ))
