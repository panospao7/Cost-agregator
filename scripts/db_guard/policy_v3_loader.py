"""Fail-closed loader for DB ownership policy v3 documents (PR-GR-15).

Loads a v3 policy YAML file and converts it into immutable
:class:`~scripts.db_guard.policy_v3_model.PolicyEntryV3` objects without
ever calling ``sys.exit``.  Every rejection is reported as a controlled
:class:`~scripts.db_guard.policy_errors.PolicyError` from the closed code
set.

Document contract (exact):
    ``{schemaVersion: int 3, entries: [non-empty list of mappings]}``

Per-entry contract (exact required fields, no others).  The identity
fields are byte-for-byte the v2 identity fields; v3 replaces v2's
metadata-only ``barrierMode`` string with a structured
``barrierRequirement`` mapping carrying the closed mode/contract pair:

    ``path ownerFqcn kind method receiver parameterTypes daoAccessor
    daoFqcn operation barrierRequirement reason owner linkedIssue``

    ``barrierRequirement``: ``{mode: ..., contract: ...}`` with exactly
    two keys and a pair from :data:`PROOF_CONTRACT_BY_MODE`:
    ``direct`` -> ``cfg-direct-dominance-v1``,
    ``helper`` -> ``bounded-helper-mediation-v1``,
    ``workerMediated`` -> ``bounded-worker-mediation-v1``.

Rejections beyond the v2 field rules (all fail closed):
    * ``schemaVersion`` 1, 2, or any non-3 value -> schema mismatch /
      invalid type; a v2 active policy is a controlled configuration
      failure, never silently upgraded;
    * the v2 ``barrierMode`` field (and every other unknown key) ->
      ``POLICY_ERROR_UNKNOWN_FIELD``;
    * missing ``barrierRequirement`` -> ``POLICY_ERROR_MISSING_FIELD``;
    * barrierRequirement not a two-key mapping / unknown mode / unknown
      contract / mode-contract mismatch ->
      ``POLICY_ERROR_V3_BARRIER_CONTRACT_INVALID``;
    * duplicate mutation identities -> ``POLICY_ERROR_V3_DUPLICATE_MUTATION_KEY``
      (count only, identities never echoed).

Wildcard metacharacters, noncanonical paths, malformed signatures, and
empty ``reason``/``owner``/``linkedIssue`` reject exactly as in v2.  The
barrier requirement is validated state but NOT part of the mutation
identity: :meth:`PolicyEntryV3.mutation_key` equals the v2 identity.
"""
from __future__ import annotations

import os

try:  # package mode: imported as ``scripts.db_guard.policy_v3_loader``
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
    POLICY_ERROR_V3_BARRIER_CONTRACT_INVALID,
    POLICY_ERROR_V3_DUPLICATE_MUTATION_KEY,
    POLICY_ERROR_V2_PATH_NOT_CANONICAL,
    POLICY_ERROR_YAML_MALFORMED,
    POLICY_ERROR_YAML_MODULE_UNAVAILABLE,
    PolicyError,
)
from .policy_model import CallableKind
from .policy_v3_model import (
    PROOF_CONTRACT_BY_MODE,
    BarrierProofContract,
    BarrierProofMode,
    BarrierRequirement,
    PolicyEntryV3,
)

__all__ = [
    "V3_SCHEMA_VERSION",
    "V3_DOCUMENT_KEYS",
    "V3_REQUIRED_ENTRY_FIELDS",
    "V3_BARRIER_REQUIREMENT_FIELDS",
    "build_policy_entry_v3",
    "load_policy_v3",
]

# ── Schema constants ──────────────────────────────────────────────────────────

V3_SCHEMA_VERSION = 3

#: Exact top-level document key set.
V3_DOCUMENT_KEYS = ("schemaVersion", "entries")

#: Exact per-entry required fields, in validation order.
V3_REQUIRED_ENTRY_FIELDS = (
    "path",
    "ownerFqcn",
    "kind",
    "method",
    "receiver",
    "parameterTypes",
    "daoAccessor",
    "daoFqcn",
    "operation",
    "barrierRequirement",
    "reason",
    "owner",
    "linkedIssue",
)

#: The barrierRequirement sub-mapping carries exactly these keys.
V3_BARRIER_REQUIREMENT_FIELDS = ("mode", "contract")

#: v2 metadata-only spellings, rejected through the ordinary unknown-field
#: path; listed explicitly so the v2 -> v3 boundary is documented/testable.
V2_REJECTED_ENTRY_KEYS = frozenset({"barrierMode"})

try:
    import yaml
    _HAS_YAML = True
except ImportError:  # pragma: no cover - exercised only without PyYAML
    _HAS_YAML = False


# ── Internal helpers (mirror policy_v2_loader) ────────────────────────────────

_V3_DOCUMENT_LABEL = "v3 policy document"


def _yaml_safe_load(path):
    if not _HAS_YAML:
        return None, PolicyError(
            POLICY_ERROR_YAML_MODULE_UNAVAILABLE, {"label": _V3_DOCUMENT_LABEL}
        )

    if not os.path.exists(path):
        return None, PolicyError(
            POLICY_ERROR_POLICY_FILE_NOT_FOUND, {"label": _V3_DOCUMENT_LABEL}
        )

    try:
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
    except yaml.YAMLError:
        return None, PolicyError(POLICY_ERROR_YAML_MALFORMED, {"label": _V3_DOCUMENT_LABEL})

    if data is None:
        return None, PolicyError(POLICY_ERROR_POLICY_EMPTY, {"label": _V3_DOCUMENT_LABEL})

    return data, None


def _type_name(value):
    return type(value).__name__


def _unknown_field_error(key, index=None):
    field_label = key if isinstance(key, str) else "<non-string>"
    context = {"field": field_label}
    if index is not None:
        context["index"] = index
    return PolicyError(POLICY_ERROR_UNKNOWN_FIELD, context)


def _enum_or_none(enum_cls, value):
    if isinstance(value, str):
        try:
            return enum_cls(value)
        except ValueError:
            return None
    return None


def _validate_path_field(value, index, errors):
    if not isinstance(value, str):
        errors.append(
            PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": "path", "index": index})
        )
        return None
    try:
        return canonical_source_path(value)
    except ParserError as exc:
        errors.append(
            PolicyError(
                POLICY_ERROR_V2_PATH_NOT_CANONICAL,
                {"index": index, "parser_code": exc.code},
            )
        )
        return None


def _validate_parameter_types_field(value, index, errors):
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


def _validate_barrier_requirement_field(value, index, errors):
    """Validate the closed mode/contract pair; append errors on failure.

    Returns a :class:`BarrierRequirement` or ``None``.  The mapping must
    carry exactly ``mode`` and ``contract``; any other shape, any unknown
    mode or contract, and any mode/contract mismatch rejects with
    ``POLICY_ERROR_V3_BARRIER_CONTRACT_INVALID`` (bounded context only).
    """
    invalid = lambda field: PolicyError(  # noqa: E731 - bounded helper
        POLICY_ERROR_V3_BARRIER_CONTRACT_INVALID, {"field": field, "index": index}
    )
    if not isinstance(value, dict):
        errors.append(invalid("barrierRequirement"))
        return None
    for key in list(value.keys()):
        if key not in V3_BARRIER_REQUIREMENT_FIELDS:
            errors.append(invalid(key if isinstance(key, str) else "barrierRequirement"))
    for field_name in V3_BARRIER_REQUIREMENT_FIELDS:
        if field_name not in value:
            errors.append(invalid(field_name))
    if errors:
        return None
    mode = _enum_or_none(BarrierProofMode, value["mode"])
    contract = _enum_or_none(BarrierProofContract, value["contract"])
    if mode is None or contract is None:
        errors.append(invalid("barrierRequirement"))
        return None
    if PROOF_CONTRACT_BY_MODE.get(mode) is not contract:
        errors.append(invalid("contract"))
        return None
    return BarrierRequirement(mode=mode, contract=contract)


# ── Public API ────────────────────────────────────────────────────────────────

def build_policy_entry_v3(raw_mapping, index):
    """Build one :class:`PolicyEntryV3` from a raw YAML mapping.

    Identity-field validation mirrors :func:`scripts.db_guard.policy_v2_loader.build_policy_entry`
    exactly (same controlled codes, same bounded contexts); the v2
    metadata-only ``barrierMode`` key is rejected as unknown, and the
    required ``barrierRequirement`` mapping is validated against the
    closed mode/contract pairs.  Returns ``(entry, [])`` on success.
    """
    if not isinstance(raw_mapping, dict):
        return None, [PolicyError(POLICY_ERROR_ENTRY_NOT_MAPPING, {"index": index})]

    errors = []

    for key in list(raw_mapping.keys()):
        try:
            allowed = key in set(V3_REQUIRED_ENTRY_FIELDS)
        except TypeError:
            allowed = False
        if not allowed:
            errors.append(_unknown_field_error(key, index))

    for field_name in V3_REQUIRED_ENTRY_FIELDS:
        if field_name not in raw_mapping:
            errors.append(
                PolicyError(POLICY_ERROR_MISSING_FIELD, {"field": field_name, "index": index})
            )
    if errors:
        return None, errors

    values = {}

    canonical_path = _validate_path_field(raw_mapping["path"], index, errors)
    if canonical_path is not None:
        values["path"] = canonical_path

    owner_fqcn = raw_mapping["ownerFqcn"]
    if isinstance(owner_fqcn, str) and owner_fqcn and "." in owner_fqcn:
        values["owner_fqcn"] = owner_fqcn
    else:
        errors.append(
            PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": "ownerFqcn", "index": index})
        )

    kind = _enum_or_none(CallableKind, raw_mapping["kind"])
    if kind is not None:
        values["kind"] = kind
    else:
        errors.append(PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": "kind", "index": index}))

    method = raw_mapping["method"]
    if isinstance(method, str) and method:
        if not any(ch in method for ch in ("*", "?", "[", "]")):
            values["method"] = method
        else:
            errors.append(
                PolicyError(POLICY_ERROR_INVALID_SIGNATURE, {"field": "method", "index": index})
            )
    else:
        errors.append(PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": "method", "index": index}))

    receiver = raw_mapping["receiver"]
    if receiver is None:
        values["receiver"] = None
    elif isinstance(receiver, str):
        try:
            values["receiver"] = normalize_type_text(receiver)
        except SignatureError as exc:
            errors.append(
                PolicyError(
                    POLICY_ERROR_INVALID_SIGNATURE,
                    {"field": "receiver", "index": index, "signature_code": exc.code},
                )
            )
    else:
        errors.append(PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": "receiver", "index": index}))

    parameter_types = _validate_parameter_types_field(raw_mapping["parameterTypes"], index, errors)
    if parameter_types is not None:
        values["parameter_types"] = parameter_types

    dao_accessor = raw_mapping["daoAccessor"]
    if isinstance(dao_accessor, str) and dao_accessor:
        values["dao_accessor"] = dao_accessor
    else:
        errors.append(
            PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": "daoAccessor", "index": index})
        )

    dao_fqcn = raw_mapping["daoFqcn"]
    if isinstance(dao_fqcn, str) and dao_fqcn:
        values["dao_fqcn"] = dao_fqcn
    else:
        errors.append(PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": "daoFqcn", "index": index}))

    operation = raw_mapping["operation"]
    if isinstance(operation, str) and operation:
        values["operation"] = operation
    else:
        errors.append(PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": "operation", "index": index}))

    barrier_requirement = _validate_barrier_requirement_field(
        raw_mapping["barrierRequirement"], index, errors
    )
    if barrier_requirement is not None:
        values["barrier_requirement"] = barrier_requirement

    for field_name in ("reason", "owner", "linkedIssue"):
        field_value = raw_mapping[field_name]
        attr_name = "linked_issue" if field_name == "linkedIssue" else field_name
        if isinstance(field_value, str) and field_value:
            values[attr_name] = field_value
        else:
            errors.append(
                PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": field_name, "index": index})
            )

    if errors:
        return None, errors

    try:
        entry = PolicyEntryV3(**values)
    except TypeError:
        # Fail-closed safety net: the validators above fully determine the
        # model contract, so this only fires on an internal inconsistency.
        return None, [PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": "entry", "index": index})]
    return entry, []


def load_policy_v3(path):
    """Load and validate a v3 policy document without exiting.

    Returns ``(document, [])`` on success, where ``document`` is a tuple
    of :class:`PolicyEntryV3` objects; returns ``(None, errors)`` on any
    failure.  ``schemaVersion`` must be the integer ``3``: a v2 active
    policy (``schemaVersion: 2``) is a controlled schema mismatch, never
    silently upgraded.  Duplicate mutation identities reject with
    ``POLICY_ERROR_V3_DUPLICATE_MUTATION_KEY`` reported once with a count
    only.
    """
    data, load_error = _yaml_safe_load(path)
    if load_error is not None:
        return None, [load_error]

    errors = []

    if not isinstance(data, dict):
        return None, [
            PolicyError(POLICY_ERROR_INVALID_TYPE, {"expected": "mapping", "got": _type_name(data)})
        ]

    for key in list(data.keys()):
        try:
            known = key in V3_DOCUMENT_KEYS
        except TypeError:
            known = False
        if not known:
            errors.append(_unknown_field_error(key))

    for key in V3_DOCUMENT_KEYS:
        if key not in data:
            errors.append(PolicyError(POLICY_ERROR_MISSING_FIELD, {"field": key}))
    if errors:
        return None, errors

    schema_version = data["schemaVersion"]
    if isinstance(schema_version, bool) or not isinstance(schema_version, int):
        errors.append(PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": "schemaVersion"}))
    elif schema_version != V3_SCHEMA_VERSION:
        errors.append(
            PolicyError(
                POLICY_ERROR_SCHEMA_MISMATCH,
                {"expected": V3_SCHEMA_VERSION, "got": schema_version},
            )
        )

    entries_value = data["entries"]
    if not isinstance(entries_value, list):
        errors.append(PolicyError(POLICY_ERROR_INVALID_TYPE, {"field": "entries"}))
        return None, errors
    if not entries_value:
        errors.append(PolicyError(POLICY_ERROR_POLICY_EMPTY, {"label": _V3_DOCUMENT_LABEL}))
        return None, errors

    entries = []
    seen_keys = set()
    duplicate_count = 0
    for index, raw_mapping in enumerate(entries_value):
        entry, entry_errors = build_policy_entry_v3(raw_mapping, index)
        errors.extend(entry_errors)
        if entry is not None:
            key = entry.mutation_key().canonical_key()
            if key in seen_keys:
                duplicate_count += 1
            else:
                seen_keys.add(key)
                entries.append(entry)

    if duplicate_count:
        errors.append(
            PolicyError(POLICY_ERROR_V3_DUPLICATE_MUTATION_KEY, {"count": duplicate_count})
        )

    if errors:
        return None, errors
    return tuple(entries), []
