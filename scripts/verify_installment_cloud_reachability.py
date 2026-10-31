#!/usr/bin/env python3
"""
Installment-plan cloud reachability guard (G-INSTALLMENT-CLOUD-01..03).

Loan-installments slice S1, invariant INV-31 (docs/features/loan-installments/
domain-spec.md, section 4 "Write paths, persistence and privacy" and section 12
privacy bullet): installment plan data (plans, entries, repositories, DAOs and
the read-only projection port) must never be reachable from a cloud payload
assembly (CloudPayloadPurpose.*, including DASHBOARD_BRIEFING).  The existing
cloud_payload guard (G-CLOUD-01) proves request-body provenance only; it has no
dependency model, so this guard is a separate, registered, blocking guard.

Cloud AI payload surface (declared production Kotlin files, enumerated through
the checked-in manifest ``config/guards/production_source_roots.yml`` via
``scripts/guardrails/production_source_scope.py``):

  directory prefixes (matched as path segments of the repository-relative path)
    data/ai/         cloud/hybrid/on-device providers, prompt formatters,
                     response parsers, DailyBriefingWorker, connection tester
    domain/ai/       payload input builders (dashboard briefing, transaction
                     insight, financial-query/assistant interpretation, review,
                     receipt, dedupe, categorization), use cases, AI models
    domain/privacy/  CloudPayloadPolicy, PreparedCloudPayload, redactor, gate
    data/privacy/    default cloud payload policy and redactor
  exact files (8 entries)
    di/AiModule.kt, di/PrivacyModule.kt   (cloud provider / policy wiring)
    data/repository/FinancialWeatherRepository.kt,
    domain/forecasting/ForecastInputAssembler.kt,
    domain/logic/SynthesisEngine.kt, domain/logic/NarrativeGenerator.kt,
    domain/usecase/dashboard/DashboardDataProvider.kt
                     (the cloud briefing chain: weather repository -> forecast
                     assembly -> synthesis -> narrative -> processed dashboard
                     data feeding the DASHBOARD_BRIEFING payload; added by loan
                     slice S8b-1 / amendment A15 so this chain stays
                     installment-free by construction.  The Home-only
                     installment-aware weather source lives off-surface in a
                     separate file, e.g. data/repository/
                     HomeInstallmentWeatherSource.kt)
    data/repository/DashboardContractsAdapter.kt
                     (the production adapter that implements the Dashboard*
                     contracts over the weather repository and feeds
                     DashboardDataProvider; added by owner-approvals 59a so the
                     last hop of the cloud briefing chain is fenced too)
  file-name patterns (anywhere in the declared production roots, so a payload
  builder added later outside the directories above is still fenced)
    *CloudPayload*, *PromptFormatter*, *PromptBuilder*, *PayloadAssembler*,
    *PayloadBuilder*, and (Briefing|Insight|Assistant|Query)*InputBuilder

Forbidden in those files (comments and string-literal text are masked first, so
only executable references count):

  G-INSTALLMENT-CLOUD-01  import of any type from the packages
                          domain.installment, service.installment or
                          ui.screens.installments (wildcard and alias imports
                          included)
  G-INSTALLMENT-CLOUD-02  any other (fully qualified) reference to those
                          packages, including a ``package`` declaration in them
  G-INSTALLMENT-CLOUD-03  ANY identifier containing ``installment`` (matched
                          case-insensitively, as a substring) in executable
                          code, for example InstallmentPlan*, installmentPlanDao(),
                          UpcomingItem.Installment, InstallmentOverview,
                          installmentCount.  The cloud surface has no legitimate
                          use of the word, so the rule is a substring fence and
                          not a type-name list.

Normalisation (rules 01/02 only evadable through formatting otherwise): after
masking, backticks are removed and every ``\\s*\\.\\s*`` run is collapsed to a
single ``.`` before matching, so ``domain`.installment`` and a ``domain`` /
``.installment`` split over lines are matched like ``domain.installment``.
Every offset is mapped back to the masked (= original) text, so the reported
line is the line where the match starts.  At most one violation per
(rule, line) is reported.

Surface staleness: every directory prefix and every exact-file suffix above
must match at least one scanned file (infrastructure failure
E_INSTALLMENT_CLOUD_SURFACE_ENTRY_UNMATCHED naming the entry string only).
File-name patterns are exempt, because they are open-ended by design.

Scope limits (documented, not silent): this is a direct-reference fence.
Transitive reachability through a shared upstream model (for example the
dashboard model that feeds the briefing builder) is closed by the S8a payload
exclusion seam and the AE-23 payload test, not by this text guard.  The 8
exact-file entries fence the known cloud briefing chain files directly
(weather repository, forecast input assembler, synthesis engine, narrative
generator, dashboard data provider, dashboard contracts adapter) plus the two DI
modules; a file outside the 8 entries, the directory prefixes and the name
patterns is not scanned.
Known limit: a simple string template ``"$identifier"`` (for example
``"$installmentPlans"``) is masked together with the string literal, so rule 03
does not see the identifier; ``${...}`` expressions are code and are scanned.

There is no allowlist and no baseline.  The guard passes vacuously while no
plan code exists.  An empty cloud surface (zero matching files) is an
infrastructure failure: the surface definition would be stale and the guard
would pass for the wrong reason.  A partially stale surface (a directory prefix
or exact file that matches no scanned file) is the same class of failure.

Exit codes (universal mapping): 0 pass, 1 violation, 2 infrastructure
(unresolved production source scope, unreadable or unparseable source file,
empty cloud surface, unmatched surface entry).  ``--fail-on-violation`` is accepted for canonical argv
compatibility; a violation always fails.

Output contract: one line per violation, ``<path>:<line> [<rule id>]`` plus a
fixed description.  No file content is ever echoed.

Purity contract: library functions never call ``sys.exit``; only the CLI
adapter exits.
"""

import argparse
import bisect
import os
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import List, Optional, Tuple

EXIT_PASS = 0
EXIT_VIOLATION = 1
EXIT_INFRA = 2

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guardrails.production_source_scope import (  # noqa: E402
    ProductionSourceScopeError,
    iter_production_kotlin_files,
    resolve_production_source_scope,
    resolve_source_root_set_for_test_fixtures,
)
from guardrails.cloud_payload_proof import (  # noqa: E402
    CloudProofError,
    executable_kotlin_source,
)
from kotlin_callable_parser import ParserError  # noqa: E402

RULE_IMPORT = "G-INSTALLMENT-CLOUD-01"
RULE_QUALIFIED = "G-INSTALLMENT-CLOUD-02"
RULE_TYPE_NAME = "G-INSTALLMENT-CLOUD-03"

_DESCRIPTIONS = {
    RULE_IMPORT: (
        "Cloud payload surface imports an installment-plan package "
        "(domain.installment / service.installment / ui.screens.installments)"
    ),
    RULE_QUALIFIED: (
        "Cloud payload surface references an installment-plan package "
        "by qualified name"
    ),
    RULE_TYPE_NAME: "installment identifier on cloud surface",
}

# Report order within one line (matches the historical import/qualified/type order).
_RULE_ORDER = {RULE_IMPORT: 0, RULE_QUALIFIED: 1, RULE_TYPE_NAME: 2}

E_SURFACE_ENTRY_UNMATCHED = "E_INSTALLMENT_CLOUD_SURFACE_ENTRY_UNMATCHED"

# ── Cloud payload surface definition ───────────────────────────────────────────

SURFACE_DIRECTORY_PREFIXES: Tuple[str, ...] = (
    "data/ai",
    "domain/ai",
    "domain/privacy",
    "data/privacy",
)

SURFACE_EXACT_FILE_SUFFIXES: Tuple[str, ...] = (
    "di/AiModule.kt",
    "di/PrivacyModule.kt",
    # S8b-1 (A15): the cloud briefing chain must stay installment-free by construction.
    "data/repository/FinancialWeatherRepository.kt",
    "domain/forecasting/ForecastInputAssembler.kt",
    "domain/logic/SynthesisEngine.kt",
    "domain/logic/NarrativeGenerator.kt",
    "domain/usecase/dashboard/DashboardDataProvider.kt",
    # Owner-approvals 59a: the production adapter that wires the cloud-path weather into the
    # dashboard data (DashboardContractsAdapter) must stay installment-free as well.
    "data/repository/DashboardContractsAdapter.kt",
)

_ASCII = re.ASCII

_SURFACE_DIR_RE = re.compile(
    r"(?:^|/)(?:" + "|".join(re.escape(p) for p in SURFACE_DIRECTORY_PREFIXES) + r")/",
    _ASCII,
)
_SURFACE_EXACT_RE = re.compile(
    r"(?:^|/)(?:" + "|".join(re.escape(p) for p in SURFACE_EXACT_FILE_SUFFIXES) + r")$",
    _ASCII,
)
_SURFACE_DIR_ENTRY_RES = {
    prefix: re.compile(r"(?:^|/)" + re.escape(prefix) + r"/", _ASCII)
    for prefix in SURFACE_DIRECTORY_PREFIXES
}
_SURFACE_EXACT_ENTRY_RES = {
    suffix: re.compile(r"(?:^|/)" + re.escape(suffix) + r"$", _ASCII)
    for suffix in SURFACE_EXACT_FILE_SUFFIXES
}
_SURFACE_NAME_RE = re.compile(
    r"CloudPayload|PromptFormatter|PromptBuilder|PayloadAssembler|PayloadBuilder"
    r"|(?:Briefing|Insight|Assistant|Query)\w*InputBuilder",
    _ASCII,
)

# ── Forbidden reference patterns ───────────────────────────────────────────────

_PACKAGE = (
    r"(?:domain\.installments?|service\.installments?|ui\.screens\.installments?)"
)
_PACKAGE_REF = r"(?<![A-Za-z0-9_])" + _PACKAGE + r"(?![A-Za-z0-9_])"

# All of these run on the NORMALISED text (backticks removed, ``\s*\.\s*`` -> ``.``).
_IMPORT_RE = re.compile(
    r"^[ \t]*import[ \t]+(?:\w+\.)*" + _PACKAGE + r"(?![A-Za-z0-9_])",
    _ASCII | re.MULTILINE,
)
_IMPORT_LINE_RE = re.compile(r"^[ \t]*import[ \t][^\n]*", _ASCII | re.MULTILINE)
_QUALIFIED_RE = re.compile(_PACKAGE_REF, _ASCII)
# Rule 03: any identifier containing "installment", case-insensitive substring.
_INSTALLMENT_IDENTIFIER_RE = re.compile(r"installment", _ASCII | re.IGNORECASE)
_DOT_RUN_RE = re.compile(r"\s*\.\s*", _ASCII)


@dataclass(frozen=True)
class Violation:
    path: str  # repository-relative, forward slashes
    line: int  # 1-based
    rule_id: str
    description: str


def is_cloud_surface_path(relative_path: str) -> bool:
    """True when the repository-relative path belongs to the cloud AI payload surface."""
    normalized = relative_path.replace("\\", "/")
    if _SURFACE_DIR_RE.search(normalized):
        return True
    if _SURFACE_EXACT_RE.search(normalized):
        return True
    base = normalized.rsplit("/", 1)[-1]
    if not base.endswith(".kt"):
        return False
    return _SURFACE_NAME_RE.search(base[:-3]) is not None


def matched_surface_entries(relative_path: str) -> List[str]:
    """Directory-prefix and exact-file entries (not name patterns) matching the path."""
    normalized = relative_path.replace("\\", "/")
    matched = [p for p, rx in _SURFACE_DIR_ENTRY_RES.items() if rx.search(normalized)]
    matched.extend(s for s, rx in _SURFACE_EXACT_ENTRY_RES.items() if rx.search(normalized))
    return matched


def _normalise_with_offsets(masked: str) -> Tuple[str, List[int]]:
    """Remove backticks and collapse ``\\s*\\.\\s*`` to ``.``.

    Returns ``(normalised_text, offsets)`` where ``offsets[i]`` is the index in
    ``masked`` of the character that produced ``normalised_text[i]`` (for a
    collapsed dot run, the index of the dot itself).
    """
    kept_chars: List[str] = []
    kept_offsets: List[int] = []
    for index, char in enumerate(masked):
        if char != "`":
            kept_chars.append(char)
            kept_offsets.append(index)
    stripped = "".join(kept_chars)

    out_chars: List[str] = []
    out_offsets: List[int] = []
    position = 0
    for match in _DOT_RUN_RE.finditer(stripped):
        start, end = match.start(), match.end()
        out_chars.append(stripped[position:start])
        out_offsets.extend(kept_offsets[position:start])
        dot_index = start + match.group(0).index(".")
        out_chars.append(".")
        out_offsets.append(kept_offsets[dot_index])
        position = end
    out_chars.append(stripped[position:])
    out_offsets.extend(kept_offsets[position:])
    return "".join(out_chars), out_offsets


def scan_executable_text(relative_path: str, executable_text: str) -> List[Violation]:
    """Scan already-masked (comment- and literal-free) Kotlin text.

    Matching runs on the normalised text (see module docstring); the reported
    line is the line of the masked text where the match starts.  At most one
    violation per (rule, line).
    """
    normalised, offsets = _normalise_with_offsets(executable_text)
    newline_offsets = [i for i, char in enumerate(executable_text) if char == "\n"]

    def line_of(normalised_index: int) -> int:
        return bisect.bisect_left(newline_offsets, offsets[normalised_index]) + 1

    import_spans = [(m.start(), m.end()) for m in _IMPORT_LINE_RE.finditer(normalised)]

    def in_import_line(normalised_index: int) -> bool:
        return any(start <= normalised_index < end for start, end in import_spans)

    found = set()  # (line, rule id)
    for match in _IMPORT_RE.finditer(normalised):
        found.add((line_of(match.start()), RULE_IMPORT))
    for match in _QUALIFIED_RE.finditer(normalised):
        if not in_import_line(match.start()):
            found.add((line_of(match.start()), RULE_QUALIFIED))
    for match in _INSTALLMENT_IDENTIFIER_RE.finditer(normalised):
        found.add((line_of(match.start()), RULE_TYPE_NAME))

    return [
        Violation(relative_path, line, rule_id, _DESCRIPTIONS[rule_id])
        for line, rule_id in sorted(found, key=lambda item: (item[0], _RULE_ORDER[item[1]]))
    ]


def scan_text(relative_path: str, text: str) -> List[Violation]:
    """Mask comments and string-literal text, then scan (fixture-level API).

    Raises ``ParserError`` / ``CloudProofError`` on unparseable Kotlin; the
    CLI maps both to an infrastructure failure (fail closed).
    """
    return scan_executable_text(relative_path, executable_kotlin_source(text))


def _scan_declared_roots(
    repo_root: Path, root_set
) -> Tuple[List[Violation], int, Optional[str]]:
    """Scan the cloud payload surface inside the declared production roots.

    Returns ``(violations, scanned_file_count, infra_error)``.  ``infra_error``
    is a bounded controlled code (never an exception message or path).
    """
    violations: List[Violation] = []
    scanned = 0
    matched_entries = set()
    try:
        for source_file in iter_production_kotlin_files(str(repo_root), root_set):
            relative = source_file.repository_relative_path
            if not is_cloud_surface_path(relative):
                continue
            try:
                text = Path(source_file.absolute_path).read_text(encoding="utf-8")
            except (OSError, UnicodeError):
                return [], 0, "E_INSTALLMENT_CLOUD_SOURCE_UNREADABLE"
            try:
                violations.extend(scan_text(relative, text))
            except (ParserError, CloudProofError):
                return [], 0, "E_INSTALLMENT_CLOUD_SOURCE_UNPARSEABLE"
            scanned += 1
            matched_entries.update(matched_surface_entries(relative))
    except ProductionSourceScopeError:
        return [], 0, "E_INSTALLMENT_CLOUD_SOURCE_UNREADABLE"
    if scanned == 0:
        return [], 0, "E_INSTALLMENT_CLOUD_SURFACE_EMPTY"
    # Partial staleness: every directory prefix / exact file must match a scanned
    # file (name patterns are exempt).  Bounded message: the entry string only.
    for entry in SURFACE_DIRECTORY_PREFIXES + SURFACE_EXACT_FILE_SUFFIXES:
        if entry not in matched_entries:
            return [], 0, f"{E_SURFACE_ENTRY_UNMATCHED}: {entry}"
    return violations, scanned, None


def scan_source_root(source_root: Path) -> Tuple[List[Violation], int, Optional[str]]:
    """Scan ``<source_root>`` (fixture-level API).

    Repository-level invocations go through ``main()`` (manifest required, fail
    closed).  This entry point resolves the scope via the explicitly named
    TEST-FIXTURE seam so synthetic repositories without a manifest keep working.
    """
    if not Path(source_root).is_dir():
        return [], 0, "E_INSTALLMENT_CLOUD_SOURCE_ROOT_MISSING"
    root_set, _diagnostics = resolve_source_root_set_for_test_fixtures(str(source_root))
    if root_set is None:
        return [], 0, "E_INSTALLMENT_CLOUD_SOURCE_ROOT_MISSING"
    return _scan_declared_roots(Path(source_root), root_set)


def main(argv: Optional[List[str]] = None) -> int:
    parser = argparse.ArgumentParser(
        description="Installment-plan cloud reachability guard (G-INSTALLMENT-CLOUD-01..03)."
    )
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parent.parent,
        help="Repository root directory (default: two levels up from this script).",
    )
    parser.add_argument(
        "--fail-on-violation",
        action="store_true",
        help="Accepted for canonical argv compatibility; violations always fail.",
    )
    args = parser.parse_args(argv)

    root_set, scope_diagnostics = resolve_production_source_scope(str(args.root))
    if root_set is None:
        codes = ", ".join(sorted({code for code, _ctx in scope_diagnostics}))
        print(
            f"INSTALLMENT CLOUD REACHABILITY: infrastructure error "
            f"(E_INSTALLMENT_CLOUD_SOURCE_SCOPE_UNRESOLVED: {codes})",
            file=sys.stderr,
        )
        return EXIT_INFRA

    violations, scanned, infra_error = _scan_declared_roots(args.root, root_set)
    if infra_error is not None:
        print(
            f"INSTALLMENT CLOUD REACHABILITY: infrastructure error ({infra_error})",
            file=sys.stderr,
        )
        return EXIT_INFRA

    if violations:
        for violation in violations:
            print(
                f"INSTALLMENT CLOUD REACHABILITY: {violation.path}:{violation.line} "
                f"[{violation.rule_id}] {violation.description}"
            )
        print(
            f"FAIL: {len(violations)} installment cloud reachability violation(s) "
            f"(G-INSTALLMENT-CLOUD-01..03). Installment plan data must not be "
            f"reachable from cloud payload assembly (INV-31)."
        )
        return EXIT_VIOLATION

    print(
        f"PASS: no installment plan references in the cloud payload surface "
        f"({scanned} file(s) scanned, G-INSTALLMENT-CLOUD-01..03)."
    )
    return EXIT_PASS


if __name__ == "__main__":
    sys.exit(main())
