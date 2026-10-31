package com.yourname.expensetracker.ui.screens.installments.actions

import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.ui.model.MoneyDisplayUi
import com.yourname.expensetracker.ui.screens.installments.detail.InstallmentEntryLabelUi
import com.yourname.expensetracker.ui.screens.installments.detail.InstallmentEntryRowUi
import com.yourname.expensetracker.ui.screens.installments.detail.InstallmentPlanDetailContent
import com.yourname.expensetracker.ui.screens.installments.overview.InstallmentAmountUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * loan-installments S10b-2: the mapping from the loaded plan detail to the action context
 * (`toActionContext`) and the plan actions offered by status (ux-flows 3.7 table). Pure; synthetic rows.
 */
class InstallmentPlanActionContextTest {

    private fun row(id: Long, label: InstallmentEntryLabelUi, minor: Long) = InstallmentEntryRowUi(
        entryId = id,
        seq = id.toInt(),
        dueEpochDay = 20_000L + id,
        label = label,
        reason = null,
        amount = MoneyDisplayUi.from(minor / 100.0, "EUR"),
        originalAmount = null,
        variance = null,
        awaitingConfirmation = false,
        scheduledMinor = minor
    )

    private fun content(
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
        kind: InstallmentPlanKind = InstallmentPlanKind.STANDALONE,
        entries: List<InstallmentEntryRowUi>,
        paidCount: Int = 0
    ) = InstallmentPlanDetailContent(
        planId = 7L,
        displayName = "Synthetic plan",
        lenderLabel = null,
        currencyCode = "EUR",
        kind = kind,
        status = status,
        fulfilmentMode = InstallmentFulfilmentMode.CONFIRM,
        paidCount = paidCount,
        totalCount = entries.size,
        scheduledCount = entries.count { it.label == InstallmentEntryLabelUi.UPCOMING },
        remaining = MoneyDisplayUi.from(70.0, "EUR"),
        paidSoFar = InstallmentAmountUi.Money(MoneyDisplayUi.from(0.0, "EUR")),
        financingCost = InstallmentAmountUi.Money(MoneyDisplayUi.from(0.0, "EUR")),
        financingNote = null,
        endEpochDay = 20_010L,
        awaitingCount = 0,
        awaitingAmount = null,
        entries = entries
    )

    private val unpaidRows = listOf(
        row(1L, InstallmentEntryLabelUi.UPCOMING, 1_500L),
        row(2L, InstallmentEntryLabelUi.DUE_TODAY, 2_500L),
        row(3L, InstallmentEntryLabelUi.OVERDUE, 3_000L)
    )

    private fun availability(status: InstallmentPlanStatus, entries: List<InstallmentEntryRowUi>) =
        InstallmentPlanAvailability.of(content(status = status, entries = entries).toActionContext())

    // ------------------------------------------------------------------ toActionContext

    @Test
    fun `toActionContext counts and sums only open entries, ignoring skipped, cancelled and paid before tracking`() {
        val ctx = content(
            kind = InstallmentPlanKind.PURCHASE_ATTACHED,
            paidCount = 2,
            entries = unpaidRows + listOf(
                row(4L, InstallmentEntryLabelUi.SKIPPED, 9_900L),
                row(5L, InstallmentEntryLabelUi.CANCELLED, 8_800L),
                row(6L, InstallmentEntryLabelUi.PAID_BEFORE_TRACKING, 7_700L)
            )
        ).toActionContext()

        assertEquals(7L, ctx.planId)
        assertEquals(InstallmentPlanStatus.ACTIVE, ctx.status)
        assertEquals(InstallmentPlanKind.PURCHASE_ATTACHED, ctx.kind)
        assertEquals(3, ctx.unpaidCount)
        assertEquals(7_000L, ctx.unpaidTotalMinor)
        assertEquals(2, ctx.paidCount)
        assertEquals(MoneyDisplayUi.from(70.0, "EUR").formatted, ctx.unpaidTotal.formatted)
    }

    @Test
    fun `toActionContext reports payments only for paid, payoff or refunded entries`() {
        assertFalse(content(entries = unpaidRows).toActionContext().hasPayments)
        assertFalse(
            content(entries = unpaidRows + row(4L, InstallmentEntryLabelUi.SKIPPED, 1_000L)).toActionContext().hasPayments
        )
        assertTrue(
            content(entries = unpaidRows + row(4L, InstallmentEntryLabelUi.PAID, 1_000L)).toActionContext().hasPayments
        )
        assertTrue(
            content(entries = unpaidRows + row(4L, InstallmentEntryLabelUi.PAYOFF, 1_000L)).toActionContext().hasPayments
        )
        assertTrue(
            content(entries = unpaidRows + row(4L, InstallmentEntryLabelUi.REFUNDED, 1_000L)).toActionContext().hasPayments
        )
    }

    @Test
    fun `toActionContext of a plan without entries has zero open entries and a zero payoff prefill`() {
        val ctx = content(entries = emptyList()).toActionContext()

        assertEquals(0, ctx.unpaidCount)
        assertEquals(0L, ctx.unpaidTotalMinor)
        assertFalse(ctx.hasPayments)
    }

    // ------------------------------------------------------------------ ux-flows 3.7 table by status

    private fun assertAvailability(
        status: InstallmentPlanStatus,
        entries: List<InstallmentEntryRowUi>,
        edit: Boolean,
        stop: Boolean,
        refund: Boolean,
        payOff: Boolean,
        delete: Boolean
    ) {
        assertEquals(
            "status=$status entries=${entries.map { it.label }}",
            InstallmentPlanAvailability(edit = edit, stop = stop, refund = refund, payOff = payOff, delete = delete),
            availability(status, entries)
        )
    }

    private val withPayment = unpaidRows + row(4L, InstallmentEntryLabelUi.PAID, 1_000L)

    @Test
    fun `table Active without payments offers everything`() =
        assertAvailability(InstallmentPlanStatus.ACTIVE, unpaidRows, edit = true, stop = true, refund = true, payOff = true, delete = true)

    @Test
    fun `table Active with a paid entry offers no delete`() =
        assertAvailability(InstallmentPlanStatus.ACTIVE, withPayment, edit = true, stop = true, refund = true, payOff = true, delete = false)

    @Test
    fun `table Active with nothing left unpaid offers no pay off`() =
        assertAvailability(
            InstallmentPlanStatus.ACTIVE,
            listOf(row(1L, InstallmentEntryLabelUi.PAID, 1_000L)),
            edit = true, stop = true, refund = true, payOff = false, delete = false
        )

    @Test
    fun `table Completed offers edit details and refund only`() =
        assertAvailability(
            InstallmentPlanStatus.COMPLETED,
            listOf(row(1L, InstallmentEntryLabelUi.PAID, 1_000L)),
            edit = true, stop = false, refund = true, payOff = false, delete = false
        )

    @Test
    fun `table Cancelled offers delete only when nothing was paid`() {
        val cancelledRows = listOf(row(1L, InstallmentEntryLabelUi.CANCELLED, 1_000L))
        assertAvailability(InstallmentPlanStatus.CANCELLED, cancelledRows, edit = false, stop = false, refund = false, payOff = false, delete = true)
        assertAvailability(
            InstallmentPlanStatus.CANCELLED,
            cancelledRows + row(2L, InstallmentEntryLabelUi.PAID, 1_000L),
            edit = false, stop = false, refund = false, payOff = false, delete = false
        )
    }

    @Test
    fun `table Refunded offers nothing`() {
        val rows = listOf(row(1L, InstallmentEntryLabelUi.REFUNDED, 1_000L))
        assertAvailability(InstallmentPlanStatus.REFUNDED, rows, edit = false, stop = false, refund = false, payOff = false, delete = false)
        assertFalse(availability(InstallmentPlanStatus.REFUNDED, rows).canEnd)
    }

    @Test
    fun `table Paid off offers nothing`() {
        val rows = listOf(row(1L, InstallmentEntryLabelUi.PAYOFF, 1_000L))
        assertAvailability(InstallmentPlanStatus.PAID_OFF, rows, edit = false, stop = false, refund = false, payOff = false, delete = false)
        assertFalse(availability(InstallmentPlanStatus.PAID_OFF, rows).canEnd)
    }

    @Test
    fun `canEnd is true when at least one end option exists`() {
        assertTrue(availability(InstallmentPlanStatus.ACTIVE, unpaidRows).canEnd)
        assertTrue(
            availability(InstallmentPlanStatus.COMPLETED, listOf(row(1L, InstallmentEntryLabelUi.PAID, 1_000L))).canEnd
        )
    }
}
