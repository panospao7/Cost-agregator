package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import androidx.room.withTransaction
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * A.9 Batch 1 regression tests: proves that full-data repository methods
 * (`getAllExpenses`, `getExpensesBetween`, `getExpensesBetweenFlow`) return
 * the complete dataset without silent truncation, even for histories that
 * exceed the former default caps (500 / 2000).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseRepositoryTruncationTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val userCorrectionDao = mockk<UserCorrectionDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val transferDirectionAnalytics = mockk<TransferDirectionAnalytics>(relaxed = true)
    private val transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)

    private lateinit var repository: ExpenseRepository

    /** Generate N distinct test expenses. */
    private fun generateExpenses(count: Int, baseTime: Long = 1_700_000_000_000L): List<Expense> =
        (1..count).map { i ->
            Expense(
                id = i.toLong(),
                amount = 10.0 + i,
                merchant = "Merchant-$i",
                transactionType = TransactionType.PURCHASE,
                date = baseTime - i * 60_000L  // 1 minute apart
            )
        }

    @Before
    fun setup() {
        // Default: uncapped flow returns empty list
        every { expenseDao.getAllFlowUncapped() } returns flowOf(emptyList())

        // Mock Room withTransaction to run the block on the test coroutine
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(transactionBlock)) } coAnswers {
            transactionBlock.captured.invoke()
        }

        repository = ExpenseRepository(
            database,
            expenseDao,
            userCorrectionDao,
            pendingReviewDao,
            merchantCategoryRepository,
            merchantNormalizer,
            transferDirectionAnalytics,
            transactionLifecycleCoordinator
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    // ── getAllExpenses — formerly capped at 500 ─────────────────────────────

    @Test
    fun `getAllExpenses returns all rows when history exceeds former 500-row cap`() = runTest {
        val largeHistory = generateExpenses(750)
        every { expenseDao.getAllFlowUncapped() } returns flowOf(largeHistory)

        val result = repository.getAllExpenses().first()

        assertEquals(
            "getAllExpenses must return the full dataset (750), not the former 500 cap",
            750,
            result.size
        )
    }

    @Test
    fun `getAllExpenses returns all rows when history exceeds former 2000-row cap`() = runTest {
        val largeHistory = generateExpenses(2500)
        every { expenseDao.getAllFlowUncapped() } returns flowOf(largeHistory)

        val result = repository.getAllExpenses().first()

        assertEquals(
            "getAllExpenses must return the full dataset (2500), not any legacy cap",
            2500,
            result.size
        )
    }

    @Test
    fun `getAllExpenses delegates to uncapped DAO flow not bounded getAllFlow`() = runTest {
        every { expenseDao.getAllFlowUncapped() } returns flowOf(emptyList())

        repository.getAllExpenses().first()

        verify { expenseDao.getAllFlowUncapped() }
        verify(exactly = 0) { expenseDao.getAllFlow(any()) }
    }

    // ── getExpensesBetween — formerly capped at 2000 ────────────────────────

    @Test
    fun `getExpensesBetween returns all rows when history exceeds former 2000-row cap`() = runTest {
        val start = 1_690_000_000_000L
        val end = 1_700_000_000_000L
        val largeHistory = generateExpenses(3000, baseTime = end)

        coEvery { expenseDao.getExpensesBetweenUncapped(start, end) } returns largeHistory

        val result = repository.getExpensesBetween(start, end)

        assertEquals(
            "getExpensesBetween must return full dataset (3000), not the former 2000 cap",
            3000,
            result.size
        )
    }

    @Test
    fun `getExpensesBetween delegates to uncapped DAO method not bounded variant`() = runTest {
        val start = 1_690_000_000_000L
        val end = 1_700_000_000_000L

        coEvery { expenseDao.getExpensesBetweenUncapped(start, end) } returns emptyList()

        repository.getExpensesBetween(start, end)

        coVerify { expenseDao.getExpensesBetweenUncapped(start, end) }
        coVerify(exactly = 0) { expenseDao.getExpensesBetween(any(), any(), any(), any()) }
    }

    // ── getExpensesBetweenFlow — formerly capped at 2000 ────────────────────

    @Test
    fun `getExpensesBetweenFlow returns all rows when history exceeds former 2000-row cap`() = runTest {
        val start = 1_690_000_000_000L
        val end = 1_700_000_000_000L
        val largeHistory = generateExpenses(2500, baseTime = end)

        every { expenseDao.getExpensesBetweenFlowUncapped(start, end) } returns flowOf(largeHistory)

        val result = repository.getExpensesBetweenFlow(start, end).first()

        assertEquals(
            "getExpensesBetweenFlow must return full dataset (2500), not the former 2000 cap",
            2500,
            result.size
        )
    }

    @Test
    fun `getExpensesBetweenFlow delegates to uncapped DAO flow not bounded variant`() = runTest {
        val start = 1_690_000_000_000L
        val end = 1_700_000_000_000L

        every { expenseDao.getExpensesBetweenFlowUncapped(start, end) } returns flowOf(emptyList())

        repository.getExpensesBetweenFlow(start, end).first()

        verify { expenseDao.getExpensesBetweenFlowUncapped(start, end) }
        verify(exactly = 0) { expenseDao.getExpensesBetweenFlow(any(), any(), any()) }
    }

    // ── Paged variants are NOT affected ─────────────────────────────────────

    @Test
    fun `getExpensesBetweenPaged still uses bounded DAO method with explicit limit-offset`() = runTest {
        val start = 1_690_000_000_000L
        val end = 1_700_000_000_000L

        coEvery { expenseDao.getExpensesBetween(start, end, 100, 0) } returns generateExpenses(100)

        val result = repository.getExpensesBetweenPaged(start, end, 100, 0)

        assertEquals(100, result.size)
        coVerify { expenseDao.getExpensesBetween(start, end, 100, 0) }
    }

    // ── createDebugSnapshot uses uncapped DAO ───────────────────────────────

    @Test
    fun `createDebugSnapshot uses uncapped getAllUncapped not capped getAll`() = runTest {
        val allExpenses = generateExpenses(5000)
        coEvery { expenseDao.getAllUncapped() } returns allExpenses

        val snapshot = repository.createDebugSnapshot()

        assertEquals(5000, snapshot.expenses.size)
        coVerify { expenseDao.getAllUncapped() }
        coVerify(exactly = 0) { expenseDao.getAll(any()) }
    }
}
