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
        val receiptId = NotificationIdGenerator.forReceipt(100L)
        val billId = NotificationIdGenerator.forBill(100L)
        
        // All should be different
        assertThat(warrantyId).isNotEqualTo(receiptId)
        assertThat(warrantyId).isNotEqualTo(billId)
        assertThat(receiptId).isNotEqualTo(billId)
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
    fun `negative long ID is handled correctly`() {
        // In Kotlin, -1L % 9999 is negative, but we convert to Int which handles it
        val id = NotificationIdGenerator.forReceipt(-1L)
        
        // Should still be in valid range (modulo of negative is implementation dependent)
        assertThat(id).isAtLeast(20000)
        assertThat(id).isAtMost(29999)
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
        
        assertThat(id1).isAtMost(14999)
        assertThat(id7).isAtMost(14999)
    }

    @Test
    fun `warranty with days above 7 uses 30-day range`() {
        // Days 8+ should use the 30-day range (15000-19999)
        val id8 = NotificationIdGenerator.forWarranty(100L, 8)
        val id30 = NotificationIdGenerator.forWarranty(100L, 30)
        val id365 = NotificationIdGenerator.forWarranty(100L, 365)
        
        assertThat(id8).isAtLeast(15000)
        assertThat(id30).isAtLeast(15000)
        assertThat(id365).isAtLeast(15000)
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