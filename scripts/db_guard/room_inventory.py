"""Fail-closed inventory of Room DAO mutators.

This module deliberately uses the small DAO accessor parser instead of
guessing from method names.  It is a source inventory, not a Kotlin parser;
constructs it cannot identify are reported as controlled diagnostics.
"""

from __future__ import annotations

import json
import os
import re
import tempfile
from contextlib import suppress
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

try:
    import yaml
except ImportError:  # pragma: no cover - the policy path requires PyYAML
    yaml = None

from .dao_accessors import (
    AccessorError, DaoId, DaoMethodAnnotation, DaoMethodId,
    find_dao_declarations, find_dao_method_annotations,
)
from ..db_policy_signature import SignatureError, normalize_type_text
from ..kotlin_callable_parser import ParserError, mask_kotlin_source
from .source_roots import DB_SOURCE_ROOT_UNDECLARED, SourceRootSet, resolve_source_root_set
from .sql_classifier import classify_sql


INVENTORY_SCHEMA = "cost-aggregator.room-mutator-inventory"
INVENTORY_VERSION = 1

# Canonical production RawQuery classification policy.  The default lookup
# always reads this exact file; test fixtures under scripts/fixtures are
# never consulted unless a caller passes them explicitly.
DEFAULT_RAW_QUERY_POLICY = str(
    Path(__file__).resolve().parents[2] / "config" / "guards" / "db_raw_query_classification.yml"
)


class InventoryWriteError(RuntimeError):
    """A sanitized, controlled failure from the inventory writer."""

    code = "DB_ROOM_INVENTORY_WRITE_FAILED"

    def __init__(self, target_status: str = "NOT_REPLACED") -> None:
        self.target_status = target_status
        super().__init__(self.code)


class InventoryDurabilityUnconfirmedError(InventoryWriteError):
    """The target was replaced, but the directory durability barrier failed."""

    code = "INVENTORY_DURABILITY_UNCONFIRMED"

    def __init__(self) -> None:
        super().__init__("REPLACED_NOT_DURABLE")


@dataclass(frozen=True)
class RoomMutator:
    method: str
    mutation_kind: str
    annotation: str
    query_kind: str | None
    inherited_from: str | None
    source_location: str


@dataclass(frozen=True)
class _RawQueryIdentity:
    """One effective discovered @RawQuery identity.

    ``location`` is the controlled repository-relative source location of the
    ``@RawQuery`` annotation that declares the method.  For identities
    inherited by a child DAO the location stays at the original declaring
    annotation while ``inherited_from`` records the immediate parent through
    which the identity flowed during fixed-point inheritance resolution;
    ``None`` means the identity is declared directly by its owning DAO.
    """

    location: str
    inherited_from: str | None


@dataclass(frozen=True)
class RoomInventory:
    schema: str
    schema_version: int
    daos: tuple[Any, ...]
    methods: tuple[Any, ...]
    mutators: tuple[RoomMutator, ...]
    diagnostics: tuple[str, ...]


_DATABASE = re.compile(r"@(?:[A-Za-z_][A-Za-z0-9_]*\.)*Database\s*\((?P<body>[^)]*)\)", re.S)
_VERSION = re.compile(r"\bversion\s*=\s*(?P<version>[0-9]+)\b")
_HEADER_PARENTS = re.compile(r"\b(?:interface|abstract\s+class)\s+(?P<name>[A-Za-z_]\w*)\s*(?:<[^>{}]*>)?\s*:\s*(?P<parents>[^\{]+)")


def _diag(code: str, location: str | None = None) -> str:
    # Diagnostics are intentionally codes and canonical source locations only.
    return code if not location else f"{code}:{location}"


# Approved production source roots are no longer hard-coded here.  They are
# resolved through the shared contract in ``scripts/db_guard/source_roots.py``
# (``resolve_source_root_set``): an explicit ``SourceRootSet`` wins, then the
# checked-in manifest ``config/guards/production_source_roots.yml`` (loaded,
# validated, and topology-verified; any diagnostic fails closed and never
# falls back), then the implicit conventional single root — a branch that
# exists solely for synthetic test fixtures and embedders without a manifest;
# real repositories always ship the manifest.


def _absolute_root_anchor(root_abs: str) -> str | None:
    """Project anchor for an implicit absolute declared root.

    Implicit bare-directory roots (a conventional source directory passed
    directly as ``source_root`` by synthetic fixtures or embedders) are
    anchored at their enclosing project so emitted paths stay
    repository-relative POSIX exactly as for manifest-declared roots:
    ``.../<module>/src/main/java`` anchors at the module's parent directory,
    and ``.../src/main/kotlin`` anchors at the enclosing module directory.
    Returns ``None`` (fail closed) when the tail does not match or no
    anchor remains above the tail.
    """
    parts = os.path.normpath(root_abs).split(os.sep)
    if parts[-3:] == ("src", "main", "java"):
        anchor_parts = parts[:-4]
    elif parts[-3:] == ("src", "main", "kotlin"):
        anchor_parts = parts[:-3]
    else:
        return None
    if not anchor_parts:
        return None
    return os.path.join(*anchor_parts)


def _declared_root_files(repo_root: Any, root_set: SourceRootSet) -> tuple[list[tuple[str, Path]], bool]:
    """Discover Kotlin files below every declared production source root.

    Walks each declared root in manifest order with the exact legacy
    traversal semantics (sorted directories, sorted filenames, silent skip
    of non-regular ``.kt`` entries) and anchors every emitted path at the
    repository root so paths remain repository-relative POSIX.  Implicit
    absolute roots are anchored at their enclosing project via
    ``_absolute_root_anchor``.  Returns ``(files, unreadable)``; any walk
    or stat failure marks the whole scan unreadable so the caller fails
    closed instead of emitting a partial inventory.  Test, androidTest,
    debug, release, and generated/build output roots are never walked:
    only declared production roots are.
    """
    result: list[tuple[str, Path]] = []
    unreadable = False

    def onerror(_error: OSError) -> None:
        nonlocal unreadable
        unreadable = True

    repo_abs = os.path.abspath(repo_root)
    for root in root_set.roots:
        declared = root.path
        if os.path.isabs(declared):
            base = os.path.normpath(declared)
            anchor = _absolute_root_anchor(base)
            if anchor is None:
                unreadable = True
                continue
        else:
            anchor = repo_abs
            base = os.path.join(repo_abs, *declared.split("/"))
        try:
            for directory, directories, filenames in os.walk(base, topdown=True, onerror=onerror):
                directories.sort()
                for filename in sorted(filenames):
                    if not filename.endswith(".kt"):
                        continue
                    path = Path(directory) / filename
                    try:
                        if not path.is_file():
                            continue
                        relative = path.relative_to(anchor).as_posix()
                    except (OSError, ValueError):
                        unreadable = True
                        continue
                    result.append((relative, path))
        except OSError:
            unreadable = True
    return result, unreadable


def _constant_query(argument: str | None) -> str | None:
    if argument is None:
        return None
    value = argument.strip()
    named = re.fullmatch(r"(?:value|query)\s*=\s*(.*)", value, re.S)
    if named:
        value = named.group(1).strip()
    if value.startswith('"""') and value.endswith('"""'):
        return value[3:-3]
    if len(value) >= 2 and value[0] == '"' and value[-1] == '"':
        body = value[1:-1]
        # Decode only the small, ASCII Kotlin escape subset which can occur in
        # an SQL annotation.  ``unicode_escape`` is deliberately not used: it
        # silently turns malformed/non-ASCII escapes into different SQL.
        decoded: list[str] = []
        index = 0
        escapes = {"\\": "\\", '"': '"', "'": "'", "n": "\n", "r": "\r", "t": "\t", "b": "\b", "f": "\f", "$": "$"}
        while index < len(body):
            char = body[index]
            if char != "\\":
                decoded.append(char)
                index += 1
                continue
            if index + 1 >= len(body) or body[index + 1] not in escapes:
                return None
            decoded.append(escapes[body[index + 1]])
            index += 2
        return "".join(decoded)
    return None


# Bounds for @Query template constant resolution.  Resolution is bounded so
# adversarial or malformed const chains cannot consume unbounded work; cycles
# are detected explicitly and fail closed.
_QUERY_TEMPLATE_MAX_DEPTH = 64
# Whitespace and newlines are allowed between the name, the optional type,
# and ``=`` so ``const val NAME = "..."``, ``const val NAME: String = ...``,
# and line-broken declarations such as
# ``const val NAME: String =\n    "..." + ...`` are all collected.  The
# ``=`` still deliberately has no trailing ``\s*``: ``mask_kotlin_source``
# blanks string literals to spaces, so a greedy ``\s*`` after ``=`` would
# swallow the literal (and the line break) and move ``match.end()`` past the
# value, misaligning the offset at which ``_split_const_rhs_operands`` reads
# the original source.
_CONST_VAL_DECLARATION = re.compile(
    r"\bconst\s+val\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)\s*(?::\s*[A-Za-z_][A-Za-z0-9_.]*\s*)?="
)
_QUERY_TEMPLATE_NAME = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")


def _split_const_rhs_operands(source: str, start: int) -> tuple[str, ...] | None:
    """Split a ``const val`` RHS into ``+``-joined operands.

    Scanning is quote-aware.  The expression ends at a top-level newline
    unless the current line ends with ``+`` (Kotlin line continuation) or at
    a top-level ``//`` comment, so a trailing comment or the next statement
    never leaks into an operand.  A newline is treated as legal whitespace
    while the RHS line still carries no content, so an expression that
    begins on the line *after* ``=`` (for example the production
    ``EFFECTIVE_AMOUNT_SQL`` shape) is collected.  Returns None when the RHS
    has an unbalanced quote; that declaration is then not collected and any
    query referencing it fails closed.
    """
    operands: list[str] = []
    operand_start = start
    index = start
    length = len(source)
    quote: str | None = None  # "'" or '"' for single-quoted, '"""' for triple
    line_tail = ""  # last significant character of the current line
    line_content = False  # any non-whitespace seen on the current line
    while index < length:
        char = source[index]
        if quote == '"""':
            line_content = True
            if source.startswith('"""', index):
                index += 3
                quote = None
            else:
                index += 1
            continue
        if quote is not None:
            line_content = True
            if char == "\\":
                index += 2
            else:
                index += 1
                if char == quote:
                    quote = None
            continue
        if source.startswith('"""', index):
            quote = '"""'
            index += 3
            line_tail = '"'
            line_content = True
            continue
        if char in "\"'":
            quote = char
            index += 1
            line_tail = char
            line_content = True
            continue
        if source.startswith("//", index):
            break
        if char in "\r\n":
            if not line_content:
                # The current line only holds whitespace (before the first
                # RHS fragment or after a ``+`` continuation); the newline
                # is legal Kotlin whitespace, not the end of the expression.
                index += 1
                continue
            if line_tail == "+":
                line_tail = ""
                line_content = False
                index += 1
                continue
            break
        if char == "+":
            operands.append(source[operand_start:index])
            index += 1
            operand_start = index
            line_tail = "+"
            line_content = False
            continue
        if not char.isspace():
            line_tail = char
            line_content = True
        index += 1
    if quote is not None:
        return None
    operands.append(source[operand_start:index])
    return tuple(operands)


def _resolve_const_operands(operands: tuple[str, ...],
                            consts: dict[str, tuple[str, ...]],
                            depth: int, chain: tuple[str, ...]) -> str | None:
    """Resolve one ``const val`` RHS to a plain string, bounded and safe.

    Each operand is either a string literal (decoded with the same small
    escape subset as ``_constant_query``) or an identifier naming another
    collected constant.  Unknown names, conflicting duplicates, malformed
    values, excessive depth, and cycles all return None so the caller fails
    closed.  No code is ever evaluated; this is pure string handling.
    """
    if depth > _QUERY_TEMPLATE_MAX_DEPTH:
        return None
    parts: list[str] = []
    for operand in operands:
        text = operand.strip()
        literal = _constant_query(text)
        if literal is not None:
            parts.append(literal)
            continue
        if not _QUERY_TEMPLATE_NAME.fullmatch(text):
            return None
        if text in chain or text not in consts:
            return None
        value = _resolve_const_operands(consts[text], consts, depth + 1, chain + (text,))
        if value is None:
            return None
        parts.append(value)
    return "".join(parts)


def _file_query_consts(source: str) -> dict[str, tuple[str, ...]] | None:
    """Collect same-file ``const val NAME = ...`` query constants.

    Only safe, exact forms are collected: ``const val NAME = "..."`` string
    literals and ``const val NAME = OTHER + "..."`` concatenations whose
    operands are themselves literals or collected constants.  The masked
    source locates declarations (so comments and strings are never mistaken
    for declarations) while values are read from the original text at the
    same offsets.  Returns None when the source cannot be masked (fail
    closed).
    """
    try:
        masked = mask_kotlin_source(source)
    except ParserError:
        return None
    consts: dict[str, tuple[str, ...]] = {}
    for match in _CONST_VAL_DECLARATION.finditer(masked):
        name = match.group("name")
        operands = _split_const_rhs_operands(source, match.end())
        if operands is None or len(operands) == 1 and not operands[0].strip():
            continue
        if name in consts and consts[name] != operands:
            # Conflicting duplicates are ambiguous; queries referencing the
            # name must fail closed instead of choosing one declaration.
            consts[name] = ()
        else:
            consts[name] = operands
    return {name: operands for name, operands in consts.items() if operands}


def _resolve_query_template(template: str, consts: dict[str, tuple[str, ...]]) -> str | None:
    """Substitute ``${NAME}``/``$NAME`` in a decoded @Query template.

    Only names present in the same-file const map are replaced.  Unknown
    names, runtime expressions, and malformed ``$`` forms return None so
    the caller emits ``DB_ROOM_QUERY_UNCLASSIFIABLE``.
    """
    parts: list[str] = []
    index = 0
    length = len(template)
    while index < length:
        dollar = template.find("$", index)
        if dollar < 0:
            parts.append(template[index:])
            break
        parts.append(template[index:dollar])
        rest = template[dollar + 1:]
        if rest.startswith("{"):
            closing = rest.find("}")
            if closing < 0:
                return None
            name = rest[1:closing]
            if not _QUERY_TEMPLATE_NAME.fullmatch(name):
                return None
            operands = consts.get(name)
            if operands is None:
                return None
            resolved = _resolve_const_operands(operands, consts, 0, (name,))
            if resolved is None:
                return None
            parts.append(resolved)
            index = dollar + 2 + len(name)
        else:
            match = _QUERY_TEMPLATE_NAME.match(rest)
            if match is None:
                return None
            name = match.group(0)
            operands = consts.get(name)
            if operands is None:
                return None
            resolved = _resolve_const_operands(operands, consts, 0, (name,))
            if resolved is None:
                return None
            parts.append(resolved)
            index = dollar + 1 + len(name)
    return "".join(parts)


def _direct_const_query(argument: str | None, source: str) -> str | None:
    """Resolve a bare same-file const reference, e.g. ``@Query(SELECT_FROM)``.

    ``@Query(NAME)`` (including the ``@Query(value = NAME)`` and
    ``@Query(query = NAME)`` spellings) names a same-file ``const val``
    directly.  The named const is resolved through the same bounded,
    cycle-checked chain as ``$NAME`` template references.  Any other
    argument shape returns None (fail closed); the caller reports
    ``DB_ROOM_QUERY_UNCLASSIFIABLE``.
    """
    if argument is None:
        return None
    value = argument.strip()
    named = re.fullmatch(r"(?:value|query)\s*=\s*(.*)", value, re.S)
    if named:
        value = named.group(1).strip()
    if not value or not _QUERY_TEMPLATE_NAME.fullmatch(value):
        return None
    consts = _file_query_consts(source)
    if consts is None:
        return None
    operands = consts.get(value)
    if operands is None:
        return None
    return _resolve_const_operands(operands, consts, 0, (value,))


def _query_sql(argument: str | None, source: str) -> str | None:
    """Decode a @Query argument and resolve same-file constant templates.

    Returns the plain SQL when the argument is a literal with no
    interpolation, a literal whose ``${NAME}``/``$NAME`` references all
    resolve to collected same-file consts, or a bare identifier naming a
    same-file const (``@Query(SELECT_FROM)``).  Returns None otherwise
    (fail closed); the caller reports ``DB_ROOM_QUERY_UNCLASSIFIABLE``.
    """
    template = _constant_query(argument)
    if template is not None:
        if "$" not in template:
            return template
        consts = _file_query_consts(source)
        if consts is None:
            return None
        return _resolve_query_template(template, consts)
    return _direct_const_query(argument, source)


def _line(source: str, offset: int) -> int:
    return source.count("\n", 0, offset) + 1


_POLICY_ENTRY_KEYS = frozenset({"dao", "method", "signature", "classification", "reason", "owner", "linked_issue"})
_POLICY_SIGNATURE_KEYS = frozenset({"receiver", "parameters"})
_CLASSIFICATIONS = frozenset({"read", "write"})
_IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*$")
_METHOD_NAME = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
# The only supported Room RawQuery query parameter.  The exact canonical FQCN
# is confirmed by the production source root: every production @RawQuery
# declaration imports ``androidx.sqlite.db.SupportSQLiteQuery``.  A @RawQuery
# method whose single parameter does not resolve to exactly this type is
# never a valid policy identity and never a mutator, even when a policy entry
# appears to match its signature.
_SUPPORT_QUERY_FQCN = "androidx.sqlite.db.SupportSQLiteQuery"


class _InheritanceParseError(Exception):
    """A controlled failure while reading a DAO's parent declaration."""


def _split_parent_types(text: str) -> list[str]:
    """Split a Kotlin supertype list without splitting generic arguments."""
    result: list[str] = []
    start = 0
    depth = 0
    for index, char in enumerate(text):
        if char in "(<[{":
            depth += 1
        elif char in ")>]}" and depth:
            depth -= 1
        elif char in ")>]}" :
            raise _InheritanceParseError()
        elif char == "," and depth == 0:
            if not text[start:index].strip():
                raise _InheritanceParseError()
            result.append(text[start:index].strip())
            start = index + 1
    if depth != 0 or not text[start:].strip():
        raise _InheritanceParseError()
    result.append(text[start:].strip())
    return result


@dataclass(frozen=True)
class _TypeDeclaration:
    fqcn: str
    name: str
    start: int
    end: int
    header: str


def _type_declarations(source: str) -> tuple[_TypeDeclaration, ...]:
    """Return declarations with their lexical owner, rather than by name alone."""
    masked = mask_kotlin_source(source)
    package_match = re.search(r"\bpackage\s+([A-Za-z_][A-Za-z0-9_.]*)\b", masked)
    package = package_match.group(1) if package_match else ""
    declarations: list[_TypeDeclaration] = []
    all_matches = list(re.finditer(
        r"\b(?:(?:public|private|internal|protected|open|abstract|final|sealed)\s+)*"
        r"(?:class|interface|object)\s+(?P<name>[A-Za-z_]\w*)\b(?P<header>[^{}]*)\{",
        masked,
    ))
    spans: list[tuple[int, int, str]] = []
    for match in all_matches:
        depth = 0
        close = -1
        for index in range(match.end() - 1, len(masked)):
            if masked[index] == "{":
                depth += 1
            elif masked[index] == "}":
                depth -= 1
                if depth == 0:
                    close = index
                    break
        if close < 0:
            raise _InheritanceParseError()
        spans.append((match.start(), close, match.group("name")))
    for match, (_, close, name) in zip(all_matches, spans):
        owners = [owner for start, end, owner in spans if start < match.start() and close <= end]
        fqcn = ".".join(([package] if package else []) + owners + [name])
        declarations.append(_TypeDeclaration(fqcn, name, match.start(), close, match.group("header")))
    # Kotlin permits a bodyless interface/class declaration.  DAO fixtures
    # commonly use that form for inheritance-only children; it still has a
    # real supertype list and must participate in graph validation.
    bodyless = re.finditer(
        r"\b(?:(?:public|private|internal|protected|open|abstract|final|sealed)\s+)*"
        r"(?:class|interface|object)\s+(?P<name>[A-Za-z_]\w*)\b(?P<header>[^{}\n]*)(?=\n|$)",
        masked,
    )
    known_starts = {item.start for item in declarations}
    for match in bodyless:
        if match.start() in known_starts:
            continue
        owners = [owner for start, end, owner in spans if start < match.start() <= end]
        fqcn = ".".join(([package] if package else []) + owners + [match.group("name")])
        declarations.append(_TypeDeclaration(fqcn, match.group("name"), match.start(), match.end(), match.group("header")))
    return tuple(declarations)


def _dao_parents(dao: DaoId, source: str) -> tuple[str, ...]:
    """Read direct parent names from this DAO's exact lexical declaration."""
    declarations = [item for item in _type_declarations(source) if item.fqcn == dao.fqcn]
    if len(declarations) != 1:
        raise _InheritanceParseError()
    header = declarations[0].header
    if ":" not in header:
        return ()
    parents: list[str] = []
    for parent in _split_parent_types(header.split(":", 1)[1]):
        parent = re.sub(r"\s*\(.*\)\s*$", "", parent).strip()
        parent = re.sub(r"<.*>\s*$", "", parent).strip()
        if not _IDENTIFIER.fullmatch(parent):
            raise _InheritanceParseError()
        parents.append(parent)
    return tuple(parents)


def _resolve_parent(parent: str, dao: DaoId, source: str,
                    declarations: dict[str, list[_TypeDeclaration]],
                    imports: dict[str, str]) -> str | None:
    """Resolve a parent only through exact names, imports, or lexical scope."""
    if "." in parent:
        return parent if len(declarations.get(parent, ())) == 1 else None
    parts = dao.fqcn.split(".")[:-1]
    for count in range(len(parts), -1, -1):
        candidate = ".".join(parts[:count] + [parent])
        matches = declarations.get(candidate, ())
        if matches:
            return candidate if len(matches) == 1 else None
    imported = imports.get(parent)
    if imported and len(declarations.get(imported, ())) == 1:
        return imported
    return None


def _imports(source: str) -> dict[str, str]:
    masked = mask_kotlin_source(source)
    result: dict[str, str] = {}
    for match in re.finditer(r"\bimport\s+([A-Za-z_][A-Za-z0-9_.]*(?:\.\*)?)(?:\s+as\s+([A-Za-z_]\w*))?", masked):
        fqcn = match.group(1)
        name = match.group(2) or fqcn.rsplit(".", 1)[-1]
        if name in result and result[name] != fqcn:
            result[name] = ""
        else:
            result[name] = fqcn
    return result


def _type_imports(source: str) -> tuple[dict[str, str], set[str], set[str]]:
    """Return exact imports, wildcard packages, and ambiguous simple names."""
    masked = mask_kotlin_source(source)
    exact: dict[str, str] = {}
    ambiguous: set[str] = set()
    wildcards: set[str] = set()
    for match in re.finditer(r"\bimport\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\s+as\s+([A-Za-z_]\w*))?", masked):
        imported = match.group(1)
        alias = match.group(2) or imported.rsplit(".", 1)[-1]
        if imported.endswith(".*"):
            wildcards.add(imported[:-2])
            continue
        if alias in exact and exact[alias] != imported:
            ambiguous.add(alias)
        else:
            exact[alias] = imported
    return exact, wildcards, ambiguous


def _resolve_raw_query_parameters(method: DaoMethodId, source: str) -> tuple[str, ...] | None:
    """Resolve RawQuery type names without guessing package or wildcard scope."""
    exact, wildcards, ambiguous = _type_imports(source)
    resolved: list[str] = []
    for parameter in method.parameters:
        try:
            canonical = normalize_type_text(parameter, allow_vararg=True)
        except (SignatureError, TypeError):
            return None
        simple = canonical.rsplit(".", 1)[-1]
        if canonical == "SupportSQLiteQuery":
            if simple in ambiguous or simple not in exact:
                return None
            canonical = exact[simple]
        elif simple in ambiguous:
            return None
        elif canonical in exact:
            canonical = exact[canonical]
        # Generic/container types are kept canonical; only the RawQuery
        # contract's query type needs symbol resolution here.
        resolved.append(canonical)
    return tuple(resolved)


def _resolve_raw_query_contract(
    method: DaoMethodId, source: str
) -> tuple[tuple[str, str, str | None, tuple[str, ...]] | None, str | None]:
    """Resolve one @RawQuery callable to its exact policy identity (fail closed).

    The RawQuery signature contract requires exactly one parameter whose
    canonical type resolves to the exact ``androidx.sqlite.db.SupportSQLiteQuery``
    FQCN and no receiver.  Returns ``(key, None)`` for the valid identity and
    ``(None, reason)`` otherwise, where ``reason`` is the controlled
    diagnostic code the caller must emit:

    - ``DB_ROOM_RAW_QUERY_POLICY_INVALID`` for a resolvable-but-unsupported
      signature: a wrong parameter count, a wrong parameter type (String,
      Object, a generic/container, or a nullable type), or an extension
      receiver;
    - ``DB_SIGNATURE_UNRESOLVED`` when the parameter cannot be resolved at
      all (an unknown simple name, a wildcard import, or an ambiguous
      import).

    A method failing the contract is never a policy identity and can never
    become a mutator, even when a policy entry appears to match it.
    """
    try:
        receiver = (
            normalize_type_text(method.receiver) if method.receiver is not None else None
        )
    except (SignatureError, TypeError):
        return None, "DB_ROOM_RAW_QUERY_POLICY_INVALID"
    if receiver is not None:
        return None, "DB_ROOM_RAW_QUERY_POLICY_INVALID"
    try:
        parameters = _resolve_raw_query_parameters(method, source)
    except (ParserError, SignatureError, TypeError):
        return None, "DB_ROOM_RAW_QUERY_POLICY_INVALID"
    if parameters is None:
        return None, "DB_SIGNATURE_UNRESOLVED"
    if len(parameters) != 1 or parameters[0] != _SUPPORT_QUERY_FQCN:
        return None, "DB_ROOM_RAW_QUERY_POLICY_INVALID"
    return (method.dao.fqcn, method.name, receiver, parameters), None


def _is_broad(value: Any) -> bool:
    """Reject policy values which would turn an exact exception into a wildcard."""
    if not isinstance(value, str):
        return False
    normalized = value.strip().lower()
    return not normalized or normalized in {"*", "**", "any", "all", "wildcard"} or any(
        marker in value for marker in ("*", "%", "...")
    )


def _policy_entries(policy: Any) -> tuple[dict[str, Any], ...] | None:
    if policy is None:
        return ()
    data = policy
    if isinstance(policy, (str, os.PathLike)):
        if yaml is None:
            return None
        try:
            with open(policy, "r", encoding="utf-8") as handle:
                data = yaml.safe_load(handle)
        except (OSError, ValueError, TypeError):
            return None
        except Exception:  # malformed YAML must remain a controlled failure
            return None
    if not isinstance(data, dict) or set(data) != {"version", "methods"} or data.get("version") != 1:
        return None
    data = data["methods"]
    if not isinstance(data, list):
        return None
    entries: list[dict[str, Any]] = []
    keys_seen: set[tuple[str, str, str | None, tuple[str, ...]]] = set()
    for item in data:
        if not isinstance(item, dict) or set(item) != _POLICY_ENTRY_KEYS:
            return None
        dao = item["dao"]
        method = item["method"]
        signature = item["signature"]
        classification = item["classification"]
        if not isinstance(dao, str) or _is_broad(dao) or not _IDENTIFIER.fullmatch(dao):
            return None
        if not isinstance(method, str) or _is_broad(method) or not _METHOD_NAME.fullmatch(method):
            return None
        if not isinstance(signature, dict) or set(signature) != _POLICY_SIGNATURE_KEYS:
            return None
        receiver = signature["receiver"]
        parameters = signature["parameters"]
        if receiver is not None and (not isinstance(receiver, str) or _is_broad(receiver)):
            return None
        if not isinstance(parameters, list):
            return None
        try:
            canonical_receiver = normalize_type_text(receiver) if receiver is not None else None
            canonical_parameters = tuple(
                normalize_type_text(parameter, allow_vararg=True)
                for parameter in parameters
            )
        except (SignatureError, TypeError):
            return None
        # Policy input must already be canonical. Normalization above remains
        # necessary for validation and key construction, but equivalent
        # whitespace is only accepted for source-discovered signatures.
        if receiver != canonical_receiver or any(
            original != canonical
            for original, canonical in zip(parameters, canonical_parameters)
        ):
            return None
        if not isinstance(classification, str) or classification not in _CLASSIFICATIONS:
            return None
        if any(not isinstance(item[field], str) or _is_broad(item[field]) for field in ("reason", "owner", "linked_issue")):
            return None
        key = (dao, method, canonical_receiver, canonical_parameters)
        if key in keys_seen:
            return None
        keys_seen.add(key)
        canonical_entry = dict(item)
        canonical_entry["signature"] = {
            "receiver": canonical_receiver,
            "parameters": list(canonical_parameters),
        }
        entries.append(canonical_entry)
    return tuple(entries)


def _method_signature(method: DaoMethodId) -> str:
    receiver = f"({method.receiver})" if method.receiver else ""
    return f"{method.dao.canonical_path}::{method.dao.fqcn}#{method.name}{receiver}({', '.join(method.parameters)})"


def _policy_key(method: DaoMethodId) -> tuple[str, str, str | None, tuple[str, ...]]:
    """The policy identity is an exact callable identity, never a source path."""
    receiver = normalize_type_text(method.receiver) if method.receiver is not None else None
    parameters = tuple(normalize_type_text(parameter, allow_vararg=True) for parameter in method.parameters)
    return (method.dao.fqcn, method.name, receiver, parameters)


def _resolved_raw_query_key(
    method: DaoMethodId, source: str
) -> tuple[str, str, str | None, tuple[str, ...]] | None:
    """Resolve one method to its canonical RawQuery policy identity.

    Uses the exact same signature contract as direct @RawQuery discovery
    (``_resolve_raw_query_contract``) so inherited and direct identities
    live in the same key space and can shadow each other.  Methods failing
    the RawQuery signature contract (wrong parameter count/type, extension
    receivers, or unresolved signatures) return None (fail closed) and are
    never claimed as discovered identities.
    """
    key, _ = _resolve_raw_query_contract(method, source)
    return key


def _discovered_raw_query_keys(
    methods: list[DaoMethodId],
    method_annotations: dict[tuple[str, str, str, str | None, tuple[str, ...]], list[DaoMethodAnnotation]],
    sources: dict[str, str],
    daos: list[DaoId],
    parents_by_dao: dict[str, tuple[str, ...] | None],
    invalid_inheritance: set[str],
    duplicate_daos: set[str],
    dao_by_fqcn: dict[str, DaoId],
    diagnostics: list[str],
) -> dict[tuple[str, str, str | None, tuple[str, ...]], _RawQueryIdentity]:
    """The complete effective discovered @RawQuery identity set from every DAO.

    Direct @RawQuery identities are claimed exactly as before: only methods
    with exactly one RawQuery annotation record and a resolvable signature
    matching the RawQuery signature contract (exactly one parameter resolving
    to the canonical ``androidx.sqlite.db.SupportSQLiteQuery`` FQCN, no
    receiver) participate; ambiguous/conflicting declarations and
    contract-violating or unresolved signatures fail closed through their own
    diagnostics and can never be claimed as an exact policy match.

    On top of the direct set, inherited @RawQuery identities are resolved as
    a graph fixed point after the DAO inheritance graph is final.  For every
    child DAO exposing an inherited @RawQuery a new identity owned by the
    child DAO is created with the same exact method/signature/receiver/params
    and ``inherited_from`` metadata recording the immediate parent through
    which it flowed; all effective child identities join the discovered
    policy set so the canonical policy must carry a child-DAO entry for them.

    Fail-closed inheritance rules:

    - A child's own declaration of the same callable identity replaces the
      inherited one (declaration-only policy is never silently assumed to
      cover the child); the child's effective identity is then whatever its
      own declaration is, not the parent's @RawQuery.
    - Multiple parents exposing the same identity are ambiguous: the child
      identity is never claimed and
      ``DB_ROOM_RAW_QUERY_POLICY_INHERITED_AMBIGUOUS`` is emitted.
    - DAOs with unresolved, cyclic, or duplicate inheritance never claim
      inherited identities (their direct identities are preserved); the DAO
      inheritance graph already emitted the controlled
      ``DB_DAO_INHERITANCE_*`` diagnostics for those cases.
    """
    direct: dict[tuple[str, str, str | None, tuple[str, ...]], str] = {}
    direct_declared: dict[str, set[tuple[str, str | None, tuple[str, ...]]]] = {
        dao.fqcn: set() for dao in daos
    }
    for method in methods:
        source = sources.get(method.dao.canonical_path, "")
        key = _resolved_raw_query_key(method, source)
        if key is not None:
            direct_declared.setdefault(method.dao.fqcn, set()).add(key[1:])
        records = method_annotations.get(
            (method.dao.canonical_path, method.dao.fqcn, method.name, method.receiver, method.parameters),
            [],
        )
        if len(records) != 1 or records[0].kind != "RawQuery":
            continue
        if key is None:
            # The RawQuery signature contract diagnostic
            # (DB_ROOM_RAW_QUERY_POLICY_INVALID / DB_SIGNATURE_UNRESOLVED)
            # is emitted by direct_mutator; such identities are never claimed.
            continue
        location = f"{method.dao.canonical_path}:{_line(source, records[0].start)}"
        direct.setdefault(key, location)

    # Group direct identities by owning DAO.  The identity is stored as the
    # callable suffix ``(name, receiver, parameters)`` so it can be
    # transferred through arbitrarily deep inheritance and re-attached to the
    # owning DAO when the discovered set is built (a child-owned identity
    # carries the child's FQCN, never the declaring parent's).
    direct_by_dao: dict[str, dict[tuple[str, str | None, tuple[str, ...]], str]] = {
        dao.fqcn: {} for dao in daos
    }
    for key, location in direct.items():
        direct_by_dao[key[0]][key[1:]] = location
    effective = {fqcn: dict(items) for fqcn, items in direct_by_dao.items()}
    inherited_meta: dict[str, dict[tuple[str, str | None, tuple[str, ...]], str]] = {
        fqcn: {} for fqcn in dao_by_fqcn
    }
    for _ in range(max(1, len(daos) * len(daos) + 1)):
        changed = False
        for dao in sorted(daos, key=lambda item: item.fqcn):
            if (dao.fqcn in duplicate_daos or dao.fqcn in invalid_inheritance or
                    parents_by_dao.get(dao.fqcn) is None):
                # Preserve the DAO's direct identities, but never claim
                # inherited ones when its parent list could not be resolved.
                continue
            candidates: dict[
                tuple[str, str | None, tuple[str, ...]],
                list[tuple[str, str]],
            ] = {}
            for parent_fqcn in parents_by_dao[dao.fqcn]:
                if parent_fqcn not in dao_by_fqcn or parent_fqcn in invalid_inheritance:
                    continue
                for identity, location in effective[parent_fqcn].items():
                    candidates.setdefault(identity, []).append((parent_fqcn, location))
            new_inherited: dict[
                tuple[str, str | None, tuple[str, ...]],
                tuple[str, str],
            ] = {}
            for identity, options in candidates.items():
                if len(options) > 1:
                    # Ambiguous even when both parents expose the same
                    # signature/classification: never use traversal order as
                    # an implicit first-parent choice and never claim
                    # equality for an identity we cannot resolve.
                    diagnostics.append(_diag("DB_ROOM_RAW_QUERY_POLICY_INHERITED_AMBIGUOUS", dao.canonical_path))
                    continue
                parent_fqcn, location = options[0]
                new_inherited[identity] = (parent_fqcn, location)
            merged = dict(direct_by_dao[dao.fqcn])
            inherited_this: dict[
                tuple[str, str | None, tuple[str, ...]], str
            ] = {}
            for identity, (parent_fqcn, location) in new_inherited.items():
                if identity in merged or identity in direct_declared.get(dao.fqcn, ()):
                    # The child's own declaration replaces the inherited
                    # callable; the inherited @RawQuery identity is not
                    # claimed for this child.
                    continue
                merged[identity] = location
                inherited_this[identity] = parent_fqcn
            if merged != effective[dao.fqcn]:
                effective[dao.fqcn] = merged
                changed = True
            inherited_meta[dao.fqcn] = inherited_this
        if not changed:
            break

    discovered: dict[tuple[str, str, str | None, tuple[str, ...]], _RawQueryIdentity] = {}
    for fqcn in sorted(effective):
        for suffix in sorted(effective[fqcn]):
            discovered.setdefault(
                (fqcn,) + suffix,
                _RawQueryIdentity(
                    location=effective[fqcn][suffix],
                    inherited_from=inherited_meta.get(fqcn, {}).get(suffix),
                ),
            )
    return discovered


def _raw_query_mutators(
    discovered: dict[tuple[str, str, str | None, tuple[str, ...]], _RawQueryIdentity],
    policy: dict[tuple[str, str, str | None, tuple[str, ...]], str],
    dao_by_fqcn: dict[str, DaoId],
) -> dict[str, RoomMutator]:
    """Derive @RawQuery mutators from effective identities and the child-owned
    policy classification.

    Every effective identity (direct or inherited) is evaluated with its own
    exact callable key ``(dao_fqcn, method, receiver, parameters)`` against
    the policy:

    - ``read`` classification yields no mutator.  A read RawQuery is never a
      Room mutation, so an inherited write-classified parent callable is not
      emitted for a child whose own declaration (or inherited effective
      identity) is read-classified.
    - ``write`` classification yields a ``ROOM_MUTATING_QUERY`` mutator whose
      method identity is owned by the identity's DAO.  For an inherited
      identity that is the child DAO (child FQCN and child canonical path in
      the signature, ``inherited_from`` recording the immediate parent), never
      the declaring parent.
    - a missing policy classification yields no mutator; the
      ``DB_ROOM_RAW_QUERY_POLICY_REQUIRED`` diagnostic already failed closed
      for that exact identity.
    - ambiguous inherited identities were never claimed by
      ``_discovered_raw_query_keys`` (``DB_ROOM_RAW_QUERY_POLICY_INHERITED_
      AMBIGUOUS``) and therefore never appear here.

    The signature string is built from the canonical resolved parameter types
    (the same values that form the policy key), so direct and inherited
    mutators share one exact callable identity space.
    """
    result: dict[str, RoomMutator] = {}
    for key in sorted(discovered):
        fqcn, name, receiver, parameters = key
        if policy.get(key) != "write":
            continue
        dao = dao_by_fqcn.get(fqcn)
        if dao is None:
            continue
        identity = discovered[key]
        receiver_text = f"({receiver})" if receiver else ""
        signature = f"{dao.canonical_path}::{fqcn}#{name}{receiver_text}({', '.join(parameters)})"
        result[signature] = RoomMutator(
            signature,
            "ROOM_MUTATING_QUERY",
            "RawQuery",
            "WRITE",
            identity.inherited_from,
            identity.location,
        )
    return result


def _source_root_failure_diagnostics(root_diagnostics: Any) -> tuple[str, ...]:
    """Controlled untrusted-inventory diagnostics for a failed root-set
    resolution.

    The historical undeclared-conventional-layout failure keeps its exact
    legacy single-code shape so existing callers see only
    ``DB_ROOM_INVALID_SOURCE``; every other resolution failure additionally
    carries the resolved controlled ``DB_SOURCE_ROOT_*`` codes.  Codes only
    — never raw exception text, stack traces, or runtime-discovered paths.
    """
    codes = [code for code, _context in root_diagnostics]
    if codes == [DB_SOURCE_ROOT_UNDECLARED]:
        return (_diag("DB_ROOM_INVALID_SOURCE"),)
    unique = {"DB_ROOM_INVALID_SOURCE"}
    unique.update(codes)
    return tuple(sorted(unique))


def build_room_inventory(
    source_root: Any, raw_query_policy: Any = None, *, source_root_set: Any = None
) -> RoomInventory:
    """Discover Room mutators below *source_root*, conservatively.

    Discovery scans every declared production source root, resolved through
    the shared contract ``resolve_source_root_set``: an explicit keyword-only
    ``source_root_set`` (a ``SourceRootSet``) is used as-is; otherwise the
    checked-in manifest ``config/guards/production_source_roots.yml`` is
    loaded, validated, and topology-verified — any diagnostic fails closed
    and is never allowed to fall back; otherwise the implicit conventional
    single root (``app/src/main/java``, or the conventional source directory
    itself) is used.  That implicit branch exists solely for synthetic test
    fixtures and embedders without a manifest; real repositories always ship
    the manifest.  Test, androidTest, debug, release, and generated/build
    output roots are never inventoried.

    A failed resolution returns the standard untrusted-inventory shape with
    ``DB_ROOM_INVALID_SOURCE`` plus the controlled ``DB_SOURCE_ROOT_*``
    codes (the plain undeclared-conventional-layout case keeps the
    historical single-code shape).  All emitted source paths remain
    repository-relative POSIX exactly as before.

    ``raw_query_policy`` may be a YAML path, a parsed policy dict, or
    ``None``.  When ``None`` the canonical production policy at
    ``config/guards/db_raw_query_classification.yml`` is used; fixture
    policies must be passed explicitly so they are never used by default.
    """
    diagnostics: list[str] = []
    root_set, root_diagnostics = resolve_source_root_set(source_root, source_root_set)
    if root_set is None or root_diagnostics:
        return RoomInventory(
            INVENTORY_SCHEMA,
            INVENTORY_VERSION,
            (), (), (),
            _source_root_failure_diagnostics(root_diagnostics),
        )
    entries = _policy_entries(DEFAULT_RAW_QUERY_POLICY if raw_query_policy is None else raw_query_policy)
    policy_valid = entries is not None
    if entries is None:
        diagnostics.append(_diag("DB_ROOM_RAW_QUERY_POLICY_INVALID"))
        entries = ()
    policy = {
        (entry["dao"], entry["method"], entry["signature"]["receiver"], tuple(entry["signature"]["parameters"])):
        entry["classification"]
        for entry in entries
    }
    files, walk_unreadable = _declared_root_files(source_root, root_set)
    if walk_unreadable:
        diagnostics.append(_diag("DB_ROOM_SOURCE_UNREADABLE"))
    if not files:
        diagnostics.append(_diag("DB_ROOM_SOURCE_EMPTY"))
    daos: list[DaoId] = []
    methods: list[DaoMethodId] = []
    method_annotations: dict[tuple[str, str, str, str | None, tuple[str, ...]], list[DaoMethodAnnotation]] = {}
    sources: dict[str, str] = {}
    for canonical, path in files:
        try:
            source = path.read_text(encoding="utf-8")
            sources[canonical] = source
            found = find_dao_declarations(source, canonical)
            daos.extend(found)
            for dao in found:
                for record in find_dao_method_annotations(source, dao):
                    methods.append(record.method)
                    method = record.method
                    method_annotations.setdefault((method.dao.canonical_path, method.dao.fqcn, method.name, method.receiver, method.parameters), []).append(record)
        except (OSError, UnicodeError):
            diagnostics.append(_diag("DB_ROOM_SOURCE_UNREADABLE", canonical))
        except AccessorError as error:
            # Bare accessor codes (``INVALID_INPUT``, ...) are namespaced as
            # ``DB_ROOM_*``; fully-qualified accessor codes (``DB_DAO_*``,
            # e.g. ``DB_DAO_ANNOTATION_SCOPE_UNRESOLVED``) pass through as-is.
            code = error.code if error.code.startswith("DB_") else f"DB_ROOM_{error.code}"
            diagnostics.append(_diag(code, canonical))

    if any(item.startswith("DB_ROOM_SOURCE_UNREADABLE") for item in diagnostics):
        # A partial source view is not a safe inventory.  Keep only controlled
        # diagnostics and never expose a successful subset to callers.
        return RoomInventory(
            INVENTORY_SCHEMA, INVENTORY_VERSION, (), (), (), tuple(sorted(set(diagnostics)))
        )
    if not daos:
        diagnostics.append(_diag("DB_ROOM_SOURCE_EMPTY"))

    # Room database version is useful context, but its absence is not a reason
    # to invent one; inventory schema version remains the format version.
    versions: set[int] = set()
    for source in sources.values():
        for match in _DATABASE.finditer(source):
            version = _VERSION.search(match.group("body"))
            if version:
                versions.add(int(version.group("version")))
    if len(versions) > 1:
        diagnostics.append(_diag("DB_ROOM_DATABASE_VERSION_CONFLICT"))

    mutators: dict[str, RoomMutator] = {}
    method_map: dict[tuple[str, str, str, str | None, tuple[str, ...]], DaoMethodId] = {}
    for method in methods:
        key = (method.dao.canonical_path, method.dao.fqcn, method.name, method.receiver, method.parameters)
        if key in method_map:
            diagnostics.append(_diag("DB_ROOM_DUPLICATE_METHOD", method.dao.canonical_path))
        method_map[key] = method

    def direct_mutator(method: DaoMethodId) -> RoomMutator | None:
        source = sources.get(method.dao.canonical_path, "")
        records = method_annotations.get((method.dao.canonical_path, method.dao.fqcn, method.name, method.receiver, method.parameters), [])
        if len(records) > 1:
            location = f"{method.dao.canonical_path}:{_line(source, records[0].start)}"
            diagnostics.append(_diag("DB_ROOM_ANNOTATION_CONFLICT", location))
            return None
        record = records[0] if records else None
        if record is not None:
            kind = record.kind
            location = f"{method.dao.canonical_path}:{_line(source, record.start)}"
            if kind in {"Insert", "Update", "Delete", "Upsert"}:
                return RoomMutator(_method_signature(method), f"ROOM_{kind.upper()}", kind, None, None, location)
            if kind == "Query":
                classification = classify_sql(_query_sql(record.argument, source))
                if classification.is_mutation:
                    return RoomMutator(_method_signature(method), "ROOM_MUTATING_QUERY", kind, classification.operation, None, location)
                if classification.is_read:
                    return None
                diagnostics.append(_diag("DB_ROOM_QUERY_UNCLASSIFIABLE", location))
                return None
            key, reason = _resolve_raw_query_contract(method, source)
            if key is None:
                diagnostics.append(_diag(reason, location))
                return None
            # A valid @RawQuery never becomes a direct mutator here.  Direct
            # and inherited RawQuery mutators are derived together after
            # inheritance fixed-point resolution from the effective identity
            # set and the child-owned policy classification
            # (``_raw_query_mutators``), so a child can never inherit a
            # parent's classification and a child declaration can shadow the
            # inherited callable regardless of annotation/read/write.  The
            # RawQuery signature contract diagnostic above already failed
            # closed for invalid identities.
            return None
        diagnostics.append(_diag("DB_ROOM_UNSUPPORTED_METHOD", method.dao.canonical_path))
        return None

    # Callable identity is independent of source path.  Duplicate declarations
    # are ambiguous even when their annotations happen to agree; claiming one
    # would make the inventory dependent on traversal order.
    callable_counts: dict[tuple[str, str, str | None, tuple[str, ...]], int] = {}
    for method in methods:
        identity = (method.dao.fqcn, method.name, method.receiver, method.parameters)
        callable_counts[identity] = callable_counts.get(identity, 0) + 1
    ambiguous_callables = {identity for identity, count in callable_counts.items() if count > 1}
    duplicate_daos_pre = {dao.fqcn for dao in daos if sum(candidate.fqcn == dao.fqcn for candidate in daos) > 1}
    for identity in sorted(ambiguous_callables):
        diagnostics.append(_diag("DB_ROOM_MUTATOR_IDENTITY_AMBIGUOUS", identity[0]))

    direct_by_dao: dict[str, dict[str, RoomMutator]] = {dao.fqcn: {} for dao in daos}
    for method in methods:
        identity_key = (method.dao.fqcn, method.name, method.receiver, method.parameters)
        if identity_key in ambiguous_callables or method.dao.fqcn in duplicate_daos_pre:
            continue
        item = direct_mutator(method)
        if item:
            mutators[item.method] = item
            # The method's DaoId is the ownership source of truth.  Do not
            # recover ownership by looking for a path prefix: one Kotlin file
            # may contain several DAOs (including nested DAOs).
            identity = item.method.split("#", 1)[1]
            direct_by_dao.setdefault(method.dao.fqcn, {})[identity] = item

    # Resolve inheritance as a graph fixed point.  Source order is deliberately
    # irrelevant: a child may occur before its parent, and inherited methods
    # may themselves come from a transitive parent.
    dao_by_fqcn: dict[str, DaoId] = {}
    duplicate_daos: set[str] = set()
    for dao in daos:
        if dao.fqcn in dao_by_fqcn:
            duplicate_daos.add(dao.fqcn)
        else:
            dao_by_fqcn[dao.fqcn] = dao
    declarations_by_file: dict[str, dict[str, list[_TypeDeclaration]]] = {}
    declaration_index: dict[str, list[_TypeDeclaration]] = {}
    imports_by_file: dict[str, dict[str, str]] = {}
    for canonical, source in sources.items():
        try:
            declarations = _type_declarations(source)
            declarations_by_file[canonical] = {}
            for declaration in declarations:
                declarations_by_file[canonical].setdefault(declaration.fqcn, []).append(declaration)
                declaration_index.setdefault(declaration.fqcn, []).append(declaration)
            imports_by_file[canonical] = _imports(source)
        except (ParserError, _InheritanceParseError):
            # Individual DAO parsing below reports the controlled diagnostic.
            declarations_by_file[canonical] = {}
            imports_by_file[canonical] = {}
    # None means parsing failed; an empty tuple means a valid declaration with
    # no parents. Keep those states distinct so inheritance fails closed.
    parents_by_dao: dict[str, tuple[str, ...] | None] = {}
    for dao in daos:
        source = sources.get(dao.canonical_path, "")
        if dao.fqcn in duplicate_daos:
            diagnostics.append(_diag("DB_DAO_INHERITANCE_UNRESOLVED", f"{dao.canonical_path}:{dao.fqcn}"))
            parents_by_dao[dao.fqcn] = None
            continue
        try:
            parents = _dao_parents(dao, source)
        except (ParserError, _InheritanceParseError):
            declaration = re.search(
                rf"\b(?:interface|abstract\s+class)\s+{re.escape(dao.fqcn.rsplit('.', 1)[-1])}\b",
                source,
            )
            line = _line(source, declaration.start()) if declaration else 1
            location = f"{dao.canonical_path}:{line}:{dao.fqcn}"
            diagnostics.append(_diag("DB_DAO_INHERITANCE_UNRESOLVED", location))
            parents = None
        resolved_parents: list[str] = []
        unresolved_parent = False
        for parent in parents or ():
            resolved = _resolve_parent(
                parent, dao, source,
                declaration_index,
                imports_by_file.get(dao.canonical_path, {}),
            )
            if resolved is None or resolved not in dao_by_fqcn or resolved in duplicate_daos:
                diagnostics.append(_diag("DB_DAO_INHERITANCE_UNRESOLVED", f"{dao.canonical_path}:{dao.fqcn}"))
                unresolved_parent = True
            else:
                resolved_parents.append(resolved)
        # A partially resolved parent list is not safe: inheriting the valid
        # subset would silently turn an ambiguous declaration into a claim.
        parents_by_dao[dao.fqcn] = None if parents is None or unresolved_parent else tuple(resolved_parents)

    # Detect cycles over the complete DAO dependency graph.  Fixed-point
    # iteration alone can otherwise leave a cycle looking like a valid parent.
    visiting: set[str] = set()
    visited: set[str] = set()
    cyclic: set[str] = set()

    def visit(node: str, stack: tuple[str, ...] = ()) -> None:
        if node in visiting:
            if node in stack:
                cyclic.update(stack[stack.index(node):])
            return
        if node in visited:
            return
        visiting.add(node)
        parents = parents_by_dao.get(node)
        if parents is not None:
            for parent in parents:
                visit(parent, stack + (node,))
        visiting.remove(node)
        visited.add(node)

    for fqcn in tuple(parents_by_dao):
        visit(fqcn)
    for fqcn in cyclic:
        parents_by_dao[fqcn] = None
        dao = dao_by_fqcn.get(fqcn)
        if dao is not None:
            diagnostics.append(_diag("DB_DAO_INHERITANCE_UNRESOLVED", f"{dao.canonical_path}:{fqcn}"))

    # Compute the invalid ancestor closure before resolving any callable.  A
    # descendant of an unresolved/cyclic/duplicate DAO must not inherit the
    # subset that happened to be resolvable: doing so makes source order and
    # partial graph visibility affect ownership.
    invalid_inheritance: set[str] = {
        fqcn for fqcn, parents in parents_by_dao.items() if parents is None
    }
    closure_changed = True
    while closure_changed:
        closure_changed = False
        for fqcn, parents in parents_by_dao.items():
            if fqcn in invalid_inheritance or parents is None:
                continue
            if any(parent in invalid_inheritance for parent in parents):
                invalid_inheritance.add(fqcn)
                closure_changed = True
                dao = dao_by_fqcn.get(fqcn)
                if dao is not None:
                    diagnostics.append(_diag(
                        "DB_DAO_INHERITANCE_INVALID_ANCESTOR",
                        f"{dao.canonical_path}:{fqcn}",
                    ))

    # A method identity is the callable suffix after the owning DAO.  Keep
    # direct mutators separate so a conflict cannot overwrite a legal direct
    # declaration while inheritance is being evaluated.
    effective = {fqcn: dict(items) for fqcn, items in direct_by_dao.items()}
    inherited_items: dict[str, dict[str, RoomMutator]] = {fqcn: {} for fqcn in dao_by_fqcn}
    for _ in range(max(1, len(daos) * len(daos) + 1)):
        changed = False
        for dao in sorted(daos, key=lambda d: d.fqcn):
            if (dao.fqcn in duplicate_daos or dao.fqcn in invalid_inheritance or
                    parents_by_dao.get(dao.fqcn) is None):
                # Preserve the DAO declaration, but do not claim inherited
                # mutators when its parent list could not be resolved.
                continue
            candidates: dict[str, list[tuple[str, RoomMutator]]] = {}
            for parent_fqcn in parents_by_dao[dao.fqcn]:
                if parent_fqcn not in dao_by_fqcn or parent_fqcn in invalid_inheritance:
                    continue
                for identity, parent_item in effective[parent_fqcn].items():
                    candidates.setdefault(identity, []).append((parent_fqcn, parent_item))
            new_inherited: dict[str, RoomMutator] = {}
            for identity, options in candidates.items():
                # Multiple parents exposing the same callable are ambiguous,
                # even when their annotations/classifications agree.  Never
                # use traversal order as an implicit first-parent choice.
                if len(options) > 1:
                    diagnostics.append(_diag("DB_ROOM_INHERITED_METHOD_CONFLICT", dao.canonical_path))
                    continue
                parent_fqcn, item = options[0]
                # The identity is already the canonical callable suffix, so
                # it can be transferred through arbitrarily deep inheritance
                # without requiring a source declaration in every child.
                child_method = f"{dao.canonical_path}::{dao.fqcn}#{identity}"
                new_inherited[identity] = RoomMutator(
                    child_method, item.mutation_kind, item.annotation, item.query_kind,
                    parent_fqcn, item.source_location,
                )
            merged = dict(direct_by_dao[dao.fqcn])
            for identity, item in new_inherited.items():
                if identity not in merged:
                    merged[identity] = item
            if merged != effective[dao.fqcn]:
                effective[dao.fqcn] = merged
                changed = True
            inherited_items[dao.fqcn] = new_inherited
        if not changed:
            break

    mutators = {item.method: item for items in effective.values() for item in items.values()}

    # Global RawQuery policy equality contract over effective identities.  The
    # complete discovered production @RawQuery identity set must equal the
    # canonical policy key set in both directions, and it is derived after
    # inheritance fixed-point resolution so every child DAO exposing an
    # inherited @RawQuery contributes its own child-owned identity (with
    # ``inherited_from`` metadata) instead of only the declaring DAO's:
    #   discovered-but-unlisted -> DB_ROOM_RAW_QUERY_POLICY_REQUIRED
    #   policy-only/stale       -> DB_ROOM_RAW_QUERY_POLICY_STALE
    # Ambiguous inherited identities emit
    # DB_ROOM_RAW_QUERY_POLICY_INHERITED_AMBIGUOUS and are never claimed as an
    # exact policy match.  A malformed policy already failed closed with
    # DB_ROOM_RAW_QUERY_POLICY_INVALID and an empty source already failed
    # closed with DB_ROOM_SOURCE_EMPTY, so neither participates in the set
    # comparison; an unreadable source returned even earlier.
    #
    # RawQuery mutators are then derived from the same effective identities by
    # evaluating the child-owned policy classification: read yields no
    # mutator, write yields a ROOM_MUTATING_QUERY mutator, and a missing or
    # ambiguous identity yields no mutator (its REQUIRED / INHERITED_AMBIGUOUS
    # diagnostic already failed closed).
    if policy_valid and daos:
        discovered_raw = _discovered_raw_query_keys(
            methods, method_annotations, sources,
            daos, parents_by_dao, invalid_inheritance, duplicate_daos, dao_by_fqcn,
            diagnostics,
        )
        policy_keys = set(policy)
        for key in sorted(discovered_raw):
            if key not in policy_keys:
                diagnostics.append(_diag("DB_ROOM_RAW_QUERY_POLICY_REQUIRED", discovered_raw[key].location))
        for key in sorted(policy_keys):
            if key not in discovered_raw:
                diagnostics.append(_diag("DB_ROOM_RAW_QUERY_POLICY_STALE", key[0]))
        mutators.update(_raw_query_mutators(discovered_raw, policy, dao_by_fqcn))

    unique_diagnostics = tuple(sorted(set(diagnostics)))
    return RoomInventory(INVENTORY_SCHEMA, INVENTORY_VERSION, tuple(sorted(daos, key=lambda d: (d.fqcn, d.canonical_path))), tuple(sorted(methods, key=lambda m: _method_signature(m))), tuple(sorted(mutators.values(), key=lambda m: (m.method, m.source_location))), unique_diagnostics)


def write_inventory_atomic(path: Any, inventory: RoomInventory) -> None:
    """Write JSON through a sibling temporary file and atomic replacement."""
    fd: int | None = None
    temporary: str | None = None
    try:
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "schema": INVENTORY_SCHEMA,
            "schema_version": INVENTORY_VERSION,
            "daos": [asdict(item) for item in inventory.daos],
            "methods": [asdict(item) for item in inventory.methods],
            "mutators": [asdict(item) for item in inventory.mutators],
            "diagnostics": list(inventory.diagnostics),
        }
        fd, temporary = tempfile.mkstemp(
            prefix=f".{target.name}.", suffix=".tmp", dir=str(target.parent), text=True
        )
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            fd = None
            json.dump(payload, handle, sort_keys=True, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, target)
        temporary = None
        # A directory fsync makes the rename durable.  Windows and other
        # platforms without O_DIRECTORY do not provide a confirmable
        # directory durability barrier.  Never report success in that case.
        directory_flag = getattr(os, "O_DIRECTORY", None)
        if directory_flag is None:
            raise InventoryDurabilityUnconfirmedError()
        directory_fd: int | None = None
        try:
            directory_fd = os.open(str(target.parent), os.O_RDONLY | directory_flag)
            os.fsync(directory_fd)
        except Exception:
            # os.replace already succeeded.  Report the target's actual
            # state instead of turning a durability uncertainty into a
            # misleading pre-replacement write failure.
            raise InventoryDurabilityUnconfirmedError() from None
        finally:
            if directory_fd is not None:
                with suppress(Exception):
                    os.close(directory_fd)
    except InventoryDurabilityUnconfirmedError:
        raise
    except Exception:
        raise InventoryWriteError() from None
    finally:
        if fd is not None:
            with suppress(Exception):
                os.close(fd)
        if temporary is not None:
            with suppress(Exception):
                os.unlink(temporary)


__all__ = ["InventoryWriteError", "InventoryDurabilityUnconfirmedError", "RoomMutator", "RoomInventory", "DEFAULT_RAW_QUERY_POLICY", "build_room_inventory", "write_inventory_atomic"]
