package com.yourname.expensetracker.domain.util

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * CRITICAL TEST (HIGH-4): Notification ID Generator
 *
 * Tests safe mapping of Long database IDs to Int notification IDs
 * to prevent integer overflow and notification collisions.
 *
 * Repetitive range / equality / inequality cases are table-driven; the failure
 * message names the row.
 */
class NotificationIdGeneratorTest {

    // ==================== RANGE TABLE ====================

    private data class RangeRow(val label: String, val id: Int, val min: Int, val max: Int)

    @Test
    fun `generated notification ids stay inside the reserved range of their kind`() {
        val rows = listOf(
            // warranty
            RangeRow("warranty 7-day id 12345", NotificationIdGenerator.forWarranty(12345L, 7), 10000, 14999),
            RangeRow("warranty 30-day id 12345", NotificationIdGenerator.forWarranty(12345L, 30), 15000, 19999),
            RangeRow("warranty 7-day id 100", NotificationIdGenerator.forWarranty(100L, 7), 10000, 14999),
            RangeRow("warranty 30-day id 100", NotificationIdGenerator.forWarranty(100L, 30), 15000, 19999),
            RangeRow("warranty 7-day Long.MAX_VALUE no overflow",
                NotificationIdGenerator.forWarranty(Long.MAX_VALUE, 7), 10000, 14999),
            RangeRow("warranty 30-day Long.MAX_VALUE no overflow",
                NotificationIdGenerator.forWarranty(Long.MAX_VALUE, 30), 15000, 19999),
            RangeRow("warranty days 1 uses 7-day range", NotificationIdGenerator.forWarranty(100L, 1), 10000, 14999),
            RangeRow("warranty days 8 uses 30-day range", NotificationIdGenerator.forWarranty(100L, 8), 15000, 19999),
            RangeRow("warranty days 365 uses 30-day range", NotificationIdGenerator.forWarranty(100L, 365), 15000, 19999),
            // receipt
            RangeRow("receipt id 999", NotificationIdGenerator.forReceipt(999L), 20000, 29999),
            RangeRow("receipt id 999_999_999", NotificationIdGenerator.forReceipt(999_999_999L), 20000, 29999),
            RangeRow("receipt id -1 (floor-mod)", NotificationIdGenerator.forReceipt(-1L), 20000, 29999),
            // bill
            RangeRow("bill id 500", NotificationIdGenerator.forBill(500L), 30000, 39999),
            RangeRow("bill Long.MAX_VALUE", NotificationIdGenerator.forBill(Long.MAX_VALUE), 30000, 39999),
            RangeRow("bill Long.MIN_VALUE", NotificationIdGenerator.forBill(Long.MIN_VALUE), 30000, 39999),
            RangeRow("typed NotificationId.forBill Long.MAX_VALUE",
                NotificationId.forBill(Long.MAX_VALUE).value, 30000, 39999),
            RangeRow("typed NotificationId.forBill Long.MIN_VALUE",
                NotificationId.forBill(Long.MIN_VALUE).value, 30000, 39999),
            // budget
            RangeRow("budget id 100", NotificationIdGenerator.forBudget(100L), 1, 9999),
            RangeRow("budget id -12345", NotificationIdGenerator.forBudget(-12345L), 1, 9999),
            RangeRow("budget id 9999 (range boundary)", NotificationIdGenerator.forBudget(9999L), 1, 9999),
            // general / fromLong
            RangeRow("general id 250", NotificationIdGenerator.forGeneral(250L), 40000, 49999),
            RangeRow("fromLong custom range base 50000", NotificationIdGenerator.fromLong(12345L, 50000), 50000, 59999),
            RangeRow("fromLong Long.MAX_VALUE default range", NotificationIdGenerator.fromLong(Long.MAX_VALUE), 40000, 49999),
            // non-overlapping ranges, one id per kind (source id 1)
            RangeRow("kind ranges: warranty 7-day id 1", NotificationIdGenerator.forWarranty(1L, 7), 10000, 14999),
            RangeRow("kind ranges: warranty 30-day id 1", NotificationIdGenerator.forWarranty(1L, 30), 15000, 19999),
            RangeRow("kind ranges: receipt id 1", NotificationIdGenerator.forReceipt(1L), 20000, 29999),
            RangeRow("kind ranges: bill id 1", NotificationIdGenerator.forBill(1L), 30000, 39999),
            RangeRow("kind ranges: budget id 1", NotificationIdGenerator.forBudget(1L), 1, 9999),
            RangeRow("kind ranges: general id 1", NotificationIdGenerator.forGeneral(1L), 40000, 49999)
        )
        // 9 warranty + 3 receipt + 5 bill (3 + 2 typed) + 3 budget + 3 general/fromLong + 6 kind-range rows
        assertThat(rows).hasSize(29)
        rows.forEach { row ->
            assertWithMessage("row '${row.label}' id=${row.id} lower bound ${row.min}")
                .that(row.id).isAtLeast(row.min)
            assertWithMessage("row '${row.label}' id=${row.id} upper bound ${row.max}")
                .that(row.id).isAtMost(row.max)
        }
    }

    // ==================== EXACT VALUE / EQUALITY / INEQUALITY TABLES ====================

    private data class ValueRow(val label: String, val actual: Int, val expected: Int)

    @Test
    fun `documented exact notification id values`() {
        val rows = listOf(
            ValueRow("warranty zero id maps to start of 7-day range", NotificationIdGenerator.forWarranty(0L, 7), 10000),
            ValueRow("budget id 1 gives 2 (1 % 9999 = 1, + 1)", NotificationIdGenerator.forBudget(1L), 2),
            ValueRow("budget id 10000 wraps to 2 (10000 % 9999 = 1, + 1)", NotificationIdGenerator.forBudget(10_000L), 2),
            ValueRow("receipt id -1 maps via floor-mod to 20000 + 9998", NotificationIdGenerator.forReceipt(-1L), 20000 + 9998)
        )
        assertThat(rows).hasSize(4)
        rows.forEach { row ->
            assertWithMessage("row '${row.label}'").that(row.actual).isEqualTo(row.expected)
        }
    }

    @Test
    fun `typed and extension entry points delegate to the generator`() {
        val rows = listOf(
            // toNotificationId extension
            ValueRow("toNotificationId warranty 7 days",
                100L.toNotificationId(NotificationType.WARRANTY_7DAYS), NotificationIdGenerator.forWarranty(100L, 7)),
            ValueRow("toNotificationId warranty 30 days",
                100L.toNotificationId(NotificationType.WARRANTY_30DAYS), NotificationIdGenerator.forWarranty(100L, 30)),
            ValueRow("toNotificationId receipt",
                200L.toNotificationId(NotificationType.RECEIPT), NotificationIdGenerator.forReceipt(200L)),
            ValueRow("toNotificationId bill",
                300L.toNotificationId(NotificationType.BILL), NotificationIdGenerator.forBill(300L)),
            ValueRow("toNotificationId budget",
                400L.toNotificationId(NotificationType.BUDGET), NotificationIdGenerator.forBudget(400L)),
            ValueRow("toNotificationId general",
                500L.toNotificationId(NotificationType.GENERAL), NotificationIdGenerator.forGeneral(500L)),
            // NotificationId factories
            ValueRow("NotificationId.forBill", NotificationId.forBill(500L).value, NotificationIdGenerator.forBill(500L)),
            ValueRow("NotificationId.forWarranty 7",
                NotificationId.forWarranty(500L, 7).value, NotificationIdGenerator.forWarranty(500L, 7)),
            ValueRow("NotificationId.forWarranty 30",
                NotificationId.forWarranty(500L, 30).value, NotificationIdGenerator.forWarranty(500L, 30)),
            ValueRow("NotificationId.forReceipt",
                NotificationId.forReceipt(500L).value, NotificationIdGenerator.forReceipt(500L)),
            ValueRow("NotificationId.forBudget",
                NotificationId.forBudget(500L).value, NotificationIdGenerator.forBudget(500L)),
            ValueRow("NotificationId.forGeneral",
                NotificationId.forGeneral(500L).value, NotificationIdGenerator.forGeneral(500L)),
            // NotificationKey
            ValueRow("NotificationKey.Bill",
                NotificationKey.Bill(123L).notificationId.value, NotificationIdGenerator.forBill(123L)),
            ValueRow("NotificationKey.Warranty 7",
                NotificationKey.Warranty(123L, 7).notificationId.value, NotificationIdGenerator.forWarranty(123L, 7)),
            ValueRow("NotificationKey.Receipt",
                NotificationKey.Receipt(123L).notificationId.value, NotificationIdGenerator.forReceipt(123L)),
            ValueRow("NotificationKey.Budget",
                NotificationKey.Budget(123L).notificationId.value, NotificationIdGenerator.forBudget(123L)),
            ValueRow("NotificationKey.General",
                NotificationKey.General(123L).notificationId.value, NotificationIdGenerator.forGeneral(123L))
        )
        assertThat(rows).hasSize(17)
        rows.forEach { row ->
            assertWithMessage("row '${row.label}'").that(row.actual).isEqualTo(row.expected)
        }
    }

    @Test
    fun `equal inputs and range-size wrapping produce equal ids`() {
        val rows = listOf(
            ValueRow("receipt is consistent for the same id",
                NotificationIdGenerator.forReceipt(12345L), NotificationIdGenerator.forReceipt(12345L)),
            ValueRow("receipt ids differing by RANGE_SIZE 9999 wrap to the same value",
                NotificationIdGenerator.forReceipt(100L), NotificationIdGenerator.forReceipt(100L + 9999L)),
            ValueRow("budget id 1 equals budget id 10000",
                NotificationIdGenerator.forBudget(1L), NotificationIdGenerator.forBudget(10000L)),
            ValueRow("fromLong is stable for the same input",
                NotificationIdGenerator.fromLong(987654321L), NotificationIdGenerator.fromLong(987654321L))
        )
        assertThat(rows).hasSize(4)
        rows.forEach { row ->
            assertWithMessage("row '${row.label}'").that(row.actual).isEqualTo(row.expected)
        }
    }

    @Test
    fun `distinct kinds and inputs produce different ids`() {
        val rows = listOf(
            ValueRow("warranty 7-day vs 30-day for id 100",
                NotificationIdGenerator.forWarranty(100L, 7), NotificationIdGenerator.forWarranty(100L, 30)),
            ValueRow("fromLong 1 vs 2", NotificationIdGenerator.fromLong(1L), NotificationIdGenerator.fromLong(2L)),
            ValueRow("receipt vs bill for id 12345",
                NotificationIdGenerator.forReceipt(12345L), NotificationIdGenerator.forBill(12345L)),
            ValueRow("bill vs budget for id 12345",
                NotificationIdGenerator.forBill(12345L), NotificationIdGenerator.forBudget(12345L)),
            ValueRow("receipt vs budget for id 12345",
                NotificationIdGenerator.forReceipt(12345L), NotificationIdGenerator.forBudget(12345L)),
            ValueRow("warranty 7-day vs receipt for id 100",
                NotificationIdGenerator.forWarranty(100L, 7), NotificationIdGenerator.forReceipt(100L)),
            ValueRow("warranty 30-day vs receipt for id 100",
                NotificationIdGenerator.forWarranty(100L, 30), NotificationIdGenerator.forReceipt(100L)),
            ValueRow("warranty 7-day vs bill for id 100",
                NotificationIdGenerator.forWarranty(100L, 7), NotificationIdGenerator.forBill(100L)),
            ValueRow("warranty 30-day vs bill for id 100",
                NotificationIdGenerator.forWarranty(100L, 30), NotificationIdGenerator.forBill(100L)),
            ValueRow("receipt vs bill for id 100",
                NotificationIdGenerator.forReceipt(100L), NotificationIdGenerator.forBill(100L))
        )
        assertThat(rows).hasSize(10)
        rows.forEach { row ->
            assertWithMessage("row '${row.label}' both were ${row.actual}")
                .that(row.actual).isNotEqualTo(row.expected)
        }
    }

    // ==================== COLLISION / DISTRIBUTION ====================

    @Test
    fun `warranty ranges stay fully below receipt range`() {
        val samples = listOf(0L, 1L, 4_999L, 5_000L, 9_999L, Long.MAX_VALUE)
        assertThat(samples).hasSize(6)

        samples.forEach { warrantyId ->
            val warranty7DayId = NotificationIdGenerator.forWarranty(warrantyId, 7)
            val warranty30DayId = NotificationIdGenerator.forWarranty(warrantyId, 30)

            assertWithMessage("sample $warrantyId 7-day").that(warranty7DayId).isLessThan(20000)
            assertWithMessage("sample $warrantyId 30-day").that(warranty30DayId).isLessThan(20000)
            assertWithMessage("sample $warrantyId 7-day vs receipt")
                .that(warranty7DayId).isNotEqualTo(NotificationIdGenerator.forReceipt(warrantyId))
            assertWithMessage("sample $warrantyId 30-day vs receipt")
                .that(warranty30DayId).isNotEqualTo(NotificationIdGenerator.forReceipt(warrantyId))
        }
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
        runBlocking {
            fake.postBudgetAlertForKind(NotificationKind.BUDGET, NotificationId.forBill(sourceId), "title", "msg")
        }

        assertThat(fake.lastPostedId).isEqualTo(NotificationIdGenerator.forBill(sourceId))
        assertThat(fake.lastPostedId).isAtLeast(30000)
        assertThat(fake.lastPostedId).isAtMost(39999)
    }

    @Test
    fun `posting helper for anomaly uses generator general range`() {
        val fake = CapturingNotificationService()

        runBlocking {
            fake.postAnomalyAlertForKind(NotificationKind.ANOMALY, NotificationId.forGeneral(77L), "t", "m", 77L)
        }

        assertThat(fake.lastPostedId).isEqualTo(NotificationIdGenerator.forGeneral(77L))
        assertThat(fake.lastPostedId).isAtLeast(40000)
        assertThat(fake.lastPostedId).isAtMost(49999)
    }

    /** Minimal fake that captures the raw Int the mediated helper delegates to. */
    private class CapturingNotificationService : com.yourname.expensetracker.domain.service.NotificationService {
        var lastPostedId: Int = -1

        override suspend fun sendBudgetAlertForKind(
            kind: com.yourname.expensetracker.domain.notification.presentation.NotificationKind,
            notificationId: Int,
            title: String,
            message: String
        ): com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult {
            lastPostedId = notificationId
            return com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult.DELIVERED
        }

        override suspend fun sendAiBriefingReadyForKind(
            kind: com.yourname.expensetracker.domain.notification.presentation.NotificationKind,
            notificationId: Int,
            title: String,
            message: String,
            targetKey: String
        ): com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult {
            lastPostedId = notificationId
            return com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult.DELIVERED
        }

        override suspend fun sendAnomalyAlertForKind(
            kind: com.yourname.expensetracker.domain.notification.presentation.NotificationKind,
            notificationId: Int,
            title: String,
            message: String,
            expenseId: Long
        ): com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult {
            lastPostedId = notificationId
            return com.yourname.expensetracker.domain.service.NotificationService.DeliveryResult.DELIVERED
        }
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
}
