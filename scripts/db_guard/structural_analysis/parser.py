"""Per-callable classification over the conservative tokenizer (shadow-only).

Turns one :class:`CallableBodyParse` into the GR-11 structural verdict for a
callable: SUPPORTED with the syntax families its body used, or
UNSUPPORTED_CONSERVATIVELY with one closed-code diagnostic per conservative
stop.  No dominance, mediation, or safety claim is produced here — that is
GR-12/GR-13.  Diagnostics carry bounded coordinates only (path, line,
callable key, syntax family); raw source text never enters this layer.
"""
from __future__ import annotations

from dataclasses import dataclass

from .diagnostics import make_diagnostic
from .model import AnalysisStatus, SourceSpan, StructuralDiagnostic, SyntaxFamily
from .tokenizer import CallableBodyParse, RegionKind, parse_callable_body

__all__ = [
    "CallableBodyClassification",
    "classify_callable_body",
    "analyze_callable_body",
]

_FAMILIES_BY_KIND = {
    RegionKind.IF: SyntaxFamily.IF_ELSE,
    RegionKind.WHEN: SyntaxFamily.WHEN,
    RegionKind.LOOP: SyntaxFamily.LOOP,
    RegionKind.TRY: SyntaxFamily.TRY_FINALLY,
    RegionKind.CATCH: SyntaxFamily.TRY_FINALLY,
    RegionKind.FINALLY: SyntaxFamily.TRY_FINALLY,
    RegionKind.BARRIER_SCOPE: SyntaxFamily.NESTED_LAMBDA,
}


@dataclass(frozen=True)
class CallableBodyClassification:
    """Conservative structural verdict for one callable body."""

    status: AnalysisStatus
    syntax_families: tuple[SyntaxFamily, ...]
    diagnostics: tuple[StructuralDiagnostic, ...]


def _region_families(parse: CallableBodyParse) -> tuple[SyntaxFamily, ...]:
    families: set[SyntaxFamily] = set()

    def walk(regions) -> None:
        for region in regions:
            family = _FAMILIES_BY_KIND.get(region.kind)
            if family is not None:
                families.add(family)
            walk(region.children)

    walk(parse.regions)
    return tuple(sorted(families, key=lambda item: item.value))


def classify_callable_body(
    parse: CallableBodyParse, *, path: str, callable_key: str
) -> CallableBodyClassification:
    """Classify one tokenizer result for an exact callable identity."""
    if parse.unsupported:
        diagnostics = tuple(
            make_diagnostic(
                finding.code,
                path,
                finding.span.line,
                callable_key=callable_key,
                syntax_family=SyntaxFamily.UNKNOWN_CONSTRUCT,
            )
            for finding in parse.unsupported
        )
        return CallableBodyClassification(
            status=AnalysisStatus.UNSUPPORTED_CONSERVATIVELY,
            syntax_families=(),
            diagnostics=diagnostics,
        )
    return CallableBodyClassification(
        status=AnalysisStatus.SUPPORTED,
        syntax_families=_region_families(parse),
        diagnostics=(),
    )


def analyze_callable_body(
    masked_text: str,
    body_span: SourceSpan,
    *,
    path: str,
    callable_key: str,
) -> CallableBodyClassification:
    """Parse and classify one callable body span in one step."""
    parse = parse_callable_body(masked_text, body_span)
    return classify_callable_body(parse, path=path, callable_key=callable_key)
