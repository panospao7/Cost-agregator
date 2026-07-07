"""
test_verify_receipt_link_boundaries.py
Acceptance tests for the static receipt link ownership guard.

Rule: G-RCPT-LINK-01

Run with:
  python -m pytest scripts/test_verify_receipt_link_boundaries.py -v
"""

import os
import sys
import tempfile
from pathlib import Path

# Import the module under test
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard",
    os.path.join(os.path.dirname(__file__), "verify_receipt_link_boundaries.py")
)
_mod = importlib.util.module_from_spec(_spec)
import unittest.mock as _mock
with _mock.patch("builtins.__import__", side_effect=__import__):
    _spec.loader.exec_module(_mod)

scan_file = _mod.scan_file
load_allowlist = _mod.load_allowlist
is_migration_file = _mod.is_migration_file
RULE_ID = _mod.RULE_ID


# ── Helpers ─────────────────────────────────────────────────────────────────

def _write_kt(tmp_path, filename, content):
    """Write a Kotlin source file to a temp directory."""
    f = tmp_path / filename
    f.write_text(content, encoding="utf-8")
    return tmp_path


def _write_allowlist(tmp_path, classes):
    """Write a simple allowlist YAML to a temp directory."""
    p = tmp_path / "allowlist.yml"
    lines = ["allowed_writers:"]
    for cls in classes:
        lines.append(f'  - class: {cls}')
        lines.append(f'    reason: "Test allowlist entry"')
        lines.append(f'    owner: "@test"')
    p.write_text("\n".join(lines), encoding="utf-8")
    return p


# ── Test 1: Direct expenseId update outside service fails ─────────────────

def test_direct_expenseid_update_outside_service_fails(tmp_path, monkeypatch):
    """A ViewModel calling scannedReceiptDao.update(receipt.copy(expenseId = ...)) should be flagged."""
    class_name = "SomeViewModel"
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, f"{class_name}.kt", """
package com.example

class SomeViewModel {
    private val scannedReceiptDao: ScannedReceiptDao = TODO()

    fun linkReceipt(receipt: ScannedReceipt, expenseId: Long) {
        scannedReceiptDao.update(
            receipt.copy(expenseId = expenseId, matchStatus = MatchStatus.MANUALLY_MATCHED)
        )
    }
}
""")

    approved = set()  # empty allowlist — this class is NOT approved
    filepath = src / f"{class_name}.kt"
    rel_path = f"src/{class_name}.kt"
    violations = scan_file(filepath, rel_path, approved)

    assert len(violations) > 0, (
        f"Expected violations for direct expenseId update outside approved path, "
        f"got {len(violations)}"
    )

    # Should detect either DIRECT_EXPENSEID_COPY or DIRECT_RECEIPT_DAO_MUTATION
    reason_codes = {v[3] for v in violations}
    assert any("EXPENSEID" in rc or "RECEIPT_DAO" in rc for rc in reason_codes), (
        f"Expected EXPENSEID or RECEIPT_DAO violation, got: {reason_codes}"
    )


# ── Test 2: ReceiptLinkService path passes ─────────────────────────────────

def test_receipt_link_service_path_passes(tmp_path):
    """ReceiptLinkService is in the approved set — its mutations should NOT be flagged."""
    class_name = "ReceiptLinkService"
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, f"{class_name}.kt", """
package com.example.receipt.lifecycle

class ReceiptLinkService {
    private val scannedReceiptDao: ScannedReceiptDao = TODO()

    suspend fun linkReceiptToExpense(receiptId: Long, expenseId: Long) {
        val receipt = scannedReceiptDao.getById(receiptId)!!
        scannedReceiptDao.update(
            receipt.copy(expenseId = expenseId, matchStatus = MatchStatus.MANUALLY_MATCHED)
        )
    }
}
""")

    approved = {class_name}  # ReceiptLinkService is allowlisted
    filepath = src / f"{class_name}.kt"
    rel_path = f"src/{class_name}.kt"
    violations = scan_file(filepath, rel_path, approved)

    assert len(violations) == 0, (
        f"ReceiptLinkService is the canonical owner — expected 0 violations, "
        f"got {len(violations)}: {violations}"
    )


# ── Test 3: Migration class passes (allowlisted) ───────────────────────────

def test_migration_class_passes(tmp_path):
    """Migration files are inherently approved and should NOT be flagged."""
    class_name = "Migration_87_88"
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, f"{class_name}.kt", """
package com.example.data.database.migrations

class Migration_87_88 : Migration(87, 88) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(\"\"\"
            UPDATE scanned_receipts SET expenseId = NULL WHERE expenseId NOT IN (SELECT id FROM expenses)
        \"\"\")
    }
}
""")

    approved = set()  # empty — but migration files pass via is_migration_file() check
    filepath = src / f"{class_name}.kt"
    rel_path = f"src/{class_name}.kt"

    # Migration files should be recognized
    assert is_migration_file(f"{class_name}.kt"), (
        f"'{class_name}' should be recognized as a migration file"
    )

    violations = scan_file(filepath, rel_path, approved)
    assert len(violations) == 0, (
        f"Migration class should be auto-approved — expected 0 violations, "
        f"got {len(violations)}: {violations}"
    )


# ── Test 4 (bonus): ReceiptLifecycleCoordinator passes ─────────────────────

def test_receipt_lifecycle_coordinator_passes(tmp_path):
    """ReceiptLifecycleCoordinator is in the allowlist — should pass."""
    class_name = "ReceiptLifecycleCoordinator"
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, f"{class_name}.kt", """
package com.example.receipt.lifecycle

class ReceiptLifecycleCoordinator {
    private val scannedReceiptDao: ScannedReceiptDao = TODO()

    suspend fun processReceiptInput() {
        scannedReceiptDao.update(receipt)
    }
}
""")

    approved = {class_name}
    filepath = src / f"{class_name}.kt"
    rel_path = f"src/{class_name}.kt"
    violations = scan_file(filepath, rel_path, approved)

    assert len(violations) == 0, (
        f"ReceiptLifecycleCoordinator is allowlisted — expected 0 violations, "
        f"got {len(violations)}: {violations}"
    )


# ── Test: DAO interface file is skipped ────────────────────────────────────

def test_dao_interface_file_is_skipped(tmp_path):
    """DAO interface files (e.g., ScannedReceiptDao.kt) should be skipped entirely."""
    class_name = "ScannedReceiptDao"
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, f"{class_name}.kt", """
package com.example.data.database.dao

@Dao
interface ScannedReceiptDao {
    @Insert
    suspend fun insert(receipt: ScannedReceipt): Long

    @Update
    suspend fun update(receipt: ScannedReceipt)

    @Delete
    suspend fun delete(receipt: ScannedReceipt)
}
""")

    approved = set()  # empty
    filepath = src / f"{class_name}.kt"
    rel_path = f"src/{class_name}.kt"
    violations = scan_file(filepath, rel_path, approved)

    assert len(violations) == 0, (
        f"DAO interface files must be skipped — got {len(violations)}: {violations}"
    )


# ── Production codebase passes the guard ────────────────────────────────────

def test_production_codebase_has_no_violations():
    """The actual production source tree must pass --fail-on-violation."""
    project_root = os.path.join(os.path.dirname(__file__), "..")
    source_dir = os.path.join(project_root, "app", "src", "main", "java")
    allowlist_path = os.path.join(
        project_root, "scripts", "allowlists", "receipt_link_allowlist.yml"
    )

    if not os.path.isdir(source_dir):
        return  # skip in environments without the full source tree

    approved = load_allowlist(Path(allowlist_path))
    if not approved:
        print("WARNING: Allowlist empty — production test inconclusive")
        return

    all_violations = []
    for filepath in Path(source_dir).rglob("*.kt"):
        rel_path = str(filepath.relative_to(project_root)).replace("\\", "/")
        parts = rel_path.replace("\\", "/").split("/")
        if any(d in {"test", "androidTest", "migration", "generated", "build"} for d in parts):
            continue
        violations = scan_file(filepath, rel_path, approved)
        all_violations.extend(violations)

    assert all_violations == [], (
        f"Production codebase has {len(all_violations)} receipt link violation(s):\n"
        + "\n".join(f"  {f}:{l}  [{r}]  {t}" for f, l, t, r in all_violations[:20])
    )
