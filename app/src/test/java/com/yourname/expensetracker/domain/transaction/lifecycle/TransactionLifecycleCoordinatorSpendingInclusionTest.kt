package com.yourname.expensetracker.domain.transaction.lifecycle

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.LifecycleEventType
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Loan-installments S3: the TLC spending-inclusion op (INV-2), the INV-22 purchase lock
 * (AE-20), flagged rows staying duplicate BLOCKING targets (INV-8, AE-6 replay part) and the
 * A10 stale-save regression. Real in-memory Room database and the real coordinator; side-effect
 * collaborators are mocked (same harness as TransactionLifecycleCoordinatorGroupLinkedGuardTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TransactionLifecycleCoordinatorSpendingInclusionTest {

    private lateinit var db: AppDatabase
    private lateinit var timeProvider: FakeTimeProvider
    private lateinit var planner: TransactionSideEffectPlanner
    private lateinit var coordinator: TransactionLifecycleCoordinator
    private var shoppingCategoryId: Long = 0L

    @Before
    fun setUp() = runBlocking {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = AppDatabaseTestFactory.create(context)
        timeProvider = FakeTimeProvider.forDate(2026, 5, 1)

        shoppingCategoryId = db.categoryDao().insert(
            Category(name = "Shopping", icon = "S", color = "#33FF57")
        )

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

        planner = mockk(relaxed = true)

        coordinator = TransactionLifecycleCoordinator(
            database = db,
            expenseDao = db.expenseDao(),
            transactionEventDao = db.transactionEventDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = planner,
            runner = mockk(relaxed = true),
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = writeBarrier,
            currencySettingsRepository = currencySettingsRepository,
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun seedPurchase(merchant: String = "Electronics Shop", amount: Double = 600.0): Long {
        val id = db.expenseDao().insert(
            Expense(
                amount = amount,
                currency = "EUR",
                merchant = merchant,
                merchantKey = merchant.lowercase(),
                transactionType = TransactionType.PURCHASE,
                date = timeProvider.now()
            )
        )
        assertTrue(id > 0L)
        return id
    }

    private suspend fun seedFlaggedPurchase(
        reason: SpendingExclusionReason = SpendingExclusionReason.INSTALLMENT_PURCHASE
    ): Long {
        val id = seedPurchase()
        val result = coordinator.setSpendingExclusionDbOnly(id, reason)
        assertTrue(result.value is SpendingInclusionResult.Updated)
        return id
    }

    private suspend fun events(expenseId: Long) =
        db.transactionEventDao().getEventsForExpense(expenseId)

    private suspend fun flag(expenseId: Long): String? =
        db.expenseDao().getById(expenseId)!!.spendingExclusionReason

    private suspend fun assertLocked(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected InstallmentLockedExpenseException")
        } catch (e: InstallmentLockedExpenseException) {
            assertEquals(
                InstallmentLockedExpenseException.EXPENSE_LOCKED_BY_INSTALLMENT_PLAN,
                e.code
            )
            assertEquals("EXPENSE_LOCKED_BY_INSTALLMENT_PLAN", e.message)
        }
    }

    // ── set / clear / event kind (INV-2) ────────────────────────────────────

    @Test
    fun `set writes the flag and one UPDATED event with the SPENDING_INCLUSION kind`() = runTest {
        val id = seedPurchase()

        val mutation = coordinator.setSpendingExclusionDbOnly(
            id,
            SpendingExclusionReason.INSTALLMENT_PURCHASE,
            correlationId = "corr-1"
        )

        assertEquals(SpendingInclusionResult.Updated(id), mutation.value)
        assertEquals("INSTALLMENT_PURCHASE", flag(id))
        val written = events(id)
        assertEquals(1, written.size)
        val event = written.single()
        assertEquals(LifecycleEventType.UPDATED.name, event.eventType)
        assertEquals(ExpenseSource.INSTALLMENT_PLAN.name, event.source)
        assertEquals("INSTALLMENT_PURCHASE", event.reason)
        assertEquals("corr-1", event.correlationId)
        assertTrue(event.metadata!!.contains("SPENDING_INCLUSION"))
        verify(exactly = 1) {
            planner.planUpdated(id, ExpenseSource.INSTALLMENT_PLAN.name, "corr-1", TransactionUpdateKind.SPENDING_INCLUSION)
        }
    }

    @Test
    fun `event carries only bounded flag data and no amount merchant or notes`() = runTest {
        val id = seedPurchase(merchant = "Very Private Merchant")

        coordinator.setSpendingExclusionDbOnly(id, SpendingExclusionReason.INSTALLMENT_PURCHASE)

        val event = events(id).single()
        val text = listOfNotNull(event.beforeSnapshot, event.afterSnapshot, event.metadata, event.reason)
            .joinToString(" ")
        assertFalse(text.contains("Very Private Merchant"))
        assertFalse(text.contains("600"))
        assertTrue(event.afterSnapshot!!.contains("INSTALLMENT_PURCHASE"))
    }

    @Test
    fun `the event row keeps the expense dedupeKey like every UPDATED event`() = runTest {
        val id = db.expenseDao().insert(
            Expense(
                amount = 600.0,
                currency = "EUR",
                merchant = "Electronics Shop",
                merchantKey = "electronics shop",
                transactionType = TransactionType.PURCHASE,
                date = timeProvider.now(),
                dedupeKey = "dedupe-key-for-pin"
            )
        )

        coordinator.setSpendingExclusionDbOnly(id, SpendingExclusionReason.INSTALLMENT_PURCHASE)

        assertEquals("dedupe-key-for-pin", events(id).single().dedupeKey)
    }

    @Test
    fun `clear removes the flag and writes a second UPDATED event`() = runTest {
        val id = seedFlaggedPurchase()

        val mutation = coordinator.setSpendingExclusionDbOnly(id, null)

        assertEquals(SpendingInclusionResult.Updated(id), mutation.value)
        assertNull(flag(id))
        val written = events(id)
        assertEquals(2, written.size)
        assertTrue(written.all { it.eventType == LifecycleEventType.UPDATED.name })
        // One planUpdated per mutation: the set in seedFlaggedPurchase plus this clear.
        verify(exactly = 2) {
            planner.planUpdated(id, any(), any(), TransactionUpdateKind.SPENDING_INCLUSION)
        }
    }

    @Test
    fun `reversal flag replaces the purchase flag`() = runTest {
        val id = seedFlaggedPurchase(SpendingExclusionReason.INSTALLMENT_PURCHASE)

        val mutation = coordinator.setSpendingExclusionDbOnly(
            id,
            SpendingExclusionReason.INSTALLMENT_REVERSED
        )

        assertEquals(SpendingInclusionResult.Updated(id), mutation.value)
        assertEquals("INSTALLMENT_REVERSED", flag(id))
    }

    @Test
    fun `setting the current value is a NoOp without a write or an event`() = runTest {
        val id = seedFlaggedPurchase()
        val eventsBefore = events(id).size

        val same = coordinator.setSpendingExclusionDbOnly(id, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        assertEquals(SpendingInclusionResult.NoOp, same.value)

        val unflagged = seedPurchase(merchant = "Other Shop")
        val clearNothing = coordinator.setSpendingExclusionDbOnly(unflagged, null)
        assertEquals(SpendingInclusionResult.NoOp, clearNothing.value)

        assertEquals(eventsBefore, events(id).size)
        assertEquals(0, events(unflagged).size)
    }

    @Test
    fun `a missing expense is NotFound and writes nothing`() = runTest {
        val result = coordinator.setSpendingExclusionDbOnly(
            9_999L,
            SpendingExclusionReason.INSTALLMENT_PURCHASE
        )

        assertEquals(SpendingInclusionResult.NotFound, result.value)
        assertEquals(0, events(9_999L).size)
    }

    // ── D-10: inside a caller-held transaction ──────────────────────────────

    @Test
    fun `the op joins a caller held transaction and commits with it`() = runTest {
        val id = seedPurchase()

        val mutation = db.withTransaction {
            coordinator.setSpendingExclusionDbOnly(id, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        }

        assertEquals(SpendingInclusionResult.Updated(id), mutation.value)
        assertEquals("INSTALLMENT_PURCHASE", flag(id))
        assertEquals(1, events(id).size)
    }

    @Test
    fun `a rollback of the caller transaction also rolls back the flag and the event`() = runTest {
        val id = seedPurchase()

        try {
            db.withTransaction {
                coordinator.setSpendingExclusionDbOnly(id, SpendingExclusionReason.INSTALLMENT_PURCHASE)
                throw IllegalStateException("injected failure after the flag write")
            }
            fail("Expected the injected failure")
        } catch (expected: IllegalStateException) {
            assertEquals("injected failure after the flag write", expected.message)
        }

        assertNull(flag(id))
        assertEquals(0, events(id).size)
    }

    @Test
    fun `a NotFound or NoOp inside a caller transaction does not roll the caller back`() = runTest {
        val id = seedPurchase()

        db.withTransaction {
            val missing = coordinator.setSpendingExclusionDbOnly(
                9_999L,
                SpendingExclusionReason.INSTALLMENT_PURCHASE
            )
            assertEquals(SpendingInclusionResult.NotFound, missing.value)
            val noOp = coordinator.setSpendingExclusionDbOnly(id, null)
            assertEquals(SpendingInclusionResult.NoOp, noOp.value)
            coordinator.setSpendingExclusionDbOnly(id, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        }

        assertEquals("INSTALLMENT_PURCHASE", flag(id))
    }

    @Test
    fun `a real write barrier allows the nested runWrite inside a caller held write scope`() = runTest {
        val id = seedPurchase()
        val maintenanceMode = mockk<RestoreMaintenanceMode> {
            every { currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
            every { isWritesAllowed() } returns true
        }
        val realBarrier = DatabaseWriteBarrier(maintenanceMode)
        val realCoordinator = TransactionLifecycleCoordinator(
            database = db,
            expenseDao = db.expenseDao(),
            transactionEventDao = db.transactionEventDao(),
            timeProvider = timeProvider,
            currencyConverter = mockk(relaxed = true),
            sideEffectDispatcher = mockk(relaxed = true),
            planner = planner,
            runner = mockk(relaxed = true),
            recurringLifecycleCoordinator = mockk(relaxed = true),
            writeBarrier = realBarrier,
            currencySettingsRepository = mockk(relaxed = true),
            sourceLinkWriter = mockk(relaxed = true),
            transactionValidator = mockk(relaxed = true),
            diagnosticEventWriter = mockk(relaxed = true)
        )

        val mutation = realBarrier.runWrite(DatabaseAccessOperation("SpendingInclusionTest.outer")) {
            db.withTransaction {
                realCoordinator.setSpendingExclusionDbOnly(
                    id,
                    SpendingExclusionReason.INSTALLMENT_PURCHASE
                )
            }
        }

        assertEquals(SpendingInclusionResult.Updated(id), mutation.value)
        assertEquals("INSTALLMENT_PURCHASE", flag(id))
        assertEquals(1, events(id).size)
        assertEquals(0, realBarrier.inFlightWriteCount())
    }

    // ── AE-20 / INV-22 purchase lock ────────────────────────────────────────

    @Test
    fun `AE-20 an amount edit of a flagged row is refused and nothing changes`() = runTest {
        val id = seedFlaggedPurchase()
        val flagged = db.expenseDao().getById(id)!!
        val eventsBefore = events(id).size

        assertLocked { coordinator.updateExpense(flagged.copy(amount = 650.0)) }

        assertEquals(600.0, db.expenseDao().getById(id)!!.amount, 0.0)
        assertEquals(eventsBefore, events(id).size)
    }

    @Test
    fun `AE-20 currency and type edits of a flagged row are refused`() = runTest {
        val id = seedFlaggedPurchase()
        val flagged = db.expenseDao().getById(id)!!
        val eventsBefore = events(id).size

        assertLocked { coordinator.updateExpense(flagged.copy(currency = "USD")) }
        assertLocked { coordinator.updateExpense(flagged.copy(transactionType = TransactionType.DEPOSIT)) }
        assertLocked { coordinator.updateType(id, TransactionType.DEPOSIT) }

        val after = db.expenseDao().getById(id)!!
        assertEquals("EUR", after.currency)
        assertEquals(TransactionType.PURCHASE, after.transactionType)
        assertEquals(eventsBefore, events(id).size)
    }

    @Test
    fun `AE-20 marking a flagged row not-mine or shared is refused`() = runTest {
        val id = seedFlaggedPurchase()
        val flagged = db.expenseDao().getById(id)!!
        val eventsBefore = events(id).size

        assertLocked {
            coordinator.updateOwnership(
                expenseId = id,
                isNotMine = true,
                ownerName = null,
                isSharedExpense = false,
                sharedWithName = null,
                mySharePercentage = null,
                myShareAmount = null
            )
        }
        assertLocked { coordinator.updateExpense(flagged.copy(isNotMine = true)) }
        assertLocked {
            coordinator.updateExpense(flagged.copy(isSharedExpense = true, myShareAmount = 100.0))
        }

        val after = db.expenseDao().getById(id)!!
        assertFalse(after.isNotMine)
        assertFalse(after.isSharedExpense)
        assertEquals(eventsBefore, events(id).size)
    }

    @Test
    fun `AE-20 updateTypeAndTransferDetails refuses a type change of a flagged row`() = runTest {
        val id = seedFlaggedPurchase()
        val eventsBefore = events(id).size

        assertLocked {
            coordinator.updateTypeAndTransferDetails(
                expenseId = id,
                newType = TransactionType.DEPOSIT,
                transferDirection = null,
                transferAccountName = null
            )
        }

        assertEquals(TransactionType.PURCHASE, db.expenseDao().getById(id)!!.transactionType)
        assertEquals(eventsBefore, events(id).size)
    }

    @Test
    fun `AE-20 updateOwnershipDbOnlyV2 refuses shared on a flagged row`() = runTest {
        val id = seedFlaggedPurchase()
        val eventsBefore = events(id).size

        assertLocked {
            coordinator.updateOwnershipDbOnlyV2(
                expenseId = id,
                isNotMine = false,
                ownerName = null,
                isSharedExpense = true,
                sharedWithName = "Friend",
                mySharePercentage = 50,
                myShareAmount = null
            )
        }

        assertFalse(db.expenseDao().getById(id)!!.isSharedExpense)
        assertEquals(eventsBefore, events(id).size)
    }

    @Test
    fun `AE-20 a name-only ownership edit of a flagged row is accepted on both paths`() = runTest {
        val id = db.expenseDao().insert(
            Expense(
                amount = 600.0,
                currency = "EUR",
                merchant = "Electronics Shop",
                merchantKey = "electronics shop",
                transactionType = TransactionType.PURCHASE,
                date = timeProvider.now(),
                isNotMine = true,
                ownerName = "Alice"
            )
        )
        coordinator.setSpendingExclusionDbOnly(id, SpendingExclusionReason.INSTALLMENT_PURCHASE)

        val viaV2 = coordinator.updateOwnershipDbOnlyV2(
            expenseId = id,
            isNotMine = true,
            ownerName = "Bob",
            isSharedExpense = false,
            sharedWithName = null,
            mySharePercentage = null,
            myShareAmount = null
        )
        assertTrue(viaV2.value is OwnershipUpdateResult.Updated)
        assertEquals("Bob", db.expenseDao().getById(id)!!.ownerName)

        coordinator.updateExpense(db.expenseDao().getById(id)!!.copy(ownerName = "Carol"))

        val after = db.expenseDao().getById(id)!!
        assertEquals("Carol", after.ownerName)
        assertTrue(after.isNotMine)
        assertEquals("INSTALLMENT_PURCHASE", after.spendingExclusionReason)
        assertEquals(600.0, after.amount, 0.0)
    }

    @Test
    fun `AE-20 a merchant edit of a flagged row is accepted and the flag survives`() = runTest {
        val id = seedFlaggedPurchase()

        coordinator.updateMerchant(id, "Renamed Shop")

        val after = db.expenseDao().getById(id)!!
        assertEquals("Renamed Shop", after.merchant)
        assertEquals("INSTALLMENT_PURCHASE", after.spendingExclusionReason)
        assertEquals(600.0, after.amount, 0.0)
    }

    @Test
    fun `AE-20 a category edit of a flagged row is accepted and the flag survives`() = runTest {
        val id = seedFlaggedPurchase()
        val flagged = db.expenseDao().getById(id)!!

        coordinator.updateCategory(id, shoppingCategoryId)
        assertEquals(shoppingCategoryId, db.expenseDao().getById(id)!!.categoryId)

        coordinator.updateExpense(flagged.copy(categoryId = null))
        val after = db.expenseDao().getById(id)!!
        assertNull(after.categoryId)
        assertEquals("INSTALLMENT_PURCHASE", after.spendingExclusionReason)
    }

    @Test
    fun `an unflagged row keeps every edit path unchanged`() = runTest {
        val id = seedPurchase()
        val plain = db.expenseDao().getById(id)!!

        coordinator.updateExpense(plain.copy(amount = 650.0))
        assertEquals(650.0, db.expenseDao().getById(id)!!.amount, 0.0)

        coordinator.updateType(id, TransactionType.DEPOSIT)
        assertEquals(TransactionType.DEPOSIT, db.expenseDao().getById(id)!!.transactionType)
        assertNull(flag(id))
    }

    @Test
    fun `the lock ends when the flag is cleared`() = runTest {
        val id = seedFlaggedPurchase()
        coordinator.setSpendingExclusionDbOnly(id, null)
        val plain = db.expenseDao().getById(id)!!

        coordinator.updateExpense(plain.copy(amount = 650.0))

        assertEquals(650.0, db.expenseDao().getById(id)!!.amount, 0.0)
    }

    // ── A10: stale editor save must not reset the flag ──────────────────────

    @Test
    fun `A10 a stale editor copy with a null reason does not reset the flag`() = runTest {
        val id = seedPurchase()
        val staleCopy = db.expenseDao().getById(id)!!
        assertNull(staleCopy.spendingExclusionReason)

        coordinator.setSpendingExclusionDbOnly(id, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        coordinator.updateExpense(staleCopy.copy(notes = "edited after the flag was set"))

        val after = db.expenseDao().getById(id)!!
        assertEquals("INSTALLMENT_PURCHASE", after.spendingExclusionReason)
        assertEquals("edited after the flag was set", after.notes)
        assertEquals(600.0, after.amount, 0.0)
    }

    @Test
    fun `A10 a stale editor copy with an old reason does not restore a cleared flag`() = runTest {
        val id = seedFlaggedPurchase()
        val staleCopy = db.expenseDao().getById(id)!!
        assertEquals("INSTALLMENT_PURCHASE", staleCopy.spendingExclusionReason)

        coordinator.setSpendingExclusionDbOnly(id, null)
        coordinator.updateExpense(staleCopy.copy(notes = "saved from a stale editor"))

        val after = db.expenseDao().getById(id)!!
        assertNull(after.spendingExclusionReason)
        assertEquals("saved from a stale editor", after.notes)
    }

    // ── INV-8 / AE-6: a flagged purchase stays a duplicate BLOCKING target ──

    @Test
    fun `AE-6 a replayed capture of a flagged purchase is still blocked as a duplicate`() = runTest {
        val request = CreateExpenseRequest(
            merchant = "SKLAVENITIS",
            amount = 600.0,
            currency = "EUR",
            date = timeProvider.now(),
            transactionType = TransactionType.PURCHASE,
            source = ExpenseSource.MANUAL_ENTRY,
            isManualEntry = true
        )
        val first = coordinator.createExpenseDbOnlyV2(request).value
        assertTrue("Expected Created, got $first", first is CreateExpenseResult.Created)
        val purchaseId = (first as CreateExpenseResult.Created).expenseId

        coordinator.setSpendingExclusionDbOnly(purchaseId, SpendingExclusionReason.INSTALLMENT_PURCHASE)
        assertNotNull(flag(purchaseId))

        val replay = coordinator.createExpenseDbOnlyV2(request).value

        assertTrue("Expected DuplicateSkipped, got $replay", replay is CreateExpenseResult.DuplicateSkipped)
        assertEquals(purchaseId, (replay as CreateExpenseResult.DuplicateSkipped).existingExpenseId)
        assertEquals(1, db.expenseDao().getTotalCount())
        assertEquals("INSTALLMENT_PURCHASE", flag(purchaseId))
    }

    @Test
    fun `INV-8 the BLOCKING duplicate probes still see a flagged row`() = runTest {
        val id = seedFlaggedPurchase()
        val flagged = db.expenseDao().getById(id)!!

        val found = db.expenseDao().findBlockingDuplicateIdCurrencyAware(
            amount = flagged.amount,
            merchant = flagged.merchant,
            date = flagged.date,
            currency = flagged.currency,
            transactionType = flagged.transactionType.name,
            merchantKey = flagged.merchantKey,
            dedupeKey = flagged.dedupeKey
        )

        assertEquals(id, found)
    }
}
