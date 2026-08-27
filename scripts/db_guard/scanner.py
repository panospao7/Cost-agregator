"""Room-aware database access discovery (D4).

This module intentionally performs source-range discovery only.  It does not
consume legacy guard stdout, infer identity from filenames, or create policy
rules.  Uncertainty is represented by registered protocol diagnostics and
therefore makes the returned report untrusted. Clean v2 reports exit 0,
findings exit 1, and diagnostics exit 2.

GR-07 Option-B trust amendment: SCANNER-STAGE per-callable diagnostics are
classified BLOCKING vs ADVISORY by the DB relevance of the enclosing
declaration range.  A diagnostic on a callable whose range shows DB-surface
evidence (DAO accessor/operation calls, structural operations/handle usage)
is blocking -> the scan is untrusted (exit 2 upstream).  A diagnostic on a
callable with NO DB-relevant content (Compose/UI/service code that never
touches a DAO or DB handle) carries the bounded
``controlled_context["advisory"] = True`` marker, is still reported, and
never breaks trust.  Pre-scan stage failures (source roots, inventory,
loader, evidence) are never advisory and stay always blocking.

Activation (PR-GR-07 Slice 2): D4 authorization is TYPED.  The ownership
policy input is a sequence of immutable
:class:`~scripts.db_guard.policy_model.PolicyEntry` objects loaded from a
schemaVersion-2 document, and every discovered direct DAO mutation is
authorized by EXACT equality on the full mutation identity via
``PolicyEntry``/``match_mutation``: path + ownerFqcn + kind + method +
receiver + ordered parameterTypes + daoAccessor + daoFqcn + operation.  The
legacy authorization paths are removed from this module: no simple-name
(owner_fqcn.rsplit) comparison, no legacy ``class``/``daos``/``signature``
dict fields, no name-only operation matching, no cross-overload unions, no
v1 fallback, and no wildcards.  Unmatched discovered mutations become
findings; unresolved targets become diagnostics — never guessed findings.
Structural exception matching is unchanged.
"""
from __future__ import annotations

import re
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:  # pragma: no cover
    yaml = None

from ..ci.guard_findings import (
    _is_unresolved_symbol,
    CallableSymbol, GuardDiagnostic, GuardFinding, GuardRunReport,
    SourceLocation, ValidationError, canonical_path, KIND_FUNCTION,
    KIND_INITIALIZER, KIND_PROPERTY_GETTER, KIND_PROPERTY_SETTER,
    KIND_VALUES,
)
from ..ci.finding_rule_catalog import is_known_diagnostic
from ..db_policy_signature import SignatureError, normalize_type_text
from ..kotlin_callable_parser import (
    ParserError, erase_star_projections, find_callable_declarations,
    find_owner_declarations, mask_kotlin_source,
)
from .declaration_scanner import (
    anchor_for_declared_path,
    build_project_type_index,
    declared_root_pairs,
    scan_production_declarations,
)
from .policy_model import BarrierMode, CallableKind, PolicyEntry, match_mutation
from .room_inventory import build_room_inventory
from .source_roots import resolve_source_root_set
from .policy_legacy import (
    legacy_structural_entry_metadata_errors as structural_entry_metadata_errors,
)


_STRUCTURAL = {
    "execSQL": re.compile(r"\bexecSQL\s*\("),
    "openDatabase": re.compile(r"\bopenDatabase\s*\("),
    "getDatabasePath": re.compile(r"\bgetDatabasePath\s*\("),
    # All structural evidence patterns begin at the operation token.  The
    # enclosing call matcher also exposes the operation token (not its dot),
    # so structural authorization and unsupported-token matching use one
    # coordinate system for every operation.
    "deleteRecursively": re.compile(r"\bdeleteRecursively\s*\(\s*\)"),
    "writableDatabase": re.compile(r"\bwritableDatabase\b"),
}
_UNSUPPORTED_STRUCTURAL = {
    "execSQL": re.compile(r"\bexecSQL\b"),
    "openDatabase": re.compile(r"\bopenDatabase\b"),
    "getDatabasePath": re.compile(r"\bgetDatabasePath\b"),
    "deleteRecursively": re.compile(r"\bdeleteRecursively\b"),
    # Keep the exact property separate from prefix-like identifiers.  A
    # property access is evidence even without a call, while names such as
    # ``writableDatabaseFoo`` must not be silently ignored.
    "writableDatabase": re.compile(r"\bwritableDatabase(?:[A-Za-z_]\w*)?\b"),
}
# Types for which an arbitrary member call is a database structural operation.
# We only use this allow-list when the receiver type was resolved from the
# declaration's lexical scope; guessing from a variable name would create
# false positives and, more importantly, could authorize an unknown operation.
_STRUCTURAL_RECEIVER_TYPES = frozenset({
    "SQLiteDatabase", "SupportSQLiteDatabase", "RoomDatabase",
    "android.database.sqlite.SQLiteDatabase",
    "androidx.sqlite.db.SupportSQLiteDatabase",
})
# Simple-name spellings of the same handle types.  A receiver EXPRESSION whose
# text is one of these names (``SQLiteDatabase.openDatabase(...)`` or the
# fully qualified ``android.database.sqlite.SQLiteDatabase.openDatabase(...)``)
# addresses the TYPE (static factory), not a variable, so no lexical binding
# can exist for it; the last segment identifies the handle exactly.
_STRUCTURAL_TYPE_SIMPLE_NAMES = frozenset({
    "SQLiteDatabase", "SupportSQLiteDatabase", "RoomDatabase",
})
# Closed androidx Room/SQLite accessor-member surface, keyed by MEMBER name.
# ``RoomDatabase.getOpenHelper()``/``openHelper`` yields the
# ``SupportSQLiteOpenHelper`` handle and ``SupportSQLiteOpenHelper``
# exposes ``writableDatabase``/``readableDatabase`` handles.  Member-name
# keys are deliberate: production subclasses (``AppDatabase : RoomDatabase``)
# inherit the accessors, and tracking a class hierarchy is out of scope for
# this fail-closed scanner.  The map only ever feeds the CLOSED structural
# receiver checks below -- never DAO authorization.
_STRUCTURAL_MEMBER_TYPES = {
    "openHelper": "SupportSQLiteOpenHelper",
    "writableDatabase": "SupportSQLiteDatabase",
    "readableDatabase": "SupportSQLiteDatabase",
}
# Read-only cursor APIs of the raw database handles.  D4 authorizes MUTATIONS;
# these operations return a Cursor and mutate nothing, so once the receiver is
# VERIFIED to be a structural database handle they are classified reads and
# fall outside the mutation-authorization contract (Room-layer @RawQuery reads
# remain policed by the raw-query policy in the inventory).  Any operation
# outside this closed set on a structural handle keeps its honest
# DB_STRUCTURAL_SCOPE_UNSUPPORTED diagnostic.
_STRUCTURAL_READ_OPERATIONS = frozenset({"query", "rawQuery"})
# GR-07 convergence round: transactional lifecycle operations of a VERIFIED
# database handle.  Evidence (2026-08-27 line-level baseline scan,
# build/guard-debug/gr07/probe11_baseline_lines.json): ``close`` x5
# (DatabaseBackupRepositoryImpl.kt:230, FinancialRescueCoordinator.kt:136/769,
# BackupVerifier.kt:264/464), and ``beginTransaction``/``
# setTransactionSuccessful``/``endTransaction`` triads on the annotated
# ``android.database.sqlite.SQLiteDatabase`` parameter of
# repairBudgetsSchemaToV86 (DatabaseBackupRepositoryImpl.kt:2144/2207/2209).
# These operations never touch application tables -- they manage the handle's
# connection/transaction state -- so on a VERIFIED structural handle they are
# classified like the read-only cursor APIs above.  Receivers that are NOT
# verified handles keep falling through silently exactly as before (these
# names are not DAO operations, so an unverified receiver never reaches this
# gate).  ``inTransaction`` completes the closed androidx transaction-state
# family named by the approved round scope; it has zero production occurrences
# today, so adding it is a zero-delta map completion, recorded here for the
# next evidence probe.
_STRUCTURAL_HANDLE_OPERATIONS = frozenset({
    "close", "beginTransaction", "endTransaction", "setTransactionSuccessful",
    "inTransaction",
})
# Calls are found from the dot and their receiver is parsed backwards.  A
# suffix regex is unsafe here: it turns ``context.expenseDao`` and
# ``holder(expenseDao)`` into the apparently bare ``expenseDao``.
_METHOD_CALL = re.compile(r"\.(?P<safe>\?)?\s*(?P<method>[A-Za-z_]\w*)\s*\(")
# Type text keeps the FULL source spelling: generic arguments (bounded two
# nesting levels) and trailing nullability.  Dropping ``List<Expense>`` to
# ``List`` or ``Long?`` to ``Long`` made call-site argument types never equal
# to the DAO declaration's parameter spelling and failed every such target
# with a false DB_CALL_TARGET_AMBIGUOUS.
_TYPE_TEXT = (
    r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*"
    r"(?:<(?:[^<>]|<[^<>]*>)*>)?"
    r"(?:\?)?"
)
_TYPE = re.compile(r"\b(?:val|var|private|protected|internal|public|lateinit\s+var)\s+(?P<name>[A-Za-z_]\w*)\s*:\s*(?P<type>" + _TYPE_TEXT + r")")
_PARAM = re.compile(r"\b(?P<name>[A-Za-z_]\w*)\s*:\s*(?P<type>" + _TYPE_TEXT + r")")
_DECL_PARAM = re.compile(r"(?:fun\s+)?[A-Za-z_]\w*\s*\((?P<body>[^)]*)\)")
_ACCESSOR = re.compile(r"\b(?P<kind>get|set)\s*\((?P<params>[^)]*)\)")
_PROPERTY_STRUCTURAL_ACCESS = re.compile(r"\.(?P<property>writableDatabase)\b")
# Local declarations WITHOUT an explicit type annotation.  Their type is
# inferred only through the closed shapes below (direct constructor/factory
# call, or a resolvable androidx member chain); anything else stays
# unresolved and keeps failing closed exactly as before.
# GR-07 convergence round 5: the ``=`` separator is deliberately restricted
# to SAME-LINE whitespace (``[ \t]``).  The previous ``\s*=\s*`` let the
# separator cross a newline, so a declaration whose initializer line ended in
# a blanked string template swallowed the NEXT declaration whole:
# ``val stagedDbName = "<template>"`` consumed the following
# ``val stagedDbFile = context.getDatabasePath(...)`` line as its own
# initializer text, ``stagedDbFile`` was never collected, and every later
# ``stagedDbFile.delete()`` failed closed with DB_DAO_SCOPE_UNRESOLVED
# (DatabaseBackupRepositoryImpl.kt x6, probe_r5_sites.json).
_UNTYPED_VAL = re.compile(r"\bval\s+(?P<name>[A-Za-z_]\w*)[ \t]*=[ \t]*(?P<init>[^\n]+)")
# GR-07 convergence round 5: the constructed head may carry a qualified
# package prefix (``java.io.File(...)`` -> ``File``).  Only the capitalized
# head names the constructed type; lowercase segments are package/object
# spellings and never contribute.
_FACTORY_INIT = re.compile(
    r"(?:(?:[a-z_]\w*)(?:\.[a-z_]\w*)*\.)?"
    r"(?P<type>[A-Z][A-Za-z0-9_]*)\s*(?:\(|\.\s*[A-Za-z_]\w*\s*\()"
)
_DOTTED_INIT = re.compile(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+")
# The closed kotlin.use dispatch: ``handle.use { it.<op>(...) }`` binds the
# lambda's implicit single parameter to the receiver's type.
# GR-07 convergence round 5: closed SELF-type standard-library dispatches.
# ``file.also { it.exists() }`` / ``File(...).takeIf { it.exists() }`` bind
# the lambda's implicit single parameter to the RECEIVER ITSELF (Kotlin
# contract of let/also/takeIf/takeUnless), so once the receiver resolves the
# binding is exact.  Element-typed dispatches (forEach/map/filter/...) are
# deliberately NOT included: their parameter is a collection ELEMENT whose
# type no closed scanner knows, and guessing it would fabricate bindings.
_SELF_TYPE_LAMBDA_DISPATCH = ("use", "let", "also", "takeIf", "takeUnless")
_LAMBDA_DISPATCH = re.compile(
    r"\.\s*(?P<dispatch>%s)\s*\{" % "|".join(_SELF_TYPE_LAMBDA_DISPATCH)
)
# GR-07 convergence round: closed PLATFORM return-type facts for untyped-local
# inference.  Evidence (probe14_classify.json D2 family): ``val dbFile =
# context.getDatabasePath(...)`` leaves ``dbFile`` unresolved because the
# initializer root is a lower-case receiver, and the following
# ``dbFile.exists()``/``dbFile.delete()`` collide with DAO operation names
# (DatabaseBackupRepositoryImpl.kt x4+, CostbackupBundle.kt,
# DefaultCloudPayloadPolicy.kt).  Only method names with one exact platform
# return type belong here; anything else stays unresolved (fail closed).
_PLATFORM_FACTORY_RETURNS = {
    "getDatabasePath": "File",
}
_PLATFORM_CALL_INIT = re.compile(
    r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*\s*\.\s*(?P<method>[A-Za-z_]\w*)\s*\("
)
# GR-07 convergence round 5: named call arguments.  ``foo(x = 1)`` carries
# the VALUE's type, not the parameter name's; the prefix is stripped before
# value resolution.  The negative lookahead keeps comparison operators
# (``a == b``) unstripped.
_NAMED_ARGUMENT = re.compile(r"^(?P<name>[A-Za-z_]\w*)[ \t]*=(?!=)[ \t]*(?P<value>.+)$", re.S)
# Head of a constructed value: an optional lowercase qualified prefix, a
# capitalized type head, and the opening parenthesis of its argument list.
_QUALIFIED_CTOR_HEAD = re.compile(
    r"^(?:(?:[a-z_]\w*)(?:\.[a-z_]\w*)*\.)?"
    r"(?P<type>[A-Z][A-Za-z0-9_]*)\s*\("
)
# GR-07 convergence round 5: zero-argument DAO accessor calls.
# ``database.expenseDao().insert(...)`` and the local inference shape
# ``val dao = database.expenseDao()`` both end in ``.accessor()`` with no
# other call in the chain; ``accessor`` must be a declared @Database accessor
# (see ``_database_accessor_types``) resolving to exactly one DAO FQCN.
_ACCESSOR_CALL = re.compile(
    r"^[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*\.(?P<accessor>[A-Za-z_]\w*)\(\)$"
)
# @Database-annotated classes: their abstract DAO accessors are the evidence
# for the accessor-name -> DAO-type map below.
_DATABASE_ANNOTATION = re.compile(r"@\s*Database\s*\(")
_DATABASE_ACCESSOR_DECL = re.compile(
    r"\babstract\s+(?:suspend\s+)?(?:fun|val|var)\s+(?P<name>[A-Za-z_]\w*)"
    r"(?:\(\))?\s*:\s*(?P<type>" + _TYPE_TEXT + r")"
)
# Control-flow keywords whose ``(condition)`` group prefixes a statement's
# receiver expression (``if (dst.exists()) dst.delete()``).
_CONTROL_GROUP_PREFIX = re.compile(r"^(?:if|for|while|when)\s*\(")


def _balanced_group_end(text: str, opening: int) -> int | None:
    """Exclusive end of the balanced bracket group opening at ``opening``."""
    depth = 0
    for index in range(opening, len(text)):
        char = text[index]
        if char in "([{":
            depth += 1
        elif char in ")]}":
            depth -= 1
            if depth == 0:
                return index + 1
    return None


def _constructor_call_type(value: str) -> str | None:
    """Constructed type of a ``Name(...)`` / ``pkg.Name(...)`` expression.

    The whole (stripped) value must BE the constructor call: the argument
    list closes exactly at the end.  Anything else -- trailing members,
    unbalanced groups, lowercase heads -- returns None (fail closed).
    """
    candidate = value.strip()
    head = _QUALIFIED_CTOR_HEAD.match(candidate)
    if head is None:
        return None
    end = _balanced_group_end(candidate, head.end() - 1)
    if end is None or candidate[end:].strip():
        return None
    return head.group("type")


def _accessor_call_name(expression: str) -> str | None:
    """Accessor member name of a ``root.chain.accessor()`` expression.

    The whole expression must be a dotted identifier chain whose terminal
    member is called with an EMPTY argument list; any nested call, index, or
    lambda returns None (fail closed).
    """
    match = _ACCESSOR_CALL.match(expression.strip())
    return match.group("accessor") if match else None


def _strip_leading_paren_group_prefix(expression: str) -> str:
    """Drop leading control-group prefixes from a receiver text.

    ``if (cond) target.delete()`` parses its receiver as either
    ``if (cond) target`` (the walk stopped at a statement boundary left of
    the keyword) or ``(cond) target`` (it stopped at the unmatched group
    opener).  A leading ``if``/``for``/``while``/``when`` keyword followed by
    one balanced group, or a bare leading balanced group, followed by a
    non-dot continuation is such a control prefix; the true receiver is what
    follows it.  A leading group whose continuation starts with ``.`` is a
    real parenthesized receiver (``(a ?: b).length``) and is kept whole.
    """
    stripped = expression.strip()
    while True:
        keyword = _CONTROL_GROUP_PREFIX.match(stripped)
        if keyword is not None:
            opening = stripped.index("(", keyword.end() - 1)
        elif stripped.startswith("("):
            opening = 0
        else:
            break
        end = _balanced_group_end(stripped, opening)
        if end is None:
            break
        remainder = stripped[end:].lstrip()
        if not remainder or remainder.startswith("."):
            break
        stripped = remainder
    return stripped


def _database_accessor_types(
    sources: dict[str, str], dao_simple: dict[str, set[str]]
) -> dict[str, str]:
    """Map @Database abstract accessor names to unique DAO FQCNs.

    Evidence-based: only accessors DECLARED inside a ``@Database``-annotated
    class body are collected, and only when the declared DAO type resolves to
    exactly one inventory FQCN.  Ambiguous or undeclared names stay absent --
    callers fail closed on absence.
    """
    accessors: dict[str, set[str]] = {}
    for path in sorted(sources):
        masked = mask_kotlin_source(sources[path])
        for annotation in _DATABASE_ANNOTATION.finditer(masked):
            body_open = masked.find("{", annotation.end())
            if body_open < 0:
                continue
            body_close = _balanced_group_end(masked, body_open)
            if body_close is None:
                continue
            for declaration in _DATABASE_ACCESSOR_DECL.finditer(
                masked, annotation.end(), body_close
            ):
                simple = declaration.group("type").rsplit(".", 1)[-1]
                fqcns = set(dao_simple.get(declaration.group("type"), ()))
                fqcns.update(dao_simple.get(simple, ()))
                if len(fqcns) != 1:
                    continue
                accessors.setdefault(declaration.group("name"), set()).add(
                    next(iter(fqcns))
                )
    return {
        name: next(iter(fqcns))
        for name, fqcns in sorted(accessors.items())
        if len(fqcns) == 1
    }


def _accessor_declaration_matches(span_masked: str) -> list[re.Match]:
    """Accessor DECLARATIONS inside one property's masked span.

    A property span contains both its ``get(...)``/``set(...)`` accessors and
    any initializer CALLS spelled ``.get(``/``.set(`` (every
    ``AtomicInteger.get()``).  Counting calls as accessors made single-getter
    properties look like duplicated accessors and shifted the accessor offset
    selection in ``_property_symbol_at``.  Two closed structural rules
    separate them: an accessor declaration is never preceded by ``.``/``?``
    (member/safe-call), and its parameter list is always followed by ``=`` or
    ``{`` (the accessor body).
    """
    matches: list[re.Match] = []
    for match in _ACCESSOR.finditer(span_masked):
        prefix = span_masked[:match.start()].rstrip()
        if prefix.endswith(".") or prefix.endswith("?"):
            continue
        tail = span_masked[match.end():].lstrip()
        if not tail.startswith("=") and not tail.startswith("{"):
            continue
        matches.append(match)
    return matches


def _load_policy(value: Any) -> tuple[list[dict[str, Any]], bool]:
    if value is None:
        return [], True
    try:
        if isinstance(value, (str, Path)):
            if yaml is None:
                return [], False
            data = yaml.safe_load(Path(value).read_text(encoding="utf-8"))
        else:
            data = value
        entries = data.get("entries", data) if isinstance(data, dict) else data
        if not isinstance(entries, list) or any(not isinstance(item, dict) for item in entries):
            return [], False
        return list(entries), True
    except Exception:
        return [], False


def _line(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


def _line_start(source: str, line: int) -> int:
    cursor = 0
    for _ in range(max(0, line - 1)):
        newline = source.find("\n", cursor)
        if newline < 0:
            return len(source)
        cursor = newline + 1
    return cursor


def _line_end(source: str, line: int) -> int:
    newline = source.find("\n", _line_start(source, line))
    return len(source) if newline < 0 else newline


def _diag_from_text(value: str) -> GuardDiagnostic | None:
    if not isinstance(value, str):
        return GuardDiagnostic("DB_DECLARATION_UNRESOLVED")
    code, _, rest = value.partition(":")
    if not is_known_diagnostic(code):
        return GuardDiagnostic("DB_DECLARATION_UNRESOLVED")
    path = None
    if rest:
        # Topology-neutral: any repo-relative POSIX .kt path is accepted.
        # The last colon-separated segment is the line number; everything
        # before it is the path.
        path_text, separator, line_text = rest.rpartition(":")
        candidate = None
        if separator and path_text.endswith(".kt") and line_text.isdigit():
            candidate = path_text
        elif not separator and rest.endswith(".kt"):
            candidate = rest
        if candidate is not None:
            try:
                canonical_path(candidate)
            except ValidationError:
                # Absolute, drive-prefixed, or traversal text fails the
                # canonical-path contract that ``GuardDiagnostic`` enforces
                # downstream.  Degrade to the bare controlled diagnostic
                # (``path=None``) instead of raising through the inventory
                # conversion; the raw text is never carried onward.
                candidate = None
        path = candidate
    return GuardDiagnostic(code=code, path=path)


# F1 location bound for the controlled ``line`` context value carried by
# scanner diagnostics (mirrors ``declaration_scanner.MAX_LOCATION_NUMBER``).
_MAX_DIAGNOSTIC_LINE = 2 ** 31 - 1


def _line_diagnostic(
    code: str, path: str | None, line: int | None, *, advisory: bool = False,
) -> GuardDiagnostic:
    """Controlled diagnostic carrying its source line as bounded context.

    The current ``GuardDiagnostic`` contract has no ``location`` field; the
    one meaningful coordinate survives as a bounded positive int under the
    ``controlled_context["line"]`` key.  Sites without a meaningful line
    omit the key entirely.  ``advisory=True`` adds the bounded
    ``controlled_context["advisory"]`` marker (GR-07 Option-B amendment):
    the diagnostic keeps its code/path/line verbatim but never breaks
    report trust.
    """
    context: dict[str, Any] = {}
    if advisory:
        context["advisory"] = True
    if line is not None:
        context["line"] = min(max(int(line), 1), _MAX_DIAGNOSTIC_LINE)
    return GuardDiagnostic(code, path=path, controlled_context=context)


# GR-07 Option-B trust-contract amendment: scanner-stage per-callable
# diagnostics are classified BLOCKING vs ADVISORY by the DB relevance of the
# enclosing declaration range.  Relevance is a closed, name/shape-based
# determination over the SAME masked text the scan already uses; it can only
# ever move a diagnostic from blocking to advisory, and every pre-scan /
# infrastructure emission stays unflagged (always blocking).
def _db_surface_names(inventory) -> frozenset[str]:
    """Closed DB-surface vocabulary for relevance classification.

    Every declared DAO accessor identity (FQCN and simple name) plus every
    declared DAO operation name from the Room inventory.  A declaration range
    containing a ``_METHOD_CALL`` against one of these names — or any
    structural operation/handle token — touches a database surface.
    """
    names: set[str] = set()
    for dao in inventory.daos:
        names.add(dao.fqcn)
        names.add(dao.fqcn.rsplit(".", 1)[-1])
    for method in inventory.methods:
        names.add(method.name)
    return frozenset(names)


def _declaration_touches_db_surface(
    masked: str, start: int, end: int, db_names: frozenset[str],
) -> bool:
    """True when the declaration range shows DB-surface evidence.

    Evidence: any structural operation/handle token (the supported
    ``_STRUCTURAL`` shapes, the unsupported-token forms, and the
    ``writableDatabase`` property access), or any ``_METHOD_CALL`` whose
    method name is a known DAO accessor/operation name.  Everything else —
    Compose/UI/service code that never names a DAO operation or a DB handle
    — has no DB relevance, so scanner diagnostics on it are advisory.
    """
    if _PROPERTY_STRUCTURAL_ACCESS.search(masked, start, end):
        return True
    for pattern in _STRUCTURAL.values():
        if pattern.search(masked, start, end):
            return True
    for pattern in _UNSUPPORTED_STRUCTURAL.values():
        if pattern.search(masked, start, end):
            return True
    return any(
        call.group("method") in db_names
        for call in _METHOD_CALL.finditer(masked, start, end)
    )


def _range_diagnostic(
    code: str,
    path: str | None,
    *,
    db_relevant: bool,
    line: int | None = None,
) -> GuardDiagnostic:
    """Diagnostic classified by its declaration range's DB relevance.

    DB-relevant ranges keep the plain blocking diagnostic.  Ranges with NO
    DB-surface evidence carry the bounded ``controlled_context["advisory"]``
    marker: the diagnostic is still reported verbatim (same code/path/line),
    but it never breaks report trust and never withholds findings.  Emission
    sites whose DB relevance was already positively computed by the scan
    itself (verified structural-handle usage) pass ``db_relevant=True``
    explicitly, so an unknown operation on a verified handle stays blocking
    even when its name matches no DAO accessor.
    """
    return _line_diagnostic(code, path, line, advisory=not db_relevant)


def _property_symbol_at(source: str, declaration, offset: int) -> CallableSymbol:
    """Resolve a property initializer/getter/setter at an exact source offset."""
    begin = _line_start(source, declaration.start_line)
    end = _line_end(source, declaration.end_line)
    text = source[begin:end]
    name_match = re.search(r"\b(?:val|var)\s+([A-Za-z_]\w*)", text)
    if not name_match:
        raise SignatureError("BAD_NAME")
    name = name_match.group(1)
    accessors = _accessor_declaration_matches(mask_kotlin_source(text))
    selected = None
    for index, accessor in enumerate(accessors):
        absolute = begin + accessor.start()
        next_absolute = begin + (accessors[index + 1].start() if index + 1 < len(accessors) else len(text))
        if absolute <= offset < next_absolute:
            selected = accessor
            break
    if selected is None:
        # A property initializer is executable, but is not a getter. Keeping a
        # distinct kind prevents an initializer policy from authorizing either
        # accessor (and preserves the old initializer scan).
        kind, parameters = KIND_INITIALIZER, ()
    elif selected.group("kind") == "get":
        kind, parameters = KIND_PROPERTY_GETTER, ()
    else:
        raw = selected.group("params").strip()
        match = re.fullmatch(r"[A-Za-z_]\w*\s*:\s*(.+)", raw, re.S)
        if not match:
            raise SignatureError("BAD_PARAMS")
        parameters = (normalize_type_text(
            erase_star_projections(match.group(1).strip()),
        ),)
        kind = KIND_PROPERTY_SETTER
    return CallableSymbol(owner=declaration.owner_fqcn, name=name,
                          receiver=None, parameters=parameters, kind=kind)


def _receiver_expression(masked: str, dot_start: int) -> tuple[str, bool]:
    """Parse the complete expression immediately preceding a call dot.

    This is deliberately a small, fail-closed Kotlin expression boundary
    parser, not a name/suffix heuristic.  It balances calls, indexing and
    parentheses while walking left, then returns the complete expression.  The
    resolver below accepts only the single identifier form; every other valid
    expression is therefore reported as unresolved rather than guessed.
    """
    end = dot_start
    i = end - 1
    while i >= 0 and masked[i].isspace():
        i -= 1
    safe = i >= 0 and masked[i] == "?"
    if safe:
        i -= 1
        while i >= 0 and masked[i].isspace():
            i -= 1
    depth = {')': 0, ']': 0, '}': 0}
    # Walking LEFT, a closer defers to its opener further left; an opener with
    # no pending closer is the expression's left boundary.  The map below is
    # keyed by the OPENING character for exactly that check.  Keying it by the
    # closing character (as an earlier revision did) made both branches test
    # closers only, so an unmatched ``(`` was skipped instead of stopping the
    # walk and ``if (!file.exists()`` parsed as the receiver expression.
    openers = {'(': ')', '[': ']', '{': '}'}
    while i >= 0:
        char = masked[i]
        if char in depth:
            depth[char] += 1
        elif char in openers:
            closer = openers[char]
            if depth[closer] > 0:
                depth[closer] -= 1
            else:
                break
        elif not any(depth.values()) and char in ';=\n':
            break
        elif not any(depth.values()) and char in "&|":
            # GR-07 convergence round 5: ``&&``/``||`` at bracket depth zero
            # separate boolean operands; the receiver of the RIGHT operand's
            # call must not swallow the left one.  Walking left, the pair's
            # rightmost character is met first, so breaking here leaves the
            # whole operator -- and everything left of it -- out of the
            # expression.
            break
        elif (not any(depth.values()) and char == "?"
                and masked[i + 1:i + 2] == ":"):
            # GR-07 convergence round 5: an elvis operator at bracket depth
            # zero separates the fallback expression from its left side.
            # ``x ?: dao.probe(...)`` used to parse its receiver as
            # ``x ?: dao``.  Step over the ':' so it is excluded too.
            i -= 1
            break
        elif (not any(depth.values()) and char == ">"
                and i > 0 and masked[i - 1] == "-"):
            # GR-07 convergence round: a ``->`` arrow at bracket depth zero is
            # a lambda/``when`` branch boundary, not part of the receiver.
            # ``chunks.forEach { chunk -> memberDao.insertAll(chunk) }`` used
            # to parse its receiver as ``chunk -> memberDao`` (and ``when (x)
            # { true -> budgetDao.insert(..) }`` as ``... -> budgetDao``),
            # failing every such call as DB_DAO_SCOPE_UNRESOLVED.  Only the
            # arrow's ``>`` needs checking: walking left, it is reached before
            # the ``-``, and inside brackets the depth guard keeps comparison
            # operators (``if (a > b) dao.op()``) behaving as before.
            break
        i -= 1
    expression = masked[i + 1:end].strip()
    # Prefix tokens that bind to the CALL RESULT or to the enclosing
    # STATEMENT -- never to the receiver itself: unary operators
    # (``!dao.probe(x)``), the elvis operator (``b ?: dao.probe(x)``), and
    # the statement keywords ``return``/``else`` (``return dao.probe(x)``).
    # Strip the closed set repeatedly, then classify bareness on what
    # remains; every other expression shape stays non-bare (fail closed).
    # GR-07 convergence round 5: a leading balanced ``(condition)`` group
    # followed by a non-dot continuation is an ``if``/``for``/``while``
    # control prefix (``if (dst.exists()) dst.delete()`` parses its receiver
    # as ``(dst.exists()) dst``); the true receiver is what follows it.
    stripped = expression
    prefix = re.compile(r"^(?:[!+\-]|\?:|return\b|else\b)\s*")
    while True:
        candidate = prefix.sub("", stripped)
        candidate = _strip_leading_paren_group_prefix(candidate)
        if candidate == stripped:
            break
        stripped = candidate
    return stripped, (not safe and bool(re.fullmatch(r"[A-Za-z_]\w*", stripped)))


def _structural_match(entries, path: str, owner: str, method: str, operation: str) -> bool:
    for entry in entries:
        if (entry.get("path"), entry.get("class"), entry.get("operation")) != (path, owner, operation):
            continue
        pattern = entry.get("method_pattern")
        if isinstance(pattern, str):
            try:
                if re.fullmatch(pattern, method):
                    return True
            except re.error:
                pass
    return False


def _is_structural_receiver(receiver_type: str | None) -> bool:
    if not receiver_type:
        return False
    return receiver_type in _STRUCTURAL_RECEIVER_TYPES or receiver_type.rsplit(".", 1)[-1] in {
        "SQLiteDatabase", "SupportSQLiteDatabase", "RoomDatabase",
    }


def _static_structural_receiver(
    receiver: str, receiver_types: dict[str, str | None]
) -> bool:
    """True when the receiver TEXT is a type spelling, not a variable.

    ``SQLiteDatabase.openDatabase(...)`` and its fully qualified spelling
    address the companion/type object; no lexical binding can exist for a
    capitalized type path whose first segment is not a visible name.  A
    receiver that IS a lexically resolved name is never treated as a type
    spelling, so a variable shadowing a type name keeps its variable
    resolution.
    """
    if receiver in receiver_types:
        return False
    return receiver.rsplit(".", 1)[-1] in _STRUCTURAL_TYPE_SIMPLE_NAMES


def _chain_structural_access_type(
    receiver: str, operation: str, receiver_types: dict[str, str | None]
) -> str | None:
    """Resolve ``root.member...operation`` over the closed androidx surface.

    Every segment after the root (including the terminal operation) must be a
    member of the closed ``_STRUCTURAL_MEMBER_TYPES`` accessor map; any
    unknown link returns None (fail closed) -- the chain is never guessed.

    GR-07 convergence round: the ROOT no longer has to resolve to a lexical
    type.  Evidence: ``freshDb.openHelper.writableDatabase`` where
    ``val freshDb = restoreDatabaseOpener.openFreshDatabase()`` needs cross-
    file return-type knowledge no closed scanner has (x3:
    DatabaseBackupRepositoryImpl.kt:994/2261, AppStartupCoordinator.kt:262).
    Verification comes from the chain SHAPE itself: at least one INTERMEDIATE
    member plus the terminal operation, ALL drawn from the closed androidx
    accessor map (``x.openHelper.writableDatabase``), is not a spelling any
    non-Room expression can carry.  A bare ``x.writableDatabase`` (no
    intermediate member) keeps failing closed -- no such production shape
    exists.  Acceptance here only classifies the access as database evidence;
    authorization still flows through the structural policy's exact
    (path, class, method, operation) tuples, so an unauthorized location
    becomes a finding, never a silent pass.
    """
    segments = receiver.split(".") + [operation]
    if len(segments) < 3:
        # No intermediate member: ``x.writableDatabase`` is not evidenced.
        return None
    for segment in segments[1:]:
        current = _STRUCTURAL_MEMBER_TYPES.get(segment)
        if current is None:
            return None
    return current


def _structural_access_supported(
    receiver: str,
    receiver_is_bare: bool,
    receiver_type: str | None,
    operation: str,
    receiver_types: dict[str, str | None],
) -> bool:
    """True when THIS access's receiver is a verified database handle.

    Supported forms: a variable/parameter resolved to a structural handle
    type; a ``SupportSQLiteOpenHelper``-typed receiver accessing
    ``writableDatabase``/``readableDatabase``; a TYPE-spelled receiver
    (static factory such as ``SQLiteDatabase.openDatabase``); a dotted
    chain fully resolved through the closed androidx member map; and -- GR-07
    convergence round -- the two evidenced platform shapes whose receivers
    resolve to exactly ``Context`` (``getDatabasePath``) or ``File``
    (``deleteRecursively``).  Both stay inside the structural policy's exact
    tuple authorization; only the receiver-verification gate is closed here.
    """
    if receiver_is_bare:
        if _is_structural_receiver(receiver_type):
            return True
        # A bare receiver whose TEXT is a structural handle type name is a
        # static/companion access (``SQLiteDatabase.openDatabase``): no
        # variable of that name can exist in scope for these closed names.
        if receiver in _STRUCTURAL_TYPE_SIMPLE_NAMES:
            return True
        # GR-07 convergence round, evidenced per-method shapes:
        # ``context.getDatabasePath(...)`` x10 and ``tempDir.deleteRecursively()``
        # x11 (line-level baseline, probe11_baseline_lines.json).  The
        # receiver type must have been RESOLVED from the declaration's lexical
        # scope to the exact platform type -- a name-guess never qualifies --
        # and the operation stays policy-gated downstream.
        simple_type = (
            receiver_type.rsplit(".", 1)[-1]
            if isinstance(receiver_type, str) else None
        )
        if operation == "getDatabasePath" and simple_type == "Context":
            return True
        if operation == "deleteRecursively" and simple_type == "File":
            return True
        return (
            operation in ("writableDatabase", "readableDatabase")
            and isinstance(receiver_type, str)
            and receiver_type.rsplit(".", 1)[-1] == "SupportSQLiteOpenHelper"
        )
    if _static_structural_receiver(receiver, receiver_types):
        return True
    if "." in receiver:
        chain_type = _chain_structural_access_type(receiver, operation, receiver_types)
        return chain_type is not None and _is_structural_receiver(chain_type)
    return False


def _argument_types(masked: str, opening: int, receiver_types: dict[str, str]) -> tuple[str, ...] | None:
    """Resolve the small set of argument forms needed for overload identity."""
    depth = 0
    start = opening + 1
    parts = []
    for i in range(start, len(masked)):
        c = masked[i]
        if c in "([{": depth += 1
        elif c in ")]}" :
            if depth == 0:
                parts.append(masked[start:i].strip())
                break
            depth -= 1
        elif c == "," and depth == 0:
            parts.append(masked[start:i].strip())
            start = i + 1
    if not parts or (len(parts) == 1 and not parts[0]):
        return ()
    result = []
    for value in parts:
        # GR-07 convergence round 5: a NAMED argument contributes its VALUE's
        # type, never the parameter name's spelling.
        # ``insert(OperationRun(correlationId = correlationId, ...))`` used to
        # fail closed because the raw part text ``name = value`` matched no
        # closed form.  Strip the ``name =`` prefix (comparison operators are
        # kept -- see ``_NAMED_ARGUMENT``), then resolve the value.
        named = _NAMED_ARGUMENT.match(value)
        if named is not None:
            value = named.group("value").strip()
        if value in receiver_types:
            result.append(receiver_types[value])
            continue
        if re.fullmatch(r"[0-9]+", value):
            result.append("Int")
            continue
        if re.fullmatch(r"[0-9]+[lL]", value):
            result.append("Long")
            continue
        if value in {"true", "false"}:
            result.append("Boolean")
            continue
        if re.fullmatch(r'"(?:[^"\\]|\\.)*"', value, re.S):
            result.append("String")
            continue
        # GR-07 convergence round 5: a constructed value IS an instance of
        # its head type.  ``insert(OperationRun(...))`` carries
        # ``OperationRun`` exactly like the untyped-local factory inference
        # above; any trailing member access or unbalanced group stays
        # unresolved (fail closed).
        constructed = _constructor_call_type(value)
        if constructed is not None:
            result.append(constructed)
            continue
        return None
    return tuple(result)


def _normalized_type_tuple(types: tuple[str, ...]) -> tuple[str, ...] | None:
    """Grammar-normalized parameter tuple, or None when unnormalizable.

    Both sides of the overload comparison go through the same normalizer, so
    equivalent spellings (``Long?`` vs ``Long ?``, generic argument spacing)
    compare equal while any grammar failure keeps the raw tuple out of the
    comparison instead of crashing the scan.
    """
    try:
        return tuple(normalize_type_text(item, allow_vararg=True) for item in types)
    except SignatureError:
        return None


def _brace_scopes(masked: str) -> tuple[tuple[int, int], ...]:
    """Return balanced lexical brace ranges in source order."""
    stack: list[int] = []
    pairs: list[tuple[int, int]] = []
    for offset, char in enumerate(masked):
        if char == "{":
            stack.append(offset)
        elif char == "}" and stack:
            pairs.append((stack.pop(), offset))
    return tuple(pairs)


def _receiver_types(
    source: str,
    begin: int,
    end: int,
    *,
    callable_start: int | None = None,
    callable_end: int | None = None,
    owner_start: int | None = None,
    owner_end: int | None = None,
    use_offset: int | None = None,
    dao_accessor_types: dict[str, str] | None = None,
) -> dict[str, str | None]:
    """Resolve names in the lexical environment of one callable.

    ``begin``/``end`` are the executable range, not a source search window.
    In particular, declarations from a sibling method are never considered.
    A ``None`` value is an intentional ambiguity marker: callers must fail
    closed instead of choosing between equally-scoped declarations.
    """
    masked = mask_kotlin_source(source)
    use = end if use_offset is None else use_offset
    cstart = begin if callable_start is None else callable_start
    cend = end if callable_end is None else callable_end
    scopes = _brace_scopes(masked)

    def scope_for(offset: int, fallback: tuple[int, int]) -> tuple[int, int]:
        candidates = [scope for scope in scopes if scope[0] < offset < scope[1]]
        return min(candidates, key=lambda item: item[1] - item[0]) if candidates else fallback

    candidates: list[tuple[str, str, int, tuple[int, int]]] = []

    # Callable parameters are restricted to this callable's own header.  This
    # avoids accidentally treating constructor or sibling method parameters as
    # receivers.
    header_end = masked.find("{", cstart, cend)
    if header_end < 0:
        header_end = cend
    header = source[cstart:header_end]
    for match in _PARAM.finditer(header):
        absolute = cstart + match.start()
        candidates.append((match.group("name"), match.group("type"), absolute,
                           (cstart, cend)))

    # Class properties and primary-constructor parameters are visible to the
    # callable, but only direct members of its enclosing class are included.
    if owner_start is not None and owner_end is not None:
        owner_scopes = [scope for scope in scopes
                        if owner_start <= scope[0] and scope[1] <= owner_end]
        owner_body = min(owner_scopes, key=lambda item: item[0]) if owner_scopes else None
        if owner_body is not None:
            for match in _TYPE.finditer(source, owner_body[0] + 1, owner_body[1]):
                declaration_scope = scope_for(match.start(), owner_body)
                if declaration_scope == owner_body:
                    candidates.append((match.group("name"), match.group("type"),
                                       match.start(), owner_body))
        constructor_header = source[owner_start:owner_body[0] if owner_body else owner_end]
        for match in _PARAM.finditer(constructor_header):
            absolute = owner_start + match.start()
            if absolute < cstart:
                candidates.append((match.group("name"), match.group("type"),
                                   absolute, (owner_start, owner_end)))

    # Locals are deliberately limited to the current callable body and are
    # visible only after their declaration.  The scope range supplies the
    # innermost-shadowing rule.  Locals WITHOUT an explicit annotation are
    # collected too, but their type is only filled through the closed
    # inference shapes (constructor/factory call or androidx member chain)
    # after the annotated environment has been resolved.
    body_start = masked.find("{", cstart, cend)
    untyped_locals: list[tuple[str, str, int, tuple[int, int]]] = []
    if body_start >= 0:
        body_scope = scope_for(body_start + 1, (body_start, cend))
        for match in _TYPE.finditer(source, body_start + 1, cend):
            declaration_scope = scope_for(match.start(), body_scope)
            candidates.append((match.group("name"), match.group("type"),
                               match.start(), declaration_scope))
        for match in _UNTYPED_VAL.finditer(masked, body_start + 1, cend):
            # Masked text: initializer string literals are blanked, so literal
            # content can never look like a constructor/factory shape.  Names
            # an annotated declaration already defines keep the annotated
            # spelling; inference below must never widen it.
            declaration_scope = scope_for(match.start(), body_scope)
            untyped_locals.append((match.group("name"), match.group("init").strip(),
                                   match.start(), declaration_scope))

    def finalize(env: list[tuple[str, str, int, tuple[int, int]]]) -> dict[str, str | None]:
        by_name: dict[str, list[tuple[str, int, tuple[int, int]]]] = {}
        for name, typ, declared, scope in env:
            if declared >= use or not (scope[0] <= use <= scope[1]):
                continue
            by_name.setdefault(name, []).append((typ, declared, scope))
        resolved: dict[str, str | None] = {}
        for name, values in by_name.items():
            innermost = min(item[2][1] - item[2][0] for item in values)
            visible = [item for item in values
                       if item[2][1] - item[2][0] == innermost]
            types = {item[0] for item in visible}
            resolved[name] = next(iter(types)) if len(visible) == 1 else None
        return resolved

    resolved = finalize(candidates)

    # Closed-shape inference for untyped locals: ``val x = Foo(...)`` and
    # ``val x = Foo.bar(...)`` construct/carry ``Foo``; a pure dotted chain
    # (``val db = database.openHelper.writableDatabase``) resolves link by
    # link over the closed androidx member map from its root's resolved type.
    # Anything else stays unresolved and keeps failing closed.
    inferred: list[tuple[str, str, int, tuple[int, int]]] = []
    seen_untyped: set[str] = {name for name, _t, _o, _s in candidates}
    for name, init, declared, scope in untyped_locals:
        if name in seen_untyped:
            continue
        inferred_type: str | None = None
        # GR-07 hardening: the initializer must BEGIN with the constructor /
        # companion-factory shape (``Foo(...)`` / ``Foo.bar(...)``).  The
        # previous ``search`` matched mid-identifier substrings because the
        # pattern has no word boundary: ``operationRunDao.getByCorrelationId(``
        # yielded ``RunDao``, ``timeProvider.now()`` yielded ``Provider``, and
        # ``categorizationEngineProvider.get()`` yielded ``EngineProvider``.
        # Those fabricated bindings flowed into ``_argument_types`` through
        # the lexical environment and produced wrong-but-confident overload
        # comparisons (DB_CALL_TARGET_AMBIGUOUS with argtypes like ``RunDao``
        # against a ``Long`` signature).  Anchoring at position 0 keeps every
        # legitimate leading-shape inference and turns mid-expression roots
        # into honest unresolved locals (fail closed).
        factory = _FACTORY_INIT.match(init)
        if factory:
            inferred_type = factory.group("type")
        elif init.endswith(")"):
            platform_call = _PLATFORM_CALL_INIT.match(init)
            if platform_call and platform_call.group("method") in _PLATFORM_FACTORY_RETURNS:
                inferred_type = _PLATFORM_FACTORY_RETURNS[platform_call.group("method")]
            elif (
                dao_accessor_types
                and _ACCESSOR_CALL.match(init)
                and init.count("(") == 1
            ):
                # GR-07 convergence round 5: ``val dao = database.expenseDao()``
                # carries the accessor's declared DAO type.  The accessor name
                # must be a declared @Database abstract accessor resolving to
                # exactly one inventory FQCN (``_database_accessor_types``);
                # anything else stays unresolved (fail closed).  The single
                # ``(`` guard rejects nested-call chains the regex alone
                # cannot see.
                accessor_type = dao_accessor_types.get(
                    _accessor_call_name(init) or ""
                )
                if isinstance(accessor_type, str):
                    inferred_type = accessor_type
            elif _DOTTED_INIT.fullmatch(init):
                root_type = resolved.get(init.split(".", 1)[0])
                if isinstance(root_type, str):
                    inferred_type = root_type
                    for segment in init.split(".")[1:]:
                        inferred_type = _STRUCTURAL_MEMBER_TYPES.get(segment)
                        if inferred_type is None:
                            break
        if inferred_type is not None:
            seen_untyped.add(name)
            inferred.append((name, inferred_type, declared, scope))
    if inferred:
        resolved = finalize(candidates + inferred)

    # ``handle.use { it.op(...) }`` binds the lambda's implicit single
    # parameter to the receiver's type.  GR-07 convergence round 5: the
    # closed SELF-type standard-library dispatches (let/also/takeIf/
    # takeUnless) bind ``it`` to the receiver ITSELF by Kotlin contract, so a
    # resolved receiver yields an exact binding there too.  Element-typed
    # dispatches (forEach/map/...) are deliberately excluded -- their
    # parameter type is unknown to a closed scanner.  The receiver must
    # resolve through the environment above or a constructed ``File(...)``
    # head; nothing is guessed.
    if body_start >= 0:
        bindings: list[tuple[str, str, int, tuple[int, int]]] = []
        for match in _LAMBDA_DISPATCH.finditer(masked, body_start, cend):
            open_brace = match.end() - 1
            lambda_scope = scope_for(open_brace + 1, (open_brace, cend))
            if lambda_scope[0] < open_brace:
                continue
            receiver_text, receiver_is_bare = _receiver_expression(masked, match.start())
            receiver_kind: str | None = None
            if receiver_is_bare:
                candidate_type = resolved.get(receiver_text)
                receiver_kind = candidate_type if isinstance(candidate_type, str) else None
            elif "." in receiver_text:
                first, rest = receiver_text.split(".", 1)
                root_type = resolved.get(first)
                if isinstance(root_type, str):
                    receiver_kind = root_type
                    for segment in rest.split("."):
                        receiver_kind = _STRUCTURAL_MEMBER_TYPES.get(segment)
                        if receiver_kind is None:
                            break
            else:
                receiver_kind = _constructor_call_type(receiver_text)
            if receiver_kind is not None:
                bindings.append(("it", receiver_kind, open_brace + 1, lambda_scope))
        if bindings:
            resolved = finalize(candidates + inferred + bindings)
    # GR-07 convergence round: captured type TEXTS keep their source spelling
    # (including star projections) until this boundary.  Erasing ``Type<*>``
    # to its canonical ``Type<Any?>`` form here keeps every environment value
    # comparable with the parser-resolved DAO signatures on the other side of
    # the overload comparison; a raw star spelling would fail signature
    # normalization and fall back to a raw-tuple comparison that can never
    # match (a false DB_CALL_TARGET_AMBIGUOUS).
    return {
        name: erase_star_projections(value) if isinstance(value, str) else value
        for name, value in resolved.items()
    }


def _dao_maps(inventory) -> tuple[dict[str, set[str]], dict[tuple[str, str], list[Any]]]:
    by_simple: dict[str, set[str]] = {}
    methods: dict[tuple[str, str], list[Any]] = {}
    for dao in inventory.daos:
        simple = dao.fqcn.rsplit(".", 1)[-1]
        by_simple.setdefault(simple, set()).add(dao.fqcn)
        by_simple.setdefault(dao.fqcn, set()).add(dao.fqcn)
    for method in inventory.methods:
        methods.setdefault((method.dao.fqcn, method.name), []).append(method)
    return by_simple, methods


def scan_db_access(source_root, ownership_policy=None, structural_policy=None, raw_query_policy=None):
    """Return a deterministic protocol-v2 report for one DB discovery run.

    ``source_root`` accepts the same project/source-root forms as the D2
    scanners.  ``ownership_policy`` is the ACTIVATED typed contract: a
    sequence of immutable :class:`~scripts.db_guard.policy_model.PolicyEntry`
    objects (or ``None``), loaded from a schemaVersion-2 document by the CLI
    via ``load_policy_v2``.  Every discovered direct DAO mutation is
    authorized by exact full-identity equality (``match_mutation``); anything
    that is not a ``PolicyEntry`` fails closed as an infrastructure
    diagnostic.  ``structural_policy`` may be parsed mappings/lists or YAML
    paths.  The scanner is deliberately conservative: unresolved callable,
    receiver, or operation signatures become diagnostics rather than guessed
    findings.
    """
    # Typed v2 authorization input only.  A non-PolicyEntry item can never be
    # matched, so it fails closed as DB_POLICY_SOURCE_EVIDENCE_INVALID instead
    # of silently authorizing or silently rejecting mutations.
    ownership = (
        list(ownership_policy) if ownership_policy is not None else []
    )
    own_ok = all(isinstance(item, PolicyEntry) for item in ownership)
    structural, structural_ok = _load_policy(structural_policy)
    # Validate the complete canonical structural schema before any policy is
    # indexed or matched.  In particular, malformed signatures must become an
    # infrastructure diagnostic, never an unauthorized mutation finding.
    structural_ok = structural_ok and all(
        not structural_entry_metadata_errors(item) for item in structural
    )
    # One shared root-set resolution drives every D4 discovery leg (inventory,
    # declaration ranges, and source reads).  No private root heuristics:
    # single-root inputs behave exactly as before, and multi-root
    # repositories consider every declared root in manifest order.
    root_set, _root_diagnostics = resolve_source_root_set(source_root)
    inventory = build_room_inventory(source_root, raw_query_policy, source_root_set=root_set)
    declarations = scan_production_declarations(source_root, root_set=root_set)
    diagnostics: list[GuardDiagnostic] = []
    diagnostics.extend(_diag_from_text(item) for item in inventory.diagnostics)
    diagnostics.extend(
        GuardDiagnostic(
            item.code,
            item.path,
        )
        for item in declarations.diagnostics
    )
    diagnostics = [item for item in diagnostics if item is not None]
    if not own_ok or not structural_ok:
        diagnostics.append(GuardDiagnostic("DB_POLICY_SOURCE_EVIDENCE_INVALID"))

    dao_simple, dao_methods = _dao_maps(inventory)
    # Exact mutator identity strings, for the mutation-only arity gate: an
    # unresolvable argument list is fail-closed debt ONLY when the call
    # could reach an authorization decision (a mutator); read-only targets
    # end at the mutator gate either way.
    mutator_methods = {item.method for item in inventory.mutators}
    # Option-B amendment: closed DB-surface vocabulary shared by every
    # range-classified diagnostic below.
    db_surface_names = _db_surface_names(inventory)
    findings: list[GuardFinding] = []
    # Read each file once; declaration ranges are the authoritative scan
    # units.  Paths map back through the SAME declared-root anchors the
    # declaration scanner used to emit them, so single-root inputs read
    # exactly as before and every declared root of a multi-root repository
    # resolves to its own enclosing project.
    pairs = declared_root_pairs(source_root, root_set) if root_set is not None else ()
    # GR-07 hardening step A: the project-wide type index is built ONCE per
    # scan from the SAME declared production roots the declaration scan
    # walked, then threaded into every callable discovery below.  A
    # parameter/receiver type declared in another production file now
    # resolves through a unique simple-name match; an ambiguous simple name
    # still fails closed as DB_SIGNATURE_UNRESOLVED.  Deterministic: same
    # tree -> same index -> same resolutions; no I/O beyond this one reuse
    # of the existing declared-root walk.
    project_types = build_project_type_index(pairs)
    files: dict[str, Path] = {}
    for item in declarations.helper_ranges:
        anchor = anchor_for_declared_path(pairs, item.path)
        if anchor is not None:
            files[item.path] = anchor / item.path
    sources: dict[str, str] = {}
    for path in sorted(files):
        try:
            sources[path] = files[path].read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            diagnostics.append(GuardDiagnostic("DB_SOURCE_UNREADABLE", path=path))

    # GR-07 convergence round 5: declared @Database abstract accessors are
    # the closed evidence for ``database.expenseDao()`` receiver resolution
    # and for ``val dao = database.expenseDao()`` local inference.  Built
    # once per scan from the SAME sources; only names resolving to exactly
    # one inventory DAO FQCN are present, so absence fails closed.
    dao_accessor_types = _database_accessor_types(sources, dao_simple)

    # Typed authorization index: bucket entries by (canonical path, exact
    # operation) so each discovered mutation is compared only against the
    # entries that could possibly authorize it, then by FULL identity
    # equality via ``match_mutation`` (path + ownerFqcn + kind + method +
    # receiver + ordered parameterTypes + daoAccessor + daoFqcn + operation).
    policy_by_path_operation: dict[tuple[str, str], list[PolicyEntry]] = {}
    for item in ownership:
        # A non-PolicyEntry can never authorize (``own_ok`` already flagged
        # the run untrusted above); indexing it here would crash on its
        # missing attributes instead of failing closed through the
        # controlled DB_POLICY_SOURCE_EVIDENCE_INVALID diagnostic path.
        if not isinstance(item, PolicyEntry):
            continue
        policy_by_path_operation.setdefault(
            (item.path, item.operation), []
        ).append(item)
    # Per-scan discovery memo (deterministic pure functions of the file
    # text): owners per path, callables per (path, owner-identity).  A stored
    # exception instance is re-raised for every declaration of the same
    # poisoned owner so the controlled diagnostic path stays identical while
    # the duplicated parse work disappears.
    owner_cache: dict[str, Any] = {}
    callable_cache: dict[tuple[str, str], Any] = {}
    for declaration in declarations.helper_ranges:
        source = sources.get(declaration.path)
        if source is None:
            continue
        if declaration.kind in {"class", "interface", "object", "companion", "enum", "annotation", "dao"}:
            continue
        if declaration.source_start is not None and declaration.source_end is not None:
            # Declaration discovery supplies exact offsets for properties and
            # accessors.  Line reconstruction would merge same-line accessors
            # and could let one policy identity authorize another callable.
            start = declaration.source_start
            end = declaration.source_end
        elif declaration.body_start is not None:
            start = declaration.body_start
            end = declaration.body_end or len(source)
        else:
            diagnostics.append(GuardDiagnostic("DB_METHOD_BODY_UNSUPPORTED", path=declaration.path))
            continue
        masked = mask_kotlin_source(source)
        # Option-B classification input: does THIS declaration's range touch
        # a database surface?  Computed once per declaration from the same
        # masked text the scan below uses; every range-classified emission
        # site shares this single determination.
        db_relevant = _declaration_touches_db_surface(
            masked, start, end, db_surface_names,
        )
        if declaration.kind == "property":
            # Duplicated accessor kinds make the property's callable identity
            # ambiguous (which ``set`` would a policy authorize?): fail
            # closed with the controlled unresolved-signature diagnostic
            # instead of letting the first accessor silently stand in for
            # the whole property's identity.  Initializer CALLS spelled
            # ``.get(``/``.set(`` are not accessors -- see
            # ``_accessor_declaration_matches``.
            accessor_span = masked[_line_start(source, declaration.start_line):
                                   _line_end(source, declaration.end_line)]
            accessor_kinds = [
                item.group("kind")
                for item in _accessor_declaration_matches(accessor_span)
            ]
            if len(accessor_kinds) != len(set(accessor_kinds)):
                diagnostics.append(_range_diagnostic(
                    "DB_SIGNATURE_UNRESOLVED", declaration.path,
                    db_relevant=db_relevant,
                ))
                continue
        try:
            # Discovery is deterministic per (file, owner), so memoize it for
            # the scan: every declaration range of one owner re-ran the full
            # owner/callable parse, and one poisoned owner re-emitted the same
            # failure once per declaration (thousands of duplicate emissions).
            owners = owner_cache.get(declaration.path)
            if owners is None:
                try:
                    owners = find_owner_declarations(source)
                except (ParserError, SignatureError) as error:
                    owners = error
                owner_cache[declaration.path] = owners
            if isinstance(owners, BaseException):
                raise owners
            owner = next((item for item in owners if item.owner == declaration.owner_fqcn), None)
            discovery_key = (
                declaration.path,
                owner.owner if owner is not None else declaration.owner_fqcn,
            )
            callables = callable_cache.get(discovery_key)
            if callables is None:
                try:
                    callables = find_callable_declarations(
                        source, owner or declaration.owner_fqcn,
                        project_types=project_types,
                    )
                except (ParserError, SignatureError) as error:
                    callables = error
                callable_cache[discovery_key] = callables
            if isinstance(callables, BaseException):
                raise callables
            # GR-07 convergence round 5: the containing callable is matched
            # HALF-OPEN (``start < end_offset``).  Callable ranges are
            # contiguous -- one callable's ``end_offset`` equals the next
            # callable's ``start_offset`` -- so the previous inclusive upper
            # bound bound every declaration whose range starts exactly on
            # that boundary to the PRECEDING callable.  Its header params and
            # body locals then filled the lexical environment, the real
            # callable's names were invisible, and every local/param-based
            # resolution failed closed (ExpenseGroupDao.insertGroupWithMembers
            # bound to setActiveStatus; probe_r5_boundary.py).  An empty span
            # still matches itself for safety.
            callable_item = next(
                (
                    item for item in callables
                    if item.start_offset <= start
                    and (start < item.end_offset or item.start_offset == item.end_offset == start)
                ),
                None,
            )
            if declaration.kind == "function" and callable_item is None:
                raise ParserError()
            if declaration.callable_name is not None:
                symbol = CallableSymbol(
                    owner=declaration.owner_fqcn,
                    name=declaration.callable_name,
                    receiver=None,
                    parameters=tuple(declaration.parameters),
                    # ``property`` is a declaration-range kind, not a
                    # protocol callable kind.  Resolve the executable
                    # initializer/accessor at each call below.
                    kind=declaration.kind if declaration.kind in KIND_VALUES else "unknown",
                )
            elif callable_item is not None:
                symbol = CallableSymbol(owner=callable_item.signature.owner_fqcn,
                                        name=callable_item.signature.function_name,
                                        receiver=callable_item.signature.receiver,
                                        parameters=tuple(callable_item.signature.parameter_types),
                                        kind=KIND_FUNCTION if owner is not None else "top_level_function")
            else:
                if declaration.kind == "initializer":
                    symbol = CallableSymbol(owner=declaration.owner_fqcn,
                                            name="init", receiver=None,
                                            parameters=(), kind=KIND_INITIALIZER)
                else:
                    declaration_text = source[_line_start(source, declaration.start_line):
                                               _line_end(source, declaration.end_line)]
                    name_match = re.search(r"\b(?:val|var)\s+([A-Za-z_]\w*)", declaration_text)
                    if not name_match:
                        raise ParserError()
                    symbol = CallableSymbol(owner=declaration.owner_fqcn or "top_level",
                                            name=name_match.group(1), receiver=None,
                                            parameters=(), kind="unknown")
        except (ParserError, SignatureError):
            diagnostics.append(_range_diagnostic(
                "DB_SIGNATURE_UNRESOLVED", declaration.path,
                db_relevant=db_relevant,
            ))
            continue
        # A property's accessor parameter lists live AFTER the first body
        # brace (the getter's), so the callable-header scan inside
        # ``_receiver_types`` never sees them.  Collect every accessor
        # parameter of THIS property once so a setter argument such as
        # ``value`` resolves exactly like a declared parameter; without it,
        # second-accessor mutations fail closed with a spurious
        # DB_SIGNATURE_UNRESOLVED.  The raw declared spelling is kept, the
        # same way ``_PARAM`` candidates store it.
        accessor_parameters: dict[str, str] = {}
        if declaration.kind == "property":
            property_text = masked[_line_start(source, declaration.start_line):
                                   _line_end(source, declaration.end_line)]
            for accessor_match in re.finditer(r"\bset\s*\(([^)]*)\)", property_text):
                param_match = re.fullmatch(
                    r"([A-Za-z_]\w*)\s*:\s*(.+)",
                    accessor_match.group(1).strip(), re.S,
                )
                if param_match:
                    accessor_parameters[param_match.group(1)] = erase_star_projections(
                        param_match.group(2).strip()
                    )
        # GR-07 convergence round: expression-bodied callables
        # (``fun f(): X = withContext(io) { ... }``) keep the parser's span at
        # the header ``=`` boundary, but their executable body -- and every
        # local declared inside it -- extends to the declaration range's end.
        # Restricting the lexical environment to the header-only span hid all
        # such locals, so ``val stagedDatabase = AppDatabase.fileBuilder(...).
        # build()`` / ``val tempDir = File(...)`` / ``val freshDb =
        # restoreDatabaseOpener.openFreshDatabase()`` stayed unresolved and
        # every downstream access failed closed (evidence:
        # DatabaseBackupRepositoryImpl.kt restoreCostBackup /
        # restoreFromSafetyBackup in probe11_baseline_lines.json).  The
        # parameters still come from the real header; only the LOCALS window
        # widens to the range the scanner already scans for calls.
        env_callable_start = (
            callable_item.start_offset if callable_item is not None else start
        )
        env_callable_end = (
            callable_item.end_offset if callable_item is not None else end
        )
        if (
            callable_item is not None
            and callable_item.status == "UNSUPPORTED_EXPRESSION_BODY"
            and env_callable_end < end
        ):
            env_callable_end = end
        calls = list(_METHOD_CALL.finditer(masked, start, end))
        for call in sorted(calls, key=lambda item: item.start()):
            receiver_types = _receiver_types(
                source, start, end,
                callable_start=env_callable_start,
                callable_end=env_callable_end,
                owner_start=owner.start_offset if owner is not None else None,
                owner_end=owner.end_offset if owner is not None else None,
                use_offset=call.start(),
                dao_accessor_types=dao_accessor_types,
            )
            if accessor_parameters:
                merged = dict(accessor_parameters)
                merged.update(receiver_types)
                receiver_types = merged
            receiver, receiver_is_bare = _receiver_expression(masked, call.start())
            operation = call.group("method")
            # GR-07 convergence round 5: a NON-bare receiver can still carry
            # an exact closed type.  ``database.expenseDao()`` is a declared
            # @Database accessor resolving to exactly one DAO FQCN, and
            # ``File(...)`` constructs its head type; every other non-bare
            # shape stays unresolved (fail closed).  Safe calls keep their
            # intentional fail-closed contract below.
            receiver_type = receiver_types.get(receiver) if receiver_is_bare else None
            if (not receiver_is_bare and not call.groupdict().get("safe")):
                accessor_name = _accessor_call_name(receiver)
                if accessor_name is not None:
                    receiver_type = dao_accessor_types.get(accessor_name)
                if receiver_type is None:
                    receiver_type = _constructor_call_type(receiver)
            call_symbol = symbol
            if declaration.kind == "property":
                try:
                    call_symbol = _property_symbol_at(source, declaration, call.start())
                except SignatureError:
                    diagnostics.append(_range_diagnostic(
                        "DB_STRUCTURAL_SCOPE_UNSUPPORTED", declaration.path,
                        db_relevant=db_relevant,
                        line=_line(source, call.start()),
                    ))
                    continue
            is_structural = operation in _STRUCTURAL
            receiver_supported = _structural_access_supported(
                receiver, receiver_is_bare, receiver_type, operation, receiver_types,
            )
            if is_structural and not receiver_supported:
                diagnostics.append(_line_diagnostic(
                    "DB_STRUCTURAL_SCOPE_UNSUPPORTED", declaration.path,
                    _line(source, call.start()),
                ))
                continue
            # A VERIFIED structural handle used for an operation that is
            # neither a supported structural operation, a classified
            # read-only cursor API, a transactional lifecycle operation, nor
            # any known DAO operation is an unsupported database access
            # shape: honest controlled diagnostic, never a guess.
            if (receiver_supported
                    and operation not in _STRUCTURAL
                    and operation not in _STRUCTURAL_READ_OPERATIONS
                    and operation not in _STRUCTURAL_HANDLE_OPERATIONS
                    and not any(key[1] == operation for key in dao_methods)):
                diagnostics.append(_range_diagnostic(
                    "DB_STRUCTURAL_SCOPE_UNSUPPORTED", declaration.path,
                    # Verified handle usage was positively computed above, so
                    # this diagnostic is DB-relevant even when the operation
                    # name matches no DAO accessor and the range carries no
                    # structural token: always blocking (fail closed).
                    db_relevant=True, line=_line(source, call.start()),
                ))
                continue
            # The receiver parser is for DAO operations only.  Ordinary Kotlin
            # calls (including structural DB APIs handled below) must not be
            # reclassified as unresolved DAO scope merely because they use a
            # dot-call form.
            if not any(key[1] == operation for key in dao_methods):
                continue
            # Safe calls are intentionally not authorized as bare DAO access.
            # The old matcher skipped ``dao?.insert`` entirely; recognizing the
            # token and failing closed preserves a structured diagnostic for
            # direct, qualified, and nested safe-call forms.  GR-07 round 5:
            # a non-bare receiver with an EXACT closed type (declared
            # @Database accessor call or constructed head) proceeds; every
            # other non-bare shape keeps the structured diagnostic.
            if call.groupdict().get("safe") or (
                not receiver_is_bare and receiver_type is None
            ):
                diagnostics.append(GuardDiagnostic(
                    "DB_DAO_SCOPE_UNRESOLVED", path=declaration.path,
                ))
                continue
            typ = receiver_type
            fqcn_candidates = set(dao_simple.get(typ or "", ()))
            if not fqcn_candidates:
                if typ is None:
                    diagnostics.append(GuardDiagnostic("DB_DAO_SCOPE_UNRESOLVED", path=declaration.path))
                continue
            if len(fqcn_candidates) != 1:
                diagnostics.append(GuardDiagnostic("DB_DAO_SCOPE_UNRESOLVED", path=declaration.path))
                continue
            dao = next(iter(fqcn_candidates))
            candidates = dao_methods.get((dao, operation), [])
            argument_types = _argument_types(masked, call.end() - 1, receiver_types)
            if argument_types is None:
                # An unresolvable argument list is honest fail-closed debt
                # for MUTATIONS: without the argument types the exact
                # overload -- and therefore the authorized identity -- can
                # never be confirmed.  A READ-only candidate set however
                # ends at the mutator gate below without any authorization
                # decision, so its target identity cannot affect the
                # report; skipping it here records no finding and no
                # diagnostic, exactly as an resolved-but-unmatched read
                # already did.
                if any(
                    f"{item.dao.canonical_path}::{dao}#{operation}"
                    f"({', '.join(item.parameters)})" in mutator_methods
                    for item in candidates
                ):
                    diagnostics.append(GuardDiagnostic(
                        "DB_SIGNATURE_UNRESOLVED", path=declaration.path,
                    ))
                    continue
                continue
            # Overload disambiguation by NORMALIZED ordered-parameter
            # equality on BOTH sides: source spellings may differ in
            # whitespace around generics/nullability while naming the same
            # overload.  A nullability-insensitive second pass is provably
            # safe for target SELECTION -- Kotlin forbids same-erasure
            # overloads differing only in ``?`` -- so ``Item?`` at the call
            # site still selects the single ``insert(Item)`` declaration
            # (and any later policy mismatch stays a visible finding).  A
            # different type NAME never matches: the ambiguity stays an
            # honest DB_CALL_TARGET_AMBIGUOUS.
            normalized_arguments = _normalized_type_tuple(argument_types)
            matching: list[Any] = []
            for item in candidates:
                if normalized_arguments is None:
                    if tuple(item.parameters) == argument_types:
                        matching.append(item)
                    continue
                normalized_item = _normalized_type_tuple(item.parameters)
                if normalized_item is None:
                    if tuple(item.parameters) == argument_types:
                        matching.append(item)
                    continue
                if normalized_item == normalized_arguments:
                    matching.append(item)
            if not matching and normalized_arguments is not None:
                insensitive_arguments = tuple(
                    item[:-1] if item.endswith("?") else item
                    for item in normalized_arguments
                )
                for item in candidates:
                    normalized_item = _normalized_type_tuple(item.parameters)
                    if normalized_item is None:
                        continue
                    insensitive_item = tuple(
                        part[:-1] if part.endswith("?") else part
                        for part in normalized_item
                    )
                    if insensitive_item == insensitive_arguments:
                        matching.append(item)
            if len(matching) != 1:
                # GR-07 convergence round: a READ-only candidate set ends at
                # the mutator gate below WITHOUT any authorization decision,
                # so a non-unique target cannot affect the report -- the same
                # honesty rule the unresolved-argument path above already
                # applies to reads.  Evidence: 6 of the 7 residual
                # DB_CALL_TARGET_AMBIGUOUS emissions were single-candidate
                # READ calls whose confidently-fabricated argument types
                # (untyped-local factory inference such as
                # ``TimePeriodUtils.startOfMonth(...)`` -> "TimePeriodUtils")
                # could never match the declaration.  MUTATION candidate sets
                # keep the exact pinned DB_CALL_TARGET_AMBIGUOUS contract:
                # an unknown or mismatched argument list must never reach an
                # authorization decision by arity alone.
                if argument_types is not None and candidates and not any(
                    f"{item.dao.canonical_path}::{dao}#{operation}"
                    f"({', '.join(item.parameters)})" in mutator_methods
                    for item in candidates
                ):
                    continue
                diagnostics.append(GuardDiagnostic("DB_CALL_TARGET_AMBIGUOUS", path=declaration.path))
                continue
            method = matching[0]
            mutator = next((item for item in inventory.mutators if item.method == f"{method.dao.canonical_path}::{dao}#{operation}({', '.join(method.parameters)})"), None)
            if mutator is None:
                continue
            line = _line(source, call.start())
            location = SourceLocation(line=line, end_line=line)
            # Typed v2 authorization (PR-GR-07 Slice 2): EXACT full-identity
            # equality against PolicyEntry objects.  The discovered mutation's
            # kind must be a real CallableKind; an unknown kind is unresolved
            # signature debt and takes the controlled diagnostic path — never
            # a guessed finding.  There is no simple-name owner comparison, no
            # legacy class/daos/signature fields, no name-only operation
            # matching, no cross-overload union, and no wildcard.
            try:
                callable_kind = CallableKind(call_symbol.kind)
            except ValueError:
                diagnostics.append(_line_diagnostic(
                    "DB_SIGNATURE_UNRESOLVED", declaration.path, line,
                ))
                continue
            matched_entries = [
                item for item in policy_by_path_operation.get(
                    (declaration.path, operation), ())
                if match_mutation(
                    item,
                    path=declaration.path,
                    owner_fqcn=declaration.owner_fqcn,
                    kind=callable_kind,
                    method=call_symbol.name,
                    receiver=call_symbol.receiver,
                    parameter_types=tuple(call_symbol.parameters),
                    dao_accessor=receiver,
                    dao_fqcn=dao,
                    operation=operation,
                )
            ]
            if not matched_entries:
                findings.append(GuardFinding(
                    "DB_UNAUTHORIZED_MUTATION", "error", declaration.path,
                     location, call_symbol,
                     {"dao": dao, "accessor": receiver, "operation": operation,
                      "mutation_kind": mutator.mutation_kind, "call_form": "receiver"},
                    "Database mutation is not owned by an exact policy entry",
                ))
            elif any(item.barrier_mode is BarrierMode.DIRECT
                     for item in matched_entries):
                before = masked[start:call.start()]
                if not re.search(r"\bwriteBarrier\s*\.\s*(?:checkWritesAllowed|runWrite)\s*\(", before):
                    findings.append(GuardFinding(
                        "DB_MISSING_WRITE_BARRIER", "error", declaration.path,
                        location, call_symbol, {"dao": dao, "operation": operation},
                        "Database write lacks required barrier evidence",
                    ))

        for operation, pattern in _STRUCTURAL.items():
            # ``writableDatabase`` is a property access, not a method call;
            # it is handled below with the same resolved-receiver checks.
            if operation == "writableDatabase":
                continue
            for match in pattern.finditer(masked, start, end):
                if not any(
                    item.group("method") == operation
                    and item.start("method") == match.start()
                    for item in calls
                ):
                    diagnostics.append(_line_diagnostic(
                        "DB_STRUCTURAL_SCOPE_UNSUPPORTED", declaration.path,
                        _line(source, match.start()),
                    ))
                    continue
                structural_symbol = symbol
                if declaration.kind == "property":
                    try:
                        structural_symbol = _property_symbol_at(source, declaration, match.start())
                    except SignatureError:
                        diagnostics.append(_line_diagnostic(
                            "DB_STRUCTURAL_SCOPE_UNSUPPORTED", declaration.path,
                            _line(source, match.start()),
                        ))
                        continue
                if _is_unresolved_symbol(structural_symbol):
                    # Findings must never lack resolved callable identity:
                    # the structural rule declares symbol.* identity fields,
                    # so an unresolved signature takes the controlled
                    # DB_SIGNATURE_UNRESOLVED diagnostic path -- the same
                    # shape every other unresolved path here uses -- and the
                    # finding is skipped.  Identical emissions deduplicate.
                    diagnostics.append(GuardDiagnostic(
                        "DB_SIGNATURE_UNRESOLVED", path=declaration.path,
                    ))
                    continue
                if not _structural_match(structural, declaration.path,
                                         declaration.owner_fqcn.rsplit(".", 1)[-1],
                                         structural_symbol.name, operation):
                    findings.append(GuardFinding(
                        "DB_FORBIDDEN_STRUCTURAL_OPERATION", "error", declaration.path,
                        SourceLocation(_line(source, match.start()), end_line=_line(source, match.start())),
                         structural_symbol, {"operation": operation},
                         "Forbidden structural database operation",
                        ))

        for match in _PROPERTY_STRUCTURAL_ACCESS.finditer(masked, start, end):
            operation = match.group("property")
            receiver, receiver_is_bare = _receiver_expression(masked, match.start())
            # The property-access path runs OUTSIDE the method-call loop, so
            # it must resolve the receiver environment at ITS OWN position.
            # Reusing the last call's environment leaked a sibling
            # declaration's lexical scope (or raised NameError when the
            # declaration had no earlier method call), misclassifying every
            # writableDatabase access after an unrelated mutation.
            receiver_types_at_access = _receiver_types(
                source, start, end,
                callable_start=env_callable_start,
                callable_end=env_callable_end,
                owner_start=owner.start_offset if owner is not None else None,
                owner_end=owner.end_offset if owner is not None else None,
                use_offset=match.start(),
            )
            receiver_type = receiver_types_at_access.get(receiver) if receiver_is_bare else None
            # Same verified-handle contract as the method-call structural
            # gate: a bare variable of a structural handle type, an
            # openHelper-typed receiver, a TYPE-spelled static path, or a
            # dotted chain fully resolved over the closed androidx member
            # map.  Anything else stays an honest unsupported-shape
            # diagnostic.
            if not _structural_access_supported(
                receiver, receiver_is_bare, receiver_type, operation,
                receiver_types_at_access,
            ):
                diagnostics.append(_line_diagnostic(
                    "DB_STRUCTURAL_SCOPE_UNSUPPORTED", declaration.path,
                    _line(source, match.start()),
                ))
                continue
            structural_symbol = symbol
            if declaration.kind == "property":
                try:
                    structural_symbol = _property_symbol_at(source, declaration, match.start())
                except SignatureError:
                    diagnostics.append(_line_diagnostic(
                        "DB_STRUCTURAL_SCOPE_UNSUPPORTED", declaration.path,
                        _line(source, match.start()),
                    ))
                    continue
            if _is_unresolved_symbol(structural_symbol):
                # Same resolved-identity contract as the method-shaped
                # structural path above: an unresolved signature becomes the
                # controlled DB_SIGNATURE_UNRESOLVED diagnostic, never a
                # finding.
                diagnostics.append(GuardDiagnostic(
                    "DB_SIGNATURE_UNRESOLVED", path=declaration.path,
                ))
                continue
            if not _structural_match(structural, declaration.path,
                                     declaration.owner_fqcn.rsplit(".", 1)[-1],
                                     structural_symbol.name, operation):
                findings.append(GuardFinding(
                    "DB_FORBIDDEN_STRUCTURAL_OPERATION", "error", declaration.path,
                    SourceLocation(_line(source, match.start()), end_line=_line(source, match.start())),
                    structural_symbol, {"operation": operation},
                    "Forbidden structural database operation",
                ))

        # A structural API token without the complete supported call shape is
        # still evidence of database access.  Do not discard it as an
        # uninteresting identifier (for example ``db.execSQL`` without an
        # invocation); emit a diagnostic instead of guessing its scope.
        for operation, token_pattern in _UNSUPPORTED_STRUCTURAL.items():
            supported_starts = {
                match.start() for match in _STRUCTURAL[operation].finditer(masked, start, end)
            }
            for token in token_pattern.finditer(masked, start, end):
                if token.start() not in supported_starts:
                    diagnostics.append(_line_diagnostic(
                        "DB_STRUCTURAL_SCOPE_UNSUPPORTED", declaration.path,
                        _line(source, token.start()),
                    ))

    # Deduplicate by full content identity (``FrozenDict`` hashes and
    # compares by value, so no repr() is needed: object-identity reprs are
    # neither process-stable nor content-sensitive) and order
    # deterministically by (code, path, symbol, line).  ``line`` is the
    # bounded context coordinate; diagnostics without one sort first
    # within identical code/path/symbol because real lines are >= 1.
    diagnostics = tuple(sorted(
        {(item.code, item.path, item.symbol, item.controlled_context): item
         for item in diagnostics}.values(),
        key=lambda item: (item.code, item.path or "", item.symbol or "",
                          item.controlled_context.get("line", 0)),
    ))
    # GR-07 Option-B trust computation: only BLOCKING diagnostics break
    # trust.  Advisory diagnostics (the bounded controlled_context["advisory"]
    # marker on declarations with no DB-surface evidence) are reported but
    # never discard findings and never flip ``trusted``.  Pre-scan and
    # infrastructure emissions are never flagged advisory, so they stay
    # blocking here by construction.
    blocking_diagnostics = tuple(
        item for item in diagnostics
        if item.controlled_context.get("advisory") is not True
    )
    # Infrastructure diagnostics are never a partial authorization result.
    # Discard all provisional findings so callers cannot baseline an untrusted
    # source/inventory parse.
    if blocking_diagnostics:
        findings = []
    statistics = {"files_scanned": len(sources), "declarations_scanned": len(declarations.helper_ranges), "inventory_daos": len(inventory.daos), "inventory_mutators": len(inventory.mutators), "trusted": not bool(blocking_diagnostics), "advisoryDiagnosticCount": len(diagnostics) - len(blocking_diagnostics)}
    return GuardRunReport(guard="db_access", findings=tuple(findings), diagnostics=diagnostics, statistics=statistics)


__all__ = ["scan_db_access"]
