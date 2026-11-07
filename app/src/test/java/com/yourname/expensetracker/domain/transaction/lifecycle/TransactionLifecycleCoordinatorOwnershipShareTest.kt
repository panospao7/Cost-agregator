package com.yourname.expensetracker.domain.transaction.lifecycle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.transaction.validation.DefaultTransactionDatePolicy
import com.yourname.expensetracker.domain.transaction.validation.TransactionValidator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * N-01-2: [TransactionLifecycleCoordinator.updateOwnership] rejects a share amount above the
 * STORED expense amount when share fields change, with the bounded code only. A call that
 * leaves the share fields unchanged is never rejected, even when the stored row already
 * exceeds its total (legacy data must stay editable for non-share fields).
 *
 * Real Room and a REAL [TransactionValidator]; peripheral collaborators are mocked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionLifecycleCoordinatorOwnershipShareTest {

    private lateinit var db: AppDatabase
    private lateinit var coordinator: TransactionLifecycleCoordinator

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        val timeProvider = FakeTimeProvider.forDate(2026, 5, 1)

        val writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true)
        coEvery {
            writeBarrier.runWrite(
                any<DatabaseAccessOperation>(),
                any<suspend () -> Any?>()
            )
        } coAnswers { secondArg<suspend () -> Any?>().invoke() }

        val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
            HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

        coordinator = TransactionLifecycleCoordinator(
            database = db,
            expenseDao = db.expenseDao(),
            transactionEventDao = db.transactionEventDao(),
            expenseRefundLinkDao = db.expenseRefundLinkDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = mockk(relaxed = true),
            runner = mockk(relaxed = true),
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            currencySettingsRepository = currencySettingsRepository,
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = TransactionValidator(timeProvider, DefaultTransactionDatePolicy()),
            diagnosticEventWriter = mockk(relaxed = true),
            receiptLinkService = com.yourname.expensetracker.testfixtures.noLinkedReceiptsLinkService()
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun seed(
        amount: Double,
        shared: Boolean = false,
        shareAmount: Double? = null
    ): Long {
        val id = db.expenseDao().insert(
            Expense(
                amount = amount,
                currency = "EUR",
                merchant = "Synthetic Cafe",
                merchantKey = "synthetic cafe",
                transactionType = TransactionType.PURCHASE,
                date = 1_777_000_000_000L,
                isSharedExpense = shared,
                sharedWithName = if (shared) "Anna" else null,
                myShareAmount = shareAmount
            )
        )
        assertTrue(id > 0L)
        return id
    }

    private suspend fun share(id: Long, name: String = "Anna", amount: Double?) =
        coordinator.updateOwnership(
            expenseId = id,
            isNotMine = false,
            ownerName = null,
            isSharedExpense = true,
            sharedWithName = name,
            mySharePercentage = null,
            myShareAmount = amount
        )

    @Test
    fun `N-01-2 share over stored amount is rejected with bounded code and writes nothing`() = runTest {
        val id = seed(amount = 40.0)

        try {
            share(id, amount = 40.01)
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals(TransactionValidator.OWNERSHIP_SHARE_EXCEEDS_TOTAL, e.message)
        }

        val after = db.expenseDao().getById(id)!!
        assertEquals(false, after.isSharedExpense)
        assertNull(after.myShareAmount)
        assertEquals(0, db.transactionEventDao().getEventsForExpense(id).size)
    }

    @Test
    fun `N-01-2 share equal to stored amount is accepted`() = runTest {
        val id = seed(amount = 40.0)

        share(id, amount = 40.0)

        val after = db.expenseDao().getById(id)!!
        assertEquals(true, after.isSharedExpense)
        assertEquals(40.0, after.myShareAmount!!, 0.0)
    }

    @Test
    fun `N-01-2 changing only a non share field on a legacy over total row is not rejected`() = runTest {
        // Legacy row already exceeds its total (seeded directly, bypassing validation).
        val id = seed(amount = 40.0, shared = true, shareAmount = 100.0)

        share(id, name = "Bob", amount = 100.0)

        val after = db.expenseDao().getById(id)!!
        assertEquals("Bob", after.sharedWithName)
        assertEquals(100.0, after.myShareAmount!!, 0.0)
    }

    @Test
    fun `N-01-2 fully unchanged call on a legacy over total row is not rejected`() = runTest {
        val id = seed(amount = 40.0, shared = true, shareAmount = 100.0)

        share(id, name = "Anna", amount = 100.0)

        val after = db.expenseDao().getById(id)!!
        assertEquals(100.0, after.myShareAmount!!, 0.0)
        assertEquals(0, db.transactionEventDao().getEventsForExpense(id).size)
    }

    @Test
    fun `N-01-2 changing the share on a legacy over total row to another over total value is rejected`() = runTest {
        val id = seed(amount = 40.0, shared = true, shareAmount = 100.0)

        try {
            share(id, amount = 90.0)
            fail("expected rejection")
        } catch (e: IllegalArgumentException) {
            assertEquals(TransactionValidator.OWNERSHIP_SHARE_EXCEEDS_TOTAL, e.message)
        }
        assertEquals(100.0, db.expenseDao().getById(id)!!.myShareAmount!!, 0.0)
    }
}
