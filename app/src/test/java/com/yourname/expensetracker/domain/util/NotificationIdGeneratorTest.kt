package com.yourname.expensetracker.domain.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * CRITICAL TEST (HIGH-4): Notification ID Generator
 * 
 * Tests safe mapping of Long database IDs to Int notification IDs
 * to prevent integer overflow and notification collisions.
 */
class NotificationIdGeneratorTest {

    // ==================== WARRANTY NOTIFICATION TESTS ====================

    @Test
    fun `warranty 7-day notification is in correct range`() {
        val id = NotificationIdGenerator.forWarranty(12345L, 7)
        
        assertThat(id).isAtLeast(10000)
        assertThat(id).isAtMost(14999)
    }

    @Test
    fun `warranty 30-day notification is in correct range`() {
        val id = NotificationIdGenerator.forWarranty(12345L, 30)
        
        assertThat(id).isAtLeast(15000)
        assertThat(id).isAtMost(19999)
    }

    @Test
    fun `warranty notifications for same ID have different ranges by days`() {
        val id7Days = NotificationIdGenerator.forWarranty(100L, 7)
        val id30Days = NotificationIdGenerator.forWarranty(100L, 30)
        
        assertThat(id7Days).isNotEqualTo(id30Days)
        assertThat(id7Days).isAtMost(14999)
        assertThat(id30Days).isAtLeast(15000)
        assertThat(id30Days).isAtMost(19999)
    }

    @Test
    fun `warranty notification handles very large ID`() {
        val hugeId = Long.MAX_VALUE // 9,223,372,036,854,775,807
        
        val result = NotificationIdGenerator.forWarranty(hugeId, 7)
        
        // Should still be within warranty range, no overflow
        assertThat(result).isAtLeast(10000)
        assertThat(result).isAtMost(14999)
    }

    @Test
    fun `warranty 30-day notification handles very large ID`() {
        val hugeId = Long.MAX_VALUE

        val result = NotificationIdGenerator.forWarranty(hugeId, 30)

        assertThat(result).isAtLeast(15000)
        assertThat(result).isAtMost(19999)
    }

    @Test
    fun `warranty notification handles zero ID`() {
        val id = NotificationIdGenerator.forWarranty(0L, 7)
        
        assertThat(id).isEqualTo(10000)
    }

    // ==================== RECEIPT NOTIFICATION TESTS ====================

    @Test
    fun `receipt notification is in correct range`() {
        val id = NotificationIdGenerator.forReceipt(999L)
        
        assertThat(id).isAtLeast(20000)
        assertThat(id).isAtMost(29999)
    }

    @Test
    fun `receipt notification for large ID stays in range`() {
        val id = NotificationIdGenerator.forReceipt(999_999_999L)
        
        assertThat(id).isAtLeast(20000)
        assertThat(id).isAtMost(29999)
    }

    @Test
    fun `receipt notification produces consistent results`() {
        val id1 = NotificationIdGenerator.forReceipt(12345L)
        val id2 = NotificationIdGenerator.forReceipt(12345L)
        
        assertThat(id1).isEqualTo(id2)
    }

    // ==================== BILL NOTIFICATION TESTS ====================

    @Test
    fun `bill notification is in correct range`() {
        val id = NotificationIdGenerator.forBill(500L)
        
        assertThat(id).isAtLeast(30000)
        assertThat(id).isAtMost(39999)
    }

    @Test
    fun `bill notification handles max Long value`() {
        val id = NotificationIdGenerator.forBill(Long.MAX_VALUE)
        
        assertThat(id).isAtLeast(30000)
        assertThat(id).isAtMost(39999)
    }

    // ==================== BUDGET NOTIFICATION TESTS ====================

    @Test
    fun `budget notification is in correct range`() {
        val id = NotificationIdGenerator.forBudget(100L)
        
        assertThat(id).isAtLeast(1)
        assertThat(id).isAtMost(9999)
    }

    @Test
    fun `budget notification for ID 1 gives low number`() {
        val id = NotificationIdGenerator.forBudget(1L)
        
        assertThat(id).isEqualTo(2) // 1 % 9999 = 1, + 1 = 2
    }

    @Test
    fun `budget notification for large ID wraps correctly`() {
        val id = NotificationIdGenerator.forBudget(10_000L)
        
        // 10000 % 9999 = 1, so should be same as budget 1
        assertThat(id).isEqualTo(2)
    }

    // ==================== GENERAL NOTIFICATION TESTS ====================

    @Test
    fun `general notification is in correct range`() {
        val id = NotificationIdGenerator.forGeneral(250L)
        
        assertThat(id).isAtLeast(40000)
        assertThat(id).isAtMost(49999)
    }

    @Test
    fun `fromLong with custom range`() {
        val id = NotificationIdGenerator.fromLong(12345L, 50000)
        
        assertThat(id).isAtLeast(50000)
        assertThat(id).isAtMost(59999)
    }

    @Test
    fun `fromLong with very large value uses hash mixing`() {
        val hugeValue = Long.MAX_VALUE
        
        val id = NotificationIdGenerator.fromLong(hugeValue)
        
        // Should be in default range
        assertThat(id).isAtLeast(40000)
        assertThat(id).isAtMost(49999)
    }

    @Test
    fun `fromLong produces different IDs for different inputs`() {
        val id1 = NotificationIdGenerator.fromLong(1L)
        val id2 = NotificationIdGenerator.fromLong(2L)
        
        assertThat(id1).isNotEqualTo(id2)
    }

    // ==================== COLLISION PREVENTION TESTS ====================

    @Test
    fun `different ranges prevent collision between types`() {
        val receiptId = NotificationIdGenerator.forReceipt(12345L)
        val billId = NotificationIdGenerator.forBill(12345L)
        val budgetId = NotificationIdGenerator.forBudget(12345L)
        
        assertThat(receiptId).isNotEqualTo(billId)
        assertThat(billId).isNotEqualTo(budgetId)
        assertThat(receiptId).isNotEqualTo(budgetId)
    }

    @Test
    fun `same database ID in different ranges produces different notification IDs`() {
        val warrantyId = NotificationIdGenerator.forWarranty(100L, 7)
        val warranty30DayId = NotificationIdGenerator.forWarranty(100L, 30)
        val receiptId = NotificationIdGenerator.forReceipt(100L)
        val billId = NotificationIdGenerator.forBill(100L)
        
        // All should be different
        assertThat(warrantyId).isNotEqualTo(receiptId)
        assertThat(warranty30DayId).isNotEqualTo(receiptId)
        assertThat(warrantyId).isNotEqualTo(billId)
        assertThat(warranty30DayId).isNotEqualTo(billId)
        assertThat(receiptId).isNotEqualTo(billId)
    }

    @Test
    fun `warranty ranges stay fully below receipt range`() {
        val samples = listOf(0L, 1L, 4_999L, 5_000L, 9_999L, Long.MAX_VALUE)

        samples.forEach { warrantyId ->
            val warranty7DayId = NotificationIdGenerator.forWarranty(warrantyId, 7)
            val warranty30DayId = NotificationIdGenerator.forWarranty(warrantyId, 30)

            assertThat(warranty7DayId).isLessThan(20000)
            assertThat(warranty30DayId).isLessThan(20000)
            assertThat(warranty7DayId).isNotEqualTo(NotificationIdGenerator.forReceipt(warrantyId))
            assertThat(warranty30DayId).isNotEqualTo(NotificationIdGenerator.forReceipt(warrantyId))
        }
    }

    @Test
    fun `wrapping prevents ID overflow`() {
        // IDs that differ by exactly RANGE_SIZE (9999) should wrap to same value
        val id1 = NotificationIdGenerator.forReceipt(100L)
        val id2 = NotificationIdGenerator.forReceipt(100L + 9999L)
        
        // Both should map to same notification ID (wraps around)
        assertThat(id1).isEqualTo(id2)
    }

    // ==================== EXTENSION FUNCTION TESTS ====================

    @Test
    fun `toNotificationId extension for warranty 7 days`() {
        val id = 100L.toNotificationId(NotificationType.WARRANTY_7DAYS)
        
        assertThat(id).isEqualTo(NotificationIdGenerator.forWarranty(100L, 7))
    }

    @Test
    fun `toNotificationId extension for warranty 30 days`() {
        val id = 100L.toNotificationId(NotificationType.WARRANTY_30DAYS)
        
        assertThat(id).isEqualTo(NotificationIdGenerator.forWarranty(100L, 30))
    }

    @Test
    fun `toNotificationId extension for receipt`() {
        val id = 200L.toNotificationId(NotificationType.RECEIPT)
        
        assertThat(id).isEqualTo(NotificationIdGenerator.forReceipt(200L))
    }

    @Test
    fun `toNotificationId extension for bill`() {
        val id = 300L.toNotificationId(NotificationType.BILL)
        
        assertThat(id).isEqualTo(NotificationIdGenerator.forBill(300L))
    }

    @Test
    fun `toNotificationId extension for budget`() {
        val id = 400L.toNotificationId(NotificationType.BUDGET)
        
        assertThat(id).isEqualTo(NotificationIdGenerator.forBudget(400L))
    }

    @Test
    fun `toNotificationId extension for general`() {
        val id = 500L.toNotificationId(NotificationType.GENERAL)
        
        assertThat(id).isEqualTo(NotificationIdGenerator.forGeneral(500L))
    }

    // ==================== EDGE CASE TESTS ====================

    @Test
    fun `negative long ID is mapped by floor-mod into range`() {
        // RP-16 16-A: positiveRangeOffset uses Math.floorMod, which maps negative
        // inputs into the non-negative domain: floorMod(-1, 9999) == 9998.
        val id = NotificationIdGenerator.forReceipt(-1L)

        assertThat(id).isEqualTo(20000 + 9998)
        assertThat(id).isAtLeast(20000)
        assertThat(id).isAtMost(29999)
    }

    @Test
    fun `negative long ID extreme values stay in range`() {
        val idMin = NotificationIdGenerator.forBill(Long.MIN_VALUE)
        val idNeg = NotificationIdGenerator.forBudget(-12345L)

        assertThat(idMin).isAtLeast(30000)
        assertThat(idMin).isAtMost(39999)
        assertThat(idNeg).isAtLeast(1)
        assertThat(idNeg).isAtMost(9999)
    }

    // ==================== RP-16 16-A: TYPED BOUNDARY TESTS ====================

    @Test
    fun `NotificationId factories delegate to generator for all kinds`() {
        assertThat(NotificationId.forBill(500L).value).isEqualTo(NotificationIdGenerator.forBill(500L))
        assertThat(NotificationId.forWarranty(500L, 7).value).isEqualTo(NotificationIdGenerator.forWarranty(500L, 7))
        assertThat(NotificationId.forWarranty(500L, 30).value).isEqualTo(NotificationIdGenerator.forWarranty(500L, 30))
        assertThat(NotificationId.forReceipt(500L).value).isEqualTo(NotificationIdGenerator.forReceipt(500L))
        assertThat(NotificationId.forBudget(500L).value).isEqualTo(NotificationIdGenerator.forBudget(500L))
        assertThat(NotificationId.forGeneral(500L).value).isEqualTo(NotificationIdGenerator.forGeneral(500L))
    }

    @Test
    fun `NotificationId stays in reserved range for large and negative source IDs`() {
        val bill = NotificationId.forBill(Long.MAX_VALUE)
        val billNeg = NotificationId.forBill(Long.MIN_VALUE)

        assertThat(bill.value).isAtLeast(30000)
        assertThat(bill.value).isAtMost(39999)
        assertThat(billNeg.value).isAtLeast(30000)
        assertThat(billNeg.value).isAtMost(39999)
    }

    @Test
    fun `NotificationKey derives generator-only IDs per kind`() {
        assertThat(NotificationKey.Bill(123L).notificationId.value)
            .isEqualTo(NotificationIdGenerator.forBill(123L))
        assertThat(NotificationKey.Warranty(123L, 7).notificationId.value)
            .isEqualTo(NotificationIdGenerator.forWarranty(123L, 7))
        assertThat(NotificationKey.Receipt(123L).notificationId.value)
            .isEqualTo(NotificationIdGenerator.forReceipt(123L))
        assertThat(NotificationKey.Budget(123L).notificationId.value)
            .isEqualTo(NotificationIdGenerator.forBudget(123L))
        assertThat(NotificationKey.General(123L).notificationId.value)
            .isEqualTo(NotificationIdGenerator.forGeneral(123L))
    }

    @Test
    fun `cross-kind collision separation via typed keys`() {
        val sameSource = 987654321L
        val ids = setOf(
            NotificationKey.Bill(sameSource).notificationId.value,
            NotificationKey.Warranty(sameSource, 7).notificationId.value,
            NotificationKey.Warranty(sameSource, 30).notificationId.value,
            NotificationKey.Receipt(sameSource).notificationId.value,
            NotificationKey.Budget(sameSource).notificationId.value,
            NotificationKey.General(sameSource).notificationId.value
        )
        // Bill/Warranty7/Warranty30/Receipt/Budget/General live in disjoint ranges.
        assertThat(ids).hasSize(6)
    }

    @Test
    fun `helper-mediated posting cannot pass raw IDs — value comes from generator`() {
        val fake = CapturingNotificationService()

        val sourceId = 4_500_000_000L // > Int.MAX_VALUE — legacy (id % Int.MAX_VALUE) would overflow/collide
        fake.postBudgetAlert(NotificationId.forBill(sourceId), "title", "msg")

        assertThat(fake.lastPostedId).isEqualTo(NotificationIdGenerator.forBill(sourceId))
        assertThat(fake.lastPostedId).isAtLeast(30000)
        assertThat(fake.lastPostedId).isAtMost(39999)
    }

    @Test
    fun `posting helper for anomaly uses generator general range`() {
        val fake = CapturingNotificationService()

        fake.postAnomalyAlert(NotificationId.forGeneral(77L), "t", "m", 77L)

        assertThat(fake.lastPostedId).isEqualTo(NotificationIdGenerator.forGeneral(77L))
        assertThat(fake.lastPostedId).isAtLeast(40000)
        assertThat(fake.lastPostedId).isAtMost(49999)
    }

    /** Minimal fake that captures the raw Int the mediated helper delegates to. */
    private class CapturingNotificationService : com.yourname.expensetracker.domain.service.NotificationService {
        var lastPostedId: Int = -1

        override fun sendBudgetAlert(notificationId: Int, title: String, message: String): com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult {
            lastPostedId = notificationId
            return com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult.DELIVERED
        }

        override fun sendAiBriefingReady(notificationId: Int, title: String, message: String, targetKey: String) {
            lastPostedId = notificationId
        }

        override fun sendAnomalyAlert(notificationId: Int, title: String, message: String, expenseId: Long) {
            lastPostedId = notificationId
        }
    }

    @Test
    fun `ID at range boundary maps correctly`() {
        // 9999 is at the boundary
        val id = NotificationIdGenerator.forBudget(9999L)
        
        assertThat(id).isAtLeast(1)
        assertThat(id).isAtMost(9999)
    }

    @Test
    fun `ID one past range boundary wraps`() {
        // 10000 should wrap to same as 1 (10000 % 9999 = 1)
        val id1 = NotificationIdGenerator.forBudget(1L)
        val id10000 = NotificationIdGenerator.forBudget(10000L)
        
        assertThat(id1).isEqualTo(id10000)
    }

    @Test
    fun `fromLong produces stable results for same input`() {
        val id1 = NotificationIdGenerator.fromLong(987654321L)
        val id2 = NotificationIdGenerator.fromLong(987654321L)
        
        assertThat(id1).isEqualTo(id2)
    }

    @Test
    fun `multiple different IDs are distributed across range`() {
        val ids = (1L..100L).map { NotificationIdGenerator.forReceipt(it) }
        
        // All should be in valid range
        ids.forEach { id ->
            assertThat(id).isAtLeast(20000)
            assertThat(id).isAtMost(29999)
        }
        
        // Should have variety (not all same)
        val uniqueIds = ids.toSet()
        assertThat(uniqueIds.size).isGreaterThan(50) // Most should be unique
    }

    @Test
    fun `warranty with days below 7 uses 7-day range`() {
        // Days 1-7 should all use the 7-day range (10000-14999)
        val id1 = NotificationIdGenerator.forWarranty(100L, 1)
        val id7 = NotificationIdGenerator.forWarranty(100L, 7)
        
        assertThat(id1).isAtLeast(10000)
        assertThat(id1).isAtMost(14999)
        assertThat(id7).isAtLeast(10000)
        assertThat(id7).isAtMost(14999)
    }

    @Test
    fun `warranty with days above 7 uses 30-day range`() {
        // Days 8+ should use the 30-day range (15000-19999)
        val id8 = NotificationIdGenerator.forWarranty(100L, 8)
        val id30 = NotificationIdGenerator.forWarranty(100L, 30)
        val id365 = NotificationIdGenerator.forWarranty(100L, 365)
        
        assertThat(id8).isAtLeast(15000)
        assertThat(id8).isAtMost(19999)
        assertThat(id30).isAtLeast(15000)
        assertThat(id30).isAtMost(19999)
        assertThat(id365).isAtLeast(15000)
        assertThat(id365).isAtMost(19999)
    }

    @Test
    fun `all notification types have non-overlapping ranges`() {
        // Generate IDs from each type
        val warranty7 = NotificationIdGenerator.forWarranty(1L, 7)
        val warranty30 = NotificationIdGenerator.forWarranty(1L, 30)
        val receipt = NotificationIdGenerator.forReceipt(1L)
        val bill = NotificationIdGenerator.forBill(1L)
        val budget = NotificationIdGenerator.forBudget(1L)
        val general = NotificationIdGenerator.forGeneral(1L)
        
        // Verify ranges don't overlap
        // Budget: 1-9999
        assertThat(budget).isAtMost(9999)
        
        // Warranty 7-day: 10000-14999
        assertThat(warranty7).isAtLeast(10000)
        assertThat(warranty7).isAtMost(14999)
        
        // Warranty 30-day: 15000-19999
        assertThat(warranty30).isAtLeast(15000)
        assertThat(warranty30).isAtMost(19999)
        
        // Receipt: 20000-29999
        assertThat(receipt).isAtLeast(20000)
        assertThat(receipt).isAtMost(29999)
        
        // Bill: 30000-39999
        assertThat(bill).isAtLeast(30000)
        assertThat(bill).isAtMost(39999)
        
        // General: 40000-49999
        assertThat(general).isAtLeast(40000)
        assertThat(general).isAtMost(49999)
    }
}
