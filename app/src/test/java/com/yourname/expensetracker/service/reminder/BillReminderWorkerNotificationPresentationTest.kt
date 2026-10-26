package com.yourname.expensetracker.service.reminder

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.database.entity.RecurringOccurrence
import com.yourname.expensetracker.data.database.entity.RecurringReminderDelivery
import com.yourname.expensetracker.data.service.NotificationKindMarker
import com.yourname.expensetracker.data.service.NotificationPresentationApplier
import com.yourname.expensetracker.data.service.NotificationPresentationTexts
import com.yourname.expensetracker.data.service.PresentationFixtures
import com.yourname.expensetracker.data.service.PresentationFixtures.ScriptedSettingsRepository
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationMode
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationResolver
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import com.yourname.expensetracker.domain.recurring.lifecycle.RecurringLifecycleCoordinator
import com.yourname.expensetracker.domain.reminder.BillReminderSettings
import com.yourname.expensetracker.domain.reminder.BillReminderSettingsRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.NotificationId
import com.yourname.expensetracker.domain.workers.WorkerExecutionGuard
import com.yourname.expensetracker.domain.workers.WorkerGuardResult
import com.yourname.expensetracker.domain.workers.WorkerRunContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * S8, post side of [BillReminderWorker] (AE-1, AE-2, AE-7, AE-9, AE-11 bill site, AE-12, AE-13 site
 * declaration BILL, AE-121 bill marker, INV-4, INV-7..INV-13, OD-5a).
 *
 * Real Robolectric NotificationManager, real builder, real [NotificationPresentationApplier],
 * [NotificationPresentationTexts] and [NotificationPresentationResolver]. The settings repository is a
 * scripted double, the diagnostics emitter is relaxed, the coordinator and the guard are mocks. A merchant
 * and an amount sentinel are the only caller data. The matrix runs once per SDK level through the
 * subclasses at the end of the file.
 */
@RunWith(RobolectricTestRunner::class)
abstract class BillReminderWorkerNotificationPresentationContract {

    protected abstract val expectedSdk: Int

    private lateinit var context: Context
    private lateinit var texts: NotificationPresentationTexts
    private lateinit var diagnosticsScope: CoroutineScope
    private lateinit var coordinator: RecurringLifecycleCoordinator
    private lateinit var executionGuard: WorkerExecutionGuard
    private lateinit var diagnosticEventWriter: DiagnosticEventWriter
    private lateinit var reminderSettingsRepository: BillReminderSettingsRepository
    private lateinit var runContext: WorkerRunContext
    private lateinit var privacyEmitter: PrivacyDiagnosticsEmitter

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        texts = NotificationPresentationTexts(context)
        diagnosticsScope = CoroutineScope(Job())
        coordinator = mockk(relaxed = true)
        executionGuard = mockk(relaxed = true)
        diagnosticEventWriter = mockk(relaxed = true)
        reminderSettingsRepository = mockk()
        runContext = mockk(relaxed = true)
        privacyEmitter = mockk(relaxed = true)

        coEvery { executionGuard.runGuardedWithContext(any(), any<suspend (WorkerRunContext) -> Any>()) } coAnswers {
            val block = secondArg<suspend (WorkerRunContext) -> Any>()
            WorkerGuardResult.Success(block.invoke(runContext))
        }
        coEvery { reminderSettingsRepository.getSnapshot() } returns BillReminderSettings(
            billRemindersEnabled = true,
            quietHoursEnabled = false
        )
    }

    @After
    fun tearDown() {
        diagnosticsScope.cancel()
        unmockkStatic(NotificationManagerCompat::class)
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun manager(): NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun repoFor(mode: NotificationPresentationMode): ScriptedSettingsRepository =
        ScriptedSettingsRepository {
            AppLockSettingsLoadState.Loaded(
                AppLockPrivacySettings(discreetNotifications = mode == NotificationPresentationMode.DISCREET)
            )
        }

    private fun buildWorker(repo: ScriptedSettingsRepository): BillReminderWorker {
        val resolver = NotificationPresentationResolver(
            repo,
            privacyEmitter,
            diagnosticsScope
        )
        val applier = NotificationPresentationApplier(context, texts)
        return TestListenableWorkerBuilder<BillReminderWorker>(context)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): BillReminderWorker = BillReminderWorker(
                    appContext,
                    workerParameters,
                    coordinator,
                    executionGuard,
                    diagnosticEventWriter,
                    reminderSettingsRepository,
                    FakeTimeProvider(0L),
                    resolver,
                    applier
                )
            })
            .build()
    }

    private fun reminder(id: Long, occurrenceId: Long) = RecurringReminderDelivery(
        id = id,
        occurrenceId = occurrenceId,
        reminderWindow = "DUE_DAY",
        scheduledAt = 0L,
        status = "SCHEDULED"
    )

    private fun snapshot(reminder: RecurringReminderDelivery) = RecurringLifecycleCoordinator.ReminderDispatchSnapshot(
        delivery = reminder,
        occurrence = RecurringOccurrence(
            id = reminder.occurrenceId,
            sourceType = "RECURRING_RULE",
            sourceId = 10L,
            occurrenceKey = "RECURRING_RULE|10|${reminder.occurrenceId}|MONTHLY",
            dueDate = 0L,
            status = "PLANNED",
            expectedAmount = AMOUNT,
            expectedCurrency = "USD",
            frequency = "MONTHLY",
            merchant = MERCHANT
        )
    )

    private fun scriptDue(vararg reminders: RecurringReminderDelivery) {
        coEvery { coordinator.recoverAndGetDueReminders() } returns reminders.toList()
        reminders.forEach { r ->
            coEvery { coordinator.claimReminderDelivery(r.id) } returns true
            coEvery { coordinator.getDispatchableClaimedReminder(r.id) } returns snapshot(r)
            coEvery { coordinator.markReminderSent(r.id, any()) } returns true
        }
    }

    /** Runs the worker once and returns the single posted bill notification. */
    private fun runAndRead(repo: ScriptedSettingsRepository, deliveryId: Long = DELIVERY_ID): StatusBarNotification {
        scriptDue(reminder(deliveryId, 100L))
        runTest(timeout = 10.seconds) {
            assertEquals(Result.success(), buildWorker(repo).doWork())
        }
        val matches = manager().activeNotifications.filter { it.id == NotificationId.forBill(deliveryId).value }
        assertEquals("exactly one bill notification", 1, matches.size)
        return matches.single()
    }

    private fun everyString(n: Notification): List<String> = PresentationFixtures.everyString(n)

    private fun assertNoSentinel(n: Notification, label: String) {
        val all = everyString(n)
        SENTINELS.forEach { sentinel ->
            all.forEach { s -> assertFalse("$label: '$sentinel' leaked into '$s'", s.contains(sentinel)) }
        }
    }

    private fun Notification.titleText(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.contentText(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    @Suppress("DEPRECATION")
    private fun keysHoldingToken(extras: Bundle, token: String): Set<String> =
        extras.keySet().filter { key -> extras.get(key)?.toString() == token }.toSet()

    // --- environment ------------------------------------------------------------------------------

    @Test
    fun `the Robolectric SDK matches the configured level`() {
        assertEquals(expectedSdk, Build.VERSION.SDK_INT)
    }

    // --- AE-1 / AE-2: FULL control and DISCREET ----------------------------------------------------

    @Test
    fun `AE-1 control FULL shows the detailed bill text so the DISCREET scan is not vacuous`() {
        val n = runAndRead(repoFor(NotificationPresentationMode.FULL)).notification

        assertEquals("Bill due", n.titleText())
        assertEquals("$MERCHANT due: 1234.56 USD", n.contentText())
        assertTrue(everyString(n).any { it.contains(MERCHANT) })
        assertTrue(everyString(n).any { it.contains("1234.56") })
        assertFalse("bill has no expanded style today", n.extras.containsKey(Notification.EXTRA_BIG_TEXT))
    }

    @Test
    fun `AE-2 DISCREET shows only the bill constants and no merchant or amount anywhere`() {
        val n = runAndRead(repoFor(NotificationPresentationMode.DISCREET)).notification

        assertEquals(context.getString(R.string.notif_discreet_bill_title), n.titleText())
        assertEquals(context.getString(R.string.notif_discreet_bill_text), n.contentText())
        assertNoSentinel(n, "bill DISCREET sdk=$expectedSdk")
        assertFalse(n.extras.containsKey(Notification.EXTRA_BIG_TEXT))
        assertNull("no expanded style in DISCREET", n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
    }

    @Test
    fun `INV-7 the public version is the generic pair in FULL and DISCREET with no actions style or marker`() {
        var deliveryId = 50L
        for (mode in NotificationPresentationMode.entries) {
            val label = "$mode sdk=$expectedSdk"
            val pub = runAndRead(repoFor(mode), deliveryId++).notification.publicVersion

            assertNotNull(label, pub)
            assertEquals(label, context.getString(R.string.notif_public_title), pub!!.titleText())
            assertEquals(label, context.getString(R.string.notif_public_text), pub.contentText())
            assertTrue(label, pub.actions.isNullOrEmpty())
            assertNull(label, pub.extras.getString(Notification.EXTRA_TEMPLATE))
            assertNull(label, NotificationKindMarker.read(pub))
            assertNoSentinel(pub, "$label public")
        }
    }

    // --- AE-121 / AE-13: marker --------------------------------------------------------------------

    @Test
    fun `AE-13 and AE-121 the bill post carries the BILL marker on the private notification in FULL and DISCREET`() {
        var deliveryId = 60L
        for (mode in NotificationPresentationMode.entries) {
            val label = "$mode sdk=$expectedSdk"
            val n = runAndRead(repoFor(mode), deliveryId++).notification
            val token = NotificationKind.BILL.stableToken

            assertEquals(label, token, NotificationKindMarker.read(n))
            assertEquals(label, token, n.extras.getString(NotificationKindMarker.EXTRA_KEY))
            // The marker is not a text extra: the only entry whose value is the token is the marker key.
            assertEquals(label, setOf(NotificationKindMarker.EXTRA_KEY), keysHoldingToken(n.extras, token))
            assertNotEqualsToken(label, n.titleText(), token)
            assertNotEqualsToken(label, n.contentText(), token)
            assertNotEqualsToken(label, n.tickerText?.toString(), token)
        }
    }

    private fun assertNotEqualsToken(label: String, value: String?, token: String) {
        assertFalse("$label text value equals the marker token", value == token)
    }

    // --- id, tag, channel --------------------------------------------------------------------------

    @Test
    fun `AE-121 the posted id is NotificationId forBill the tag is absent and the channel is unchanged`() {
        var deliveryId = 70L
        for (mode in NotificationPresentationMode.entries) {
            val label = "$mode sdk=$expectedSdk"
            val sbn = runAndRead(repoFor(mode), deliveryId)
            val expected = NotificationId.forBill(deliveryId).value

            assertEquals(label, expected, sbn.id)
            assertTrue(label, expected in 30000..39999)
            assertNull(label, sbn.tag)
            if (Build.VERSION.SDK_INT >= 26) {
                assertEquals(label, "bill_reminders", sbn.notification.channelId)
            }
            deliveryId++
        }
        assertEquals("bill_reminders", BillReminderWorker.CHANNEL_ID)
    }

    // --- action titles, intents, AUTH_REQUIRED -------------------------------------------------------

    @Test
    fun `the Snooze and Dismiss titles come from the string resources in FULL and DISCREET`() {
        var deliveryId = 80L
        for (mode in NotificationPresentationMode.entries) {
            val label = "$mode sdk=$expectedSdk"
            val actions = runAndRead(repoFor(mode), deliveryId++).notification.actions

            assertNotNull(label, actions)
            assertEquals(label, 2, actions.size)
            assertEquals(label, context.getString(R.string.notif_action_snooze), actions[0].title.toString())
            assertEquals(label, context.getString(R.string.action_dismiss), actions[1].title.toString())
            // The English defaults equal the literals the worker used before S8.
            assertEquals(label, "Snooze", actions[0].title.toString())
            assertEquals(label, "Dismiss", actions[1].title.toString())
        }
    }

    @Test
    fun `the action PendingIntents keep their receivers extras request codes and immutability`() {
        val sbn = runAndRead(repoFor(NotificationPresentationMode.DISCREET), DELIVERY_ID)
        val id = NotificationId.forBill(DELIVERY_ID).value
        val actions = sbn.notification.actions

        val snooze = shadowOf(actions[0].actionIntent)
        val dismiss = shadowOf(actions[1].actionIntent)
        assertTrue(snooze.isBroadcastIntent)
        assertTrue(dismiss.isBroadcastIntent)
        assertEquals(SnoozeReminderReceiver::class.java.name, snooze.savedIntent.component?.className)
        assertEquals(DismissReminderReceiver::class.java.name, dismiss.savedIntent.component?.className)
        assertEquals(DELIVERY_ID, snooze.savedIntent.getLongExtra("deliveryId", -1L))
        assertEquals(DELIVERY_ID, dismiss.savedIntent.getLongExtra("deliveryId", -1L))
        assertEquals(id, snooze.requestCode)
        assertEquals(id xor 0x40000000, dismiss.requestCode)
        assertTrue(snooze.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(dismiss.flags and PendingIntent.FLAG_IMMUTABLE != 0)
        assertTrue(snooze.flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
        assertTrue(dismiss.flags and PendingIntent.FLAG_UPDATE_CURRENT != 0)
    }

    @Test
    fun `INV-10 the bill actions require authentication on API 31 and above for every user and are unchanged below`() {
        var deliveryId = 90L
        for (mode in NotificationPresentationMode.entries) {
            val label = "$mode sdk=$expectedSdk"
            val actions = runAndRead(repoFor(mode), deliveryId++).notification.actions

            assertEquals(label, 2, actions.size)
            if (Build.VERSION.SDK_INT >= 31) {
                actions.forEach { assertTrue("$label ${it.title}", it.isAuthenticationRequired) }
            } else {
                // Below API 31 the platform has no such flag; the actions stay attached exactly as before.
                assertEquals(label, listOf("Snooze", "Dismiss"), actions.map { it.title.toString() })
            }
        }
    }

    // --- AE-11 bill site: lifecycle parity --------------------------------------------------------------

    @Test
    fun `AE-11 bill site DISCREET keeps claim then mark sent once and counts one notification`() {
        val repo = repoFor(NotificationPresentationMode.DISCREET)
        runAndRead(repo, DELIVERY_ID)

        coVerifyOrder {
            coordinator.claimReminderDelivery(DELIVERY_ID)
            coordinator.getDispatchableClaimedReminder(DELIVERY_ID)
            coordinator.markReminderSent(DELIVERY_ID, NotificationId.forBill(DELIVERY_ID).value)
        }
        coVerify(exactly = 1) { coordinator.markReminderSent(any(), any()) }
        coVerify(exactly = 0) { coordinator.markReminderFailed(any(), any()) }
        coVerify(exactly = 0) { coordinator.cancelClaimedReminderDelivery(any(), any()) }
        coVerify(exactly = 1) { runContext.addNotificationsSent() }
        assertEquals("one settings read per post", 1, repo.reads)
    }

    @Test
    fun `the decision is resolved once per post`() {
        val repo = repoFor(NotificationPresentationMode.FULL)
        scriptDue(reminder(1L, 100L), reminder(2L, 101L))

        runTest(timeout = 10.seconds) {
            assertEquals(Result.success(), buildWorker(repo).doWork())
        }

        assertEquals(2, repo.reads)
        assertEquals(2, manager().activeNotifications.size)
        coVerify(exactly = 2) { runContext.addNotificationsSent() }
    }

    // --- AE-9: cancellation ----------------------------------------------------------------------------

    @Test
    fun `AE-9 a cancellation during the settings read propagates posts nothing and records no outcome`() {
        val repo = ScriptedSettingsRepository { throw CancellationException("worker stopped") }
        scriptDue(reminder(DELIVERY_ID, 100L))
        val before = ShadowLog.getLogs().size

        runTest(timeout = 10.seconds) {
            assertFailsWith<CancellationException> { buildWorker(repo).doWork() }
        }

        assertEquals(1, repo.reads)
        assertTrue("nothing may be posted", manager().activeNotifications.isEmpty())
        coVerify(exactly = 0) { coordinator.markReminderSent(any(), any()) }
        coVerify(exactly = 0) { coordinator.markReminderFailed(any(), any()) }
        // The row stays CLAIMED: a cancelled worker must not release or cancel the claim.
        coVerify(exactly = 0) { coordinator.cancelClaimedReminderDelivery(any(), any()) }
        coVerify(exactly = 0) { runContext.addNotificationsSent() }
        coVerify(exactly = 0) { diagnosticEventWriter.emit(any()) }
        assertTrue(
            ShadowLog.getLogs().drop(before).none { it.tag == "BillReminderWorker" && it.type >= android.util.Log.WARN }
        )
    }

    // --- INV-6 / AE-11: failure falls back to DISCREET -------------------------------------------------------

    @Test
    fun `AE-7 an IO error in the settings store posts DISCREET with the marker marks sent once and records the unreadable code`() {
        val repo = ScriptedSettingsRepository {
            AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR)
        }
        val n = runAndRead(repo).notification
        // The resolver emits from its own scope, so the record arrives asynchronously.
        // NotificationPresentationResolverTest also covers the code and cause directly.
        coVerify(timeout = 1_000, exactly = 1) {
            privacyEmitter.emit(
                PrivacyDiagnosticCode.NOTIF_POLICY_SETTINGS_UNREADABLE,
                match { it.entries[PrivacyDiagnosticKey.CAUSE] == SettingsUnreadableCause.IO_ERROR }
            )
        }

        assertEquals(context.getString(R.string.notif_discreet_bill_title), n.titleText())
        assertEquals(context.getString(R.string.notif_discreet_bill_text), n.contentText())
        assertEquals(NotificationKind.BILL.stableToken, NotificationKindMarker.read(n))
        assertNoSentinel(n, "bill unreadable sdk=$expectedSdk")
        coVerify(exactly = 1) { coordinator.markReminderSent(DELIVERY_ID, any()) }
        coVerify(exactly = 1) { runContext.addNotificationsSent() }
    }

    @Test
    fun `a settings read that throws a non cancellation exception posts DISCREET`() {
        val repo = ScriptedSettingsRepository { throw IllegalStateException("secret-store-detail $MERCHANT") }
        val before = ShadowLog.getLogs().size
        val n = runAndRead(repo).notification

        assertEquals(context.getString(R.string.notif_discreet_bill_title), n.titleText())
        assertNoSentinel(n, "bill read exception sdk=$expectedSdk")
        assertTrue(
            ShadowLog.getLogs().drop(before).none { it.msg.contains("secret-store-detail") || it.msg.contains(MERCHANT) }
        )
        coVerify(exactly = 1) { coordinator.markReminderSent(DELIVERY_ID, any()) }
    }

    // --- where resolve sits: after claim and revalidation ---------------------------------------------------

    @Test
    fun `resolve is not reached when the claim is lost and nothing is posted`() {
        val repo = repoFor(NotificationPresentationMode.FULL)
        scriptDue(reminder(DELIVERY_ID, 100L))
        coEvery { coordinator.claimReminderDelivery(DELIVERY_ID) } returns false

        runTest(timeout = 10.seconds) {
            assertEquals(Result.success(), buildWorker(repo).doWork())
        }

        assertEquals("settings must not be read before the claim succeeds", 0, repo.reads)
        assertTrue("nothing may be posted", manager().activeNotifications.isEmpty())
        coVerify(exactly = 0) { coordinator.markReminderSent(any(), any()) }
        coVerify(exactly = 0) { runContext.addNotificationsSent() }
    }

    @Test
    fun `resolve is not reached when the claimed reminder is no longer dispatchable and nothing is posted`() {
        val repo = repoFor(NotificationPresentationMode.FULL)
        scriptDue(reminder(DELIVERY_ID, 100L))
        coEvery { coordinator.getDispatchableClaimedReminder(DELIVERY_ID) } returns null

        runTest(timeout = 10.seconds) {
            assertEquals(Result.success(), buildWorker(repo).doWork())
        }

        assertEquals("settings must not be read before revalidation succeeds", 0, repo.reads)
        assertTrue("nothing may be posted", manager().activeNotifications.isEmpty())
        coVerify(exactly = 0) { coordinator.markReminderSent(any(), any()) }
        coVerify(exactly = 0) { runContext.addNotificationsSent() }
    }

    // --- unchanged failure mapping ------------------------------------------------------------------------

    @Test
    fun `a SecurityException from notify still cancels the claimed delivery and counts nothing in DISCREET`() {
        scriptDue(reminder(DELIVERY_ID, 100L))
        coEvery { coordinator.cancelClaimedReminderDelivery(any(), any()) } returns true
        mockkStatic(NotificationManagerCompat::class)
        val nm = mockk<NotificationManagerCompat>(relaxed = true)
        every { NotificationManagerCompat.from(any<Context>()) } returns nm
        every { nm.notify(any(), any()) } throws SecurityException("/private/receipt/1234.56")

        runTest(timeout = 10.seconds) {
            assertEquals(Result.success(), buildWorker(repoFor(NotificationPresentationMode.DISCREET)).doWork())
        }

        coVerify(exactly = 1) { coordinator.cancelClaimedReminderDelivery(DELIVERY_ID, "notification_permission_revoked") }
        coVerify(exactly = 0) { coordinator.markReminderSent(any(), any()) }
        coVerify(exactly = 0) { coordinator.markReminderFailed(any(), any()) }
        coVerify(exactly = 0) { runContext.addNotificationsSent() }
    }

    companion object {
        private const val DELIVERY_ID = 7L
        private const val MERCHANT = "Zorbas"
        private const val AMOUNT = 1234.56
        private val SENTINELS = listOf(MERCHANT, "1234.56", "USD")
    }
}

// --- one subclass per SDK level (AE-12) ----------------------------------------------------------------------

@Config(sdk = [26])
class BillReminderWorkerNotificationPresentationSdk26Test : BillReminderWorkerNotificationPresentationContract() {
    override val expectedSdk: Int = 26
}

@Config(sdk = [28])
class BillReminderWorkerNotificationPresentationSdk28Test : BillReminderWorkerNotificationPresentationContract() {
    override val expectedSdk: Int = 28
}

@Config(sdk = [29])
class BillReminderWorkerNotificationPresentationSdk29Test : BillReminderWorkerNotificationPresentationContract() {
    override val expectedSdk: Int = 29
}

@Config(sdk = [30])
class BillReminderWorkerNotificationPresentationSdk30Test : BillReminderWorkerNotificationPresentationContract() {
    override val expectedSdk: Int = 30
}

@Config(sdk = [31])
class BillReminderWorkerNotificationPresentationSdk31Test : BillReminderWorkerNotificationPresentationContract() {
    override val expectedSdk: Int = 31
}

@Config(sdk = [34])
class BillReminderWorkerNotificationPresentationSdk34Test : BillReminderWorkerNotificationPresentationContract() {
    override val expectedSdk: Int = 34
}
