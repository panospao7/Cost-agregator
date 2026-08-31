#!/usr/bin/env python3
"""
test_verify_raw_money_aggregates.py

PR-GR-10A Slice 3 — EXTRACTED_AND_REGISTERED disposition tests for the
``raw_money_aggregates`` guard (scripts/verify_raw_money_aggregates.py), the
registered replacement of the retired Gradle KTS inline scanner
``checkRawMoneyAggregates``.

Proven here, per rule, on temporary fixture trees only:

  1. every stable rule ID (G-MONEY-RAW-01..07) detects its positive fixture;
  2. negative fixtures (normalized aggregates, allowlisted files, test
     trees, fromBuckets blocks, comment/import lines) produce no findings;
  3. the fromBuckets brace-depth state machine matches the retired KTS
     scanner's behaviour (triggering line skipped, block skipped until the
     braces rebalance);
  4. exit codes follow the universal mapping (0 pass / 1 violation /
     2 infrastructure) and the CLI adapter preserves them;
  5. the rule inventory is exactly the transcribed KTS rule set — the
     extraction neither widened nor narrowed any pattern.

Run:
    python -m pytest scripts/test_verify_raw_money_aggregates.py -v
"""

import subprocess
import sys
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parent / "verify_raw_money_aggregates.py"
sys.path.insert(0, str(Path(__file__).resolve().parent))

import verify_raw_money_aggregates as guard  # noqa: E402


def _source_root(tmp_path: Path) -> Path:
    root = tmp_path / "app" / "src" / "main" / "java"
    root.mkdir(parents=True, exist_ok=True)
    return root


def _write(root: Path, relative: str, content: str) -> Path:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")
    return path


def _run_cli(root: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(_SCRIPT), "--root", str(root), "--fail-on-violation"],
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=60,
    )


def _violations_by_rule(root: Path):
    violations, infra = guard.scan_source_root(root)
    assert infra is None, infra
    by_rule = {}
    for violation in violations:
        by_rule.setdefault(violation.rule_id, []).append(violation)
    return by_rule


# ── Rule inventory is exactly the transcribed KTS rule set ──────────────────


def test_rule_inventory_is_the_transcribed_kts_set():
    """The extraction owns exactly G-MONEY-RAW-01..07 — no more, no fewer."""
    assert [rule.rule_id for rule in guard.RULES] == [
        "G-MONEY-RAW-01",
        "G-MONEY-RAW-02",
        "G-MONEY-RAW-03",
        "G-MONEY-RAW-04",
        "G-MONEY-RAW-05",
        "G-MONEY-RAW-06",
        "G-MONEY-RAW-07",
    ]


def test_rule_patterns_are_transcribed_from_the_kts_scanner():
    """Pattern bodies match the retired KTS regexes byte-for-byte."""
    expected = {
        "G-MONEY-RAW-01": r"\.sumOf\s*\{\s*it\.amount\s*\}",
        "G-MONEY-RAW-02": r"\.sumOf\s*\{\s*it\.effectiveAmount\s*\}",
        "G-MONEY-RAW-03": r"\.sumOf\s*\{\s*it\.normalizedAmount\s*\}",
        "G-MONEY-RAW-04": r"\.sumOf\s*\{\s*it\.\w*[Pp]rice\s*\}",
        "G-MONEY-RAW-05": r"\.sumBy\s*\{\s*it\.amount\s*\.(?:toInt|roundToInt)\s*\(\)\s*\}",
        "G-MONEY-RAW-06": r"total\s*:\s*Double",
        "G-MONEY-RAW-07": r"var\s+total\s*=\s*0\.0\s*;?\s*//?\s*.*sum",
    }
    for rule in guard.RULES:
        assert rule.pattern.pattern == expected[rule.rule_id], rule.rule_id


def test_allowlist_is_the_transcribed_kts_file_set():
    assert guard.ALLOWLIST_FILES == frozenset({
        "MoneyAggregateBuilder.kt",
        "MoneyAggregate.kt",
        "ConvertedMoney.kt",
        "CurrencyConverter.kt",
        "MultiCurrencyRepository.kt",
        "ExpenseDao.kt",
        "BudgetDao.kt",
    })


# ── Positive fixtures: every rule fires ─────────────────────────────────────


@pytest.mark.parametrize(
    "rule_id,snippet",
    [
        ("G-MONEY-RAW-01", "val total = items.sumOf { it.amount }"),
        ("G-MONEY-RAW-02", "val total = items.sumOf { it.effectiveAmount }"),
        ("G-MONEY-RAW-03", "val total = items.sumOf { it.normalizedAmount }"),
        ("G-MONEY-RAW-04", "val total = items.sumOf { it.salePrice }"),
        ("G-MONEY-RAW-04b", "val total = items.sumOf { it.unitPrice }"),
        ("G-MONEY-RAW-05", "val cents = items.sumBy { it.amount.toInt() }"),
        ("G-MONEY-RAW-05b", "val cents = items.sumBy { it.amount.roundToInt() }"),
        ("G-MONEY-RAW-06", "data class Row(val total: Double)"),
        ("G-MONEY-RAW-07", "var total = 0.0 // sum of buckets"),
    ],
)
def test_positive_fixture_fires_exactly_its_rule(tmp_path, rule_id, snippet):
    root = _source_root(tmp_path)
    _write(root, "Probe.kt", "package probe\n" + snippet + "\n")
    expected_rule = rule_id[:-1] if rule_id.endswith("b") else rule_id
    by_rule = _violations_by_rule(root)
    assert expected_rule in by_rule, (expected_rule, by_rule)
    assert by_rule[expected_rule][0].path == "Probe.kt"
    # The fixture fires ONLY the expected rule family.
    others = {rid for rid in by_rule if rid != expected_rule}
    assert not others, others


def test_positive_violation_reports_line_number(tmp_path):
    root = _source_root(tmp_path)
    _write(
        root,
        "Lines.kt",
        "package probe\n\n"
        "fun f(items: List<Int>): Double {\n"
        "    return items.sumOf { it.amount }\n"
        "}\n",
    )
    by_rule = _violations_by_rule(root)
    assert by_rule["G-MONEY-RAW-01"][0].line == 4


# ── Negative fixtures: no findings ──────────────────────────────────────────


def test_normalized_aggregates_do_not_fire(tmp_path):
    root = _source_root(tmp_path)
    _write(
        root,
        "Clean.kt",
        "package probe\n"
        "val ok1 = items.sumOf { it.normalizedEffectiveAmount }\n"
        "val ok2 = items.sumOf { it.expense.signedEffectiveAmount() }\n"
        "val ok3 = items.sumOf { it.count }\n"
        "val ok4 = items.sumOf { expense -> expense.effectiveAmount }\n"
        "val ok5 = items.sumBy { it.rowsPurged }\n"
        "data class Row(val count: Int)\n",
    )
    assert _violations_by_rule(root) == {}


def test_allowlisted_files_are_skipped(tmp_path):
    root = _source_root(tmp_path)
    for name in sorted(guard.ALLOWLIST_FILES):
        _write(root, name, "val total = items.sumOf { it.amount }\n")
    assert _violations_by_rule(root) == {}


def test_test_trees_are_skipped(tmp_path):
    root = _source_root(tmp_path)
    _write(root, "testing/Probe.kt", "val total = items.sumOf { it.amount }\n")
    _write(root, "androidTest/Probe.kt", "val total: Double\n")
    assert _violations_by_rule(root) == {}


def test_comment_and_import_lines_are_skipped(tmp_path):
    root = _source_root(tmp_path)
    _write(
        root,
        "Comments.kt",
        "package probe\n"
        "import com.example.sumOf\n"
        "// val total = items.sumOf { it.amount }\n"
        "* val total: Double\n"
        "/* val total: Double */\n",
    )
    assert _violations_by_rule(root) == {}


def test_from_buckets_block_is_skipped(tmp_path):
    root = _source_root(tmp_path)
    _write(
        root,
        "Buckets.kt",
        "package probe\n"
        "val grouped = items.fromBuckets { bucket ->\n"
        "    bucket.sumOf { it.amount }\n"
        "    bucket.total\n"
        "}\n"
        "val after = items.sumOf { it.amount }\n",
    )
    by_rule = _violations_by_rule(root)
    # The lines inside the fromBuckets block are skipped; the line after the
    # block (braces rebalanced) is scanned again.
    assert [v.line for v in by_rule["G-MONEY-RAW-01"]] == [6]


def test_from_buckets_triggering_line_is_skipped(tmp_path):
    root = _source_root(tmp_path)
    _write(
        root,
        "Trigger.kt",
        "package probe\n"
        "val x = items.fromBuckets { it.amount }\n"
        "val y = 1\n",
    )
    assert _violations_by_rule(root) == {}


# ── Exit-code contract ──────────────────────────────────────────────────────


def test_cli_exit_zero_on_clean_tree(tmp_path):
    root = _source_root(tmp_path)
    _write(root, "Clean.kt", "package probe\nval ok = items.sumOf { it.count }\n")
    result = _run_cli(root)
    assert result.returncode == 0, result.stdout + result.stderr
    assert "PASS" in result.stdout


def test_cli_exit_one_on_violation(tmp_path):
    root = _source_root(tmp_path)
    _write(root, "Probe.kt", "val total = items.sumOf { it.amount }\n")
    result = _run_cli(root)
    assert result.returncode == 1
    assert "G-MONEY-RAW-01" in result.stdout
    assert "FAIL" in result.stdout


def test_cli_exit_two_on_missing_source_root(tmp_path):
    result = _run_cli(tmp_path)  # no app/src/main/java below tmp_path
    assert result.returncode == 2
    assert "E_RAW_MONEY_SOURCE_ROOT_MISSING" in result.stderr
    # Bounded diagnostic: no filesystem path in the stderr payload.
    assert str(tmp_path) not in result.stderr


def test_cli_accepts_canonical_fail_on_violation_flag(tmp_path):
    root = _source_root(tmp_path)
    _write(root, "Clean.kt", "package probe\nval ok = 1\n")
    result = subprocess.run(
        [sys.executable, str(_SCRIPT), "--root", str(root), "--fail-on-violation"],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=60,
    )
    assert result.returncode == 0, result.stdout + result.stderr
