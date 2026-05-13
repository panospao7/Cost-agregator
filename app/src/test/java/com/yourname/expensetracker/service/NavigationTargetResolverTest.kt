package com.yourname.expensetracker.service

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.navigation.DomainOwnershipFilter
import com.yourname.expensetracker.domain.engine.DashboardFollowThroughEngine
import com.yourname.expensetracker.domain.model.navigation.DomainTransactionFilter
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * Unit tests for NavigationTargetResolver.
 * Tests mapping of navigation targets to NavigationActions and filter deserialization.
 * 
 * Phase 2: AI Follow-Through - Filter & Navigation Integration
 */
class NavigationTargetResolverTest {

    private lateinit var filterSerializer: TransactionFilterSerializer
    private lateinit var resolver: NavigationTargetResolverImpl

    @Before
    fun setup() {
        filterSerializer = mockk(relaxed = true)
        resolver = NavigationTargetResolverImpl(filterSerializer)
    }

    // ========== canHandle() Tests ==========

    @Test
    fun `canHandle returns true for TRANSACTION_LIST target`() {
        val result = resolver.canHandle(DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST)
        assertTrue(result)
    }

    @Test
    fun `canHandle returns true for CATEGORY_DETAIL target`() {
        val result = resolver.canHandle(DashboardFollowThroughEngine.NAV_TARGET_CATEGORY_DETAIL)
        assertTrue(result)
    }

    @Test
    fun `canHandle returns true for BUDGET_DETAIL target`() {
        val result = resolver.canHandle(DashboardFollowThroughEngine.NAV_TARGET_BUDGET_DETAIL)
        assertTrue(result)
    }

    @Test
    fun `canHandle returns true for ANALYTICS target`() {
        val result = resolver.canHandle(DashboardFollowThroughEngine.NAV_TARGET_ANALYTICS)
        assertTrue(result)
    }

    @Test
    fun `canHandle returns true for MAP target`() {
        val result = resolver.canHandle("MAP")
        assertTrue(result)
    }

    @Test
    fun `canHandle returns true for lowercase map target`() {
        val result = resolver.canHandle("map")
        assertTrue(result)
    }

    @Test
    fun `canHandle returns true for mixed case target`() {
        val result = resolver.canHandle("Transaction_List")
        assertTrue(result)
    }

    @Test
    fun `canHandle returns false for unknown target`() {
        val result = resolver.canHandle("UNKNOWN_TARGET")
        assertFalse(result)
    }

    @Test
    fun `canHandle returns false for empty string`() {
        val result = resolver.canHandle("")
        assertFalse(result)
    }

    @Test
    fun `canHandle handles whitespace in target`() {
        val result = resolver.canHandle("  TRANSACTION_LIST  ")
        assertTrue(result)
    }

    // ========== resolve() for TRANSACTION_LIST ==========

    @Test
    fun `resolve maps TRANSACTION_LIST to ToTransactionList action`() {
        val filter = DomainTransactionFilter(categoryId = 123L)
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
            """{"categoryId":123}"""
        )

        assertTrue(action is NavigationAction.ToTransactionList)
        assertEquals(123L, (action as NavigationAction.ToTransactionList).filter.categoryId)
    }

    @Test
    fun `resolve deserializes filter JSON correctly for TRANSACTION_LIST`() {
        val expectedFilter = DomainTransactionFilter(
            categoryId = 123L,
            merchantName = "Test Merchant",
            transactionType = DomainTransactionType.PURCHASE,
            minAmount = 10.0,
            maxAmount = 100.0
        )
        
        every { filterSerializer.deserialize(any()) } returns expectedFilter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
            """{"categoryId":123,"merchantName":"Test Merchant"}"""
        )

        assertTrue(action is NavigationAction.ToTransactionList)
        val resultFilter = (action as NavigationAction.ToTransactionList).filter
        assertEquals(123L, resultFilter.categoryId)
        assertEquals("Test Merchant", resultFilter.merchantName)
        assertEquals(TransactionType.PURCHASE, resultFilter.transactionType)
    }

    @Test
    fun `resolve handles null filter JSON for TRANSACTION_LIST`() {
        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
            null
        )

        assertTrue(action is NavigationAction.ToTransactionList)
        val filter = (action as NavigationAction.ToTransactionList).filter
        assertNull(filter.categoryId)
        assertNull(filter.merchantName)
    }

    @Test
    fun `resolve handles empty filter JSON for TRANSACTION_LIST`() {
        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
            ""
        )

        assertTrue(action is NavigationAction.ToTransactionList)
        val filter = (action as NavigationAction.ToTransactionList).filter
        assertNull(filter.categoryId)
    }

    @Test
    fun `resolve handles blank filter JSON for TRANSACTION_LIST`() {
        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
            "   "
        )

        assertTrue(action is NavigationAction.ToTransactionList)
    }

    // ========== resolve() for CATEGORY_DETAIL ==========

    @Test
    fun `resolve maps CATEGORY_DETAIL to ToTransactionList action`() {
        val filter = DomainTransactionFilter(categoryId = 456L)
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_CATEGORY_DETAIL,
            """{"categoryId":456}"""
        )

        assertTrue(action is NavigationAction.ToTransactionList)
        assertEquals(456L, (action as NavigationAction.ToTransactionList).filter.categoryId)
    }

    // ========== resolve() for BUDGET_DETAIL ==========

    @Test
    fun `resolve maps BUDGET_DETAIL to ToBudgetDetail action`() {
        val filter = DomainTransactionFilter(categoryId = 789L)
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_BUDGET_DETAIL,
            """{"categoryId":789}"""
        )

        assertTrue(action is NavigationAction.ToBudgetDetail)
        assertEquals("789", (action as NavigationAction.ToBudgetDetail).category)
    }

    @Test
    fun `resolve uses GENERAL category when categoryId is null for BUDGET_DETAIL`() {
        every { filterSerializer.deserialize(any()) } returns DomainTransactionFilter()

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_BUDGET_DETAIL,
            """{}"""
        )

        assertTrue(action is NavigationAction.ToBudgetDetail)
        assertEquals("GENERAL", (action as NavigationAction.ToBudgetDetail).category)
    }

    @Test
    fun `resolve handles zero categoryId for BUDGET_DETAIL`() {
        val filter = DomainTransactionFilter(categoryId = 0L)
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_BUDGET_DETAIL,
            """{"categoryId":0}"""
        )

        assertTrue(action is NavigationAction.ToBudgetDetail)
        assertEquals("0", (action as NavigationAction.ToBudgetDetail).category)
    }

    // ========== resolve() for ANALYTICS ==========

    @Test
    fun `resolve maps ANALYTICS to ToAnalytics action with week period`() {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + (7L * 24 * 60 * 60 * 1000) // 7 days
        val filter = DomainTransactionFilter(dateRange = Pair(startTime, endTime))
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_ANALYTICS,
            """{"dateRangeStart":$startTime,"dateRangeEnd":$endTime}"""
        )

        assertTrue(action is NavigationAction.ToAnalytics)
        assertEquals("week", (action as NavigationAction.ToAnalytics).period)
    }

    @Test
    fun `resolve maps ANALYTICS to ToAnalytics action with month period`() {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + (30L * 24 * 60 * 60 * 1000) // 30 days
        val filter = DomainTransactionFilter(dateRange = Pair(startTime, endTime))
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_ANALYTICS,
            """{}"""
        )

        assertTrue(action is NavigationAction.ToAnalytics)
        assertEquals("month", (action as NavigationAction.ToAnalytics).period)
    }

    @Test
    fun `resolve maps ANALYTICS to ToAnalytics action with custom period`() {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + (90L * 24 * 60 * 60 * 1000) // 90 days
        val filter = DomainTransactionFilter(dateRange = Pair(startTime, endTime))
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_ANALYTICS,
            """{}"""
        )

        assertTrue(action is NavigationAction.ToAnalytics)
        assertEquals("custom", (action as NavigationAction.ToAnalytics).period)
    }

    @Test
    fun `resolve defaults to month period when no dateRange in ANALYTICS`() {
        every { filterSerializer.deserialize(any()) } returns DomainTransactionFilter()

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_ANALYTICS,
            null
        )

        assertTrue(action is NavigationAction.ToAnalytics)
        assertEquals("month", (action as NavigationAction.ToAnalytics).period)
    }

    @Test
    fun `resolve handles 8 day range as month period (boundary)`() {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + (9L * 24 * 60 * 60 * 1000) // 9 days
        val filter = DomainTransactionFilter(dateRange = Pair(startTime, endTime))
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_ANALYTICS,
            """{}"""
        )

        assertTrue(action is NavigationAction.ToAnalytics)
        assertEquals("month", (action as NavigationAction.ToAnalytics).period)
    }

    @Test
    fun `resolve handles 32 day range as custom period (boundary)`() {
        val startTime = System.currentTimeMillis()
        val endTime = startTime + (33L * 24 * 60 * 60 * 1000) // 33 days
        val filter = DomainTransactionFilter(dateRange = Pair(startTime, endTime))
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_ANALYTICS,
            """{}"""
        )

        assertTrue(action is NavigationAction.ToAnalytics)
        assertEquals("custom", (action as NavigationAction.ToAnalytics).period)
    }

    // ========== resolve() for MAP ==========

    @Test
    fun `resolve maps MAP to ToMap action`() {
        val filter = DomainTransactionFilter(merchantName = "Test Location")
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve("MAP", """{"merchantName":"Test Location"}""")

        assertTrue(action is NavigationAction.ToMap)
        assertEquals("Test Location", (action as NavigationAction.ToMap).location)
    }

    @Test
    fun `resolve handles MAP with null location`() {
        every { filterSerializer.deserialize(any()) } returns DomainTransactionFilter()

        val action = resolver.resolve("MAP", null)

        assertTrue(action is NavigationAction.ToMap)
        assertNull((action as NavigationAction.ToMap).location)
    }

    // ========== resolve() Fallback Behavior ==========

    @Test
    fun `resolve falls back to ToTransactionList for unknown target`() {
        val action = resolver.resolve("UNKNOWN_TARGET", null)

        assertTrue(action is NavigationAction.ToTransactionList)
        val filter = (action as NavigationAction.ToTransactionList).filter
        assertNull(filter.categoryId)
    }

    @Test
    fun `resolve falls back gracefully when deserialize returns null`() {
        every { filterSerializer.deserialize(any()) } returns null

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
            """invalid json"""
        )

        assertTrue(action is NavigationAction.ToTransactionList)
        val filter = (action as NavigationAction.ToTransactionList).filter
        assertNull(filter.categoryId)
    }

    @Test
    fun `resolve handles complex filter with multiple fields`() {
        val filter = DomainTransactionFilter(
            categoryId = 123L,
            merchantName = "Test",
            transactionType = DomainTransactionType.PURCHASE,
            dateRange = Pair(1000000L, 2000000L),
            ownership = DomainOwnershipFilter.ALL,
            minAmount = 10.0,
            maxAmount = 100.0
        )
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_TRANSACTION_LIST,
            """{"categoryId":123}"""
        )

        assertTrue(action is NavigationAction.ToTransactionList)
        val resultFilter = (action as NavigationAction.ToTransactionList).filter
        assertEquals(123L, resultFilter.categoryId)
        assertEquals("Test", resultFilter.merchantName)
        assertEquals(10.0, resultFilter.minAmount)
        assertEquals(100.0, resultFilter.maxAmount)
    }

    @Test
    fun `resolve handles negative dateRange span safely`() {
        // Edge case: end before start (should coerce to 0)
        val endTime = System.currentTimeMillis()
        val startTime = endTime + 1000000 // start after end
        val filter = DomainTransactionFilter(dateRange = Pair(startTime, endTime))
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve(
            DashboardFollowThroughEngine.NAV_TARGET_ANALYTICS,
            """{}"""
        )

        assertTrue(action is NavigationAction.ToAnalytics)
        // Should default to "week" since span is coerced to 0 (which is <= 8)
        assertEquals("week", (action as NavigationAction.ToAnalytics).period)
    }

    @Test
    fun `resolve is case insensitive for target matching`() {
        val filter = DomainTransactionFilter(categoryId = 999L)
        every { filterSerializer.deserialize(any()) } returns filter

        val action = resolver.resolve("transaction_list", """{"categoryId":999}""")

        assertTrue(action is NavigationAction.ToTransactionList)
        assertEquals(999L, (action as NavigationAction.ToTransactionList).filter.categoryId)
    }
}
