"""Fail-closed loader for DB ownership policy v2 documents (PR-01).

Loads a v2 policy YAML file and converts it into immutable
:class:`~scripts.db_guard.policy_model.PolicyEntry` objects without ever
calling ``sys.exit``.  Every rejection is reported as a controlled
:class:`~scripts.db_guard.policy_errors.PolicyError` from the closed code
set; unknown reason codes cannot leak into diagnostics because
``PolicyError`` construction fails closed.

Document contract (exact):
    ``{schemaVersion: int 2, entries: [non-empty list of mappings]}``

Per-entry contract (exact required fields, no others):
    ``path ownerFqcn kind method receiver parameterTypes daoAccessor
    daoFqcn operation barrierMode reason owner linkedIssue``

Unknown keys — including the v1 legacy keys ``class``, ``daos``,
``signature``, ``barrier_required``, ``barrier_via``, ``private``, and
``delegate_of`` — are rejected with ``POLICY_ERROR_UNKNOWN_FIELD``; there
is no silent upgrade path from v1 documents.

Bounded-context contract (see ``policy_errors.py``): ``PolicyError.context``
carries only fixed labels, indices, counts, positions, type names, and
controlled parser/signature codes — never file paths, never raw exception
text, never policy entry payloads.  Duplicate mutation identities are
reported once per document as a count only.
"""

from __future__ import annotations

import os

try:  # package mode: imported as ``scripts.db_guard.policy_v2_loader``
    from ..db_policy_signature import SignatureError, normalize_type_text
    from ..kotlin_callable_parser import ParserError, canonical_source_path
except ImportError:  # pragma: no cover - flat mode: standalone tools put ``scripts`` on sys.path
    from db_policy_signature import SignatureError, normalize_type_text
    from kotlin_callable_parser import ParserError, canonical_source_path

from .policy_errors import (
    POLICY_ERROR_ENTRY_NOT_MAPPING,
    POLICY_ERROR_INVALID_SIGNATURE,
    POLICY_ERROR_INVALID_TYPE,
    POLICY_ERROR_MISSING_FIELD,
    POLICY_ERROR_POLICY_EMPTY,
    POLICY_ERROR_POLICY_FILE_NOT_FOUND,
    POLICY_ERROR_SCHEMA_MISMATCH,
    POLICY_ERROR_UNKNOWN_FIELD,
    POLICY_ERROR_V2_DUPLICATE_MUTATION_KEY,
    POLICY_ERROR_V2_PATH_NOT_CANONICAL,
    POLICY_ERROR_YAML_MALFORMED,
    POLICY_ERROR_YAML_MODULE_UNAVAILABLE,
    PolicyError,
)
from .policy_model import BarrierMode, CallableKind, PolicyEntry

__all__ = [
    "V2_SCHEMA_VERSION",
    "DOCUMENT_KEYS",
    "REQUIRED_ENTRY_FIELDS",
    "ALLOWED_ENTRY_FIELDS",
    "LEGACY_REJECTED_ENTRY_KEYS",
    "build_policy_entry",
    "load_policy_v2",
]

# ── Schema constants ──────────────────────────────────────────────────────────

V2_SCHEMA_VERSION = 2

#: Exact top-level document key set.
DOCUMENT_KEYS = ("schemaVersion", "entries")

#: Exact per-entry required fields, in validation order.  Every required
#: field must be present; no other key may appear.
REQUIRED_ENTRY_FIELDS = (
    "path",
    "ownerFqcn",
    "kind",
    "method",
    "receiver",
    "parameterTypes",
    "daoAccessor",
    "daoFqcn",
    "operation",
    "barrierMode",
    "reason",
    "owner",
    "linkedIssue",
)

ALLOWED_ENTRY_FIELDS = frozenset(REQUIRED_ENTRY_FIELDS)

#: v1 legacy keys.  Rejected through the ordinary unknown-field path; listed
#: explicitly so the v1 -> v2 boundary is documented and testable.
LEGACY_REJECTED_ENTRY_KEYS = frozenset(
    {
        "class",
        "daos",
        "signature",
        "barrier_required",
        "barrier_via",
        "private",
        "delegate_of",
    }
)

try:
    import yaml
    _HAS_YAML = True
except ImportError:  # pragma: no cover - exercised only without PyYAML
    _HAS_YAML = False


# ── Internal helpers ──────────────────────────────────────────────────────────

_V2_DOCUMENT_LABEL = "v2 policy document"


def _yaml_safe_load(path):
    """Load a YAML file with ``yaml.safe_load`` without exiting.

    Same read/parse flow as the legacy loaders' ``legacy_yaml_safe_load``
    (same checks, same order, same fail-closed semantics):

      * PyYAML unavailable          -> POLICY_ERROR_YAML_MODULE_UNAVAILABLE
      * file does not exist         -> POLICY_ERROR_POLICY_FILE_NOT_FOUND
      * ``yaml.YAMLError`` on parse -> POLICY_ERROR_YAML_MALFORMED
      * document parses to ``None`` -> POLICY_ERROR_POLICY_EMPTY

    Returns ``(data, None)`` on success and ``(None, PolicyError)`` on
    failure.  Only ``yaml.YAMLError`` is handled — any other I/O error
    propagates.  ``context`` stays bounded (``label`` only): never the
    path, never raw exception text.
    """
    if not _HAS_YAML:
        return None, PolicyError(
            POLICY_ERROR_YAML_MODULE_UNAVAILABLE, {"label": _V2_DOCUMENT_LABEL}
        )

    if not os.path.exists(path):
        return None, PolicyError(
            POLICY_ERROR_POLICY_FILE_NOT_FOUND, {"label": _V2_DOCUMENT_LABEL}
        )

    try:
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
    except yaml.YAMLError:
        return None, PolicyError(POLICY_ERROR_YAML_MALFORMED, {"label": _V2_DOCUMENT_LABEL})

    if data is None:
        return None, PolicyError(POLICY_ERROR_POLICY_EMPTY, {"label": _V2_DOCUMENT_LABEL})

    return data, None


def _type_name(value):
    """Bounded type name for ``expected``/``got`` context fields."""
    return type(value).__name__


def _unknown_field_error(key, index=None):
    """Controlled error for one disallowed key.

    Non-string YAML keys never enter ``context``; they collapse to the
    fixed label ``<non-string>`` so arbitrary payloads cannot leak into
    diagnostics.
    """
    field_label = key if isinstance(key, str) else "<non-string>"
    context = {"field": field_label}
    if index is not None:
        context["index"] = index
    return PolicyError(POLICY_ERROR_UNKNOWN_FIELD, context)


def _enum_or_none(enum_cls, value):
    """Return ``enum_cls(value)`` for string inputs, else ``None``."""
    if isinstance(value, str):
        try:
            return enum_cls(value)
        except ValueError:
            return None
    return None


def _validate_path_field(value, index, errors):
    """Validate one ``path`` value; append controlled errors."""
    if not isinstance(value, str):
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE, {"field": "path", "index": index}
            )
        )
        return None
    try:
        return canonical_source_path(value)
    except ParserError as exc:
        # The parser's code is a controlled constant and safe to expose;
        # input text and exception details are never exposed.
        errors.append(
            PolicyError(
                POLICY_ERROR_V2_PATH_NOT_CANONICAL,
                {"index": index, "parser_code": exc.code},
            )
        )
        return None


def _validate_parameter_types_field(value, index, errors):
    """Validate and normalize one ``parameterTypes`` value."""
    if not isinstance(value, list):
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {"field": "parameterTypes", "index": index},
            )
        )
        return None
    normalized = []
    for position, item in enumerate(value):
        if not isinstance(item, str):
            errors.append(
                PolicyError(
                    POLICY_ERROR_INVALID_TYPE,
                    {"field": "parameterTypes", "index": index},
                )
            )
            return None
        try:
            normalized.append(normalize_type_text(item))
        except SignatureError as exc:
            # Signature codes are controlled constants; raw input text is
            # never exposed.
            errors.append(
                PolicyError(
                    POLICY_ERROR_INVALID_SIGNATURE,
                    {
                        "field": "parameterTypes",
                        "index": index,
                        "position": position,
                        "signature_code": exc.code,
                    },
                )
            )
            return None
    return tuple(normalized)


# ── Public API ────────────────────────────────────────────────────────────────

def build_policy_entry(raw_mapping, index):
    """Build one :class:`PolicyEntry` from a raw YAML mapping.

    Fails closed: returns ``(None, errors)`` with one controlled
    ``PolicyError`` per violated rule instead of raising.  All detectable
    field problems for the entry are reported together:

      * non-mapping input          -> POLICY_ERROR_ENTRY_NOT_MAPPING;
      * missing required field     -> POLICY_ERROR_MISSING_FIELD;
      * unknown / legacy key       -> POLICY_ERROR_UNKNOWN_FIELD;
      * mistyped simple field      -> POLICY_ERROR_INVALID_TYPE;
      * non-canonical ``path``     -> POLICY_ERROR_V2_PATH_NOT_CANONICAL
        (the parser's controlled code rides in ``context.parser_code``);
      * malformed parameter type   -> POLICY_ERROR_INVALID_SIGNATURE
        (the signature code rides in ``context.signature_code``);
      * wildcard metacharacter (``*``, ``?``, ``[``, ``]``) in ``method``
        -> POLICY_ERROR_INVALID_SIGNATURE;
      * malformed ``receiver`` type text -> POLICY_ERROR_INVALID_SIGNATURE
        (the signature code rides in ``context.signature_code``).

    ``kind`` accepts exactly the ``CallableKind`` values; ``barrierMode``
    accepts exactly the ``BarrierMode`` values; ``receiver`` accepts
    ``None`` or a Kotlin type text normalized via ``normalize_type_text``;
    ``parameterTypes`` accepts a list of Kotlin type texts normalized via
    ``normalize_type_text``.

    Returns ``(entry, [])`` on success.
    """
    if not isinstance(raw_mapping, dict):
        return None, [
            PolicyError(
                POLICY_ERROR_ENTRY_NOT_MAPPING,
                {"index": index},
            )
        ]

    errors = []

    # Unknown keys first (including every legacy v1 key), then missing
    # required fields, then per-field types — deterministic order.
    for key in list(raw_mapping.keys()):
        try:
            allowed = key in ALLOWED_ENTRY_FIELDS
        except TypeError:
            allowed = False
        if not allowed:
            errors.append(_unknown_field_error(key, index))

    for field_name in REQUIRED_ENTRY_FIELDS:
        if field_name not in raw_mapping:
            errors.append(
                PolicyError(
                    POLICY_ERROR_MISSING_FIELD,
                    {"field": field_name, "index": index},
                )
            )
    if errors:
        return None, errors

    values = {}

    path_value = raw_mapping["path"]
    canonical_path = _validate_path_field(path_value, index, errors)
    if canonical_path is not None:
        values["path"] = canonical_path

    owner_fqcn = raw_mapping["ownerFqcn"]
    if isinstance(owner_fqcn, str) and owner_fqcn and "." in owner_fqcn:
        values["owner_fqcn"] = owner_fqcn
    else:
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {"field": "ownerFqcn", "index": index},
            )
        )

    kind = _enum_or_none(CallableKind, raw_mapping["kind"])
    if kind is not None:
        values["kind"] = kind
    else:
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE, {"field": "kind", "index": index}
            )
        )

    method = raw_mapping["method"]
    if isinstance(method, str) and method:
        if not any(ch in method for ch in ("*", "?", "[", "]")):
            values["method"] = method
        else:
            errors.append(
                PolicyError(
                    POLICY_ERROR_INVALID_SIGNATURE,
                    {"field": "method", "index": index},
                )
            )
    else:
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE, {"field": "method", "index": index}
            )
        )

    receiver = raw_mapping["receiver"]
    if receiver is None:
        values["receiver"] = None
    elif isinstance(receiver, str):
        try:
            values["receiver"] = normalize_type_text(receiver)
        except SignatureError as exc:
            # Signature codes are controlled constants; raw input text is
            # never exposed.
            errors.append(
                PolicyError(
                    POLICY_ERROR_INVALID_SIGNATURE,
                    {
                        "field": "receiver",
                        "index": index,
                        "signature_code": exc.code,
                    },
                )
            )
    else:
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {"field": "receiver", "index": index},
            )
        )

    parameter_types = _validate_parameter_types_field(
        raw_mapping["parameterTypes"], index, errors
    )
    if parameter_types is not None:
        values["parameter_types"] = parameter_types

    dao_accessor = raw_mapping["daoAccessor"]
    if isinstance(dao_accessor, str) and dao_accessor:
        values["dao_accessor"] = dao_accessor
    else:
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {"field": "daoAccessor", "index": index},
            )
        )

    dao_fqcn = raw_mapping["daoFqcn"]
    if isinstance(dao_fqcn, str) and dao_fqcn:
        values["dao_fqcn"] = dao_fqcn
    else:
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {"field": "daoFqcn", "index": index},
            )
        )

    operation = raw_mapping["operation"]
    if isinstance(operation, str) and operation:
        values["operation"] = operation
    else:
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {"field": "operation", "index": index},
            )
        )

    barrier_mode = _enum_or_none(BarrierMode, raw_mapping["barrierMode"])
    if barrier_mode is not None:
        values["barrier_mode"] = barrier_mode
    else:
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {"field": "barrierMode", "index": index},
            )
        )

    for field_name in ("reason", "owner", "linkedIssue"):
        field_value = raw_mapping[field_name]
        attr_name = "linked_issue" if field_name == "linkedIssue" else field_name
        if isinstance(field_value, str) and field_value:
            values[attr_name] = field_value
        else:
            errors.append(
                PolicyError(
                    POLICY_ERROR_INVALID_TYPE,
                    {"field": field_name, "index": index},
                )
            )

    if errors:
        return None, errors

    try:
        entry = PolicyEntry(**values)
    except TypeError:
        # Fail-closed safety net: the validators above fully determine the
        # model contract, so this only fires on an internal inconsistency.
        return None, [
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {"field": "entry", "index": index},
            )
        ]
    return entry, []


def load_policy_v2(path):
    """Load and validate a v2 policy document without exiting.

    Returns ``(document, [])`` on success, where ``document`` is a tuple
    of :class:`PolicyEntry` objects; returns ``(None, errors)`` on any
    failure, with every detected problem reported as a controlled
    ``PolicyError``:

      * YAML load failure        -> module-unavailable / file-not-found /
        malformed / empty codes (see :func:`_yaml_safe_load`);
      * document not a mapping   -> POLICY_ERROR_INVALID_TYPE;
      * wrong top-level keys     -> POLICY_ERROR_MISSING_FIELD /
        POLICY_ERROR_UNKNOWN_FIELD;
      * ``schemaVersion`` not the integer ``2`` -> POLICY_ERROR_INVALID_TYPE
        (non-int, including booleans) or POLICY_ERROR_SCHEMA_MISMATCH
        (wrong integer);
      * ``entries`` not a list   -> POLICY_ERROR_INVALID_TYPE;
      * empty ``entries``        -> POLICY_ERROR_POLICY_EMPTY;
      * failing entry            -> the :func:`build_policy_entry` codes;
      * duplicate mutation identities across entries ->
        POLICY_ERROR_V2_DUPLICATE_MUTATION_KEY, reported once with
        ``context.count`` only (never the identities themselves).
    """
    data, load_error = _yaml_safe_load(path)
    if load_error is not None:
        return None, [load_error]

    errors = []

    if not isinstance(data, dict):
        return None, [
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {"expected": "mapping", "got": _type_name(data)},
            )
        ]

    # Exact top-level key set: unknown keys first, then missing ones, in
    # DOCUMENT_KEYS order.
    for key in list(data.keys()):
        try:
            known = key in DOCUMENT_KEYS
        except TypeError:
            known = False
        if not known:
            errors.append(_unknown_field_error(key))

    for key in DOCUMENT_KEYS:
        if key not in data:
            errors.append(
                PolicyError(POLICY_ERROR_MISSING_FIELD, {"field": key})
            )
    if errors:
        return None, errors

    schema_version = data["schemaVersion"]
    if isinstance(schema_version, bool) or not isinstance(schema_version, int):
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {"field": "schemaVersion"},
            )
        )
    elif schema_version != V2_SCHEMA_VERSION:
        errors.append(
            PolicyError(
                POLICY_ERROR_SCHEMA_MISMATCH,
                {
                    "field": "schemaVersion",
                    "expected": V2_SCHEMA_VERSION,
                    "got": schema_version,
                },
            )
        )

    entries_raw = data["entries"]
    if not isinstance(entries_raw, list):
        errors.append(
            PolicyError(
                POLICY_ERROR_INVALID_TYPE,
                {
                    "field": "entries",
                    "expected": "list",
                    "got": _type_name(entries_raw),
                },
            )
        )
    elif not entries_raw:
        errors.append(
            PolicyError(POLICY_ERROR_POLICY_EMPTY, {"field": "entries"})
        )

    built_entries = []
    if isinstance(entries_raw, list):
        for index, raw_entry in enumerate(entries_raw):
            entry, entry_errors = build_policy_entry(raw_entry, index)
            if entry is not None:
                built_entries.append(entry)
            errors.extend(entry_errors)

    # Duplicate mutation identities: one document-level error, count only.
    seen_keys = set()
    duplicate_count = 0
    for entry in built_entries:
        key = entry.mutation_key().canonical_key()
        if key in seen_keys:
            duplicate_count += 1
        else:
            seen_keys.add(key)
    if duplicate_count:
        errors.append(
            PolicyError(
                POLICY_ERROR_V2_DUPLICATE_MUTATION_KEY,
                {"count": duplicate_count},
            )
        )

    if errors:
        return None, errors

    return tuple(built_entries), []
