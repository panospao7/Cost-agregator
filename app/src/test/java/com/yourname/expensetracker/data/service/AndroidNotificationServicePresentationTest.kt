package com.yourname.expensetracker.data.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationMode
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationResolver
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsWriteResult
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import com.yourname.expensetracker.domain.service.NotificationService
import com.yourname.expensetracker.ui.MainActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import kotlin.time.Duration.Companion.seconds

/**
 * S5a, post side of [AndroidNotificationService] kind-aware methods (AE-109, AE-111, AE-112, AE-121,
 * AE-32, TECH-9 / owner decision C6). Real Robolectric NotificationManager, real builder, real
 * [NotificationPresentationApplier] and [NotificationPresentationTexts], real
 * [NotificationPresentationResolver]. Only the settings repository and the diagnostics emitter are
 * test doubles. A merchant sentinel is the only caller PII.
 *
 * The matrix tests live in [AndroidNotificationServicePresentationContract] and run once per supported
 * SDK level through the subclasses below. SDK independent tests (disabled, C6, legacy) run once, in
 * [AndroidNotificationServicePresentationTest].
 */

// --- shared doubles and data ---------------------------------------------------------------------

/** Scripted settings store. Counts reads so a test can observe whether the resolver was consulted. */
class ScriptedSettingsRepository(
    private val onRead: suspend () -> AppLockSettingsLoadState
) : AppLockSettingsRepository {
    var reads: Int = 0
        private set

    override suspend fun getLoadState(): AppLockSettingsLoadState {
        reads++
        return onRead()
    }

    override fun observeLoadState(): Flow<AppLockSettingsLoadState> = emptyFlow()

    override suspend fun setDiscreetNotifications(enabled: Boolean): AppLockSettingsWriteResult =
        error("not used by the notification service")

    override suspend fun reviewSave(record: AppLockPrivacySettings): AppLockSettingsWriteResult =
        error("not used by the notification service")
}

/** One service kind and the kind-aware method that posts it, with the tap target it must produce. */
class PostCase(
    val label: String,
    val kind: NotificationKind,
    val expectedData: String,
    val expectedIntentFlags: Int,
    val post: suspend (NotificationService, Int, String, String) -> NotificationService.DeliveryResult
) {
    override fun toString(): String = label
}

const val BRIEFING_KEY = "dashboard_home:2026-03-17"
const val EXPENSE_ID = 77L
const val SENTINEL_TITLE = "Lidl 4242 John"
const val SENTINEL_TEXT = "Lidl 4242 John spent 42.50 EUR"
val SENTINELS = listOf("Lidl", "4242", "John", "42.50")

private const val HOME_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
private const val DEEP_LINK_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP

/** The five service kinds, in the form the production callers post them. */
val POST_CASES: List<PostCase> = listOf(
    PostCase("BUDGET", NotificationKind.BUDGET, "expensetracker://home", HOME_FLAGS) { s, id, t, m ->
        s.sendBudgetAlertForKind(NotificationKind.BUDGET, id, t, m)
    },
    PostCase("WARRANTY", NotificationKind.WARRANTY, "expensetracker://home", HOME_FLAGS) { s, id, t, m ->
        s.sendBudgetAlertForKind(NotificationKind.WARRANTY, id, t, m)
    },
    PostCase("RECEIPT_MATCH", NotificationKind.RECEIPT_MATCH, "expensetracker://home", HOME_FLAGS) { s, id, t, m ->
        s.sendBudgetAlertForKind(NotificationKind.RECEIPT_MATCH, id, t, m)
    },
    PostCase(
        "ANOMALY",
        NotificationKind.ANOMALY,
        "expensetracker://activity?expenseId=$EXPENSE_ID",
        DEEP_LINK_FLAGS
    ) { s, id, t, m ->
        s.sendAnomalyAlertForKind(NotificationKind.ANOMALY, id, t, m, EXPENSE_ID)
    },
    PostCase(
        "BRIEFING",
        NotificationKind.BRIEFING,
        "expensetracker://dashboard?briefingKey=$BRIEFING_KEY",
        DEEP_LINK_FLAGS
    ) { s, id, t, m ->
        s.sendAiBriefingReadyForKind(NotificationKind.BRIEFING, id, t, m, BRIEFING_KEY)
    }
)

// --- the SDK independent contract -----------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
abstract class AndroidNotificationServicePresentationContract {

    protected abstract val expectedSdk: Int

    protected lateinit var appContext: Context
    protected lateinit var texts: NotificationPresentationTexts
    private lateinit var diagnosticsScope: CoroutineScope

    @Before
    fun setUpContract() {
        appContext = ApplicationProvider.getApplicationContext()
        texts = NotificationPresentationTexts(appContext)
        diagnosticsScope = CoroutineScope(Job())
    }

    @After
    fun tearDownContract() {
        diagnosticsScope.cancel()
    }

    // --- helpers ---------------------------------------------------------------------------------

    protected fun realManager(): NotificationManager =
        appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    protected fun repoFor(mode: NotificationPresentationMode): ScriptedSettingsRepository =
        ScriptedSettingsRepository {
            AppLockSettingsLoadState.Loaded(
                AppLockPrivacySettings(discreetNotifications = mode == NotificationPresentationMode.DISCREET)
            )
        }

    protected fun newService(
        context: Context,
        repo: ScriptedSettingsRepository
    ): AndroidNotificationService {
        val resolver = NotificationPresentationResolver(
            repo,
            mockk<PrivacyDiagnosticsEmitter>(relaxed = true),
            diagnosticsScope
        )
        val applier = NotificationPresentationApplier(appContext, texts)
        return AndroidNotificationService(context, resolver, applier)
    }

    /** A context whose notification manager is [manager]; everything else is the real application context. */
    protected fun contextWith(manager: NotificationManager): Context =
        object : ContextWrapper(appContext) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.NOTIFICATION_SERVICE) manager else super.getSystemService(name)
        }

    protected fun posted(id: Int): StatusBarNotification {
        val matches = realManager().activeNotifications.filter { it.id == id }
        assertEquals("exactly one active notification with id $id", 1, matches.size)
        return matches.single()
    }

    /** Posts [case] in [mode] through the real manager and returns what the platform now holds. */
    protected fun postAndRead(
        case: PostCase,
        mode: NotificationPresentationMode,
        id: Int,
        title: String = SENTINEL_TITLE,
        message: String = SENTINEL_TEXT
    ): StatusBarNotification {
        val service = newService(appContext, repoFor(mode))
        var result: NotificationService.DeliveryResult? = null
        runTest(timeout = 10.seconds) {
            result = case.post(service, id, title, message)
        }
        assertEquals("${case.label} $mode", NotificationService.DeliveryResult.DELIVERED, result)
        return posted(id)
    }

    private fun Notification.titleText(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.contentText(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** Every string reachable from the notification: ticker, every extra key and value (flattened), and the public version. */
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
            all.forEach { s ->
                assertFalse("$label: '$sentinel' leaked into '$s'", s.contains(sentinel))
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun keysHoldingToken(extras: Bundle, token: String): Set<String> =
        extras.keySet().filter { key ->
            val v = extras.get(key)
            (v is CharSequence && v.toString() == token)
        }.toSet()

    private val platformTextExtras = listOf(
        Notification.EXTRA_TITLE,
        Notification.EXTRA_TITLE_BIG,
        Notification.EXTRA_TEXT,
        Notification.EXTRA_BIG_TEXT,
        Notification.EXTRA_SUB_TEXT,
        Notification.EXTRA_INFO_TEXT,
        Notification.EXTRA_SUMMARY_TEXT
    )

    private inline fun <reified T : Throwable> expectThrown(label: String, block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (e is T) return e
            throw e
        }
        throw AssertionError("$label: expected ${T::class.java.simpleName} but nothing was thrown")
    }

    // --- sanity: the SDK configuration really applies ---------------------------------------------

    @Test
    fun `the Robolectric SDK matches the configured level`() {
        assertEquals(expectedSdk, Build.VERSION.SDK_INT)
    }

    // --- AE-112 / AE-121 post side: marker ----------------------------------------------------------

    @Test
    fun `AE-112 each service kind carries its marker on the private notification in FULL and DISCREET`() {
        var id = 1000
        for (case in POST_CASES) {
            for (mode in NotificationPresentationMode.entries) {
                val label = "${case.label} $mode sdk=$expectedSdk"
                val n = postAndRead(case, mode, id++).notification

                assertEquals(label, case.kind.stableToken, NotificationKindMarker.read(n))
                assertEquals(
                    label,
                    case.kind.stableToken,
                    n.extras.getString(NotificationKindMarker.EXTRA_KEY)
                )
            }
        }
    }

    @Test
    fun `AE-112 the marker is not on the public version`() {
        var id = 1100
        for (case in POST_CASES) {
            for (mode in NotificationPresentationMode.entries) {
                val label = "${case.label} $mode sdk=$expectedSdk"
                val pub = postAndRead(case, mode, id++).notification.publicVersion

                assertNotNull(label, pub)
                assertNull(label, NotificationKindMarker.read(pub))
                assertFalse(label, pub!!.extras.containsKey(NotificationKindMarker.EXTRA_KEY))
                NotificationKind.entries.forEach { kind ->
                    assertTrue(
                        "$label token ${kind.stableToken} found on the public version",
                        keysHoldingToken(pub.extras, kind.stableToken).isEmpty()
                    )
                }
            }
        }
    }

    @Test
    fun `AE-121 no platform text extra and no ticker holds the marker`() {
        var id = 1200
        for (case in POST_CASES) {
            for (mode in NotificationPresentationMode.entries) {
                val label = "${case.label} $mode sdk=$expectedSdk"
                val n = postAndRead(case, mode, id++).notification
                val token = case.kind.stableToken

                platformTextExtras.forEach { key ->
                    val value = n.extras.getCharSequence(key)?.toString()
                    assertNotEquals("$label $key", token, value)
                    assertFalse("$label $key", value.orEmpty().contains(NotificationKindMarker.EXTRA_KEY))
                }
                n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach {
                    assertNotEquals("$label text lines", token, it.toString())
                }
                assertNotEquals("$label ticker", token, n.tickerText?.toString())
                // The only extras entry whose value is the token is the marker key itself.
                assertEquals(
                    label,
                    setOf(NotificationKindMarker.EXTRA_KEY),
                    keysHoldingToken(n.extras, token)
                )
            }
        }
    }

    @Test
    fun `AE-121 the marker key is not among the keys the platform renders as text`() {
        assertFalse(platformTextExtras.contains(NotificationKindMarker.EXTRA_KEY))
        assertFalse(NotificationKindMarker.EXTRA_KEY == Notification.EXTRA_TEXT_LINES)
    }

    // --- id and tag ---------------------------------------------------------------------------------

    @Test
    fun `TECH-12 the posted id equals the supplied id and the tag is null for every kind and mode`() {
        var id = 20_000
        for (case in POST_CASES) {
            for (mode in NotificationPresentationMode.entries) {
                val label = "${case.label} $mode sdk=$expectedSdk"
                val expectedId = id++
                val sbn = postAndRead(case, mode, expectedId)

                assertEquals(label, expectedId, sbn.id)
                assertNull(label, sbn.tag)
            }
        }
        // Every post kept its own id: nothing was replaced or renumbered.
        assertEquals(POST_CASES.size * NotificationPresentationMode.entries.size, realManager().activeNotifications.size)
    }

    // --- AE-109: public version ---------------------------------------------------------------------

    @Test
    fun `AE-109 the public version is the generic pair in FULL and DISCREET for every service kind`() {
        val generic = texts.genericPublic()
        var id = 3000
        for (case in POST_CASES) {
            for (mode in NotificationPresentationMode.entries) {
                val label = "${case.label} $mode sdk=$expectedSdk"
                val n = postAndRead(case, mode, id++).notification
                val pub = n.publicVersion

                assertNotNull(label, pub)
                assertEquals(label, generic.title, pub!!.titleText())
                assertEquals(label, generic.text, pub.contentText())
                assertNotEquals(label, texts.discreet(case.kind).title, pub.titleText())
                assertEquals(label, Notification.VISIBILITY_PRIVATE, n.visibility)
                assertNoSentinel(pub, "$label public")
                assertNull(label, pub.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                assertNull(label, pub.extras.getString(Notification.EXTRA_TEMPLATE))
                assertNull(label, pub.contentIntent)
                assertTrue(label, pub.actions == null || pub.actions.isEmpty())
            }
        }
    }

    // --- AE-111: visible text -------------------------------------------------------------------------

    @Test
    fun `AE-111 control FULL shows the supplied text so the DISCREET leak scan is not vacuous`() {
        var id = 4000
        for (case in POST_CASES) {
            val label = "${case.label} sdk=$expectedSdk"
            val n = postAndRead(case, NotificationPresentationMode.FULL, id++).notification

            assertEquals(label, SENTINEL_TITLE, n.titleText())
            assertEquals(label, SENTINEL_TEXT, n.contentText())
            assertTrue(label, everyString(n).any { it.contains("Lidl") })
        }
    }

    @Test
    fun `AE-111 DISCREET shows only the kind constants and no merchant sentinel anywhere`() {
        var id = 4100
        for (case in POST_CASES) {
            val label = "${case.label} sdk=$expectedSdk"
            val n = postAndRead(case, NotificationPresentationMode.DISCREET, id++).notification

            assertEquals(label, texts.discreet(case.kind).title, n.titleText())
            assertEquals(label, texts.discreet(case.kind).text, n.contentText())
            assertNoSentinel(n, label)
            assertNull(label, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            assertNull(label, n.extras.getString(Notification.EXTRA_TEMPLATE))
            assertNull(label, n.tickerText)
        }
    }

    @Test
    fun `AE-111 RECEIPT_MATCH DISCREET never shows the BUDGET constants`() {
        val receipt = POST_CASES.single { it.kind == NotificationKind.RECEIPT_MATCH }
        val n = postAndRead(
            receipt,
            NotificationPresentationMode.DISCREET,
            id = 4200,
            title = "Receipt from Lidl 4242 John",
            message = "Receipt from Lidl 4242 John matched"
        ).notification
        val budget = texts.discreet(NotificationKind.BUDGET)

        assertEquals(texts.discreet(NotificationKind.RECEIPT_MATCH).title, n.titleText())
        assertEquals(texts.discreet(NotificationKind.RECEIPT_MATCH).text, n.contentText())
        assertNotEquals(budget.title, n.titleText())
        assertNotEquals(budget.text, n.contentText())
        everyString(n).forEach { s ->
            assertNotEquals(budget.title, s)
            assertNotEquals(budget.text, s)
        }
        assertNoSentinel(n, "receipt match")
        assertFalse(everyString(n).any { it.contains("Receipt from") })
    }

    @Test
    fun `AE-111 WARRANTY and BUDGET through the same method show their own constants`() {
        val budgetCase = POST_CASES.single { it.kind == NotificationKind.BUDGET }
        val warrantyCase = POST_CASES.single { it.kind == NotificationKind.WARRANTY }
        val budget = postAndRead(budgetCase, NotificationPresentationMode.DISCREET, 4300).notification
        val warranty = postAndRead(warrantyCase, NotificationPresentationMode.DISCREET, 4301).notification

        assertEquals(texts.discreet(NotificationKind.BUDGET).title, budget.titleText())
        assertEquals(texts.discreet(NotificationKind.WARRANTY).title, warranty.titleText())
        assertNotEquals(budget.titleText(), warranty.titleText())
    }

    // --- AE-32: tap intent ------------------------------------------------------------------------------

    @Test
    fun `AE-32 the content intent is explicit to MainActivity with ACTION_VIEW the expected data and FLAG_IMMUTABLE`() {
        var id = 5000
        for (case in POST_CASES) {
            for (mode in NotificationPresentationMode.entries) {
                val label = "${case.label} $mode sdk=$expectedSdk"
                val pending = postAndRead(case, mode, id++).notification.contentIntent
                assertNotNull(label, pending)

                val shadow = shadowOf(pending)
                val intent = shadow.savedIntent

                assertTrue(label, shadow.isActivityIntent)
                assertNotNull("$label component (explicit intent)", intent.component)
                assertEquals(label, MainActivity::class.java.name, intent.component!!.className)
                assertEquals(label, appContext.packageName, intent.component!!.packageName)
                assertEquals(label, Intent.ACTION_VIEW, intent.action)
                assertEquals(label, case.expectedData, intent.dataString)
                assertEquals(label, case.expectedIntentFlags, intent.flags)
                assertTrue(
                    "$label the tap intent carries no extras",
                    intent.extras == null || intent.extras!!.isEmpty()
                )
                assertTrue(
                    "$label FLAG_IMMUTABLE",
                    (shadow.flags and android.app.PendingIntent.FLAG_IMMUTABLE) != 0
                )
            }
        }
    }

    @Test
    fun `AE-32 the tap intent carries the same data in FULL and DISCREET`() {
        for ((index, case) in POST_CASES.withIndex()) {
            val full = shadowOf(
                postAndRead(case, NotificationPresentationMode.FULL, 5100 + index).notification.contentIntent
            ).savedIntent
            val discreet = shadowOf(
                postAndRead(case, NotificationPresentationMode.DISCREET, 5200 + index).notification.contentIntent
            ).savedIntent

            assertEquals(case.label, full.dataString, discreet.dataString)
            assertEquals(case.label, full.action, discreet.action)
            assertEquals(case.label, full.component, discreet.component)
        }
    }

    // --- fail-closed through the real resolver ------------------------------------------------------------

    @Test
    fun `INV-6 an unreadable settings store posts DISCREET with the marker for every service kind`() {
        var id = 6000
        for (case in POST_CASES) {
            val label = "${case.label} sdk=$expectedSdk"
            val repo = ScriptedSettingsRepository {
                AppLockSettingsLoadState.Unreadable(SettingsUnreadableCause.IO_ERROR)
            }
            val expectedId = id++
            val service = newService(appContext, repo)
            var result: NotificationService.DeliveryResult? = null
            runTest(timeout = 10.seconds) {
                result = case.post(service, expectedId, SENTINEL_TITLE, SENTINEL_TEXT)
            }
            val n = posted(expectedId).notification

            assertEquals(label, NotificationService.DeliveryResult.DELIVERED, result)
            assertEquals(label, texts.discreet(case.kind).title, n.titleText())
            assertEquals(label, case.kind.stableToken, NotificationKindMarker.read(n))
            assertNoSentinel(n, label)
        }
    }

    @Test
    fun `INV-6 a settings read that throws a non cancellation exception posts DISCREET`() {
        var id = 6100
        for (case in POST_CASES) {
            val label = "${case.label} sdk=$expectedSdk"
            val repo = ScriptedSettingsRepository { throw IllegalStateException("store exploded for Lidl 4242") }
            val expectedId = id++
            val service = newService(appContext, repo)
            var result: NotificationService.DeliveryResult? = null
            runTest(timeout = 10.seconds) {
                result = case.post(service, expectedId, SENTINEL_TITLE, SENTINEL_TEXT)
            }
            val n = posted(expectedId).notification

            assertEquals(label, NotificationService.DeliveryResult.DELIVERED, result)
            assertEquals(label, texts.discreet(case.kind).title, n.titleText())
            assertNoSentinel(n, label)
        }
    }

    // --- C6 / TECH-9 (mock manager seam: a ContextWrapper returning a NotificationManager that throws) ----------

    @Test
    fun `C6 TECH-9 a SecurityException from notify returns PERMISSION_DENIED for each kind-aware method`() {
        for (case in POST_CASES) {
            for (mode in NotificationPresentationMode.entries) {
                val label = "${case.label} $mode sdk=$expectedSdk"
                val manager = mockk<NotificationManager>(relaxed = true)
                every { manager.areNotificationsEnabled() } returns true
                every { manager.notify(any<Int>(), any<Notification>()) } throws
                    SecurityException("POST_NOTIFICATIONS denied for Lidl 4242 John")
                val service = newService(contextWith(manager), repoFor(mode))

                var result: NotificationService.DeliveryResult? = null
                runTest(timeout = 10.seconds) {
                    result = case.post(service, 7001, SENTINEL_TITLE, SENTINEL_TEXT)
                }

                assertEquals(label, NotificationService.DeliveryResult.PERMISSION_DENIED, result)
                verify(exactly = 1) { manager.notify(7001, any<Notification>()) }
            }
        }
    }

    @Test
    fun `C6 TECH-9 PERMISSION_DENIED is not DELIVERED and is not NOT_DELIVERED`() {
        assertNotEquals(
            NotificationService.DeliveryResult.DELIVERED,
            NotificationService.DeliveryResult.PERMISSION_DENIED
        )
        assertNotEquals(
            NotificationService.DeliveryResult.NOT_DELIVERED,
            NotificationService.DeliveryResult.PERMISSION_DENIED
        )
    }

    @Test
    fun `C6 TECH-9 a non SecurityException from notify propagates and is not mapped to PERMISSION_DENIED`() {
        for (case in POST_CASES) {
            val label = "${case.label} sdk=$expectedSdk"
            val manager = mockk<NotificationManager>(relaxed = true)
            every { manager.areNotificationsEnabled() } returns true
            every { manager.notify(any<Int>(), any<Notification>()) } throws IllegalStateException("not a permission problem")
            val service = newService(contextWith(manager), repoFor(NotificationPresentationMode.FULL))

            var result: NotificationService.DeliveryResult? = null
            val thrown = expectThrown<IllegalStateException>(label) {
                runTest(timeout = 10.seconds) {
                    result = case.post(service, 7002, SENTINEL_TITLE, SENTINEL_TEXT)
                }
            }

            assertNotNull(label, thrown)
            assertNull("$label must not yield a result", result)
        }
    }

    @Test
    fun `C6 TECH-9 the PERMISSION_DENIED log line carries no notification text`() {
        for (case in POST_CASES) {
            val manager = mockk<NotificationManager>(relaxed = true)
            every { manager.areNotificationsEnabled() } returns true
            every { manager.notify(any<Int>(), any<Notification>()) } throws
                SecurityException("denied for Lidl 4242 John 42.50")
            val service = newService(contextWith(manager), repoFor(NotificationPresentationMode.FULL))

            runTest(timeout = 10.seconds) {
                case.post(service, 7003, SENTINEL_TITLE, SENTINEL_TEXT)
            }
        }

        val lines = ShadowLog.getLogs().map { "${it.tag} ${it.msg}" }
        SENTINELS.forEach { sentinel ->
            lines.forEach { line -> assertFalse("'$sentinel' leaked into log '$line'", line.contains(sentinel)) }
        }
        POST_CASES.forEach { case ->
            assertTrue(
                "no log line names kind ${case.kind.stableToken}",
                lines.any { it.contains(case.kind.stableToken) }
            )
        }
    }
}

// --- the single-run tests ----------------------------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidNotificationServicePresentationTest : AndroidNotificationServicePresentationContract() {

    override val expectedSdk: Int = 34

    @Test
    fun `notifications disabled returns NOT_DELIVERED posts nothing and never consults the resolver`() {
        shadowOf(realManager()).setNotificationsEnabled(false)
        for (case in POST_CASES) {
            for (mode in NotificationPresentationMode.entries) {
                val label = "${case.label} $mode"
                val repo = repoFor(mode)
                val service = newService(appContext, repo)

                var result: NotificationService.DeliveryResult? = null
                runTest(timeout = 10.seconds) {
                    result = case.post(service, 8001, SENTINEL_TITLE, SENTINEL_TEXT)
                }

                assertEquals(label, NotificationService.DeliveryResult.NOT_DELIVERED, result)
                assertEquals("$label resolver consulted", 0, repo.reads)
                assertTrue(label, realManager().activeNotifications.isEmpty())
            }
        }
    }

    @Test
    fun `control notifications enabled posts once and consults the resolver exactly once per post`() {
        for ((index, case) in POST_CASES.withIndex()) {
            val repo = repoFor(NotificationPresentationMode.FULL)
            val service = newService(appContext, repo)

            var result: NotificationService.DeliveryResult? = null
            runTest(timeout = 10.seconds) {
                result = case.post(service, 8100 + index, SENTINEL_TITLE, SENTINEL_TEXT)
            }

            assertEquals(case.label, NotificationService.DeliveryResult.DELIVERED, result)
            assertEquals(case.label, 1, repo.reads)
        }
        assertEquals(POST_CASES.size, realManager().activeNotifications.size)
    }

    @Test
    fun `C6 TECH-9 a CancellationException from the resolver propagates posts nothing and is not PERMISSION_DENIED`() {
        for (case in POST_CASES) {
            val repo = ScriptedSettingsRepository { throw CancellationException("worker stopped") }
            val service = newService(appContext, repo)

            var result: NotificationService.DeliveryResult? = null
            var cancelled = false
            runTest(timeout = 10.seconds) {
                try {
                    result = case.post(service, 8200, SENTINEL_TITLE, SENTINEL_TEXT)
                } catch (e: CancellationException) {
                    // Observed on purpose: the contract is that it reaches the caller.
                    cancelled = true
                }
            }

            assertTrue("${case.label} CancellationException must propagate", cancelled)
            assertNull("${case.label} must not map to a result", result)
            assertTrue("${case.label} nothing may be posted", realManager().activeNotifications.isEmpty())
            assertEquals(case.label, 1, repo.reads)
        }
    }

    @Test
    fun `C6 TECH-9 a SecurityException from the settings read is not a PERMISSION_DENIED`() {
        // A SecurityException thrown by the resolver path is a policy exception: fail closed to DISCREET, deliver.
        val case = POST_CASES.single { it.kind == NotificationKind.BUDGET }
        val repo = ScriptedSettingsRepository { throw SecurityException("store denied") }
        val service = newService(appContext, repo)

        var result: NotificationService.DeliveryResult? = null
        runTest(timeout = 10.seconds) {
            result = case.post(service, 8300, SENTINEL_TITLE, SENTINEL_TEXT)
        }

        assertEquals(NotificationService.DeliveryResult.DELIVERED, result)
        assertEquals(texts.discreet(NotificationKind.BUDGET).title, posted(8300).notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    // --- legacy methods: unchanged, no marker ---------------------------------------------------------------

    @Test
    fun `legacy sendBudgetAlert posts the supplied text with no marker and never reads the settings`() {
        val repo = repoFor(NotificationPresentationMode.DISCREET)
        val service = newService(appContext, repo)

        val result = service.sendBudgetAlert(9001, "Budget title", "Budget message")
        val n = posted(9001).notification

        assertEquals(NotificationService.DeliveryResult.DELIVERED, result)
        assertNull(NotificationKindMarker.read(n))
        assertFalse(n.extras.containsKey(NotificationKindMarker.EXTRA_KEY))
        assertEquals("Budget title", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Budget message", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(0, repo.reads)
    }

    @Test
    fun `legacy sendAiBriefingReadyWithResult posts the supplied text with no marker and never reads the settings`() {
        val repo = repoFor(NotificationPresentationMode.DISCREET)
        val service = newService(appContext, repo)

        val result = service.sendAiBriefingReadyWithResult(9002, "Briefing title", "Briefing message", BRIEFING_KEY)
        val n = posted(9002).notification

        assertEquals(NotificationService.DeliveryResult.DELIVERED, result)
        assertNull(NotificationKindMarker.read(n))
        assertFalse(n.extras.containsKey(NotificationKindMarker.EXTRA_KEY))
        assertEquals("Briefing title", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Briefing message", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(0, repo.reads)
    }

    @Test
    fun `legacy sendAiBriefingReady posts with no marker`() {
        val service = newService(appContext, repoFor(NotificationPresentationMode.DISCREET))

        service.sendAiBriefingReady(9003, "Briefing title", "Briefing message", BRIEFING_KEY)
        val n = posted(9003).notification

        assertNull(NotificationKindMarker.read(n))
        assertEquals("Briefing title", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
    }

    @Test
    fun `legacy sendAnomalyAlert posts the supplied text with no marker and never reads the settings`() {
        val repo = repoFor(NotificationPresentationMode.DISCREET)
        val service = newService(appContext, repo)

        service.sendAnomalyAlert(9004, "Anomaly title", "Anomaly message", EXPENSE_ID)
        val n = posted(9004).notification

        assertNull(NotificationKindMarker.read(n))
        assertFalse(n.extras.containsKey(NotificationKindMarker.EXTRA_KEY))
        assertEquals("Anomaly title", n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals("Anomaly message", n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(0, repo.reads)
    }
}

// --- the same matrix on the other supported SDK levels --------------------------------------------------------------

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AndroidNotificationServicePresentationSdk26Test : AndroidNotificationServicePresentationContract() {
    override val expectedSdk: Int = 26
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class AndroidNotificationServicePresentationSdk28Test : AndroidNotificationServicePresentationContract() {
    override val expectedSdk: Int = 28
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AndroidNotificationServicePresentationSdk30Test : AndroidNotificationServicePresentationContract() {
    override val expectedSdk: Int = 30
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class AndroidNotificationServicePresentationSdk31Test : AndroidNotificationServicePresentationContract() {
    override val expectedSdk: Int = 31
}
