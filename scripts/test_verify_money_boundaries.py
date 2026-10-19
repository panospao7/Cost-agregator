"""
test_verify_money_boundaries.py
PR-GR-10B Slice 2 scope-contract tests for the static money-boundary guard
(G-MONEY-10..21).

Contract under test:
  1. With the checked-in manifest declaring app/src/main/java, findings are
     identical to the pre-GR-10B hard-coded-root era.
  2. A missing manifest fails closed with exit 2 (no conventional-root
     fallback).
  3. Kotlin files outside the declared production roots are never scanned.
  4. The checked-in money guard baseline (config/baselines/money.json) holds
     no allowlisted fingerprints (absolute-zero pin).

Run with: python -m pytest scripts/test_verify_money_boundaries.py -v
"""
import json
import os
import sys
import pytest

# Import the module under test directly
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard_money",
    os.path.join(os.path.dirname(__file__), "verify_money_boundaries.py"),
)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)


# ── Helpers ─────────────────────────────────────────────────────────────────

def _write_scope_manifest(root, root_rel="app/src/main/java"):
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


def _write_declared(root, rel, content):
    target = root / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


_MONEY_VIOLATION_KT = (
    "package com.example\n"
    "\n"
    "class CurrencyProbe {\n"
    "    val sentinel = CurrencyCode(\"XXX\")\n"
    "}\n"
)

_CLEAN_KT = "package com.example\nclass Clean { fun f() = 1 }\n"


def _scope_run_main(root, monkeypatch, capsys, extra=()):
    monkeypatch.setattr(
        sys, "argv",
        ["verify_money_boundaries.py", "--root", str(root)] + list(extra),
    )
    with pytest.raises(SystemExit) as excinfo:
        _mod.main()
    return excinfo.value.code, capsys.readouterr()


# ── Test 1: declared root -> finding identical to hardcoded era ─────────────

def test_manifest_declared_root_finding_matches_hardcoded_era(tmp_path, monkeypatch, capsys):
    """With the single-root manifest declaring app/src/main/java, a fake
    unavailable-currency sentinel under the declared root is flagged
    (G-MONEY-11) exactly as in the pre-GR-10B hard-coded-root era."""
    _write_scope_manifest(tmp_path)
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/CurrencyProbe.kt",
        _MONEY_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 1
    assert "[G-MONEY-11]" in out.out


# ── Test 2: missing manifest fails closed (exit 2) ──────────────────────────

def test_missing_manifest_fails_closed(tmp_path, monkeypatch, capsys):
    """No checked-in manifest -> exit 2 (no conventional-root fallback)."""
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/CurrencyProbe.kt",
        _MONEY_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 2
    assert "production source scope unresolved" in out.err
    assert "DB_SOURCE_ROOT_UNDECLARED" in out.err


# ── Test 3: undeclared-root file is never scanned ───────────────────────────

def test_undeclared_root_file_is_never_scanned(tmp_path, monkeypatch, capsys):
    """Kotlin files outside the declared production roots are invisible: a
    money violation under an undeclared tree produces no finding."""
    _write_scope_manifest(tmp_path)
    _write_declared(tmp_path, "app/src/main/java/com/example/Clean.kt", _CLEAN_KT)
    undeclared = (
        tmp_path / "other" / "src" / "main" / "java" / "com" / "example"
        / "CurrencyProbe.kt"
    )
    undeclared.parent.mkdir(parents=True)
    undeclared.write_text(_MONEY_VIOLATION_KT, encoding="utf-8")

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 0
    assert "CurrencyProbe" not in out.out
    assert "PASS" in out.out


# ── Fixture parity with the retired Kotlin MoneyBoundaryGuardTest ───────────
# Source bodies are copied verbatim from the Kotlin fixtures; relative paths
# are preserved because financial_only rules (and G-MONEY-21's dashboard
# use-case scope) depend on the path.

_KT_BASE = "app/src/main/java/com/yourname/expensetracker/domain/"

_VIOLATION_FIXTURES = [
    pytest.param(
        "forecasting/Bad.kt",
        "\n".join([
            "package com.yourname.expensetracker.domain.forecasting",
            "import com.yourname.expensetracker.domain.core.money.CurrencyCode",
            'val bad = CurrencyCode("XXX")',
        ]),
        "G-MONEY-11",
        id="currency-code-xxx-sentinel",
    ),
    pytest.param(
        "forecasting/Bad.kt",
        "\n".join([
            "package com.yourname.expensetracker.domain.forecasting",
            "import com.yourname.expensetracker.domain.core.money.CurrencyCode",
            'val bad = CurrencyCode("")',
        ]),
        "G-MONEY-11",
        id="currency-code-blank",
    ),
    pytest.param(
        "forecasting/Bad.kt",
        "\n".join([
            "package com.yourname.expensetracker.domain.forecasting",
            'val displayCurrency = "N/A"',
        ]),
        "G-MONEY-11",
        id="display-currency-na-sentinel",
    ),
    pytest.param(
        "forecasting/Bad.kt",
        "\n".join([
            "package com.yourname.expensetracker.domain.forecasting",
            "import com.yourname.expensetracker.domain.model.ExpenseSnapshot",
            "fun bad(snapshot: ExpenseSnapshot) {",
            "    val snap = ExpenseSnapshot(effectiveAmount = snapshot.effectiveAmount)",
            "}",
        ]),
        "G-MONEY-10",
        id="raw-expense-snapshot-in-synthesis",
    ),
    pytest.param(
        "usecase/dashboard/ComputeDashboardWidgetsUseCase.kt",
        "\n".join([
            "package com.yourname.expensetracker.domain.usecase.dashboard",
            "fun bad() {",
            '    when (val result = DashboardNormalizedInputResult.Unavailable("", 0, 0)) {',
            "        is DashboardNormalizedInputResult.Unavailable -> emptyList()",
            "    }",
            "}",
        ]),
        "G-MONEY-21",
        id="unavailable-emptylist-fallback-in-dashboard",
    ),
    pytest.param(
        "forecasting/Bad.kt",
        "\n".join([
            "package com.yourname.expensetracker.domain.forecasting",
            "suspend fun bad(converter: com.yourname.expensetracker.domain.currency.CurrencyConverter) {",
            '    converter.convertMultiple(listOf(1.0 to "USD"), "EUR")',
            "}",
        ]),
        "G-MONEY-17",
        id="convert-multiple-in-aggregate-code",
    ),
]

_CLEAN_FIXTURES = [
    pytest.param(
        "core/money/Good.kt",
        "\n".join([
            "package com.yourname.expensetracker.domain.core.money",
            'val result = MoneyAggregateResult.Unavailable("no currency", RateBasis.TRANSACTION_DATE)',
        ]),
        id="money-aggregate-result-unavailable",
    ),
    pytest.param(
        "forecasting/Good.kt",
        "\n".join([
            "package com.yourname.expensetracker.domain.forecasting",
            "val input = NormalizedForecastInput(",
            '    homeCurrency = CurrencyCode("EUR"),',
            "    normalizedExpenses = emptyList(),",
            "    pastSumDaily = emptyList(),",
            "    recurringPatterns = emptyList(),",
            "    plannedExpenses = emptyList(),",
            "    savingsGoals = emptyList(),",
            "    budgetStatuses = emptyList(),",
            '    spendingPace = SpendingPace(0.0, 0, 0, 0.0, null, null, 100f, com.yourname.expensetracker.domain.analytics.PaceStatus.NO_BASELINE, "EUR")',
            ")",
        ]),
        id="normalized-forecast-input",
    ),
    pytest.param(
        "forecasting/Good.kt",
        "\n".join([
            "package com.yourname.expensetracker.domain.forecasting",
            "import com.yourname.expensetracker.domain.core.money.StaleRatePolicy",
            "import com.yourname.expensetracker.domain.core.money.RateBasis",
            "val policy = StaleRatePolicy.forBasis(RateBasis.LATEST_AVAILABLE)",
        ]),
        id="stale-rate-policy-for-basis",
    ),
]


@pytest.mark.parametrize("rel_path, source, rule_id", _VIOLATION_FIXTURES)
def test_violation_fixture_fires_rule(
    tmp_path, monkeypatch, capsys, rel_path, source, rule_id
):
    """Each known-regression fixture exits 1 and reports its rule id."""
    _write_scope_manifest(tmp_path)
    _write_declared(tmp_path, _KT_BASE + rel_path, source)

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 1, out.out + out.err
    assert f"[{rule_id}]" in out.out


@pytest.mark.parametrize("rel_path, source", _CLEAN_FIXTURES)
def test_clean_fixture_passes(tmp_path, monkeypatch, capsys, rel_path, source):
    """Sanctioned money constructs pass the guard with exit 0."""
    _write_scope_manifest(tmp_path)
    _write_declared(tmp_path, _KT_BASE + rel_path, source)

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 0, out.out + out.err
    assert "PASS" in out.out


# ── Baseline absolute-zero pin ──────────────────────────────────────────────

def test_money_baseline_has_no_allowlisted_findings():
    """Replaces the retired MoneyBoundaryGuardTest live-scan absolute pin:
    the money guard baseline must stay empty."""
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    baseline_path = os.path.join(repo_root, "config", "baselines", "money.json")
    with open(baseline_path, encoding="utf-8") as fh:
        data = json.load(fh)

    assert data["guard"] == "money"
    assert "fingerprints" in data
    assert isinstance(data["fingerprints"], list)
    assert data["fingerprints"] == [], (
        "Money findings must be fixed, not baselined: the deleted "
        "MoneyBoundaryGuardTest live scan required zero findings."
    )
