package com.yourname.expensetracker.ui.screens.home

import com.yourname.expensetracker.domain.forecasting.StressRecommendationAction
import com.yourname.expensetracker.domain.forecasting.StressRecommendationKind
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * stress-forecast-actions AE-8, AE-9, AE-10 (INV-3, INV-4, INV-5, INV-6): the Home dispatcher
 * routes by typed action only, fires exactly one callback for an actionable action and none for
 * an informational one. The dispatcher takes no text, so routing cannot depend on locale.
 */
class StressRecommendationActionMapperTest {

    private val zone = ZoneId.of("Europe/Athens")
    private lateinit var originalZone: TimeZone

    private var recurringCalls = 0
    private var savingsGoalsCalls = 0
    private val transactionFilters = mutableListOf<TransactionFilter>()

    @Before
    fun pinTimeZone() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zone))
        recurringCalls = 0
        savingsGoalsCalls = 0
        transactionFilters.clear()
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun dispatch(action: StressRecommendationAction, referenceNowMillis: Long = millis(2026, 2, 15, 12)) {
        dispatchStressRecommendationAction(
            action = action,
            referenceNowMillis = referenceNowMillis,
            onOpenRecurring = { recurringCalls++ },
            onOpenTransactions = { transactionFilters += it },
            onOpenSavingsGoals = { savingsGoalsCalls++ }
        )
    }

    private fun assertNoNavigation() {
        assertEquals(0, recurringCalls)
        assertEquals(0, savingsGoalsCalls)
        assertEquals(emptyList<TransactionFilter>(), transactionFilters)
    }

    @Test
    fun `AE-8 OpenRecurring fires onOpenRecurring exactly once and nothing else`() {
        dispatch(StressRecommendationAction.OpenRecurring)

        assertEquals(1, recurringCalls)
        assertEquals(0, savingsGoalsCalls)
        assertEquals(emptyList<TransactionFilter>(), transactionFilters)
    }

    @Test
    fun `AE-8 OpenSavingsGoals fires onOpenSavingsGoals exactly once and nothing else`() {
        dispatch(StressRecommendationAction.OpenSavingsGoals)

        assertEquals(1, savingsGoalsCalls)
        assertEquals(0, recurringCalls)
        assertEquals(emptyList<TransactionFilter>(), transactionFilters)
    }

    @Test
    fun `AE-8 OpenTransactionsThisMonth fires onOpenTransactions exactly once and nothing else`() {
        dispatch(StressRecommendationAction.OpenTransactionsThisMonth)

        assertEquals(1, transactionFilters.size)
        assertEquals(0, recurringCalls)
        assertEquals(0, savingsGoalsCalls)
    }

    @Test
    fun `AE-8 None fires no navigation callback`() {
        dispatch(StressRecommendationAction.None)

        assertNoNavigation()
    }

    @Test
    fun `AE-8 informational kinds R4 R5 and R6 fire no navigation callback`() {
        listOf(
            StressRecommendationKind.DELAY_NON_ESSENTIAL_PURCHASES,
            StressRecommendationKind.HEALTHY_OUTLOOK,
            StressRecommendationKind.NO_IMMEDIATE_CONCERNS
        ).forEach { kind ->
            dispatch(kind.action)
        }

        assertNoNavigation()
    }

    @Test
    fun `AE-8 actionable kinds route through their kind action to the matching callback`() {
        dispatch(StressRecommendationKind.REVIEW_RECURRING_OBLIGATIONS.action)
        dispatch(StressRecommendationKind.REDUCE_DISCRETIONARY_SPENDING.action)
        dispatch(StressRecommendationKind.BUILD_EMERGENCY_BUFFER.action)

        assertEquals(1, recurringCalls)
        assertEquals(1, transactionFilters.size)
        assertEquals(1, savingsGoalsCalls)
    }

    @Test
    fun `AE-8 each tap fires once so two taps fire twice`() {
        dispatch(StressRecommendationAction.OpenRecurring)
        dispatch(StressRecommendationAction.OpenRecurring)

        assertEquals(2, recurringCalls)
    }

    @Test
    fun `AE-8 this-month filter spans first of month to first of next month for a mid-month reference`() {
        val reference = millis(2026, 2, 15, 12)

        dispatch(StressRecommendationAction.OpenTransactionsThisMonth, reference)

        assertEquals(
            TransactionFilter(dateRange = millis(2026, 2, 1, 0) to millis(2026, 3, 1, 0)),
            transactionFilters.single()
        )
    }

    @Test
    fun `AE-8 this-month filter at the last minute of a month stays in that month`() {
        val reference = millis(2026, 1, 31, 23, 59)

        dispatch(StressRecommendationAction.OpenTransactionsThisMonth, reference)

        assertEquals(
            TransactionFilter(dateRange = millis(2026, 1, 1, 0) to millis(2026, 2, 1, 0)),
            transactionFilters.single()
        )
    }

    @Test
    fun `AE-8 this-month filter at the first instant of a month starts that month`() {
        val reference = millis(2026, 3, 1, 0)

        dispatch(StressRecommendationAction.OpenTransactionsThisMonth, reference)

        assertEquals(
            TransactionFilter(dateRange = millis(2026, 3, 1, 0) to millis(2026, 4, 1, 0)),
            transactionFilters.single()
        )
    }

    @Test
    fun `AE-8 this-month filter across a DST change month uses local midnights`() {
        // Europe/Athens switches to summer time on 2026-03-29, inside this month.
        val reference = millis(2026, 3, 29, 12)

        dispatch(StressRecommendationAction.OpenTransactionsThisMonth, reference)

        val range = transactionFilters.single().dateRange
        assertEquals(millis(2026, 3, 1, 0) to millis(2026, 4, 1, 0), range)
    }

    @Test
    fun `AE-8 this-month filter in December ends at the first of January of the next year`() {
        val reference = millis(2026, 12, 20, 9)

        dispatch(StressRecommendationAction.OpenTransactionsThisMonth, reference)

        assertEquals(
            TransactionFilter(dateRange = millis(2026, 12, 1, 0) to millis(2027, 1, 1, 0)),
            transactionFilters.single()
        )
    }

    @Test
    fun `AE-8 this-month filter sets only the date range`() {
        dispatch(StressRecommendationAction.OpenTransactionsThisMonth)

        val filter = transactionFilters.single()
        assertNull(filter.categoryId)
        assertNull(filter.merchantName)
        assertNull(filter.transactionType)
        assertNull(filter.ownership)
        assertNull(filter.minAmount)
        assertNull(filter.maxAmount)
    }

    @Test
    fun `AE-9 routing is identical under en el and es default locales`() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            val outcomes = listOf(java.util.Locale.ENGLISH, java.util.Locale("el"), java.util.Locale("es")).map { locale ->
                java.util.Locale.setDefault(locale)
                recurringCalls = 0
                savingsGoalsCalls = 0
                transactionFilters.clear()
                StressRecommendationAction.values().forEach { dispatch(it) }
                Triple(recurringCalls, savingsGoalsCalls, transactionFilters.toList())
            }

            assertEquals(1, outcomes.map { it.first }.distinct().single())
            assertEquals(1, outcomes.map { it.second }.distinct().single())
            assertEquals(1, outcomes.map { it.third }.distinct().single().size)
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `AE-9 informational R6 never opens transactions under any default locale`() {
        val originalLocale = java.util.Locale.getDefault()
        try {
            listOf(java.util.Locale.ENGLISH, java.util.Locale("el"), java.util.Locale("es")).forEach { locale ->
                java.util.Locale.setDefault(locale)
                dispatch(StressRecommendationKind.NO_IMMEDIATE_CONCERNS.action)
            }
            assertNoNavigation()
        } finally {
            java.util.Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `AE-10 dispatcher input is the typed action only so text containing spending cannot change routing`() {
        // The dispatcher signature has no String parameter; an informational kind whose display
        // text would contain "spending", "emergency" or "subscriptions" still routes by kind.
        val delayNonEssential = StressRecommendationKind.DELAY_NON_ESSENTIAL_PURCHASES
        dispatch(delayNonEssential.action)

        assertNoNavigation()
    }

    @Test
    fun `AE-10 every action value has a defined dispatch outcome without throwing`() {
        StressRecommendationAction.values().forEach { action ->
            dispatch(action)
        }

        assertEquals(1, recurringCalls)
        assertEquals(1, savingsGoalsCalls)
        assertEquals(1, transactionFilters.size)
    }
}
