package com.yourname.expensetracker.domain.alerts

import com.yourname.expensetracker.data.database.dao.AnomalyAlertDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.AnomalyAlert
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.model.ExpenseWithCategory
import com.yourname.expensetracker.domain.analytics.AnomalyDetector
import com.yourname.expensetracker.domain.analytics.AnomalyMethod
import com.yourname.expensetracker.domain.analytics.AnomalyTransaction
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.service.NotificationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import kotlin.coroutines.cancellation.CancellationException

class AnomalyAlertOrchestratorTest {

    private val anomalyDetector = mockk<AnomalyDetector>()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>()
    private val anomalyAlertDao = mockk<AnomalyAlertDao>()
    private val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>()
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>()
    private val timeProvider = FakeTimeProvider(FIXED_NOW)

    private lateinit var orchestrator: AnomalyAlertOrchestrator

    @Before
    fun setup() {
        orchestrator = AnomalyAlertOrchestrator(
            anomalyDetector = anomalyDetector,
            notificationService = notificationService,
            expenseDao = expenseDao,
            anomalyAlertRepository = object : AnomalyAlertRepository {
                override suspend fun getLastAlertForExpense(expenseId: Long): StoredAnomalyAlert? {
                    return anomalyAlertDao.getLastAlertForExpense(expenseId)?.toStoredAlert()
                }

                override suspend fun getLastAlertForMerchant(merchant: String, sinceMs: Long): StoredAnomalyAlert? {
                    return anomalyAlertDao.getLastAlertForMerchant(merchant, sinceMs)?.toStoredAlert()
                }

                override suspend fun getLastAlertForCategory(category: String, sinceMs: Long): StoredAnomalyAlert? {
                    return anomalyAlertDao.getLastAlertForCategory(category, sinceMs)?.toStoredAlert()
                }

                override suspend fun getLooksNormalCountForMerchant(merchant: String): Int {
                    return anomalyAlertDao.getLooksNormalCountForMerchant(merchant)
                }

                override suspend fun insert(alert: NewAnomalyAlert): Long {
                    return anomalyAlertDao.insert(
                        AnomalyAlert(
                            expenseId = alert.expenseId,
                            merchant = alert.merchant,
                            category = alert.category,
                            amount = alert.amount,
                            anomalyReason = alert.anomalyReason,
                            severity = alert.severity,
                            alertedAt = alert.alertedAt
                        )
                    )
                }
            },
            analyticsCurrencyNormalizer = analyticsCurrencyNormalizer,
            currencySettingsRepository = currencySettingsRepository,
            timeProvider = timeProvider
        )

        every { currencySettingsRepository.homeCurrency() } returns kotlinx.coroutines.flow.flowOf("EUR")
        coEvery { currencySettingsRepository.resolveHomeCurrency() } returns HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))
        coEvery { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) } answers {
            val expenses = firstArg<List<com.yourname.expensetracker.domain.model.ExpenseSnapshot>>()
            AnalyticsNormalizationResult(
                homeCurrency = secondArg(),
                normalizedExpenses = expenses.map {
                    NormalizedExpenseSnapshot(
                        snapshot = it,
                        originalCurrency = it.currency,
                        originalEffectiveAmount = it.effectiveAmount,
                        normalizedEffectiveAmount = it.effectiveAmount
                    )
                },
                includedExpenses = expenses,
                warnings = emptyList(),
                latestRateTimestamp = null
            )
        }
    }

    @Test
    fun `checkAndAlert fetches 90-day category history and passes context to detector`() = runTest {
        val expense = expenseWithCategory(id = 100L, merchant = "Mega Mart", categoryId = 7L, categoryName = "Groceries")
        val historical = expenseEntity(id = 101L, merchant = "Mega Mart", categoryId = 7L, amount = 18.0)
        val duplicateCurrent = expense.expense.copy()

        coEvery { expenseDao.getExpensesByCategory(7L, any(), any()) } returns listOf(historical, duplicateCurrent)
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns emptyList()

        orchestrator.checkAndAlert(expense)

        val expectedLookback = TimePeriodUtils.addDays(FIXED_NOW, -90)
        coVerify(exactly = 1) { expenseDao.getExpensesByCategory(7L, expectedLookback, FIXED_NOW) }

        val allExpensesSlot = slot<List<com.yourname.expensetracker.domain.model.ExpenseSnapshot>>()
        verify {
            anomalyDetector.detect(
                any(),
                any(),
                capture(allExpensesSlot),
                "EUR"
            )
        }

        val passedIds = allExpensesSlot.captured.map { it.id }
        assertTrue(passedIds.contains(101L))
        assertTrue(passedIds.contains(100L))
        assertEquals("Current expense should be included once", 1, passedIds.count { it == 100L })

        coVerify(exactly = 0) { anomalyAlertDao.insert(any()) }
        verify(exactly = 0) { notificationService.sendAnomalyAlert(any(), any(), any(), any()) }
    }

    @Test
    fun `checkAndAlert triggers alert for high confidence anomaly`() = runTest {
        val expense = expenseWithCategory(id = 200L, merchant = "High Risk Shop", categoryId = 3L, categoryName = "Travel")
        val anomaly = anomalyFor(expense.expense, deviation = 6.2f, method = AnomalyMethod.IQR)

        coEvery { expenseDao.getExpensesByCategory(eq(3L), any(), any()) } returns emptyList()
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns listOf(anomaly)
        coEvery { anomalyAlertDao.getLastAlertForExpense(200L) } returns null
        coEvery { anomalyAlertDao.getLastAlertForMerchant("High Risk Shop", any()) } returns null
        coEvery { anomalyAlertDao.getLastAlertForCategory("Travel", any()) } returns null
        coEvery { anomalyAlertDao.getLooksNormalCountForMerchant("High Risk Shop") } returns 0
        coEvery { anomalyAlertDao.insert(any()) } returns 9001L

        val alertSlot = slot<AnomalyAlert>()
        val messageSlot = slot<String>()

        orchestrator.checkAndAlert(expense)

        coVerify(exactly = 1) { anomalyAlertDao.insert(capture(alertSlot)) }
        verify(exactly = 1) {
            notificationService.sendAnomalyAlert(
                any(),
                "Unusual Charge Detected",
                capture(messageSlot),
                200L
            )
        }

        assertEquals("HIGH", alertSlot.captured.severity)
        assertEquals(200L, alertSlot.captured.expenseId)
        assertTrue(messageSlot.captured.contains("High Risk Shop"))
        assertTrue(messageSlot.captured.contains("120.00") || messageSlot.captured.contains("€120.00"))
    }

    @Test
    fun `checkAndAlert suppresses when merchant cooldown is active within 24h`() = runTest {
        val expense = expenseWithCategory(id = 300L, merchant = "Cooldown Merchant", categoryId = 1L, categoryName = "Food")
        val anomaly = anomalyFor(expense.expense, deviation = 7.0f, method = AnomalyMethod.MAD)

        coEvery { expenseDao.getExpensesByCategory(eq(1L), any(), any()) } returns emptyList()
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns listOf(anomaly)
        coEvery { anomalyAlertDao.getLastAlertForExpense(300L) } returns null
        coEvery { anomalyAlertDao.getLastAlertForMerchant("Cooldown Merchant", any()) } returns previousAlert(merchant = "Cooldown Merchant")

        orchestrator.checkAndAlert(expense)

        coVerify(exactly = 0) { anomalyAlertDao.getLastAlertForCategory(any(), any()) }
        coVerify(exactly = 0) { anomalyAlertDao.insert(any()) }
        verify(exactly = 0) { notificationService.sendAnomalyAlert(any(), any(), any(), any()) }
    }

    @Test
    fun `checkAndAlert suppresses when category cooldown is active within 12h`() = runTest {
        val expense = expenseWithCategory(id = 301L, merchant = "Shop A", categoryId = 2L, categoryName = "Entertainment")
        val anomaly = anomalyFor(expense.expense, deviation = 5.1f, method = AnomalyMethod.IQR)

        coEvery { expenseDao.getExpensesByCategory(eq(2L), any(), any()) } returns emptyList()
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns listOf(anomaly)
        coEvery { anomalyAlertDao.getLastAlertForExpense(301L) } returns null
        coEvery { anomalyAlertDao.getLastAlertForMerchant("Shop A", any()) } returns null
        coEvery { anomalyAlertDao.getLastAlertForCategory("Entertainment", any()) } returns previousAlert(merchant = "Other")

        orchestrator.checkAndAlert(expense)

        coVerify(exactly = 0) { anomalyAlertDao.insert(any()) }
        verify(exactly = 0) { notificationService.sendAnomalyAlert(any(), any(), any(), any()) }
    }

    @Test
    fun `checkAndAlert deduplicates and never alerts same expense twice`() = runTest {
        val expense = expenseWithCategory(id = 400L, merchant = "Duplicate Merchant", categoryId = 6L, categoryName = "Shopping")
        val anomaly = anomalyFor(expense.expense, deviation = 8.0f, method = AnomalyMethod.MAD)

        coEvery { expenseDao.getExpensesByCategory(eq(6L), any(), any()) } returns emptyList()
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns listOf(anomaly)
        coEvery { anomalyAlertDao.getLastAlertForExpense(400L) } returns previousAlert(expenseId = 400L, merchant = "Duplicate Merchant")

        orchestrator.checkAndAlert(expense)

        coVerify(exactly = 0) { anomalyAlertDao.getLastAlertForMerchant(any(), any()) }
        coVerify(exactly = 0) { anomalyAlertDao.insert(any()) }
        verify(exactly = 0) { notificationService.sendAnomalyAlert(any(), any(), any(), any()) }
    }

    @Test
    fun `checkAndAlert uses single-flight guard for concurrent calls on same expense`() = runTest {
        val expense = expenseWithCategory(id = 450L, merchant = "Single Flight Merchant", categoryId = 8L, categoryName = "Shopping")
        val anomaly = anomalyFor(expense.expense, deviation = 8.5f, method = AnomalyMethod.MAD)
        val historyGate = CompletableDeferred<Unit>()

        coEvery { expenseDao.getExpensesByCategory(eq(8L), any(), any()) } coAnswers {
            historyGate.await()
            emptyList()
        }
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns listOf(anomaly)
        coEvery { anomalyAlertDao.getLastAlertForExpense(450L) } returns null
        coEvery { anomalyAlertDao.getLastAlertForMerchant("Single Flight Merchant", any()) } returns null
        coEvery { anomalyAlertDao.getLastAlertForCategory("Shopping", any()) } returns null
        coEvery { anomalyAlertDao.getLooksNormalCountForMerchant("Single Flight Merchant") } returns 0
        coEvery { anomalyAlertDao.insert(any()) } returns 9450L

        val firstCall = async { orchestrator.checkAndAlert(expense) }
        runCurrent()

        val secondCall = async { orchestrator.checkAndAlert(expense) }
        runCurrent()

        historyGate.complete(Unit)
        advanceUntilIdle()

        firstCall.await()
        secondCall.await()

        coVerify(exactly = 1) { expenseDao.getExpensesByCategory(eq(8L), any(), any()) }
        coVerify(exactly = 1) { anomalyAlertDao.getLastAlertForExpense(450L) }
        coVerify(exactly = 1) { anomalyAlertDao.insert(any()) }
        verify(exactly = 1) { notificationService.sendAnomalyAlert(any(), any(), any(), 450L) }
    }

    @Test
    fun `checkAndAlert respects looks_normal feedback and suppresses future moderate anomalies`() = runTest {
        val expense = expenseWithCategory(id = 500L, merchant = "Trusted Merchant", categoryId = 9L, categoryName = "Utilities")
        val anomaly = anomalyFor(expense.expense, deviation = 4.2f, method = AnomalyMethod.IQR)

        coEvery { expenseDao.getExpensesByCategory(eq(9L), any(), any()) } returns emptyList()
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns listOf(anomaly)
        coEvery { anomalyAlertDao.getLastAlertForExpense(500L) } returns null
        coEvery { anomalyAlertDao.getLastAlertForMerchant("Trusted Merchant", any()) } returns null
        coEvery { anomalyAlertDao.getLastAlertForCategory("Utilities", any()) } returns null
        coEvery { anomalyAlertDao.getLooksNormalCountForMerchant("Trusted Merchant") } returns 3

        orchestrator.checkAndAlert(expense)

        coVerify(exactly = 0) { anomalyAlertDao.insert(any()) }
        verify(exactly = 0) { notificationService.sendAnomalyAlert(any(), any(), any(), any()) }
    }

    @Test
    fun `checkAndAlert handles no history first transaction and sends no alert when detector returns none`() = runTest {
        val expense = expenseWithCategory(id = 600L, merchant = "First Purchase", categoryId = 11L, categoryName = "New Category")

        coEvery { expenseDao.getExpensesByCategory(eq(11L), any(), any()) } returns emptyList()
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns emptyList()

        orchestrator.checkAndAlert(expense)

        verify(exactly = 1) { anomalyDetector.detect(any(), any(), any(), any()) }
        coVerify(exactly = 0) { anomalyAlertDao.insert(any()) }
        verify(exactly = 0) { notificationService.sendAnomalyAlert(any(), any(), any(), any()) }
    }

    @Test
    fun `checkAndAlert edge case with no category skips history fetch and detector can still run`() = runTest {
        val uncategorized = ExpenseWithCategory(
            expense = Expense(
                id = 700L,
                amount = 19.99,
                merchant = "No Category Shop",
                transactionType = TransactionType.PURCHASE,
                date = FIXED_NOW - 1_000L,
                categoryId = null
            ),
            category = null
        )

        every { anomalyDetector.detect(any(), any(), any(), any()) } returns emptyList()

        orchestrator.checkAndAlert(uncategorized)

        coVerify(exactly = 0) { expenseDao.getExpensesByCategory(any(), any(), any()) }
        verify(exactly = 1) { anomalyDetector.detect(any(), any(), any(), any()) }
    }

    private fun expenseWithCategory(
        id: Long,
        merchant: String,
        categoryId: Long,
        categoryName: String,
        amount: Double = 120.0,
        date: Long = FIXED_NOW - 10_000L
    ): ExpenseWithCategory {
        val category = Category(
            id = categoryId,
            name = categoryName,
            icon = "🧪",
            color = "#123456"
        )
        return ExpenseWithCategory(
            expense = expenseEntity(
                id = id,
                merchant = merchant,
                categoryId = categoryId,
                amount = amount,
                date = date
            ),
            category = category
        )
    }

    private fun expenseEntity(
        id: Long,
        merchant: String,
        categoryId: Long,
        amount: Double,
        date: Long = FIXED_NOW - 20_000L
    ): Expense = Expense(
        id = id,
        amount = amount,
        merchant = merchant,
        transactionType = TransactionType.PURCHASE,
        date = date,
        categoryId = categoryId,
        isNotMine = false
    )

    private fun anomalyFor(
        expense: Expense,
        deviation: Float,
        method: AnomalyMethod
    ): AnomalyTransaction = AnomalyTransaction(
        expense = com.yourname.expensetracker.domain.analytics.AnalyticsTransactionSummary(
            id = expense.id,
            amount = expense.effectiveAmount,
            effectiveAmount = expense.effectiveAmount,
            currency = expense.currency,
            merchant = expense.merchant,
            date = expense.date,
            categoryId = expense.categoryId
        ),
        merchantAvg = 20.0,
        deviationMultiple = deviation,
        category = null,
        detectionMethod = method,
        categoryAvg = 20.0,
        displayCurrency = "EUR",
    )

    private fun previousAlert(
        expenseId: Long = 1L,
        merchant: String,
        alertedAt: Long = FIXED_NOW - 1_000L
    ): AnomalyAlert = AnomalyAlert(
        id = 55L,
        expenseId = expenseId,
        merchant = merchant,
        category = "Some Category",
        amount = 10.0,
        anomalyReason = "test",
        severity = "HIGH",
        alertedAt = alertedAt
    )

    private fun AnomalyAlert.toStoredAlert(): StoredAnomalyAlert {
        return StoredAnomalyAlert(
            id = id,
            expenseId = expenseId,
            merchant = merchant,
            category = category,
            amount = amount,
            anomalyReason = anomalyReason,
            severity = severity,
            alertedAt = alertedAt,
            dismissed = dismissed,
            dismissedAt = dismissedAt,
            userFeedback = userFeedback
        )
    }

    companion object {
        private const val FIXED_NOW = 1_730_000_000_000L
    }

    @Test
    fun `checkAndAlert propagates CancellationException instead of logging and swallowing`() = runTest {
        val expense = expenseWithCategory(id = 800L, merchant = "Cancel Shop", categoryId = 5L, categoryName = "Test")

        // Arrange: make the DAO call throw CancellationException
        coEvery { expenseDao.getExpensesByCategory(eq(5L), any(), any()) } throws CancellationException("test cancellation")

        // Act + Assert: CancellationException must propagate
        try {
            orchestrator.checkAndAlert(expense)
            fail("Expected CancellationException to propagate")
        } catch (e: CancellationException) {
            // expected — cancellation was correctly rethrown
        }

        // Verify no alert was persisted or sent
        coVerify(exactly = 0) { anomalyAlertDao.insert(any()) }
        verify(exactly = 0) { notificationService.sendAnomalyAlert(any(), any(), any(), any()) }
    }
}