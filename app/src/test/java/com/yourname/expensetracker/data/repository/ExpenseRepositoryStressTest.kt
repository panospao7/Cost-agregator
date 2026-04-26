package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.MerchantCategoryRepository
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import androidx.room.withTransaction
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@Ignore("Stress test: may hang in CI, run manually")
class ExpenseRepositoryStressTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val userCorrectionDao = mockk<UserCorrectionDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val transferDirectionAnalytics = mockk<TransferDirectionAnalytics>(relaxed = true)

    private lateinit var repository: ExpenseRepository

    @Before
    fun setup() {
        coEvery { expenseDao.insertAtomic(any()) } returns 1L
        coEvery { expenseDao.delete(any()) } returns Unit
        coEvery { expenseDao.deleteAll() } returns Unit
        coEvery { expenseDao.getAllFlow(any()) } returns MutableStateFlow(emptyList())
        coEvery { expenseDao.getAllWithCategoryFlow(any()) } returns MutableStateFlow(emptyList())
        coEvery { userCorrectionDao.insert(any()) } returns 1L
        coEvery { pendingReviewDao.bulkRenameMerchant(any(), any(), any(), any()) } returns Unit

        // Mock Room withTransaction to run the block on the test coroutine (avoids Dispatchers.IO leak)
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
            transferDirectionAnalytics
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    // ============================================================================
    // SECTION 1: SEARCH AND QUERY EDGE CASES
    // ============================================================================

    @Test
    fun `stress - searchMerchants with empty query returns empty`() = runTest {
        val result = repository.searchMerchants("")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `stress - searchMerchants with blank query returns empty`() = runTest {
        val result = repository.searchMerchants("   ")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `stress - searchMerchants with valid query returns results`() = runTest {
        val suggestions = listOf(
            MerchantSuggestion(merchant = "Amazon", categoryId = 1, avgAmount = 50.0, txCount = 10)
        )
        coEvery { expenseDao.searchMerchants(any()) } returns suggestions

        val result = repository.searchMerchants("Amazon")
        assertEquals(1, result.size)
    }

    // ============================================================================
    // SECTION 2: UPDATE EXPENSE CATEGORY
    // ============================================================================

    @Test
    fun `stress - updateExpenseCategory with valid expense`() = runTest {
        val expense = Expense(
            id = 1,
            amount = 50.0,
            currency = "EUR",
            merchant = "Test",
            merchantKey = "test",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            categoryId = 1,
            createdAt = System.currentTimeMillis()
        )

        coEvery { expenseDao.updateCategory(any(), any()) } returns Unit
        coEvery { merchantCategoryRepository.learnPattern(any(), any()) } returns Unit

        repository.updateExpenseCategory(expense, 2)

        coVerify { merchantCategoryRepository.learnPattern("Test", 2) }
    }

    // ============================================================================
    // SECTION 3: BULK UPDATE OPERATIONS
    // ============================================================================

    @Test
    fun `stress - updateExpenseCategoryBulk for many merchants`() = runTest {
        coEvery { expenseDao.updateCategoryForMerchant(any(), any()) } returns Unit
        coEvery { merchantCategoryRepository.learnPattern(any(), any()) } returns Unit
        coEvery { userCorrectionDao.insert(any()) } returns 1L

        repeat(100) { i ->
            repository.updateExpenseCategoryBulk("Merchant $i", 1)
        }
    }

    @Test
    fun `stress - updateExpenseMerchantBulk for many merchants`() = runTest {
        coEvery { expenseDao.updateMerchantForMerchant(any(), any(), any()) } returns Unit
        coEvery { merchantNormalizer.learnMerchantAlias(any(), any()) } returns Unit

        repeat(50) { i ->
            repository.updateExpenseMerchantBulk("Old Merchant $i", "New Merchant $i")
        }
    }

    // ============================================================================
    // SECTION 4: TRANSACTION TYPE UPDATES
    // ============================================================================

    @Test
    fun `stress - updateExpenseType with same type does nothing`() = runTest {
        val expense = Expense(
            id = 1,
            amount = 50.0,
            currency = "EUR",
            merchant = "Test",
            merchantKey = "test",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            categoryId = 1,
            createdAt = System.currentTimeMillis()
        )

        repository.updateExpenseType(expense, TransactionType.PURCHASE)

        coVerify(exactly = 0) { expenseDao.updateTransactionType(any(), any(), any()) }
    }

    @Test
    fun `stress - updateExpenseType with different type updates`() = runTest {
        val expense = Expense(
            id = 1,
            amount = 50.0,
            currency = "EUR",
            merchant = "Test",
            merchantKey = "test",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            categoryId = 1,
            createdAt = System.currentTimeMillis()
        )

        coEvery { expenseDao.updateTransactionType(any(), any(), any()) } returns Unit

        repository.updateExpenseType(expense, TransactionType.TRANSFER)

        coVerify { expenseDao.updateTransactionType(1, "TRANSFER", any()) }
    }

    // ============================================================================
    // SECTION 5: DELETE OPERATIONS
    // ============================================================================

    @Test
    fun `stress - deleteExpense with valid expense`() = runTest {
        val expense = Expense(
            id = 1,
            amount = 50.0,
            currency = "EUR",
            merchant = "Test",
            merchantKey = "test",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            categoryId = 1,
            createdAt = System.currentTimeMillis()
        )

        repository.deleteExpense(expense)

        coVerify { expenseDao.delete(expense) }
    }

    @Test
    fun `stress - deleteAllExpenses clears database`() = runTest {
        repository.deleteAllExpenses()

        coVerify { expenseDao.deleteAll() }
    }

    // ============================================================================
    // SECTION 6: PAGINATED QUERIES
    // ============================================================================

    @Test
    fun `stress - getExpensesPaged with various page sizes`() = runTest {
        coEvery { expenseDao.getExpensesWithCategoryPaged(any(), any()) } returns emptyList()

        repository.getExpensesPaged(10, 0)
        repository.getExpensesPaged(50, 0)
        repository.getExpensesPaged(100, 0)
        repository.getExpensesPaged(500, 0)
    }

    // ============================================================================
    // SECTION 7: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - getRecentMerchantNames returns list`() = runTest {
        coEvery { expenseDao.getRecentMerchantNames() } returns listOf("Amazon", "Netflix", "Spotify")

        val result = repository.getRecentMerchantNames()
        assertEquals(3, result.size)
    }

    @Test
    fun `stress - getCountForPeriod returns zero for empty range`() = runTest {
        coEvery { expenseDao.getCountForPeriod(any(), any()) } returns 0

        val result = repository.getCountForPeriod(0, System.currentTimeMillis())
        assertEquals(0, result)
    }
}
