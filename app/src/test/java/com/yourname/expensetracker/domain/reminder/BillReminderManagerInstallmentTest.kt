package com.yourname.expensetracker.domain.reminder

import com.yourname.expensetracker.data.repository.MultiCurrencyRepository
import com.yourname.expensetracker.data.repository.RecurringExpenseRepository
import com.yourname.expensetracker.domain.currency.CurrencySettingsRepository
import com.yourname.expensetracker.domain.installment.model.InstallmentCurrency
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryKind
import com.yourname.expensetracker.domain.installment.model.InstallmentEntryStatus
import com.yourname.expensetracker.domain.installment.model.InstallmentPlanStatus
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectedEntry
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionPort
import com.yourname.expensetracker.domain.installment.projection.InstallmentProjectionRule
import com.yourname.expensetracker.domain.installment.projection.ProjectionEntryInput
import com.yourname.expensetracker.domain.installment.projection.ProjectionPlanInput
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * S7b: installment reminders through the existing [BillReminderManager] (INV-15, INV-31; AE-7,
 * AE-11, AE-31). The fake port applies the real [InstallmentProjectionRule], so the bounds
 * (SCHEDULED entries of ACTIVE plans due today or later) are exercised end to end. The real
 * two-phase ledger semantics are covered in InstallmentReminderLedgerImplTest; the ledger here is a
 * recording fake used to observe which manager call touched it.
 */
class BillReminderManagerInstallmentTest {

    private val today: LocalDate = LocalDate.of(2027, 6, 10)
    private val todayEpochDay: Long = today.toEpochDay()
    private val nowMillis: Long = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val timeProvider = mockk<TimeProvider>()
    private val port = FakeProjectionPort()
    private val ledger = FakeLedger()
    private val settingsRepository = FakeSettingsRepository()

    private lateinit var manager: BillReminderManager

    @Before
    fun setUp() {
        every { timeProvider.now() } returns nowMillis
        port.todayEpochDay = todayEpochDay
        manager = BillReminderManager(
            mockk<RecurringExpenseRepository>(relaxed = true),
            timeProvider,
            mockk<MultiCurrencyRepository>(relaxed = true),
            mockk<CurrencySettingsRepository>(relaxed = true),
            port,
            ledger,
            settingsRepository
        )
    }

    private fun entry(
        id: Long,
        seq: Int,
        dueOffsetDays: Long,
        status: InstallmentEntryStatus = InstallmentEntryStatus.SCHEDULED,
        generation: Int = 0,
        minor: Long = 5000L
    ) = ProjectionEntryInput(
        entryId = id,
        seq = seq,
        kind = InstallmentEntryKind.REGULAR,
        status = status,
        dueEpochDay = todayEpochDay + dueOffsetDays,
        scheduledMinor = minor,
        recordGeneration = generation
    )

    private fun plan(
        id: Long,
        uuid: String,
        name: String = "Demo Electronics",
        status: InstallmentPlanStatus = InstallmentPlanStatus.ACTIVE,
        entries: List<ProjectionEntryInput> = emptyList()
    ) = ProjectionPlanInput(id, uuid, name, InstallmentCurrency.EUR, status, entries)

    @Test
    fun `reminds only SCHEDULED entries of ACTIVE plans due today or later within the window`() = runTest {
        port.plans = listOf(
            plan(
                1L, "plan-a", "Demo Electronics", InstallmentPlanStatus.ACTIVE,
                listOf(
                    entry(11, 1, dueOffsetDays = 1),
                    entry(12, 2, dueOffsetDays = 0),
                    entry(13, 3, dueOffsetDays = 1, status = InstallmentEntryStatus.PAID),
                    entry(14, 4, dueOffsetDays = 1, status = InstallmentEntryStatus.SKIPPED),
                    entry(15, 5, dueOffsetDays = -1),
                    entry(16, 6, dueOffsetDays = BillReminderManager.DEFAULT_REMINDER_DAYS + 7L)
                )
            ),
            plan(2L, "plan-b", status = InstallmentPlanStatus.CANCELLED, entries = listOf(entry(21, 1, 1))),
            plan(3L, "plan-c", status = InstallmentPlanStatus.COMPLETED, entries = listOf(entry(31, 1, 1))),
            plan(4L, "plan-d", status = InstallmentPlanStatus.PAID_OFF, entries = listOf(entry(41, 1, 1)))
        )

        val due = manager.getDueInstallmentReminders()

        assertEquals(listOf("plan-a:2:0", "plan-a:1:0"), due.map { it.dedupeKey })
        assertTrue(due.all { it.daysUntilDue in 0..BillReminderManager.DEFAULT_REMINDER_DAYS })
    }

    @Test
    fun `nothing is reminded after Stop (AE-7)`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        assertEquals(1, manager.getDueInstallmentReminders().size)

        port.plans = listOf(
            plan(1L, "plan-a", status = InstallmentPlanStatus.CANCELLED, entries = listOf(
                entry(11, 1, 1, status = InstallmentEntryStatus.CANCELLED)
            ))
        )

        assertTrue(manager.getDueInstallmentReminders().isEmpty())
    }

    @Test
    fun `nothing is reminded after completion even when a stale SCHEDULED entry remains (AE-11)`() = runTest {
        // The vacuous variant (a PAID entry) is excluded by the entry-status rule alone. Here the plan
        // is COMPLETED but still carries a SCHEDULED-looking entry inside the window, so only the
        // plan-status bound of the projection can exclude it.
        port.plans = listOf(
            plan(
                1L, "plan-a", status = InstallmentPlanStatus.COMPLETED,
                entries = listOf(entry(11, 1, 1), entry(12, 2, 0))
            )
        )

        val due = manager.getDueInstallmentReminders()

        assertTrue(due.isEmpty())
        // The ledger was never asked about, claimed or marked anything (prune on read is allowed).
        assertEquals(listOf("prune"), ledger.calls)
        assertTrue(ledger.released.isEmpty())
    }

    @Test
    fun `a plan completed between listing and claim yields no reminder and keeps no claim (AE-11)`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        val reminder = manager.getDueInstallmentReminders().single()

        port.plans = listOf(
            plan(1L, "plan-a", status = InstallmentPlanStatus.COMPLETED, entries = listOf(entry(11, 1, 1)))
        )

        assertNull(manager.claimInstallmentReminder(reminder))
        assertEquals(listOf(reminder.dedupeKey), ledger.released)
        assertFalse(ledger.isClaimed(reminder.dedupeKey, reminder.dueEpochDay, nowMillis))
    }

    @Test
    fun `an entry is reminded once per planUuid seq recordGeneration`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1, generation = 0))))
        val first = manager.getDueInstallmentReminders().single()
        assertEquals("plan-a:1:0", first.dedupeKey)

        assertNotNull(manager.claimInstallmentReminder(first))
        assertNull(manager.claimInstallmentReminder(first))
        assertTrue(manager.getDueInstallmentReminders().isEmpty())

        // A re-recorded entry (new recordGeneration) is a different reminder.
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1, generation = 1))))
        assertEquals(listOf("plan-a:1:1"), manager.getDueInstallmentReminders().map { it.dedupeKey })
    }

    @Test
    fun `a released claim can be reminded again`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        val reminder = manager.getDueInstallmentReminders().single()
        val claimed = manager.claimInstallmentReminder(reminder)!!

        manager.releaseInstallmentReminder(claimed)

        assertEquals(1, manager.getDueInstallmentReminders().size)
    }

    @Test
    fun `markInstallmentReminderSent records the key and due day with the current time and reports the write result`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        val claimed = manager.claimInstallmentReminder(manager.getDueInstallmentReminders().single())!!

        assertTrue(manager.markInstallmentReminderSent(claimed))
        assertEquals(listOf(Triple("plan-a:1:0", claimed.dueEpochDay, nowMillis)), ledger.markSentCalls)

        ledger.markSentResult = false
        assertFalse(manager.markInstallmentReminderSent(claimed))
        // A sent key is never listed again, a failed write is reported but not retried here.
        assertTrue(manager.getDueInstallmentReminders().isEmpty())
    }

    @Test
    fun `quiet hours and disabled reminders suppress installment reminders`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        val reminder = manager.getDueInstallmentReminders().single()

        settingsRepository.settings = BillReminderSettings(
            quietHoursEnabled = true,
            quietHoursStartMinuteOfDay = 11 * 60,
            quietHoursEndMinuteOfDay = 13 * 60
        )
        assertTrue(manager.getDueInstallmentReminders().isEmpty())
        assertNull(manager.claimInstallmentReminder(reminder))
        assertFalse(ledger.isClaimed(reminder.dedupeKey, reminder.dueEpochDay, nowMillis))

        settingsRepository.settings = BillReminderSettings(billRemindersEnabled = false)
        assertTrue(manager.getDueInstallmentReminders().isEmpty())

        // Outside the quiet window the same reminder is delivered.
        settingsRepository.settings = BillReminderSettings(
            quietHoursEnabled = true,
            quietHoursStartMinuteOfDay = 22 * 60,
            quietHoursEndMinuteOfDay = 8 * 60
        )
        assertEquals(1, manager.getDueInstallmentReminders().size)
    }

    @Test
    fun `an entry paid or linked after listing is revalidated away and its claim released`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        val reminder = manager.getDueInstallmentReminders().single()

        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1, status = InstallmentEntryStatus.PAID))))

        assertNull(manager.claimInstallmentReminder(reminder))
        assertEquals(listOf(reminder.dedupeKey), ledger.released)
        assertFalse(ledger.isClaimed(reminder.dedupeKey, reminder.dueEpochDay, nowMillis))
    }

    @Test
    fun `a plan stopped between listing and posting yields no reminder`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        val reminder = manager.getDueInstallmentReminders().single()

        port.plans = listOf(plan(1L, "plan-a", status = InstallmentPlanStatus.CANCELLED, entries = listOf(
            entry(11, 1, 1, status = InstallmentEntryStatus.CANCELLED)
        )))

        assertNull(manager.claimInstallmentReminder(reminder))
        assertEquals(listOf(reminder.dedupeKey), ledger.released)
    }

    @Test
    fun `a bumped recordGeneration after listing invalidates the claimed reminder`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1, generation = 0))))
        val reminder = manager.getDueInstallmentReminders().single()

        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1, generation = 1))))

        assertNull(manager.claimInstallmentReminder(reminder))
        assertEquals(listOf(reminder.dedupeKey), ledger.released)
        assertFalse(ledger.isClaimed(reminder.dedupeKey, reminder.dueEpochDay, nowMillis))
    }

    // --- Due day moved between listing and the claim (E3 edit does not bump recordGeneration) ---

    @Test
    fun `a due day moved between listing and claim releases the claim and yields null`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, dueOffsetDays = 1))))
        val reminder = manager.getDueInstallmentReminders().single()

        // Same planUuid, seq and recordGeneration, still SCHEDULED, but now due a day later.
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, dueOffsetDays = 2))))

        assertNull(manager.claimInstallmentReminder(reminder))
        assertEquals(listOf(reminder.dedupeKey), ledger.released)
        assertFalse(ledger.isClaimed(reminder.dedupeKey, reminder.dueEpochDay, nowMillis))

        // The next run lists the entry under its new due day.
        val relisted = manager.getDueInstallmentReminders().single()
        assertEquals(reminder.dedupeKey, relisted.dedupeKey)
        assertEquals(todayEpochDay + 2, relisted.dueEpochDay)
    }

    // --- Revalidation throw releases the claim and rethrows (ISSUE-2) ---

    @Test
    fun `a projection port throw during revalidation releases the claim and rethrows the same failure`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        val reminder = manager.getDueInstallmentReminders().single()
        val boom = IllegalStateException("synthetic port failure")
        port.failure = boom
        port.failureFromCall = port.calls + 1

        val thrown = runCatching { manager.claimInstallmentReminder(reminder) }.exceptionOrNull()

        assertCarries(boom, thrown)
        assertEquals(listOf(reminder.dedupeKey), ledger.released)
        assertFalse(ledger.isClaimed(reminder.dedupeKey, reminder.dueEpochDay, nowMillis))

        // Not suppressed: once the port recovers the reminder is listed again.
        port.failure = null
        assertEquals(listOf(reminder.dedupeKey), manager.getDueInstallmentReminders().map { it.dedupeKey })
    }

    @Test
    fun `a CancellationException during revalidation releases the claim and propagates`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        val reminder = manager.getDueInstallmentReminders().single()
        val cancelled = CancellationException("synthetic cancellation")
        port.failure = cancelled
        port.failureFromCall = port.calls + 1

        val thrown = runCatching { manager.claimInstallmentReminder(reminder) }.exceptionOrNull()

        assertTrue(thrown is CancellationException)
        assertEquals(listOf(reminder.dedupeKey), ledger.released)
        assertFalse(ledger.isClaimed(reminder.dedupeKey, reminder.dueEpochDay, nowMillis))
    }

    @Test
    fun `a settings read throw during revalidation releases the claim and rethrows`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        val reminder = manager.getDueInstallmentReminders().single()
        val boom = IllegalStateException("synthetic settings failure")
        settingsRepository.failure = boom
        settingsRepository.failureFromCall = settingsRepository.calls + 1

        val thrown = runCatching { manager.claimInstallmentReminder(reminder) }.exceptionOrNull()

        assertCarries(boom, thrown)
        assertEquals(listOf(reminder.dedupeKey), ledger.released)
    }

    @Test
    fun `a ledger claim denied before revalidation neither revalidates nor releases`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        val reminder = manager.getDueInstallmentReminders().single()
        assertNotNull(manager.claimInstallmentReminder(reminder))
        val portCallsBefore = port.calls
        val releasedBefore = ledger.released.size

        assertNull(manager.claimInstallmentReminder(reminder))

        assertEquals(portCallsBefore, port.calls)
        assertEquals(releasedBefore, ledger.released.size)
    }

    // --- PAID_BEFORE_TRACKING is not SCHEDULED (INV-32): never listed ---

    @Test
    fun `a PAID_BEFORE_TRACKING entry is never listed`() = runTest {
        port.plans = listOf(
            plan(
                1L, "plan-a",
                entries = listOf(
                    entry(11, 1, dueOffsetDays = 1, status = InstallmentEntryStatus.PAID_BEFORE_TRACKING),
                    entry(12, 2, dueOffsetDays = 0, status = InstallmentEntryStatus.PAID_BEFORE_TRACKING),
                    entry(13, 3, dueOffsetDays = 1)
                )
            )
        )

        assertEquals(listOf("plan-a:3:0"), manager.getDueInstallmentReminders().map { it.dedupeKey })
    }

    @Test
    fun `an entry marked PAID_BEFORE_TRACKING after listing is revalidated away and its claim released`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", entries = listOf(entry(11, 1, 1))))
        val reminder = manager.getDueInstallmentReminders().single()

        port.plans = listOf(
            plan(1L, "plan-a", entries = listOf(entry(11, 1, 1, status = InstallmentEntryStatus.PAID_BEFORE_TRACKING)))
        )

        assertNull(manager.claimInstallmentReminder(reminder))
        assertEquals(listOf(reminder.dedupeKey), ledger.released)
    }

    // --- Text and redaction ---

    @Test
    fun `a blank plan name falls back to the generic bill text and never yields an empty title`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", name = "   ", entries = listOf(entry(11, 1, 1))))
        val reminder = manager.getDueInstallmentReminders().single()

        val text = withLocale(Locale.US) { manager.buildInstallmentReminderText(reminder) }

        assertEquals("Bill due", text.title)
        assertEquals("Bill due: 50.00 EUR", text.body)
    }

    @Test
    fun `notification text carries the plan name and amount like a bill and no plan only fields`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", name = "Demo Electronics", entries = listOf(
            entry(11, 3, 1, minor = 5050L)
        )))
        val reminder = manager.getDueInstallmentReminders().single()

        val text = withLocale(Locale.US) { manager.buildInstallmentReminderText(reminder) }

        assertEquals("Bill due", text.title)
        assertEquals("Demo Electronics due: 50.50 EUR", text.body)
        val combined = text.title + " " + text.body
        assertFalse(combined.contains("plan-a"))
        assertFalse(combined.contains("3/"))
        assertFalse(combined.contains("of 12"))
        assertFalse(combined.contains(todayEpochDay.toString()))
        assertEquals("InstallmentReminder(redacted)", reminder.toString())
    }

    @Test
    fun `the amount uses the default locale format like the bill reminder (comma for Germany)`() = runTest {
        port.plans = listOf(plan(1L, "plan-a", name = "Demo Electronics", entries = listOf(
            entry(11, 3, 1, minor = 123_456L)
        )))
        val reminder = manager.getDueInstallmentReminders().single()

        val germany = withLocale(Locale.GERMANY) { manager.buildInstallmentReminderText(reminder) }
        val us = withLocale(Locale.US) { manager.buildInstallmentReminderText(reminder) }

        assertEquals("Demo Electronics due: 1234,56 EUR", germany.body)
        assertEquals("Demo Electronics due: 1234.56 EUR", us.body)
    }

    @Test
    fun `InstallmentReminderText and InstallmentReminder toString never print their values`() {
        val text = InstallmentReminderText(title = "Bill due", body = "Demo Electronics due: 50.00 EUR")
        val reminder = InstallmentReminder(
            planUuid = "plan-secret-uuid",
            seq = 7,
            recordGeneration = 3,
            entryId = 99L,
            displayName = "Demo Electronics",
            currencyCode = "EUR",
            amountMinor = 5000L,
            dueEpochDay = 20_123L,
            daysUntilDue = 1
        )

        assertEquals("InstallmentReminderText(redacted)", text.toString())
        assertEquals("InstallmentReminder(redacted)", reminder.toString())
        assertEquals("InstallmentReminderText(redacted)", "$text")
        assertFalse(reminder.toString().contains("Demo"))
        assertFalse(reminder.toString().contains("plan-secret-uuid"))
        assertFalse(reminder.toString().contains("20123"))
    }

    @Test
    fun `cancellation from the projection port is not swallowed`() = runTest {
        port.failure = CancellationException("cancelled")

        val failure = runCatching { manager.getDueInstallmentReminders() }.exceptionOrNull()

        assertTrue(failure is CancellationException)
    }

    /**
     * The failure surfaces as itself or, under coroutine stack-trace recovery, as a copy whose cause
     * chain contains the original instance.
     */
    private fun assertCarries(expected: Throwable, actual: Throwable?) {
        assertTrue(
            "expected the original failure in the thrown chain",
            generateSequence(actual) { it.cause }.any { it === expected }
        )
    }

    private inline fun <T> withLocale(locale: Locale, block: () -> T): T {
        val previous = Locale.getDefault()
        Locale.setDefault(locale)
        try {
            return block()
        } finally {
            Locale.setDefault(previous)
        }
    }

    private class FakeProjectionPort : InstallmentProjectionPort {
        var plans: List<ProjectionPlanInput> = emptyList()
        var todayEpochDay: Long = 0L
        var failure: Throwable? = null

        /** First call number (1-based) from which [failure] is thrown. */
        var failureFromCall: Int = 1
        var calls: Int = 0

        override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> {
            calls++
            failure?.let { if (calls >= failureFromCall) throw it }
            return InstallmentProjectionRule.project(plans, todayEpochDay, horizonEndEpochDay)
        }
    }

    /** Recording stand-in: remembers a claimed due day per key. Real semantics: InstallmentReminderLedgerImplTest. */
    private class FakeLedger : InstallmentReminderLedger {
        private val claimedDue = HashMap<String, Long>()
        private val sent = HashSet<String>()
        val calls = mutableListOf<String>()
        val released = mutableListOf<String>()
        val markSentCalls = mutableListOf<Triple<String, Long, Long>>()
        var markSentResult = true

        override suspend fun tryClaim(key: String, dueEpochDay: Long, todayEpochDay: Long, nowMillis: Long): Boolean {
            calls += "tryClaim"
            if (claimedDue[key] == dueEpochDay) return false
            claimedDue[key] = dueEpochDay
            sent.remove(key)
            return true
        }

        override suspend fun markSent(key: String, dueEpochDay: Long, nowMillis: Long): Boolean {
            calls += "markSent"
            markSentCalls += Triple(key, dueEpochDay, nowMillis)
            claimedDue[key] = dueEpochDay
            sent += key
            return markSentResult
        }

        override suspend fun isClaimed(key: String, dueEpochDay: Long, nowMillis: Long): Boolean {
            calls += "isClaimed"
            return claimedDue[key] == dueEpochDay
        }

        override suspend fun release(key: String) {
            calls += "release"
            released += key
            if (key !in sent) claimedDue.remove(key)
        }

        override suspend fun prune(todayEpochDay: Long) {
            calls += "prune"
        }
    }

    private class FakeSettingsRepository : BillReminderSettingsRepository {
        var settings: BillReminderSettings = BillReminderSettings()
        var failure: Throwable? = null
        var failureFromCall: Int = 1
        var calls: Int = 0

        override suspend fun getSnapshot(): BillReminderSettings {
            calls++
            failure?.let { if (calls >= failureFromCall) throw it }
            return settings
        }

        override suspend fun setBillRemindersEnabled(enabled: Boolean) = Unit
        override suspend fun setQuietHoursEnabled(enabled: Boolean, startMinute: Int, endMinute: Int) = Unit
    }
}
