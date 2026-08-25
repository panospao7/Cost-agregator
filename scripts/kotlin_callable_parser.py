"""Small, fail-closed Kotlin callable scanner used by the DB discovery guard."""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

try:  # package mode: imported as ``scripts.kotlin_callable_parser``
    from .db_policy_signature import FunctionSignature, SignatureError, normalize_type_text
except ImportError:  # pragma: no cover - flat mode: standalone tools put ``scripts`` on sys.path
    from db_policy_signature import FunctionSignature, SignatureError, normalize_type_text

__all__ = [
    "ParserError", "CallableDeclaration", "OwnerDeclaration", "mask_kotlin_source",
    "canonical_source_path", "parse_kotlin_file", "find_owner_declarations",
    "find_callable_declarations", "resolve_callable",
]

MAX_SOURCE = 2_000_000
MAX_DEPTH = 128
MAX_TOKENS = 250_000
#: Generic repo-Kotlin-path bounds for ``canonical_source_path``: at most
#: 16 path components and 256 characters.  Purely syntactic -- no
#: source-root/topology knowledge lives in this module.
MAX_SOURCE_PATH_COMPONENTS = 16
MAX_SOURCE_PATH_LENGTH = 256
_MESSAGE = "kotlin callable parser error"

# Signature failures retain their controlled codes at this boundary.  The
# code is safe to expose; input and exception text are never exposed.
_SIGNATURE_ERROR_CODES = frozenset({
    "NOT_TEXT", "BLANK_TYPE", "CONTROL_TYPE", "CONTROL_PATH",
    "UNSUPPORTED_TOKEN", "BAD_TYPE", "UNBALANCED_ANGLE", "UNBALANCED_PAREN",
    "UNBALANCED_ARRAY", "BAD_COMMAS", "EMPTY_GENERIC", "DUPLICATE_NULLABLE",
    "MISSING_ARROW", "EMPTY_FUNCTION", "VARARG_CONTEXT", "VARARG_PREFIX",
    "TYPE_TOO_LONG", "TYPE_TOO_MANY_TOKENS", "NESTING_TOO_DEEP",
    "PATH_TOO_LONG", "PATH_TOO_DEEP", "PATH_SEGMENT_TOO_LONG", "BAD_NAME",
    "BAD_PATH", "BAD_PARAMS", "NOT_OBJECT", "BAD_KEYS", "BAD_OWNER",
    "BAD_RECEIVER", "INVALID_SIGNATURE_ERROR",
})

# Controlled path-syntax codes for ``canonical_source_path``: exactly one
# code per rejection class.  The set is closed so unknown reason codes
# cannot leak into diagnostics; the codes are safe to expose, input text
# is not.
_PATH_SYNTAX_ERROR_CODES = frozenset({
    "PATH_NOT_TEXT", "PATH_EMPTY", "PATH_BACKSLASH", "PATH_ABSOLUTE",
    "PATH_UNC", "PATH_DRIVE", "PATH_TRAILING_SLASH", "PATH_DOUBLE_SLASH",
    "PATH_TRAVERSAL", "PATH_DOT_SEGMENT", "PATH_TOO_LONG", "PATH_TOO_DEEP",
    "PATH_NOT_KOTLIN",
})


class ParserError(Exception):
    """An error with a deliberately fixed, non-data-bearing diagnostic."""
    def __init__(self, code: str = "PARSER_ERROR") -> None:
        allowed_codes = {
            "PARSER_ERROR", "SIGNATURE_UNSUPPORTED", "TYPE_UNRESOLVED",
            "PATH_TOO_LONG", "PATH_TOO_DEEP", "PATH_SEGMENT_TOO_LONG",
        } | _SIGNATURE_ERROR_CODES | _PATH_SYNTAX_ERROR_CODES
        self.code = code if code in allowed_codes else "PARSER_ERROR"
        self.message = _MESSAGE
        super().__init__(self.message)


def _fail(code: str = "PARSER_ERROR") -> None:
    raise ParserError(code)


def _fail_signature(error: SignatureError) -> None:
    _fail(error.code if error.code in _SIGNATURE_ERROR_CODES else "PARSER_ERROR")


def mask_kotlin_source(text: str) -> str:
    """Blank comments, strings, and character literals without changing offsets."""
    if not isinstance(text, str) or len(text) > MAX_SOURCE:
        _fail()
    out = list(text)
    i, n, mode, depth = 0, len(text), None, 0
    while i < n:
        c = text[i]
        if mode == "line":
            if c not in "\r\n": out[i] = " "
            if c in "\r\n": mode = None
        elif mode == "block":
            if text.startswith("/*", i): depth += 1; out[i] = out[i + 1] = " "; i += 1
            elif text.startswith("*/", i):
                depth -= 1; out[i] = out[i + 1] = " "; i += 1
                if depth == 0: mode = None
            elif c not in "\r\n": out[i] = " "
        elif mode in ("string", "triple", "char"):
            end = ((mode == "triple" and text.startswith('"""', i)) or
                   (mode == "string" and c == '"') or
                   (mode == "char" and c == "'"))
            if end:
                width = 3 if mode == "triple" else 1
                for j in range(width): out[i + j] = " "
                i += width - 1; mode = None
            else:
                if c not in "\r\n": out[i] = " "
                if mode != "triple" and c == "\\" and i + 1 < n:
                    if text[i + 1] not in "\r\n": out[i + 1] = " "
                    i += 1
        else:
            if text.startswith("//", i): mode = "line"; out[i] = out[i + 1] = " "; i += 1
            elif text.startswith("/*", i): mode = "block"; depth = 1; out[i] = out[i + 1] = " "; i += 1
            elif text.startswith('"""', i): mode = "triple"; out[i:i + 3] = [" "] * 3; i += 2
            elif c == '"': mode = "string"; out[i] = " "
            elif c == "'": mode = "char"; out[i] = " "
        i += 1
    if mode == "block": _fail("MALFORMED_SOURCE")
    if mode in ("string", "triple", "char"): _fail("MALFORMED_SOURCE")
    return "".join(out)


def canonical_source_path(path: str | Path) -> str:
    """Return a syntactically valid repository-relative POSIX ``.kt`` path.

    GENERIC repo-Kotlin-path syntax validation only: repository-relative
    POSIX form, ``.kt`` extension, bounded length and depth.  There is
    deliberately no source-root/topology knowledge left in this module --
    any module tree (``app/src/main/java``, ``feature/src/main/kotlin``,
    ``lib/core/src/main/java``, ...) is syntactically valid, and so is any
    source set.  Root MEMBERSHIP is a separate concern, validated later by
    root-aware stages via ``source_roots.is_declared_production_path``.

    Exactly one controlled ``ParserError`` code per rejection class:

      * non-string input          -> PATH_NOT_TEXT
      * blank                     -> PATH_EMPTY
      * backslash separator       -> PATH_BACKSLASH
      * leading ``/``             -> PATH_ABSOLUTE
      * leading ``//`` (UNC)      -> PATH_UNC
      * drive prefix (``C:``)     -> PATH_DRIVE
      * trailing ``/``            -> PATH_TRAILING_SLASH
      * duplicate slash           -> PATH_DOUBLE_SLASH
      * ``..`` segment            -> PATH_TRAVERSAL
      * ``.`` segment             -> PATH_DOT_SEGMENT
      * more than MAX_SOURCE_PATH_LENGTH characters     -> PATH_TOO_LONG
      * more than MAX_SOURCE_PATH_COMPONENTS components -> PATH_TOO_DEEP
      * missing ``.kt`` suffix    -> PATH_NOT_KOTLIN

    The input is never normalized before validation: normalization would
    turn an invalid path into a valid-looking one and hide traversal or
    foreign roots.
    """
    try:
        if isinstance(path, Path):
            if path.drive:
                # pathlib spells UNC drives as ``//server/share``.
                _fail("PATH_UNC" if path.drive.startswith(("//", "\\")) else "PATH_DRIVE")
            if path.root or path.is_absolute():
                _fail("PATH_ABSOLUTE")
            if any("\\" in part for part in path.parts):
                _fail("PATH_BACKSLASH")
            raw = "/".join(path.parts)
        elif isinstance(path, str):
            raw = path
        else:
            _fail("PATH_NOT_TEXT")
        # Do not normalize first: normalization would turn an invalid path into
        # a valid-looking one (and would hide traversal or foreign roots).
        if not raw:
            _fail("PATH_EMPTY")
        if "\\" in raw:
            _fail("PATH_BACKSLASH")
        if raw.startswith("//"):
            _fail("PATH_UNC")
        if raw.startswith("/"):
            _fail("PATH_ABSOLUTE")
        if re.match(r"^[A-Za-z]:", raw):
            _fail("PATH_DRIVE")
        if raw.endswith("/"):
            _fail("PATH_TRAILING_SLASH")
        parts = raw.split("/")
        if any(part == "" for part in parts):
            _fail("PATH_DOUBLE_SLASH")
        if any(part == ".." for part in parts):
            _fail("PATH_TRAVERSAL")
        if any(part == "." for part in parts):
            _fail("PATH_DOT_SEGMENT")
        if len(raw) > MAX_SOURCE_PATH_LENGTH:
            _fail("PATH_TOO_LONG")
        if len(parts) > MAX_SOURCE_PATH_COMPONENTS:
            _fail("PATH_TOO_DEEP")
        if not raw.endswith(".kt"):
            _fail("PATH_NOT_KOTLIN")
        return raw
    except ParserError:
        raise
    except Exception:
        _fail()


@dataclass(frozen=True)
class OwnerDeclaration:
    owner: str
    name: str
    start_offset: int
    end_offset: int
    body_start: int
    body_end: int
    status: str = "RESOLVED_EXACTLY"


@dataclass(frozen=True)
class CallableDeclaration:
    signature: FunctionSignature
    owner: str
    start_offset: int
    end_offset: int
    body: str | None
    status: str = "RESOLVED_EXACTLY"

    @property
    def offsets(self) -> tuple[int, int]:
        return self.start_offset, self.end_offset


_ID = r"[A-Za-z_][A-Za-z0-9_]*"
_OWNER = re.compile(r"\b(class|object)\s+(%s)" % _ID)
_FUN = re.compile(r"\bfun\b")
_MODIFIERS = re.compile(r"(?:public|private|protected|internal|expect|actual|inline|infix|operator|tailrec|override|final|open|abstract|external|suspend|crossinline|noinline|reified|const|lateinit|\s)+")

# Names which are concrete without a declaration in the source file.  This is
# deliberately a small, closed list: accepting every capitalised identifier
# made the old scanner silently authorize misspelled/project-local types.
_BUILTINS = frozenset({
    "Any", "Nothing", "Unit", "String", "Char", "Boolean", "Byte", "Short",
    "Int", "Long", "Float", "Double", "Number", "Array", "ByteArray",
    "ShortArray", "IntArray", "LongArray", "FloatArray", "DoubleArray",
    "BooleanArray", "CharArray", "List", "Set", "Map", "Collection",
    "Iterable", "Iterator", "Sequence", "Comparable", "Enum", "Pair", "Triple",
})


@dataclass(frozen=True)
class _TypeEnvironment:
    package: str
    types: frozenset[str]
    imports: dict[str, str | None]
    aliases: dict[str, str | None]
    type_counts: dict[str, int]
    owner_scope: str = ""


def _type_environment(masked: str, owner_scope: str = "") -> _TypeEnvironment:
    package_match = re.search(r"\bpackage\s+([A-Za-z_][A-Za-z0-9_.]*)", masked)
    package = package_match.group(1) if package_match else ""
    imports: dict[str, str | None] = {}
    for match in re.finditer(r"\bimport\s+([A-Za-z_][A-Za-z0-9_.]*)(?:\s+as\s+(%s))?" % _ID, masked):
        fqcn, alias = match.group(1), match.group(2)
        key = alias or fqcn.rsplit(".", 1)[-1]
        if key in imports and imports[key] != fqcn:
            imports[key] = None
        else:
            imports[key] = fqcn
    aliases: dict[str, str | None] = {}
    for match in re.finditer(r"\btypealias\s+(%s)\s*=\s*([^\n;]+)" % _ID, masked):
        key, target = match.group(1), match.group(2).strip()
        if key in aliases and aliases[key] != target:
            aliases[key] = None
        else:
            aliases[key] = target
    types = set()
    type_counts: dict[str, int] = {}
    owners = find_owner_declarations(masked)
    for match in re.finditer(r"\b(?:class|object|interface|enum\s+class|annotation\s+class)\s+(%s)" % _ID, masked):
        # A declaration nested in an owner is not a package-level type.  The
        # old package-prefixed pass added ``package.Inner`` for
        # ``package.Outer.Inner`` as well, making an otherwise exact nested
        # lookup appear ambiguous.
        parent = max(
            (owner for owner in owners
             if owner.body_start <= match.start() < owner.body_end),
            key=lambda owner: owner.body_start,
            default=None,
        )
        fqcn = ((parent.owner if parent else package) + "." if (parent or package) else "") + match.group(1)
        types.add(fqcn)
        type_counts[fqcn] = type_counts.get(fqcn, 0) + 1
    # Nested declarations are also concrete symbols.  Owner discovery supplies
    # the authoritative qualified spelling; the filename is never involved.
    for owner in find_owner_declarations(masked):
        types.add(owner.owner)
    return _TypeEnvironment(package, frozenset(types), imports, aliases, type_counts, owner_scope)


def _resolve_type(typ: str, env: _TypeEnvironment, *, allow_vararg: bool = False) -> str:
    """Normalize and resolve every named component of a type expression."""
    try:
        normalized = normalize_type_text(typ, allow_vararg=allow_vararg)
    except SignatureError as error:
        _fail_signature(error)
    vararg = normalized.startswith("vararg ")
    body = normalized[7:] if vararg else normalized

    def resolve_atom(name: str, seen: set[str]) -> str:
        if "." in name:
            if name in env.types:
                if env.type_counts.get(name, 0) != 1:
                    _fail("TYPE_UNRESOLVED")
                return name
            if name in _BUILTINS:
                return name
            # Imported symbols may be referenced by their full spelling.
            if name in env.imports.values():
                return name
            _fail("TYPE_UNRESOLVED")
        # Resolution is deliberately ordered: exact qualified spelling,
        # lexical/nested owner scope, same package, imports, then builtins.
        owner_scope = env.owner_scope
        nested_candidates = []
        if owner_scope:
            scope = owner_scope
            while "." in scope:
                candidate = scope + "." + name
                if candidate in env.types:
                    nested_candidates = [candidate]
                    break
                scope = scope.rsplit(".", 1)[0]
        if nested_candidates:
            candidates = nested_candidates
        elif env.package and env.package + "." + name in env.types:
            candidates = [env.package + "." + name]
        else:
            # An unimported type from another package is not in scope.  Do
            # not turn the closed-world inventory into a nondeterministic
            # simple-name search.
            candidates = []
        if candidates:
            if len(candidates) != 1 or any(env.type_counts.get(candidate, 0) != 1 for candidate in candidates):
                _fail("TYPE_UNRESOLVED")
            return candidates[0]
        target = env.imports.get(name)
        if name in env.imports and target is None:
            _fail("TYPE_UNRESOLVED")
        if target:
            return target
        target = env.aliases.get(name)
        if name in env.aliases and target is None:
            _fail("TYPE_UNRESOLVED")
        if target is not None:
            if name in seen:
                _fail("TYPE_UNRESOLVED")
            return resolve_expr(target, seen | {name})
        if name in _BUILTINS:
            return name
        _fail("TYPE_UNRESOLVED")

    def resolve_expr(expr: str, seen: set[str]) -> str:
        # Function types are split only at the top-level arrow.
        depth = 0
        arrow = -1
        for i, char in enumerate(expr):
            if char in "(<[": depth += 1
            elif char in ")>]": depth -= 1
            elif char == "-" and depth == 0 and expr[i:i + 2] == "->":
                arrow = i
                break
        if arrow >= 0:
            left, right = expr[:arrow].strip(), expr[arrow + 2:].strip()
            if not (left.startswith("(") and left.endswith(")")):
                _fail("TYPE_UNRESOLVED")
            args_text = left[1:-1].strip()
            args = [] if not args_text else _split_top(args_text)
            return "(" + ",".join(resolve_expr(x.strip(), seen) for x in args) + ")->" + resolve_expr(right, seen)
        suffix = ""
        while expr.endswith("?") or expr.endswith("[]"):
            if expr.endswith("?"):
                suffix = "?" + suffix; expr = expr[:-1].rstrip()
            else:
                suffix = "[]" + suffix; expr = expr[:-2].rstrip()
        if expr.startswith("(") and expr.endswith(")"):
            return "(" + resolve_expr(expr[1:-1].strip(), seen) + ")" + suffix
        lt = expr.find("<")
        if lt < 0:
            return resolve_atom(expr, seen) + suffix
        if not expr.endswith(">"):
            _fail("TYPE_UNRESOLVED")
        base = resolve_atom(expr[:lt].strip(), seen)
        args = _split_top(expr[lt + 1:-1])
        return base + "<" + ",".join(resolve_expr(x.strip(), seen) for x in args) + ">" + suffix

    resolved = resolve_expr(body, set())
    return ("vararg " if vararg else "") + resolved


def _pairs(text: str, a: int, b: str, limit: int = MAX_DEPTH) -> int:
    close = {"(": ")", "{": "}", "[": "]", "<": ">"}
    stack: list[str] = []
    i = a
    while i < len(text):
        if text.startswith("->", i):
            # A Kotlin ``->`` arrow is not a closing angle bracket: skip its
            # ``>`` without touching the delimiter stack (same rule as
            # ``declaration_scanner._header_opening``), so function-type
            # parameter spans such as ``(Int) -> String`` stay parseable
            # instead of failing closed as MALFORMED_SOURCE.
            i += 2
            continue
        c = text[i]
        if c in close:
            stack.append(close[c])
            if len(stack) > limit: _fail("NESTING_TOO_DEEP")
        elif c in ")}]>":
            if not stack or stack.pop() != c: _fail("MALFORMED_SOURCE")
            if not stack: return i
        i += 1
    _fail("MALFORMED_SOURCE")


def _body_end(text: str, start: int, limit: int = MAX_DEPTH) -> int:
    """Match a Kotlin block without treating comparison operators as angles."""
    stack: list[str] = []
    close = {"{": "}", "(": ")", "[": "]"}
    for i in range(start, len(text)):
        c = text[i]
        if c in close:
            stack.append(close[c])
            if len(stack) > limit: _fail("NESTING_TOO_DEEP")
        elif c in ")]}" :
            if not stack or stack.pop() != c: _fail("MALFORMED_SOURCE")
            if not stack: return i
    _fail("MALFORMED_SOURCE")


def _owner_body(text: str, start: int, scope_end: int, limit: int = MAX_DEPTH) -> tuple[int | None, int]:
    """Find only a declaration header's body delimiter.

    In particular, never use a later brace belonging to a sibling declaration
    as the body of a bodyless class/object.  A ``fun`` keyword at the top
    level of the enclosing scope is such a sibling too: a bodyless owner must
    stop there instead of adopting the function's body brace (which would
    hide the function from its real owner's callable scan).
    """
    stack: list[str] = []
    close = {"(": ")", "[": "]", "<": ">"}
    i = start
    while i < scope_end:
        c = text[i]
        if c in close:
            stack.append(close[c])
            if len(stack) > limit: _fail("NESTING_TOO_DEEP")
        elif c in ")]>":
            if not stack or stack.pop() != c: _fail("MALFORMED_SOURCE")
        elif not stack:
            if c == "{": return i, i
            if c == "}": return None, i
            if re.match(r"(?:fun|class|object)\b", text[i:]): return None, i
        i += 1
    if stack: _fail("MALFORMED_SOURCE")
    return None, scope_end


def _split_top(text: str, sep: str = ",") -> list[str]:
    result, start, stack = [], 0, []
    i = 0
    while i < len(text):
        if text.startswith("->", i):
            # A Kotlin ``->`` arrow is not a closing angle bracket: skip its
            # ``>`` without touching the delimiter stack (same rule as
            # ``_pairs``/``_header_body_start``), so function-type parameter
            # spans such as ``(Int) -> String`` and generics containing them
            # split at real top-level commas instead of failing closed as
            # MALFORMED_SOURCE when the arrow's ``>`` reaches this scanner.
            i += 2
            continue
        c = text[i]
        if c in "(<[{": stack.append(c)
        elif c in ")>]}" :
            expected = {"(": ")", "<": ">", "[": "]", "{": "}"}
            if not stack or expected[stack[-1]] != c: _fail("MALFORMED_SOURCE")
            stack.pop()
        elif c == sep and not stack:
            result.append(text[start:i]); start = i + 1
        i += 1
    if stack: _fail("MALFORMED_SOURCE")
    result.append(text[start:])
    return result


def _header_body_start(text: str, start: int, scope_end: int, limit: int = MAX_DEPTH) -> tuple[int | None, int]:
    """Find a function body after a header, allowing multiline return types."""
    stack: list[str] = []
    close = {"(": ")", "[": "]", "<": ">"}
    i = start
    while i < scope_end:
        if text.startswith("->", i):
            # A Kotlin ``->`` arrow (e.g. a function-typed return type such as
            # ``(Int) -> String``) is not a closing angle bracket: skip its
            # ``>`` without touching the delimiter stack.
            i += 2
            continue
        c = text[i]
        if c in close:
            stack.append(close[c])
            if len(stack) > limit: _fail("NESTING_TOO_DEEP")
        elif c in ")]>":
            if not stack or stack.pop() != c:
                _fail("MALFORMED_SOURCE")
        elif not stack:
            if c in "={":
                return i, i
            if re.match(r"(?:fun|class|object|interface|enum|annotation)\b", text[i:]):
                return None, i
        i += 1
    if stack:
        _fail("MALFORMED_SOURCE")
    return None, scope_end


def find_owner_declarations(text: str) -> tuple[OwnerDeclaration, ...]:
    masked = mask_kotlin_source(text)
    package_match = re.search(r"\bpackage\s+([A-Za-z_][A-Za-z0-9_.]*)", masked)
    package = package_match.group(1) if package_match else ""
    found: list[OwnerDeclaration] = []
    for m in _OWNER.finditer(masked):
        brace, header_end = _owner_body(masked, m.end(), len(masked))
        parent = next((x.owner for x in reversed(found) if x.body_start < m.start() < x.body_end), "")
        owner = (parent + "." if parent else (package + "." if package else "")) + m.group(2)
        if brace is None:
            # Unsupported, exact declaration only: this scope is empty, so a
            # following sibling can never be swallowed by it.
            found.append(OwnerDeclaration(owner, m.group(2), m.start(), header_end,
                                          header_end, header_end, "SIGNATURE_UNSUPPORTED"))
        else:
            end = _body_end(masked, brace)
            found.append(OwnerDeclaration(owner, m.group(2), m.start(), end + 1, brace + 1, end))
    return tuple(found)


def _callable_body_ranges(text: str, scope_start: int, scope_end: int) -> tuple[tuple[int, int, int], ...]:
    """Return ``(declaration_start, body_start, body_end)`` for braced funs.

    This is intentionally a structural pass over masked source.  A ``fun``
    found while walking an owner's body may be a local declaration rather than
    an owner member; its enclosing method range is needed before signature
    parsing can safely consider it.
    """
    ranges: list[tuple[int, int, int]] = []
    for fm in _FUN.finditer(text, scope_start, scope_end):
        p = text.find("(", fm.end(), scope_end)
        if p < 0:
            _fail("MALFORMED_SOURCE")
        pend = _pairs(text, p, ")")
        q = pend + 1
        while q < scope_end and text[q].isspace():
            q += 1
        if q < scope_end and text[q] == ":":
            q += 1
        body_start, boundary = _header_body_start(text, q, scope_end)
        q = body_start if body_start is not None else boundary
        if q < scope_end and text[q] == "{":
            ranges.append((fm.start(), q, _body_end(text, q) + 1))
    return tuple(ranges)


def _type_before(text: str, pos: int) -> tuple[str | None, int]:
    s = text[:pos].rstrip()
    if not s: return None, pos
    # Receiver ends immediately before the function name and may contain a dot.
    m = re.search(r"(%s(?:\s*\.\s*%s)*(?:\s*<[^<>]*>)?(?:\s*\?)?(?:\s*\[\])*)\s*\.\s*$" % (_ID, _ID), s)
    if not m: return None, pos
    return m.group(1).replace(" ", ""), m.start()


def _raw_parameter_type(param: str) -> str:
    """Extract one parameter's raw type text exactly as written (no resolution)."""
    if ":" not in param: _fail("SIGNATURE_UNSUPPORTED")
    declaration, typ = param.split(":", 1)
    vararg_prefix = bool(re.match(r"\s*vararg\b", declaration))
    typ = typ.split("=", 1)[0].strip()
    if vararg_prefix:
        typ = "vararg " + typ
    return typ


def find_callable_declarations(
    text: str,
    owner: OwnerDeclaration | str,
    *,
    tolerate_unresolved_types: bool = False,
) -> tuple[CallableDeclaration, ...]:
    """Discover the member ``fun`` declarations of one owner, fail-closed.

    With the default ``tolerate_unresolved_types=False``, a parameter or
    receiver type the closed-world resolver cannot resolve aborts the whole
    discovery with ``ParserError("TYPE_UNRESOLVED")`` -- the exact behavior
    the scanner and evidence verifiers depend on.

    With ``tolerate_unresolved_types=True`` (PR-GR-05 Slice 3 narrow
    repair) ONLY that type-resolution family becomes non-fatal, and only
    per declaration: the affected declaration is retained with status
    ``"TYPE_UNRESOLVED"`` and its signature kept as grammar-normalized
    simple names exactly as written (never resolved, never fabricated), so
    discovery continues with the remaining declarations.  Retained
    declarations can never act as exactly-resolved candidates downstream
    (``resolve_callable`` reports ``SIGNATURE_UNSUPPORTED`` for them).
    Every other failure family (masking, structure, signature grammar)
    stays fatal in both modes.
    """
    masked = mask_kotlin_source(text)
    owner_name = owner.owner if isinstance(owner, OwnerDeclaration) else owner
    environment = _type_environment(masked, owner_name)
    scope_start = owner.body_start if isinstance(owner, OwnerDeclaration) else 0
    scope_end = owner.body_end if isinstance(owner, OwnerDeclaration) else len(masked)
    owners = find_owner_declarations(text)
    body_ranges = _callable_body_ranges(masked, scope_start, scope_end)
    result: list[CallableDeclaration] = []
    for fm in _FUN.finditer(masked, scope_start, scope_end):
        if fm.start() >= scope_end: break
        # Ignore functions belonging to a nested owner when scanning this owner.
        nested = [x for x in owners if scope_start <= x.start_offset < scope_end and x.start_offset < fm.start() < x.end_offset]
        if nested and nested[-1].owner != owner_name: continue
        # A function declaration inside a method/block is local Kotlin code,
        # not a callable member of this owner.  Nested class/object scopes are
        # the one supported exception: their members are discovered by their
        # own owner scan, even when the nested owner itself is local to a
        # method.  Do not let an enclosing method from that outer scope hide
        # the nested owner's direct members.
        enclosing = [r for r in body_ranges if r[0] < fm.start() < r[2]]
        if enclosing:
            nested_owner_scope = (
                isinstance(owner, OwnerDeclaration)
                and any(r[0] < scope_start < r[2] for r in body_ranges)
            )
            if not nested_owner_scope or any(r[0] >= scope_start for r in enclosing):
                continue
        head = masked[scope_start:fm.start()]
        p = masked.find("(", fm.end(), scope_end)
        if p < 0: _fail("MALFORMED_SOURCE")
        prefix = masked[fm.end():p].strip()
        prefix = re.sub(r"^<[^<>]*>\s*", "", prefix)
        nm = re.search(r"(%s)\s*$" % _ID, prefix)
        if not nm: _fail("MALFORMED_SOURCE")
        name = nm.group(1)
        before_name = prefix[:nm.start()].rstrip()
        receiver_text = None
        if before_name:
            if not before_name.endswith("."): _fail("SIGNATURE_UNSUPPORTED")
            receiver_text = before_name[:-1].strip()
            if not receiver_text: _fail("SIGNATURE_UNSUPPORTED")
        name_end = p
        pend = _pairs(masked, p, ")")
        params = [] if not masked[p + 1:pend].strip() else _split_top(masked[p + 1:pend])
        types: list[str] = []
        unresolved_types = False
        try:
            for param in params:
                types.append(_resolve_type(_raw_parameter_type(param), environment, allow_vararg=True))
            receiver = receiver_text
            if receiver is not None:
                receiver = _resolve_type(receiver, environment)
        except ParserError as error:
            # Narrow repair (PR-GR-05 Slice 3): only the closed-world
            # type-resolution family is tolerable, and only behind the
            # explicit flag.  The raise happens mid-construction (per
            # parameter / on the receiver), so the declaration is rebuilt
            # from its raw parameter texts as grammar-normalized simple
            # names -- faithful source spelling, never a resolved or
            # fabricated identity -- and discovery continues after this
            # one fun.
            if not tolerate_unresolved_types or error.code != "TYPE_UNRESOLVED":
                raise
            try:
                types = [
                    normalize_type_text(_raw_parameter_type(param), allow_vararg=True)
                    for param in params
                ]
                receiver = (
                    normalize_type_text(receiver_text)
                    if receiver_text is not None
                    else None
                )
            except SignatureError as signature_error:
                # Grammar validation of the retained simple names is NOT
                # part of the tolerated family: stay fatal, exactly as the
                # strict pass would have been when reaching that parameter.
                _fail_signature(signature_error)
            unresolved_types = True
        try:
            sig = FunctionSignature(canonical_source_path("app/src/main/unknown.kt"), owner_name, name, receiver, tuple(types))
        except SignatureError as error: _fail_signature(error)
        q = pend + 1
        while q < scope_end and masked[q].isspace(): q += 1
        if q < scope_end and masked[q] == ":":
            q += 1
        body_start, boundary = _header_body_start(masked, q, scope_end)
        q = body_start if body_start is not None else boundary
        body = None
        end = q
        if q < scope_end and masked[q] == "{":
            end = _body_end(masked, q) + 1
            body = text[q:end]
        status = "RESOLVED_EXACTLY"
        if q < scope_end and masked[q] == "=":
            status = "UNSUPPORTED_EXPRESSION_BODY"
        if unresolved_types:
            # Tolerant retention names the type-resolution fact -- the
            # exact failure strict mode died on for this declaration.
            # Either status keeps the declaration out of exact resolution;
            # this one is the specific debt this slice exposes.
            status = "TYPE_UNRESOLVED"
        result.append(CallableDeclaration(sig, owner_name, fm.start(), end, body, status))
    return tuple(result)


def parse_kotlin_file(path: str | Path) -> tuple[CallableDeclaration, ...]:
    try:
        canonical = canonical_source_path(path)
        # Validate the caller-supplied path before touching the filesystem.
        # This is intentionally separate from Path.read_text so rejected
        # absolute, backslash, and traversal paths cannot be opened.
        text = Path(path).read_text(encoding="utf-8")
        owners = find_owner_declarations(text)
        out: list[CallableDeclaration] = []
        for o in owners:
            for d in find_callable_declarations(text, o):
                sig = FunctionSignature(canonical, d.owner, d.signature.function_name, d.signature.receiver, d.signature.parameter_types)
                out.append(CallableDeclaration(sig, d.owner, d.start_offset, d.end_offset, d.body, d.status))
        return tuple(out)
    except ParserError:
        raise
    except Exception:
        _fail()


def resolve_callable(declarations: Iterable[CallableDeclaration], owner: str, name: str, receiver: str | None, parameter_types: Iterable[str]) -> str:
    try:
        params = tuple(normalize_type_text(x, allow_vararg=True) for x in parameter_types)
        recv = normalize_type_text(receiver) if receiver is not None else None
    except SignatureError as error:
        return error.code if error.code in _SIGNATURE_ERROR_CODES else "PARSER_ERROR"
    except TypeError:
        return "NOT_TEXT"
    matches = [d for d in declarations if d.owner == owner and d.signature.function_name == name and d.signature.receiver == recv and d.signature.parameter_types == params]
    if any(d.status != "RESOLVED_EXACTLY" for d in matches): return "SIGNATURE_UNSUPPORTED"
    if len(matches) == 1: return "RESOLVED_EXACTLY"
    if len(matches) > 1: return "AMBIGUOUS_OVERLOAD"
    if any(d.owner == owner and d.signature.function_name == name for d in declarations): return "SIGNATURE_UNSUPPORTED"
    return "METHOD_MISSING"
