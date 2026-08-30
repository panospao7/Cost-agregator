#!/usr/bin/env python3
"""
test_verify_deprecation_escalations.py -- Pytest suite for PR-GR-10a.

Covers scripts/ci/verify_deprecation_escalations.py:

  1. Masking immunity: ERROR-deprecation text inside line/block comments,
     KDoc, and string literals never produces a site.
  2. Site extraction: named and positional ``DeprecationLevel.ERROR``,
     intervening annotations (``@Query``/``@Suppress``), modifier skipping,
     overload fingerprint collapse, WARNING-level immunity.
  3. Changelog parsing: header detection, separator rows, malformed cell
     counts, invalid cells, duplicates, missing ledger table, out-of-scope
     file paths.
  4. Verdicts: exit 0 (all covered), exit 1 (missing / stale findings),
     exit 2 (infrastructure + malformed changelog, fail closed).
  5. Deterministic output ordering across repeated runs.
  6. Registry + CI manifest wiring for the ``deprecation_escalations`` guard.

Every filesystem test builds its own synthetic repository under ``tmp_path``;
no test scans the real repository or executes Gradle.

Run:
    python -m pytest scripts/ci/test_verify_deprecation_escalations.py -v
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

_SCRIPT_DIR = str(Path(__file__).resolve().parent)
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

import verify_deprecation_escalations as vde  # noqa: E402
import guard_registry  # noqa: E402
import run_static_guard_suite  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[2]
SRC = "app/src/main/java"
CHANGELOG_REL = "docs/ci/DEPRECATION_ESCALATIONS.md"

GUARD_NAME = "deprecation_escalations"
GUARD_SCRIPT = "scripts/ci/verify_deprecation_escalations.py"
GUARD_TESTS = "scripts/ci/test_verify_deprecation_escalations.py"


# ── Fixtures (Kotlin snippets) ──────────────────────────────────────────────────

KOTLIN_SINGLE = (
    "package com.example\n"
    "\n"
    "@Deprecated(\"Use newApi()\", level = DeprecationLevel.ERROR)\n"
    "suspend fun oldApi(): Int = 1\n"
)

KOTLIN_DAO_STYLE = (
    "package com.example\n"
    "\n"
    "@Deprecated(\n"
    "    \"Raw SUM across mixed currencies.\",\n"
    "    ReplaceWith(\"repo.safeTotal(start, end)\"),\n"
    "    level = DeprecationLevel.ERROR\n"
    ")\n"
    "// TODO remove after migration\n"
    "@Query(\"\"\"\n"
    "    SELECT SUM(amount) FROM expenses\n"
    "    WHERE date >= :startMs AND date < :endMs\n"
    "\"\"\")\n"
    "suspend fun getTotalSpentBetween(startMs: Long, endMs: Long): Double?\n"
)

KOTLIN_MASKED = (
    "package com.example\n"
    "\n"
    "// @Deprecated(\"fake\", level = DeprecationLevel.ERROR)\n"
    "/* @Deprecated(\"fake\", level = DeprecationLevel.ERROR) */\n"
    "/**\n"
    " * @Deprecated(\"doc\", level = DeprecationLevel.ERROR)\n"
    " */\n"
    "val fake = \"@Deprecated(\\\"x\\\", level = DeprecationLevel.ERROR)\"\n"
)

KOTLIN_WARNING = (
    "package com.example\n"
    "\n"
    "@Deprecated(\"legacy\", level = DeprecationLevel.WARNING)\n"
    "fun old() {}\n"
)

KOTLIN_POSITIONAL = (
    "package com.example\n"
    "\n"
    "@Deprecated(\"msg\", DeprecationLevel.ERROR)\n"
    "fun positional() {}\n"
)

KOTLIN_OVERLOADS = (
    "package com.example\n"
    "\n"
    "@Deprecated(\"m\", level = DeprecationLevel.ERROR)\n"
    "fun createExpense(a: Int) {}\n"
    "\n"
    "@Deprecated(\"m\", level = DeprecationLevel.ERROR)\n"
    "fun createExpense(a: String) {}\n"
)

KOTLIN_SUPPRESS = (
    "package com.example\n"
    "\n"
    "@Deprecated(\"m\", level = DeprecationLevel.ERROR)\n"
    "@Suppress(\"UNUSED_PARAMETER\")\n"
    "suspend fun markBillPaid(id: Long): Nothing =\n"
    "    throw IllegalStateException()\n"
)


# ── Helpers ─────────────────────────────────────────────────────────────────────

def _write(root, rel, content):
    """Create ``root/rel`` (parents included) with text content."""
    target = Path(root).joinpath(*rel.split("/"))
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def _ledger(rows, date="2026-08-30"):
    """Build changelog text from (file, symbol) tuples."""
    lines = [
        "# Deprecation Escalation Changelog",
        "",
        "| File | Symbol | Date | Reason | Migration target |",
        "| --- | --- | --- | --- | --- |",
    ]
    for file_cell, symbol_cell in rows:
        lines.append(
            f"| {file_cell} | {symbol_cell} | {date} | test reason | test target |"
        )
    return "\n".join(lines) + "\n"


def _make_repo(tmp_path, sources, rows=(), changelog_text=None):
    for rel, content in sources.items():
        _write(tmp_path, rel, content)
    if changelog_text is None:
        changelog_text = _ledger(rows)
    _write(tmp_path, CHANGELOG_REL, changelog_text)


def _run(root, capsys, changelog=CHANGELOG_REL, source=SRC):
    """Run the guard CLI; returns (exit_code, stdout, stderr)."""
    argv = ["--root", str(root), "--changelog", changelog, "--source", source]
    with pytest.raises(SystemExit) as excinfo:
        vde.main(argv)
    out = capsys.readouterr()
    return excinfo.value.code, out.out, out.err


def _src_path(filename):
    return f"{SRC}/com/example/{filename}"


# ── Masking ─────────────────────────────────────────────────────────────────────

def test_mask_kotlin_blanks_comments_and_strings_keeps_newlines():
    masked = vde.mask_kotlin('val a = 1 // @Deprecated("x")\nval b = "y("')
    assert "Deprecated" not in masked
    assert "y(" not in masked
    assert masked.count("\n") == 2
    assert "val a = 1" in masked


def test_mask_kotlin_preserves_template_expressions():
    masked = vde.mask_kotlin('val msg = "total ${compute(x)} done"')
    assert "${compute(x)}" in masked.replace(" ", "")
    assert "total" not in masked


# ── Site extraction ─────────────────────────────────────────────────────────────

def test_single_site_extracted(tmp_path):
    _make_repo(tmp_path, {_src_path("A.kt"): KOTLIN_SINGLE})
    sites = vde.scan_error_deprecation_sites(str(tmp_path), SRC)
    assert sites == {(_src_path("A.kt"), "oldApi"): 3}


def test_dao_style_intervening_annotations_extracted(tmp_path):
    _make_repo(tmp_path, {_src_path("Dao.kt"): KOTLIN_DAO_STYLE})
    sites = vde.scan_error_deprecation_sites(str(tmp_path), SRC)
    assert list(sites) == [(_src_path("Dao.kt"), "getTotalSpentBetween")]


def test_positional_error_level_detected(tmp_path):
    _make_repo(tmp_path, {_src_path("P.kt"): KOTLIN_POSITIONAL})
    sites = vde.scan_error_deprecation_sites(str(tmp_path), SRC)
    assert (_src_path("P.kt"), "positional") in sites


def test_warning_level_not_flagged(tmp_path):
    _make_repo(tmp_path, {_src_path("W.kt"): KOTLIN_WARNING})
    assert vde.scan_error_deprecation_sites(str(tmp_path), SRC) == {}


def test_masked_comment_immunity(tmp_path):
    _make_repo(tmp_path, {_src_path("M.kt"): KOTLIN_MASKED})
    assert vde.scan_error_deprecation_sites(str(tmp_path), SRC) == {}


def test_overloads_collapse_to_one_fingerprint(tmp_path):
    _make_repo(tmp_path, {_src_path("O.kt"): KOTLIN_OVERLOADS})
    sites = vde.scan_error_deprecation_sites(str(tmp_path), SRC)
    assert list(sites) == [(_src_path("O.kt"), "createExpense")]


def test_suppress_between_annotation_and_declaration(tmp_path):
    _make_repo(tmp_path, {_src_path("S.kt"): KOTLIN_SUPPRESS})
    sites = vde.scan_error_deprecation_sites(str(tmp_path), SRC)
    assert (_src_path("S.kt"), "markBillPaid") in sites


def test_unterminated_annotation_is_fatal(tmp_path):
    broken = "package com.example\n\n@Deprecated(\"x\", level = DeprecationLevel.ERROR\n"
    _make_repo(tmp_path, {_src_path("B.kt"): broken})
    with pytest.raises(vde.GuardFatalError):
        vde.scan_error_deprecation_sites(str(tmp_path), SRC)


def test_unresolvable_declaration_is_fatal(tmp_path):
    # Annotation applied to an expression (not a declaration) cannot be
    # fingerprinted -> fail closed with a fatal error.
    broken = (
        "package com.example\n"
        "\n"
        "@Deprecated(\"x\", level = DeprecationLevel.ERROR)\n"
        "println(\"hi\")\n"
    )
    _make_repo(tmp_path, {_src_path("U.kt"): broken})
    with pytest.raises(vde.GuardFatalError):
        vde.scan_error_deprecation_sites(str(tmp_path), SRC)


# ── Changelog parsing ───────────────────────────────────────────────────────────

def test_parse_entries_roundtrip():
    text = _ledger([("a/b.kt", "foo"), ("a/c.kt", "bar")])
    entries = vde.parse_changelog_entries(text)
    assert set(entries) == {("a/b.kt", "foo"), ("a/c.kt", "bar")}
    assert all(isinstance(v, int) for v in entries.values())


def test_parse_ignores_tables_above_ledger():
    text = (
        "| Other | Columns |\n"
        "| --- | --- |\n"
        "| x | y |\n"
        "\n"
        "# Ledger\n"
        "\n"
        + _ledger([("a/b.kt", "foo")])
    )
    assert set(vde.parse_changelog_entries(text)) == {("a/b.kt", "foo")}


def test_parse_missing_ledger_table_raises():
    with pytest.raises(vde.ChangelogError):
        vde.parse_changelog_entries("# just prose\nno table here\n")


def test_parse_wrong_cell_count_raises():
    text = _ledger([]) + "| a/b.kt | foo | 2026-08-30 | only-four |\n"
    with pytest.raises(vde.ChangelogError):
        vde.parse_changelog_entries(text)


def test_parse_bad_date_raises():
    text = _ledger([]) + "| a/b.kt | foo | 2026-13-99 | r | m |\n"
    with pytest.raises(vde.ChangelogError):
        vde.parse_changelog_entries(text)


def test_parse_bad_symbol_raises():
    text = _ledger([]) + "| a/b.kt | not a symbol | 2026-08-30 | r | m |\n"
    with pytest.raises(vde.ChangelogError):
        vde.parse_changelog_entries(text)


def test_parse_empty_reason_raises():
    text = _ledger([]) + "| a/b.kt | foo | 2026-08-30 |  | m |\n"
    with pytest.raises(vde.ChangelogError):
        vde.parse_changelog_entries(text)


def test_parse_duplicate_entry_raises():
    text = _ledger([("a/b.kt", "foo"), ("a/b.kt", "foo")])
    with pytest.raises(vde.ChangelogError):
        vde.parse_changelog_entries(text)


def test_parse_backslash_file_raises():
    text = _ledger([]) + "| a\\b.kt | foo | 2026-08-30 | r | m |\n"
    with pytest.raises(vde.ChangelogError):
        vde.parse_changelog_entries(text)


def test_out_of_scope_entry_reported_stale(tmp_path, capsys):
    # Shape-valid entry whose file lies outside the scanned source root can
    # never match a live site -> stale finding (exit 1), not malformed.
    _make_repo(
        tmp_path,
        {_src_path("A.kt"): KOTLIN_SINGLE},
        rows=[(_src_path("A.kt"), "oldApi"), ("docs/notes.kt", "ghost")],
    )
    code, out, _err = _run(tmp_path, capsys)
    assert code == 1
    assert vde.RULE_ID_STALE in out
    assert "docs/notes.kt" in out


# ── Verdicts ────────────────────────────────────────────────────────────────────

def test_all_sites_covered_exits_zero(tmp_path, capsys):
    _make_repo(
        tmp_path,
        {_src_path("A.kt"): KOTLIN_SINGLE, _src_path("Dao.kt"): KOTLIN_DAO_STYLE},
        rows=[
            (_src_path("A.kt"), "oldApi"),
            (_src_path("Dao.kt"), "getTotalSpentBetween"),
        ],
    )
    code, out, _err = _run(tmp_path, capsys)
    assert code == 0
    assert out.startswith("PASS:")


def test_new_site_without_entry_exits_one(tmp_path, capsys):
    _make_repo(
        tmp_path,
        {_src_path("A.kt"): KOTLIN_SINGLE, _src_path("Dao.kt"): KOTLIN_DAO_STYLE},
        rows=[(_src_path("A.kt"), "oldApi")],
    )
    code, out, _err = _run(tmp_path, capsys)
    assert code == 1
    assert out.startswith(vde.RULE_ID_MISSING)
    assert "oldApi" in out


def test_stale_entry_exits_one(tmp_path, capsys):
    _make_repo(
        tmp_path,
        {_src_path("A.kt"): KOTLIN_SINGLE},
        rows=[(_src_path("A.kt"), "oldApi"), (_src_path("Gone.kt"), "removed")],
    )
    code, out, _err = _run(tmp_path, capsys)
    assert code == 1
    assert vde.RULE_ID_STALE in out
    assert "removed" in out


def test_missing_and_stale_reported_together_missing_first(tmp_path, capsys):
    _make_repo(
        tmp_path,
        {_src_path("A.kt"): KOTLIN_SINGLE},
        rows=[(_src_path("Gone.kt"), "removed")],
    )
    code, out, _err = _run(tmp_path, capsys)
    assert code == 1
    lines = [ln for ln in out.splitlines() if ln.startswith("G-DEPRECATION-")]
    assert len(lines) == 2
    assert lines[0].startswith(vde.RULE_ID_MISSING)
    assert lines[1].startswith(vde.RULE_ID_STALE)


def test_missing_changelog_file_exits_two(tmp_path, capsys):
    _write(tmp_path, _src_path("A.kt"), KOTLIN_SINGLE)
    code, out, err = _run(tmp_path, capsys, changelog="docs/ci/absent.md")
    assert code == 2
    assert out == ""
    assert "changelog not found" in err


def test_missing_source_tree_exits_two(tmp_path, capsys):
    _write(tmp_path, CHANGELOG_REL, _ledger([]))
    code, out, err = _run(tmp_path, capsys)
    assert code == 2
    assert out == ""
    assert "source tree not found" in err


def test_empty_source_tree_exits_two(tmp_path, capsys):
    _write(tmp_path, _src_path(".keep"), "")
    _write(tmp_path, CHANGELOG_REL, _ledger([]))
    code, out, err = _run(tmp_path, capsys)
    assert code == 2
    assert out == ""
    assert "no Kotlin/Java source files" in err


def test_unreadable_source_file_exits_two(tmp_path, capsys, monkeypatch):
    _make_repo(tmp_path, {_src_path("A.kt"): KOTLIN_SINGLE})
    monkeypatch.setattr(
        vde, "safe_read_file",
        lambda _path: (None, "Permission denied: <simulated>"),
    )
    code, out, err = _run(tmp_path, capsys)
    assert code == 2
    assert out == ""
    assert "cannot read source file" in err


def test_malformed_changelog_exits_two(tmp_path, capsys):
    _make_repo(
        tmp_path,
        {_src_path("A.kt"): KOTLIN_SINGLE},
        rows=[(_src_path("A.kt"), "oldApi")],
        changelog_text=_ledger([]) + "| broken | row | only |\n",
    )
    code, out, err = _run(tmp_path, capsys)
    assert code == 2
    assert out == ""
    assert "malformed changelog" in err


def test_changelog_without_ledger_table_exits_two(tmp_path, capsys):
    _make_repo(
        tmp_path,
        {_src_path("A.kt"): KOTLIN_SINGLE},
        changelog_text="# no table\n",
    )
    code, out, err = _run(tmp_path, capsys)
    assert code == 2
    assert out == ""
    assert "malformed changelog" in err


# ── Determinism ─────────────────────────────────────────────────────────────────

def test_output_deterministic_and_sorted(tmp_path, capsys):
    sources = {
        _src_path("b.kt"): KOTLIN_SINGLE.replace("oldApi", "zeta"),
        _src_path("a.kt"): KOTLIN_SINGLE.replace("oldApi", "alpha"),
        _src_path("c.kt"): KOTLIN_SINGLE.replace("oldApi", "beta"),
    }
    _make_repo(tmp_path, sources)  # empty ledger -> three missing findings
    runs = []
    for _ in range(2):
        code, out, _err = _run(tmp_path, capsys)
        assert code == 1
        runs.append(out)
    assert runs[0] == runs[1]
    finding_lines = [
        ln for ln in runs[0].splitlines() if ln.startswith(vde.RULE_ID_MISSING)
    ]
    assert [ln.split()[1] for ln in finding_lines] == [
        f"{_src_path('a.kt')}:3",
        f"{_src_path('b.kt')}:3",
        f"{_src_path('c.kt')}:3",
    ]


# ── Registry + CI manifest wiring ───────────────────────────────────────────────

def test_guard_is_registered_blocking_with_tests_field():
    entry = guard_registry.GUARD_REGISTRY[GUARD_NAME]
    assert entry["mode"] == "blocking"
    assert entry["tests"] == GUARD_TESTS
    assert entry["script"] == GUARD_SCRIPT
    assert entry["description"]


def test_guard_files_exist_in_repository():
    entry = guard_registry.GUARD_REGISTRY[GUARD_NAME]
    assert (REPO_ROOT / entry["script"]).is_file()
    assert (REPO_ROOT / entry["tests"]).is_file()


def test_guard_is_in_ci_manifest():
    manifest_names = [
        name for name, _command, _mode in run_static_guard_suite.GUARD_MANIFEST
    ]
    assert GUARD_NAME in manifest_names


def test_registry_consistency_validation_passes():
    assert guard_registry.validate_registry(str(REPO_ROOT)) == []
