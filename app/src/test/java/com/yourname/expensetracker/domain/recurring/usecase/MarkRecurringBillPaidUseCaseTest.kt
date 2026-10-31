package com.yourname.expensetracker.domain.recurring.usecase

import com.yourname.expensetracker.data.database.entity.ManualRecurringExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.repository.ManualRecurringExpenseRepository
import com.yourname.expensetracker.domain.model.RecurrenceFrequency
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator
import com.yourname.expensetracker.domain.recurring.lifecycle.SpecificLinkResult
import com.yourname.expensetracker.domain.sideeffect.MutationResult
import com.yourname.expensetracker.domain.sideeffect.PostCommitAction
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionBatch
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.CreateExpenseResult
import com.yourname.expensetracker.domain.transaction.DeduplicationMode
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * R21 S2: [MarkRecurringBillPaidUseCase] against an in-memory simulation of the
 * rule / occurrence / expense state (mockk answers), so ordering, locking and
 * idempotency are exercised without Room.
 */
class MarkRecurringBillPaidUseCaseTest {

    private val ruleRepo = mockk<ManualRecurringExpenseRepository>()
    private val occurrences = mockk<RecurringLifecycleCoordinator>()
    private val ruleCoordinator = mockk<RecurringRuleLifecycleCoordinator>()
    private val tlc = mockk<TransactionLifecycleCoordinator>()

    private val now = day(2026, 9, 19) + 12L * 3_600_000L
    private val timeProvider = object : TimeProvider {
        override fun now() = now
    }

    private val d1 = day(2026, 9, 19)
    private val d2 = day(2026, 10, 19)
    private val d3 = day(2026, 11, 19)

    private var ruleCurrency = "USD"
    private var ruleNextDate = d1
    private val store = mutableListOf<RecurringOccurrence>()
    private val createdByKey = mutableMapOf<String, Long>()
    private val createRequests = mutableListOf<CreateExpenseRequest>()
    private var ruleFrequency = RecurrenceFrequency.MONTHLY
    private var advanceFailures = 0
    private var failNextLink = false
    private var createResultOverride: CreateExpenseResult? = null
    private var linkResultOverride: SpecificLinkResult? = null
    private var cancelCallerAfterCreate = false
    private val advanceCalls = mutableListOf<Long>()
    private val callOrder = mutableListOf<String>()
    private val runner = mockk<PostCommitActionRunner>()
    private val plannedBatch = PostCommitActionBatch("test", listOf(mockk<PostCommitAction>()))

    private lateinit var useCase: MarkRecurringBillPaidUseCase

    @Before
    fun setup() {
        store.clear()
        listOf(d1, d2, d3).forEachIndexed { i, due ->
            store += RecurringOccurrence(
                id = (i + 1).toLong(),
                sourceType = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE,
                sourceId = RULE_ID,
                occurrenceKey = "RECURRING_RULE|$RULE_ID|$due|MONTHLY",
                dueDate = due,
                status = "PLANNED",
                expectedAmount = 12.5,
                expectedCurrency = "USD",
                frequency = "MONTHLY",
                merchant = "Gym"
            )
        }

        coEvery { ruleRepo.getById(RULE_ID) } answers {
            ManualRecurringExpense(
                id = RULE_ID,
                merchant = "Gym",
                amount = 12.5,
                currency = ruleCurrency,
                frequency = ruleFrequency,
                nextDate = ruleNextDate,
                categoryId = 7L,
                isActive = true
            )
        }
        coEvery { occurrences.getOccurrences(RULE_ID) } answers { store.sortedBy { it.dueDate } }
        coEvery { occurrences.getOccurrenceById(any()) } answers { store.firstOrNull { it.id == firstArg<Long>() } }
        coEvery { occurrences.linkExpenseToSpecificOccurrence(any(), any(), any()) } coAnswers {
            // yield() is a cancellation check: it throws if the caller's job is cancelled, so the
            // post-commit section only survives cancellation when it is NonCancellable.
            yield()
            callOrder += "link"
            if (failNextLink) {
                failNextLink = false
                throw IllegalStateException("secret-link-failure-detail")
            }
            linkResultOverride?.let { return@coAnswers it }
            replace(firstArg()) { it.copy(status = "PAID", linkedExpenseId = secondArg()) }
            SpecificLinkResult.Linked
        }
        coEvery { occurrences.skipOccurrence(any()) } answers {
            replace(firstArg()) { it.copy(status = "SKIPPED") }
        }
        coEvery { ruleCoordinator.topUpHorizon(RULE_ID, any()) } returns 0
        coEvery { ruleCoordinator.advanceNextDate(RULE_ID, any()) } answers {
            if (advanceFailures > 0) {
                advanceFailures--
                throw IllegalStateException("secret-advance-failure-detail")
            }
            advanceCalls += secondArg<Long>()
            ruleNextDate = secondArg()
        }
        // Suspending mock: yield() before the check-then-write so that, without the per-rule Mutex,
        // concurrent invokes would interleave and create duplicate expenses.
        coEvery { tlc.createExpenseDbOnlyV2(any()) } coAnswers {
            yield()
            callOrder += "create"
            val request = firstArg<CreateExpenseRequest>()
            createRequests += request
            val result = createResultOverride ?: run {
                val key = request.idempotencyKey!!
                createdByKey[key]?.let { CreateExpenseResult.DuplicateSkipped(it, "idempotent") }
                    ?: CreateExpenseResult.Created((createdByKey.size + 100).toLong()).also {
                        createdByKey[key] = it.expenseId
                    }
            }
            // Simulates the caller leaving the screen right after the expense commit returned.
            if (cancelCallerAfterCreate) currentCoroutineContext().job.cancel()
            MutationResult(result, plannedBatch)
        }
        coEvery { runner.run(any()) } coAnswers {
            yield()
            callOrder += "sideEffects"
            mockk(relaxed = true)
        }

        useCase = MarkRecurringBillPaidUseCase(ruleRepo, occurrences, ruleCoordinator, tlc, runner, timeProvider)
    }

    @Test
    fun `two quick invokes with the same seen date create one expense`() = runTest {
        val first = useCase.invoke(RULE_ID, d1)
        val second = useCase.invoke(RULE_ID, d1)

        assertTrue(first is MarkPaidResult.Paid)
        assertEquals(MarkPaidResult.AlreadyHandled, second)
        assertEquals(1, createRequests.size)
        assertEquals(d2, ruleNextDate)
        assertEquals("PAID", store.first { it.id == 1L }.status)
    }

    @Test
    fun `parallel invokes create exactly one expense`() = runTest {
        val results = (1..4).map { async { useCase.invoke(RULE_ID, d1) } }.awaitAll()

        assertEquals(1, results.count { it is MarkPaidResult.Paid })
        assertEquals(3, results.count { it == MarkPaidResult.AlreadyHandled })
        assertEquals(1, createRequests.size)
        assertEquals(d2, ruleNextDate)
    }

    @Test
    fun `retry after link failure reuses the idempotent expense`() = runTest {
        failNextLink = true
        val failed = useCase.invoke(RULE_ID, d1)

        assertTrue(failed is MarkPaidResult.Failed)
        assertFalse(failed.toString().contains("secret-link-failure-detail"))
        assertEquals(d1, ruleNextDate) // not advanced, so the retry is still valid

        val retried = useCase.invoke(RULE_ID, d1)

        assertTrue(retried is MarkPaidResult.Paid)
        assertEquals(1, createdByKey.size) // one persisted expense
        assertEquals(2, createRequests.size) // second attempt resolved to the existing row
        assertEquals(createRequests[0].idempotencyKey, createRequests[1].idempotencyKey)
        assertEquals(createdByKey.values.single(), (retried as MarkPaidResult.Paid).expenseId)
        assertEquals(d2, ruleNextDate)
        // The planned batch ran exactly once across both attempts (first: Created; retry: DuplicateSkipped).
        coVerify(exactly = 1) { runner.run(plannedBatch) }
    }

    @Test
    fun `next date later in the day still matches the midnight occurrence of that day`() = runTest {
        val seen = d1 + 14L * 3_600_000L
        ruleNextDate = seen

        val result = useCase.invoke(RULE_ID, seen)

        assertTrue(result is MarkPaidResult.Paid)
        assertEquals("expense is dated on the occurrence due date", d1, createRequests.single().date)
        assertEquals("PAID", store.first { it.id == 1L }.status)
        assertEquals(d2, ruleNextDate)
    }

    @Test
    fun `occurrence exactly at the next midnight is not targeted for the seen day`() = runTest {
        val nextMidnight = day(2026, 9, 20)
        store.clear()
        addOccurrence(10L, nextMidnight)

        val result = useCase.invoke(RULE_ID, d1)

        assertEquals(MarkPaidResult.AdvancedOnly, result)
        coVerify(exactly = 0) { tlc.createExpenseDbOnlyV2(any()) }
        assertEquals("PLANNED", store.first { it.id == 10L }.status)
        assertEquals(nextMidnight, ruleNextDate)
    }

    @Test
    fun `top up that creates the on day row on the first miss is then paid with one create`() = runTest {
        store.clear()
        coEvery { ruleCoordinator.topUpHorizon(RULE_ID, any()) } answers {
            if (store.none { it.dueDate == d1 }) addOccurrence(20L, d1)
            1
        }

        val result = useCase.invoke(RULE_ID, d1)

        assertTrue(result is MarkPaidResult.Paid)
        assertEquals(1, createRequests.size)
        assertEquals(d1, createRequests.single().date)
        assertEquals("PAID", store.first { it.id == 20L }.status)
        assertTrue(ruleNextDate > d1)
    }

    @Test
    fun `caller cancelled right after the commit still links and runs the post commit batch`() = runTest {
        cancelCallerAfterCreate = true

        val job = async { useCase.invoke(RULE_ID, d1) }
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(listOf("create", "link", "sideEffects"), callOrder)
        coVerify(exactly = 1) { runner.run(plannedBatch) }
        assertEquals("PAID", store.first { it.id == 1L }.status)
    }

    @Test
    fun `request uses rule currency strict idempotency and recurring source`() = runTest {
        useCase.invoke(RULE_ID, d1)

        val request = createRequests.single()
        assertEquals("USD", request.currency)
        assertEquals("Gym", request.merchant)
        assertEquals(12.5, request.amount, 0.0)
        assertEquals(7L, request.categoryId)
        assertEquals("expense is dated on the occurrence due date", d1, request.date)
        assertEquals(store.first { it.id == 1L }.dueDate, request.date)
        assertEquals(ExpenseSource.RECURRING_GENERATED, request.source)
        assertEquals(DeduplicationMode.STRICT_EXTERNAL_ID, request.deduplicationMode)
        assertEquals("recurring_paid:RECURRING_RULE|$RULE_ID|$d1|MONTHLY", request.idempotencyKey)
    }

    @Test
    fun `already paid target only advances the rule`() = runTest {
        store[0] = store[0].copy(status = "PAID", linkedExpenseId = 55L)

        val result = useCase.invoke(RULE_ID, d1)

        assertEquals(MarkPaidResult.AdvancedOnly, result)
        coVerify(exactly = 0) { tlc.createExpenseDbOnlyV2(any()) }
        assertEquals(d2, ruleNextDate)
    }

    @Test
    fun `missing occurrence triggers one top up then advances only`() = runTest {
        store.clear()

        val result = useCase.invoke(RULE_ID, d1)

        assertEquals(MarkPaidResult.AdvancedOnly, result)
        coVerify(atLeast = 1) { ruleCoordinator.topUpHorizon(RULE_ID, now) }
        coVerify(exactly = 0) { tlc.createExpenseDbOnlyV2(any()) }
    }

    @Test
    fun `already recorded skips the occurrence and creates no expense`() = runTest {
        val result = useCase.skip(RULE_ID, d1)

        assertEquals(MarkPaidResult.Skipped(1L), result)
        assertEquals("SKIPPED", store.first { it.id == 1L }.status)
        coVerify(exactly = 0) { tlc.createExpenseDbOnlyV2(any()) }
        assertEquals(d2, ruleNextDate)
        assertEquals(MarkPaidResult.AlreadyHandled, useCase.skip(RULE_ID, d1))
    }

    @Test
    fun `create failure returns bounded code and does not advance`() = runTest {
        createResultOverride = CreateExpenseResult.Error(RuntimeException("sql-secret-detail"))

        val result = useCase.invoke(RULE_ID, d1)

        assertEquals(MarkPaidResult.Failed(MarkPaidFailureCodes.CREATE_FAILED), result)
        assertFalse(result.toString().contains("sql-secret-detail"))
        assertEquals(d1, ruleNextDate)
    }

    private fun addOccurrence(id: Long, due: Long) {
        store += RecurringOccurrence(
            id = id,
            sourceType = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE,
            sourceId = RULE_ID,
            occurrenceKey = "RECURRING_RULE|$RULE_ID|$due|MONTHLY",
            dueDate = due,
            status = "PLANNED",
            expectedAmount = 12.5,
            expectedCurrency = "USD",
            frequency = "MONTHLY",
            merchant = "Gym"
        )
    }

    @Test
    fun `legacy stale planned rows before next date pay the seen occurrence and never move next date back`() =
        runTest {
            // Stale PLANNED rows months before nextDate (legacy rule): the bill the user saw is d2.
            addOccurrence(10L, day(2026, 6, 19))
            addOccurrence(11L, day(2026, 7, 19))
            ruleNextDate = d2

            val result = useCase.invoke(RULE_ID, d2)

            assertEquals(MarkPaidResult.Paid(100L), result)
            assertEquals("expense is dated on the seen occurrence", d2, createRequests.single().date)
            assertEquals("PAID", store.first { it.dueDate == d2 }.status)
            assertEquals("stale rows stay untouched", "PLANNED", store.first { it.id == 10L }.status)
            assertEquals(d3, ruleNextDate)
            assertTrue("nextDate never decreases", advanceCalls.all { it >= d2 })
        }

    @Test
    fun `stale planned rows without an on day occurrence create no expense and never move next date back`() =
        runTest {
            store.clear()
            addOccurrence(10L, day(2026, 6, 19))
            addOccurrence(11L, day(2026, 7, 19))
            addOccurrence(3L, d3)
            ruleNextDate = d2 // no occurrence on d2 itself, even after the (no-op) top up

            val result = useCase.invoke(RULE_ID, d2)

            assertEquals(MarkPaidResult.AdvancedOnly, result)
            coVerify(exactly = 0) { tlc.createExpenseDbOnlyV2(any()) }
            coVerify(atLeast = 1) { ruleCoordinator.topUpHorizon(RULE_ID, now) }
            assertEquals("stale rows stay untouched", "PLANNED", store.first { it.id == 10L }.status)
            assertEquals(d3, ruleNextDate)
            assertTrue("nextDate never decreases", advanceCalls.all { it > d2 })
        }

    @Test
    fun `only stale rows and a non advancing calculator report no change and leave next date`() = runTest {
        store.clear()
        addOccurrence(10L, day(2026, 6, 19))
        ruleFrequency = RecurrenceFrequency.IRREGULAR

        val result = useCase.invoke(RULE_ID, d1)

        assertEquals(MarkPaidResult.NoChange, result)
        coVerify(exactly = 0) { tlc.createExpenseDbOnlyV2(any()) }
        assertTrue(advanceCalls.isEmpty())
        assertEquals(d1, ruleNextDate)
    }

    @Test
    fun `on day open occurrence is paid and next date strictly increases`() = runTest {
        val result = useCase.invoke(RULE_ID, d1)

        assertTrue(result is MarkPaidResult.Paid)
        assertEquals("PAID", store.first { it.id == 1L }.status)
        assertTrue(ruleNextDate > d1)
        assertEquals(listOf(d2), advanceCalls)
    }

    @Test
    fun `on day skipped occurrence creates no expense and only advances`() = runTest {
        store[0] = store[0].copy(status = "SKIPPED")

        val result = useCase.invoke(RULE_ID, d1)

        assertEquals(MarkPaidResult.AdvancedOnly, result)
        coVerify(exactly = 0) { tlc.createExpenseDbOnlyV2(any()) }
        assertEquals(d2, ruleNextDate)
    }

    @Test
    fun `retry after the advance failed does not create a second expense even with stale planned rows`() =
        runTest {
            addOccurrence(10L, day(2026, 6, 19))
            addOccurrence(11L, day(2026, 7, 19))
            advanceFailures = 1

            val failed = useCase.invoke(RULE_ID, d1)

            assertEquals(MarkPaidResult.Failed(MarkPaidFailureCodes.INTERNAL), failed)
            assertFalse(failed.toString().contains("secret-advance-failure-detail"))
            assertEquals(1, createRequests.size)
            assertEquals("PAID", store.first { it.id == 1L }.status) // link succeeded
            assertEquals(d1, ruleNextDate) // advance did not happen

            val retried = useCase.invoke(RULE_ID, d1)

            assertEquals(MarkPaidResult.AdvancedOnly, retried)
            assertEquals("still exactly one create", 1, createRequests.size)
            assertEquals(1, createdByKey.size)
            assertEquals(d2, ruleNextDate)
            assertEquals("PLANNED", store.first { it.id == 10L }.status)
        }

    @Test
    fun `repeated taps create at most one expense per occurrence`() = runTest {
        advanceFailures = 3

        val results = (1..4).map { useCase.invoke(RULE_ID, d1) }

        assertEquals(3, results.count { it is MarkPaidResult.Failed })
        assertEquals(MarkPaidResult.AdvancedOnly, results.last())
        assertEquals(1, createRequests.size)
        assertEquals(d2, ruleNextDate)

        // The next occurrence is a distinct bill: exactly one more expense, with its own key.
        assertTrue(useCase.invoke(RULE_ID, d2) is MarkPaidResult.Paid)
        assertEquals(2, createRequests.size)
        assertEquals(2, createRequests.map { it.idempotencyKey }.toSet().size)
        assertEquals(MarkPaidResult.AlreadyHandled, useCase.invoke(RULE_ID, d2))
        assertEquals(2, createRequests.size)
    }

    @Test
    fun `created expense runs the planned post commit batch once after the link`() = runTest {
        val result = useCase.invoke(RULE_ID, d1)

        assertTrue(result is MarkPaidResult.Paid)
        coVerify(exactly = 1) { runner.run(plannedBatch) }
        assertEquals(listOf("create", "link", "sideEffects"), callOrder)
        coVerify(exactly = 0) { tlc.dispatchPostCreationSideEffects(any(), any(), any(), any()) }
    }

    @Test
    fun `duplicate skipped does not re-run side effects`() = runTest {
        createdByKey["recurring_paid:RECURRING_RULE|$RULE_ID|$d1|MONTHLY"] = 777L

        val result = useCase.invoke(RULE_ID, d1)

        assertEquals(MarkPaidResult.Paid(777L), result)
        coVerify(exactly = 0) { runner.run(any()) }
    }

    @Test
    fun `rejected link still runs side effects for the created expense`() = runTest {
        linkResultOverride = SpecificLinkResult.NotOpen

        val result = useCase.invoke(RULE_ID, d1)

        assertEquals(MarkPaidResult.Failed(MarkPaidFailureCodes.LINK_REJECTED), result)
        coVerify(exactly = 1) { runner.run(plannedBatch) }
        assertEquals(d1, ruleNextDate)
    }

    // Owner item 37(b): the coordinator refuses an UNKNOWN expense with the existing ineligible
    // outcome (SpecificLinkResult.ExpenseNotEligible). The use case reports it as the existing
    // LINK_REJECTED code and does not advance the rule. The coordinator side of the UNKNOWN refusal is
    // pinned with real Room in RecurringLifecycleCoordinatorInstallmentLinkPolicyTest.
    @Test
    fun `link refused as not eligible reports link rejected and does not advance`() = runTest {
        linkResultOverride = SpecificLinkResult.ExpenseNotEligible

        val result = useCase.invoke(RULE_ID, d1)

        assertEquals(MarkPaidResult.Failed(MarkPaidFailureCodes.LINK_REJECTED), result)
        assertEquals("PLANNED", store.first { it.id == 1L }.status)
        assertEquals(d1, ruleNextDate)
    }

    @Test
    fun `create failure runs no side effects`() = runTest {
        createResultOverride = CreateExpenseResult.Error(RuntimeException("x"))

        useCase.invoke(RULE_ID, d1)

        coVerify(exactly = 0) { runner.run(any()) }
    }

    private fun replace(id: Long, transform: (RecurringOccurrence) -> RecurringOccurrence) {
        val index = store.indexOfFirst { it.id == id }
        store[index] = transform(store[index])
    }

    companion object {
        private const val RULE_ID = 1L

        private fun day(y: Int, m: Int, d: Int): Long =
            LocalDate.of(y, m, d).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}
