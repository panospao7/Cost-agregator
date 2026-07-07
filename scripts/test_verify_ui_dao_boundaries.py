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
