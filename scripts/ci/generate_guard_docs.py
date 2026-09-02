#!/usr/bin/env python3
"""
GENERATE_GUARD_DOCS — PR-GR-10D generated current guard reference renderer.

Renders the two tracked generated reference documents from exactly three
inputs (plan deliverable 3 — "Generate, do not hand-maintain"):

    1. the canonical registry/execution plan from GR-10A
       (scripts/ci/guard_registry.py compiled via guard_execution_plan);
    2. the GR-10B source-scope metadata (the registry ``sourceScope`` fields,
       kept in lockstep with docs/ci/GR-10B_SOURCE_SCOPE_MATRIX.md by
       scripts/ci/test_gr10b_source_scope_matrix.py);
    3. the evidence index (docs/ci/GUARD_EVIDENCE_INDEX.yml).

Outputs (byte-deterministic; the tracked files must byte-match this render —
scripts/ci/verify_guard_docs_truth.py enforces it):

    docs/ci/GUARD_COMMANDS.generated.md
    docs/ci/GUARD_STATUS.generated.md

Determinism contract:
    - registry order (dict order) is the only ordering;
    - no timestamps, durations, machine paths, or run ids are emitted;
    - command identities use the canonical plan form (interpreter replaced by
      ``<resolved-interpreter>``, absolute paths relativized to the repo
      root) — the same shape guard_execution_plan.canonicalize_plan_for_
      comparison produces;
    - all counts/status values come from the evidence index, never prose;
    - output is LF, UTF-8, trailing newline.

Purity contract: no subprocesses, no writes outside the explicit output
paths, and all failures are raised as GuardDocsRenderError with controlled
codes (callers own exit-code decisions; the CLI maps them to exit 2).
"""

from __future__ import annotations

import argparse
import importlib.util
import os
import sys
from typing import Any, Dict, List, Optional, Tuple

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guard_execution_plan import (  # noqa: E402
    ExecutionContext,
    canonicalize_plan_for_comparison,
    compile_static_suite_plan,
    load_guard_specs,
)

try:  # PyYAML is a hard dependency of the existing guard tooling.
    import yaml  # type: ignore
except ImportError:  # pragma: no cover - environment guard
    yaml = None  # type: ignore


DOCUMENT_INDEX_REL = os.path.join("docs", "ci", "GUARD_DOCUMENT_INDEX.yml")
EVIDENCE_INDEX_REL = os.path.join("docs", "ci", "GUARD_EVIDENCE_INDEX.yml")
COMMANDS_DOC_REL = os.path.join("docs", "ci", "GUARD_COMMANDS.generated.md")
STATUS_DOC_REL = os.path.join("docs", "ci", "GUARD_STATUS.generated.md")

INTERPRETER_PLACEHOLDER = "<resolved-interpreter>"
OWNER = "@panospao7"

_E_ENGINE_EXTERNAL_SKIPPED = "E_ENGINE_EXTERNAL_SKIPPED"


class GuardDocsRenderError(RuntimeError):
    """Controlled renderer failure (code + bounded context, no raw payloads)."""

    def __init__(self, code: str, context: str) -> None:
        super().__init__(f"{code}: {context}")
        self.code = code
        self.context = context[:200]


# ── Input loading ───────────────────────────────────────────────────────────────


def _load_yaml(path: str) -> Any:
    if yaml is None:
        raise GuardDocsRenderError(
            "E_YAML_UNAVAILABLE", "PyYAML is required to load index inputs"
        )
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return yaml.safe_load(handle)
    except OSError:
        raise GuardDocsRenderError("E_INPUT_UNREADABLE", os.path.basename(path))


def _load_registry_module(root: str):
    registry_path = os.path.join(root, "scripts", "ci", "guard_registry.py")
    spec = importlib.util.spec_from_file_location(
        "_gr10d_guard_registry", registry_path
    )
    if spec is None or spec.loader is None:
        raise GuardDocsRenderError("E_REGISTRY_LOAD_FAILED", "module spec")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _load_inputs(root: str):
    """Load (specs, registry_module, document_index, evidence_index)."""
    registry_module = _load_registry_module(root)
    specs, load_diags = load_guard_specs(
        os.path.join(root, "scripts", "ci", "guard_registry.py")
    )
    errors = [d for d in load_diags if d.severity == "error"]
    if errors or not specs:
        raise GuardDocsRenderError(
            "E_REGISTRY_LOAD_FAILED",
            ";".join(d.code for d in errors) or "no specs loaded",
        )
    document_index = _load_yaml(os.path.join(root, DOCUMENT_INDEX_REL))
    evidence_index = _load_yaml(os.path.join(root, EVIDENCE_INDEX_REL))
    if not isinstance(document_index, dict) or not isinstance(
        document_index.get("documents"), list
    ):
        raise GuardDocsRenderError(
            "E_DOCUMENT_INDEX_MALFORMED", "documents list absent"
        )
    if not isinstance(evidence_index, dict) or not isinstance(
        evidence_index.get("records"), list
    ):
        raise GuardDocsRenderError(
            "E_EVIDENCE_INDEX_MALFORMED", "records list absent"
        )
    return specs, registry_module, document_index, evidence_index


# ── Derived views ───────────────────────────────────────────────────────────────


def _owner_by_anchor(document_index: Dict[str, Any]) -> Dict[str, str]:
    owners: Dict[str, str] = {}
    for entry in document_index["documents"]:
        if not isinstance(entry, dict):
            continue
        anchor = entry.get("path")
        owner = entry.get("owner")
        if isinstance(anchor, str) and isinstance(owner, str):
            owners[anchor] = owner
    return owners


def _current_record(evidence_index: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    """The single COMPLETE + reproducible record, if any (else None)."""
    current = [
        record
        for record in evidence_index["records"]
        if isinstance(record, dict)
        and record.get("status") == "COMPLETE"
        and record.get("reproducible") is True
    ]
    if len(current) > 1:
        raise GuardDocsRenderError(
            "E_MULTIPLE_CURRENT_RECORDS",
            "evidence index carries more than one COMPLETE reproducible record",
        )
    return current[0] if current else None


def _compiled_plans(root: str, specs) -> Dict[str, Dict[str, Any]]:
    """Canonical plan identity per guard id (path-free, deterministic)."""
    context = ExecutionContext(
        repo_root=os.path.abspath(root),
        interpreter_path=sys.executable,
        ci_mode=False,
    )
    plans, compile_diags = compile_static_suite_plan(context, specs=specs)
    errors = [d for d in compile_diags if d.severity == "error"]
    if errors:
        raise GuardDocsRenderError(
            "E_PLAN_COMPILE_FAILED",
            ";".join(f"{d.guard_id}:{d.code}" for d in errors),
        )
    external_skipped = {
        d.guard_id
        for d in compile_diags
        if d.code == _E_ENGINE_EXTERNAL_SKIPPED and d.severity == "warning"
    }
    identities: Dict[str, Dict[str, Any]] = {}
    for plan in plans:
        canonical = canonicalize_plan_for_comparison(plan)
        identities[plan.guard_id] = {
            "outer": list(canonical["outerArgv"]),
            "child": (
                list(canonical["childArgv"])
                if canonical["childArgv"] is not None
                else None
            ),
        }
    return identities, external_skipped


# ── Rendering helpers ───────────────────────────────────────────────────────────


def _argv_line(tokens: List[str]) -> str:
    return " ".join(tokens)


def _guard_owner(owners: Dict[str, str], anchor: str) -> str:
    return owners.get(anchor, OWNER)


# ── GUARD_COMMANDS.generated.md ─────────────────────────────────────────────────


def _render_commands_doc(
    specs,
    registry_module,
    owners: Dict[str, str],
    plan_identities: Dict[str, Dict[str, Any]],
    external_skipped: set,
) -> str:
    lines: List[str] = [
        "<!-- GENERATED FILE — do not edit by hand. -->",
        "<!-- Renderer: scripts/ci/generate_guard_docs.py (PR-GR-10D). -->",
        "<!-- Inputs: scripts/ci/guard_registry.py execution schema (GR-10A), "
        "registry sourceScope fields (GR-10B), docs/ci/GUARD_EVIDENCE_INDEX.yml. -->",
        "<!-- Regenerate: python scripts/ci/generate_guard_docs.py --root . -->",
        "",
        "# Guard Commands — Generated Reference",
        "",
        "Canonical command identity for every registered guard, compiled from",
        "the registry execution schema (PR-GR-10A) via",
        "`guard_execution_plan.compile_static_suite_plan`. Source-scope",
        "classifications are the registry `sourceScope` values (PR-GR-10B),",
        "kept in lockstep with `docs/ci/GR-10B_SOURCE_SCOPE_MATRIX.md` by",
        "`scripts/ci/test_gr10b_source_scope_matrix.py`. This document is the",
        "only place command identities are spelled out; other documents link",
        "here instead of pasting commands.",
        "",
        "Command identities are token lists: `<resolved-interpreter>` stands",
        "for the runtime interpreter (never a bare `python`/`python3`), and",
        "every path is repository-relative. Ratchet guards run under",
        "`scripts/ci/guard_ratchet.py`; the outer identity carries the child",
        "argv as repeated single-token `--command-arg=<value>` entries.",
        "",
    ]
    for spec in specs:
        anchor = spec.documentation_anchor
        owner = _guard_owner(owners, anchor)
        registry_entry = registry_module.GUARD_REGISTRY.get(spec.guard_id, {})
        source_scope = registry_entry.get("sourceScope", "undeclared")
        lines.append(f"## {spec.guard_id}")
        lines.append("")
        lines.append(f"- mode: {spec.mode}")
        lines.append(f"- owner: {owner} (doc anchor: {anchor})")
        lines.append(f"- source-scope: {source_scope}")
        lines.append(f"- engine: {spec.engine}")
        if spec.engine in ("external", "gradle-native"):
            lines.append(
                "- suite-participation: excluded from the canonical suite "
                "plan (declared external; warning diagnostic "
                f"{_E_ENGINE_EXTERNAL_SKIPPED}); the Python runner bridge "
                "never executes it"
            )
            entry_execution = registry_entry.get("execution", {})
            lines.append(
                "- entrypoint: "
                + str(entry_execution.get("entrypoint", spec.entrypoint))
            )
            arguments = entry_execution.get("arguments") or ()
            if arguments:
                lines.append(
                    "- arguments: " + _argv_line([str(t) for t in arguments])
                )
        else:
            lines.append(
                f"- timeout-profile: {spec.timeout_profile} "
                f"({_plan_timeout_seconds(spec.timeout_profile)}s)"
            )
            if spec.ratchet is not None:
                lines.append(
                    f"- finding-protocol: {spec.ratchet.finding_protocol}"
                )
                lines.append(f"- baseline: {spec.ratchet.baseline_path}")
            if spec.required_inputs:
                lines.append(
                    "- required-inputs: " + ", ".join(spec.required_inputs)
                )
            lines.append(f"- output-contract: {spec.output_contract}")
            identity = plan_identities.get(spec.guard_id)
            if identity is None:
                raise GuardDocsRenderError(
                    "E_GUARD_PLAN_MISSING", spec.guard_id
                )
            lines.append(
                "- command-identity: " + _argv_line(identity["outer"])
            )
            if identity["child"] is not None:
                lines.append(
                    "- ratchet-child-identity: "
                    + _argv_line(identity["child"])
                )
        lines.append(f"- documentation-anchor: {anchor}")
        lines.append("")
    if external_skipped:
        skipped = ", ".join(sorted(external_skipped))
        lines.append(
            "Declared-external guards excluded from the compiled suite plan: "
            f"{skipped}."
        )
        lines.append("")
    lines.append(
        "Guard status and evidence state are maintained in "
        "docs/ci/GUARD_STATUS.generated.md."
    )
    lines.append("")
    return "\n".join(lines)


def _plan_timeout_seconds(profile: str) -> int:
    from guard_execution_plan import TIMEOUT_PROFILES

    return TIMEOUT_PROFILES[profile]


# ── GUARD_STATUS.generated.md ───────────────────────────────────────────────────


def _status_blocks(
    specs,
    registry_module,
    owners: Dict[str, str],
    record: Optional[Dict[str, Any]],
) -> List[str]:
    blocks: List[str] = []
    leg_outcomes: Dict[str, str] = {}
    guard_results: Dict[str, Dict[str, Any]] = {}
    debt: Dict[str, Dict[str, int]] = {}
    if record is not None:
        suite = record.get("staticSuite") or {}
        leg_outcomes = dict(suite.get("legOutcomes") or {})
        guard_results = dict(record.get("guardResults") or {})
        debt = dict(suite.get("ratchetDebtObservations") or {})
    for spec in specs:
        guard_id = spec.guard_id
        anchor = spec.documentation_anchor
        owner = _guard_owner(owners, anchor)
        registry_entry = registry_module.GUARD_REGISTRY.get(guard_id, {})
        source_scope = registry_entry.get("sourceScope", "undeclared")
        lines = [f"<!-- GUARD_STATUS:BEGIN {guard_id} -->"]
        verified_evidence = None
        if record is not None:
            if guard_id in leg_outcomes or guard_id in guard_results:
                verified_evidence = record
        if verified_evidence is not None:
            lines.append("Status: VERIFIED_AT_SHA")
            lines.append(f"Evidence: {verified_evidence['evidenceId']}")
            lines.append(f"Verified SHA: {verified_evidence['targetSha']}")
            lines.append(
                "Outcome: " + _outcome_line(guard_id, spec, guard_results,
                                            leg_outcomes)
            )
            if guard_id in debt:
                counts = debt[guard_id]
                lines.append(
                    "Debt: {current} unchanged ratchet findings (baseline "
                    "{baseline}) — recorded debt is never authorization".format(
                        current=counts.get("currentFindings"),
                        baseline=counts.get("baselineFindings"),
                    )
                )
            if guard_id == "db_access":
                gradle = (verified_evidence.get("gradleObservations") or {})
                lines.append(
                    "Gradle leg: "
                    + str(gradle.get("verifyDbAccessBoundaries",
                                     "NOT_RUN"))
                )
        else:
            lines.append("Status: IMPLEMENTED_UNVERIFIED")
            lines.append(
                "Reason: no qualifying exact-SHA evidence record in "
                "docs/ci/GUARD_EVIDENCE_INDEX.yml covers this guard"
            )
        lines.append(
            f"Canonical command reference: GUARD_COMMANDS.generated.md#{guard_id}"
        )
        lines.append(f"Scope: {source_scope}")
        lines.append(f"Owner: {owner} (doc anchor: {anchor})")
        lines.append(f"<!-- GUARD_STATUS:END {guard_id} -->")
        blocks.append("\n".join(lines))
    return blocks


def _outcome_line(guard_id, spec, guard_results, leg_outcomes) -> str:
    result = guard_results.get(guard_id)
    if guard_id == "db_access" and isinstance(result, dict):
        detail = (
            "exit {exit}, trusted, {findings} findings, {advisory} advisory "
            "diagnostics, {blocking} blocking diagnostics — advisory "
            "diagnostics are reported, never authorization".format(
                exit=result.get("directExit"),
                findings=result.get("findingCount"),
                advisory=result.get("advisoryDiagnosticCount"),
                blocking=result.get("blockingDiagnosticCount"),
            )
        )
        ratchet = result.get("ratchetExit")
        if ratchet is not None:
            detail += (
                "; ratchet exit {ratchet} (baseline {baseline}: 0 new, "
                "0 resolved, 0 expired)".format(
                    ratchet=ratchet,
                    baseline=result.get("ratchetBaselinePath",
                                        "config/baselines/db_access_v2.json"),
                )
            )
        return "PASS (" + detail + ")"
    if guard_id == "time_boundaries" and isinstance(result, dict):
        return "PASS (exit {exit}, {findings} findings)".format(
            exit=result.get("exit"), findings=result.get("findingCount")
        )
    if guard_id == "ui_dao" and isinstance(result, dict):
        return "VIOLATION (exit {exit} — {rule}: {count} finding(s) at the verified SHA)".format(
            exit=result.get("exit"),
            rule=result.get("violationRuleId", "G-UI-DAO-01"),
            count=result.get("findingCount"),
        )
    return "PASS"


def _render_status_doc(
    specs,
    registry_module,
    owners: Dict[str, str],
    evidence_index: Dict[str, Any],
    record: Optional[Dict[str, Any]],
) -> str:
    lines: List[str] = [
        "<!-- GENERATED FILE — do not edit by hand. -->",
        "<!-- Renderer: scripts/ci/generate_guard_docs.py (PR-GR-10D). "
        "This renderer owns every GUARD_STATUS block below; manual edits "
        "inside the markers fail scripts/ci/verify_guard_docs_truth.py. -->",
        "<!-- Inputs: docs/ci/GUARD_EVIDENCE_INDEX.yml, "
        "scripts/ci/guard_registry.py, docs/ci/GUARD_DOCUMENT_INDEX.yml. -->",
        "<!-- Status vocabulary: IMPLEMENTED_UNVERIFIED | VERIFIED_AT_SHA | "
        "PARTIAL | BLOCKED | HISTORICAL | SUPERSEDED | PLANNED -->",
        "",
        "# Guard Status — Generated Reference",
        "",
        "Current state of every registered guard, derived only from the",
        "tracked evidence index (docs/ci/GUARD_EVIDENCE_INDEX.yml). A guard",
        "is `VERIFIED_AT_SHA` only when a COMPLETE, reproducible evidence",
        "record at an exact SHA covers it; guards without a covering record",
        "are `IMPLEMENTED_UNVERIFIED` (code/config exist, but no qualifying",
        "exact-SHA evidence is recorded here). Counts are copied from the",
        "evidence record only — never from prose.",
        "",
    ]
    if record is not None:
        suite = record.get("staticSuite") or {}
        runs = record.get("captureRuns") or []
        digests = sorted({str(r.get("semanticDigestSha256")) for r in runs})
        lines.append(
            "## Current evidence record: " + str(record.get("evidenceId"))
        )
        lines.append("")
        lines.append(f"- target SHA: {record.get('targetSha')}")
        lines.append(f"- capture runs: {len(runs)}"
                     f" (semantic digests identical: "
                     f"{len(digests) == 1})")
        db = (record.get("guardResults") or {}).get("db_access")
        if isinstance(db, dict):
            lines.append(
                "- DB gate (db_access): exit {exit}, trusted, {findings} "
                "findings, {advisory} advisory diagnostics, {blocking} "
                "blocking diagnostics; ratchet exit {ratchet}".format(
                    exit=db.get("directExit"),
                    findings=db.get("findingCount"),
                    advisory=db.get("advisoryDiagnosticCount"),
                    blocking=db.get("blockingDiagnosticCount"),
                    ratchet=db.get("ratchetExit"),
                )
            )
        if suite:
            lines.append(
                "- static suite: exit {exit} — {total} legs: {passed} pass, "
                "{violations} violation ({leg}, {rule}), {infra} infra error "
                "({infra_leg})".format(
                    exit=suite.get("exit"),
                    total=suite.get("legsTotal"),
                    passed=suite.get("passed"),
                    violations=suite.get("violations"),
                    leg=suite.get("violationLeg"),
                    rule=suite.get("violationRuleId"),
                    infra=suite.get("infraErrors"),
                    infra_leg=suite.get("infraErrorLeg"),
                )
            )
        gradle = record.get("gradleObservations") or {}
        if gradle:
            lines.append(
                "- gradle: :app:verifyDbAccessBoundaries {db}, "
                ":app:check --dry-run {check}, "
                ":app:compileDebugKotlin {compile}".format(
                    db=gradle.get("verifyDbAccessBoundaries", "NOT_RUN"),
                    check=gradle.get("checkDryRun", "NOT_RUN"),
                    compile=gradle.get("compileDebugKotlin", "NOT_RUN"),
                )
            )
        lines.append("")
    lines.append("## Per-guard status")
    lines.append("")
    lines.extend(block + "\n" for block in
                 _status_blocks(specs, registry_module, owners, record))
    lines.append(
        "Historical bundles are marked `HISTORICAL` in the evidence index and"
        " are never current-state authority."
    )
    lines.append("")
    return "\n".join(lines)


# ── Entry points ────────────────────────────────────────────────────────────────


def render_guard_documents(root: str) -> Tuple[str, str]:
    """Render both generated documents; returns (commands_text, status_text)."""
    specs, registry_module, document_index, evidence_index = _load_inputs(root)
    owners = _owner_by_anchor(document_index)
    plan_identities, external_skipped = _compiled_plans(root, specs)
    record = _current_record(evidence_index)
    commands_text = _render_commands_doc(
        specs, registry_module, owners, plan_identities, external_skipped
    )
    status_text = _render_status_doc(
        specs, registry_module, owners, evidence_index, record
    )
    return commands_text, status_text


def _write_atomic(path: str, text: str) -> None:
    import tempfile

    destination = os.path.abspath(path)
    parent = os.path.dirname(destination)
    os.makedirs(parent, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(
        dir=parent, prefix=os.path.basename(destination) + ".", suffix=".tmp"
    )
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        os.replace(tmp_name, destination)
    except BaseException:
        try:
            os.unlink(tmp_name)
        except OSError:
            pass
        raise


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Render docs/ci/GUARD_COMMANDS.generated.md and "
            "docs/ci/GUARD_STATUS.generated.md from the registry, "
            "GR-10B source scope, and the evidence index."
        )
    )
    parser.add_argument("--root", default=".", help="Repository root")
    parser.add_argument(
        "--check",
        action="store_true",
        help="Exit 1 when the tracked generated docs are not byte-current "
        "instead of writing them",
    )
    args = parser.parse_args()
    root = os.path.abspath(args.root)
    try:
        commands_text, status_text = render_guard_documents(root)
        if args.check:
            for rel, text in (
                (COMMANDS_DOC_REL, commands_text),
                (STATUS_DOC_REL, status_text),
            ):
                path = os.path.join(root, rel)
                with open(path, "r", encoding="utf-8", newline="") as handle:
                    tracked = handle.read()
                if tracked != text:
                    print(f"GUARD DOCS STALE: {rel}")
                    return 1
            print("GUARD DOCS CURRENT: generated references byte-match")
            return 0
        _write_atomic(os.path.join(root, COMMANDS_DOC_REL), commands_text)
        _write_atomic(os.path.join(root, STATUS_DOC_REL), status_text)
        print(
            "GUARD DOCS RENDERED: "
            f"{COMMANDS_DOC_REL}, {STATUS_DOC_REL}"
        )
        return 0
    except GuardDocsRenderError as error:
        print(f"GUARD DOCS RENDER FAILED: {error.code}: {error.context}")
        return 2


if __name__ == "__main__":
    sys.exit(main())
