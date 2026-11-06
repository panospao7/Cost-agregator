package com.yourname.expensetracker.ui.screens.recurringmanual

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Comment
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * FC-PAID-UNLINKED S3 (ux-flows sections 7.2 and 9): the 13 new keys (12 strings and 1 plural) exist in en,
 * es and el; every es and el entry is flagged DRAFT directly above it (owner native sign-off pending); the
 * plural has one and other (es also many); the format placeholders are identical across the locales.
 */
class PaymentLinkStringsTest {

    private val stringKeys = listOf(
        "recurring_link_suggestion_title_format",
        "recurring_link_action",
        "recurring_link_decline",
        "recurring_link_suggestion_cd_format",
        "recurring_link_action_cd_format",
        "recurring_link_decline_cd_format",
        "recurring_link_done_format",
        "recurring_link_declined",
        "recurring_link_no_longer_valid",
        "recurring_link_suggestions_unavailable",
        "home_paid_bill_hint_action",
        "home_paid_bill_hint_review_cd"
    )
    private val pluralKey = "home_paid_bill_hint"
    private val allKeys = stringKeys + pluralKey

    /** Highest positional argument each format string takes (ux-flows section 7.2). */
    private val expectedArgumentCount = mapOf(
        "recurring_link_suggestion_title_format" to 2,
        "recurring_link_suggestion_cd_format" to 4,
        "recurring_link_action_cd_format" to 3,
        "recurring_link_decline_cd_format" to 3,
        "recurring_link_done_format" to 1
    )

    private val placeholder = Regex("%(\\d+)\\$([a-z])")
    private val currencySymbol = Regex("\\p{Sc}")
    private val translatedFolders = listOf("values-es", "values-el")
    private val allFolders = listOf("values") + translatedFolders

    private fun parse(folder: String): Document {
        val file = listOf(
            "src/main/res/$folder/strings_payment_link.xml",
            "app/src/main/res/$folder/strings_payment_link.xml",
            "../app/src/main/res/$folder/strings_payment_link.xml"
        ).map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("$folder/strings_payment_link.xml not found", file)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file!!)
    }

    private fun elements(doc: Document, tag: String): List<Element> {
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun stringElement(doc: Document, name: String): Element? =
        elements(doc, "string").firstOrNull { it.getAttribute("name") == name }

    private fun pluralElement(doc: Document): Element? =
        elements(doc, "plurals").firstOrNull { it.getAttribute("name") == pluralKey }

    private fun element(doc: Document, key: String): Element? =
        if (key == pluralKey) pluralElement(doc) else stringElement(doc, key)

    private fun quantities(plural: Element): Map<String, String> =
        elements(plural.ownerDocument, "item")
            .filter { it.parentNode == plural }
            .associate { it.getAttribute("quantity") to it.textContent }

    private fun placeholders(value: String): List<Pair<String, String>> =
        placeholder.findAll(value).map { it.groupValues[1] to it.groupValues[2] }.toList()

    private fun precedingComment(element: Element): Comment? {
        var previous: Node? = element.previousSibling
        while (previous != null && previous.nodeType == Node.TEXT_NODE && previous.textContent.isBlank()) {
            previous = previous.previousSibling
        }
        return previous as? Comment
    }

    // ---- presence ------------------------------------------------------------------------------

    @Test
    fun `all 13 keys are present in values values-es and values-el`() {
        assertEquals(13, allKeys.size)
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in allKeys) {
                assertNotNull("$folder is missing $key", element(doc, key))
            }
        }
    }

    @Test
    fun `each locale file defines exactly the 13 keys and no duplicates`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            val names = elements(doc, "string").map { it.getAttribute("name") } +
                elements(doc, "plurals").map { it.getAttribute("name") }
            assertEquals("$folder defines duplicate or extra keys: $names", allKeys.sorted(), names.sorted())
        }
    }

    @Test
    fun `no value is blank in any locale`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in stringKeys) {
                assertTrue("$folder/$key is blank", stringElement(doc, key)!!.textContent.isNotBlank())
            }
            for ((quantity, value) in quantities(pluralElement(doc)!!)) {
                assertTrue("$folder/$pluralKey/$quantity is blank", value.isNotBlank())
            }
        }
    }

    // ---- DRAFT flag on es and el ----------------------------------------------------------------

    @Test
    fun `es and el entries are each directly preceded by a DRAFT comment`() {
        for (folder in translatedFolders) {
            val doc = parse(folder)
            for (key in allKeys) {
                val comment = precedingComment(element(doc, key)!!)
                assertTrue(
                    "$folder/$key must be directly preceded by a DRAFT comment",
                    comment != null && comment.data.contains("DRAFT")
                )
            }
        }
    }

    // ---- plural ---------------------------------------------------------------------------------

    @Test
    fun `plural has one and other in every locale and es also has many`() {
        for (folder in allFolders) {
            val quantities = quantities(pluralElement(parse(folder))!!)
            assertTrue("$folder is missing one", "one" in quantities)
            assertTrue("$folder is missing other", "other" in quantities)
        }
        assertTrue("values-es is missing many", "many" in quantities(pluralElement(parse("values-es"))!!))
    }

    @Test
    fun `every plural item carries the count placeholder as an integer`() {
        for (folder in allFolders) {
            for ((quantity, value) in quantities(pluralElement(parse(folder))!!)) {
                assertEquals("$folder/$quantity", listOf("1" to "d"), placeholders(value))
            }
        }
    }

    @Test
    fun `English plural reads as one bill versus bills`() {
        val en = quantities(pluralElement(parse("values"))!!)
        assertEquals("1 bill may already be paid.", String.format(Locale.US, en.getValue("one"), 1))
        assertEquals("2 bills may already be paid.", String.format(Locale.US, en.getValue("other"), 2))
    }

    // ---- placeholders ---------------------------------------------------------------------------

    @Test
    fun `format strings take exactly the documented positional arguments in en`() {
        val en = parse("values")
        for ((key, count) in expectedArgumentCount) {
            val found = placeholders(stringElement(en, key)!!.textContent).map { it.first.toInt() }.toSet()
            assertEquals("values/$key", (1..count).toSet(), found)
            assertTrue("values/$key uses only string placeholders", placeholders(stringElement(en, key)!!.textContent).all { it.second == "s" })
        }
    }

    @Test
    fun `placeholders are consistent across en es and el`() {
        val en = parse("values")
        for (folder in translatedFolders) {
            val doc = parse(folder)
            for (key in stringKeys) {
                assertEquals(
                    "$folder/$key placeholders differ from values",
                    placeholders(stringElement(en, key)!!.textContent).toSet(),
                    placeholders(stringElement(doc, key)!!.textContent).toSet()
                )
            }
        }
    }

    @Test
    fun `strings without arguments carry no placeholder in any locale`() {
        val noArgs = stringKeys - expectedArgumentCount.keys
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in noArgs) {
                assertTrue(
                    "$folder/$key must not have placeholders",
                    placeholders(stringElement(doc, key)!!.textContent).isEmpty()
                )
            }
        }
    }

    @Test
    fun `format strings format without error and without leftover placeholders for sample arguments`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for ((key, count) in expectedArgumentCount) {
                val args = Array<Any>(count) { "ARG${it + 1}" }
                val formatted = String.format(Locale.US, stringElement(doc, key)!!.textContent, *args)
                assertFalse("$folder/$key left a placeholder: $formatted", formatted.contains("%"))
                assertTrue("$folder/$key lost an argument: $formatted", (1..count).all { formatted.contains("ARG$it") })
            }
        }
    }

    // ---- content rules --------------------------------------------------------------------------

    @Test
    fun `money wrapper strings carry no currency symbol of their own in any locale`() {
        // The amount arrives already formatted with its own currency (ux-flows section 4).
        val moneyKeys = listOf(
            "recurring_link_suggestion_title_format",
            "recurring_link_suggestion_cd_format",
            "recurring_link_action_cd_format",
            "recurring_link_decline_cd_format"
        )
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in moneyKeys) {
                val value = stringElement(doc, key)!!.textContent.replace(Regex("%\\d+\\$"), "")
                assertFalse("$folder/$key must not contain a currency symbol: $value", currencySymbol.containsMatchIn(value))
            }
        }
    }

    @Test
    fun `apostrophes are escaped so aapt accepts the strings`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in stringKeys) {
                val value = stringElement(doc, key)!!.textContent
                assertFalse("$folder/$key has an unescaped apostrophe: $value", Regex("(?<!\\\\)'").containsMatchIn(value))
            }
        }
        assertTrue(stringElement(parse("values"), "recurring_link_declined")!!.textContent.contains("won\\'t"))
    }

    // ---- wiring: no hardcoded user text, every key is used ----------------------------------------

    private fun sourceText(relative: String): String {
        val file = listOf(
            "src/main/java/com/yourname/expensetracker/$relative",
            "app/src/main/java/com/yourname/expensetracker/$relative",
            "../app/src/main/java/com/yourname/expensetracker/$relative"
        ).map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("source $relative not found", file)
        return file!!.readText()
    }

    private fun section(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("marker not found: $start", from >= 0)
        val to = text.indexOf(end, from)
        assertTrue("marker not found: $end", to > from)
        return text.substring(from, to)
    }

    @Test
    fun `the new composables render user text only from string resources`() {
        val screen = sourceText("ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt")
        val home = sourceText("ui/screens/home/HomeScreen.kt")
        val bodies = listOf(
            section(screen, "internal fun PaymentLinkSuggestionBlock(", "private const val STACK_BUTTONS_FONT_SCALE"),
            section(home, "internal fun PaymentLinkHintCard(", "fun WidgetWrapper(")
        )
        val literalText = Regex("""\bText\s*\(\s*(text\s*=\s*)?"""")
        val literalDescription = Regex("""contentDescription\s*=\s*"""")
        for (body in bodies) {
            assertFalse("a Text(...) takes a string literal", literalText.containsMatchIn(body))
            assertFalse("a contentDescription takes a string literal", literalDescription.containsMatchIn(body))
        }
    }

    @Test
    fun `every new key is referenced by the screens`() {
        val sources = sourceText("ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt") +
            sourceText("ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt") +
            sourceText("ui/screens/home/HomeScreen.kt")
        for (key in stringKeys) {
            assertTrue("$key is not referenced by any screen or view model", sources.contains("R.string.$key"))
        }
        assertTrue("$pluralKey is not referenced", sources.contains("R.plurals.$pluralKey"))
    }

    @Test
    fun `the suggestion block uses bill wording and the visible labels stay short`() {
        val en = parse("values")
        assertEquals("Link", stringElement(en, "recurring_link_action")!!.textContent)
        assertEquals("Not this bill", stringElement(en, "recurring_link_decline")!!.textContent)
        assertEquals("Review", stringElement(en, "home_paid_bill_hint_action")!!.textContent)
        assertEquals(
            "Payment suggestions are unavailable right now.",
            stringElement(en, "recurring_link_suggestions_unavailable")!!.textContent
        )
    }
}
