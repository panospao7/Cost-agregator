#!/usr/bin/env python3
"""
VERIFY_GUARD_DOCS_TRUTH — PR-GR-10D documentation/evidence truth validator.

Validates a CLOSED STRUCTURED CLAIM CONTRACT (not unrestricted prose
inference) between:

  - docs/ci/GUARD_DOCUMENT_INDEX.yml   (document classification authority)
  - docs/ci/GUARD_EVIDENCE_INDEX.yml   (exact-SHA evidence authority)
  - docs/ci/GUARD_COMMANDS.generated.md and docs/ci/GUARD_STATUS.generated.md
    (renderer-owned generated references)
  - scripts/ci/guard_registry.py       (canonical registry / GR-10A plan)
  - the current-class guard documents themselves

Checks (plan deliverable 4):
   1. document index + evidence index + generated docs exist;
   2. every indexed document exists and is classified from the closed
      vocabulary;
   3. every active registry guard has a documentation anchor that is indexed
      exactly once as a current-class document (one current owner);
   4. current documents carry no unsupported completion claims (closed
      contract: DONE/GREEN/complete/VERIFIED-style status claims are rejected
      unless the line carries an evidence-sha reference; unqualified
      "v1 active" / "GR-07 pending" DB-state claims are rejected; baseline
      authorization and advisory-authorization wordings are rejected);
   5. generated documents are byte-reproducible from the renderer;
   6. generated command sections derive from the canonical plan;
   7. evidence claims reference real exact-SHA records;
   8. historical documents are visibly marked;
   9. stale/manual count claims cannot appear: GUARD_STATUS blocks in
      hand-maintained documents must byte-match the renderer output;
  10. unknown baseline/policy/source-root document references are rejected;
  11. plan documents cannot present future work as active implementation;
  12. output is deterministic and path-free (repo-relative diagnostics only).

Exit codes: 0 = contract holds; 1 = violations; 2 = infrastructure error
(missing/malformed inputs, renderer failure).
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
from typing import Any, Dict, List, Optional, Tuple

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

try:  # PyYAML is a hard dependency of the existing guard tooling.
    import yaml  # type: ignore
except ImportError:  # pragma: no cover - environment guard
    yaml = None  # type: ignore


DOCUMENT_INDEX_REL = "docs/ci/GUARD_DOCUMENT_INDEX.yml"
EVIDENCE_INDEX_REL = "docs/ci/GUARD_EVIDENCE_INDEX.yml"
COMMANDS_DOC_REL = "docs/ci/GUARD_COMMANDS.generated.md"
STATUS_DOC_REL = "docs/ci/GUARD_STATUS.generated.md"

CLASSIFICATIONS = frozenset({
    "CURRENT_ARCHITECTURE",
    "CURRENT_OPERATIONS",
    "CURRENT_EVIDENCE",
    "NORMATIVE_TARGET",
    "HISTORICAL_RECORD",
    "PLAN_OR_BACKLOG",
    "GENERATED_REFERENCE",
})
CURRENT_CLASSES = frozenset({
    "CURRENT_ARCHITECTURE",
    "CURRENT_OPERATIONS",
    "CURRENT_EVIDENCE",
})
SHA40_RE = re.compile(r"^[0-9a-f]{40}$")
SHA64_RE = re.compile(r"^[0-9a-f]{64}$")
GUARD_LEGS = frozenset({"guard_registry", "guard_tests"})
LEG_OUTCOMES = frozenset({"PASS", "VIOLATION", "INFRASTRUCTURE"})
GRADLE_OUTCOMES = frozenset({"PASS", "VIOLATION", "INFRASTRUCTURE", "NOT_RUN"})

# Evidence reference allowance: a line carrying one of these is treated as
# evidence-backed for the completion-claim contract.
_EVIDENCE_LINE_RE = re.compile(
    r"gate-00r-|GUARD_EVIDENCE_INDEX|VERIFIED_AT_SHA|[0-9a-f]{40}"
)
# Prohibition wordings ("do NOT mark DONE/GREEN") are the opposite of a
# completion claim; they must never be flagged.
_PROHIBITION_LINE_RE = re.compile(
    r"\b(do\s+not\s+mark|don'?t\s+mark|no\s+DONE|no\s+GREEN|"
    r"no\s+DONE/GREEN|no\s+claim)\b",
    re.IGNORECASE,
)

# Closed claim contract (narrow, known-dangerous wordings only).
_STATUS_CLAIM_RE = re.compile(
    r"^\s*>?\s*(?:\*\*)?status\s*[:\*]\s*(?:\*\*)?\s*.*"
    r"\b(DONE|GREEN|COMPLETE|COMPLETED|VERIFIED)\b",
    re.IGNORECASE,
)
_MILESTONE_CLAIM_RE = re.compile(
    r"\b(all guards pass|fully enforced|release ready)\b", re.IGNORECASE
)
_V1_ACTIVE_RE = re.compile(
    r"v1\b[^.\n]{0,80}\bremains\s+the\s+.{0,40}?\b(ACTIVE|active)\b"
    r"|\bv1\s+policy\s+[^.\n]{0,40}\bACTIVE gate\b",
    re.IGNORECASE,
)
_GR07_PENDING_RE = re.compile(
    r"\bactivation\s+is\s+GR-07\b"
    r"|\bGR-07\b[^.\n]{0,40}\bpending\b"
    r"|\bexpected\s+until\s+v2\s+activation\b",
    re.IGNORECASE,
)
_BASELINE_AUTHORIZATION_RE = re.compile(
    r"\bbaseline\s+(authorizes|grants|is\s+an\s+authorization)\b",
    re.IGNORECASE,
)
_ADVISORY_AUTHORIZATION_RE = re.compile(
    r"advisory[^.\n]{0,60}\b(approved|authorization)\b", re.IGNORECASE
)
_NEGATION_RE = re.compile(
    r"\b(not|never|nothing|non-|nor)\b", re.IGNORECASE
)
_PLAN_ACTIVE_RE = re.compile(
    r"\b(is\s+now\s+active|now\s+active|landed\s+in\s+this\s+PR)\b",
    re.IGNORECASE,
)
_HISTORICAL_BANNER_RE = re.compile(r"historical\s+record", re.IGNORECASE)
_HISTORICAL_BANNER_SCOPE_RE = re.compile(
    r"\b(as-of|applies\s+to|scope|superseded|not\s+evidence)\b",
    re.IGNORECASE,
)

# Path-reference scan roots (checked for existence on disk). Tokens carrying
# glob metacharacters are skipped (they are patterns, not file references).
_PATH_REF_RE = re.compile(
    r"\b((?:config/guards|config/baselines|docs/ci|scripts/ci"
    r"|scripts/db_guard|scripts/guardrails)/[A-Za-z0-9_./\*-]+)"
)

# Generated-output hygiene: no machine spellings may leak into the tracked
# generated references.
_UNSAFE_OUTPUT_RE = re.compile(
    r"[A-Za-z]:[\\/]|\\\\|/Users/|/home/|C:/"
)


class _InfraError(RuntimeError):
    def __init__(self, code: str, context: str) -> None:
        super().__init__(f"{code}: {context}")
        self.code = code
        self.context = context[:200]


# ── Text region helpers ─────────────────────────────────────────────────────────


def _strip_protected_regions(text: str) -> str:
    """Remove fenced code, historical markers, and GUARD_STATUS blocks.

    Claim scanning applies only to ordinary prose of the document: fenced
    code blocks are command/contract examples, HISTORICAL_RECORD regions are
    explicitly labelled history, and GUARD_STATUS blocks are renderer-owned
    (byte-verified separately).
    """
    lines = text.splitlines()
    kept: List[str] = []
    in_fence = False
    in_historical = False
    in_status_block = False
    for line in lines:
        stripped = line.strip()
        if in_fence:
            if stripped.startswith("```"):
                in_fence = False
            continue
        if stripped.startswith("```"):
            in_fence = True
            continue
        if in_historical:
            if "HISTORICAL_RECORD:END" in line:
                in_historical = False
            continue
        if "HISTORICAL_RECORD:BEGIN" in line:
            in_historical = True
            continue
        if in_status_block:
            if "<!-- GUARD_STATUS:END" in line:
                in_status_block = False
            continue
        if "<!-- GUARD_STATUS:BEGIN" in line:
            in_status_block = True
            continue
        kept.append(line)
    return "\n".join(kept)


# ── Index loading ───────────────────────────────────────────────────────────────


def _load_yaml_file(path: str, code: str) -> Any:
    if yaml is None:
        raise _InfraError("E_YAML_UNAVAILABLE", "PyYAML is required")
    try:
        with open(path, "r", encoding="utf-8") as handle:
            return yaml.safe_load(handle)
    except OSError:
        raise _InfraError(code, os.path.basename(path))


def _load_registry_guards(root: str) -> Dict[str, Dict[str, Any]]:
    import importlib.util

    registry_path = os.path.join(root, "scripts", "ci", "guard_registry.py")
    spec = importlib.util.spec_from_file_location(
        "_gr10d_docs_registry", registry_path
    )
    if spec is None or spec.loader is None:
        raise _InfraError("E_REGISTRY_LOAD_FAILED", "module spec")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    registry = getattr(module, "GUARD_REGISTRY", None)
    if not isinstance(registry, dict) or not registry:
        raise _InfraError("E_REGISTRY_LOAD_FAILED", "GUARD_REGISTRY absent")
    return registry


# ── Individual check families ───────────────────────────────────────────────────


def _check_document_index(
    root: str,
    document_index: Dict[str, Any],
    registry: Dict[str, Dict[str, Any]],
) -> List[Tuple[str, str, str]]:
    violations: List[Tuple[str, str, str]] = []
    documents = document_index.get("documents")
    if not isinstance(documents, list) or not documents:
        raise _InfraError(
            "E_DOCUMENT_INDEX_MALFORMED", "documents list absent or empty"
        )
    seen_paths: Dict[str, str] = {}
    indexed_by_path: Dict[str, Dict[str, Any]] = {}
    for entry in documents:
        if not isinstance(entry, dict):
            raise _InfraError("E_DOCUMENT_INDEX_MALFORMED", "non-mapping entry")
        path = entry.get("path")
        classification = entry.get("classification")
        doc_id = str(entry.get("id", "<unnamed>"))
        if not isinstance(path, str) or not path:
            raise _InfraError(
                "E_DOCUMENT_INDEX_MALFORMED", f"{doc_id}: path absent"
            )
        if path in seen_paths:
            violations.append(
                ("E_DOC_DUPLICATE", path,
                 f"indexed by both {seen_paths[path]} and {doc_id}")
            )
        seen_paths[path] = doc_id
        indexed_by_path[path] = entry
        if classification not in CLASSIFICATIONS:
            violations.append(
                ("E_CLASSIFICATION_INVALID", path,
                 f"classification {classification!r} outside the closed "
                 f"vocabulary")
            )
        if not os.path.isfile(os.path.join(root, path)):
            violations.append(("E_DOC_MISSING", path, "indexed doc not found"))
    # Every active registry guard must have exactly one current documentation
    # owner: its documentationAnchor must resolve to exactly one indexed
    # document whose classification is a current class.
    for guard_id, entry in registry.items():
        execution = entry.get("execution") or {}
        anchor = execution.get("documentationAnchor")
        if not isinstance(anchor, str) or not anchor:
            violations.append(
                ("E_ANCHOR_MISSING", f"registry:{guard_id}",
                 "guard has no documentationAnchor")
            )
            continue
        owner = indexed_by_path.get(anchor)
        if owner is None:
            violations.append(
                ("E_ANCHOR_MISSING", f"registry:{guard_id}",
                 f"anchor {anchor} is not indexed")
            )
        elif owner.get("classification") not in CURRENT_CLASSES:
            violations.append(
                ("E_ANCHOR_MISSING", f"registry:{guard_id}",
                 f"anchor {anchor} is not owned by a current-class document "
                 f"(classification {owner.get('classification')!r})")
            )
    return violations


def _check_claims(
    document_index: Dict[str, Any],
    texts: Dict[str, str],
) -> List[Tuple[str, str, str]]:
    violations: List[Tuple[str, str, str]] = []
    for entry in document_index.get("documents", []):
        if not isinstance(entry, dict):
            continue
        path = entry.get("path")
        classification = entry.get("classification")
        if not isinstance(path, str) or path not in texts:
            continue
        if classification in ("HISTORICAL_RECORD", "PLAN_OR_BACKLOG",
                              "NORMATIVE_TARGET", "GENERATED_REFERENCE"):
            if classification == "PLAN_OR_BACKLOG":
                body = _strip_protected_regions(texts[path])
                for lineno, line in enumerate(
                    body.splitlines(), start=1
                ):
                    if _PLAN_ACTIVE_RE.search(line):
                        violations.append(
                            ("E_PLAN_PRESENTS_ACTIVE", path,
                             f"line {lineno}: plan document presents work as "
                             f"active implementation")
                        )
                        break
            continue
        if entry.get("id") == "guard-evidence-index":
            # The evidence index IS the structured evidence authority: its
            # status fields are validated structurally by
            # _check_evidence_index, not by the prose claim scan.
            continue
        body = _strip_protected_regions(texts[path])
        for lineno, line in enumerate(body.splitlines(), start=1):
            if _EVIDENCE_LINE_RE.search(line):
                continue
            if _PROHIBITION_LINE_RE.search(line):
                continue
            if _STATUS_CLAIM_RE.search(line):
                violations.append(
                    ("E_CLAIM_UNSUPPORTED", path,
                     f"line {lineno}: status claim without an evidence-sha "
                     f"reference")
                )
            if _MILESTONE_CLAIM_RE.search(line):
                violations.append(
                    ("E_CLAIM_UNSUPPORTED", path,
                     f"line {lineno}: unqualified milestone claim")
                )
            if _V1_ACTIVE_RE.search(line) or _GR07_PENDING_RE.search(line):
                violations.append(
                    ("E_DB_STATE_STALE_CLAIM", path,
                     f"line {lineno}: unqualified pre-activation DB-state "
                     f"claim (v1 active / GR-07 pending)")
                )
            if _BASELINE_AUTHORIZATION_RE.search(
                line
            ) and not _NEGATION_RE.search(line):
                violations.append(
                    ("E_BASELINE_AUTHORIZATION_CLAIM", path,
                     f"line {lineno}: baseline described as authorization")
                )
            if _ADVISORY_AUTHORIZATION_RE.search(
                line
            ) and not _NEGATION_RE.search(line):
                violations.append(
                    ("E_ADVISORY_AUTHORIZATION_CLAIM", path,
                     f"line {lineno}: advisory diagnostics described as "
                     f"approved/authorization")
                )
    return violations


def _check_historical_banners(
    document_index: Dict[str, Any], texts: Dict[str, str]
) -> List[Tuple[str, str, str]]:
    violations: List[Tuple[str, str, str]] = []
    for entry in document_index.get("documents", []):
        if not isinstance(entry, dict):
            continue
        if entry.get("classification") != "HISTORICAL_RECORD":
            continue
        path = entry.get("path")
        text = texts.get(path)
        if text is None:
            continue
        head = "\n".join(text.splitlines()[:15])
        if not _HISTORICAL_BANNER_RE.search(head):
            violations.append(
                ("E_HISTORICAL_UNMARKED", path,
                 "no visible historical-record banner in the first 15 lines")
            )
        elif not _HISTORICAL_BANNER_SCOPE_RE.search(head):
            violations.append(
                ("E_HISTORICAL_UNMARKED", path,
                 "historical banner lacks as-of/scope limitation wording")
            )
    return violations


def _check_evidence_index(
    evidence_index: Dict[str, Any],
    registry: Dict[str, Dict[str, Any]],
) -> List[Tuple[str, str, str]]:
    violations: List[Tuple[str, str, str]] = []
    if evidence_index.get("schemaVersion") != 1:
        raise _InfraError("E_EVIDENCE_INDEX_MALFORMED", "schemaVersion != 1")
    records = evidence_index.get("records")
    if not isinstance(records, list) or not records:
        raise _InfraError(
            "E_EVIDENCE_INDEX_MALFORMED", "records list absent or empty"
        )
    known_guard_ids = set(registry) | GUARD_LEGS
    for record in records:
        if not isinstance(record, dict):
            raise _InfraError("E_EVIDENCE_INDEX_MALFORMED", "non-mapping record")
        evidence_id = str(record.get("evidenceId", "<unnamed>"))
        where = f"evidence:{evidence_id}"
        status = record.get("status")
        if status not in ("COMPLETE", "INCOMPLETE", "HISTORICAL"):
            violations.append(
                ("E_EVIDENCE_RECORD_INVALID", where,
                 f"status {status!r} outside the closed vocabulary")
            )
        target_sha = record.get("targetSha")
        if not isinstance(target_sha, str) or not SHA40_RE.fullmatch(
            target_sha
        ):
            violations.append(
                ("E_EVIDENCE_RECORD_INVALID", where,
                 "targetSha missing, abbreviated, or not 40 lowercase hex")
            )
        for field in ("targetTreeSha", "baseSha", "mergeBaseSha"):
            value = record.get(field)
            if value is not None and (
                not isinstance(value, str) or not SHA40_RE.fullmatch(value)
            ):
                violations.append(
                    ("E_EVIDENCE_RECORD_INVALID", where,
                     f"{field} must be a 40-hex SHA or null")
                )
        runs = record.get("captureRuns")
        if not isinstance(runs, list) or not runs:
            violations.append(
                ("E_EVIDENCE_RECORD_INVALID", where,
                 "captureRuns absent or empty")
            )
            continue
        digests = set()
        for run in runs:
            if not isinstance(run, dict):
                violations.append(
                    ("E_EVIDENCE_RECORD_INVALID", where,
                     "capture run is not a mapping")
                )
                continue
            digest = run.get("semanticDigestSha256")
            if not isinstance(digest, str) or not SHA64_RE.fullmatch(digest):
                violations.append(
                    ("E_EVIDENCE_RECORD_INVALID", where,
                     f"run {run.get('runId')}: semanticDigestSha256 must be "
                     f"64 lowercase hex")
                )
            else:
                digests.add(digest)
            manifest_digest = run.get("artifactManifestSha256")
            if manifest_digest is None:
                if status == "COMPLETE":
                    violations.append(
                        ("E_EVIDENCE_RECORD_INVALID", where,
                         f"run {run.get('runId')}: artifactManifestSha256 is "
                         f"required for a COMPLETE record")
                    )
            elif not SHA64_RE.fullmatch(manifest_digest):
                violations.append(
                    ("E_EVIDENCE_RECORD_INVALID", where,
                     f"run {run.get('runId')}: artifactManifestSha256 must be "
                     f"64 lowercase hex or null")
                )
        if status == "COMPLETE":
            if record.get("reproducible") is not True:
                violations.append(
                    ("E_EVIDENCE_RECORD_INVALID", where,
                     "COMPLETE record must set reproducible: true")
                )
            if len(runs) < 2:
                violations.append(
                    ("E_EVIDENCE_RECORD_INVALID", where,
                     "a COMPLETE/VERIFIED record needs two capture runs")
                )
            if len(digests) != 1:
                violations.append(
                    ("E_EVIDENCE_RECORD_INVALID", where,
                     "run semantic digests differ; record cannot support "
                     "VERIFIED_AT_SHA")
                )
            if record.get("workingTreeClean") is not True:
                violations.append(
                    ("E_EVIDENCE_RECORD_INVALID", where,
                     "COMPLETE record must record workingTreeClean: true")
                )
            if not isinstance(record.get("guardResults"), dict):
                violations.append(
                    ("E_EVIDENCE_RECORD_INVALID", where,
                     "COMPLETE record must record guardResults")
                )
        if record.get("status") == "HISTORICAL" and record.get("reproducible"):
            violations.append(
                ("E_EVIDENCE_RECORD_INVALID", where,
                 "HISTORICAL record must set reproducible: false")
            )
        guard_results = record.get("guardResults")
        if isinstance(guard_results, dict):
            for guard_id, result in guard_results.items():
                if guard_id not in known_guard_ids:
                    violations.append(
                        ("E_EVIDENCE_RECORD_INVALID", where,
                         f"unknown guard id in guardResults: {guard_id}")
                    )
                    continue
                if not isinstance(result, dict):
                    continue
                for field in ("directExit", "exit", "ratchetExit"):
                    value = result.get(field)
                    if value is not None and value not in (0, 1, 2):
                        violations.append(
                            ("E_EVIDENCE_RECORD_INVALID", where,
                             f"{guard_id}.{field} must be 0, 1, 2, or absent")
                        )
                for field in ("findingCount", "blockingDiagnosticCount",
                              "advisoryDiagnosticCount"):
                    value = result.get(field)
                    if value is not None and (
                        not isinstance(value, int)
                        or isinstance(value, bool)
                        or value < 0
                    ):
                        violations.append(
                            ("E_EVIDENCE_RECORD_INVALID", where,
                             f"{guard_id}.{field} must be a non-negative "
                             f"integer")
                        )
                for field, vocabulary in (
                    ("staticSuiteOutcome", LEG_OUTCOMES),
                    ("gradleOutcome", GRADLE_OUTCOMES),
                ):
                    value = result.get(field)
                    if value is not None and value not in vocabulary:
                        violations.append(
                            ("E_EVIDENCE_RECORD_INVALID", where,
                             f"{guard_id}.{field} outside the closed "
                             f"vocabulary")
                        )
        suite = record.get("staticSuite")
        if isinstance(suite, dict):
            leg_outcomes = suite.get("legOutcomes")
            if isinstance(leg_outcomes, dict):
                for leg, outcome in leg_outcomes.items():
                    if leg not in known_guard_ids:
                        violations.append(
                            ("E_EVIDENCE_RECORD_INVALID", where,
                             f"unknown suite leg: {leg}")
                        )
                    if outcome not in LEG_OUTCOMES:
                        violations.append(
                            ("E_EVIDENCE_RECORD_INVALID", where,
                             f"leg {leg}: outcome {outcome!r} outside the "
                             f"closed vocabulary")
                        )
    return violations


def _check_evidence_references(
    document_index: Dict[str, Any],
    evidence_index: Dict[str, Any],
    texts: Dict[str, str],
) -> List[Tuple[str, str, str]]:
    violations: List[Tuple[str, str, str]] = []
    evidence_ids = {
        str(record.get("evidenceId"))
        for record in evidence_index.get("records", [])
        if isinstance(record, dict)
    }
    target_shas = {
        str(record.get("targetSha"))
        for record in evidence_index.get("records", [])
        if isinstance(record, dict) and isinstance(record.get("targetSha"), str)
    }
    for entry in document_index.get("documents", []):
        if not isinstance(entry, dict):
            continue
        path = entry.get("path")
        classification = entry.get("classification")
        if not isinstance(path, str) or path not in texts:
            continue
        if classification not in CURRENT_CLASSES and (
            classification != "GENERATED_REFERENCE"
        ):
            continue
        for lineno, line in enumerate(texts[path].splitlines(), start=1):
            for match in re.finditer(r"gate-00r-[0-9a-f]{40}", line):
                if match.group(0) not in evidence_ids:
                    violations.append(
                        ("E_EVIDENCE_REF_INVALID", path,
                         f"line {lineno}: unknown evidence id "
                         f"{match.group(0)}")
                    )
            for match in re.finditer(r"[0-9a-f]{40}", line):
                sha = match.group(0)
                if sha in target_shas:
                    continue
                # Only flag SHAs presented as verification targets.
                if re.search(
                    r"verified\s+sha|target\s+sha|evidence\s+sha",
                    line,
                    re.IGNORECASE,
                ):
                    violations.append(
                        ("E_EVIDENCE_REF_INVALID", path,
                         f"line {lineno}: SHA {sha} is not a recorded "
                         f"evidence targetSha")
                    )
    return violations


def _check_generated_docs(
    root: str,
    registry: Dict[str, Dict[str, Any]],
    status_text: str,
    commands_text: str,
) -> List[Tuple[str, str, str]]:
    violations: List[Tuple[str, str, str]] = []
    try:
        import generate_guard_docs as renderer
    except ImportError:
        raise _InfraError("E_RENDERER_UNAVAILABLE", "generate_guard_docs")
    try:
        rendered_commands, rendered_status = renderer.render_guard_documents(
            root
        )
    except renderer.GuardDocsRenderError as error:
        raise _InfraError(
            "E_RENDER_FAILED", f"{error.code}: {error.context}"
        )
    if rendered_commands != commands_text:
        violations.append(
            ("E_GENERATED_NOT_REPRODUCIBLE", COMMANDS_DOC_REL,
             "tracked bytes differ from the renderer output")
        )
    if rendered_status != status_text:
        violations.append(
            ("E_GENERATED_NOT_REPRODUCIBLE", STATUS_DOC_REL,
             "tracked bytes differ from the renderer output")
        )
    for name, text in (("commands", rendered_commands),
                       ("status", rendered_status)):
        if _UNSAFE_OUTPUT_RE.search(text):
            violations.append(
                ("E_UNSAFE_GENERATED_OUTPUT",
                 f"generated:{name}",
                 "generated reference contains an absolute path spelling")
            )
    # Command sections must derive from the canonical plan.
    try:
        from guard_execution_plan import (  # type: ignore
            ExecutionContext,
            canonicalize_plan_for_comparison,
            compile_static_suite_plan,
            load_guard_specs,
        )

        specs, _diags = load_guard_specs(
            os.path.join(root, "scripts", "ci", "guard_registry.py")
        )
        context = ExecutionContext(
            repo_root=os.path.abspath(root),
            interpreter_path=sys.executable,
            ci_mode=False,
        )
        plans, _compile_diags = compile_static_suite_plan(context, specs=specs)
    except Exception as exc:  # noqa: BLE001 - bounded infra diagnostics
        raise _InfraError(
            "E_PLAN_COMPILE_FAILED", type(exc).__name__
        )
    for plan in plans:
        canonical = canonicalize_plan_for_comparison(plan)
        guard_id = plan.guard_id
        section = re.search(
            rf"^## {re.escape(guard_id)}$.*?^## ",
            commands_text,
            re.MULTILINE | re.DOTALL,
        )
        body = section.group(0) if section else commands_text
        expected_outer = " ".join(canonical["outerArgv"])
        if f"- command-identity: {expected_outer}" not in body:
            violations.append(
                ("E_COMMAND_IDENTITY_DRIFT", COMMANDS_DOC_REL,
                 f"{guard_id}: command identity does not match the canonical "
                 f"plan")
            )
        if canonical["childArgv"] is not None:
            expected_child = " ".join(canonical["childArgv"])
            if f"- ratchet-child-identity: {expected_child}" not in body:
                violations.append(
                    ("E_COMMAND_IDENTITY_DRIFT", COMMANDS_DOC_REL,
                     f"{guard_id}: ratchet child identity does not match the "
                     f"canonical plan")
                )
    # GUARD_STATUS blocks in hand-maintained documents must byte-match the
    # renderer-owned blocks in the generated status doc.
    generated_blocks = dict(_extract_status_blocks(status_text))
    for entry in document_index_for_blocks(root):
        if entry.get("classification") not in CURRENT_CLASSES:
            continue
        for section_name in entry.get("generatedSections") or []:
            if section_name != "guard-status-summary":
                continue
            path = entry.get("path")
            text = texts.get(path)
            if text is None:
                continue
            for block_id, block_text in _extract_status_blocks(text).items():
                expected = generated_blocks.get(block_id)
                if expected is None:
                    violations.append(
                        ("E_STATUS_BLOCK_UNKNOWN", path,
                         f"GUARD_STATUS block {block_id} has no generated "
                         f"counterpart")
                    )
                elif block_text != expected:
                    violations.append(
                        ("E_STATUS_BLOCK_DRIFT", path,
                         f"GUARD_STATUS block {block_id} does not byte-match "
                         f"the renderer output")
                    )
    return violations


def document_index_for_blocks(root: str) -> List[Dict[str, Any]]:
    """Reload the document index (kept separate for test seam clarity)."""
    payload = _load_yaml_file(
        os.path.join(root, DOCUMENT_INDEX_REL),
        "E_DOCUMENT_INDEX_MISSING",
    )
    if not isinstance(payload, dict):
        return []
    return [e for e in payload.get("documents", []) if isinstance(e, dict)]


def _extract_status_blocks(text: str) -> Dict[str, str]:
    blocks: Dict[str, str] = {}
    pattern = re.compile(
        r"(<!-- GUARD_STATUS:BEGIN ([A-Za-z0-9_]+) -->\n.*?"
        r"<!-- GUARD_STATUS:END \2 -->\n?)",
        re.DOTALL,
    )
    for match in pattern.finditer(text):
        blocks[match.group(2)] = match.group(1)
    return blocks


def _check_path_references(
    document_index: Dict[str, Any], root: str, texts: Dict[str, str]
) -> List[Tuple[str, str, str]]:
    violations: List[Tuple[str, str, str]] = []
    for entry in document_index.get("documents", []):
        if not isinstance(entry, dict):
            continue
        path = entry.get("path")
        if entry.get("classification") not in CURRENT_CLASSES:
            continue
        if not isinstance(path, str) or path not in texts:
            continue
        body = _strip_protected_regions(texts[path])
        for lineno, line in enumerate(body.splitlines(), start=1):
            for match in _PATH_REF_RE.finditer(line):
                token = match.group(1).rstrip(".,;:)")
                if "*" in token:
                    continue  # glob pattern, not a file reference
                if not os.path.exists(os.path.join(root, token)):
                    violations.append(
                        ("E_UNKNOWN_PATH_REFERENCE", path,
                         f"line {lineno}: referenced file does not exist: "
                         f"{token}")
                    )
    return violations


# ── Orchestration ───────────────────────────────────────────────────────────────


def run_validator(root: str) -> Tuple[List[Tuple[str, str, str]], int]:
    """Run all checks; returns (violations, infra_count)."""
    doc_index_path = os.path.join(root, DOCUMENT_INDEX_REL)
    evidence_path = os.path.join(root, EVIDENCE_INDEX_REL)
    commands_path = os.path.join(root, COMMANDS_DOC_REL)
    status_path = os.path.join(root, STATUS_DOC_REL)
    for path, code in (
        (doc_index_path, "E_INDEX_MISSING"),
        (evidence_path, "E_EVIDENCE_MISSING"),
        (commands_path, "E_GENERATED_MISSING"),
        (status_path, "E_GENERATED_MISSING"),
    ):
        if not os.path.isfile(path):
            raise _InfraError(code, os.path.relpath(path, root))

    document_index = _load_yaml_file(
        doc_index_path, "E_DOCUMENT_INDEX_MALFORMED"
    )
    evidence_index = _load_yaml_file(evidence_path, "E_EVIDENCE_INDEX_MALFORMED")
    if not isinstance(document_index, dict):
        raise _InfraError("E_DOCUMENT_INDEX_MALFORMED", "not a mapping")
    if not isinstance(evidence_index, dict):
        raise _InfraError("E_EVIDENCE_INDEX_MALFORMED", "not a mapping")
    registry = _load_registry_guards(root)

    texts: Dict[str, str] = {}
    for entry in document_index.get("documents", []):
        if isinstance(entry, dict) and isinstance(entry.get("path"), str):
            path = os.path.join(root, entry["path"])
            if os.path.isfile(path):
                with open(path, "r", encoding="utf-8") as handle:
                    texts[entry["path"]] = handle.read()
    with open(commands_path, "r", encoding="utf-8") as handle:
        commands_text = handle.read()
    with open(status_path, "r", encoding="utf-8") as handle:
        status_text = handle.read()

    violations: List[Tuple[str, str, str]] = []
    violations.extend(_check_document_index(root, document_index, registry))
    violations.extend(_check_claims(document_index, texts))
    violations.extend(_check_historical_banners(document_index, texts))
    violations.extend(_check_evidence_index(evidence_index, registry))
    violations.extend(
        _check_evidence_references(document_index, evidence_index, texts)
    )
    violations.extend(
        _check_generated_docs(root, registry, status_text, commands_text)
    )
    violations.extend(_check_path_references(document_index, root, texts))
    return violations, 0


def _deterministic_json(violations: List[Tuple[str, str, str]]) -> str:
    payload = {
        "validator": "guard-docs-truth",
        "schemaVersion": 1,
        "violationCount": len(violations),
        "violations": [
            {"code": code, "path": path, "detail": detail}
            for code, path, detail in sorted(violations)
        ],
    }
    return json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Validate the guard documentation/evidence truth contract "
            "(PR-GR-10D)."
        )
    )
    parser.add_argument("--root", default=".", help="Repository root")
    args = parser.parse_args()
    root = os.path.abspath(args.root)
    try:
        violations, _infra = run_validator(root)
    except _InfraError as error:
        print(f"GUARD DOCS TRUTH INFRA: {error.code}: {error.context}")
        return 2
    print(_deterministic_json(violations))
    if violations:
        print(
            f"GUARD DOCS TRUTH: FAIL ({len(violations)} violation(s))"
        )
        return 1
    print("GUARD DOCS TRUTH: PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
