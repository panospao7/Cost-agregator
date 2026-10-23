package com.yourname.expensetracker.data.service

import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM test of [NotificationKindClassifier] (spec 4.6, AMD-21, AMD-22, INV-13, AE-112, AE-121). The channel
 * ids are written as literals on purpose: they are wire values, and the test pins them against the production
 * constants so a changed constant fails here.
 */
class NotificationKindClassifierTest {

    private val budget = "budget_alerts"
    private val bill = "bill_reminders"
    private val anomaly = "anomaly_alerts"
    private val ai = "ai_briefings"

    private fun classify(channel: String?, id: Int, marker: String? = null): NotificationKind? =
        NotificationKindClassifier.classify(channel, id, marker)

    @Test
    fun `channel literals match the production constants`() {
        assertEquals(budget, AndroidNotificationService.BUDGET_CHANNEL_ID)
        assertEquals(bill, com.yourname.expensetracker.service.reminder.BillReminderWorker.CHANNEL_ID)
        assertEquals(anomaly, AndroidNotificationService.ANOMALY_CHANNEL_ID)
        assertEquals(ai, AndroidNotificationService.AI_CHANNEL_ID)
    }

    // --- 4.6 table, marker-less -----------------------------------------------------------------

    @Test
    fun `bill_reminders is BILL for any id`() {
        listOf(30011, 30000, 39999, 1, 0, -5, Int.MAX_VALUE).forEach {
            assertEquals("id $it", NotificationKind.BILL, classify(bill, it))
        }
    }

    @Test
    fun `anomaly_alerts is ANOMALY for any id`() {
        listOf(40077, 1, 15003, 20042, 0, -1).forEach {
            assertEquals("id $it", NotificationKind.ANOMALY, classify(anomaly, it))
        }
    }

    @Test
    fun `ai_briefings is BRIEFING for any id`() {
        listOf(1, 42, 15003, 40000, 0, -1).forEach {
            assertEquals("id $it", NotificationKind.BRIEFING, classify(ai, it))
        }
    }

    @Test
    fun `budget_alerts 10000 to 19999 is WARRANTY`() {
        listOf(10000, 10003, 14999, 15000, 15003, 19999).forEach {
            assertEquals("id $it", NotificationKind.WARRANTY, classify(budget, it))
        }
    }

    @Test
    fun `budget_alerts 20000 to 29999 is RECEIPT_MATCH`() {
        listOf(20000, 20042, 25000, 29999).forEach {
            assertEquals("id $it", NotificationKind.RECEIPT_MATCH, classify(budget, it))
        }
    }

    @Test
    fun `budget_alerts any other id is BUDGET with no 1 to 9999 assumption`() {
        listOf(Int.MIN_VALUE, -1, 0, 1, 7, 9999, 30000, 30500, 39999, 40000, Int.MAX_VALUE).forEach {
            assertEquals("id $it", NotificationKind.BUDGET, classify(budget, it))
        }
    }

    @Test
    fun `range boundaries are exact`() {
        assertEquals(NotificationKind.BUDGET, classify(budget, 9999))
        assertEquals(NotificationKind.WARRANTY, classify(budget, 10000))
        assertEquals(NotificationKind.WARRANTY, classify(budget, 19999))
        assertEquals(NotificationKind.RECEIPT_MATCH, classify(budget, 20000))
        assertEquals(NotificationKind.RECEIPT_MATCH, classify(budget, 29999))
        assertEquals(NotificationKind.BUDGET, classify(budget, 30000))
    }

    @Test
    fun `any other channel is not a sweep kind including the capture FGS channel`() {
        // "expense_tracker_service" is the production capture channel (NotificationCaptureService.CHANNEL_ID, private).
        listOf(null, "", "expense_tracker_service", "capture_service", "capture_fgs", "Budget_Alerts", "budget_alerts ", "other").forEach { channel ->
            listOf(1, 15003, 30011, 40077).forEach { id ->
                assertNull("$channel/$id", classify(channel, id))
                NotificationKind.values().forEach { kind ->
                    assertNull("$channel/$id marker ${kind.stableToken}", classify(channel, id, kind.stableToken))
                }
            }
        }
    }

    @Test
    fun `the four app channels each classify to a kind by channel alone`() {
        listOf(budget, bill, anomaly, ai).forEach { assertNotNull(it, classify(it, 1)) }
    }

    // --- AE-121 ---------------------------------------------------------------------------------

    @Test
    fun `AE-121 BUDGET marker on id 15003 is BUDGET`() {
        assertEquals(NotificationKind.BUDGET, classify(budget, 15003, "BUDGET"))
    }

    @Test
    fun `AE-121 no marker on id 10003 is WARRANTY`() {
        assertEquals(NotificationKind.WARRANTY, classify(budget, 10003, null))
    }

    @Test
    fun `AE-121 BILL marker on budget_alerts 20042 is not allowed so the table gives RECEIPT_MATCH`() {
        assertEquals(NotificationKind.RECEIPT_MATCH, classify(budget, 20042, "BILL"))
    }

    @Test
    fun `AE-121 no marker on bill_reminders 30011 is BILL`() {
        assertEquals(NotificationKind.BILL, classify(bill, 30011, null))
    }

    @Test
    fun `AE-121 unknown marker X on id 7 falls back to the table BUDGET`() {
        assertEquals(NotificationKind.BUDGET, classify(budget, 7, "X"))
    }

    // --- raw budget ids with and without markers ---------------------------------------------------

    @Test
    fun `raw budget ids 7 15003 20042 and 30500 without a marker follow the table`() {
        assertEquals(NotificationKind.BUDGET, classify(budget, 7))
        assertEquals(NotificationKind.WARRANTY, classify(budget, 15003))
        assertEquals(NotificationKind.RECEIPT_MATCH, classify(budget, 20042))
        assertEquals(NotificationKind.BUDGET, classify(budget, 30500))
    }

    @Test
    fun `raw budget ids 7 15003 20042 and 30500 with a BUDGET marker are BUDGET`() {
        listOf(7, 15003, 20042, 30500).forEach {
            assertEquals("id $it", NotificationKind.BUDGET, classify(budget, it, "BUDGET"))
        }
    }

    @Test
    fun `an allowed budget-channel marker wins over the id range`() {
        assertEquals(NotificationKind.WARRANTY, classify(budget, 7, "WARRANTY"))
        assertEquals(NotificationKind.RECEIPT_MATCH, classify(budget, 7, "RECEIPT_MATCH"))
        assertEquals(NotificationKind.WARRANTY, classify(budget, 20042, "WARRANTY"))
        assertEquals(NotificationKind.RECEIPT_MATCH, classify(budget, 15003, "RECEIPT_MATCH"))
        assertEquals(NotificationKind.WARRANTY, classify(budget, 30500, "WARRANTY"))
    }

    // --- marker allowed-per-channel matrix -------------------------------------------------------

    @Test
    fun `a marker that names a kind not allowed on the channel is ignored for every channel and kind`() {
        val allowed = mapOf(
            budget to setOf(NotificationKind.BUDGET, NotificationKind.WARRANTY, NotificationKind.RECEIPT_MATCH),
            bill to setOf(NotificationKind.BILL),
            anomaly to setOf(NotificationKind.ANOMALY),
            ai to setOf(NotificationKind.BRIEFING)
        )
        val tableResult = mapOf(
            bill to NotificationKind.BILL,
            anomaly to NotificationKind.ANOMALY,
            ai to NotificationKind.BRIEFING
        )
        allowed.forEach { (channel, allowedKinds) ->
            NotificationKind.values().forEach { kind ->
                val result = classify(channel, 7, kind.stableToken)
                if (kind in allowedKinds) {
                    assertEquals("$channel marker ${kind.stableToken}", kind, result)
                } else {
                    // Not allowed: the table decides. Id 7 on budget_alerts is BUDGET.
                    assertEquals(
                        "$channel marker ${kind.stableToken}",
                        tableResult[channel] ?: NotificationKind.BUDGET,
                        result
                    )
                }
            }
        }
    }

    @Test
    fun `unknown empty or wrongly cased marker values fall back to the table`() {
        listOf("X", "", " ", "budget", "Bill", "BILL ", "BUDGET ", "null", "WARRANTY_30").forEach { marker ->
            assertEquals("'$marker'", NotificationKind.WARRANTY, classify(budget, 10003, marker))
            assertEquals("'$marker'", NotificationKind.BILL, classify(bill, 30011, marker))
            assertEquals("'$marker'", NotificationKind.ANOMALY, classify(anomaly, 40077, marker))
            assertEquals("'$marker'", NotificationKind.BRIEFING, classify(ai, 42, marker))
        }
        // A trailing-newline BUDGET token is not the BUDGET token.
        assertEquals(NotificationKind.WARRANTY, classify(budget, 10003, "BUDGET\n"))
    }

    @Test
    fun `every kind token is recognised exactly`() {
        assertEquals(NotificationKind.BILL, classify(bill, 1, "BILL"))
        assertEquals(NotificationKind.ANOMALY, classify(anomaly, 1, "ANOMALY"))
        assertEquals(NotificationKind.BRIEFING, classify(ai, 1, "BRIEFING"))
        assertEquals(NotificationKind.BUDGET, classify(budget, 15003, "BUDGET"))
        assertEquals(NotificationKind.WARRANTY, classify(budget, 7, "WARRANTY"))
        assertEquals(NotificationKind.RECEIPT_MATCH, classify(budget, 7, "RECEIPT_MATCH"))
    }

    @Test
    fun `a marker never pulls another channel into the sweep`() {
        NotificationKind.values().forEach { kind ->
            assertNull(kind.name, classify("expense_tracker_service", 1, kind.stableToken))
            assertNull(kind.name, classify("capture_service", 1, kind.stableToken))
            assertNull(kind.name, classify(null, 1, kind.stableToken))
        }
    }
}
