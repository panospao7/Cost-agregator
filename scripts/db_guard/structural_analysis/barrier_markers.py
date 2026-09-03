"""Barrier-marker extraction over a parsed callable body (shadow-only).

Markers are SYNTAX OBSERVATION, never proof: regions recognized by the
tokenizer carry their own candidate kind (DIRECT_CHECK, DIRECT_SCOPE,
WORKER_GUARD_CANDIDATE) and become markers directly; any other barrier-like
call shape in the masked text stays UNKNOWN_BARRIER_LIKE_CALL.  Receiver
types are not resolved in GR-11, so ``receiver_fqcn`` is always None — an
honest unknown.  Markers never come from comments/strings (input is masked)
and never duplicate a recognized region's span.
"""
from __future__ import annotations

import re

from .model import BarrierMarker, BarrierMarkerKind, SourceSpan
from .tokenizer import (
    CallableBodyParse,
    RegionKind,
    _match_forward,
    _RE_LIKE_BARRIER,
    _RE_WORKER_GUARD,
)

__all__ = [
    "collect_barrier_markers",
    "barrier_like_call_spans",
    "lambda_opacity_predicate",
]

_WORKER_GUARD_RE = re.compile(
    r"\b[A-Za-z_][A-Za-z0-9_]*\s*\.\s*"
    r"(?P<method>runGuardedWithContext|runGuarded)\s*(?:\([^()]*\))?\s*[\(\{]"
)
_BARRIER_LIKE_RE = re.compile(
    r"\b(?P<receiver>[A-Za-z_][A-Za-z0-9_]*)\s*\.\s*"
    r"(?P<method>runWrite|checkWritesAllowed)\s*[\(\{]"
)

_REGION_METHODS = (
    ("runGuardedWithContext", "runGuardedWithContext"),
    ("runGuarded", "runGuarded"),
    ("runWrite", "runWrite"),
    ("checkWritesAllowed", "checkWritesAllowed"),
)


def _method_in_span(masked_text: str, span: SourceSpan) -> str:
    text = masked_text[span.start : span.end]
    for needle, method in _REGION_METHODS:
        if needle in text:
            return method
    return "unknown"


def _collect_regions(parse: CallableBodyParse, masked_text: str) -> list[BarrierMarker]:
    markers: list[BarrierMarker] = []

    def walk(regions) -> None:
        for region in regions:
            if region.barrier is not None:
                markers.append(
                    BarrierMarker(
                        kind=region.barrier,
                        span=region.span,
                        receiver_fqcn=None,
                        method=_method_in_span(masked_text, region.span),
                    )
                )
            walk(region.children)

    walk(parse.regions)
    return markers


def collect_barrier_markers(
    parse: CallableBodyParse, masked_text: str
) -> tuple[BarrierMarker, ...]:
    """Collect every barrier marker for one parsed callable body.

    ``masked_text`` must be the same masked text the parse came from.  Text
    occurrences inside a recognized barrier region are not re-reported; any
    other barrier-like call shape is UNKNOWN_BARRIER_LIKE_CALL (explicit
    uncertainty, never a successful marker).  Deterministic: sorted by
    (span.start, span.end, kind, method).
    """
    markers = _collect_regions(parse, masked_text)
    covered = [(marker.span.start, marker.span.end) for marker in markers]

    def inside_recognized(start: int, end: int) -> bool:
        return any(rec_start <= start and end <= rec_end for rec_start, rec_end in covered)

    for pattern, kind in (
        (_WORKER_GUARD_RE, BarrierMarkerKind.WORKER_GUARD_CANDIDATE),
        (_BARRIER_LIKE_RE, BarrierMarkerKind.UNKNOWN_BARRIER_LIKE_CALL),
    ):
        for match in pattern.finditer(masked_text, parse.body_span.start, parse.body_span.end):
            if inside_recognized(match.start(), match.end()):
                continue
            if (
                kind is BarrierMarkerKind.UNKNOWN_BARRIER_LIKE_CALL
                and match.group("receiver") == "writeBarrier"
            ):
                # A canonical-receiver call inside an unmodelable region is
                # silence, never a mislabeled unknown marker.
                continue
            markers.append(
                BarrierMarker(
                    kind=kind,
                    span=SourceSpan(
                        start=match.start(),
                        end=match.end(),
                        line=_line_at(masked_text, match.start()),
                        column=match.start() - _line_start(masked_text, match.start()) + 1,
                    ),
                    receiver_fqcn=None,
                    method=match.group("method"),
                )
            )

    markers.sort(key=lambda item: (item.span.start, item.span.end, item.kind.value, item.method))
    return tuple(markers)


def _line_start(text: str, offset: int) -> int:
    return text.rfind("\n", 0, offset) + 1


def _line_at(text: str, offset: int) -> int:
    return text.count("\n", 0, offset) + 1


def barrier_like_call_spans(
    masked_text: str, start: int, end: int
) -> tuple[tuple[int, int], ...]:
    """Spans of any barrier-shaped call site inside ``[start, end)``.

    Parse-independent (regex over masked text): canonical
    ``writeBarrier.runWrite {`` / ``writeBarrier.checkWritesAllowed(``
    receivers, any-receiver ``runWrite`` / ``checkWritesAllowed`` shapes, and
    worker-guard scopes.  Used by the opaque-lambda soundness gate so a
    lambda body is never modeled opaque when it hides a barrier call.
    """
    spans: set[tuple[int, int]] = set()
    for pattern in (_RE_LIKE_BARRIER, _RE_WORKER_GUARD):
        for match in pattern.finditer(masked_text, start, end):
            spans.add((match.start(), match.end()))
    return tuple(sorted(spans))


def _brace_groups(
    text: str, start: int, end: int
) -> tuple[tuple[int, int], ...] | None:
    """Matched ``{ ... }`` groups inside ``[start, end)``, or None when a
    brace never closes."""
    groups: list[tuple[int, int]] = []
    i = start
    while i < end:
        if text[i] == "{":
            close = _match_forward(text, i, end)
            if close < 0:
                return None
            groups.append((i, close))
            i = close
        else:
            i += 1
    return tuple(groups)


def lambda_opacity_predicate(
    masked_text: str,
    mutation_sites,
    barrier_like_spans: tuple[tuple[int, int], ...],
):
    """Build the soundness gate for opaque-lambda modeling.

    A brace-containing statement may be modeled as one opaque STATEMENT only
    when every brace group inside it contains no mutation-site start offset
    and no barrier-shaped call overlapping the group.  Anything else keeps
    the strict lambda-escape failure (fail closed).
    """
    site_starts = tuple(sorted(site.span.start for site in mutation_sites))

    def predicate(start: int, end: int) -> bool:
        groups = _brace_groups(masked_text, start, end)
        if groups is None:
            return False
        for group_start, group_end in groups:
            if any(group_start <= s < group_end for s in site_starts):
                return False
            if any(
                marker_start < group_end and marker_end > group_start
                for marker_start, marker_end in barrier_like_spans
            ):
                return False
        return True

    return predicate
