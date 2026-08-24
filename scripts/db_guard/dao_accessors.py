"""Small, fail-closed Kotlin DAO declaration/accessor discovery helpers."""

from __future__ import annotations

import os
import re
from dataclasses import dataclass
from typing import Any

from ..db_policy_signature import SignatureError, _normalize_canonical_path
from ..kotlin_callable_parser import ParserError, mask_kotlin_source


ERROR_INVALID_INPUT = "INVALID_INPUT"
ERROR_BAD_PATH = "BAD_PATH"
ERROR_UNSUPPORTED_DECLARATION = "UNSUPPORTED_DECLARATION"
ERROR_AMBIGUOUS_DECLARATION = "AMBIGUOUS_DECLARATION"
ERROR_MISSING_DECLARATION = "MISSING_DECLARATION"
ERROR_UNSUPPORTED_METHOD = "UNSUPPORTED_METHOD"
ERROR_AMBIGUOUS_METHOD = "AMBIGUOUS_METHOD"
# Fully-qualified controlled code: a ``@Dao`` annotation-to-declaration span
# that is structurally legal but exceeds the documented safe maximum cannot
# be resolved within the bounded span contract, so the accessor fails closed
# instead of silently skipping the DAO.
ERROR_ANNOTATION_SCOPE_UNRESOLVED = "DB_DAO_ANNOTATION_SCOPE_UNRESOLVED"

_ERROR_CODES = frozenset(
    {
        ERROR_INVALID_INPUT,
        ERROR_BAD_PATH,
        ERROR_UNSUPPORTED_DECLARATION,
        ERROR_AMBIGUOUS_DECLARATION,
        ERROR_MISSING_DECLARATION,
        ERROR_UNSUPPORTED_METHOD,
        ERROR_AMBIGUOUS_METHOD,
        ERROR_ANNOTATION_SCOPE_UNRESOLVED,
    }
)

# The documented safe maximum for a legal annotation-to-declaration span:
# the number of characters between the end of a ``@Dao`` annotation and the
# start of the declaration it decorates.  Annotation discovery is bounded by
# the enclosing scope structure (see ``_annotation_span_lower_bound``), so a
# legal span within this limit is always associated.  A legal span larger
# than this maximum fails closed with ``DB_DAO_ANNOTATION_SCOPE_UNRESOLVED``;
# the DAO is never silently omitted.
MAX_ANNOTATION_TO_DECLARATION_SPAN = 16384


class AccessorError(Exception):
    """An accessor-discovery failure containing only a controlled code."""

    def __init__(self, code: str) -> None:
        self.code = code if code in _ERROR_CODES else ERROR_INVALID_INPUT
        super().__init__(self.code)


@dataclass(frozen=True)
class DaoId:
    fqcn: str
    canonical_path: str


@dataclass(frozen=True)
class DaoMethodId:
    dao: DaoId
    name: str
    receiver: str | None
    parameters: tuple[str, ...]


@dataclass(frozen=True)
class DaoMethodAnnotation:
    method: DaoMethodId
    kind: str
    start: int
    end: int
    argument: str | None
    #: Offset of the ``fun`` declaration this annotation decorates.  Records
    #: sharing one method identity AND one function start come from a single
    #: callable declaration (for example ``@Insert @Query(...) fun save``),
    #: which lets consumers distinguish an annotation conflict on one
    #: declaration from genuinely duplicated callable declarations.
    function_start: int = -1


__all__ = [
    "AccessorError",
    "DaoId",
    "DaoMethodId",
    "DaoMethodAnnotation",
    "canonical_dao_path",
    "find_dao_declarations",
    "find_dao_methods",
    "find_dao_method_annotations",
]

_IDENT = r"[A-Za-z_][A-Za-z0-9_]*"
_PACKAGE = re.compile(r"\bpackage\s+([A-Za-z_][A-Za-z0-9_.]*)\b")
_DAO_ANNOTATION = re.compile(r"@(?:[A-Za-z_][A-Za-z0-9_]*\.)*Dao\b")
_DECLARATION = re.compile(r"\b(?:(?:public|private|internal|protected|open|abstract|final|sealed)\s+)*(interface|abstract\s+class)\s+(%s)\b" % _IDENT)
_OWNER_DECLARATION = re.compile(r"\b(?:class|interface|object)\s+(%s)\b" % _IDENT)
_FUN = re.compile(r"\bfun\s+(?:(%s)\s*\.\s*)?(%s)\s*\(" % (_IDENT, _IDENT))
_ROOM_ANNOTATION = re.compile(
    r"@(?:[A-Za-z_][A-Za-z0-9_]*\.)*(Insert|Update|Delete|Upsert|Query|RawQuery)\b"
)
_ANY_ANNOTATION = re.compile(r"@(?:[A-Za-z_][A-Za-z0-9_]*\.)*[A-Za-z_][A-Za-z0-9_]*\b")
# Keywords which can start a sibling declaration at the top level of a scope.
# A bodyless declaration header ends at the first such keyword, so a sibling
# ``{`` is never treated as this declaration's body.
_HEADER_SIBLING = re.compile(r"\b(?:interface|class|object|fun|val|var|typealias|enum|annotation)\b")
# The declaration kinds a ``@Dao`` annotation can decorate.  ``@Dao`` on
# ``class``/``object`` is not a Room DAO and is deliberately not covered.
_DAO_HEADER_KEYWORD = re.compile(
    r"\b(?:(?:public|private|internal|protected|open|abstract|final|sealed)\s+)*(interface|abstract\s+class)\b"
)


def canonical_dao_path(path: Any) -> str:
    """Validate a DAO source path with db_policy_signature's path policy."""
    try:
        value = os.fspath(path)
    except TypeError:
        raise AccessorError(ERROR_BAD_PATH) from None
    try:
        return _normalize_canonical_path(value)
    except (SignatureError, TypeError):
        raise AccessorError(ERROR_BAD_PATH) from None


def _check_source(source: Any) -> str:
    if not isinstance(source, str):
        raise AccessorError(ERROR_INVALID_INPUT)
    return source


def _mask(source: str) -> str:
    try:
        return mask_kotlin_source(source)
    except ParserError:
        raise AccessorError(ERROR_UNSUPPORTED_DECLARATION) from None


def _matching_brace(source: str, opening: int) -> int:
    depth = 0
    for index in range(opening, len(source)):
        char = source[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth == 0:
                return index
            if depth < 0:
                break
    raise AccessorError(ERROR_UNSUPPORTED_DECLARATION)


def _brace_depths(source: str) -> list[int]:
    depths = [0] * (len(source) + 1)
    depth = 0
    for index, char in enumerate(source):
        depths[index] = depth
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
            if depth < 0:
                raise AccessorError(ERROR_UNSUPPORTED_METHOD)
    depths[len(source)] = depth
    if depth:
        raise AccessorError(ERROR_UNSUPPORTED_METHOD)
    return depths


def _header_body(source: str, start: int, limit: int) -> tuple[int | None, int]:
    """Find a declaration header's body opening ``{`` without crossing into a
    following sibling declaration.

    Returns ``(opening, header_end)``.  When ``opening`` is None the
    declaration is bodyless and ``header_end`` is the exact boundary where the
    header ends: the enclosing scope's ``}``, the start of the next
    declaration keyword, or ``limit``.  A sibling's ``{`` is never used as
    this declaration's body.  Unbalanced header delimiters fail closed.
    """
    depth = 0
    index = start
    while index < limit:
        char = source[index]
        if char in "([<":
            depth += 1
        elif char == ">" and (index == 0 or source[index - 1] != "-"):
            # The ``>`` of a ``->`` arrow is not a generic closer.
            if depth == 0:
                raise AccessorError(ERROR_UNSUPPORTED_DECLARATION)
            depth -= 1
        elif char in ")]":
            if depth == 0:
                raise AccessorError(ERROR_UNSUPPORTED_DECLARATION)
            depth -= 1
        elif depth == 0:
            if char == "{":
                return index, index
            if char == "}":
                return None, index
            if _HEADER_SIBLING.match(source, index):
                return None, index
        index += 1
    if depth:
        raise AccessorError(ERROR_UNSUPPORTED_DECLARATION)
    return None, limit


@dataclass(frozen=True)
class _Declaration:
    dao: DaoId
    start: int
    body_start: int | None
    body_end: int | None
    bodyless: bool = False


def _annotation_span_lower_bound(source: str, start: int) -> int:
    """The structural lower bound for annotations decorating the declaration
    at *start*: the first character after the ``{`` that opens the scope
    directly containing the declaration, or 0 for top-level declarations.

    The bound is structural (scope/header boundaries), never an arbitrary
    character count: an annotation before this boundary belongs to an outer
    scope and can never be adjacent to the declaration (the scope opener or a
    sibling body would sit between them).  Unbalanced input still yields a
    conservative bound (0), so the adjacency filter remains the correctness
    gate.
    """
    depth = 0
    index = start - 1
    while index >= 0:
        char = source[index]
        if char == "}":
            depth += 1
        elif char == "{":
            if depth == 0:
                return index + 1
            depth -= 1
        index -= 1
    return 0


def _declarations(source: str, path: str) -> list[_Declaration]:
    package_match = _PACKAGE.search(source)
    package = package_match.group(1) if package_match else ""
    candidates: list[tuple[int, int, str]] = []
    for match in _DECLARATION.finditer(source):
        lower = _annotation_span_lower_bound(source, match.start())
        adjacent: list[Any] = []
        for annotation in _DAO_ANNOTATION.finditer(source, lower, match.start()):
            if not _adjacent_to_declaration(source, annotation.end(), match.start()):
                continue
            adjacent.append(annotation)
            if match.start() - annotation.end() > MAX_ANNOTATION_TO_DECLARATION_SPAN:
                raise AccessorError(ERROR_ANNOTATION_SCOPE_UNRESOLVED)
        if not adjacent:
            continue
        kind, name = match.group(1), match.group(2)
        candidates.append((match.start(), match.end(), name))

    # A ``@Dao`` annotation decorating ``interface``/``abstract class``
    # without a valid name is a malformed bodyless declaration: fail closed
    # instead of silently skipping it.
    valid_starts = {candidate_start for candidate_start, _, _ in candidates}
    for annotation in _DAO_ANNOTATION.finditer(source):
        keyword = _DAO_HEADER_KEYWORD.search(source, annotation.end())
        if keyword is None:
            continue
        if not _adjacent_to_declaration(source, annotation.end(), keyword.start()):
            continue
        if keyword.start() - annotation.end() > MAX_ANNOTATION_TO_DECLARATION_SPAN:
            raise AccessorError(ERROR_ANNOTATION_SCOPE_UNRESOLVED)
        if keyword.start() not in valid_starts:
            raise AccessorError(ERROR_UNSUPPORTED_DECLARATION)

    containers: list[tuple[int, int, str]] = []
    for match in _OWNER_DECLARATION.finditer(source):
        opening, _header_end = _header_body(source, match.end(), len(source))
        if opening is not None:
            containers.append((match.start(), _matching_brace(source, opening), match.group(1)))

    result: list[_Declaration] = []
    for start, end, name in candidates:
        opening, header_end = _header_body(source, end, len(source))
        if opening is None:
            bodyless = True
            body_start: int | None = None
            body_end: int | None = None
            span_end = header_end
        else:
            bodyless = False
            closing = _matching_brace(source, opening)
            body_start = opening + 1
            body_end = closing
            span_end = closing
        parents = [
            (other_start, other_end, other_name)
            for other_start, other_end, other_name in containers
            if other_start < start and span_end <= other_end
        ]
        # The containment calculation is intentionally based on declaration
        # spans below; malformed nesting is rejected rather than guessed.
        owner_parts = [p[2] for p in sorted(parents, key=lambda item: item[0])]
        owner_parts.append(name)
        fqcn = ".".join(([package] if package else []) + owner_parts)
        result.append(_Declaration(DaoId(fqcn, path), start, body_start, body_end, bodyless))
    result.sort(key=lambda item: (item.dao.fqcn, item.dao.canonical_path, item.start))
    return result


def find_dao_declarations(masked_source: Any, canonical_path: Any) -> tuple[DaoId, ...]:
    source = _mask(_check_source(masked_source))
    path = canonical_dao_path(canonical_path)
    declarations = _declarations(source, path)
    return tuple(item.dao for item in declarations)


def _split_parameters(text: str) -> tuple[str, ...]:
    if not text.strip():
        return ()
    parts: list[str] = []
    start = 0
    depth = 0
    for index, char in enumerate(text):
        if char in "(<[{":
            depth += 1
        elif char in ")]}":
            depth -= 1
            if depth < 0:
                raise AccessorError(ERROR_UNSUPPORTED_METHOD)
        elif char == ">" and (index == 0 or text[index - 1] != "-"):
            # ``>`` closes a generic argument (``List<Expense>``).  The ``>``
            # of a ``->`` arrow is not a generic closer; comparisons in
            # default-value expressions are deliberately unsupported and fail
            # closed below.
            depth -= 1
            if depth < 0:
                raise AccessorError(ERROR_UNSUPPORTED_METHOD)
        elif char == "," and depth == 0:
            parts.append(text[start:index])
            start = index + 1
    if depth:
        raise AccessorError(ERROR_UNSUPPORTED_METHOD)
    parts.append(text[start:])
    types: list[str] = []
    for part in parts:
        colon = _top_level_colon(part)
        if colon < 0:
            raise AccessorError(ERROR_UNSUPPORTED_METHOD)
        value = part[colon + 1 :]
        equals = _top_level_equals(value)
        if equals >= 0:
            value = value[:equals]
        value = value.strip()
        if not value:
            raise AccessorError(ERROR_UNSUPPORTED_METHOD)
        types.append(value)
    return tuple(types)


def _top_level_colon(text: str) -> int:
    depth = 0
    for index, char in enumerate(text):
        if char in "(<[{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == ">" and (index == 0 or text[index - 1] != "-"):
            depth -= 1
        elif char == ":" and depth == 0:
            return index
    return -1


def _top_level_equals(text: str) -> int:
    depth = 0
    for index, char in enumerate(text):
        if char in "(<[{":
            depth += 1
        elif char in ")]}":
            depth -= 1
        elif char == ">" and (index == 0 or text[index - 1] != "-"):
            depth -= 1
        elif char == "=" and depth == 0:
            return index
    return -1


def _annotation_end(source: str, start: int) -> int:
    match = _ROOM_ANNOTATION.match(source, start)
    if match is None:
        raise AccessorError(ERROR_UNSUPPORTED_METHOD)
    cursor = match.end()
    while cursor < len(source) and source[cursor].isspace():
        cursor += 1
    if cursor >= len(source) or source[cursor] != "(":
        return _DAO_OR_ROOM_END(source, start)
    opening = cursor
    close = _balanced_delimiter(source, opening, "(", ")")
    return close + 1


def _any_annotation_end(source: str, start: int) -> int:
    """Return the end of any Kotlin annotation, not just a Room one."""
    match = _ANY_ANNOTATION.match(source, start)
    if match is None:
        raise AccessorError(ERROR_UNSUPPORTED_METHOD)
    cursor = match.end()
    while cursor < len(source) and source[cursor].isspace():
        cursor += 1
    if cursor < len(source) and source[cursor] == "(":
        return _balanced_delimiter(source, cursor, "(", ")") + 1
    return match.end()


_MODIFIER = re.compile(r"(?:public|private|internal|protected|open|abstract|final|sealed|override|suspend|inline|infix|operator|tailrec|expect|actual)\b")


def _annotation_or_modifier_end(source: str, cursor: int, limit: int) -> int | None:
    while cursor < limit:
        if source[cursor].isspace() or source[cursor] == ";":
            cursor += 1
            continue
        if source[cursor] == "@":
            end = _any_annotation_end(source, cursor)
            if end > limit:
                return None
            cursor = end
            continue
        modifier = _MODIFIER.match(source, cursor)
        if modifier:
            cursor = modifier.end()
            continue
        return None
    return cursor


def _adjacent_to_declaration(source: str, annotation_end: int, declaration_start: int) -> bool:
    return _annotation_or_modifier_end(source, annotation_end, declaration_start) == declaration_start


def _annotation_argument(source: str, start: int, end: int) -> str | None:
    cursor = _ROOM_ANNOTATION.match(source, start).end()  # type: ignore[union-attr]
    while cursor < end and source[cursor].isspace():
        cursor += 1
    if cursor >= end or source[cursor] != "(":
        return None
    return source[cursor + 1 : end - 1]


def _DAO_OR_ROOM_END(source: str, start: int) -> int:
    match = _ROOM_ANNOTATION.match(source, start)
    return match.end() if match else start


def _balanced_delimiter(source: str, opening: int, left: str, right: str) -> int:
    depth = 0
    for index in range(opening, len(source)):
        if source[index] == left:
            depth += 1
        elif source[index] == right:
            depth -= 1
            if depth == 0:
                return index
    raise AccessorError(ERROR_UNSUPPORTED_METHOD)


def _method_annotations(source: str, dao_decl: DaoId, original: str | None = None) -> tuple[DaoMethodAnnotation, ...]:
    if not isinstance(dao_decl, DaoId):
        raise AccessorError(ERROR_INVALID_INPUT)
    path = canonical_dao_path(dao_decl.canonical_path)
    declarations = [d for d in _declarations(source, path) if d.dao == dao_decl]
    if not declarations:
        raise AccessorError(ERROR_MISSING_DECLARATION)
    if len(declarations) != 1:
        raise AccessorError(ERROR_AMBIGUOUS_DECLARATION)
    declaration = declarations[0]
    if declaration.bodyless:
        # A bodyless DAO has no direct methods.  Never scan past the header
        # into a following sibling declaration.
        return ()
    depths = _brace_depths(source)
    records: list[DaoMethodAnnotation] = []
    for annotation in _ROOM_ANNOTATION.finditer(source, declaration.body_start, declaration.body_end):
        if depths[annotation.start()] != depths[declaration.body_start]:
            continue
        end = _annotation_end(source, annotation.start())
        function = _FUN.search(source, end, declaration.body_end)
        if (function is None or depths[function.start()] != depths[declaration.body_start]
                or not _adjacent_to_declaration(source, end, function.start())):
            raise AccessorError(ERROR_UNSUPPORTED_METHOD)
        opening = source.find("(", function.start(), declaration.body_end)
        closing = _balanced_delimiter(source, opening, "(", ")")
        if closing >= declaration.body_end:
            raise AccessorError(ERROR_UNSUPPORTED_METHOD)
        params = _split_parameters(source[opening + 1 : closing])
        method = DaoMethodId(
                dao_decl,
                function.group(2),
                function.group(1).strip() if function.group(1) else None,
                params,
            )
        records.append(DaoMethodAnnotation(method, annotation.group(1), annotation.start(), end,
                                            _annotation_argument(original or source, annotation.start(), end),
                                            function.start()))
    records.sort(key=lambda item: (item.method.name, item.method.receiver or "", item.method.parameters))
    return tuple(records)


def find_dao_method_annotations(masked_source: Any, dao_decl: DaoId) -> tuple[DaoMethodAnnotation, ...]:
    original = _check_source(masked_source)
    return _method_annotations(_mask(original), dao_decl, original)


def find_dao_methods(masked_source: Any, dao_decl: DaoId) -> tuple[DaoMethodId, ...]:
    return tuple(record.method for record in find_dao_method_annotations(masked_source, dao_decl))
