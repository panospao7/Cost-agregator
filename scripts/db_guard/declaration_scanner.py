"""Fail-closed inventory of Kotlin declarations used by the DB guard.

This is deliberately a source *range* scanner, not a Kotlin compiler.  Comments
and literals are removed by the repository parser before structural matching;
anything that cannot be bounded unambiguously is reported and is not claimed
as a resolved declaration.
"""
from __future__ import annotations

import hashlib
import json
import math
import os
import re
import tempfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Mapping

from ..kotlin_callable_parser import ParserError, mask_kotlin_source
from .dao_accessors import AccessorError, find_dao_declarations

try:
    from ..ci.finding_rule_catalog import is_known_diagnostic as _is_known_diagnostic
except ImportError:
    # Fail closed: without the immutable catalog no diagnostic code can be
    # verified, so every unknown/unverifiable code is rejected at
    # ``Diagnostic`` construction instead of being trusted.
    def _is_known_diagnostic(code: object) -> bool:
        return False


SCAN_SCHEMA = "cost-aggregator.db-declaration-scan"
SCAN_SCHEMA_VERSION = 2
_ROOT = Path("app") / "src" / "main" / "java"
_ID = r"[A-Za-z_][A-Za-z0-9_]*"
_DECL = re.compile(
    r"\b(?P<prefix>(?:(?:public|private|protected|internal|open|abstract|final|sealed|data|enum|annotation)\s+)*)"
    r"(?P<kind>class|interface|object)\s+(?P<name>" + _ID + r")\b"
)
_ENUM = re.compile(r"\benum\s+class\s+(?P<name>" + _ID + r")\b")
_ANNOTATION = re.compile(r"\bannotation\s+class\s+(?P<name>" + _ID + r")\b")
# ``enum class`` and ``annotation class`` declarations are owned exclusively
# by ``_ENUM``/``_ANNOTATION``: the generic declaration pattern must never
# claim their ``class`` keyword, or an enum/annotation would be inventoried
# twice (once with its exclusive kind and once as a duplicate generic class).
_EXCLUSIVE_KINDS = frozenset({"enum", "annotation"})
_COMPANION_DECL = re.compile(r"\bcompanion\s+object\b")
_FUN = re.compile(r"\bfun\b")
_PROPERTY = re.compile(r"\b(?:const\s+)?(?:val|var)\s+" + _ID + r"\b")
_COMPANION = re.compile(r"\bcompanion\s+object\b")
_ACCESSOR = re.compile(r"\b(?:get|set)\s*\(")
_INIT = re.compile(r"\binit\s*\{")
# Direct-scope sibling declarations (owners and callables) bound a bodyless
# declaration's header.  ``_SIBLING_MODIFIER`` covers the modifier block that
# may precede a sibling's kind keyword: the full Kotlin declaration-modifier
# set (visibility/lifecycle/type modifiers plus callable modifiers such as
# ``override``/``suspend``, type/declaration modifiers such as ``value``
# (``value class``), and property modifiers such as ``const``/``lateinit``),
# ``companion`` before ``object``, and the parameter
# modifiers (``vararg``, ``noinline``, ``crossinline``, ``reified``).
# ``enum``/``annotation`` are declaration keywords too and stay on
# ``_DIRECT_DECLARATION``, never duplicated as modifiers here.  ``typealias``
# is included in both boundary paths because it is a direct declaration when
# encountered at its keyword, and a fresh-line sibling starter when the
# boundary walker examines the complete next declaration token.
_DIRECT_DECLARATION = re.compile(
    r"(?:fun|val|var|class|interface|object|enum|annotation|typealias|init)\b"
)
_SIBLING_MODIFIER = re.compile(
    r"(?:value|override|operator|suspend|inline|infix|tailrec|external|expect|actual|inner|"
    r"const|lateinit|vararg|noinline|crossinline|reified|"
    r"data|sealed|open|abstract|final|public|private|protected|internal|companion|typealias)\b"
)

# F1 protocol-safe bounds for ``Diagnostic.controlled_context`` (mirrors
# ``scripts/ci/guard_findings.py`` protocol-v2 section 5.2 limits).
MAX_CONTEXT = 300
MAX_CONTEXT_KEY = 64
MAX_CONTEXT_DEPTH = 4
MAX_CONTEXT_ITEMS = 256
MAX_CONTEXT_NUMBER = 10 ** 18

# Diagnostic shape bounds: ``path`` must be a canonical repository-relative
# POSIX ``app/src`` path (protocol MAX_PATH=500), and ``location`` line and
# column values must be finite, bounded positive ints (or None for column).
MAX_DIAGNOSTIC_PATH = 500
MAX_LOCATION_NUMBER = 2 ** 31 - 1

# Key names that must never appear in free-form context: they are the names
# used for raw source, exception, and user payload content.  ``path`` is
# forbidden too because ``Diagnostic`` has the dedicated ``path`` field.
_FORBIDDEN_CONTEXT_KEYS = frozenset({
    "message", "exception", "stack", "trace", "traceback", "source", "snippet",
    "sql", "ocr", "path", "user", "raw",
})
# Word components extended with ``payload`` so payload-smuggling keys such as
# ``user_payload`` are rejected as well.
_FORBIDDEN_CONTEXT_PARTS = frozenset(_FORBIDDEN_CONTEXT_KEYS | {"payload"})
_FORBIDDEN_CONTEXT_WORD_RE = re.compile(r"[^a-z0-9]+")
_NUL_RE = re.compile(r"\x00")
_CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")
_DRIVE_RE = re.compile(r"^[A-Za-z]:")
_EXCEPTION_TEXT_RE = re.compile(
    r"(?:Traceback\b)|(?:\b[A-Z][A-Za-z0-9_]*(?:Error|Exception)\b)"
)
# Single-token suffixes that mark a value as a raw filename instead of a
# controlled identifier.  The set is explicit and curated (never a shape-only
# rule): reason codes such as ``EXPORT_FAILED``, stage names like ``stage``,
# short namespaced identifiers (``writer/helper``), and dotted identifiers the
# contract allows (``foo.bar``) never carry these suffixes, so they remain
# valid while ``secret.kt``/``file.json``/``trace.log``/``backup.db`` and
# similar artifact names are rejected as raw path text.
_KNOWN_FILE_EXTENSIONS = frozenset({
    # source/build artifacts
    "kt", "kts", "java", "py", "js", "ts", "c", "cpp", "h", "cs", "go",
    "rb", "php", "sh", "bat", "gradle", "jar", "class",
    # data/config artifacts
    "json", "yaml", "yml", "xml", "toml", "ini", "cfg", "conf",
    "properties", "csv", "tsv", "sql", "db", "sqlite", "sqlite3", "txt",
    "md",
    # logs/temp/backup/archive artifacts
    "log", "tmp", "temp", "bak", "backup", "old", "orig", "zip", "gz",
    "tar", "tgz", "7z", "rar", "apk", "aar", "dmp", "dump", "trace",
    "pid", "core",
    # key/certificate artifacts
    "pem", "key", "crt", "p12", "jks",
})


class DiagnosticContextError(ValueError):
    """Controlled ``controlled_context`` validation failure.

    The message is the controlled code only; offending keys, values, raw
    paths, and exception text are never echoed.
    """

    def __init__(self, code: str) -> None:
        super().__init__(code)
        self.code = code


class FrozenContext(Mapping[str, Any]):
    """Immutable, hashable, JSON-safe mapping for controlled context.

    Values are recursively deep-frozen (mappings become ``FrozenContext``,
    sequences become tuples) after validation, so callers can never mutate
    nested data or inject raw payloads after construction.  Keys are sorted
    and ``repr`` is deterministic so diagnostics stay stable.
    """

    __slots__ = ("_items", "_hash")

    def __init__(self, items: Mapping[str, Any] | None = None, **kwargs: Any) -> None:
        source = items if items is not None else kwargs
        self._items = tuple(
            sorted((key, _freeze(value)) for key, value in source.items())
        )
        self._hash = _context_hash(self._items)

    def __getitem__(self, key: str) -> Any:
        for item_key, value in self._items:
            if item_key == key:
                return value
        raise KeyError(key)

    def __iter__(self):
        return (key for key, _ in self._items)

    def __len__(self) -> int:
        return len(self._items)

    def __repr__(self) -> str:
        return repr(_plain(dict(self._items)))

    def __eq__(self, other: object) -> bool:
        if isinstance(other, Mapping):
            return dict(self._items) == dict(other)
        return NotImplemented

    def __hash__(self) -> int:
        return self._hash


def _canonical_number(value: Any) -> Any:
    """Normalize bools/integral floats to ``int`` for stable hash text."""
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, float):
        return int(value) if value.is_integer() else value
    return value


def _canonical_context(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _canonical_context(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_canonical_context(item) for item in value]
    return _canonical_number(value)


def _context_hash(items: tuple[tuple[str, Any], ...]) -> int:
    """Deterministic, process-stable Python hash for a frozen mapping."""
    canonical = json.dumps(
        _canonical_context(dict(items)), sort_keys=True, separators=(",", ":")
    )
    digest = hashlib.sha256(canonical.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") & 0x7FFFFFFFFFFFFFFF


def _freeze(value: Any) -> Any:
    """Recursively copy ``value`` into an immutable, JSON-safe structure."""
    if isinstance(value, FrozenContext):
        return value
    if isinstance(value, Mapping):
        return FrozenContext(value)
    if isinstance(value, (list, tuple)):
        return tuple(_freeze(item) for item in value)
    return value


def _plain(value: Any) -> Any:
    if isinstance(value, Mapping):
        return {key: _plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(item) for item in value]
    return value


def _is_forbidden_context_key(key: str) -> bool:
    normalized = key.lower()
    if normalized in _FORBIDDEN_CONTEXT_KEYS:
        return True
    return any(
        part in _FORBIDDEN_CONTEXT_PARTS
        for part in _FORBIDDEN_CONTEXT_WORD_RE.split(normalized)
    )


def _looks_like_raw_path(value: str) -> bool:
    """True when ``value`` looks like a raw filesystem path.

    Rooted (``/``/``\\``), drive-letter, or backslash-containing strings are
    treated as raw paths: repository-relative POSIX paths (the dedicated
    ``Diagnostic.path`` domain) never match.  Drive-relative values
    (``C:secret.kt``, ``D:tmp``) are rejected too: any ``X:`` prefix is a
    drive designator, never a controlled identifier.

    Relative path shapes are rejected even when they are not rooted: a
    slash-separated value with traversal (``.``/``..``) or empty segments,
    with a filename-like dotted component (``secret/file.kt``), or with a
    deep hierarchy (three or more segments, ``app/src/main/java/...``) is
    path-shaped rather than a controlled reason identifier.  Short
    controlled identifiers with a single namespace separator
    (``writer/helper``) are retained.

    Separator-free values are raw filenames when they are hidden/dotfiles
    (``.gitignore``, ``.env``) or when their final dot segment is a known
    file-extension suffix (``secret.kt``, ``file.json``, ``trace.log``,
    ``backup.db``, ``foo.py``, ``data.sqlite``).  Controlled identifiers
    never carry those suffixes: reason codes (``EXPORT_FAILED``), stage
    names (``stage``), and dotted identifiers the contract allows
    (``foo.bar``) remain valid.
    """
    if value.startswith(("/", "\\")):
        return True
    if _DRIVE_RE.match(value):
        return True
    if "\\" in value:
        return True
    if "/" in value:
        segments = value.split("/")
        if len(segments) > 2:
            return True
        return any(segment in ("", ".", "..") or "." in segment for segment in segments)
    return _looks_like_single_filename(value)


def _looks_like_single_filename(value: str) -> bool:
    """True when a separator-free value is a raw single filename.

    Hidden/dotfile names (``.gitignore``, ``.env``) and names whose final
    dot segment is a known file-extension suffix (``secret.kt``) are raw
    filenames; controlled identifiers (``EXPORT_FAILED``, ``stage``,
    ``foo.bar``) are not.  The extension set is explicit and curated so the
    dotted-identifier contract (``foo.bar``) is never rejected by shape
    alone.
    """
    if value.startswith("."):
        return True
    if "." in value:
        suffix = value.rsplit(".", 1)[1].lower()
        if suffix in _KNOWN_FILE_EXTENSIONS:
            return True
    return False


def _looks_like_exception_text(value: str) -> bool:
    """True when ``value`` carries exception class/traceback text."""
    return _EXCEPTION_TEXT_RE.search(value) is not None


def _validate_context_key(key: Any) -> None:
    if not isinstance(key, str):
        raise DiagnosticContextError("CONTEXT_KEY_NOT_STRING")
    if _NUL_RE.search(key) or _CONTROL_RE.search(key) or key != key.strip() or not key:
        raise DiagnosticContextError("INVALID_CONTEXT_KEY")
    if len(key) > MAX_CONTEXT_KEY:
        raise DiagnosticContextError("CONTEXT_KEY_TOO_LONG")
    if _is_forbidden_context_key(key):
        raise DiagnosticContextError("FORBIDDEN_CONTEXT_KEY")


def _validate_controlled_context(value: Any, *, depth: int = 0) -> None:
    """Recursively validate a bounded, controlled free-form context value.

    Only controlled scalars (bounded strings/numbers/bools/null) and
    mapping/list/tuple containers bounded by nesting depth and item count
    are allowed.  Strings must be stripped, free of NUL/control characters,
    bounded in length, and must not carry raw filesystem paths or exception
    text.  Forbidden payload-like key names are rejected at every level.
    Any violation raises ``DiagnosticContextError`` with a controlled code.
    """
    if depth > MAX_CONTEXT_DEPTH:
        raise DiagnosticContextError("CONTEXT_TOO_DEEP")
    if value is None or isinstance(value, bool):
        return
    if isinstance(value, str):
        if _NUL_RE.search(value) or _CONTROL_RE.search(value) or value != value.strip():
            raise DiagnosticContextError("INVALID_CONTEXT_VALUE")
        if len(value) > MAX_CONTEXT:
            raise DiagnosticContextError("STRING_TOO_LONG")
        if _looks_like_raw_path(value) or _looks_like_exception_text(value):
            raise DiagnosticContextError("RAW_CONTEXT_VALUE")
        return
    if isinstance(value, int):
        if abs(value) > MAX_CONTEXT_NUMBER:
            raise DiagnosticContextError("NUMBER_OUT_OF_RANGE")
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise DiagnosticContextError("NON_FINITE_NUMBER")
        if abs(value) > MAX_CONTEXT_NUMBER:
            raise DiagnosticContextError("NUMBER_OUT_OF_RANGE")
        return
    if isinstance(value, Mapping):
        if len(value) > MAX_CONTEXT_ITEMS:
            raise DiagnosticContextError("CONTEXT_TOO_MANY")
        for key, item in value.items():
            _validate_context_key(key)
            _validate_controlled_context(item, depth=depth + 1)
        return
    if isinstance(value, (list, tuple)):
        if len(value) > MAX_CONTEXT_ITEMS:
            raise DiagnosticContextError("CONTEXT_TOO_MANY")
        for item in value:
            _validate_controlled_context(item, depth=depth + 1)
        return
    raise DiagnosticContextError("NOT_JSONABLE")


def _validate_diagnostic_path(path: Any) -> str:
    """Validate a canonical repository-relative POSIX ``app/src`` path.

    Absolute, drive-letter, backslash, traversal (``.``/``..``), control,
    whitespace, and out-of-root paths are rejected with a controlled code.
    The offending value is never echoed in the error.
    """
    if not isinstance(path, str):
        raise DiagnosticContextError("INVALID_DIAGNOSTIC_PATH")
    if _NUL_RE.search(path) or _CONTROL_RE.search(path):
        raise DiagnosticContextError("INVALID_DIAGNOSTIC_PATH")
    if not path or any(char.isspace() for char in path):
        raise DiagnosticContextError("INVALID_DIAGNOSTIC_PATH")
    if len(path) > MAX_DIAGNOSTIC_PATH:
        raise DiagnosticContextError("INVALID_DIAGNOSTIC_PATH")
    if _DRIVE_RE.match(path) or path.startswith("/") or "\\" in path:
        raise DiagnosticContextError("INVALID_DIAGNOSTIC_PATH")
    if any(segment in ("", ".", "..") for segment in path.split("/")):
        raise DiagnosticContextError("INVALID_DIAGNOSTIC_PATH")
    if not path.startswith("app/src/"):
        raise DiagnosticContextError("INVALID_DIAGNOSTIC_PATH")
    return path


_LOCATION_KEYS = frozenset({"line", "column"})


def _validate_location(location: Any) -> dict[str, int | None]:
    """Validate a structured, finite, bounded source position.

    ``location`` must be a mapping with exactly ``line`` and ``column``
    keys: ``line`` is a positive bounded int, ``column`` is a positive
    bounded int or ``None``. Arbitrary dicts, strings, non-finite floats,
    bools, zero/negative values, and unbounded numbers are rejected with a
    controlled code; the offending value is never echoed.
    """
    if not isinstance(location, Mapping):
        raise DiagnosticContextError("INVALID_DIAGNOSTIC_LOCATION")
    if set(location) != _LOCATION_KEYS:
        raise DiagnosticContextError("INVALID_DIAGNOSTIC_LOCATION")
    line = location["line"]
    column = location["column"]
    if isinstance(line, bool) or not isinstance(line, int):
        raise DiagnosticContextError("INVALID_DIAGNOSTIC_LOCATION")
    if line < 1 or line > MAX_LOCATION_NUMBER:
        raise DiagnosticContextError("INVALID_DIAGNOSTIC_LOCATION")
    if column is not None:
        if isinstance(column, bool) or not isinstance(column, int):
            raise DiagnosticContextError("INVALID_DIAGNOSTIC_LOCATION")
        if column < 1 or column > MAX_LOCATION_NUMBER:
            raise DiagnosticContextError("INVALID_DIAGNOSTIC_LOCATION")
    return {"line": line, "column": column}


@dataclass(frozen=True)
class DeclarationRange:
    path: str
    owner_fqcn: str
    kind: str
    start_line: int
    end_line: int
    is_dao: bool
    is_abstract: bool
    body_start: int | None
    body_end: int | None
    callable_name: str | None = None
    parameters: tuple[str, ...] = ()
    source_start: int | None = None
    source_end: int | None = None


@dataclass(frozen=True)
class Diagnostic:
    """A scanner diagnostic with a stable code and separate source metadata.

    The ``code`` must be registered in the immutable diagnostic catalog
    (``scripts/ci/finding_rule_catalog.py``); an unknown code raises
    ``DiagnosticContextError`` carrying the controlled
    ``DB_DECLARATION_UNRESOLVED`` code, never the offending value.

    ``path`` must be a canonical repository-relative POSIX ``app/src`` path
    and ``location`` must be a structured ``{"line": <int>, "column": <int
    | None>}`` mapping with finite, bounded, positive values; invalid values
    are rejected with a controlled code and never echoed.

    ``controlled_context`` is validated against the F1 protocol-safe rules
    (bounded depth/items/string lengths, finite bounded numbers/bools/null
    only, no forbidden payload keys, no raw filesystem paths or exception
    text) and deep-frozen (mappings become ``FrozenContext``, sequences
    become tuples) at construction.  Invalid context raises
    ``DiagnosticContextError`` so a raw payload can never enter a scan.
    """

    code: str
    path: str | None = None
    location: Mapping[str, int | None] | None = None
    controlled_context: Mapping[str, Any] = field(default_factory=dict)

    def __post_init__(self) -> None:
        if not isinstance(self.code, str) or not _is_known_diagnostic(self.code):
            raise DiagnosticContextError("DB_DECLARATION_UNRESOLVED")
        if self.path is not None:
            object.__setattr__(self, "path", _validate_diagnostic_path(self.path))
        if self.location is not None:
            object.__setattr__(self, "location", _validate_location(self.location))
        if not isinstance(self.controlled_context, Mapping):
            raise DiagnosticContextError("CONTEXT_NOT_MAPPING")
        _validate_controlled_context(self.controlled_context)
        object.__setattr__(self, "controlled_context", FrozenContext(self.controlled_context))

    def to_dict(self) -> dict[str, Any]:
        return {
            "code": self.code,
            "path": self.path,
            "location": dict(self.location) if self.location is not None else None,
            "controlled_context": _plain(self.controlled_context),
        }


@dataclass(frozen=True)
class DaoFileScan:
    schema: str
    schema_version: int
    files_scanned: tuple[str, ...]
    dao_declarations: tuple[DeclarationRange, ...]
    skipped_dao_declaration_ranges: tuple[DeclarationRange, ...]
    helper_ranges: tuple[DeclarationRange, ...]
    diagnostics: tuple[Diagnostic, ...]
    findings: tuple[str, ...]


class ScanWriteError(RuntimeError):
    """Controlled atomic-write failure; never carries filesystem details."""

    code = "DB_DECLARATION_SCAN_WRITE_FAILED"

    def __init__(self) -> None:
        super().__init__(self.code)


def _diag(code: str, path: str | None = None, *, location: Mapping[str, int] | None = None,
          context: Mapping[str, Any] | None = None) -> Diagnostic:
    return Diagnostic(code, path, location, context or {})


def _line(source: str, offset: int) -> int:
    return source.count("\n", 0, max(0, offset)) + 1


def _range_lines(source: str, start: int, end: int) -> tuple[int, int]:
    """Return lines for a half-open ``[start, end)`` source range."""
    return _line(source, start), _line(source, max(start, end - 1))


def _reject_symlink_components(project_root: Path, supplied_root: Path) -> None:
    """Reject a supplied root reached through any symlinked component.

    The supplied root's absolute lexical path is derived without
    dereferencing symlinks (``os.path.abspath`` normalizes ``.``/``..``
    lexically but never follows links), so a symlinked *parent* of the
    supplied root is still visible to the component walk and a relative
    supplied root is inspected from its true absolute location.  Every
    existing component from the lexical path anchor (the drive root, or
    ``/`` on POSIX) down to the supplied path is tested with
    ``is_symlink()``; if any component is a symlink the path cannot be
    trusted to stay inside the project, so the controlled
    ``DB_DECLARATION_SYMLINK_OUTSIDE`` code is raised as a ``ValueError``
    (the offending path is never echoed).  A real root with no symlinked
    components is left untouched, and components that do not exist yet are
    skipped so the strict resolve can still fail closed later.
    """
    absolute = Path(os.path.abspath(supplied_root))
    current = Path(absolute.anchor)
    for part in absolute.parts[len(current.parts):]:
        current = current / part
        try:
            if current.is_symlink():
                raise ValueError("DB_DECLARATION_SYMLINK_OUTSIDE")
        except OSError:
            continue


def _approved_source(root: Any) -> tuple[Path, Path] | None:
    try:
        # A symlinked component on the path to the supplied source is
        # rejected before resolution dereferences it into a trusted path.
        # The anchor is a string (``C:\\`` on Windows, ``/`` on POSIX); it is
        # wrapped in a ``Path`` so the component walk never mixes a string
        # with ``Path / part`` (which would raise TypeError and downgrade a
        # valid root to ``DB_DECLARATION_INVALID_SOURCE``).
        _reject_symlink_components(Path(Path(root).anchor), Path(root) / _ROOT)
        supplied = Path(root).resolve(strict=True)
        if not supplied.is_dir():
            return None
    except ValueError as error:
        # Only the controlled symlink code escapes; other ValueError inputs
        # (for example embedded NUL bytes) stay fail closed as an invalid
        # source.
        if str(error) != "DB_DECLARATION_SYMLINK_OUTSIDE":
            return None
        raise
    except (OSError, RuntimeError, TypeError):
        return None
    candidates = [supplied, supplied / _ROOT]
    for source in candidates:
        try:
            source = source.resolve(strict=True)
            if source.name == "java" and source.parent.name == "main" and source.parent.parent.name == "src" and source.parent.parent.parent.name == "app":
                project = source.parents[3]
                if source == project / _ROOT:
                    return project, source
        except (OSError, RuntimeError, ValueError):
            continue
    return None


def _files(project: Path, source: Path) -> tuple[list[tuple[str, Path]], bool, set[str]]:
    result: list[tuple[str, Path]] = []
    failed = False
    symlink_diagnostics: set[str] = set()

    def onerror(_error: OSError) -> None:
        nonlocal failed
        failed = True

    try:
        for directory, directories, names in os.walk(source, topdown=True, onerror=onerror):
            directories.sort()
            for directory_name in tuple(directories):
                directory_path = Path(directory) / directory_name
                if directory_path.is_symlink():
                    symlink_diagnostics.add("DB_DECLARATION_SYMLINK_OUTSIDE")
                    directories.remove(directory_name)
            for name in sorted(names):
                # Kotlin production sources are selected by content/path, not
                # by the conventional DAO filename.
                if not name.endswith(".kt"):
                    continue
                candidate = Path(directory) / name
                try:
                    if candidate.is_symlink():
                        symlink_diagnostics.add("DB_DECLARATION_SYMLINK_OUTSIDE")
                        continue
                    real = candidate.resolve(strict=True)
                    if project / _ROOT not in real.parents:
                        symlink_diagnostics.add("DB_DECLARATION_SYMLINK_OUTSIDE")
                        continue
                    relative = candidate.relative_to(project).as_posix()
                    if candidate.is_file(): result.append((relative, candidate))
                except (OSError, ValueError):
                    failed = True
    except OSError:
        failed = True
    return result, failed, symlink_diagnostics


def _pairs(masked: str) -> dict[int, int]:
    stack: list[int] = []
    pairs: dict[int, int] = {}
    for index, char in enumerate(masked):
        if char == "{":
            stack.append(index)
        elif char == "}":
            if not stack:
                raise ValueError("unbalanced")
            opening = stack.pop()
            pairs[opening] = index
    if stack:
        raise ValueError("unbalanced")
    return pairs


@dataclass(frozen=True)
class _Scope:
    start: int
    end: int
    body_start: int
    body_end: int
    owner: str
    kind: str
    abstract: bool


def _preceding_token(masked: str, start: int) -> str | None:
    """Return the identifier token ending immediately before ``start``.

    ``masked`` is already comment/literal-blanked, so the token is
    structural source text only.  Returns ``None`` when no identifier
    immediately precedes ``start``.
    """
    cursor = start
    while cursor > 0 and masked[cursor - 1].isspace():
        cursor -= 1
    end = cursor
    while cursor > 0 and (masked[cursor - 1].isalnum() or masked[cursor - 1] == "_"):
        cursor -= 1
    return masked[cursor:end] if cursor < end else None


def _is_exclusive_class_tail(masked: str, match: re.Match) -> bool:
    """True when a generic declaration match claims a ``class`` keyword that
    belongs exclusively to an ``enum class``/``annotation class`` declaration.

    The generic pattern can reach that keyword through either route:

    * the match starts at the exclusive keyword itself, which the prefix
      group then carries (``enum class Mode`` matches with prefix
      ``enum``/``annotation``);
    * the prefix group is empty and the match starts at the bare ``class``
      keyword, so the token immediately before it in the masked source is
      ``enum`` or ``annotation``.

    Both routes are owned exclusively by ``_ENUM``/``_ANNOTATION``; letting
    the generic pattern claim them would invent a duplicate ``class`` owner
    for an enum/annotation declaration.
    """
    if _EXCLUSIVE_KINDS.intersection((match.group("prefix") or "").split()):
        return True
    return _preceding_token(masked, match.start()) in _EXCLUSIVE_KINDS


def _owner_tree(masked: str, package: str) -> tuple[_Scope, ...]:
    """Build one lexical owner tree; no later sibling brace can be borrowed."""
    pairs = _pairs(masked)
    candidates = [m for m in _DECL.finditer(masked)
                  if not _is_exclusive_class_tail(masked, m)]
    candidates += list(_ENUM.finditer(masked)) + list(_ANNOTATION.finditer(masked))
    candidates += list(_COMPANION_DECL.finditer(masked))
    candidates.sort(key=lambda m: (m.start(), -m.end()))
    result: list[_Scope] = []
    for match in candidates:
        if match.re is _COMPANION_DECL:
            kind, name = "companion", ""
        else:
            kind = match.groupdict().get("kind") or ("enum" if match.re is _ENUM else "annotation")
            name = match.group("name")
        # A declaration is a child only when its start is in a completed parent.
        parent = max((s for s in result if s.body_start <= match.start() < s.body_end),
                     key=lambda s: s.body_start, default=None)
        owner = parent.owner if kind == "companion" and parent else ((parent.owner + "." if parent else (package + "." if package else "")) + name)
        opening, header_end = _header_opening(masked, match.end(), len(masked), allow_siblings=True)
        if opening is None:
            # Bodyless declaration: its header ends at the next sibling
            # boundary — including the sibling's leading annotation/modifier
            # block — (or the enclosing ``}`` or EOF), and the scope has an
            # empty body there.  A later sibling's ``{`` is never borrowed as
            # this declaration's body, and the empty body keeps a following
            # declaration from being nested under it.
            result.append(_Scope(match.start(), header_end, header_end, header_end,
                                 owner, kind, kind in {"interface", "annotation"} or
                                 "abstract" in (match.groupdict().get("prefix") or "")))
            continue
        closing = pairs.get(opening)
        if closing is None:
            raise ValueError("owner body")
        result.append(_Scope(match.start(), closing + 1, opening + 1, closing,
                             owner, kind, kind in {"interface", "annotation"} or
                             "abstract" in (match.groupdict().get("prefix") or "")))
    return tuple(sorted(result, key=lambda s: (s.start, s.end)))


def _starts_source_line(text: str, index: int) -> bool:
    """True when ``index`` is the first token of its source line.

    Only horizontal whitespace may separate ``index`` from the preceding
    ``\\n`` (or the start of ``text``).  A token that continues the current
    declaration's own header on the same line (the ``@Inject``/``private`` of
    a primary constructor) is never mistaken for a sibling block.
    """
    cursor = index - 1
    while cursor >= 0 and text[cursor] in " \t\r":
        cursor -= 1
    return cursor < 0 or text[cursor] == "\n"


def _at_sibling_annotation_or_modifier(text: str, index: int) -> bool:
    """True when ``index`` opens the next sibling's annotation/modifier block.

    A bodyless declaration's header ends where the following declaration's
    annotation (``@Marker``) or modifier block (``override fun``, ``data
    class``, ``const val``) begins, so the block is never absorbed into the
    previous range.  ``_SIBLING_MODIFIER`` carries the full supported
    declaration-modifier set.  The block must start on its own source line:
    the same-line ``@Inject`` or ``private`` of the current declaration's own
    primary constructor is header text, never a sibling boundary.
    """
    if not _starts_source_line(text, index):
        return False
    return text[index] == "@" or _SIBLING_MODIFIER.match(text, index) is not None


def _header_opening(text: str, start: int, end: int,
                    *, allow_siblings: bool) -> tuple[int | None, int]:
    """Find a declaration body without treating header lambdas as the body.

    Braces in constructor defaults, function-type expressions, and generic
    argument expressions are nested in ``()``, ``[]``, or ``<>`` and therefore
    cannot terminate the declaration header.  ``text`` is already masked, so
    braces in strings/comments are absent as tokens as well.

    Returns ``(opening, header_end)``.  When the declaration is bodyless
    (``opening`` is None) ``header_end`` is the exact boundary where the
    header ends: the enclosing scope's ``}``, the start of the next sibling
    declaration (including its leading annotation/modifier block), or
    ``end``.  A sibling's ``{`` is never used as this declaration's body.
    """
    stack: list[str] = []
    closing = {"(": ")", "[": "]", "<": ">"}
    index = start
    while index < end:
        char = text[index]
        if char == "-" and index + 1 < end and text[index + 1] == ">":
            # A Kotlin ``->`` arrow is not a closing angle bracket: skip its
            # ``>`` without touching the delimiter stack.  This keeps
            # function-type parameters such as ``(Int) -> String`` from being
            # misread as an unbalanced generic close.
            # The loop increment below consumes the arrow's ``>``.  Advancing
            # by two here would also skip the first token after the arrow (and
            # can skip an immediately following body brace).
            index += 1
        elif char in closing:
            stack.append(closing[char])
        elif char in ")]>":
            if not stack or stack.pop() != char:
                raise ValueError("header delimiter")
        elif not stack:
            if char == "{":
                return index, index
            if char == "}" or (allow_siblings and (
                    _DIRECT_DECLARATION.match(text, index) is not None
                    or _at_sibling_annotation_or_modifier(text, index))):
                return None, index
        index += 1
    if stack:
        raise ValueError("header delimiter")
    return None, end


def _range(path: str, source: str, start: int, end: int, owner: str, kind: str,
           dao: bool, abstract: bool, body_start: int | None, body_end: int | None) -> DeclarationRange:
    start_line, end_line = _range_lines(source, start, end)
    source_end = end
    # Structural boundary discovery intentionally stops at the next sibling
    # token (or the enclosing close), which commonly means that a bodyless
    # declaration's half-open boundary includes its terminating newline.  The
    # source range, unlike the structural empty-body marker, ends at the last
    # character belonging to the declaration itself.
    if body_start is None and body_end is None:
        while source_end > start and source[source_end - 1].isspace():
            source_end -= 1
    return DeclarationRange(path, owner, kind, start_line, end_line, dao,
                            abstract, body_start, body_end,
                            source_start=start, source_end=source_end)


def _direct_functions(masked: str, source: str, path: str, start: int, end: int,
                      owner: str, is_dao: bool, pairs: dict[int, int]) -> list[DeclarationRange]:
    """Parse sibling functions, so a bodyless method never owns a sibling body."""
    result: list[DeclarationRange] = []
    base_depth = masked[:start].count("{") - masked[:start].count("}")
    for match in _FUN.finditer(masked, start, end):
        # ``fun interface`` declarations are interface owners owned
        # exclusively by the owner parser; never invent a bogus function for
        # their ``fun`` keyword.
        if re.match(r"\s*interface\b", masked[match.end():]):
            continue
        depth = masked[:match.start()].count("{") - masked[:match.start()].count("}")
        if depth != base_depth:
            continue
        opening = masked.find("(", match.end(), end)
        if opening < 0:
            raise ValueError("function header")
        close = _matching(masked, opening, "(", ")", end)
        cursor = close + 1
        brace, equals, boundary = _header_tokens(masked, cursor, end)
        if brace is None and equals is None:
            body = None
            finish = boundary
        elif brace is not None and (equals is None or brace < equals) and brace in pairs:
            body = (brace + 1, pairs[brace])
            finish = pairs[brace] + 1
        else:
            raise ValueError("function body")
        abstract = is_dao and body is None
        result.append(_range(path, source, match.start(), finish, owner, "function",
                             is_dao, abstract, body[0] if body else None,
                             body[1] if body else None))
    return result


def _matching(text: str, start: int, opening: str, closing: str, end: int) -> int:
    depth = 0
    for index in range(start, end):
        if text[index] == opening: depth += 1
        elif text[index] == closing:
            depth -= 1
            if depth == 0: return index
    raise ValueError("delimiter")


def _header_tokens(text: str, start: int, end: int) -> tuple[int | None, int | None, int]:
    stack: list[str] = []
    pairs = {"(": ")", "[": "]", "<": ">"}
    index = start
    while index < end:
        c = text[index]
        if c == "-" and index + 1 < end and text[index + 1] == ">":
            # A Kotlin ``->`` arrow is not a closing angle bracket.
            # The loop increment below consumes the arrow's ``>``; do not skip
            # the first token in the return type/body after it.
            index += 1
        elif c in pairs: stack.append(pairs[c])
        elif c in ")]>":
            if not stack or stack.pop() != c: raise ValueError("header delimiter")
        elif not stack:
            if c == "{": return index, None, index
            if c == "=": return None, index, index
            if _DIRECT_DECLARATION.match(text, index) is not None:
                return None, None, index
            # A fresh-line annotation/modifier block opens the next sibling
            # declaration and is never absorbed into this header.
            if _at_sibling_annotation_or_modifier(text, index):
                return None, None, index
        index += 1
    if stack: raise ValueError("header delimiter")
    return None, None, end


def _is_accessor_at(masked: str, index: int, scope_end: int) -> bool:
    """True when a ``get``/``set`` keyword at ``index`` starts an accessor.

    The keyword must be a standalone token followed by optional whitespace
    and ``(``: bare identifiers (``val handler = set``/``val get = value``),
    ``forget``/``reset``, and ``getValue`` never match.  The previous
    non-whitespace character must then be a
    complete value (identifier, literal, or closing delimiter), so member
    calls such as ``x.get()``, extension-function names, and references such
    as ``val x = set`` never match; an accessor is only claimed at the exact
    position where Kotlin grammar allows one.
    """
    match = re.match(r"(?:get|set)\s*\(", masked[index:scope_end])
    if not match:
        return False
    if index > 0 and (masked[index - 1].isalnum() or masked[index - 1] == "_"):
        # The keyword must start a new token: ``forget``/``reset`` are
        # identifiers that merely contain ``get``/``set``, never accessors.
        return False
    cursor = index
    while cursor > 0 and masked[cursor - 1] in " \t\r\n":
        cursor -= 1
    if cursor == 0:
        return True
    previous = masked[cursor - 1]
    return previous.isalnum() or previous == "_" or previous in ")]}>"


def _next_nonblank(text: str, index: int, end: int) -> int:
    """Return the next non-horizontal source character within ``[index, end)``."""
    while index < end and text[index] in " \t\r\n":
        index += 1
    return index


def _is_direct_scope_boundary(text: str, index: int, end: int) -> bool:
    """Whether ``index`` starts a declaration belonging to the enclosing scope."""
    if index >= end or text[index] == "}":
        return True
    return (_DIRECT_DECLARATION.match(text, index) is not None
            or _at_sibling_annotation_or_modifier(text, index))


def _expression_end(masked: str, start: int, scope_end: int) -> int:
    """Exclusive end of a top-level expression (an accessor ``=`` body).

    Parentheses, brackets and braces keep the expression alive; only a
    top-level semicolon, a direct-scope declaration/accessor boundary, the
    enclosing scope close, or the scope end terminates it.
    """
    stack: list[str] = []
    closers = {"(": ")", "[": "]", "{": "}"}
    index = start
    while index < scope_end:
        char = masked[index]
        if char == "-" and index + 1 < scope_end and masked[index + 1] == ">":
            index += 2
            continue
        if char in closers:
            stack.append(closers[char])
        elif char in ")]}":
            if not stack:
                if char == "}":
                    return index
                raise ValueError("property accessor expression")
            if stack.pop() != char:
                raise ValueError("property accessor expression")
        elif not stack:
            if char == ";":
                return index
            if char == "\n":
                cursor = _next_nonblank(masked, index + 1, scope_end)
                if cursor >= scope_end:
                    return scope_end
                if (_is_direct_scope_boundary(masked, cursor, scope_end)
                        or _is_accessor_at(masked, cursor, scope_end)):
                    return index
            if _is_accessor_at(masked, index, scope_end):
                return index
        index += 1
    if stack:
        raise ValueError("property accessor expression")
    return scope_end


def _accessor_end(masked: str, index: int, scope_end: int,
                  pairs: dict[int, int]) -> tuple[int, tuple[int, int] | None]:
    """Consume one ``get``/``set`` accessor; return its exclusive end.

    The accessor carries an optional parameter list and either an expression
    body (``= ...``) or a balanced block body; a block body span is returned
    for structural reporting.
    """
    match = re.match(r"(?:get|set)\s*\(", masked[index:])
    if not match:
        raise ValueError("property accessor")
    opening = index + match.end() - 1
    close = _matching(masked, opening, "(", ")", scope_end)
    cursor = close + 1
    while cursor < scope_end and masked[cursor] in " \t\r\n":
        cursor += 1
    if cursor >= scope_end:
        raise ValueError("property accessor")
    if masked[cursor] == "=":
        return _expression_end(masked, cursor + 1, scope_end), None
    if masked[cursor] == "{":
        closing = pairs.get(cursor)
        if closing is None:
            raise ValueError("property accessor")
        return closing + 1, (cursor + 1, closing)
    raise ValueError("property accessor")


def _property_bounds(masked: str, start: int, scope_end: int,
                     pairs: dict[int, int]) -> tuple[int, int | None, int | None]:
    """Exact structural bounds for one property declaration.

    ``start`` is the offset after the property name and ``finish`` is
    exclusive.  The walk consumes the header (type annotation and defaults),
    the initializer after ``=``, and any accessors as one declaration:

    * a semicolon or a direct-scope declaration ends a plain property
      exactly, so ``val x = 1; fun next() {}`` splits at the ``;``;
    * a same-line or following ``get``/``set`` accessor extends the
      declaration, which ends at the final accessor expression/body, never
      at the enclosing scope;
    * a balanced lambda inside the initializer is reported as the body span
      but never truncates the declaration (the next direct-scope sibling is
      the outer boundary).
    """
    stack: list[str] = []
    closers = {"(": ")", "[": "]", "<": ">"}
    index = start
    finish = scope_end
    body_start = body_end = None
    first_brace: int | None = None
    in_initializer = False
    accessor_seen = False

    def _result(boundary: int) -> tuple[int, int | None, int | None]:
        end = finish if accessor_seen else boundary
        if body_start is not None:
            return end, body_start, body_end
        if first_brace is not None:
            closing = pairs.get(first_brace)
            if closing is None:
                raise ValueError("property body")
            return end, first_brace + 1, closing
        return end, None, None

    while index < scope_end:
        char = masked[index]
        if char == "-" and index + 1 < scope_end and masked[index + 1] == ">":
            index += 2
            continue
        if char == "{":
            if not in_initializer and not stack:
                closing = pairs.get(index)
                if closing is None:
                    raise ValueError("property body")
                body_start, body_end = index + 1, closing
                index = closing + 1
                continue
            if in_initializer and first_brace is None:
                first_brace = index
            stack.append("}")
            index += 1
            continue
        if char in closers:
            stack.append(closers[char])
            index += 1
            continue
        if char in ")]}":
            if not stack:
                if char == "}":
                    return _result(index)
                raise ValueError("property delimiter")
            if stack.pop() != char:
                raise ValueError("property delimiter")
            index += 1
            continue
        # Top level: only declaration-level tokens decide the boundary.
        if _is_accessor_at(masked, index, scope_end):
            end, accessor_body = _accessor_end(masked, index, scope_end, pairs)
            finish = end
            accessor_seen = True
            if accessor_body is not None:
                body_start, body_end = accessor_body
            index = end
            continue
        if char == "=":
            in_initializer = True
            index += 1
            continue
        if char == ";":
            return _result(index)
        if char == "\n":
            cursor = _next_nonblank(masked, index + 1, scope_end)
            if cursor >= scope_end:
                return _result(scope_end)
            if _is_direct_scope_boundary(masked, cursor, scope_end):
                # Keep a balanced multiline initializer in the property
                # range, while preserving the direct sibling as the boundary.
                return _result(cursor if first_brace is not None else index)
            if _is_accessor_at(masked, cursor, scope_end):
                index = cursor
                continue
        index += 1
    if stack:
        raise ValueError("property delimiter")
    return _result(scope_end)


def _scan_file(path: str, source: str, diagnostics: list[Diagnostic]) -> tuple[list[DeclarationRange], list[DeclarationRange], list[DeclarationRange]]:
    try:
        masked = mask_kotlin_source(source)
        pairs = _pairs(masked)
        package_match = re.search(r"\bpackage\s+([A-Za-z_][A-Za-z0-9_.]*)", masked)
        package = package_match.group(1) if package_match else ""
        daos = find_dao_declarations(source, path)
        owners = _owner_tree(masked, package)
        # Keep all declaration recovery in one transaction.  A parser failure
        # must never leave a partially trusted file (or scan) behind.
        dao_names = {item.fqcn for item in daos}
        dao_ranges: list[DeclarationRange] = []
        skipped: list[DeclarationRange] = []
        helpers: list[DeclarationRange] = []

        for scope in owners:
            is_dao = scope.owner in dao_names
            item = _range(path, source, scope.start, scope.end, scope.owner,
                          "dao" if is_dao else scope.kind, is_dao, scope.abstract,
                          scope.body_start, scope.body_end)
            if is_dao:
                dao_ranges.append(item)
            else:
                helpers.append(item)

        for owner in owners:
            calls = _direct_functions(masked, source, path, owner.body_start,
                                      owner.body_end, owner.owner,
                                      owner.owner in dao_names, pairs)
            for item in calls:
                if item.is_abstract:
                    skipped.append(item)
                else:
                    helpers.append(item)

        # Top-level extension functions and properties are outside
        # find_owner_declarations and must not disappear from the caller scan.
        top_level = _direct_functions(masked, source, path, 0, len(masked),
                                      package, False, pairs)
        helpers.extend(item for item in top_level if not any(
            item.start_line == existing.start_line and item.owner_fqcn == existing.owner_fqcn
            for existing in helpers
        ))
        for match in _PROPERTY.finditer(masked):
            depth = masked[:match.start()].count("{") - masked[:match.start()].count("}")
            owner = max((s for s in owners if s.body_start <= match.start() < s.body_end),
                        key=lambda s: s.body_start, default=None)
            # Constructor parameters and header defaults sit between an
            # owner's declaration start and its body ``{``; they are not
            # properties and must never be parsed as standalone declarations.
            if any(s.start <= match.start() < s.body_start for s in owners):
                continue
            base = masked[:owner.body_start].count("{") - masked[:owner.body_start].count("}") if owner else 0
            if depth != base:
                continue
            finish, body_start, body_end = _property_bounds(
                masked, match.end(), owner.body_end if owner else len(masked), pairs)
            helpers.append(_range(path, source, match.start(), finish,
                                  owner.owner if owner else package, "property",
                                  bool(owner and owner.owner in dao_names), False,
                                  body_start, body_end))
        return dao_ranges, skipped, helpers
    except (ParserError, AccessorError, UnicodeDecodeError, OSError, ValueError):
        diagnostics.append(_diag("DB_DECLARATION_UNRESOLVED", path))
        return [], [], []


def scan_production_declarations(source_root: Any) -> DaoFileScan:
    try:
        return _scan_production_declarations(source_root)
    except DiagnosticContextError:
        # A diagnostic whose controlled_context fails protocol validation is
        # never trusted: emit the controlled unresolved diagnostic and no
        # ranges (the complete inventory is diagnostics-only).
        return DaoFileScan(SCAN_SCHEMA, SCAN_SCHEMA_VERSION, (), (), (), (),
                           (_diag("DB_DECLARATION_UNRESOLVED"),), ())


def _scan_production_declarations(source_root: Any) -> DaoFileScan:
    diagnostics: list[Diagnostic] = []
    try:
        if Path(source_root).is_symlink():
            return DaoFileScan(SCAN_SCHEMA, SCAN_SCHEMA_VERSION, (), (), (), (),
                               (_diag("DB_DECLARATION_SYMLINK_OUTSIDE"),), ())
    except DiagnosticContextError:
        # Controlled context failures are never swallowed here; they must
        # reach the scan-level fail-closed handler (controlled diagnostic,
        # cleared ranges).
        raise
    except (OSError, TypeError, ValueError):
        pass
    try:
        approved = _approved_source(source_root)
    except ValueError as error:
        # A symlinked component is a controlled fail-closed rejection: report
        # it with its own code instead of downgrading it to a generic invalid
        # source or silently dereferencing the link into a trusted scan.
        if str(error) != "DB_DECLARATION_SYMLINK_OUTSIDE":
            raise
        return DaoFileScan(SCAN_SCHEMA, SCAN_SCHEMA_VERSION, (), (), (), (),
                           (_diag("DB_DECLARATION_SYMLINK_OUTSIDE"),), ())
    if approved is None:
        return DaoFileScan(SCAN_SCHEMA, SCAN_SCHEMA_VERSION, (), (), (), (), (_diag("DB_DECLARATION_INVALID_SOURCE"),), ())
    project, source = approved
    files, walk_failed, symlink_diagnostics = _files(project, source)
    diagnostics.extend(_diag(code) for code in sorted(symlink_diagnostics))
    if walk_failed:
        diagnostics.append(_diag("DB_DECLARATION_SOURCE_UNREADABLE"))
    if not files and not walk_failed and not symlink_diagnostics:
        diagnostics.append(_diag("DB_DECLARATION_SOURCE_EMPTY"))
    daos: list[DeclarationRange] = []
    skipped: list[DeclarationRange] = []
    helpers: list[DeclarationRange] = []
    scanned: list[str] = []
    for path, candidate in files:
        try:
            # Every discovered canonical relative path must satisfy the same
            # diagnostic-path contract as a ``Diagnostic`` before its file is
            # read or any range is claimed.  Unsafe whitespace/control/path
            # segments are never inserted into ``files_scanned`` and never
            # produce ranges; the controlled invalid-source diagnostic fails
            # the complete inventory closed (the offending path is never
            # echoed).
            _validate_diagnostic_path(path)
        except DiagnosticContextError:
            diagnostics.append(_diag("DB_DECLARATION_INVALID_SOURCE"))
            continue
        # Provenance is recorded as soon as the canonical path is validated:
        # an unreadable or malformed file still appears in ``files_scanned``
        # while its diagnostic clears every range below.
        scanned.append(path)
        try:
            text = candidate.read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            diagnostics.append(_diag("DB_DECLARATION_SOURCE_UNREADABLE", path))
            continue
        if not text.strip():
            # An empty/whitespace-only Kotlin source makes the complete
            # inventory untrusted: the scanner reports it and, because any
            # diagnostic clears all ranges below, no partial range from any
            # other file can survive in the same scan.
            diagnostics.append(_diag("DB_DECLARATION_SOURCE_EMPTY", path))
            continue
        found, omitted, support = _scan_file(path, text, diagnostics)
        daos.extend(found)
        skipped.extend(omitted)
        helpers.extend(support)
    # Any source/declaration diagnostic makes the complete inventory
    # untrusted.  In particular, a valid file must not survive alongside a
    # malformed file: callers either get the whole trusted scan or diagnostics
    # only.
    if diagnostics:
        daos, skipped, helpers = [], [], []
    key = lambda item: (item.path, item.start_line, item.end_line, item.owner_fqcn, item.kind)
    def unique(items: list[DeclarationRange]) -> tuple[DeclarationRange, ...]:
        return tuple(sorted(set(items), key=key))
    diagnostic_key = lambda item: (item.code, item.path or "", repr(item.location), repr(item.controlled_context))
    def unique_diagnostics(items: list[Diagnostic]) -> tuple[Diagnostic, ...]:
        # Deduplicate by the stable key only.  Diagnostic carries a Mapping
        # context, which is not hashable, so the fail-closed scan must never
        # build a ``set(Diagnostic)`` (that would raise instead of returning
        # the diagnostics-only inventory).
        seen: set[tuple[Any, ...]] = set()
        result: list[Diagnostic] = []
        for item in items:
            marker = diagnostic_key(item)
            if marker in seen:
                continue
            seen.add(marker)
            result.append(item)
        return tuple(sorted(result, key=diagnostic_key))
    return DaoFileScan(
        SCAN_SCHEMA, SCAN_SCHEMA_VERSION, tuple(sorted(set(scanned))),
        unique(daos), unique(skipped), unique(helpers),
        unique_diagnostics(diagnostics), (),
    )


def write_scan_delta_atomic(path: Any, scan: DaoFileScan) -> None:
    if not isinstance(scan, DaoFileScan):
        raise ScanWriteError()
    temporary: str | None = None
    fd: int | None = None
    try:
        target = Path(path)
        target.parent.mkdir(parents=True, exist_ok=True)
        payload = {
            "schema": scan.schema,
            "schema_version": scan.schema_version,
            "files_scanned": list(scan.files_scanned),
            "dao_declarations": [asdict(item) for item in scan.dao_declarations],
            "skipped_dao_declaration_ranges": [asdict(item) for item in scan.skipped_dao_declaration_ranges],
            "helper_ranges": [asdict(item) for item in scan.helper_ranges],
            "diagnostics": [item.to_dict() for item in scan.diagnostics],
            "findings": list(scan.findings),
        }
        fd, temporary = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp", dir=str(target.parent), text=True)
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            fd = None
            json.dump(payload, handle, sort_keys=True, indent=2)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, target)
        temporary = None
    except Exception:
        raise ScanWriteError() from None
    finally:
        if fd is not None:
            try:
                os.close(fd)
            except OSError:
                pass
        if temporary is not None:
            try:
                os.unlink(temporary)
            except OSError:
                pass


__all__ = ["DeclarationRange", "Diagnostic", "DiagnosticContextError", "DaoFileScan", "scan_production_declarations", "write_scan_delta_atomic"]
