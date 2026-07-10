"""
test_verify_db_access_boundaries.py
PR 10 acceptance tests for the static DAO mutation guard.

Run with: python -m pytest scripts/test_verify_db_access_boundaries.py -v
"""
import os
import sys

# Import the module under test directly
sys.path.insert(0, os.path.dirname(__file__))
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard", os.path.join(os.path.dirname(__file__), "verify_db_access_boundaries.py")
)
_mod = importlib.util.module_from_spec(_spec)
# Execute without calling main()
import unittest.mock as _mock
with _mock.patch("builtins.__import__", side_effect=__import__):
    _spec.loader.exec_module(_mod)

scan = _mod.scan
load_allowlist = _mod.load_allowlist


def _write_kt(tmp_path, filename, content):
    f = tmp_path / filename
    f.write_text(content, encoding="utf-8")
    return tmp_path


def _allowlist(tmp_path, yaml_content):
    p = tmp_path / "allowlist.yml"
    p.write_text(yaml_content, encoding="utf-8")
    return str(p)


# ── guard_fails_on_direct_expenseDao_update_in_viewmodel ─────────────────────

def test_fail_on_violation_exits_nonzero_when_violations_exist(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "SomeViewModel.kt",
              "class SomeViewModel { fun save() { expenseDao.insert(expense) } }")
    approved = load_allowlist(_allowlist(tmp_path, "allowed_writers: []\n"))
    violations, files_scanned = scan(str(src), approved)
    assert files_scanned > 0, "Should have scanned at least one file"
    assert len(violations) > 0, "Expected at least one violation"


# ── guard_allows_transaction_lifecycle_expense_write ─────────────────────────

def test_allowlisted_class_does_not_trigger_violation(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "TransactionLifecycleCoordinator.kt",
              "class TransactionLifecycleCoordinator { fun save() { expenseDao.insert(e) } }")
    approved = load_allowlist(_allowlist(tmp_path,
        "allowed_writers:\n  - class: TransactionLifecycleCoordinator\n    requires_write_barrier: false\n    daos: [expenseDao]\n    reason: canonical\n"
    ))
    violations, files_scanned = scan(str(src), approved)
    assert files_scanned > 0
    assert len(violations) == 0, f"Allowlisted class should not be flagged: {violations}"


# ── guard_fails_on_worker_direct_receipt_update ───────────────────────────────

def test_worker_direct_dao_mutation_fails(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "DataRetentionWorker.kt",
              "class DataRetentionWorker { fun run() { scannedReceiptDao.delete(r) } }")
    approved = load_allowlist(_allowlist(tmp_path, "allowed_writers: []\n"))
    violations, files_scanned = scan(str(src), approved)
    assert files_scanned > 0
    assert len(violations) > 0


# ── guard_allows_room_migration (DAO files skipped) ───────────────────────────

def test_dao_files_themselves_are_skipped(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "ExpenseDao.kt",
              "@Dao interface ExpenseDao { @Insert fun insert(e: Expense): Long }")
    approved = load_allowlist(_allowlist(tmp_path, "allowed_writers: []\n"))
    violations, files_scanned = scan(str(src), approved)
    assert files_scanned > 0
    assert len(violations) == 0, "DAO interface files must be skipped"


# ── allowlist_requires_reason ─────────────────────────────────────────────────

def test_every_allowlist_entry_has_reason():
    allowlist_path = os.path.join(
        os.path.dirname(__file__), "..", "config", "db_access_allowlist.yml"
    )
    if not os.path.exists(allowlist_path):
        return

    with open(allowlist_path, encoding="utf-8") as f:
        content = f.read()

    missing = []
    current_class = None
    for line in content.splitlines():
        s = line.strip()
        if s.startswith("- class:"):
            current_class = s[len("- class:"):].strip()
        elif s.startswith("reason:") and current_class:
            val = s[len("reason:"):].strip().strip('"').strip("'")
            if not val:
                missing.append(current_class)
            current_class = None

    assert not missing, f"Allowlist entries missing reason: {missing}"


# ── warning_mode_exits_zero_with_violations ───────────────────────────────────

def test_warning_mode_scan_returns_violations_but_does_not_raise(tmp_path):
    """scan() returns violations without raising — caller decides exit code."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "BadViewModel.kt",
              "class BadViewModel { fun x() { expenseDao.delete(e) } }")
    approved = load_allowlist(_allowlist(tmp_path, "allowed_writers: []\n"))
    # scan() must return violations, not raise
    violations, files_scanned = scan(str(src), approved)
    assert files_scanned > 0
    assert isinstance(violations, list)
    assert len(violations) > 0


# ── production DB findings match ratchet baseline ────────────────────────────

def test_production_db_findings_match_ratchet_baseline():
    """Use the ratchet to verify no new DB findings over baseline."""
    import subprocess, sys
    from pathlib import Path
    project_root = Path(__file__).parent.parent
    baseline_path = project_root / "config" / "baselines" / "db_access.json"
    ratchet_script = project_root / "scripts" / "ci" / "guard_ratchet.py"
    guard_script = project_root / "scripts" / "verify_db_access_boundaries.py"
    
    if not ratchet_script.exists() or not guard_script.exists():
        return  # skip in environments without the full source tree
    
    result = subprocess.run(
        [sys.executable, str(ratchet_script),
         "--guard-name", "db_access",
         "--command", "python " + str(guard_script) + " --fail-on-violation",
         "--baseline", str(baseline_path),
         "--fail-on-violation"],
        capture_output=True, text=True, timeout=30,
        cwd=str(project_root)
    )
    assert result.returncode == 0, f"Ratchet failed (exit {result.returncode}):\n{result.stdout}\n{result.stderr}"


# ── Structural exception tests ──────────────────────────────────────────────

def test_structural_exception_for_migration_sql(tmp_path):
    """MIGRATION_145_146 execSQL should pass as structural exception."""
    content = """
package com.example
import androidx.room.migration.Migration
object DatabaseMigrations {
    val MIGRATION_145_146 = object : Migration(145, 146) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE test(id INTEGER)")
        }
    }
}
"""
    approved = load_allowlist(_allowlist(tmp_path, "allowed_writers: []\n"))
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "DatabaseMigrations.kt", content)
    violations, files_scanned = scan(str(src), approved)
    assert files_scanned > 0
    assert len(violations) == 0, (
        f"Migration execSQL should pass as structural exception, got: {violations}"
    )


def test_unrelated_sql_in_migrations_file_fails(tmp_path):
    """Non-migration SQL outside MIGRATION object should still fail."""
    content = """
package com.example
object DatabaseMigrations {
    fun someHelper() {
        val db = getDatabase()
        db.execSQL("DROP TABLE users")
    }
}
"""
    approved = load_allowlist(_allowlist(tmp_path, "allowed_writers: []\n"))
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "DatabaseMigrations.kt", content)
    violations, files_scanned = scan(str(src), approved)
    assert files_scanned > 0
    assert len(violations) > 0, (
        "Non-migration SQL outside MIGRATION object should produce a violation"
    )


def test_rescue_operations_under_maintenance_pass(tmp_path):
    """performMaintenanceRescue raw SQL should pass as structural exception."""
    content = """
package com.example
class FinancialRescueCoordinator {
    fun performMaintenanceRescue() {
        val db = getWritableDatabase()
        db.execSQL("VACUUM")
    }
}
"""
    approved = load_allowlist(_allowlist(tmp_path, "allowed_writers: []\n"))
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "FinancialRescueCoordinator.kt", content)
    violations, files_scanned = scan(str(src), approved)
    assert files_scanned > 0
    assert len(violations) == 0, (
        f"performMaintenanceRescue execSQL should pass as structural exception, got: {violations}"
    )
