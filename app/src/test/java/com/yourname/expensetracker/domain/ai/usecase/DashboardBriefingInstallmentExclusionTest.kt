package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.ai.provider.CloudDashboardBriefingService
import com.yourname.expensetracker.data.ai.provider.DashboardBriefingPromptFormatter
import com.yourname.expensetracker.data.ai.provider.HybridDashboardBriefingService
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.repository.AnalyticsRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.DashboardContractsAdapter
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.data.repository.FinancialWeatherRepository
import com.yourname.expensetracker.data.repository.HomeInstallmentWeatherSource
import com.yourname.expensetracker.data.repository.PlannedExpenseRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.data.repository.ReviewQueueRepository
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiRoute
import com.yourname.expensetracker.domain.ai.model.AiRouteDecision
import com.yourname.expensetracker.domain.ai.model.AiSettings
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.policy.AiPolicy
import com.yourname.expensetracker.domain.ai.service.AiArtifactRepository
import com.yourname.expensetracker.domain.ai.service.AiCapabilityRouter
import com.yourname.expensetracker.domain.ai.service.AiSettingsRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCategoryBreakdown
import com.yourname.expensetracker.domain.analytics.AnalyticsCategoryRef
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.AnalyticsNormalizationResult
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.NormalizedExpenseSnapshot
import com.yourname.expensetracker.domain.core.money.ConversionFailureType
import com.yourname.expensetracker.domain.core.money.ConversionOutcome
import com.yourname.expensetracker.domain.core.money.ConversionPath
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.core.money.RateBasis
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.HomeCurrencyResolution
import com.yourname.expensetracker.domain.dto.AiArtifactRecord
import com.yourname.expensetracker.domain.forecasting.ForecastInputAssembler
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.installment.InstallmentPlanReadRepository
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentEntryLinkReader
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.projection.ProjectionEntryInput
import com.yourname.expensetracker.domain.installment.projection.ProjectionPlanInput
import com.yourname.expensetracker.domain.installment.projection.emptyInstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.projection.fakeInstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentOverview
import com.yourname.expensetracker.domain.logic.NarrativeGenerator
import com.yourname.expensetracker.domain.logic.SynthesisEngine
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.model.PlannedExpense
import com.yourname.expensetracker.domain.model.PlannedExpensePriority
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.model.UpcomingItem
import com.yourname.expensetracker.domain.model.dashboard.DashboardCategoryBreakdown
import com.yourname.expensetracker.domain.model.dashboard.FinancialWeather
import com.yourname.expensetracker.domain.model.dashboard.SpendingSummary
import com.yourname.expensetracker.domain.model.dashboard.WeatherState
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import com.yourname.expensetracker.domain.privacy.PrivacySettingsRepository
import com.yourname.expensetracker.domain.recurring.RecurringInstallmentEligibility
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardAnalyticsRepository
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardData
import com.yourname.expensetracker.domain.usecase.dashboard.DashboardDataProvider
import com.yourname.expensetracker.domain.usecase.dashboard.ProcessedDashboardData
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.TimeBoundaryTicker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import com.yourname.expensetracker.data.repository.SpendingSummary as AnalyticsSpendingSummary
import com.yourname.expensetracker.domain.savings.SavingsGoalRepository as DomainSavingsGoalRepository

/**
 * Loan-installments S8a/S8b-4, INV-31 / spec 11.5 / AE-23 (payload part), plan amendment A15
 * (ISSUE-3 differential, ISSUE-4 placeholder replacement).
 *
 * Installment plan data must never reach a cloud-bound DASHBOARD_BRIEFING payload. The seam is
 * hard-wired in [DashboardBriefingInputBuilder]: only explicitly mapped [UpcomingItem] variants
 * are copied into the payload, anything else is dropped whole before the cap. It is never a
 * user setting.
 *
 * ## End-to-end differential (S8b-4; replaces the S8a placeholders 1, 2 and 5)
 * Chain under test, run once with an EMPTY projection port and once with a NON-EMPTY one:
 *
 *   fake InstallmentProjectionPort (empty vs one plan, the REAL INV-15 projection rule behind it)
 *     -> [HomeInstallmentWeatherSource] (the only consumer of the port; non-vacuity side)
 *     -> REAL [FinancialWeatherRepository] with the REAL ForecastInputAssembler, SynthesisEngine
 *        and NarrativeGenerator (cloud side: option A, never takes the port)
 *     -> REAL [DashboardDataProvider.getProcessedDataFlow]
 *     -> REAL [GenerateDashboardBriefingUseCase] with the REAL [DashboardBriefingInputBuilder]
 *     -> REAL [CloudDashboardBriefingService] request body (policy.prepareText, redaction on and off)
 *
 * Held constant across the two runs (ISSUE-3): the expense rows, the installment link state (an
 * empty link reader), the spending-predicate state (recurring patterns, planned expense, budgets,
 * goals, home currency, time). ONLY the projection-port output varies. Assertions: the processed
 * dashboard data, the briefing input, the prompts (shouldRedact false and true), the raw cloud
 * request bodies (redactBeforeCloud false and true) and the stored artifact source hash are all
 * identical, and the same fixture proves it is not vacuous: [HomeInstallmentWeatherSource.observe]
 * is null with the empty port and non-null with the plan port, with a different total committed.
 *
 * ## Substitutions (documented, narrowest possible)
 * - The repositories and DAOs beneath FinancialWeatherRepository (expenses, budgets, recurring,
 *   planned, goals, currency settings, converter, the pattern detector) are mocks returning fixed
 *   synthetic data, as in HomeInstallmentWeatherSourceTest and FinancialWeatherRepositoryTest.
 * - DashboardContractsAdapter is the REAL production class (S8b-3/4 privacy review F1), built
 *   around the same mocks; the mocks beneath it (categories, review queue, analytics, the day
 *   ticker, the expense window query) return fixed synthetic values. Its weather methods
 *   delegate to the REAL baseline FinancialWeatherRepository.
 * - One NarrativeGenerator instance is shared by the baseline repository and the Home source.
 * - HybridDashboardBriefingService (route selection only) is a mock whose generate() forwards the
 *   exact input the use case built to the REAL CloudDashboardBriefingService, whose OkHttp client
 *   is an interceptor capturing the request body instead of a network call.
 * - AiCapabilityRouter, AiSettingsRepository, AiArtifactRepository, AiPolicy and
 *   PrivacySettingsRepository are mocks (peripheral policy and storage).
 *
 * What the other tests in this class enforce: the builder allow-list drops an unmapped
 * [UpcomingItem] variant whole before the cap, and the sealed-variant tripwire fails when a new
 * variant is added, so its author must add an explicit exclusion assertion here instead of the
 * variant silently riding along.
 */
class DashboardBriefingInstallmentExclusionTest {

    private val now = 1_700_000_000_000L
    private val builder = DashboardBriefingInputBuilder(FakeTimeProvider(fixedTime = now))
    private val formatter = DashboardBriefingPromptFormatter()

    // Plan-side facts that must never appear anywhere in a cloud payload.
    private val lenderLabel = "Zebra Lender 0% Visa"
    private val planUuid = "7c1f3e2a-plan-uuid-ae23"
    private val projectedAmountMinor = 987_654L
    private val projectedAmountText = "9876.54"
    private val projectedAmount = 9_876.54

    // Differential fixture clock: 2026-01-15 12:00 in the default zone (plan entry due 2026-01-20).
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 1, 15)
    private val fixtureNowMs = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun ms(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun jan(day: Int): LocalDate = LocalDate.of(2026, 1, day)

    private fun weather(items: List<UpcomingItem>) = FinancialWeather(
        state = WeatherState.PARTLY_CLOUDY,
        headline = UiText.from("Mostly fine"),
        summary = UiText.from("Two bills this month"),
        icon = "x",
        riskLevel = 10,
        totalCommitted = 250.0,
        totalLikely = 320.0,
        predictedDiscretionary = 400.0,
        discretionaryBudget = 600.0,
        upcomingItems = items
    )

    private fun processed(weather: FinancialWeather): ProcessedDashboardData {
        val data = DashboardData(
            expenses = emptyList(),
            categories = emptyList(),
            budgetStatuses = emptyList(),
            pendingCount = 2,
            weather = weather,
            recurringPatterns = emptyList(),
            plannedExpenses = emptyList(),
            goals = emptyList()
        )
        val summary = SpendingSummary(
            totalSpent = 300.0,
            previousTotalSpent = null,
            changePercent = null,
            dailyHistory = emptyList(),
            previousDailyHistory = emptyList(),
            transactionCount = 3
        )
        return ProcessedDashboardData(
            data = data,
            summary = summary,
            categoryBreakdown = listOf(
                DashboardCategoryBreakdown(
                    categoryId = 1L,
                    categoryName = "Food",
                    categoryIcon = "i",
                    categoryColor = "#FFFFFF",
                    amount = 120.0,
                    percentage = 40.0,
                    changeFromLastPeriod = 0.0
                )
            )
        )
    }

    // ── End-to-end differential fixture ───────────────────────────────────────

    private class EmptyLinkReader : InstallmentEntryLinkReader {
        override suspend fun linkedEntryIdFor(expenseId: Long): Long? = null
        override suspend fun isPlanPurchase(expenseId: Long): Boolean = false
        override suspend fun linkedExpenseIds(): Set<Long> = emptySet()
        override suspend fun planPurchaseExpenseIds(): Set<Long> = emptySet()
    }

    /** One complete wiring of the chain around a given projection port. Every other input is identical. */
    private inner class World(port: InstallmentProjectionPort) {
        val timeProvider = FakeTimeProvider(fixedTime = fixtureNowMs)

        private val expenseRepository = mockk<ExpenseRepository>(relaxed = true)
        private val budgetRepository = mockk<BudgetRepository>(relaxed = true)
        private val recurringExpenseRepository = mockk<RecurringExpenseRepository>(relaxed = true)
        private val plannedExpenseRepository = mockk<PlannedExpenseRepository>(relaxed = true)
        private val savingsGoalRepository = mockk<DomainSavingsGoalRepository>(relaxed = true)
        private val patternsProvider = mockk<MergedRecurringPatternsProvider>(relaxed = true)
        private val currencySettingsRepository = mockk<CurrencySettingsRepository>(relaxed = true)
        private val currencyConverter = mockk<CurrencyConverter>(relaxed = true)
        private val normalizer = mockk<AnalyticsCurrencyNormalizer>(relaxed = true)
        private val readRepository = mockk<InstallmentPlanReadRepository>(relaxed = true)
        private val appContext = mockk<android.content.Context>(relaxed = true)
        private val categoryRepository = mockk<CategoryRepository>(relaxed = true)
        private val reviewQueueRepository = mockk<ReviewQueueRepository>(relaxed = true)
        private val analyticsRepository = mockk<AnalyticsRepository>(relaxed = true)
        private val timeBoundaryTicker = mockk<TimeBoundaryTicker>(relaxed = true)

        val baseline: FinancialWeatherRepository
        val home: HomeInstallmentWeatherSource
        val provider: DashboardDataProvider
        val analytics: DashboardAnalyticsRepository

        init {
            // Expense rows, patterns and planned expense: the spending-predicate state, constant.
            every { expenseRepository.getAllExpenses() } returns flowOf(
                listOf(purchase(1L, 40.0, jan(5)), purchase(2L, 100.0, jan(10)))
            )
            every { budgetRepository.getBudgetStatuses() } returns flowOf(emptyList())
            every { recurringExpenseRepository.getAllFlow() } returns flowOf(emptyList())
            every { plannedExpenseRepository.getAllPlannedExpenses() } returns flowOf(
                listOf(
                    com.yourname.expensetracker.data.database.entity.PlannedExpense(
                        id = 7L,
                        description = "Synthetic laptop",
                        amount = 300.0,
                        date = ms(jan(18)),
                        priority = com.yourname.expensetracker.data.database.entity.PlannedExpensePriority.LIKELY
                    )
                )
            )
            every { savingsGoalRepository.observeSavingsGoals() } returns flowOf(emptyList())
            coEvery { patternsProvider.getPatternsFromSnapshots(any(), any()) } returns
                listOf(monthlyPattern("Synthetic rent", 600.0, jan(25)))
            every { patternsProvider.getConfirmedPatterns(any()) } returns emptyList()

            // Beneath the REAL DashboardContractsAdapter: fixed synthetic dashboard-side data.
            every { timeBoundaryTicker.dayBoundaryTicks() } returns flowOf(fixtureNowMs)
            every { expenseRepository.getExpensesWithCategoryInPeriod(any(), any()) } returns flowOf(emptyList())
            every { categoryRepository.allCategories } returns flowOf(emptyList())
            every { reviewQueueRepository.getPendingReviewCount() } returns flowOf(2)
            every { analyticsRepository.getSpendingSummary(any(), any()) } returns flowOf(
                AnalyticsSpendingSummary(
                    totalSpent = 140.0,
                    previousTotalSpent = null,
                    changePercent = null,
                    dailyHistory = emptyList(),
                    previousDailyHistory = emptyList(),
                    transactionCount = 2,
                    currency = "EUR"
                )
            )
            every { analyticsRepository.getCategoryBreakdown(any(), any()) } returns flowOf(
                listOf(
                    AnalyticsCategoryBreakdown(
                        category = AnalyticsCategoryRef(id = 1L, name = "Food", icon = "i", color = "#FFFFFF"),
                        moneyTotal = MoneyAmount(140.0, CurrencyCode("EUR")),
                        count = 2,
                        percentage = 100f,
                        displayCurrency = "EUR"
                    )
                )
            )

            every { currencySettingsRepository.homeCurrency() } returns flowOf("EUR")
            coEvery { currencySettingsRepository.resolveHomeCurrency() } returns
                HomeCurrencyResolution.Resolved(CurrencyCode("EUR"))

            coEvery {
                currencyConverter.convertOutcome(any(), any(), any(), any(), any(), any())
            } answers {
                val amount = firstArg<Double>()
                val source = secondArg<String>()
                val target = thirdArg<String>()
                val basis = arg<RateBasis>(3)
                if (source.equals(target, ignoreCase = true)) {
                    ConversionOutcome.Converted(
                        originalAmount = amount,
                        originalCurrency = CurrencyCode(source),
                        convertedAmount = amount,
                        targetCurrency = CurrencyCode(target),
                        rateUsed = 1.0,
                        rateBasis = basis,
                        rateValidDate = null,
                        rateLastUpdated = null,
                        rateSource = null,
                        conversionPath = ConversionPath.DIRECT
                    )
                } else {
                    ConversionOutcome.Failed(
                        originalAmount = amount,
                        originalCurrency = source,
                        targetCurrency = target,
                        rateBasis = basis,
                        failureType = ConversionFailureType.MISSING_RATE,
                        message = ConversionFailureType.MISSING_RATE.name
                    )
                }
            }
            // Pass-through normalizer: history is already in home currency.
            coEvery { normalizer.normalizeSnapshots(any(), any()) } answers {
                val snapshots = firstArg<List<ExpenseSnapshot>>()
                AnalyticsNormalizationResult(
                    homeCurrency = secondArg<String>(),
                    normalizedExpenses = snapshots.map {
                        NormalizedExpenseSnapshot(it, it.currency, it.effectiveAmount, it.effectiveAmount)
                    },
                    includedExpenses = snapshots,
                    warnings = emptyList(),
                    latestRateTimestamp = null,
                    totalInputCount = snapshots.size
                )
            }
            every { readRepository.observeOverview() } returns flowOf(mockk<InstallmentOverview>(relaxed = true))
            // PE-C: the Home source's invalidation trigger is observeChanges().
            every { readRepository.observeChanges() } returns flowOf(Unit)
            every { appContext.getString(R.string.installment_plan_fallback_label) } returns "Synthetic installment label"

            val assembler = ForecastInputAssembler(
                timeProvider = timeProvider,
                analyticsCurrencyNormalizer = normalizer,
                currencySettingsRepository = currencySettingsRepository,
                currencyConverter = currencyConverter,
                recurringLifecycleCoordinator = mockk<RecurringLifecycleCoordinator>(relaxed = true),
                recurringOccurrenceDao = mockk<RecurringOccurrenceDao>(relaxed = true),
                databaseReadBarrier = mockk(relaxed = true)
            )
            val synthesisEngine = SynthesisEngine(timeProvider, currencyConverter = currencyConverter)
            // One shared generator: both weather paths narrate with the same instance.
            val narrativeGenerator = NarrativeGenerator()

            baseline = FinancialWeatherRepository(
                expenseRepository = expenseRepository,
                budgetRepository = budgetRepository,
                recurringExpenseRepository = recurringExpenseRepository,
                mergedRecurringPatternsProvider = patternsProvider,
                plannedExpenseRepository = plannedExpenseRepository,
                savingsGoalRepository = savingsGoalRepository,
                forecastInputAssembler = assembler,
                synthesisEngine = synthesisEngine,
                narrativeGenerator = narrativeGenerator,
                analyticsRepository = analyticsRepository,
                currencySettingsRepository = currencySettingsRepository,
                timeProvider = timeProvider
            )
            home = HomeInstallmentWeatherSource(
                expenseRepository = expenseRepository,
                budgetRepository = budgetRepository,
                recurringExpenseRepository = recurringExpenseRepository,
                mergedRecurringPatternsProvider = patternsProvider,
                plannedExpenseRepository = plannedExpenseRepository,
                savingsGoalRepository = savingsGoalRepository,
                forecastInputAssembler = assembler,
                synthesisEngine = synthesisEngine,
                narrativeGenerator = narrativeGenerator,
                currencySettingsRepository = currencySettingsRepository,
                timeProvider = timeProvider,
                installmentProjectionPort = port,
                recurringInstallmentEligibility = RecurringInstallmentEligibility(
                    dagger.Lazy { EmptyLinkReader() }
                ),
                installmentPlanReadRepository = readRepository,
                context = appContext
            )

            // The REAL production adapter: the cloud path sees the weather exactly as production wires it.
            val fixture = DashboardContractsAdapter(
                expenseRepository = expenseRepository,
                categoryRepository = categoryRepository,
                budgetRepository = budgetRepository,
                reviewQueueRepository = reviewQueueRepository,
                financialWeatherRepository = baseline,
                savingsGoalRepository = savingsGoalRepository,
                analyticsRepository = analyticsRepository,
                recurringExpenseRepository = recurringExpenseRepository,
                plannedExpenseRepository = plannedExpenseRepository,
                timeBoundaryTicker = timeBoundaryTicker
            )
            analytics = fixture
            provider = DashboardDataProvider(
                expenseRepository = fixture,
                categoryRepository = fixture,
                budgetRepository = fixture,
                reviewQueueRepository = fixture,
                financialWeatherRepository = fixture,
                savingsGoalRepository = fixture,
                insightsEngine = mockk<InsightsEngine>(relaxed = true),
                synthesisEngine = synthesisEngine,
                timeProvider = timeProvider,
                analyticsCurrencyNormalizer = normalizer
            )
        }
    }

    private fun purchase(id: Long, amount: Double, date: LocalDate) = Expense(
        id = id,
        amount = amount,
        currency = "EUR",
        merchant = "Synthetic merchant $id",
        transactionType = TransactionType.PURCHASE,
        date = ms(date),
        createdAt = 1_000L,
        source = "test"
    )

    private fun monthlyPattern(name: String, amount: Double, nextDate: LocalDate) = RecurringPattern(
        merchantName = name,
        averageAmount = amount,
        currency = "EUR",
        frequency = RecurrenceFrequency.MONTHLY,
        periodVarianceDays = 0,
        amountVariancePercent = 0.0,
        nextExpectedDate = ms(nextDate),
        confidence = 0.95f,
        previousDates = emptyList()
    )

    /** One ACTIVE EUR plan with one SCHEDULED entry due 2026-01-20 worth [projectedAmountMinor]. */
    private fun projectedPlanPort(): InstallmentProjectionPort = fakeInstallmentProjectionPort(
        plans = listOf(
            ProjectionPlanInput(
                planId = 1L,
                planUuid = planUuid,
                displayName = lenderLabel,
                currency = InstallmentCurrency.EUR,
                status = InstallmentPlanStatus.ACTIVE,
                entries = listOf(
                    ProjectionEntryInput(
                        entryId = 11L,
                        seq = 1,
                        kind = InstallmentEntryKind.REGULAR,
                        status = InstallmentEntryStatus.SCHEDULED,
                        dueEpochDay = jan(20).toEpochDay(),
                        scheduledMinor = projectedAmountMinor,
                        recordGeneration = 0
                    )
                )
            )
        ),
        todayEpochDay = today.toEpochDay()
    )

    private fun successfulResponseBody(): String = """
        {"candidates":[{"content":{"parts":[{"text":"{\"title\":\"Daily snapshot\",\"text\":\"You are on track today.\",\"tone\":\"neutral\",\"confidence\":0.8}"}]}}]}
    """.trimIndent()

    /** A real [CloudDashboardBriefingService] whose HTTP client records the request body it would send. */
    private fun cloudService(bodies: MutableList<String>, redactBeforeCloud: Boolean): CloudDashboardBriefingService {
        val keys = mockk<SecureKeyStorage>()
        every { keys.getKey(SecureKeyStorage.KEY_GEMINI) } returns "test-key"
        val resolver = mockk<EffectiveCloudAiPolicyResolver>()
        coEvery { resolver.resolve() } returns EffectiveCloudAiPolicy(
            cloudAllowed = true,
            reason = null,
            redactBeforeCloud = redactBeforeCloud,
            receiptImageUploadAllowed = false,
            bankStatementCloudAllowed = false
        )
        val gate = mockk<PrivacyGate>()
        coEvery { gate.check(any<PrivacyCapability>(), any()) } returns PrivacyDecision.Allowed
        val policy: CloudPayloadPolicy = DefaultCloudPayloadPolicy(
            policyResolver = resolver,
            redactor = DefaultCloudPayloadRedactor()
        )
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val buffer = okio.Buffer()
            chain.request().body?.writeTo(buffer)
            bodies += buffer.readUtf8()
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(successfulResponseBody().toResponseBody("application/json".toMediaType()))
                .build()
        }.build()
        return CloudDashboardBriefingService(
            secureKeyStorage = keys,
            client = client,
            promptFormatter = DashboardBriefingPromptFormatter(),
            aiSettingsRepository = null,
            privacyGate = gate,
            cloudPayloadPolicy = policy,
            effectiveCloudAiPolicyResolver = resolver,
            auditLogger = PrivacyAuditLogger.NO_OP,
            retryDelay = { _ -> }
        )
    }

    private fun cloudRoute() = AiRouteDecision(AiRoute.CLOUD, "S8B4_TEST", "gemini", "test-model")

    private fun enabledSettings() = AiSettings(aiEnabled = true, dashboardBriefingEnabled = true)

    private class BriefingCapture(
        val processed: ProcessedDashboardData,
        val input: DashboardBriefingInput,
        val requestBody: String,
        val storedSourceHashes: List<String?>
    )

    /** Runs the whole cloud chain for [world] with the given cloud redaction setting. */
    private fun runBriefingChain(world: World, redactBeforeCloud: Boolean): BriefingCapture = runBlocking {
        val processedData = world.provider.getProcessedDataFlow(world.analytics).first()

        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(enabledSettings())
        val artifacts = mockk<AiArtifactRepository>(relaxed = true)
        coEvery { artifacts.getLatest(any(), any()) } returns null
        val stored = mutableListOf<AiArtifactRecord>()
        coEvery { artifacts.upsert(capture(stored)) } returns 1L
        val router = mockk<AiCapabilityRouter>()
        coEvery { router.decide(any(), any(), any()) } returns cloudRoute()

        val bodies = mutableListOf<String>()
        val cloud = cloudService(bodies, redactBeforeCloud)
        val inputs = mutableListOf<DashboardBriefingInput>()
        val hybrid = mockk<HybridDashboardBriefingService>()
        coEvery { hybrid.generate(any()) } coAnswers {
            val input = firstArg<DashboardBriefingInput>()
            inputs += input
            cloud.generate(input)
        }

        GenerateDashboardBriefingUseCase(
            aiSettingsRepository = settingsRepository,
            aiArtifactRepository = artifacts,
            dashboardBriefingService = hybrid,
            aiCapabilityRouter = router,
            inputBuilder = DashboardBriefingInputBuilder(world.timeProvider),
            timeProvider = world.timeProvider
        )(processedData)

        assertEquals("the use case must hand exactly one input to the service", 1, inputs.size)
        assertEquals("the cloud service must have sent exactly one request", 1, bodies.size)
        BriefingCapture(processedData, inputs.single(), bodies.single(), stored.map { it.sourceHash })
    }

    private class InsightCapture(val input: DashboardBriefingInput, val requestBody: String)

    /** Runs the real GenerateTransactionInsightUseCase for one fixed expense through the real cloud service. */
    private fun runInsightChain(world: World, redact: Boolean): InsightCapture = runBlocking {
        val settingsRepository = mockk<AiSettingsRepository>()
        every { settingsRepository.settings() } returns flowOf(enabledSettings())
        val artifacts = mockk<AiArtifactRepository>(relaxed = true)
        val router = mockk<AiCapabilityRouter>()
        coEvery { router.decide(any(), any(), any()) } returns cloudRoute()
        val aiPolicy = mockk<AiPolicy>(relaxed = true)
        coEvery { aiPolicy.shouldRedact(any(), any()) } returns redact
        val privacySettings = mockk<PrivacySettingsRepository>(relaxed = true)

        val bodies = mutableListOf<String>()
        val cloud = cloudService(bodies, redact)
        val inputs = mutableListOf<DashboardBriefingInput>()
        val hybrid = mockk<HybridDashboardBriefingService>()
        coEvery { hybrid.generate(any()) } coAnswers {
            val input = firstArg<DashboardBriefingInput>()
            inputs += input
            cloud.generate(input)
        }

        val record = GenerateTransactionInsightUseCase(
            aiSettingsRepository = settingsRepository,
            aiArtifactRepository = artifacts,
            dashboardBriefingService = hybrid,
            aiCapabilityRouter = router,
            aiPolicy = aiPolicy,
            inputBuilder = TransactionInsightInputBuilder(),
            timeProvider = world.timeProvider,
            privacySettingsRepository = privacySettings
        )(purchase(42L, 42.0, jan(12)).copy(merchant = "Corner Shop"))

        assertNotNull("the insight chain must produce an artifact", record)
        assertEquals(1, inputs.size)
        assertEquals(1, bodies.size)
        InsightCapture(inputs.single(), bodies.single())
    }

    private fun assertNoPlanFacts(label: String, text: String) {
        assertFalse("$label leaks the plan label", text.contains(lenderLabel))
        assertFalse("$label leaks the lender name", text.contains("Zebra"))
        assertFalse("$label leaks the plan uuid", text.contains(planUuid))
        assertFalse("$label leaks the projected minor amount", text.contains(projectedAmountMinor.toString()))
        assertFalse("$label leaks the projected amount", text.contains(projectedAmountText))
        assertFalse("$label mentions installments", text.contains("installment", ignoreCase = true))
    }

    private fun payloadText(input: DashboardBriefingInput): String =
        input.toString() + "\n" +
            formatter.buildPrompt(input, shouldRedact = false) + "\n" +
            formatter.buildPrompt(input, shouldRedact = true)

    @Test
    fun `AE-23 briefing payload with plans present contains no plan fields display names or projected amounts`() {
        val world = World(projectedPlanPort())

        // Non-vacuity: in this very fixture the plan IS projected and Home sees it.
        val aware = runBlocking { world.home.observe().first() }
        assertNotNull("the plan port must project an entry into the home view", aware)
        assertEquals(listOf(11L), aware!!.installmentRows.map { it.entryId })
        assertEquals(lenderLabel, aware.installmentRows.single().displayName)
        assertEquals(projectedAmountMinor, aware.installmentRows.single().amountMinor)

        for (redact in listOf(false, true)) {
            val capture = runBriefingChain(world, redactBeforeCloud = redact)
            val label = "briefing (redactBeforeCloud=$redact)"
            assertNoPlanFacts("$label input", payloadText(capture.input))
            assertNoPlanFacts("$label request body", capture.requestBody)
            assertNoPlanFacts("$label processed data", capture.processed.toString())
            // The chain is not vacuous: the real weather path produced real upcoming facts, but only
            // from the installment-free forecast.
            assertTrue(capture.input.upcomingItems.any { it.description == "Synthetic laptop" })
            assertFalse(capture.input.upcomingItems.any { it.description == lenderLabel })
        }
    }

    @Test
    fun `AE-23 differential - same fixture with and without plans yields identical input and prompt`() {
        val withoutPlans = World(emptyInstallmentProjectionPort())
        val withPlans = World(projectedPlanPort())

        // Non-vacuity (A15 ISSUE-3): ONLY the port output differs, and it visibly changes the Home view.
        val awareEmpty = runBlocking { withoutPlans.home.observe().first() }
        val awarePlans = runBlocking { withPlans.home.observe().first() }
        assertNull("an empty port must leave the Home weather on the baseline", awareEmpty)
        assertNotNull("a non-empty port must produce the installment-aware Home weather", awarePlans)
        assertTrue(awarePlans!!.installmentRows.isNotEmpty())
        val baselineWeather = runBlocking { withPlans.baseline.getFinancialWeather().first() }
        assertEquals(
            "the Home view carries the projected entry",
            baselineWeather.totalCommitted + projectedAmount,
            awarePlans.weather.totalCommitted,
            0.001
        )
        assertNotEquals(baselineWeather.totalCommitted, awarePlans.weather.totalCommitted, 0.001)

        for (redact in listOf(false, true)) {
            val a = runBriefingChain(withoutPlans, redactBeforeCloud = redact)
            val b = runBriefingChain(withPlans, redactBeforeCloud = redact)
            val label = "redactBeforeCloud=$redact"

            // Spending aggregates, weather and upcoming facts are the cloud path's own baseline.
            assertEquals("$label weather totalCommitted", a.processed.data.weather.totalCommitted, b.processed.data.weather.totalCommitted, 0.0)
            assertEquals("$label weather totalLikely", a.processed.data.weather.totalLikely, b.processed.data.weather.totalLikely, 0.0)
            assertEquals("$label weather upcomingItems", a.processed.data.weather.upcomingItems, b.processed.data.weather.upcomingItems)
            assertEquals("$label processed dashboard data", a.processed, b.processed)
            // The cloud path stayed on the installment-free baseline while Home moved.
            assertEquals(baselineWeather.totalCommitted, b.processed.data.weather.totalCommitted, 0.0)

            assertEquals("$label briefing input", a.input, b.input)
            assertEquals(
                "$label prompt (shouldRedact=false)",
                formatter.buildPrompt(a.input, shouldRedact = false),
                formatter.buildPrompt(b.input, shouldRedact = false)
            )
            assertEquals(
                "$label prompt (shouldRedact=true)",
                formatter.buildPrompt(a.input, shouldRedact = true),
                formatter.buildPrompt(b.input, shouldRedact = true)
            )
            assertEquals("$label cloud request body", a.requestBody, b.requestBody)
            assertEquals("$label stored artifact source hash", a.storedSourceHashes, b.storedSourceHashes)
            assertTrue("$label chain is not vacuous", a.input.upcomingItems.isNotEmpty())
        }
    }

    // Upcoming items cap at five. Drop-before-cap ORDERING (excluded variants never consume cap
    // slots) is enforced by code review and the variant tripwire below only: MockK cannot build an
    // unmapped UpcomingItem (it instantiates a real sealed subclass), so no test can plant one.
    @Test
    fun `upcoming items cap at five`() {
        val many = (1..8).map { index ->
            UpcomingItem.Planned(
                PlannedExpense(
                    id = index.toLong(),
                    description = "Bill $index",
                    amount = 10.0 * index,
                    currency = "EUR",
                    date = now + index * 86_400_000L,
                    categoryId = null,
                    isRecurring = false,
                    priority = PlannedExpensePriority.LIKELY
                )
            )
        }
        val input = builder.build(processed(weather(many)))
        assertEquals(5, input.upcomingItems.size)
        assertEquals((1..5).map { "Bill $it" }, input.upcomingItems.map { it.description })
    }

    // Tripwire: a new UpcomingItem variant is excluded from the cloud payload by the builder's
    // else branch. Whoever adds one (S8b projection item) must add an explicit exclusion
    // assertion to this class and review the builder allow-list before updating this set.
    @Test
    fun `UpcomingItem variants are exactly the reviewed set - a new variant needs an explicit cloud exclusion test`() {
        val variants = UpcomingItem::class.sealedSubclasses.mapNotNull { it.simpleName }.toSet()
        assertEquals(setOf("Recurring", "Occurrence", "Planned"), variants)
    }

    @Test
    fun `AE-23 transaction insight input and prompt are identical with and without plans`() {
        val withoutPlans = World(emptyInstallmentProjectionPort())
        val withPlans = World(projectedPlanPort())

        // Non-vacuity: the plan world really has a projected entry live in the same process.
        assertNull(runBlocking { withoutPlans.home.observe().first() })
        assertNotNull(runBlocking { withPlans.home.observe().first() })

        for (redact in listOf(false, true)) {
            val a = runInsightChain(withoutPlans, redact)
            val b = runInsightChain(withPlans, redact)
            val label = "redact=$redact"

            assertEquals("$label insight input", a.input, b.input)
            assertEquals(
                "$label insight prompt",
                formatter.buildPrompt(a.input, shouldRedact = redact),
                formatter.buildPrompt(b.input, shouldRedact = redact)
            )
            assertEquals("$label insight cloud request body", a.requestBody, b.requestBody)
            assertTrue(b.input.upcomingItems.isEmpty())
            assertNoPlanFacts("$label insight input", payloadText(b.input))
            assertNoPlanFacts("$label insight request body", b.requestBody)
        }
    }
}
