package com.yourname.expensetracker.domain.installment.lifecycle

import android.content.Context
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.data.database.dao.RestrictedExpenseDaoMutation
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.InstallmentCandidateChargeProbeImpl
import com.yourname.expensetracker.data.repository.InstallmentExpenseFactsReaderImpl
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.currency.RateEligibilityEvaluator
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentResidualPlacement
import com.yourname.expensetracker.domain.installment.model.InstallmentSplitMethod
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.transaction.lifecycle.SpendingInclusionResult
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectPlanner
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import timber.log.Timber
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Shared harness for the loan-installments S6a coordinator tests (Declare, LinkConfirm,
 * Reconcile, privacy). Real in-memory Room database, the real [TransactionLifecycleCoordinator],
 * a real [DatabaseWriteBarrier] (NORMAL unless a test flips the mode), the real facts reader and
 * the real plan DAO. Only peripheral collaborators are mocked (side-effect planner and runner,
 * converter, validator, diagnostics, rate evaluator).
 *
 * Failure injection is done at the persistence boundary: [HookedPlanDao] and
 * [HookedFactsReader] delegate to the real objects and can throw, return a lost compare-and-set
 * or run a concurrent change at one chosen call; SQLite triggers raise real constraint errors.
 * Synthetic data only.
 */
@OptIn(RestrictedExpenseDaoMutation::class)
class InstallmentCoordinatorFixture private constructor(
    val db: AppDatabase,
    val time: FakeTimeProvider,
    val planner: TransactionSideEffectPlanner,
    val evaluator: RateEligibilityEvaluator,
    val tlc: TransactionLifecycleCoordinator,
    val barrier: DatabaseWriteBarrier,
    val facts: InstallmentExpenseFactsReader,
    val categoryId: Long,
    private val modeRef: AtomicReference<RestoreMaintenanceMode.Mode>
) {
    val planDao: InstallmentPlanDao get() = db.installmentPlanDao()
    val expenseDao: ExpenseDao get() = db.expenseDao()

    fun close() {
        db.close()
    }

    fun setMode(mode: RestoreMaintenanceMode.Mode) {
        modeRef.set(mode)
    }

    fun coordinator(
        dao: InstallmentPlanDao = planDao,
        reader: InstallmentExpenseFactsReader = facts,
        lifecycle: TransactionLifecycleCoordinator = tlc
    ): InstallmentPlanLifecycleCoordinator =
        InstallmentPlanLifecycleCoordinator(
            db, dao, lifecycle, reader, barrier, time,
            InstallmentCandidateChargeProbeImpl(reader, dao)
        )

    // ------------------------------------------------------------------ raw SQL helpers

    fun count(table: String): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getInt(0)
        }

    /** Event types in insertion order. */
    fun eventTypes(): List<String> =
        db.openHelper.readableDatabase.query("SELECT eventType FROM transaction_events ORDER BY id").use {
            val out = ArrayList<String>()
            while (it.moveToNext()) out += it.getString(0)
            out
        }

    fun exec(sql: String) {
        db.openHelper.writableDatabase.execSQL(sql)
    }

    /** Test-only stand-in for the standard expense delete path (the plan coordinator never deletes expenses). */
    fun deleteExpenseRow(expenseId: Long) {
        exec("DELETE FROM expenses WHERE id = $expenseId")
    }

    suspend fun flagOf(expenseId: Long): String? = expenseDao.getById(expenseId)?.spendingExclusionReason

    /** Sets or clears the flag through TLC (the only legal flag writer). */
    suspend fun setFlag(expenseId: Long, reason: SpendingExclusionReason?) {
        val result = tlc.setSpendingExclusionDbOnly(expenseId, reason)
        assertTrue(result.value is SpendingInclusionResult.Updated)
    }

    /** Canonical spending of one calendar month (UTC bounds; all test data sits mid-month). */
    suspend fun spending(year: Int, month: Int): Double {
        val start = LocalDate.of(year, month, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val end = LocalDate.of(year, month, 1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        // Canonical spending predicate (S4a); tests use a single currency, so the EUR row is the total.
        return expenseDao.getTotalSpentBetweenByCurrency(start, end).sumOf { it.total }
    }

    suspend fun entries(planId: Long): List<InstallmentEntry> =
        planDao.getEntriesForPlan(planId).sortedBy { it.seq }

    // ------------------------------------------------------------------ seeding

    suspend fun seedExpense(
        amount: Double = 600.0,
        currency: String = "EUR",
        type: TransactionType = TransactionType.PURCHASE,
        dateMillis: Long = PURCHASE_MILLIS,
        merchant: String = "Demo Electronics",
        isNotMine: Boolean = false,
        isShared: Boolean = false,
        myShareAmount: Double? = null,
        splitVisualization: String? = null,
        category: Long? = categoryId,
        paymentMethod: PaymentMethod = PaymentMethod.CARD
    ): Long {
        val id = expenseDao.insert(
            Expense(
                amount = amount,
                currency = currency,
                merchant = merchant,
                merchantKey = merchant.lowercase(),
                transactionType = type,
                date = dateMillis,
                categoryId = category,
                paymentMethod = paymentMethod,
                isNotMine = isNotMine,
                isSharedExpense = isShared,
                myShareAmount = myShareAmount,
                splitVisualization = splitVisualization
            )
        )
        assertTrue(id > 0L)
        return id
    }

    suspend fun linkToOccurrence(expenseId: Long, key: String = "synthetic-occurrence") {
        db.recurringOccurrenceDao().insert(
            RecurringOccurrence(
                sourceType = "PLANNED",
                sourceId = 1L,
                occurrenceKey = key,
                dueDate = 0L,
                status = "PAID",
                linkedExpenseId = expenseId,
                expectedAmount = 50.0,
                expectedCurrency = "EUR",
                frequency = "MONTHLY",
                merchant = null
            )
        )
    }

    class SeedEntry(
        val seq: Int,
        val status: String = "SCHEDULED",
        val linkedExpenseId: Long? = null,
        val dueEpochDay: Long = day(2027, 4, 10),
        val amount: Double = 50.0,
        val autoRecordConsumed: Boolean = false,
        val recordGeneration: Int = 0
    )

    /** Inserts plan and entries directly through the DAO (arbitrary stored state for reconcile tests). */
    suspend fun seedPlan(
        uuid: String,
        entries: List<SeedEntry>,
        kind: String = "STANDALONE",
        status: String = "ACTIVE",
        purchaseExpenseId: Long? = null,
        currency: String = "EUR",
        mode: String = "CONFIRM",
        displayName: String = "Synthetic Plan"
    ): Long {
        val planId = planDao.insertPlan(
            InstallmentPlan(
                planUuid = uuid,
                kind = kind,
                purchaseExpenseId = purchaseExpenseId,
                displayName = displayName,
                currency = currency,
                splitMethod = "FIXED_PAYMENT",
                fixedPaymentAmount = 50.0,
                installmentCount = entries.size,
                frequency = "MONTHLY",
                firstDueDate = day(2027, 4, 10),
                anchorDayOfMonth = 10,
                residualPlacement = "FIRST",
                categoryId = categoryId,
                paymentMethod = PaymentMethod.CARD,
                fulfilmentMode = mode,
                declaredOnDate = day(2027, 3, 12),
                status = status,
                createdAt = time.now(),
                updatedAt = time.now()
            )
        )
        planDao.insertEntries(
            entries.map {
                InstallmentEntry(
                    planId = planId,
                    seq = it.seq,
                    kind = "REGULAR",
                    dueDate = it.dueEpochDay,
                    scheduledAmount = it.amount,
                    status = it.status,
                    linkedExpenseId = it.linkedExpenseId,
                    linkOrigin = if (it.linkedExpenseId != null) "USER_LINKED" else null,
                    autoRecordConsumed = it.autoRecordConsumed,
                    recordGeneration = it.recordGeneration,
                    statusChangedAt = time.now()
                )
            }
        )
        return planId
    }

    // ------------------------------------------------------------------ request builders

    fun terms(
        planUuid: String = "plan-uuid-1",
        displayName: String = "Demo Electronics",
        lender: String? = "Synthetic Lender",
        currency: String = "EUR",
        split: InstallmentSplitMethod = InstallmentSplitMethod.EQUAL_SPLIT,
        total: Double? = 600.0,
        fixed: Double? = null,
        count: Int = 12,
        firstDue: Long = day(2027, 4, 10),
        residual: InstallmentResidualPlacement = InstallmentResidualPlacement.FIRST,
        mode: InstallmentFulfilmentMode = InstallmentFulfilmentMode.AUTO_RECORD,
        paidBeforeTracking: Int = 0,
        declaredOn: Long = day(2027, 3, 12)
    ) = InstallmentPlanTerms(
        planUuid = planUuid,
        displayName = displayName,
        lenderLabel = lender,
        currency = currency,
        splitMethod = split,
        declaredTotal = total,
        fixedPaymentAmount = fixed,
        installmentCount = count,
        firstDueEpochDay = firstDue,
        residualPlacement = residual,
        fulfilmentMode = mode,
        paidBeforeTrackingCount = paidBeforeTracking,
        declaredOnEpochDay = declaredOn
    )

    /** FIXED_PAYMENT 50.00 x [count] standalone terms, first due 2027-04-10. */
    fun standaloneTerms(
        planUuid: String = "standalone-uuid-1",
        count: Int = 6,
        mode: InstallmentFulfilmentMode = InstallmentFulfilmentMode.CONFIRM,
        currency: String = "EUR",
        fixed: Double = 50.0
    ) = terms(
        planUuid = planUuid,
        displayName = "Synthetic Loan",
        lender = null,
        currency = currency,
        split = InstallmentSplitMethod.FIXED_PAYMENT,
        total = null,
        fixed = fixed,
        count = count,
        mode = mode
    )

    fun purchaseRequest(
        amount: Double = 600.0,
        currency: String = "EUR",
        merchant: String = "Demo Electronics",
        dateMillis: Long = PURCHASE_MILLIS,
        isNotMine: Boolean = false
    ) = CreateExpenseRequest(
        merchant = merchant,
        amount = amount,
        currency = currency,
        date = dateMillis,
        transactionType = TransactionType.PURCHASE,
        source = ExpenseSource.MANUAL_ENTRY,
        categoryId = categoryId,
        paymentMethod = PaymentMethod.CARD,
        isManualEntry = true,
        isNotMine = isNotMine
    )

    /** Declares AE-1 (600 EUR, 12 x 50) on a fresh purchase; returns (planId, expenseId). */
    suspend fun declareAe1(
        coordinator: InstallmentPlanLifecycleCoordinator,
        planUuid: String = "plan-uuid-1"
    ): Pair<Long, Long> {
        val expenseId = seedExpense()
        val result = coordinator.declareOnExpense(expenseId, terms(planUuid = planUuid), ATHENS)
        val done = result as InstallmentOpResult.Done
        return done.value.planId to expenseId
    }

    /** Seeds an unlinked 50.00 EUR expense on [dateMillis] (a "captured" installment charge). */
    suspend fun seedCharge(dateMillis: Long, amount: Double = 50.0, currency: String = "EUR"): Long =
        seedExpense(amount = amount, currency = currency, dateMillis = dateMillis, merchant = "Synthetic Lender Charge")

    fun assertTablesUnchanged(before: Triple<Int, Int, Int>) {
        assertEquals(before, Triple(count("expenses"), count("installment_plans"), count("installment_entries")))
    }

    fun tableCounts(): Triple<Int, Int, Int> =
        Triple(count("expenses"), count("installment_plans"), count("installment_entries"))

    companion object {
        val ATHENS: ZoneId = ZoneId.of("Europe/Athens")

        fun day(year: Int, month: Int, dayOfMonth: Int): Long = LocalDate.of(year, month, dayOfMonth).toEpochDay()

        fun athensMillis(year: Int, month: Int, dayOfMonth: Int, hour: Int = 12, minute: Int = 0): Long =
            ZonedDateTime.of(year, month, dayOfMonth, hour, minute, 0, 0, ATHENS).toInstant().toEpochMilli()

        /** AE-1: the purchase is dated 2027-03-10 14:32 Athens. */
        val PURCHASE_MILLIS: Long = athensMillis(2027, 3, 10, 14, 32)

        suspend fun create(context: Context): InstallmentCoordinatorFixture {
            val db = AppDatabaseTestFactory.create(context)
            val categoryId = db.categoryDao().insert(Category(name = "Electronics", icon = "E", color = "#33FF57"))
            val time = FakeTimeProvider.forDate(2027, 3, 12)

            val modeRef = AtomicReference(RestoreMaintenanceMode.Mode.NORMAL)
            val maintenance = mockk<RestoreMaintenanceMode> {
                every { currentMode() } answers { modeRef.get() }
                every { isWritesAllowed() } answers { modeRef.get() == RestoreMaintenanceMode.Mode.NORMAL }
            }
            val barrier = DatabaseWriteBarrier(maintenance)

            val currencySettings = mockk<CurrencySettingsRepository>(relaxed = true)
            coEvery { currencySettings.resolveHomeCurrency() } returns
                HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

            val planner = mockk<TransactionSideEffectPlanner>(relaxed = true)
            val evaluator = mockk<RateEligibilityEvaluator>()

            val tlc = TransactionLifecycleCoordinator(
                database = db,
                expenseDao = db.expenseDao(),
                transactionEventDao = db.transactionEventDao(),
                timeProvider = time,
                currencyConverter = mockk(relaxed = true),
                sideEffectDispatcher = mockk(relaxed = true),
                planner = planner,
                runner = mockk(relaxed = true),
                recurringLifecycleCoordinator = mockk(relaxed = true),
                writeBarrier = barrier,
                currencySettingsRepository = currencySettings,
                sourceLinkWriter = mockk(relaxed = true),
                transactionValidator = mockk(relaxed = true),
                diagnosticEventWriter = mockk(relaxed = true),
                rateEligibilityEvaluator = evaluator
            )
            val facts = InstallmentExpenseFactsReaderImpl(db.expenseDao(), db.recurringOccurrenceDao(), db.categoryDao())
            return InstallmentCoordinatorFixture(db, time, planner, evaluator, tlc, barrier, facts, categoryId, modeRef)
        }
    }
}

/**
 * Real plan DAO with injectable failures. Every hook runs before the delegate call, so a hook
 * that throws leaves that statement unexecuted. A forced result replaces the compare-and-set
 * (simulates a lost race: 0 rows).
 */
class HookedPlanDao(private val d: InstallmentPlanDao) : InstallmentPlanDao by d {
    var getPlanByIdHook: (suspend (Long) -> Unit)? = null
    var getEntryByLinkedExpenseIdHook: (suspend (Long) -> Unit)? = null
    var afterInsertEntriesHook: (suspend () -> Unit)? = null
    var claimResult: Int? = null
    var releaseResult: Int? = null
    var scheduleResult: Int? = null
    var statusResult: Int? = null
    var planStatusResult: Int? = null
    var planStatusHook: (suspend () -> Unit)? = null

    override suspend fun getPlanById(planId: Long): InstallmentPlan? {
        getPlanByIdHook?.invoke(planId)
        return d.getPlanById(planId)
    }

    override suspend fun getEntryByLinkedExpenseId(expenseId: Long): InstallmentEntry? {
        getEntryByLinkedExpenseIdHook?.invoke(expenseId)
        return d.getEntryByLinkedExpenseId(expenseId)
    }

    override suspend fun insertEntries(entries: List<InstallmentEntry>): List<Long> {
        val ids = d.insertEntries(entries)
        afterInsertEntriesHook?.invoke()
        return ids
    }

    override suspend fun claimEntryForExpense(
        entryId: Long,
        expenseId: Long,
        linkOrigin: String,
        markAutoRecordConsumed: Boolean,
        now: Long
    ): Int = claimResult ?: d.claimEntryForExpense(entryId, expenseId, linkOrigin, markAutoRecordConsumed, now)

    override suspend fun releaseEntryLink(entryId: Long, expectedExpenseId: Long, statusReason: String?, now: Long): Int =
        releaseResult ?: d.releaseEntryLink(entryId, expectedExpenseId, statusReason, now)

    override suspend fun updateEntryScheduleIfScheduled(
        entryId: Long,
        newAmount: Double,
        newDueDate: Long,
        adjustReason: String,
        now: Long
    ): Int = scheduleResult ?: d.updateEntryScheduleIfScheduled(entryId, newAmount, newDueDate, adjustReason, now)

    override suspend fun updateEntryStatusIfCurrent(
        entryId: Long,
        expectedStatus: String,
        newStatus: String,
        statusReason: String?,
        now: Long
    ): Int = statusResult ?: d.updateEntryStatusIfCurrent(entryId, expectedStatus, newStatus, statusReason, now)

    override suspend fun updatePlanStatusIfCurrent(
        planId: Long,
        expectedStatus: String,
        newStatus: String,
        statusReason: String?,
        terminalAt: Long?,
        now: Long
    ): Int {
        planStatusHook?.invoke()
        return planStatusResult ?: d.updatePlanStatusIfCurrent(planId, expectedStatus, newStatus, statusReason, terminalAt, now)
    }
}

/** Real facts reader with injectable post-processing (stored-facts drift, concurrent change after the orphan scan). */
class HookedFactsReader(private val d: InstallmentExpenseFactsReader) : InstallmentExpenseFactsReader by d {
    var factsTransform: (suspend (Long, InstallmentExpenseFacts?) -> InstallmentExpenseFacts?)? = null
    var afterFindFlagged: (suspend (List<InstallmentFlaggedExpense>) -> Unit)? = null

    override suspend fun findExpenseFacts(expenseId: Long): InstallmentExpenseFacts? {
        val real = d.findExpenseFacts(expenseId)
        val transform = factsTransform ?: return real
        return transform(expenseId, real)
    }

    override suspend fun findFlaggedExpenses(): List<InstallmentFlaggedExpense> {
        val list = d.findFlaggedExpenses()
        afterFindFlagged?.invoke(list)
        return list
    }
}

/** Timber tree that records every formatted line plus the throwable text (privacy log capture). */
class CapturingTree : Timber.Tree() {
    val lines = CopyOnWriteArrayList<String>()

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        lines += if (t == null) message else "$message | $t"
    }

    fun plant() = Timber.plant(this)

    fun uproot() = Timber.uproot(this)
}
