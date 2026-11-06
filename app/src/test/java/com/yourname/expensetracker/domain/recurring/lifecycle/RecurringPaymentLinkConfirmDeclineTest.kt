package com.yourname.expensetracker.domain.recurring.lifecycle

import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.entity.PlannedExpense
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkTestFixture.Companion.MERCHANT
import com.yourname.expensetracker.domain.recurring.lifecycle.PaymentLinkTestFixture.Companion.TODAY
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.time.Duration.Companion.seconds

/**
 * FC-PAID-UNLINKED S2: [RecurringLifecycleCoordinator.confirmPaymentLink] and
 * [RecurringLifecycleCoordinator.declinePaymentLink].
 *
 * Invariants: INV-2 (one to one), INV-3 (single write path), INV-12 (confirm re-validates inside the
 * transaction, including the rule ownership of the occurrence), INV-13 (advance after confirm), INV-14
 * (decline), INV-16 (writes blocked during restore), INV-18 (privacy). Acceptance examples: AE-1, AE-2,
 * AE-14, AE-18, AE-19, AE-20, AE-27.
 *
 * Harness: [PaymentLinkTestFixture], a REAL in-memory Room database (so rollback is real), REAL
 * barriers, REAL coordinator. The rent fixture is rule "Rent", 400 EUR, monthly, due Nov 27, 2026,
 * today Nov 26, payment of 400 EUR on Nov 25. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class RecurringPaymentLinkConfirmDeclineTest {

    private lateinit var f: PaymentLinkTestFixture

    private val due: LocalDate = TODAY.plusDays(1) // Nov 27
    private val twoDaysEarly: LocalDate = TODAY.minusDays(1) // Nov 25

    @Before
    fun setup() {
        f = PaymentLinkTestFixture()
    }

    @After
    fun tearDown() {
        f.close()
    }

    private fun replaceFixture(replacement: PaymentLinkTestFixture) {
        f.close()
        f = replacement
    }

    private suspend fun seedRent(): Long {
        f.seedRule(1L)
        return f.seedOccurrence(1L, due)
    }

    /** Everything a refused or failed call must leave unchanged. */
    private data class Snapshot(val occurrences: List<RecurringOccurrence>, val eventCount: Int)

    private suspend fun snapshot() = Snapshot(f.allOccurrences(), f.allEventCount())

    private suspend fun assertNothingWritten(before: Snapshot) {
        assertEquals("occurrence rows changed", before.occurrences, f.allOccurrences())
        assertEquals("lifecycle events were written", before.eventCount, f.allEventCount())
    }

    // -- INV-12 / INV-3: confirm links ----------------------------------------------------------

    @Test
    fun `INV-12 AE-1 confirm links the suggested pair and the suggestion disappears`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            val suggested = f.suggestionItems().single()
            assertEquals(occ, suggested.occurrenceId) // before the confirm: still PLANNED and unlinked
            assertEquals("PLANNED", f.status(occ))
            assertNull(f.occurrence(occ).linkedExpenseId)

            val result = f.lifecycle.confirmPaymentLink(suggested.ruleId, suggested.expenseId, suggested.occurrenceId)

            assertEquals(PaymentLinkConfirmResult.Linked, result)
            val linked = f.occurrence(occ)
            assertEquals("PAID", linked.status)
            assertEquals(e, linked.linkedExpenseId)
            assertEquals(400.0, linked.paidAmount!!, 0.0)
            assertEquals("EUR", linked.paidCurrency)
            assertEquals(occ, f.linkedOccurrenceOf(e))
            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
        }

    @Test
    fun `INV-12 confirm writes one OCCURRENCE_PAID event with ids and amounts only`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)

            assertEquals(PaymentLinkConfirmResult.Linked, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            val paid = f.events(occ).filter { it.eventType == "OCCURRENCE_PAID" }
            assertEquals(1, paid.size)
            assertEquals("PLANNED", paid.single().oldStatus)
            assertEquals("PAID", paid.single().newStatus)
            val metadata = JSONObject(paid.single().metadata!!)
            assertEquals(e, metadata.getLong("expenseId"))
            assertEquals(400.0, metadata.getDouble("amount"), 0.0)
            assertEquals("EUR", metadata.getString("currency"))
        }

    @Test
    fun `INV-12 the provenance source of a confirmed link is user_confirmed_match`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            val key = f.occurrence(occ).occurrenceKey
            f.plannedDao.insertPlannedExpense(
                PlannedExpense(
                    description = "Rent",
                    amount = 400.0,
                    currency = "EUR",
                    date = f.startOf(due),
                    createdAt = f.clockNow,
                    sourceOccurrenceKey = key,
                    openSourceOccurrenceKey = key,
                    sourceRecurringRuleId = 1L,
                    merchantKey = MerchantKeyGenerator.generate(MERCHANT),
                    updatedAt = f.clockNow
                )
            )

            assertEquals(PaymentLinkConfirmResult.Linked, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            val planned = f.plannedDao.getBySourceOccurrenceKey(key)!!
            assertEquals("FULFILLED", planned.status)
            assertEquals(e, planned.linkedActualExpenseId)
            val fulfilled = f.events(occ).single { it.eventType == "PLANNED_FULFILLED" }
            assertEquals("user_confirmed_match", JSONObject(fulfilled.metadata!!).getString("source"))
            val suppression = f.events(occ).single { it.eventType.startsWith("REMINDER_SUPPRESS") }
            assertEquals("user_confirmed_match", JSONObject(suppression.metadata!!).getString("source"))
        }

    @Test
    fun `INV-18 confirm writes only ids amounts and bounded codes - no merchant or note text`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)

            assertEquals(PaymentLinkConfirmResult.Linked, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            val allowedKeys = setOf(
                "expenseId", "amount", "currency", "plannedExpenseId", "occurrenceKey", "source", "suppressedCount"
            )
            val events = f.events(occ)
            assertTrue(events.isNotEmpty())
            for (event in events) {
                val metadata = event.metadata ?: continue
                assertFalse("merchant text in ${event.eventType}", metadata.contains(MERCHANT, ignoreCase = true))
                val keys = JSONObject(metadata).keys().asSequence().toSet()
                assertTrue("unexpected keys $keys in ${event.eventType}", allowedKeys.containsAll(keys))
            }
        }

    @Test
    fun `INV-12 a confirm may target any open pair-eligible bill - the Different bill choice`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            f.seedRule(2L)
            val nearest = f.seedOccurrence(1L, TODAY.minusDays(2)) // Nov 24, |d| 2
            val other = f.seedOccurrence(2L, TODAY.plusDays(4)) // Nov 30, |d| 4
            val e = f.seedExpense(TODAY)

            assertEquals(PaymentLinkConfirmResult.Linked, f.lifecycle.confirmPaymentLink(2L, e, other))

            assertEquals(other, f.linkedOccurrenceOf(e))
            assertEquals("PAID", f.status(other))
            assertEquals("PLANNED", f.status(nearest))
        }

    @Test
    fun `INV-12 control - an edited expense that still matches can be confirmed`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            f.expenseDao.update(f.expenseDao.getById(e)!!.copy(amount = 430.0))

            assertEquals(PaymentLinkConfirmResult.Linked, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            assertEquals(430.0, f.occurrence(occ).paidAmount!!, 0.0)
        }

    // -- INV-13: advance after confirm ----------------------------------------------------------

    @Test
    fun `INV-13 AE-1 confirming the bill due on the rule's next date advances the rule to the next open bill`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L, nextDate = f.startOf(due))
            val occ = f.seedOccurrence(1L, due)
            val dec27 = LocalDate.of(2026, 12, 27)
            f.seedOccurrence(1L, dec27)
            val e = f.seedExpense(twoDaysEarly)

            assertEquals(PaymentLinkConfirmResult.Linked, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            coVerify(exactly = 1) { f.ruleCoordinator.advanceNextDate(1L, f.startOf(dec27)) }
        }

    @Test
    fun `INV-13 failing boundary - a bill that is not due on the rule's next date does not advance the rule`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent() // the rule's nextDate is parked years away
            val e = f.seedExpense(twoDaysEarly)

            assertEquals(PaymentLinkConfirmResult.Linked, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            coVerify(exactly = 0) { f.ruleCoordinator.advanceNextDate(any(), any()) }
        }

    @Test
    fun `INV-13 a failing advance never turns the committed link into a failure`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L, nextDate = f.startOf(due))
            val occ = f.seedOccurrence(1L, due)
            f.seedOccurrence(1L, LocalDate.of(2026, 12, 27))
            val e = f.seedExpense(twoDaysEarly)
            coEvery { f.ruleCoordinator.advanceNextDate(any(), any()) } throws IllegalStateException("secret-advance-detail")

            val result = f.lifecycle.confirmPaymentLink(RULE_ID, e, occ)

            assertEquals(PaymentLinkConfirmResult.Linked, result)
            assertEquals("PAID", f.status(occ))
            assertEquals(e, f.occurrence(occ).linkedExpenseId)
        }

    @Test
    fun `INV-13 a cancellation that lands after the claim does not skip the rule advance`() =
        runTest(timeout = 60.seconds) {
            var caller: Job? = null
            replaceFixture(
                PaymentLinkTestFixture(
                    eventWriterDecorator = { delegate ->
                        object : RecurringLifecycleEventWriter {
                            override suspend fun writeCritical(
                                occurrenceId: Long?,
                                eventType: String,
                                oldStatus: String?,
                                newStatus: String?,
                                metadata: String?,
                                occurredAt: Long
                            ): Long {
                                val id = delegate.writeCritical(
                                    occurrenceId, eventType, oldStatus, newStatus, metadata, occurredAt
                                )
                                // The caller is cancelled while the claim transaction is still finishing.
                                if (eventType == "OCCURRENCE_PAID") caller?.cancel()
                                return id
                            }
                        }
                    }
                )
            )
            f.seedRule(1L, nextDate = f.startOf(due))
            val occ = f.seedOccurrence(1L, due)
            val dec27 = LocalDate.of(2026, 12, 27)
            f.seedOccurrence(1L, dec27)
            val e = f.seedExpense(twoDaysEarly)

            val job = launch {
                try {
                    f.lifecycle.confirmPaymentLink(RULE_ID, e, occ)
                } catch (ex: CancellationException) {
                    // either outcome is fine: what matters is that claim and advance both completed
                }
            }
            caller = job
            job.join()

            assertEquals("PAID", f.status(occ))
            assertEquals(e, f.occurrence(occ).linkedExpenseId)
            coVerify(exactly = 1) { f.ruleCoordinator.advanceNextDate(1L, f.startOf(dec27)) }
        }

    @Test
    fun `INV-3 a caller that is already cancelled stops before the claim and writes nothing`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()
            var thrown: Throwable? = null

            val caller = launch {
                currentCoroutineContext().job.cancel()
                try {
                    f.lifecycle.confirmPaymentLink(RULE_ID, e, occ)
                } catch (ex: CancellationException) {
                    thrown = ex
                }
            }
            caller.join()

            assertTrue("a CancellationException must propagate", thrown is CancellationException)
            assertNothingWritten(before)
            assertEquals("PLANNED", f.status(occ))
            assertNull(f.linkedOccurrenceOf(e))
        }

    // -- AE-19: idempotency ---------------------------------------------------------------------

    @Test
    fun `AE-19 confirming the same pair twice is Linked then AlreadyLinkedToThis with no second write`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L, nextDate = f.startOf(due))
            val occ = f.seedOccurrence(1L, due)
            f.seedOccurrence(1L, LocalDate.of(2026, 12, 27))
            val e = f.seedExpense(twoDaysEarly)

            assertEquals(PaymentLinkConfirmResult.Linked, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))
            val afterFirst = snapshot()
            assertEquals(1, f.eventCount(occ, "OCCURRENCE_PAID"))

            assertEquals(PaymentLinkConfirmResult.AlreadyLinkedToThis, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            assertNothingWritten(afterFirst)
            assertEquals(1, f.eventCount(occ, "OCCURRENCE_PAID"))
            assertEquals(occ, f.linkedOccurrenceOf(e))
            coVerify(exactly = 1) { f.ruleCoordinator.advanceNextDate(any(), any()) }
        }

    // -- INV-12: NoLongerValid, nothing written ---------------------------------------------------

    @Test
    fun `AE-20 confirm after the expense was deleted is NoLongerValid and writes nothing`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            f.expenseDao.delete(f.expenseDao.getById(e)!!)
            val before = snapshot()

            assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            assertNothingWritten(before)
            assertEquals("PLANNED", f.status(occ))
        }

    @Test
    fun `AE-14 confirm for a bill already PAID by another expense is NoLongerValid and writes nothing`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            val first = f.seedExpense(due.minusDays(2)) // the payment that already owns the bill
            val occ = f.seedOccurrence(1L, due, status = "PAID", linkedExpenseId = first)
            val stale = f.seedExpense(twoDaysEarly) // a stale screen still offers this one
            val before = snapshot()

            assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, stale, occ))

            assertNothingWritten(before)
            assertEquals(first, f.occurrence(occ).linkedExpenseId)
            assertNull(f.linkedOccurrenceOf(stale))
        }

    @Test
    fun `INV-12 confirm for a SKIPPED bill is NoLongerValid and writes nothing`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L)
        val occ = f.seedOccurrence(1L, due, status = "SKIPPED")
        val e = f.seedExpense(twoDaysEarly)
        val before = snapshot()

        assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

        assertNothingWritten(before)
    }

    @Test
    fun `INV-12 failing boundary - an amount edited outside the tolerance makes the confirm NoLongerValid`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            f.expenseDao.update(f.expenseDao.getById(e)!!.copy(amount = 450.0))
            val before = snapshot()

            assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            assertNothingWritten(before)
        }

    @Test
    fun `INV-12 failing boundary - a date edited outside the window makes the confirm NoLongerValid`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            f.expenseDao.update(f.expenseDao.getById(e)!!.copy(date = f.at(LocalDate.of(2026, 11, 15)))) // d = -12
            val before = snapshot()

            assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            assertNothingWritten(before)
        }

    @Test
    fun `INV-12 failing boundary - a currency edit makes the confirm NoLongerValid`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            f.expenseDao.update(f.expenseDao.getById(e)!!.copy(currency = "GBP"))
            val before = snapshot()

            assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            assertNothingWritten(before)
        }

    @Test
    fun `INV-12 failing boundary - an expense turned into a transfer makes the confirm NoLongerValid`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            f.expenseDao.update(f.expenseDao.getById(e)!!.copy(transactionType = TransactionType.TRANSFER))
            val before = snapshot()

            assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            assertNothingWritten(before)
        }

    @Test
    fun `AE-27 confirm for a paused rule is NoLongerValid and writes nothing`() = runTest(timeout = 60.seconds) {
        f.seedRule(1L, active = false)
        val occ = f.seedOccurrence(1L, due)
        val e = f.seedExpense(twoDaysEarly)
        val before = snapshot()

        assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

        assertNothingWritten(before)
    }

    @Test
    fun `INV-14 confirm for a declined pair is NoLongerValid and writes nothing`() = runTest(timeout = 60.seconds) {
        val occ = seedRent()
        val e = f.seedExpense(twoDaysEarly)
        f.seedDecline(occ, """{"expenseId":$e,"source":"user_declined_match"}""")
        val before = snapshot()

        assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

        assertNothingWritten(before)
    }

    @Test
    fun `INV-12 confirm for an unknown occurrence or expense is NoLongerValid`() = runTest(timeout = 60.seconds) {
        val occ = seedRent()
        val e = f.seedExpense(twoDaysEarly)
        val before = snapshot()

        assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ + 1000L))
        assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, e + 1000L, occ))

        assertNothingWritten(before)
    }

    @Test
    fun `INV-12 confirm for a non-rule occurrence is NoLongerValid and writes nothing`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            val occ = f.seedOccurrence(1L, TODAY, sourceType = "SUBSCRIPTION")
            val e = f.seedExpense(TODAY)
            val before = snapshot()

            assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ))

            assertNothingWritten(before)
        }

    @Test
    fun `INV-12 confirm with a rule id that does not own the occurrence is NoLongerValid and writes nothing`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            f.seedRule(2L)
            val occ = f.seedOccurrence(1L, due)
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            assertEquals(PaymentLinkConfirmResult.NoLongerValid, f.lifecycle.confirmPaymentLink(2L, e, occ))

            assertNothingWritten(before)
            assertEquals("PLANNED", f.status(occ))
            assertNull(f.linkedOccurrenceOf(e))
            // control: the owning rule id links the same pair
            assertEquals(PaymentLinkConfirmResult.Linked, f.lifecycle.confirmPaymentLink(1L, e, occ))
        }

    // -- INV-2: one to one -------------------------------------------------------------------------

    @Test
    fun `INV-2 confirm for an expense linked to another bill is ExpenseLinkedElsewhere and writes nothing`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            f.seedRule(2L)
            val a = f.seedOccurrence(1L, due)
            val b = f.seedOccurrence(2L, due.plusDays(2)) // Nov 29, d = -4 from the Nov 25 payment
            val e = f.seedExpense(twoDaysEarly)
            assertEquals(
                SpecificLinkResult.Linked,
                f.lifecycle.linkExpenseToSpecificOccurrence(a, e, "direct_expense_link")
            )
            val before = snapshot()

            assertEquals(PaymentLinkConfirmResult.ExpenseLinkedElsewhere, f.lifecycle.confirmPaymentLink(2L, e, b))

            assertNothingWritten(before)
            assertEquals(a, f.linkedOccurrenceOf(e))
            assertEquals("PLANNED", f.status(b))
        }

    // -- INV-16 / AE-18: write barrier --------------------------------------------------------------

    @Test
    fun `AE-18 INV-16 confirm during every non-normal mode is Failed WRITE_BLOCKED and writes nothing`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            val blockedModes = RestoreMaintenanceMode.Mode.values().filter { it != RestoreMaintenanceMode.Mode.NORMAL }
            assertTrue(blockedModes.isNotEmpty())
            for (mode in blockedModes) {
                f.maintenanceMode = mode
                assertEquals(
                    "mode $mode",
                    PaymentLinkConfirmResult.Failed(PaymentLinkFailureCodes.WRITE_BLOCKED),
                    f.lifecycle.confirmPaymentLink(RULE_ID, e, occ)
                )
                assertNothingWritten(before)
            }

            f.maintenanceMode = RestoreMaintenanceMode.Mode.NORMAL
            assertEquals(PaymentLinkConfirmResult.Linked, f.lifecycle.confirmPaymentLink(RULE_ID, e, occ)) // not sticky
        }

    @Test
    fun `AE-18 INV-16 confirm with a mismatched rule id during a restore window is WRITE_BLOCKED not INTERNAL`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            f.seedRule(2L)
            val occ = f.seedOccurrence(1L, due)
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            val blockedModes = RestoreMaintenanceMode.Mode.values().filter { it != RestoreMaintenanceMode.Mode.NORMAL }
            assertTrue(blockedModes.isNotEmpty())
            for (mode in blockedModes) {
                f.maintenanceMode = mode
                assertEquals(
                    "mode $mode",
                    PaymentLinkConfirmResult.Failed(PaymentLinkFailureCodes.WRITE_BLOCKED),
                    f.lifecycle.confirmPaymentLink(2L, e, occ)
                )
                assertNothingWritten(before)
            }
        }

    // -- rollback and cancellation of confirm ---------------------------------------------------------

    @Test
    fun `INV-3 a failing event write rolls the whole confirm back and reports Failed INTERNAL`() =
        runTest(timeout = 60.seconds) {
            replaceFixture(
                PaymentLinkTestFixture(
                    eventWriterDecorator = failingWriterOn("OCCURRENCE_PAID") { IllegalStateException(SECRET) }
                )
            )
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            val result = f.lifecycle.confirmPaymentLink(RULE_ID, e, occ)

            assertEquals(PaymentLinkConfirmResult.Failed(PaymentLinkFailureCodes.INTERNAL), result)
            assertFalse(result.toString().contains(SECRET))
            assertNothingWritten(before)
            assertEquals("PLANNED", f.status(occ))
            assertNull(f.occurrence(occ).linkedExpenseId)
            assertNull(f.linkedOccurrenceOf(e))
        }

    @Test
    fun `INV-3 cancellation during the confirm transaction is rethrown and rolls the confirm back`() =
        runTest(timeout = 60.seconds) {
            replaceFixture(
                PaymentLinkTestFixture(
                    eventWriterDecorator = failingWriterOn("OCCURRENCE_PAID") { CancellationException("cancelled") }
                )
            )
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            try {
                f.lifecycle.confirmPaymentLink(RULE_ID, e, occ)
                fail("a CancellationException must propagate, but a result was returned")
            } catch (ex: CancellationException) {
                // expected: never mapped to Failed
            }

            assertNothingWritten(before)
            assertEquals("PLANNED", f.status(occ))
            assertNull(f.linkedOccurrenceOf(e))
        }

    // -- INV-14: decline -------------------------------------------------------------------------------

    @Test
    fun `INV-14 AE-2 decline writes exactly one LINK_SUGGESTION_DECLINED event with ids and a bounded source`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)

            assertEquals(PaymentLinkDeclineResult.Declined, f.lifecycle.declinePaymentLink(RULE_ID, e, occ))

            val declined = f.events(occ).filter { it.eventType == "LINK_SUGGESTION_DECLINED" }
            assertEquals(1, declined.size)
            assertNull(declined.single().oldStatus)
            assertNull(declined.single().newStatus)
            val metadata = JSONObject(declined.single().metadata!!)
            assertEquals(setOf("expenseId", "source"), metadata.keys().asSequence().toSet())
            assertEquals(e, metadata.getLong("expenseId"))
            assertEquals("user_declined_match", metadata.getString("source"))
            assertEquals(1, f.allEventCount())
        }

    @Test
    fun `INV-14 AE-2 a decline changes no occurrence status and the suggestion disappears`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            assertEquals(1, f.suggestionItems().size)
            val rowBefore = f.occurrence(occ)

            assertEquals(PaymentLinkDeclineResult.Declined, f.lifecycle.declinePaymentLink(RULE_ID, e, occ))

            assertEquals(rowBefore, f.occurrence(occ)) // PLANNED, unlinked, same snapshot
            assertEquals(emptyList<PaymentLinkSuggestion>(), f.suggestionItems())
            coVerify(exactly = 0) { f.ruleCoordinator.advanceNextDate(any(), any()) }
        }

    @Test
    fun `AE-19 declining the same pair twice is Declined then AlreadyDeclined with one event`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)

            assertEquals(PaymentLinkDeclineResult.Declined, f.lifecycle.declinePaymentLink(RULE_ID, e, occ))
            val afterFirst = snapshot()

            assertEquals(PaymentLinkDeclineResult.AlreadyDeclined, f.lifecycle.declinePaymentLink(RULE_ID, e, occ))

            assertNothingWritten(afterFirst)
            assertEquals(1, f.eventCount(occ, "LINK_SUGGESTION_DECLINED"))
        }

    @Test
    fun `INV-14 a decline is per pair - declining a second expense writes a second event`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val first = f.seedExpense(twoDaysEarly)
            val second = f.seedExpense(TODAY.minusDays(3), amount = 405.0)

            assertEquals(PaymentLinkDeclineResult.Declined, f.lifecycle.declinePaymentLink(RULE_ID, first, occ))
            assertEquals(PaymentLinkDeclineResult.Declined, f.lifecycle.declinePaymentLink(RULE_ID, second, occ))

            assertEquals(2, f.eventCount(occ, "LINK_SUGGESTION_DECLINED"))
        }

    @Test
    fun `INV-14 after a decline the next nearest bill is suggested`() = runTest(timeout = 60.seconds) {
        val nearest = seedRent() // Nov 27, |d| 2
        f.seedRule(2L)
        val next = f.seedOccurrence(2L, due.plusDays(2)) // Nov 29, |d| 4
        val e = f.seedExpense(twoDaysEarly)

        assertEquals(PaymentLinkDeclineResult.Declined, f.lifecycle.declinePaymentLink(RULE_ID, e, nearest))

        val s = f.suggestionItems().single()
        assertEquals(next, s.occurrenceId)
        assertEquals(e, s.expenseId)
    }

    @Test
    fun `INV-14 a declined pair is not auto-linked either`() = runTest(timeout = 60.seconds) {
        val occ = seedRent()
        val e = f.seedExpense(due) // on the due day: inside the auto window
        assertEquals(PaymentLinkDeclineResult.Declined, f.lifecycle.declinePaymentLink(RULE_ID, e, occ))

        assertFalse(f.lifecycle.linkExpenseToOccurrence(e))

        assertEquals("PLANNED", f.status(occ))
        assertNull(f.linkedOccurrenceOf(e))
    }

    @Test
    fun `INV-14 decline for an unknown occurrence or expense is NoLongerValid and writes nothing`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            assertEquals(PaymentLinkDeclineResult.NoLongerValid, f.lifecycle.declinePaymentLink(RULE_ID, e, occ + 1000L))
            assertEquals(PaymentLinkDeclineResult.NoLongerValid, f.lifecycle.declinePaymentLink(RULE_ID, e + 1000L, occ))

            assertNothingWritten(before)
        }

    @Test
    fun `INV-14 decline for a bill that is no longer open is NoLongerValid and writes nothing`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            f.seedRule(2L)
            val paidBy = f.seedExpense(due.minusDays(3))
            val paid = f.seedOccurrence(1L, due, status = "PAID", linkedExpenseId = paidBy)
            val skipped = f.seedOccurrence(2L, due.minusDays(1), status = "SKIPPED")
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            assertEquals(PaymentLinkDeclineResult.NoLongerValid, f.lifecycle.declinePaymentLink(1L, e, paid))
            assertEquals(PaymentLinkDeclineResult.NoLongerValid, f.lifecycle.declinePaymentLink(2L, e, skipped))

            assertNothingWritten(before)
        }

    @Test
    fun `INV-14 decline for a non-rule occurrence is NoLongerValid and writes nothing`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            val occ = f.seedOccurrence(1L, TODAY, sourceType = "SUBSCRIPTION")
            val e = f.seedExpense(TODAY)
            val before = snapshot()

            assertEquals(PaymentLinkDeclineResult.NoLongerValid, f.lifecycle.declinePaymentLink(RULE_ID, e, occ))

            assertNothingWritten(before)
        }

    @Test
    fun `INV-14 decline with a rule id that does not own the occurrence is NoLongerValid and writes nothing`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            f.seedRule(2L)
            val occ = f.seedOccurrence(1L, due)
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            assertEquals(PaymentLinkDeclineResult.NoLongerValid, f.lifecycle.declinePaymentLink(2L, e, occ))

            assertNothingWritten(before)
            assertEquals(0, f.eventCount(occ, "LINK_SUGGESTION_DECLINED"))
            // control: the owning rule id records the decline
            assertEquals(PaymentLinkDeclineResult.Declined, f.lifecycle.declinePaymentLink(1L, e, occ))
        }

    @Test
    fun `AE-18 INV-16 decline during every non-normal mode is Failed WRITE_BLOCKED and writes nothing`() =
        runTest(timeout = 60.seconds) {
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            val blockedModes = RestoreMaintenanceMode.Mode.values().filter { it != RestoreMaintenanceMode.Mode.NORMAL }
            assertTrue(blockedModes.isNotEmpty())
            for (mode in blockedModes) {
                f.maintenanceMode = mode
                assertEquals(
                    "mode $mode",
                    PaymentLinkDeclineResult.Failed(PaymentLinkFailureCodes.WRITE_BLOCKED),
                    f.lifecycle.declinePaymentLink(RULE_ID, e, occ)
                )
                assertNothingWritten(before)
            }

            f.maintenanceMode = RestoreMaintenanceMode.Mode.NORMAL
            assertEquals(PaymentLinkDeclineResult.Declined, f.lifecycle.declinePaymentLink(RULE_ID, e, occ)) // not sticky
        }

    @Test
    fun `AE-18 INV-16 decline with a mismatched rule id during a restore window is WRITE_BLOCKED not INTERNAL`() =
        runTest(timeout = 60.seconds) {
            f.seedRule(1L)
            f.seedRule(2L)
            val occ = f.seedOccurrence(1L, due)
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            val blockedModes = RestoreMaintenanceMode.Mode.values().filter { it != RestoreMaintenanceMode.Mode.NORMAL }
            assertTrue(blockedModes.isNotEmpty())
            for (mode in blockedModes) {
                f.maintenanceMode = mode
                assertEquals(
                    "mode $mode",
                    PaymentLinkDeclineResult.Failed(PaymentLinkFailureCodes.WRITE_BLOCKED),
                    f.lifecycle.declinePaymentLink(2L, e, occ)
                )
                assertNothingWritten(before)
            }
        }

    @Test
    fun `INV-14 a failing event write rolls the decline back and reports Failed INTERNAL`() =
        runTest(timeout = 60.seconds) {
            replaceFixture(
                PaymentLinkTestFixture(
                    eventWriterDecorator = failingWriterOn("LINK_SUGGESTION_DECLINED") { IllegalStateException(SECRET) }
                )
            )
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            val result = f.lifecycle.declinePaymentLink(RULE_ID, e, occ)

            assertEquals(PaymentLinkDeclineResult.Failed(PaymentLinkFailureCodes.INTERNAL), result)
            assertFalse(result.toString().contains(SECRET))
            assertNothingWritten(before)
            assertEquals(1, f.suggestionItems().size) // the suggestion is still there
        }

    @Test
    fun `INV-14 cancellation during the decline transaction is rethrown and writes nothing`() =
        runTest(timeout = 60.seconds) {
            replaceFixture(
                PaymentLinkTestFixture(
                    eventWriterDecorator = failingWriterOn("LINK_SUGGESTION_DECLINED") { CancellationException("cancelled") }
                )
            )
            val occ = seedRent()
            val e = f.seedExpense(twoDaysEarly)
            val before = snapshot()

            try {
                f.lifecycle.declinePaymentLink(RULE_ID, e, occ)
                fail("a CancellationException must propagate, but a result was returned")
            } catch (ex: CancellationException) {
                // expected: never mapped to Failed
            }

            assertNothingWritten(before)
        }

    private companion object {
        const val RULE_ID = 1L
        const val SECRET = "secret-sql-text /data/user/0/private.db"
    }
}
