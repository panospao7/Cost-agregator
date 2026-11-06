package com.yourname.expensetracker.domain.recurring.usecase

import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.repository.ManualRecurringExpenseRepository
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkConfirmResult
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkDeclineResult
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkFailureCodes
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkSuggestion
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkSuggestions
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkUnavailableReason
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringRuleLifecycleCoordinator
import com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner
import com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/**
 * FC-PAID-UNLINKED S2: the use case entry points [MarkRecurringBillPaidUseCase.findSuggestions],
 * [MarkRecurringBillPaidUseCase.linkExistingPayment] and [MarkRecurringBillPaidUseCase.declinePayment]
 * (INV-12, INV-14, INV-11, AE-28).
 *
 * The coordinator is a mock: the use case owns only the ownership check (the occurrence must be a
 * rule occurrence of the named rule), the per-rule [kotlinx.coroutines.sync.Mutex], the bounded
 * error mapping and the cancellation rule. The coordinator's own transactional behaviour is covered
 * by `RecurringPaymentLinkConfirmDeclineTest`. Ids are distinct on purpose so that a swapped
 * argument order fails.
 */
class MarkRecurringBillPaidUseCaseLinkTest {

    private val ruleRepo = mockk<ManualRecurringExpenseRepository>()
    private val occurrences = mockk<RecurringLifecycleCoordinator>()
    private val ruleCoordinator = mockk<RecurringRuleLifecycleCoordinator>()
    private val tlc = mockk<TransactionLifecycleCoordinator>()
    private val runner = mockk<PostCommitActionRunner>()

    private val now = 1_800_000_000_000L
    private val timeProvider = object : TimeProvider {
        override fun now() = now
    }

    private lateinit var useCase: MarkRecurringBillPaidUseCase

    /** Occurrences by id; the use case reads them through the coordinator only. */
    private val store = mutableMapOf<Long, RecurringOccurrence>()

    private var active = 0
    private var maxActive = 0

    @Before
    fun setup() {
        store.clear()
        store[OCC_ID] = occurrence(OCC_ID, RULE_ID)
        store[OTHER_OCC_ID] = occurrence(OTHER_OCC_ID, OTHER_RULE_ID)
        coEvery { occurrences.getOccurrenceById(any()) } answers { store[firstArg()] }
        useCase = MarkRecurringBillPaidUseCase(ruleRepo, occurrences, ruleCoordinator, tlc, runner, timeProvider)
    }

    private fun occurrence(
        id: Long,
        ruleId: Long,
        sourceType: String = RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE
    ) = RecurringOccurrence(
        id = id,
        sourceType = sourceType,
        sourceId = ruleId,
        occurrenceKey = "$sourceType|$ruleId|$id|MONTHLY",
        dueDate = 1_000L * id,
        status = "PLANNED",
        expectedAmount = 400.0,
        expectedCurrency = "EUR",
        frequency = "MONTHLY",
        merchant = "Acme Properties"
    )

    /** Marks a critical section as entered and leaves it after two scheduling points. */
    private suspend fun trackedCriticalSection() {
        active++
        if (active > maxActive) maxActive = active
        yield()
        yield()
        active--
    }

    // -- findSuggestions ------------------------------------------------------------------------

    @Test
    fun `findSuggestions reads at the injected clock and returns the coordinator result unchanged`() = runTest {
        val item = PaymentLinkSuggestion(
            occurrenceId = OCC_ID, ruleId = RULE_ID, occurrenceKey = "k", dueDate = 5L,
            expectedAmount = 400.0, expectedCurrency = "EUR", expenseId = EXPENSE_ID, expenseDate = 4L,
            paidAmount = 400.0, paidCurrency = "EUR", dayDelta = -2, alternatives = listOf(OTHER_OCC_ID)
        )
        val available = PaymentLinkSuggestions.Available(listOf(item))
        coEvery { occurrences.findPaymentLinkSuggestions(now) } returns available

        assertEquals(available, useCase.findSuggestions())

        coVerify(exactly = 1) { occurrences.findPaymentLinkSuggestions(now) }
    }

    @Test
    fun `findSuggestions passes Unavailable READ_BLOCKED through and never turns it into an empty list`() = runTest {
        val blocked = PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_BLOCKED)
        coEvery { occurrences.findPaymentLinkSuggestions(any()) } returns blocked

        assertEquals(blocked, useCase.findSuggestions())
    }

    @Test
    fun `findSuggestions maps an unexpected exception to Unavailable READ_FAILED without raw text`() = runTest {
        coEvery { occurrences.findPaymentLinkSuggestions(any()) } throws IllegalStateException(SECRET)

        val result = useCase.findSuggestions()

        assertEquals(PaymentLinkSuggestions.Unavailable(PaymentLinkUnavailableReason.READ_FAILED), result)
        assertFalse(result.toString().contains(SECRET))
    }

    @Test
    fun `findSuggestions rethrows cancellation instead of mapping it to Unavailable`() = runTest {
        coEvery { occurrences.findPaymentLinkSuggestions(any()) } throws CancellationException("cancelled")

        try {
            useCase.findSuggestions()
            fail("a CancellationException must propagate, but a result was returned")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }
    }

    // -- linkExistingPayment ---------------------------------------------------------------------

    @Test
    fun `INV-12 linkExistingPayment delegates to confirmPaymentLink with the expense and occurrence ids in order`() =
        runTest {
            coEvery { occurrences.confirmPaymentLink(any(), any()) } returns PaymentLinkConfirmResult.Linked

            val result = useCase.linkExistingPayment(RULE_ID, OCC_ID, EXPENSE_ID)

            assertEquals(PaymentLinkConfirmResult.Linked, result)
            coVerify(exactly = 1) { occurrences.confirmPaymentLink(EXPENSE_ID, OCC_ID) }
        }

    @Test
    fun `INV-12 linkExistingPayment returns every coordinator outcome unchanged`() = runTest {
        val outcomes = listOf(
            PaymentLinkConfirmResult.Linked,
            PaymentLinkConfirmResult.AlreadyLinkedToThis,
            PaymentLinkConfirmResult.NoLongerValid,
            PaymentLinkConfirmResult.ExpenseLinkedElsewhere,
            PaymentLinkConfirmResult.Failed(PaymentLinkFailureCodes.WRITE_BLOCKED),
            PaymentLinkConfirmResult.Failed(PaymentLinkFailureCodes.INTERNAL)
        )
        for (outcome in outcomes) {
            coEvery { occurrences.confirmPaymentLink(any(), any()) } returns outcome

            assertEquals(outcome, useCase.linkExistingPayment(RULE_ID, OCC_ID, EXPENSE_ID))
        }
    }

    @Test
    fun `INV-12 linkExistingPayment with a rule id that does not own the occurrence is NoLongerValid and never writes`() =
        runTest {
            coEvery { occurrences.confirmPaymentLink(any(), any()) } returns PaymentLinkConfirmResult.Linked

            val result = useCase.linkExistingPayment(OTHER_RULE_ID, OCC_ID, EXPENSE_ID)

            assertEquals(PaymentLinkConfirmResult.NoLongerValid, result)
            coVerify(exactly = 0) { occurrences.confirmPaymentLink(any(), any()) }
        }

    @Test
    fun `INV-12 linkExistingPayment for an unknown occurrence is NoLongerValid and never writes`() = runTest {
        coEvery { occurrences.confirmPaymentLink(any(), any()) } returns PaymentLinkConfirmResult.Linked

        val result = useCase.linkExistingPayment(RULE_ID, UNKNOWN_OCC_ID, EXPENSE_ID)

        assertEquals(PaymentLinkConfirmResult.NoLongerValid, result)
        coVerify(exactly = 0) { occurrences.confirmPaymentLink(any(), any()) }
    }

    @Test
    fun `INV-12 linkExistingPayment for a non-rule occurrence with the same source id is NoLongerValid`() = runTest {
        store[SUBSCRIPTION_OCC_ID] = occurrence(SUBSCRIPTION_OCC_ID, RULE_ID, sourceType = "SUBSCRIPTION")
        coEvery { occurrences.confirmPaymentLink(any(), any()) } returns PaymentLinkConfirmResult.Linked

        val result = useCase.linkExistingPayment(RULE_ID, SUBSCRIPTION_OCC_ID, EXPENSE_ID)

        assertEquals(PaymentLinkConfirmResult.NoLongerValid, result)
        coVerify(exactly = 0) { occurrences.confirmPaymentLink(any(), any()) }
    }

    @Test
    fun `INV-12 linkExistingPayment maps an unexpected exception to Failed INTERNAL without raw text`() = runTest {
        coEvery { occurrences.confirmPaymentLink(any(), any()) } throws IllegalStateException(SECRET)

        val result = useCase.linkExistingPayment(RULE_ID, OCC_ID, EXPENSE_ID)

        assertEquals(PaymentLinkConfirmResult.Failed(PaymentLinkFailureCodes.INTERNAL), result)
        assertFalse(result.toString().contains(SECRET))
    }

    @Test
    fun `INV-12 linkExistingPayment rethrows cancellation and releases the rule lock`() = runTest {
        coEvery { occurrences.confirmPaymentLink(any(), any()) } throws CancellationException("cancelled")

        try {
            useCase.linkExistingPayment(RULE_ID, OCC_ID, EXPENSE_ID)
            fail("a CancellationException must propagate, but a result was returned")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }

        coEvery { occurrences.confirmPaymentLink(any(), any()) } returns PaymentLinkConfirmResult.Linked
        assertEquals(PaymentLinkConfirmResult.Linked, useCase.linkExistingPayment(RULE_ID, OCC_ID, EXPENSE_ID))
    }

    // -- declinePayment --------------------------------------------------------------------------

    @Test
    fun `INV-14 declinePayment delegates to declinePaymentLink with the expense and occurrence ids in order`() =
        runTest {
            coEvery { occurrences.declinePaymentLink(any(), any()) } returns PaymentLinkDeclineResult.Declined

            val result = useCase.declinePayment(RULE_ID, OCC_ID, EXPENSE_ID)

            assertEquals(PaymentLinkDeclineResult.Declined, result)
            coVerify(exactly = 1) { occurrences.declinePaymentLink(EXPENSE_ID, OCC_ID) }
        }

    @Test
    fun `INV-14 declinePayment returns every coordinator outcome unchanged`() = runTest {
        val outcomes = listOf(
            PaymentLinkDeclineResult.Declined,
            PaymentLinkDeclineResult.AlreadyDeclined,
            PaymentLinkDeclineResult.NoLongerValid,
            PaymentLinkDeclineResult.Failed(PaymentLinkFailureCodes.WRITE_BLOCKED),
            PaymentLinkDeclineResult.Failed(PaymentLinkFailureCodes.INTERNAL)
        )
        for (outcome in outcomes) {
            coEvery { occurrences.declinePaymentLink(any(), any()) } returns outcome

            assertEquals(outcome, useCase.declinePayment(RULE_ID, OCC_ID, EXPENSE_ID))
        }
    }

    @Test
    fun `INV-14 declinePayment with a rule id that does not own the occurrence is NoLongerValid and never writes`() =
        runTest {
            coEvery { occurrences.declinePaymentLink(any(), any()) } returns PaymentLinkDeclineResult.Declined

            val result = useCase.declinePayment(OTHER_RULE_ID, OCC_ID, EXPENSE_ID)

            assertEquals(PaymentLinkDeclineResult.NoLongerValid, result)
            coVerify(exactly = 0) { occurrences.declinePaymentLink(any(), any()) }
        }

    @Test
    fun `INV-14 declinePayment for an unknown or non-rule occurrence is NoLongerValid and never writes`() = runTest {
        store[SUBSCRIPTION_OCC_ID] = occurrence(SUBSCRIPTION_OCC_ID, RULE_ID, sourceType = "SUBSCRIPTION")
        coEvery { occurrences.declinePaymentLink(any(), any()) } returns PaymentLinkDeclineResult.Declined

        assertEquals(PaymentLinkDeclineResult.NoLongerValid, useCase.declinePayment(RULE_ID, UNKNOWN_OCC_ID, EXPENSE_ID))
        assertEquals(
            PaymentLinkDeclineResult.NoLongerValid,
            useCase.declinePayment(RULE_ID, SUBSCRIPTION_OCC_ID, EXPENSE_ID)
        )

        coVerify(exactly = 0) { occurrences.declinePaymentLink(any(), any()) }
    }

    @Test
    fun `INV-14 declinePayment maps an unexpected exception to Failed INTERNAL without raw text`() = runTest {
        coEvery { occurrences.declinePaymentLink(any(), any()) } throws IllegalStateException(SECRET)

        val result = useCase.declinePayment(RULE_ID, OCC_ID, EXPENSE_ID)

        assertEquals(PaymentLinkDeclineResult.Failed(PaymentLinkFailureCodes.INTERNAL), result)
        assertFalse(result.toString().contains(SECRET))
    }

    @Test
    fun `INV-14 declinePayment rethrows cancellation and releases the rule lock`() = runTest {
        coEvery { occurrences.declinePaymentLink(any(), any()) } throws CancellationException("cancelled")

        try {
            useCase.declinePayment(RULE_ID, OCC_ID, EXPENSE_ID)
            fail("a CancellationException must propagate, but a result was returned")
        } catch (e: CancellationException) {
            assertEquals("cancelled", e.message)
        }

        coEvery { occurrences.declinePaymentLink(any(), any()) } returns PaymentLinkDeclineResult.Declined
        assertEquals(PaymentLinkDeclineResult.Declined, useCase.declinePayment(RULE_ID, OCC_ID, EXPENSE_ID))
    }

    // -- AE-28: the per-rule lock ------------------------------------------------------------------

    @Test
    fun `AE-28 concurrent confirms and declines of one rule never overlap`() = runTest {
        coEvery { occurrences.confirmPaymentLink(any(), any()) } coAnswers {
            trackedCriticalSection()
            PaymentLinkConfirmResult.Linked
        }
        coEvery { occurrences.declinePaymentLink(any(), any()) } coAnswers {
            trackedCriticalSection()
            PaymentLinkDeclineResult.Declined
        }

        listOf(
            async { useCase.linkExistingPayment(RULE_ID, OCC_ID, EXPENSE_ID) },
            async { useCase.declinePayment(RULE_ID, OCC_ID, EXPENSE_ID) },
            async { useCase.linkExistingPayment(RULE_ID, OCC_ID, EXPENSE_ID) }
        ).awaitAll()

        assertEquals("two writers of one rule ran at the same time", 1, maxActive)
    }

    @Test
    fun `AE-28 a confirm and a mark-paid of the same rule share the lock`() = runTest {
        coEvery { ruleRepo.getById(RULE_ID) } coAnswers {
            trackedCriticalSection()
            null // the mark-paid ends right away: RULE_NOT_FOUND
        }
        coEvery { occurrences.confirmPaymentLink(any(), any()) } coAnswers {
            trackedCriticalSection()
            PaymentLinkConfirmResult.Linked
        }

        listOf(
            async { useCase.linkExistingPayment(RULE_ID, OCC_ID, EXPENSE_ID) },
            async { useCase.invoke(RULE_ID, 1_000L) },
            async { useCase.skip(RULE_ID, 1_000L) }
        ).awaitAll()

        assertEquals("a confirm and a mark-paid ran at the same time", 1, maxActive)
    }

    @Test
    fun `AE-28 control - confirms of two different rules may run at the same time`() = runTest {
        coEvery { occurrences.confirmPaymentLink(any(), any()) } coAnswers {
            trackedCriticalSection()
            PaymentLinkConfirmResult.Linked
        }

        listOf(
            async { useCase.linkExistingPayment(RULE_ID, OCC_ID, EXPENSE_ID) },
            async { useCase.linkExistingPayment(OTHER_RULE_ID, OTHER_OCC_ID, EXPENSE_ID) }
        ).awaitAll()

        assertEquals("the probe must be able to observe overlap", 2, maxActive)
    }

    private companion object {
        const val RULE_ID = 9L
        const val OTHER_RULE_ID = 10L
        const val OCC_ID = 77L
        const val OTHER_OCC_ID = 88L
        const val SUBSCRIPTION_OCC_ID = 99L
        const val UNKNOWN_OCC_ID = 12345L
        const val EXPENSE_ID = 501L
        const val SECRET = "secret-sql-text /data/user/0/private.db"
    }
}
