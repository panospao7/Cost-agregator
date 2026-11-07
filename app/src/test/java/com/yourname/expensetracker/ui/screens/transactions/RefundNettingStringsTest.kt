package com.yourname.expensetracker.ui.screens.transactions

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
 * Refund netting S4 (ux-flows sections 7 and 9): the 44 new keys exist in values, values-es and values-el;
 * every es and el entry is flagged DRAFT directly above it (owner native sign-off pending); format
 * placeholders are identical across the locales; the reworded likelihood notice (strings.xml) exists in all
 * three locales with a DRAFT flag on es and el.
 */
class RefundNettingStringsTest {

    private val keys = listOf(
        "refund_notice_action",
        "refund_menu_apply",
        "refund_menu_details",
        "refund_chip_purchase_format",
        "refund_chip_net_format",
        "refund_chip_deposit_format",
        "refund_chip_deposit_left_format",
        "refund_chip_purchase_cd_format",
        "refund_chip_deposit_cd_format",
        "refund_picker_title",
        "refund_picker_subtitle_format",
        "refund_picker_loading_cd",
        "refund_picker_none_format",
        "refund_picker_unavailable",
        "refund_candidate_remaining_format",
        "refund_candidate_select_cd_format",
        "refund_confirm_title",
        "refund_label_purchase",
        "refund_label_refunded",
        "refund_label_net",
        "refund_confirm_effect_format",
        "refund_confirm_partial_format",
        "refund_confirm_action",
        "refund_done_format",
        "refund_done_plain_format",
        "refund_already_applied",
        "refund_refused_nothing_to_apply",
        "refund_refused_linked_elsewhere",
        "refund_refused_before_purchase",
        "refund_refused_currency",
        "refund_refused_installment",
        "refund_refused_group",
        "refund_refused_stale",
        "refund_unlinked",
        "refund_details_purchase_header",
        "refund_details_deposit_header",
        "refund_details_unavailable",
        "refund_details_remove",
        "refund_details_remove_cd_format",
        "refund_unlink_title",
        "refund_unlink_body_format",
        "refund_unlink_body_remaining_format",
        "transactions_refund_linked_locked",
        "transactions_delete_refund_linked_note"
    )

    private val noticeKey = "transactions_refund_likelihood_notice"

    /** Highest positional argument each format string takes (ux-flows section 7). */
    private val expectedArgumentCount = mapOf(
        "refund_chip_purchase_format" to 1,
        "refund_chip_net_format" to 1,
        "refund_chip_deposit_format" to 1,
        "refund_chip_deposit_left_format" to 1,
        "refund_chip_purchase_cd_format" to 3,
        "refund_chip_deposit_cd_format" to 2,
        "refund_picker_subtitle_format" to 2,
        "refund_picker_none_format" to 2,
        "refund_candidate_remaining_format" to 1,
        "refund_candidate_select_cd_format" to 1,
        "refund_confirm_effect_format" to 3,
        "refund_confirm_partial_format" to 2,
        "refund_done_format" to 2,
        "refund_done_plain_format" to 1,
        "refund_details_remove_cd_format" to 2,
        "refund_unlink_body_format" to 3,
        "refund_unlink_body_remaining_format" to 3
    )

    private val placeholder = Regex("%(\\d+)\\$([a-z])")
    private val translatedFolders = listOf("values-es", "values-el")
    private val allFolders = listOf("values") + translatedFolders

    private fun parseFile(folder: String, fileName: String): Document {
        val file = listOf(
            "src/main/res/$folder/$fileName",
            "app/src/main/res/$folder/$fileName",
            "../app/src/main/res/$folder/$fileName"
        ).map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("$folder/$fileName not found", file)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file!!)
    }

    private fun parse(folder: String): Document = parseFile(folder, "strings_refund_netting.xml")

    private fun elements(doc: Document, tag: String): List<Element> {
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun stringElement(doc: Document, name: String): Element? =
        elements(doc, "string").firstOrNull { it.getAttribute("name") == name }

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
    fun `there are 44 distinct new keys`() {
        assertEquals(44, keys.size)
        assertEquals(44, keys.toSet().size)
    }

    @Test
    fun `all 44 keys are present in values values-es and values-el`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys) {
                assertNotNull("$folder is missing $key", stringElement(doc, key))
            }
        }
    }

    @Test
    fun `each locale file defines exactly the 44 keys and no duplicates`() {
        for (folder in allFolders) {
            val names = elements(parse(folder), "string").map { it.getAttribute("name") }
            assertEquals("$folder defines duplicate or extra keys", keys.sorted(), names.sorted())
        }
    }

    @Test
    fun `no value is blank in any locale`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys) {
                assertTrue("$folder/$key is blank", stringElement(doc, key)!!.textContent.isNotBlank())
            }
        }
    }

    // ---- DRAFT flag on es and el ----------------------------------------------------------------

    @Test
    fun `es and el entries are each directly preceded by a DRAFT comment`() {
        for (folder in translatedFolders) {
            val doc = parse(folder)
            for (key in keys) {
                val comment = precedingComment(stringElement(doc, key)!!)
                assertTrue(
                    "$folder/$key must be directly preceded by a DRAFT comment",
                    comment != null && comment.data.contains("DRAFT")
                )
            }
        }
    }

    // ---- reworded likelihood notice -------------------------------------------------------------

    @Test
    fun `the reworded likelihood notice exists in en es and el`() {
        for (folder in allFolders) {
            val element = stringElement(parseFile(folder, "strings.xml"), noticeKey)
            assertNotNull("$folder/strings.xml is missing $noticeKey", element)
            assertTrue("$folder/$noticeKey is blank", element!!.textContent.isNotBlank())
        }
    }

    @Test
    fun `the likelihood notice es and el entries carry a DRAFT comment and en does not need one`() {
        for (folder in translatedFolders) {
            val element = stringElement(parseFile(folder, "strings.xml"), noticeKey)!!
            val comment = precedingComment(element)
            assertTrue(
                "$folder/$noticeKey must be directly preceded by a DRAFT comment",
                comment != null && comment.data.contains("DRAFT")
            )
        }
    }

    @Test
    fun `the English likelihood notice explains the income effect of applying the refund`() {
        val text = stringElement(parseFile("values", "strings.xml"), noticeKey)!!.textContent
        assertTrue(text.contains("refund"))
        assertTrue("notice must say what applying does", text.contains("income"))
        assertTrue("notice has no placeholder", placeholders(text).isEmpty())
    }

    @Test
    fun `the likelihood notice has no placeholders in any locale`() {
        for (folder in allFolders) {
            val text = stringElement(parseFile(folder, "strings.xml"), noticeKey)!!.textContent
            assertTrue("$folder/$noticeKey must not have placeholders", placeholders(text).isEmpty())
        }
    }

    // ---- placeholders ---------------------------------------------------------------------------

    @Test
    fun `format strings take exactly the documented positional arguments in en`() {
        val en = parse("values")
        for ((key, count) in expectedArgumentCount) {
            val found = placeholders(stringElement(en, key)!!.textContent).map { it.first.toInt() }.toSet()
            assertEquals("values/$key", (1..count).toSet(), found)
        }
    }

    @Test
    fun `placeholders are consistent across en es and el`() {
        val en = parse("values")
        for (folder in translatedFolders) {
            val doc = parse(folder)
            for (key in keys) {
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
        val noArgs = keys - expectedArgumentCount.keys
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
    fun `format strings format without error and without lost arguments for sample arguments`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for ((key, count) in expectedArgumentCount) {
                val raw = stringElement(doc, key)!!.textContent
                val args = Array<Any>(count) { index ->
                    // The picker "none" text takes a day count as its second argument (%2$d).
                    if (key == "refund_picker_none_format" && index == 1) 14 else "ARG${index + 1}"
                }
                val formatted = String.format(Locale.US, raw, *args)
                assertFalse("$folder/$key left a placeholder: $formatted", formatted.contains("%"))
                assertTrue(
                    "$folder/$key lost an argument: $formatted",
                    (1..count).all { i -> formatted.contains(if (key == "refund_picker_none_format" && i == 2) "14" else "ARG$i") }
                )
            }
        }
    }

    // ---- content rules --------------------------------------------------------------------------

    @Test
    fun `money wrapper strings carry no currency symbol of their own in any locale`() {
        // The amount arrives already formatted with its own currency (ux-flows section 4).
        val symbol = Regex("\\p{Sc}")
        val moneyKeys = expectedArgumentCount.keys - setOf("refund_picker_none_format", "refund_candidate_select_cd_format")
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in moneyKeys) {
                val value = stringElement(doc, key)!!.textContent.replace(Regex("%\\d+\\$"), "")
                assertFalse("$folder/$key must not contain a currency symbol: $value", symbol.containsMatchIn(value))
            }
        }
    }

    @Test
    fun `apostrophes are escaped so aapt accepts the strings`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys) {
                val value = stringElement(doc, key)!!.textContent
                assertFalse("$folder/$key has an unescaped apostrophe: $value", Regex("(?<!\\\\)'").containsMatchIn(value))
            }
        }
    }

    @Test
    fun `English copy for the key user-visible actions`() {
        val en = parse("values")
        assertEquals("Apply to purchase", stringElement(en, "refund_notice_action")!!.textContent)
        assertEquals("This is a refund of...", stringElement(en, "refund_menu_apply")!!.textContent)
        assertEquals("Remove link", stringElement(en, "refund_details_remove")!!.textContent)
    }
}
