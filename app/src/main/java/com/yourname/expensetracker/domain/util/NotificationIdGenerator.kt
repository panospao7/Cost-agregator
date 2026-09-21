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
 * RP-16 16-A: Typed notification identity — the allocation boundary for
 * Android notification IDs.
 *
 * A [NotificationId] can ONLY be created through the companion factories,
 * which delegate to [NotificationIdGenerator]. Posting helpers
 * ([com.yourname.expensetracker.domain.service.NotificationService]) accept
 * this type, so raw `Int` IDs (e.g. `(dbId % Int.MAX_VALUE)`) cannot reach
 * `notify()` through the mediated path.
 */
class NotificationId private constructor(val value: Int) {
    companion object {
        fun forBill(expenseId: Long): NotificationId =
            NotificationId(NotificationIdGenerator.forBill(expenseId))

        fun forWarranty(warrantyId: Long, daysUntilExpiration: Int): NotificationId =
            NotificationId(NotificationIdGenerator.forWarranty(warrantyId, daysUntilExpiration))

        fun forReceipt(receiptId: Long): NotificationId =
            NotificationId(NotificationIdGenerator.forReceipt(receiptId))

        fun forBudget(budgetId: Long): NotificationId =
            NotificationId(NotificationIdGenerator.forBudget(budgetId))

        fun forGeneral(id: Long): NotificationId =
            NotificationId(NotificationIdGenerator.forGeneral(id))
    }

    override fun equals(other: Any?): Boolean = other is NotificationId && other.value == value
    override fun hashCode(): Int = value
    override fun toString(): String = "NotificationId($value)"
}

/**
 * RP-16 16-A: Typed source key for a notification. Each kind derives its
 * [notificationId] exclusively from [NotificationIdGenerator], guaranteeing
 * cross-kind collision separation (disjoint reserved ranges).
 */
sealed interface NotificationKey {
    val notificationId: NotificationId

    data class Bill(val expenseId: Long) : NotificationKey {
        override val notificationId: NotificationId get() = NotificationId.forBill(expenseId)
    }

    data class Warranty(val warrantyId: Long, val daysUntilExpiration: Int) : NotificationKey {
        override val notificationId: NotificationId
            get() = NotificationId.forWarranty(warrantyId, daysUntilExpiration)
    }

    data class Receipt(val receiptId: Long) : NotificationKey {
        override val notificationId: NotificationId get() = NotificationId.forReceipt(receiptId)
    }

    data class Budget(val budgetId: Long) : NotificationKey {
        override val notificationId: NotificationId get() = NotificationId.forBudget(budgetId)
    }

    data class General(val id: Long) : NotificationKey {
        override val notificationId: NotificationId get() = NotificationId.forGeneral(id)
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
