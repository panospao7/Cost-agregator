"""GR-11 structural shadow CLI (read-only, report-only, opt-in).

Runs the conservative structural analysis over every callable that the D4
scanner fully resolved as a DAO mutation in the current tree, plus corpus
accounting against the active v2 policy.  It NEVER modifies policy,
baseline, source, or any active gate result, and it is NOT a ratchet child.

Exit contract:
  0  every analyzed callable is SUPPORTED and fully accounted for
  1  valid analysis with one or more UNSUPPORTED_CONSERVATIVELY callables
  2  infrastructure/correlation failure: invalid policy/roots, an observed
     mutation whose callable cannot be correlated to one exact declaration,
     or any INFRASTRUCTURE_FAILURE result

The report is deterministic JSON: sorted callables, bounded path/line
diagnostics, no raw source, no absolute paths, no timestamps.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys

_PROJECT_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
)
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)

from scripts.db_guard.declaration_scanner import (  # noqa: E402
    scan_production_declarations,
)
from scripts.db_guard.mutation_observation import (  # noqa: E402
    MutationObservation,
    canonical_callable_key,
)
from scripts.db_guard.policy_v2_loader import load_policy_v2  # noqa: E402
from scripts.db_guard.source_roots import resolve_source_root_set  # noqa: E402
from scripts.db_guard.scanner import scan_db_access  # noqa: E402
from scripts.db_guard.structural_analysis.barrier_markers import (  # noqa: E402
    collect_barrier_markers,
)
from scripts.db_guard.structural_analysis.model import (  # noqa: E402
    AnalysisStatus,
    MutationSite,
    SourceSpan,
)
from scripts.db_guard.structural_analysis.shadow_report import (  # noqa: E402
    analyze_callable_structurally,
    build_shadow_report,
)
from scripts.kotlin_callable_parser import mask_kotlin_source  # noqa: E402

__all__ = ["build_structural_shadow", "main"]

_EXIT_OK = 0
_EXIT_UNSUPPORTED = 1
_EXIT_INFRASTRUCTURE = 2

_HEADER_NAME_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*\(")


def _span_of(declaration) -> tuple[int, int]:
    """Analysis span of a declaration: braced body, or full declaration span
    for expression-bodied members (whose body the scanner records as None)."""
    if declaration.body_start is not None and declaration.body_end is not None:
        return declaration.body_start, declaration.body_end
    return declaration.source_start, declaration.source_end


def _expression_body_start(masked: str, start: int, end: int) -> int | None:
    """Offset of the expression-body ``=`` of a declaration, or None.

    The first standalone ``=`` after the header's parameter list; arrows
    (``->``), equality/comparison operators, and defaults inside the header
    are skipped by construction (the scan starts after the balanced ``(...)``).
    """
    open_paren = masked.find("(", start, end)
    if open_paren < 0:
        return None
    depth = 0
    close_paren = None
    for offset in range(open_paren, end):
        character = masked[offset]
        if character == "(":
            depth += 1
        elif character == ")":
            depth -= 1
            if depth == 0:
                close_paren = offset
                break
    if close_paren is None:
        return None
    for offset in range(close_paren + 1, end):
        if masked[offset] != "=":
            continue
        previous = masked[offset - 1] if offset > 0 else ""
        following = masked[offset + 1] if offset + 1 < end else ""
        if previous in ("-!", "<>=") or following == "=":
            continue
        return offset
    return None


def _project_root_of(root: str) -> str:
    supplied = os.path.abspath(os.fspath(root))
    parts = os.path.basename(supplied)
    if parts == "java" and os.path.basename(os.path.dirname(supplied)) == "main":
        return os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(supplied))))
    return supplied


def _policy_path(root: str, value: str | None) -> str:
    candidate = value or os.path.join(root, "config", "guards", "db_ownership_policy.yml")
    return candidate if os.path.isabs(candidate) else os.path.join(root, candidate)


def _split_callable_key(callable_key: str) -> tuple[str, str, str, str] | None:
    """Return (path, owner_fqcn, kind, method) from a canonical key.

    The canonical key is ``path|ownerFqcn|kind|method|receiver|params``; the
    path is repository-relative and contains no pipes, so the first four
    pipe-separated segments are exact.
    """
    parts = callable_key.split("|")
    if len(parts) != 6 or not all(parts[:4]):
        return None
    return parts[0], parts[1], parts[2], parts[3]


def build_structural_shadow(
    root: str,
    policy_path_value: str | None,
    target_sha: str | None = None,
    include_graphs: bool = False,
) -> tuple[dict, int]:
    """Run the shadow analysis and return (report, exit_code).

    Raises nothing by contract: infrastructure failures are encoded in the
    report and the exit code.
    """
    project_root = _project_root_of(root)
    policy_file = _policy_path(project_root, policy_path_value)

    failure_reasons: list[str] = []
    results = []
    unaccounted: list[str] = []
    corpus_policy_callable_count = 0

    root_set, root_diagnostics = resolve_source_root_set(project_root)
    if root_set is None or root_diagnostics:
        failure_reasons.extend(
            sorted({code for code, _context in root_diagnostics})
        )
    policy_entries = None
    if not failure_reasons:
        policy_entries, policy_errors = load_policy_v2(policy_file)
        if policy_entries is None:
            failure_reasons.append("DB_POLICY_SOURCE_EVIDENCE_INVALID")

    observations: list[MutationObservation] = []
    scan_diagnostic_codes: list[str] = []
    scan_finding_count = 0
    if not failure_reasons:
        scan_result = scan_db_access(
            root,
            policy_entries,
            os.path.join(project_root, "config", "guards", "db_structural_exceptions.yml"),
            os.path.join(project_root, "config", "guards", "db_raw_query_classification.yml"),
            mutation_observation_sink=observations,
        )
        # Bounded scan facts only; trust/untrusted decisions stay with the
        # normal DB CLI, never with this shadow tool.
        scan_diagnostic_codes = sorted(
            {diagnostic.code for diagnostic in getattr(scan_result, "diagnostics", ())}
        )
        scan_finding_count = len(getattr(scan_result, "findings", ()))

    if not failure_reasons and policy_entries is not None:
        corpus_policy_callable_count = len(
            {
                canonical_callable_key(
                    entry.path,
                    entry.owner_fqcn,
                    entry.kind,
                    entry.method,
                    entry.receiver,
                    entry.parameter_types,
                )
                for entry in policy_entries
            }
        )

    declaration_index: dict[tuple[str, str, str], list] = {}
    file_text: dict[str, str] = {}
    if not failure_reasons:
        scan = scan_production_declarations(root, root_set=root_set)

        def _callable_name(masked: str, declaration) -> str | None:
            # The declaration scanner records no callable_name; the name is
            # the identifier before the header's first "(" in masked text.
            header_end = (
                declaration.body_start
                if declaration.body_start is not None
                else declaration.source_end
            )
            header = masked[declaration.source_start:header_end]
            match = _HEADER_NAME_RE.search(header)
            return match.group(1) if match else None

        for declaration in scan.helper_ranges:
            if declaration.kind != "function" or declaration.source_start is None:
                # Expression-bodied members (body_start None) stay in the
                # index: their full declaration span supports containment
                # and their analysis fails conservatively as expression-body.
                continue
            if declaration.path not in file_text:
                with open(
                    os.path.join(project_root, declaration.path),
                    "r",
                    encoding="utf-8",
                ) as handle:
                    file_text[declaration.path] = handle.read()
            masked = mask_kotlin_source(file_text[declaration.path])
            name = _callable_name(masked, declaration)
            if name is None:
                continue
            # Policy CallableKind spellings (function, top_level_function,
            # property_getter, ...) do not map 1:1 onto declaration kinds, so
            # correlation keys on identity only and disambiguates by
            # parameter tuple; ambiguity fails closed below.
            declaration_index.setdefault(
                (declaration.path, declaration.owner_fqcn, name),
                [],
            ).append(declaration)

        for observation in observations:
            identity = _split_callable_key(observation.callable_key)
            if identity is None:
                unaccounted.append(observation.callable_key)
                continue
            path, owner_fqcn, _kind, method = identity
            candidates = declaration_index.get((path, owner_fqcn, method), [])
            # The declaration scanner records no parameter tuples, so
            # overloads are disambiguated by EXACT span containment: the
            # observed DAO call offset must fall inside exactly one same-name
            # declaration's body.  Anything else fails closed.
            containing = [
                item
                for item in candidates
                if _span_of(item)[0] <= observation.source_start < _span_of(item)[1]
            ]
            if len(containing) != 1:
                # Correlation is tooling, not source syntax: an observed
                # mutation that cannot be matched to exactly one declared
                # callable is an infrastructure failure, never "unsupported".
                unaccounted.append(observation.callable_key)
                continue
            declaration = containing[0]
            if declaration.path not in file_text:
                with open(
                    os.path.join(project_root, declaration.path), "r", encoding="utf-8"
                ) as handle:
                    file_text[declaration.path] = handle.read()
            masked = mask_kotlin_source(file_text[declaration.path])
            if declaration.body_start is not None:
                body_start, body_end = declaration.body_start, declaration.body_end
            else:
                # Expression-bodied member: the declaration scanner records
                # no braced body.  Aim the span at the "=" so the tokenizer
                # fails conservatively with the expression-body reason —
                # never silently skipped.
                expression_start = _expression_body_start(
                    masked, declaration.source_start, declaration.source_end
                )
                if expression_start is None:
                    unaccounted.append(observation.callable_key)
                    continue
                body_start, body_end = expression_start, declaration.source_end
            body_line = masked.count("\n", 0, body_start) + 1
            body_span = SourceSpan(
                start=body_start,
                end=body_end,
                line=body_line,
                column=1,
            )
            sites = [
                MutationSite.from_observation(item)
                for item in observations
                if item.callable_key == observation.callable_key
                and item.path == declaration.path
            ]
            result = analyze_callable_structurally(
                masked,
                body_span,
                path=declaration.path,
                callable_key=observation.callable_key,
                mutation_sites=sites,
                barrier_marker_fn=collect_barrier_markers,
            )
            results.append(result)

    results.sort(key=lambda item: item.callable_key)
    seen_keys: set[str] = set()
    deduped_results = []
    for result in results:
        if result.callable_key in seen_keys:
            continue
        seen_keys.add(result.callable_key)
        deduped_results.append(result)

    analyzed = {result.callable_key for result in deduped_results}
    unaccounted = sorted(set(unaccounted) | (set() if failure_reasons else set()))
    report = build_shadow_report(deduped_results, target_sha=target_sha, include_graphs=include_graphs)
    report["corpus"] = {
        "policyCallableCount": corpus_policy_callable_count,
        "observedCallableCount": len(analyzed),
        "unaccountedCallableKeys": unaccounted,
        "scanFindingCount": scan_finding_count,
        "scanDiagnosticCodes": scan_diagnostic_codes,
    }
    report["infrastructure"] = {
        "failureReasons": sorted(set(failure_reasons)),
    }

    if failure_reasons or unaccounted:
        return report, _EXIT_INFRASTRUCTURE
    if any(
        result.status is AnalysisStatus.INFRASTRUCTURE_FAILURE
        for result in deduped_results
    ):
        return report, _EXIT_INFRASTRUCTURE
    if any(
        result.status is AnalysisStatus.UNSUPPORTED_CONSERVATIVELY
        for result in deduped_results
    ):
        return report, _EXIT_UNSUPPORTED
    return report, _EXIT_OK


def _write_report_atomic(path: str, report: dict) -> None:
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    temporary = path + ".tmp"
    with open(temporary, "w", encoding="utf-8", newline="\n") as handle:
        json.dump(report, handle, indent=2)
        handle.write("\n")
    os.replace(temporary, path)


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="GR-11 structural shadow report (read-only, report-only)."
    )
    parser.add_argument("--root", default=_PROJECT_ROOT)
    parser.add_argument("--policy", default=None)
    parser.add_argument("--output", required=True)
    parser.add_argument("--target-sha", default=None)
    parser.add_argument("--include-graphs", action="store_true")
    args = parser.parse_args(argv)

    try:
        report, exit_code = build_structural_shadow(
            args.root,
            args.policy,
            target_sha=args.target_sha,
            include_graphs=args.include_graphs,
        )
    except (OSError, TypeError, ValueError):
        report = {
            "schemaVersion": 1,
            "reportOnly": True,
            "targetSha": args.target_sha,
            "infrastructure": {"failureReasons": ["DB_STRUCTURAL_MODEL_REPORT_INVALID"]},
        }
        exit_code = _EXIT_INFRASTRUCTURE
    _write_report_atomic(args.output, report)
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
