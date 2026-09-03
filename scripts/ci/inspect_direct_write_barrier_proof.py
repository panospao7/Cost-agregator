"""GR-12 direct write-barrier proof shadow CLI (read-only, report-only).

For every active-policy mutation with barrierMode: direct, emits BOTH the
legacy lexical gate result (the pre-GR-12 "barrier text appears earlier in
the callable" rule, computed here for comparison only) AND the GR-12 CFG
dominance proof result.  NEVER modifies policy, source, baseline, or any
active gate result, and is NOT a ratchet child.

Exit contract (per docs/guardrails/PR-GR-12 plan):
  0  every direct entry PROVEN by the dominance proof
  1  valid analysis with one or more COUNTEREXAMPLE (and none unsupported)
  2  any UNSUPPORTED / INFRASTRUCTURE result, or an infrastructure failure
     (invalid policy/roots, uncorrelatable mutation, any crash)

Outputs (deterministic JSON, atomic write):
  <output>            legacy lexical shadow report ("before")
  <output>.after.json GR-12 dominance proof report ("after")
  <output>.after.sha256
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys

_PROJECT_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
)
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)

from scripts.db_guard.structural_analysis.barrier_proof import (  # noqa: E402
    CANONICAL_BARRIER_CONTRACT_V1,
    ReceiverTypeResolver,
    prove_direct_barrier,
)
from scripts.db_guard.structural_analysis.barrier_markers import (  # noqa: E402
    collect_barrier_markers,
)
from scripts.db_guard.declaration_scanner import (  # noqa: E402
    scan_production_declarations,
)
from scripts.db_guard.mutation_observation import MutationObservation  # noqa: E402
from scripts.db_guard.policy_v2_loader import load_policy_v2  # noqa: E402
from scripts.db_guard.source_roots import resolve_source_root_set  # noqa: E402
from scripts.db_guard.structural_analysis.model import (  # noqa: E402
    MutationSite,
    SourceSpan,
)
from scripts.db_guard.scanner import scan_db_access  # noqa: E402
from scripts.ci.inspect_db_structural_model import (  # noqa: E402
    _expression_body_start,
    _project_root_of,
    _split_callable_key,
    _span_of,
)
from scripts.kotlin_callable_parser import mask_kotlin_source  # noqa: E402

__all__ = ["build_direct_proof_shadow", "main"]

_EXIT_ALL_PROVEN = 0
_EXIT_COUNTEREXAMPLE = 1
_EXIT_UNSUPPORTED = 2

# The legacy lexical gate (scanner.py): canonical barrier text anywhere
# earlier in the declaration authorizes the mutation.  Replicated here ONLY
# for the before/after comparison; the active gate is replaced in PR-GR-12
# Step 7 by the shared proof index.
_LEGACY_BARRIER_RE = re.compile(
    r"\bwriteBarrier\s*\.\s*(?:checkWritesAllowed|runWrite)\s*\("
)

_HEADER_NAME_RE = re.compile(r"([A-Za-z_][A-Za-z0-9_]*)\s*\(")


def _legacy_guarded(masked: str, declaration_start: int, call_start: int) -> bool:
    return bool(_LEGACY_BARRIER_RE.search(masked[declaration_start:call_start]))


def _fun_body_span(masked: str, declaration) -> SourceSpan | None:
    if declaration.body_start is not None and declaration.body_end is not None:
        body_start, body_end = declaration.body_start, declaration.body_end
    else:
        expression_start = _expression_body_start(
            masked, declaration.source_start, declaration.source_end
        )
        if expression_start is None:
            return None
        body_start, body_end = expression_start, declaration.source_end
    return SourceSpan(
        start=body_start,
        end=body_end,
        line=masked.count("\n", 0, body_start) + 1,
        column=1,
    )


def build_direct_proof_shadow(
    root: str,
    policy_path_value: str | None,
    target_sha: str | None = None,
) -> tuple[dict, dict, int]:
    """Run the legacy + dominance shadow comparison.

    Returns (before_report, after_report, exit_code).  Raises nothing by
    contract: infrastructure failures are encoded in the reports and the
    exit code.
    """
    project_root = _project_root_of(root)
    policy_file = (
        policy_path_value
        if policy_path_value and os.path.isabs(policy_path_value)
        else os.path.join(project_root, policy_path_value or "config/guards/db_ownership_policy.yml")
    )

    failure_reasons: list[str] = []
    root_set, root_diagnostics = resolve_source_root_set(project_root)
    if root_set is None or root_diagnostics:
        failure_reasons.extend(sorted({code for code, _ in root_diagnostics}))
    policy_entries = None
    if not failure_reasons:
        policy_entries, policy_errors = load_policy_v2(policy_file)
        if policy_entries is None:
            failure_reasons.append("DB_POLICY_SOURCE_EVIDENCE_INVALID")

    observations: list[MutationObservation] = []
    scan_finding_count = 0
    scan_diagnostic_codes: list[str] = []
    if not failure_reasons and policy_entries is not None:
        scan_result = scan_db_access(
            root,
            policy_entries,
            os.path.join(project_root, "config", "guards", "db_structural_exceptions.yml"),
            os.path.join(project_root, "config", "guards", "db_raw_query_classification.yml"),
            mutation_observation_sink=observations,
        )
        scan_finding_count = len(getattr(scan_result, "findings", ()))
        scan_diagnostic_codes = sorted(
            {diagnostic.code for diagnostic in getattr(scan_result, "diagnostics", ())}
        )

    direct_entries = [
        entry for entry in (policy_entries or []) if entry.barrier_mode.value == "direct"
    ]
    obs_by_mutation_key: dict[str, list[MutationObservation]] = {}
    obs_by_callable: dict[str, list[MutationObservation]] = {}
    for observation in observations:
        mutation_key = (
            observation.callable_key
            + "|"
            + observation.dao_accessor
            + "|"
            + observation.dao_fqcn
            + "|"
            + observation.operation
        )
        obs_by_mutation_key.setdefault(mutation_key, []).append(observation)
        obs_by_callable.setdefault(observation.callable_key, []).append(observation)

    # Declaration index (same correlation contract as the GR-11 shadow CLI:
    # exact span containment, ambiguity fails closed).
    declaration_index: dict[tuple[str, str, str], list] = {}
    file_text: dict[str, str] = {}
    if not failure_reasons:
        scan = scan_production_declarations(root, root_set=root_set)
        for declaration in scan.helper_ranges:
            if declaration.kind != "function" or declaration.source_start is None:
                continue
            if declaration.path not in file_text:
                with open(
                    os.path.join(project_root, declaration.path), "r", encoding="utf-8"
                ) as handle:
                    file_text[declaration.path] = handle.read()
            masked = mask_kotlin_source(file_text[declaration.path])
            header_end = (
                declaration.body_start
                if declaration.body_start is not None
                else declaration.source_end
            )
            match = _HEADER_NAME_RE.search(masked[declaration.source_start:header_end])
            if not match:
                continue
            declaration_index.setdefault(
                (declaration.path, declaration.owner_fqcn, match.group(1)), []
            ).append(declaration)

    before_rows: list[dict] = []
    after_rows: list[dict] = []
    uncorrelated: list[str] = []
    for entry in direct_entries:
        mutation_key = entry.mutation_key().canonical_key()
        callable_key = entry.callable_key().canonical_key()
        matches = obs_by_mutation_key.get(mutation_key, [])
        if len(matches) != 1:
            before_rows.append(
                {
                    "mutationKey": mutation_key,
                    "callableKey": callable_key,
                    "path": entry.path,
                    "legacyStatus": "NOT_OBSERVED",
                    "directPolicyRow": True,
                }
            )
            after_rows.append(
                {
                    "mutationKey": mutation_key,
                    "callableKey": callable_key,
                    "path": entry.path,
                    "proofStatus": "UNSUPPORTED",
                    "diagnosticCode": "DB_DIRECT_BARRIER_PROOF_UNSUPPORTED",
                    "reason": "no exact D4-resolved mutation observation",
                }
            )
            uncorrelated.append(mutation_key)
            continue
        observation = matches[0]

        def _unknown_before_row(reason: str) -> dict:
            return {
                "mutationKey": mutation_key,
                "callableKey": callable_key,
                "path": entry.path,
                "legacyStatus": "UNKNOWN",
                "legacyRule": "barrier-text-earlier-in-callable",
                "mutationLine": observation.line,
                "directPolicyRow": True,
                "reason": reason,
            }

        identity = _split_callable_key(observation.callable_key)
        if identity is None:
            uncorrelated.append(mutation_key)
            before_rows.append(_unknown_before_row("observation callable key uncorrelatable"))
            after_rows.append(
                {
                    "mutationKey": mutation_key,
                    "callableKey": callable_key,
                    "path": entry.path,
                    "proofStatus": "INFRASTRUCTURE_FAILURE",
                    "diagnosticCode": "DB_DIRECT_BARRIER_CONTRACT_INVALID",
                    "reason": "observation callable key uncorrelatable",
                }
            )
            continue
        obs_path, owner_fqcn, _kind, method = identity
        candidates = declaration_index.get((obs_path, owner_fqcn, method), [])
        containing = [
            declaration
            for declaration in candidates
            if _span_of(declaration)[0] <= observation.source_start < _span_of(declaration)[1]
        ]
        if len(containing) != 1:
            uncorrelated.append(mutation_key)
            before_rows.append(
                _unknown_before_row("observed mutation not correlatable to exactly one declaration")
            )
            after_rows.append(
                {
                    "mutationKey": mutation_key,
                    "callableKey": callable_key,
                    "path": entry.path,
                    "proofStatus": "INFRASTRUCTURE_FAILURE",
                    "diagnosticCode": "DB_DIRECT_BARRIER_CONTRACT_INVALID",
                    "reason": "observed mutation not correlatable to exactly one declaration",
                }
            )
            continue
        declaration = containing[0]
        if declaration.path not in file_text:
            with open(
                os.path.join(project_root, declaration.path), "r", encoding="utf-8"
            ) as handle:
                file_text[declaration.path] = handle.read()
        masked = mask_kotlin_source(file_text[declaration.path])
        body_span = _fun_body_span(masked, declaration)
        legacy_status = (
            "PASS"
            if _legacy_guarded(masked, _span_of(declaration)[0], observation.source_start)
            else "FAIL"
        )
        before_rows.append(
            {
                "mutationKey": mutation_key,
                "callableKey": callable_key,
                "path": entry.path,
                "legacyStatus": legacy_status,
                "legacyRule": "barrier-text-earlier-in-callable",
                "mutationLine": observation.line,
                "directPolicyRow": True,
            }
        )
        if body_span is None:
            after_rows.append(
                {
                    "mutationKey": mutation_key,
                    "callableKey": callable_key,
                    "path": entry.path,
                    "proofStatus": "UNSUPPORTED",
                    "diagnosticCode": "DB_DIRECT_BARRIER_PROOF_UNSUPPORTED",
                    "reason": "expression-body callable",
                }
            )
            continue
        from scripts.db_guard.structural_analysis.tokenizer import (
            parse_callable_body,
        )
        from scripts.db_guard.structural_analysis.shadow_report import (
            _default_opacity_predicate,
        )

        # The parse must use the SAME opacity gate and the SAME site set the
        # structural shadow pipeline uses: ALL resolved mutations of the
        # callable (a lambda hiding any other row's mutation must never be
        # modeled opaque).
        callable_sites = tuple(
            MutationSite.from_observation(item)
            for item in sorted(
                obs_by_callable.get(observation.callable_key, ()),
                key=lambda item: (item.source_start, item.source_end),
            )
        )
        opacity = _default_opacity_predicate(masked, body_span, callable_sites)
        parse_result = parse_callable_body(
            masked, body_span, lambda_opacity_predicate=opacity
        )
        if parse_result.unsupported:
            after_rows.append(
                {
                    "mutationKey": mutation_key,
                    "callableKey": callable_key,
                    "path": entry.path,
                    "proofStatus": "UNSUPPORTED",
                    "diagnosticCode": "DB_DIRECT_BARRIER_PROOF_UNSUPPORTED",
                    "reason": "callable not conservatively modelable",
                }
            )
            continue
        mutation_site = next(
            item for item in callable_sites if item.span.start == observation.source_start
        )
        markers = collect_barrier_markers(parse_result, masked)
        from scripts.db_guard.structural_analysis.cfg import build_callable_cfg

        try:
            cfg, cfg_diagnostics = build_callable_cfg(
                parse_result,
                callable_sites,
                markers,
                path=entry.path,
                callable_key=observation.callable_key,
            )
        except (TypeError, ValueError):
            after_rows.append(
                {
                    "mutationKey": mutation_key,
                    "callableKey": callable_key,
                    "path": entry.path,
                    "proofStatus": "INFRASTRUCTURE_FAILURE",
                    "diagnosticCode": "DB_DIRECT_BARRIER_CONTRACT_INVALID",
                }
            )
            continue
        resolver = ReceiverTypeResolver(masked)
        results, proof_diagnostics = prove_direct_barrier(
            masked,
            body_span,
            cfg,
            (mutation_site,),
            CANONICAL_BARRIER_CONTRACT_V1,
            resolver,
            path=entry.path,
            callable_key=observation.callable_key,
        )
        result = results[0]
        after_rows.append(
            {
                "mutationKey": mutation_key,
                "callableKey": callable_key,
                "path": entry.path,
                "proofStatus": result.status.value,
                "proofVersion": result.proof_version,
                "barrierForm": result.barrier_form,
                "mutationLine": observation.line,
                "counterexampleNodeKinds": list(result.counterexample_node_kinds),
                "counterexampleLineSequence": list(result.counterexample_line_sequence),
                "diagnosticCode": result.diagnostic_code,
                "proofDiagnostics": list(proof_diagnostics),
            }
        )

    before_rows.sort(key=lambda row: row["mutationKey"])
    after_rows.sort(key=lambda row: row["mutationKey"])
    contract_version = CANONICAL_BARRIER_CONTRACT_V1.contract_version
    before_report = {
        "schemaVersion": 1,
        "reportOnly": True,
        "engine": "legacy-lexical",
        "legacyRule": "barrier-text-earlier-in-callable",
        "targetSha": target_sha,
        "canonicalBarrierContractVersion": contract_version,
        "summary": {
            "directEntryCount": len(before_rows),
            "legacyPassCount": sum(1 for r in before_rows if r.get("legacyStatus") == "PASS"),
            "legacyFailCount": sum(1 for r in before_rows if r.get("legacyStatus") == "FAIL"),
            "legacyUnknownCount": sum(1 for r in before_rows if r.get("legacyStatus") == "UNKNOWN"),
            "notObservedCount": sum(1 for r in before_rows if r.get("legacyStatus") == "NOT_OBSERVED"),
        },
        "entries": before_rows,
        "corpus": {
            "scanFindingCount": scan_finding_count,
            "scanDiagnosticCodes": scan_diagnostic_codes,
            "uncorrelatedMutationKeys": sorted(set(uncorrelated)),
        },
        "infrastructure": {"failureReasons": sorted(set(failure_reasons))},
    }
    after_summary = {
        "directEntryCount": len(after_rows),
        "provenCount": sum(1 for r in after_rows if r.get("proofStatus") == "PROVEN"),
        "counterexampleCount": sum(1 for r in after_rows if r.get("proofStatus") == "COUNTEREXAMPLE"),
        "unsupportedCount": sum(1 for r in after_rows if r.get("proofStatus") == "UNSUPPORTED"),
        "infrastructureFailureCount": sum(
            1 for r in after_rows if r.get("proofStatus") == "INFRASTRUCTURE_FAILURE"
        ),
    }
    after_report = {
        "schemaVersion": 1,
        "reportOnly": True,
        "engine": "gr12-dominance-proof",
        "proofVersion": 1,
        "targetSha": target_sha,
        "canonicalBarrierContractVersion": contract_version,
        "summary": after_summary,
        "entries": after_rows,
        "infrastructure": {"failureReasons": sorted(set(failure_reasons))},
    }

    if failure_reasons or after_summary["infrastructureFailureCount"]:
        exit_code = _EXIT_UNSUPPORTED
    elif after_summary["unsupportedCount"]:
        exit_code = _EXIT_UNSUPPORTED
    elif after_summary["counterexampleCount"]:
        exit_code = _EXIT_COUNTEREXAMPLE
    else:
        exit_code = _EXIT_ALL_PROVEN
    return before_report, after_report, exit_code


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
        description="GR-12 direct write-barrier proof shadow (read-only)."
    )
    parser.add_argument("--root", default=_PROJECT_ROOT)
    parser.add_argument("--policy", default=None)
    parser.add_argument("--output", required=True)
    parser.add_argument("--target-sha", default=None)
    args = parser.parse_args(argv)

    try:
        before_report, after_report, exit_code = build_direct_proof_shadow(
            args.root, args.policy, target_sha=args.target_sha
        )
    except Exception:
        failure = {
            "schemaVersion": 1,
            "reportOnly": True,
            "engine": "gr12-dominance-proof",
            "infrastructure": {"failureReasons": ["DB_DIRECT_BARRIER_REPORT_INVALID"]},
            "summary": {
                "directEntryCount": 0,
                "provenCount": 0,
                "counterexampleCount": 0,
                "unsupportedCount": 0,
                "infrastructureFailureCount": 1,
            },
            "entries": [],
        }
        _write_report_atomic(args.output, failure)
        _write_report_atomic(args.output + ".after.json", failure)
        return _EXIT_UNSUPPORTED
    _write_report_atomic(args.output, before_report)
    after_path = args.output + ".after.json"
    _write_report_atomic(after_path, after_report)
    digest = hashlib.sha256(
        json.dumps(after_report, indent=2, sort_keys=False).encode("utf-8") + b"\n"
    ).hexdigest()
    with open(after_path + ".sha256", "w", encoding="utf-8", newline="\n") as handle:
        handle.write(digest + "  " + os.path.basename(after_path) + "\n")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
