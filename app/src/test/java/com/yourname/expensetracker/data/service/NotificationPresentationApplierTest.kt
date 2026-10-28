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

    // --- G1b-0 presentation-mode token ------------------------------------------------------------------

    @Test
    fun `G1b-0 the mode extras key and the two tokens are pinned literals`() {
        assertEquals("com.yourname.expensetracker.extra.PRESENTATION_MODE", NotificationPresentationApplier.MODE_EXTRA_KEY)
        assertEquals("D", NotificationPresentationApplier.MODE_TOKEN_DISCREET)
        assertEquals("F", NotificationPresentationApplier.MODE_TOKEN_FULL)
        assertNotEquals(NotificationKindMarker.EXTRA_KEY, NotificationPresentationApplier.MODE_EXTRA_KEY)
    }

    @Test
    fun `G1b-0 DISCREET stamps token D and FULL stamps token F next to the kind marker for every kind`() {
        for (kind in NotificationKind.entries) {
            val discreet = apply(decision(kind, NotificationPresentationMode.DISCREET))
            val full = apply(decision(kind, NotificationPresentationMode.FULL))

            assertEquals("$kind DISCREET", "D", NotificationPresentationApplier.readModeToken(discreet))
            assertEquals("$kind FULL", "F", NotificationPresentationApplier.readModeToken(full))
            assertEquals("$kind DISCREET raw extra", "D", discreet.extras.getString(NotificationPresentationApplier.MODE_EXTRA_KEY))
            assertEquals("$kind FULL raw extra", "F", full.extras.getString(NotificationPresentationApplier.MODE_EXTRA_KEY))
            // The kind marker is still present beside the token, unchanged.
            assertEquals("$kind DISCREET marker", kind.stableToken, NotificationKindMarker.read(discreet))
            assertEquals("$kind FULL marker", kind.stableToken, NotificationKindMarker.read(full))
        }
    }

    @Test
    fun `G1b-0 the token is absent from the public version in both modes and carries no content`() {
        for (kind in NotificationKind.entries) {
            for (mode in NotificationPresentationMode.entries) {
                val n = apply(decision(kind, mode), actions = specs())
                val pub = n.publicVersion!!
                val label = "$kind $mode"

                assertNull(label, NotificationPresentationApplier.readModeToken(pub))
                assertFalse(label, pub.extras.containsKey(NotificationPresentationApplier.MODE_EXTRA_KEY))
                // The token is exactly the one-letter literal, so it cannot carry the detailed text.
                assertTrue(label, NotificationPresentationApplier.readModeToken(n) in setOf("D", "F"))
            }
        }
    }

    @Test
    fun `G1b-0 readModeToken is null for a null notification an absent token and a non-string value and never throws`() {
        assertNull(NotificationPresentationApplier.readModeToken(null))
        assertNull("control: a caller builder without apply has no token", NotificationPresentationApplier.readModeToken(callerBuilder().build()))
        val wrongType = callerBuilder()
            .addExtras(Bundle().apply { putInt(NotificationPresentationApplier.MODE_EXTRA_KEY, 7) })
            .build()
        assertNull(NotificationPresentationApplier.readModeToken(wrongType))
        val junk = callerBuilder()
            .addExtras(Bundle().apply { putString(NotificationPresentationApplier.MODE_EXTRA_KEY, "garbage") })
            .build()
        assertEquals("a junk string is returned as is; only D counts as discreet", "garbage", NotificationPresentationApplier.readModeToken(junk))
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

    // --- S9a: the former hardenLegacy tests, migrated onto apply ----------------------------------------------
    // hardenLegacy is gone with the kind-less methods. Its visibility, public-version, marker and builder-contract
    // assertions are now made against apply(); the equivalents that are not already covered above follow.

    private val callerContentIntent: PendingIntent by lazy { pendingIntent(77) }

    /** A builder shaped like the production callers: own tap target and category, no visibility. */
    private fun callerBuilderWithTapTarget(): NotificationCompat.Builder =
        callerBuilder()
            .setContentIntent(callerContentIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)

    private fun applyTo(builder: NotificationCompat.Builder, decision: NotificationPresentationDecision): Notification =
        applier.apply(
            builder = builder,
            decision = decision,
            channelId = CHANNEL_ID,
            smallIcon = android.R.drawable.ic_dialog_info,
            detailedTitle = SENTINEL_TITLE,
            detailedText = SENTINEL_TEXT
        ).build()

    @Test
    fun `INV-7 apply sets private visibility and never public even over a public caller setting`() {
        for (mode in NotificationPresentationMode.entries) {
            val callerPublic = callerBuilderWithTapTarget().setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Control: without apply the caller's PUBLIC setting stands, so the PRIVATE below is the applier's doing.
            assertEquals(Notification.VISIBILITY_PUBLIC, callerPublic.build().visibility)

            val n = applyTo(callerPublic, decision(NotificationKind.BUDGET, mode))

            assertEquals("$mode", Notification.VISIBILITY_PRIVATE, n.visibility)
            assertNotEquals("$mode", Notification.VISIBILITY_PUBLIC, n.visibility)
        }
    }

    @Test
    fun `control a caller builder without apply has no public version`() {
        val n = callerBuilderWithTapTarget().build()

        assertNull(n.publicVersion)
    }

    @Test
    fun `INV-7 apply with a pre-existing caller action keeps no action on the public version and no leak of the detailed text`() {
        val generic = texts.genericPublic()
        val builder = callerBuilderWithTapTarget().addAction(android.R.drawable.ic_menu_agenda, "Pre-existing", pendingIntent(78))
        val pub = applyTo(builder, decision(NotificationKind.BUDGET, NotificationPresentationMode.FULL)).publicVersion

        assertNotNull(pub)
        assertEquals(generic.title, pub!!.titleText())
        assertEquals(generic.text, pub.contentText())
        assertTrue(pub.actions == null || pub.actions.isEmpty())
        assertNull(pub.contentIntent)
        assertNull(pub.tickerText)
        assertNoSentinel(pub, "public version")
    }

    @Test
    fun `INV-7 apply leaves the callers tap target category and channel as built in both modes`() {
        for (mode in NotificationPresentationMode.entries) {
            val n = applyTo(callerBuilderWithTapTarget(), decision(NotificationKind.BUDGET, mode))

            assertTrue("$mode", n.contentIntent === callerContentIntent)
            assertEquals("$mode", CHANNEL_ID, n.channelId)
            assertEquals("$mode", NotificationCompat.CATEGORY_REMINDER, n.category)
        }
    }

    @Test
    fun `INV-7 apply adds no action of its own when no specs are supplied`() {
        val n = applyTo(callerBuilderWithTapTarget(), decision(NotificationKind.BUDGET, NotificationPresentationMode.FULL))

        assertTrue(n.actions == null || n.actions.isEmpty())
    }

    @Test
    @Config(sdk = [26])
    fun `INV-7 apply also holds on sdk 26 with private visibility the generic public version and the marker`() {
        val n = applyTo(callerBuilderWithTapTarget(), decision(NotificationKind.BUDGET, NotificationPresentationMode.FULL))

        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals(texts.genericPublic().title, n.publicVersion!!.titleText())
        assertEquals("BUDGET", NotificationKindMarker.read(n))
        assertEquals(SENTINEL_TITLE, n.titleText())
    }

    private companion object {
        const val CHANNEL_ID = "applier_test_channel"
        const val SENTINEL_TITLE = "Lidl 4242 John"
        const val SENTINEL_TEXT = "Lidl 4242 John spent 42.50 EUR"
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
