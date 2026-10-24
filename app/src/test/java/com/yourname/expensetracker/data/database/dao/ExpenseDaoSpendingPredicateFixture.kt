package com.yourname.expensetracker.data.database.dao

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yourname.expensetracker.data.backup.DatabaseAccessOperation
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.transaction.lifecycle.SpendingInclusionResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Shared fixture for the loan-installments canonical-predicate rollout tests
 * (ExpenseDaoSpendingPredicate*Test, plan amendment A12.5).
 *
 * Real in-memory Room database plus the real [TransactionLifecycleCoordinator]
 * (same wiring as TransactionLifecycleCoordinatorSpendingInclusionTest). The spending
 * exclusion flag is written ONLY through
 * [TransactionLifecycleCoordinator.setSpendingExclusionDbOnly]; this fixture never sets the
 * flag on an entity, never calls a DAO flag writer and never issues a raw UPDATE.
 */
class ExpenseDaoSpendingPredicateFixture private constructor(
    val db: AppDatabase,
    val coordinator: TransactionLifecycleCoordinator,
    val cat1: Long,
    val cat2: Long
) {
    val dao: ExpenseDao get() = db.expenseDao()

    /** Raw readable handle, used to run verbatim pre-edit SQL text for the neutrality proofs. */
    val raw: SupportSQLiteDatabase get() = db.openHelper.readableDatabase

    fun close() {
        db.close()
    }

    /** Inserts one expense row (test seeding of plain rows; flags are never set here). */
    @OptIn(RestrictedExpenseDaoMutation::class)
    suspend fun insert(
        amount: Double,
        date: Long,
        type: TransactionType = TransactionType.PURCHASE,
        currency: String = "EUR",
        merchant: String = "Shop",
        categoryId: Long? = cat1,
        isNotMine: Boolean = false,
        isShared: Boolean = false,
        sharePct: Int? = null,
        shareAmount: Double? = null
    ): Long {
        val id = dao.insert(
            Expense(
                amount = amount,
                currency = currency,
                merchant = merchant,
                merchantKey = merchant.lowercase(),
                transactionType = type,
                date = date,
                categoryId = categoryId,
                isNotMine = isNotMine,
                isSharedExpense = isShared,
                mySharePercentage = sharePct,
                myShareAmount = shareAmount
            )
        )
        assertTrue(id > 0L)
        return id
    }

    /** Sets the spending-exclusion flag through the coordinator and asserts it was applied. */
    suspend fun flag(expenseId: Long, reason: SpendingExclusionReason) {
        val result = coordinator.setSpendingExclusionDbOnly(expenseId, reason)
        assertEquals(SpendingInclusionResult.Updated(expenseId), result.value)
    }

    companion object {
        /** Mid-month noon UTC: the same calendar month in every device time zone. */
        fun day(year: Int, month: Int, dayOfMonth: Int = 15): Long =
            LocalDate.of(year, month, dayOfMonth)
                .atStartOfDay(ZoneOffset.UTC)
                .plusHours(12)
                .toInstant()
                .toEpochMilli()

        /** First millisecond of a month (UTC); used for half-open range bounds. */
        fun monthStart(year: Int, month: Int): Long =
            LocalDate.of(year, month, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

        suspend fun create(context: Context): ExpenseDaoSpendingPredicateFixture {
            val db = AppDatabaseTestFactory.create(context)
            val cat1 = db.categoryDao().insert(Category(name = "Electronics", icon = "E", color = "#33FF57"))
            val cat2 = db.categoryDao().insert(Category(name = "Groceries", icon = "G", color = "#3357FF"))

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

            val coordinator = TransactionLifecycleCoordinator(
                database = db,
                expenseDao = db.expenseDao(),
                transactionEventDao = db.transactionEventDao(),
                timeProvider = FakeTimeProvider.forDate(2026, 5, 1),
                currencyConverter = mockk(relaxed = true),
                sideEffectDispatcher = mockk(relaxed = true),
                planner = mockk(relaxed = true),
                runner = mockk(relaxed = true),
                recurringLifecycleCoordinator = mockk(relaxed = true),
                writeBarrier = writeBarrier,
                currencySettingsRepository = currencySettingsRepository,
                sourceLinkWriter = mockk(relaxed = true),
                transactionValidator = mockk(relaxed = true),
                diagnosticEventWriter = mockk(relaxed = true)
            )
            return ExpenseDaoSpendingPredicateFixture(db, coordinator, cat1, cat2)
        }
    }
}
