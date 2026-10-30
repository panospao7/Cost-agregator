package com.yourname.expensetracker.domain.installment.readmodel

import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentDueState
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentFulfilmentMode
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanKind
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure read model (loan-installments S6a): AE-5 per-currency overview, AE-10b awaiting line,
 * AE-11 history, variance, INV-13 / spec 8.2 suggestion windows, INV-16, INV-17, INV-26.
 * All data is synthetic; days are plain epoch-day numbers, money is Long minor units.
 */
class InstallmentPlanReadModelTest {

    private val today = 20_000L
    private val due = 20_000L

    // ------------------------------------------------------------------ builders

    private fun money(
        status: InstallmentEntryStatus = InstallmentEntryStatus.SCHEDULED,
        dueDay: Long = due,
        scheduled: Long = 5_000L,
        kind: InstallmentEntryKind = InstallmentEntryKind.REGULAR,
        linked: LinkedExpenseMoney? = null
    ) = EntryMoney(kind, status, dueDay, scheduled, linked)

    private fun entry(
        id: Long,
        seq: Int = id.toInt(),
        status: InstallmentEntryStatus = InstallmentEntryStatus.SCHEDULED,
        dueDay: Long = due,
        scheduled: Long = 5_000L,
        kind: InstallmentEntryKind = InstallmentEntryKind.REGULAR,
        linked: LinkedExpenseMoney? = null,
        linkedExpenseId: Long? = null
    ) = EntrySnapshot(
        entryId = id,
        seq = seq,
        originalScheduledMinor = null,
        linkedExpenseId = linkedExpenseId,
        statusReason = null,
        money = money(status, dueDay, scheduled, kind, linked)
    )

    private fun paid(id: Long, dueDay: Long, minor: Long = 5_000L, currency: String = "EUR") =
        entry(
            id = id,
            status = InstallmentEntryStatus.PAID,
            dueDay = dueDay,
            scheduled = minor,
            linked = LinkedExpenseMoney(currency, minor, minor),
            linkedExpenseId = 1_000L + id
        )

    private fun plan(
        id: Long,
        entries: List<EntrySnapshot>,
        currency: InstallmentCurrency = InstallmentCurrency.EUR,
        mode: InstallmentFulfilmentMode = InstallmentFulfilmentMode.CONFIRM,
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
        kind: InstallmentPlanKind = InstallmentPlanKind.PURCHASE_ATTACHED,
        purchase: PurchaseMoney? = null,
        terminalEpochDay: Long? = null,
        categoryId: Long? = null
    ) = PlanSnapshot(
        planId = id,
        planUuid = "plan-uuid-$id",
        categoryId = categoryId,
        displayName = "Synthetic plan $id",
        lenderLabel = null,
        kind = kind,
        fulfilmentMode = mode,
        status = status,
        currency = currency,
        purchaseExpenseId = if (purchase != null) 500L + id else null,
        purchase = purchase,
        terminalEpochDay = terminalEpochDay,
        entries = entries
    )

    private fun candidate(id: Long, dayOffset: Long, gross: Long = 5_000L, currency: String = "EUR") =
        CandidateExpense(expenseId = id, currency = currency, grossMinor = gross, dateEpochDay = due + dayOffset)

    private fun suggestedIds(plan: PlanSnapshot, candidates: List<CandidateExpense>): List<Long> =
        InstallmentPlanReadModel.suggestionsFor(plan, candidates).map { it.expenseId }

    private fun oneEntryPlan(
        mode: InstallmentFulfilmentMode,
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
        currency: InstallmentCurrency = InstallmentCurrency.EUR
    ) = plan(1L, listOf(entry(1L)), currency = currency, mode = mode, status = status)

    // ------------------------------------------------------------------ S10b-2 category carried into the detail

    @Test
    fun `detail carries the snapshot categoryId and keeps null as null`() {
        val withCategory = plan(1L, listOf(entry(1L)), categoryId = 42L)
        val withoutCategory = plan(2L, listOf(entry(1L)), categoryId = null)

        assertEquals(42L, InstallmentPlanReadModel.detail(withCategory, today).categoryId)
        assertNull(InstallmentPlanReadModel.detail(withoutCategory, today).categoryId)
    }

    // ------------------------------------------------------------------ AE-5 overview per currency

    @Test
    fun `AE-5 overview lists remaining scheduled per currency without a combined total`() {
        val a = plan(
            1L,
            (1L..6L).map { paid(it, today - 200L + it) } +
                (7L..12L).map { entry(it, dueDay = today + it * 30L, scheduled = 5_000L) }
        )
        val b = plan(
            2L,
            (1L..4L).map { paid(100L + it, today - 100L + it, minor = 12_500L, currency = "USD") } +
                (5L..8L).map { entry(100L + it, seq = it.toInt(), dueDay = today + it * 30L, scheduled = 12_500L) },
            currency = InstallmentCurrency.USD,
            kind = InstallmentPlanKind.STANDALONE
        )
        val c = plan(
            3L,
            (1L..4L).map { entry(200L + it, seq = it.toInt(), dueDay = today + it * 30L, scheduled = 5_600L) },
            purchase = PurchaseMoney("USD", 24_000L)
        )

        val overview = InstallmentPlanReadModel.overview(listOf(a, b, c), today)

        assertEquals(52_400L, overview.remainingScheduledByCurrency[InstallmentCurrency.EUR])
        assertEquals(50_000L, overview.remainingScheduledByCurrency[InstallmentCurrency.USD])
        assertEquals("exactly one line per currency, never a combined line", 2, overview.remainingScheduledByCurrency.size)
        assertEquals(3, overview.plans.size)
        assertEquals(0, overview.unreadablePlanCount)
    }

    @Test
    fun `AE-5 financing cost is unavailable for the standalone plan and for the currency differing plan`() {
        val standalone = plan(
            2L,
            listOf(entry(1L)),
            currency = InstallmentCurrency.USD,
            kind = InstallmentPlanKind.STANDALONE
        )
        val currencyDiffers = plan(3L, listOf(entry(2L)), purchase = PurchaseMoney("USD", 24_000L))

        assertEquals(
            InstallmentAmount.Unavailable(InstallmentUnavailableReason.NO_PURCHASE),
            InstallmentPlanReadModel.detail(standalone, today).financingCost
        )
        assertEquals(
            InstallmentAmount.Unavailable(InstallmentUnavailableReason.CURRENCY_DIFFERS),
            InstallmentPlanReadModel.detail(currencyDiffers, today).financingCost
        )
    }

    @Test
    fun `AE-5 a currency with no active plan has no line and is not zero`() {
        val eurOnly = plan(1L, listOf(entry(1L, dueDay = today + 10L)))
        val completedUsd = plan(
            2L,
            listOf(paid(2L, today - 30L, currency = "USD")),
            currency = InstallmentCurrency.USD,
            status = InstallmentPlanStatus.COMPLETED
        )

        val overview = InstallmentPlanReadModel.overview(listOf(eurOnly, completedUsd), today)

        assertTrue(overview.remainingScheduledByCurrency.containsKey(InstallmentCurrency.EUR))
        assertFalse("no USD line may appear", overview.remainingScheduledByCurrency.containsKey(InstallmentCurrency.USD))
        assertNull(overview.remainingScheduledByCurrency[InstallmentCurrency.USD])
        assertTrue("a currency with nothing awaiting has no awaiting line", overview.awaitingConfirmationByCurrency.isEmpty())
    }

    @Test
    fun `AE-5 overview lists only active plans and carries the unreadable count through`() {
        val active = plan(1L, listOf(entry(1L, dueDay = today + 5L)))
        val others = listOf(
            plan(2L, listOf(entry(2L)), status = InstallmentPlanStatus.COMPLETED),
            plan(3L, listOf(entry(3L)), status = InstallmentPlanStatus.CANCELLED),
            plan(4L, listOf(entry(4L)), status = InstallmentPlanStatus.REFUNDED),
            plan(5L, listOf(entry(5L)), status = InstallmentPlanStatus.PAID_OFF)
        )

        val overview = InstallmentPlanReadModel.overview(listOf(active) + others, today, unreadablePlanCount = 2)

        assertEquals(listOf(1L), overview.plans.map { it.planId })
        assertEquals(2, overview.unreadablePlanCount)
    }

    @Test
    fun `AE-5 overview orders plans by next due then plan id and puts plans without a next due last`() {
        val late = plan(1L, listOf(entry(1L, dueDay = today + 30L)))
        val early = plan(2L, listOf(entry(2L, dueDay = today + 10L)))
        val sameDueHigherId = plan(4L, listOf(entry(4L, dueDay = today + 10L)))
        val nothingLeft = plan(3L, listOf(paid(3L, today - 5L)))

        val overview = InstallmentPlanReadModel.overview(listOf(late, nothingLeft, sameDueHigherId, early), today)

        assertEquals(listOf(2L, 4L, 1L, 3L), overview.plans.map { it.planId })
        assertNull(overview.plans.last().nextDue)
    }

    @Test
    fun `overview next due is the earliest scheduled entry and an overdue one wins`() {
        val p = plan(
            1L,
            listOf(
                paid(1L, today - 60L),
                entry(2L, dueDay = today - 3L),
                entry(3L, dueDay = today + 27L)
            )
        )

        val next = InstallmentPlanReadModel.summary(p, today).nextDue

        assertNotNull(next)
        assertEquals(2L, next!!.entryId)
        assertEquals(InstallmentDueState.OVERDUE, next.dueState)
        assertEquals(today + 27L, InstallmentPlanReadModel.summary(p, today).endEpochDay)
    }

    // ------------------------------------------------------------------ AE-10b awaiting confirmation

    @Test
    fun `AE-10b awaiting line counts past due scheduled entries of confirm plans per currency`() {
        val confirmEur = plan(
            1L,
            listOf(
                entry(1L, dueDay = today - 20L, scheduled = 5_000L),
                entry(2L, dueDay = today, scheduled = 5_000L),
                entry(3L, dueDay = today + 1L, scheduled = 5_000L)
            )
        )
        val confirmUsd = plan(
            2L,
            listOf(entry(4L, dueDay = today - 1L, scheduled = 12_500L)),
            currency = InstallmentCurrency.USD
        )

        val overview = InstallmentPlanReadModel.overview(listOf(confirmEur, confirmUsd), today)

        assertEquals(InstallmentAwaitingTotals(count = 2, minor = 10_000L), overview.awaitingConfirmationByCurrency[InstallmentCurrency.EUR])
        assertEquals(InstallmentAwaitingTotals(count = 1, minor = 12_500L), overview.awaitingConfirmationByCurrency[InstallmentCurrency.USD])
    }

    @Test
    fun `AE-10b awaiting line excludes auto record plans paid entries and non active plans`() {
        val autoRecord = plan(
            1L,
            listOf(entry(1L, dueDay = today - 20L)),
            mode = InstallmentFulfilmentMode.AUTO_RECORD
        )
        val confirmPaid = plan(2L, listOf(paid(2L, today - 20L), entry(3L, dueDay = today + 9L)))
        val confirmCompleted = plan(
            3L,
            listOf(entry(4L, dueDay = today - 20L)),
            status = InstallmentPlanStatus.COMPLETED
        )
        val confirmSkipped = plan(
            4L,
            listOf(entry(5L, status = InstallmentEntryStatus.SKIPPED, dueDay = today - 20L))
        )

        val overview = InstallmentPlanReadModel.overview(
            listOf(autoRecord, confirmPaid, confirmCompleted, confirmSkipped),
            today
        )

        assertTrue(overview.awaitingConfirmationByCurrency.isEmpty())
    }

    @Test
    fun `AE-10b a single awaiting entry reads one payment and its amount`() {
        val p = plan(1L, listOf(entry(2L, seq = 2, dueDay = today - 15L, scheduled = 5_000L)))

        val awaiting = InstallmentPlanReadModel.overview(listOf(p), today)
            .awaitingConfirmationByCurrency[InstallmentCurrency.EUR]

        assertEquals(1, awaiting!!.count)
        assertEquals(5_000L, awaiting.minor)
    }

    // ------------------------------------------------------------------ AE-11 history

    private fun completedPlan(): PlanSnapshot = plan(
        1L,
        (1L..12L).map { paid(it, dueDay = 19_000L + it * 30L) },
        status = InstallmentPlanStatus.COMPLETED,
        purchase = PurchaseMoney("EUR", 60_000L),
        terminalEpochDay = 19_361L
    )

    @Test
    fun `AE-11 completed plan leaves the overview and its remaining line disappears`() {
        val overview = InstallmentPlanReadModel.overview(listOf(completedPlan()), today)

        assertTrue(overview.plans.isEmpty())
        assertTrue(overview.remainingScheduledByCurrency.isEmpty())
    }

    @Test
    fun `AE-11 history line shows counted total paid count and financing cost`() {
        val item = InstallmentPlanReadModel.history(listOf(completedPlan())).single()

        assertEquals(InstallmentPlanStatus.COMPLETED, item.summary.finalStatus)
        assertEquals(InstallmentAmount.Available(60_000L), item.summary.totalCounted)
        assertEquals(12, item.summary.counts.paid)
        assertEquals(InstallmentAmount.Available(0L), item.summary.financingCost)
        assertEquals(19_000L + 30L, item.summary.startEpochDay)
        assertEquals(19_000L + 360L, item.summary.endEpochDay)
    }

    @Test
    fun `AE-11 history of a refunded plan counts zero because its paid entries are reversed`() {
        val refunded = plan(
            1L,
            listOf(
                entry(
                    1L, status = InstallmentEntryStatus.REVERSED, dueDay = today - 90L,
                    linked = LinkedExpenseMoney("EUR", 5_000L, 0L), linkedExpenseId = 1_001L
                ),
                entry(2L, status = InstallmentEntryStatus.CANCELLED, dueDay = today - 60L)
            ),
            status = InstallmentPlanStatus.REFUNDED,
            purchase = PurchaseMoney("EUR", 60_000L),
            terminalEpochDay = today - 50L
        )

        val summary = InstallmentPlanReadModel.history(listOf(refunded)).single().summary

        assertEquals(InstallmentAmount.Available(0L), summary.totalCounted)
        assertEquals(1, summary.counts.reversed)
        assertEquals(1, summary.counts.cancelled)
        assertEquals(
            InstallmentAmount.Unavailable(InstallmentUnavailableReason.STATUS_NOT_ELIGIBLE),
            summary.financingCost
        )
    }

    @Test
    fun `AE-11 history is most recent end date first with unknown end dates last and excludes active plans`() {
        val old = plan(1L, listOf(entry(1L, status = InstallmentEntryStatus.CANCELLED)), status = InstallmentPlanStatus.CANCELLED, terminalEpochDay = 100L)
        val recent = plan(2L, listOf(entry(2L, status = InstallmentEntryStatus.CANCELLED)), status = InstallmentPlanStatus.CANCELLED, terminalEpochDay = 900L)
        val unknownEnd = plan(3L, listOf(entry(3L, status = InstallmentEntryStatus.CANCELLED)), status = InstallmentPlanStatus.CANCELLED, terminalEpochDay = null)
        val active = plan(4L, listOf(entry(4L)))

        val ids = InstallmentPlanReadModel.history(listOf(unknownEnd, old, active, recent)).map { it.planId }

        assertEquals(listOf(2L, 1L, 3L), ids)
    }

    // ------------------------------------------------------------------ variance

    @Test
    fun `variance is positive when the linked expense exceeds the schedule`() {
        val e = money(
            status = InstallmentEntryStatus.PAID, scheduled = 5_000L,
            linked = LinkedExpenseMoney("EUR", 5_300L, 5_300L)
        )

        assertEquals(InstallmentAmount.Available(300L), InstallmentPlanReadModel.entryVariance(InstallmentCurrency.EUR, e))
    }

    @Test
    fun `variance is negative when the linked expense is below the schedule`() {
        val e = money(
            status = InstallmentEntryStatus.PAID, scheduled = 5_000L,
            linked = LinkedExpenseMoney("EUR", 4_800L, 4_800L)
        )

        assertEquals(InstallmentAmount.Available(-200L), InstallmentPlanReadModel.entryVariance(InstallmentCurrency.EUR, e))
    }

    @Test
    fun `variance of an exact payment is an available zero and not unavailable`() {
        val e = money(
            status = InstallmentEntryStatus.PAID, scheduled = 5_000L,
            linked = LinkedExpenseMoney("EUR", 5_000L, 5_000L)
        )

        assertEquals(InstallmentAmount.Available(0L), InstallmentPlanReadModel.entryVariance(InstallmentCurrency.EUR, e))
    }

    @Test
    fun `variance of a not mine linked expense uses effective zero and is minus the scheduled amount`() {
        val e = money(
            status = InstallmentEntryStatus.PAID, scheduled = 5_000L,
            linked = LinkedExpenseMoney("EUR", grossMinor = 5_000L, effectiveMinor = 0L)
        )

        assertEquals(InstallmentAmount.Available(-5_000L), InstallmentPlanReadModel.entryVariance(InstallmentCurrency.EUR, e))
    }

    @Test
    fun `variance is unavailable and never zero when the link is missing`() {
        val e = money(status = InstallmentEntryStatus.PAID, scheduled = 5_000L, linked = null)

        assertEquals(
            InstallmentAmount.Unavailable(InstallmentUnavailableReason.MISSING_LINKED_EXPENSE),
            InstallmentPlanReadModel.entryVariance(InstallmentCurrency.EUR, e)
        )
    }

    @Test
    fun `variance is unavailable and never zero on linked currency drift`() {
        val e = money(
            status = InstallmentEntryStatus.PAID, scheduled = 5_000L,
            linked = LinkedExpenseMoney("USD", 5_000L, 5_000L)
        )

        assertEquals(
            InstallmentAmount.Unavailable(InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT),
            InstallmentPlanReadModel.entryVariance(InstallmentCurrency.EUR, e)
        )
    }

    @Test
    fun `variance is null for every status that is not paid`() {
        for (status in InstallmentEntryStatus.entries.filter { it != InstallmentEntryStatus.PAID }) {
            assertNull(
                "status $status",
                InstallmentPlanReadModel.entryVariance(InstallmentCurrency.EUR, money(status = status))
            )
        }
    }

    @Test
    fun `detail entry lines carry variance and due state and are ordered by due date`() {
        val p = plan(
            1L,
            listOf(
                entry(3L, seq = 3, dueDay = today + 30L),
                entry(
                    1L, seq = 1, status = InstallmentEntryStatus.PAID, dueDay = today - 30L, scheduled = 5_000L,
                    linked = LinkedExpenseMoney("EUR", 5_300L, 5_300L), linkedExpenseId = 1_001L
                ),
                entry(2L, seq = 2, dueDay = today)
            )
        )

        val lines = InstallmentPlanReadModel.detail(p, today).entries

        assertEquals(listOf(1L, 2L, 3L), lines.map { it.entryId })
        assertEquals(InstallmentAmount.Available(300L), lines[0].variance)
        assertNull(lines[0].dueState)
        assertEquals(InstallmentDueState.DUE, lines[1].dueState)
        assertNull(lines[1].variance)
        assertEquals(InstallmentDueState.UPCOMING, lines[2].dueState)
    }

    // ------------------------------------------------------------------ INV-13 / 8.2 windows

    @Test
    fun `INV-13 auto record window pins to due minus three through due`() {
        assertEquals(due - 3L, InstallmentCandidateRule.autoRecordWindowStartEpochDay(due))
        assertEquals(due, InstallmentCandidateRule.autoRecordWindowEndEpochDay(due))
        assertEquals(3L, InstallmentCandidateRule.AUTO_RECORD_DAYS_BEFORE_DUE)
        assertEquals(0L, InstallmentCandidateRule.AUTO_RECORD_DAYS_AFTER_DUE)
        assertEquals(
            InstallmentCandidateRule.autoRecordWindowStartEpochDay(due),
            InstallmentCandidateRule.windowStartEpochDay(InstallmentFulfilmentMode.AUTO_RECORD, due)
        )
        assertEquals(
            InstallmentCandidateRule.autoRecordWindowEndEpochDay(due),
            InstallmentCandidateRule.windowEndEpochDay(InstallmentFulfilmentMode.AUTO_RECORD, due)
        )
    }

    @Test
    fun `spec 8-2 confirm window pins to due minus five through due plus ten`() {
        assertEquals(due - 5L, InstallmentCandidateRule.windowStartEpochDay(InstallmentFulfilmentMode.CONFIRM, due))
        assertEquals(due + 10L, InstallmentCandidateRule.windowEndEpochDay(InstallmentFulfilmentMode.CONFIRM, due))
    }

    @Test
    fun `INV-13 auto record window edges`() {
        val p = oneEntryPlan(InstallmentFulfilmentMode.AUTO_RECORD)
        val all = listOf(
            candidate(1L, -4L), candidate(2L, -3L), candidate(3L, -1L), candidate(4L, 0L), candidate(5L, 1L)
        )

        assertEquals(listOf(2L, 3L, 4L), suggestedIds(p, all).sorted())
    }

    @Test
    fun `spec 8-2 confirm window edges`() {
        val p = oneEntryPlan(InstallmentFulfilmentMode.CONFIRM)
        val all = listOf(
            candidate(1L, -6L), candidate(2L, -5L), candidate(3L, 0L), candidate(4L, 10L), candidate(5L, 11L)
        )

        assertEquals(listOf(2L, 3L, 4L), suggestedIds(p, all).sorted())
    }

    @Test
    fun `INV-13 amount tolerance is one minor unit in both modes and two minor units is out`() {
        for (mode in InstallmentFulfilmentMode.entries) {
            val p = oneEntryPlan(mode)
            val all = listOf(
                candidate(1L, 0L, gross = 5_000L),
                candidate(2L, 0L, gross = 5_001L),
                candidate(3L, 0L, gross = 4_999L),
                candidate(4L, 0L, gross = 5_002L),
                candidate(5L, 0L, gross = 4_998L)
            )

            assertEquals("mode $mode", listOf(1L, 2L, 3L), suggestedIds(p, all).sorted())
        }
    }

    @Test
    fun `INV-13 suggestion delta is the expense gross minus the scheduled amount`() {
        val p = oneEntryPlan(InstallmentFulfilmentMode.CONFIRM)

        val s = InstallmentPlanReadModel.suggestionsFor(p, listOf(candidate(7L, 2L, gross = 4_999L))).single()

        assertEquals(-1L, s.deltaMinor)
        assertEquals(1L, s.planId)
        assertEquals(1L, s.entryId)
        assertEquals(7L, s.expenseId)
        assertEquals(4_999L, s.expenseGrossMinor)
        assertEquals(due + 2L, s.expenseDateEpochDay)
    }

    @Test
    fun `INV-13 a candidate in another currency is never suggested`() {
        for (mode in InstallmentFulfilmentMode.entries) {
            val p = oneEntryPlan(mode)

            assertTrue(suggestedIds(p, listOf(candidate(1L, 0L, currency = "USD"))).isEmpty())
        }
        val usdPlan = oneEntryPlan(InstallmentFulfilmentMode.CONFIRM, currency = InstallmentCurrency.USD)
        assertEquals(listOf(3L), suggestedIds(usdPlan, listOf(candidate(3L, 0L, currency = "USD"), candidate(4L, 0L, currency = "EUR"))))
    }

    @Test
    fun `INV-13 only scheduled unlinked regular entries get suggestions`() {
        val entries = listOf(
            entry(1L, status = InstallmentEntryStatus.SCHEDULED),
            entry(2L, status = InstallmentEntryStatus.PAID_BEFORE_TRACKING),
            entry(3L, status = InstallmentEntryStatus.SCHEDULED, kind = InstallmentEntryKind.PAYOFF),
            entry(4L, status = InstallmentEntryStatus.SCHEDULED, linkedExpenseId = 99L),
            entry(5L, status = InstallmentEntryStatus.PAID),
            entry(6L, status = InstallmentEntryStatus.SKIPPED),
            entry(7L, status = InstallmentEntryStatus.CANCELLED),
            entry(8L, status = InstallmentEntryStatus.REVERSED)
        )
        val p = plan(1L, entries)

        val suggestions = InstallmentPlanReadModel.suggestionsFor(p, listOf(candidate(50L, 0L)))

        assertEquals(listOf(1L), suggestions.map { it.entryId })
    }

    @Test
    fun `INV-13 a paid before tracking entry alone gets no suggestion`() {
        val p = plan(1L, listOf(entry(1L, status = InstallmentEntryStatus.PAID_BEFORE_TRACKING)))

        assertTrue(suggestedIds(p, listOf(candidate(1L, 0L))).isEmpty())
    }

    @Test
    fun `INV-13 a non active plan gets no suggestions`() {
        for (status in InstallmentPlanStatus.entries.filter { it != InstallmentPlanStatus.ACTIVE }) {
            val p = oneEntryPlan(InstallmentFulfilmentMode.CONFIRM, status = status)

            assertTrue("status $status", suggestedIds(p, listOf(candidate(1L, 0L))).isEmpty())
        }
    }

    @Test
    fun `INV-13 suggestions are ordered by due then closest amount then newest date then expense id`() {
        val p = plan(
            1L,
            listOf(
                entry(2L, seq = 2, dueDay = due + 40L),
                entry(1L, seq = 1, dueDay = due)
            )
        )
        val all = listOf(
            candidate(10L, 0L, gross = 5_000L),
            candidate(11L, 0L, gross = 5_001L),
            candidate(12L, 1L, gross = 5_000L),
            candidate(14L, 1L, gross = 5_000L),
            candidate(20L, 40L, gross = 5_000L)
        )

        val ordered = InstallmentPlanReadModel.suggestionsFor(p, all).map { it.entryId to it.expenseId }

        assertEquals(
            listOf(1L to 12L, 1L to 14L, 1L to 10L, 1L to 11L, 2L to 20L),
            ordered
        )
    }

    @Test
    fun `INV-13 matches is currency amount and date only`() {
        val c = candidate(1L, 0L)
        assertTrue(
            InstallmentCandidateRule.matches(InstallmentFulfilmentMode.AUTO_RECORD, InstallmentCurrency.EUR, due, 5_000L, c)
        )
        assertFalse(
            InstallmentCandidateRule.matches(InstallmentFulfilmentMode.AUTO_RECORD, InstallmentCurrency.USD, due, 5_000L, c)
        )
        assertFalse(
            InstallmentCandidateRule.matches(InstallmentFulfilmentMode.AUTO_RECORD, InstallmentCurrency.EUR, due + 5L, 5_000L, c)
        )
        assertTrue(
            InstallmentCandidateRule.matches(InstallmentFulfilmentMode.CONFIRM, InstallmentCurrency.EUR, due + 5L, 5_000L, c)
        )
    }

    // ------------------------------------------------------------------ mayHaveCandidate

    @Test
    fun `mayHaveCandidate for auto record starts at due minus three`() {
        val e = entry(1L, dueDay = due)
        val mode = InstallmentFulfilmentMode.AUTO_RECORD

        assertFalse(InstallmentPlanReadModel.mayHaveCandidate(mode, e, due - 4L))
        assertTrue(InstallmentPlanReadModel.mayHaveCandidate(mode, e, due - 3L))
        assertTrue(InstallmentPlanReadModel.mayHaveCandidate(mode, e, due))
        assertTrue("window start only, later days still may have a candidate", InstallmentPlanReadModel.mayHaveCandidate(mode, e, due + 30L))
    }

    @Test
    fun `mayHaveCandidate for confirm starts at due minus five`() {
        val e = entry(1L, dueDay = due)
        val mode = InstallmentFulfilmentMode.CONFIRM

        assertFalse(InstallmentPlanReadModel.mayHaveCandidate(mode, e, due - 6L))
        assertTrue(InstallmentPlanReadModel.mayHaveCandidate(mode, e, due - 5L))
        assertTrue(InstallmentPlanReadModel.mayHaveCandidate(mode, e, due + 10L))
    }

    @Test
    fun `mayHaveCandidate is false for linked non scheduled and payoff entries`() {
        val mode = InstallmentFulfilmentMode.CONFIRM

        assertFalse(InstallmentPlanReadModel.mayHaveCandidate(mode, entry(1L, linkedExpenseId = 5L), due))
        assertFalse(InstallmentPlanReadModel.mayHaveCandidate(mode, entry(2L, status = InstallmentEntryStatus.PAID), due))
        assertFalse(
            InstallmentPlanReadModel.mayHaveCandidate(mode, entry(3L, status = InstallmentEntryStatus.PAID_BEFORE_TRACKING), due)
        )
        assertFalse(
            InstallmentPlanReadModel.mayHaveCandidate(mode, entry(4L, kind = InstallmentEntryKind.PAYOFF), due)
        )
    }
}
