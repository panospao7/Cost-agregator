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
import pytest
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


# ── PR-GR-10B source-scope contract ─────────────────────────────────────────
# The guard's scan scope is the checked-in production source-root manifest
# (config/guards/production_source_roots.yml), resolved via
# scripts/guardrails/production_source_scope.py — fail closed with exit 2
# when it is missing/malformed/undeclared; approved-path relevance is a
# semantic filter applied AFTER enumerating every declared production file.
# The fixture allowlist must be a NON-EMPTY YAML list: load_allowlist exits 2
# on a missing file AND on a document that is not a list (an empty file loads
# as None), so a placeholder list entry keeps the fixture self-contained.

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


_RECEIPT_VIOLATION_KT = (
    "package com.example\n"
    "\n"
    "class ReceiptLinkViolator {\n"
    "    fun link(receipt: ScannedReceipt, expenseId: Long): ScannedReceipt {\n"
    "        return receipt.copy(expenseId = expenseId)\n"
    "    }\n"
    "}\n"
)

_CLEAN_KT = "package com.example\nclass Clean { fun f() = 1 }\n"

_SCOPE_ALLOWLIST_REL = "receipt_link_scope_allowlist.yml"


def _write_scope_allowlist(root):
    """Write a NON-EMPTY YAML list allowlist (empty file -> 'not a list' exit 2)."""
    target = root / _SCOPE_ALLOWLIST_REL
    target.write_text(
        "- path: Placeholder\n"
        "  reason: \"scope-contract fixture placeholder\"\n",
        encoding="utf-8",
    )


def _scope_run_main(root, monkeypatch, capsys, extra=()):
    monkeypatch.setattr(
        sys, "argv",
        ["verify_receipt_link_boundaries.py", "--root", str(root)]
        + ["--allowlist", _SCOPE_ALLOWLIST_REL] + list(extra),
    )
    with pytest.raises(SystemExit) as excinfo:
        _mod.main()
    return excinfo.value.code, capsys.readouterr()


def _write_declared(root, rel, content):
    target = root / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def test_manifest_declared_root_finding_matches_hardcoded_era(tmp_path, monkeypatch, capsys):
    """With the single-root manifest declaring app/src/main/java, findings
    are identical to the pre-GR-10B hard-coded-root era: a receipt
    .copy(expenseId = ...) outside approved paths under the declared root is
    flagged."""
    _write_scope_manifest(tmp_path)
    _write_scope_allowlist(tmp_path)
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/ReceiptLinkViolator.kt",
        _RECEIPT_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys, ["--fail-on-violation"])

    assert code == 1
    assert "ReceiptLinkViolator.kt" in out.out
    assert "DIRECT_EXPENSEID_COPY" in out.out


def test_missing_manifest_fails_closed(tmp_path, monkeypatch, capsys):
    """No checked-in manifest -> exit 2 (no conventional-root fallback)."""
    _write_scope_allowlist(tmp_path)
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/ReceiptLinkViolator.kt",
        _RECEIPT_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 2
    assert "production source scope unresolved" in out.err


def test_undeclared_root_file_is_never_scanned(tmp_path, monkeypatch, capsys):
    """Kotlin files outside the declared production roots are invisible."""
    _write_scope_manifest(tmp_path)
    _write_scope_allowlist(tmp_path)
    _write_declared(tmp_path, "app/src/main/java/com/example/Clean.kt", _CLEAN_KT)
    undeclared = (
        tmp_path / "other" / "src" / "main" / "java" / "com" / "example"
        / "ReceiptLinkViolator.kt"
    )
    undeclared.parent.mkdir(parents=True)
    undeclared.write_text(_RECEIPT_VIOLATION_KT, encoding="utf-8")

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 0
    assert "ReceiptLinkViolator" not in out.out
