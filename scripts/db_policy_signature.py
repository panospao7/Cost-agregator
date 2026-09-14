"""Database policy signature helpers.

A frozen, immutable description of a function signature used by the DB
policy layer for signature lookup. This module imports only the standard
library and never embeds source text, exception messages, or
user-controlled payloads in the errors it raises.

Type text is validated structurally instead of by a permissive character
filter. The accepted grammar covers qualified names, generic arguments,
nullability markers, array suffixes, and function types (including nested
function types with generic arguments). A ``vararg`` marker is accepted
only as a single leading prefix on a top-level parameter type; it is
rejected in owner, receiver, nested generic, and function-type contexts.
Reserved Kotlin declaration identifiers (``vararg`` plus the hard
keywords ``class``, ``fun``, ``interface``, ``object``, ``typealias``,
``val``, ``var``) are never accepted as an owner FQCN segment or as a
function name; they fail with the controlled ``BAD_OWNER`` / ``BAD_NAME``
codes.
Input is normalized to a canonical spelling that is deterministic and
collision safe for signature lookup.

Type-text validation is bounded: inputs longer than 4096 characters, more
than 256 tokens, or nested deeper than 32 levels are rejected with
controlled SignatureError codes so pathological inputs cannot cause
unbounded token growth, recursion, or stack exhaustion.

Canonical path validation is bounded too: paths longer than 512
characters, deeper than 32 segments, or carrying a segment longer than
128 characters are rejected with controlled SignatureError codes
(``PATH_TOO_LONG``, ``PATH_TOO_DEEP``, ``PATH_SEGMENT_TOO_LONG``) so
pathological inputs cannot create oversized canonical identities.

The canonical signature identity is the GR-09 readable form
``<repo-relative-posix-path>::owner#function(receiver)(param, param)``
where the receiver group is present only for non-null receivers. The
path is syntax-only (any repo-relative POSIX ``.kt`` path is accepted);
topology membership is validated separately by root-aware stages. Frame
delimiter characters inside field values are backslash-escaped so the
composite string is collision safe while ordinary inputs stay readable.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from typing import Any

__all__ = ["FunctionSignature", "SignatureError", "normalize_type_text"]


_CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")
# Control characters rejected in TYPE text.  Standard Kotlin token
# separators (tab, newline, carriage return) are whitespace, not hostile
# control payload: multi-line type expressions are legal Kotlin and the
# grammar's tokenizer skips them, so they must not fail the CONTROL_TYPE
# gate.  Every other C0 control plus DEL stays rejected -- including the
# whitespace-like ``\x1c``-``\x1f`` anti-evasion set at leading/trailing
# positions.
_TYPE_CONTROL_RE = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_NAME_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
# Owner FQCNs are a strict dotted chain of Kotlin identifiers only; no
# generics, nullability markers, array suffixes, function types, whitespace,
# or control characters are permitted.
_OWNER_FQCN_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*")

# Controlled reserved-identifier set for callable identity fields. These are
# Kotlin hard keywords that can never be a class/object/function declaration
# name, plus ``vararg`` (the modifier keyword the type grammar already
# treats specially). An owner segment or function name equal to one of these
# is rejected with the controlled BAD_OWNER / BAD_NAME code instead of being
# accepted as a bare identifier; the type-text parser is untouched, so
# ``vararg`` keeps its existing top-level parameter-prefix semantics there.
_RESERVED_IDENTIFIERS = frozenset(
    {
        "class",
        "fun",
        "interface",
        "object",
        "typealias",
        "val",
        "var",
        "vararg",
    }
)
_KEYS = (
    "canonical_path",
    "owner_fqcn",
    "function_name",
    "receiver",
    "parameter_types",
)
_KEY_SET = frozenset(_KEYS)

# Canonical identity framing (GR-09 readable form). Backslash, colon,
# hash, parentheses, and comma are escaped inside field values; ``::``,
# ``#``, and the parenthesis/comma groups are the unescaped frame.
_CANONICAL_ESCAPE_RE = re.compile(r"([\\:#(),])")

# Canonical source paths are POSIX, repo-relative, ending in .kt.
# Topology membership (which module/source-set a path lives under) is NOT
# enforced here -- that is the responsibility of root-aware stages via
# ``source_roots.is_declared_production_path``.  This module performs
# syntax-only validation so any module tree (``app/src/main/java``,
# ``feature/src/main/kotlin``, ``lib/core/src/main/java``, ...) is accepted.
_PATH_DRIVE_RE = re.compile(r"^[A-Za-z]:")

# Bounded resource limits for type-text validation. Inputs exceeding these
# limits are rejected with controlled SignatureError codes before or while
# parsing, so pathological inputs cannot cause unbounded token growth,
# unbounded recursion, or stack exhaustion.
MAX_TYPE_INPUT_LENGTH = 4096
MAX_TYPE_TOKENS = 256
MAX_TYPE_DEPTH = 32

# Bounded resource limits for canonical path validation. Paths exceeding
# these limits are rejected with controlled SignatureError codes before
# they can be accepted into a signature, so pathological inputs cannot
# create oversized canonical identities.
MAX_CANONICAL_PATH_LENGTH = 512
MAX_CANONICAL_PATH_SEGMENTS = 32
MAX_CANONICAL_PATH_SEGMENT_LENGTH = 128

# Only these exact constant messages may be adopted by SignatureError;
# caller-supplied arbitrary text is ignored so raw input cannot leak.
_MESSAGES = frozenset(
    {
        "unsupported character in type",
        "malformed type",
        "unbalanced generic delimiters",
        "unbalanced parenthesis delimiters",
        "unbalanced array delimiters",
        "malformed comma in type",
        "duplicate nullability marker",
        "generic type arguments must not be empty",
        "function type must declare at least one parameter",
        "parenthesized type requires a single type or a function arrow",
        "type must be a string",
        "blank type",
        "control characters are not allowed in a type",
        "control characters are not allowed in a path",
        "vararg is only allowed on a top-level parameter type",
        "vararg prefix must be followed by exactly one type",
        "type text is too long",
        "type text has too many tokens",
        "type nesting is too deep",
        "canonical path is too long",
        "canonical path has too many segments",
        "canonical path segment is too long",
    }
)

# Closed set of controlled reason codes. SignatureError never invents codes:
# an unknown or non-constant code is replaced by the fixed fallback below so
# no arbitrary caller text can become the code or appear in str(exc).
_FALLBACK_ERROR_CODE = "INVALID_SIGNATURE_ERROR"
_ERROR_CODES = frozenset(
    {
        "NOT_TEXT",
        "BLANK_TYPE",
        "CONTROL_TYPE",
        "CONTROL_PATH",
        "UNSUPPORTED_TOKEN",
        "BAD_TYPE",
        "UNBALANCED_ANGLE",
        "UNBALANCED_PAREN",
        "UNBALANCED_ARRAY",
        "BAD_COMMAS",
        "EMPTY_GENERIC",
        "DUPLICATE_NULLABLE",
        "MISSING_ARROW",
        "EMPTY_FUNCTION",
        "VARARG_CONTEXT",
        "VARARG_PREFIX",
        "TYPE_TOO_LONG",
        "TYPE_TOO_MANY_TOKENS",
        "NESTING_TOO_DEEP",
        "PATH_TOO_LONG",
        "PATH_TOO_DEEP",
        "PATH_SEGMENT_TOO_LONG",
        "BAD_NAME",
        "BAD_PATH",
        "BAD_PARAMS",
        "NOT_OBJECT",
        "BAD_KEYS",
        "BAD_OWNER",
        "BAD_RECEIVER",
        _FALLBACK_ERROR_CODE,
    }
)

# Token kinds produced by the structural type scanner.
_TOKEN_IDENT = "IDENT"
_TOKEN_DOT = "DOT"
_TOKEN_ARROW = "ARROW"
_TOKEN_LT = "LT"
_TOKEN_GT = "GT"
_TOKEN_LPAREN = "LPAREN"
_TOKEN_RPAREN = "RPAREN"
_TOKEN_LBRACKET = "LBRACKET"
_TOKEN_RBRACKET = "RBRACKET"
_TOKEN_QUESTION = "QUESTION"
_TOKEN_COMMA = "COMMA"

_SCANNER = re.Scanner(
    [
        (r"\s+", lambda scanner, token: None),
        (r"->", lambda scanner, token: (_TOKEN_ARROW, token)),
        (r"[A-Za-z_][A-Za-z0-9_]*", lambda scanner, token: (_TOKEN_IDENT, token)),
        (r"\.", lambda scanner, token: (_TOKEN_DOT, token)),
        (r"<", lambda scanner, token: (_TOKEN_LT, token)),
        (r">", lambda scanner, token: (_TOKEN_GT, token)),
        (r"\(", lambda scanner, token: (_TOKEN_LPAREN, token)),
        (r"\)", lambda scanner, token: (_TOKEN_RPAREN, token)),
        (r"\[", lambda scanner, token: (_TOKEN_LBRACKET, token)),
        (r"\]", lambda scanner, token: (_TOKEN_RBRACKET, token)),
        (r"\?", lambda scanner, token: (_TOKEN_QUESTION, token)),
        (r",", lambda scanner, token: (_TOKEN_COMMA, token)),
    ]
)


class SignatureError(Exception):
    """Controlled error carrying a stable reason code and constant message.

    The ``code`` is a controlled reason code drawn from the module's closed
    ``_ERROR_CODES`` set; an unknown or non-constant code is replaced by the
    fixed ``INVALID_SIGNATURE_ERROR`` fallback. The ``message`` is adopted
    only when it is one of the module's approved constants; caller-supplied
    arbitrary text is ignored, so raw input can never leak through this
    exception.
    """

    def __init__(self, code: str, message: str | None = None) -> None:
        if not isinstance(code, str) or code not in _ERROR_CODES:
            code = _FALLBACK_ERROR_CODE
        self.code = code
        self.message = (
            message if isinstance(message, str) and message in _MESSAGES else code
        )
        super().__init__(self.message)


def _reject(code: str, message: str | None = None) -> None:
    raise SignatureError(code, message)


def _tokenize(text: str) -> list[tuple[str, str]]:
    """Scan text into tokens, rejecting any unsupported character or an
    input that exceeds the bounded token budget."""
    tokens, rest = _SCANNER.scan(text)
    if rest:
        _reject("UNSUPPORTED_TOKEN", "unsupported character in type")
    if len(tokens) > MAX_TYPE_TOKENS:
        _reject("TYPE_TOO_MANY_TOKENS", "type text has too many tokens")
    return tokens


class _Parser:
    """Recursive-descent parser for the accepted type-text grammar.

    Whitespace between tokens is skipped; the normalized output is rebuilt
    with only the canonical single spaces (after ``vararg``, around the
    function arrow, and after list commas).
    """

    def __init__(self, tokens: list[tuple[str, str]]) -> None:
        self._tokens = tokens
        self._pos = 0
        # Current recursive-descent nesting depth; every nested type parse
        # passes through _parse_core, so it is the single choke point for
        # the bounded-depth check.
        self._depth = 0

    def _peek(self) -> tuple[str, str] | None:
        if self._pos < len(self._tokens):
            return self._tokens[self._pos]
        return None

    def _take(self) -> tuple[str, str] | None:
        token = self._peek()
        if token is not None:
            self._pos += 1
        return token

    def _is(self, kind: str) -> bool:
        token = self._peek()
        return token is not None and token[0] == kind

    def _is_ident(self, text: str) -> bool:
        token = self._peek()
        return token is not None and token[0] == _TOKEN_IDENT and token[1] == text

    def _unexpected_primary(self) -> None:
        token = self._peek()
        if token is None:
            _reject("BAD_TYPE", "malformed type")
        kind = token[0]
        if kind == _TOKEN_GT:
            _reject("UNBALANCED_ANGLE", "unbalanced generic delimiters")
        if kind == _TOKEN_RPAREN:
            _reject("UNBALANCED_PAREN", "unbalanced parenthesis delimiters")
        if kind in (_TOKEN_LBRACKET, _TOKEN_RBRACKET):
            _reject("UNBALANCED_ARRAY", "unbalanced array delimiters")
        if kind == _TOKEN_COMMA:
            _reject("BAD_COMMAS", "malformed comma in type")
        _reject("BAD_TYPE", "malformed type")

    def _reject_leftover(self) -> None:
        token = self._peek()
        if token is None:
            return
        kind = token[0]
        if kind == _TOKEN_GT:
            _reject("UNBALANCED_ANGLE", "unbalanced generic delimiters")
        if kind == _TOKEN_RPAREN:
            _reject("UNBALANCED_PAREN", "unbalanced parenthesis delimiters")
        if kind in (_TOKEN_LBRACKET, _TOKEN_RBRACKET):
            _reject("UNBALANCED_ARRAY", "unbalanced array delimiters")
        if kind == _TOKEN_COMMA:
            _reject("BAD_COMMAS", "malformed comma in type")
        _reject("BAD_TYPE", "malformed type")

    def parse_type(self) -> str:
        """Parse one complete type expression.

        ``vararg`` tokens are rejected here, so owner, receiver, nested
        generic, and function-type contexts can never accept them.
        """
        if self._is_ident("vararg"):
            _reject(
                "VARARG_CONTEXT",
                "vararg is only allowed on a top-level parameter type",
            )
        return self._parse_core()

    def parse_parameter_type(self) -> str:
        """Parse one parameter type, allowing a single top-level vararg prefix.

        The core type after ``vararg `` is parsed with nested vararg still
        rejected (``parse_type`` is the only nested entry), so duplicate
        prefixes (``vararg vararg X``) and empty prefixes (``vararg`` alone)
        fail with ``VARARG_PREFIX``.
        """
        if self._is_ident("vararg"):
            self._take()
            if self._is_ident("vararg") or self._peek() is None:
                _reject(
                    "VARARG_PREFIX",
                    "vararg prefix must be followed by exactly one type",
                )
            core = self._parse_core()
            return "vararg " + core
        return self._parse_core()

    def _parse_core(self) -> str:
        self._depth += 1
        if self._depth > MAX_TYPE_DEPTH:
            _reject("NESTING_TOO_DEEP", "type nesting is too deep")
        if self._is(_TOKEN_LPAREN):
            result = self._parse_paren_group()
        else:
            result = self._parse_qualified()
        while self._is(_TOKEN_LBRACKET):
            self._take()
            if not self._is(_TOKEN_RBRACKET):
                _reject("UNBALANCED_ARRAY", "unbalanced array delimiters")
            self._take()
            result += "[]"
        if self._is(_TOKEN_QUESTION):
            self._take()
            if self._is(_TOKEN_QUESTION):
                _reject("DUPLICATE_NULLABLE", "duplicate nullability marker")
            result += "?"
        self._depth -= 1
        return result

    def _parse_qualified(self) -> str:
        if not self._is(_TOKEN_IDENT):
            self._unexpected_primary()
        parts = [self._take()[1]]
        while self._is(_TOKEN_DOT):
            self._take()
            if not self._is(_TOKEN_IDENT):
                _reject("BAD_TYPE", "malformed type")
            parts.append(self._take()[1])
        name = ".".join(parts)
        if self._is(_TOKEN_LT):
            self._take()
            name += self._parse_type_args()
        return name

    def _parse_type_args(self) -> str:
        # Caller consumed the opening '<'.
        if self._peek() is None:
            _reject("UNBALANCED_ANGLE", "unbalanced generic delimiters")
        if self._is(_TOKEN_GT):
            _reject("EMPTY_GENERIC", "generic type arguments must not be empty")
        if self._is(_TOKEN_COMMA):
            _reject("BAD_COMMAS", "malformed comma in type")
        args = [self.parse_type()]
        while True:
            if self._is(_TOKEN_COMMA):
                self._take()
                if (
                    self._is(_TOKEN_COMMA)
                    or self._is(_TOKEN_GT)
                    or self._peek() is None
                ):
                    _reject("BAD_COMMAS", "malformed comma in type")
                args.append(self.parse_type())
            elif self._is(_TOKEN_GT):
                self._take()
                break
            else:
                _reject("UNBALANCED_ANGLE", "unbalanced generic delimiters")
        return "<" + ", ".join(args) + ">"

    def _parse_paren_group(self) -> str:
        # Caller peeked '('.
        self._take()
        if self._peek() is None:
            _reject("UNBALANCED_PAREN", "unbalanced parenthesis delimiters")
        if self._is(_TOKEN_RPAREN):
            self._take()
            if self._is(_TOKEN_ARROW):
                self._take()
                return_type = self.parse_type()
                return "() -> " + return_type
            _reject(
                "MISSING_ARROW",
                "parenthesized type requires a single type or a function arrow",
            )
        if self._is(_TOKEN_COMMA):
            _reject("BAD_COMMAS", "malformed comma in type")
        params = [self.parse_type()]
        while self._is(_TOKEN_COMMA):
            self._take()
            if (
                self._is(_TOKEN_COMMA)
                or self._is(_TOKEN_RPAREN)
                or self._peek() is None
            ):
                _reject("BAD_COMMAS", "malformed comma in type")
            params.append(self.parse_type())
        if not self._is(_TOKEN_RPAREN):
            _reject("UNBALANCED_PAREN", "unbalanced parenthesis delimiters")
        self._take()
        if self._is(_TOKEN_ARROW):
            self._take()
            # The return type is a nested function-type context: parse it
            # through the non-vararg entry so a leading ``vararg`` token is
            # rejected with VARARG_CONTEXT instead of being absorbed as a
            # bare identifier.
            return_type = self.parse_type()
            return "(" + ", ".join(params) + ") -> " + return_type
        if len(params) != 1:
            _reject(
                "MISSING_ARROW",
                "parenthesized type requires a single type or a function arrow",
            )
        return "(" + params[0] + ")"


def normalize_type_text(value: Any, *, allow_vararg: bool = False) -> str:
    """Return a normalized type text or raise SignatureError.

    Structurally validates Kotlin-style type text: qualified names, nested
    generic arguments, nullability, arrays, and function types (including
    nested function types with generic arguments). Rejects unbalanced
    delimiters, empty generic/function components, malformed commas, control
    characters, unsupported tokens, and inputs exceeding the bounded
    length/token/depth limits (``TYPE_TOO_LONG``, ``TYPE_TOO_MANY_TOKENS``,
    ``NESTING_TOO_DEEP``). The length bound is enforced on the raw input
    before trimming, so whitespace padding cannot bypass it. Control
    characters are likewise validated against the raw input before trimming:
    standard Kotlin token separators (tab, newline, carriage return) are
    accepted between tokens (multi-line type expressions are legal), while
    every other control character -- including the whitespace-like
    ``\x1c``-``\x1f`` set, which ``isspace()``/``strip()`` treat as
    whitespace, at leading or trailing positions -- is rejected with
    ``CONTROL_TYPE`` and can never be silently stripped away.

    ``vararg`` is rejected by default in every context. When
    ``allow_vararg=True`` a single leading ``vararg `` prefix is accepted on
    the whole expression (parameter-type semantics); the type following the
    prefix must be exactly one non-vararg type, so nested/duplicate/empty
    prefixes still fail.
    """
    if not isinstance(value, str):
        _reject("NOT_TEXT", "type must be a string")
    # The length bound applies to the raw input before strip/normalization,
    # so whitespace padding can never bypass it: any raw input longer than
    # MAX_TYPE_INPUT_LENGTH is rejected with TYPE_TOO_LONG regardless of
    # what it would normalize to.
    if len(value) > MAX_TYPE_INPUT_LENGTH:
        _reject("TYPE_TOO_LONG", "type text is too long")
    # Control characters are validated against the raw input before
    # strip/normalization, so whitespace padding can never bypass it.  Tab,
    # newline, and carriage return are accepted Kotlin token separators
    # (multi-line type expressions are legal); every other control character,
    # including the whitespace-like ``\x1c``-``\x1f`` set (which
    # ``isspace()``/``strip()`` treat as whitespace) at leading or trailing
    # positions, is rejected with ``CONTROL_TYPE`` and can never be silently
    # stripped away.
    if _TYPE_CONTROL_RE.search(value):
        _reject("CONTROL_TYPE", "control characters are not allowed in a type")
    text = value.strip()
    if not text:
        _reject("BLANK_TYPE", "blank type")
    tokens = _tokenize(text)
    parser = _Parser(tokens)
    try:
        normalized = (
            parser.parse_parameter_type() if allow_vararg else parser.parse_type()
        )
        parser._reject_leftover()
    except RecursionError:
        # Safety net: recursive-descent depth is already bounded by
        # MAX_TYPE_DEPTH, but map any residual RecursionError to the
        # controlled code so neither raw input nor interpreter detail
        # escapes.
        _reject("NESTING_TOO_DEEP", "type nesting is too deep")
    return normalized


def _normalize_name(value: Any) -> str:
    if not isinstance(value, str) or not _NAME_RE.fullmatch(value):
        _reject("BAD_NAME")
    # A reserved Kotlin declaration identifier cannot be a function name;
    # reject it with the controlled code so confusable identities never
    # enter lookup.
    if value in _RESERVED_IDENTIFIERS:
        _reject("BAD_NAME")
    return value


def _normalize_owner_fqcn(value: Any) -> str:
    """Validate and return an owner fully-qualified class name.

    The owner must match the strict FQCN grammar
    ``[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*``. Non-strings,
    blank values, control characters, and any input carrying whitespace,
    generic arguments, nullability markers, array suffixes, function types,
    or malformed/invalid segments are rejected with controlled
    SignatureError codes. A segment equal to a reserved Kotlin declaration
    identifier (for example ``vararg`` in ``com.vararg.Type``) is rejected
    with ``BAD_OWNER`` too, even though it matches the identifier grammar.
    Control characters are validated against the raw input before the blank
    check: whitespace-like control characters (for
    example ``\x1c``-``\x1f``, which ``isspace()``/``strip()`` treat as
    whitespace) at leading or trailing positions are rejected with
    CONTROL_TYPE, never silently stripped into a blank or padded value.
    Valid input is returned unchanged so canonical identity is preserved.
    """
    if not isinstance(value, str):
        _reject("NOT_TEXT", "type must be a string")
    # Control characters are validated against the raw input before the
    # blank check (which strips), so whitespace-like control characters
    # such as \x1c-\x1f at leading or trailing positions fail with
    # CONTROL_TYPE instead of being silently removed or blanked out.
    if _CONTROL_RE.search(value):
        _reject("CONTROL_TYPE", "control characters are not allowed in a type")
    if not value.strip():
        _reject("BLANK_TYPE", "blank type")
    if not _OWNER_FQCN_RE.fullmatch(value):
        _reject("BAD_OWNER")
    # Reserved Kotlin declaration identifiers (e.g. ``vararg``) cannot be a
    # package or class segment; a dotted segment equal to one is rejected
    # with the controlled code, never accepted as a bare identifier.
    if any(segment in _RESERVED_IDENTIFIERS for segment in value.split(".")):
        _reject("BAD_OWNER")
    return value


def _escape_canonical_text(value: str) -> str:
    """Escape canonical frame delimiters inside one field value."""
    return _CANONICAL_ESCAPE_RE.sub(r"\\\1", value)


def _normalize_canonical_path(value: Any) -> str:
    """Validate and return a canonical POSIX repo-relative ``.kt`` path.

    Syntax-only validation: rejects non-strings, absolute paths and drive
    prefixes, backslashes, traversal/empty/current-directory segments,
    whitespace, control characters, and paths not ending in ``.kt``.
    Topology membership (which module/source-set a path lives under) is
    NOT enforced here -- that is the responsibility of root-aware stages
    via ``source_roots.is_declared_production_path``.  Any module tree
    (``app/src/main/java``, ``feature/src/main/kotlin``,
    ``lib/core/src/main/java``, ...) is syntactically valid.

    Control characters are rejected with the dedicated CONTROL_PATH code;
    whitespace is rejected with BAD_PATH. Accepted values are already
    canonical and are returned unchanged.

    Bounded resource limits apply before structural checks: a path longer
    than ``MAX_CANONICAL_PATH_LENGTH`` is rejected with PATH_TOO_LONG, a
    path deeper than ``MAX_CANONICAL_PATH_SEGMENTS`` segments with
    PATH_TOO_DEEP, and a path carrying a segment longer than
    ``MAX_CANONICAL_PATH_SEGMENT_LENGTH`` with PATH_SEGMENT_TOO_LONG.
    The length bound is enforced on the raw input before any structural
    check, so no input can bypass it by being non-canonical. Control
    characters are validated against the raw input before the whitespace
    and structural checks: whitespace-like control characters (for example
    ``\x1c``-``\x1f``, which ``isspace()`` treats as whitespace) at leading
    or trailing positions are rejected with CONTROL_PATH, never silently
    accepted or folded into the generic whitespace BAD_PATH rejection.
    """
    if not isinstance(value, str):
        _reject("BAD_PATH")
    # The length bound applies to the raw input before structural checks,
    # so over-long inputs fail with PATH_TOO_LONG regardless of shape.
    if len(value) > MAX_CANONICAL_PATH_LENGTH:
        _reject("PATH_TOO_LONG", "canonical path is too long")
    # Control characters are validated against the raw input before any
    # stripping/whitespace handling, so whitespace-like control characters
    # such as \x1c-\x1f at leading or trailing positions fail with
    # CONTROL_PATH instead of being silently accepted or folded into the
    # generic whitespace BAD_PATH rejection.
    if _CONTROL_RE.search(value):
        _reject("CONTROL_PATH", "control characters are not allowed in a path")
    if any(c.isspace() for c in value):
        _reject("BAD_PATH")
    if "\\" in value:
        _reject("BAD_PATH")
    if value.startswith("/") or _PATH_DRIVE_RE.match(value):
        _reject("BAD_PATH")
    if not value.endswith(".kt"):
        _reject("BAD_PATH")
    segments = value.split("/")
    if any(part in ("", ".", "..") for part in segments):
        _reject("BAD_PATH")
    if len(segments) > MAX_CANONICAL_PATH_SEGMENTS:
        _reject("PATH_TOO_DEEP", "canonical path has too many segments")
    if any(len(segment) > MAX_CANONICAL_PATH_SEGMENT_LENGTH for segment in segments):
        _reject("PATH_SEGMENT_TOO_LONG", "canonical path segment is too long")
    return value


@dataclass(frozen=True)
class FunctionSignature:
    """Frozen function signature description used by DB policy lookup."""

    canonical_path: str
    owner_fqcn: str
    function_name: str
    receiver: str | None
    parameter_types: tuple[str, ...]

    def __post_init__(self) -> None:
        if not isinstance(self.parameter_types, (tuple, list)):
            _reject("BAD_PARAMS")
        path = _normalize_canonical_path(self.canonical_path)
        owner = _normalize_owner_fqcn(self.owner_fqcn)
        name = _normalize_name(self.function_name)
        receiver = normalize_type_text(self.receiver) if self.receiver is not None else None
        # Only parameter types may carry a single top-level ``vararg`` prefix;
        # owner/receiver/nested/function contexts reject it.
        params = tuple(
            normalize_type_text(p, allow_vararg=True) for p in self.parameter_types
        )
        object.__setattr__(self, "canonical_path", path)
        object.__setattr__(self, "owner_fqcn", owner)
        object.__setattr__(self, "function_name", name)
        object.__setattr__(self, "receiver", receiver)
        object.__setattr__(self, "parameter_types", params)

    def canonical(self) -> str:
        """Stable collision-safe canonical identity excluding return type,
        parameter names, defaults, visibility, suspend, and annotations.

        Format (GR-09 readable):
        ``<canonical_path>::<owner_fqcn>#<function_name>(<receiver>)(<param>, ...)``
        The receiver group is emitted only when the receiver is non-null.
        Delimiter characters inside field values are backslash-escaped so
        fields cannot collide after concatenation; ordinary inputs contain
        no delimiter characters and stay readable.
        """
        parts = [
            _escape_canonical_text(self.canonical_path),
            "::",
            _escape_canonical_text(self.owner_fqcn),
            "#",
            _escape_canonical_text(self.function_name),
        ]
        if self.receiver is not None:
            parts.append("(" + _escape_canonical_text(self.receiver) + ")")
        parts.append(
            "("
            + ", ".join(_escape_canonical_text(p) for p in self.parameter_types)
            + ")"
        )
        return "".join(parts)

    def to_dict(self) -> dict[str, Any]:
        return {
            "canonical_path": self.canonical_path,
            "owner_fqcn": self.owner_fqcn,
            "function_name": self.function_name,
            "receiver": self.receiver,
            "parameter_types": list(self.parameter_types),
        }

    @classmethod
    def from_dict(cls, data: Any) -> FunctionSignature:
        if not isinstance(data, dict):
            _reject("NOT_OBJECT")
        if set(data.keys()) != _KEY_SET:
            _reject("BAD_KEYS")
        path = data["canonical_path"]
        owner = data["owner_fqcn"]
        name = data["function_name"]
        receiver = data["receiver"]
        params = data["parameter_types"]
        if not isinstance(path, str):
            _reject("BAD_PATH")
        if not isinstance(owner, str):
            _reject("BAD_OWNER")
        if not isinstance(name, str):
            _reject("BAD_NAME")
        if receiver is not None and not isinstance(receiver, str):
            _reject("BAD_RECEIVER")
        if not isinstance(params, list):
            _reject("BAD_PARAMS")
        for p in params:
            if not isinstance(p, str):
                _reject("BAD_PARAMS")
        return cls(
            canonical_path=path,
            owner_fqcn=owner,
            function_name=name,
            receiver=receiver,
            parameter_types=tuple(params),
        )
