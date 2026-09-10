"""Shared resolved-mutation observation seam (PR-GR-11 Slice 1).

One typed, immutable record of a DAO mutation that the D4 scanner
(``scan_db_access``) has FULLY resolved through its existing DAO-resolution
path: receiver typing, DAO FQCN narrowing, overload disambiguation, and
mutator-gate membership.  The scanner constructs every observation through
:func:`build_mutation_observation` at the exact point where it used to build
its authorization comparison, and consumes the observation's fields for the
``match_mutation`` call and both finding emissions.  There is no second
mutation detector here: this module scans nothing.

Slice-1 contracts (docs/architecture/DB_STRUCTURAL_ANALYSIS_MODEL.md):

- Observations carry resolved identity only.  No policy authorization state
  (matched/unmatched, barrierMode, findings) belongs in an observation.
- Source offsets (``source_start``/``source_end``) are internal coordinates.
  Report rendering is bounded to path/line/column via
  :meth:`MutationObservation.bounded_fields`; no raw source text ever flows
  through this module.
- ``callable_key`` is the exact v2 ``CallableKey.canonical_key()`` spelling:
  ``path|ownerFqcn|kind|method|receiver|null|param,param`` (receiver is the
  literal ``null`` when absent, parameter types joined verbatim with ``,`` --
  the same convention the policy model uses, including commas inside generic
  parameter spellings).
- ``source_identity`` is the resolved inventory mutator identity string
  (``daoPath::daoFqcn#operation(params)``), a bounded structural coordinate,
  never source text.
- Construction fails closed: any missing/empty identity, non-monotonic span,
  or non-positive line/column raises ``TypeError``/``ValueError`` instead of
  producing a half-resolved observation.
"""

from __future__ import annotations

from dataclasses import dataclass

__all__ = [
    "MutationObservation",
    "build_mutation_observation",
    "canonical_callable_key",
    "NULL_RECEIVER",
]

#: Spelling the v2 ``CallableKey.canonical_key()`` uses for an absent receiver.
NULL_RECEIVER = "null"

_IDENTITY_FIELDS = (
    "path",
    "callable_key",
    "dao_accessor",
    "dao_fqcn",
    "operation",
    "mutation_kind",
    "source_identity",
)
_OFFSET_FIELDS = ("source_start", "source_end", "line", "column")


def canonical_callable_key(
    path: str,
    owner_fqcn: str,
    kind,
    method: str,
    receiver,
    parameter_types,
) -> str:
    """Return the exact v2 callable-key spelling for a resolved callable.

    ``kind`` accepts a ``CallableKind`` enum member or its plain string value;
    both render as the kind's ``value`` so the key stays byte-identical to the
    policy model's canonical form.
    """
    kind_value = getattr(kind, "value", kind)
    receiver_part = receiver if receiver is not None else NULL_RECEIVER
    return "|".join(
        [
            path,
            owner_fqcn,
            str(kind_value),
            method,
            receiver_part,
            ",".join(parameter_types),
        ]
    )


@dataclass(frozen=True)
class MutationObservation:
    """One fully resolved DAO mutation site discovered by the D4 scanner.

    Immutable by construction; every field is a bounded structural coordinate
    (identity strings, offsets, line/column).  Carries no policy state and no
    source text.
    """

    path: str
    callable_key: str
    source_start: int
    source_end: int
    line: int
    column: int
    dao_accessor: str
    dao_fqcn: str
    operation: str
    mutation_kind: str
    source_identity: str

    def __post_init__(self) -> None:
        for name in _IDENTITY_FIELDS:
            value = getattr(self, name)
            if not isinstance(value, str) or not value:
                raise TypeError(
                    "MutationObservation.%s must be a non-empty string" % (name,)
                )
        for name in _OFFSET_FIELDS:
            value = getattr(self, name)
            if isinstance(value, bool) or not isinstance(value, int) or value < 0:
                raise TypeError(
                    "MutationObservation.%s must be a non-negative int" % (name,)
                )
        if self.source_end < self.source_start:
            raise ValueError(
                "MutationObservation.source_end must not precede source_start"
            )
        if self.line < 1 or self.column < 1:
            raise ValueError(
                "MutationObservation.line/column are 1-based and must be >= 1"
            )

    def bounded_fields(self) -> dict:
        """Bounded report rendering: path/line/column only.

        Source offsets and identity strings stay internal; a report built from
        this dict can never carry raw source text or internal span data.
        """
        return {"path": self.path, "line": self.line, "column": self.column}


def build_mutation_observation(
    *,
    path: str,
    owner_fqcn: str,
    kind,
    method: str,
    receiver,
    parameter_types,
    source: str,
    call_start: int,
    call_end: int,
    dao_accessor: str,
    dao_fqcn: str,
    operation: str,
    mutation_kind: str,
    source_identity: str,
) -> MutationObservation:
    """Build the observation for one mutation the D4 path already resolved.

    Every identity argument must be the SAME value the scanner passes to
    ``match_mutation`` for this call; ``source`` is the full file text the
    scanner is scanning and ``call_start``/``call_end`` the resolved call's
    half-open span inside it.  Line/column are derived 1-based from
    ``call_start`` exactly like the scanner's ``_line`` helper
    (``source.count("\n", 0, offset) + 1``), so observation coordinates and
    finding coordinates can never disagree.
    """
    line = source.count("\n", 0, call_start) + 1
    line_start = source.rfind("\n", 0, call_start) + 1
    column = call_start - line_start + 1
    return MutationObservation(
        path=path,
        callable_key=canonical_callable_key(
            path, owner_fqcn, kind, method, receiver, parameter_types
        ),
        source_start=call_start,
        source_end=call_end,
        line=line,
        column=column,
        dao_accessor=dao_accessor,
        dao_fqcn=dao_fqcn,
        operation=operation,
        mutation_kind=mutation_kind,
        source_identity=source_identity,
    )
