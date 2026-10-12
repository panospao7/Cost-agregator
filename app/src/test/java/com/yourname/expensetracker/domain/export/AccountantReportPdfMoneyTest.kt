package com.yourname.expensetracker.domain.export

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableException
import com.yourname.expensetracker.domain.core.money.MoneyDisplayUnavailableReasonCode
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountantReportPdfMoneyTest {
    private val converter = mockk<CurrencyConverter>()
    private val exporter = AccountantReportPdfExporter(FakeTimeProvider(1_700_000_000_000L), converter, mockk())

    @Test
    fun `deductible totals preserve source counts and explicitly use latest rates`() = runTest {
        coEvery { converter.convertOutcome(any(), "USD", "EUR", RateBasis.LATEST_AVAILABLE, null, any()) } answers {
            ConversionOutcome.Converted(firstArg(), CurrencyCode.USD, firstArg<Double>() * 0.5,
                CurrencyCode.EUR, 0.5, RateBasis.LATEST_AVAILABLE, null, null, "TEST", ConversionPath.DIRECT)
        }
        val aggregate = exporter.generateDeductibleAggregate(listOf(
            expense(1, 10.0, "EUR"), expense(2, 20.0, "USD"),
            expense(3, 5.0, "USD"), expense(4, 100.0, "EUR").copy(isBusinessExpense = false)
        ), "EUR")
        assertEquals(22.5, aggregate.displayAmount, 0.0)
        assertEquals(3, aggregate.totalTransactionCount)
        assertEquals(RateBasis.LATEST_AVAILABLE, aggregate.requestedRateBasis)
        assertEquals(RateBasis.LATEST_AVAILABLE, aggregate.actualRateBasis)
        coVerify(exactly = 1) { converter.convertOutcome(25.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE, null, any()) }
    }

    @Test
    fun `missing deductible conversion remains partial with failed source count`() = runTest {
        coEvery { converter.convertOutcome(any(), "USD", "EUR", any(), any(), any()) } returns
            ConversionOutcome.Failed(20.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE,
                ConversionFailureType.MISSING_RATE, "TEST_MISSING_RATE")
        val aggregate = exporter.generateDeductibleAggregate(listOf(expense(1, 10.0, "EUR"), expense(2, 20.0, "USD")), "EUR")
        assertTrue(aggregate.isPartial)
        assertEquals(1, aggregate.failedTransactionCount)
        assertEquals(2, aggregate.totalTransactionCount)
    }

    @Test
    fun `valid empty deductible input is available zero with no conversion`() = runTest {
        val aggregate = exporter.generateDeductibleAggregate(emptyList(), "EUR")
        assertEquals(0.0, aggregate.displayAmount, 0.0)
        assertEquals(0, aggregate.totalTransactionCount)
        assertEquals(com.yourname.expensetracker.domain.core.money.ConversionQuality.COMPLETE, aggregate.conversionQuality)
        coVerify(exactly = 0) { converter.convertOutcome(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `all failed deductible conversion stays unavailable not empty zero`() = runTest {
        coEvery { converter.convertOutcome(any(), "USD", "EUR", any(), any(), any()) } returns
            ConversionOutcome.Failed(20.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE,
                ConversionFailureType.MISSING_RATE, "TEST")
        val aggregate = exporter.generateDeductibleAggregate(listOf(expense(1L, 20.0, "USD")), "EUR")
        assertEquals(com.yourname.expensetracker.domain.core.money.ConversionQuality.UNAVAILABLE, aggregate.conversionQuality)
        assertTrue(aggregate.isPartial)
        assertEquals(1, aggregate.failedTransactionCount)
    }

    @Test
    fun `source sections reconcile effective spending category totals and counts without FX`() {
        val sections = exporter.generateSourceSections(listOf(
            expense(1L, 20.0, "EUR").copy(categoryId = 1L),
            expense(2L, 40.0, "EUR").copy(categoryId = 2L, isSharedExpense = true, myShareAmount = 10.0),
            expense(3L, 7.0, "USD").copy(categoryId = 1L),
            expense(4L, 500.0, "EUR").copy(transactionType = TransactionType.DEPOSIT)
        ), mapOf(1L to "Food", 2L to "Travel"))
        assertEquals(listOf("EUR", "USD"), sections.map { it.currency })
        val eur = sections.first()
        assertEquals(30.0, eur.total.displayAmount, 0.0)
        assertEquals(2, eur.total.totalTransactionCount)
        assertEquals(20.0, eur.categories.getValue("Food").displayAmount, 0.0)
        assertEquals(10.0, eur.categories.getValue("Travel").displayAmount, 0.0)
        assertEquals(eur.total.displayAmount, eur.categories.values.sumOf { it.displayAmount }, 0.0)
        assertEquals(eur.total.totalTransactionCount, eur.categories.values.sumOf { it.totalTransactionCount })
        assertEquals(7.0, sections.last().total.displayAmount, 0.0)
        assertEquals(CurrencyCode.USD, sections.last().total.displayCurrency)
        assertEquals(RateBasis.IDENTITY, eur.total.actualRateBasis)
        io.mockk.verify { converter wasNot io.mockk.Called }
    }

    @Test
    fun `source sections keep empty valid input separate from unresolved currency`() {
        assertTrue(exporter.generateSourceSections(emptyList(), emptyMap()).isEmpty())
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            exporter.generateSourceSections(listOf(expense(1L, 20.0, "XYZ")), emptyMap())
        }
        val sections = exporter.generateSourceSections(listOf(
            expense(1L, 20.0, "EUR").copy(transactionType = TransactionType.DEPOSIT)
        ), emptyMap(), transactionTypeFilter = null)
        assertEquals(20.0, sections.single().total.displayAmount, 0.0)
        assertEquals(1, sections.single().total.totalTransactionCount)
    }

    @Test
    fun `deductible currency aliases share one source bucket and latest conversion`() = runTest {
        coEvery { converter.convertOutcome(25.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE, null, any()) } returns
            ConversionOutcome.Converted(25.0, CurrencyCode.USD, 12.5, CurrencyCode.EUR, 0.5,
                RateBasis.LATEST_AVAILABLE, null, null, "TEST", ConversionPath.DIRECT)
        val aggregate = exporter.generateDeductibleAggregate(listOf(
            expense(1L, 10.0, " eur "), expense(2L, 20.0, " usd "), expense(3L, 5.0, "USD")
        ), "EUR")
        assertEquals(22.5, aggregate.displayAmount, 0.0)
        assertEquals(2, aggregate.sourceBuckets.size)
        assertEquals(3, aggregate.totalTransactionCount)
        val usd = aggregate.sourceBuckets.single { it.currency == CurrencyCode.USD }
        assertEquals(25.0, usd.amount, 0.0)
        assertEquals(2, usd.transactionCount)
        coVerify(exactly = 1) { converter.convertOutcome(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `deductible totals preserve shared ownership and three source currencies`() = runTest {
        coEvery { converter.convertOutcome(any(), any(), "EUR", RateBasis.LATEST_AVAILABLE, null, any()) } answers {
            val amount = firstArg<Double>()
            val currency = CurrencyCode(arg<String>(1))
            val rate = if (currency == CurrencyCode.USD) 0.5 else 2.0
            ConversionOutcome.Converted(amount, currency, amount * rate, CurrencyCode.EUR, rate,
                RateBasis.LATEST_AVAILABLE, null, null, "TEST", ConversionPath.DIRECT)
        }
        val aggregate = exporter.generateDeductibleAggregate(listOf(
            expense(1L, 20.0, "EUR"),
            expense(2L, 40.0, "USD").copy(isSharedExpense = true, mySharePercentage = 30,
                date = 1_600_000_000_000L),
            expense(3L, 400.0, "GBP").copy(isSharedExpense = true, myShareAmount = 40.0,
                date = 1_500_000_000_000L),
            expense(4L, 1000.0, "USD").copy(isNotMine = true),
            expense(5L, 500.0, "XYZ").copy(isBusinessExpense = false)
        ), "EUR")
        assertEquals(106.0, aggregate.displayAmount, 0.0)
        assertEquals(4, aggregate.totalTransactionCount)
        assertEquals(4, aggregate.metadata.includedTransactionCount)
        assertEquals(0, aggregate.failedTransactionCount)
        assertEquals(3, aggregate.sourceBuckets.size)
        assertEquals(12.0, aggregate.sourceBuckets.single { it.currency == CurrencyCode.USD }.amount, 0.0)
        assertEquals(2, aggregate.sourceBuckets.single { it.currency == CurrencyCode.USD }.transactionCount)
        assertEquals(40.0, aggregate.sourceBuckets.single { it.currency == CurrencyCode.GBP }.amount, 0.0)
        coVerify(exactly = 1) { converter.convertOutcome(12.0, "USD", "EUR", RateBasis.LATEST_AVAILABLE, null, any()) }
        coVerify(exactly = 1) { converter.convertOutcome(40.0, "GBP", "EUR", RateBasis.LATEST_AVAILABLE, null, any()) }
        coVerify(exactly = 2) { converter.convertOutcome(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid deductible target cannot become empty available zero`() = runTest {
        for (currency in listOf("", "XXX", "ZZZ")) {
            val error = runCatching { exporter.generateDeductibleAggregate(emptyList(), currency) }.exceptionOrNull()
            assertTrue(error is MoneyDisplayUnavailableException)
            assertEquals(MoneyDisplayUnavailableReasonCode.INVALID_TARGET_CURRENCY,
                (error as MoneyDisplayUnavailableException).reasonCode)
        }
        coVerify(exactly = 0) { converter.convertOutcome(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `invalid deductible source rejects whole input before any conversion`() = runTest {
        for (currency in listOf("", "XXX", "ZZZ")) {
            val error = runCatching {
                exporter.generateDeductibleAggregate(listOf(
                    expense(1L, 20.0, "USD"), expense(2L, 10.0, currency)
                ), "EUR")
            }.exceptionOrNull()
            assertTrue(error is MoneyDisplayUnavailableException)
            assertEquals(MoneyDisplayUnavailableReasonCode.INVALID_SOURCE_CURRENCY,
                (error as MoneyDisplayUnavailableException).reasonCode)
        }
        coVerify(exactly = 0) { converter.convertOutcome(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `deductible conversion cancellation propagates unchanged`() = runTest {
        val cancellation = CancellationException("TEST_CANCELLED")
        coEvery { converter.convertOutcome(any(), any(), any(), any(), any(), any()) } throws cancellation
        val error = runCatching {
            exporter.generateDeductibleAggregate(listOf(expense(1L, 20.0, "USD")), "EUR")
        }.exceptionOrNull()
        assertSame(cancellation, error)
    }

    @Test
    fun `source categories retain unassigned rows aliases and effective shared amounts`() {
        val sections = exporter.generateSourceSections(listOf(
            expense(1L, 80.0, " eur ").copy(categoryId = 1L, isSharedExpense = true, mySharePercentage = 25),
            expense(2L, 10.0, "EUR").copy(categoryId = 2L),
            expense(3L, 3.0, "EUR").copy(categoryId = null),
            expense(4L, 7.0, "EUR").copy(categoryId = 99L),
            expense(5L, 1000.0, "EUR").copy(categoryId = 1L, isNotMine = true),
            expense(6L, 7.0, " gbp ")
        ), mapOf(1L to "Food", 2L to "Food"))
        assertEquals(listOf("EUR", "GBP"), sections.map { it.currency })
        val eur = sections.first()
        assertEquals(40.0, eur.total.displayAmount, 0.0)
        assertEquals(5, eur.total.totalTransactionCount)
        assertEquals(30.0, eur.categories.getValue("Food").displayAmount, 0.0)
        assertEquals(3, eur.categories.getValue("Food").totalTransactionCount)
        assertEquals(10.0, eur.categories.getValue("Uncategorized").displayAmount, 0.0)
        assertEquals(2, eur.categories.getValue("Uncategorized").totalTransactionCount)
        for (section in sections) {
            assertEquals(section.total.displayAmount, section.categories.values.sumOf { it.displayAmount }, 0.0)
            assertEquals(section.total.totalTransactionCount,
                section.categories.values.sumOf { it.totalTransactionCount })
            assertEquals(CurrencyCode(section.currency), section.total.displayCurrency)
            assertEquals(RateBasis.IDENTITY, section.total.actualRateBasis)
        }
        io.mockk.verify { converter wasNot io.mockk.Called }
    }

    @Test
    fun `zero source section retains rows and is not an empty input`() {
        val sections = exporter.generateSourceSections(listOf(
            expense(1L, 0.0, "GBP"), expense(2L, 100.0, "GBP").copy(isNotMine = true)
        ), emptyMap())
        val section = sections.single()
        assertEquals(0.0, section.total.displayAmount, 0.0)
        assertEquals(2, section.total.totalTransactionCount)
        assertEquals(2, section.categories.getValue("Uncategorized").totalTransactionCount)
        assertEquals(com.yourname.expensetracker.domain.core.money.ConversionQuality.COMPLETE,
            section.total.conversionQuality)
        assertTrue(exporter.generateSourceSections(emptyList(), emptyMap()).isEmpty())
        io.mockk.verify { converter wasNot io.mockk.Called }
    }

    @Test
    fun `unsupported source fails public export before allocating Android PDF`() {
        for (currency in listOf("", "XXX", "ZZZ")) {
            val error = assertThrows(IllegalStateException::class.java) {
                exporter.export(listOf(expense(1L, 10.0, currency)), emptyMap(),
                    1_600_000_000_000L, 1_700_000_000_000L)
            }
            assertEquals("ACCOUNTING_REPORT_CURRENCY_UNAVAILABLE", error.message)
        }
        io.mockk.verify { converter wasNot io.mockk.Called }
    }

    @Test
    fun `invalid source amounts cannot become formatted zero`() {
        for (amount in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val error = assertThrows(IllegalStateException::class.java) {
                exporter.generateSourceSections(listOf(expense(1L, amount, "EUR")), emptyMap())
            }
            assertEquals("ACCOUNTING_REPORT_CURRENCY_UNAVAILABLE", error.message)
        }
        io.mockk.verify { converter wasNot io.mockk.Called }
    }

    @Test
    fun `source currency validation applies only to selected transaction types`() {
        val rows = listOf(
            expense(1L, 10.0, "USD"),
            expense(2L, 100.0, "ZZZ").copy(transactionType = TransactionType.DEPOSIT)
        )
        val section = exporter.generateSourceSections(rows, emptyMap()).single()
        assertEquals("USD", section.currency)
        assertEquals(10.0, section.total.displayAmount, 0.0)
        assertEquals(1, section.total.totalTransactionCount)
        assertThrows(IllegalStateException::class.java) {
            exporter.generateSourceSections(rows, emptyMap(), transactionTypeFilter = null)
        }
        io.mockk.verify { converter wasNot io.mockk.Called }
    }

    private fun expense(id: Long, amount: Double, currency: String) = Expense(
        id = id, amount = amount, merchant = "Merchant", currency = currency,
        transactionType = TransactionType.PURCHASE,
        date = 1_700_000_000_000L, isBusinessExpense = true
    )
}
