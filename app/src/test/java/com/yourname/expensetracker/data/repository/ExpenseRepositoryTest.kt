package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.UserCorrection
import com.yourname.expensetracker.domain.analytics.TransferDirectionAnalytics
import com.yourname.expensetracker.domain.intelligence.ml.MerchantNormalizer
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import androidx.room.withTransaction
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
class ExpenseRepositoryTest {

    private val database = mockk<AppDatabase>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val userCorrectionDao = mockk<UserCorrectionDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val merchantCategoryRepository = mockk<MerchantCategoryRepository>(relaxed = true)
    private val merchantNormalizer = mockk<MerchantNormalizer>(relaxed = true)
    private val transferDirectionAnalytics = mockk<TransferDirectionAnalytics>(relaxed = true)
    private val transactionLifecycleCoordinator = mockk<TransactionLifecycleCoordinator>(relaxed = true)
    private val writeBarrier = mockk<com.yourname.expensetracker.data.backup.DatabaseWriteBarrier>(relaxed = true)

    private lateinit var repository: ExpenseRepository

    @Before
    fun setup() {
        // Mock internal flow to avoid lateinit issues
        // A.9: repository now calls the uncapped Flow variant
        every { expenseDao.getAllFlowUncapped() } returns flowOf(emptyList())

        // Mock Room withTransaction to run the block on the test coroutine (avoids Dispatchers.IO leak)
        mockkStatic("androidx.room.RoomDatabaseKt")
        val transactionBlock = slot<suspend () -> Any?>()
        coEvery { database.withTransaction(capture(transactionBlock)) } coAnswers {
            transactionBlock.captured.invoke()
        }

        repository = ExpenseRepository(
            writeBarrier,
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
        date = 1_700_000_000_000L
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
        date = 1_700_000_000_000L
    )

coEvery { expenseDao.updateMerchantAndKey(expenseId, newMerchant, MerchantKeyGenerator.generate(newMerchant), any()) } just runs

        // Act
        repository.updateExpenseMerchant(expense, newMerchant)

        // Assert
        coVerify { expenseDao.updateMerchantAndKey(expenseId, newMerchant, MerchantKeyGenerator.generate(newMerchant), any()) }
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
        date = 1_700_000_000_000L
    )

    repository.updateExpenseMerchant(expense, newMerchant, applyToAll = true)

        coVerify {
            expenseDao.updateMerchantForMerchant(
                MerchantKeyGenerator.generate(oldMerchant),
                newMerchant,
                MerchantKeyGenerator.generate(newMerchant)
            )
        }
        coVerify {
            pendingReviewDao.bulkRenameMerchant(
                MerchantKeyGenerator.generate(oldMerchant),
                oldMerchant,
                newMerchant,
                MerchantKeyGenerator.generate(newMerchant)
            )
        }
        coVerify(exactly = 0) { expenseDao.updateMerchantAndKey(expense.id, any(), any(), any()) }
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
        date = 1_700_000_000_000L
    )

    repository.updateExpenseMerchant(expense, merchant, applyToAll = false)

        coVerify(exactly = 0) { expenseDao.updateMerchantAndKey(any(), any(), any(), any()) }
        coVerify(exactly = 0) { expenseDao.updateMerchantForMerchant(any(), any(), any()) }
        coVerify(exactly = 0) { pendingReviewDao.bulkRenameMerchant(any(), any(), any(), any()) }
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
        // A.1 changed AMOUNT_DESC to use the effective-amount CASE expression,
        // so we assert the expression appears somewhere in the ORDER BY clause.
        assertTrue("Contains sort clause with effective-amount expression",
            sql.contains("ORDER BY") && sql.contains("DESC"))
        assertTrue("Contains pagination", sql.contains("LIMIT ? OFFSET ?"))
    }

    // ── B.4 / ISSUE-1 regression tests ────────────────────────────────────────
    // Verify that getExpensesPagedDynamic() projects the full expense row (e.*)
    // so that newer fields like isBusinessExpense and splitTemplateId are never
    // silently dropped to their default values.

    /**
     * The generated SQL must use "SELECT e.*" so that newer Expense columns
     * (isBusinessExpense, splitTemplateId, etc.) are always included.
     * A partial explicit projection would cause Room to silently default-fill
     * the missing fields, corrupting persisted business/split data.
     */
    @Test
    fun `getExpensesPagedDynamic SQL uses SELECT e-star so newer fields are not dropped`() = runTest {
        val querySlot = slot<androidx.sqlite.db.SupportSQLiteQuery>()
        coEvery { expenseDao.getExpensesDynamic(capture(querySlot)) } returns emptyList()

        repository.getExpensesPagedDynamic(limit = 10, offset = 0)

        val sql = querySlot.captured.sql
        // Must contain the wildcard projection — NOT a hand-enumerated column list.
        assertTrue(
            "SQL must use 'SELECT e.*' to project the full expense row; " +
            "a partial column list would silently drop isBusinessExpense, splitTemplateId, etc.",
            sql.contains("SELECT e.*")
        )
        // Sanity: must not contain any of the fields that were previously
        // hard-coded and that caused the regression in the first place.
        assertFalse(
            "SQL must not fall back to an explicit column enumeration",
            sql.contains("e.isManualEntry") || sql.contains("e.merchantKey,") || sql.contains("e.merchantKey\n")
        )
    }

    /**
     * When the DAO returns an expense with isBusinessExpense=true and a
     * non-null splitTemplateId, getExpensesPagedDynamic() must surface those
     * values unchanged — i.e. the repository layer must not zero/null them out.
     */
    @Test
    fun `getExpensesPagedDynamic preserves isBusinessExpense and splitTemplateId from DAO result`() = runTest {
        // Arrange: build a stub ExpenseWithCategory that carries the newer fields.
        val newerFieldsExpense = Expense(
            id = 999L,
            amount = 250.0,
            currency = "EUR",
            merchant = "Acme Corp",
            transactionType = TransactionType.PURCHASE,
        date = 1_700_000_000_000L,
        isBusinessExpense = true,
            businessPurpose = "Client dinner",
            splitTemplateId = 7L,
            splitVisualization = """{"segments":[]}"""
        )
        val stubResult = listOf(
            ExpenseWithCategory(
                expense = newerFieldsExpense,
                category = null
            )
        )
        coEvery { expenseDao.getExpensesDynamic(any()) } returns stubResult

        // Act
        val results = repository.getExpensesPagedDynamic(limit = 10, offset = 0)

        // Assert: the repository must return the DAO result without stripping fields.
        assertEquals(1, results.size)
        val returned = results[0].expense
        assertTrue(
            "isBusinessExpense must be preserved through getExpensesPagedDynamic",
            returned.isBusinessExpense
        )
        assertEquals(
            "splitTemplateId must be preserved through getExpensesPagedDynamic",
            7L, returned.splitTemplateId
        )
        assertEquals(
            "businessPurpose must be preserved through getExpensesPagedDynamic",
            "Client dinner", returned.businessPurpose
        )
        assertEquals(
            "splitVisualization must be preserved through getExpensesPagedDynamic",
            """{"segments":[]}""", returned.splitVisualization
        )
    }

    @Test
    fun `assistant filtered helpers keep multi value list and count filters in sync`() = runTest {
        val listQuerySlot = slot<androidx.sqlite.db.SupportSQLiteQuery>()
        val countQuerySlot = slot<androidx.sqlite.db.SupportSQLiteQuery>()
        coEvery { expenseDao.getAssistantExpensesDynamic(capture(listQuerySlot)) } returns emptyList()
        coEvery { expenseDao.getAssistantExpenseCountDynamic(capture(countQuerySlot)) } returns 0

        repository.getAssistantExpensesFiltered(
            startDate = 100L,
            endDate = 200L,
            transactionTypes = setOf(TransactionType.PURCHASE, TransactionType.WITHDRAWAL),
            categoryIds = setOf(1L, 2L),
            merchantNames = setOf("Lidl", "Shell"),
            ownershipFilter = OwnershipFilter.SHARED,
            minAmount = 5.0,
            maxAmount = 50.0
        )
        repository.getAssistantExpenseCountFiltered(
            startDate = 100L,
            endDate = 200L,
            transactionTypes = setOf(TransactionType.PURCHASE, TransactionType.WITHDRAWAL),
            categoryIds = setOf(1L, 2L),
            merchantNames = setOf("Lidl", "Shell"),
            ownershipFilter = OwnershipFilter.SHARED,
            minAmount = 5.0,
            maxAmount = 50.0
        )

        val listSql = listQuerySlot.captured.sql
        val countSql = countQuerySlot.captured.sql

        assertTrue(listSql.contains("e.transactionType IN (?, ?)"))
        assertTrue(countSql.contains("e.transactionType IN (?, ?)"))
        assertTrue(listSql.contains("e.categoryId IN (?, ?)"))
        assertTrue(countSql.contains("e.categoryId IN (?, ?)"))
        assertTrue(listSql.contains("e.merchantKey IN (?, ?)"))
        assertTrue(countSql.contains("e.merchantKey IN (?, ?)"))
        assertTrue(listSql.contains("e.isSharedExpense = 1"))
        assertTrue(countSql.contains("e.isSharedExpense = 1"))
        assertTrue(listSql.contains("LIMIT") .not())
        assertTrue(countSql.contains("ORDER BY").not())
    }
}
