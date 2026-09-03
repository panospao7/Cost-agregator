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
from .tokenizer import CallableBodyParse, RegionKind

__all__ = ["collect_barrier_markers"]

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
