package com.yourname.expensetracker.domain.reminder

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
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
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * S7b (INV-15, owner-approvals item 44): the two-phase local delivery ledger for installment
 * reminders, on real (Robolectric) SharedPreferences, plus the manager driven by the real ledger.
 *
 * Time and days are literals; keys are synthetic opaque `planUuid:seq:recordGeneration` values.
 * The raw `k:<key>` = `dueDay|claimedAt|sent` layout is touched only where a corrupt record has to
 * be planted or the "claimedAt is kept" rule has to be observed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class InstallmentReminderLedgerImplTest {

    private lateinit var context: Context
    private lateinit var ledger: InstallmentReminderLedgerImpl

    private val key = "plan-a:1:0"
    private val otherKey = "plan-b:3:2"
    private val t0 = 1_800_000_000_000L
    private val staleMs = InstallmentReminderLedger.STALE_CLAIM_THRESHOLD_MS
    private val retentionDays = InstallmentReminderLedger.RETENTION_DAYS
    private val today = 20_000L
    private val due = 20_002L

    private val rawPrefs: SharedPreferences
        get() = context.getSharedPreferences("installment_reminder_ledger", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        rawPrefs.edit().clear().commit()
        ledger = InstallmentReminderLedgerImpl(context)
    }

    @Test
    fun `constants match the recurring claim recovery threshold and the sixty day retention`() {
        assertEquals(300_000L, InstallmentReminderLedger.STALE_CLAIM_THRESHOLD_MS)
        assertEquals(60L, InstallmentReminderLedger.RETENTION_DAYS)
    }

    // --- Claim: normal control and fresh/stale boundary ---

    @Test
    fun `an absent key is claimable once and then reads as claimed`() = runTest {
        assertFalse(ledger.isClaimed(key, due, t0))

        assertTrue(ledger.tryClaim(key, due, today, t0))

        assertTrue(ledger.isClaimed(key, due, t0))
        assertFalse(ledger.tryClaim(key, due, today, t0))
    }

    @Test
    fun `a fresh unsent claim is not reclaimable and a stale one is, at the exact threshold`() = runTest {
        assertTrue(ledger.tryClaim(key, due, today, t0))

        // One millisecond before the threshold: still fresh.
        assertTrue(ledger.isClaimed(key, due, t0 + staleMs - 1))
        assertFalse(ledger.tryClaim(key, due, today, t0 + staleMs - 1))

        // At the threshold: stale, listed again and claimable.
        assertFalse(ledger.isClaimed(key, due, t0 + staleMs))
        assertTrue(ledger.tryClaim(key, due, today, t0 + staleMs))

        // The reclaim restarts the freshness window.
        assertTrue(ledger.isClaimed(key, due, t0 + staleMs + staleMs - 1))
        assertFalse(ledger.tryClaim(key, due, today, t0 + staleMs + staleMs - 1))
    }

    @Test
    fun `a stale unsent claim stays reclaimable long after the threshold`() = runTest {
        assertTrue(ledger.tryClaim(key, due, today, t0))

        assertFalse(ledger.isClaimed(key, due, t0 + 10 * staleMs))
        assertTrue(ledger.tryClaim(key, due, today, t0 + 10 * staleMs))
    }

    // --- Sent keys are final ---

    @Test
    fun `a sent key is never reclaimed or listed again however much time passes`() = runTest {
        assertTrue(ledger.tryClaim(key, due, today, t0))
        assertTrue(ledger.markSent(key, due, t0 + 1_000))

        val muchLater = t0 + 365L * 24 * 60 * 60 * 1000
        assertTrue(ledger.isClaimed(key, due, muchLater))
        assertFalse(ledger.tryClaim(key, due, today, muchLater))
    }

    @Test
    fun `release on a sent key is a no-op`() = runTest {
        assertTrue(ledger.tryClaim(key, due, today, t0))
        assertTrue(ledger.markSent(key, due, t0 + 1_000))

        ledger.release(key)

        assertTrue(ledger.isClaimed(key, due, t0 + 2 * staleMs))
        assertFalse(ledger.tryClaim(key, due, today, t0 + 2 * staleMs))
    }

    @Test
    fun `release on an unsent claim gives it back immediately`() = runTest {
        assertTrue(ledger.tryClaim(key, due, today, t0))

        ledger.release(key)

        assertFalse(ledger.isClaimed(key, due, t0))
        assertTrue(ledger.tryClaim(key, due, today, t0))
    }

    @Test
    fun `release on an absent key does nothing and leaves other keys alone`() = runTest {
        assertTrue(ledger.tryClaim(otherKey, due, today, t0))

        ledger.release(key)

        assertTrue(ledger.isClaimed(otherKey, due, t0))
    }

    @Test
    fun `release removes only the named key`() = runTest {
        assertTrue(ledger.tryClaim(key, due, today, t0))
        assertTrue(ledger.tryClaim(otherKey, due, today, t0))

        ledger.release(key)

        assertFalse(ledger.isClaimed(key, due, t0))
        assertTrue(ledger.isClaimed(otherKey, due, t0))
    }

    @Test
    fun `markSent upserts when the claim record is gone and then blocks`() = runTest {
        assertFalse(ledger.isClaimed(key, due, t0))

        assertTrue(ledger.markSent(key, due, t0))

        assertTrue(ledger.isClaimed(key, due, t0))
        assertFalse(ledger.tryClaim(key, due, today, t0 + 10 * staleMs))
        assertEquals("$due|$t0|1", rawPrefs.getString("k:$key", null))
    }

    @Test
    fun `markSent keeps the original claimedAt of the claim`() = runTest {
        assertTrue(ledger.tryClaim(key, due, today, t0))

        assertTrue(ledger.markSent(key, due, t0 + 4_000))

        assertEquals("$due|$t0|1", rawPrefs.getString("k:$key", null))
    }

    @Test
    fun `a sent record survives a new ledger instance on the same preferences (process restart)`() = runTest {
        assertTrue(ledger.tryClaim(key, due, today, t0))
        assertTrue(ledger.markSent(key, due, t0 + 1))

        val restarted = InstallmentReminderLedgerImpl(context)

        assertTrue(restarted.isClaimed(key, due, t0 + 10 * staleMs))
        assertFalse(restarted.tryClaim(key, due, today, t0 + 10 * staleMs))
    }

    @Test
    fun `an unsent claim left by a died run is recovered only after the stale threshold in a new instance`() = runTest {
        assertTrue(ledger.tryClaim(key, due, today, t0))

        val restarted = InstallmentReminderLedgerImpl(context)

        assertFalse(restarted.tryClaim(key, due, today, t0 + staleMs - 1))
        assertTrue(restarted.tryClaim(key, due, today, t0 + staleMs))
    }

    // --- A different due day never blocks (E3 edit does not bump recordGeneration) ---

    @Test
    fun `a sent record for another due day does not block the new due day`() = runTest {
        assertTrue(ledger.markSent(key, due, t0))

        assertFalse(ledger.isClaimed(key, due + 1, t0))
        assertTrue(ledger.tryClaim(key, due + 1, today, t0))
        // The original day is no longer recorded for the key; the new one now is.
        assertTrue(ledger.isClaimed(key, due + 1, t0))
    }

    @Test
    fun `a fresh unsent claim for another due day does not block the new due day`() = runTest {
        assertTrue(ledger.tryClaim(key, due, today, t0))

        assertFalse(ledger.isClaimed(key, due + 1, t0))
        assertTrue(ledger.tryClaim(key, due + 1, today, t0))
    }

    @Test
    fun `an earlier due day is also not blocked by a record for a later one`() = runTest {
        assertTrue(ledger.markSent(key, due, t0))

        assertFalse(ledger.isClaimed(key, due - 1, t0))
        assertTrue(ledger.tryClaim(key, due - 1, today, t0))
    }

    // --- Retention: 60-day prune boundary ---

    @Test
    fun `prune keeps a record at exactly sixty days and removes one at sixty one`() = runTest {
        val atBoundary = today - retentionDays
        val overBoundary = today - retentionDays - 1
        assertTrue(ledger.markSent("keep:1:0", atBoundary, t0))
        assertTrue(ledger.markSent("drop:1:0", overBoundary, t0))
        assertTrue(ledger.isClaimed("keep:1:0", atBoundary, t0))
        assertTrue(ledger.isClaimed("drop:1:0", overBoundary, t0))

        ledger.prune(today)

        assertTrue(ledger.isClaimed("keep:1:0", atBoundary, t0))
        assertFalse(ledger.isClaimed("drop:1:0", overBoundary, t0))
    }

    @Test
    fun `prune keeps current and future records`() = runTest {
        assertTrue(ledger.markSent("now:1:0", today, t0))
        assertTrue(ledger.markSent("soon:1:0", today + 3, t0))

        ledger.prune(today)

        assertTrue(ledger.isClaimed("now:1:0", today, t0))
        assertTrue(ledger.isClaimed("soon:1:0", today + 3, t0))
    }

    @Test
    fun `prune does not touch preferences that are not ledger records`() = runTest {
        rawPrefs.edit().putString("unrelated", "keep").commit()
        assertTrue(ledger.markSent("drop:1:0", today - retentionDays - 5, t0))

        ledger.prune(today)

        assertEquals("keep", rawPrefs.getString("unrelated", null))
    }

    @Test
    fun `tryClaim prunes expired records whether or not the claim itself is granted`() = runTest {
        val expiredDue = today - retentionDays - 1
        assertTrue(ledger.markSent("old-a:1:0", expiredDue, t0))
        assertTrue(ledger.markSent("old-b:1:0", expiredDue, t0))
        assertTrue(ledger.markSent(key, due, t0))

        // Granted claim for another key prunes old-a.
        assertTrue(ledger.tryClaim(otherKey, due, today, t0))
        assertFalse(ledger.isClaimed("old-a:1:0", expiredDue, t0))

        // A denied claim (sent key) still commits the prune of old-b.
        assertFalse(ledger.tryClaim(key, due, today, t0))
        assertFalse(ledger.isClaimed("old-b:1:0", expiredDue, t0))
    }

    // --- Fail closed: unreadable values ---

    @Test
    fun `an unreadable record reads as claimed and cannot be claimed`() = runTest {
        val corrupt = listOf("garbage", "1|2", "a|b|1", "20002|5|7", "20002|5|1|9", "", "20002||1")
        for ((index, value) in corrupt.withIndex()) {
            val corruptKey = "corrupt:$index:0"
            rawPrefs.edit().putString("k:$corruptKey", value).commit()

            assertTrue("isClaimed for '$value'", ledger.isClaimed(corruptKey, due, t0))
            assertFalse("tryClaim for '$value'", ledger.tryClaim(corruptKey, due, today, t0))
        }
    }

    @Test
    fun `a non string stored value reads as claimed and cannot be claimed`() = runTest {
        rawPrefs.edit().putLong("k:$key", 5L).commit()

        assertTrue(ledger.isClaimed(key, due, t0))
        assertFalse(ledger.tryClaim(key, due, today, t0))
    }

    @Test
    fun `release never removes an unreadable record`() = runTest {
        rawPrefs.edit().putString("k:$key", "garbage").commit()

        ledger.release(key)

        assertTrue(ledger.isClaimed(key, due, t0))
        assertEquals("garbage", rawPrefs.getString("k:$key", null))
    }

    // --- Fail closed: a write that is not durable ---

    @Test
    fun `tryClaim returns false when the write commit fails`() = runTest {
        val failing = ledgerOverPreferencesWhoseCommitFails()

        assertFalse(failing.tryClaim(key, due, today, t0))
    }

    @Test
    fun `markSent reports false when the write commit fails`() = runTest {
        val failing = ledgerOverPreferencesWhoseCommitFails()

        assertFalse(failing.markSent(key, due, t0))
    }

    @Test
    fun `the same mocked preferences with a succeeding commit grant the claim (control)`() = runTest {
        val working = ledgerOverMockedPreferences(commitResult = true)

        assertTrue(working.tryClaim(key, due, today, t0))
        assertTrue(working.markSent(key, due, t0))
    }

    // --- Redaction ---

    @Test
    fun `the internal record toString never prints its values`() {
        val recordClass = Class.forName(
            "com.yourname.expensetracker.domain.reminder.InstallmentReminderLedgerImpl\$Record"
        )
        val constructor = recordClass.declaredConstructors.single { it.parameterTypes.size == 3 }
        constructor.isAccessible = true

        val record = constructor.newInstance(123_456L, 987_654L, true)

        assertEquals("Record(redacted)", record.toString())
        assertFalse(record.toString().contains("123456"))
        assertFalse(record.toString().contains("987654"))
    }

    @Test
    fun `stored values contain only integers and a flag, never the key or plan data`() = runTest {
        assertTrue(ledger.tryClaim("plan-secret-uuid:4:1", due, today, t0))
        assertTrue(ledger.markSent("plan-secret-uuid:4:1", due, t0 + 1))

        val values = rawPrefs.all.values.map { it.toString() }

        assertEquals(1, values.size)
        assertTrue(values.single().matches(Regex("""\d+\|\d+\|[01]""")))
    }

    // --- Manager driven by the real ledger ---

    @Test
    fun `after markSent for day D an entry with the same key due on D plus one is listed and claimable`() = runTest {
        val fixture = ManagerFixture(context, ledger)
        fixture.plans = listOf(fixture.plan(entries = listOf(fixture.entry(seq = 1, dueOffsetDays = 1))))
        val first = fixture.manager.getDueInstallmentReminders().single()
        val claimed = fixture.manager.claimInstallmentReminder(first)!!
        assertTrue(fixture.manager.markInstallmentReminderSent(claimed))
        assertTrue(fixture.manager.getDueInstallmentReminders().isEmpty())
        assertNull(fixture.manager.claimInstallmentReminder(first))

        // E3 edit: same planUuid/seq/recordGeneration, new due day.
        fixture.plans = listOf(fixture.plan(entries = listOf(fixture.entry(seq = 1, dueOffsetDays = 2))))

        val moved = fixture.manager.getDueInstallmentReminders().single()
        assertEquals(first.dedupeKey, moved.dedupeKey)
        assertEquals(fixture.todayEpochDay + 2, moved.dueEpochDay)
        assertNotNull(fixture.manager.claimInstallmentReminder(moved))
    }

    @Test
    fun `a due day moved between listing and claim releases the real claim and the next run lists the new day`() = runTest {
        val fixture = ManagerFixture(context, ledger)
        fixture.plans = listOf(fixture.plan(entries = listOf(fixture.entry(seq = 1, dueOffsetDays = 1))))
        val listed = fixture.manager.getDueInstallmentReminders().single()

        fixture.plans = listOf(fixture.plan(entries = listOf(fixture.entry(seq = 1, dueOffsetDays = 2))))

        assertNull(fixture.manager.claimInstallmentReminder(listed))
        // The claim for the old day was given back, so nothing blocks the old key.
        assertFalse(ledger.isClaimed(listed.dedupeKey, listed.dueEpochDay, fixture.nowMillis))
        val relisted = fixture.manager.getDueInstallmentReminders().single()
        assertEquals(fixture.todayEpochDay + 2, relisted.dueEpochDay)
        assertNotNull(fixture.manager.claimInstallmentReminder(relisted))
    }

    @Test
    fun `a revalidation failure releases the real claim so the next run reminds again`() = runTest {
        val fixture = ManagerFixture(context, ledger)
        fixture.plans = listOf(fixture.plan(entries = listOf(fixture.entry(seq = 1, dueOffsetDays = 1))))
        val listed = fixture.manager.getDueInstallmentReminders().single()
        fixture.port.failure = IllegalStateException("synthetic port failure")
        fixture.port.failureFromCall = fixture.port.calls + 1

        val thrown = runCatching { fixture.manager.claimInstallmentReminder(listed) }.exceptionOrNull()

        assertNotNull(thrown)
        assertFalse(ledger.isClaimed(listed.dedupeKey, listed.dueEpochDay, fixture.nowMillis))
        fixture.port.failure = null
        assertEquals(1, fixture.manager.getDueInstallmentReminders().size)
    }

    @Test
    fun `a posted but unmarked reminder is not re-listed within the stale window and is after it (died run)`() = runTest {
        val fixture = ManagerFixture(context, ledger)
        fixture.plans = listOf(fixture.plan(entries = listOf(fixture.entry(seq = 1, dueOffsetDays = 1))))
        val listed = fixture.manager.getDueInstallmentReminders().single()
        assertNotNull(fixture.manager.claimInstallmentReminder(listed))
        // The run dies here: no markSent, no release.

        assertTrue(fixture.manager.getDueInstallmentReminders().isEmpty())

        fixture.clock += staleMs
        assertEquals(1, fixture.manager.getDueInstallmentReminders().size)
    }

    // --- Helpers ---

    private fun ledgerOverPreferencesWhoseCommitFails(): InstallmentReminderLedgerImpl =
        ledgerOverMockedPreferences(commitResult = false)

    private fun ledgerOverMockedPreferences(commitResult: Boolean): InstallmentReminderLedgerImpl {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns commitResult
        val prefs = mockk<SharedPreferences>()
        every { prefs.edit() } returns editor
        every { prefs.contains(any()) } returns false
        every { prefs.all } returns emptyMap<String, Any?>()
        val mockedContext = mockk<Context>()
        every { mockedContext.getSharedPreferences(any(), any()) } returns prefs
        return InstallmentReminderLedgerImpl(mockedContext)
    }

    private class ManagerPort : InstallmentProjectionPort {
        var plans: List<ProjectionPlanInput> = emptyList()
        var todayEpochDay: Long = 0L
        var failure: Throwable? = null
        var failureFromCall: Int = 1
        var calls: Int = 0

        override suspend fun upcomingEntries(horizonEndEpochDay: Long?): List<InstallmentProjectedEntry> {
            calls++
            failure?.let { if (calls >= failureFromCall) throw it }
            return InstallmentProjectionRule.project(plans, todayEpochDay, horizonEndEpochDay)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private class ManagerFixture(context: Context, ledger: InstallmentReminderLedger) {
        private val today: LocalDate = LocalDate.of(2027, 6, 10)
        val todayEpochDay: Long = today.toEpochDay()
        var clock: Long = today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val nowMillis: Long get() = clock
        val port = ManagerPort().also { it.todayEpochDay = todayEpochDay }
        var plans: List<ProjectionPlanInput>
            get() = port.plans
            set(value) {
                port.plans = value
            }
        val manager: BillReminderManager

        init {
            val timeProvider = mockk<TimeProvider>()
            every { timeProvider.now() } answers { clock }
            val settings = mockk<BillReminderSettingsRepository>()
            coEvery { settings.getSnapshot() } returns BillReminderSettings()
            manager = BillReminderManager(
                mockk<RecurringExpenseRepository>(relaxed = true),
                timeProvider,
                mockk<MultiCurrencyRepository>(relaxed = true),
                mockk<CurrencySettingsRepository>(relaxed = true),
                port,
                ledger,
                settings
            )
        }

        fun entry(seq: Int, dueOffsetDays: Long) = ProjectionEntryInput(
            entryId = 10L + seq,
            seq = seq,
            kind = InstallmentEntryKind.REGULAR,
            status = InstallmentEntryStatus.SCHEDULED,
            dueEpochDay = todayEpochDay + dueOffsetDays,
            scheduledMinor = 5000L,
            recordGeneration = 0
        )

        fun plan(entries: List<ProjectionEntryInput>) = ProjectionPlanInput(
            1L, "plan-a", "Demo Electronics", InstallmentCurrency.EUR, InstallmentPlanStatus.ACTIVE, entries
        )
    }
}
