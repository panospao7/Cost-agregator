package com.yourname.expensetracker.data.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
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
 * S5a/S5b: [NotificationPresentationApplier] (INV-4, INV-5, INV-7, INV-8, INV-10, INV-12, INV-13 post side;
 * AE-109, AE-111, AE-112, AE-121). Real builder, real string resources, a sentinel as the only caller PII.
 * Action titles are string resource ids (S5b), so the only title text that can ever reach a notification
 * is a resolved resource string; the sentinel can only enter through the detailed title and text.
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
        NotificationActionSpec(android.R.drawable.ic_menu_agenda, SPEC_TITLE_RES[0], pendingIntent(1)),
        NotificationActionSpec(android.R.drawable.ic_menu_close_clear_cancel, SPEC_TITLE_RES[1], pendingIntent(2)),
        NotificationActionSpec(android.R.drawable.ic_menu_add, SPEC_TITLE_RES[2], pendingIntent(3))
    )

    /** The strings the three [specs] titles must resolve to, in order, read straight from the resources. */
    private fun expectedSpecTitles(): List<String> = SPEC_TITLE_RES.map { context.getString(it) }

    private fun singleActionSpec() =
        NotificationActionSpec(android.R.drawable.ic_menu_agenda, SINGLE_ACTION_TITLE_RES, pendingIntent(9))

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
    fun `INV-8 control the leak scan reads action titles so the action scan is not vacuous`() {
        val resolved = context.getString(SINGLE_ACTION_TITLE_RES)
        val decision = decision(NotificationKind.BILL, NotificationPresentationMode.FULL, policy = NotificationActionPolicy.UNCHANGED)

        val without = apply(decision)
        val with = apply(decision, actions = listOf(singleActionSpec()))

        // The resolved resource string is the action title, and only the notification with the action has it.
        assertEquals(listOf(resolved), actionTitles(with))
        assertTrue("the scan must include the resolved action title", everyString(with).any { it == resolved })
        assertFalse("without the action the scan must not hold the title", everyString(without).any { it == resolved })
        // The detailed sentinels still reach the FULL notification, so the surrounding scan is live.
        assertTrue(everyString(with).any { it.contains("Lidl") && it.contains("4242") })
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

        assertEquals(expectedSpecTitles(), actionTitles(n))
        assertEquals("the three spec titles are distinct, so the order assertion is meaningful", 3, expectedSpecTitles().toSet().size)
        n.actions.forEach { assertFalse(it.isAuthenticationRequired) }
    }

    @Test
    @Config(sdk = [31])
    fun `INV-10 AUTH_REQUIRED attaches every spec in order with authentication required on sdk 31`() {
        val n = apply(
            decision(NotificationKind.BILL, NotificationPresentationMode.FULL, policy = NotificationActionPolicy.AUTH_REQUIRED),
            actions = specs()
        )

        assertEquals(expectedSpecTitles(), actionTitles(n))
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
     * S5b closed the S5a gap: an action title is a string resource id resolved by the applier, so no caller
     * supplied free text can become an action title. In DISCREET the titles are exactly the resolved
     * resources, under both policies that keep actions, and no merchant sentinel appears anywhere.
     */
    @Test
    @Config(sdk = [34])
    fun `INV-8 DISCREET action titles come only from string resources`() {
        val policies = listOf(NotificationActionPolicy.UNCHANGED, NotificationActionPolicy.AUTH_REQUIRED)
        for (policy in policies) {
            val label = "policy=$policy"
            val single = apply(
                decision(NotificationKind.BILL, NotificationPresentationMode.DISCREET, policy = policy),
                actions = listOf(singleActionSpec())
            )
            val several = apply(
                decision(NotificationKind.BILL, NotificationPresentationMode.DISCREET, policy = policy),
                actions = specs()
            )

            assertEquals(label, listOf(context.getString(SINGLE_ACTION_TITLE_RES)), actionTitles(single))
            assertEquals(label, expectedSpecTitles(), actionTitles(several))
            assertNoSentinel(single, "$label single action")
            assertNoSentinel(several, "$label several actions")
            actionTitles(several).forEach { title ->
                SENTINELS.forEach { sentinel -> assertFalse("$label '$sentinel' in action title '$title'", title.contains(sentinel)) }
            }
            // The title and text are still the discreet constants.
            assertEquals(label, texts.discreet(NotificationKind.BILL).title, single.titleText())
            assertEquals(label, texts.discreet(NotificationKind.BILL).text, single.contentText())
        }
    }

    @Test
    @Config(sdk = [34])
    fun `INV-8 FULL action titles are the same resolved resources as DISCREET`() {
        for (policy in listOf(NotificationActionPolicy.UNCHANGED, NotificationActionPolicy.AUTH_REQUIRED)) {
            val full = apply(decision(NotificationKind.BILL, NotificationPresentationMode.FULL, policy = policy), actions = specs())
            val discreet = apply(decision(NotificationKind.BILL, NotificationPresentationMode.DISCREET, policy = policy), actions = specs())

            assertEquals(policy.toString(), expectedSpecTitles(), actionTitles(full))
            assertEquals(policy.toString(), actionTitles(full), actionTitles(discreet))
        }
    }

    @Test
    @Config(sdk = [30])
    fun `INV-10 AUTH_REQUIRED below sdk 31 still attaches every action in order`() {
        val n = apply(
            decision(NotificationKind.BILL, NotificationPresentationMode.FULL, policy = NotificationActionPolicy.AUTH_REQUIRED),
            actions = specs()
        )

        assertEquals(expectedSpecTitles(), actionTitles(n))
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

    // --- hardenLegacy (S5b) ---------------------------------------------------------------------------------

    private val legacyContentIntent: PendingIntent by lazy { pendingIntent(77) }

    /** A builder shaped like the legacy methods: own title, text, BigText, tap target, no visibility. */
    private fun legacyBuilder(withAction: Boolean = false): NotificationCompat.Builder =
        callerBuilder()
            .setContentTitle(LEGACY_TITLE)
            .setContentText(LEGACY_TEXT)
            .setStyle(NotificationCompat.BigTextStyle().bigText(LEGACY_TEXT))
            .setContentIntent(legacyContentIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .apply {
                if (withAction) addAction(android.R.drawable.ic_menu_agenda, "Pre-existing", pendingIntent(78))
            }

    private fun hardened(builder: NotificationCompat.Builder = legacyBuilder()): Notification =
        applier.hardenLegacy(builder, CHANNEL_ID, android.R.drawable.ic_dialog_info).build()

    @Test
    fun `INV-7 hardenLegacy sets private visibility and never public even over a public caller setting`() {
        val callerPublic = legacyBuilder().setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        // Control: without hardenLegacy the caller's PUBLIC setting stands, so the PRIVATE below is the applier's doing.
        assertEquals(Notification.VISIBILITY_PUBLIC, callerPublic.build().visibility)

        val n = hardened(callerPublic)

        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertNotEquals(Notification.VISIBILITY_PUBLIC, n.visibility)
    }

    @Test
    fun `control a legacy builder without hardenLegacy has no public version`() {
        val n = legacyBuilder().build()

        assertNull(n.publicVersion)
    }

    @Test
    fun `INV-7 hardenLegacy attaches the generic public version with no actions style marker or content intent`() {
        val generic = texts.genericPublic()
        val n = hardened(legacyBuilder(withAction = true))
        val pub = n.publicVersion

        assertNotNull(pub)
        assertEquals(generic.title, pub!!.titleText())
        assertEquals(generic.text, pub.contentText())
        assertTrue(pub.actions == null || pub.actions.isEmpty())
        assertNull(pub.extras.getString(Notification.EXTRA_TEMPLATE))
        assertNull(pub.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        assertNull(NotificationKindMarker.read(pub))
        assertFalse(pub.extras.containsKey(NotificationKindMarker.EXTRA_KEY))
        assertNull(pub.contentIntent)
        assertNull(pub.tickerText)
        assertFalse(LEGACY_PUBLIC_CHECK, everyString(pub).any { it.contains(LEGACY_TITLE) || it.contains(LEGACY_TEXT) })
    }

    @Test
    fun `AE-121 hardenLegacy stamps no kind marker`() {
        val n = hardened()

        assertNull(NotificationKindMarker.read(n))
        assertFalse(n.extras.containsKey(NotificationKindMarker.EXTRA_KEY))
    }

    @Test
    fun `INV-7 hardenLegacy adds no action and keeps the callers own`() {
        val none = hardened(legacyBuilder(withAction = false))
        val one = hardened(legacyBuilder(withAction = true))

        assertTrue(none.actions == null || none.actions.isEmpty())
        assertEquals(listOf("Pre-existing"), actionTitles(one))
    }

    @Test
    fun `INV-7 hardenLegacy leaves the visible title text style and tap target as built`() {
        val n = hardened()

        assertEquals(LEGACY_TITLE, n.titleText())
        assertEquals(LEGACY_TEXT, n.contentText())
        assertEquals(LEGACY_TEXT, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        assertTrue(n.extras.getString(Notification.EXTRA_TEMPLATE)!!.contains("BigTextStyle"))
        assertTrue(n.contentIntent === legacyContentIntent)
        assertEquals(CHANNEL_ID, n.channelId)
        assertEquals(NotificationCompat.CATEGORY_REMINDER, n.category)
    }

    @Test
    fun `INV-4 hardenLegacy returns the same builder it was given`() {
        val builder = legacyBuilder()

        val returned = applier.hardenLegacy(builder, CHANNEL_ID, android.R.drawable.ic_dialog_info)

        assertTrue(returned === builder)
    }

    @Test
    @Config(sdk = [26])
    fun `INV-7 hardenLegacy also holds on sdk 26`() {
        val n = hardened()

        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(texts.genericPublic().title, n.publicVersion!!.titleText())
        assertNull(NotificationKindMarker.read(n))
        assertEquals(LEGACY_TITLE, n.titleText())
    }

    private companion object {
        const val CHANNEL_ID = "applier_test_channel"
        const val SENTINEL_TITLE = "Lidl 4242 John"
        const val SENTINEL_TEXT = "Lidl 4242 John spent 42.50 EUR"
        const val LEGACY_TITLE = "Legacy budget exceeded"
        const val LEGACY_TEXT = "Legacy detail for Groceries: 120 of 100"
        const val LEGACY_PUBLIC_CHECK = "the public version must not repeat the legacy title or text"
        val SENTINELS = listOf("Lidl", "4242", "John", "42.50")

        /** Three distinct existing string resources used as action titles (resolved by the applier). */
        val SPEC_TITLE_RES = listOf(
            R.string.action_dismiss,
            R.string.notif_action_snooze,
            R.string.bill_reminders_mark_paid
        )
        val SINGLE_ACTION_TITLE_RES = R.string.action_cancel
    }
}
