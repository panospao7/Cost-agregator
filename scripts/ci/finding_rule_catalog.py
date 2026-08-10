#!/usr/bin/env python3
"""
FINDING_RULE_CATALOG -- Immutable metadata for guard finding rules and diagnostics.

This module is the single, immutable source of truth for the stable policy
rules and infrastructure diagnostic codes emitted by guards. Consumers (the
finding model, the ratchet, and reporters) look up controlled metadata here
instead of hard-coding rule identities, so a rule's required identity fields
or a diagnostic's baseline-ability can only change in one place.

Contents
--------

Policy rules (baseline-able findings):

  * ``DB_UNAUTHORIZED_MUTATION``
  * ``DB_MISSING_WRITE_BARRIER``
  * ``DB_FORBIDDEN_STRUCTURAL_OPERATION``

Infrastructure diagnostics (never baseline-able; exit 2):

  * ``DB_SOURCE_UNREADABLE``
  * ``DB_METHOD_BODY_UNSUPPORTED``
  * ``DB_EXPRESSION_BODY_UNSUPPORTED``
  * ``DB_DAO_SCOPE_UNRESOLVED``
  * ``DB_CALL_TARGET_AMBIGUOUS``
  * ``DB_POLICY_SOURCE_EVIDENCE_INVALID``
  * ``DB_ROOM_QUERY_UNCLASSIFIABLE``
  * ``DB_SIGNATURE_UNRESOLVED``
  * ``DB_DAO_INHERITANCE_UNRESOLVED``

Public API:

  * Models: ``RuleProfile``, ``DiagnosticProfile`` (both immutable)
  * Lookups: ``known_rule()``, ``known_diagnostic()`` (return metadata, or
    ``None`` -- falsey, i.e. no metadata -- for unknown values)
  * Boolean checks: ``is_known_rule()``, ``is_known_diagnostic()``
  * Immutable mappings: ``RULE_PROFILES``, ``DIAGNOSTIC_PROFILES``,
    ``ALL_PROFILES``
  * Code sets: ``RULE_CODES``, ``DIAGNOSTIC_CODES``, ``ALL_CODES``

Everything is stdlib-only and immutable: profiles are frozen dataclasses and
the catalog mappings are ``types.MappingProxyType`` wrappers, so no consumer
can mutate the catalog at runtime.
"""

import re
from dataclasses import dataclass
from types import MappingProxyType
from typing import Dict, Mapping, Optional, Tuple

# ------------------------------------------------------------------
# Controlled constants
# ------------------------------------------------------------------

# Every rule/diagnostic in this catalog belongs to the DB access guard.
GUARD_DB_ACCESS = "db_access"

# Multiplicity is the only supported baseline multiplicity: same-rule
# occurrences inside one method increment a per-fingerprint count instead of
# collapsing into a single finding.
MULTIPLICITY_COUNT = "count"

# Code pattern shared with the finding model: [A-Z][A-Z0-9_]{2,63}.
_CODE_RE = re.compile(r"^[A-Z][A-Z0-9_]{2,63}$")


def _validate_code(code: str) -> str:
    if not isinstance(code, str):
        raise TypeError("code must be a string")
    if _CODE_RE.fullmatch(code) is None:
        raise ValueError(f"invalid controlled code: {code!r}")
    return code


def _validate_guard(guard: str) -> str:
    if not isinstance(guard, str) or not guard:
        raise ValueError("guard must be a non-empty string")
    return guard


# ------------------------------------------------------------------
# Metadata models (immutable)
# ------------------------------------------------------------------


@dataclass(frozen=True)
class RuleProfile:
    """Immutable metadata for one baseline-able policy rule.

    ``identity_fields`` lists the ``GuardFinding`` fields (dotted paths such
    as ``symbol.owner`` or ``identity.dao``) that participate in the stable
    fingerprint for this rule. ``multiplicity`` declares how occurrences of
    the same fingerprint accumulate; only ``"count"`` is supported.
    ``baseline_able`` is always ``True`` for policy rules.
    """

    code: str
    guard: str
    identity_fields: Tuple[str, ...]
    multiplicity: str
    description: str
    baseline_able: bool = True

    def __post_init__(self) -> None:
        object.__setattr__(self, "code", _validate_code(self.code))
        object.__setattr__(self, "guard", _validate_guard(self.guard))
        fields = self.identity_fields
        if not isinstance(fields, tuple) or not fields:
            raise ValueError(
                f"rule {self.code} must declare non-empty identity_fields"
            )
        for field in fields:
            if not isinstance(field, str) or not field:
                raise ValueError(
                    f"rule {self.code} identity_fields must be non-empty strings"
                )
        object.__setattr__(self, "identity_fields", fields)
        if self.multiplicity != MULTIPLICITY_COUNT:
            raise ValueError(
                f"rule {self.code} multiplicity must be "
                f"{MULTIPLICITY_COUNT!r}, got {self.multiplicity!r}"
            )
        if not isinstance(self.description, str) or not self.description:
            raise ValueError(f"rule {self.code} must have a description")
        if self.baseline_able is not True:
            raise ValueError(f"rule {self.code} must be baseline-able")


@dataclass(frozen=True)
class DiagnosticProfile:
    """Immutable metadata for one infrastructure diagnostic code.

    Diagnostics describe guard/parser infrastructure failures. They are never
    baseline-able (``baseline_able`` is always ``False``): a report containing
    any infrastructure diagnostic must exit 2 and must not emit partial
    authorization success.
    """

    code: str
    guard: str
    description: str
    baseline_able: bool = False

    def __post_init__(self) -> None:
        object.__setattr__(self, "code", _validate_code(self.code))
        object.__setattr__(self, "guard", _validate_guard(self.guard))
        if not isinstance(self.description, str) or not self.description:
            raise ValueError(f"diagnostic {self.code} must have a description")
        if self.baseline_able is not False:
            raise ValueError(f"diagnostic {self.code} must not be baseline-able")


# ------------------------------------------------------------------
# Catalog entries
# ------------------------------------------------------------------

_RULE_ENTRIES: Dict[str, RuleProfile] = {
    "DB_UNAUTHORIZED_MUTATION": RuleProfile(
        code="DB_UNAUTHORIZED_MUTATION",
        guard=GUARD_DB_ACCESS,
        identity_fields=(
            "path",
            "symbol.owner",
            "symbol.name",
            "symbol.receiver",
            "symbol.parameters",
            "identity.dao",
            "identity.accessor",
            "identity.operation",
            "identity.mutation_kind",
            "identity.call_form",
        ),
        multiplicity=MULTIPLICITY_COUNT,
        description="Mutation is not owned by an exact DB policy entry",
    ),
    "DB_MISSING_WRITE_BARRIER": RuleProfile(
        code="DB_MISSING_WRITE_BARRIER",
        guard=GUARD_DB_ACCESS,
        identity_fields=(
            "path",
            "symbol.owner",
            "symbol.name",
            "symbol.receiver",
            "symbol.parameters",
            "identity.dao",
            "identity.operation",
        ),
        multiplicity=MULTIPLICITY_COUNT,
        description="DB write is missing its required write-barrier guard",
    ),
    "DB_FORBIDDEN_STRUCTURAL_OPERATION": RuleProfile(
        code="DB_FORBIDDEN_STRUCTURAL_OPERATION",
        guard=GUARD_DB_ACCESS,
        identity_fields=(
            "path",
            "symbol.owner",
            "symbol.name",
            "identity.operation",
        ),
        multiplicity=MULTIPLICITY_COUNT,
        description="Forbidden structural DB operation was performed",
    ),
}

_DIAGNOSTIC_ENTRIES: Dict[str, DiagnosticProfile] = {
    "DB_SOURCE_UNREADABLE": DiagnosticProfile(
        code="DB_SOURCE_UNREADABLE",
        guard=GUARD_DB_ACCESS,
        description="Source file cannot be read",
    ),
    "DB_METHOD_BODY_UNSUPPORTED": DiagnosticProfile(
        code="DB_METHOD_BODY_UNSUPPORTED",
        guard=GUARD_DB_ACCESS,
        description="Method body contains unsupported syntax",
    ),
    "DB_EXPRESSION_BODY_UNSUPPORTED": DiagnosticProfile(
        code="DB_EXPRESSION_BODY_UNSUPPORTED",
        guard=GUARD_DB_ACCESS,
        description="Expression-body function not analyzable",
    ),
    "DB_DAO_SCOPE_UNRESOLVED": DiagnosticProfile(
        code="DB_DAO_SCOPE_UNRESOLVED",
        guard=GUARD_DB_ACCESS,
        description="DAO call scope cannot be determined",
    ),
    "DB_CALL_TARGET_AMBIGUOUS": DiagnosticProfile(
        code="DB_CALL_TARGET_AMBIGUOUS",
        guard=GUARD_DB_ACCESS,
        description="Call target resolution is ambiguous",
    ),
    "DB_POLICY_SOURCE_EVIDENCE_INVALID": DiagnosticProfile(
        code="DB_POLICY_SOURCE_EVIDENCE_INVALID",
        guard=GUARD_DB_ACCESS,
        description="Policy entry cannot be verified against source",
    ),
    "DB_ROOM_QUERY_UNCLASSIFIABLE": DiagnosticProfile(
        code="DB_ROOM_QUERY_UNCLASSIFIABLE",
        guard=GUARD_DB_ACCESS,
        description="Room @Query SQL cannot be classified",
    ),
    "DB_SIGNATURE_UNRESOLVED": DiagnosticProfile(
        code="DB_SIGNATURE_UNRESOLVED",
        guard=GUARD_DB_ACCESS,
        description="Exact callable signature cannot be resolved",
    ),
    "DB_DAO_INHERITANCE_UNRESOLVED": DiagnosticProfile(
        code="DB_DAO_INHERITANCE_UNRESOLVED",
        guard=GUARD_DB_ACCESS,
        description="DAO inheritance chain is broken",
    ),
}

# ------------------------------------------------------------------
# Immutable catalog mappings
# ------------------------------------------------------------------

# MappingProxyType makes the catalog read-only at runtime; the keys are
# controlled strings and the values are immutable frozen dataclasses.
RULE_PROFILES: Mapping[str, RuleProfile] = MappingProxyType(
    dict(sorted(_RULE_ENTRIES.items()))
)

DIAGNOSTIC_PROFILES: Mapping[str, DiagnosticProfile] = MappingProxyType(
    dict(sorted(_DIAGNOSTIC_ENTRIES.items()))
)

ALL_PROFILES: Mapping[str, object] = MappingProxyType(
    dict(sorted({**_RULE_ENTRIES, **_DIAGNOSTIC_ENTRIES}.items()))
)

# ------------------------------------------------------------------
# Code sets (immutable)
# ------------------------------------------------------------------

RULE_CODES = frozenset(RULE_PROFILES)
DIAGNOSTIC_CODES = frozenset(DIAGNOSTIC_PROFILES)
ALL_CODES = RULE_CODES | DIAGNOSTIC_CODES


# ------------------------------------------------------------------
# Lookups
# ------------------------------------------------------------------


def known_rule(rule: object) -> Optional[RuleProfile]:
    """Return the immutable ``RuleProfile`` for a known policy rule.

    Returns ``None`` (falsey, i.e. no metadata) for unknown or non-string
    values. An unknown rule ID is an infrastructure failure; callers that
    need an explicit boolean can use ``is_known_rule``.
    """
    if not isinstance(rule, str):
        return None
    return RULE_PROFILES.get(rule)


def known_diagnostic(code: object) -> Optional[DiagnosticProfile]:
    """Return the immutable ``DiagnosticProfile`` for a known diagnostic code.

    Returns ``None`` (falsey, i.e. no metadata) for unknown or non-string
    values. Diagnostics are never baseline-able; callers that need an
    explicit boolean can use ``is_known_diagnostic``.
    """
    if not isinstance(code, str):
        return None
    return DIAGNOSTIC_PROFILES.get(code)


def is_known_rule(rule: object) -> bool:
    """Return ``True`` when ``rule`` is a registered policy rule code."""
    return isinstance(rule, str) and rule in RULE_PROFILES


def is_known_diagnostic(code: object) -> bool:
    """Return ``True`` when ``code`` is a registered diagnostic code."""
    return isinstance(code, str) and code in DIAGNOSTIC_PROFILES
