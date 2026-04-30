package com.yourname.expensetracker

import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.domain.analytics.AnomalyDetector
import com.yourname.expensetracker.domain.analytics.AnalyticsCategoryRef
import com.yourname.expensetracker.domain.analytics.AnalyticsConversionWarning
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.analytics.CategoryInsightEngine
import com.yourname.expensetracker.domain.analytics.DayOfWeekAnalyzer
import com.yourname.expensetracker.domain.analytics.InsightsEngine
import com.yourname.expensetracker.domain.analytics.InsightsSnapshot
import com.yourname.expensetracker.domain.analytics.MonthPeriod
import com.yourname.expensetracker.domain.analytics.MonthlyComparison
import com.yourname.expensetracker.domain.analytics.MonthlyComparisonCalculator
import com.yourname.expensetracker.domain.analytics.MerchantInsight
import com.yourname.expensetracker.domain.analytics.MerchantInsightEngine
import com.yourname.expensetracker.domain.analytics.SpendingPace
import com.yourname.expensetracker.domain.analytics.SpendingPaceCalculator
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.currency.DomainExchangeRate
import com.yourname.expensetracker.domain.currency.ExchangeRateStore
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

fun Category.toAnalyticsCategoryRef(): AnalyticsCategoryRef = AnalyticsCategoryRef(
    id = id,
    name = name,
    icon = icon,
    color = color
)

fun List<Category>.toAnalyticsCategoryRefs(): List<AnalyticsCategoryRef> = map(Category::toAnalyticsCategoryRef)

fun List<Category>.toAnalyticsCategoryMap(): Map<Long, AnalyticsCategoryRef> = associateBy(Category::id) {
    it.toAnalyticsCategoryRef()
}

fun Expense.toExpenseSnapshot(): ExpenseSnapshot = ExpenseSnapshot(
    id = id,
    amount = amount,
    effectiveAmount = effectiveAmount,
    currency = currency,
    merchant = merchant,
    merchantKey = merchantKey,
    transactionType = when (transactionType) {
        TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
        TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
        TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
        TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
        TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
    },
    date = date,
    categoryId = categoryId,
    isNotMine = isNotMine,
    transferDirection = when (transferDirection) {
        TransferDirection.INCOMING -> DomainTransferDirection.INCOMING
        TransferDirection.OUTGOING -> DomainTransferDirection.OUTGOING
        null -> null
    },
    notes = notes
)

fun List<Expense>.toExpenseSnapshots(): List<ExpenseSnapshot> = map(Expense::toExpenseSnapshot)

class TestCurrencySettingsRepository(
    initialHomeCurrency: String = "EUR",
    initialLastRateUpdate: Long = 0L
) : CurrencySettingsRepository {
    private val homeCurrencyFlow = MutableStateFlow(initialHomeCurrency)
    private val lastRateUpdateFlow = MutableStateFlow(initialLastRateUpdate)

    override fun homeCurrency(): Flow<String> = homeCurrencyFlow

    override suspend fun setHomeCurrency(currencyCode: String) {
        homeCurrencyFlow.value = currencyCode
    }

    override fun lastRateUpdate(): Flow<Long> = lastRateUpdateFlow

    override suspend fun setLastRateUpdate(timestamp: Long) {
        lastRateUpdateFlow.value = timestamp
    }

    override suspend fun areRatesStale(thresholdMs: Long): Boolean {
        return thresholdMs >= 0 && lastRateUpdateFlow.value <= 0L
    }

    override suspend fun clear() {
        homeCurrencyFlow.value = "EUR"
        lastRateUpdateFlow.value = 0L
    }
}

class TestExchangeRateStore : ExchangeRateStore {
    private val rates = mutableMapOf<Pair<String, String>, DomainExchangeRate>()

    override suspend fun getRate(fromCurrency: String, toCurrency: String): DomainExchangeRate? {
        return rates[fromCurrency.uppercase() to toCurrency.uppercase()]
    }

    override suspend fun insertOrUpdate(rate: DomainExchangeRate) {
        rates[rate.fromCurrency.uppercase() to rate.toCurrency.uppercase()] = rate
    }

    override suspend fun insertOrUpdateAll(rates: List<DomainExchangeRate>) {
        for (rate in rates) {
            insertOrUpdate(rate)
        }
    }

    override fun getAllRatesForBase(baseCurrency: String): Flow<List<DomainExchangeRate>> = emptyFlow()

    override suspend fun getLatestRate(): DomainExchangeRate? = rates.values.maxByOrNull { it.lastUpdated }

    override suspend fun deleteOldRates(olderThan: Long) {
        rates.entries.removeAll { (_, rate) -> rate.lastUpdated < olderThan }
    }
}

fun testCurrencyConverter(exchangeRateStore: ExchangeRateStore = TestExchangeRateStore()): CurrencyConverter {
    return CurrencyConverter(exchangeRateStore)
}

fun testAnalyticsCurrencyNormalizer(
    currencyConverter: CurrencyConverter = testCurrencyConverter()
): AnalyticsCurrencyNormalizer {
    return AnalyticsCurrencyNormalizer(currencyConverter)
}

suspend fun InsightsEngine.generateInsights(
    categories: List<Category>,
    allExpenses: List<Expense>,
    displayCurrency: String = "EUR",
    conversionWarnings: List<AnalyticsConversionWarning> = emptyList()
): InsightsSnapshot = generateInsights(
    categories = categories.toAnalyticsCategoryRefs(),
    allExpenses = allExpenses.toExpenseSnapshots(),
    displayCurrency = displayCurrency,
    conversionWarnings = conversionWarnings
)

suspend fun InsightsEngine.generateInsights(
    categories: List<Category>,
    allExpenses: List<ExpenseSnapshot>,
    displayCurrency: String = "EUR",
    conversionWarnings: List<AnalyticsConversionWarning> = emptyList()
): InsightsSnapshot = generateInsights(
    categories = categories.toAnalyticsCategoryRefs(),
    allExpenses = allExpenses,
    displayCurrency = displayCurrency,
    conversionWarnings = conversionWarnings
)

suspend fun InsightsEngine.generateInsights(
    categories: List<AnalyticsCategoryRef>,
    allExpenses: List<Expense>,
    displayCurrency: String = "EUR",
    conversionWarnings: List<AnalyticsConversionWarning> = emptyList()
): InsightsSnapshot = generateInsights(
    categories = categories,
    allExpenses = allExpenses.toExpenseSnapshots(),
    displayCurrency = displayCurrency,
    conversionWarnings = conversionWarnings
)

fun InsightsEngine.buildDailyTotals(expenses: List<Expense>, days: Int): Map<String, Double> =
    buildDailyTotals(expenses.toExpenseSnapshots(), days)

fun SpendingPaceCalculator.calculate(
    currentMonthStart: Long,
    previousMonthStart: Long,
    previousMonthEnd: Long,
    allExpenses: List<ExpenseSnapshot>
): SpendingPace = calculate(
    currentMonthStart = currentMonthStart,
    previousMonthStart = previousMonthStart,
    previousMonthEnd = previousMonthEnd,
    allExpenses = allExpenses,
    displayCurrency = "EUR"
)

fun SpendingPaceCalculator.calculate(
    currentMonthStart: Long,
    previousMonthStart: Long,
    previousMonthEnd: Long,
    allExpenses: List<Expense>
): SpendingPace = calculate(
    currentMonthStart = currentMonthStart,
    previousMonthStart = previousMonthStart,
    previousMonthEnd = previousMonthEnd,
    allExpenses = allExpenses.toExpenseSnapshots(),
    displayCurrency = "EUR"
)

fun MonthlyComparisonCalculator.calculate(
    currentMonth: MonthPeriod,
    previousMonth: MonthPeriod?,
    allExpenses: List<ExpenseSnapshot>
): MonthlyComparison = calculate(currentMonth, previousMonth, allExpenses, "EUR")

fun MonthlyComparisonCalculator.calculate(
    currentMonth: MonthPeriod,
    previousMonth: MonthPeriod?,
    allExpenses: List<Expense>
): MonthlyComparison = calculate(currentMonth, previousMonth, allExpenses.toExpenseSnapshots(), "EUR")

fun CategoryInsightEngine.calculate(
    currentMonth: MonthPeriod,
    previousMonth: MonthPeriod?,
    categoryMap: Map<Long, AnalyticsCategoryRef>,
    allExpenses: List<ExpenseSnapshot>
) = calculate(currentMonth, previousMonth, categoryMap, allExpenses, "EUR")

fun CategoryInsightEngine.calculate(
    currentMonth: MonthPeriod,
    previousMonth: MonthPeriod?,
    categoryMap: Map<Long, AnalyticsCategoryRef>,
    allExpenses: List<Expense>
) = calculate(currentMonth, previousMonth, categoryMap, allExpenses.toExpenseSnapshots(), "EUR")

fun MerchantInsightEngine.calculate(
    allExpenses: List<ExpenseSnapshot>
): List<MerchantInsight> = calculate(allExpenses, "EUR")

fun MerchantInsightEngine.calculate(
    allExpenses: List<Expense>
): List<MerchantInsight> = calculate(allExpenses.toExpenseSnapshots(), "EUR")

fun DayOfWeekAnalyzer.analyze(
    startDate: Long,
    endDate: Long,
    allExpenses: List<ExpenseSnapshot>
) = analyze(startDate, endDate, allExpenses, "EUR")

fun DayOfWeekAnalyzer.analyze(
    startDate: Long,
    endDate: Long,
    allExpenses: List<Expense>
) = analyze(startDate, endDate, allExpenses.toExpenseSnapshots(), "EUR")

fun AnomalyDetector.detect(
    monthPeriod: MonthPeriod,
    categoryMap: Map<Long, AnalyticsCategoryRef>,
    allExpenses: List<ExpenseSnapshot>
) = detect(monthPeriod, categoryMap, allExpenses, "EUR")

fun AnomalyDetector.detect(
    monthPeriod: MonthPeriod,
    categoryMap: Map<Long, AnalyticsCategoryRef>,
    allExpenses: List<Expense>
) = detect(monthPeriod, categoryMap, allExpenses.toExpenseSnapshots(), "EUR")
