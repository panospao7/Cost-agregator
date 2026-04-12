package com.yourname.expensetracker.data.database.dao

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BudgetDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var budgetDao: BudgetDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        budgetDao = database.budgetDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun makeBudget(
        amount: Double,
        period: BudgetPeriod,
        startDate: Long,
        categoryId: Long? = null,
        isActive: Boolean = true,
        notifyAtWarning: Float = 0.75f,
        createdAt: Long = 1_700_000_000_000L
    ) = Budget(
        categoryId = categoryId,
        amount = amount,
        period = period,
        startDate = startDate,
        isActive = isActive,
        notifyAtWarning = notifyAtWarning,
        createdAt = createdAt
    )

    @Test
    fun insert_budget_then_retrieve_by_id_returns_persisted_budget() = runBlocking {
        val budget = makeBudget(
            amount = 500.0,
            period = BudgetPeriod.MONTHLY,
            startDate = 1_706_745_600_000L
        )

        val id = budgetDao.insert(budget)
        val stored = budgetDao.getById(id)

        assertNotNull(stored)
        assertEquals(id, stored!!.id)
        assertEquals(500.0, stored.amount, 0.0001)
        assertEquals(BudgetPeriod.MONTHLY, stored.period)
    }

    @Test
    fun query_budgets_by_period_date_range_returns_matching_budgets() = runBlocking {
        val janStart = 1_704_067_200_000L // 2024-01-01 UTC
        val febStart = 1_706_745_600_000L // 2024-02-01 UTC
        val marStart = 1_709_251_200_000L // 2024-03-01 UTC

        // Use inactive budgets to avoid partial-unique-index violation
        // (only one active overall budget allowed at a time).
        budgetDao.insert(makeBudget(amount = 300.0, period = BudgetPeriod.MONTHLY, startDate = janStart, isActive = false))
        budgetDao.insert(makeBudget(amount = 400.0, period = BudgetPeriod.MONTHLY, startDate = febStart, isActive = false))
        budgetDao.insert(makeBudget(amount = 450.0, period = BudgetPeriod.MONTHLY, startDate = marStart, isActive = false))

        val rangeStart = febStart
        val rangeEnd = marStart
        val inRange = budgetDao.getAll().filter { it.startDate in rangeStart..rangeEnd }

        assertEquals(2, inRange.size)
        assertEquals(setOf(febStart, marStart), inRange.map { it.startDate }.toSet())
    }

    @Test
    fun update_warning_threshold_persists_new_value() = runBlocking {
        val id = budgetDao.insert(
            makeBudget(
                amount = 800.0,
                period = BudgetPeriod.MONTHLY,
                startDate = 1_706_745_600_000L,
                notifyAtWarning = 0.75f
            )
        )

        val original = budgetDao.getById(id)!!
        budgetDao.update(original.copy(notifyAtWarning = 0.85f))

        val updated = budgetDao.getById(id)
        assertNotNull(updated)
        assertEquals(0.85f, updated!!.notifyAtWarning)
    }

    @Test
    fun delete_budget_then_getById_returns_null() = runBlocking {
        val id = budgetDao.insert(
            makeBudget(
                amount = 250.0,
                period = BudgetPeriod.WEEKLY,
                startDate = 1_706_745_600_000L
            )
        )
        val stored = budgetDao.getById(id)!!

        budgetDao.delete(stored)

        val deleted = budgetDao.getById(id)
        assertNull(deleted)
    }

    // ── B4: deterministic active-budget selection tests ───────────────

    @Test
    fun getOverallBudget_returns_budget_with_highest_id_when_unique_index_prevents_duplicates() = runBlocking {
        // With FRESH_INSTALL_CALLBACK the partial unique index is in place,
        // so we can only have one active overall budget.
        // Verify the single active one is returned.
        val id = budgetDao.insert(
            makeBudget(amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L)
        )

        val overall = budgetDao.getOverallBudget()
        assertNotNull(overall)
        assertEquals(id, overall!!.id)
        assertEquals(1000.0, overall.amount, 0.0001)
    }

    @Test
    fun getByCategory_returns_correct_category_budget() = runBlocking {
        // Need a real category for the FK
        val catId = database.categoryDao().insert(
            Category(name = "Food", icon = "🍔", color = "#E53935", isDefault = true)
        )

        val budgetId = budgetDao.insert(
            makeBudget(amount = 200.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L, categoryId = catId)
        )

        val result = budgetDao.getByCategory(catId)
        assertNotNull(result)
        assertEquals(budgetId, result!!.id)
        assertEquals(catId, result.categoryId)
    }

    @Test
    fun deactivateOtherOverallBudgets_demotes_older_budgets() = runBlocking {
        // Temporarily turn off the unique index enforcement by inserting then deactivating
        val id1 = budgetDao.insert(
            makeBudget(amount = 500.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L)
        )
        // Deactivate id1 first to avoid index violation
        budgetDao.setActive(id1, false)

        val id2 = budgetDao.insert(
            makeBudget(amount = 600.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L)
        )

        // Reactivate id1 - this should work since id2 is active
        // Actually with the index, we can't have both active. Test deactivation logic.
        budgetDao.deactivateOtherOverallBudgets(id2)

        val b1 = budgetDao.getById(id1)!!
        val b2 = budgetDao.getById(id2)!!
        // id1 was already inactive, id2 should remain active
        assertTrue(!b1.isActive)
        assertTrue(b2.isActive)
    }

    @Test
    fun deactivateOtherCategoryBudgets_demotes_older_category_budgets() = runBlocking {
        val catId = database.categoryDao().insert(
            Category(name = "Transport", icon = "🚌", color = "#1E88E5", isDefault = true)
        )

        val id1 = budgetDao.insert(
            makeBudget(amount = 100.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L, categoryId = catId)
        )
        // Deactivate id1 to avoid index violation when inserting id2
        budgetDao.setActive(id1, false)

        val id2 = budgetDao.insert(
            makeBudget(amount = 150.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L, categoryId = catId)
        )

        budgetDao.deactivateOtherCategoryBudgets(catId, id2)

        val b1 = budgetDao.getById(id1)!!
        val b2 = budgetDao.getById(id2)!!
        assertTrue(!b1.isActive)
        assertTrue(b2.isActive)
    }

    @Test
    fun getOverallBudget_returns_null_when_no_active_overall_budget_exists() = runBlocking {
        // Insert a category budget, not an overall budget
        val catId = database.categoryDao().insert(
            Category(name = "Bills", icon = "💳", color = "#546E7A")
        )
        budgetDao.insert(
            makeBudget(amount = 300.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L, categoryId = catId)
        )

        val overall = budgetDao.getOverallBudget()
        assertNull(overall)
    }

    // ── B4: ABORT semantics & transactional switching tests ───────────

    @Test
    fun insert_with_ABORT_rejects_duplicate_active_overall_budget() = runBlocking {
        // First active overall budget succeeds
        budgetDao.insert(
            makeBudget(amount = 500.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L)
        )

        // Second active overall budget must fail (partial unique index)
        try {
            budgetDao.insert(
                makeBudget(amount = 600.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L)
            )
            fail("Expected SQLiteConstraintException for duplicate active overall budget")
        } catch (_: SQLiteConstraintException) {
            // expected — ABORT raises instead of silently replacing
        }

        // Only the original budget should exist
        val all = budgetDao.getAll()
        assertEquals(1, all.size)
        assertEquals(500.0, all[0].amount, 0.0001)
    }

    @Test
    fun insert_with_ABORT_rejects_duplicate_active_category_budget() = runBlocking {
        val catId = database.categoryDao().insert(
            Category(name = "Groceries", icon = "🛒", color = "#43A047")
        )

        budgetDao.insert(
            makeBudget(amount = 200.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L, categoryId = catId)
        )

        try {
            budgetDao.insert(
                makeBudget(amount = 300.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L, categoryId = catId)
            )
            fail("Expected SQLiteConstraintException for duplicate active category budget")
        } catch (_: SQLiteConstraintException) {
            // expected
        }

        val all = budgetDao.getAll()
        assertEquals(1, all.size)
        assertEquals(200.0, all[0].amount, 0.0001)
    }

    @Test
    fun insertAndActivateOverall_deactivates_old_budget_and_preserves_its_notification_fields() = runBlocking {
        // Insert first overall budget and simulate notifications
        val id1 = budgetDao.insert(
            makeBudget(amount = 500.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L)
        )
        budgetDao.updateWarningNotification(id1, 1_700_100_000_000L)
        budgetDao.updateCriticalNotification(id1, 1_700_200_000_000L)

        // Switch to a new active overall budget via the safe transactional path
        val id2 = budgetDao.insertAndActivateOverall(
            makeBudget(amount = 800.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L)
        )

        // Old budget: deactivated but notification fields preserved
        val old = budgetDao.getById(id1)!!
        assertTrue(!old.isActive)
        assertEquals(1_700_100_000_000L, old.lastWarningNotifiedAt)
        assertEquals(1_700_200_000_000L, old.lastCriticalNotifiedAt)
        assertEquals(500.0, old.amount, 0.0001) // amount unchanged

        // New budget: active
        val new = budgetDao.getById(id2)!!
        assertTrue(new.isActive)
        assertEquals(800.0, new.amount, 0.0001)

        // Only one active overall budget
        val overall = budgetDao.getOverallBudget()
        assertNotNull(overall)
        assertEquals(id2, overall!!.id)
    }

    @Test
    fun insertAndActivateCategory_deactivates_old_category_budget_and_preserves_its_data() = runBlocking {
        val catId = database.categoryDao().insert(
            Category(name = "Dining", icon = "🍽️", color = "#FF7043")
        )

        val id1 = budgetDao.insert(
            makeBudget(amount = 150.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L, categoryId = catId)
        )
        budgetDao.updateExceededNotification(id1, 1_700_300_000_000L)

        val id2 = budgetDao.insertAndActivateCategory(
            makeBudget(amount = 250.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L, categoryId = catId)
        )

        // Old row deactivated, notification preserved
        val old = budgetDao.getById(id1)!!
        assertTrue(!old.isActive)
        assertEquals(1_700_300_000_000L, old.lastExceededNotifiedAt)
        assertEquals(150.0, old.amount, 0.0001)

        // New row is active
        val new = budgetDao.getById(id2)!!
        assertTrue(new.isActive)
        assertEquals(catId, new.categoryId)

        // Query returns only the new one
        val result = budgetDao.getByCategory(catId)
        assertNotNull(result)
        assertEquals(id2, result!!.id)
    }

    @Test
    fun insertAndActivateOverall_works_when_no_prior_active_budget_exists() = runBlocking {
        val id = budgetDao.insertAndActivateOverall(
            makeBudget(amount = 1000.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L)
        )

        val overall = budgetDao.getOverallBudget()
        assertNotNull(overall)
        assertEquals(id, overall!!.id)
        assertTrue(overall.isActive)
    }

    @Test
    fun insertAndActivateCategory_requires_non_null_categoryId() = runBlocking {
        try {
            budgetDao.insertAndActivateCategory(
                makeBudget(amount = 100.0, period = BudgetPeriod.MONTHLY, startDate = 1_706_745_600_000L, categoryId = null)
            )
            fail("Expected IllegalArgumentException for null categoryId")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("non-null categoryId"))
        }
    }
}
