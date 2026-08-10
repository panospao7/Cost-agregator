#!/usr/bin/env python3
"""Protocol-v2 guard finding model and JSON persistence (PR-F1).

Models: SourceLocation, CallableSymbol, GuardFinding, GuardDiagnostic,
GuardRunReport, FingerprintProfile, AggregatedFinding.  Report envelope uses
exactly the keys schema, schema_version, guard, findings, diagnostics,
statistics (no tool/fingerprint_profile/created_at/extra keys); the rule
catalog is authoritative and unknown rule/diagnostic codes fail closed.
Fingerprint is a temporary v2 placeholder excluding line/column/message
(hardened in F1b); it is computed on demand and never serialized in JSON.
Public API: validate_report, build_report, fingerprint_finding,
aggregate_findings, canonicalize_report, load_report_json, load_report,
write_report_atomic, canonical_path, is_known_guard, KNOWN_GUARDS,
DIAGNOSTIC_SIGNATURE_UNRESOLVED, unresolved_symbol_diagnostic,
ProtocolFailure, FrozenDict.

F1 privacy/API hardening: ``controlled_context`` and ``statistics`` are
validated recursively (controlled scalars only, bounded string/number
magnitudes, bounded dict/list depth and item count, forbidden payload-like
key names); report guard names must be registered in the canonical guard
catalog regardless of the report's findings/diagnostics content (a report
claiming an unregistered guard fails closed with the controlled
``UNKNOWN_GUARD`` error whether it is empty, findings-only, or
diagnostics-only); ``from_dict`` validates the top-level schema,
schema_version, and registered guard *before* touching findings/diagnostics
content, so an unregistered guard (or schema/version mismatch) is reported
even when the content is malformed or unknown, and ``from_dict`` requires
list-typed findings/diagnostics and mapping statistics before iterating;
fingerprints follow the catalog-declared identity-field order (path, full
callable symbol identity, then declared ``identity.*`` fields) and never
lexicographically re-sort identity fields; public load /
``from_dict`` / atomic-write failures use controlled error codes and never
echo raw filesystem paths, exception messages, or user values.  Atomic
writes always serialize the canonicalized report.

Unknown blocking symbol contract: a DB policy finding (a rule whose profile
requires ``symbol.*`` identity fields) whose ``CallableSymbol`` has
``kind == unknown`` or an unresolved required signature (missing/empty
owner, name, or parameters) is never serialized as a baseline-able
``GuardFinding``; constructing it fails report validation with the
controlled ``UNRESOLVED_SYMBOL_BLOCKING`` error and directs the emitter to
the controlled ``DB_SIGNATURE_UNRESOLVED`` diagnostic (protocol /
infrastructure exit-2 path).  ``unresolved_symbol_diagnostic(...)`` is the
explicit conversion helper that builds that controlled diagnostic (and
rejects resolved symbols and unregistered codes with ``ProtocolFailure``).

Unknown rule contract: a rule code that is not registered in the rule
catalog is a direct protocol error (exit 2).  Constructing a
``GuardFinding`` with it raises the catalog-backed ``UNKNOWN_RULE`` error
(``ProtocolFailure``); ``UNKNOWN_RULE`` is itself registered in the
diagnostic catalog so it is a controlled code and is never baseline-able.

Deep immutability contract: every mapping stored in ``GuardFinding.identity``,
``GuardDiagnostic.controlled_context``, and ``GuardRunReport.statistics`` is
recursively frozen to ``FrozenDict`` (keys sorted, values deep-frozen) and
every sequence to an immutable tuple *after* validation, so callers can
never mutate nested data after construction or inject raw payloads.  The
frozen structures remain strictly JSON-serializable (``_plain``) with
deterministic ordering and hashing.  FrozenDict hashing canonicalizes
numeric values (booleans and integral floats to ``int``) so mappings equal
under Python mapping semantics (``1 == 1.0``, ``-0.0 == 0.0``,
``True == 1``) hash equally within and across processes, without changing
the serialized report JSON or stored values.
"""

import hashlib
import json
import math
import os
import re
import sys
import tempfile
from contextlib import suppress
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Dict, List, Mapping, Optional, Sequence, Tuple
from urllib.parse import quote

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)
from finding_rule_catalog import (  # noqa: E402
    GUARD_DB_ACCESS,
    MULTIPLICITY_COUNT,
    is_known_diagnostic,
    is_known_rule,
    known_rule,
)

REPORT_SCHEMA = "cost-aggregator.guard-findings"
REPORT_SCHEMA_VERSION = 2
FINGERPRINT_VERSION = 2
FINGERPRINT_PREFIX = "v2"
NONE_MARKER = "<none>"
SEVERITY_ERROR = "error"
SEVERITY_WARNING = "warning"
SEVERITY_VALUES = frozenset({SEVERITY_ERROR, SEVERITY_WARNING})
KIND_FUNCTION = "function"
KIND_CONSTRUCTOR = "constructor"
KIND_PROPERTY_GETTER = "property_getter"
KIND_PROPERTY_SETTER = "property_setter"
KIND_TOP_LEVEL_FUNCTION = "top_level_function"
KIND_INITIALIZER = "initializer"
KIND_UNKNOWN = "unknown"
KIND_VALUES = frozenset({KIND_FUNCTION, KIND_CONSTRUCTOR, KIND_PROPERTY_GETTER, KIND_PROPERTY_SETTER, KIND_TOP_LEVEL_FUNCTION, KIND_INITIALIZER, KIND_UNKNOWN})
# Controlled infrastructure diagnostic emitted when a DB policy finding's
# callable signature cannot be resolved (kind == unknown or missing/empty
# required signature components).  Registered in the diagnostic catalog; a
# report carrying it takes the protocol/infrastructure (exit 2) path and it
# is never baseline-able.
DIAGNOSTIC_SIGNATURE_UNRESOLVED = "DB_SIGNATURE_UNRESOLVED"
# Bounded length limits (protocol v2, section 5.2).
MAX_PATH = 500
MAX_SYMBOL = 300
MAX_IDENTITY = 300
MAX_CONTEXT = 300
MAX_MESSAGE = 500
MAX_CODE = 100
MAX_GUARD = 128
MAX_KEY = 64
MAX_FINDINGS = 100_000
# Bounded structure limits for recursive free-form context/statistics values
# (protocol v2, section 5.2 extension).
MAX_CONTEXT_DEPTH = 4
MAX_CONTEXT_ITEMS = 256
MAX_NUMBER = 10 ** 18
# Key names that must never appear in free-form context (controlled_context /
# statistics): they are the names guards use for raw source, exception, and
# user payload content. ``_FORBIDDEN_CONTEXT_PARTS`` extends the exact list
# with word components such as ``payload`` so payload-smuggling keys like
# ``user_payload`` are rejected too.
FORBIDDEN_CONTEXT_KEYS = frozenset({
    "message", "exception", "stack", "trace", "source", "sql", "ocr", "path",
})
_FORBIDDEN_CONTEXT_PARTS = frozenset({
    "message", "exception", "stack", "trace", "source", "sql", "ocr", "path",
    "payload",
})
_FORBIDDEN_CONTEXT_WORD_RE = re.compile(r"[^a-z0-9]+")
_NUL_RE = re.compile(r"\x00")
_CONTROL_RE = re.compile(r"[\x00-\x1f\x7f]")
_DRIVE_RE = re.compile(r"^[A-Za-z]:")
_CODE_RE = re.compile(r"^[A-Z][A-Z0-9_]{2,63}$")
_GUARD_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_.-]{0,127}$")
_FP_RE = re.compile(r"^v2\|[^\s].*$")

# Canonical report guard catalog (PR-F1): the set of report guard names that
# may appear in a protocol-v2 report envelope. It is derived from the
# rule/diagnostic catalog contract in finding_rule_catalog.py -- a guard is
# registered only when the catalog declares rules/diagnostics for it. Reports
# claiming an unregistered guard fail closed even when they contain no
# findings, so an empty report cannot bypass the guard registry.
KNOWN_GUARDS = frozenset({GUARD_DB_ACCESS})


def is_known_guard(guard):
    """Return ``True`` when ``guard`` is a registered report guard name."""
    return isinstance(guard, str) and guard in KNOWN_GUARDS

class GuardFindingsError(Exception):
    """Base controlled failure with a controlled ``code``."""
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message

class ValidationError(GuardFindingsError): pass
class JsonValidationError(GuardFindingsError): pass
class DuplicateFindingError(GuardFindingsError): pass
class FingerprintError(GuardFindingsError): pass
class AtomicWriteError(GuardFindingsError): pass

class ProtocolFailure(ValidationError):
    """Controlled protocol/infrastructure failure that maps to exit 2.

    Raised for contract violations that must never produce a baseline-able
    finding: unresolved blocking symbols (``UNRESOLVED_SYMBOL_BLOCKING``),
    unknown/unregistered rule codes (``UNKNOWN_RULE``), and attempts to emit
    an unregistered diagnostic (``UNKNOWN_DIAGNOSTIC``).  It subclasses
    ``ValidationError`` so existing validation call sites are unaffected;
    emitters and the ratchet catch it to take the exit-2 path.
    """

def _bounded(value, label, *, max_length=MAX_PATH, pattern=None):
    """Validate a strict bounded string (no NUL/control/unstripped content)."""
    if not isinstance(value, str):
        raise ValidationError("NOT_STRING", f"{label} must be a string")
    if _NUL_RE.search(value):
        raise ValidationError("NUL_BYTE", f"{label} contains a NUL byte")
    if _CONTROL_RE.search(value):
        raise ValidationError("CONTROL_CHARACTER", f"{label} contains control characters")
    if value != value.strip():
        raise ValidationError("UNSTRIPPED", f"{label} must not have leading or trailing whitespace")
    if not value:
        raise ValidationError("EMPTY_STRING", f"{label} must not be empty")
    if len(value) > max_length:
        raise ValidationError("STRING_TOO_LONG", f"{label} exceeds max length {max_length}")
    if pattern is not None and not pattern.fullmatch(value):
        raise ValidationError("INVALID_FORMAT", f"{label} does not match the required format")
    return value

def _opt(value, fn, *args, **kwargs):
    return None if value is None else fn(value, *args, **kwargs)

def _pos(value, label):
    if isinstance(value, bool) or not isinstance(value, int):
        raise ValidationError("NOT_INT", f"{label} must be an int")
    if value < 1:
        raise ValidationError("OUT_OF_RANGE", f"{label} must be >= 1")
    return value

def _kind(value):
    if not isinstance(value, str) or value not in KIND_VALUES:
        raise ValidationError("INVALID_KIND", f"kind must be one of {sorted(KIND_VALUES)}")
    return value

def _severity(value):
    if not isinstance(value, str) or value not in SEVERITY_VALUES:
        raise ValidationError("INVALID_SEVERITY", f"severity must be one of {sorted(SEVERITY_VALUES)}")
    return value

def canonical_path(value):
    """Canonical repository-relative POSIX path; backslashes normalized, traversal rejected."""
    if isinstance(value, Path):
        value = str(value)
    if not isinstance(value, str):
        raise ValidationError("PATH_NOT_STRING", "path must be a string or Path")
    value = _bounded(value, "path", max_length=MAX_PATH)
    normalized = value.replace("\\", "/")
    if _DRIVE_RE.match(normalized) or normalized.startswith("/"):
        raise ValidationError("NON_CANONICAL_PATH", "absolute/drive paths are not canonical")
    if any(segment in ("", ".", "..") for segment in normalized.split("/")):
        raise ValidationError("NON_CANONICAL_PATH", "path is not canonical")
    return normalized

def _jsonable(value, label):
    """Reject values that are not strict JSON (incl. non-finite floats)."""
    if value is None or isinstance(value, (str, bool, int)):
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValidationError("NON_FINITE_NUMBER", f"{label} contains a non-finite float")
        return
    if isinstance(value, Mapping):
        for key, item in value.items():
            _bounded(key, f"{label} key", max_length=MAX_KEY)
            _jsonable(item, f"{label}.{key}")
        return
    if isinstance(value, (list, tuple)):
        for index, item in enumerate(value):
            _jsonable(item, f"{label}[{index}]")
        return
    raise ValidationError("NOT_JSONABLE", f"{label} is not JSON-serializable")

def _is_forbidden_context_key(key):
    """True when ``key`` is (or contains) a forbidden free-form key name.

    Exact names from ``FORBIDDEN_CONTEXT_KEYS`` and word components such as
    ``payload`` are matched on the lowercased key so payload-smuggling keys
    (``user_payload``, ``raw_message``) cannot slip through.
    """
    normalized = key.lower()
    if normalized in FORBIDDEN_CONTEXT_KEYS:
        return True
    return any(
        part in _FORBIDDEN_CONTEXT_PARTS
        for part in _FORBIDDEN_CONTEXT_WORD_RE.split(normalized)
    )

def _validate_freeform(value, label, *, depth=0):
    """Recursively validate a bounded, controlled free-form value.

    Used for ``GuardDiagnostic.controlled_context`` and
    ``GuardRunReport.statistics``.  Only controlled scalars (bounded
    strings/numbers/bools/null) and dict/list containers bounded by nesting
    depth and item count are allowed.  Strings must be stripped and free of
    NUL/control characters; numbers must be finite and bounded; forbidden
    payload-like key names are rejected at every level so raw source,
    exception, and user values cannot be smuggled into structured reports.

    ``label`` is a generic field category (``controlled_context`` or
    ``statistics``); it is never extended with user-controlled key names or
    list indices during recursion.  Exception messages therefore interpolate
    only the category and bounded limits, never raw context keys, nested
    labels, or payload values, so hostile keys/values from an untrusted
    report cannot leak into public error text.
    """
    if depth > MAX_CONTEXT_DEPTH:
        raise ValidationError(
            "CONTEXT_TOO_DEEP",
            f"{label} exceeds max nesting depth {MAX_CONTEXT_DEPTH}",
        )
    if value is None or isinstance(value, bool):
        return
    if isinstance(value, str):
        # String-value defects (NUL/control/whitespace) share one controlled
        # code with a generic message; the offending raw text is never echoed.
        if _NUL_RE.search(value) or _CONTROL_RE.search(value) or value != value.strip():
            raise ValidationError(
                "INVALID_CONTEXT_VALUE",
                f"{label} contains a non-conforming string value",
            )
        if len(value) > MAX_CONTEXT:
            raise ValidationError(
                "STRING_TOO_LONG",
                f"{label} value exceeds max length {MAX_CONTEXT}",
            )
        return
    if isinstance(value, int):
        if abs(value) > MAX_NUMBER:
            raise ValidationError(
                "NUMBER_OUT_OF_RANGE",
                f"{label} value exceeds max magnitude {MAX_NUMBER}",
            )
        return
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValidationError(
                "NON_FINITE_NUMBER",
                f"{label} contains a non-finite number",
            )
        if abs(value) > MAX_NUMBER:
            raise ValidationError(
                "NUMBER_OUT_OF_RANGE",
                f"{label} value exceeds max magnitude {MAX_NUMBER}",
            )
        return
    if isinstance(value, Mapping):
        if len(value) > MAX_CONTEXT_ITEMS:
            raise ValidationError(
                "CONTEXT_TOO_MANY",
                f"{label} exceeds max items {MAX_CONTEXT_ITEMS}",
            )
        for key, item in value.items():
            # Key names are bounded without ever echoing the raw key; a
            # forbidden payload-like key is reported by code alone.
            key = _bounded(key, f"{label} key", max_length=MAX_KEY)
            if _is_forbidden_context_key(key):
                raise ValidationError(
                    "FORBIDDEN_CONTEXT_KEY",
                    f"{label} uses a forbidden key",
                )
            _validate_freeform(item, label, depth=depth + 1)
        return
    if isinstance(value, (list, tuple)):
        if len(value) > MAX_CONTEXT_ITEMS:
            raise ValidationError(
                "CONTEXT_TOO_MANY",
                f"{label} exceeds max items {MAX_CONTEXT_ITEMS}",
            )
        for item in value:
            _validate_freeform(item, label, depth=depth + 1)
        return
    raise ValidationError("NOT_JSONABLE", f"{label} contains a non-JSON value")

def _plain(value):
    if isinstance(value, Mapping):
        return {key: _plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_plain(item) for item in value]
    return value

def _canonical_number(value):
    """Normalize a validated number for FrozenDict hash canonicalization.

    Python mapping equality treats ``1 == 1.0``, ``-0.0 == 0.0``, and
    booleans as their integer values (``True == 1``, ``False == 0``), so the
    hash canonical text must collapse those equal forms: an equal mapping
    whose values differ only by numeric type would otherwise hash differently
    (violating the dict hash contract).  Booleans and integral floats are
    therefore normalized to ``int`` (``int`` is exact for every integral
    float, including ``-0.0`` -> ``0``) while unequal values stay distinct.
    Non-integral floats are returned unchanged; their JSON text is the
    deterministic shortest round-trip repr.
    """
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, float):
        return int(value) if value.is_integer() else value
    return value

def _canonical_plain(value):
    """Deterministic normalized copy used only for ``FrozenDict`` hashing.

    Mirrors ``_plain`` but normalizes numbers via ``_canonical_number`` so
    values equal under Python mapping semantics produce identical canonical
    text (and therefore identical sha256-based hashes) while unequal values
    stay distinct.  The result is used only to derive the hash: it is never
    stored (deep immutability keeps the original values) and never serialized
    to report JSON (``_plain`` / ``to_dict`` are used for that), so report
    JSON semantics and finding fingerprints are unchanged.
    """
    if isinstance(value, Mapping):
        return {key: _canonical_plain(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_canonical_plain(item) for item in value]
    return _canonical_number(value)

def _stable_hash(canonical):
    """Deterministic, process-stable Python hash for a canonical JSON string.

    Python's built-in ``hash(str)`` is randomized per interpreter process
    (``PYTHONHASHSEED``), so hashing the sha256 hex digest directly would
    make equal FrozenDicts hash differently in different processes.  Instead
    the first 8 bytes of the sha256 digest are interpreted as a big-endian
    unsigned integer and masked to the non-negative signed 64-bit range,
    which is a valid Python hash value in every process (never ``-1``, the
    reserved error sentinel) and identical regardless of ``PYTHONHASHSEED``.
    """
    digest = hashlib.sha256(canonical.encode("utf-8")).digest()
    return int.from_bytes(digest[:8], "big") & 0x7FFFFFFFFFFFFFFF

class FrozenDict(Mapping[str, Any]):
    """Immutable, hashable, JSON-safe mapping sorted by key.

    Values are recursively deep-frozen via ``_freeze``: nested mappings
    become ``FrozenDict`` (keys sorted at every level) and nested
    sequences become tuples.  The stored structure is therefore fully
    immutable, deterministic, and JSON-serializable; callers cannot mutate
    nested data after construction.  The hash is derived deterministically
    from the sha256 digest of the canonical JSON of a numeric-normalized
    copy (``_canonical_plain``), so values equal under Python mapping
    semantics (``1`` vs ``1.0``, ``-0.0`` vs ``0.0``, ``True`` vs ``1``)
    hash equally while unequal values keep distinct hashes, within and
    across interpreter processes.  Stored values are never normalized:
    deep immutability preserves the caller's original values and the
    serialized JSON (``_plain``) is unchanged.
    """
    __slots__ = ("_items", "_hash")
    def __init__(self, items=None, **kwargs):
        if items is not None and kwargs:
            raise TypeError("FrozenDict accepts a mapping OR keywords, not both")
        source = items if items is not None else kwargs
        _jsonable(source, "mapping")
        self._items = tuple(sorted(
            (_bounded(k, "key", max_length=MAX_KEY), _freeze(v))
            for k, v in source.items()
        ))
        canonical = json.dumps(_canonical_plain(dict(self._items)), sort_keys=True, separators=(",", ":"))
        self._hash = _stable_hash(canonical)
    def __getitem__(self, key):
        for k, v in self._items:
            if k == key:
                return v
        raise KeyError(key)
    def __iter__(self):
        return (key for key, _ in self._items)
    def __len__(self):
        return len(self._items)
    def __hash__(self):
        return self._hash

def _freeze(value):
    """Recursively copy ``value`` into an immutable, JSON-safe structure.

    Mappings become ``FrozenDict`` (keys sorted, values deep-frozen),
    lists and tuples become tuples (order preserved, items deep-frozen),
    and scalars pass through unchanged.  ``FrozenDict`` instances are
    returned as-is because they are already deeply frozen.  This is applied
    only *after* the value has been validated (``_validate_freeform`` or
    per-field ``_bounded`` checks), so no raw payload can enter a report.
    """
    if isinstance(value, FrozenDict):
        return value
    if isinstance(value, Mapping):
        return FrozenDict(value)
    if isinstance(value, (list, tuple)):
        return tuple(_freeze(item) for item in value)
    return value

def _check_keys(data, required, allowed, label):
    if not isinstance(data, Mapping):
        raise JsonValidationError("NOT_OBJECT", f"{label} must be a JSON object")
    missing = sorted(set(required) - set(data))
    if missing:
        raise JsonValidationError("MISSING_KEY", f"{label} is missing required key(s): {missing}")
    unknown = sorted(set(data) - set(allowed))
    if unknown:
        # Never echo user-controlled key names: they may contain raw or
        # unbounded content from a hostile report file.
        raise JsonValidationError("UNKNOWN_KEY", f"{label} has {len(unknown)} unknown key(s)")

@dataclass(frozen=True)
class SourceLocation:
    """Canonical source position; every present field is >= 1."""
    line: int
    column: Optional[int] = None
    end_line: Optional[int] = None
    end_column: Optional[int] = None
    def __post_init__(self):
        object.__setattr__(self, "line", _pos(self.line, "line"))
        object.__setattr__(self, "column", _opt(self.column, _pos, "column"))
        object.__setattr__(self, "end_line", _opt(self.end_line, _pos, "end_line"))
        object.__setattr__(self, "end_column", _opt(self.end_column, _pos, "end_column"))
    def to_dict(self):
        return {key: getattr(self, key) for key in ("line", "column", "end_line", "end_column") if getattr(self, key) is not None}
    @classmethod
    def from_dict(cls, data):
        _check_keys(data, ("line",), ("line", "column", "end_line", "end_column"), "SourceLocation")
        return cls(**{key: data.get(key) for key in ("line", "column", "end_line", "end_column")})

@dataclass(frozen=True)
class CallableSymbol:
    """Fully qualified callable with bounded components and fixed kind set.

    ``parameters`` must be a list or tuple of bounded strings (never None, a
    mapping, an integer, a single string, or an arbitrary iterable); element
    order is preserved so fingerprints stay parameter-order sensitive.
    """
    owner: str
    name: str
    receiver: Optional[str] = None
    parameters: Tuple[str, ...] = ()
    kind: str = KIND_FUNCTION
    def __post_init__(self):
        parameters = self.parameters
        if not isinstance(parameters, (list, tuple)):
            raise ValidationError("INVALID_PARAMETERS", "symbol.parameters must be a list or tuple of strings")
        object.__setattr__(self, "owner", _bounded(self.owner, "symbol.owner", max_length=MAX_SYMBOL))
        object.__setattr__(self, "name", _bounded(self.name, "symbol.name", max_length=MAX_SYMBOL))
        object.__setattr__(self, "receiver", _opt(self.receiver, _bounded, "symbol.receiver", max_length=MAX_SYMBOL))
        object.__setattr__(self, "parameters", tuple(_bounded(p, "symbol.parameters", max_length=MAX_SYMBOL) for p in parameters))
        object.__setattr__(self, "kind", _kind(self.kind))
    def to_dict(self):
        return {"owner": self.owner, "name": self.name, "receiver": self.receiver, "parameters": list(self.parameters), "kind": self.kind}
    @classmethod
    def from_dict(cls, data):
        _check_keys(data, ("owner", "name", "receiver", "parameters", "kind"), ("owner", "name", "receiver", "parameters", "kind"), "CallableSymbol")
        return cls(owner=data["owner"], name=data["name"], receiver=data["receiver"], parameters=data["parameters"], kind=data["kind"])

@dataclass(frozen=True)
class GuardDiagnostic:
    """Controlled infrastructure diagnostic; never baseline-able."""
    code: str
    path: Optional[str] = None
    symbol: Optional[str] = None
    controlled_context: Mapping[str, Any] = field(default_factory=FrozenDict)
    def __post_init__(self):
        code = _bounded(self.code, "diagnostic code", max_length=MAX_CODE, pattern=_CODE_RE)
        if not is_known_diagnostic(code):
            raise ValidationError("UNKNOWN_DIAGNOSTIC", "diagnostic code is not registered in the diagnostic catalog")
        if not isinstance(self.controlled_context, Mapping):
            raise ValidationError("CONTEXT_NOT_MAPPING", "controlled_context must be a mapping")
        _validate_freeform(self.controlled_context, "controlled_context")
        context = FrozenDict(self.controlled_context)
        object.__setattr__(self, "code", code)
        object.__setattr__(self, "path", canonical_path(self.path) if self.path is not None else None)
        object.__setattr__(self, "symbol", _opt(self.symbol, _bounded, "diagnostic symbol", max_length=MAX_SYMBOL))
        object.__setattr__(self, "controlled_context", context)
    def to_dict(self):
        return {"code": self.code, "path": self.path, "symbol": self.symbol, "controlled_context": _plain(self.controlled_context)}
    @classmethod
    def from_dict(cls, data):
        _check_keys(data, ("code", "path", "symbol", "controlled_context"), ("code", "path", "symbol", "controlled_context"), "GuardDiagnostic")
        return cls(code=data["code"], path=data["path"], symbol=data["symbol"], controlled_context=data["controlled_context"])

def _identity(value, profile):
    """Validate identity: exactly the profile's ``identity.*`` fields, bounded strings."""
    if not isinstance(value, Mapping):
        raise ValidationError("IDENTITY_NOT_MAPPING", "identity must be a mapping")
    declared = {f[9:] for f in profile.identity_fields if f.startswith("identity.")}
    if not declared:
        raise ValidationError("IDENTITY_UNSUPPORTED", f"rule {profile.code!r} declares no identity.* fields")
    result = {}
    for key, item in value.items():
        key = _bounded(key, "identity key", max_length=MAX_KEY)
        if key not in declared:
            raise ValidationError("IDENTITY_UNDECLARED", f"identity key is not declared by rule {profile.code!r}")
        result[key] = _bounded(item, f"identity.{key}", max_length=MAX_IDENTITY)
    missing = sorted(declared - set(result))
    if missing:
        raise ValidationError("IDENTITY_MISSING", f"identity is missing declared field(s): {missing}")
    return FrozenDict(result)

def _is_unresolved_symbol(symbol):
    """Return ``True`` when ``symbol`` cannot back a baseline-able finding.

    A callable symbol is unresolved when its kind is ``unknown`` (allowed
    only in non-blocking discovery scans) or a required signature component
    (owner, name, parameters) is missing/empty.  Blocking findings that
    require symbol identity must reject such symbols with the
    ``UNRESOLVED_SYMBOL_BLOCKING`` protocol/infrastructure failure (exit 2)
    and the emitter must create the controlled ``DB_SIGNATURE_UNRESOLVED``
    diagnostic instead of serializing a baseline-able finding.
    """
    return (
        symbol.kind == KIND_UNKNOWN
        or not symbol.owner
        or not symbol.name
        or not symbol.parameters
    )

def unresolved_symbol_diagnostic(symbol=None, *, path=None, code=DIAGNOSTIC_SIGNATURE_UNRESOLVED, **context):
    """Explicit conversion contract from an unresolved blocking symbol to the
    controlled ``DB_SIGNATURE_UNRESOLVED`` infrastructure diagnostic.

    Emitters call this when a blocking finding's ``CallableSymbol`` cannot
    back a baseline-able ``GuardFinding`` (``kind == unknown`` or a
    missing/empty owner, name, or parameters).  The returned diagnostic is
    validated and deep-frozen like any ``GuardDiagnostic``: ``path`` must be
    canonical, ``symbol`` is a bounded controlled string (derived from
    ``symbol.owner``/``symbol.name`` when a ``CallableSymbol`` is passed),
    and ``**context`` must be bounded controlled scalars/containers.  Any
    report containing it takes the protocol/infrastructure exit-2 path and
    it is never baseline-able.

    Contract enforcement (raises ``ProtocolFailure``, exit 2):

    * a resolved ``CallableSymbol`` (nothing to convert) raises
      ``UNRESOLVED_SYMBOL_BLOCKING`` -- a resolved symbol must be emitted as
      a finding, never as a signature diagnostic;
    * an unregistered ``code`` raises ``UNKNOWN_DIAGNOSTIC`` -- no raw code
      can enter the report;
    * invalid ``path``/``symbol``/``context`` values raise the normal
      controlled ``ValidationError`` codes from ``GuardDiagnostic``.
    """
    if isinstance(symbol, CallableSymbol):
        if not _is_unresolved_symbol(symbol):
            raise ProtocolFailure(
                "UNRESOLVED_SYMBOL_BLOCKING",
                "resolved symbols must be emitted as findings, not as DB_SIGNATURE_UNRESOLVED diagnostics",
            )
        symbol_text = ".".join(part for part in (symbol.owner, symbol.name) if part) or None
    else:
        symbol_text = symbol
    if not is_known_diagnostic(code):
        raise ProtocolFailure(
            "UNKNOWN_DIAGNOSTIC",
            "diagnostic code is not registered in the diagnostic catalog",
        )
    return GuardDiagnostic(
        code=code,
        path=path,
        symbol=symbol_text,
        controlled_context=dict(context),
    )

@dataclass(frozen=True)
class GuardFinding:
    """A single guard finding; rule/severity/path/symbol/identity are controlled."""
    rule: str
    severity: str
    path: str
    location: SourceLocation
    symbol: CallableSymbol
    identity: Mapping[str, str]
    message: str
    def __post_init__(self):
        rule = _bounded(self.rule, "rule", max_length=MAX_CODE, pattern=_CODE_RE)
        if is_known_diagnostic(rule):
            raise ValidationError("DIAGNOSTIC_AS_FINDING", "diagnostic code must not be emitted as a finding")
        profile = known_rule(rule)
        if profile is None:
            # An unregistered rule code is a direct protocol error (exit 2).
            # ``UNKNOWN_RULE`` is itself registered in the diagnostic catalog,
            # so the code is controlled and can never be baseline-able.
            raise ProtocolFailure("UNKNOWN_RULE", "rule is not registered in the rule catalog")
        if not isinstance(self.location, SourceLocation):
            raise ValidationError("LOCATION_TYPE", "location must be a SourceLocation")
        if not isinstance(self.symbol, CallableSymbol):
            raise ValidationError("SYMBOL_TYPE", "symbol must be a CallableSymbol")
        if _is_unresolved_symbol(self.symbol) and any(f.startswith("symbol.") for f in profile.identity_fields):
            raise ValidationError(
                "UNRESOLVED_SYMBOL_BLOCKING",
                f"rule {rule!r} requires a resolved callable signature; emit diagnostic {DIAGNOSTIC_SIGNATURE_UNRESOLVED!r} instead of a finding",
            )
        object.__setattr__(self, "rule", rule)
        object.__setattr__(self, "severity", _severity(self.severity))
        object.__setattr__(self, "path", canonical_path(self.path))
        object.__setattr__(self, "location", self.location)
        object.__setattr__(self, "symbol", self.symbol)
        object.__setattr__(self, "identity", _identity(self.identity, profile))
        object.__setattr__(self, "message", _bounded(self.message, "message", max_length=MAX_MESSAGE))
    @property
    def fingerprint(self):
        return fingerprint_finding(self)
    def to_dict(self):
        return {"rule": self.rule, "severity": self.severity, "path": self.path,
                "location": self.location.to_dict(), "symbol": self.symbol.to_dict(),
                "identity": _plain(self.identity), "message": self.message}
    @classmethod
    def from_dict(cls, data):
        _check_keys(data, ("rule", "severity", "path", "location", "symbol", "identity", "message"),
                    ("rule", "severity", "path", "location", "symbol", "identity", "message"), "GuardFinding")
        return cls(rule=data["rule"], severity=data["severity"], path=data["path"],
                   location=SourceLocation.from_dict(data["location"]),
                   symbol=CallableSymbol.from_dict(data["symbol"]),
                   identity=data["identity"], message=data["message"])

# ---- Fingerprinting (temporary v2 placeholder; hardened in F1b) ------------
def _field(finding, name):
    """Resolve one profile identity field to a canonical string."""
    if name == "path":
        return finding.path
    if name.startswith("symbol."):
        symbol = finding.symbol
        attr = name[7:]
        if attr == "owner":
            return symbol.owner
        if attr == "name":
            return symbol.name
        if attr == "receiver":
            return symbol.receiver if symbol.receiver is not None else NONE_MARKER
        if attr == "parameters":
            return json.dumps(list(symbol.parameters), separators=(",", ":"))
        if attr == "kind":
            return symbol.kind
    if name.startswith("identity."):
        key = name[9:]
        if key in finding.identity:
            return finding.identity[key]
    raise FingerprintError("UNRESOLVED_FIELD", f"unsupported identity field {name!r}")

def fingerprint_finding(finding):
    """Deterministic v2 fingerprint excluding line/column/message.

    Format: ``v2|<guard>|<rule>|key=<percent-encoded value>|...`` using the
    rule profile's identity fields **in the catalog-declared profile order**
    (protocol v2, section 7.2): canonical ``path``, the full callable symbol
    identity (owner, name, receiver, parameters, kind), then the
    catalog-declared ``identity.*`` fields in declared profile order.  The
    declared order is the canonical order; identity fields are never
    lexicographically re-sorted.
    """
    if not isinstance(finding, GuardFinding):
        raise FingerprintError("FINDING_TYPE", "fingerprint_finding requires a GuardFinding")
    profile = known_rule(finding.rule)
    if profile is None:
        raise FingerprintError("UNKNOWN_RULE", f"rule {finding.rule!r} is not registered")
    parts = [f"{name}={quote(_field(finding, name), safe='')}" for name in profile.identity_fields]
    return "|".join([FINGERPRINT_PREFIX, profile.guard, finding.rule] + parts)

# ---- FingerprintProfile ----------------------------------------------------
@dataclass(frozen=True)
class FingerprintProfile:
    """Declared fingerprint identity for a rule, mirrored from the catalog."""
    guard: str
    identity_fields: Tuple[str, ...]
    multiplicity: str = MULTIPLICITY_COUNT
    def __post_init__(self):
        fields = self.identity_fields
        if isinstance(fields, str) or not isinstance(fields, (list, tuple)) or not fields:
            raise ValidationError("PROFILE_IDENTITY_FIELDS", "identity_fields must be a non-empty sequence of strings")
        object.__setattr__(self, "guard", _bounded(self.guard, "guard", max_length=MAX_GUARD, pattern=_GUARD_RE))
        object.__setattr__(self, "identity_fields", tuple(_bounded(f, "identity_fields", max_length=MAX_SYMBOL) for f in fields))
        multiplicity = _bounded(self.multiplicity, "multiplicity", max_length=32)
        if multiplicity != MULTIPLICITY_COUNT:
            raise ValidationError("PROFILE_MULTIPLICITY", f"multiplicity must be {MULTIPLICITY_COUNT!r}, got {multiplicity!r}")
        object.__setattr__(self, "multiplicity", multiplicity)
    @classmethod
    def from_rule(cls, rule):
        profile = known_rule(rule)
        if profile is None:
            # Never echo the raw rule value from an untrusted caller; the
            # message is a fixed controlled text under the UNKNOWN_RULE code.
            raise ValidationError("UNKNOWN_RULE", "rule is not registered in the rule catalog")
        return cls(guard=profile.guard, identity_fields=profile.identity_fields, multiplicity=profile.multiplicity)

# ---- Aggregation -----------------------------------------------------------
@dataclass(frozen=True)
class AggregatedFinding:
    """A fingerprint-grouped summary; ``count`` tracks multiplicity."""
    fingerprint: str
    count: int
    rule: str
    locations: Tuple[SourceLocation, ...] = ()
    def __post_init__(self):
        fingerprint = _bounded(self.fingerprint, "fingerprint", max_length=2048, pattern=_FP_RE)
        if isinstance(self.count, bool) or not isinstance(self.count, int) or self.count < 1:
            raise ValidationError("AGG_COUNT", "count must be an int >= 1")
        rule = _bounded(self.rule, "rule", max_length=MAX_CODE, pattern=_CODE_RE)
        if not is_known_rule(rule):
            # Never echo the raw rule value from an untrusted caller; the
            # message is a fixed controlled text under the UNKNOWN_RULE code.
            raise ValidationError("UNKNOWN_RULE", "rule is not registered in the rule catalog")
        locations = self.locations or ()
        if isinstance(locations, SourceLocation):
            locations = (locations,)
        if any(not isinstance(item, SourceLocation) for item in locations):
            raise ValidationError("LOCATION_TYPE", "locations entries must be SourceLocation")
        object.__setattr__(self, "fingerprint", fingerprint)
        object.__setattr__(self, "count", self.count)
        object.__setattr__(self, "rule", rule)
        object.__setattr__(self, "locations", tuple(sorted({loc: None for loc in locations}, key=lambda loc: (loc.line, loc.column or 0, loc.end_line or 0, loc.end_column or 0))))

def aggregate_findings(findings):
    """Group findings by fingerprint and return sorted aggregates.

    Exact duplicates -- findings sharing ``(rule, path, location, symbol,
    identity)`` -- are rejected with ``DuplicateFindingError`` before any
    grouping, matching the report-level duplicate contract; exact duplicates
    are never silently deduplicated.  ``count`` is the number of distinct
    source locations in each fingerprint group, so findings with the same
    fingerprint at different locations are counted once per location.
    """
    normalized = _seq(findings, GuardFinding, "FINDINGS_TYPE")
    seen = set()
    for finding in normalized:
        dedup_key = (finding.rule, finding.path, finding.location, finding.symbol, finding.identity)
        if dedup_key in seen:
            # Exact duplicates are rejected with a fixed message; the raw
            # path/rule/symbol/identity and the source location never leak.
            raise DuplicateFindingError("DUPLICATE_FINDING", "duplicate finding detected")
        seen.add(dedup_key)
    grouped = {}
    for finding in normalized:
        grouped.setdefault(finding.fingerprint, []).append(finding)
    result = []
    for fingerprint in sorted(grouped):
        group = grouped[fingerprint]
        first = group[0]
        locations = tuple(sorted({f.location for f in group}, key=lambda loc: (loc.line, loc.column or 0, loc.end_line or 0, loc.end_column or 0)))
        result.append(AggregatedFinding(fingerprint=fingerprint, count=len(locations), rule=first.rule, locations=locations))
    return tuple(result)

# ---- Run report ------------------------------------------------------------
def _location_tiebreak_key(loc):
    """Deterministic tie-break key for a ``SourceLocation``.

    Optional fields default to 0 so ``None`` never breaks tuple comparison.
    Used only as the final tie-breaker in canonical finding sort, never as a
    symbol identity field.
    """
    return (loc.line, loc.column or 0, loc.end_line or 0, loc.end_column or 0)

def _canonical_finding_sort_key(finding):
    """Canonical finding sort key.

    Exact order: ``(rule, path, symbol.owner, symbol.name, symbol.receiver,
    symbol.parameters, symbol.kind, identity fields, location)``.  Location is
    a final tie-breaker only, never before symbol identity; ``message`` is
    never a sort/fingerprint field.  ``symbol.receiver`` is normalized with
    ``NONE_MARKER`` and identity items come from the sorted ``FrozenDict`` so
    the key is deterministic across input order.
    """
    symbol = finding.symbol
    return (
        finding.rule,
        finding.path,
        symbol.owner,
        symbol.name,
        symbol.receiver if symbol.receiver is not None else NONE_MARKER,
        tuple(symbol.parameters),
        symbol.kind,
        tuple(finding.identity.items()),
        _location_tiebreak_key(finding.location),
    )

def _seq(seq, kind, label):
    """Normalize a sequence of model instances to a tuple."""
    if seq is None or isinstance(seq, kind):
        return () if seq is None else (seq,)
    if isinstance(seq, (list, tuple)) and all(isinstance(item, kind) for item in seq):
        return tuple(seq)
    raise ValidationError(label, f"{label.lower()} must be a sequence of {kind.__name__}")

def _validate_top_level(schema, schema_version, guard):
    """Validate a report's top-level ``schema`` / ``schema_version`` / ``guard``.

    Shared by ``GuardRunReport.__post_init__`` and
    ``GuardRunReport.from_dict`` so the JSON read path validates the
    registered guard (and the schema/version) *before* touching
    findings/diagnostics content: a report claiming an unregistered guard
    fails closed with ``UNKNOWN_GUARD`` even when its content is malformed or
    unknown, and a schema/version mismatch is reported before any content is
    materialized.  Returns the validated values.
    """
    schema = _bounded(schema, "schema", max_length=128)
    if schema != REPORT_SCHEMA:
        # Never echo the raw schema value from an untrusted report; the
        # message is a fixed controlled text under the SCHEMA_MISMATCH code.
        raise ValidationError("SCHEMA_MISMATCH", "schema does not match the expected report schema")
    if isinstance(schema_version, bool) or not isinstance(schema_version, int) or schema_version != REPORT_SCHEMA_VERSION:
        raise ValidationError("SCHEMA_VERSION", f"schema_version must be {REPORT_SCHEMA_VERSION}")
    guard = _bounded(guard, "guard", max_length=MAX_GUARD, pattern=_GUARD_RE)
    # Fail closed on unregistered guard names unconditionally: the report
    # guard name must be registered in the canonical guard catalog
    # regardless of the findings/diagnostics content.  A report claiming
    # an unregistered guard is a controlled error (UNKNOWN_GUARD) even
    # when it carries findings (whose rule would otherwise only trip
    # GUARD_MISMATCH) or only diagnostics, so no content shape can bypass
    # the guard registry.  The fixed message never echoes the guard name
    # or any report payload.
    if not is_known_guard(guard):
        raise ValidationError("UNKNOWN_GUARD", "guard is not registered in the report guard catalog")
    return schema, schema_version, guard

@dataclass(frozen=True)
class GuardRunReport:
    """A versioned, validated protocol-v2 report of one guard run."""
    guard: str
    findings: Tuple[GuardFinding, ...] = ()
    diagnostics: Tuple[GuardDiagnostic, ...] = ()
    statistics: Mapping[str, Any] = field(default_factory=FrozenDict)
    schema: str = REPORT_SCHEMA
    schema_version: int = REPORT_SCHEMA_VERSION
    def __post_init__(self):
        schema, schema_version, guard = _validate_top_level(self.schema, self.schema_version, self.guard)
        findings = _seq(self.findings, GuardFinding, "FINDINGS_TYPE")
        if len(findings) > MAX_FINDINGS:
            raise ValidationError("TOO_MANY_FINDINGS", f"report exceeds max findings {MAX_FINDINGS}")
        if not isinstance(self.statistics, Mapping):
            raise ValidationError("STATISTICS_NOT_MAPPING", "statistics must be a mapping")
        _validate_freeform(self.statistics, "statistics")
        statistics = self.statistics if isinstance(self.statistics, FrozenDict) else FrozenDict(self.statistics)
        seen = set()
        for finding in findings:
            profile = known_rule(finding.rule)
            if profile is not None and profile.guard != guard:
                # Never echo the report guard, the finding rule, or the
                # catalog guard; a mismatched finding is reported by code only.
                raise ValidationError("GUARD_MISMATCH", "finding rule does not belong to the report guard")
            dedup_key = (finding.rule, finding.path, finding.location, finding.symbol, finding.identity)
            if dedup_key in seen:
                # Exact duplicates are rejected with a fixed message: the raw
                # path, rule, symbol, identity, or location must never leak.
                raise DuplicateFindingError("DUPLICATE_FINDING", "report contains duplicate findings")
            seen.add(dedup_key)
        object.__setattr__(self, "schema", schema)
        object.__setattr__(self, "schema_version", schema_version)
        object.__setattr__(self, "guard", guard)
        object.__setattr__(self, "findings", tuple(sorted(findings, key=_canonical_finding_sort_key)))
        object.__setattr__(self, "diagnostics", _seq(self.diagnostics, GuardDiagnostic, "DIAGNOSTICS_TYPE"))
        object.__setattr__(self, "statistics", statistics)
    def to_dict(self):
        return {"schema": self.schema, "schema_version": self.schema_version, "guard": self.guard,
                "findings": [f.to_dict() for f in self.findings],
                "diagnostics": [d.to_dict() for d in self.diagnostics],
                "statistics": _plain(self.statistics)}
    @classmethod
    def from_dict(cls, data):
        _check_keys(data, ("schema", "schema_version", "guard", "findings", "diagnostics", "statistics"),
                    ("schema", "schema_version", "guard", "findings", "diagnostics", "statistics"), "GuardRunReport")
        # Validate the top-level schema, schema_version, and registered guard
        # *before* touching findings/diagnostics content: a report claiming an
        # unregistered guard fails closed with UNKNOWN_GUARD even when its
        # content is malformed or unknown, and a schema/version mismatch is
        # reported before any content is materialized.
        _validate_top_level(data["schema"], data["schema_version"], data["guard"])
        findings = data["findings"]
        if not isinstance(findings, list):
            raise JsonValidationError("FINDINGS_NOT_LIST", "findings must be a list")
        if len(findings) > MAX_FINDINGS:
            raise ValidationError("TOO_MANY_FINDINGS", f"report exceeds max findings {MAX_FINDINGS}")
        diagnostics = data["diagnostics"]
        if not isinstance(diagnostics, list):
            raise JsonValidationError("DIAGNOSTICS_NOT_LIST", "diagnostics must be a list")
        statistics = data["statistics"]
        if not isinstance(statistics, Mapping):
            raise JsonValidationError("STATISTICS_NOT_MAPPING", "statistics must be a mapping")
        return cls(schema=data["schema"], schema_version=data["schema_version"], guard=data["guard"],
                   findings=tuple(GuardFinding.from_dict(item) for item in findings),
                   diagnostics=tuple(GuardDiagnostic.from_dict(item) for item in diagnostics),
                   statistics=statistics)

# ---- Build / validate / persistence ----------------------------------------
def build_report(guard, findings, *, diagnostics=(), statistics=None,
                 schema=REPORT_SCHEMA, schema_version=REPORT_SCHEMA_VERSION, reject_duplicates=True):
    """Build a validated report; exact duplicates are rejected by default.

    Exact duplicates share ``(rule, path, location, symbol, identity)``;
    findings with the same semantic fingerprint at different locations are
    allowed.  With ``reject_duplicates=False`` the first occurrence of each
    exact duplicate is kept instead of failing.
    """
    normalized = _seq(findings, GuardFinding, "FINDINGS_TYPE")
    if not reject_duplicates:
        seen = set()
        collapsed = []
        for finding in normalized:
            dedup_key = (finding.rule, finding.path, finding.location, finding.symbol, finding.identity)
            if dedup_key not in seen:
                seen.add(dedup_key)
                collapsed.append(finding)
        normalized = tuple(collapsed)
    return GuardRunReport(schema=schema, schema_version=schema_version, guard=guard,
                          findings=normalized, diagnostics=diagnostics,
                          statistics=statistics if statistics is not None else {})

def validate_report(report):
    """Validate a ``GuardRunReport`` or a JSON-parsed report dict."""
    if isinstance(report, GuardRunReport):
        return report
    if isinstance(report, Mapping):
        return GuardRunReport.from_dict(report)
    raise ValidationError("REPORT_TYPE", "validate_report requires a GuardRunReport or a report dict")

def _path_from(path, error_cls):
    """Safely convert ``path`` to a ``Path`` via ``os.fspath``.

    ``os.fspath`` accepts str and objects implementing ``__fspath__``;
    anything else (an int, None, a plain object, a custom path-like whose
    ``__fspath__`` returns a non-string, or one whose ``__fspath__`` raises
    an unexpected exception) fails closed with a controlled ``INVALID_PATH``
    error instead of leaking a raw exception, the object repr, or the
    converted value.  ``Path`` rejects bytes, which is likewise converted to
    ``INVALID_PATH``.  ``BaseException`` subclasses such as
    ``KeyboardInterrupt`` and ``SystemExit`` are never caught and propagate
    unchanged.
    """
    try:
        converted = os.fspath(path)
    except Exception:
        raise error_cls("INVALID_PATH", "target path must be a path-like value") from None
    try:
        return Path(converted)
    except Exception:
        raise error_cls("INVALID_PATH", "target path must be a path-like value") from None


def load_report_json(path):
    """Load, parse, and fully validate a JSON report file.

    Path conversion, existence check, open/read, and JSON parse failures are
    converted to controlled ``JsonValidationError`` codes; raw filesystem
    paths, exception text, and user values are never echoed.  Unexpected
    ``Exception`` failures (including hostile custom path or read hooks) are
    sanitized to the fixed codes and messages; ``KeyboardInterrupt`` and
    ``SystemExit`` propagate unchanged.  A document that parses but has a
    structurally invalid nested report type raises the underlying
    ``JsonValidationError`` from ``GuardRunReport.from_dict`` unchanged.
    """
    target = _path_from(path, JsonValidationError)
    try:
        is_file = target.is_file()
    except Exception:
        # Any failure to inspect the target (OS errors, permission problems,
        # or an unexpected exception from a hostile path hook) becomes the
        # fixed FILE_CHECK_FAILED code; the raw path/exception never leaks.
        raise JsonValidationError("FILE_CHECK_FAILED", "failed to inspect report file") from None
    if not is_file:
        raise JsonValidationError("MISSING_FILE", "report file does not exist")
    try:
        data = json.loads(target.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        # Never expose the raw filesystem path or the underlying parser
        # message; the caller only needs the controlled failure code.
        raise JsonValidationError("INVALID_JSON", "report file is not valid JSON") from None
    except RecursionError:
        # Deeply nested JSON can exhaust the parser recursion limit; report
        # it with the same controlled parse-failure code and a fixed message.
        raise JsonValidationError("INVALID_JSON", "report file is not valid JSON") from None
    except Exception:
        # Open/read/decoding failures (and any unexpected exception from a
        # read hook) are distinct from parse failures so an I/O or
        # permissions problem never masquerades as invalid content; the raw
        # path and exception text are never echoed.
        raise JsonValidationError("READ_FAILED", "failed to read report file") from None
    return GuardRunReport.from_dict(data)

def canonicalize_report(report):
    """Validate ``report`` (a ``GuardRunReport`` or JSON-parsed dict) and
    return a deterministically sorted copy as a fresh ``GuardRunReport``."""
    if isinstance(report, GuardRunReport):
        return GuardRunReport(schema=report.schema, schema_version=report.schema_version,
                              guard=report.guard, findings=report.findings,
                              diagnostics=report.diagnostics, statistics=report.statistics)
    if isinstance(report, Mapping):
        return GuardRunReport.from_dict(report)
    raise ValidationError("REPORT_TYPE", "canonicalize_report requires a GuardRunReport or a report dict")

def load_report(path):
    """Alias of :func:`load_report_json`."""
    return load_report_json(path)

def write_report_atomic(path, report):
    """Write ``report`` atomically via sibling temp + fsync + os.replace.

    The report is canonicalized (deterministically sorted and revalidated)
    before serialization, so the on-disk form is always the canonical
    protocol-v2 form.     Path conversion, parent resolution/checks, temp
    creation, open/write/fsync, and publish (``os.replace``) failures are
    converted to sanitized ``AtomicWriteError`` codes; raw filesystem paths
    and OS exception text are never echoed.  Unexpected ``Exception``
    failures (including hostile custom path or write/replace hooks) are
    sanitized to the fixed codes and messages; ``KeyboardInterrupt`` and
    ``SystemExit`` propagate unchanged, and existing controlled errors
    (``MISSING_PARENT``, validation failures, etc.) are preserved.
    """
    if not isinstance(report, GuardRunReport):
        raise AtomicWriteError("REPORT_TYPE", "write_report_atomic requires a GuardRunReport")
    target = _path_from(path, AtomicWriteError)
    try:
        parent = target.parent
    except Exception:
        raise AtomicWriteError("INVALID_PATH", "target path must be a path-like value") from None
    try:
        if not parent.is_dir():
            raise AtomicWriteError("MISSING_PARENT", "target parent directory does not exist")
    except AtomicWriteError:
        raise
    except Exception:
        raise AtomicWriteError("PARENT_CHECK_FAILED", "failed to inspect target parent directory") from None
    try:
        canonical = canonicalize_report(report)
        json_text = json.dumps(canonical.to_dict(), indent=2, sort_keys=False) + "\n"
    except GuardFindingsError:
        raise
    except Exception:
        raise AtomicWriteError("WRITE_FAILED", "failed to prepare report for atomic write") from None
    fd = None
    temp_name = None
    try:
        fd, temp_name = tempfile.mkstemp(prefix=f".{target.name}.", suffix=".tmp", dir=str(parent), text=True)
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            fd = None
            handle.write(json_text)
            handle.flush()
            os.fsync(handle.fileno())
        with suppress(OSError):
            os.chmod(temp_name, 0o644)
        os.replace(temp_name, str(target))
        temp_name = None
        return str(target)
    except Exception:
        # Never expose the raw filesystem path, temp path, or the underlying
        # OS/exception message; any temp/open/write/fsync/replace failure
        # (including a hostile write or replace hook) is a fixed
        # WRITE_FAILED with no raw text.
        raise AtomicWriteError("WRITE_FAILED", "failed to write report atomically") from None
    finally:
        with suppress(OSError):
            if fd is not None:
                os.close(fd)
        with suppress(OSError):
            if temp_name is not None:
                os.unlink(temp_name)
