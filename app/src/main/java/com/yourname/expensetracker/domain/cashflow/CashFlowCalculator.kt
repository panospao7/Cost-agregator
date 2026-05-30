package com.yourname.expensetracker.domain.cashflow

import com.yourname.expensetracker.data.backup.DatabaseReadBarrier
import com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransferDirection
import com.yourname.expensetracker.data.database.entity.toRecurringPattern
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.analytics.AnalyticsCurrencyNormalizer
import com.yourname.expensetracker.domain.core.money.MoneyAmount
import com.yourname.expensetracker.domain.currency.CurrencyConverter
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.forecasting.MergedRecurringPatternsProvider
import com.yourname.expensetracker.domain.model.RecurringPattern
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import kotlinx.coroutines.flow.first
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

data class DailyCashFlow(
    val date: Date,
    val startingBalance: Double,
    val income: List<Expense>,
    val expenses: List<Expense>,
    val predictedRecurring: List<RecurringPattern>,
    val endingBalance: Double,
    val riskLevel: CashFlowRiskLevel,
    /** Currency code this cash flow is denominated in (e.g. "EUR", "USD"). */
    val currency: String = "",
    /** S8-023: true when one or more currency conversions failed for this day */
    val isPartial: Boolean = false,
    /** S8-023: count of failed currency conversions for this day */
    val failedConversionCount: Int = 0,
    /**
     * P6-CURRENT-018: true when occurrence projection failed for one or more
     * manual recurring rules and the calculator fell back to ad-hoc expansion.
     * Bills are NOT dropped (they are recovered via fallback), but the result is
     * approximate, so this surfaces a data-quality signal to the UI.
     */
    val occurrenceGenerationFailed: Boolean = false,
    /** P6-CURRENT-018: number of manual rules whose occurrence projection failed. */
    val failedOccurrenceRuleCount: Int = 0
)

enum class CashFlowRiskLevel {
    NONE,      // Healthy surplus
    LOW,       // Slight surplus
    MEDIUM,    // Near break-even
    HIGH       // Risk of going negative
}

@Singleton
class CashFlowCalculator @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val recurringPatternsProvider: MergedRecurringPatternsProvider,
    private val timeProvider: TimeProvider,
    private val recurringLifecycleCoordinator: RecurringLifecycleCoordinator,
    private val recurringOccurrenceDao: RecurringOccurrenceDao,
    private val analyticsCurrencyNormalizer: AnalyticsCurrencyNormalizer,
    private val currencySettingsRepository: CurrencySettingsRepository,
    private val currencyConverter: CurrencyConverter,
    private val databaseReadBarrier: DatabaseReadBarrier
) {

    /**
     * Calculates daily cash flow for a given date range.
     *
     * @param startingBalance The opening balance for the forecast period as a typed
     *   [MoneyAmount]. The currency precondition is now **type-enforced**: this MUST be
     *   denominated in the user's home currency. A balance whose currency does not match
     *   the resolved home currency is **rejected** with [IllegalArgumentException] rather
     *   than auto-converted, since all internal calculations normalize to home currency.
     *
     * ## FCST-3 / P6-CURRENT-024: Read-only occurrence-driven prediction
     * This method PROJECTS occurrences in memory via
     * [RecurringLifecycleCoordinator.projectOccurrences] (no row materialization,
     * no lifecycle events, no write barrier) and merges them by occurrenceKey with
     * any already-materialized rows read through [RecurringOccurrenceDao] (guarded
     * by [DatabaseReadBarrier]). Materialized rows carry authoritative status and
     * override projections for the same key. Detected-only patterns (without a
     * manual rule) are handled via ad-hoc date matching on
     * [RecurringPattern.nextExpectedDate]. This approach captures all recurring
     * obligations without double-counting and without writing on a read path.
     *
     * ## P6-CURRENT-018: Occurrence projection failure
     * If projection fails for a manual rule, the calculator falls back to ad-hoc
     * expansion for that rule (so bills are not silently dropped) and flags the
     * affected days via [DailyCashFlow.occurrenceGenerationFailed].
     */
    suspend fun calculateDailyCashFlow(
        startDate: Date,
        endDate: Date,
        startingBalance: MoneyAmount
    ): List<DailyCashFlow> {
        // CURR-70F-09: Use typed resolution — fail explicitly if home currency unavailable
        val homeCurrency = when (val resolution = currencySettingsRepository.resolveHomeCurrency()) {
            is com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Resolved -> resolution.currency.code
            is com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.FirstRunDefault -> resolution.currency.code
            is com.yourname.expensetracker.domain.currency.HomeCurrencyResolution.Failed ->
                throw IllegalStateException("Home currency unavailable for cash flow: ${resolution.reason}")
        }
        // P6-CURRENT-020: Reject (do NOT auto-convert) a starting balance whose currency
        // does not match the resolved home currency. The type now carries the currency,
        // so a mismatch is a caller bug, not something to silently coerce.
        require(startingBalance.currency.code.equals(homeCurrency, ignoreCase = true)) {
            "startingBalance currency '${startingBalance.currency.code}' does not match home currency '$homeCurrency'. " +
                "Pass the starting balance in the home currency (no auto-conversion is performed)."
        }
        val calendar = Calendar.getInstance()
        val results = mutableListOf<DailyCashFlow>()
        var runningBalance = startingBalance.amount

        val startTime = startDate.time
        val endTime = endDate.time

        // ── Pre-compute occurrence-driven predictions (FCST-3) ──────────────
        // P6-CURRENT-024: READ-ONLY. Manual rules are PROJECTED in memory via the
        // coordinator (no row materialization, no lifecycle events, no write
        // barrier). Already-materialized rows are read separately (guarded by the
        // read barrier) so user overrides (SKIPPED/CANCELLED) still take effect.
        val recurringPatterns = recurringPatternsProvider.getConfirmedPatterns()
        val manualPatterns = recurringPatterns.filter { it.id != null }
        val ruleIds = manualPatterns
            .mapNotNull { it.id }
            .distinct()

        // P6-CURRENT-018: track rules whose projection failed so we can fall back
        // to ad-hoc expansion (bills are NOT silently dropped) and surface a flag.
        val failedRuleIds = mutableListOf<Long>()
        val projectedOccurrences = mutableListOf<RecurringOccurrence>()
        for (ruleId in ruleIds) {
            try {
                projectedOccurrences += recurringLifecycleCoordinator.projectOccurrences(
                    ruleId = ruleId,
                    startDate = startTime,
                    endDate = endTime
                )
            } catch (e: Exception) {
                Timber.w(e, "$TAG: projectOccurrences failed for ruleId=%d — falling back to ad-hoc expansion", ruleId)
                failedRuleIds.add(ruleId)
            }
        }

        // DBG-06: when the materialized read is blocked specifically by the restore
        // read barrier, projections still bypass the barrier and would surface PLANNED
        // bills WITHOUT the materialized SKIPPED/CANCELLED overrides. Track that case so
        // we can flag the recurring section as partial/unreliable rather than silently
        // showing cancelled bills as planned during restore.
        var materializedReadBlocked = false
        // Read already-materialized occurrences (READ-ONLY, barrier-guarded).
        // Materialized rows carry authoritative status and override projections
        // for the same occurrenceKey (keys align — projection reuses the exact
        // expand logic the materializer uses).
        //
        // DBG-03: `ruleIds` is derived from `getConfirmedPatterns()` which (via
        // RecurringExpenseRepository.getAll → dao.getAllActive) returns ACTIVE rules
        // only. Filtering materialized rows to `sourceId in ruleIds` therefore excludes
        // any previously-materialized PLANNED rows belonging to a PAUSED (isActive=false)
        // rule, so a paused subscription no longer leaks in as a future obligation.
        val materializedOccurrences: List<RecurringOccurrence> = if (ruleIds.isNotEmpty()) {
            try {
                databaseReadBarrier.checkReadAllowed("CashFlowCalculator.calculateDailyCashFlow.readOccurrences")
                recurringOccurrenceDao.getByDateRange(startTime, endTime)
                    .filter {
                        // DBG-03: active-rule filter (ruleIds are active-only, see above).
                        it.sourceType == RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE &&
                            it.sourceId in ruleIds
                    }
            } catch (e: com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException) {
                // DBG-06: barrier-blocked (restore mode). Mark partial so the recurring
                // section is flagged unreliable instead of showing projections-only
                // (which would drop SKIPPED/CANCELLED overrides).
                Timber.w(e, "$TAG: materialized read blocked by restore barrier — marking partial")
                materializedReadBlocked = true
                emptyList()
            } catch (e: Exception) {
                Timber.w(e, "$TAG: reading materialized occurrences failed")
                emptyList()
            }
        } else {
            emptyList()
        }

        val mergedOccurrencesByKey = LinkedHashMap<String, RecurringOccurrence>()
        for (occ in projectedOccurrences) mergedOccurrencesByKey[occ.occurrenceKey] = occ
        for (occ in materializedOccurrences) mergedOccurrencesByKey[occ.occurrenceKey] = occ
        val plannedOccurrences = mergedOccurrencesByKey.values.filter { it.status == "PLANNED" }

        // Build a day-indexed map from occurrences (yyyy-MM-dd → patterns)
        val occurrencePatternsByDay = mutableMapOf<String, MutableList<RecurringPattern>>()
        for (occ in plannedOccurrences) {
            occurrencePatternsByDay.getOrPut(formatDayKey(occ.dueDate)) { mutableListOf() }
                .add(occ.toRecurringPattern())
        }

        // P6-CURRENT-018: Ad-hoc fallback for rules whose projection failed.
        // Mirror FinancialStressForecastEngine's failed-rule fallback so the bills
        // still appear in the forecast (approximate, flagged via occurrenceGenerationFailed).
        if (failedRuleIds.isNotEmpty()) {
            val failedPatterns = manualPatterns.filter { it.id in failedRuleIds }
            for (pattern in failedPatterns) {
                for (dueDate in expandPatternDueDates(pattern, startTime, endTime)) {
                    occurrencePatternsByDay.getOrPut(formatDayKey(dueDate)) { mutableListOf() }
                        .add(pattern)
                }
            }
        }
        val occurrenceGenerationFailed = failedRuleIds.isNotEmpty()
        val failedOccurrenceRuleCount = failedRuleIds.size
        // DBG-06: the recurring section is partial/unreliable when EITHER projection
        // fell back to ad-hoc expansion OR the materialized read was blocked by the
        // restore barrier (the latter drops SKIPPED/CANCELLED overrides). Keep
        // occurrenceGenerationFailed narrow (projection failures only) and surface the
        // broader degraded signal through isPartial below.
        val recurringSectionPartial = occurrenceGenerationFailed || materializedReadBlocked

        // Part 2: Detected-only patterns (no manual rule) —
        // ad-hoc date matching on nextExpectedDate.
        val detectedPatterns = recurringPatterns.filter { it.id == null }

        // Get historical data for the period
        val historicalExpenses = expenseRepository.getExpensesBetween(startTime, endTime)

        // Group historical expenses by day key (yyyy-MM-dd) to avoid cross-year collisions
        val expensesByDay = mutableMapOf<String, MutableList<Expense>>()
        for (expense in historicalExpenses) {
            calendar.timeInMillis = expense.date
            val dayKey = String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )
            val list = expensesByDay.getOrPut(dayKey) { mutableListOf() }
            list.add(expense)
        }

        // Process each day
        calendar.time = startDate
        while (calendar.time.before(endDate)) {
            val currentDay = calendar.time
            val dayKey = String.format(
                Locale.US,
                "%04d-%02d-%02d",
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH)
            )

            // Get day's expenses
            val dayExpenses = expensesByDay[dayKey] ?: mutableListOf()

            // Split into inflow and outflow using explicit transaction-type classification.
            // Inflow  = DEPOSIT, or TRANSFER with transferDirection == INCOMING.
            // Outflow = PURCHASE, WITHDRAWAL, or TRANSFER with transferDirection == OUTGOING.
            // TRANSFER rows without a transferDirection and UNKNOWN are excluded
            // from both sides so they don't distort the cash-flow balance.
            val incomeList = mutableListOf<Expense>()
            val expenseList = mutableListOf<Expense>()
            for (expense in dayExpenses) {
                when (expense.transactionType.toDomain()) {
                    DomainTransactionType.DEPOSIT -> incomeList.add(expense)
                    DomainTransactionType.PURCHASE,
                    DomainTransactionType.WITHDRAWAL -> expenseList.add(expense)
                    DomainTransactionType.TRANSFER -> {
                        when (expense.transferDirection) {
                            TransferDirection.INCOMING -> incomeList.add(expense)
                            TransferDirection.OUTGOING -> expenseList.add(expense)
                            null -> { /* unclassified transfer – no cash-flow impact */ }
                        }
                    }
                    else -> { /* UNKNOWN – no cash-flow impact */ }
                }
            }

            // Calculate predicted recurring for this day — two-path approach (FCST-3)
            val predictedRecurringList = mutableListOf<RecurringPattern>()

            // Path 1: Occurrence-driven predictions from manual rules
            occurrencePatternsByDay[dayKey]?.let { predictedRecurringList.addAll(it) }

            // Path 2: Detected-only patterns — ad-hoc date matching on nextExpectedDate
            val currentDayStart = TimePeriodUtils.getStartOfDay(currentDay.time)
            val currentDayEnd = TimePeriodUtils.getEndOfDay(currentDay.time)
            for (pattern in detectedPatterns) {
                val expectedDayStart = TimePeriodUtils.getStartOfDay(pattern.nextExpectedDate)
                if (expectedDayStart >= currentDayStart && expectedDayStart < currentDayEnd) {
                    predictedRecurringList.add(pattern)
                }
            }
            
            // P6-CURRENT-019: Content-aware dedup (replaces merchant-name-only).
            // A predicted recurring item is suppressed only when it matches another
            // item by canonical merchant key (MerchantKeyGenerator) + currency +
            // amount within tolerance, on this same day. This dedupes:
            //   • predicted-vs-actual: a real expense already covers the bill
            //   • predicted-vs-predicted: duplicate projections collapse to one
            // It does NOT merge legitimately distinct same-merchant bills (e.g. a
            // €15 and a €70 Netflix charge), because amount/currency are part of the key.
            val actualSignatures = (incomeList + expenseList).map { exp ->
                ContentSignature(
                    merchantKey = merchantKeyOf(exp.merchant),
                    currency = exp.currency.uppercase(Locale.US),
                    amount = exp.amount
                )
            }
            val deduplicatedPredicted = mutableListOf<RecurringPattern>()
            for (pattern in predictedRecurringList) {
                val patternKey = merchantKeyOf(pattern.merchantName)
                val patternCurrency = pattern.currency.uppercase(Locale.US)

                // predicted-vs-actual
                val matchesActual = actualSignatures.any { sig ->
                    sig.merchantKey == patternKey &&
                        sig.currency == patternCurrency &&
                        amountsWithinTolerance(sig.amount, pattern.averageAmount)
                }
                if (matchesActual) continue

                // predicted-vs-predicted (against already-accepted items)
                val matchesAccepted = deduplicatedPredicted.any { accepted ->
                    merchantKeyOf(accepted.merchantName) == patternKey &&
                        accepted.currency.uppercase(Locale.US) == patternCurrency &&
                        amountsWithinTolerance(accepted.averageAmount, pattern.averageAmount)
                }
                if (matchesAccepted) continue

                deduplicatedPredicted.add(pattern)
            }

            // Calculate ending balance — normalize to home currency
            var dayIncome = 0.0
            var conversionFailures = 0
            for (inc in incomeList) {
                if (inc.currency.equals(homeCurrency, ignoreCase = true)) {
                    dayIncome += inc.effectiveAmount
                } else {
                    val converted = currencyConverter.convertAsOf(inc.effectiveAmount, inc.currency, homeCurrency, inc.date)
                    if (converted != null) {
                        dayIncome += converted.convertedAmount
                    } else {
                        conversionFailures++
                    }
                }
            }
            
            var dayExpensesTotal = 0.0
            for (exp in expenseList) {
                if (exp.currency.equals(homeCurrency, ignoreCase = true)) {
                    dayExpensesTotal += exp.effectiveAmount
                } else {
                    val converted = currencyConverter.convertAsOf(exp.effectiveAmount, exp.currency, homeCurrency, exp.date)
                    if (converted != null) {
                        dayExpensesTotal += converted.convertedAmount
                    } else {
                        conversionFailures++
                    }
                }
            }
            for (recurring in deduplicatedPredicted) {
                if (recurring.currency.equals(homeCurrency, ignoreCase = true)) {
                    dayExpensesTotal += recurring.averageAmount
                } else {
                    // CURR-70F-12: Use FORECAST_DATE basis for predicted recurring items
                    val outcome = currencyConverter.convertOutcome(
                        amount = recurring.averageAmount,
                        fromCurrency = recurring.currency,
                        toCurrency = homeCurrency,
                        rateBasis = com.yourname.expensetracker.domain.core.money.RateBasis.FORECAST_DATE,
                        atMillis = currentDay.time,
                        stalePolicy = com.yourname.expensetracker.domain.core.money.StaleRatePolicy.None
                    )
                    when (outcome) {
                        is com.yourname.expensetracker.domain.core.money.ConversionOutcome.Converted ->
                            dayExpensesTotal += outcome.convertedAmount
                        is com.yourname.expensetracker.domain.core.money.ConversionOutcome.Failed ->
                            conversionFailures++
                    }
                }
            }

            if (conversionFailures > 0) {
                Timber.w("$TAG: %d conversion(s) failed for day %s — those amounts were dropped from cash flow", conversionFailures, dayKey)
            }
            
            runningBalance = runningBalance + dayIncome - dayExpensesTotal
            
            // Determine risk level
            val riskLevel = when {
                runningBalance > 500 -> CashFlowRiskLevel.NONE
                runningBalance > 100 -> CashFlowRiskLevel.LOW
                runningBalance > 0 -> CashFlowRiskLevel.MEDIUM
                else -> CashFlowRiskLevel.HIGH
            }
            
            results.add(
                DailyCashFlow(
                    date = currentDay,
                    startingBalance = runningBalance - dayIncome + dayExpensesTotal,
                    income = incomeList,
                    expenses = expenseList,
                    predictedRecurring = deduplicatedPredicted,
                    endingBalance = runningBalance,
                    riskLevel = riskLevel,
                    currency = homeCurrency,
                    // S8-022/S8-023: Surface data quality to UI.
                    // DBG-06: recurringSectionPartial also covers a restore-barrier-blocked
                    // materialized read (overrides may be missing), not just conversion
                    // failures / projection fallback.
                    isPartial = conversionFailures > 0 || recurringSectionPartial,
                    failedConversionCount = conversionFailures,
                    // P6-CURRENT-018: Surface occurrence-projection failures so
                    // bills approximated via ad-hoc fallback are flagged, not hidden.
                    occurrenceGenerationFailed = occurrenceGenerationFailed,
                    failedOccurrenceRuleCount = failedOccurrenceRuleCount
                )
            )
            
            // Move to next day
            calendar.add(Calendar.DAY_OF_MONTH, 1)
        }
        
        return results
    }
    
    suspend fun getUpcomingBills(daysAhead: Int): List<RecurringPattern> {
        val now = timeProvider.now()
        val startOfToday = TimePeriodUtils.getStartOfDay(now)
        // Exclusive end — covers all days up to and including `daysAhead` from today
        val endDate = TimePeriodUtils.addDays(startOfToday, daysAhead + 1)

        val patterns = recurringPatternsProvider.getConfirmedPatterns()
        val ruleIds = patterns
            .filter { it.id != null }
            .mapNotNull { it.id }
            .distinct()

        // ── Part 1: Manual rules — READ-ONLY occurrence path ────────────────
        // P6-CURRENT-024: project occurrences in memory (no writes) and merge with
        // any already-materialized rows (barrier-guarded read) by occurrenceKey so
        // user overrides win and there is no double-count.
        val manualUpcoming = if (ruleIds.isNotEmpty()) {
            val projected = mutableListOf<RecurringOccurrence>()
            for (ruleId in ruleIds) {
                try {
                    projected += recurringLifecycleCoordinator.projectOccurrences(
                        ruleId = ruleId,
                        startDate = startOfToday,
                        endDate = endDate
                    )
                } catch (e: Exception) {
                    Timber.w(e, "$TAG: projectOccurrences failed for ruleId=%d, skipping rule", ruleId)
                }
            }
            val materialized = try {
                databaseReadBarrier.checkReadAllowed("CashFlowCalculator.getUpcomingBills.readOccurrences")
                recurringOccurrenceDao.getByDateRange(startOfToday, endDate)
                    .filter {
                        it.sourceType == RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE &&
                            it.sourceId in ruleIds
                    }
            } catch (e: Exception) {
                Timber.w(e, "$TAG: reading materialized occurrences failed in getUpcomingBills")
                emptyList()
            }
            val mergedByKey = LinkedHashMap<String, RecurringOccurrence>()
            for (occ in projected) mergedByKey[occ.occurrenceKey] = occ
            for (occ in materialized) mergedByKey[occ.occurrenceKey] = occ
            mergedByKey.values
                .filter { it.status == "PLANNED" }
                .map { it.toRecurringPattern() }
        } else {
            emptyList()
        }

        // ── Part 2: Detected-only patterns — simplified ad-hoc fallback ──────
        val detectedUpcoming = patterns
            .filter { it.id == null }
            .filter { it.nextExpectedDate >= startOfToday && it.nextExpectedDate < endDate }

        return manualUpcoming + detectedUpcoming
    }

    // Boundary mapper: data-layer TransactionType -> domain DomainTransactionType
    private fun com.yourname.expensetracker.data.database.entity.TransactionType.toDomain(): DomainTransactionType =
        when (this) {
            com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE -> DomainTransactionType.PURCHASE
            com.yourname.expensetracker.data.database.entity.TransactionType.WITHDRAWAL -> DomainTransactionType.WITHDRAWAL
            com.yourname.expensetracker.data.database.entity.TransactionType.TRANSFER -> DomainTransactionType.TRANSFER
            com.yourname.expensetracker.data.database.entity.TransactionType.DEPOSIT -> DomainTransactionType.DEPOSIT
            com.yourname.expensetracker.data.database.entity.TransactionType.UNKNOWN -> DomainTransactionType.UNKNOWN
        }

    /**
     * P6-CURRENT-019: Content signature used for content-aware dedup of predicted
     * recurring items against actual expenses and other predictions on the same day.
     */
    private data class ContentSignature(
        val merchantKey: String,
        val currency: String,
        val amount: Double
    )

    /**
     * Canonical merchant key with a consistent fallback (mirrors the fallback used
     * across forecast paths): when [MerchantKeyGenerator] yields blank, fall back to
     * the lowercase trimmed name so blank-key merchants still dedupe against
     * themselves rather than collapsing all blanks together.
     */
    private fun merchantKeyOf(merchant: String): String =
        MerchantKeyGenerator.generate(merchant)
            .takeIf { it.isNotBlank() }
            ?: merchant.lowercase(Locale.US).trim()

    /**
     * P6-CURRENT-019: Amounts match when within ±[AMOUNT_TOLERANCE_PERCENT] of the
     * larger magnitude. Materially distinct amounts (e.g. €15 vs €70) never match, so
     * legitimately different same-merchant bills are preserved.
     *
     * ## DBG-05: Known limitation — intentional over-merge within ±10% (DO NOT "fix")
     * Because the tolerance is proportional (±10% of the larger amount), two genuinely
     * DISTINCT same-merchant, same-currency bills that happen to fall within 10% of each
     * other on the same day WILL be merged into one (e.g. an Amazon €100 subscription and
     * a separate €105 one-off → treated as a single recurring item). This is a deliberate
     * accuracy tradeoff: it is strictly better than the previous name-only dedup (which
     * merged ALL same-merchant items regardless of amount), and the proportional band is
     * what lets us absorb normal price drift (FX rounding, minor plan changes) without
     * spawning phantom duplicate predictions. Tightening the band to eliminate this case
     * would re-introduce false duplicates for the far more common drift scenario, so the
     * behavior is intentional. Documented here so future readers do not mistake it for a bug.
     */
    private fun amountsWithinTolerance(a: Double, b: Double): Boolean {
        if (!a.isFinite() || !b.isFinite()) return false
        val reference = maxOf(abs(a), abs(b))
        if (reference == 0.0) return abs(a - b) < 0.0001
        return abs(a - b) <= reference * AMOUNT_TOLERANCE_PERCENT
    }

    /** Formats an epoch-ms instant to a yyyy-MM-dd key (avoids cross-year collisions). */
    private fun formatDayKey(epochMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /**
     * P6-CURRENT-018: Ad-hoc due-date expansion for a manual pattern, used only as a
     * fallback when occurrence projection fails for that rule. Mirrors the
     * calendar-aware advancement used by [FinancialStressForecastEngine.expandDetectedPatterns].
     * Returns due dates in `[startTime, endTime)` (half-open, matching the projection range).
     */
    private fun expandPatternDueDates(
        pattern: RecurringPattern,
        startTime: Long,
        endTime: Long
    ): List<Long> {
        if (pattern.frequency == RecurrenceFrequency.IRREGULAR) return emptyList()
        if (startTime >= endTime) return emptyList()

        val dueDates = mutableListOf<Long>()
        var next = pattern.nextExpectedDate
        var iterations = 0
        while (next < endTime) {
            if (++iterations > 1000) {
                Timber.w("$TAG: expandPatternDueDates exceeded 1000 iterations, breaking")
                break
            }
            if (next >= startTime) dueDates.add(next)
            next = when (pattern.frequency) {
                RecurrenceFrequency.WEEKLY -> TimePeriodUtils.addDays(next, 7)
                RecurrenceFrequency.BIWEEKLY -> TimePeriodUtils.addDays(next, 14)
                RecurrenceFrequency.MONTHLY -> TimePeriodUtils.addMonths(next, 1)
                RecurrenceFrequency.QUARTERLY -> TimePeriodUtils.addMonths(next, 3)
                RecurrenceFrequency.SEMI_ANNUALLY -> TimePeriodUtils.addMonths(next, 6)
                RecurrenceFrequency.ANNUALLY -> TimePeriodUtils.addYears(next, 1)
                RecurrenceFrequency.IRREGULAR -> break
            }
        }
        return dueDates
    }

    companion object {
        private const val TAG = "CashFlowCalculator"
        /** P6-CURRENT-019: ±10% amount tolerance for content-aware dedup. */
        private const val AMOUNT_TOLERANCE_PERCENT = 0.10
    }
}
