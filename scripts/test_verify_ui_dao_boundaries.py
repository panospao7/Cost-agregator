"""
test_verify_ui_dao_boundaries.py
Acceptance tests for the static UI/ViewModel DAO boundary guard (G-UI-DAO-01).

3 test cases:
  1. ViewModel with @Inject Dao — FAILS
  2. Repository-based ViewModel — PASSES
  3. Read-only query in UI — FLAGGED

Run with: python -m pytest scripts/test_verify_ui_dao_boundaries.py -v
"""
import os
import sys
import tempfile
import pytest

# Import the module under test directly
sys.path.insert(0, os.path.dirname(__file__))
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard", os.path.join(os.path.dirname(__file__), "verify_ui_dao_boundaries.py")
)
_mod = importlib.util.module_from_spec(_spec)
import unittest.mock as _mock
with _mock.patch("builtins.__import__", side_effect=__import__):
    _spec.loader.exec_module(_mod)

scan_file = _mod.scan_file
violation = _mod.violation
RULE_ID = _mod.RULE_ID


def _write_kt(path, filename, content):
    """Write a .kt file in the given directory."""
    filepath = os.path.join(str(path), filename)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content)
    return filepath


def _yaml(tmp_path, content):
    """Write a YAML allowlist file."""
    p = os.path.join(str(tmp_path), "allowlist.yml")
    with open(p, "w", encoding="utf-8") as f:
        f.write(content)
    return p


# ── Test 1: ViewModel with @Inject Dao FAILS ─────────────────────

def test_viewmodel_with_inject_dao_fails(tmp_path):
    """A ViewModel that injects a Dao via @Inject constructor must be flagged."""
    src = os.path.join(str(tmp_path), "ui", "screens", "test")
    os.makedirs(src, exist_ok=True)
    _write_kt(src, "BadViewModel.kt", """\
package com.example.ui.screens.test

import androidx.lifecycle.ViewModel
import com.example.data.database.dao.ExpenseDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BadViewModel @Inject constructor(
    private val expenseDao: ExpenseDao
) : ViewModel() {
    fun save() {
        expenseDao.insert(expense)
    }
}
""".strip())

    allowlist_path = _yaml(tmp_path, "# empty allowlist\n")
    allowlist = _mod.load_allowlist(allowlist_path)

    rel_path = f"ui/screens/test/BadViewModel.kt"
    violations = scan_file(
        os.path.join(str(tmp_path), "ui", "screens", "test", "BadViewModel.kt"),
        rel_path,
        allowlist
    )

    assert len(violations) > 0, (
        f"Expected violations for ViewModel with @Inject Dao, got {len(violations)}"
    )
    # Should flag the @Inject constructor
    has_constructor_flag = any("@Inject constructor" in v for v in violations)
    assert has_constructor_flag, (
        f"Expected violation about @Inject constructor Dao injection, got: {violations}"
    )


# ── Test 2: Repository-based ViewModel PASSES ────────────────────

def test_repository_based_viewmodel_passes(tmp_path):
    """A ViewModel using only repository abstractions must not be flagged."""
    src = os.path.join(str(tmp_path), "ui", "screens", "test")
    os.makedirs(src, exist_ok=True)
    _write_kt(src, "GoodViewModel.kt", """\
package com.example.ui.screens.test

import androidx.lifecycle.ViewModel
import com.example.data.repository.ExpenseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class GoodViewModel @Inject constructor(
    private val expenseRepository: ExpenseRepository
) : ViewModel() {
    fun save() {
        expenseRepository.saveExpense(expense)
    }
}
""".strip())

    allowlist_path = _yaml(tmp_path, "# empty allowlist\n")
    allowlist = _mod.load_allowlist(allowlist_path)

    rel_path = f"ui/screens/test/GoodViewModel.kt"
    violations = scan_file(
        os.path.join(str(tmp_path), "ui", "screens", "test", "GoodViewModel.kt"),
        rel_path,
        allowlist
    )

    assert len(violations) == 0, (
        f"Expected no violations for repository-based ViewModel, got: {violations}"
    )


# ── Test 3: Read-only query in UI is flagged ─────────────────────

def test_readonly_query_in_ui_is_flagged(tmp_path):
    """UI files with Dao read operations should be flagged (Dao import detected)."""
    src = os.path.join(str(tmp_path), "ui", "screens", "test")
    os.makedirs(src, exist_ok=True)
    _write_kt(src, "QueryViewModel.kt", """\
package com.example.ui.screens.test

import androidx.lifecycle.ViewModel
import com.example.data.database.dao.ExpenseDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class QueryViewModel @Inject constructor(
    private val expenseDao: ExpenseDao
) : ViewModel() {
    fun getExpenses() = expenseDao.getAll()
}
""".strip())

    allowlist_path = _yaml(tmp_path, "# empty allowlist\n")
    allowlist = _mod.load_allowlist(allowlist_path)

    rel_path = f"ui/screens/test/QueryViewModel.kt"
    violations = scan_file(
        os.path.join(str(tmp_path), "ui", "screens", "test", "QueryViewModel.kt"),
        rel_path,
        allowlist
    )

    assert len(violations) > 0, (
        f"Expected violations for ViewModel importing Dao (even read-only), got: {violations}"
    )
    has_import_flag = any("imports Dao" in v for v in violations)
    has_inject_flag = any("@Inject constructor" in v for v in violations)
    assert has_import_flag or has_inject_flag, (
        f"Expected violation about Dao import or @Inject Dao, got: {violations}"
    )


# ── Allowlist support ─────────────────────────────────────────────

def test_allowlisted_file_passes(tmp_path):
    """An allowlisted ViewModel with Dao injection should pass."""
    src = os.path.join(str(tmp_path), "ui", "screens", "bank")
    os.makedirs(src, exist_ok=True)
    _write_kt(src, "BankConnectionsViewModel.kt", """\
package com.example.ui.screens.bank

import androidx.lifecycle.ViewModel
import com.example.data.database.dao.BankConnectionDao
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class BankConnectionsViewModel @Inject constructor(
    private val bankConnectionDao: BankConnectionDao
) : ViewModel() {
    fun disconnect(id: Long) {
        bankConnectionDao.disconnect(id)
    }
}
""".strip())

    allowlist_content = f"""\
- rule: {RULE_ID}
  path: app/src/main/java/com/example/ui/screens/bank/BankConnectionsViewModel.kt
  reason: "Legacy bank connection VM"
  owner: "@tester"
  expires: "permanent"
"""
    allowlist_path = _yaml(tmp_path, allowlist_content)
    allowlist = _mod.load_allowlist(allowlist_path)

    rel_path = "app/src/main/java/com/example/ui/screens/bank/BankConnectionsViewModel.kt"
    violations = scan_file(
        os.path.join(str(tmp_path), "ui", "screens", "bank", "BankConnectionsViewModel.kt"),
        rel_path,
        allowlist
    )

    assert len(violations) == 0, (
        f"Expected no violations for allowlisted ViewModel, got: {violations}"
    )

# ── PR-GR-10B source-scope contract ─────────────────────────────────────────
# The guard's scan scope is the checked-in production source-root manifest
# (config/guards/production_source_roots.yml), resolved via
# scripts/guardrails/production_source_scope.py — fail closed with exit 2
# when it is missing/malformed/undeclared; UI/ViewModel relevance is a
# semantic filter applied AFTER enumerating every declared production file.

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


_UI_VIOLATION_KT = (
    "package com.example.ui\n"
    "\n"
    "class SampleViewModel @Inject constructor(\n"
    "    private val sampleDao: SampleDao\n"
    ")\n"
)


def _scope_run_main(root, monkeypatch, capsys, extra=()):
    monkeypatch.setattr(
        sys, "argv",
        ["verify_ui_dao_boundaries.py", "--root", str(root)] + list(extra),
    )
    with pytest.raises(SystemExit) as excinfo:
        _mod.main()
    return excinfo.value.code, capsys.readouterr()


def test_manifest_declared_root_finding_matches_hardcoded_era(tmp_path, monkeypatch, capsys):
    """With the single-root manifest declaring app/src/main/java, findings
    are identical to the pre-GR-10B hard-coded-root era."""
    _write_scope_manifest(tmp_path)
    target = (
        tmp_path / "app" / "src" / "main" / "java" / "com" / "example"
        / "SampleViewModel.kt"
    )
    target.parent.mkdir(parents=True)
    target.write_text(_UI_VIOLATION_KT, encoding="utf-8")

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys, ["--fail-on-violation"])

    assert code == 1
    assert "SampleViewModel.kt" in out.out
    assert "injects Dao directly" in out.out


def test_missing_manifest_fails_closed(tmp_path, monkeypatch, capsys):
    """No checked-in manifest -> exit 2 (no conventional-root fallback)."""
    target = (
        tmp_path / "app" / "src" / "main" / "java" / "com" / "example"
        / "SampleViewModel.kt"
    )
    target.parent.mkdir(parents=True)
    target.write_text(_UI_VIOLATION_KT, encoding="utf-8")

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 2
    assert "production source scope unresolved" in out.err


def test_undeclared_root_file_is_never_scanned(tmp_path, monkeypatch, capsys):
    """Kotlin files outside the declared production roots are invisible."""
    _write_scope_manifest(tmp_path)
    undeclared = (
        tmp_path / "other" / "src" / "main" / "java" / "com" / "example"
        / "SampleViewModel.kt"
    )
    undeclared.parent.mkdir(parents=True)
    undeclared.write_text(_UI_VIOLATION_KT, encoding="utf-8")

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 0
    assert "SampleViewModel" not in out.out
