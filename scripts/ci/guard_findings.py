#!/usr/bin/env python3
"""
GUARD_FINDINGS -- Canonical guard-finding model, v2 fingerprints, JSON persistence.

This module is the shared, self-contained data contract for guard findings:
the CI ratchet, baseline generation, and the finding database all exchange
findings through this model so that fingerprints, ordering, and persistence
are deterministic and privacy-safe (structured, bounded fields only).

Fingerprint (v2)
----------------

A v2 fingerprint is a percent-encoded, SHA-256 derived digest that is
STABLE under incidental source movement:

  * included components (sorted by key): rule, kind, severity, path,
    symbol (qualified name), diagnostic_codes;
  * EXCLUDED components: line, column, message.

Format: ``v2:<64 lowercase hex sha256>`` over the canonical string

    <key>=<percent-encoded value>&...   (keys sorted, values percent-encoded
                                         via urllib.parse.quote(value, safe=""))

Percent-encoding with ``safe=""`` guarantees the ``=``/``&`` separators never
appear inside values, so the encoding is unambiguous and byte-for-byte
deterministic across runs and platforms.

Canonical paths
---------------

``SourceLocation.path`` must be strictly canonical:

  * forward slashes only;
  * no empty segments (no leading/trailing/double slashes);
  * no ``.`` or ``..`` segments;
  * either an absolute filesystem path (fully resolved via
    ``os.path.realpath``) or a repository-rooted relative path such as
    ``app/src/main/java/com/example/Worker.kt``.

JSON persistence
----------------

Reports serialize to a versioned JSON object. Reading validates the entire
document (keys, types, controlled constants, recomputed fingerprints) and
raises ``JsonValidationError`` on any mismatch. Writing is atomic: the JSON
is written to a temporary file in the target directory, fsynced, then
``os.replace``d over the destination.

Public API (relied on by tests and consumers):

  * Errors: ``GuardFindingsError``, ``ValidationError``,
    ``DuplicateFindingError``, ``JsonValidationError``,
    ``FingerprintError``, ``AtomicWriteError``
  * Models: ``SourceLocation``, ``CallableSymbol``, ``GuardDiagnostic``,
    ``GuardFinding``, ``FingerprintProfile``, ``GuardRunReport``,
    ``AggregatedFinding``
  * Fingerprint: ``fingerprint_v2()``, ``compute_fingerprint()``
  * Ordering/dedupe: ``sorted_findings()``, ``dedupe_findings()``
  * Aggregation: ``aggregate_findings()``, ``aggregate_report()``
  * Build/persist: ``build_report()``, ``write_report_atomic()``,
    ``load_report_json()``, ``validate_report_dict()``
"""

import hashlib
import json
import math
import os
import re
import sys
import tempfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, Iterable, Iterator, List, Mapping, Optional, Sequence, Tuple
from urllib.parse import quote

# Ensure this directory is importable so the sibling finding rule catalog can
# be imported regardless of how this module is loaded (direct script
# execution, pytest, or as a library module).
_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)
from finding_rule_catalog import (  # noqa: E402
    is_known_diagnostic,
    is_known_rule,
    known_rule,
)

# Ensure stdout/stderr can handle Unicode on Windows
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
if hasattr(sys.stderr, "reconfigure"):
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


# ------------------------------------------------------------------
# Controlled constants
# ------------------------------------------------------------------

FINGERPRINT_VERSION = 2
FINGERPRINT_PREFIX = "v2"
FINGERPRINT_ALGORITHM = "sha256"
FINGERPRINT_ENCODING = "percent-encoded"
# v2 deliberately excludes source position and free-form message text so a
# finding survives renames of its line/column and wording changes.
FINGERPRINT_EXCLUDED_FIELDS = frozenset({"line", "column", "message"})
# Exactly these fields participate in the v2 fingerprint (in this canonical
# key set; keys are sorted at encoding time for determinism).
FINGERPRINT_COMPONENT_FIELDS = (
    "rule",
    "kind",
    "severity",
    "path",
    "symbol",
    "diagnostic_codes",
)
# Every field a finding can carry; used to reject unknown excluded fields.
KNOWN_FINDING_FIELDS = frozenset(
    {
        "rule",
        "kind",
        "severity",
        "message",
        "path",
        "line",
        "column",
        "symbol",
        "diagnostic_codes",
    }
)

KIND_VIOLATION = "violation"
KIND_WARNING = "warning"
KIND_INFO = "info"
KIND_ERROR = "error"
KIND_VALUES = frozenset({KIND_VIOLATION, KIND_WARNING, KIND_INFO, KIND_ERROR})

SEVERITY_FATAL = "fatal"
SEVERITY_ERROR = "error"
SEVERITY_WARNING = "warning"
SEVERITY_INFO = "info"
SEVERITY_VALUES = frozenset(
    {SEVERITY_FATAL, SEVERITY_ERROR, SEVERITY_WARNING, SEVERITY_INFO}
)

REPORT_SCHEMA = "cost-aggregator.guard-findings"
REPORT_SCHEMA_VERSION = 2

_MAX_STRING_LENGTH = 4096
_MAX_SYMBOL_LENGTH = 1024
_FINGERPRINT_RE = re.compile(r"^v2:[0-9a-f]{64}$")
_DIAGNOSTIC_CODE_RE = re.compile(r"^[A-Z][A-Z0-9_]{2,63}$")
_GUARD_ID_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
_ISO8601_UTC_RE = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z$")
_DRIVE_RE = re.compile(r"^[A-Za-z]:")
_NUL_RE = re.compile(r"\x00")
_CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")


# ------------------------------------------------------------------
# Controlled errors
# ------------------------------------------------------------------


class GuardFindingsError(Exception):
    """Base controlled failure for this module.

    ``code`` is a controlled constant intended for structured diagnostics;
    it must be used instead of raw exception text.
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class ValidationError(GuardFindingsError):
    """A model instance failed strict field validation."""


class DuplicateFindingError(GuardFindingsError):
    """Two findings produced the same v2 fingerprint."""


class JsonValidationError(GuardFindingsError):
    """A JSON document failed schema/validation checks."""


class FingerprintError(GuardFindingsError):
    """A fingerprint could not be computed."""


class AtomicWriteError(GuardFindingsError):
    """An atomic JSON write failed."""


# ------------------------------------------------------------------
# Strict scalar validators
# ------------------------------------------------------------------


def _reject_control_characters(value: str, label: str) -> None:
    if _CONTROL_RE.search(value):
        raise ValidationError(
            "CONTROL_CHARACTER",
            f"{label} contains control characters",
        )


def validate_strict_string(
    value: Any,
    label: str,
    *,
    allow_empty: bool = False,
    max_length: int = _MAX_STRING_LENGTH,
    pattern: Optional[re.Pattern] = None,
) -> str:
    """Validate and return a strict string.

    Strict means: a ``str``, no NUL bytes, no control characters, no leading
    or trailing whitespace, bounded length, and (optionally) matching a
    controlled pattern. Returns the validated string unchanged.
    """
    if not isinstance(value, str):
        raise ValidationError("NOT_STRING", f"{label} must be a string")
    if _NUL_RE.search(value):
        raise ValidationError("NUL_BYTE", f"{label} contains a NUL byte")
    _reject_control_characters(value, label)
    if value != value.strip():
        raise ValidationError(
            "UNSTRIPPED",
            f"{label} must not have leading or trailing whitespace",
        )
    if not allow_empty and not value:
        raise ValidationError("EMPTY_STRING", f"{label} must not be empty")
    if len(value) > max_length:
        raise ValidationError(
            "STRING_TOO_LONG",
            f"{label} exceeds max length {max_length}",
        )
    if pattern is not None and not pattern.fullmatch(value):
        raise ValidationError(
            "INVALID_FORMAT",
            f"{label} does not match the required format",
        )
    return value


def canonicalize_path(value: Any) -> str:
    """Return the strict canonical form of a source path.

    Canonical means: forward slashes only, no empty segments, no ``.`` or
    ``..`` segments, and either an absolute filesystem path (fully resolved
    via ``os.path.realpath``) or a repository-rooted relative path such as
    ``app/src/main/java/...``. Non-canonical input is rejected, never
    silently repaired.
    """
    if isinstance(value, Path):
        value = str(value)
    if not isinstance(value, str):
        raise ValidationError("PATH_NOT_STRING", "path must be a string or Path")
    if _NUL_RE.search(value):
        raise ValidationError("NUL_BYTE", "path contains a NUL byte")
    _reject_control_characters(value, "path")
    if value != value.strip():
        raise ValidationError("UNSTRIPPED", "path must not have leading or trailing whitespace")
    if not value:
        raise ValidationError("EMPTY_STRING", "path must not be empty")
    if len(value) > _MAX_STRING_LENGTH:
        raise ValidationError("STRING_TOO_LONG", "path exceeds max length")

    normalized = value.replace("\\", "/")
    segments = normalized.split("/")
    for segment in segments:
        if segment in ("", ".", ".."):
            raise ValidationError(
                "NON_CANONICAL_PATH",
                f"path is not canonical (bad segment {segment!r}): {value!r}",
            )

    drive_match = _DRIVE_RE.match(normalized)
    if drive_match:
        # Drive-relative paths such as "C:foo" resolve against the current
        # working directory and are machine-dependent; reject them.
        if len(normalized) < 3 or normalized[2] != "/":
            raise ValidationError(
                "NON_CANONICAL_PATH",
                f"drive-relative paths are not canonical: {value!r}",
            )
        resolved = os.path.realpath(normalized).replace("\\", "/")
        if not os.path.isabs(resolved):
            raise ValidationError("NON_CANONICAL_PATH", f"path is not absolute: {value!r}")
        return resolved

    if normalized.startswith("/"):
        resolved = os.path.realpath(normalized).replace("\\", "/")
        return resolved

    # Repository-rooted relative canonical path (matches the ratchet's
    # rel_path convention: forward slashes, no leading "./").
    return normalized


def validate_line(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValidationError("LINE_NOT_INT", "line must be an int")
    if value < 1:
        raise ValidationError("LINE_OUT_OF_RANGE", "line must be >= 1")
    return value


def validate_column(value: Any) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValidationError("COLUMN_NOT_INT", "column must be an int")
    if value < 0:
        raise ValidationError("COLUMN_OUT_OF_RANGE", "column must be >= 0")
    return value


def _validate_kind(value: Any) -> str:
    if not isinstance(value, str) or value not in KIND_VALUES:
        raise ValidationError(
            "INVALID_KIND",
            f"kind must be one of {sorted(KIND_VALUES)}, got {value!r}",
        )
    return value


def _validate_severity(value: Any) -> str:
    if not isinstance(value, str) or value not in SEVERITY_VALUES:
        raise ValidationError(
            "INVALID_SEVERITY",
            f"severity must be one of {sorted(SEVERITY_VALUES)}, got {value!r}",
        )
    return value


# ------------------------------------------------------------------
# JSON-safety helpers and FrozenDict
# ------------------------------------------------------------------


def _ensure_jsonable(value: Any, label: str) -> None:
    """Reject values that are not strict JSON (incl. non-finite floats)."""
    if value is None or isinstance(value, (str, bool)):
        return
    if isinstance(value, int):
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValidationError(
                "NON_FINITE_NUMBER",
                f"{label} contains a non-finite float",
            )
        return
    if isinstance(value, Mapping):
        for key, item in value.items():
            validate_strict_string(key, f"{label} key", max_length=256)
            _ensure_jsonable(item, f"{label}[{key}]")
        return
    if isinstance(value, (list, tuple)):
        for index, item in enumerate(value):
            _ensure_jsonable(item, f"{label}[{index}]")
        return
    raise ValidationError("NOT_JSONABLE", f"{label} is not JSON-serializable")


def _deep_freeze(value: Any) -> Any:
    """Convert nested dicts/lists into hashable frozen equivalents."""
    if isinstance(value, Mapping) and not isinstance(value, FrozenDict):
        return FrozenDict(value)
    if isinstance(value, list):
        return tuple(_deep_freeze(item) for item in value)
    if isinstance(value, tuple):
        return tuple(_deep_freeze(item) for item in value)
    return value


def _to_jsonable(value: Any) -> Any:
    """Convert frozen containers back into plain JSON-friendly containers."""
    if isinstance(value, FrozenDict):
        return {key: _to_jsonable(item) for key, item in value.items()}
    if isinstance(value, Mapping):
        return {key: _to_jsonable(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_to_jsonable(item) for item in value]
    return value


class FrozenDict(Mapping[str, Any]):
    """Immutable, hashable, JSON-safe mapping for diagnostic ``data``.

    Backed by a sorted tuple of (key, frozen value) pairs so iteration,
    equality, and hashing are deterministic across runs.
    """

    __slots__ = ("_items", "_hash")

    def __init__(self, items: Optional[Mapping[str, Any]] = None, **kwargs: Any) -> None:
        if items is not None and kwargs:
            raise TypeError("FrozenDict accepts a mapping OR keyword arguments, not both")
        source: Mapping[str, Any] = items if items is not None else kwargs
        _ensure_jsonable(source, "diagnostic data")
        pairs = []
        for key, value in source.items():
            key = validate_strict_string(key, "data key", max_length=256)
            pairs.append((key, _deep_freeze(value)))
        pairs.sort(key=lambda pair: pair[0])
        self._items = tuple(pairs)
        canonical = json.dumps(
            _to_jsonable(dict(self._items)),
            sort_keys=True,
            separators=(",", ":"),
        )
        digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
        self._hash = hash(digest)

    def __getitem__(self, key: str) -> Any:
        for k, v in self._items:
            if k == key:
                return v
        raise KeyError(key)

    def __iter__(self) -> Iterator[str]:
        return (key for key, _ in self._items)

    def __len__(self) -> int:
        return len(self._items)

    def __hash__(self) -> int:
        return self._hash

    def __repr__(self) -> str:
        return f"FrozenDict({dict(self._items)!r})"


# ------------------------------------------------------------------
# Normalization helpers for sequence fields
# ------------------------------------------------------------------


def _normalize_diagnostics(seq: Any) -> Tuple["GuardDiagnostic", ...]:
    if seq is None:
        return ()
    if isinstance(seq, GuardDiagnostic):
        return (seq,)
    if isinstance(seq, (list, tuple)):
        result = []
        for item in seq:
            if not isinstance(item, GuardDiagnostic):
                raise ValidationError(
                    "DIAGNOSTIC_TYPE",
                    "diagnostics entries must be GuardDiagnostic",
                )
            result.append(item)
        return tuple(result)
    raise ValidationError(
        "DIAGNOSTICS_TYPE",
        "diagnostics must be a sequence of GuardDiagnostic",
    )


def _normalize_findings(seq: Any) -> Tuple["GuardFinding", ...]:
    if seq is None:
        return ()
    if isinstance(seq, GuardFinding):
        return (seq,)
    if isinstance(seq, (list, tuple)):
        result = []
        for item in seq:
            if not isinstance(item, GuardFinding):
                raise ValidationError(
                    "FINDING_TYPE",
                    "findings entries must be GuardFinding",
                )
            result.append(item)
        return tuple(result)
    raise ValidationError(
        "FINDINGS_TYPE",
        "findings must be a sequence of GuardFinding",
    )


def _normalize_location_tuple(seq: Any) -> Tuple["SourceLocation", ...]:
    if seq is None:
        return ()
    if isinstance(seq, SourceLocation):
        return (seq,)
    if isinstance(seq, (list, tuple)):
        result = []
        for item in seq:
            if not isinstance(item, SourceLocation):
                raise ValidationError(
                    "LOCATION_TYPE",
                    "locations entries must be SourceLocation",
                )
            result.append(item)
        deduped = list({loc: None for loc in result})
        return tuple(sorted(deduped, key=SourceLocation.sort_key))
    raise ValidationError(
        "LOCATIONS_TYPE",
        "locations must be a sequence of SourceLocation",
    )


def _normalize_string_tuple(
    seq: Any,
    label: str,
    *,
    pattern: Optional[re.Pattern] = None,
    sort_items: bool = True,
) -> Tuple[str, ...]:
    if seq is None:
        return ()
    if isinstance(seq, str):
        raise ValidationError(
            "STRING_TUPLE_TYPE",
            f"{label} must be a sequence of strings, not a single string",
        )
    if not isinstance(seq, (list, tuple)):
        raise ValidationError("STRING_TUPLE_TYPE", f"{label} must be a sequence")
    result = list(
        dict.fromkeys(
            validate_strict_string(item, label, max_length=256, pattern=pattern)
            for item in seq
        )
    )
    if sort_items:
        result.sort()
    return tuple(result)


# ------------------------------------------------------------------
# JSON mapping helpers
# ------------------------------------------------------------------


def _require_mapping(data: Any, label: str) -> None:
    if not isinstance(data, Mapping):
        raise JsonValidationError(
            "NOT_OBJECT",
            f"{label} must be a JSON object",
        )


def _require_keys(data: Mapping[str, Any], keys: Sequence[str], label: str) -> None:
    missing = [key for key in keys if key not in data]
    if missing:
        raise JsonValidationError(
            "MISSING_KEY",
            f"{label} is missing required key(s): {sorted(missing)}",
        )


def _reject_unknown_keys(
    data: Mapping[str, Any],
    allowed: Sequence[str],
    label: str,
) -> None:
    unknown = sorted(set(data.keys()) - set(allowed))
    if unknown:
        raise JsonValidationError(
            "UNKNOWN_KEY",
            f"{label} has unknown key(s): {unknown}",
        )


# ------------------------------------------------------------------
# Source location
# ------------------------------------------------------------------


@dataclass(frozen=True)
class SourceLocation:
    """A canonical source position.

    ``path`` is strictly canonical (see ``canonicalize_path``), ``line`` is
    ``>= 1``, and ``column`` is ``>= 0`` (``0`` means "not specified").
    """

    path: str
    line: int
    column: int = 0

    def __post_init__(self) -> None:
        object.__setattr__(self, "path", canonicalize_path(self.path))
        object.__setattr__(self, "line", validate_line(self.line))
        object.__setattr__(self, "column", validate_column(self.column))

    def to_dict(self) -> Dict[str, Any]:
        return {"path": self.path, "line": self.line, "column": self.column}

    @classmethod
    def from_dict(cls, data: Any) -> "SourceLocation":
        _require_mapping(data, "SourceLocation")
        _require_keys(data, ("path", "line", "column"), "SourceLocation")
        _reject_unknown_keys(data, ("path", "line", "column"), "SourceLocation")
        return cls(
            path=data["path"],
            line=data["line"],
            column=data["column"],
        )

    def sort_key(self) -> Tuple[str, int, int]:
        return (self.path, self.line, self.column)


# ------------------------------------------------------------------
# Callable symbol
# ------------------------------------------------------------------


@dataclass(frozen=True)
class CallableSymbol:
    """A fully qualified callable with its trailing simple name.

    Invariant: ``qualified_name`` must end with ``name`` so the short name is
    always the last dotted segment of the qualified name.
    """

    qualified_name: str
    name: str

    def __post_init__(self) -> None:
        qualified = validate_strict_string(
            self.qualified_name,
            "qualified_name",
            max_length=_MAX_SYMBOL_LENGTH,
        )
        simple = validate_strict_string(
            self.name,
            "name",
            max_length=_MAX_SYMBOL_LENGTH,
        )
        if not qualified.endswith(simple):
            raise ValidationError(
                "SYMBOL_NAME_MISMATCH",
                f"name {simple!r} must be the trailing segment of "
                f"qualified_name {qualified!r}",
            )
        object.__setattr__(self, "qualified_name", qualified)
        object.__setattr__(self, "name", simple)

    def to_dict(self) -> Dict[str, Any]:
        return {"qualified_name": self.qualified_name, "name": self.name}

    @classmethod
    def from_dict(cls, data: Any) -> "CallableSymbol":
        _require_mapping(data, "CallableSymbol")
        _require_keys(data, ("qualified_name", "name"), "CallableSymbol")
        _reject_unknown_keys(
            data, ("qualified_name", "name"), "CallableSymbol"
        )
        return cls(
            qualified_name=data["qualified_name"],
            name=data["name"],
        )

    def sort_key(self) -> Tuple[str, str]:
        return (self.qualified_name, self.name)


# ------------------------------------------------------------------
# Guard diagnostic
# ------------------------------------------------------------------


@dataclass(frozen=True)
class GuardDiagnostic:
    """A controlled, structured diagnostic.

    ``code`` must match the controlled ``[A-Z][A-Z0-9_]{2,63}`` pattern (e.g.
    ``GUARD_001``). ``data`` must be a JSON-safe mapping; it is stored as a
    hashable ``FrozenDict``.
    """

    code: str
    message: Optional[str] = None
    data: Mapping[str, Any] = field(default_factory=FrozenDict)

    def __post_init__(self) -> None:
        code = validate_strict_string(
            self.code,
            "diagnostic code",
            max_length=64,
            pattern=_DIAGNOSTIC_CODE_RE,
        )
        if not is_known_diagnostic(code):
            raise ValidationError(
                "UNKNOWN_DIAGNOSTIC",
                f"diagnostic code {code!r} is not registered in the "
                "diagnostic catalog",
            )
        message: Optional[str] = None
        if self.message is not None:
            message = validate_strict_string(
                self.message,
                "diagnostic message",
                max_length=_MAX_STRING_LENGTH,
            )
        if not isinstance(self.data, Mapping):
            raise ValidationError(
                "DATA_NOT_MAPPING",
                "diagnostic data must be a mapping",
            )
        frozen_data = (
            self.data if isinstance(self.data, FrozenDict) else FrozenDict(self.data)
        )
        object.__setattr__(self, "code", code)
        object.__setattr__(self, "message", message)
        object.__setattr__(self, "data", frozen_data)

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {"code": self.code}
        if self.message is not None:
            result["message"] = self.message
        result["data"] = _to_jsonable(self.data)
        return result

    @classmethod
    def from_dict(cls, data: Any) -> "GuardDiagnostic":
        _require_mapping(data, "GuardDiagnostic")
        _require_keys(data, ("code", "data"), "GuardDiagnostic")
        allowed = {"code", "message", "data"}
        _reject_unknown_keys(data, allowed, "GuardDiagnostic")
        return cls(
            code=data["code"],
            message=data.get("message"),
            data=data["data"],
        )

    def sort_key(self) -> Tuple[str, str, str]:
        return (self.code, self.message or "", _to_jsonable_str(self.data))


def _to_jsonable_str(value: Any) -> str:
    return json.dumps(_to_jsonable(value), sort_keys=True, separators=(",", ":"))


# ------------------------------------------------------------------
# Fingerprinting
# ------------------------------------------------------------------


def _fingerprint_components(finding: "GuardFinding") -> Tuple[Tuple[str, str], ...]:
    symbol = finding.symbol.qualified_name if finding.symbol is not None else ""
    codes = ",".join(sorted({diagnostic.code for diagnostic in finding.diagnostics}))
    components = {
        "rule": finding.rule,
        "kind": finding.kind,
        "severity": finding.severity,
        "path": finding.location.path,
        "symbol": symbol,
        "diagnostic_codes": codes,
    }
    return tuple(sorted(components.items()))


def _canonical_percent_string(components: Sequence[Tuple[str, str]]) -> str:
    return "&".join(
        f"{quote(key, safe='')}={quote(value, safe='')}" for key, value in components
    )


def fingerprint_v2(finding: "GuardFinding") -> str:
    """Compute the v2 fingerprint for a finding.

    Percent-encoded, SHA-256 digest over the sorted canonical components,
    excluding ``line``, ``column``, and ``message``. Format:
    ``v2:<64 lowercase hex sha256>``.
    """
    if not isinstance(finding, GuardFinding):
        raise FingerprintError(
            "FINDING_TYPE",
            "fingerprint_v2 requires a GuardFinding",
        )
    canonical = _canonical_percent_string(_fingerprint_components(finding))
    digest = hashlib.sha256(canonical.encode("utf-8")).hexdigest()
    return f"{FINGERPRINT_PREFIX}:{digest}"


def compute_fingerprint(finding: "GuardFinding") -> str:
    """Alias for ``fingerprint_v2`` (stable public entry point)."""
    return fingerprint_v2(finding)


# ------------------------------------------------------------------
# Guard finding
# ------------------------------------------------------------------


@dataclass(frozen=True)
class GuardFinding:
    """A single guard finding.

    ``rule`` is a controlled rule code from the rule catalog (diagnostic
    codes are rejected as rules), ``kind``/``severity`` are controlled
    constants, ``location`` is canonical, ``symbol`` is optional, and
    ``diagnostics`` carry controlled structured detail. The v2 fingerprint is
    derived from everything except line/column/message.
    """

    rule: str
    kind: str
    severity: str
    message: str
    location: SourceLocation
    symbol: Optional[CallableSymbol] = None
    diagnostics: Tuple[GuardDiagnostic, ...] = ()

    def __post_init__(self) -> None:
        rule = validate_strict_string(
            self.rule,
            "rule",
            max_length=128,
            pattern=_GUARD_ID_RE,
        )
        if is_known_diagnostic(rule):
            raise ValidationError(
                "DIAGNOSTIC_AS_FINDING",
                f"diagnostic code {rule!r} must not be emitted as a finding",
            )
        if not is_known_rule(rule):
            raise ValidationError(
                "UNKNOWN_RULE",
                f"rule {rule!r} is not registered in the rule catalog",
            )
        kind = _validate_kind(self.kind)
        severity = _validate_severity(self.severity)
        message = validate_strict_string(
            self.message,
            "message",
            max_length=_MAX_STRING_LENGTH,
        )
        if not isinstance(self.location, SourceLocation):
            raise ValidationError(
                "LOCATION_TYPE",
                "location must be a SourceLocation",
            )
        symbol = self.symbol
        if symbol is not None and not isinstance(symbol, CallableSymbol):
            raise ValidationError(
                "SYMBOL_TYPE",
                "symbol must be a CallableSymbol or None",
            )
        diagnostics = _normalize_diagnostics(self.diagnostics)
        object.__setattr__(self, "rule", rule)
        object.__setattr__(self, "kind", kind)
        object.__setattr__(self, "severity", severity)
        object.__setattr__(self, "message", message)
        object.__setattr__(self, "location", self.location)
        object.__setattr__(self, "symbol", symbol)
        object.__setattr__(self, "diagnostics", diagnostics)

    @property
    def fingerprint(self) -> str:
        return fingerprint_v2(self)

    def to_dict(self) -> Dict[str, Any]:
        result: Dict[str, Any] = {
            "rule": self.rule,
            "kind": self.kind,
            "severity": self.severity,
            "message": self.message,
            "location": self.location.to_dict(),
            "fingerprint": self.fingerprint,
        }
        if self.symbol is not None:
            result["symbol"] = self.symbol.to_dict()
        if self.diagnostics:
            result["diagnostics"] = [
                diagnostic.to_dict() for diagnostic in self.diagnostics
            ]
        return result

    @classmethod
    def from_dict(cls, data: Any) -> "GuardFinding":
        _require_mapping(data, "GuardFinding")
        _require_keys(
            data,
            ("rule", "kind", "severity", "message", "location"),
            "GuardFinding",
        )
        allowed = {
            "rule",
            "kind",
            "severity",
            "message",
            "location",
            "symbol",
            "diagnostics",
            "fingerprint",
        }
        _reject_unknown_keys(data, allowed, "GuardFinding")
        symbol = (
            CallableSymbol.from_dict(data["symbol"])
            if data.get("symbol") is not None
            else None
        )
        diagnostics = tuple(
            GuardDiagnostic.from_dict(item) for item in data.get("diagnostics", ())
        )
        finding = cls(
            rule=data["rule"],
            kind=data["kind"],
            severity=data["severity"],
            message=data["message"],
            location=SourceLocation.from_dict(data["location"]),
            symbol=symbol,
            diagnostics=diagnostics,
        )
        stored = data.get("fingerprint")
        if stored is not None:
            if not isinstance(stored, str):
                raise JsonValidationError(
                    "FINGERPRINT_TYPE",
                    "stored fingerprint must be a string",
                )
            if stored != finding.fingerprint:
                raise JsonValidationError(
                    "FINGERPRINT_MISMATCH",
                    "stored fingerprint does not match the recomputed fingerprint "
                    f"at {finding.location.path}:{finding.location.line}",
                )
        return finding

    def sort_key(self) -> Tuple[Any, ...]:
        return (
            self.fingerprint,
            self.location.path,
            self.location.line,
            self.location.column,
            self.message,
            self.rule,
            self.kind,
            self.severity,
        )


# ------------------------------------------------------------------
# Fingerprint profile
# ------------------------------------------------------------------


@dataclass(frozen=True)
class FingerprintProfile:
    """Declared fingerprint algorithm, version, and exclusions.

    The v2 profile is fixed: version 2, SHA-256, percent-encoded, and exactly
    ``{"line", "column", "message"}`` excluded. Any other profile is rejected
    so fingerprints are comparable across runs and tools.
    """

    version: int = FINGERPRINT_VERSION
    algorithm: str = FINGERPRINT_ALGORITHM
    encoding: str = FINGERPRINT_ENCODING
    excluded_fields: Tuple[str, ...] = tuple(sorted(FINGERPRINT_EXCLUDED_FIELDS))
    prefix: str = FINGERPRINT_PREFIX

    def __post_init__(self) -> None:
        if isinstance(self.version, bool) or not isinstance(self.version, int):
            raise ValidationError("PROFILE_VERSION", "version must be an int")
        if self.version < 1:
            raise ValidationError("PROFILE_VERSION", "version must be >= 1")
        if self.version != FINGERPRINT_VERSION:
            raise ValidationError(
                "UNSUPPORTED_VERSION",
                f"only fingerprint version {FINGERPRINT_VERSION} is supported",
            )
        algorithm = validate_strict_string(
            self.algorithm,
            "algorithm",
            max_length=32,
        )
        if algorithm != FINGERPRINT_ALGORITHM:
            raise ValidationError(
                "UNSUPPORTED_ALGORITHM",
                f"only algorithm {FINGERPRINT_ALGORITHM!r} is supported",
            )
        encoding = validate_strict_string(
            self.encoding,
            "encoding",
            max_length=32,
        )
        if encoding != FINGERPRINT_ENCODING:
            raise ValidationError(
                "UNSUPPORTED_ENCODING",
                f"only encoding {FINGERPRINT_ENCODING!r} is supported",
            )
        excluded = _normalize_string_tuple(
            self.excluded_fields, "excluded_fields"
        )
        excluded_set = set(excluded)
        if excluded_set != FINGERPRINT_EXCLUDED_FIELDS:
            raise ValidationError(
                "PROFILE_EXCLUDED_FIELDS",
                f"v2 fingerprint must exclude exactly "
                f"{sorted(FINGERPRINT_EXCLUDED_FIELDS)}, got {sorted(excluded_set)}",
            )
        if not excluded_set.issubset(KNOWN_FINDING_FIELDS):
            raise ValidationError(
                "UNKNOWN_EXCLUDED_FIELD",
                f"excluded_fields contains unknown finding fields: "
                f"{sorted(excluded_set - KNOWN_FINDING_FIELDS)}",
            )
        prefix = validate_strict_string(
            self.prefix,
            "prefix",
            max_length=16,
            pattern=re.compile(r"^v\d+$"),
        )
        if prefix != FINGERPRINT_PREFIX:
            raise ValidationError(
                "UNSUPPORTED_PREFIX",
                f"only prefix {FINGERPRINT_PREFIX!r} is supported",
            )
        object.__setattr__(self, "version", self.version)
        object.__setattr__(self, "algorithm", algorithm)
        object.__setattr__(self, "encoding", encoding)
        object.__setattr__(self, "excluded_fields", excluded)
        object.__setattr__(self, "prefix", prefix)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "version": self.version,
            "algorithm": self.algorithm,
            "encoding": self.encoding,
            "excluded_fields": list(self.excluded_fields),
            "prefix": self.prefix,
        }

    @classmethod
    def from_dict(cls, data: Any) -> "FingerprintProfile":
        _require_mapping(data, "FingerprintProfile")
        _require_keys(
            data,
            ("version", "algorithm", "encoding", "excluded_fields", "prefix"),
            "FingerprintProfile",
        )
        _reject_unknown_keys(
            data,
            ("version", "algorithm", "encoding", "excluded_fields", "prefix"),
            "FingerprintProfile",
        )
        return cls(
            version=data["version"],
            algorithm=data["algorithm"],
            encoding=data["encoding"],
            excluded_fields=tuple(data["excluded_fields"]),
            prefix=data["prefix"],
        )


# ------------------------------------------------------------------
# Run report
# ------------------------------------------------------------------


def _reject_duplicate_fingerprints(findings: Sequence[GuardFinding]) -> None:
    seen: Dict[str, GuardFinding] = {}
    for finding in findings:
        fingerprint = finding.fingerprint
        if fingerprint in seen:
            prior = seen[fingerprint]
            raise DuplicateFindingError(
                "DUPLICATE_FINGERPRINT",
                f"duplicate fingerprint {fingerprint!r} at "
                f"{finding.location.path}:{finding.location.line} "
                f"(first seen at {prior.location.path}:{prior.location.line})",
            )
        seen[fingerprint] = finding


@dataclass(frozen=True)
class GuardRunReport:
    """A versioned, validated report of one guard run.

    Findings are normalized, duplicate fingerprints are rejected, and
    findings are stored deterministically sorted by ``GuardFinding.sort_key``.
    The report can be serialized to canonical JSON and written atomically.
    """

    guard: str
    tool: str
    schema: str = REPORT_SCHEMA
    schema_version: int = REPORT_SCHEMA_VERSION
    fingerprint_profile: FingerprintProfile = field(default_factory=FingerprintProfile)
    findings: Tuple[GuardFinding, ...] = ()
    diagnostics: Tuple[GuardDiagnostic, ...] = ()
    created_at: Optional[str] = None

    def __post_init__(self) -> None:
        schema = validate_strict_string(self.schema, "schema", max_length=128)
        if schema != REPORT_SCHEMA:
            raise ValidationError(
                "SCHEMA_MISMATCH",
                f"schema must be {REPORT_SCHEMA!r}, got {schema!r}",
            )
        guard = validate_strict_string(
            self.guard,
            "guard",
            max_length=128,
            pattern=_GUARD_ID_RE,
        )
        tool = validate_strict_string(self.tool, "tool", max_length=256)
        if (
            isinstance(self.schema_version, bool)
            or not isinstance(self.schema_version, int)
            or self.schema_version != REPORT_SCHEMA_VERSION
        ):
            raise ValidationError(
                "SCHEMA_VERSION",
                f"schema_version must be {REPORT_SCHEMA_VERSION}",
            )
        if not isinstance(self.fingerprint_profile, FingerprintProfile):
            raise ValidationError(
                "PROFILE_TYPE",
                "fingerprint_profile must be a FingerprintProfile",
            )
        findings = _normalize_findings(self.findings)
        diagnostics = _normalize_diagnostics(self.diagnostics)
        created_at = self.created_at
        if created_at is not None:
            created_at = validate_strict_string(
                created_at,
                "created_at",
                max_length=64,
                pattern=_ISO8601_UTC_RE,
            )
        # Strict invariant: a report must never contain duplicate fingerprints.
        _reject_duplicate_fingerprints(findings)
        findings = tuple(sorted(findings, key=GuardFinding.sort_key))
        # Guard consistency: every finding rule must belong to this guard.
        for finding in findings:
            profile = known_rule(finding.rule)
            if profile is None:
                raise ValidationError(
                    "UNKNOWN_RULE",
                    f"rule {finding.rule!r} is not registered in the rule catalog",
                )
            if profile.guard != guard:
                raise ValidationError(
                    "GUARD_MISMATCH",
                    f"finding rule {finding.rule!r} belongs to guard "
                    f"{profile.guard!r}, not report guard {guard!r}",
                )
        object.__setattr__(self, "schema", schema)
        object.__setattr__(self, "guard", guard)
        object.__setattr__(self, "tool", tool)
        object.__setattr__(self, "schema_version", self.schema_version)
        object.__setattr__(self, "fingerprint_profile", self.fingerprint_profile)
        object.__setattr__(self, "findings", findings)
        object.__setattr__(self, "diagnostics", diagnostics)
        object.__setattr__(self, "created_at", created_at)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "schema": self.schema,
            "schema_version": self.schema_version,
            "guard": self.guard,
            "tool": self.tool,
            "fingerprint_profile": self.fingerprint_profile.to_dict(),
            "created_at": self.created_at,
            "findings": [finding.to_dict() for finding in self.findings],
            "diagnostics": [
                diagnostic.to_dict() for diagnostic in self.diagnostics
            ],
        }

    def to_json(self, *, indent: int = 2, sort_keys: bool = True) -> str:
        """Return canonical JSON text (deterministic key order + newline)."""
        return json.dumps(self.to_dict(), indent=indent, sort_keys=sort_keys) + "\n"

    @classmethod
    def from_dict(cls, data: Any) -> "GuardRunReport":
        _require_mapping(data, "GuardRunReport")
        _require_keys(
            data,
            (
                "schema",
                "schema_version",
                "guard",
                "tool",
                "fingerprint_profile",
                "created_at",
                "findings",
                "diagnostics",
            ),
            "GuardRunReport",
        )
        _reject_unknown_keys(
            data,
            (
                "schema",
                "schema_version",
                "guard",
                "tool",
                "fingerprint_profile",
                "created_at",
                "findings",
                "diagnostics",
            ),
            "GuardRunReport",
        )
        return cls(
            schema=data["schema"],
            schema_version=data["schema_version"],
            guard=data["guard"],
            tool=data["tool"],
            fingerprint_profile=FingerprintProfile.from_dict(
                data["fingerprint_profile"]
            ),
            created_at=data["created_at"],
            findings=tuple(GuardFinding.from_dict(item) for item in data["findings"]),
            diagnostics=tuple(
                GuardDiagnostic.from_dict(item) for item in data["diagnostics"]
            ),
        )

    def write_atomic(
        self,
        path: Any,
        *,
        mode: int = 0o644,
        sort_keys: bool = True,
    ) -> str:
        """Write this report atomically to ``path`` and return the path."""
        return write_report_atomic(self, path, mode=mode, sort_keys=sort_keys)

    def sort_key(self) -> Tuple[str, str]:
        return (self.guard, self.tool)


def build_report(
    guard: str,
    tool: str,
    findings: Sequence[GuardFinding],
    *,
    diagnostics: Sequence[GuardDiagnostic] = (),
    fingerprint_profile: Optional[FingerprintProfile] = None,
    created_at: Optional[str] = None,
    schema_version: int = REPORT_SCHEMA_VERSION,
    reject_duplicates: bool = True,
) -> GuardRunReport:
    """Build a validated ``GuardRunReport`` from raw findings.

    Duplicate fingerprints are rejected by default; pass
    ``reject_duplicates=False`` to keep the first occurrence of each
    fingerprint instead of failing.
    """
    normalized = _normalize_findings(findings)
    normalized = dedupe_findings(normalized, reject_duplicates=reject_duplicates)
    return GuardRunReport(
        schema=REPORT_SCHEMA,
        guard=guard,
        tool=tool,
        findings=normalized,
        diagnostics=diagnostics,
        fingerprint_profile=fingerprint_profile
        if fingerprint_profile is not None
        else FingerprintProfile(),
        created_at=created_at,
        schema_version=schema_version,
    )


def validate_report_dict(data: Any) -> GuardRunReport:
    """Validate a raw JSON-parsed report dict and return the model."""
    return GuardRunReport.from_dict(data)


# ------------------------------------------------------------------
# Sorting and duplicate rejection
# ------------------------------------------------------------------


def sorted_findings(
    findings: Sequence[GuardFinding],
) -> Tuple[GuardFinding, ...]:
    """Return findings sorted deterministically by ``GuardFinding.sort_key``."""
    normalized = _normalize_findings(findings)
    return tuple(sorted(normalized, key=GuardFinding.sort_key))


def dedupe_findings(
    findings: Sequence[GuardFinding],
    *,
    reject_duplicates: bool = True,
) -> Tuple[GuardFinding, ...]:
    """Reject (or dedupe to first occurrence of) duplicate fingerprints.

    Two findings are duplicates when their v2 fingerprints match, i.e. they
    share rule/kind/severity/path/symbol/diagnostic_codes regardless of
    line, column, or message. Raises ``DuplicateFindingError`` when
    ``reject_duplicates`` is true; otherwise keeps the first occurrence per
    fingerprint. Result is always deterministically sorted.
    """
    normalized = _normalize_findings(findings)
    by_fingerprint: Dict[str, List[GuardFinding]] = {}
    for finding in normalized:
        by_fingerprint.setdefault(finding.fingerprint, []).append(finding)
    duplicates = {
        fingerprint: group
        for fingerprint, group in by_fingerprint.items()
        if len(group) > 1
    }
    if reject_duplicates and duplicates:
        first_fingerprint = sorted(duplicates)[0]
        sample = duplicates[first_fingerprint][0]
        raise DuplicateFindingError(
            "DUPLICATE_FINGERPRINT",
            f"{len(duplicates)} duplicate fingerprint(s); first duplicate "
            f"{first_fingerprint!r} at {sample.location.path}:{sample.location.line}",
        )
    deduped = [group[0] for group in by_fingerprint.values()]
    return tuple(sorted(deduped, key=GuardFinding.sort_key))


# ------------------------------------------------------------------
# Aggregation
# ------------------------------------------------------------------


@dataclass(frozen=True)
class AggregatedFinding:
    """A fingerprint-grouped summary of one or more occurrences.

    ``count`` is the number of occurrences with the same v2 fingerprint;
    ``locations``/``symbols``/``diagnostic_codes`` are deduplicated and
    sorted. Deterministically sortable via ``sort_key``.
    """

    fingerprint: str
    guard_id: str
    kind: str
    severity: str
    count: int
    locations: Tuple[SourceLocation, ...] = ()
    symbols: Tuple[str, ...] = ()
    diagnostic_codes: Tuple[str, ...] = ()

    def __post_init__(self) -> None:
        fingerprint = validate_strict_string(
            self.fingerprint,
            "fingerprint",
            max_length=128,
            pattern=_FINGERPRINT_RE,
        )
        guard_id = validate_strict_string(
            self.guard_id,
            "guard_id",
            max_length=128,
            pattern=_GUARD_ID_RE,
        )
        kind = _validate_kind(self.kind)
        severity = _validate_severity(self.severity)
        if isinstance(self.count, bool) or not isinstance(self.count, int):
            raise ValidationError("AGG_COUNT", "count must be an int")
        if self.count < 1:
            raise ValidationError("AGG_COUNT", "count must be >= 1")
        locations = _normalize_location_tuple(self.locations)
        symbols = _normalize_string_tuple(self.symbols, "symbol")
        codes = _normalize_string_tuple(
            self.diagnostic_codes,
            "diagnostic_codes",
            pattern=_DIAGNOSTIC_CODE_RE,
        )
        object.__setattr__(self, "fingerprint", fingerprint)
        object.__setattr__(self, "guard_id", guard_id)
        object.__setattr__(self, "kind", kind)
        object.__setattr__(self, "severity", severity)
        object.__setattr__(self, "count", self.count)
        object.__setattr__(self, "locations", locations)
        object.__setattr__(self, "symbols", symbols)
        object.__setattr__(self, "diagnostic_codes", codes)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "fingerprint": self.fingerprint,
            "guard_id": self.guard_id,
            "kind": self.kind,
            "severity": self.severity,
            "count": self.count,
            "locations": [location.to_dict() for location in self.locations],
            "symbols": list(self.symbols),
            "diagnostic_codes": list(self.diagnostic_codes),
        }

    @classmethod
    def from_dict(cls, data: Any) -> "AggregatedFinding":
        _require_mapping(data, "AggregatedFinding")
        _require_keys(
            data,
            (
                "fingerprint",
                "guard_id",
                "kind",
                "severity",
                "count",
                "locations",
                "symbols",
                "diagnostic_codes",
            ),
            "AggregatedFinding",
        )
        _reject_unknown_keys(
            data,
            (
                "fingerprint",
                "guard_id",
                "kind",
                "severity",
                "count",
                "locations",
                "symbols",
                "diagnostic_codes",
            ),
            "AggregatedFinding",
        )
        return cls(
            fingerprint=data["fingerprint"],
            guard_id=data["guard_id"],
            kind=data["kind"],
            severity=data["severity"],
            count=data["count"],
            locations=tuple(
                SourceLocation.from_dict(item) for item in data["locations"]
            ),
            symbols=tuple(data["symbols"]),
            diagnostic_codes=tuple(data["diagnostic_codes"]),
        )

    def sort_key(self) -> Tuple[str, str, str, str]:
        return (self.fingerprint, self.guard_id, self.kind, self.severity)


def aggregate_findings(
    findings: Sequence[GuardFinding],
) -> Tuple[AggregatedFinding, ...]:
    """Group findings by v2 fingerprint and return sorted aggregates."""
    normalized = _normalize_findings(findings)
    grouped: Dict[str, List[GuardFinding]] = {}
    for finding in normalized:
        grouped.setdefault(finding.fingerprint, []).append(finding)
    aggregated: List[AggregatedFinding] = []
    for fingerprint, group in grouped.items():
        first = group[0]
        locations = tuple(
            sorted({finding.location for finding in group}, key=SourceLocation.sort_key)
        )
        symbols = tuple(
            sorted(
                {
                    finding.symbol.qualified_name
                    for finding in group
                    if finding.symbol is not None
                }
            )
        )
        codes = tuple(
            sorted(
                {
                    diagnostic.code
                    for finding in group
                    for diagnostic in finding.diagnostics
                }
            )
        )
        aggregated.append(
            AggregatedFinding(
                fingerprint=fingerprint,
                guard_id=known_rule(first.rule).guard,
                kind=first.kind,
                severity=first.severity,
                count=len(group),
                locations=locations,
                symbols=symbols,
                diagnostic_codes=codes,
            )
        )
    return tuple(sorted(aggregated, key=AggregatedFinding.sort_key))


def aggregate_report(report: GuardRunReport) -> Tuple[AggregatedFinding, ...]:
    """Convenience: aggregate all findings of a validated report."""
    if not isinstance(report, GuardRunReport):
        raise ValidationError(
            "REPORT_TYPE",
            "aggregate_report requires a GuardRunReport",
        )
    return aggregate_findings(report.findings)


# ------------------------------------------------------------------
# Atomic JSON persistence
# ------------------------------------------------------------------


def write_report_atomic(
    report: GuardRunReport,
    path: Any,
    *,
    mode: int = 0o644,
    sort_keys: bool = True,
) -> str:
    """Atomically write ``report`` as canonical JSON to ``path``.

    The JSON is written to a temporary file in the destination directory,
    flushed and fsynced, then ``os.replace``d over the target so readers never
    observe a partial file. On any failure the temporary file is removed and
    ``AtomicWriteError`` is raised. Returns the target path as a string.
    """
    if not isinstance(report, GuardRunReport):
        raise AtomicWriteError(
            "REPORT_TYPE",
            "write_report_atomic requires a GuardRunReport",
        )
    target = Path(path)
    parent = target.parent
    if not parent.is_dir():
        raise AtomicWriteError(
            "MISSING_PARENT",
            f"target parent directory does not exist: {parent}",
        )
    json_text = report.to_json(sort_keys=sort_keys)
    fd: Optional[int] = None
    temp_name: Optional[str] = None
    try:
        fd, temp_name = tempfile.mkstemp(
            prefix=f".{target.name}.",
            suffix=".tmp",
            dir=str(parent),
            text=True,
        )
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            fd = None
            handle.write(json_text)
            handle.flush()
            os.fsync(handle.fileno())
        try:
            os.chmod(temp_name, mode)
        except OSError:
            # chmod semantics are limited on Windows; the content contract is
            # what matters and os.replace is still atomic.
            pass
        os.replace(temp_name, str(target))
        temp_name = None
        return str(target)
    except (OSError, json.JSONDecodeError) as exc:
        raise AtomicWriteError(
            "WRITE_FAILED",
            f"failed to write report atomically to {target}: {exc}",
        ) from exc
    finally:
        if fd is not None:
            try:
                os.close(fd)
            except OSError:
                pass
        if temp_name is not None:
            try:
                os.unlink(temp_name)
            except OSError:
                pass


def load_report_json(path: Any) -> GuardRunReport:
    """Load, parse, and fully validate a JSON report file."""
    target = Path(path)
    if not target.is_file():
        raise JsonValidationError(
            "MISSING_FILE",
            f"report file does not exist: {target}",
        )
    try:
        text = target.read_text(encoding="utf-8")
        data = json.loads(text)
    except (OSError, UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise JsonValidationError(
            "INVALID_JSON",
            f"report file is not valid JSON: {target}",
        ) from exc
    return GuardRunReport.from_dict(data)


# ------------------------------------------------------------------
# Public API
# ------------------------------------------------------------------

__all__ = [
    # constants
    "FINGERPRINT_VERSION",
    "FINGERPRINT_PREFIX",
    "FINGERPRINT_ALGORITHM",
    "FINGERPRINT_ENCODING",
    "FINGERPRINT_EXCLUDED_FIELDS",
    "KIND_VIOLATION",
    "KIND_WARNING",
    "KIND_INFO",
    "KIND_ERROR",
    "KIND_VALUES",
    "SEVERITY_FATAL",
    "SEVERITY_ERROR",
    "SEVERITY_WARNING",
    "SEVERITY_INFO",
    "SEVERITY_VALUES",
    "REPORT_SCHEMA",
    "REPORT_SCHEMA_VERSION",
    # errors
    "GuardFindingsError",
    "ValidationError",
    "DuplicateFindingError",
    "JsonValidationError",
    "FingerprintError",
    "AtomicWriteError",
    # models
    "SourceLocation",
    "CallableSymbol",
    "GuardDiagnostic",
    "GuardFinding",
    "FingerprintProfile",
    "GuardRunReport",
    "AggregatedFinding",
    "FrozenDict",
    # fingerprinting
    "fingerprint_v2",
    "compute_fingerprint",
    # ordering / dedupe / aggregation
    "sorted_findings",
    "dedupe_findings",
    "aggregate_findings",
    "aggregate_report",
    # build / persist / validate
    "build_report",
    "validate_report_dict",
    "write_report_atomic",
    "load_report_json",
    "canonicalize_path",
    "validate_strict_string",
    "validate_line",
    "validate_column",
]


# ------------------------------------------------------------------
# Self check
# ------------------------------------------------------------------


def _self_check() -> None:
    """In-memory smoke check; writes no files and runs no external tools."""
    location = SourceLocation(
        path="app/src/main/java/com/example/Worker.kt",
        line=42,
        column=7,
    )
    symbol = CallableSymbol(
        qualified_name="com.example.Worker.doWork",
        name="doWork",
    )
    diagnostic = GuardDiagnostic(
        code="DB_SIGNATURE_UNRESOLVED",
        message="exact callable signature cannot be resolved",
        data={"count": 1, "nested": {"ok": True}},
    )
    finding = GuardFinding(
        rule="DB_UNAUTHORIZED_MUTATION",
        kind=KIND_VIOLATION,
        severity=SEVERITY_ERROR,
        message="Mutation is not owned by an exact DB policy entry",
        location=location,
        symbol=symbol,
        diagnostics=(diagnostic,),
    )
    assert finding.fingerprint.startswith("v2:")
    assert finding.fingerprint == fingerprint_v2(finding)
    report = build_report(
        "db_access",
        "verify_db_access_boundaries.py",
        (finding,),
        created_at="2026-08-10T00:00:00Z",
    )
    data = report.to_dict()
    assert GuardRunReport.from_dict(json.loads(report.to_json())) == report
    assert GuardRunReport.from_dict(data) == report
    aggregated = aggregate_findings(report.findings)
    assert len(aggregated) == 1
    assert aggregated[0].count == 1
    assert aggregated[0].fingerprint == finding.fingerprint
    print(f"guard_findings self-check OK (fingerprint={finding.fingerprint})")


if __name__ == "__main__":
    _self_check()
