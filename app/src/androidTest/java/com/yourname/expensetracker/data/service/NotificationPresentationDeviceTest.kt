package com.yourname.expensetracker.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationMode
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationResolver
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingField
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.AutoLockTimeout
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.data.backup.MaintenanceBlockedReason
import com.yourname.expensetracker.data.backup.MaintenanceDiagnosticRecord
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.domain.util.NotificationId
import com.yourname.expensetracker.domain.util.SystemMonotonicTimeProvider
import com.yourname.expensetracker.domain.util.TimeProvider
import com.yourname.expensetracker.domain.workers.WorkerLeaseRegistry
import com.yourname.expensetracker.service.reminder.DismissReminderReceiver
import com.yourname.expensetracker.service.reminder.SnoozeReminderReceiver
import com.yourname.expensetracker.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Increment 1a gate G1a, device evidence that the Robolectric unit tests cannot give (AE-14 structure,
 * AE-15 and AE-102 silent re-post, AE-112 and AE-121 marker survival through the real system service,
 * AE-32 explicit component, API 31+ authentication-required BILL actions, AE-12 on API 26).
 *
 * Real [AndroidNotificationService], [NotificationPresentationApplier], [NotificationPresentationTexts]
 * and [NotificationPresentationResolver] on the target app context, posting to the real
 * NotificationManager of the device. Only the settings repository is scripted (FULL, then DISCREET) and
 * the diagnostics sinks are no-op fakes. No Hilt and no mocking library is used, so the test also runs on
 * API 26 where inline mocking is unavailable. BILL is posted the way BillReminderWorker builds it
 * (resolver, applier, Snooze and Dismiss broadcast PendingIntents), because the worker's send method is private.
 *
 * Not provable here (manual): true lock-screen rendering of the public version, tapping Snooze or Dismiss
 * on a locked device, and a notification shade screenshot.
 */
@RunWith(AndroidJUnit4::class)
class NotificationPresentationDeviceTest {

    private lateinit var ctx: Context
    private lateinit var manager: NotificationManager
    private lateinit var texts: NotificationPresentationTexts
    private lateinit var applier: NotificationPresentationApplier
    private lateinit var scope: CoroutineScope

    // --- fixtures ---------------------------------------------------------------------------------

    private class DeviceSettingsRepository(private val discreet: Boolean) : AppLockSettingsRepository {
        override suspend fun getLoadState(): AppLockSettingsLoadState =
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = discreet))

        override fun observeLoadState(): Flow<AppLockSettingsLoadState> = emptyFlow()

        override suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult =
            error("not used by the notification service")

        // S18 ii-f (finding 8): the write methods are abstract; this fake does not support them, so they fail closed.
        override suspend fun setAppLockEnabled(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.APP_LOCK_ENABLED)

        override suspend fun setAutoLockTimeout(timeout: AutoLockTimeout): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT)

        override suspend fun setAutoLockTimeoutIfNotWeakening(timeout: AutoLockTimeout): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.AUTO_LOCK_TIMEOUT)

        override suspend fun setDiscreetNotificationsIfNotForced(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.DISCREET)

        override suspend fun setSecureScreenIfNotForced(enabled: Boolean): AppLockSettingsWriteResult =
            AppLockSettingsWriteResult.Failed(AppLockSettingField.SECURE_SCREEN)
    }

    private class NoopDiagnosticWriter : DiagnosticEventWriter {
        override suspend fun emit(event: DiagnosticEvent) = Unit
    }

    private class NoopMaintenanceSink : MaintenanceSafeDiagnosticSink {
        override suspend fun recordBlockedOperation(
            operation: String,
            mode: RestoreMaintenanceMode.Mode,
            pipeline: String?,
            entity: String?,
            reason: MaintenanceBlockedReason
        ) = Unit

        override suspend fun recordDiagnosticEvent(
            event: DiagnosticEvent,
            mode: RestoreMaintenanceMode.Mode,
            writeFailure: Throwable?
        ) = Unit

        override fun observeRecent(): Flow<List<MaintenanceDiagnosticRecord>> = emptyFlow()

        override suspend fun clearOlderThan(cutoffMs: Long) = Unit
    }

    private class FixedTimeProvider : TimeProvider {
        override fun now(): Long = 1_700_000_000_000L
    }

    private class Case(
        val label: String,
        val kind: NotificationKind,
        val id: Int,
        val channelId: String,
        /** Tap data the service must put on the explicit MainActivity intent; null for BILL (no content intent). */
        val expectedData: String?,
        /** PendingIntent request code the service uses for the tap target. */
        val requestCode: Int
    ) {
        override fun toString(): String = label
    }

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ctx = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(ctx.packageName, POST_NOTIFICATIONS)
        }
        manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        texts = NotificationPresentationTexts(ctx)
        applier = NotificationPresentationApplier(ctx, texts)
        scope = CoroutineScope(Job())
        manager.cancelAll()
        assertTrue("notifications must be enabled for the target app on this device", manager.areNotificationsEnabled())
        manager.createNotificationChannel(
            NotificationChannel(BILL_CHANNEL, "Bill Reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    @After
    fun tearDown() {
        manager.cancelAll()
        scope.cancel()
    }

    private fun diagnostics(): PrivacyDiagnosticsEmitter =
        PrivacyDiagnosticsEmitter(
            NotificationDiagnosticEmitter(
                NoopDiagnosticWriter(),
                NoopMaintenanceSink(),
                RestoreMaintenanceMode(
                    ctx,
                    object : dagger.Lazy<WorkerLeaseRegistry> {
                        override fun get(): WorkerLeaseRegistry = error("not used by the notification path")
                    },
                    FixedTimeProvider()
                ),
                Dispatchers.IO,
                FixedTimeProvider()
            ),
            SystemMonotonicTimeProvider()
        )

    private fun resolverFor(mode: NotificationPresentationMode) =
        NotificationPresentationResolver(
            DeviceSettingsRepository(discreet = mode == NotificationPresentationMode.DISCREET),
            diagnostics(),
            scope
        )

    private fun serviceFor(mode: NotificationPresentationMode) =
        AndroidNotificationService(ctx, resolverFor(mode), applier, diagnostics())

    private val cases: List<Case> = listOf(
        Case("BUDGET", NotificationKind.BUDGET, 7, BUDGET_CHANNEL, "expensetracker://home", 0),
        Case("WARRANTY", NotificationKind.WARRANTY, 10003, BUDGET_CHANNEL, "expensetracker://home", 0),
        Case("RECEIPT_MATCH", NotificationKind.RECEIPT_MATCH, 20042, BUDGET_CHANNEL, "expensetracker://home", 0),
        Case(
            "ANOMALY", NotificationKind.ANOMALY, 40077, ANOMALY_CHANNEL,
            "expensetracker://activity?expenseId=$EXPENSE_ID", 40077
        ),
        Case(
            "BRIEFING", NotificationKind.BRIEFING, 42, AI_CHANNEL,
            "expensetracker://dashboard?briefingKey=dashboard_home%3A2026-10-23", 42
        ),
        Case("BILL", NotificationKind.BILL, NotificationId.forBill(DELIVERY_ID).value, BILL_CHANNEL, null, 0)
    )

    private val tapCases: List<Case> get() = cases.filter { it.expectedData != null }

    // --- posting and reading ------------------------------------------------------------------------

    private fun post(case: Case, mode: NotificationPresentationMode) {
        if (case.kind == NotificationKind.BILL) {
            postBill(case, mode)
            return
        }
        val service = serviceFor(mode)
        val result = runBlocking {
            when (case.kind) {
                NotificationKind.ANOMALY ->
                    service.sendAnomalyAlertForKind(case.kind, case.id, SENTINEL_TITLE, SENTINEL_TEXT, EXPENSE_ID)
                NotificationKind.BRIEFING ->
                    service.sendAiBriefingReadyForKind(case.kind, case.id, SENTINEL_TITLE, SENTINEL_TEXT, BRIEFING_KEY)
                else ->
                    service.sendBudgetAlertForKind(case.kind, case.id, SENTINEL_TITLE, SENTINEL_TEXT)
            }
        }
        assertEquals("${case.label} $mode", NotificationService.DeliveryResult.DELIVERED, result)
    }

    /** The BILL notification as BillReminderWorker builds it: resolver, then the applier with Snooze and Dismiss. */
    private fun postBill(case: Case, mode: NotificationPresentationMode) {
        val decision = runBlocking { resolverFor(mode).resolve(NotificationKind.BILL, Build.VERSION.SDK_INT) }
        val builder = NotificationCompat.Builder(ctx, BILL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
        val notification = applier.apply(
            builder = builder,
            decision = decision,
            channelId = BILL_CHANNEL,
            smallIcon = android.R.drawable.ic_dialog_info,
            detailedTitle = SENTINEL_TITLE,
            detailedText = SENTINEL_TEXT,
            actions = billActionSpecs(case.id)
        ).build()
        manager.notify(case.id, notification)
    }

    private fun billActionSpecs(id: Int): List<NotificationActionSpec> {
        val snooze = PendingIntent.getBroadcast(
            ctx,
            id,
            Intent(ctx, SnoozeReminderReceiver::class.java).putExtra("deliveryId", DELIVERY_ID),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val dismiss = PendingIntent.getBroadcast(
            ctx,
            id xor 0x40000000,
            Intent(ctx, DismissReminderReceiver::class.java).putExtra("deliveryId", DELIVERY_ID),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return listOf(
            NotificationActionSpec(R.drawable.ic_snooze, R.string.notif_action_snooze, snooze),
            NotificationActionSpec(R.drawable.ic_dismiss, R.string.action_dismiss, dismiss)
        )
    }

    /** Active notifications of this app, without the system's own auto-group summary (the app never posts summaries). */
    private fun ownActive(): List<StatusBarNotification> =
        manager.activeNotifications.filter { (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0 }

    /** Bounded poll: the platform may take a moment to list a just-posted notification. No other waiting is done. */
    private fun awaitOwn(label: String, predicate: (List<StatusBarNotification>) -> Boolean): List<StatusBarNotification> {
        val deadline = SystemClock.elapsedRealtime() + POLL_TIMEOUT_MS
        var last = ownActive()
        while (!predicate(last)) {
            if (SystemClock.elapsedRealtime() > deadline) {
                fail("$label: timed out waiting for the platform; active ids=${last.map { it.id }}")
            }
            Thread.sleep(POLL_STEP_MS)
            last = ownActive()
        }
        return last
    }

    private fun awaitIds(label: String, ids: Set<Int>): List<StatusBarNotification> =
        awaitOwn(label) { list -> list.map { it.id }.toSet().containsAll(ids) }

    /** The title the notification must carry once the post for [mode] has landed: the sentinel in FULL, the kind constant in DISCREET. */
    private fun expectedTitle(case: Case, mode: NotificationPresentationMode): String =
        if (mode == NotificationPresentationMode.FULL) SENTINEL_TITLE else texts.discreet(case.kind).title

    private fun List<StatusBarNotification>.hasTitle(case: Case, mode: NotificationPresentationMode): Boolean =
        singleOrNull { it.id == case.id }?.notification?.titleText() == expectedTitle(case, mode)

    /**
     * Posts one case and waits for the mode's title, not just the id. A post over an existing id of the other
     * mode is an update, and the platform drops updates silently above its enqueue rate, so waiting for the id
     * alone could read the previous mode's notification. Posts are spaced below 5 per second (no global setting is changed).
     */
    private fun postAndAwait(case: Case, mode: NotificationPresentationMode, label: String): StatusBarNotification {
        post(case, mode)
        Thread.sleep(POST_SPACING_MS)
        return awaitOwn("$label (title for $mode)") { list -> list.hasTitle(case, mode) }.single { it.id == case.id }
    }

    private fun postAll(mode: NotificationPresentationMode): Map<Int, StatusBarNotification> {
        cases.forEach {
            post(it, mode)
            Thread.sleep(POST_SPACING_MS)
        }
        val active = awaitOwn("post all $mode") { list -> cases.all { list.hasTitle(it, mode) } }
        return cases.associate { case -> case.id to active.single { it.id == case.id } }
    }

    // --- reading helpers ----------------------------------------------------------------------------

    private fun Notification.titleText(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.contentText(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** Every string reachable from the notification: ticker, every extras key and value (recursive), actions and the public version. */
    private fun everyString(n: Notification): List<String> {
        val out = mutableListOf<String>()
        n.tickerText?.let { out += it.toString() }
        flattenInto(out, n.extras)
        n.actions?.forEach {
            out += it.title.toString()
            flattenInto(out, it.extras)
        }
        n.publicVersion?.let { out += everyString(it) }
        return out
    }

    @Suppress("DEPRECATION")
    private fun flattenInto(out: MutableList<String>, value: Any?) {
        when (value) {
            null -> Unit
            is Bundle -> value.keySet().forEach { key ->
                out += key
                flattenInto(out, value.get(key))
            }
            is CharSequence -> out += value.toString()
            is Array<*> -> value.forEach { flattenInto(out, it) }
            is Iterable<*> -> value.forEach { flattenInto(out, it) }
            else -> out += value.toString()
        }
    }

    private fun assertNoSentinel(n: Notification, label: String) {
        val all = everyString(n)
        SENTINELS.forEach { sentinel ->
            all.forEach { s -> assertFalse("$label: '$sentinel' leaked into '$s'", s.contains(sentinel)) }
        }
    }

    /** FULL-mode scan: only the action titles, the action extras and the public version, not the detailed title or text. */
    private fun assertNoSentinelInActionsAndPublicVersion(n: Notification, label: String) {
        val scanned = mutableListOf<String>()
        n.actions?.forEach {
            scanned += it.title.toString()
            flattenInto(scanned, it.extras)
        }
        n.publicVersion?.let { scanned += everyString(it) }
        SENTINELS.forEach { sentinel ->
            scanned.forEach { s -> assertFalse("$label: '$sentinel' leaked into '$s'", s.contains(sentinel)) }
        }
    }

    @Suppress("DEPRECATION")
    private fun keysHoldingToken(extras: Bundle, token: String): Set<String> =
        extras.keySet().filter { key ->
            val v = extras.get(key)
            v is CharSequence && v.toString() == token
        }.toSet()

    // --- tap target introspection (AE-32) -------------------------------------------------------------

    private fun tapIntent(data: String, explicit: Boolean): Intent =
        if (explicit) {
            Intent(ctx, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                this.data = Uri.parse(data)
            }
        } else {
            Intent(Intent.ACTION_VIEW, Uri.parse(data))
        }

    /**
     * Looks up an existing activity PendingIntent without creating one. The system matches on request code
     * and Intent.filterEquals (action, data, component, package, categories), so a non-null result proves a
     * PendingIntent with exactly that target exists.
     */
    private fun lookupTap(case: Case, explicit: Boolean): PendingIntent? =
        PendingIntent.getActivity(
            ctx,
            case.requestCode,
            tapIntent(case.expectedData!!, explicit),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

    private fun cancelTap(case: Case) {
        lookupTap(case, explicit = true)?.cancel()
    }

    private fun assertExplicitTap(case: Case, sbn: StatusBarNotification, label: String) {
        val pending = sbn.notification.contentIntent
        assertNotNull("$label content intent", pending)
        assertEquals("$label creator package", ctx.packageName, pending!!.creatorPackage)
        assertNotNull("$label an activity PendingIntent with the explicit MainActivity component and data exists", lookupTap(case, explicit = true))
        assertNull("$label control: the same target without the component is a different PendingIntent", lookupTap(case, explicit = false))
    }

    private fun shell(command: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    // --- AE-14: structure of the public version ---------------------------------------------------------

    @Test
    // AE-14 every kind in FULL and DISCREET is PRIVATE with a public version that is only the generic pair
    fun ae14_everyKindInFullAndDiscreet_isPrivateWithGenericPublicVersion() {
        val generic = texts.genericPublic()
        for (mode in NotificationPresentationMode.entries) {
            val active = postAll(mode)
            for (case in cases) {
                val label = "${case.label} $mode sdk=${Build.VERSION.SDK_INT}"
                val n = active.getValue(case.id).notification
                val pub = n.publicVersion

                assertEquals(label, Notification.VISIBILITY_PRIVATE, n.visibility)
                assertNotEquals(label, Notification.VISIBILITY_PUBLIC, n.visibility)
                assertNotNull("$label public version survives the system round trip", pub)
                assertEquals(label, generic.title, pub!!.titleText())
                assertEquals(label, generic.text, pub.contentText())
                assertNull("$label public version has no content intent", pub.contentIntent)
                assertTrue("$label public version has no actions", pub.actions.isNullOrEmpty())
                assertNull("$label public version has no ticker", pub.tickerText)
                assertNull("$label public version has no style", pub.extras.getString(Notification.EXTRA_TEMPLATE))
                assertNull("$label public version has no big text", pub.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                assertNull("$label public version carries no marker", NotificationKindMarker.read(pub))
                assertNoSentinel(pub, "$label public")
            }
        }
    }

    // --- AE-111 / AE-14 leak scan, not vacuous -------------------------------------------------------------

    @Test
    // control FULL carries the sentinel so the DISCREET scan is not vacuous
    fun control_fullCarriesSentinel_soDiscreetScanIsNotVacuous() {
        val active = postAll(NotificationPresentationMode.FULL)
        for (case in cases) {
            val n = active.getValue(case.id).notification
            assertEquals(case.label, SENTINEL_TITLE, n.titleText())
            assertEquals(case.label, SENTINEL_TEXT, n.contentText())
            assertTrue(case.label, everyString(n).any { it.contains(SENTINELS.first()) })
        }
    }

    @Test
    // AE-111 DISCREET shows only the kind constants and no sentinel anywhere in the recursive extras scan
    fun ae111_discreetShowsOnlyKindConstants_noSentinelInRecursiveExtrasScan() {
        val active = postAll(NotificationPresentationMode.DISCREET)
        for (case in cases) {
            val label = "${case.label} sdk=${Build.VERSION.SDK_INT}"
            val n = active.getValue(case.id).notification

            assertEquals(label, texts.discreet(case.kind).title, n.titleText())
            assertEquals(label, texts.discreet(case.kind).text, n.contentText())
            assertNull("$label no expanded text", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            assertNull("$label no ticker", n.tickerText)
            assertNoSentinel(n, label)
        }
    }

    // --- AE-112 / AE-121 marker survival -----------------------------------------------------------------------

    @Test
    // AE-112 the kind marker survives the system round trip on the private notification in FULL and DISCREET
    fun ae112_kindMarkerSurvivesSystemRoundTripOnPrivateNotificationInFullAndDiscreet() {
        for (mode in NotificationPresentationMode.entries) {
            val active = postAll(mode)
            for (case in cases) {
                val label = "${case.label} $mode sdk=${Build.VERSION.SDK_INT}"
                val n = active.getValue(case.id).notification

                assertEquals(label, case.kind.stableToken, NotificationKindMarker.read(n))
                assertEquals(label, case.kind.stableToken, n.extras.getString(NotificationKindMarker.EXTRA_KEY))
                assertEquals(
                    "$label the marker key is the only extras entry holding the token",
                    setOf(NotificationKindMarker.EXTRA_KEY),
                    keysHoldingToken(n.extras, case.kind.stableToken)
                )
                NotificationKind.entries.filter { it != case.kind }.forEach { other ->
                    assertTrue(
                        "$label foreign token ${other.stableToken}",
                        keysHoldingToken(n.extras, other.stableToken).isEmpty()
                    )
                }
                listOf(Notification.EXTRA_TITLE, Notification.EXTRA_TEXT, Notification.EXTRA_SUB_TEXT).forEach { key ->
                    assertNotEquals("$label $key", case.kind.stableToken, n.extras.getCharSequence(key)?.toString())
                }
            }
        }
    }

    // --- AE-32: explicit component ------------------------------------------------------------------------------

    @Test
    // AE-32 the content intent of every tap kind targets the explicit MainActivity component in FULL and DISCREET
    fun ae32_contentIntentOfEveryTapKind_targetsExplicitMainActivityInFullAndDiscreet() {
        for (mode in NotificationPresentationMode.entries) {
            for (case in tapCases) {
                val label = "${case.label} $mode sdk=${Build.VERSION.SDK_INT}"
                cancelTap(case)
                assertNull("$label precondition: no stale PendingIntent for this target", lookupTap(case, explicit = true))

                val sbn = postAndAwait(case, mode, label)

                assertExplicitTap(case, sbn, label)
                if (Build.VERSION.SDK_INT >= 31) {
                    assertTrue("$label is an activity PendingIntent", sbn.notification.contentIntent.isActivity)
                }
            }
        }
    }

    @Test
    // AE-32 the BILL notification has no content intent so no tap target is invented
    fun ae32_billNotificationHasNoContentIntent_soNoTapTargetIsInvented() {
        val bill = cases.single { it.kind == NotificationKind.BILL }
        post(bill, NotificationPresentationMode.DISCREET)
        val sbn = awaitIds("bill", setOf(bill.id)).single { it.id == bill.id }

        assertNull(sbn.notification.contentIntent)
    }

    @Test
    // AE-32 dumpsys lists every startActivity PendingIntent of the app with an explicit component
    fun ae32_dumpsysListsEveryStartActivityPendingIntentOfAppWithExplicitComponent() {
        tapCases.forEach { post(it, NotificationPresentationMode.DISCREET) }
        awaitIds("dumpsys", tapCases.map { it.id }.toSet())

        val dump = shell("dumpsys activity intents")
        val ours = dump.lines().filter { it.contains("startActivity") && it.contains("pkg=${ctx.packageName}") }
        if (ours.isEmpty()) {
            // The dump layout differs on this API level; the PendingIntent lookup tests above are the assertion of record.
            Log.i(TAG, "AE-32 dumpsys layout not recognised on sdk=${Build.VERSION.SDK_INT}; ${dump.length} chars, no startActivity records for the app")
            return
        }
        ours.forEach { line ->
            assertTrue("startActivity record without an explicit component: $line", line.contains("cmp=${ctx.packageName}/"))
            assertTrue("startActivity record not targeting MainActivity: $line", line.contains("MainActivity"))
        }
    }

    // --- API 31+ authentication-required BILL actions -------------------------------------------------------------------

    @Test
    // INV-10 BILL Snooze and Dismiss are authentication required exactly on API 31 and above in FULL and DISCREET
    fun inv10_billSnoozeAndDismiss_areAuthenticationRequiredExactlyOnApi31AndAbove_inFullAndDiscreet() {
        val bill = cases.single { it.kind == NotificationKind.BILL }
        for (mode in NotificationPresentationMode.entries) {
            val label = "BILL $mode sdk=${Build.VERSION.SDK_INT}"
            val n = postAndAwait(bill, mode, label).notification
            val actions = n.actions

            assertNotNull(label, actions)
            assertEquals(label, 2, actions.size)
            assertEquals(label, ctx.getString(R.string.notif_action_snooze), actions[0].title.toString())
            assertEquals(label, ctx.getString(R.string.action_dismiss), actions[1].title.toString())
            assertNotNull(label, actions[0].actionIntent)
            assertNotNull(label, actions[1].actionIntent)
            if (mode == NotificationPresentationMode.FULL) {
                // The detailed title and text legitimately carry the merchant in FULL: scan only the surfaces
                // that must stay clean in every mode (the action titles and extras, and the public version).
                assertNoSentinelInActionsAndPublicVersion(n, label)
            } else {
                assertNoSentinel(n, label)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                assertTrue("$label Snooze", actions[0].isAuthenticationRequired)
                assertTrue("$label Dismiss", actions[1].isAuthenticationRequired)
            } else {
                // Below API 31 the platform has no such flag: the actions stay as supplied (policy UNCHANGED).
                Log.i(TAG, "isAuthenticationRequired is not queryable on sdk=${Build.VERSION.SDK_INT}; actions kept as supplied")
            }
        }
    }

    @Test
    // control an action built without authentication required reports false on API 31 and above
    fun control_actionBuiltWithoutAuthenticationRequired_reportsFalseOnApi31AndAbove() {
        if (Build.VERSION.SDK_INT < 31) return
        val plain = NotificationCompat.Builder(ctx, BILL_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("control")
            .addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_snooze,
                    "control",
                    billActionSpecs(CONTROL_ID).first().pendingIntent
                ).build()
            )
            .build()
        manager.notify(CONTROL_ID, plain)
        val n = awaitIds("control", setOf(CONTROL_ID)).single { it.id == CONTROL_ID }.notification

        assertFalse(n.actions[0].isAuthenticationRequired)
    }

    // --- AE-15 / AE-102: the silent in-place re-post ---------------------------------------------------------------------

    private class PostedSnapshot(val whenMillis: Long, val tag: String?, val channelId: String, val flags: Int)

    @Test
    // AE-15 AE-102 the sweep re-posts every FULL notification in place as DISCREET, silent, with the original time, id, tag and channel
    fun ae15_ae102_sweepRepostsEveryFullNotificationInPlaceAsDiscreetSilentWithOriginalTimeIdTagAndChannel() {
        val originals = postAll(NotificationPresentationMode.FULL)
        val before = originals.mapValues { (_, sbn) ->
            PostedSnapshot(sbn.notification.`when`, sbn.tag, sbn.notification.channelId, sbn.notification.flags)
        }
        cases.forEach { case ->
            val label = "${case.label} original"
            assertEquals("$label is not silent", 0, before.getValue(case.id).flags and Notification.FLAG_ONLY_ALERT_ONCE)
            assertTrue(label, everyString(originals.getValue(case.id).notification).any { it.contains(SENTINELS.first()) })
        }
        val countBefore = ownActive().size
        // Let the platform enqueue-rate window drain so the measured result does not depend on rate state.
        Thread.sleep(PRE_SWEEP_QUIET_MS)

        val result = runBlocking { serviceFor(NotificationPresentationMode.DISCREET).repostActiveAsDiscreet() }

        assertEquals(NotificationService.RepostResult(reposted = cases.size, failed = 0), result)
        val after = awaitOwn("sweep") { list ->
            cases.all { case ->
                list.singleOrNull { it.id == case.id }?.notification?.titleText() == texts.discreet(case.kind).title
            }
        }
        assertEquals("count matches: replaced in place, nothing added or lost", countBefore, after.size)
        for (case in cases) {
            val label = "${case.label} reposted sdk=${Build.VERSION.SDK_INT}"
            val sbn = after.single { it.id == case.id }
            val n = sbn.notification
            val was = before.getValue(case.id)

            assertEquals("$label id", case.id, sbn.id)
            assertEquals("$label tag", was.tag, sbn.tag)
            assertEquals("$label channel", was.channelId, n.channelId)
            assertEquals("$label original post time", was.whenMillis, n.`when`)
            assertTrue("$label is silent", (n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
            assertEquals(label, Notification.VISIBILITY_PRIVATE, n.visibility)
            assertEquals(label, texts.discreet(case.kind).text, n.contentText())
            assertEquals("$label fresh marker", case.kind.stableToken, NotificationKindMarker.read(n))
            assertNull("$label no marker on the public version", NotificationKindMarker.read(n.publicVersion))
            assertEquals(label, texts.genericPublic().title, n.publicVersion?.titleText())
            assertNull("$label no expanded text", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            assertNoSentinel(n, label)
            if (case.expectedData != null) assertExplicitTap(case, sbn, label) else assertNull(label, n.contentIntent)
        }
        val afterActions = awaitOwn("sweep BILL actions") { list ->
            list.single { it.id == cases.last().id }.notification.actions?.size == 2
        }
        val billActions = afterActions.single { it.id == cases.last().id }.notification.actions
        assertEquals(ctx.getString(R.string.notif_action_snooze), billActions[0].title.toString())
        assertEquals(ctx.getString(R.string.action_dismiss), billActions[1].title.toString())
        if (Build.VERSION.SDK_INT >= 31) {
            assertTrue(billActions[0].isAuthenticationRequired)
            assertTrue(billActions[1].isAuthenticationRequired)
        }
    }

    // --- AE-112 marker-less fallback (upgrade path 1) --------------------------------------------------------------------------

    @Test
    // AE-112 marker-less FULL originals are classified by channel and id range and re-posted DISCREET
    fun ae112_markerLessFullOriginals_areClassifiedByChannelAndIdRange_andRepostedDiscreet() {
        serviceFor(NotificationPresentationMode.FULL) // creates the three app channels
        val originals = listOf(
            Triple(7, BUDGET_CHANNEL, NotificationKind.BUDGET),
            Triple(10003, BUDGET_CHANNEL, NotificationKind.WARRANTY),
            Triple(20042, BUDGET_CHANNEL, NotificationKind.RECEIPT_MATCH),
            Triple(30011, BILL_CHANNEL, NotificationKind.BILL),
            Triple(40077, ANOMALY_CHANNEL, NotificationKind.ANOMALY),
            Triple(42, AI_CHANNEL, NotificationKind.BRIEFING)
        )
        originals.forEach { (id, channel, kind) ->
            val builder = NotificationCompat.Builder(ctx, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(SENTINEL_TITLE)
                .setContentText(SENTINEL_TEXT)
                .setSubText(SENTINEL_TEXT)
                .setTicker(SENTINEL_TEXT)
                .setStyle(NotificationCompat.BigTextStyle().bigText(SENTINEL_TEXT))
            if (kind == NotificationKind.BILL) {
                billActionSpecs(id).forEach { builder.addAction(android.R.drawable.ic_dialog_info, "$SENTINEL_TITLE action", it.pendingIntent) }
            }
            manager.notify(id, builder.build())
        }
        val active = awaitIds("marker-less originals", originals.map { it.first }.toSet())
        originals.forEach { (id, _, kind) ->
            val n = active.single { it.id == id }.notification
            assertNull("control: ${kind.name} original has no marker", NotificationKindMarker.read(n))
            assertEquals("control: ${kind.name} original is FULL", SENTINEL_TITLE, n.titleText())
        }
        // Let the platform enqueue-rate window drain so the measured result does not depend on rate state.
        Thread.sleep(PRE_SWEEP_QUIET_MS)

        val result = runBlocking { serviceFor(NotificationPresentationMode.DISCREET).repostActiveAsDiscreet() }

        assertEquals(NotificationService.RepostResult(reposted = originals.size, failed = 0), result)
        val after = awaitOwn("marker-less sweep") { list ->
            originals.all { (id, _, kind) ->
                list.singleOrNull { it.id == id }?.notification?.titleText() == texts.discreet(kind).title
            }
        }
        originals.forEach { (id, channel, kind) ->
            val label = "${kind.name} id $id sdk=${Build.VERSION.SDK_INT}"
            val n = after.single { it.id == id }.notification
            assertEquals(label, channel, n.channelId)
            assertEquals(label, texts.discreet(kind).text, n.contentText())
            assertEquals("$label marker stamped by the re-post", kind.stableToken, NotificationKindMarker.read(n))
            assertTrue("$label is silent", (n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
            assertNoSentinel(n, label)
            if (kind == NotificationKind.BILL) {
                assertEquals(label, 2, n.actions?.size)
                assertEquals(label, ctx.getString(R.string.notif_action_snooze), n.actions[0].title.toString())
            }
        }
    }

    private companion object {
        const val TAG = "G1aDeviceTest"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val POLL_TIMEOUT_MS = 5_000L
        const val POLL_STEP_MS = 50L

        // Keeps the test's own posts under the platform enqueue rate (5 per second) without touching global settings.
        const val POST_SPACING_MS = 250L

        // Quiet gap between the test's last post and the sweep under test (at least 1 s).
        const val PRE_SWEEP_QUIET_MS = 1_000L

        const val BUDGET_CHANNEL = "budget_alerts"
        const val AI_CHANNEL = "ai_briefings"
        const val ANOMALY_CHANNEL = "anomaly_alerts"
        const val BILL_CHANNEL = "bill_reminders"

        const val DELIVERY_ID = 7L
        const val CONTROL_ID = 99001
        const val EXPENSE_ID = 9100077L
        const val BRIEFING_KEY = "dashboard_home:2026-10-23"

        // Distinctive on purpose: a short numeric sentinel could collide with a hash in a Parcelable toString.
        const val SENTINEL_TITLE = "SENTINEL_MERCHANT_Zorkx SENTINEL_PERSON_Quillfeather"
        const val SENTINEL_TEXT = "SENTINEL_MERCHANT_Zorkx SENTINEL_PERSON_Quillfeather spent SENTINEL_AMOUNT_8841.37 EUR"
        val SENTINELS = listOf("SENTINEL_MERCHANT_Zorkx", "SENTINEL_PERSON_Quillfeather", "SENTINEL_AMOUNT_8841.37")
    }
}
