"""
test_verify_db_access_boundaries.py
PR H2 acceptance tests for the static DAO mutation guard.

Updated for H2: tests now use the ownership policy and structural exceptions
instead of the legacy allowlist.

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


def _ownership_policy(entries=None):
    """Create an ownership policy list from simplified entry dicts."""
    if entries is None:
        return []
    return entries


def _structural_exceptions(entries=None):
    """Create a structural exceptions list from simplified entry dicts."""
    if entries is None:
        return []
    return entries


# ── guard_fails_on_direct_expenseDao_update_in_viewmodel ─────────────────────

def test_fail_on_violation_exits_nonzero_when_violations_exist(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "SomeViewModel.kt",
              "class SomeViewModel { fun save() { expenseDao.insert(expense) } }")
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned > 0, "Should have scanned at least one file"
    assert len(violations) > 0, "Expected at least one violation"


# ── guard_allows_transaction_lifecycle_expense_write ─────────────────────────

def test_allowlisted_class_does_not_trigger_violation(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "TransactionLifecycleCoordinator.kt",
              "class TransactionLifecycleCoordinator { fun save() { expenseDao.insert(e) } }")
    policy = [
        {
            "path": "TransactionLifecycleCoordinator.kt",
            "class": "TransactionLifecycleCoordinator",
            "method": "*",
            "daos": ["expenseDao"],
            "operation": "write",
            "barrier_required": False,
            "reason": "canonical",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    assert len(violations) == 0, f"Ownership-policy class should not be flagged: {violations}"


# ── guard_fails_on_worker_direct_receipt_update ───────────────────────────────

def test_worker_direct_dao_mutation_fails(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "DataRetentionWorker.kt",
              "class DataRetentionWorker { fun run() { scannedReceiptDao.delete(r) } }")
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned > 0
    assert len(violations) > 0


# ── guard_allows_room_migration (DAO files skipped) ───────────────────────────

def test_dao_files_themselves_are_skipped(tmp_path):
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "ExpenseDao.kt",
              "@Dao interface ExpenseDao { @Insert fun insert(e: Expense): Long }")
    violations, files_scanned = scan(str(src), [], [])
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
    # scan() must return violations, not raise
    violations, files_scanned = scan(str(src), [], [])
    assert files_scanned > 0
    assert isinstance(violations, list)
    assert len(violations) > 0


# ── Ownership policy: barrier enforcement ────────────────────────────────────

def test_ownership_policy_with_barrier_required_fails_if_missing(tmp_path):
    """Policy entry with barrier_required:true must have writeBarrier before mutation."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "BudgetRepository.kt", """
package com.example
class BudgetRepository {
    fun updateBudget() {
        budgetDao.update(b)
    }
}
""")
    policy = [
        {
            "path": "BudgetRepository.kt",
            "class": "BudgetRepository",
            "method": "*",
            "daos": ["budgetDao"],
            "operation": "write",
            "barrier_required": True,
            "reason": "test",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    assert len(violations) > 0, "Should flag MISSING_WRITE_BARRIER"
    assert any("MISSING_WRITE_BARRIER" in v[3] for v in violations), \
        f"Expected MISSING_WRITE_BARRIER, got: {violations}"


def test_ownership_policy_with_barrier_passes_when_present(tmp_path):
    """Policy entry with barrier_required:true passes when writeBarrier is present."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "BudgetRepository.kt", """
package com.example
class BudgetRepository {
    fun updateBudget() {
        writeBarrier.checkWritesAllowed()
        budgetDao.update(b)
    }
}
""")
    policy = [
        {
            "path": "BudgetRepository.kt",
            "class": "BudgetRepository",
            "method": "*",
            "daos": ["budgetDao"],
            "operation": "write",
            "barrier_required": True,
            "reason": "test",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    assert len(violations) == 0, f"writeBarrier present should pass: {violations}"


def test_ownership_policy_without_barrier_required_passes(tmp_path):
    """Policy entry with barrier_required:false passes without writeBarrier."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "TransactionLifecycleCoordinator.kt", """
package com.example
class TransactionLifecycleCoordinator {
    fun insertExpense() {
        expenseDao.insert(e)
    }
}
""")
    policy = [
        {
            "path": "TransactionLifecycleCoordinator.kt",
            "class": "TransactionLifecycleCoordinator",
            "method": "*",
            "daos": ["expenseDao"],
            "operation": "write",
            "barrier_required": False,
            "reason": "canonical",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    assert len(violations) == 0, f"Coordinator without barrier should pass: {violations}"


# ── Ownership policy: method wildcard ────────────────────────────────────────

def test_ownership_policy_method_wildcard_matches_any_method(tmp_path):
    """method '*' should match any method name."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "CategoryRepository.kt", """
package com.example
class CategoryRepository {
    fun addCategory() {
        categoryDao.insert(c)
    }
    fun removeCategory() {
        categoryDao.delete(c)
    }
    fun updateCategory() {
        categoryDao.update(c)
    }
}
""")
    policy = [
        {
            "path": "CategoryRepository.kt",
            "class": "CategoryRepository",
            "method": "*",
            "daos": ["categoryDao"],
            "operation": "write",
            "barrier_required": False,
            "reason": "canonical",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    assert len(violations) == 0, f"Wildcard method should match all methods: {violations}"


# ── Ownership policy: exact method ───────────────────────────────────────────

def test_ownership_policy_exact_method_only_matches_specified(tmp_path):
    """Exact method name should only match that method."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "SomeRepo.kt", """
package com.example
class SomeRepo {
    fun approvedMethod() {
        budgetDao.insert(b)
    }
    fun unapprovedMethod() {
        budgetDao.delete(b)
    }
}
""")
    policy = [
        {
            "path": "SomeRepo.kt",
            "class": "SomeRepo",
            "method": "approvedMethod",
            "daos": ["budgetDao"],
            "operation": "write",
            "barrier_required": False,
            "reason": "test",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    # approvedMethod should pass, unapprovedMethod should fail
    assert len(violations) == 1, (
        f"Expected 1 violation for unapprovedMethod, got {len(violations)}: {violations}"
    )
    assert "UNALLOWLISTED_CLASS" in violations[0][3], \
        f"Expected UNALLOWLISTED_CLASS, got: {violations[0][3]}"


# ── Structural exception tests ──────────────────────────────────────────────

_SAMPLE_STRUCTURAL_EXCEPTIONS = [
    {
        "path": "DatabaseMigrations.kt",
        "class": "DatabaseMigrations",
        "method_pattern": r"MIGRATION_\d+_\d+",
        "operation": "execSQL",
        "reason": "Room migration SQL",
        "owner": "@test",
        "linked_issue": "TEST-001",
    },
    {
        "path": "FinancialRescueCoordinator.kt",
        "class": "FinancialRescueCoordinator",
        "method_pattern": "performMaintenanceRescue",
        "operation": "raw_sqlite",
        "reason": "Exclusive maintenance rescue operation",
        "owner": "@test",
        "linked_issue": "TEST-001",
    },
]


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
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "DatabaseMigrations.kt", content)
    violations, files_scanned = scan(str(src), [], _SAMPLE_STRUCTURAL_EXCEPTIONS)
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
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "DatabaseMigrations.kt", content)
    violations, files_scanned = scan(str(src), [], _SAMPLE_STRUCTURAL_EXCEPTIONS)
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
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "FinancialRescueCoordinator.kt", content)
    violations, files_scanned = scan(str(src), [], _SAMPLE_STRUCTURAL_EXCEPTIONS)
    assert files_scanned > 0
    assert len(violations) == 0, (
        f"performMaintenanceRescue execSQL should pass as structural exception, got: {violations}"
    )


# ── Ownership policy: DAO-specific matching ─────────────────────────────────

def test_ownership_policy_dao_match_is_exact(tmp_path):
    """Mutation on a DAO not in the policy entry's daos list should fail."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "TransactionLifecycleCoordinator.kt", """
package com.example
class TransactionLifecycleCoordinator {
    fun doSomething() {
        scannedReceiptDao.insert(r)  // not in policy DAOs
    }
}
""")
    policy = [
        {
            "path": "TransactionLifecycleCoordinator.kt",
            "class": "TransactionLifecycleCoordinator",
            "method": "*",
            "daos": ["expenseDao", "transactionEventDao"],  # scannedReceiptDao NOT listed
            "operation": "write",
            "barrier_required": False,
            "reason": "canonical transaction lifecycle writer",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    assert len(violations) > 0, (
        f"DAO not in policy should produce violation, got: {violations}"
    )


# ── DB Batch 1: Property-to-interface resolution tests ────────────────────────

def test_property_decl_group_dao_matches_policy_via_interface_type(tmp_path):
    """groupDao: ExpenseGroupDao → resolved to expenseGroupDao, matching policy."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "GroupLifecycleCoordinator.kt", """
package com.example
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import javax.inject.Inject

class GroupLifecycleCoordinator @Inject constructor(
    private val groupDao: ExpenseGroupDao
) {
    fun addGroup() {
        groupDao.insert(g)
    }
}
""")
    policy = [
        {
            "path": "GroupLifecycleCoordinator.kt",
            "class": "GroupLifecycleCoordinator",
            "method": "*",
            "daos": ["expenseGroupDao"],
            "operation": "write",
            "barrier_required": False,
            "reason": "canonical group lifecycle writer",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    assert len(violations) == 0, (
        f"groupDao resolved to expenseGroupDao should match policy, got: {violations}"
    )


def test_property_decl_usage_dao_matches_policy_via_interface_type(tmp_path):
    """usageDao: SubscriptionUsageDao → resolved to subscriptionUsageDao, matching policy."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "SubscriptionRepository.kt", """
package com.example
import com.yourname.expensetracker.data.database.dao.SubscriptionUsageDao
import javax.inject.Inject

class SubscriptionRepository @Inject constructor(
    private val usageDao: SubscriptionUsageDao
) {
    fun recordUsage() {
        usageDao.insert(u)
    }
}
""")
    policy = [
        {
            "path": "SubscriptionRepository.kt",
            "class": "SubscriptionRepository",
            "method": "*",
            "daos": ["subscriptionUsageDao"],
            "operation": "write",
            "barrier_required": False,
            "reason": "canonical subscription writer",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    assert len(violations) == 0, (
        f"usageDao resolved to subscriptionUsageDao should match policy, got: {violations}"
    )


def test_exact_matching_does_not_allow_unrelated_dao(tmp_path):
    """Only the declared DAO interface identity matches; unrelated DAOs are flagged."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "SomeRepo.kt", """
package com.example
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import javax.inject.Inject

class SomeRepo @Inject constructor(
    private val groupDao: ExpenseGroupDao
) {
    fun save() {
        groupDao.insert(g)          // approved: groupDao -> expenseGroupDao in policy
        scannedReceiptDao.delete(r) // NOT approved: scannedReceiptDao not in policy
    }
}
""")
    policy = [
        {
            "path": "SomeRepo.kt",
            "class": "SomeRepo",
            "method": "*",
            "daos": ["expenseGroupDao"],
            "operation": "write",
            "barrier_required": False,
            "reason": "group repo",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    assert len(violations) == 1, (
        f"Expected 1 violation for unrelated DAO, got {len(violations)}: {violations}"
    )
    assert "UNALLOWLISTED_CLASS" in violations[0][3], (
        f"Expected UNALLOWLISTED_CLASS for scannedReceiptDao, got: {violations[0][3]}"
    )


def test_one_approved_dao_does_not_suppress_second_unapproved_dao(tmp_path):
    """One approved DAO property must not suppress a second unapproved DAO in the same class."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "MultiDaoRepo.kt", """
package com.example
import com.yourname.expensetracker.data.database.dao.ExpenseGroupDao
import com.yourname.expensetracker.data.database.dao.GroupMemberDao
import javax.inject.Inject

class MultiDaoRepo @Inject constructor(
    private val groupDao: ExpenseGroupDao,
    private val memberDao: GroupMemberDao
) {
    fun doWork() {
        groupDao.insert(g)   // approved: expenseGroupDao in policy
        memberDao.update(m)  // NOT approved: groupMemberDao NOT in policy
    }
}
""")
    policy = [
        {
            "path": "MultiDaoRepo.kt",
            "class": "MultiDaoRepo",
            "method": "*",
            "daos": ["expenseGroupDao"],
            "operation": "write",
            "barrier_required": False,
            "reason": "group repo",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    assert len(violations) == 1, (
        f"Expected 1 violation for unapproved memberDao, got {len(violations)}: {violations}"
    )
    assert "UNALLOWLISTED_CLASS" in violations[0][3], (
        f"Expected UNALLOWLISTED_CLASS for memberDao, got: {violations[0][3]}"
    )


def test_structural_exception_behavior_remains_exact_after_property_mapping(tmp_path):
    """Existing structural exceptions (DatabaseMigrations, FinancialRescueCoordinator)
    are not affected by the property-to-interface mapping."""
    # Test 1: DatabaseMigrations execSQL still passes
    content_migration = """
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
    src1 = tmp_path / "src_mig"
    src1.mkdir()
    _write_kt(src1, "DatabaseMigrations.kt", content_migration)
    _SAMPLE_STRUCTURAL = [
        {
            "path": "DatabaseMigrations.kt",
            "class": "DatabaseMigrations",
            "method_pattern": r"MIGRATION_\d+_\d+",
            "operation": "execSQL",
            "reason": "Room migration SQL",
            "owner": "@test",
            "linked_issue": "TEST-001",
        },
    ]
    violations, files_scanned = scan(str(src1), [], _SAMPLE_STRUCTURAL)
    assert files_scanned > 0
    assert len(violations) == 0, (
        f"Migration execSQL should still pass, got: {violations}"
    )

    # Test 2: FinancialRescueCoordinator raw_sqlite still passes
    content_rescue = """
package com.example
class FinancialRescueCoordinator {
    fun performMaintenanceRescue() {
        val db = getWritableDatabase()
        db.execSQL("VACUUM")
    }
}
"""
    src2 = tmp_path / "src_rescue"
    src2.mkdir()
    _write_kt(src2, "FinancialRescueCoordinator.kt", content_rescue)
    _SAMPLE_STRUCTURAL2 = [
        {
            "path": "FinancialRescueCoordinator.kt",
            "class": "FinancialRescueCoordinator",
            "method_pattern": "performMaintenanceRescue",
            "operation": "raw_sqlite",
            "reason": "Exclusive maintenance rescue operation",
            "owner": "@test",
            "linked_issue": "TEST-001",
        },
    ]
    violations2, files_scanned2 = scan(str(src2), [], _SAMPLE_STRUCTURAL2)
    assert files_scanned2 > 0
    assert len(violations2) == 0, (
        f"FinancialRescueCoordinator execSQL should still pass, got: {violations2}"
    )

    # Test 3: Non-migration SQL outside migration object still fails (exact matching)
    content_bad = """
package com.example
object DatabaseMigrations {
    fun someHelper() {
        val db = getDatabase()
        db.execSQL("DROP TABLE users")
    }
}
"""
    src3 = tmp_path / "src_bad"
    src3.mkdir()
    _write_kt(src3, "DatabaseMigrations.kt", content_bad)
    violations3, files_scanned3 = scan(str(src3), [], _SAMPLE_STRUCTURAL)
    assert files_scanned3 > 0
    assert len(violations3) > 0, (
        "Non-migration SQL outside MIGRATION object should still produce a violation"
    )


def test_property_decl_with_fully_qualified_type(tmp_path):
    """Fully qualified type paths should still resolve correctly."""
    src = tmp_path / "src"
    src.mkdir()
    _write_kt(src, "SomeRepo.kt", """
package com.example
import javax.inject.Inject

class SomeRepo @Inject constructor(
    private val memberDao: com.yourname.expensetracker.data.database.dao.GroupMemberDao
) {
    fun doWork() {
        memberDao.insert(m)
    }
}
""")
    policy = [
        {
            "path": "SomeRepo.kt",
            "class": "SomeRepo",
            "method": "*",
            "daos": ["groupMemberDao"],
            "operation": "write",
            "barrier_required": False,
            "reason": "test",
            "owner": "@test",
            "linked_issue": "TEST-001",
        }
    ]
    violations, files_scanned = scan(str(src), policy, [])
    assert files_scanned > 0
    assert len(violations) == 0, (
        f"Fully qualified GroupMemberDao should resolve to groupMemberDao, got: {violations}"
    )
