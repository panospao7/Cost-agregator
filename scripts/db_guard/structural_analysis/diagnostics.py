"""Shadow-only structural diagnostics (conservative, no proof claims).

GR-12 owns dominance. This module defines the closed diagnostic code set.
DB_STRUCTURAL_MODEL_GRAPH_INVARIANT_FAILED and
DB_STRUCTURAL_MODEL_REPORT_INVALID are internal/infrastructure codes:
raising them as diagnostics from source analysis is an infrastructure
failure (exit 2 semantics), never a "source unsupported" result.
"""
from __future__ import annotations

__all__ = ["DIAGNOSTIC_CODES", "make_diagnostic"]

DIAGNOSTIC_CODES = (
    "DB_STRUCTURAL_MODEL_CALLABLE_UNRESOLVED",
    "DB_STRUCTURAL_MODEL_BODY_UNSUPPORTED",
    "DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED",
    "DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED",
    "DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE",
    "DB_STRUCTURAL_MODEL_EXCEPTION_FLOW_UNSUPPORTED",
    "DB_STRUCTURAL_MODEL_MUTATION_SITE_UNRESOLVED",
    "DB_STRUCTURAL_MODEL_BARRIER_FORM_UNRECOGNIZED",
    "DB_STRUCTURAL_MODEL_GRAPH_INVARIANT_FAILED",
    "DB_STRUCTURAL_MODEL_REPORT_INVALID",
)


def make_diagnostic(code, path, line, callable_key=None, syntax_family=None):
    """Build a StructuralDiagnostic, rejecting unknown codes."""
    if code not in DIAGNOSTIC_CODES:
        raise ValueError("unknown structural diagnostic code: %r" % (code,))
    from .model import StructuralDiagnostic

    return StructuralDiagnostic(
        code=code,
        path=path,
        line=line,
        callable_key=callable_key,
        syntax_family=syntax_family,
    )
