package com.yourname.expensetracker.golden

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Base class for Golden Scenario tests.
 *
 * Provides:
 * - Real Room in-memory database with all DAOs
 * - Fixed TimeProvider (deterministic timestamps)
 * - Real DatabaseWriteBarrier (delegates to RestoreMaintenanceMode)
 * - Test dispatcher for coroutines
 *
 * Subclasses get real DB behavior without mocks for the data layer.
 * Only external services (cloud AI, geocoding, network) should be mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
abstract class GoldenTestBase {

    protected lateinit var database: AppDatabase
    protected val testDispatcher = StandardTestDispatcher()
    protected val testScope = TestScope(testDispatcher)

    // Fixed time: 2026-04-15 12:00:00 UTC
    protected val fixedNow = 1776340800000L
    protected val timeProvider: TimeProvider = mockk<TimeProvider>().also {
        every { it.now() } returns fixedNow
    }

    // Real write barrier backed by real maintenance mode
    protected val restoreMaintenanceMode by lazy {
        RestoreMaintenanceMode(ApplicationProvider.getApplicationContext<Context>(), timeProvider)
    }
    protected val writeBarrier = DatabaseWriteBarrier(restoreMaintenanceMode)

    @Before
    open fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    open fun tearDown() {
        database.close()
    }

    // ── Helper: Insert test categories ──
    protected suspend fun seedCategories(): List<Category> {
        val categories = listOf(
            Category(id = 1, name = "Food", icon = "🍕", color = "#FF5722"),
            Category(id = 2, name = "Transport", icon = "🚗", color = "#2196F3"),
            Category(id = 3, name = "Shopping", icon = "🛍️", color = "#9C27B0"),
            Category(id = 4, name = "Bills", icon = "📄", color = "#607D8B"),
            Category(id = 5, name = "Entertainment", icon = "🎬", color = "#E91E63")
        )
        categories.forEach { database.categoryDao().insert(it) }
        return categories
    }

    // ── Helper: Create a purchase expense ──
    protected fun createPurchase(
        amount: Double,
        currency: String = "EUR",
        merchant: String = "Test Merchant",
        categoryId: Long? = 1L,
        date: Long = fixedNow,
        id: Long = 0L
    ): Expense = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = merchant,
        merchantKey = merchant.lowercase().replace(" ", "_"),
        categoryId = categoryId,
        date = date,
        transactionType = TransactionType.PURCHASE,
        createdAt = fixedNow
    )

    // ── Helper: Insert expense directly (for setup, not for testing lifecycle) ──
    protected suspend fun insertExpense(expense: Expense): Long {
        return database.expenseDao().insertAtomic(expense)
    }
}
