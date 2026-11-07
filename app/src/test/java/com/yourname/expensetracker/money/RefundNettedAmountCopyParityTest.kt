package com.yourname.expensetracker.money

import com.google.common.truth.Truth.assertThat
import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsInputAssembler
import com.yourname.expensetracker.domain.analytics.NormalizedExpense
import com.yourname.expensetracker.domain.analytics.SingleCurrencyAnalyticsInput
import com.yourname.expensetracker.domain.analytics.toExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyNormalizationEngine
import com.yourname.expensetracker.domain.core.money.NormalizationResult
import com.yourname.expensetracker.domain.core.time.PeriodKind
import com.yourname.expensetracker.domain.core.time.PeriodRange
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.export.ExpenseExportMapper
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * refund-netting S2b: every copy site that moves an Expense (or a DTO derived from it) into
 * another DTO must carry {@code refundNettedAmount} next to {@code spendingExclusionReason}.
 *
 * Behavioural coverage through the real mapping functions for the public / testable sites.
 * The private mappers (AnomalyAlertOrchestrator, RecurringExpenseEngine, AnalyticsViewModel) have
 * no observable output for the field; they are covered structurally in
 * RefundNettedAmountDtoParityGuardTest (last resort).
 *
 * Note: AnalyticsCurrencyNormalizer drops rows with a non-null spendingExclusionReason before
 * mapping, so its copies are exercised with a null reason (the reason cannot travel there).
 */
class RefundNettedAmountCopyParityTest {

    private val refund = 12.5
    private val reason = SpendingExclusionReason.INSTALLMENT_REVERSED.name

    private fun expense(
        refundNettedAmount: Double? = refund,
        spendingExclusionReason: String? = null,
        currency: String = "EUR"
    ): Expense {
        val base = Expense(
            id = 7L,
            amount = 100.0,
            currency = currency,
            merchant = "Parity Merchant",
            transactionType = TransactionType.PURCHASE,
            date = 1_700_000_000_000L,
            categoryId = null,
            isNotMine = false,
            isManualEntry = false,
            spendingExclusionReason = spendingExclusionReason
        )
        return if (refundNettedAmount == null) base else base.copy(refundNettedAmount = refundNettedAmount)
    }

    private fun snapshot(
        refundNettedAmount: Double? = refund,
        spendingExclusionReason: String? = null
    ): ExpenseSnapshot {
        val base = ExpenseSnapshot(
            id = 7L,
            amount = 100.0,
            effectiveAmount = 100.0,
            currency = "EUR",
            merchant = "Parity Merchant",
            merchantKey = "parity merchant",
            transactionType = DomainTransactionType.PURCHASE,
            date = 1_700_000_000_000L,
            categoryId = null,
            isNotMine = false,
            transferDirection = null,
            notes = null,
            spendingExclusionReason = spendingExclusionReason
        )
        return if (refundNettedAmount == null) base else base.copy(refundNettedAmount = refundNettedAmount)
    }

    private fun normalizedExpense(
        refundNettedAmount: Double? = refund,
        spendingExclusionReason: String? = null
    ): NormalizedExpense {
        val base = NormalizedExpense(
            id = 7L,
            originalAmount = 100.0,
            originalEffectiveAmount = 100.0,
            originalCurrency = "EUR",
            normalizedAmount = 100.0,
            normalizedCurrency = "EUR",
            date = 1_700_000_000_000L,
            merchant = "Parity Merchant",
            merchantKey = "parity merchant",
            categoryId = null,
            categoryNameSnapshot = null,
            transactionType = "PURCHASE",
            isNotMine = false,
            isSharedExpense = false,
            ownershipMode = null,
            source = null,
            spendingExclusionReason = spendingExclusionReason
        )
        return if (refundNettedAmount == null) base else base.copy(refundNettedAmount = refundNettedAmount)
    }

    private fun converter() = CurrencyConverter(mockk<ExchangeRateStore>(relaxed = true), timeProvider = mockk())

    // ---- ExpenseExportMapper / ExportTransaction ----

    @Test
    fun `export mapper carries refundNettedAmount and spendingExclusionReason`() {
        val exported = ExpenseExportMapper.map(expense(spendingExclusionReason = reason))

        assertThat(exported.refundNettedAmount).isEqualTo(12.5)
        assertThat(exported.spendingExclusionReason).isEqualTo(reason)
    }

    @Test
    fun `export mapper defaults refundNettedAmount to zero when the expense has no link`() {
        assertThat(ExpenseExportMapper.map(expense(refundNettedAmount = null)).refundNettedAmount).isEqualTo(0.0)
    }

    @Test
    fun `export mapper maps NaN refundNettedAmount to zero`() {
        assertThat(ExpenseExportMapper.map(expense(refundNettedAmount = Double.NaN)).refundNettedAmount).isEqualTo(0.0)
    }

    @Test
    fun `export mapper maps positive and negative infinity refundNettedAmount to zero`() {
        assertThat(ExpenseExportMapper.map(expense(refundNettedAmount = Double.POSITIVE_INFINITY)).refundNettedAmount)
            .isEqualTo(0.0)
        assertThat(ExpenseExportMapper.map(expense(refundNettedAmount = Double.NEGATIVE_INFINITY)).refundNettedAmount)
            .isEqualTo(0.0)
    }

    // ---- NormalizedAnalyticsInput.NormalizedExpense.toExpenseSnapshot ----

    @Test
    fun `NormalizedExpense toExpenseSnapshot carries refundNettedAmount and spendingExclusionReason`() {
        val snap = normalizedExpense(spendingExclusionReason = reason).toExpenseSnapshot()

        assertThat(snap.refundNettedAmount).isEqualTo(12.5)
        assertThat(snap.spendingExclusionReason).isEqualTo(reason)
    }

    @Test
    fun `NormalizedExpense toExpenseSnapshot defaults refundNettedAmount to zero`() {
        assertThat(normalizedExpense(refundNettedAmount = null).toExpenseSnapshot().refundNettedAmount).isEqualTo(0.0)
    }

    // ---- SingleCurrencyAnalyticsInput (ExpenseSnapshot -> NormalizedExpense) ----

    @Test
    fun `SingleCurrencyAnalyticsInput carries refundNettedAmount and spendingExclusionReason`() {
        val input = SingleCurrencyAnalyticsInput.from(listOf(snapshot(spendingExclusionReason = reason)), "EUR")

        val row = input.includedExpenses.single()
        assertThat(row.refundNettedAmount).isEqualTo(12.5)
        assertThat(row.spendingExclusionReason).isEqualTo(reason)
    }

    @Test
    fun `SingleCurrencyAnalyticsInput defaults refundNettedAmount to zero`() {
        val input = SingleCurrencyAnalyticsInput.from(listOf(snapshot(refundNettedAmount = null)), "EUR")

        assertThat(input.includedExpenses.single().refundNettedAmount).isEqualTo(0.0)
    }

    // ---- MoneyNormalizationEngine (Expense -> NormalizedExpense) ----

    @Test
    fun `MoneyNormalizationEngine carries refundNettedAmount and spendingExclusionReason for a neutralised row`() = runTest {
        val engine = MoneyNormalizationEngine(converter())

        val result = engine.normalizeExpense(expense(spendingExclusionReason = reason), CurrencyCode.EUR)

        val row = (result as NormalizationResult.Included<NormalizedExpense>).value
        assertThat(row.refundNettedAmount).isEqualTo(12.5)
        assertThat(row.spendingExclusionReason).isEqualTo(reason)
    }

    @Test
    fun `MoneyNormalizationEngine carries refundNettedAmount for a same-currency counted row`() = runTest {
        val engine = MoneyNormalizationEngine(converter())

        val result = engine.normalizeExpense(expense(), CurrencyCode.EUR)

        assertThat((result as NormalizationResult.Included<NormalizedExpense>).value.refundNettedAmount).isEqualTo(12.5)
    }

    @Test
    fun `MoneyNormalizationEngine defaults refundNettedAmount to zero`() = runTest {
        val engine = MoneyNormalizationEngine(converter())

        val result = engine.normalizeExpense(expense(refundNettedAmount = null), CurrencyCode.EUR)

        assertThat((result as NormalizationResult.Included<NormalizedExpense>).value.refundNettedAmount).isEqualTo(0.0)
    }

    // ---- AnalyticsCurrencyNormalizer (all three copies) ----

    @Test
    fun `AnalyticsCurrencyNormalizer normalizeExpenses carries refundNettedAmount into both snapshots`() = runTest {
        val normalizer = AnalyticsCurrencyNormalizer(converter())

        val result = normalizer.normalizeExpenses(listOf(expense()), "EUR")

        assertThat(result.includedExpenses.single().refundNettedAmount).isEqualTo(12.5)
        assertThat(result.normalizedExpenses.single().snapshot.refundNettedAmount).isEqualTo(12.5)
    }

    @Test
    fun `AnalyticsCurrencyNormalizer normalizeSnapshots carries refundNettedAmount into both snapshots`() = runTest {
        val normalizer = AnalyticsCurrencyNormalizer(converter())

        val result = normalizer.normalizeSnapshots(listOf(snapshot()), "EUR")

        assertThat(result.includedExpenses.single().refundNettedAmount).isEqualTo(12.5)
        assertThat(result.normalizedExpenses.single().snapshot.refundNettedAmount).isEqualTo(12.5)
    }

    @Test
    fun `AnalyticsCurrencyNormalizer defaults refundNettedAmount to zero`() = runTest {
        val normalizer = AnalyticsCurrencyNormalizer(converter())

        val fromExpense = normalizer.normalizeExpenses(listOf(expense(refundNettedAmount = null)), "EUR")
        val fromSnapshot = normalizer.normalizeSnapshots(listOf(snapshot(refundNettedAmount = null)), "EUR")

        assertThat(fromExpense.includedExpenses.single().refundNettedAmount).isEqualTo(0.0)
        assertThat(fromSnapshot.includedExpenses.single().refundNettedAmount).isEqualTo(0.0)
    }

    // ---- AnalyticsInputAssembler (normalizer snapshot -> NormalizedExpense) ----

    @Test
    fun `AnalyticsInputAssembler carries refundNettedAmount into the canonical NormalizedExpense`() = runTest {
        val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        coEvery { categoryRepository.getAll() } returns emptyList()
        val assembler = AnalyticsInputAssembler(
            expenseRepository = mockk<ExpenseRepository>(relaxed = true),
            normalizer = AnalyticsCurrencyNormalizer(converter()),
            currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true),
            timeProvider = mockk<TimeProvider>(relaxed = true),
            categoryRepository = categoryRepository
        )
        val period = PeriodRange(
            kind = PeriodKind.CUSTOM,
            startInclusiveMillis = 0L,
            endExclusiveMillis = 9_999_999_999_999L
        )

        val input = assembler.build(expenses = listOf(expense()), homeCurrency = "EUR", period = period)

        assertThat(input.includedExpenses.single().refundNettedAmount).isEqualTo(12.5)
    }

    // ---- ForecastInputAssembler.mapExpenseSnapshots ----

    @Test
    fun `ForecastInputAssembler mapExpenseSnapshots carries refundNettedAmount and spendingExclusionReason`() {
        val assembler = forecastAssembler()

        val mapped = assembler.mapExpenseSnapshots(
            listOf(expense(spendingExclusionReason = reason), expense(refundNettedAmount = null).copy(id = 8L))
        )

        assertThat(mapped[0].refundNettedAmount).isEqualTo(12.5)
        assertThat(mapped[0].spendingExclusionReason).isEqualTo(reason)
        assertThat(mapped[1].refundNettedAmount).isEqualTo(0.0)
    }

    private fun forecastAssembler() = ForecastInputAssembler(
        mockk<TimeProvider>(relaxed = true),
        analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true),
        currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true),
        currencyConverter = mockk<CurrencyConverter>(relaxed = true),
        recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
        recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true),
        databaseReadBarrier = mockk<DatabaseReadBarrier>(relaxed = true)
    )
}
