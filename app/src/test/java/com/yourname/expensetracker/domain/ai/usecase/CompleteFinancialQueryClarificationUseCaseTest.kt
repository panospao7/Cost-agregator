package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.model.AnswerMode
import com.yourname.expensetracker.domain.ai.model.ClarificationChoice
import com.yourname.expensetracker.domain.ai.model.ClarificationCompletion
import com.yourname.expensetracker.domain.ai.model.ClarificationSlot
import com.yourname.expensetracker.domain.ai.model.ExpenseQueryFilters
import com.yourname.expensetracker.domain.ai.model.FinancialQueryIntent
import com.yourname.expensetracker.domain.ai.model.PeriodChoice
import com.yourname.expensetracker.domain.ai.model.PeriodGranularity
import com.yourname.expensetracker.domain.ai.model.PresentationChoice
import com.yourname.expensetracker.domain.ai.model.QueryComparison
import com.yourname.expensetracker.domain.ai.model.QueryGrouping
import com.yourname.expensetracker.domain.ai.model.QueryMetric
import com.yourname.expensetracker.domain.ai.model.QueryOwnershipScope
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.PeriodRange
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlin.time.Duration.Companion.seconds

/**
 * ai-fixes C4: INV-C13 slot filling (AE-C20, AE-C21).
 *
 * The completer resolves periods in ZoneId.systemDefault(); the JVM default zone is pinned to
 * Europe/Athens and restored afterwards. NOW = Fri 2026-10-16 12:00 EEST (explicit `nowMs`).
 */
class CompleteFinancialQueryClarificationUseCaseTest {

    private val athens: ZoneId = ZoneId.of("Europe/Athens")
    private var originalZone: TimeZone? = null

    @Before
    fun pinDefaultZoneToAthens() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
    }

    @After
    fun restoreDefaultZone() {
        originalZone?.let { TimeZone.setDefault(it) }
    }

    private fun athensMs(year: Int, month: Int, day: Int, hour: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, 0, 0, 0, athens).toInstant().toEpochMilli()

    private val nowOct16: Long get() = athensMs(2026, 10, 16, 12)

    private fun useCase(clockMs: Long = 0L) = CompleteFinancialQueryClarificationUseCase(FakeTimeProvider(clockMs))

    /** "how much on food": Food filter, TOTAL, no period yet. */
    private fun foodBase() = FinancialQueryIntent(
        rawQuery = "how much on food",
        normalizedQuery = "how much on food",
        filters = ExpenseQueryFilters(categoryIds = setOf(1L)),
        metric = QueryMetric.TOTAL
    )

    /** Every non-slot field set to a non-default value so any accidental reset is visible. */
    private fun richBase() = FinancialQueryIntent(
        rawQuery = "Average spend per merchant for my food over 5 EUR",
        normalizedQuery = "average spend per merchant for my food over 5 eur",
        filters = ExpenseQueryFilters(
            period = PeriodRange(100L, 200L),
            periodGranularity = PeriodGranularity.DAY,
            merchants = setOf("Bakery", "Cafe"),
            categoryIds = setOf(1L, 4L),
            transactionTypes = setOf(DomainTransactionType.PURCHASE),
            ownership = QueryOwnershipScope.MINE,
            minAmount = 5.0,
            maxAmount = 250.5,
            currency = "EUR",
            sourceType = "manual",
            status = "active"
        ),
        metric = QueryMetric.AVERAGE,
        grouping = QueryGrouping.MERCHANT,
        comparison = QueryComparison.PREVIOUS_EQUIVALENT_PERIOD,
        answerMode = AnswerMode.NAVIGATE
    )

    private fun periodCompletion(base: FinancialQueryIntent) = ClarificationCompletion(
        baseIntent = base,
        slot = ClarificationSlot.PERIOD,
        choices = listOf(
            ClarificationChoice.Period(PeriodChoice.THIS_MONTH),
            ClarificationChoice.Period(PeriodChoice.THIS_WEEK),
            ClarificationChoice.Period(PeriodChoice.LAST_MONTH)
        )
    )

    private fun presentationCompletion(base: FinancialQueryIntent) = ClarificationCompletion(
        baseIntent = base,
        slot = ClarificationSlot.PRESENTATION,
        choices = listOf(
            ClarificationChoice.Presentation(PresentationChoice.BREAKDOWN),
            ClarificationChoice.Presentation(PresentationChoice.TRANSACTION_LIST)
        )
    )

    // ---- PERIOD slot (AE-C20) -----------------------------------------------------------------

    @Test
    fun `AE-C20 last month fills September with MONTH granularity and keeps the Food filter`() = runTest(timeout = 30.seconds) {
        val base = foodBase()

        val completed = useCase()(
            periodCompletion(base),
            ClarificationChoice.Period(PeriodChoice.LAST_MONTH),
            nowMs = nowOct16
        )

        assertEquals(PeriodRange(athensMs(2026, 9, 1), athensMs(2026, 10, 1)), completed.filters.period)
        assertEquals(PeriodGranularity.MONTH, completed.filters.periodGranularity)
        assertEquals(setOf(1L), completed.filters.categoryIds)
        assertEquals(QueryMetric.TOTAL, completed.metric)
        // The same value the resolver produces for the same choice and clock.
        val resolved = AssistantPeriodResolver.resolveChoice(PeriodChoice.LAST_MONTH, nowOct16)
        assertEquals(resolved.startMs, completed.filters.period!!.start)
        assertEquals(resolved.endMs, completed.filters.period!!.end)
        assertEquals(resolved.granularity, completed.filters.periodGranularity)
        // Byte-for-byte: only the period and its granularity differ from the base.
        assertEquals(
            base.copy(
                filters = base.filters.copy(
                    period = PeriodRange(athensMs(2026, 9, 1), athensMs(2026, 10, 1)),
                    periodGranularity = PeriodGranularity.MONTH
                )
            ),
            completed
        )
    }

    @Test
    fun `AE-C20 this month fills the full October month with MONTH granularity`() = runTest(timeout = 30.seconds) {
        val completed = useCase()(
            periodCompletion(foodBase()),
            ClarificationChoice.Period(PeriodChoice.THIS_MONTH),
            nowMs = nowOct16
        )

        assertEquals(PeriodRange(athensMs(2026, 10, 1), athensMs(2026, 11, 1)), completed.filters.period)
        assertEquals(PeriodGranularity.MONTH, completed.filters.periodGranularity)
        assertEquals(setOf(1L), completed.filters.categoryIds)
    }

    @Test
    fun `AE-C20 this week fills Monday to Monday with WEEK granularity`() = runTest(timeout = 30.seconds) {
        val completed = useCase()(
            periodCompletion(foodBase()),
            ClarificationChoice.Period(PeriodChoice.THIS_WEEK),
            nowMs = nowOct16
        )

        // Fri 2026-10-16 belongs to the week Mon 10-12 .. Mon 10-19.
        assertEquals(PeriodRange(athensMs(2026, 10, 12), athensMs(2026, 10, 19)), completed.filters.period)
        assertEquals(PeriodGranularity.WEEK, completed.filters.periodGranularity)
        assertEquals(setOf(1L), completed.filters.categoryIds)
    }

    @Test
    fun `INV-C13 period completion replaces both the period and a stale granularity`() = runTest(timeout = 30.seconds) {
        // Boundary: the base carries a custom range with a stale DAY granularity; both must be replaced.
        val base = richBase()
        assertEquals(PeriodGranularity.DAY, base.filters.periodGranularity)

        val completed = useCase()(
            periodCompletion(base),
            ClarificationChoice.Period(PeriodChoice.THIS_WEEK),
            nowMs = nowOct16
        )

        assertEquals(PeriodGranularity.WEEK, completed.filters.periodGranularity)
        assertNotEquals(base.filters.period, completed.filters.period)
        assertEquals(PeriodRange(athensMs(2026, 10, 12), athensMs(2026, 10, 19)), completed.filters.period)
    }

    @Test
    fun `INV-C13 period completion preserves every other intent field exactly`() = runTest(timeout = 30.seconds) {
        val base = richBase()

        val completed = useCase()(
            periodCompletion(base),
            ClarificationChoice.Period(PeriodChoice.LAST_MONTH),
            nowMs = nowOct16
        )

        val expected = base.copy(
            filters = base.filters.copy(
                period = PeriodRange(athensMs(2026, 9, 1), athensMs(2026, 10, 1)),
                periodGranularity = PeriodGranularity.MONTH
            )
        )
        assertEquals(expected, completed)
        assertEquals(base.rawQuery, completed.rawQuery)
        assertEquals(base.normalizedQuery, completed.normalizedQuery)
        assertEquals(base.metric, completed.metric)
        assertEquals(base.grouping, completed.grouping)
        assertEquals(base.comparison, completed.comparison)
        assertEquals(base.answerMode, completed.answerMode)
        assertEquals(base.filters.merchants, completed.filters.merchants)
        assertEquals(base.filters.categoryIds, completed.filters.categoryIds)
        assertEquals(base.filters.transactionTypes, completed.filters.transactionTypes)
        assertEquals(base.filters.ownership, completed.filters.ownership)
        assertEquals(base.filters.minAmount, completed.filters.minAmount)
        assertEquals(base.filters.maxAmount, completed.filters.maxAmount)
        assertEquals(base.filters.currency, completed.filters.currency)
        assertEquals(base.filters.sourceType, completed.filters.sourceType)
        assertEquals(base.filters.status, completed.filters.status)
    }

    @Test
    fun `INV-C13 completing never mutates the base intent held by the completion`() = runTest(timeout = 30.seconds) {
        val base = foodBase()
        val completion = periodCompletion(base)

        useCase()(completion, ClarificationChoice.Period(PeriodChoice.THIS_MONTH), nowMs = nowOct16)

        assertEquals(foodBase(), completion.baseIntent)
        assertEquals(null, completion.baseIntent.filters.period)
    }

    // ---- PRESENTATION slot (AE-C21) ---------------------------------------------------------

    @Test
    fun `AE-C21 breakdown sets metric TOTAL and keeps the grouping`() = runTest(timeout = 30.seconds) {
        val base = richBase()

        val completed = useCase()(
            presentationCompletion(base),
            ClarificationChoice.Presentation(PresentationChoice.BREAKDOWN),
            nowMs = nowOct16
        )

        assertEquals(QueryMetric.TOTAL, completed.metric)
        assertEquals(QueryGrouping.MERCHANT, completed.grouping)
        assertEquals(base.copy(metric = QueryMetric.TOTAL), completed)
    }

    @Test
    fun `AE-C21 breakdown keeps a category grouping too`() = runTest(timeout = 30.seconds) {
        val base = richBase().copy(metric = QueryMetric.COUNT, grouping = QueryGrouping.CATEGORY)

        val completed = useCase()(
            presentationCompletion(base),
            ClarificationChoice.Presentation(PresentationChoice.BREAKDOWN),
            nowMs = nowOct16
        )

        assertEquals(QueryMetric.TOTAL, completed.metric)
        assertEquals(QueryGrouping.CATEGORY, completed.grouping)
    }

    @Test
    fun `AE-C21 transaction list sets metric LIST and clears the grouping`() = runTest(timeout = 30.seconds) {
        val base = richBase()

        val completed = useCase()(
            presentationCompletion(base),
            ClarificationChoice.Presentation(PresentationChoice.TRANSACTION_LIST),
            nowMs = nowOct16
        )

        assertEquals(QueryMetric.LIST, completed.metric)
        assertEquals(QueryGrouping.NONE, completed.grouping)
        assertEquals(base.copy(metric = QueryMetric.LIST, grouping = QueryGrouping.NONE), completed)
    }

    @Test
    fun `INV-C13 presentation completion leaves the period and every filter untouched`() = runTest(timeout = 30.seconds) {
        val base = richBase()

        val breakdown = useCase()(
            presentationCompletion(base),
            ClarificationChoice.Presentation(PresentationChoice.BREAKDOWN),
            nowMs = nowOct16
        )
        val list = useCase()(
            presentationCompletion(base),
            ClarificationChoice.Presentation(PresentationChoice.TRANSACTION_LIST),
            nowMs = nowOct16
        )

        // The clock is irrelevant to the presentation slot: the base period (even if custom) is kept.
        assertEquals(base.filters, breakdown.filters)
        assertEquals(base.filters, list.filters)
        assertEquals(PeriodRange(100L, 200L), breakdown.filters.period)
        assertEquals(PeriodGranularity.DAY, list.filters.periodGranularity)
    }

    // ---- clock (default nowMs comes from the injected TimeProvider) -----------------------------

    @Test
    fun `INV-C13 default nowMs is read from the injected time provider`() = runTest(timeout = 30.seconds) {
        val completer = useCase(clockMs = nowOct16)

        val completed = completer(
            periodCompletion(foodBase()),
            ClarificationChoice.Period(PeriodChoice.LAST_MONTH)
        )

        assertEquals(PeriodRange(athensMs(2026, 9, 1), athensMs(2026, 10, 1)), completed.filters.period)
        assertEquals(PeriodGranularity.MONTH, completed.filters.periodGranularity)
    }

    @Test
    fun `INV-C13 the time provider is read at tap time and not captured at construction`() = runTest(timeout = 30.seconds) {
        val clock = FakeTimeProvider(nowOct16)
        val completer = CompleteFinancialQueryClarificationUseCase(clock)
        val completion = periodCompletion(foodBase())
        val lastMonth = ClarificationChoice.Period(PeriodChoice.LAST_MONTH)

        val first = completer(completion, lastMonth)
        clock.setTime(athensMs(2026, 12, 3, 9))
        val second = completer(completion, lastMonth)

        assertEquals(PeriodRange(athensMs(2026, 9, 1), athensMs(2026, 10, 1)), first.filters.period)
        assertEquals(PeriodRange(athensMs(2026, 11, 1), athensMs(2026, 12, 1)), second.filters.period)
    }

    @Test
    fun `INV-C13 an explicit nowMs wins over the time provider`() = runTest(timeout = 30.seconds) {
        // Boundary: the provider says December, the caller says October 16; the caller wins.
        val completer = useCase(clockMs = athensMs(2026, 12, 3, 9))

        val completed = completer(
            periodCompletion(foodBase()),
            ClarificationChoice.Period(PeriodChoice.THIS_MONTH),
            nowMs = nowOct16
        )

        assertEquals(PeriodRange(athensMs(2026, 10, 1), athensMs(2026, 11, 1)), completed.filters.period)
    }

    @Test
    fun `INV-C13 last month across a year boundary resolves December of the previous year`() = runTest(timeout = 30.seconds) {
        val completed = useCase()(
            periodCompletion(foodBase()),
            ClarificationChoice.Period(PeriodChoice.LAST_MONTH),
            nowMs = athensMs(2027, 1, 15, 12)
        )

        assertEquals(PeriodRange(athensMs(2026, 12, 1), athensMs(2027, 1, 1)), completed.filters.period)
        assertEquals(PeriodGranularity.MONTH, completed.filters.periodGranularity)
    }
}
