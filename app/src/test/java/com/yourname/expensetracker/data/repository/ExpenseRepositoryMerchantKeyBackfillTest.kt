package com.yourname.expensetracker.data.repository

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(RestrictedExpenseDaoMutation::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExpenseRepositoryMerchantKeyBackfillTest {
    private lateinit var database: AppDatabase
    private lateinit var expenseDao: ExpenseDao
    private lateinit var writeBarrier: DatabaseWriteBarrier

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(ApplicationProvider.getApplicationContext()).build()
        expenseDao = database.expenseDao()
        writeBarrier = mockk(relaxed = true)
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun repository(dao: ExpenseDao = expenseDao) = ExpenseRepository(
        writeBarrier = writeBarrier,
        database = database,
        expenseDao = dao,
        userCorrectionDao = mockk(relaxed = true),
        pendingReviewDao = mockk(relaxed = true),
        merchantCategoryRepository = mockk(relaxed = true),
        merchantNormalizer = mockk(relaxed = true),
        transferDirectionAnalytics = mockk(relaxed = true),
        transactionLifecycleCoordinator = mockk(relaxed = true),
        debugExpenseAuditWriter = mockk(relaxed = true)
    )

    private suspend fun seed(merchant: String = "Original Merchant", key: String? = null): Expense {
        val expense = Expense(
            amount = 10.0,
            currency = "EUR",
            merchant = merchant,
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L,
            merchantKey = key
        )
        val id = expenseDao.insertAtomic(expense)
        assertTrue(id > 0)
        return expense.copy(id = id)
    }

    @Test
    fun fillsMissingKeyAndDoesNotOverwriteOnRetry() = runTest(timeout = 60.seconds) {
        val expense = seed()
        val key = MerchantKeyGenerator.generate(expense.merchant)
        val repository = repository()

        assertTrue(repository.updateMerchantKey(expense.id, key))
        assertFalse(repository.updateMerchantKey(expense.id, key))
        assertEquals(key, expenseDao.getById(expense.id)!!.merchantKey)
    }

    @Test
    fun staleWorkerKeyCannotBeAppliedToCurrentMerchant() = runTest(timeout = 60.seconds) {
        val expense = seed(merchant = "Changed Merchant")
        val staleKey = MerchantKeyGenerator.generate("Original Merchant")

        assertFalse(repository().updateMerchantKey(expense.id, staleKey))

        val current = expenseDao.getById(expense.id)!!
        assertEquals(expense.merchant, current.merchant)
        assertNull(current.merchantKey)
    }

    @Test
    fun existingMerchantKeyIsPreserved() = runTest(timeout = 60.seconds) {
        val expense = seed(key = "already-filled")

        assertFalse(repository().updateMerchantKey(expense.id, MerchantKeyGenerator.generate(expense.merchant)))

        assertEquals("already-filled", expenseDao.getById(expense.id)!!.merchantKey)
    }

    @Test
    fun deletedExpenseIsABenignNoOp() = runTest(timeout = 60.seconds) {
        assertFalse(repository().updateMerchantKey(Long.MAX_VALUE, "missing"))
    }

    @Test
    fun daoRejectsChangedMerchantEvenWhenKeyIsStillNull() = runTest(timeout = 60.seconds) {
        val expense = seed()
        expenseDao.update(expense.copy(merchant = "Changed Merchant"))

        assertEquals(0, expenseDao.updateMerchantKey(
            expense.id, MerchantKeyGenerator.generate(expense.merchant), expense.merchant
        ))
        assertNull(expenseDao.getById(expense.id)!!.merchantKey)
    }

    @Test
    fun daoDoesNotOverwriteKeyFilledByAnotherWriter() = runTest(timeout = 60.seconds) {
        val expense = seed()
        assertEquals(1, expenseDao.updateMerchantKey(expense.id, "first-key", expense.merchant))

        assertEquals(0, expenseDao.updateMerchantKey(expense.id, "stale-key", expense.merchant))

        assertEquals("first-key", expenseDao.getById(expense.id)!!.merchantKey)
    }

    @Test
    fun concurrentRenameBetweenRepositoryReadAndWriteLosesCas() = runTest(timeout = 60.seconds) {
        val expense = seed()
        val actualDao = expenseDao
        val racingDao = object : ExpenseDao by actualDao {
            override suspend fun updateMerchantKey(
                expenseId: Long, merchantKey: String, expectedMerchant: String
            ): Int {
                val current = requireNotNull(actualDao.getById(expenseId))
                actualDao.update(current.copy(merchant = "Concurrent Merchant"))
                return actualDao.updateMerchantKey(expenseId, merchantKey, expectedMerchant)
            }
        }

        assertFalse(repository(racingDao).updateMerchantKey(
            expense.id, MerchantKeyGenerator.generate(expense.merchant)
        ))

        val current = expenseDao.getById(expense.id)!!
        assertEquals("Concurrent Merchant", current.merchant)
        assertNull(current.merchantKey)
    }

    @Test
    fun barrierCancellationPropagatesBeforeReadingOrWriting() = runTest(timeout = 60.seconds) {
        val unreadDao = mockk<ExpenseDao>()
        val cancellation = CancellationException("TEST_CANCELLED")
        every { writeBarrier.checkWritesAllowed(any<String>()) } throws cancellation

        val thrown = assertFailsWith<CancellationException> {
            repository(unreadDao).updateMerchantKey(1L, "unused")
        }

        assertSame(cancellation, thrown)
        coVerify(exactly = 0) { unreadDao.getById(any()) }
        coVerify(exactly = 0) { unreadDao.updateMerchantKey(any(), any(), any()) }
    }
}
