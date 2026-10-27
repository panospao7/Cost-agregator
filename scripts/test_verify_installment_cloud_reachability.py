#!/usr/bin/env python3
"""
test_verify_installment_cloud_reachability.py

Loan-installments slice S1 tests for the ``installment_cloud_reachability``
guard (scripts/verify_installment_cloud_reachability.py), INV-31 of
docs/features/loan-installments/domain-spec.md: installment plan data must
never be reachable from a cloud AI payload assembly.

Proven here, on temporary synthetic fixture trees only (no real repository
content, no real receipts or personal data):

  1. positive fixtures (no plan reference, comment-only, string-literal-only,
     plan text outside the cloud surface, installment words in comments and
     literals) pass with exit 0 and a non-empty scanned surface;
  2. negative fixtures report the exact rule ids and 1-based line number:
     G-INSTALLMENT-CLOUD-01 (import, wildcard, alias, service.installment,
     ui.screens.installments), -02 (qualified reference, package
     declaration), -03 (ANY case-insensitive ``installment`` substring in
     executable code, so it also fires beside -01/-02 and on any identifier
     such as InstallmentX or installmentCount), in every surface category;
  3. normalisation: backticks and whitespace/newlines around dots cannot
     evade -01/-02, the reported line is where the match starts, at most one
     violation per (rule, line), ordered by line then rule 01, 02, 03;
  4. infrastructure failures (empty surface, unmatched surface entry, missing
     production source manifest, unparseable source, unreadable source) exit 2
     with a bounded E_ code and never pass;
  5. output hygiene: no fixture file content is echoed, only the fixed
     description; ``--fail-on-violation`` is accepted;
  6. ``is_cloud_surface_path`` truth table;
  7. the guard registry entry agrees with the script's rule constants.

S8b-1 (owner-approved guard strengthening, A15): the surface also fences the
five files of the cloud briefing chain (FinancialWeatherRepository,
ForecastInputAssembler, SynthesisEngine, NarrativeGenerator,
DashboardDataProvider).  Proven here: each new exact entry flags a projection
port import plus field (G-INSTALLMENT-CLOUD-01/-03), the off-surface Home
source and a suffix look-alike are NOT scanned, and omitting any entry's file
is an infrastructure failure naming that entry.

Every fixture tree that expects PASS or a violation first seeds one clean file
for each of the eleven surface entries (four directory prefixes and seven exact
files; ``_seed_surface``), because a surface entry that matches no scanned file
is itself an infrastructure failure.

Run:
    python -m pytest scripts/test_verify_installment_cloud_reachability.py -v
"""

import importlib.util
import os
import subprocess
import sys
from pathlib import Path

import pytest

_SCRIPTS_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _SCRIPTS_DIR.parent
_SCRIPT = _SCRIPTS_DIR / "verify_installment_cloud_reachability.py"
sys.path.insert(0, str(_SCRIPTS_DIR))

import verify_installment_cloud_reachability as guard  # noqa: E402

_PKG = "com/yourname/expensetracker"
_JAVA = "app/src/main/java"

R1 = "G-INSTALLMENT-CLOUD-01"
R2 = "G-INSTALLMENT-CLOUD-02"
R3 = "G-INSTALLMENT-CLOUD-03"

# A package-qualified prefix used by fixtures.
_BASE = "com.yourname.expensetracker"

# One clean seed file per surface entry (directory prefix or exact file).  The
# guard fails closed (exit 2) when any entry matches no scanned file.
_SURFACE_SEEDS = {
    "data/ai": "data/ai/SurfaceSeed.kt",
    "domain/ai": "domain/ai/SurfaceSeed.kt",
    "domain/privacy": "domain/privacy/SurfaceSeed.kt",
    "data/privacy": "data/privacy/SurfaceSeed.kt",
    "di/AiModule.kt": "di/AiModule.kt",
    "di/PrivacyModule.kt": "di/PrivacyModule.kt",
    "data/repository/FinancialWeatherRepository.kt": "data/repository/FinancialWeatherRepository.kt",
    "domain/forecasting/ForecastInputAssembler.kt": "domain/forecasting/ForecastInputAssembler.kt",
    "domain/logic/SynthesisEngine.kt": "domain/logic/SynthesisEngine.kt",
    "domain/logic/NarrativeGenerator.kt": "domain/logic/NarrativeGenerator.kt",
    "domain/usecase/dashboard/DashboardDataProvider.kt": "domain/usecase/dashboard/DashboardDataProvider.kt",
}
# The five exact entries added by S8b-1 (cloud briefing chain, A15).
_S8B1_ENTRIES = (
    "data/repository/FinancialWeatherRepository.kt",
    "domain/forecasting/ForecastInputAssembler.kt",
    "domain/logic/SynthesisEngine.kt",
    "domain/logic/NarrativeGenerator.kt",
    "domain/usecase/dashboard/DashboardDataProvider.kt",
)
_SEED_COUNT = len(_SURFACE_SEEDS)
_ALL_SURFACE_ENTRIES = tuple(_SURFACE_SEEDS)
_UNMATCHED = "E_INSTALLMENT_CLOUD_SURFACE_ENTRY_UNMATCHED"


def _source_root(tmp_path: Path) -> Path:
    root = tmp_path / "app" / "src" / "main" / "java"
    root.mkdir(parents=True, exist_ok=True)
    return root


def _write(tmp_path: Path, sub: str, content: str) -> Path:
    """Write ``<tmp>/app/src/main/java/com/yourname/expensetracker/<sub>``."""
    path = _source_root(tmp_path) / _PKG / sub
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8", newline="\n")
    return path


def _rel(sub: str) -> str:
    """Repository-relative POSIX path the guard reports for ``sub``."""
    return f"{_JAVA}/{_PKG}/{sub}"


def _seed_surface(tmp_path: Path, omit=()) -> None:
    """Seed one clean file for each surface entry not listed in ``omit``.

    A test that writes the same relative path afterwards simply overwrites the
    seed (an exact-file entry such as di/AiModule.kt), so the scanned count
    stays equal to the number of surface entries (11).
    """
    for entry, sub in _SURFACE_SEEDS.items():
        if entry in omit:
            continue
        _write(tmp_path, sub, "package probe\nval seeded = 1\n")


def _write_manifest(root: Path, root_rel: str = _JAVA) -> Path:
    """Declare the production source roots the CLI resolves (PR-GR-10B)."""
    manifest = root / "config" / "guards" / "production_source_roots.yml"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(
        "schemaVersion: 1\n"
        "roots:\n"
        "  - module: ':app'\n"
        "    sourceSet: main\n"
        f"    path: {root_rel}\n",
        encoding="utf-8",
    )
    return manifest


def _run_cli(root: Path, manifest: bool = True, flag: bool = True) -> subprocess.CompletedProcess:
    if manifest:
        _write_manifest(root)
    argv = [sys.executable, str(_SCRIPT), "--root", str(root)]
    if flag:
        argv.append("--fail-on-violation")
    return subprocess.run(
        argv,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=60,
    )


def _scan(tmp_path: Path):
    """Library-level scan of the repository root with the manifest declared."""
    _write_manifest(tmp_path)
    return guard.scan_source_root(tmp_path)


def _found(tmp_path: Path):
    violations, scanned, infra = _scan(tmp_path)
    assert infra is None, infra
    assert scanned > 0
    return sorted((v.path, v.line, v.rule_id) for v in violations)


def _assert_clean(tmp_path: Path, expected_scanned: int = _SEED_COUNT + 1):
    """Assert PASS; default = the eleven seeds plus the one fixture file under test."""
    violations, scanned, infra = _scan(tmp_path)
    assert infra is None, infra
    assert violations == []
    assert scanned > 0
    assert scanned == expected_scanned
    result = _run_cli(tmp_path)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "PASS" in result.stdout


# ── Rule inventory ──────────────────────────────────────────────────────────


def test_rule_ids_are_the_three_documented_rules():
    assert (guard.RULE_IMPORT, guard.RULE_QUALIFIED, guard.RULE_TYPE_NAME) == (R1, R2, R3)


def test_surface_definition_constants():
    assert tuple(guard.SURFACE_DIRECTORY_PREFIXES) == (
        "data/ai", "domain/ai", "domain/privacy", "data/privacy",
    )
    assert tuple(guard.SURFACE_EXACT_FILE_SUFFIXES) == (
        "di/AiModule.kt",
        "di/PrivacyModule.kt",
        "data/repository/FinancialWeatherRepository.kt",
        "domain/forecasting/ForecastInputAssembler.kt",
        "domain/logic/SynthesisEngine.kt",
        "domain/logic/NarrativeGenerator.kt",
        "domain/usecase/dashboard/DashboardDataProvider.kt",
    )
    assert _S8B1_ENTRIES == tuple(guard.SURFACE_EXACT_FILE_SUFFIXES)[2:]


# ── Positive fixtures (exit 0, non-empty surface) ───────────────────────────


def test_positive_surface_file_without_plan_references(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/provider/CloudDashboardBriefingService.kt",
        f"package {_BASE}.data.ai.provider\n"
        "\n"
        f"import {_BASE}.domain.model.Expense\n"
        "\n"
        "class CloudDashboardBriefingService {\n"
        "    fun brief(items: List<Expense>): Int = items.size\n"
        "}\n",
    )
    _assert_clean(tmp_path)


def test_positive_comment_only_mention_of_installment_plan(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "domain/ai/usecase/Notes.kt",
        f"package {_BASE}.domain.ai.usecase\n"
        f"// never import {_BASE}.domain.installment.InstallmentPlan here\n"
        "/* block: domain.installment.InstallmentPlan and InstallmentProjectionPort */\n"
        "/**\n"
        " * KDoc: see service.installment and ui.screens.installments\n"
        " */\n"
        "val ok = 1\n",
    )
    _assert_clean(tmp_path)


def test_positive_string_literal_only_mention(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/privacy/Labels.kt",
        f"package {_BASE}.data.privacy\n"
        f'val a = "{_BASE}.domain.installment.InstallmentPlan"\n'
        'val b = """\n'
        "domain.installment.InstallmentProjectionPort\n"
        'service.installment ui.screens.installments\n'
        '"""\n'
        'val c = "InstallmentPlan InstallmentEntry"\n',
    )
    _assert_clean(tmp_path)


def test_positive_plan_text_in_non_surface_file_is_not_scanned(tmp_path):
    _seed_surface(tmp_path)
    _write(tmp_path, "data/ai/Clean.kt", f"package {_BASE}.data.ai\nval ok = 1\n")
    _write(
        tmp_path,
        "ui/screens/home/X.kt",
        f"package {_BASE}.ui.screens.home\n"
        f"import {_BASE}.domain.installment.InstallmentPlan\n"
        f"import {_BASE}.domain.installment.InstallmentProjectionPort\n"
        "class X(val plan: InstallmentPlan, val port: InstallmentProjectionPort)\n",
    )
    # Only the eleven seeds plus Clean.kt count as scanned; X.kt is off the surface.
    _assert_clean(tmp_path, expected_scanned=_SEED_COUNT + 1)


# Identifiers that the previous guard treated as unrelated and rule -03 (any
# case-insensitive "installment" substring) now flags on the surface.
_FORMER_UNRELATED_IDENTIFIER_LINES = [
    "class InstallmentX",
    "class MyInstallmentPlanner",
    "class InstallmentRecorder",
    "val installmentCount = 3",
    "val v = domain.installmentCount",
    "val u = UpcomingItem.Installment",
    "val dao = installmentPlanDao()",
    "class InstallmentOverview",
]


@pytest.mark.parametrize("code_line", _FORMER_UNRELATED_IDENTIFIER_LINES)
def test_negative_installment_substring_identifier_is_flagged_on_surface(tmp_path, code_line):
    # Flipped from a positive test: InstallmentX, InstallmentRecorder,
    # MyInstallmentPlanner, installmentCount, domain.installmentCount,
    # UpcomingItem.Installment, installmentPlanDao(), InstallmentOverview are
    # now rule -03 violations on the cloud surface (and only -03).
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "domain/ai/usecase/Unrelated.kt",
        f"package {_BASE}.domain.ai.usecase\n{code_line}\n",
    )
    assert _found(tmp_path) == [(_rel("domain/ai/usecase/Unrelated.kt"), 2, R3)]


@pytest.mark.parametrize("code_line", _FORMER_UNRELATED_IDENTIFIER_LINES)
def test_positive_installment_substring_identifier_in_comment_or_literal_is_clean(
    tmp_path, code_line
):
    _seed_surface(tmp_path)
    escaped = code_line.replace('"', "")
    _write(
        tmp_path,
        "domain/ai/usecase/Masked.kt",
        f"package {_BASE}.domain.ai.usecase\n"
        f"// {code_line}\n"
        f"/* {code_line} */\n"
        f'val s = "{escaped}"\n'
        "val ok = 1\n",
    )
    _assert_clean(tmp_path)


@pytest.mark.parametrize("code_line", _FORMER_UNRELATED_IDENTIFIER_LINES)
def test_positive_installment_substring_identifier_off_surface_is_clean(tmp_path, code_line):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "ui/screens/home/Elsewhere.kt",
        f"package {_BASE}.ui.screens.home\n{code_line}\n",
    )
    # Off-surface file is not scanned: only the eleven seeds count.
    _assert_clean(tmp_path, expected_scanned=_SEED_COUNT)


# ── Negative fixtures: rule ids and line numbers ────────────────────────────

_IMPORT_FILE = "data/ai/provider/CloudDashboardBriefingService.kt"


def test_negative_plain_import_reports_rule_01_and_03_with_line(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        _IMPORT_FILE,
        f"package {_BASE}.data.ai.provider\n"
        "\n"
        f"import {_BASE}.domain.installment.InstallmentPlan\n"
        "\n"
        "class CloudDashboardBriefingService\n",
    )
    p = _rel(_IMPORT_FILE)
    assert _found(tmp_path) == [(p, 3, R1), (p, 3, R3)]


def test_negative_import_cli_exit_one_prints_rules_and_lines(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        _IMPORT_FILE,
        f"package {_BASE}.data.ai.provider\n"
        "\n"
        f"import {_BASE}.domain.installment.InstallmentPlan\n"
        "\n"
        "class CloudDashboardBriefingService\n",
    )
    result = _run_cli(tmp_path)
    assert result.returncode == 1, result.stdout + result.stderr
    p = _rel(_IMPORT_FILE)
    assert f"{p}:3 [{R1}]" in result.stdout
    assert f"{p}:3 [{R3}]" in result.stdout
    assert "FAIL" in result.stdout
    assert "2 installment cloud reachability violation(s)" in result.stdout


def test_negative_violation_fails_without_the_canonical_flag(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        _IMPORT_FILE,
        f"package {_BASE}.data.ai.provider\n"
        f"import {_BASE}.domain.installment.Foo\n",
    )
    result = _run_cli(tmp_path, flag=False)
    assert result.returncode == 1, result.stdout + result.stderr


def test_negative_wildcard_import(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Wild.kt",
        f"package {_BASE}.data.ai\n"
        f"import {_BASE}.domain.installment.*\n"
        "class Wild\n",
    )
    p = _rel("data/ai/Wild.kt")
    assert _found(tmp_path) == [(p, 2, R1), (p, 2, R3)]


def test_negative_alias_import(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Alias.kt",
        f"package {_BASE}.data.ai\n"
        "\n"
        f"import {_BASE}.domain.installment.Loan as L\n",
    )
    p = _rel("data/ai/Alias.kt")
    assert _found(tmp_path) == [(p, 3, R1), (p, 3, R3)]


def test_negative_alias_import_cannot_hide_the_plan_type(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Alias2.kt",
        f"package {_BASE}.data.ai\n"
        f"import {_BASE}.domain.installment.InstallmentPlan as Harmless\n",
    )
    p = _rel("data/ai/Alias2.kt")
    assert _found(tmp_path) == [(p, 2, R1), (p, 2, R3)]


def test_negative_service_installment_import(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "domain/ai/usecase/UsesService.kt",
        f"package {_BASE}.domain.ai.usecase\n"
        f"import {_BASE}.service.installment.InstallmentRecorder\n",
    )
    p = _rel("domain/ai/usecase/UsesService.kt")
    assert _found(tmp_path) == [(p, 2, R1), (p, 2, R3)]


def test_negative_ui_screens_installments_import(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "domain/privacy/UsesUi.kt",
        f"package {_BASE}.domain.privacy\n"
        "\n"
        "\n"
        f"import {_BASE}.ui.screens.installments.InstallmentsViewModel\n",
    )
    p = _rel("domain/privacy/UsesUi.kt")
    assert _found(tmp_path) == [(p, 4, R1), (p, 4, R3)]


def test_negative_fully_qualified_use_without_import(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Qualified.kt",
        f"package {_BASE}.data.ai\n"
        "class Qualified {\n"
        f"    val p: {_BASE}.domain.installment.Foo? = null\n"
        "}\n",
    )
    p = _rel("data/ai/Qualified.kt")
    assert _found(tmp_path) == [(p, 3, R2), (p, 3, R3)]


@pytest.mark.parametrize(
    "package_name",
    [
        f"{_BASE}.domain.installment",
        f"{_BASE}.domain.installment.sub",
        f"{_BASE}.service.installment",
        f"{_BASE}.ui.screens.installments",
    ],
)
def test_negative_package_declaration_in_plan_package_under_surface_path(tmp_path, package_name):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/privacy/Squatter.kt",
        f"package {package_name}\n"
        "\n"
        "class Squatter\n",
    )
    p = _rel("data/privacy/Squatter.kt")
    assert _found(tmp_path) == [(p, 1, R2), (p, 1, R3)]


def test_negative_bare_projection_port_constructor_parameter(tmp_path):
    _seed_surface(tmp_path)
    sub = "domain/ai/usecase/DashboardBriefingInputBuilder.kt"
    _write(
        tmp_path,
        sub,
        f"package {_BASE}.domain.ai.usecase\n"
        "\n"
        "class DashboardBriefingInputBuilder(\n"
        "    private val clock: Clock,\n"
        "    private val projection: InstallmentProjectionPort,\n"
        ")\n",
    )
    assert _found(tmp_path) == [(_rel(sub), 5, R3)]


# ── S8b-1: cloud briefing chain entries (A15) ───────────────────────────────


def _projection_port_leak_text(sub: str) -> str:
    """Kotlin text with a projection-port import (line 3) and a field (line 6).

    The package line is derived from ``sub`` and carries no installment word.
    Expected findings: line 3 -> rule 01 + 03 (an import line is never also
    reported by rule 02), line 6 -> rule 03.
    """
    package = f"{_BASE}." + sub.rsplit("/", 1)[0].replace("/", ".")
    return (
        f"package {package}\n"
        "\n"
        f"import {_BASE}.domain.installment.projection.InstallmentProjectionPort\n"
        "\n"
        "class Seeded(\n"
        "    private val installmentProjectionPort: InstallmentProjectionPort,\n"
        ")\n"
    )


@pytest.mark.parametrize("sub", _S8B1_ENTRIES)
def test_negative_s8b1_entry_flags_projection_port_import_and_field(tmp_path, sub):
    _seed_surface(tmp_path)
    _write(tmp_path, sub, _projection_port_leak_text(sub))
    p = _rel(sub)
    assert _found(tmp_path) == [(p, 3, R1), (p, 3, R3), (p, 6, R3)]
    result = _run_cli(tmp_path)
    assert result.returncode == 1, result.stdout + result.stderr
    assert f"{p}:3 [{R1}]" in result.stdout
    assert f"{p}:3 [{R3}]" in result.stdout
    assert f"{p}:6 [{R3}]" in result.stdout
    assert "3 installment cloud reachability violation(s)" in result.stdout
    assert "InstallmentProjectionPort" not in result.stdout


def test_positive_s8b1_off_surface_home_installment_weather_source_is_not_scanned(tmp_path):
    # The Home-only installment-aware source lives off-surface by design: the
    # same text that is flagged on the five new entries is NOT flagged here.
    sub = "data/repository/HomeInstallmentWeatherSource.kt"
    assert guard.is_cloud_surface_path(sub) is False
    assert guard.matched_surface_entries(sub) == []
    _seed_surface(tmp_path)
    _write(tmp_path, sub, _projection_port_leak_text(sub))
    _assert_clean(tmp_path, expected_scanned=_SEED_COUNT)


@pytest.mark.parametrize("sub", _S8B1_ENTRIES)
def test_positive_s8b1_suffix_precision_look_alike_file_names_are_not_on_the_surface(
    tmp_path, sub
):
    directory, _sep, name = sub.rpartition("/")
    look_alike = f"{directory}/X{name}"
    assert guard.is_cloud_surface_path(look_alike) is False
    assert guard.matched_surface_entries(look_alike) == []
    _seed_surface(tmp_path)
    _write(tmp_path, look_alike, _projection_port_leak_text(look_alike))
    _assert_clean(tmp_path, expected_scanned=_SEED_COUNT)


@pytest.mark.parametrize("sub", _S8B1_ENTRIES)
def test_positive_s8b1_suffix_precision_look_alike_directory_is_not_on_the_surface(
    tmp_path, sub
):
    look_alike = f"x{sub}"
    assert guard.is_cloud_surface_path(look_alike) is False
    assert guard.matched_surface_entries(look_alike) == []
    _seed_surface(tmp_path)
    _write(tmp_path, look_alike, _projection_port_leak_text(look_alike))
    _assert_clean(tmp_path, expected_scanned=_SEED_COUNT)


@pytest.mark.parametrize("omitted", _S8B1_ENTRIES)
def test_infra_s8b1_entry_file_missing_exits_two_naming_that_entry(tmp_path, omitted):
    _seed_surface(tmp_path, omit=(omitted,))
    result = _run_cli(tmp_path)
    assert result.returncode == 2, result.stdout + result.stderr
    assert result.stderr.strip() == (
        f"INSTALLMENT CLOUD REACHABILITY: infrastructure error ({_UNMATCHED}: {omitted})"
    )
    assert "PASS" not in result.stdout
    assert "FAIL" not in result.stdout


@pytest.mark.parametrize(
    "type_name",
    [
        "InstallmentPlan",
        "InstallmentPlanRepository",
        "InstallmentEntry",
        "InstallmentEntries",
        "InstallmentScheduleItem",
        "InstallmentProjectionPort",
        "InstallmentUpcomingCharge",
        "InstallmentDueSoon",
        "InstallmentPlanner",
    ],
)
def test_negative_every_planned_type_name_family_is_flagged(tmp_path, type_name):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Names.kt",
        f"package {_BASE}.data.ai\n"
        f"class Names(val dep: {type_name})\n",
    )
    assert _found(tmp_path) == [(_rel("data/ai/Names.kt"), 2, R3)]


def test_negative_string_template_expression_is_executable_and_flagged(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Template.kt",
        f"package {_BASE}.data.ai\n"
        'val label = "plan: ${InstallmentPlanRepository.name}"\n',
    )
    assert _found(tmp_path) == [(_rel("data/ai/Template.kt"), 2, R3)]


def test_known_limit_simple_dollar_name_template_is_not_executable(tmp_path):
    # Documented KNOWN LIMIT, not a desired property: a `${...}` template
    # expression is executable code and is flagged (line 2), but a simple
    # `$name` template is masked together with the string text, so the same
    # identifier on line 3 is NOT reported.  If the masking ever starts
    # treating `$name` as executable, this test must be revisited deliberately.
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/SimpleTemplate.kt",
        f"package {_BASE}.data.ai\n"
        'val braced = "plan: ${InstallmentPlanRepository.name}"\n'
        'val simple = "plan: $InstallmentPlanRepository"\n',
    )
    assert _found(tmp_path) == [(_rel("data/ai/SimpleTemplate.kt"), 2, R3)]


def test_negative_reference_after_a_comment_on_the_same_line_is_flagged(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Mixed.kt",
        f"package {_BASE}.data.ai\n"
        "/* harmless */ val p: InstallmentPlan? = null // trailing\n",
    )
    assert _found(tmp_path) == [(_rel("data/ai/Mixed.kt"), 2, R3)]


@pytest.mark.parametrize(
    "sub",
    [
        "data/ai/provider/CloudProvider.kt",
        "domain/ai/usecase/SomeUseCase.kt",
        "domain/privacy/CloudPayloadPolicy.kt",
        "data/privacy/DefaultRedactor.kt",
        "di/AiModule.kt",
        "di/PrivacyModule.kt",
        "data/repository/FinancialWeatherRepository.kt",
        "domain/forecasting/ForecastInputAssembler.kt",
        "domain/logic/SynthesisEngine.kt",
        "domain/logic/NarrativeGenerator.kt",
        "domain/usecase/dashboard/DashboardDataProvider.kt",
        "some/pkg/FooCloudPayloadBuilder.kt",
        "some/pkg/FooPromptFormatter.kt",
        "some/pkg/FooPromptBuilder.kt",
        "some/pkg/FooPayloadAssembler.kt",
        "some/pkg/WidgetInsightInputBuilder.kt",
        "some/pkg/AssistantFooInputBuilder.kt",
        "some/pkg/QueryInputBuilder.kt",
    ],
)
def test_negative_one_reference_in_each_surface_category(tmp_path, sub):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        sub,
        "package probe\n"
        "val leaked: InstallmentPlanDto? = null\n",
    )
    assert _found(tmp_path) == [(_rel(sub), 2, R3)]


def test_negative_multiple_violations_across_files_are_all_reported(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/A.kt",
        f"package {_BASE}.data.ai\nimport {_BASE}.domain.installment.Foo\n",
    )
    _write(
        tmp_path,
        "di/AiModule.kt",
        f"package {_BASE}.di\nval x: {_BASE}.service.installment.Bar? = null\n",
    )
    assert _found(tmp_path) == [
        (_rel("data/ai/A.kt"), 2, R1),
        (_rel("data/ai/A.kt"), 2, R3),
        (_rel("di/AiModule.kt"), 2, R2),
        (_rel("di/AiModule.kt"), 2, R3),
    ]
    result = _run_cli(tmp_path)
    assert result.returncode == 1
    assert "4 installment cloud reachability violation(s)" in result.stdout


# ── Library API (fixture-level) ─────────────────────────────────────────────


def test_scan_text_masks_comments_and_literals_but_keeps_code():
    text = (
        "// import x.domain.installment.InstallmentPlan\n"
        'val s = "InstallmentPlan"\n'
        "val p: InstallmentPlan? = null\n"
    )
    violations = guard.scan_text("data/ai/X.kt", text)
    assert [(v.path, v.line, v.rule_id) for v in violations] == [("data/ai/X.kt", 3, R3)]


def test_scan_executable_text_scans_already_masked_text_by_line():
    violations = guard.scan_executable_text(
        "domain/ai/X.kt",
        "val a = 1\nimport a.b.domain.installment.C\nval d: InstallmentDueX\n",
    )
    # Line 2 carries the import (-01) and the substring rule (-03); line 3 only -03.
    assert [(v.line, v.rule_id) for v in violations] == [(2, R1), (2, R3), (3, R3)]


def test_scan_source_root_missing_directory_is_infra_not_pass(tmp_path):
    violations, scanned, infra = guard.scan_source_root(tmp_path / "does-not-exist")
    assert violations == []
    assert scanned == 0
    assert infra == "E_INSTALLMENT_CLOUD_SOURCE_ROOT_MISSING"


# ── Rule descriptions (pinned) ──────────────────────────────────────────────

_DESC_R1 = (
    "Cloud payload surface imports an installment-plan package "
    "(domain.installment / service.installment / ui.screens.installments)"
)
_DESC_R2 = (
    "Cloud payload surface references an installment-plan package by qualified name"
)
_DESC_R3 = "installment identifier on cloud surface"


def test_rule_descriptions_are_pinned():
    violations = guard.scan_executable_text(
        "data/ai/X.kt",
        "import a.domain.installment.B\nval q: a.service.installment.C\n",
    )
    assert [(v.line, v.rule_id, v.description) for v in violations] == [
        (1, R1, _DESC_R1),
        (1, R3, _DESC_R3),
        (2, R2, _DESC_R2),
        (2, R3, _DESC_R3),
    ]


def test_rule_03_description_is_the_substring_fence_wording():
    violations = guard.scan_executable_text("data/ai/X.kt", "val x = installmentCount\n")
    assert [(v.rule_id, v.description) for v in violations] == [(R3, _DESC_R3)]


# ── Normalisation: backticks, dot spacing, newlines, reported line ──────────


@pytest.mark.parametrize(
    "import_line",
    [
        f"import {_BASE}.domain.`installment`.Foo",
        f"import {_BASE}.`domain`.`installment`.Foo",
        f"import {_BASE}.`service`.`installment`.Foo",
        f"import {_BASE}.`ui`.`screens`.`installments`.Foo",
        f"import `{_BASE}`.`domain`.installment.*",
    ],
)
def test_backtick_evasion_of_rule_01_is_caught(tmp_path, import_line):
    _seed_surface(tmp_path)
    _write(tmp_path, "data/ai/Tick1.kt", f"package {_BASE}.data.ai\n{import_line}\n")
    p = _rel("data/ai/Tick1.kt")
    assert _found(tmp_path) == [(p, 2, R1), (p, 2, R3)]


@pytest.mark.parametrize(
    "ref",
    [
        f"{_BASE}.domain.`installment`.Foo",
        f"{_BASE}.`domain`.`installment`.Foo",
        f"{_BASE}.`service`.`installment`.Foo",
        f"{_BASE}.`ui`.`screens`.`installments`.Foo",
    ],
)
def test_backtick_evasion_of_rule_02_is_caught(tmp_path, ref):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Tick2.kt",
        f"package {_BASE}.data.ai\nval p: {ref}? = null\n",
    )
    p = _rel("data/ai/Tick2.kt")
    assert _found(tmp_path) == [(p, 2, R2), (p, 2, R3)]


def test_backtick_around_a_lone_installment_identifier_is_caught_by_rule_03(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Tick3.kt",
        f"package {_BASE}.data.ai\nval `Installment Plan` = 1\nval `installment`Count = 2\n",
    )
    p = _rel("data/ai/Tick3.kt")
    assert _found(tmp_path) == [(p, 2, R3), (p, 3, R3)]


def test_spaced_dot_between_domain_and_installment_is_caught_for_rule_02(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Spaced.kt",
        f"package {_BASE}.data.ai\nval p: {_BASE}.domain . installment.Foo? = null\n",
    )
    p = _rel("data/ai/Spaced.kt")
    assert _found(tmp_path) == [(p, 2, R2), (p, 2, R3)]


def test_spaced_dot_in_an_import_is_caught_for_rule_01(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/SpacedImport.kt",
        f"package {_BASE}.data.ai\nimport {_BASE}.domain . installment . Foo\n",
    )
    p = _rel("data/ai/SpacedImport.kt")
    assert _found(tmp_path) == [(p, 2, R1), (p, 2, R3)]


def test_newline_before_the_dot_reports_each_rule_at_its_own_start_line():
    # Rule -02 matches from `domain` (line 2); rule -03 matches the word
    # `installment` itself, which starts on line 3.
    violations = guard.scan_executable_text(
        "data/ai/X.kt",
        "package p\nval q: a.domain\n    .installment.Foo? = null\n",
    )
    assert [(v.line, v.rule_id) for v in violations] == [(2, R2), (3, R3)]


def test_newline_after_the_dot_reports_each_rule_at_its_own_start_line():
    violations = guard.scan_executable_text(
        "data/ai/X.kt",
        "package p\nval q: a.domain.\n    installment.Foo? = null\n",
    )
    assert [(v.line, v.rule_id) for v in violations] == [(2, R2), (3, R3)]


def test_newlines_on_both_sides_of_the_dot_report_the_start_line_for_rule_02():
    violations = guard.scan_executable_text(
        "data/ai/X.kt",
        "package p\nval q: a.service\n  .\n  installment.Foo? = null\n",
    )
    assert [(v.line, v.rule_id) for v in violations] == [(2, R2), (4, R3)]


def test_split_newline_reference_is_caught_in_a_file_tree(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Split.kt",
        f"package {_BASE}.data.ai\nval q: {_BASE}.domain\n    .installment.Foo? = null\n",
    )
    p = _rel("data/ai/Split.kt")
    assert _found(tmp_path) == [(p, 2, R2), (p, 3, R3)]


@pytest.mark.parametrize(
    "text",
    [
        "package p\nimport a.b.domain\n    .installment.Foo\n",
        "package p\nimport a.b.domain.\n    installment.Foo\n",
        "package p\nimport a.b.domain\n    .\n    installment.*\n",
    ],
)
def test_import_split_across_lines_reports_rule_01_at_the_import_line(text):
    violations = guard.scan_executable_text("data/ai/X.kt", text)
    rules = [(v.line, v.rule_id) for v in violations]
    # -01 on the `import` line (2); never downgraded to -02 (the whole
    # normalised line is still an import); -03 where the word starts (3+).
    assert (2, R1) in rules
    assert not any(rule == R2 for _line, rule in rules)
    assert [rule for _line, rule in rules] == [R1, R3]
    assert rules[1][0] >= 3


def test_split_import_reported_line_is_the_import_line_in_a_file_tree(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/SplitImport.kt",
        f"package {_BASE}.data.ai\n\nimport {_BASE}.domain\n    .installment.Foo\n",
    )
    p = _rel("data/ai/SplitImport.kt")
    assert _found(tmp_path) == [(p, 3, R1), (p, 4, R3)]


# ── Dedupe and ordering ─────────────────────────────────────────────────────


def test_rule_03_is_deduped_within_a_line():
    violations = guard.scan_executable_text(
        "data/ai/X.kt",
        "val a: InstallmentA = InstallmentB(installmentC, INSTALLMENT_D)\n",
    )
    assert [(v.line, v.rule_id) for v in violations] == [(1, R3)]


def test_rule_02_and_03_are_each_deduped_within_a_line():
    violations = guard.scan_executable_text(
        "data/ai/X.kt",
        "val a = x.domain.installment.A to y.service.installment.B\n",
    )
    assert [(v.line, v.rule_id) for v in violations] == [(1, R2), (1, R3)]


def test_two_lines_with_the_same_rule_yield_two_violations():
    violations = guard.scan_executable_text(
        "data/ai/X.kt",
        "val a: InstallmentA\nval ok = 1\nval b: InstallmentB\n",
    )
    assert [(v.line, v.rule_id) for v in violations] == [(1, R3), (3, R3)]


def test_two_import_lines_yield_one_rule_01_each():
    violations = guard.scan_executable_text(
        "data/ai/X.kt",
        "import a.domain.installment.A\nimport a.service.installment.B\n",
    )
    assert [(v.line, v.rule_id) for v in violations] == [
        (1, R1), (1, R3), (2, R1), (2, R3),
    ]


def test_violations_are_sorted_by_line_before_rule():
    violations = guard.scan_executable_text(
        "data/ai/X.kt",
        "val d: InstallmentX\nimport a.domain.installment.C\nval q: a.ui.screens.installments.D\n",
    )
    assert [(v.line, v.rule_id) for v in violations] == [
        (1, R3), (2, R1), (2, R3), (3, R2), (3, R3),
    ]


def test_within_a_line_order_is_rule_01_then_02_then_03():
    import_line = guard.scan_executable_text("p.kt", "import a.domain.installment.C\n")
    qualified_line = guard.scan_executable_text("p.kt", "val q: a.domain.installment.C\n")
    assert [v.rule_id for v in import_line] == [R1, R3]
    assert [v.rule_id for v in qualified_line] == [R2, R3]


def test_file_tree_output_order_is_line_then_rule_in_the_cli(tmp_path):
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Order.kt",
        f"package {_BASE}.data.ai\n"
        f"val q: {_BASE}.service.installment.B\n"
        f"import {_BASE}.domain.installment.A\n",
    )
    result = _run_cli(tmp_path)
    assert result.returncode == 1, result.stdout + result.stderr
    p = _rel("data/ai/Order.kt")
    expected = [f"{p}:2 [{R2}]", f"{p}:2 [{R3}]", f"{p}:3 [{R1}]", f"{p}:3 [{R3}]"]
    positions = [result.stdout.index(item) for item in expected]
    assert positions == sorted(positions)
    assert "4 installment cloud reachability violation(s)" in result.stdout


# ── Infrastructure failures (exit 2, bounded E_ code on stderr) ─────────────


def test_infra_empty_surface_exits_two(tmp_path):
    # A declared production root with only non-surface files: the surface
    # definition would be stale and the guard would pass for the wrong reason.
    _write(tmp_path, "ui/screens/home/X.kt", f"package {_BASE}.ui.screens.home\nval a = 1\n")
    violations, scanned, infra = _scan(tmp_path)
    assert (violations, scanned) == ([], 0)
    assert infra == "E_INSTALLMENT_CLOUD_SURFACE_EMPTY"
    result = _run_cli(tmp_path)
    assert result.returncode == 2, result.stdout + result.stderr
    assert "E_INSTALLMENT_CLOUD_SURFACE_EMPTY" in result.stderr
    assert "PASS" not in result.stdout


def test_infra_empty_surface_is_reported_before_any_unmatched_entry(tmp_path):
    # Zero scanned files: the empty-surface check comes first, so the code is
    # SURFACE_EMPTY and not one of the eleven ENTRY_UNMATCHED variants.
    _write(tmp_path, "ui/screens/home/X.kt", "package probe\nval a = 1\n")
    violations, scanned, infra = _scan(tmp_path)
    assert (violations, scanned) == ([], 0)
    assert infra == "E_INSTALLMENT_CLOUD_SURFACE_EMPTY"
    assert _UNMATCHED not in infra


# ── Surface staleness: every directory prefix / exact file must match ───────


def test_surface_entries_under_test_are_exactly_the_eleven_documented_entries():
    assert _ALL_SURFACE_ENTRIES == tuple(guard.SURFACE_DIRECTORY_PREFIXES) + tuple(
        guard.SURFACE_EXACT_FILE_SUFFIXES
    )
    assert len(guard.SURFACE_DIRECTORY_PREFIXES) == 4
    assert len(guard.SURFACE_EXACT_FILE_SUFFIXES) == 7
    assert len(_ALL_SURFACE_ENTRIES) == 11
    assert _SEED_COUNT == 11


def test_surface_all_eleven_entries_seeded_is_the_control_and_passes(tmp_path):
    _seed_surface(tmp_path)
    violations, scanned, infra = _scan(tmp_path)
    assert infra is None
    assert violations == []
    assert scanned == _SEED_COUNT
    result = _run_cli(tmp_path)
    assert result.returncode == 0, result.stdout + result.stderr
    assert f"{_SEED_COUNT} file(s) scanned" in result.stdout


@pytest.mark.parametrize("omitted", _ALL_SURFACE_ENTRIES)
def test_infra_unmatched_surface_entry_exits_two_with_entry_only(tmp_path, omitted):
    _seed_surface(tmp_path, omit=(omitted,))
    violations, scanned, infra = _scan(tmp_path)
    assert (violations, scanned) == ([], 0)
    assert infra == f"{_UNMATCHED}: {omitted}"
    result = _run_cli(tmp_path)
    assert result.returncode == 2, result.stdout + result.stderr
    # Bounded diagnostic: the code and the entry string, nothing else.
    assert result.stderr.strip() == (
        f"INSTALLMENT CLOUD REACHABILITY: infrastructure error ({_UNMATCHED}: {omitted})"
    )
    assert str(tmp_path) not in result.stderr
    assert "PASS" not in result.stdout
    assert "FAIL" not in result.stdout


@pytest.mark.parametrize("omitted", _ALL_SURFACE_ENTRIES)
def test_infra_unmatched_entry_hides_violations_in_the_remaining_files(tmp_path, omitted):
    # Fail closed: a stale surface is never reported as a partial violation list.
    _seed_surface(tmp_path, omit=(omitted,))
    _write(
        tmp_path,
        "some/pkg/FooPromptBuilder.kt",
        f"package {_BASE}.some.pkg\nval p: InstallmentPlan? = null\n",
    )
    violations, scanned, infra = _scan(tmp_path)
    assert (violations, scanned) == ([], 0)
    assert infra == f"{_UNMATCHED}: {omitted}"
    result = _run_cli(tmp_path)
    assert result.returncode == 2, result.stdout + result.stderr


@pytest.mark.parametrize("omitted", _ALL_SURFACE_ENTRIES)
def test_infra_name_pattern_file_does_not_satisfy_a_missing_entry(tmp_path, omitted):
    _seed_surface(tmp_path, omit=(omitted,))
    _write(tmp_path, "some/pkg/FooPromptFormatter.kt", "package probe\nval ok = 1\n")
    _violations, _scanned, infra = _scan(tmp_path)
    assert infra == f"{_UNMATCHED}: {omitted}"


def test_name_pattern_only_file_does_not_trigger_the_unmatched_check(tmp_path):
    # Name patterns are open-ended by design and exempt from staleness: with all
    # eleven entries present, an extra pattern-only file (outside every entry
    # directory) passes, and so does a tree with no pattern file at all.
    _seed_surface(tmp_path)
    _write(tmp_path, "some/pkg/FooCloudPayloadBuilder.kt", "package probe\nval ok = 1\n")
    _write(tmp_path, "some/pkg/DashboardBriefingInputBuilder.kt", "package probe\nval ok = 1\n")
    violations, scanned, infra = _scan(tmp_path)
    assert infra is None
    assert violations == []
    assert scanned == _SEED_COUNT + 2
    result = _run_cli(tmp_path)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "PASS" in result.stdout


def test_unmatched_check_is_reported_for_the_first_missing_entry_in_declared_order(tmp_path):
    _seed_surface(tmp_path, omit=("domain/privacy", "di/AiModule.kt"))
    _violations, _scanned, infra = _scan(tmp_path)
    assert infra == f"{_UNMATCHED}: domain/privacy"


@pytest.mark.parametrize(
    "path, expected",
    [
        ("app/src/main/java/x/data/ai/Foo.kt", ["data/ai"]),
        ("app/src/main/java/x/domain/ai/Foo.kt", ["domain/ai"]),
        ("app/src/main/java/x/domain/privacy/Foo.kt", ["domain/privacy"]),
        ("app/src/main/java/x/data/privacy/Foo.kt", ["data/privacy"]),
        ("app/src/main/java/x/di/AiModule.kt", ["di/AiModule.kt"]),
        ("app/src/main/java/x/di/PrivacyModule.kt", ["di/PrivacyModule.kt"]),
        (
            "app/src/main/java/x/data/repository/FinancialWeatherRepository.kt",
            ["data/repository/FinancialWeatherRepository.kt"],
        ),
        (
            "app/src/main/java/x/domain/forecasting/ForecastInputAssembler.kt",
            ["domain/forecasting/ForecastInputAssembler.kt"],
        ),
        (
            "app/src/main/java/x/domain/logic/SynthesisEngine.kt",
            ["domain/logic/SynthesisEngine.kt"],
        ),
        (
            "app/src/main/java/x/domain/logic/NarrativeGenerator.kt",
            ["domain/logic/NarrativeGenerator.kt"],
        ),
        (
            "app/src/main/java/x/domain/usecase/dashboard/DashboardDataProvider.kt",
            ["domain/usecase/dashboard/DashboardDataProvider.kt"],
        ),
        ("app/src/main/java/x/data/repository/HomeInstallmentWeatherSource.kt", []),
        ("app/src/main/java/x/data/repository/XFinancialWeatherRepository.kt", []),
        ("some/pkg/FooCloudPayloadBuilder.kt", []),
        ("mydata/ai/Foo.kt", []),
        ("di/AiModuleFoo.kt", []),
    ],
)
def test_matched_surface_entries_reports_only_directory_and_exact_entries(path, expected):
    assert sorted(guard.matched_surface_entries(path)) == sorted(expected)


def test_infra_missing_production_source_manifest_exits_two(tmp_path):
    result = _run_cli(tmp_path, manifest=False)  # nothing below tmp_path
    assert result.returncode == 2
    assert "E_INSTALLMENT_CLOUD_SOURCE_SCOPE_UNRESOLVED" in result.stderr
    # Bounded diagnostic: no filesystem path in the stderr payload.
    assert str(tmp_path) not in result.stderr
    assert "PASS" not in result.stdout


def test_infra_missing_manifest_does_not_fall_back_to_conventional_root(tmp_path):
    # Even a tree with a real violation must fail closed (infra), not scan.
    _write(
        tmp_path,
        "data/ai/A.kt",
        f"package {_BASE}.data.ai\nimport {_BASE}.domain.installment.Foo\n",
    )
    result = _run_cli(tmp_path, manifest=False)
    assert result.returncode == 2
    assert "E_INSTALLMENT_CLOUD_SOURCE_SCOPE_UNRESOLVED" in result.stderr


def test_infra_unparseable_file_unterminated_template_exits_two(tmp_path):
    _write(
        tmp_path,
        "data/ai/Broken.kt",
        f"package {_BASE}.data.ai\n"
        'val s = "value ${plan\n'
        "val other = 1\n",
    )
    violations, scanned, infra = _scan(tmp_path)
    assert (violations, scanned) == ([], 0)
    assert infra == "E_INSTALLMENT_CLOUD_SOURCE_UNPARSEABLE"
    result = _run_cli(tmp_path)
    assert result.returncode == 2, result.stdout + result.stderr
    assert "E_INSTALLMENT_CLOUD_SOURCE_UNPARSEABLE" in result.stderr
    assert "PASS" not in result.stdout


def test_infra_unparseable_file_hides_violations_in_other_files(tmp_path):
    # Fail closed: a parse failure is never reported as a partial pass/violation.
    _write(
        tmp_path,
        "data/ai/A.kt",
        f"package {_BASE}.data.ai\nimport {_BASE}.domain.installment.Foo\n",
    )
    _write(tmp_path, "data/ai/Broken.kt", 'val s = "unterminated ${x\n')
    result = _run_cli(tmp_path)
    assert result.returncode == 2, result.stdout + result.stderr


def test_infra_undecodable_file_exits_two(tmp_path):
    path = _write(tmp_path, "data/ai/Binary.kt", "placeholder\n")
    path.write_bytes(b"package probe\n\xff\xfe\xfa invalid utf-8\n")
    violations, scanned, infra = _scan(tmp_path)
    assert (violations, scanned) == ([], 0)
    assert infra is not None and infra.startswith("E_INSTALLMENT_CLOUD_")
    result = _run_cli(tmp_path)
    assert result.returncode == 2, result.stdout + result.stderr
    assert "E_INSTALLMENT_CLOUD_" in result.stderr
    assert "PASS" not in result.stdout


def test_infra_unreadable_file_exits_two(tmp_path):
    path = _write(tmp_path, "data/ai/Locked.kt", f"package {_BASE}.data.ai\nval ok = 1\n")
    if os.name == "nt" or (hasattr(os, "geteuid") and os.geteuid() == 0):
        pytest.skip("chmod cannot make a file unreadable on this platform/user")
    original_mode = path.stat().st_mode
    path.chmod(0o000)
    try:
        if os.access(path, os.R_OK):
            pytest.skip("chmod did not make the file unreadable on this platform")
        result = _run_cli(tmp_path)
        assert result.returncode == 2, result.stdout + result.stderr
        assert "E_INSTALLMENT_CLOUD_" in result.stderr
        assert "PASS" not in result.stdout
    finally:
        path.chmod(original_mode)


# ── Output hygiene ──────────────────────────────────────────────────────────


def test_output_never_echoes_fixture_content(tmp_path):
    secret = "SECRET_TOKEN_9f3a7c"
    _seed_surface(tmp_path)
    _write(
        tmp_path,
        "data/ai/Leaky.kt",
        f"package {_BASE}.data.ai\n"
        f"val p: InstallmentPlanDto{secret} = null // {secret}\n"
        f"import {_BASE}.domain.installment.Foo as Alias{secret}\n"
        f"val q: {_BASE}.service.installment.Bar{secret}? = null\n",
    )
    violations, _scanned, infra = _scan(tmp_path)
    assert infra is None
    assert {v.rule_id for v in violations} == {R1, R2, R3}
    for violation in violations:
        assert secret not in violation.description
        assert secret not in violation.path
    result = _run_cli(tmp_path)
    assert result.returncode == 1
    assert secret not in result.stdout
    assert secret not in result.stderr
    assert "InstallmentPlanDto" not in result.stdout
    # One bounded line per violation, each carrying path:line [rule].
    p = _rel("data/ai/Leaky.kt")
    assert f"{p}:2 [{R3}]" in result.stdout
    assert f"{p}:3 [{R1}]" in result.stdout
    assert f"{p}:3 [{R3}]" in result.stdout
    assert f"{p}:4 [{R2}]" in result.stdout
    assert f"{p}:4 [{R3}]" in result.stdout
    # Fixed descriptions are what is printed after the rule id.
    assert f"[{R1}] {_DESC_R1}" in result.stdout
    assert f"[{R2}] {_DESC_R2}" in result.stdout
    assert f"[{R3}] {_DESC_R3}" in result.stdout
    assert "5 installment cloud reachability violation(s)" in result.stdout


def test_cli_accepts_canonical_fail_on_violation_flag(tmp_path):
    _seed_surface(tmp_path)
    _write(tmp_path, "data/ai/Clean.kt", f"package {_BASE}.data.ai\nval ok = 1\n")
    result = _run_cli(tmp_path, flag=True)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "PASS" in result.stdout
    assert f"{_SEED_COUNT + 1} file(s) scanned" in result.stdout


def test_cli_runs_without_the_canonical_flag_on_a_clean_tree(tmp_path):
    _seed_surface(tmp_path)
    _write(tmp_path, "data/ai/Clean.kt", f"package {_BASE}.data.ai\nval ok = 1\n")
    result = _run_cli(tmp_path, flag=False)
    assert result.returncode == 0, result.stdout + result.stderr


# ── is_cloud_surface_path truth table ───────────────────────────────────────


@pytest.mark.parametrize(
    "path",
    [
        "app/src/main/java/com/yourname/expensetracker/data/ai/provider/CloudDashboardBriefingService.kt",
        "data/ai/X.kt",
        "domain/ai/usecase/Foo.kt",
        "domain/privacy/CloudPayloadPolicy.kt",
        "data/privacy/DefaultRedactor.kt",
        "app/src/main/java/com/yourname/expensetracker/di/AiModule.kt",
        "app/src/main/java/com/yourname/expensetracker/di/PrivacyModule.kt",
        "app/src/main/java/com/yourname/expensetracker/data/repository/FinancialWeatherRepository.kt",
        "app/src/main/java/com/yourname/expensetracker/domain/forecasting/ForecastInputAssembler.kt",
        "app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt",
        "app/src/main/java/com/yourname/expensetracker/domain/logic/NarrativeGenerator.kt",
        "app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/DashboardDataProvider.kt",
        "di/AiModule.kt",
        "some/pkg/FooCloudPayloadBuilder.kt",
        "some/pkg/CloudPayloadFoo.kt",
        "some/pkg/FooPromptFormatter.kt",
        "some/pkg/FooPromptBuilder.kt",
        "some/pkg/FooPayloadAssembler.kt",
        "some/pkg/FooPayloadBuilder.kt",
        "some/pkg/DashboardBriefingInputBuilder.kt",
        "some/pkg/BriefingInputBuilder.kt",
        "some/pkg/InsightInputBuilder.kt",
        "some/pkg/AssistantFooInputBuilder.kt",
        "some/pkg/QueryInputBuilder.kt",
        "app\\src\\main\\java\\data\\ai\\X.kt",
    ],
)
def test_is_cloud_surface_path_true(path):
    assert guard.is_cloud_surface_path(path) is True


@pytest.mark.parametrize(
    "path",
    [
        "ui/screens/home/X.kt",
        "data/repository/ExpenseRepository.kt",
        "data/aix/Foo.kt",
        "mydata/ai/Foo.kt",
        "domain/airline/Foo.kt",
        "domain/privacyx/Foo.kt",
        "data/privacy2/Foo.kt",
        "domain/installment/InstallmentPlan.kt",
        "di/NetworkModule.kt",
        "xdi/AiModule.kt",
        "di/AiModule.kt.bak",
        "di/AiModuleFoo.kt",
        "data/repository/HomeInstallmentWeatherSource.kt",
        "data/repository/XFinancialWeatherRepository.kt",
        "xdata/repository/FinancialWeatherRepository.kt",
        "data/repository/FinancialWeatherRepository.kt.bak",
        "domain/forecasting/XForecastInputAssembler.kt",
        "domain/logic/XSynthesisEngine.kt",
        "domain/logic/NarrativeGeneratorFoo.kt",
        "domain/usecase/dashboard/XDashboardDataProvider.kt",
        "some/pkg/Foo.java",
        "some/pkg/CloudPayload.java",
        "some/pkg/DashboardInputBuilder.kt",
        "some/pkg/Dashboard.kt",
        "cloudpayload/Foo.kt",
        "some/pkg/cloudpayload.kt",
    ],
)
def test_is_cloud_surface_path_false(path):
    assert guard.is_cloud_surface_path(path) is False


# ── Registry consistency ────────────────────────────────────────────────────


def _load_registry_module():
    path = _SCRIPTS_DIR / "ci" / "guard_registry.py"
    spec = importlib.util.spec_from_file_location("guard_registry_installment_cloud_test", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_registry_entry_rule_ids_match_script_constants():
    registry = _load_registry_module().GUARD_REGISTRY
    entry = registry["installment_cloud_reachability"]
    assert tuple(entry["execution"]["ruleIds"]) == (
        guard.RULE_IMPORT, guard.RULE_QUALIFIED, guard.RULE_TYPE_NAME,
    )
    assert entry["script"] == "scripts/verify_installment_cloud_reachability.py"
    assert entry["tests"] == "scripts/test_verify_installment_cloud_reachability.py"
    assert tuple(entry["execution"]["testManifest"]) == (entry["tests"],)
    assert entry["mode"] == "blocking"
    assert entry["baseline"] is None
    assert entry["allowlist"] is None
    assert "--fail-on-violation" in tuple(entry["execution"]["arguments"])


def test_registry_validation_passes_for_the_installment_entry():
    module = _load_registry_module()
    errors = module.validate_registry(str(_REPO_ROOT))
    mine = [e for e in errors if e.startswith("installment_cloud_reachability")]
    assert mine == [], mine
    assert (_REPO_ROOT / "scripts" / "test_verify_installment_cloud_reachability.py").is_file()
    assert (_REPO_ROOT / "scripts" / "verify_installment_cloud_reachability.py").is_file()
