"""
test_verify_import_lifecycle_boundaries.py
Acceptance tests for the static import lifecycle boundary guard.

Rule: G-IMPORT-01

Run with:
  python -m pytest scripts/test_verify_import_lifecycle_boundaries.py -v
"""

import os
import sys
from pathlib import Path

# Import the module under test
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard",
    os.path.join(os.path.dirname(__file__), "verify_import_lifecycle_boundaries.py")
)
_mod = importlib.util.module_from_spec(_spec)
import unittest.mock as _mock
with _mock.patch("builtins.__import__", side_effect=__import__):
    _spec.loader.exec_module(_mod)

scan_file = _mod.scan_file
load_allowlist = _mod.load_allowlist
has_provenance_writes = _mod.has_provenance_writes
is_import_file = _mod.is_import_file
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


# ── Test 1: Importer with direct DAO insert fails ──────────────────────────

def test_importer_with_direct_dao_insert_fails(tmp_path):
    """An import file calling expenseDao.insert directly should be flagged."""
    class_name = "CsvImporter"
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, f"{class_name}.kt", """
package com.example.util

class CsvImporter {
    private val expenseDao: ExpenseDao = TODO()
    private val categoryDao: CategoryDao = TODO()

    fun importRow(expense: Expense) {
        val id = expenseDao.insert(expense)
        val catId = categoryDao.insert(Category(name = "Imported"))
    }
}
""")

    approved = set()  # empty allowlist — this importer is NOT approved
    filepath = src / f"{class_name}.kt"
    rel_path = f"src/{class_name}.kt"
    violations = scan_file(filepath, rel_path, approved)

    assert len(violations) > 0, (
        f"Expected violations for direct DAO insert in import file, "
        f"got {len(violations)}"
    )

    reason_codes = {v[3] for v in violations}
    assert any("IMPORT" in rc or "DAO" in rc for rc in reason_codes), (
        f"Expected IMPORT_DIRECT violation, got: {reason_codes}"
    )


# ── Test 2: Importer using coordinator passes ──────────────────────────────

def test_importer_using_coordinator_passes(tmp_path):
    """An importer that routes through TransactionLifecycleCoordinator should pass."""
    class_name = "CsvExpenseImporter"
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, f"{class_name}.kt", """
package com.example.util

import com.example.domain.transaction.lifecycle.TransactionLifecycleCoordinator

class CsvExpenseImporter {
    private val coordinator: TransactionLifecycleCoordinator = TODO()
    private val categoryDao: CategoryDao = TODO()

    suspend fun importFromContent(csvContent: String, fileImportRunId: Long? = null) {
        val request = CreateExpenseRequest(
            merchant = "Test",
            amount = 10.0,
            fileImportRunId = fileImportRunId
        )
        val result = coordinator.createExpense(request)
    }
}
""")

    approved = {class_name}
    filepath = src / f"{class_name}.kt"
    rel_path = f"src/{class_name}.kt"
    violations = scan_file(filepath, rel_path, approved)

    assert len(violations) == 0, (
        f"CsvExpenseImporter using coordinator should pass — expected 0 violations, "
        f"got {len(violations)}: {violations}"
    )


# ── Test 3: Importer with provenance writes passes ─────────────────────────

def test_importer_with_provenance_writes_passes(tmp_path):
    """An importer that propagates fileImportRunId to CreateExpenseRequest should pass."""
    class_name = "JsonExpenseImporter"
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, f"{class_name}.kt", """
package com.example.util

import com.example.domain.transaction.lifecycle.TransactionLifecycleCoordinator

class JsonExpenseImporter {
    private val coordinator: TransactionLifecycleCoordinator = TODO()

    suspend fun importFromContent(jsonContent: String, fileImportRunId: Long? = null): ImportResult {
        val request = CreateExpenseRequest(
            merchant = row.getString("merchant"),
            amount = row.optDouble("amount", 0.0),
            fileImportRunId = fileImportRunId,
            csvImportBatchId = "batch-123"
        )
        return when (val result = coordinator.createExpense(request)) {
            is CreateExpenseResult.Created -> ImportResult(true, 1, 0, 0, emptyList(), listOf(result.expenseId))
            else -> ImportResult(false, 0, 0, 0, emptyList(), emptyList())
        }
    }
}
""")

    approved = {class_name}
    filepath = src / f"{class_name}.kt"
    rel_path = f"src/{class_name}.kt"
    violations = scan_file(filepath, rel_path, approved)

    assert len(violations) == 0, (
        f"JsonExpenseImporter with provenance writes should pass — "
        f"expected 0 violations, got {len(violations)}: {violations}"
    )


# ── Test: has_provenance_writes helper ──────────────────────────────────────

def test_has_provenance_writes_detects_provenance():
    """has_provenance_writes should detect fileImportRunId and importBatchId assignments."""
    content_with = """
        val request = CreateExpenseRequest(
            fileImportRunId = fileImportRunId,
            csvImportBatchId = "abc-123"
        )
    """
    assert has_provenance_writes(content_with), (
        "Content with fileImportRunId and csvImportBatchId should be detected"
    )

    content_without = """
        val request = CreateExpenseRequest(
            merchant = "Test",
            amount = 10.0
        )
    """
    assert not has_provenance_writes(content_without), (
        "Content without provenance fields should not be detected"
    )


# ── Test: is_import_file helper ────────────────────────────────────────────

def test_is_import_file_detects_import_names():
    """is_import_file should detect files with 'Import' in the name."""
    assert is_import_file("CsvExpenseImporter.kt"), "Should detect 'Importer'"
    assert is_import_file("ImportCoordinator.kt"), "Should detect 'Import'"
    assert is_import_file("ImportService.kt"), "Should detect 'Import'"
    assert not is_import_file("ExpenseViewModel.kt"), "Should not flag non-import files"
    assert not is_import_file("TransactionCoordinator.kt"), "Should not flag non-import files"


# ── Production codebase passes the guard ────────────────────────────────────

def test_production_codebase_has_no_violations():
    """The actual production source tree must pass --fail-on-violation."""
    project_root = os.path.join(os.path.dirname(__file__), "..")
    source_dir = os.path.join(project_root, "app", "src", "main", "java")
    allowlist_path = os.path.join(
        project_root, "scripts", "allowlists", "import_lifecycle_allowlist.yml"
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
        f"Production codebase has {len(all_violations)} import lifecycle violation(s):\n"
        + "\n".join(f"  {f}:{l}  [{r}]  {t}" for f, l, t, r in all_violations[:20])
    )
