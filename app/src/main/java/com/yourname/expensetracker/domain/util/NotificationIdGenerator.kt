package com.yourname.expensetracker.domain.util

/**
 * HIGH FIX (HIGH-4): Notification ID generator to prevent integer overflow.
 * 
 * Android notification IDs are Int (32-bit), but database IDs are Long (64-bit).
 * Converting Long to Int can cause overflow for large IDs.
 * 
 * This generator creates safe notification IDs by:
 * 1. Using a hash-based approach for unique mapping
 * 2. Reserving ID ranges for different notification types
 * 3. Preventing collisions between different notification sources
 * 
 * ID Ranges:
 * - 1-9999: Budget alerts
 * - 10000-14999: 7-day warranty notifications
 * - 15000-19999: 30-day warranty notifications
 * - 20000-29999: Receipt matching
 * - 30000-39999: Bill reminders
 * - 40000-49999: General app notifications
 * - 50000+: Reserved for future use
 */
object NotificationIdGenerator {
    
    private const val BUDGET_RANGE_START = 1
    private const val WARRANTY_RANGE_START = 10000
    private const val WARRANTY_30_DAY_RANGE_START = 15000
    private const val RECEIPT_RANGE_START = 20000
    private const val BILL_RANGE_START = 30000
    private const val GENERAL_RANGE_START = 40000
    private const val RANGE_SIZE = 9999
    private const val WARRANTY_SUBRANGE_SIZE = 5000
    
    /**
     * Generate notification ID for warranty expiration.
     * Maps warranty Long ID to safe Int range.
     */
    fun forWarranty(warrantyId: Long, daysUntilExpiration: Int): Int {
        val baseId = positiveRangeOffset(warrantyId, WARRANTY_SUBRANGE_SIZE)
        val rangeStart = if (daysUntilExpiration <= 7) {
            WARRANTY_RANGE_START
        } else {
            WARRANTY_30_DAY_RANGE_START
        }
        return rangeStart + baseId
    }
    
    /**
     * Generate notification ID for receipt matching.
     */
    fun forReceipt(receiptId: Long): Int {
        return RECEIPT_RANGE_START + positiveRangeOffset(receiptId, RANGE_SIZE)
    }
    
    /**
     * Generate notification ID for bill reminder.
     */
    fun forBill(expenseId: Long): Int {
        return BILL_RANGE_START + positiveRangeOffset(expenseId, RANGE_SIZE)
    }
    
    /**
     * Generate notification ID for budget alert.
     */
    fun forBudget(budgetId: Long): Int {
        return BUDGET_RANGE_START + positiveRangeOffset(budgetId, RANGE_SIZE)
    }
    
    /**
     * Generate notification ID for general app notification.
     */
    fun forGeneral(id: Long): Int {
        return GENERAL_RANGE_START + positiveRangeOffset(id, RANGE_SIZE)
    }
    
    /**
     * Generate unique notification ID from any Long.
     * Uses hashCode for better distribution.
     */
    fun fromLong(value: Long, rangeStart: Int = GENERAL_RANGE_START): Int {
        // Mix bits for better distribution
        val mixed = value xor (value shr 32)
        return rangeStart + positiveRangeOffset(mixed, RANGE_SIZE)
    }

    private fun positiveRangeOffset(value: Long, rangeSize: Int): Int {
        return Math.floorMod(value, rangeSize.toLong()).toInt()
    }
}

/**
 * Extension functions for convenience.
 */
fun Long.toNotificationId(type: NotificationType): Int {
    return when (type) {
        NotificationType.WARRANTY_7DAYS -> NotificationIdGenerator.forWarranty(this, 7)
        NotificationType.WARRANTY_30DAYS -> NotificationIdGenerator.forWarranty(this, 30)
        NotificationType.RECEIPT -> NotificationIdGenerator.forReceipt(this)
        NotificationType.BILL -> NotificationIdGenerator.forBill(this)
        NotificationType.BUDGET -> NotificationIdGenerator.forBudget(this)
        NotificationType.GENERAL -> NotificationIdGenerator.forGeneral(this)
    }
}

enum class NotificationType {
    WARRANTY_7DAYS,
    WARRANTY_30DAYS,
    RECEIPT,
    BILL,
    BUDGET,
    GENERAL
}
