package com.yourname.expensetracker.domain.recurring.lifecycle

import androidx.room.withTransaction
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.dao.RecurringReminderDeliveryDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.transaction.DomainTransactionRunner
import com.yourname.expensetracker.domain.transaction.TransactionContext
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Two rules, same merchant and day, one matching expense: the read-only projection must let the
 * second rule see the first rule's claim. Real expander and resolver; DAOs are mocks.
 */
class RecurringLifecycleCoordinatorClaimedProjectionTest {

    private lateinit var manualRecurringExpenseDao: ManualRecurringExpenseDao
    private lateinit var expenseDao: ExpenseDao
    private lateinit var database: AppDatabase
    private lateinit var coordinator: RecurringLifecycleCoordinator

    private val now = 1_712_000_000_000L
    private val startDate = now
    private val endDate = now + 7L * 24L * 60L * 60L * 1000L

    private val ruleA = ManualRecurringExpense(
        id = 1L,
        merchant = "Netflix",
        amount = 400.0,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        nextDate = now
    )
    private val ruleB = ManualRecurringExpense(
        id = 2L,
        merchant = "Netflix",
        amount = 420.0,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        nextDate = now
    )
    private val expense400 = Expense(
        id = 77L,
        amount = 400.0,
        currency = "EUR",
        merchant = "Netflix",
        transactionType = TransactionType.PURCHASE,
        date = now,
        createdAt = now,
        source = "test"
    )

    @Before
    fun setup() {
        manualRecurringExpenseDao = mockk(relaxed = true)
        expenseDao = mockk(relaxed = true)
        database = mockk(relaxed = true)
        val timeProvider = mockk<TimeProvider>(relaxed = true)
        val restoreMaintenanceMode = mockk<RestoreMaintenanceMode>(relaxed = true)

        mockkStatic("androidx.room.RoomDatabaseKt")
        coEvery { database.withTransaction(any<suspend () -> Any>()) } coAnswers {
            secondArg<suspend () -> Any>().invoke()
        }
        every { timeProvider.now() } returns now
        every { restoreMaintenanceMode.isWritesAllowed() } returns true

        val eligibility = RecurringInstallmentEligibility(
            dagger.Lazy {
                object : InstallmentEntryLinkReader {
                    override suspend fun linkedEntryIdFor(expenseId: Long): Long? = null
                    override suspend fun isPlanPurchase(expenseId: Long): Boolean = false
                    override suspend fun linkedExpenseIds(): Set<Long> = emptySet()
                    override suspend fun planPurchaseExpenseIds(): Set<Long> = emptySet()
                }
            }
        )

        coEvery { manualRecurringExpenseDao.getById(1L) } returns ruleA
        coEvery { manualRecurringExpenseDao.getById(2L) } returns ruleB
        coEvery { expenseDao.getExpensesBetween(any(), any()) } returns listOf(expense400)

        coordinator = RecurringLifecycleCoordinator(
            database = database,
            expander = RecurringOccurrenceExpander(),
            resolver = OccurrenceConflictResolver(eligibility),
            materializer = mockk<RecurringOccurrenceMaterializer>(relaxed = true),
            occurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true),
            expenseDao = expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = manualRecurringExpenseDao,
            reminderDeliveryDao = mockk<RecurringReminderDeliveryDao>(relaxed = true),
            lifecycleEventDao = mockk<RecurringLifecycleEventDao>(relaxed = true),
            restoreMaintenanceMode = restoreMaintenanceMode,
            writeBarrier = mockk<DatabaseWriteBarrier>(relaxed = true),
            plannedExpenseDao = mockk<PlannedExpenseDao>(relaxed = true),
            transactionRunner = object : DomainTransactionRunner {
                override suspend fun <T> runInTransaction(
                    correlationId: String,
                    causationId: String?,
                    operationId: String,
                    source: String,
                    metadata: Map<String, String>,
                    block: suspend (TransactionContext) -> T
                ): T = block(
                    TransactionContext(
                        correlationId = correlationId,
                        causationId = causationId,
                        operationId = operationId,
                        source = source,
                        occurredAt = now,
                        metadata = metadata
                    )
                )
            },
            eventWriter = mockk(relaxed = true),
            installmentEligibility = eligibility,
            ruleCoordinator = dagger.Lazy { mockk<RecurringRuleLifecycleCoordinator>(relaxed = true) }
        )
    }

    @After
    fun tearDown() {
        unmockkStatic("androidx.room.RoomDatabaseKt")
    }

    @Test
    fun firstRuleProjectsPaidWithTheExpenseLink() = runTest(timeout = 60.seconds) {
        val a = coordinator.projectOccurrences(1L, startDate, endDate).single()

        assertEquals("PAID", a.status)
        assertEquals(77L, a.linkedExpenseId)
    }

    @Test
    fun secondRuleWithTheFirstRulesClaimProjectsPlanned() = runTest(timeout = 60.seconds) {
        val a = coordinator.projectOccurrences(1L, startDate, endDate).single()
        val claimed = setOfNotNull(a.linkedExpenseId)

        val b = coordinator.projectOccurrences(2L, startDate, endDate, claimed).single()

        assertEquals(setOf(77L), claimed)
        assertEquals("PLANNED", b.status)
        assertNull(b.linkedExpenseId)
    }

    @Test
    fun secondRuleWithoutTheClaimStillProjectsPaidAsBefore() = runTest(timeout = 60.seconds) {
        val b = coordinator.projectOccurrences(2L, startDate, endDate).single()

        assertEquals("PAID", b.status)
        assertEquals(77L, b.linkedExpenseId)
    }
}
