#!/usr/bin/env python3
"""
test_verify_spending_predicate_usage.py

Loan-installments slice S4d tests for the ``spending_predicate_usage`` guard
(scripts/verify_spending_predicate_usage.py), INV-1 / INV-8 and plan amendment
A12.6: every aggregate @Query over ``expenses`` in a production
data/database/dao file uses the SpendingInclusionSql owner or carries a bounded
``// spending-exempt: <REASON>`` marker; dedupe-marked queries never reference it.

Proven here on temporary synthetic fixture trees (no real repository content,
no personal data), plus one read-only run on the real tree:

  1. Rule 01 positive and negative fixtures (each aggregate function, JOIN,
     alias, lowercase SQL, qualified references in every accepted form, the
     unqualified member and unknown constants rejected);
  2. Rule 02 (every REASON accepted; unknown reason, malformed marker, marker
     without @Query, duplicate marker, marker plus SpendingInclusionSql reference);
  3. Rule 03 (DEDUPE_BLOCKING / DEDUPE_RESOLVER plus a reference);
  4. robust @Query parsing (triple-quoted, concatenation, named argument,
     templates with nested strings, comments, @Query in comments and strings);
  5. infrastructure failures exit 2 (no DAO file, no @Query, unparseable source,
     missing manifest, non-DAO files do not count as surface);
  6. output hygiene and determinism;
  7. registry and suite registration agree with the script;
  8. the real tree is clean, and the report-mode exemption set equals the
     recorded A12.6 set (34 markers: DEDUPE_BLOCKING 11, DEDUPE_RESOLVER 7,
     ROW_COUNT 7, NON_SPENDING_INCOME 4, NON_MONEY 2, SUGGESTION 1,
     ASSET_VALUE 2);
  9. stale-marker hygiene (Rule 02): a non-DEDUPE marker above a query that
     does not aggregate over expenses fails; DEDUPE markers stay allowed.
 10. Rule 04 (I-04-5): the @RawQuery builders are pinned in
     config/guards/spending_raw_query_builders.yml (clean tree passes; extra
     SimpleSQLiteQuery site, new/changed selectClause literal, stale pin, count
     mismatch, unpinned @RawQuery method, builder body without the amount
     owners all fail; commented-out and string-embedded sites are ignored;
     missing or malformed pin file is infrastructure exit 2).

Run:
    python -m pytest scripts/test_verify_spending_predicate_usage.py -v
"""

import ast
import importlib.util
import re
import subprocess
import sys
from pathlib import Path

import pytest
import yaml

_SCRIPTS_DIR = Path(__file__).resolve().parent
_REPO_ROOT = _SCRIPTS_DIR.parent
_SCRIPT = _SCRIPTS_DIR / "verify_spending_predicate_usage.py"
sys.path.insert(0, str(_SCRIPTS_DIR))

import verify_spending_predicate_usage as guard  # noqa: E402

R1 = "G-SPENDING-PREDICATE-01"
R2 = "G-SPENDING-PREDICATE-02"
R3 = "G-SPENDING-PREDICATE-03"
R4 = "G-SPENDING-PREDICATE-04"

_PKG = "com/yourname/expensetracker"
_JAVA = "app/src/main/java"
_DAO_SUB = "data/database/dao"
_DAO_REL = f"{_JAVA}/{_PKG}/{_DAO_SUB}/FooDao.kt"
_PATH = "app/src/main/java/com/yourname/expensetracker/data/database/dao/FooDao.kt"


# ── fixture helpers ────────────────────────────────────────────────────────────


def _dao_text(*members: str) -> str:
    return (
        "package com.yourname.expensetracker.data.database.dao\n"
        "\n"
        "import androidx.room.Query\n"
        "\n"
        "interface FooDao {\n"
        + "\n".join(members)
        + "\n}\n"
    )


def _line_of(text: str, needle: str) -> int:
    for number, line in enumerate(text.split("\n"), start=1):
        if needle in line:
            return number
    raise AssertionError("needle not found: " + needle)


def _scan(text: str):
    return guard.scan_text(_PATH, text)


def _triples(result):
    return [(v.rule_id, v.code, v.function) for v in result.violations]


def _q(sql: str, name: str = "f") -> str:
    return '    @Query("' + sql + '")\n    fun ' + name + "(): Double"


def _marked(reason: str, sql: str, name: str = "f") -> str:
    return "    // spending-exempt: " + reason + "\n" + _q(sql, name)


def _write_dao(tmp_path: Path, name: str, text: str) -> Path:
    path = tmp_path / _JAVA / _PKG / _DAO_SUB / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8", newline="\n")
    return path


def _write_manifest(root: Path) -> None:
    manifest = root / "config" / "guards" / "production_source_roots.yml"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(
        "schemaVersion: 1\n"
        "roots:\n"
        "  - module: ':app'\n"
        "    sourceSet: main\n"
        f"    path: {_JAVA}\n",
        encoding="utf-8",
    )


def _write_empty_pin(root: Path) -> None:
    """Rule 04 needs a pin file; an empty one is valid for trees without builders."""
    pin = root / guard.PIN_RELATIVE_PATH
    if pin.exists():
        return
    pin.parent.mkdir(parents=True, exist_ok=True)
    pin.write_text("version: 1\nexpectedCount: 0\nbuilders: []\n", encoding="utf-8")


def _run_cli(root: Path, *extra: str, manifest: bool = True) -> subprocess.CompletedProcess:
    if manifest:
        _write_manifest(root)
    _write_empty_pin(root)
    return subprocess.run(
        [sys.executable, str(_SCRIPT), "--root", str(root), *extra],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=120,
    )


# ── Rule 01 ────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "constant",
    ["SPENDING_PREDICATE", "SPENDING_PREDICATE_E", "EFFECTIVE_AMOUNT", "EFFECTIVE_AMOUNT_E"],
)
def test_qualified_reference_passes_for_each_accepted_constant(constant):
    text = _dao_text(
        _q("SELECT SUM(${SpendingInclusionSql." + constant + "}) FROM expenses e")
    )
    result = _scan(text)
    assert result.violations == []
    assert [c.classification for c in result.classified] == ["predicate"]


@pytest.mark.parametrize("aggregate", ["SUM", "AVG", "TOTAL", "COUNT", "MIN", "MAX", "sum", "Count"])
def test_each_aggregate_function_without_predicate_is_rule_01(aggregate):
    text = _dao_text(_q("SELECT " + aggregate + "(amount) FROM expenses", name="agg"))
    result = _scan(text)
    assert _triples(result) == [(R1, guard.CODE_UNCOVERED, "agg")]
    assert result.violations[0].line == _line_of(text, "@Query")
    assert result.classified[0].classification == "UNCLASSIFIED"


def test_join_expenses_with_aggregate_is_rule_01():
    text = _dao_text(
        _q("SELECT SUM(e.amount) FROM warranties w LEFT JOIN expenses e ON e.id = w.expenseId", name="j")
    )
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "j")]


def test_comma_join_expenses_with_aggregate_is_rule_01():
    text = _dao_text(
        _q("SELECT SUM(e.amount) FROM categories c, expenses e WHERE e.categoryId = c.id", name="cj")
    )
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "cj")]


def test_comma_join_expenses_with_predicate_passes():
    text = _dao_text(
        _q(
            "SELECT SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT_E}) "
            "FROM categories c, expenses e WHERE ${SpendingInclusionSql.SPENDING_PREDICATE_E}",
            name="cjp",
        )
    )
    result = _scan(text)
    assert result.violations == []
    assert [c.classification for c in result.classified] == ["predicate"]


def test_multiline_comma_join_expenses_is_rule_01():
    text = _dao_text(
        '    @Query("""\n'
        "        SELECT COUNT(*)\n"
        "        FROM categories c,\n"
        "             expenses e\n"
        "        WHERE e.categoryId = c.id\n"
        '    """)\n'
        "    fun cjm(): Int"
    )
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "cjm")]


def test_concatenated_comma_join_expenses_is_rule_01():
    text = _dao_text(
        '    @Query("SELECT COUNT(*) FROM categories c, " +\n'
        '        "expenses e WHERE e.categoryId = c.id")\n'
        "    fun cjc(): Int"
    )
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "cjc")]


def test_comma_before_expenses_prefixed_identifier_is_not_the_table():
    text = _dao_text(_q("SELECT COUNT(*) FROM categories c, expenses_archive a", name="arch"))
    assert _scan(text).violations == []


def test_mutation_with_aggregate_subquery_over_expenses_is_rule_01_literal_reading():
    # A12.6 is literal: ANY @Query naming expenses in FROM/JOIN with an aggregate.
    text = _dao_text(
        _q("DELETE FROM expenses WHERE id IN (SELECT MAX(id) FROM expenses GROUP BY dedupeKey)", name="d")
    )
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "d")]


def test_unqualified_member_is_not_accepted():
    text = _dao_text(_q("SELECT SUM(amount) FROM expenses WHERE ${SPENDING_PREDICATE}", name="u"))
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "u")]


def test_unknown_spending_inclusion_constant_is_not_accepted():
    text = _dao_text(
        _q("SELECT SUM(amount) FROM expenses WHERE ${SpendingInclusionSql.OTHER_THING}", name="o")
    )
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "o")]


def test_constant_prefix_of_longer_name_is_not_accepted():
    text = _dao_text(
        _q("SELECT SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT_EXTRA}) FROM expenses", name="p")
    )
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "p")]


@pytest.mark.parametrize(
    "sql",
    [
        "SELECT * FROM expenses WHERE id = :id",
        "SELECT SUM(amount) FROM budgets",
        "SELECT COUNT(*) FROM expenses_archive",
        "SELECT SUM(amount) FROM recurring_expenses",
        "SELECT EXISTS(SELECT 1 FROM expenses WHERE dedupeKey = :k LIMIT 1)",
        "UPDATE expenses SET categoryId = :c WHERE id = :id",
    ],
)
def test_out_of_scope_queries_pass(sql):
    result = _scan(_dao_text(_q(sql)))
    assert result.violations == []
    assert result.classified == []
    assert result.queries_parsed == 1


# ── Rule 02 ────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("reason", list(guard.ALLOWED_REASONS))
def test_every_allowed_reason_passes_and_is_classified(reason):
    text = _dao_text(_marked(reason, "SELECT COUNT(*) FROM expenses", name="m"))
    result = _scan(text)
    assert result.violations == []
    assert [(c.function, c.classification) for c in result.classified] == [("m", "exempt:" + reason)]


def test_marker_with_other_annotation_lines_between_passes():
    text = _dao_text(
        "    // spending-exempt: ROW_COUNT\n"
        "    @Transaction\n"
        "    // note\n"
        '    @Query("SELECT COUNT(*) FROM expenses")\n'
        "    fun f(): Int"
    )
    assert _scan(text).violations == []


def test_marker_under_a_preceding_annotation_passes():
    text = _dao_text(
        "    @Deprecated(\n"
        '        message = "x",\n'
        "        level = DeprecationLevel.WARNING\n"
        "    )\n"
        "    // spending-exempt: ASSET_VALUE\n"
        '    @Query(\n        """\n        SELECT SUM(e.amount) FROM warranties w LEFT JOIN expenses e ON e.id = w.id\n        """\n    )\n'
        "    fun f(): Double"
    )
    result = _scan(text)
    assert result.violations == []
    assert result.classified[0].classification == "exempt:ASSET_VALUE"


def test_unknown_reason_is_rule_02():
    text = _dao_text(_marked("NOT_A_REASON", "SELECT COUNT(*) FROM expenses", name="m"))
    result = _scan(text)
    assert _triples(result) == [(R2, guard.CODE_UNKNOWN_REASON, "m")]
    assert result.violations[0].line == _line_of(text, "spending-exempt")


def test_lowercase_reason_is_unknown():
    text = _dao_text(_marked("row_count", "SELECT COUNT(*) FROM expenses", name="m"))
    assert _triples(_scan(text)) == [(R2, guard.CODE_UNKNOWN_REASON, "m")]


@pytest.mark.parametrize(
    "marker_line",
    [
        "    // spending-exempt ROW_COUNT",
        "    // spending-exempt:",
        "    // spending-exempt: ROW_COUNT because of the badge",
        "    //spending-exempt:ROW_COUNT extra",
        "    val x = 1 // spending-exempt: ROW_COUNT",
    ],
)
def test_malformed_marker_is_rule_02(marker_line):
    text = _dao_text(marker_line + "\n" + _q("SELECT COUNT(*) FROM expenses", name="m"))
    triples = _triples(_scan(text))
    assert (R2, guard.CODE_MALFORMED, "-") in triples


def test_marker_without_following_query_is_rule_02_orphan():
    text = _dao_text(
        "    // spending-exempt: ROW_COUNT\n    fun helper(): Int = 0\n",
        _q("SELECT id FROM expenses", name="g"),
    )
    assert _triples(_scan(text)) == [(R2, guard.CODE_ORPHAN, "-")]


def test_marker_at_end_of_file_is_orphan():
    text = _dao_text(_q("SELECT id FROM expenses") + "\n    // spending-exempt: ROW_COUNT")
    assert _triples(_scan(text)) == [(R2, guard.CODE_ORPHAN, "-")]


def test_marker_separated_by_blank_line_is_orphan_and_query_is_uncovered():
    text = _dao_text(
        "    // spending-exempt: ROW_COUNT\n\n" + _q("SELECT COUNT(*) FROM expenses", name="m")
    )
    assert _triples(_scan(text)) == [
        (R2, guard.CODE_ORPHAN, "-"),
        (R1, guard.CODE_UNCOVERED, "m"),
    ]


def test_marker_inside_block_comment_is_ignored():
    text = _dao_text(
        "    /*\n     // spending-exempt: BOGUS\n    */\n"
        "    /** mentions // spending-exempt: BOGUS */\n"
        + _q("SELECT id FROM expenses")
    )
    assert _scan(text).violations == []


def test_duplicate_marker_is_rule_02():
    text = _dao_text(
        "    // spending-exempt: ROW_COUNT\n    // spending-exempt: ROW_COUNT\n"
        + _q("SELECT COUNT(*) FROM expenses", name="m")
    )
    result = _scan(text)
    assert _triples(result) == [(R2, guard.CODE_DUPLICATE, "m")]
    assert result.violations[0].line == _line_of(text, "spending-exempt") + 1


@pytest.mark.parametrize("reason", ["ROW_COUNT", "NON_MONEY", "SUGGESTION", "ASSET_VALUE", "NON_SPENDING_INCOME"])
def test_marker_with_spending_inclusion_reference_is_rule_02(reason):
    text = _dao_text(
        _marked(reason, "SELECT COUNT(*) FROM expenses WHERE ${SpendingInclusionSql.SPENDING_PREDICATE}", name="m")
    )
    result = _scan(text)
    assert _triples(result) == [(R2, guard.CODE_MARKER_WITH_REFERENCE, "m")]
    assert result.violations[0].line == _line_of(text, "@Query")


def test_marker_with_unqualified_spending_inclusion_mention_is_rule_02():
    text = _dao_text(
        _marked("ROW_COUNT", "SELECT COUNT(*) FROM expenses WHERE ${SpendingInclusionSql.NOT_A_CONSTANT}", name="m")
    )
    assert _triples(_scan(text)) == [(R2, guard.CODE_MARKER_WITH_REFERENCE, "m")]


# ── Rule 03 ────────────────────────────────────────────────────────────────────


@pytest.mark.parametrize("reason", ["DEDUPE_BLOCKING", "DEDUPE_RESOLVER"])
def test_dedupe_query_referencing_spending_inclusion_is_rule_03_only(reason):
    text = _dao_text(
        _marked(reason, "SELECT EXISTS(SELECT 1 FROM expenses WHERE ${SpendingInclusionSql.SPENDING_PREDICATE})", name="d")
    )
    assert _triples(_scan(text)) == [(R3, guard.CODE_DEDUPE_REFERENCE, "d")]


@pytest.mark.parametrize("reason", ["DEDUPE_BLOCKING", "DEDUPE_RESOLVER"])
def test_dedupe_marked_aggregate_without_reference_passes(reason):
    text = _dao_text(_marked(reason, "SELECT MAX(id) FROM expenses WHERE dedupeKey = :k", name="d"))
    result = _scan(text)
    assert result.violations == []
    assert result.classified[0].classification == "exempt:" + reason


# ── Rule 02: stale markers ─────────────────────────────────────────────────────


@pytest.mark.parametrize(
    "reason",
    ["NON_SPENDING_INCOME", "ROW_COUNT", "NON_MONEY", "SUGGESTION", "ASSET_VALUE"],
)
@pytest.mark.parametrize(
    "sql",
    [
        "SELECT * FROM expenses WHERE id = :id",
        "SELECT SUM(amount) FROM budgets",
        "SELECT COUNT(*) FROM expenses_archive",
        "UPDATE expenses SET categoryId = :c WHERE id = :id",
    ],
)
def test_non_dedupe_marker_above_out_of_scope_query_is_stale_rule_02(reason, sql):
    text = _dao_text(_marked(reason, sql, name="s"))
    result = _scan(text)
    assert _triples(result) == [(R2, guard.CODE_STALE_MARKER, "s")]
    assert result.violations[0].line == _line_of(text, "@Query")


@pytest.mark.parametrize("reason", ["DEDUPE_BLOCKING", "DEDUPE_RESOLVER"])
@pytest.mark.parametrize(
    "sql",
    [
        "SELECT * FROM expenses WHERE merchantKey = :k",
        "SELECT EXISTS(SELECT 1 FROM expenses WHERE dedupeKey = :k LIMIT 1)",
        "SELECT id FROM expenses WHERE rawNotificationId = :n LIMIT 1",
    ],
)
def test_dedupe_marker_above_non_aggregating_query_is_allowed(reason, sql):
    result = _scan(_dao_text(_marked(reason, sql, name="d")))
    assert result.violations == []
    assert [(c.function, c.classification) for c in result.classified] == [("d", "exempt:" + reason)]


def test_marker_above_aggregate_over_expenses_via_join_is_not_stale():
    text = _dao_text(
        _marked("ASSET_VALUE", "SELECT SUM(e.amount) FROM warranties w LEFT JOIN expenses e ON e.id = w.id", name="w")
    )
    assert _scan(text).violations == []


# ── @Query parsing ─────────────────────────────────────────────────────────────


def test_triple_quoted_multiline_query_with_template_is_parsed():
    text = _dao_text(
        '    @Query("""\n'
        "        SELECT SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT})\n"
        "        FROM expenses\n"
        "        WHERE ${EXPENSE_TYPE_SQL}\n"
        '    """)\n'
        "    fun f(): Double"
    )
    result = _scan(text)
    assert result.violations == []
    assert result.classified[0].classification == "predicate"


def test_triple_quoted_multiline_without_predicate_reports_annotation_line():
    text = _dao_text(
        '    @Query("""\n'
        "        SELECT SUM(amount)\n"
        "        FROM expenses\n"
        '    """)\n'
        "    fun multi(): Double"
    )
    result = _scan(text)
    assert [(v.line, v.function) for v in result.violations] == [(_line_of(text, "@Query"), "multi")]


def test_concatenated_literals_and_code_reference_are_parsed():
    text = _dao_text(
        '    @Query("SELECT SUM(amount) FROM " + "expenses WHERE " + SpendingInclusionSql.SPENDING_PREDICATE)\n'
        "    fun f(): Double"
    )
    assert _scan(text).violations == []


def test_concatenation_without_reference_is_rule_01():
    text = _dao_text(
        '    @Query("SELECT SUM(amount) FROM " +\n'
        '        "expenses")\n'
        "    fun f(): Double"
    )
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "f")]


def test_named_value_argument_is_parsed():
    text = _dao_text('    @Query(value = "SELECT COUNT(*) FROM expenses")\n    fun f(): Int')
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "f")]


def test_template_with_nested_string_literal_does_not_end_the_query_early():
    text = _dao_text(
        '    @Query("SELECT SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT}) FROM expenses WHERE c = \'${"x"}\'")\n'
        "    fun f(): Double"
    )
    assert _scan(text).violations == []


def test_escaped_quote_and_char_literal_do_not_break_parsing():
    text = _dao_text(
        "    val ch = 'x'\n"
        "    val q = '\\''\n"
        '    val s = "a \\" b"\n'
        + _q("SELECT SUM(amount) FROM expenses", name="f")
    )
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "f")]


def test_comment_inside_arguments_is_ignored():
    text = _dao_text(
        '    @Query(\n        // ${SpendingInclusionSql.SPENDING_PREDICATE}\n        "SELECT SUM(amount) FROM expenses"\n    )\n'
        "    fun f(): Double"
    )
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "f")]


def test_query_text_inside_comments_and_strings_is_not_parsed():
    text = _dao_text(
        '    // @Query("SELECT SUM(amount) FROM expenses")\n'
        '    /* @Query("SELECT SUM(amount) FROM expenses") */\n'
        '    /** Uses [@Query] with "SELECT SUM(amount) FROM expenses" */\n'
        '    val doc = "@Query(\\"SELECT SUM(amount) FROM expenses\\")"\n'
        + _q("SELECT id FROM expenses")
    )
    result = _scan(text)
    assert result.violations == []
    assert result.queries_parsed == 1


def test_raw_query_and_other_annotations_are_not_queries():
    text = _dao_text(
        "    @RawQuery\n    fun a(q: Q): Int\n    @QueryX(\"SELECT SUM(amount) FROM expenses\")\n    fun b(): Int"
    )
    assert _scan(text).queries_parsed == 0


def test_fully_qualified_query_annotation_is_parsed():
    text = _dao_text('    @androidx.room.Query("SELECT SUM(amount) FROM expenses")\n    fun f(): Double')
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "f")]


def test_function_name_with_modifiers_is_resolved():
    text = _dao_text('    @Query("SELECT SUM(amount) FROM expenses")\n    suspend fun   spaced (): Double')
    assert _triples(_scan(text)) == [(R1, guard.CODE_UNCOVERED, "spaced")]


def test_violations_are_ordered_by_line():
    text = _dao_text(
        _q("SELECT SUM(amount) FROM expenses", name="b"),
        _q("SELECT COUNT(*) FROM expenses", name="a"),
    )
    lines = [v.line for v in _scan(text).violations]
    assert lines == sorted(lines) and len(lines) == 2


@pytest.mark.parametrize(
    "broken",
    [
        'val s = "never closed\n',
        '@Query("SELECT SUM(amount) FROM expenses"\n',
        "/* never closed\n",
        'val s = """never closed\n',
        "val c = 'xx\n",
    ],
)
def test_unparseable_source_raises(broken):
    with pytest.raises(guard.KotlinParseError):
        guard.scan_text(_PATH, _dao_text(broken))


# ── fixture-tree infrastructure behaviour ─────────────────────────────────────


def test_scan_source_root_clean_tree(tmp_path):
    _write_dao(
        tmp_path,
        "FooDao.kt",
        _dao_text(
            _q("SELECT SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT}) FROM expenses", name="a"),
            _marked("ROW_COUNT", "SELECT COUNT(*) FROM expenses", name="b"),
        ),
    )
    outcome = guard.scan_source_root(tmp_path)
    assert outcome.infra_error is None
    assert outcome.violations == []
    assert outcome.files_scanned == 1 and outcome.queries_parsed == 2
    assert [(c.function, c.classification) for c in outcome.classified] == [
        ("a", "predicate"),
        ("b", "exempt:ROW_COUNT"),
    ]


def test_empty_surface_no_dao_file_is_infra(tmp_path):
    other = tmp_path / _JAVA / _PKG / "domain" / "Other.kt"
    other.parent.mkdir(parents=True)
    other.write_text(_dao_text(_q("SELECT SUM(amount) FROM expenses")), encoding="utf-8")
    outcome = guard.scan_source_root(tmp_path)
    assert outcome.infra_error == guard.E_SURFACE_EMPTY
    proc = _run_cli(tmp_path)
    assert proc.returncode == 2
    assert guard.E_SURFACE_EMPTY in proc.stderr
    assert "PASS" not in proc.stdout


def test_dao_files_without_any_query_is_infra(tmp_path):
    _write_dao(tmp_path, "FooDao.kt", _dao_text("    fun f(): Int = 0"))
    outcome = guard.scan_source_root(tmp_path)
    assert outcome.infra_error == guard.E_NO_QUERIES
    proc = _run_cli(tmp_path)
    assert proc.returncode == 2
    assert guard.E_NO_QUERIES in proc.stderr


def test_unparseable_dao_file_is_infra(tmp_path):
    _write_dao(tmp_path, "FooDao.kt", _dao_text('val s = "never closed'))
    proc = _run_cli(tmp_path)
    assert proc.returncode == 2
    assert guard.E_UNPARSEABLE in proc.stderr


def test_missing_manifest_is_infra(tmp_path):
    _write_dao(tmp_path, "FooDao.kt", _dao_text(_q("SELECT id FROM expenses")))
    proc = _run_cli(tmp_path, manifest=False)
    assert proc.returncode == 2
    assert "PASS" not in proc.stdout


def test_missing_source_root_is_infra(tmp_path):
    outcome = guard.scan_source_root(tmp_path / "does-not-exist")
    assert outcome.infra_error == guard.E_ROOT_MISSING


def test_non_dao_directory_files_are_not_scanned(tmp_path):
    _write_dao(tmp_path, "FooDao.kt", _dao_text(_q("SELECT id FROM expenses")))
    other = tmp_path / _JAVA / _PKG / "data" / "repository" / "BarRepo.kt"
    other.parent.mkdir(parents=True)
    other.write_text(_dao_text(_q("SELECT SUM(amount) FROM expenses")), encoding="utf-8")
    outcome = guard.scan_source_root(tmp_path)
    assert outcome.infra_error is None
    assert outcome.violations == [] and outcome.files_scanned == 1


@pytest.mark.parametrize(
    "path,expected",
    [
        ("app/src/main/java/com/x/data/database/dao/ExpenseDao.kt", True),
        ("app/src/main/java/com/x/data/database/dao/WarrantyDao.kt", True),
        ("app/src/main/java/com/x/data/database/dao/sub/Deep.kt", False),
        ("app/src/main/java/com/x/data/database/query/SpendingInclusionSql.kt", False),
        ("app/src/main/java/com/x/data/repository/ExpenseRepository.kt", False),
        ("app/src/main/java/com/x/data/database/dao/ExpenseDao.java", False),
    ],
)
def test_is_dao_path_truth_table(path, expected):
    assert guard.is_dao_path(path) is expected


# ── CLI behaviour, hygiene, determinism ───────────────────────────────────────


def test_cli_pass_exit_zero_and_flag_accepted(tmp_path):
    _write_dao(tmp_path, "FooDao.kt", _dao_text(_q("SELECT SUM(${SpendingInclusionSql.SPENDING_PREDICATE}) FROM expenses")))
    proc = _run_cli(tmp_path, "--fail-on-violation")
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "PASS" in proc.stdout


def test_cli_violations_exit_one_and_do_not_echo_source(tmp_path):
    secret_sql = "SELECT SUM(SECRET_COLUMN_X) FROM expenses"
    _write_dao(
        tmp_path,
        "FooDao.kt",
        _dao_text(
            _q(secret_sql, name="leaky"),
            _marked("BOGUS_REASON_TEXT", "SELECT COUNT(*) FROM expenses", name="bad"),
        ),
    )
    proc = _run_cli(tmp_path, "--fail-on-violation")
    assert proc.returncode == 1
    out = proc.stdout + proc.stderr
    assert "SECRET_COLUMN_X" not in out
    assert "BOGUS_REASON_TEXT" not in out
    assert "FooDao.kt" in out and "leaky" in out and R1 in out and R2 in out


def test_cli_output_is_deterministic(tmp_path):
    _write_dao(tmp_path, "BDao.kt", _dao_text(_q("SELECT SUM(amount) FROM expenses", name="b")))
    _write_dao(tmp_path, "ADao.kt", _dao_text(_q("SELECT COUNT(*) FROM expenses", name="a")))
    first = _run_cli(tmp_path, "--report")
    second = _run_cli(tmp_path, "--report")
    assert first.returncode == second.returncode == 1
    assert first.stdout == second.stdout
    paths = [line.split()[3].split(":")[0] for line in first.stdout.splitlines() if "REPORT:" in line]
    assert paths == sorted(paths)


def test_report_mode_lists_every_classification_without_changing_exit_code(tmp_path):
    _write_dao(
        tmp_path,
        "FooDao.kt",
        _dao_text(
            _q("SELECT SUM(${SpendingInclusionSql.EFFECTIVE_AMOUNT}) FROM expenses", name="conv"),
            _marked("NON_MONEY", "SELECT MIN(date) FROM expenses", name="old"),
            _q("SELECT SUM(amount) FROM expenses", name="raw"),
        ),
    )
    proc = _run_cli(tmp_path, "--report")
    assert proc.returncode == 1
    assert " conv predicate" in proc.stdout
    assert " old exempt:NON_MONEY" in proc.stdout
    assert " raw UNCLASSIFIED" in proc.stdout
    assert "SUMMARY: exempt=1 predicate=1 unclassified=1" in proc.stdout
    assert "REASON: NON_MONEY 1" in proc.stdout


def test_report_mode_off_by_default(tmp_path):
    _write_dao(tmp_path, "FooDao.kt", _dao_text(_marked("ROW_COUNT", "SELECT COUNT(*) FROM expenses")))
    proc = _run_cli(tmp_path)
    assert proc.returncode == 0
    assert "REPORT" not in proc.stdout


# ── registration ──────────────────────────────────────────────────────────────


def _load_module(name: str, relative: str):
    path = _SCRIPTS_DIR / relative
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_registry_entry_agrees_with_script_constants():
    registry = _load_module("guard_registry_spending_predicate_test", "ci/guard_registry.py")
    entry = registry.GUARD_REGISTRY["spending_predicate_usage"]
    assert tuple(entry["execution"]["ruleIds"]) == (R1, R2, R3, R4)
    assert (
        guard.RULE_UNCOVERED,
        guard.RULE_MARKER,
        guard.RULE_DEDUPE,
        guard.RULE_RAW_BUILDER,
    ) == (R1, R2, R3, R4)
    assert tuple(entry["execution"]["requiredInputs"]) == (guard.PIN_RELATIVE_PATH,)
    assert (_REPO_ROOT / guard.PIN_RELATIVE_PATH).is_file()
    assert entry["script"] == "scripts/verify_spending_predicate_usage.py"
    assert entry["tests"] == "scripts/test_verify_spending_predicate_usage.py"
    assert tuple(entry["execution"]["testManifest"]) == (entry["tests"],)
    assert entry["mode"] == "blocking"
    assert entry["baseline"] is None
    assert entry["allowlist"] is None
    assert entry["sourceScope"] == "production-kotlin-filtered"
    assert "--fail-on-violation" in tuple(entry["execution"]["arguments"])


def test_registry_validation_passes_for_the_entry():
    registry = _load_module("guard_registry_spending_predicate_validate", "ci/guard_registry.py")
    errors = registry.validate_registry(str(_REPO_ROOT))
    mine = [e for e in errors if e.startswith("spending_predicate_usage")]
    assert mine == [], mine
    assert _SCRIPT.is_file()
    assert (_SCRIPTS_DIR / "test_verify_spending_predicate_usage.py").is_file()


def test_suite_guard_order_contains_the_guard_exactly_once():
    # Parsed statically: the suite module imports sibling modules at import time.
    tree = ast.parse((_SCRIPTS_DIR / "ci" / "run_static_guard_suite.py").read_text(encoding="utf-8"))
    order = None
    for node in tree.body:
        if isinstance(node, ast.AnnAssign) and getattr(node.target, "id", None) == "SUITE_GUARD_ORDER":
            order = ast.literal_eval(node.value)
        elif isinstance(node, ast.Assign) and any(
            getattr(target, "id", None) == "SUITE_GUARD_ORDER" for target in node.targets
        ):
            order = ast.literal_eval(node.value)
    assert order is not None
    assert tuple(order).count("spending_predicate_usage") == 1
    assert tuple(order).index("spending_predicate_usage") > tuple(order).index(
        "installment_cloud_reachability"
    )


# ── real tree ─────────────────────────────────────────────────────────────────

# The A12.6 exemption set recorded in workflows/active/loan-installments-handoff.md
# ("S4d step 2" and "S4d review fixes"): (file, function, reason).  DEDUPE_* markers sit
# on every RP-11 dedupe-family query (BLOCKING / COLLISION / RESOLVER, plus the two
# identity lookups consumed by blocking/resolution) so Rule 03 protects the family.
EXPECTED_EXEMPT = {
    ("ExpenseDao.kt", "existsByDedupeKey", "DEDUPE_BLOCKING"),
    ("ExpenseDao.kt", "existsByMerchantKeyInRange", "DEDUPE_BLOCKING"),
    ("ExpenseDao.kt", "existsByMerchantInRange", "DEDUPE_BLOCKING"),
    ("ExpenseDao.kt", "existsByMerchantKeyInRangeCurrencyAware", "DEDUPE_BLOCKING"),
    ("ExpenseDao.kt", "existsByMerchantKeyPrefixInRangeCurrencyAware", "DEDUPE_BLOCKING"),
    ("ExpenseDao.kt", "existsByMerchantInRangeCurrencyAware", "DEDUPE_BLOCKING"),
    ("ExpenseDao.kt", "getBlockingCandidateByMerchantKeyInRangeCurrencyAware", "DEDUPE_BLOCKING"),
    ("ExpenseDao.kt", "getBlockingCandidateByMerchantKeyPrefixInRangeCurrencyAware", "DEDUPE_BLOCKING"),
    ("ExpenseDao.kt", "getBlockingCandidateByMerchantInRangeCurrencyAware", "DEDUPE_BLOCKING"),
    ("ExpenseDao.kt", "findIdByDedupeKey", "DEDUPE_BLOCKING"),
    ("ExpenseDao.kt", "findIdByRawNotificationId", "DEDUPE_BLOCKING"),
    ("ExpenseDao.kt", "getDuplicateCandidateByMerchantKeyInRange", "DEDUPE_RESOLVER"),
    ("ExpenseDao.kt", "getDuplicateCandidateByMerchantInRange", "DEDUPE_RESOLVER"),
    ("ExpenseDao.kt", "getDuplicateCandidateByMerchantKeyInRangeCurrencyAware", "DEDUPE_RESOLVER"),
    ("ExpenseDao.kt", "getDuplicateCandidateByMerchantKeyPrefixInRangeCurrencyAware", "DEDUPE_RESOLVER"),
    ("ExpenseDao.kt", "getDuplicateCandidateByMerchantInRangeCurrencyAware", "DEDUPE_RESOLVER"),
    ("ExpenseDao.kt", "getDuplicateCandidatesInRange", "DEDUPE_RESOLVER"),
    ("ExpenseDao.kt", "findDuplicateId", "DEDUPE_RESOLVER"),
    ("ExpenseDao.kt", "observeExpenseMutationClock", "ROW_COUNT"),
    ("ExpenseDao.kt", "countAllExpenses", "ROW_COUNT"),
    ("ExpenseDao.kt", "countExpensesBetween", "ROW_COUNT"),
    ("ExpenseDao.kt", "getTotalCount", "ROW_COUNT"),
    ("ExpenseDao.kt", "getCountForPeriod", "ROW_COUNT"),
    # 1.0.4 B2 S4: countLocated and countUnlocated now enforce SPENDING_PREDICATE directly (-2 ROW_COUNT exemptions, 35 -> 33)
    ("ExpenseDao.kt", "getDepositTotalsBetweenByCurrency", "NON_SPENDING_INCOME"),
    ("ExpenseDao.kt", "getTotalDepositsForPeriod", "NON_SPENDING_INCOME"),
    ("ExpenseDao.kt", "getMonthlyDeposits", "NON_SPENDING_INCOME"),
    ("ExpenseDao.kt", "getTotalDeposits", "NON_SPENDING_INCOME"),
    ("ExpenseDao.kt", "getOldestExpenseDate", "NON_MONEY"),
    ("ExpenseDao.kt", "getMerchantLocationClusters", "NON_MONEY"),
    # FIX-104 AJ: id-only DR-01 probes in GroupExpenseDao (33 -> 35).
    ("GroupExpenseDao.kt", "getNonPayerLinkedExpenseIdsBetween", "NON_MONEY"),
    ("GroupExpenseDao.kt", "getPayerLinkedExpenseIdsBetween", "NON_MONEY"),
    ("ExpenseDao.kt", "searchMerchants", "SUGGESTION"),
    # 1.0.4 S7b: +2 merchant-context readers classified as SUGGESTION-exempt.
    ("ExpenseDao.kt", "getRecentExpensesForMerchant", "SUGGESTION"),
    # 1.0.4 closure: getRecentExpensesWithCategoryForMerchant now applies the predicate (36 -> 35)
    ("WarrantyDao.kt", "getTotalProtectedValue", "ASSET_VALUE"),
    ("WarrantyDao.kt", "getTotalProtectedValueByCurrency", "ASSET_VALUE"),
}

EXPECTED_REASON_COUNTS = {
    "DEDUPE_BLOCKING": 11,
    "DEDUPE_RESOLVER": 7,
    "ROW_COUNT": 5,
    "NON_SPENDING_INCOME": 4,
    "NON_MONEY": 4,
    # 1.0.4 S7b: +2 merchant-context readers in ExpenseDao marked as SUGGESTION-exempt (1 -> 3).
    # 1.0.4 closure: getRecentExpensesWithCategoryForMerchant now applies the predicate (36 -> 35)
    "SUGGESTION": 2,
    "ASSET_VALUE": 2,
}

# Converted aggregates (A12.2 S4a-S4d samples) that must stay classified as predicate users.
EXPECTED_PREDICATE_SAMPLE = {
    "getTotalSpentBetweenByCurrency",
    "getCategoryBreakdown",
    "getAverageDailySpend",
    "getPurchaseCount",
    "getMerchantStats",
    "getTotalBusinessExpensesBetween",
    "getBusinessExpensesBetweenByCurrency",
    "getBusinessExpensesByCategory",
    "getBusinessExpensesByProject",
    "getBusinessCategoryCurrencyTotals",
}

_REPORT_LINE = re.compile(r"^SPENDING PREDICATE REPORT: (\S+):(\d+) (\S+) (\S+)$")


@pytest.fixture(scope="module")
def real_tree_report():
    proc = subprocess.run(
        [sys.executable, str(_SCRIPT), "--root", str(_REPO_ROOT), "--report", "--fail-on-violation"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=300,
    )
    rows = []
    for line in proc.stdout.splitlines():
        match = _REPORT_LINE.match(line)
        if match:
            rows.append((match.group(1).rsplit("/", 1)[-1], match.group(3), match.group(4)))
    return proc, rows


def test_real_tree_is_clean(real_tree_report):
    proc, _rows = real_tree_report
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "PASS" in proc.stdout


def test_real_tree_report_exemption_set_equals_recorded_a12_6_set(real_tree_report):
    _proc, rows = real_tree_report
    exempt = [(f, fn, cls.split(":", 1)[1]) for f, fn, cls in rows if cls.startswith("exempt:")]
    # 1.0.4 S7b: +2 merchant-context readers in ExpenseDao marked as SUGGESTION-exempt (34 -> 36).
    # 1.0.4 closure: getRecentExpensesWithCategoryForMerchant now applies the predicate (36 -> 35)
    # 1.0.4 N-02 neutralisation (other session's B2, item 129): two exemptions removed (35 -> 33).
    # FIX-104 AJ: +2 NON_MONEY id-only probes in GroupExpenseDao (33 -> 35).
    assert len(exempt) == len(set(exempt)) == 35
    assert set(exempt) == EXPECTED_EXEMPT


def test_real_tree_report_reason_counts_and_no_unclassified(real_tree_report):
    proc, rows = real_tree_report
    counts = {}
    for _f, _fn, cls in rows:
        if cls.startswith("exempt:"):
            reason = cls.split(":", 1)[1]
            counts[reason] = counts.get(reason, 0) + 1
    assert counts == EXPECTED_REASON_COUNTS
    assert not any(cls == "UNCLASSIFIED" for _f, _fn, cls in rows)
    # 1.0.4 S7b: +2 merchant-context readers in ExpenseDao marked as SUGGESTION-exempt (34 -> 36).
    # 1.0.4 closure: getRecentExpensesWithCategoryForMerchant now applies the predicate (36 -> 35)
    # 1.0.4 N-02 neutralisation (other session's B2, item 129): two exemptions removed (35 -> 33).
    # FIX-104 AJ: +2 NON_MONEY id-only probes in GroupExpenseDao (33 -> 35).
    assert "SUMMARY: exempt=35 " in proc.stdout and "unclassified=0" in proc.stdout


def test_real_tree_converted_samples_are_classified_as_predicate(real_tree_report):
    _proc, rows = real_tree_report
    predicate = {fn for _f, fn, cls in rows if cls == "predicate"}
    assert EXPECTED_PREDICATE_SAMPLE <= predicate


# ── S6 (I-04-5): non-aggregate SELECT coverage ───────────────────────────────


def test_non_aggregate_select_without_predicate_or_marker_is_rule_01():
    text = _dao_text(
        _q("SELECT * FROM expenses WHERE categoryId = :c AND isNotMine = 0", name="unfilteredSelect")
    )
    result = guard.scan_text(_PATH, text, enforce_non_aggregate_selects=True)
    assert _triples(result) == [(R1, guard.CODE_NON_AGG_UNCOVERED, "unfilteredSelect")]


def test_non_aggregate_select_with_predicate_or_column_or_marker_passes():
    text = _dao_text(
        _q("SELECT * FROM expenses WHERE ${SpendingInclusionSql.SPENDING_PREDICATE}", name="withPred"),
        _q("SELECT id, spendingExclusionReason FROM expenses WHERE spendingExclusionReason IS NOT NULL", name="withCol"),
        _marked("NON_MONEY", "SELECT DISTINCT merchant FROM expenses", name="withMarker"),
    )
    result = guard.scan_text(_PATH, text, enforce_non_aggregate_selects=True)
    assert result.violations == []


# ── Rule 04 (I-04-5): pinned RawQuery builders ───────────────────────────────

_REPO_REL = f"{_JAVA}/{_PKG}/data/repository/FooRepository.kt"
_ALL_REFS = ("EFFECTIVE_AMOUNT_E_SQL", "COMPARABLE_HOME_VALUE_E_SQL")


def _repo_text(
    list_select: str = "SELECT e.*",
    refs=_ALL_REFS,
    builder: str = "buildExpenseDynamicQueryParts",
    extra: str = "",
) -> str:
    body = ""
    if "EFFECTIVE_AMOUNT_E_SQL" in refs:
        body += "        val amount = EFFECTIVE_AMOUNT_E_SQL\n"
    if "COMPARABLE_HOME_VALUE_E_SQL" in refs:
        body += '        val home = "$COMPARABLE_HOME_VALUE_E_SQL IS NULL"\n'
    return (
        "package com.yourname.expensetracker.data.repository\n"
        "\n"
        "import androidx.sqlite.db.SimpleSQLiteQuery\n"
        "\n"
        "class FooRepository(private val fooDao: FooDao) {\n"
        "    suspend fun listAll(): List<Int> {\n"
        "        val (sql, args) = " + builder + "(\n"
        '            selectClause = "' + list_select + '",\n'
        "        )\n"
        "        return fooDao.listDynamic(SimpleSQLiteQuery(sql, args.toTypedArray()))\n"
        "    }\n"
        "\n"
        "    suspend fun countAll(): Int {\n"
        "        val (sql, args) = " + builder + "(\n"
        '            selectClause = "SELECT COUNT(*)",\n'
        "        )\n"
        "        return fooDao.countDynamic(SimpleSQLiteQuery(sql, args.toTypedArray()))\n"
        "    }\n"
        "\n"
        + extra
        + "\n"
        "    private fun " + builder + "(selectClause: String): Pair<String, MutableList<Any>> {\n"
        + body
        + '        return "$selectClause FROM expenses e" to mutableListOf()\n'
        "    }\n"
        "}\n"
    )


def _raw_dao_text(extra: str = "") -> str:
    return _dao_text(
        _q("SELECT SUM(${SpendingInclusionSql.SPENDING_PREDICATE}) FROM expenses", name="total"),
        "    @RawQuery\n    suspend fun listDynamic(query: SupportSQLiteQuery): List<Int>",
        "    @RawQuery\n    suspend fun countDynamic(query: SupportSQLiteQuery): Int",
        extra,
    )


def _pin(function: str, dao_method: str, select: str, **override) -> dict:
    entry = {
        "file": _REPO_REL,
        "enclosingFunction": function,
        "daoMethod": dao_method,
        "daoFile": _DAO_REL,
        "selectClause": select,
        "reason": "fixture reason",
        "owner": "@panospao7",
        "linkedIssue": "I-04-5",
    }
    entry.update(override)
    return entry


def _default_pins() -> list:
    return [
        _pin("listAll", "listDynamic", "SELECT e.*"),
        _pin("countAll", "countDynamic", "SELECT COUNT(*)"),
    ]


def _write_raw_tree(
    root: Path,
    *,
    repo: str = None,
    dao: str = None,
    pins: list = None,
    expected: int = None,
    pin_text: str = None,
) -> None:
    _write_manifest(root)
    _write_dao(root, "FooDao.kt", _raw_dao_text() if dao is None else dao)
    repo_path = root / _REPO_REL
    repo_path.parent.mkdir(parents=True, exist_ok=True)
    repo_path.write_text(_repo_text() if repo is None else repo, encoding="utf-8", newline="\n")
    pin_list = _default_pins() if pins is None else pins
    if pin_text is None:
        pin_text = yaml.safe_dump(
            {
                "version": 1,
                "expectedCount": len(pin_list) if expected is None else expected,
                "builders": pin_list,
            },
            sort_keys=False,
        )
    pin_path = root / guard.PIN_RELATIVE_PATH
    pin_path.parent.mkdir(parents=True, exist_ok=True)
    pin_path.write_text(pin_text, encoding="utf-8", newline="\n")


def _raw_codes(outcome) -> list:
    assert outcome.infra_error is None
    assert all(v.rule_id == R4 for v in outcome.violations)
    return sorted(v.code for v in outcome.violations)


def test_rawq_text_scan_derives_function_dao_method_and_select_literal():
    scan = guard.scan_raw_query_text(_REPO_REL, _repo_text())
    builders = [(s.function, s.dao_method, s.select_clause) for s in scan.sites if s.kind == "BUILDER"]
    assert builders == [
        ("listAll", "listDynamic", "SELECT e.*"),
        ("countAll", "countDynamic", "SELECT COUNT(*)"),
    ]
    dao_scan = guard.scan_raw_query_text(_DAO_REL, _raw_dao_text())
    assert [s.function for s in dao_scan.sites if s.kind == "RAW_QUERY"] == [
        "listDynamic",
        "countDynamic",
    ]


def test_rawq_clean_repo_shaped_tree_passes(tmp_path):
    _write_raw_tree(tmp_path)
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert outcome.infra_error is None
    assert outcome.violations == []
    assert (outcome.builder_sites, outcome.raw_query_sites) == (2, 2)
    proc = _run_cli(tmp_path, "--fail-on-violation")
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "PASS" in proc.stdout and "2 pinned RawQuery builder(s)" in proc.stdout


def test_rawq_extra_simple_sqlite_query_site_fails(tmp_path):
    leak = (
        "    fun leak(dao: FooDao): Int {\n"
        '        return dao.countDynamic(SimpleSQLiteQuery("SELECT SECRET_SQL_X FROM expenses"))\n'
        "    }\n"
    )
    _write_raw_tree(tmp_path, repo=_repo_text(extra=leak))
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert _raw_codes(outcome) == sorted(
        [guard.CODE_RAWQ_UNPINNED_BUILDER, guard.CODE_RAWQ_COUNT]
    )
    assert any(v.function == "leak" and v.path == _REPO_REL for v in outcome.violations)
    proc = _run_cli(tmp_path, "--fail-on-violation")
    assert proc.returncode == 1
    assert R4 in proc.stdout and "leak" in proc.stdout
    assert "SECRET_SQL_X" not in proc.stdout + proc.stderr


def test_rawq_changed_select_clause_literal_fails(tmp_path):
    _write_raw_tree(tmp_path, repo=_repo_text(list_select="SELECT e.secretcol"))
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert _raw_codes(outcome) == sorted(
        [guard.CODE_RAWQ_SELECT_CHANGED, guard.CODE_RAWQ_UNPINNED_SELECT]
    )
    proc = _run_cli(tmp_path, "--fail-on-violation")
    assert proc.returncode == 1 and R4 in proc.stdout
    assert "secretcol" not in proc.stdout + proc.stderr


def test_rawq_new_select_clause_literal_without_new_site_fails(tmp_path):
    other = (
        "    suspend fun other() {\n"
        "        val (sql, args) = buildExpenseDynamicQueryParts(\n"
        '            selectClause = "SELECT e.id",\n'
        "        )\n"
        "    }\n"
    )
    _write_raw_tree(tmp_path, repo=_repo_text(extra=other))
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert _raw_codes(outcome) == [guard.CODE_RAWQ_UNPINNED_SELECT]


def test_rawq_stale_pin_fails(tmp_path):
    pins = _default_pins() + [_pin("gone", "goneDynamic", "SELECT e.*")]
    _write_raw_tree(tmp_path, pins=pins)
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert _raw_codes(outcome) == sorted(
        [guard.CODE_RAWQ_STALE_PIN, guard.CODE_RAWQ_STALE_PIN, guard.CODE_RAWQ_COUNT]
    )


def test_rawq_renamed_builder_function_is_unpinned_and_stale(tmp_path):
    renamed = _repo_text().replace("fun listAll", "fun listEverything")
    _write_raw_tree(tmp_path, repo=renamed)
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert _raw_codes(outcome) == sorted(
        [guard.CODE_RAWQ_UNPINNED_BUILDER, guard.CODE_RAWQ_STALE_PIN]
    )


def test_rawq_count_mismatch_fails(tmp_path):
    _write_raw_tree(tmp_path, expected=3)
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert _raw_codes(outcome) == [guard.CODE_RAWQ_COUNT]
    proc = _run_cli(tmp_path, "--fail-on-violation")
    assert proc.returncode == 1 and guard.CODE_RAWQ_COUNT in proc.stdout


def test_rawq_duplicate_pin_fails(tmp_path):
    pins = _default_pins() + [_default_pins()[0]]
    _write_raw_tree(tmp_path, pins=pins)
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert _raw_codes(outcome) == sorted(
        [guard.CODE_RAWQ_DUPLICATE_PIN, guard.CODE_RAWQ_COUNT]
    )


def test_rawq_unpinned_raw_query_dao_method_fails(tmp_path):
    dao = _raw_dao_text(
        "    @RawQuery\n    suspend fun sneakyDynamic(query: SupportSQLiteQuery): Int"
    )
    _write_raw_tree(tmp_path, dao=dao)
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert _raw_codes(outcome) == [guard.CODE_RAWQ_UNPINNED_DAO]
    assert [v.function for v in outcome.violations] == ["sneakyDynamic"]


def test_rawq_commented_out_and_string_embedded_sites_are_ignored(tmp_path):
    extra = (
        "    // return fooDao.listDynamic(SimpleSQLiteQuery(sql, args.toTypedArray()))\n"
        '    /* SimpleSQLiteQuery("SELECT 2") with selectClause = "SELECT bogus" */\n'
        '    // selectClause = "SELECT commented"\n'
        '    private val doc = "SimpleSQLiteQuery(sql) is built above"\n'
    )
    dao = _raw_dao_text(
        "    // @RawQuery\n    /* @RawQuery suspend fun hidden(): Int */\n    fun notRaw(): Int = 0"
    )
    _write_raw_tree(tmp_path, repo=_repo_text(extra=extra), dao=dao)
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert outcome.infra_error is None
    assert outcome.violations == []
    assert (outcome.builder_sites, outcome.raw_query_sites) == (2, 2)
    proc = _run_cli(tmp_path, "--fail-on-violation")
    assert proc.returncode == 0, proc.stdout + proc.stderr


def test_rawq_builder_body_must_keep_amount_semantics_owners(tmp_path):
    for refs in (("EFFECTIVE_AMOUNT_E_SQL",), ("COMPARABLE_HOME_VALUE_E_SQL",), ()):
        _write_raw_tree(tmp_path, repo=_repo_text(refs=refs))
        outcome = guard.scan_raw_query_builders(tmp_path)
        assert _raw_codes(outcome) == [guard.CODE_RAWQ_BUILDER_REFERENCE], refs


def test_rawq_builder_function_missing_fails(tmp_path):
    _write_raw_tree(tmp_path, repo=_repo_text(builder="buildSomethingElse"))
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert _raw_codes(outcome) == [guard.CODE_RAWQ_BUILDER_MISSING]


def test_rawq_missing_pin_file_is_infra(tmp_path):
    _write_raw_tree(tmp_path)
    (tmp_path / guard.PIN_RELATIVE_PATH).unlink()
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert outcome.infra_error == guard.E_PIN_MISSING
    proc = subprocess.run(
        [sys.executable, str(_SCRIPT), "--root", str(tmp_path)],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=120,
    )
    assert proc.returncode == 2
    assert guard.E_PIN_MISSING in proc.stderr and "PASS" not in proc.stdout


@pytest.mark.parametrize(
    "pin_text",
    [
        "version: 1\nexpectedCount: 1\nbuilders:\n  - file: x.kt\n",
        "version: 2\nexpectedCount: 0\nbuilders: []\n",
        "version: 1\nexpectedCount: 0\nbuilders: []\nextra: true\n",
        "version: 1\nexpectedCount: -1\nbuilders: []\n",
        "version: 1\nexpectedCount: 0\nbuilders: not-a-list\n",
        "not: [valid",
    ],
)
def test_rawq_malformed_pin_file_is_infra(tmp_path, pin_text):
    _write_raw_tree(tmp_path, pin_text=pin_text)
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert outcome.infra_error == guard.E_PIN_INVALID
    proc = _run_cli(tmp_path, "--fail-on-violation")
    assert proc.returncode == 2
    assert guard.E_PIN_INVALID in proc.stderr and "PASS" not in proc.stdout


def test_rawq_pin_entry_with_extra_field_is_infra(tmp_path):
    bad = _default_pins()
    bad[0]["wildcard"] = "*"
    _write_raw_tree(tmp_path, pins=bad)
    assert guard.scan_raw_query_builders(tmp_path).infra_error == guard.E_PIN_INVALID


def test_rawq_unparseable_builder_file_is_infra(tmp_path):
    _write_raw_tree(tmp_path, repo=_repo_text(extra='    val s = "never closed\n'))
    outcome = guard.scan_raw_query_builders(tmp_path)
    assert outcome.infra_error == guard.E_UNPARSEABLE


def test_rawq_real_pin_file_pins_the_four_dao_methods():
    pin_set, error = guard.load_raw_query_pins(_REPO_ROOT / guard.PIN_RELATIVE_PATH)
    assert error is None
    assert pin_set.expected_count == len(pin_set.pins) == 4
    assert {p.dao_method for p in pin_set.pins} == {
        "getExpensesDynamic",
        "countExpensesWithoutHomeValueDynamic",
        "getAssistantExpensesDynamic",
        "getAssistantExpenseCountDynamic",
    }
    assert {p.select_clause for p in pin_set.pins} == {"SELECT e.*", "SELECT COUNT(*)"}
    raw = yaml.safe_load((_REPO_ROOT / guard.PIN_RELATIVE_PATH).read_text(encoding="utf-8"))
    assert all(b["owner"] == "@panospao7" and b["linkedIssue"] == "I-04-5" for b in raw["builders"])


def test_rawq_real_tree_reports_four_pinned_builders(real_tree_report):
    proc, _rows = real_tree_report
    assert proc.returncode == 0, proc.stdout + proc.stderr
    assert "RAW_QUERY: builders=4 daoMethods=4" in proc.stdout

