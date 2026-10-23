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
import com.yourname.expensetracker.domain.analytics.MonthPeriod
import com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.NotificationId
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.service.NotificationService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

class AnomalyAlertOrchestratorTest {

    private val anomalyDetector = mockk<AnomalyDetector>()
    private val notificationService = mockk<NotificationService>(relaxed = true)
    private val expenseDao = mockk<ExpenseDao>()
    private val anomalyAlertDao = mockk<AnomalyAlertDao>()
    private val analyticsCurrencyNormalizer = mockk<AnalyticsCurrencyNormalizer>()
    private val currencySettingsRepository = mockk<CurrencySettingsRepository>()
    private val timeProvider = FakeTimeProvider(FIXED_NOW)

    /** One captured Timber call: priority, already-formatted message and the throwable (if any). */
    private data class LogEntry(val priority: Int, val message: String, val throwable: Throwable?)

    private class CapturingTree : Timber.Tree() {
        val entries = mutableListOf<LogEntry>()

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            entries += LogEntry(priority, message, t)
        }
    }

    private val capturingTree = CapturingTree()

    private lateinit var orchestrator: AnomalyAlertOrchestrator

    @After
    fun tearDownLogCapture() {
        // Timber.uproot throws for a tree that was never planted, so guard on membership.
        if (Timber.forest().contains(capturingTree)) {
            Timber.uproot(capturingTree)
        }
    }

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

        // Explicit result: a relaxed mock would otherwise return an arbitrary enum constant.
        coEvery {
            notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any())
        } returns NotificationService.DeliveryResult.DELIVERED
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
        coVerify(exactly = 0) { notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any()) }
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
        coVerify(exactly = 1) {
            notificationService.postAnomalyAlertForKind(
                NotificationKind.ANOMALY,
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
        coVerify(exactly = 0) { notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any()) }
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
        coVerify(exactly = 0) { notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any()) }
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
        coVerify(exactly = 1) {
            notificationService.postAnomalyAlertForKind(NotificationKind.ANOMALY, any(), any(), any(), 450L)
        }
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
        coVerify(exactly = 0) { notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `checkAndAlert handles no history first transaction and sends no alert when detector returns none`() = runTest {
        val expense = expenseWithCategory(id = 600L, merchant = "First Purchase", categoryId = 11L, categoryName = "New Category")

        coEvery { expenseDao.getExpensesByCategory(eq(11L), any(), any()) } returns emptyList()
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns emptyList()

        orchestrator.checkAndAlert(expense)

        verify(exactly = 1) { anomalyDetector.detect(any(), any(), any(), any()) }
        coVerify(exactly = 0) { anomalyAlertDao.insert(any()) }
        coVerify(exactly = 0) { notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any()) }
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

    @Test
    fun `checkAndAlert builds January detection period with 0-based month and exact 90-day window`() = runTest {
        val janNow = FakeTimeProvider.forDate(2026, 1, 15).now()
        timeProvider.setTime(janNow)

        val expense = expenseWithCategory(
            id = 810L,
            merchant = "January Shop",
            categoryId = 10L,
            categoryName = "Winter"
        )
        coEvery { expenseDao.getExpensesByCategory(eq(10L), any(), any()) } returns emptyList()

        val monthPeriodSlot = slot<MonthPeriod>()
        every { anomalyDetector.detect(capture(monthPeriodSlot), any(), any(), any()) } returns emptyList()

        orchestrator.checkAndAlert(expense)

        val period = monthPeriodSlot.captured
        assertEquals(2026, period.year)
        assertEquals(0, period.month) // Calendar.JANUARY = 0
        assertEquals(TimePeriodUtils.addDays(janNow, -90), period.startMs)
        assertEquals(janNow + 1L, period.endMs)

        coVerify(exactly = 1) { expenseDao.getExpensesByCategory(eq(10L), TimePeriodUtils.addDays(janNow, -90), janNow) }
    }

    @Test
    fun `checkAndAlert builds December detection period with 0-based month and exact 90-day window`() = runTest {
        val decNow = FakeTimeProvider.forDate(2026, 12, 15).now()
        timeProvider.setTime(decNow)

        val expense = expenseWithCategory(
            id = 811L,
            merchant = "December Shop",
            categoryId = 11L,
            categoryName = "Holiday"
        )
        coEvery { expenseDao.getExpensesByCategory(eq(11L), any(), any()) } returns emptyList()

        val monthPeriodSlot = slot<MonthPeriod>()
        every { anomalyDetector.detect(capture(monthPeriodSlot), any(), any(), any()) } returns emptyList()

        orchestrator.checkAndAlert(expense)

        val period = monthPeriodSlot.captured
        assertEquals(2026, period.year)
        assertEquals(11, period.month) // Calendar.DECEMBER = 11
        assertEquals(TimePeriodUtils.addDays(decNow, -90), period.startMs)
        assertEquals(decNow + 1L, period.endMs)

        coVerify(exactly = 1) { expenseDao.getExpensesByCategory(eq(11L), TimePeriodUtils.addDays(decNow, -90), decNow) }
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

    // ------------------------------------------------------------------------
    // S6 (INV-12, AE-13, AE-11, C7): kind/id declaration, PERMISSION_DENIED parity, log privacy
    // ------------------------------------------------------------------------

    /** Stubs everything so [expense] is a HIGH-confidence anomaly that passes every cooldown check. */
    private fun arrangeAlertable(expense: ExpenseWithCategory, insertedId: Long = 9100L) {
        val categoryId = expense.expense.categoryId!!
        val merchant = expense.expense.merchant
        val categoryName = expense.category!!.name
        coEvery { expenseDao.getExpensesByCategory(eq(categoryId), any(), any()) } returns emptyList()
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns
            listOf(anomalyFor(expense.expense, deviation = 6.2f, method = AnomalyMethod.IQR))
        coEvery { anomalyAlertDao.getLastAlertForExpense(expense.expense.id) } returns null
        coEvery { anomalyAlertDao.getLastAlertForMerchant(merchant, any()) } returns null
        coEvery { anomalyAlertDao.getLastAlertForCategory(categoryName, any()) } returns null
        coEvery { anomalyAlertDao.getLooksNormalCountForMerchant(merchant) } returns 0
        coEvery { anomalyAlertDao.insert(any()) } returns insertedId
    }

    @Test
    fun `INV-12 AE-13 anomaly alert declares kind ANOMALY with id equal to NotificationId forGeneral of the expense`() = runTest {
        for (expenseId in listOf(910L, 911L)) {
            val expense = expenseWithCategory(id = expenseId, merchant = "Kind Shop $expenseId", categoryId = 20L + expenseId, categoryName = "Kind $expenseId")
            arrangeAlertable(expense)

            orchestrator.checkAndAlert(expense)

            val expectedValue = NotificationId.forGeneral(expenseId).value
            coVerify(exactly = 1) {
                notificationService.postAnomalyAlertForKind(
                    NotificationKind.ANOMALY,
                    match { it.value == expectedValue },
                    "Unusual Charge Detected",
                    any(),
                    expenseId
                )
            }
        }
        coVerify(exactly = 2) { notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `INV-12 AE-11 PERMISSION_DENIED inserts the alert exactly once before the post and no exception escapes`() = runTest {
        val expense = expenseWithCategory(id = 920L, merchant = "Denied Shop", categoryId = 21L, categoryName = "Denied")
        arrangeAlertable(expense)
        coEvery {
            notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any())
        } returns NotificationService.DeliveryResult.PERMISSION_DENIED

        orchestrator.checkAndAlert(expense)

        coVerify(exactly = 1) { anomalyAlertDao.insert(any()) }
        coVerify(exactly = 1) {
            notificationService.postAnomalyAlertForKind(NotificationKind.ANOMALY, any(), any(), any(), 920L)
        }
        coVerifyOrder {
            anomalyAlertDao.insert(any())
            notificationService.postAnomalyAlertForKind(NotificationKind.ANOMALY, any(), any(), any(), 920L)
        }
        // The in-flight guard is released: the same expense is processed again, not skipped.
        orchestrator.checkAndAlert(expense)
        coVerify(exactly = 2) {
            notificationService.postAnomalyAlertForKind(NotificationKind.ANOMALY, any(), any(), any(), 920L)
        }
    }

    @Test
    fun `INV-12 AE-11 PERMISSION_DENIED has the same persistence effects as NOT_DELIVERED and DELIVERED`() = runTest {
        val results = listOf(
            NotificationService.DeliveryResult.DELIVERED,
            NotificationService.DeliveryResult.NOT_DELIVERED,
            NotificationService.DeliveryResult.PERMISSION_DENIED
        )
        assertEquals("row count guard", 3, results.size)
        for ((index, result) in results.withIndex()) {
            val expenseId = 930L + index
            val expense = expenseWithCategory(id = expenseId, merchant = "Parity Shop $index", categoryId = 30L + index, categoryName = "Parity $index")
            arrangeAlertable(expense)
            coEvery {
                notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), eq(expenseId))
            } returns result

            orchestrator.checkAndAlert(expense)

            coVerify(exactly = 1) { anomalyAlertDao.insert(match { it.expenseId == expenseId }) }
            coVerify(exactly = 1) {
                notificationService.postAnomalyAlertForKind(NotificationKind.ANOMALY, any(), any(), any(), expenseId)
            }
        }
    }

    @Test
    fun `INV-12 CancellationException from postAnomalyAlertForKind propagates after the insert and clears in-flight state`() = runTest {
        val expense = expenseWithCategory(id = 940L, merchant = "Cancel Post Shop", categoryId = 40L, categoryName = "CancelPost")
        arrangeAlertable(expense)
        coEvery {
            notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any())
        } throws CancellationException("post cancelled")

        try {
            orchestrator.checkAndAlert(expense)
            fail("Expected CancellationException from the post to propagate")
        } catch (e: CancellationException) {
            // expected - cancellation must never be logged and swallowed
        }

        coVerify(exactly = 1) { anomalyAlertDao.insert(any()) }
        coVerify(exactly = 1) {
            notificationService.postAnomalyAlertForKind(NotificationKind.ANOMALY, any(), any(), any(), 940L)
        }

        // In-flight state was released: a retry for the same expense is processed, not skipped.
        coEvery {
            notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any())
        } returns NotificationService.DeliveryResult.DELIVERED
        orchestrator.checkAndAlert(expense)
        coVerify(exactly = 2) {
            notificationService.postAnomalyAlertForKind(NotificationKind.ANOMALY, any(), any(), any(), 940L)
        }
    }

    @Test
    fun `C7 anomaly alert logs never contain the merchant or the amount for any delivery result`() = runTest {
        Timber.plant(capturingTree)
        val results = listOf(
            NotificationService.DeliveryResult.DELIVERED,
            NotificationService.DeliveryResult.NOT_DELIVERED,
            NotificationService.DeliveryResult.PERMISSION_DENIED
        )
        assertEquals("row count guard", 3, results.size)

        for ((index, result) in results.withIndex()) {
            capturingTree.entries.clear()
            val expenseId = 950L + index
            val expense = expenseWithCategory(
                id = expenseId,
                merchant = "Lidl SENTINEL",
                categoryId = 50L,
                categoryName = "Groceries",
                amount = 123.45
            )
            arrangeAlertable(expense)
            coEvery {
                notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), eq(expenseId))
            } returns result

            orchestrator.checkAndAlert(expense)

            // The path really ran to the post (otherwise the log assertions below would be vacuous).
            coVerify(exactly = 1) {
                notificationService.postAnomalyAlertForKind(NotificationKind.ANOMALY, any(), any(), any(), expenseId)
            }
            assertTrue("$result: the flow must log something so the privacy check is not vacuous", capturingTree.entries.isNotEmpty())
            for (entry in capturingTree.entries) {
                val rendered = entry.message + " " + (entry.throwable?.message ?: "")
                for (forbidden in listOf("Lidl", "SENTINEL", "123.45", "123,45", "€123")) {
                    assertFalse(
                        "$result: log must not contain '$forbidden' but was: ${entry.message}",
                        rendered.contains(forbidden)
                    )
                }
            }
            if (result == NotificationService.DeliveryResult.PERMISSION_DENIED) {
                assertTrue(
                    "PERMISSION_DENIED keeps an error-level diagnostic",
                    capturingTree.entries.any { it.priority == android.util.Log.ERROR }
                )
                for (entry in capturingTree.entries) {
                    assertNull("PERMISSION_DENIED must not log a throwable: ${entry.message}", entry.throwable)
                }
            }
        }
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
        coVerify(exactly = 0) { notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any()) }
    }

    // AE-19 (INV-19, INV-20): a CancellationException from the home-currency read propagates,
    // no alert is written or sent, and the expense id is removed from the in-flight set.
    @Test
    fun `AE-19 home currency read cancellation propagates, writes no alert and clears in-flight state`() = runTest {
        val expense = expenseWithCategory(id = 820L, merchant = "Home Cancel Shop", categoryId = 12L, categoryName = "Misc")
        val anomaly = anomalyFor(expense.expense, deviation = 9.0f, method = AnomalyMethod.MAD)

        coEvery { expenseDao.getExpensesByCategory(eq(12L), any(), any()) } returns emptyList()
        every { currencySettingsRepository.homeCurrency() } throws CancellationException("home read cancelled")
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns listOf(anomaly)
        coEvery { anomalyAlertDao.getLastAlertForExpense(820L) } returns null
        coEvery { anomalyAlertDao.getLastAlertForMerchant("Home Cancel Shop", any()) } returns null
        coEvery { anomalyAlertDao.getLastAlertForCategory("Misc", any()) } returns null
        coEvery { anomalyAlertDao.getLooksNormalCountForMerchant("Home Cancel Shop") } returns 0
        coEvery { anomalyAlertDao.insert(any()) } returns 9820L

        try {
            orchestrator.checkAndAlert(expense)
            fail("Expected CancellationException to propagate from the home currency read")
        } catch (e: CancellationException) {
            // expected - cancellation must not be converted into the expense-currency fallback
        }

        // No normalization, detection, persistence or notification happened.
        coVerify(exactly = 0) { analyticsCurrencyNormalizer.normalizeSnapshots(any(), any()) }
        verify(exactly = 0) { anomalyDetector.detect(any(), any(), any(), any()) }
        coVerify(exactly = 0) { anomalyAlertDao.insert(any()) }
        coVerify(exactly = 0) { notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any()) }

        // In-flight state was cleared: a retry for the same expense id is processed, not skipped.
        every { currencySettingsRepository.homeCurrency() } returns kotlinx.coroutines.flow.flowOf("EUR")

        orchestrator.checkAndAlert(expense)

        verify(exactly = 1) { anomalyDetector.detect(any(), any(), any(), any()) }
        coVerify(exactly = 1) { anomalyAlertDao.insert(any()) }
        coVerify(exactly = 1) {
            notificationService.postAnomalyAlertForKind(NotificationKind.ANOMALY, any(), any(), any(), 820L)
        }
    }

    // AE-19 (INV-19): any non-cancellation failure keeps today's fallback to the expense currency.
    @Test
    fun `AE-19 home currency read IOException falls back to the expense currency`() = runTest {
        val base = expenseWithCategory(id = 821L, merchant = "Home IO Shop", categoryId = 13L, categoryName = "Misc")
        val expense = base.copy(expense = base.expense.copy(currency = "USD"))

        coEvery { expenseDao.getExpensesByCategory(eq(13L), any(), any()) } returns emptyList()
        every { currencySettingsRepository.homeCurrency() } throws java.io.IOException("home read failed")
        every { anomalyDetector.detect(any(), any(), any(), any()) } returns emptyList()

        orchestrator.checkAndAlert(expense)

        coVerify(exactly = 1) { analyticsCurrencyNormalizer.normalizeSnapshots(any(), eq("USD")) }
        verify(exactly = 1) { anomalyDetector.detect(any(), any(), any(), eq("USD")) }
        coVerify(exactly = 0) { anomalyAlertDao.insert(any()) }
        coVerify(exactly = 0) { notificationService.postAnomalyAlertForKind(any(), any(), any(), any(), any()) }
    }
}