package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import com.yourname.expensetracker.domain.util.TimePeriodUtils
import com.yourname.expensetracker.domain.util.TimeProvider
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Detects anomalous transactions using three complementary statistical methods:
 *
 *  1. IQR (Interquartile Range) — flags amounts above Q3 + 1.5×IQR per category.
 *     More robust than Z-score for the typical right-skewed expense distribution.
 *
 *  2. MAD (Median Absolute Deviation) — modified Z-score > 3.5 per category.
 *     The single most robust method for skewed data; resistant to masking by
 *     multiple outliers that inflate the mean/std dev.
 *
 *  3. Contextual — sub-groups expenses by (dayOfWeek, timeSlot) before computing
 *     IQR thresholds. Catches transactions that are normal globally but unusual
 *     for that specific context (e.g. €50 groceries at 2 AM on a Wednesday).
 *
 * The three methods run in union: any single method flagging a transaction is
 * sufficient. MAD takes priority in [detectionMethod]; contextual hits add a
 * human-readable [AnomalyTransaction.contextualNote].
 *
 * This class operates purely on the in-memory expense list — no DB calls.
 * It complements [InsightsEngine]'s merchant-level DB-backed detection.
 *
 * ## AI-2: Recurring-expense suppression (RESOLVED)
 * Recurring/scheduled expenses (rent, subscriptions, insurance premiums) are
 * now suppressed via the [suppressRecurringMerchantKeys] parameter on [detect].
 * The caller passes a set of merchant keys derived from recurring rules
 * ([com.yourname.expensetracker.domain.logic.RecurringExpenseEngine.getPatterns]),
 * and expenses whose merchant key matches are excluded from statistical outlier
 * detection. This prevents routine bills from triggering anomaly alerts while
 * still catching unusual spikes in recurring amounts.
 */
@Singleton
class AnomalyDetector @Inject constructor(
    private val timeProvider: TimeProvider
) {

    private fun ExpenseSnapshot.toAnalyticsSummary(): AnalyticsTransactionSummary {
        return AnalyticsTransactionSummary(
            id = id,
            amount = amount,
            effectiveAmount = effectiveAmount,
            currency = currency,
            merchant = merchant,
            date = date,
            categoryId = categoryId
        )
    }

    companion object {
        // Minimum samples required before any method runs for a group
        private const val MIN_SAMPLES_GLOBAL = 5
        private const val MIN_SAMPLES_CONTEXTUAL = 3

        // IQR fence multiplier (Tukey's standard: 1.5 = mild outlier, 3.0 = extreme)
        private const val IQR_FENCE = 1.5
        private const val ZERO_DISPERSION_MULTIPLIER = 3.0

        // Modified Z-score threshold (Iglewicz & Hoaglin recommend 3.5)
        private const val MAD_ZSCORE_THRESHOLD = 3.5

        // Constant for modified Z-score formula
        private const val MAD_SCALE = 0.6745
    }

    // ─── Time-of-day buckets ──────────────────────────────────────────────────

    private enum class TimeSlot(val label: String) {
        MORNING("morning"),       //  6–11
        AFTERNOON("afternoon"),   // 12–17
        EVENING("evening"),       // 18–21
        NIGHT("night")            // 22–5
    }

    private fun timeSlot(timestampMs: Long): TimeSlot {
        val hour = TimePeriodUtils.getHourOfDay(timestampMs)
        return when (hour) {
            in 6..11  -> TimeSlot.MORNING
            in 12..17 -> TimeSlot.AFTERNOON
            in 18..21 -> TimeSlot.EVENING
            else      -> TimeSlot.NIGHT
        }
    }

    private fun dayName(timestampMs: Long): String {
        val dow = TimePeriodUtils.getDayOfWeek(timestampMs)
        return when (dow) {
            java.util.Calendar.MONDAY    -> "Monday"
            java.util.Calendar.TUESDAY   -> "Tuesday"
            java.util.Calendar.WEDNESDAY -> "Wednesday"
            java.util.Calendar.THURSDAY  -> "Thursday"
            java.util.Calendar.FRIDAY    -> "Friday"
            java.util.Calendar.SATURDAY  -> "Saturday"
            else                         -> "Sunday"
        }
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /**
     * Detects statistical anomalies in [monthPeriod]'s expenses.
     *
     * Returns a deduplicated, sorted list of [AnomalyTransaction]s.
     * Results are sorted by [AnomalyTransaction.deviationMultiple] descending
     * (most extreme first).
     *
     * ## AI-2: Recurring-expense suppression
     * When [suppressRecurringMerchantKeys] is non-empty, any expense whose
     * [ExpenseSnapshot.merchantKey] matches a key in this set is excluded from
     * anomaly detection. This prevents routine recurring bills (rent, subscriptions,
     * insurance) from triggering statistical outlier alerts. The caller is
     * responsible for computing the set of merchant keys from recurring rules
     * (e.g. via [com.yourname.expensetracker.domain.logic.RecurringExpenseEngine.getPatterns]).
     *
     * @param suppressRecurringMerchantKeys Set of merchant keys to suppress. Default empty = no suppression.
     */
    fun detect(
        monthPeriod: MonthPeriod,
        categoryMap: Map<Long, AnalyticsCategoryRef>,
        allExpenses: List<ExpenseSnapshot>,
        displayCurrency: String = "EUR",
        suppressRecurringMerchantKeys: Set<String> = emptySet()
    ): List<AnomalyTransaction> {

        val monthExpenses = allExpenses.filter { expense ->
            expense.date >= monthPeriod.startMs &&
            expense.date < monthPeriod.endMs &&
            expense.transactionType == DomainTransactionType.PURCHASE &&
            !expense.isNotMine &&
            // AI-2: Skip expenses whose merchant key matches a recurring rule
            (suppressRecurringMerchantKeys.isEmpty() || expense.merchantKey == null ||
                expense.merchantKey !in suppressRecurringMerchantKeys)
        }

        if (monthExpenses.size < MIN_SAMPLES_GLOBAL) return emptyList()

        // expense.id → best AnomalyTransaction (highest-priority method wins)
        val flagged = mutableMapOf<Long, AnomalyTransaction>()

        val byCategory = monthExpenses.groupBy { it.categoryId }

        for ((categoryId, expenses) in byCategory) {
            val category = categoryMap[categoryId]
            val amounts = expenses.map { it.effectiveAmount }

            if (amounts.size < 2) continue

            val categoryAvg = amounts.average()

            // ── 1. IQR ────────────────────────────────────────────────────────
            if (amounts.size >= MIN_SAMPLES_GLOBAL) {
                val iqrOutliers = detectIqr(expenses, amounts, category, categoryAvg, displayCurrency)
                iqrOutliers.forEach { anomaly ->
                    flagged.merge(anomaly.expense.id, anomaly) { existing, new ->
                        // MAD > IQR > CONTEXTUAL > MULTIPLIER in priority
                        if (new.detectionMethod.ordinal > existing.detectionMethod.ordinal) new
                        else existing
                    }
                }
            }

            // ── 2. MAD ────────────────────────────────────────────────────────
            if (amounts.size >= MIN_SAMPLES_GLOBAL) {
                val madOutliers = detectMad(expenses, amounts, category, categoryAvg, displayCurrency)
                madOutliers.forEach { anomaly ->
                    flagged.merge(anomaly.expense.id, anomaly) { existing, new ->
                        if (new.detectionMethod.ordinal > existing.detectionMethod.ordinal) new
                        else existing
                    }
                }
            }

            // ── 3. Contextual ─────────────────────────────────────────────────
            val contextualOutliers = detectContextual(expenses, category, categoryAvg, displayCurrency)
            contextualOutliers.forEach { anomaly ->
                // Only add contextual if not already flagged by a stronger method
                if (!flagged.containsKey(anomaly.expense.id)) {
                    flagged[anomaly.expense.id] = anomaly
                } else {
                    // Preserve existing method but attach contextual note if absent
                    val existing = flagged[anomaly.expense.id]!!
                    if (existing.contextualNote == null && anomaly.contextualNote != null) {
                        flagged[anomaly.expense.id] = existing.copy(
                            contextualNote = anomaly.contextualNote
                        )
                    }
                }
            }
        }

        return flagged.values
            .sortedByDescending { it.deviationMultiple }
    }


    // ─── Detection methods ────────────────────────────────────────────────────

    /**
     * IQR method: flags amounts above Q3 + [IQR_FENCE] × IQR.
     *
     * Better than Z-score for right-skewed expense distributions because it
     * uses the median-based quartiles rather than the mean, which is sensitive
     * to the very outliers we are trying to detect.
     */
    private fun detectIqr(
        expenses: List<ExpenseSnapshot>,
        amounts: List<Double>,
        category: AnalyticsCategoryRef?,
        categoryAvg: Double,
        displayCurrency: String
    ): List<AnomalyTransaction> {
        val sorted = amounts.sorted()
        val q1 = percentile(sorted, 25.0)
        val q3 = percentile(sorted, 75.0)
        val iqr = q3 - q1
        if (iqr == 0.0) {
            return detectZeroDispersionOutliers(
                expenses = expenses,
                category = category,
                categoryAvg = categoryAvg,
                baseline = q3,
                detectionMethod = AnomalyMethod.IQR,
                displayCurrency = displayCurrency
            )
        }

        val upperFence = q3 + IQR_FENCE * iqr

        return expenses.filter { it.effectiveAmount > upperFence }.map { expense ->
            AnomalyTransaction(
                expense = expense.toAnalyticsSummary(),
                merchantAvg = categoryAvg,
                deviationMultiple = if (categoryAvg > 0) (expense.effectiveAmount / categoryAvg).toFloat() else 0f,
                category = category,
                detectionMethod = AnomalyMethod.IQR,
                categoryAvg = categoryAvg,
                displayCurrency = displayCurrency
            )
        }
    }

    /**
     * MAD method: flags transactions whose modified Z-score exceeds [MAD_ZSCORE_THRESHOLD].
     *
     * Modified Z-score = 0.6745 × (xᵢ − median) / MAD
     *
     * Using MAD instead of std dev makes this resistant to the "masking" effect
     * where multiple outliers inflate the standard deviation and make themselves
     * harder to detect. Recommended by Iglewicz & Hoaglin (1993).
     */
    private fun detectMad(
        expenses: List<ExpenseSnapshot>,
        amounts: List<Double>,
        category: AnalyticsCategoryRef?,
        categoryAvg: Double,
        displayCurrency: String
    ): List<AnomalyTransaction> {
        val sorted = amounts.sorted()
        val median = percentile(sorted, 50.0)

        val absoluteDeviations = amounts.map { abs(it - median) }.sorted()
        val mad = percentile(absoluteDeviations, 50.0)

        if (mad == 0.0) {
            return detectZeroDispersionOutliers(
                expenses = expenses,
                category = category,
                categoryAvg = categoryAvg,
                baseline = median,
                detectionMethod = AnomalyMethod.MAD,
                displayCurrency = displayCurrency
            )
        }

        return expenses.filter { expense ->
            val modifiedZ = MAD_SCALE * (expense.effectiveAmount - median) / mad
            modifiedZ > MAD_ZSCORE_THRESHOLD
        }.map { expense ->
            AnomalyTransaction(
                expense = expense.toAnalyticsSummary(),
                merchantAvg = categoryAvg,
                deviationMultiple = if (categoryAvg > 0) (expense.effectiveAmount / categoryAvg).toFloat() else 0f,
                category = category,
                detectionMethod = AnomalyMethod.MAD,
                categoryAvg = categoryAvg,
                displayCurrency = displayCurrency
            )
        }
    }

    /**
     * Contextual method: sub-groups expenses by (dayOfWeek, timeSlot) and runs
     * IQR within each sub-group.
     *
     * A €50 grocery charge is unremarkable on a Saturday afternoon but unusual
     * at 2 AM on a Wednesday. This method catches that signal.
     *
     * Requires at least [MIN_SAMPLES_CONTEXTUAL] entries per context group.
     */
    private fun detectContextual(
        expenses: List<ExpenseSnapshot>,
        category: AnalyticsCategoryRef?,
        categoryAvg: Double,
        displayCurrency: String
    ): List<AnomalyTransaction> {
        val result = mutableListOf<AnomalyTransaction>()

        // Group by (dayOfWeek name, timeSlot)
        val byContext = expenses.groupBy { expense ->
            Pair(dayName(expense.date), timeSlot(expense.date))
        }

        for ((context, contextExpenses) in byContext) {
            if (contextExpenses.size < MIN_SAMPLES_CONTEXTUAL) continue

            val (day, slot) = context
            val amounts = contextExpenses.map { it.effectiveAmount }.sorted()
            val q1 = percentile(amounts, 25.0)
            val q3 = percentile(amounts, 75.0)
            val iqr = q3 - q1
            if (iqr == 0.0) {
                result += detectZeroDispersionOutliers(
                    expenses = contextExpenses,
                    category = category,
                    categoryAvg = categoryAvg,
                    baseline = q3,
                    detectionMethod = AnomalyMethod.CONTEXTUAL,
                    contextualNote = "Unusual for a $day ${slot.label}",
                    displayCurrency = displayCurrency
                )
                continue
            }

            val upperFence = q3 + IQR_FENCE * iqr

            contextExpenses
                .filter { it.effectiveAmount > upperFence }
                .forEach { expense ->
                    val contextAvg = amounts.average()
                    result.add(
                        AnomalyTransaction(
                            expense = expense.toAnalyticsSummary(),
                            merchantAvg = contextAvg,
                            deviationMultiple = if (contextAvg > 0) (expense.effectiveAmount / contextAvg).toFloat() else 0f,
                            category = category,
                            detectionMethod = AnomalyMethod.CONTEXTUAL,
                            contextualNote = "Unusual for a $day ${slot.label}",
                            categoryAvg = categoryAvg,
                            displayCurrency = displayCurrency
                        )
                    )
                }
        }

        return result
    }

    // ─── Statistics helpers ───────────────────────────────────────────────────

    /**
     * Interpolated percentile on a pre-sorted list.
     * Uses the "nearest rank" method — simple and correct for our data sizes.
     */
    private fun percentile(sorted: List<Double>, pct: Double): Double {
        if (sorted.isEmpty()) return 0.0
        if (sorted.size == 1) return sorted[0]
        val index = (pct / 100.0 * (sorted.size - 1))
        val lower = sorted[index.toInt()]
        val upper = sorted[(index.toInt() + 1).coerceAtMost(sorted.size - 1)]
        val fraction = index - index.toInt()
        return lower + fraction * (upper - lower)
    }

    private fun detectZeroDispersionOutliers(
        expenses: List<ExpenseSnapshot>,
        category: AnalyticsCategoryRef?,
        categoryAvg: Double,
        baseline: Double,
        detectionMethod: AnomalyMethod,
        contextualNote: String? = null,
        displayCurrency: String
    ): List<AnomalyTransaction> {
        if (baseline <= 0.0) return emptyList()

        val spikeThreshold = baseline * ZERO_DISPERSION_MULTIPLIER
        return expenses
            .filter { it.effectiveAmount > spikeThreshold }
            .map { expense ->
                AnomalyTransaction(
                    expense = expense.toAnalyticsSummary(),
                    merchantAvg = categoryAvg,
                    deviationMultiple = if (baseline > 0) (expense.effectiveAmount / baseline).toFloat() else 0f,
                    category = category,
                    detectionMethod = detectionMethod,
                    contextualNote = contextualNote,
                    categoryAvg = categoryAvg,
                    displayCurrency = displayCurrency
                )
            }

}

}
