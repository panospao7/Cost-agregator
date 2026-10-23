package com.yourname.expensetracker.data.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.domain.notification.presentation.NotificationActionPolicy
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationDecision
import com.yourname.expensetracker.domain.notification.presentation.NotificationPresentationMode
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
import org.robolectric.annotation.Config

/**
 * S5a: [NotificationPresentationApplier] (INV-4, INV-5, INV-7, INV-8, INV-10, INV-12, INV-13 post side;
 * AE-109, AE-111, AE-112, AE-121). Real builder, real string resources, a sentinel as the only caller PII.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NotificationPresentationApplierTest {

    private lateinit var context: Context
    private lateinit var texts: NotificationPresentationTexts
    private lateinit var applier: NotificationPresentationApplier

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        texts = NotificationPresentationTexts(context)
        applier = NotificationPresentationApplier(context, texts)
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun decision(
        kind: NotificationKind,
        mode: NotificationPresentationMode,
        expandedStyleAllowed: Boolean = false,
        policy: NotificationActionPolicy = NotificationActionPolicy.UNCHANGED
    ) = NotificationPresentationDecision(
        kind = kind,
        mode = mode,
        expandedStyleAllowed = expandedStyleAllowed,
        actionPolicy = policy
    )

    private fun callerBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)

    private fun pendingIntent(requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            Intent(Intent.ACTION_VIEW),
            PendingIntent.FLAG_IMMUTABLE
        )

    private fun specs(): List<NotificationActionSpec> = listOf(
        NotificationActionSpec(android.R.drawable.ic_menu_agenda, "Action one", pendingIntent(1)),
        NotificationActionSpec(android.R.drawable.ic_menu_close_clear_cancel, "Action two", pendingIntent(2)),
        NotificationActionSpec(android.R.drawable.ic_menu_add, "Action three", pendingIntent(3))
    )

    private fun sentinelActionSpec() =
        NotificationActionSpec(android.R.drawable.ic_menu_agenda, SENTINEL_ACTION_TITLE, pendingIntent(9))

    private fun apply(
        decision: NotificationPresentationDecision,
        title: String = SENTINEL_TITLE,
        text: String = SENTINEL_TEXT,
        actions: List<NotificationActionSpec> = emptyList()
    ): Notification =
        applier.apply(
            builder = callerBuilder(),
            decision = decision,
            channelId = CHANNEL_ID,
            smallIcon = android.R.drawable.ic_dialog_info,
            detailedTitle = title,
            detailedText = text,
            actions = actions
        ).build()

    private fun Notification.titleText(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.contentText(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    /** Every string reachable from the notification: ticker, every extra value (flattened), and the public version. */
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

    // --- FULL ----------------------------------------------------------------------------------

    @Test
    fun `INV-5 FULL shows the detailed title and text`() {
        NotificationKind.entries.forEach { kind ->
            val n = apply(decision(kind, NotificationPresentationMode.FULL))

            assertEquals(SENTINEL_TITLE, n.titleText())
            assertEquals(SENTINEL_TEXT, n.contentText())
        }
    }

    @Test
    fun `INV-8 control the leak scan sees the sentinel in FULL so the DISCREET scan is not vacuous`() {
        val n = apply(decision(NotificationKind.BUDGET, NotificationPresentationMode.FULL))

        assertTrue(everyString(n).any { it.contains("Lidl") })
        assertTrue(everyString(n).any { it.contains("4242") })
    }

    @Test
    fun `INV-8 control the leak scan sees a sentinel in an action title so the action scan is not vacuous`() {
        val n = apply(
            decision(NotificationKind.BILL, NotificationPresentationMode.FULL, policy = NotificationActionPolicy.UNCHANGED),
            actions = listOf(sentinelActionSpec())
        )

        assertEquals(listOf(SENTINEL_ACTION_TITLE), actionTitles(n))
        assertTrue(everyString(n).any { it.contains("Lidl") && it.contains("4242") })
        assertTrue(SENTINELS.any { sentinel -> everyString(n).any { it.contains(sentinel) } })
    }

    // --- DISCREET ------------------------------------------------------------------------------

    @Test
    fun `INV-5 DISCREET shows the kind constants and never the detailed text`() {
        NotificationKind.entries.forEach { kind ->
            val n = apply(decision(kind, NotificationPresentationMode.DISCREET))

            assertEquals(texts.discreet(kind).title, n.titleText())
            assertEquals(texts.discreet(kind).text, n.contentText())
        }
    }

    @Test
    fun `INV-8 DISCREET leaks no sentinel into title text bigText subText ticker extras or public version`() {
        for (kind in NotificationKind.entries) {
            for (expanded in listOf(false, true)) {
                for (policy in NotificationActionPolicy.entries) {
                    val n = apply(
                        decision(kind, NotificationPresentationMode.DISCREET, expanded, policy),
                        actions = specs()
                    )
                    val label = "$kind expanded=$expanded policy=$policy"

                    assertNoSentinel(n, label)
                    assertFalse(label, n.titleText().orEmpty().contains("Lidl"))
                    assertFalse(label, n.contentText().orEmpty().contains("4242"))
                    assertNull(label, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                    assertNull(label, n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                    assertNull(label, n.extras.getCharSequence(Notification.EXTRA_INFO_TEXT))
                    assertNull(label, n.extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT))
                    assertNull(label, n.extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES))
                    assertNull(label, n.tickerText)
                    assertNull(label, n.extras.getString(Notification.EXTRA_TEMPLATE))
                    assertNull(label, n.publicVersion?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT))
                }
            }
        }
    }

    @Test
    fun `INV-8 DISCREET ignores a detailed text carrying the sentinel in both title and text positions`() {
        val n = apply(
            decision(NotificationKind.RECEIPT_MATCH, NotificationPresentationMode.DISCREET),
            title = "Lidl 4242 John",
            text = "Receipt from Lidl 4242 John matched"
        )

        assertEquals(texts.discreet(NotificationKind.RECEIPT_MATCH).title, n.titleText())
        assertEquals(texts.discreet(NotificationKind.RECEIPT_MATCH).text, n.contentText())
        assertNoSentinel(n, "receipt match")
        assertFalse(everyString(n).any { it.contains("Receipt from") })
    }

    @Test
    fun `AE-111 DISCREET receipt match shows the receipt pair and not the budget pair`() {
        val receipt = apply(decision(NotificationKind.RECEIPT_MATCH, NotificationPresentationMode.DISCREET))
        val budget = texts.discreet(NotificationKind.BUDGET)

        assertEquals(texts.discreet(NotificationKind.RECEIPT_MATCH).title, receipt.titleText())
        assertNotEquals(budget.title, receipt.titleText())
    }

    // --- visibility and the public version -------------------------------------------------------

    @Test
    fun `INV-7 visibility is private and never public in both modes for every kind`() {
        for (kind in NotificationKind.entries) {
            for (mode in NotificationPresentationMode.entries) {
                val n = apply(decision(kind, mode))

                assertEquals("$kind $mode", Notification.VISIBILITY_PRIVATE, n.visibility)
                assertNotEquals("$kind $mode", Notification.VISIBILITY_PUBLIC, n.visibility)
            }
        }
    }

    @Test
    fun `INV-7 the public version is the generic pair in both modes for every kind`() {
        val generic = texts.genericPublic()
        for (kind in NotificationKind.entries) {
            for (mode in NotificationPresentationMode.entries) {
                val n = apply(decision(kind, mode), actions = specs())
                val pub = n.publicVersion

                assertNotNull("$kind $mode has a public version", pub)
                assertEquals("$kind $mode", generic.title, pub!!.titleText())
                assertEquals("$kind $mode", generic.text, pub.contentText())
            }
        }
    }

    @Test
    fun `AE-109 the public version has no actions no style no marker and no content intent`() {
        for (kind in NotificationKind.entries) {
            for (mode in NotificationPresentationMode.entries) {
                val n = apply(decision(kind, mode, expandedStyleAllowed = true, policy = NotificationActionPolicy.UNCHANGED), actions = specs())
                val pub = n.publicVersion!!
                val label = "$kind $mode"

                assertTrue(label, pub.actions == null || pub.actions.isEmpty())
                assertNull(label, pub.extras.getString(Notification.EXTRA_TEMPLATE))
                assertNull(label, pub.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
                assertNull(label, NotificationKindMarker.read(pub))
                assertFalse(label, pub.extras.containsKey(NotificationKindMarker.EXTRA_KEY))
                assertNull(label, pub.contentIntent)
                assertNull(label, pub.tickerText)
            }
        }
    }

    @Test
    fun `AE-109 the public version is identical for all kinds and differs from every discreet title`() {
        val publicTitles = NotificationKind.entries.map { apply(decision(it, NotificationPresentationMode.DISCREET)).publicVersion!!.titleText() }
        val publicTexts = NotificationKind.entries.map { apply(decision(it, NotificationPresentationMode.FULL)).publicVersion!!.contentText() }

        assertEquals(1, publicTitles.toSet().size)
        assertEquals(1, publicTexts.toSet().size)
        NotificationKind.entries.forEach { kind ->
            assertNotEquals(texts.discreet(kind).title, publicTitles.first())
        }
    }

    @Test
    fun `AE-109 the public version carries no sentinel in FULL either`() {
        NotificationKind.entries.forEach { kind ->
            val pub = apply(decision(kind, NotificationPresentationMode.FULL)).publicVersion!!

            assertNoSentinel(pub, "$kind public")
        }
    }

    // --- BigText ----------------------------------------------------------------------------------

    @Test
    fun `INV-5 BigText is set only when the expanded style is allowed and the mode is FULL`() {
        val n = apply(decision(NotificationKind.ANOMALY, NotificationPresentationMode.FULL, expandedStyleAllowed = true))

        assertEquals(SENTINEL_TEXT, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        assertNotNull(n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertTrue(n.extras.getString(Notification.EXTRA_TEMPLATE)!!.contains("BigTextStyle"))
    }

    @Test
    fun `INV-5 FULL without the expanded style allowance sets no style`() {
        val n = apply(decision(NotificationKind.BILL, NotificationPresentationMode.FULL, expandedStyleAllowed = false))

        assertNull(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertNull(n.extras.getString(Notification.EXTRA_TEMPLATE))
    }

    @Test
    fun `INV-8 DISCREET with the expanded style allowance still sets no style`() {
        val n = apply(decision(NotificationKind.BRIEFING, NotificationPresentationMode.DISCREET, expandedStyleAllowed = true))

        assertNull(n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertNull(n.extras.getString(Notification.EXTRA_TEMPLATE))
        assertNoSentinel(n, "briefing")
    }

    // --- action policies ---------------------------------------------------------------------------

    private fun actionTitles(n: Notification): List<String> =
        (n.actions ?: emptyArray()).map { it.title.toString() }

    @Test
    fun `INV-10 UNCHANGED attaches the specs in order without authentication`() {
        val n = apply(
            decision(NotificationKind.BILL, NotificationPresentationMode.FULL, policy = NotificationActionPolicy.UNCHANGED),
            actions = specs()
        )

        assertEquals(listOf("Action one", "Action two", "Action three"), actionTitles(n))
        n.actions.forEach { assertFalse(it.isAuthenticationRequired) }
    }

    @Test
    @Config(sdk = [31])
    fun `INV-10 AUTH_REQUIRED attaches every spec in order with authentication required on sdk 31`() {
        val n = apply(
            decision(NotificationKind.BILL, NotificationPresentationMode.FULL, policy = NotificationActionPolicy.AUTH_REQUIRED),
            actions = specs()
        )

        assertEquals(listOf("Action one", "Action two", "Action three"), actionTitles(n))
        n.actions.forEach { assertTrue(it.title.toString(), it.isAuthenticationRequired) }
    }

    @Test
    @Config(sdk = [34])
    fun `INV-10 AUTH_REQUIRED also holds in DISCREET and keeps the actions`() {
        val n = apply(
            decision(NotificationKind.BILL, NotificationPresentationMode.DISCREET, policy = NotificationActionPolicy.AUTH_REQUIRED),
            actions = specs()
        )

        assertEquals(3, n.actions.size)
        n.actions.forEach { assertTrue(it.isAuthenticationRequired) }
        assertNoSentinel(n, "bill discreet auth required")
    }

    /**
     * KNOWN GAP, carried forward to S5b (resource-only action titles). The applier does not sanitize action
     * titles: they are caller supplied and attached verbatim in DISCREET, so a caller passing a title with
     * PII leaks it onto the lock screen. Today's only caller (BILL) supplies constant resource strings, so
     * no leak is reachable. This test documents the CURRENT behaviour; when S5b makes titles resource-only
     * (or the applier replaces them in DISCREET) this assertion must flip to assert absence.
     */
    @Test
    @Config(sdk = [34])
    fun `KNOWN GAP S5b action titles are caller supplied and pass through in DISCREET`() {
        val n = apply(
            decision(NotificationKind.BILL, NotificationPresentationMode.DISCREET, policy = NotificationActionPolicy.UNCHANGED),
            actions = listOf(sentinelActionSpec())
        )
        val authRequired = apply(
            decision(NotificationKind.BILL, NotificationPresentationMode.DISCREET, policy = NotificationActionPolicy.AUTH_REQUIRED),
            actions = listOf(sentinelActionSpec())
        )

        assertEquals(listOf(SENTINEL_ACTION_TITLE), actionTitles(n))
        assertEquals(listOf(SENTINEL_ACTION_TITLE), actionTitles(authRequired))
        // The title and text are still the discreet constants; only the action title is the gap.
        assertEquals(texts.discreet(NotificationKind.BILL).title, n.titleText())
        assertEquals(texts.discreet(NotificationKind.BILL).text, n.contentText())
    }

    @Test
    @Config(sdk = [30])
    fun `INV-10 AUTH_REQUIRED below sdk 31 still attaches every action in order`() {
        val n = apply(
            decision(NotificationKind.BILL, NotificationPresentationMode.FULL, policy = NotificationActionPolicy.AUTH_REQUIRED),
            actions = specs()
        )

        assertEquals(listOf("Action one", "Action two", "Action three"), actionTitles(n))
    }

    @Test
    fun `INV-10 NONE attaches no action even when specs are supplied`() {
        for (mode in NotificationPresentationMode.entries) {
            val n = apply(
                decision(NotificationKind.BILL, mode, policy = NotificationActionPolicy.NONE),
                actions = specs()
            )

            assertTrue(mode.toString(), n.actions == null || n.actions.isEmpty())
        }
    }

    @Test
    fun `INV-10 no specs attaches no action under any policy`() {
        for (policy in NotificationActionPolicy.entries) {
            val n = apply(decision(NotificationKind.BUDGET, NotificationPresentationMode.FULL, policy = policy))

            assertTrue(policy.toString(), n.actions == null || n.actions.isEmpty())
        }
    }

    @Test
    fun `INV-10 the action PendingIntents are carried through unchanged`() {
        val supplied = specs()
        val n = apply(
            decision(NotificationKind.BILL, NotificationPresentationMode.FULL, policy = NotificationActionPolicy.UNCHANGED),
            actions = supplied
        )

        assertEquals(supplied.map { it.pendingIntent }, n.actions.map { it.actionIntent })
    }

    // --- kind marker ---------------------------------------------------------------------------------

    @Test
    fun `AE-112 the kind marker is stamped for all six kinds in both modes`() {
        for (kind in NotificationKind.entries) {
            for (mode in NotificationPresentationMode.entries) {
                val n = apply(decision(kind, mode))

                assertEquals("$kind $mode", kind.stableToken, NotificationKindMarker.read(n))
            }
        }
    }

    @Test
    fun `AE-121 the marker holds the token only so it carries no more than the discreet title`() {
        val n = apply(decision(NotificationKind.WARRANTY, NotificationPresentationMode.FULL))

        assertEquals("WARRANTY", n.extras.getString(NotificationKindMarker.EXTRA_KEY))
        assertNull(NotificationKindMarker.read(n.publicVersion))
    }

    // --- builder contract ------------------------------------------------------------------------------

    @Test
    fun `INV-4 apply returns the same builder it was given`() {
        val builder = callerBuilder()

        val returned = applier.apply(
            builder = builder,
            decision = decision(NotificationKind.BUDGET, NotificationPresentationMode.DISCREET),
            channelId = CHANNEL_ID,
            smallIcon = android.R.drawable.ic_dialog_info,
            detailedTitle = SENTINEL_TITLE,
            detailedText = SENTINEL_TEXT
        )

        assertTrue(returned === builder)
    }

    @Test
    @Suppress("DEPRECATION")
    fun `INV-4 caller owned settings survive apply`() {
        val n = apply(decision(NotificationKind.BUDGET, NotificationPresentationMode.DISCREET))

        assertEquals(CHANNEL_ID, n.channelId)
        assertEquals(NotificationCompat.PRIORITY_HIGH, n.priority)
        assertTrue((n.flags and Notification.FLAG_AUTO_CANCEL) != 0)
    }

    private companion object {
        const val CHANNEL_ID = "applier_test_channel"
        const val SENTINEL_TITLE = "Lidl 4242 John"
        const val SENTINEL_TEXT = "Lidl 4242 John spent 42.50 EUR"
        const val SENTINEL_ACTION_TITLE = "Lidl 4242"
        val SENTINELS = listOf("Lidl", "4242", "John", "42.50")
    }
}
