package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.UserCorrection
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseRepositoryTest {

    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val userCorrectionDao = mockk<UserCorrectionDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)

    private lateinit var repository: ExpenseRepository

    @Before
    fun setup() {
        // Mock internal flow to avoid lateinit issues
        every { expenseDao.getAllFlow(any()) } returns flowOf(emptyList())

        repository = ExpenseRepository(
            expenseDao,
            userCorrectionDao,
            pendingReviewDao,
            merchantCategoryRepository,
            merchantNormalizer
        )
    }

    @Test
    fun `updateExpenseCategory updates category and records user correction`() = runTest {
        // Arrange
        val expenseId = 100L
        val originalCategoryId = 1L
        val newCategoryId = 2L
        
        val expense = Expense(
            id = expenseId,
            amount = 50.0,
            merchant = "Test Merchant",
            categoryId = originalCategoryId,
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis()
        )
        
        coEvery { expenseDao.updateCategory(expenseId, newCategoryId) } just runs
        
        // Act
        repository.updateExpenseCategory(expense, newCategoryId)
        
        // Assert
        coVerify { expenseDao.updateCategory(expenseId, newCategoryId) }
        coVerify { merchantCategoryRepository.learnPattern("Test Merchant", newCategoryId) }
        
        val correctionSlot = slot<UserCorrection>()
        coVerify { userCorrectionDao.insert(capture(correctionSlot)) }
        
        assertEquals("Test Merchant", correctionSlot.captured.originalMerchant)
        assertEquals(originalCategoryId, correctionSlot.captured.originalCategoryId)
        assertEquals(newCategoryId, correctionSlot.captured.correctedCategoryId)
        assertTrue(correctionSlot.captured.wasApproved)
    }

    @Test
    fun `updateExpenseMerchant updates merchant and learns alias`() = runTest {
        // Arrange
        val expenseId = 101L
        val oldMerchant = "Test Mrchnt"
        val newMerchant = "Test Merchant"
        
        val expense = Expense(
            id = expenseId,
            amount = 50.0,
            merchant = oldMerchant,
            categoryId = 1L,
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis()
        )
        
        coEvery { expenseDao.updateMerchant(expenseId, newMerchant) } just runs
        
        // Act
        repository.updateExpenseMerchant(expense, newMerchant)
        
        // Assert
        coVerify { expenseDao.updateMerchant(expenseId, newMerchant) }
        coVerify { expenseDao.updateMerchantKey(expenseId, MerchantKeyGenerator.generate(newMerchant)) }
        coVerify { merchantNormalizer.learnMerchantAlias(oldMerchant, newMerchant) }
        coVerify { merchantCategoryRepository.learnPattern(newMerchant, 1L) }
    }

    @Test
    fun `updateExpenseMerchant applyToAll updates merchant and key in bulk and pending reviews`() = runTest {
        val oldMerchant = "ΑΒ Βασιλόπουλος"
        val newMerchant = "AB Vassilopoulos"
        val expense = Expense(
            id = 202L,
            amount = 12.5,
            merchant = oldMerchant,
            categoryId = 3L,
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis()
        )

        repository.updateExpenseMerchant(expense, newMerchant, applyToAll = true)

        coVerify {
            expenseDao.updateMerchantForMerchant(
                MerchantKeyGenerator.generate(oldMerchant),
                newMerchant,
                MerchantKeyGenerator.generate(newMerchant)
            )
        }
        coVerify { pendingReviewDao.bulkRenameMerchant(oldMerchant, newMerchant) }
        coVerify(exactly = 0) { expenseDao.updateMerchant(expense.id, any()) }
        coVerify(exactly = 0) { expenseDao.updateMerchantKey(expense.id, any()) }
    }

    @Test
    fun `updateExpenseMerchant noops when merchant unchanged`() = runTest {
        val merchant = "Same Merchant"
        val expense = Expense(
            id = 303L,
            amount = 9.99,
            merchant = merchant,
            categoryId = 2L,
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis()
        )

        repository.updateExpenseMerchant(expense, merchant, applyToAll = false)

        coVerify(exactly = 0) { expenseDao.updateMerchant(any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateMerchantKey(any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateMerchantForMerchant(any(), any(), any()) }
        coVerify(exactly = 0) { pendingReviewDao.bulkRenameMerchant(any(), any()) }
        coVerify(exactly = 0) { merchantNormalizer.learnMerchantAlias(any(), any()) }
    }

    @Test
    fun `searchMerchants returns empty list for blank query`() = runTest {
        // Act
        val result = repository.searchMerchants("")
        
        // Assert
        assertEquals(emptyList<MerchantSuggestion>(), result)
        coVerify(exactly = 0) { expenseDao.searchMerchants(any()) }
    }

    @Test
    fun `getExpensesPagedDynamic constructs correct query with search and sort`() = runTest {
        // Arrange
        val querySlot = slot<androidx.sqlite.db.SupportSQLiteQuery>()
        coEvery { expenseDao.getExpensesDynamic(capture(querySlot)) } returns emptyList()
        
        // Act
        repository.getExpensesPagedDynamic(
            limit = 20,
            offset = 0,
            searchQuery = "Coffee",
            sortOrder = SortOrder.AMOUNT_DESC
        )
        
        // Assert
        val capturedQuery = querySlot.captured
        val sql = capturedQuery.sql
        
        assertTrue("Contains search clause", sql.contains("e.merchant LIKE ? OR e.categoryId IN (SELECT id FROM categories WHERE name LIKE ?)"))
        assertTrue("Contains sort clause", sql.contains("ORDER BY e.amount DESC"))
        assertTrue("Contains pagination", sql.contains("LIMIT ? OFFSET ?"))
    }
}
