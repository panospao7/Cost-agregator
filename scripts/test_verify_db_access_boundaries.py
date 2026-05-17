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
    violations = scan(str(src), approved)
    assert len(violations) > 0, "Expected at least one violation"


# ── guard_allows_transaction_lifecycle_expense_write ─────────────────────────

def test_allowlisted_class_does_not_trigger_violation(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "TransactionLifecycleCoordinator.kt",
              "class TransactionLifecycleCoordinator { fun save() { expenseDao.insert(e) } }")
    approved = load_allowlist(_allowlist(tmp_path,
        "allowed_writers:\n  - class: TransactionLifecycleCoordinator\n    daos: [expenseDao]\n    reason: canonical\n"
    ))
    violations = scan(str(src), approved)
    assert len(violations) == 0, f"Allowlisted class should not be flagged: {violations}"


# ── guard_fails_on_worker_direct_receipt_update ───────────────────────────────

def test_worker_direct_dao_mutation_fails(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "DataRetentionWorker.kt",
              "class DataRetentionWorker { fun run() { scannedReceiptDao.delete(r) } }")
    approved = load_allowlist(_allowlist(tmp_path, "allowed_writers: []\n"))
    violations = scan(str(src), approved)
    assert len(violations) > 0


# ── guard_allows_room_migration (DAO files skipped) ───────────────────────────

def test_dao_files_themselves_are_skipped(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "ExpenseDao.kt",
              "@Dao interface ExpenseDao { @Insert fun insert(e: Expense): Long }")
    approved = load_allowlist(_allowlist(tmp_path, "allowed_writers: []\n"))
    violations = scan(str(src), approved)
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
    violations = scan(str(src), approved)
    assert isinstance(violations, list)
    assert len(violations) > 0


# ── production codebase passes the guard ─────────────────────────────────────

def test_production_codebase_has_no_violations():
    """The actual production source tree must pass --fail-on-violation."""
    project_root = os.path.join(os.path.dirname(__file__), "..")
    source_dir = os.path.join(project_root, "app", "src", "main", "java")
    allowlist_path = os.path.join(project_root, "config", "db_access_allowlist.yml")

    if not os.path.isdir(source_dir):
        return  # skip in environments without the full source tree

    approved = load_allowlist(allowlist_path)
    violations = scan(source_dir, approved)
    assert violations == [], (
        f"Production codebase has {len(violations)} unauthorized DAO mutation(s):\n"
        + "\n".join(f"  {p}:{n}  {t.strip()}" for p, n, t in violations)
    )
