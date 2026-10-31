package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.InstallmentPlanDao
import com.yourname.expensetracker.data.database.entity.InstallmentEntry
import com.yourname.expensetracker.data.database.entity.InstallmentPlan
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFacts
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseFactsReader
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentExpenseMoney
import com.yourname.expensetracker.domain.installment.lifecycle.InstallmentFlaggedExpense
import com.yourname.expensetracker.domain.installment.model.InstallmentDueState
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentAmount
import com.yourname.expensetracker.domain.installment.readmodel.InstallmentUnavailableReason
import com.yourname.expensetracker.domain.transaction.SpendingExclusionReason
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * [InstallmentPlanReadRepositoryImpl] over a mocked plan DAO (reads only) and a hand-written fake
 * [InstallmentExpenseFactsReader]. Covers INV-13 / 8.2 windows through the range query, INV-5
 * eligibility through ExpenseScheduleLinkPolicy, INV-17 (unavailable is never zero), fail-closed
 * decoding of stored codes and amounts, and the civil "today" from the injected TimeProvider.
 * Synthetic data only.
 */
class InstallmentPlanReadRepositoryImplTest {

    private val due = LocalDate.of(2027, 5, 10)
    private val dueDay = due.toEpochDay()

    private lateinit var originalZone: TimeZone
    private lateinit var planDao: InstallmentPlanDao
    private lateinit var reader: FakeFactsReader
    private var nowMillis = 0L
    private val timeProvider = object : TimeProvider {
        override fun now(): Long = nowMillis
    }
    private lateinit var repo: InstallmentPlanReadRepositoryImpl

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        planDao = mockk()
        reader = FakeFactsReader()
        nowMillis = noonUtc(due)
        repo = InstallmentPlanReadRepositoryImpl(
            planDao,
            reader,
            timeProvider,
            InstallmentCandidateChargeProbeImpl(reader, planDao)
        )
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    // ------------------------------------------------------------------ builders

    private class FakeFactsReader : InstallmentExpenseFactsReader {
        val money = mutableMapOf<Long, InstallmentExpenseMoney>()
        val facts = mutableMapOf<Long, InstallmentExpenseFacts>()
        val ranges = mutableListOf<Pair<Long, Long>>()

        override suspend fun findExpenseFacts(expenseId: Long): InstallmentExpenseFacts? = facts[expenseId]
        override suspend fun categoryExists(categoryId: Long): Boolean = false
        override suspend fun findFlaggedExpenses(): List<InstallmentFlaggedExpense> = emptyList()
        override suspend fun findExpenseMoney(expenseId: Long): InstallmentExpenseMoney? = money[expenseId]
        override suspend fun findExpenseMoneyBetween(
            startMillisInclusive: Long,
            endMillisExclusive: Long
        ): List<InstallmentExpenseMoney> {
            ranges += startMillisInclusive to endMillisExclusive
            return money.values
                .filter { it.dateMillis >= startMillisInclusive && it.dateMillis < endMillisExclusive }
                .sortedBy { it.expenseId }
        }
    }

    private fun noonUtc(day: LocalDate): Long = day.atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun startOfDayUtc(day: LocalDate): Long = day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun planRow(
        id: Long = 1L,
        status: String = "ACTIVE",
        currency: String = "EUR",
        mode: String = "CONFIRM",
        kind: String = "PURCHASE_ATTACHED",
        purchaseExpenseId: Long? = null,
        terminalAt: Long? = null,
        categoryId: Long? = null
    ) = InstallmentPlan(
        id = id,
        planUuid = "plan-uuid-$id",
        kind = kind,
        purchaseExpenseId = purchaseExpenseId,
        categoryId = categoryId,
        displayName = "Synthetic plan $id",
        currency = currency,
        splitMethod = "EQUAL_SPLIT",
        installmentCount = 12,
        frequency = "MONTHLY",
        firstDueDate = dueDay,
        anchorDayOfMonth = 10,
        residualPlacement = "LAST",
        fulfilmentMode = mode,
        declaredOnDate = dueDay,
        status = status,
        createdAt = 0L,
        updatedAt = 0L,
        terminalAt = terminalAt
    )

    private fun entryRow(
        id: Long,
        planId: Long = 1L,
        seq: Int = id.toInt(),
        dueDay: Long = this.dueDay,
        amount: Double = 50.0,
        status: String = "SCHEDULED",
        kind: String = "REGULAR",
        linkedExpenseId: Long? = null
    ) = InstallmentEntry(
        id = id,
        planId = planId,
        seq = seq,
        kind = kind,
        dueDate = dueDay,
        scheduledAmount = amount,
        status = status,
        linkedExpenseId = linkedExpenseId,
        statusChangedAt = 0L
    )

    private fun givenStore(plans: List<InstallmentPlan>, entries: List<InstallmentEntry>) {
        coEvery { planDao.getAllPlans() } returns plans
        coEvery { planDao.getPlanById(any()) } answers { plans.firstOrNull { p -> p.id == firstArg<Long>() } }
        coEvery { planDao.getEntriesForPlan(any()) } answers { entries.filter { e -> e.planId == firstArg<Long>() } }
        coEvery { planDao.getEntryByLinkedExpenseId(any()) } returns null
        coEvery { planDao.getPlanByPurchaseExpenseId(any()) } returns null
        every { planDao.observeAllPlans() } returns flowOf(plans)
        every { planDao.observeAllEntries() } returns flowOf(entries)
    }

    private fun money(
        id: Long,
        amount: Double = 50.0,
        effective: Double = amount,
        currency: String = "EUR",
        dateMillis: Long = noonUtc(due),
        categoryId: Long? = null
    ) = InstallmentExpenseMoney(id, currency, dateMillis, amount, effective, categoryId)

    private fun facts(
        id: Long,
        currency: String = "EUR",
        spending: Boolean = true,
        reason: String? = null,
        occurrence: Long? = null
    ) = InstallmentExpenseFacts(
        expenseId = id,
        currency = currency,
        dateMillis = 0L,
        isSpendingType = spending,
        isNotMine = false,
        isSharedExpense = false,
        hasSplit = false,
        spendingExclusionReason = reason,
        categoryId = null,
        paymentMethod = PaymentMethod.UNKNOWN,
        linkedRecurringOccurrenceId = occurrence
    )

    private fun addCandidate(id: Long, day: LocalDate = due, amount: Double = 50.0, currency: String = "EUR") {
        reader.money[id] = money(id, amount = amount, currency = currency, dateMillis = noonUtc(day))
        reader.facts[id] = facts(id, currency = currency)
    }

    private fun setZone(id: String) = TimeZone.setDefault(TimeZone.getTimeZone(id))

    // ------------------------------------------------------------------ fail-closed decoding

    @Test
    fun `unknown stored plan codes are counted unreadable and never shown`() = runTest {
        val plans = listOf(
            planRow(1L),
            planRow(2L, status = "BOGUS"),
            planRow(3L, currency = "GBP"),
            planRow(4L, mode = "BOGUS"),
            planRow(5L, kind = "BOGUS")
        )
        givenStore(plans, listOf(entryRow(1L, planId = 1L)))

        val overview = repo.loadOverview()

        assertEquals(listOf(1L), overview.plans.map { it.planId })
        assertEquals(4, overview.unreadablePlanCount)
    }

    @Test
    fun `an unknown stored entry code makes the whole plan unreadable and not a zero line`() = runTest {
        givenStore(
            listOf(planRow(1L)),
            listOf(entryRow(1L, status = "BOGUS"), entryRow(2L, amount = 50.0))
        )

        val overview = repo.loadOverview()

        assertTrue(overview.plans.isEmpty())
        assertEquals(1, overview.unreadablePlanCount)
        assertTrue("no EUR line may be invented", overview.remainingScheduledByCurrency.isEmpty())
    }

    @Test
    fun `an unknown stored entry kind makes the plan unreadable`() = runTest {
        givenStore(listOf(planRow(1L)), listOf(entryRow(1L, kind = "BOGUS")))

        assertEquals(1, repo.loadOverview().unreadablePlanCount)
    }

    @Test
    fun `an unconvertible scheduled amount is unreadable and never zero`() = runTest {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, 1_000_000_000.0)) {
            givenStore(listOf(planRow(1L)), listOf(entryRow(1L, amount = bad), entryRow(2L, amount = 50.0)))

            val overview = repo.loadOverview()

            assertEquals("amount $bad", 1, overview.unreadablePlanCount)
            assertTrue("amount $bad", overview.plans.isEmpty())
            assertTrue("amount $bad", overview.remainingScheduledByCurrency.isEmpty())
        }
    }

    @Test
    fun `an unreadable plan is omitted from detail and history`() = runTest {
        givenStore(
            listOf(planRow(1L, status = "BOGUS"), planRow(2L, status = "COMPLETED", terminalAt = noonUtc(due))),
            listOf(entryRow(1L, planId = 1L), entryRow(2L, planId = 2L, status = "PAID", linkedExpenseId = 900L))
        )
        reader.money[900L] = money(900L)

        assertNull(repo.loadDetail(1L))
        assertNull("missing plan id", repo.loadDetail(99L))
        assertEquals(listOf(2L), repo.loadHistory().map { it.planId })
    }

    @Test
    fun `a cancellation from the data source propagates and is not swallowed`() = runTest {
        coEvery { planDao.getAllPlans() } throws CancellationException("cancelled")

        try {
            repo.loadOverview()
            fail("CancellationException must propagate")
        } catch (expected: CancellationException) {
            // expected
        }
    }

    // ------------------------------------------------------------------ S10b-2 category carried for the E3 edit prefill

    @Test
    fun `loadDetail carries the stored plan categoryId for the edit prefill`() = runTest {
        givenStore(
            listOf(planRow(1L, kind = "STANDALONE", categoryId = 42L), planRow(2L, categoryId = 7L)),
            listOf(entryRow(1L, planId = 1L), entryRow(2L, planId = 2L))
        )

        assertEquals(42L, repo.loadDetail(1L)!!.categoryId)
        assertEquals("each plan carries its own category", 7L, repo.loadDetail(2L)!!.categoryId)
    }

    @Test
    fun `loadDetail keeps a missing plan categoryId as null and not a default id`() = runTest {
        givenStore(listOf(planRow(1L, categoryId = null)), listOf(entryRow(1L)))

        assertNull(repo.loadDetail(1L)!!.categoryId)
    }

    // ------------------------------------------------------------------ INV-17 linked money

    @Test
    fun `a paid entry with a missing linked expense is unavailable and not zero`() = runTest {
        givenStore(listOf(planRow(1L)), listOf(entryRow(1L, status = "PAID", linkedExpenseId = 900L)))

        val detail = repo.loadDetail(1L)!!

        assertEquals(
            InstallmentAmount.Unavailable(InstallmentUnavailableReason.MISSING_LINKED_EXPENSE),
            detail.entries.single().variance
        )
        assertEquals(
            InstallmentAmount.Unavailable(InstallmentUnavailableReason.MISSING_LINKED_EXPENSE),
            detail.summary.paidSoFar
        )
    }

    @Test
    fun `a paid entry whose linked amount cannot be converted is unavailable and not zero`() = runTest {
        givenStore(listOf(planRow(1L)), listOf(entryRow(1L, status = "PAID", linkedExpenseId = 900L)))
        reader.money[900L] = money(900L, amount = 50.0, effective = Double.NaN)

        val detail = repo.loadDetail(1L)!!

        assertEquals(
            InstallmentAmount.Unavailable(InstallmentUnavailableReason.MISSING_LINKED_EXPENSE),
            detail.entries.single().variance
        )
    }

    @Test
    fun `a paid entry linked to another currency reports drift and not zero`() = runTest {
        givenStore(listOf(planRow(1L)), listOf(entryRow(1L, status = "PAID", linkedExpenseId = 900L)))
        reader.money[900L] = money(900L, currency = "USD")

        val detail = repo.loadDetail(1L)!!

        assertEquals(
            InstallmentAmount.Unavailable(InstallmentUnavailableReason.LINKED_CURRENCY_DRIFT),
            detail.entries.single().variance
        )
    }

    @Test
    fun `variance and paid so far come from the linked expense effective amount`() = runTest {
        givenStore(listOf(planRow(1L)), listOf(entryRow(1L, status = "PAID", amount = 50.0, linkedExpenseId = 900L)))
        reader.money[900L] = money(900L, amount = 53.0, effective = 53.0)

        val detail = repo.loadDetail(1L)!!

        assertEquals(InstallmentAmount.Available(300L), detail.entries.single().variance)
        assertEquals(InstallmentAmount.Available(5_300L), detail.summary.paidSoFar)
    }

    @Test
    fun `a not mine linked expense has effective zero so variance is minus the schedule`() = runTest {
        givenStore(listOf(planRow(1L)), listOf(entryRow(1L, status = "PAID", amount = 50.0, linkedExpenseId = 900L)))
        reader.money[900L] = money(900L, amount = 50.0, effective = 0.0)

        val detail = repo.loadDetail(1L)!!

        assertEquals(InstallmentAmount.Available(-5_000L), detail.entries.single().variance)
        assertEquals(InstallmentAmount.Available(0L), detail.summary.paidSoFar)
    }

    @Test
    fun `financing cost uses the purchase money and is unavailable when the purchase is gone`() = runTest {
        givenStore(
            listOf(planRow(1L, purchaseExpenseId = 501L)),
            listOf(
                entryRow(1L, status = "PAID", amount = 50.0, linkedExpenseId = 900L),
                entryRow(2L, amount = 50.0)
            )
        )
        reader.money[900L] = money(900L, amount = 52.0)
        reader.money[501L] = money(501L, amount = 100.0)

        assertEquals(InstallmentAmount.Available(200L), repo.loadDetail(1L)!!.financingCost)

        reader.money.remove(501L)

        assertEquals(
            InstallmentAmount.Unavailable(InstallmentUnavailableReason.NO_PURCHASE),
            repo.loadDetail(1L)!!.financingCost
        )
    }

    // ------------------------------------------------------------------ S12b budget inputs

    @Test
    fun `loadBudgetInputs plumbs the expense date and category into the linked money`() = runTest {
        val chargeDate = noonUtc(due.plusDays(2))
        givenStore(
            listOf(planRow(1L, categoryId = 5L), planRow(2L, status = "BOGUS")),
            listOf(
                entryRow(1L, status = "PAID", amount = 50.0, linkedExpenseId = 900L),
                entryRow(2L, status = "PAID", amount = 40.0, linkedExpenseId = 901L),
                entryRow(3L, amount = 30.0, linkedExpenseId = 902L)
            )
        )
        reader.money[900L] = money(900L, amount = 53.0, effective = 52.0, dateMillis = chargeDate, categoryId = 9L)
        reader.money[901L] = money(901L, amount = 40.0, dateMillis = chargeDate, categoryId = null)
        reader.money[902L] = money(902L, categoryId = 3L)

        val inputs = repo.loadBudgetInputs()

        assertEquals("the BOGUS plan is counted unreadable, not listed", 1, inputs.unreadablePlanCount)
        val entries = inputs.plans.single().entries.associateBy { it.entryId }
        val paid = entries.getValue(1L).money.linkedExpense!!
        assertEquals(chargeDate, paid.dateMillis)
        assertEquals("the EXPENSE category, not the plan category 5", 9L, paid.categoryId)
        assertEquals(5_300L, paid.grossMinor)
        assertEquals(5_200L, paid.effectiveMinor)
        assertNull("an uncategorised expense stays null", entries.getValue(2L).money.linkedExpense!!.categoryId)
        assertNull("only PAID entries load linked money", entries.getValue(3L).money.linkedExpense)
    }

    // ------------------------------------------------------------------ history

    @Test
    fun `history end date for a cancelled plan is the civil date of terminalAt in the device zone`() = runTest {
        setZone("Pacific/Auckland")
        val midnight = ZonedDateTime.of(LocalDateTime.of(2027, 5, 11, 0, 0), ZoneId.of("Pacific/Auckland"))
        givenStore(
            listOf(
                planRow(1L, status = "CANCELLED", terminalAt = midnight.toInstant().toEpochMilli()),
                planRow(2L, status = "CANCELLED", terminalAt = midnight.toInstant().toEpochMilli() - 1L)
            ),
            listOf(entryRow(1L, planId = 1L, status = "CANCELLED"), entryRow(2L, planId = 2L, status = "CANCELLED"))
        )

        val byId = repo.loadHistory().associateBy { it.planId }

        assertEquals(LocalDate.of(2027, 5, 11).toEpochDay(), byId.getValue(1L).summary.endEpochDay)
        assertEquals(LocalDate.of(2027, 5, 10).toEpochDay(), byId.getValue(2L).summary.endEpochDay)
    }

    // ------------------------------------------------------------------ today (TimeProvider + zone)

    private fun dueStateTodayOf(dueDate: LocalDate): InstallmentDueState {
        givenStore(listOf(planRow(1L)), listOf(entryRow(1L, dueDay = dueDate.toEpochDay())))
        return kotlinx.coroutines.runBlocking { repo.loadOverview() }.plans.single().nextDue!!.dueState
    }

    @Test
    fun `today comes from the injected time provider in the device zone at the Auckland midnight boundary`() {
        setZone("Pacific/Auckland")
        val zone = ZoneId.of("Pacific/Auckland")
        val midnight = ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 0, 0), zone).toInstant().toEpochMilli()
        val target = LocalDate.of(2026, 10, 25)

        nowMillis = midnight
        assertEquals(InstallmentDueState.DUE, dueStateTodayOf(target))

        nowMillis = midnight - 1L
        assertEquals(InstallmentDueState.UPCOMING, dueStateTodayOf(target))
    }

    @Test
    fun `today stays the previous civil day late in the evening in Los Angeles`() {
        setZone("America/Los_Angeles")
        val zone = ZoneId.of("America/Los_Angeles")
        val target = LocalDate.of(2026, 10, 25)

        nowMillis = ZonedDateTime.of(LocalDateTime.of(2026, 10, 24, 23, 59, 59), zone).toInstant().toEpochMilli()
        assertEquals(InstallmentDueState.UPCOMING, dueStateTodayOf(target))

        nowMillis = ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 0, 0), zone).toInstant().toEpochMilli()
        assertEquals(InstallmentDueState.DUE, dueStateTodayOf(target))
    }

    @Test
    fun `today is correct on a daylight saving fall back day in Athens`() {
        setZone("Europe/Athens")
        val zone = ZoneId.of("Europe/Athens")
        val target = LocalDate.of(2026, 10, 25)

        nowMillis = ZonedDateTime.of(LocalDateTime.of(2026, 10, 25, 23, 30), zone).toInstant().toEpochMilli()
        assertEquals(InstallmentDueState.DUE, dueStateTodayOf(target))

        nowMillis = ZonedDateTime.of(LocalDateTime.of(2026, 10, 26, 0, 30), zone).toInstant().toEpochMilli()
        assertEquals(InstallmentDueState.OVERDUE, dueStateTodayOf(target))
    }

    // ------------------------------------------------------------------ suggestion range follows the mode

    @Test
    fun `confirm mode range query is due minus five through due plus ten and looks ahead after the due date`() = runTest {
        givenStore(listOf(planRow(1L, mode = "CONFIRM")), listOf(entryRow(1L)))
        nowMillis = noonUtc(due.plusDays(8))
        addCandidate(900L, day = due.plusDays(8))

        val detail = repo.loadDetail(1L)!!

        assertEquals(
            listOf(startOfDayUtc(due.minusDays(5)) to startOfDayUtc(due.plusDays(11))),
            reader.ranges
        )
        assertEquals(listOf(900L), detail.suggestions.map { it.expenseId })
    }

    @Test
    fun `auto record mode range query is due minus three through due and does not suggest at due plus five`() = runTest {
        givenStore(listOf(planRow(1L, mode = "AUTO_RECORD")), listOf(entryRow(1L)))
        nowMillis = noonUtc(due.plusDays(5))
        addCandidate(900L, day = due.plusDays(5))
        addCandidate(901L, day = due)

        val detail = repo.loadDetail(1L)!!

        assertEquals(
            listOf(startOfDayUtc(due.minusDays(3)) to startOfDayUtc(due.plusDays(1))),
            reader.ranges
        )
        assertEquals("only the in-window candidate", listOf(901L), detail.suggestions.map { it.expenseId })
    }

    @Test
    fun `no expense query happens before the window of the entry has started`() = runTest {
        givenStore(listOf(planRow(1L, mode = "AUTO_RECORD")), listOf(entryRow(1L)))
        nowMillis = noonUtc(due.minusDays(4))
        addCandidate(900L, day = due.minusDays(4))

        val detail = repo.loadDetail(1L)!!

        assertTrue(reader.ranges.isEmpty())
        assertTrue(detail.suggestions.isEmpty())
    }

    @Test
    fun `auto record window opens at due minus three`() = runTest {
        givenStore(listOf(planRow(1L, mode = "AUTO_RECORD")), listOf(entryRow(1L)))
        nowMillis = noonUtc(due.minusDays(3))
        addCandidate(900L, day = due.minusDays(3))

        assertEquals(listOf(900L), repo.loadDetail(1L)!!.suggestions.map { it.expenseId })
    }

    @Test
    fun `suggestion amount tolerance is one minor unit through the repository`() = runTest {
        givenStore(listOf(planRow(1L)), listOf(entryRow(1L, amount = 50.0)))
        addCandidate(901L, amount = 50.01)
        addCandidate(902L, amount = 49.99)
        addCandidate(903L, amount = 50.02)
        addCandidate(904L, amount = 49.98)

        assertEquals(listOf(901L, 902L), repo.loadDetail(1L)!!.suggestions.map { it.expenseId }.sorted())
    }

    @Test
    fun `a candidate in another currency is not suggested through the repository`() = runTest {
        givenStore(listOf(planRow(1L, currency = "EUR")), listOf(entryRow(1L)))
        addCandidate(900L, currency = "USD")

        assertTrue(repo.loadDetail(1L)!!.suggestions.isEmpty())
    }

    // ------------------------------------------------------------------ eligibility via ExpenseScheduleLinkPolicy

    private suspend fun suggestionCountWith(
        candidateFacts: InstallmentExpenseFacts?,
        linkedToEntry: InstallmentEntry? = null,
        purchaseOf: InstallmentPlan? = null
    ): Int {
        givenStore(listOf(planRow(1L)), listOf(entryRow(1L)))
        reader.money[900L] = money(900L)
        reader.facts.remove(900L)
        if (candidateFacts != null) reader.facts[900L] = candidateFacts
        coEvery { planDao.getEntryByLinkedExpenseId(900L) } returns linkedToEntry
        coEvery { planDao.getPlanByPurchaseExpenseId(900L) } returns purchaseOf
        return repo.loadDetail(1L)!!.suggestions.size
    }

    @Test
    fun `eligible candidate is suggested as the control`() = runTest {
        assertEquals(1, suggestionCountWith(facts(900L)))
    }

    @Test
    fun `a candidate that is not a spending type is not suggested`() = runTest {
        assertEquals(0, suggestionCountWith(facts(900L, spending = false)))
    }

    @Test
    fun `a candidate linked to another installment entry is not suggested`() = runTest {
        assertEquals(0, suggestionCountWith(facts(900L), linkedToEntry = entryRow(77L, planId = 2L, linkedExpenseId = 900L)))
    }

    @Test
    fun `a candidate that is a plan purchase record is not suggested`() = runTest {
        assertEquals(0, suggestionCountWith(facts(900L), purchaseOf = planRow(9L, purchaseExpenseId = 900L)))
    }

    @Test
    fun `a candidate flagged as an installment purchase is not suggested`() = runTest {
        assertEquals(
            0,
            suggestionCountWith(facts(900L, reason = SpendingExclusionReason.INSTALLMENT_PURCHASE.name))
        )
    }

    @Test
    fun `a candidate linked to a recurring occurrence is not suggested`() = runTest {
        assertEquals(0, suggestionCountWith(facts(900L, occurrence = 5L)))
    }

    @Test
    fun `a candidate whose facts cannot be read is not suggested`() = runTest {
        assertEquals(0, suggestionCountWith(null))
    }

    // ------------------------------------------------------------------ loadSuggestions and flows

    @Test
    fun `loadSuggestions covers active plans only and skips unreadable plans`() = runTest {
        givenStore(
            listOf(
                planRow(1L, mode = "CONFIRM"),
                planRow(2L, status = "COMPLETED"),
                planRow(3L, status = "BOGUS")
            ),
            listOf(
                entryRow(1L, planId = 1L),
                entryRow(2L, planId = 2L),
                entryRow(3L, planId = 3L)
            )
        )
        addCandidate(900L)

        val result = repo.loadSuggestions()

        assertEquals(listOf(1L), result.map { it.planId })
        assertEquals(listOf(900L), result.map { it.expenseId })
    }

    @Test
    fun `observeOverview emits the overview when the plan and entry tables emit`() = runTest {
        givenStore(listOf(planRow(1L)), listOf(entryRow(1L, dueDay = dueDay + 3L)))

        val overview = repo.observeOverview().first()

        assertEquals(listOf(1L), overview.plans.map { it.planId })
        assertEquals(InstallmentPlanStatus.ACTIVE, overview.plans.single().status)
    }

    @Test
    fun `PE-C observeChanges emits on a plan change and on an entry change and loads nothing`() = runTest {
        val plans = MutableSharedFlow<List<InstallmentPlan>>(replay = 1)
        val entries = MutableSharedFlow<List<InstallmentEntry>>(replay = 1)
        every { planDao.observeAllPlans() } returns plans
        every { planDao.observeAllEntries() } returns entries
        val emissions = Channel<Unit>(Channel.UNLIMITED)
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            repo.observeChanges().collect { emissions.send(it) }
        }

        plans.emit(listOf(planRow(1L)))
        entries.emit(listOf(entryRow(1L)))
        runCurrent()
        assertNotNull("first combined emission", emissions.tryReceive().getOrNull())

        // A plan row change.
        plans.emit(listOf(planRow(1L), planRow(2L)))
        runCurrent()
        assertNotNull("emits on a plan change", emissions.tryReceive().getOrNull())

        // An entry row change.
        entries.emit(listOf(entryRow(1L), entryRow(2L, planId = 2L)))
        runCurrent()
        assertNotNull("emits on an entry change", emissions.tryReceive().getOrNull())

        job.cancel()
        coVerify(exactly = 0) { planDao.getAllPlans() }
        coVerify(exactly = 0) { planDao.getPlanById(any()) }
        coVerify(exactly = 0) { planDao.getEntriesForPlan(any()) }
        assertTrue("no expense money was read", reader.ranges.isEmpty())
    }

    @Test
    fun `observeDetail emits null for a missing plan and a detail for an existing one`() = runTest {
        givenStore(listOf(planRow(1L)), listOf(entryRow(1L)))

        assertNull(repo.observeDetail(99L).first())
        assertNotNull(repo.observeDetail(1L).first())
    }
}
