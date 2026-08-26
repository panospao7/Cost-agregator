"""Room-aware database access discovery (D4).

This module intentionally performs source-range discovery only.  It does not
consume legacy guard stdout, infer identity from filenames, or create policy
rules.  Uncertainty is represented by registered protocol diagnostics and
therefore makes the returned report untrusted. Clean v2 reports exit 0,
findings exit 1, and diagnostics exit 2.

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
    ParserError, find_callable_declarations, find_owner_declarations,
    mask_kotlin_source,
)
from .declaration_scanner import (
    anchor_for_declared_path,
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
# Calls are found from the dot and their receiver is parsed backwards.  A
# suffix regex is unsafe here: it turns ``context.expenseDao`` and
# ``holder(expenseDao)`` into the apparently bare ``expenseDao``.
_METHOD_CALL = re.compile(r"\.(?P<safe>\?)?\s*(?P<method>[A-Za-z_]\w*)\s*\(")
_TYPE = re.compile(r"\b(?:val|var|private|protected|internal|public|lateinit\s+var)\s+(?P<name>[A-Za-z_]\w*)\s*:\s*(?P<type>[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)")
_PARAM = re.compile(r"\b(?P<name>[A-Za-z_]\w*)\s*:\s*(?P<type>[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)")
_DECL_PARAM = re.compile(r"(?:fun\s+)?[A-Za-z_]\w*\s*\((?P<body>[^)]*)\)")
_ACCESSOR = re.compile(r"\b(?P<kind>get|set)\s*\((?P<params>[^)]*)\)")
_PROPERTY_STRUCTURAL_ACCESS = re.compile(r"\.(?P<property>writableDatabase)\b")


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


def _line_diagnostic(code: str, path: str | None, line: int | None) -> GuardDiagnostic:
    """Controlled diagnostic carrying its source line as bounded context.

    The current ``GuardDiagnostic`` contract has no ``location`` field; the
    one meaningful coordinate survives as a bounded positive int under the
    ``controlled_context["line"]`` key.  Sites without a meaningful line
    omit the key entirely.
    """
    context: dict[str, int] = {}
    if line is not None:
        context["line"] = min(max(int(line), 1), _MAX_DIAGNOSTIC_LINE)
    return GuardDiagnostic(code, path=path, controlled_context=context)


def _property_symbol_at(source: str, declaration, offset: int) -> CallableSymbol:
    """Resolve a property initializer/getter/setter at an exact source offset."""
    begin = _line_start(source, declaration.start_line)
    end = _line_end(source, declaration.end_line)
    text = source[begin:end]
    name_match = re.search(r"\b(?:val|var)\s+([A-Za-z_]\w*)", text)
    if not name_match:
        raise SignatureError("BAD_NAME")
    name = name_match.group(1)
    accessors = list(_ACCESSOR.finditer(mask_kotlin_source(text)))
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
        parameters = (normalize_type_text(match.group(1).strip()),)
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
    closing = {')': '(', ']': '[', '}': '{'}
    while i >= 0:
        char = masked[i]
        if char in depth:
            depth[char] += 1
        elif char in closing:
            if depth[char] > 0:
                depth[char] -= 1
            else:
                break
        elif not any(depth.values()) and char in ';={}\n':
            break
        i -= 1
    expression = masked[i + 1:end].strip()
    return expression, (not safe and bool(re.fullmatch(r"[A-Za-z_]\w*", expression)))


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
        if value in receiver_types:
            result.append(receiver_types[value])
        elif re.fullmatch(r"[0-9]+", value):
            result.append("Int")
        elif re.fullmatch(r"[0-9]+[lL]", value):
            result.append("Long")
        elif value in {"true", "false"}:
            result.append("Boolean")
        elif re.fullmatch(r'"(?:[^"\\]|\\.)*"', value, re.S):
            result.append("String")
        else:
            return None
    return tuple(result)


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
    # innermost-shadowing rule.
    body_start = masked.find("{", cstart, cend)
    if body_start >= 0:
        body_scope = scope_for(body_start + 1, (body_start, cend))
        for match in _TYPE.finditer(source, body_start + 1, cend):
            declaration_scope = scope_for(match.start(), body_scope)
            candidates.append((match.group("name"), match.group("type"),
                               match.start(), declaration_scope))

    resolved: dict[str, str | None] = {}
    by_name: dict[str, list[tuple[str, int, tuple[int, int]]]] = {}
    for name, typ, declared, scope in candidates:
        if declared >= use or not (scope[0] <= use <= scope[1]):
            continue
        by_name.setdefault(name, []).append((typ, declared, scope))
    for name, values in by_name.items():
        innermost = min(item[2][1] - item[2][0] for item in values)
        visible = [item for item in values
                   if item[2][1] - item[2][0] == innermost]
        types = {item[0] for item in visible}
        resolved[name] = next(iter(types)) if len(visible) == 1 else None
    return resolved


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
    findings: list[GuardFinding] = []
    # Read each file once; declaration ranges are the authoritative scan
    # units.  Paths map back through the SAME declared-root anchors the
    # declaration scanner used to emit them, so single-root inputs read
    # exactly as before and every declared root of a multi-root repository
    # resolves to its own enclosing project.
    pairs = declared_root_pairs(source_root, root_set) if root_set is not None else ()
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
        if declaration.kind == "property":
            # Duplicated accessor kinds make the property's callable identity
            # ambiguous (which ``set`` would a policy authorize?): fail
            # closed with the controlled unresolved-signature diagnostic
            # instead of letting the first accessor silently stand in for
            # the whole property's identity.
            accessor_span = masked[_line_start(source, declaration.start_line):
                                   _line_end(source, declaration.end_line)]
            accessor_kinds = [
                item.group("kind") for item in _ACCESSOR.finditer(accessor_span)
            ]
            if len(accessor_kinds) != len(set(accessor_kinds)):
                diagnostics.append(GuardDiagnostic(
                    "DB_SIGNATURE_UNRESOLVED", path=declaration.path,
                ))
                continue
        try:
            owners = find_owner_declarations(source)
            owner = next((item for item in owners if item.owner == declaration.owner_fqcn), None)
            callables = find_callable_declarations(source, owner or declaration.owner_fqcn)
            callable_item = next((item for item in callables if item.start_offset <= start <= item.end_offset), None)
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
            diagnostics.append(GuardDiagnostic("DB_SIGNATURE_UNRESOLVED", path=declaration.path))
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
                    accessor_parameters[param_match.group(1)] = param_match.group(2).strip()
        calls = list(_METHOD_CALL.finditer(masked, start, end))
        for call in sorted(calls, key=lambda item: item.start()):
            receiver_types = _receiver_types(
                source, start, end,
                callable_start=callable_item.start_offset if callable_item is not None else start,
                callable_end=callable_item.end_offset if callable_item is not None else end,
                owner_start=owner.start_offset if owner is not None else None,
                owner_end=owner.end_offset if owner is not None else None,
                use_offset=call.start(),
            )
            if accessor_parameters:
                merged = dict(accessor_parameters)
                merged.update(receiver_types)
                receiver_types = merged
            receiver, receiver_is_bare = _receiver_expression(masked, call.start())
            operation = call.group("method")
            receiver_type = receiver_types.get(receiver) if receiver_is_bare else None
            call_symbol = symbol
            if declaration.kind == "property":
                try:
                    call_symbol = _property_symbol_at(source, declaration, call.start())
                except SignatureError:
                    diagnostics.append(_line_diagnostic(
                        "DB_STRUCTURAL_SCOPE_UNSUPPORTED", declaration.path,
                        _line(source, call.start()),
                    ))
                    continue
            is_structural = operation in _STRUCTURAL
            if is_structural and (
                not receiver_is_bare or not _is_structural_receiver(receiver_type)
            ):
                diagnostics.append(_line_diagnostic(
                    "DB_STRUCTURAL_SCOPE_UNSUPPORTED", declaration.path,
                    _line(source, call.start()),
                ))
                continue
            if (receiver_is_bare and _is_structural_receiver(receiver_type)
                    and operation not in _STRUCTURAL
                    and not any(key[1] == operation for key in dao_methods)):
                diagnostics.append(_line_diagnostic(
                    "DB_STRUCTURAL_SCOPE_UNSUPPORTED", declaration.path,
                    _line(source, call.start()),
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
            # direct, qualified, and nested safe-call forms.
            if call.groupdict().get("safe") or not receiver_is_bare:
                diagnostics.append(GuardDiagnostic(
                    "DB_DAO_SCOPE_UNRESOLVED", path=declaration.path,
                ))
                continue
            typ = receiver_types.get(receiver)
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
                diagnostics.append(GuardDiagnostic(
                    "DB_SIGNATURE_UNRESOLVED", path=declaration.path,
                ))
                continue
            candidates = [item for item in candidates
                          if tuple(item.parameters) == argument_types]
            if len(candidates) != 1:
                diagnostics.append(GuardDiagnostic("DB_CALL_TARGET_AMBIGUOUS", path=declaration.path))
                continue
            method = candidates[0]
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
                callable_start=callable_item.start_offset if callable_item is not None else start,
                callable_end=callable_item.end_offset if callable_item is not None else end,
                owner_start=owner.start_offset if owner is not None else None,
                owner_end=owner.end_offset if owner is not None else None,
                use_offset=match.start(),
            )
            receiver_type = receiver_types_at_access.get(receiver) if receiver_is_bare else None
            if not receiver_is_bare or not _is_structural_receiver(receiver_type):
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
    # Infrastructure diagnostics are never a partial authorization result.
    # Discard all provisional findings so callers cannot baseline an untrusted
    # source/inventory parse.
    if diagnostics:
        findings = []
    statistics = {"files_scanned": len(sources), "declarations_scanned": len(declarations.helper_ranges), "inventory_daos": len(inventory.daos), "inventory_mutators": len(inventory.mutators), "trusted": not bool(diagnostics)}
    return GuardRunReport(guard="db_access", findings=tuple(findings), diagnostics=diagnostics, statistics=statistics)


__all__ = ["scan_db_access"]
