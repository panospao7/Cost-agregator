package com.yourname.expensetracker.data.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.data.service.PresentationFixtures.SENTINELS
import com.yourname.expensetracker.data.service.PresentationFixtures.SENTINEL_TEXT
import com.yourname.expensetracker.data.service.PresentationFixtures.SENTINEL_TITLE
import com.yourname.expensetracker.data.service.PresentationFixtures.ScriptedSettingsRepository
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationResolver
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.ui.MainActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.seconds

/**
 * S9b, the discreet re-post sweep of [AndroidNotificationService.repostActiveAsDiscreet]
 * (AE-15, AE-102, AE-112, AE-121, INV-13, AMD-7, AMD-21, AMD-22, spec 4.6).
 *
 * Real Robolectric NotificationManager, real applier and texts. Originals are built here directly, FULL with
 * sentinels in every text-bearing field, so a copy of anything from an original would show as a sentinel.
 * The diagnostics emitter is a capturing double.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNotificationServiceRepostTest {

    private lateinit var appContext: Context
    private lateinit var texts: NotificationPresentationTexts
    private lateinit var scope: CoroutineScope
    private val emitted = mutableListOf<Emitted>()

    private class Emitted(val code: PrivacyDiagnosticCode, val metadata: PrivacyDiagnosticMetadata)

    private val emitter: PrivacyDiagnosticsEmitter = mockk {
        coEvery { emit(any(), any()) } coAnswers { emitted += Emitted(firstArg(), secondArg()) }
    }

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        texts = NotificationPresentationTexts(appContext)
        scope = CoroutineScope(Job())
        val manager = realManager()
        listOf(BUDGET, BILL, ANOMALY, AI, FGS).forEach {
            manager.createNotificationChannel(NotificationChannel(it, it, NotificationManager.IMPORTANCE_HIGH))
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun realManager(): NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private fun serviceWith(manager: NotificationManager, pacer: SweepPacer? = null): AndroidNotificationService {
        val context = object : ContextWrapper(appContext) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.NOTIFICATION_SERVICE) manager else super.getSystemService(name)
        }
        val repo = ScriptedSettingsRepository {
            AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(discreetNotifications = true))
        }
        val resolver = NotificationPresentationResolver(repo, mockk<PrivacyDiagnosticsEmitter>(relaxed = true), scope)
        val applier = NotificationPresentationApplier(appContext, texts)
        return if (pacer == null) {
            AndroidNotificationService(context, resolver, applier, emitter)
        } else {
            AndroidNotificationService(context, resolver, applier, emitter, pacer)
        }
    }

    private fun service(): AndroidNotificationService = serviceWith(realManager())

    private fun sweep(service: AndroidNotificationService = service()): NotificationService.RepostResult {
        var result: NotificationService.RepostResult? = null
        runTest(timeout = 10.seconds) { result = service.repostActiveAsDiscreet() }
        return result!!
    }

    private fun whenFor(id: Int): Long = 1_700_000_000_000L + id

    private fun tapIntent(id: Int): PendingIntent =
        PendingIntent.getActivity(
            appContext,
            id,
            Intent(appContext, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.parse("expensetracker://original/$id")
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun actionIntent(id: Int, name: String): PendingIntent =
        PendingIntent.getBroadcast(
            appContext,
            if (name == "snooze") id else id xor 0x40000000,
            Intent("com.yourname.test.$name").apply { putExtra("deliveryId", id.toLong()) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** Posts a FULL original with the sentinel in every text-bearing field. [marker] is stored as given (may be junk). */
    private fun postOriginal(
        id: Int,
        channel: String,
        marker: String?,
        withActions: Boolean = false,
        actionNames: List<String> = if (withActions) listOf("snooze", "dismiss") else emptyList(),
        tag: String? = null,
        contentIntent: Boolean = true,
        customize: (NotificationCompat.Builder) -> Unit = {}
    ) {
        val publicVersion = NotificationCompat.Builder(appContext, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(SENTINEL_TITLE)
            .setContentText(SENTINEL_TEXT)
            .build()
        val builder = NotificationCompat.Builder(appContext, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(SENTINEL_TITLE)
            .setContentText(SENTINEL_TEXT)
            .setSubText(SENTINEL_TEXT)
            .setContentInfo(SENTINEL_TEXT)
            .setTicker(SENTINEL_TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(SENTINEL_TEXT).setSummaryText(SENTINEL_TEXT))
            .setPublicVersion(publicVersion)
            .setWhen(whenFor(id))
            .addExtras(Bundle().apply { putString("test.custom.sentinel", SENTINEL_TEXT) })
        if (contentIntent) builder.setContentIntent(tapIntent(id))
        if (marker != null) {
            builder.addExtras(Bundle().apply { putString(NotificationKindMarker.EXTRA_KEY, marker) })
        }
        actionNames.forEach { name ->
            builder.addAction(android.R.drawable.ic_dialog_info, "Lidl $name 4242", actionIntent(id, name))
        }
        customize(builder)
        realManager().notify(tag, id, builder.build())
    }

    private fun active(id: Int): StatusBarNotification {
        val matches = realManager().activeNotifications.filter { it.id == id }
        assertEquals("exactly one active notification with id $id", 1, matches.size)
        return matches.single()
    }

    private fun Notification.titleText(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.contentText(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    private fun assertNoSentinel(n: Notification, label: String) {
        PresentationFixtures.everyString(n).forEach { s ->
            SENTINELS.forEach { sentinel ->
                assertFalse("$label: '$sentinel' leaked into '$s'", s.contains(sentinel))
            }
        }
    }

    /** One re-posted notification: kind constants, preserved identity and tap target, silent, clean of sentinels. */
    private fun assertRepostedAs(id: Int, channel: String, kind: NotificationKind, tag: String? = null) {
        val sb = active(id)
        val n = sb.notification
        val label = "id $id as ${kind.name}"
        val discreet = texts.discreet(kind)
        assertEquals(label, discreet.title, n.titleText())
        assertEquals(label, discreet.text, n.contentText())
        assertEquals(label, channel, n.channelId)
        assertEquals(label, tag, sb.tag)
        assertEquals(label, whenFor(id), n.`when`)
        assertTrue("$label is silent", (n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertEquals(label, Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(label, kind.stableToken, NotificationKindMarker.read(n))
        assertNull(label, NotificationKindMarker.read(n.publicVersion))
        assertNull("$label has no expanded style", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertNull("$label has no template", n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertFalse("$label has no custom extra", n.extras.containsKey("test.custom.sentinel"))
        assertNull("$label has no sub text", n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
        assertNull("$label has no info text", n.extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
        assertNull("$label has no ticker", n.tickerText)
        assertNoSentinel(n, label)
        // The tap target is the original's, reused.
        val tap = shadowOf(n.contentIntent)
        assertEquals(label, "expensetracker://original/$id", tap.savedIntent.dataString)
        assertEquals(label, id, tap.requestCode)
        assertEquals(label, MainActivity::class.java.name, tap.savedIntent.component?.className)
    }

    // --- AE-15 -----------------------------------------------------------------------------------

    @Test
    fun `AE-15 an active FULL anomaly notification is re-posted with the ANOMALY discreet constants and the same content intent`() {
        postOriginal(40077, ANOMALY, marker = NotificationKind.ANOMALY.stableToken)

        val result = sweep()

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        assertRepostedAs(40077, ANOMALY, NotificationKind.ANOMALY)
        assertEquals(1, realManager().activeNotifications.size)
    }

    @Test
    fun `AE-15 a sweep with nothing active re-posts nothing and records nothing`() {
        assertEquals(NotificationService.RepostResult.NONE, sweep())
        assertTrue(emitted.isEmpty())
    }

    // --- AE-102 ----------------------------------------------------------------------------------

    @Test
    fun `AE-102 the sweep re-posts silently, keeps the post time, skips a dismissed notification and records the count`() {
        postOriginal(7, BUDGET, marker = null)
        postOriginal(8, BUDGET, marker = null)
        realManager().cancel(8) // dismissed before the sweep

        val result = sweep()

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        assertRepostedAs(7, BUDGET, NotificationKind.BUDGET)
        assertTrue("dismissed notification is not re-posted", realManager().activeNotifications.none { it.id == 8 })
        val event = emitted.single()
        assertEquals(PrivacyDiagnosticCode.NOTIF_REPOSTED_DISCREET, event.code)
        assertEquals(1, event.metadata.entries[PrivacyDiagnosticKey.COUNT])
    }

    // G1b-0: the sweep is now followed by one settle + one re-read + at most one re-post of every sweep-kind
    // notification still not carrying token "D". A notification whose re-post threw stays FULL, so it is
    // attempted a second time (and only a second time) by the verification pass. The scripted manager below
    // passes every notify through to the real Robolectric manager, so what the verification re-reads is the
    // post-sweep state.

    @Test
    fun `AE-102 a SecurityException skips that notification, re-posts the other, records the class only and retries only in the single verification pass`() {
        postOriginal(7, BUDGET, marker = null)
        postOriginal(8, BUDGET, marker = null)
        val timeline = mutableListOf<String>()
        val base = scriptedManager(timeline)
        val manager = mockk<NotificationManager>(relaxed = true)
        every { manager.activeNotifications } answers { base.activeNotifications }
        every { manager.notify(any<String>(), eq(7), any()) } throws SecurityException("secret detail Lidl 4242")
        every { manager.notify(any<String>(), eq(8), any()) } answers {
            base.notify(firstArg<String?>(), 8, thirdArg<Notification>())
        }

        val result = sweep(serviceWith(manager))

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 1), result)
        verify(exactly = 2) { manager.activeNotifications }
        verify(exactly = 2) { manager.notify(any<String>(), eq(7), any()) } // sweep + the one verification pass
        verify(exactly = 1) { manager.notify(any<String>(), eq(8), any()) } // 8 is "D" by the verification read
        assertEquals(texts.discreet(NotificationKind.BUDGET).title, active(8).notification.titleText())
        assertEquals("D", NotificationPresentationApplier.readModeToken(active(8).notification))

        val failures = emitted.filter { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_FAILED }
        assertTrue(failures.isNotEmpty())
        failures.forEach {
            assertEquals("SecurityException", it.metadata.entries[PrivacyDiagnosticKey.EXCEPTION_CLASS])
            assertEquals(setOf(PrivacyDiagnosticKey.EXCEPTION_CLASS), it.metadata.entries.keys)
        }
        val done = emitted.single { it.code == PrivacyDiagnosticCode.NOTIF_REPOSTED_DISCREET }
        assertEquals(1, done.metadata.entries[PrivacyDiagnosticKey.COUNT])
        assertTrue("a re-post that threw is not counted as unconfirmed", emitted.none { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED })
        emitted.forEach { e -> e.metadata.entries.values.forEach { assertFalse(it.toString().contains("secret")) } }
    }

    @Test
    fun `AE-102 a SecurityException from every notify returns zero reposted, records no completed count and attempts each id at most twice`() {
        postOriginal(7, BUDGET, marker = null)
        val manager = mockk<NotificationManager>(relaxed = true)
        every { manager.activeNotifications } returns realManager().activeNotifications
        every { manager.notify(any<String>(), any(), any()) } throws SecurityException("x")

        val result = sweep(serviceWith(manager))

        assertEquals(NotificationService.RepostResult(reposted = 0, failed = 1), result)
        verify(exactly = 2) { manager.notify(any<String>(), eq(7), any()) } // sweep + one verification pass, never a loop
        assertTrue(emitted.isNotEmpty())
        assertTrue("only failure records, no completed or unconfirmed count", emitted.all { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_FAILED })
    }

    @Test
    fun `a SecurityException from the active notifications query returns 0 reposted 1 failed with one failure record and no notify`() {
        val manager = mockk<NotificationManager>(relaxed = true)
        every { manager.activeNotifications } throws SecurityException("secret detail Lidl 4242")

        val result = sweep(serviceWith(manager))

        assertEquals(NotificationService.RepostResult(reposted = 0, failed = 1), result)
        assertEquals(listOf(PrivacyDiagnosticCode.NOTIF_REPOST_FAILED), emitted.map { it.code })
        assertEquals("SecurityException", emitted.single().metadata.entries[PrivacyDiagnosticKey.EXCEPTION_CLASS])
        verify(exactly = 0) { manager.notify(any<String>(), any(), any()) }
        verify(exactly = 0) { manager.notify(any(), any()) }
    }

    @Test
    fun `an IllegalStateException on one id counts that one as failed and the next id is still re-posted`() {
        postOriginal(7, BUDGET, marker = null)
        postOriginal(8, BUDGET, marker = null)
        val base = scriptedManager()
        val manager = mockk<NotificationManager>(relaxed = true)
        every { manager.activeNotifications } answers { base.activeNotifications }
        every { manager.notify(any<String>(), eq(7), any()) } throws IllegalStateException("secret detail Lidl 4242")
        every { manager.notify(any<String>(), eq(8), any()) } answers {
            base.notify(firstArg<String?>(), 8, thirdArg<Notification>())
        }

        val result = sweep(serviceWith(manager))

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 1), result)
        verify(exactly = 2) { manager.notify(any<String>(), eq(7), any()) } // sweep + the one verification pass
        verify(exactly = 1) { manager.notify(any<String>(), eq(8), any()) }
        assertEquals(texts.discreet(NotificationKind.BUDGET).title, active(8).notification.titleText())
        val failures = emitted.filter { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_FAILED }
        assertTrue(failures.isNotEmpty())
        failures.forEach {
            assertEquals("IllegalStateException", it.metadata.entries[PrivacyDiagnosticKey.EXCEPTION_CLASS])
            assertEquals(setOf(PrivacyDiagnosticKey.EXCEPTION_CLASS), it.metadata.entries.keys)
        }
        emitted.forEach { e -> e.metadata.entries.values.forEach { assertFalse(it.toString().contains("secret")) } }
        assertEquals(1, emitted.single { it.code == PrivacyDiagnosticCode.NOTIF_REPOSTED_DISCREET }.metadata.entries[PrivacyDiagnosticKey.COUNT])
    }

    @Test
    fun `a CancellationException from the platform call propagates and is not swallowed`() {
        postOriginal(7, BUDGET, marker = null)
        val manager = mockk<NotificationManager>(relaxed = true)
        every { manager.activeNotifications } returns realManager().activeNotifications
        every { manager.notify(any<String>(), any(), any()) } throws CancellationException("cancelled")
        val service = serviceWith(manager)

        assertThrows(CancellationException::class.java) { runBlocking { service.repostActiveAsDiscreet() } }
        assertTrue(emitted.isEmpty())
    }

    // --- pacing (platform enqueue rate) -----------------------------------------------------------

    @Test
    fun `seven active notifications are re-posted with a 300 ms pause before every re-post including the first`() {
        val ids = listOf(7, 8, 9, 10, 11, 12, 13)
        ids.forEach { postOriginal(it, BUDGET, marker = null) }
        val pauses = mutableListOf<Long>()
        val base = scriptedManager()
        val manager = mockk<NotificationManager>(relaxed = true)
        every { manager.activeNotifications } answers { base.activeNotifications }
        val notifiedAtPauseCount = mutableListOf<Int>()
        every { manager.notify(any<String>(), any(), any()) } answers {
            notifiedAtPauseCount += pauses.size
            base.notify(firstArg<String?>(), secondArg<Int>(), thirdArg<Notification>())
        }

        val result = sweep(serviceWith(manager, SweepPacer { pauses += it }))

        assertEquals(NotificationService.RepostResult(reposted = 7, failed = 0), result)
        // Seven 300 ms re-post pauses, then the single 750 ms verification settle. Every re-post is "D" by the
        // verification read, so the verification adds no re-post and no pause beyond the settle.
        assertEquals(
            List(7) { AndroidNotificationService.SWEEP_REPOST_SPACING_MS } + AndroidNotificationService.SWEEP_VERIFY_SETTLE_MS,
            pauses
        )
        assertEquals(300L, AndroidNotificationService.SWEEP_REPOST_SPACING_MS)
        // Every re-post, the first included, is preceded by exactly one more pause.
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), notifiedAtPauseCount)
    }

    @Test
    fun `a skipped notification costs no pause`() {
        postOriginal(7, BUDGET, marker = null)
        postOriginal(99, FGS, marker = null) // not a sweep kind: classified null, skipped
        val pauses = mutableListOf<Long>()

        val result = sweep(serviceWith(realManager(), SweepPacer { pauses += it }))

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        assertEquals(
            "one pause for the one re-post, none for the skipped notification, then the verification settle",
            listOf(300L, 750L),
            pauses
        )
    }

    @Test
    fun `a sweep with only skipped notifications pauses only for the verification settle`() {
        postOriginal(99, FGS, marker = null) // not a sweep kind: classified null, skipped
        postOriginal(9, BUDGET, marker = null) { builder -> builder.setGroup("ranker_group").setGroupSummary(true) }
        val pauses = mutableListOf<Long>()

        val result = sweep(serviceWith(realManager(), SweepPacer { pauses += it }))

        assertEquals(NotificationService.RepostResult.NONE, result)
        assertEquals("no re-post pause; the settle runs once and finds nothing to re-post", listOf(750L), pauses)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `a cancellation while pacing propagates, stops the sweep and records nothing`() {
        listOf(7, 8, 9, 10, 11, 12, 13).forEach { postOriginal(it, BUDGET, marker = null) }
        val manager = mockk<NotificationManager>(relaxed = true)
        every { manager.activeNotifications } returns realManager().activeNotifications
        var pauseCalls = 0
        val pacer = SweepPacer {
            pauseCalls++
            if (pauseCalls == 2) throw CancellationException("cancelled while pacing")
        }
        val service = serviceWith(manager, pacer)

        assertThrows(CancellationException::class.java) { runBlocking { service.repostActiveAsDiscreet() } }

        assertEquals(2, pauseCalls)
        // The pause precedes each notify: the first re-post ran, the second was cancelled during its pause.
        verify(exactly = 1) { manager.notify(any<String>(), any(), any()) }
        assertTrue("no failure and no completed count is recorded", emitted.isEmpty())
    }

    // --- AE-112 ----------------------------------------------------------------------------------

    private fun runAe112(withMarkers: Boolean) {
        fun marker(kind: NotificationKind): String? = if (withMarkers) kind.stableToken else null
        postOriginal(7, BUDGET, marker(NotificationKind.BUDGET))
        postOriginal(10003, BUDGET, marker(NotificationKind.WARRANTY))
        postOriginal(15003, BUDGET, marker(NotificationKind.WARRANTY))
        postOriginal(20042, BUDGET, marker(NotificationKind.RECEIPT_MATCH))
        postOriginal(30011, BILL, marker(NotificationKind.BILL), withActions = true, contentIntent = false)
        postOriginal(40077, ANOMALY, marker(NotificationKind.ANOMALY))
        postOriginal(42, AI, marker(NotificationKind.BRIEFING))
        postOriginal(99, FGS, marker = null)
        val fgsBefore = active(99).notification

        val result = sweep()

        assertEquals(NotificationService.RepostResult(reposted = 7, failed = 0), result)
        assertRepostedAs(7, BUDGET, NotificationKind.BUDGET)
        assertRepostedAs(10003, BUDGET, NotificationKind.WARRANTY)
        assertRepostedAs(15003, BUDGET, NotificationKind.WARRANTY)
        assertRepostedAs(20042, BUDGET, NotificationKind.RECEIPT_MATCH)
        assertRepostedBill(30011)
        assertRepostedAs(40077, ANOMALY, NotificationKind.ANOMALY)
        assertRepostedAs(42, AI, NotificationKind.BRIEFING)

        // The capture FGS notification is not a sweep kind: untouched, still carrying its own content.
        val fgsAfter = active(99).notification
        assertEquals(FGS, fgsAfter.channelId)
        assertEquals(SENTINEL_TITLE, fgsAfter.titleText())
        assertEquals(SENTINEL_TEXT, fgsAfter.contentText())
        assertEquals(fgsBefore.`when`, fgsAfter.`when`)
        assertEquals(0, fgsAfter.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertNull(NotificationKindMarker.read(fgsAfter))
        assertEquals(8, realManager().activeNotifications.size)
        assertEquals(7, emitted.single().metadata.entries[PrivacyDiagnosticKey.COUNT])
    }

    @Test
    fun `AE-112 marker-less active notifications are classified by channel and id range`() = runAe112(withMarkers = false)

    @Test
    fun `AE-112 matching markers give the same classification`() = runAe112(withMarkers = true)

    /** BILL: discreet BILL constants, the original action PendingIntents re-applied with resource titles. */
    private fun assertRepostedBill(id: Int) {
        val n = active(id).notification
        val discreet = texts.discreet(NotificationKind.BILL)
        assertEquals(discreet.title, n.titleText())
        assertEquals(discreet.text, n.contentText())
        assertEquals(BILL, n.channelId)
        assertEquals(whenFor(id), n.`when`)
        assertTrue((n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0)
        assertEquals(NotificationKind.BILL.stableToken, NotificationKindMarker.read(n))
        assertNull("the original had no content intent and none is invented", n.contentIntent)
        assertNoSentinel(n, "bill $id")

        val actions = n.actions
        assertNotNull(actions)
        assertEquals(2, actions.size)
        assertEquals(appContext.getString(R.string.notif_action_snooze), actions[0].title.toString())
        assertEquals(appContext.getString(R.string.action_dismiss), actions[1].title.toString())
        val snooze = shadowOf(actions[0].actionIntent)
        val dismiss = shadowOf(actions[1].actionIntent)
        assertEquals("com.yourname.test.snooze", snooze.savedIntent.action)
        assertEquals(id, snooze.requestCode)
        assertEquals("com.yourname.test.dismiss", dismiss.savedIntent.action)
        assertEquals(id xor 0x40000000, dismiss.requestCode)
        assertEquals(id.toLong(), snooze.savedIntent.getLongExtra("deliveryId", -1L))
        // Action policy re-applied for the current API (34): authentication required.
        assertTrue(actions[0].isAuthenticationRequired)
        assertTrue(actions[1].isAuthenticationRequired)
    }

    @Test
    fun `a BILL original with no actions is re-posted with no actions invented`() {
        postOriginal(30011, BILL, marker = NotificationKind.BILL.stableToken, contentIntent = false)

        val result = sweep()

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        val n = active(30011).notification
        assertEquals(texts.discreet(NotificationKind.BILL).title, n.titleText())
        assertTrue("no actions invented", n.actions.isNullOrEmpty())
        assertNull(n.contentIntent)
    }

    @Test
    fun `a BILL original with one action keeps only that existing PendingIntent and invents no second action`() {
        postOriginal(30011, BILL, marker = NotificationKind.BILL.stableToken, actionNames = listOf("snooze"), contentIntent = false)

        val result = sweep()

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        val n = active(30011).notification
        val actions = n.actions
        assertNotNull(actions)
        assertEquals(1, actions.size)
        assertEquals(appContext.getString(R.string.notif_action_snooze), actions[0].title.toString())
        val snooze = shadowOf(actions[0].actionIntent)
        assertEquals("com.yourname.test.snooze", snooze.savedIntent.action)
        assertEquals(30011, snooze.requestCode)
        assertNoSentinel(n, "bill one action")
    }

    // --- AE-121 ----------------------------------------------------------------------------------

    @Test
    fun `AE-121 marker precedence and legacy fallback`() {
        postOriginal(15003, BUDGET, marker = "BUDGET")
        postOriginal(10003, BUDGET, marker = null)
        postOriginal(20042, BUDGET, marker = "BILL")
        postOriginal(30011, BILL, marker = null, withActions = true, contentIntent = false)
        postOriginal(7, BUDGET, marker = "X")

        val result = sweep()

        assertEquals(NotificationService.RepostResult(reposted = 5, failed = 0), result)
        assertRepostedAs(15003, BUDGET, NotificationKind.BUDGET)
        assertRepostedAs(10003, BUDGET, NotificationKind.WARRANTY)
        assertRepostedAs(20042, BUDGET, NotificationKind.RECEIPT_MATCH)
        assertRepostedBill(30011)
        assertRepostedAs(7, BUDGET, NotificationKind.BUDGET)
    }

    @Test
    fun `AE-121 raw budget id 30500 falls through to BUDGET and a marker on another channel never brings it in`() {
        postOriginal(30500, BUDGET, marker = null)
        postOriginal(55, FGS, marker = NotificationKind.BUDGET.stableToken)

        val result = sweep()

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        assertRepostedAs(30500, BUDGET, NotificationKind.BUDGET)
        val fgs = active(55).notification
        assertEquals(SENTINEL_TITLE, fgs.titleText())
        assertEquals(0, fgs.flags and Notification.FLAG_ONLY_ALERT_ONCE)
    }

    // --- rebuild, never copy ---------------------------------------------------------------------

    @Test
    fun `nothing is copied from the original and the tag is preserved so the re-post replaces it`() {
        postOriginal(15003, BUDGET, marker = null, tag = "orig-tag")

        sweep()

        val all = realManager().activeNotifications.filter { it.id == 15003 }
        assertEquals("replaced in place, not duplicated", 1, all.size)
        assertRepostedAs(15003, BUDGET, NotificationKind.WARRANTY, tag = "orig-tag")
        val n = all.single().notification
        // Every extras key of the re-post is accounted for: platform-owned keys or the kind marker.
        val unexpected = n.extras.keySet().filter { it.startsWith("test.") }
        assertTrue("copied extras: $unexpected", unexpected.isEmpty())
    }

    @Test
    fun `a non-BILL original with action titles is re-posted with no actions and no sentinel`() {
        postOriginal(40077, ANOMALY, marker = NotificationKind.ANOMALY.stableToken, withActions = true)
        assertEquals("the original does carry actions", 2, active(40077).notification.actions?.size)

        val result = sweep()

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        val n = active(40077).notification
        assertTrue("no actions on a non-BILL re-post", n.actions.isNullOrEmpty())
        assertNoSentinel(n, "anomaly with actions")
    }

    @Test
    fun `group, sort key and large icon of the original are not copied`() {
        postOriginal(7, BUDGET, marker = null) { builder ->
            builder.setGroup(SENTINEL_TEXT)
                .setSortKey(SENTINEL_TEXT)
                .setLargeIcon(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888))
        }
        assertEquals("the original does carry a group", SENTINEL_TEXT, active(7).notification.group)

        val result = sweep()

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        val n = active(7).notification
        assertNull("group not copied", n.group)
        assertNull("sort key not copied", n.sortKey)
        assertNull("large icon not copied", n.getLargeIcon())
        assertNoSentinel(n, "group, sort key and large icon")
    }

    @Test
    fun `a system group summary on an app channel is left untouched and is not counted`() {
        postOriginal(7, BUDGET, marker = null)
        postOriginal(9, BUDGET, marker = null) { builder -> builder.setGroup("ranker_group").setGroupSummary(true) }
        assertTrue(
            "the fixture is a group summary",
            (active(9).notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        )

        val result = sweep()

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        assertRepostedAs(7, BUDGET, NotificationKind.BUDGET)
        val summary = active(9).notification
        assertEquals(SENTINEL_TITLE, summary.titleText())
        assertEquals(0, summary.flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals(1, emitted.single().metadata.entries[PrivacyDiagnosticKey.COUNT])
    }

    @Test
    fun `the public version of a re-post is the generic pair`() {
        postOriginal(7, BUDGET, marker = null)

        sweep()

        val generic = texts.genericPublic()
        val pub = active(7).notification.publicVersion
        assertNotNull(pub)
        assertEquals(generic.title, pub!!.titleText())
        assertEquals(generic.text, pub.contentText())
    }

    @Test
    fun `the sweep queries the active notifications exactly twice, the sweep read and the single verification re-read`() {
        postOriginal(7, BUDGET, marker = null)
        postOriginal(8, ANOMALY, marker = null)
        val timeline = mutableListOf<String>()
        val manager = scriptedManager(timeline)

        sweep(serviceWith(manager))

        verify(exactly = 2) { manager.activeNotifications }
        assertEquals(2, timeline.count { it == "read" })
    }

    // --- G1b-0 post-sweep verification ------------------------------------------------------------------

    private fun withToken(token: String): (NotificationCompat.Builder) -> Unit = { builder ->
        builder.addExtras(Bundle().apply { putString(NotificationPresentationApplier.MODE_EXTRA_KEY, token) })
    }

    private fun Notification.modeToken(): String? = NotificationPresentationApplier.readModeToken(this)

    /**
     * A manager that reads and posts through the real Robolectric manager but records a [timeline] of "read" and
     * "notify<id>" events and lets a test drop or fail a given post. [applies] answers whether the n-th notify of
     * an id lands (false simulates the platform silently dropping the update); it may also throw.
     */
    private fun scriptedManager(
        timeline: MutableList<String> = mutableListOf(),
        onRead: (readNumber: Int) -> Unit = {},
        applies: (id: Int, callNumber: Int) -> Boolean = { _, _ -> true }
    ): NotificationManager {
        val real = realManager()
        val scripted = mockk<NotificationManager>(relaxed = true)
        every { scripted.activeNotifications } answers {
            timeline += "read"
            onRead(timeline.count { it == "read" })
            real.activeNotifications
        }
        every { scripted.notify(any<String>(), any(), any()) } answers {
            val id = secondArg<Int>()
            timeline += "notify$id"
            if (applies(id, timeline.count { it == "notify$id" })) {
                real.notify(firstArg<String?>(), id, thirdArg<Notification>())
            }
        }
        return scripted
    }

    private fun pacerInto(timeline: MutableList<String>) = SweepPacer { timeline += "pause$it" }

    @Test
    fun `G1b-0 after the sweep every active sweep-kind notification carries token D and the public version carries none`() {
        postOriginal(7, BUDGET, marker = null)
        postOriginal(8, ANOMALY, marker = NotificationKind.ANOMALY.stableToken, customize = withToken("F"))
        postOriginal(30011, BILL, marker = null, withActions = true, contentIntent = false)

        val result = sweep()

        assertEquals(NotificationService.RepostResult(reposted = 3, failed = 0), result)
        realManager().activeNotifications.forEach {
            assertEquals("id ${it.id}", "D", it.notification.modeToken())
            assertNotNull("id ${it.id} has a public version", it.notification.publicVersion)
            assertNull("id ${it.id} public version", NotificationPresentationApplier.readModeToken(it.notification.publicVersion))
        }
        assertEquals(3, realManager().activeNotifications.size)
        assertTrue("every re-post landed, so nothing is unconfirmed", emitted.none { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED })
    }

    @Test
    fun `G1b-0 control a FULL original with the F token carries F before the sweep`() {
        postOriginal(7, BUDGET, marker = null, customize = withToken("F"))

        assertEquals("F", active(7).notification.modeToken())
    }

    private fun runDroppedFirstUpdate(originalToken: String?) {
        val customize: (NotificationCompat.Builder) -> Unit = if (originalToken == null) ({ }) else withToken(originalToken)
        postOriginal(7, BUDGET, marker = null, customize = customize)
        postOriginal(8, BUDGET, marker = null)
        val timeline = mutableListOf<String>()
        // The platform drops the sweep's first update to id 7; every other post lands.
        val manager = scriptedManager(timeline, applies = { id, call -> !(id == 7 && call == 1) })

        val result = sweep(serviceWith(manager, pacerInto(timeline)))

        assertEquals("the sweep result counts what it posted, not what the verification repaired", NotificationService.RepostResult(reposted = 2, failed = 0), result)
        assertEquals("7 posted by the sweep and once more by the verification", 2, timeline.count { it == "notify7" })
        assertEquals("8 landed in the sweep, token D, not re-posted", 1, timeline.count { it == "notify8" })
        assertEquals(texts.discreet(NotificationKind.BUDGET).title, active(7).notification.titleText())
        assertEquals("D", active(7).notification.modeToken())
        assertEquals("D", active(8).notification.modeToken())

        assertEquals(
            listOf(PrivacyDiagnosticCode.NOTIF_REPOSTED_DISCREET, PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED),
            emitted.map { it.code }
        )
        assertEquals(2, emitted[0].metadata.entries[PrivacyDiagnosticKey.COUNT])
        val unconfirmed = emitted[1].metadata
        assertEquals(1, unconfirmed.entries[PrivacyDiagnosticKey.COUNT])
        assertEquals("COUNT only, no other key", setOf(PrivacyDiagnosticKey.COUNT), unconfirmed.entries.keys)
    }

    @Test
    fun `G1b-0 an original with token F whose sweep update was dropped is re-posted once by the verification`() =
        runDroppedFirstUpdate("F")

    @Test
    fun `G1b-0 an original with no token whose sweep update was dropped is re-posted once by the verification`() =
        runDroppedFirstUpdate(null)

    @Test
    fun `G1b-0 a notification already carrying token D is not re-posted by the verification`() {
        postOriginal(9, BUDGET, marker = null, customize = withToken("D"))
        val timeline = mutableListOf<String>()
        val manager = scriptedManager(timeline, applies = { _, _ -> false }) // every update dropped, 9 stays "D"

        val result = sweep(serviceWith(manager, pacerInto(timeline)))

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        assertEquals("only the sweep posted it", 1, timeline.count { it == "notify9" })
        assertTrue(emitted.none { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED })
    }

    @Test
    fun `G1b-0 a non-string or junk token counts as not confirmed discreet and is re-posted`() {
        postOriginal(7, BUDGET, marker = null, customize = withToken("d")) // wrong case is not "D"
        postOriginal(8, BUDGET, marker = null) { it.addExtras(Bundle().apply { putInt(NotificationPresentationApplier.MODE_EXTRA_KEY, 1) }) }
        val timeline = mutableListOf<String>()
        val manager = scriptedManager(timeline, applies = { _, call -> call > 1 }) // sweep updates dropped

        sweep(serviceWith(manager, pacerInto(timeline)))

        assertEquals(2, timeline.count { it == "notify7" })
        assertEquals(2, timeline.count { it == "notify8" })
        assertEquals(2, emitted.single { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED }.metadata.entries[PrivacyDiagnosticKey.COUNT])
    }

    @Test
    fun `G1b-0 the verification is a single pass, never a loop, however many updates stay dropped`() {
        postOriginal(7, BUDGET, marker = null, customize = withToken("F"))
        postOriginal(8, ANOMALY, marker = null)
        val timeline = mutableListOf<String>()
        val manager = scriptedManager(timeline, applies = { _, _ -> false }) // the platform drops everything

        val result = sweep(serviceWith(manager, pacerInto(timeline)))

        assertEquals(NotificationService.RepostResult(reposted = 2, failed = 0), result)
        assertEquals("exactly two active reads: the sweep and one verification", 2, timeline.count { it == "read" })
        assertEquals(2, timeline.count { it == "notify7" })
        assertEquals(2, timeline.count { it == "notify8" })
        val unconfirmed = emitted.single { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED }
        assertEquals(2, unconfirmed.metadata.entries[PrivacyDiagnosticKey.COUNT])
        assertEquals(setOf(PrivacyDiagnosticKey.COUNT), unconfirmed.metadata.entries.keys)
        assertEquals("one diagnostic only", 1, emitted.count { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED })
    }

    @Test
    fun `G1b-0 the verification skips group summaries and unclassifiable notifications even without a token`() {
        postOriginal(7, BUDGET, marker = null, customize = withToken("F"))
        postOriginal(9, BUDGET, marker = null) { builder -> builder.setGroup("ranker_group").setGroupSummary(true) }
        postOriginal(99, FGS, marker = null)
        val timeline = mutableListOf<String>()
        val manager = scriptedManager(timeline, applies = { _, _ -> false })

        sweep(serviceWith(manager, pacerInto(timeline)))

        assertEquals(2, timeline.count { it == "notify7" })
        assertEquals("summary never posted", 0, timeline.count { it == "notify9" })
        assertEquals("unclassifiable never posted", 0, timeline.count { it == "notify99" })
        assertEquals(1, emitted.single { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED }.metadata.entries[PrivacyDiagnosticKey.COUNT])
    }

    @Test
    fun `G1b-0 the verification order is sweep, settle 750, one re-read, then a paced 300 re-post`() {
        postOriginal(7, BUDGET, marker = null, customize = withToken("F"))
        val timeline = mutableListOf<String>()
        val manager = scriptedManager(timeline, applies = { _, call -> call > 1 })

        sweep(serviceWith(manager, pacerInto(timeline)))

        assertEquals(
            listOf("read", "pause300", "notify7", "pause750", "read", "pause300", "notify7"),
            timeline
        )
    }

    @Test
    fun `G1b-0 nothing to verify records no unconfirmed diagnostic and re-posts nothing`() {
        postOriginal(7, BUDGET, marker = null)
        val timeline = mutableListOf<String>()
        val manager = scriptedManager(timeline)

        sweep(serviceWith(manager, pacerInto(timeline)))

        assertEquals(1, timeline.count { it == "notify7" })
        assertEquals(listOf(PrivacyDiagnosticCode.NOTIF_REPOSTED_DISCREET), emitted.map { it.code })
    }

    @Test
    fun `G1b-0 a sweep with nothing active still settles once and records nothing`() {
        val timeline = mutableListOf<String>()
        val manager = scriptedManager(timeline)

        val result = sweep(serviceWith(manager, pacerInto(timeline)))

        assertEquals(NotificationService.RepostResult.NONE, result)
        assertEquals(listOf("read", "pause750", "read"), timeline)
        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `G1b-0 a CancellationException from the settle pause propagates and records no unconfirmed`() {
        postOriginal(7, BUDGET, marker = null, customize = withToken("F"))
        val manager = scriptedManager()
        val pacer = SweepPacer { millis -> if (millis == AndroidNotificationService.SWEEP_VERIFY_SETTLE_MS) throw CancellationException("cancelled in settle") }
        val service = serviceWith(manager, pacer)

        assertThrows(CancellationException::class.java) { runBlocking { service.repostActiveAsDiscreet() } }

        assertTrue(emitted.none { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED })
        verify(exactly = 1) { manager.activeNotifications } // only the sweep read happened
    }

    @Test
    fun `G1b-0 a CancellationException from the verification re-read propagates`() {
        postOriginal(7, BUDGET, marker = null, customize = withToken("F"))
        val manager = scriptedManager(onRead = { n -> if (n == 2) throw CancellationException("cancelled in re-read") })
        val service = serviceWith(manager, SweepPacer { })

        assertThrows(CancellationException::class.java) { runBlocking { service.repostActiveAsDiscreet() } }

        assertTrue(emitted.none { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED })
    }

    @Test
    fun `G1b-0 a CancellationException from a verification re-post propagates and is not swallowed`() {
        postOriginal(7, BUDGET, marker = null, customize = withToken("F"))
        val timeline = mutableListOf<String>()
        val manager = scriptedManager(timeline, applies = { _, call ->
            when (call) {
                1 -> false // the sweep update is dropped, so the verification has something to re-post
                else -> throw CancellationException("cancelled in verification re-post")
            }
        })
        val service = serviceWith(manager, SweepPacer { })

        assertThrows(CancellationException::class.java) { runBlocking { service.repostActiveAsDiscreet() } }

        assertEquals(2, timeline.count { it == "notify7" })
        assertTrue(emitted.none { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED })
    }

    @Test
    fun `G1b-0 a CancellationException from a verification pacing pause propagates`() {
        postOriginal(7, BUDGET, marker = null, customize = withToken("F"))
        val timeline = mutableListOf<String>()
        val manager = scriptedManager(timeline, applies = { _, call -> call > 1 })
        var spacingPauses = 0
        val pacer = SweepPacer { millis ->
            if (millis == AndroidNotificationService.SWEEP_REPOST_SPACING_MS && ++spacingPauses == 2) {
                throw CancellationException("cancelled in verification spacing")
            }
        }
        val service = serviceWith(manager, pacer)

        assertThrows(CancellationException::class.java) { runBlocking { service.repostActiveAsDiscreet() } }

        assertEquals("the verification re-post never ran", 1, timeline.count { it == "notify7" })
    }

    @Test
    fun `G1b-0 a SecurityException from the verification re-read is swallowed and the repost result is unchanged`() {
        postOriginal(7, BUDGET, marker = null)
        val manager = scriptedManager(onRead = { n -> if (n == 2) throw SecurityException("secret detail Lidl 4242") })

        val result = sweep(serviceWith(manager))

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        assertTrue(emitted.none { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED })
        assertEquals(1, emitted.single { it.code == PrivacyDiagnosticCode.NOTIF_REPOSTED_DISCREET }.metadata.entries[PrivacyDiagnosticKey.COUNT])
        emitted.forEach { e -> e.metadata.entries.values.forEach { assertFalse(it.toString().contains("secret")) } }
    }

    @Test
    fun `G1b-0 an unexpected exception from the verification re-read is swallowed and never reaches the caller`() {
        postOriginal(7, BUDGET, marker = null)
        val manager = scriptedManager(onRead = { n -> if (n == 2) throw IllegalStateException("secret detail Lidl 4242") })

        val result = sweep(serviceWith(manager))

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        assertTrue(emitted.none { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED })
        emitted.forEach { e -> e.metadata.entries.values.forEach { assertFalse(it.toString().contains("secret")) } }
    }

    @Test
    fun `G1b-0 a SecurityException from a verification re-post is swallowed, the sweep result is unchanged and it is not counted unconfirmed`() {
        postOriginal(7, BUDGET, marker = null, customize = withToken("F"))
        val manager = scriptedManager(applies = { _, call ->
            if (call == 1) false else throw SecurityException("secret detail Lidl 4242")
        })

        val result = sweep(serviceWith(manager))

        assertEquals(NotificationService.RepostResult(reposted = 1, failed = 0), result)
        assertTrue(emitted.none { it.code == PrivacyDiagnosticCode.NOTIF_REPOST_UNCONFIRMED })
        emitted.forEach { e -> e.metadata.entries.values.forEach { assertFalse(it.toString().contains("secret")) } }
    }

    @Test
    fun `G1b-0 the verification settle is 750 ms inside the 500 to 1000 ms window and distinct from the spacing`() {
        assertEquals(750L, AndroidNotificationService.SWEEP_VERIFY_SETTLE_MS)
        assertTrue(AndroidNotificationService.SWEEP_VERIFY_SETTLE_MS in 500L..1000L)
        assertEquals(300L, AndroidNotificationService.SWEEP_REPOST_SPACING_MS)
    }

    private companion object {
        const val BUDGET = "budget_alerts"
        const val BILL = "bill_reminders"
        const val ANOMALY = "anomaly_alerts"
        const val AI = "ai_briefings"
        const val FGS = "capture_service"
    }
}
