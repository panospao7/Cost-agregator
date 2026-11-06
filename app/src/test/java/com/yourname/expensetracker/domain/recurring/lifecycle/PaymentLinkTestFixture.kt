package com.yourname.expensetracker.domain.recurring.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao
import com.yourname.expensetracker.data.database.dao.PlannedExpenseDao
import com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringLifecycleEvent
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.InstallmentEntryLinkReaderImpl
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.OccurrenceConflictResolver
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.RecurringOccurrenceExpander
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.ZoneId

/**
 * Shared harness of the FC-PAID-UNLINKED S2 coordinator tests: a REAL in-memory Room
 * [AppDatabase] (Robolectric), the REAL read and write barriers over a mocked
 * [RestoreMaintenanceMode] whose mode the test can switch, and the REAL
 * [RecurringLifecycleCoordinator]. Only the rule coordinator behind the DR-03 advance is a relaxed
 * mock (exposed as [ruleCoordinator]), and a DAO or the event writer can be replaced to inject a
 * failure at the coordinator boundary. Synthetic data only.
 *
 * Today is Nov 26, 2026 (a Thursday), the spec's date for the acceptance examples. The rent
 * fixture of the spec: rule "Rent" at merchant [MERCHANT], 400 EUR, monthly, due Nov 27, with a
 * 400 EUR PURCHASE on Nov 25 (day delta -2, outside the auto window, inside the suggestion window).
 */
internal class PaymentLinkTestFixture(
    val today: LocalDate = TODAY,
    occurrenceDaoOverride: RecurringOccurrenceDao? = null,
    expenseDaoOverride: ExpenseDao? = null,
    lifecycleEventDaoOverride: RecurringLifecycleEventDao? = null,
    eventWriterDecorator: (RecurringLifecycleEventWriter) -> RecurringLifecycleEventWriter = { it }
) {
    val database: AppDatabase = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext<Context>(),
        AppDatabase::class.java
    ).allowMainThreadQueries().build()

    val ruleDao: ManualRecurringExpenseDao = database.manualRecurringExpenseDao()
    val occurrenceDao: RecurringOccurrenceDao = database.recurringOccurrenceDao()
    val eventDao: RecurringLifecycleEventDao = database.recurringLifecycleEventDao()
    val expenseDao: ExpenseDao = database.expenseDao()
    val plannedDao: PlannedExpenseDao = database.plannedExpenseDao()

    /** Switch to a non-NORMAL mode to block the read and write barriers; back to NORMAL to release. */
    @Volatile
    var maintenanceMode: RestoreMaintenanceMode.Mode = RestoreMaintenanceMode.Mode.NORMAL

    /** Relaxed mock behind the DR-03 advance; verify `advanceNextDate` / `topUpHorizon` on it. */
    val ruleCoordinator: RecurringRuleLifecycleCoordinator = mockk(relaxed = true)

    val clockNow: Long = at(today, 12)

    val lifecycle: RecurringLifecycleCoordinator

    init {
        val timeProvider = object : TimeProvider {
            override fun now() = clockNow
        }
        val maintenance = mockk<RestoreMaintenanceMode>(relaxed = true).apply {
            every { currentMode() } answers { this@PaymentLinkTestFixture.maintenanceMode }
            every { isWritesAllowed() } answers {
                this@PaymentLinkTestFixture.maintenanceMode == RestoreMaintenanceMode.Mode.NORMAL
            }
        }
        val writeBarrier = DatabaseWriteBarrier(maintenance)
        val readBarrier = DatabaseReadBarrier(maintenance)
        val deliveryDao = database.recurringReminderDeliveryDao()
        val realWriter = object : RecurringLifecycleEventWriter {
            override suspend fun writeCritical(
                occurrenceId: Long?,
                eventType: String,
                oldStatus: String?,
                newStatus: String?,
                metadata: String?,
                occurredAt: Long
            ): Long = eventDao.insert(
                RecurringLifecycleEvent(
                    occurrenceId = occurrenceId,
                    eventType = eventType,
                    occurredAt = if (occurredAt == 0L) clockNow else occurredAt,
                    oldStatus = oldStatus,
                    newStatus = newStatus,
                    metadata = metadata
                )
            )
        }
        val eventWriter = eventWriterDecorator(realWriter)
        val installmentEligibility = RecurringInstallmentEligibility(
            dagger.Lazy { InstallmentEntryLinkReaderImpl(database.installmentPlanDao()) }
        )
        lifecycle = RecurringLifecycleCoordinator(
            database = database,
            expander = RecurringOccurrenceExpander(),
            resolver = OccurrenceConflictResolver(installmentEligibility),
            materializer = RecurringOccurrenceMaterializer(
                database = database,
                writeBarrier = writeBarrier,
                occurrenceDao = occurrenceDao,
                reminderDeliveryDao = deliveryDao,
                timeProvider = timeProvider,
                eventWriter = realWriter,
                plannedExpenseDao = plannedDao
            ),
            occurrenceDao = occurrenceDaoOverride ?: occurrenceDao,
            expenseDao = expenseDaoOverride ?: expenseDao,
            timeProvider = timeProvider,
            manualRecurringExpenseDao = ruleDao,
            reminderDeliveryDao = deliveryDao,
            lifecycleEventDao = lifecycleEventDaoOverride ?: eventDao,
            restoreMaintenanceMode = maintenance,
            writeBarrier = writeBarrier,
            readBarrier = readBarrier,
            plannedExpenseDao = plannedDao,
            transactionRunner = mockk(relaxed = true),
            eventWriter = eventWriter,
            installmentEligibility = installmentEligibility,
            ruleCoordinator = dagger.Lazy { ruleCoordinator }
        )
    }

    fun close() {
        database.close()
    }

    // -- time ---------------------------------------------------------------

    fun at(day: LocalDate, hour: Int = 6): Long = Companion.at(day, hour)

    fun startOf(day: LocalDate): Long = Companion.at(day, 0)

    // -- seeding ------------------------------------------------------------

    /** The rule's own nextDate is parked far away unless [nextDate] is given, so the DR-03 advance stays silent. */
    suspend fun seedRule(
        id: Long,
        merchant: String = MERCHANT,
        amount: Double = 400.0,
        currency: String = "EUR",
        frequency: RecurrenceFrequency = RecurrenceFrequency.MONTHLY,
        active: Boolean = true,
        nextDate: Long = startOf(today.plusYears(5))
    ) {
        ruleDao.insert(
            ManualRecurringExpense(
                id = id,
                merchant = merchant,
                amount = amount,
                currency = currency,
                frequency = frequency,
                nextDate = nextDate,
                createdAt = startOf(today),
                isActive = active
            )
        )
    }

    suspend fun seedOccurrence(
        ruleId: Long,
        due: LocalDate,
        status: String = "PLANNED",
        expected: Double = 400.0,
        expectedCurrency: String = "EUR",
        merchant: String? = MERCHANT,
        frequency: String = "MONTHLY",
        linkedExpenseId: Long? = null,
        sourceType: String = "RECURRING_RULE"
    ): Long = occurrenceDao.insert(
        RecurringOccurrence(
            sourceType = sourceType,
            sourceId = ruleId,
            occurrenceKey = "$sourceType|$ruleId|${startOf(due)}|$frequency",
            dueDate = startOf(due),
            status = status,
            expectedAmount = expected,
            expectedCurrency = expectedCurrency,
            frequency = frequency,
            linkedExpenseId = linkedExpenseId,
            merchant = merchant,
            createdAt = startOf(due),
            updatedAt = startOf(due)
        )
    )

    suspend fun seedExpense(
        day: LocalDate,
        amount: Double = 400.0,
        currency: String = "EUR",
        merchant: String = MERCHANT,
        type: TransactionType = TransactionType.PURCHASE,
        isNotMine: Boolean = false,
        hour: Int = 6
    ): Long = expenseDao.insert(
        Expense(
            amount = amount,
            currency = currency,
            merchant = merchant,
            transactionType = type,
            date = at(day, hour),
            createdAt = at(day, hour),
            source = "test",
            isNotMine = isNotMine
        )
    )

    /** Persists a decline row in the documented format (spec section 1.4). */
    suspend fun seedDecline(occurrenceId: Long, metadata: String?, eventType: String = "LINK_SUGGESTION_DECLINED") {
        eventDao.insert(
            RecurringLifecycleEvent(
                occurrenceId = occurrenceId,
                eventType = eventType,
                occurredAt = clockNow,
                oldStatus = null,
                newStatus = null,
                metadata = metadata
            )
        )
    }

    // -- reads --------------------------------------------------------------

    suspend fun occurrence(id: Long): RecurringOccurrence = occurrenceDao.getById(id)!!

    suspend fun status(occurrenceId: Long): String = occurrence(occurrenceId).status

    suspend fun linkedOccurrenceOf(expenseId: Long): Long? = occurrenceDao.getByLinkedExpenseId(expenseId)?.id

    suspend fun events(occurrenceId: Long): List<RecurringLifecycleEvent> =
        eventDao.getEventsForOccurrence(occurrenceId)

    suspend fun eventCount(occurrenceId: Long, eventType: String): Int =
        events(occurrenceId).count { it.eventType == eventType }

    /** Every occurrence row, any status and any rule. */
    suspend fun allOccurrences(): List<RecurringOccurrence> =
        occurrenceDao.getByDateRange(0L, Long.MAX_VALUE)

    suspend fun allEventCount(): Int = allOccurrences().sumOf { events(it.id).size }

    /** Suggestions read at the fixed clock. */
    suspend fun suggestions(): PaymentLinkSuggestions = lifecycle.findPaymentLinkSuggestions(clockNow)

    /** The items of an Available result; an Unavailable result fails the test (never "none"). */
    suspend fun suggestionItems(): List<PaymentLinkSuggestion> {
        val result = suggestions()
        if (result !is PaymentLinkSuggestions.Available) {
            throw AssertionError("expected Available but was $result")
        }
        return result.items
    }

    companion object {
        /** Thursday Nov 26, 2026: the spec's "today". Away from every common DST transition. */
        val TODAY: LocalDate = LocalDate.of(2026, 11, 26)

        const val MERCHANT = "Acme Properties"

        fun at(day: LocalDate, hour: Int = 6): Long =
            day.atStartOfDay(ZoneId.systemDefault()).plusHours(hour.toLong()).toInstant().toEpochMilli()
    }
}

/** Decorator for [PaymentLinkTestFixture]: the event writer throws [failure] for [failOnType] only. */
internal fun failingWriterOn(
    failOnType: String,
    failure: () -> Throwable
): (RecurringLifecycleEventWriter) -> RecurringLifecycleEventWriter = { delegate ->
    object : RecurringLifecycleEventWriter {
        override suspend fun writeCritical(
            occurrenceId: Long?,
            eventType: String,
            oldStatus: String?,
            newStatus: String?,
            metadata: String?,
            occurredAt: Long
        ): Long {
            if (eventType == failOnType) throw failure()
            return delegate.writeCritical(occurrenceId, eventType, oldStatus, newStatus, metadata, occurredAt)
        }
    }
}
