package com.yourname.expensetracker.data.database

import android.content.Context
import android.os.Build
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * R0-1b (RG11-H2) device twin of the JVM `NestedTransactionSemanticsTest`.
 *
 * Same hypothesis, same hand-derived oracles, same REFUTED / LOUD / PRECONDITION
 * message scheme, but executed on the device's real Android framework SQLite
 * (the stronger evidence versus Robolectric's native SQLite).
 *
 * Room `withTransaction` opens a REAL nested SQLite transaction. When a nested
 * block fails and the caller swallows that failure, the SQLiteSession contract
 * marks the parent as child-failed, so the OUTERMOST `endTransaction` silently rolls
 * back every write of the outer block while the outer block still returns normally.
 *
 *  - "REFUTED: A present after swallowed nested failure"  -> hypothesis false.
 *  - "LOUD: outer threw <SimpleClassName>"                -> the rollback is not silent.
 *  - "PRECONDITION ..."                                    -> test could not exercise the shape.
 *
 * The harness is the production in-memory builder (`AppDatabase.inMemoryBuilder`).
 * Every failure message carries `PRAGMA foreign_keys`, `Build.VERSION.SDK_INT` and
 * `select sqlite_version()` so the runner log identifies the environment. Messages
 * contain counts, flags, versions and exception class names only.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(RestrictedExpenseDaoMutation::class)
class NestedTransactionSemanticsInstrumentedTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.inMemoryBuilder(context).build()
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private fun markerCategory() = Category(name = "Marker A", icon = "A", color = "#A1B2C3")

    private suspend fun markerPresent(): Boolean = db.categoryDao().getByName("Marker A") != null

    /** Records PRAGMA foreign_keys for failure messages only; asserts nothing about it. */
    private fun foreignKeysPragma(): String =
        db.openHelper.writableDatabase.query("PRAGMA foreign_keys").use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0).toString() else "unknown"
        }

    /** Records the framework SQLite version for failure messages only. */
    private fun sqliteVersion(): String =
        db.openHelper.writableDatabase.query("select sqlite_version()").use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) ?: "unknown" else "unknown"
        }

    /** Bounded environment suffix appended to every verdict message. */
    private fun env(): String =
        "foreign_keys=${foreignKeysPragma()}, sdk=${Build.VERSION.SDK_INT}, sqlite=${sqliteVersion()}"

    /**
     * T1. Oracle (hand-derived from the SQLiteSession contract):
     * outer begins (depth 1), inserts A; nested begins (depth 2) and throws, so the
     * nested endTransaction runs WITHOUT setTransactionSuccessful and marks the parent
     * child-failed; the caller swallows; the outer block returns and the outer
     * endTransaction (depth 0) rolls back although setTransactionSuccessful was called.
     * Expected if confirmed: outer returns normally AND A is absent.
     */
    @Test
    fun t1_swallowedNestedFailure_outerReturnsNormally_andOuterInsertIsRolledBack() = runBlocking {
        var outerThrown: Throwable? = null
        var nestedFailureObserved = false
        try {
            db.withTransaction {
                db.categoryDao().insert(markerCategory())
                val nested = runCatching {
                    db.withTransaction<Unit> { error("synthetic nested failure") }
                }
                nestedFailureObserved = nested.isFailure
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            outerThrown = e
        }

        val environment = env()
        val thrown = outerThrown
        if (thrown != null) {
            fail("LOUD: outer threw ${thrown.javaClass.simpleName} ($environment)")
        }
        assertTrue(
            "PRECONDITION: nested failure was not observed by the caller ($environment)",
            nestedFailureObserved
        )
        if (markerPresent()) {
            fail("REFUTED: A present after swallowed nested failure ($environment)")
        }
        assertNull("A must be absent (hypothesis confirmed)", db.categoryDao().getByName("Marker A"))
    }

    /**
     * T2 control. The exception is caught INSIDE the nested block, so the nested block
     * completes normally and marks itself successful: nothing is marked child-failed.
     * Oracle: outer commits and A is present. Must pass regardless of T1's outcome.
     */
    @Test
    fun t2_control_failureCaughtInsideNestedBlock_outerCommits_andInsertIsPresent() = runBlocking {
        var outerThrown: Throwable? = null
        try {
            db.withTransaction {
                db.categoryDao().insert(markerCategory())
                db.withTransaction<Unit> {
                    runCatching { error("synthetic failure caught inside nested block") }
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            outerThrown = e
        }

        val environment = env()
        val thrown = outerThrown
        if (thrown != null) {
            fail("CONTROL BROKEN: outer threw ${thrown.javaClass.simpleName} ($environment)")
        }
        assertTrue("CONTROL BROKEN: A absent after a clean nested block ($environment)", markerPresent())
    }

    /**
     * T3. Same shape as T1 but the nested failure is a real constraint violation:
     * `UPDATE expenses SET categoryId = <missing id>` through the real ExpenseDao
     * (the exact statement DefaultExpenseCategoryAssignmentService issues). The
     * Expense.categoryId FK references categories(id), so with foreign_keys ON the
     * statement throws a constraint exception inside the nested block.
     * Oracle: with FK enforced, nested throws; caller swallows; A (outer insert) absent
     * and the expense keeps categoryId = null. If the FK never fires, the test reports
     * a distinct PRECONDITION failure (no verdict on the hypothesis from this test).
     */
    @Test
    fun t3_swallowedForeignKeyViolationInNestedBlock_outerReturnsNormally_andOuterInsertIsRolledBack() = runBlocking {
        val missingCategoryId = 987_654L
        assertNull("fixture: category id must not exist", db.categoryDao().getById(missingCategoryId))
        val expenseId = db.expenseDao().insert(
            Expense(
                amount = 12.5,
                merchant = "Synthetic Merchant",
                transactionType = TransactionType.PURCHASE,
                date = 1_700_000_000_000L
            )
        )
        assertTrue("fixture: expense insert failed", expenseId > 0L)
        assertNull("fixture: expense must start uncategorized", db.expenseDao().getById(expenseId)!!.categoryId)

        var outerThrown: Throwable? = null
        var nestedFailure: Throwable? = null
        try {
            db.withTransaction {
                db.categoryDao().insert(markerCategory())
                nestedFailure = runCatching {
                    db.withTransaction<Unit> {
                        db.expenseDao().updateCategory(expenseId, missingCategoryId)
                    }
                }.exceptionOrNull()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            outerThrown = e
        }

        val environment = env()
        val thrown = outerThrown
        if (thrown != null) {
            fail("LOUD: outer threw ${thrown.javaClass.simpleName} ($environment)")
        }
        val nested = nestedFailure
        if (nested == null) {
            fail(
                "PRECONDITION NOT MET: FK violation did not fire in nested block " +
                    "($environment, expenseCategoryId=${db.expenseDao().getById(expenseId)!!.categoryId})"
            )
        }
        if (markerPresent()) {
            fail(
                "REFUTED: A present after swallowed nested failure " +
                    "(nested=${nested!!.javaClass.simpleName}, $environment)"
            )
        }
        val after = db.expenseDao().getById(expenseId)
        assertNotNull("expense must still exist", after)
        assertNull("expense category must be unchanged", after!!.categoryId)
    }
}
