package com.yourname.expensetracker.data.service

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.notification.presentation.NotificationKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * S5a: [NotificationPresentationTexts] constants (INV-5, INV-7, INV-8, INV-12; AE-109, AE-111).
 * Real string resources through Robolectric; no caller text can enter these strings.
 */
@RunWith(RobolectricTestRunner::class)
class NotificationPresentationTextsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun texts(): NotificationPresentationTexts = NotificationPresentationTexts(context)

    /** Every discreet and public string, by key id, for the locale of [context]. */
    private fun allConstants(ctx: Context): Map<String, String> {
        val t = NotificationPresentationTexts(ctx)
        val out = linkedMapOf<String, String>()
        NotificationKind.entries.forEach { kind ->
            out["discreet.${kind.stableToken}.title"] = t.discreet(kind).title
            out["discreet.${kind.stableToken}.text"] = t.discreet(kind).text
        }
        out["public.title"] = t.genericPublic().title
        out["public.text"] = t.genericPublic().text
        return out
    }

    private fun hasEmoji(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val type = Character.getType(cp)
            val symbolic = type == Character.OTHER_SYMBOL.toInt() || type == Character.SURROGATE.toInt()
            val inEmojiBlock = cp in 0x1F000..0x1FFFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF
            val joiner = cp == 0x200D || cp == 0xFE0F || cp == 0x20E3
            if (symbolic || inEmojiBlock || joiner) return true
            i += Character.charCount(cp)
        }
        return false
    }

    // --- INV-12 / AE-111: one distinct pair per kind -----------------------------------------

    @Test
    fun `INV-12 the six kinds have six distinct non blank discreet pairs`() {
        val t = texts()
        val pairs = NotificationKind.entries.map { t.discreet(it) }

        assertEquals(6, NotificationKind.entries.size)
        assertEquals(6, pairs.toSet().size)
        pairs.forEach {
            assertTrue(it.title.isNotBlank())
            assertTrue(it.text.isNotBlank())
        }
        assertEquals("discreet titles are distinct per kind", 6, pairs.map { it.title }.toSet().size)
    }

    @Test
    fun `AE-111 receipt match discreet pair differs from the budget pair and is the dedicated resource`() {
        val t = texts()
        val receipt = t.discreet(NotificationKind.RECEIPT_MATCH)
        val budget = t.discreet(NotificationKind.BUDGET)

        assertNotEquals(budget, receipt)
        assertNotEquals(budget.title, receipt.title)
        assertEquals(context.getString(R.string.notif_discreet_receipt_match_title), receipt.title)
        assertEquals(context.getString(R.string.notif_discreet_receipt_match_text), receipt.text)
    }

    @Test
    fun `INV-12 each kind maps to its own resources`() {
        val t = texts()
        assertEquals(context.getString(R.string.notif_discreet_bill_title), t.discreet(NotificationKind.BILL).title)
        assertEquals(context.getString(R.string.notif_discreet_bill_text), t.discreet(NotificationKind.BILL).text)
        assertEquals(context.getString(R.string.notif_discreet_anomaly_title), t.discreet(NotificationKind.ANOMALY).title)
        assertEquals(context.getString(R.string.notif_discreet_anomaly_text), t.discreet(NotificationKind.ANOMALY).text)
        assertEquals(context.getString(R.string.notif_discreet_budget_title), t.discreet(NotificationKind.BUDGET).title)
        assertEquals(context.getString(R.string.notif_discreet_budget_text), t.discreet(NotificationKind.BUDGET).text)
        assertEquals(context.getString(R.string.notif_discreet_warranty_title), t.discreet(NotificationKind.WARRANTY).title)
        assertEquals(context.getString(R.string.notif_discreet_warranty_text), t.discreet(NotificationKind.WARRANTY).text)
        assertEquals(context.getString(R.string.notif_discreet_briefing_text), t.discreet(NotificationKind.BRIEFING).text)
    }

    @Test
    fun `INV-5 the briefing discreet title is the existing briefing ready title`() {
        assertEquals(
            context.getString(R.string.notification_briefing_ready_title),
            texts().discreet(NotificationKind.BRIEFING).title
        )
    }

    // --- INV-8: constants only ----------------------------------------------------------------

    @Test
    fun `INV-8 no discreet or public string contains a percent placeholder`() {
        allConstants(context).forEach { (key, value) ->
            assertFalse("$key contains a placeholder", value.contains('%'))
        }
    }

    @Test
    fun `INV-8 no discreet or public string contains an emoji or symbol character`() {
        allConstants(context).forEach { (key, value) ->
            assertFalse("$key contains an emoji", hasEmoji(value))
        }
    }

    @Test
    fun `INV-8 discreet and public strings carry no digits`() {
        allConstants(context).forEach { (key, value) ->
            assertTrue("$key contains a digit", value.none { it.isDigit() })
        }
    }

    // --- INV-7 / AE-109: the generic public pair ----------------------------------------------

    @Test
    fun `AE-109 the generic public pair is one non blank pair from the public resources`() {
        val t = texts()

        assertEquals(context.getString(R.string.notif_public_title), t.genericPublic().title)
        assertEquals(context.getString(R.string.notif_public_text), t.genericPublic().text)
        assertTrue(t.genericPublic().title.isNotBlank())
        assertTrue(t.genericPublic().text.isNotBlank())
    }

    @Test
    fun `AE-109 the public title differs from every kind discreet title`() {
        val t = texts()
        val publicTitle = t.genericPublic().title

        NotificationKind.entries.forEach { kind ->
            assertNotEquals("public title equals discreet title of $kind", t.discreet(kind).title, publicTitle)
        }
    }

    @Test
    fun `AE-109 the public pair is a function of nothing so it is identical on every call`() {
        val t = texts()
        val first = t.genericPublic()

        NotificationKind.entries.forEach { _ -> assertEquals(first, t.genericPublic()) }
    }

    // --- el / es parity -------------------------------------------------------------------------

    private fun englishConstants(): Map<String, String> {
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.ENGLISH) }
        return allConstants(context.createConfigurationContext(config))
    }

    private fun assertLocalized(expectedLanguage: String) {
        assertEquals(expectedLanguage, context.resources.configuration.locales[0].language)
        val english = englishConstants()
        val localized = allConstants(context)

        assertEquals(english.keys, localized.keys)
        localized.forEach { (key, value) ->
            assertTrue("$key is blank in $expectedLanguage", value.isNotBlank())
            assertNotEquals("$key falls back to English in $expectedLanguage", english.getValue(key), value)
            assertFalse("$key has a placeholder in $expectedLanguage", value.contains('%'))
            assertFalse("$key has an emoji in $expectedLanguage", hasEmoji(value))
        }
        // The six pairs stay distinct and the receipt pair stays distinct from the budget pair.
        val t = NotificationPresentationTexts(context)
        assertEquals(6, NotificationKind.entries.map { t.discreet(it) }.toSet().size)
        assertNotEquals(t.discreet(NotificationKind.BUDGET), t.discreet(NotificationKind.RECEIPT_MATCH))
    }

    @Test
    @Config(qualifiers = "el")
    fun `el every discreet and public string resolves to a Greek value`() {
        assertLocalized("el")
    }

    @Test
    @Config(qualifiers = "es")
    fun `es every discreet and public string resolves to a Spanish value`() {
        assertLocalized("es")
    }

    @Test
    @Config(qualifiers = "el")
    fun `el briefing discreet title equals the localized briefing ready title`() {
        assertEquals(
            context.getString(R.string.notification_briefing_ready_title),
            texts().discreet(NotificationKind.BRIEFING).title
        )
    }

    @Test
    @Config(qualifiers = "es")
    fun `es briefing discreet title equals the localized briefing ready title`() {
        assertEquals(
            context.getString(R.string.notification_briefing_ready_title),
            texts().discreet(NotificationKind.BRIEFING).title
        )
    }
}
