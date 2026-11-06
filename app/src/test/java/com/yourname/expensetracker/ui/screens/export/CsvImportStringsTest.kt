package com.yourname.expensetracker.ui.screens.export

import com.yourname.expensetracker.util.CsvImportFailureCode
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
 * J-12-2: the CSV import UI strings exist in en, es and el; every es and el entry is flagged DRAFT directly
 * above it (owner native sign-off pending); plurals have the locale-required quantities; placeholders match
 * across locales; there is one reason string per CsvImportFailureCode; the card renders only resource text.
 */
class CsvImportStringsTest {

    private val failureKeys = CsvImportFailureCode.values().map { "csv_import_failure_${it.name.lowercase()}" }
    private val plainKeys = listOf(
        "csv_import_card_title",
        "csv_import_card_description",
        "csv_import_choose_file",
        "csv_import_choose_file_cd",
        "csv_import_reading",
        "csv_import_preview_title",
        "csv_import_preview_nothing",
        "csv_import_confirm",
        "csv_import_progress_format",
        "csv_import_progress_cd_format",
        "csv_import_cancel_import",
        "csv_import_result_title",
        "csv_import_error_size_limit",
        "csv_import_error_read_failed",
        "csv_import_error_format"
    )
    private val stringKeys = plainKeys + failureKeys
    private val pluralKeys = listOf(
        "csv_import_preview_valid",
        "csv_import_preview_invalid",
        "csv_import_preview_unknown_category",
        "csv_import_preview_skipped_installments",
        "csv_import_result_imported",
        "csv_import_result_duplicates",
        "csv_import_result_failed",
        "csv_import_cancelled"
    )
    private val allKeys = stringKeys + pluralKeys

    private val expectedArguments = mapOf(
        "csv_import_progress_format" to listOf("1" to "d", "2" to "d"),
        "csv_import_progress_cd_format" to listOf("1" to "d", "2" to "d"),
        "csv_import_error_format" to listOf("1" to "s")
    )

    private val placeholder = Regex("%(\\d+)\\$([a-z])")
    private val translatedFolders = listOf("values-es", "values-el")
    private val allFolders = listOf("values") + translatedFolders

    private fun parse(folder: String): Document {
        val file = listOf(
            "src/main/res/$folder/strings_csv_import.xml",
            "app/src/main/res/$folder/strings_csv_import.xml",
            "../app/src/main/res/$folder/strings_csv_import.xml"
        ).map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("$folder/strings_csv_import.xml not found", file)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file!!)
    }

    private fun elements(doc: Document, tag: String): List<Element> {
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun stringElement(doc: Document, name: String): Element? =
        elements(doc, "string").firstOrNull { it.getAttribute("name") == name }

    private fun pluralElement(doc: Document, name: String): Element? =
        elements(doc, "plurals").firstOrNull { it.getAttribute("name") == name }

    private fun element(doc: Document, key: String): Element? =
        if (key in pluralKeys) pluralElement(doc, key) else stringElement(doc, key)

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

    private fun sourceText(relative: String): String {
        val file = listOf(
            "src/main/java/com/yourname/expensetracker/$relative",
            "app/src/main/java/com/yourname/expensetracker/$relative",
            "../app/src/main/java/com/yourname/expensetracker/$relative"
        ).map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("source $relative not found", file)
        return file!!.readText()
    }

    // ---- presence ------------------------------------------------------------------------------

    @Test
    fun `there is one reason string per failure code`() {
        assertEquals(CsvImportFailureCode.values().size, failureKeys.size)
        assertEquals(failureKeys.size, failureKeys.toSet().size)
        assertEquals(32, allKeys.size)
    }

    @Test
    fun `all keys are present in values values-es and values-el`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in allKeys) {
                assertNotNull("$folder is missing $key", element(doc, key))
            }
        }
    }

    @Test
    fun `each locale file defines exactly the keys and no duplicates`() {
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
            for (key in pluralKeys) {
                for ((quantity, value) in quantities(pluralElement(doc, key)!!)) {
                    assertTrue("$folder/$key/$quantity is blank", value.isNotBlank())
                }
            }
        }
    }

    // ---- DRAFT markers ------------------------------------------------------------------------

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

    @Test
    fun `the default locale carries no DRAFT marker`() {
        for (key in allKeys) {
            val comment = precedingComment(element(parse("values"), key)!!)
            assertFalse("values/$key must not be marked DRAFT", comment != null && comment.data.contains("DRAFT"))
        }
    }

    // ---- plurals --------------------------------------------------------------------------------

    @Test
    fun `plurals have one and other everywhere and es also has many`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in pluralKeys) {
                val quantities = quantities(pluralElement(doc, key)!!)
                assertTrue("$folder/$key is missing one", "one" in quantities)
                assertTrue("$folder/$key is missing other", "other" in quantities)
                if (folder == "values-es") assertTrue("$folder/$key is missing many", "many" in quantities)
            }
        }
    }

    @Test
    fun `plural items carry the count as an integer and the invalid line also a reason`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in pluralKeys) {
                val expected = if (key == "csv_import_preview_invalid") listOf("1" to "d", "2" to "s") else listOf("1" to "d")
                for ((quantity, value) in quantities(pluralElement(doc, key)!!)) {
                    assertEquals("$folder/$key/$quantity", expected, placeholders(value))
                }
            }
        }
    }

    @Test
    fun `English plurals read as expected`() {
        val en = parse("values")
        val valid = quantities(pluralElement(en, "csv_import_preview_valid")!!)
        assertEquals("1 row will be imported.", String.format(Locale.US, valid.getValue("one"), 1))
        assertEquals("3 rows will be imported.", String.format(Locale.US, valid.getValue("other"), 3))
        val unknown = quantities(pluralElement(en, "csv_import_preview_unknown_category")!!)
        assertTrue(unknown.getValue("one").contains("Uncategorized"))
        assertTrue(unknown.getValue("other").contains("Uncategorized"))
        assertEquals("Import CSV", stringElement(en, "csv_import_card_title")!!.textContent)
    }

    // ---- placeholders ---------------------------------------------------------------------------

    @Test
    fun `format strings take the documented arguments and others take none`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in stringKeys) {
                val found = placeholders(stringElement(doc, key)!!.textContent)
                assertEquals("$folder/$key", expectedArguments[key].orEmpty().toSet(), found.toSet())
            }
        }
    }

    @Test
    fun `format strings format without leftover placeholders`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for ((key, args) in expectedArguments) {
                val sample = args.map { if (it.second == "d") (7 as Any) else ("ARG" as Any) }.toTypedArray()
                val formatted = String.format(Locale.US, stringElement(doc, key)!!.textContent, *sample)
                assertFalse("$folder/$key left a placeholder: $formatted", formatted.contains("%"))
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
    }

    // ---- wiring ---------------------------------------------------------------------------------

    @Test
    fun `the card renders text only from string resources`() {
        val card = sourceText("ui/screens/export/CsvImportCard.kt")
        assertFalse("a Text(...) takes a string literal", Regex("""\bText\s*\(\s*(text\s*=\s*)?"""").containsMatchIn(card))
        assertFalse("a contentDescription takes a string literal", Regex("""contentDescription\s*=\s*"""").containsMatchIn(card))
    }

    @Test
    fun `every key is referenced by the card and every failure code is mapped`() {
        val card = sourceText("ui/screens/export/CsvImportCard.kt")
        for (key in stringKeys) {
            assertTrue("$key is not referenced by the card", card.contains("R.string.$key"))
        }
        for (key in pluralKeys) {
            assertTrue("$key is not referenced by the card", card.contains("R.plurals.$key"))
        }
        assertTrue(card.contains("R.string.ui_write_blocked_maintenance"))
        for (code in CsvImportFailureCode.values()) {
            assertTrue("$code has no mapping", card.contains("CsvImportFailureCode.${code.name} ->"))
        }
    }

    @Test
    fun `the Export screen hosts the card and the view model does not touch Room`() {
        assertTrue(sourceText("ui/screens/export/ExportOptionsScreen.kt").contains("CsvImportCard()"))
        val viewModel = sourceText("ui/screens/export/CsvImportViewModel.kt")
        assertFalse(viewModel.contains("Dao"))
        assertFalse(viewModel.contains("Timber"))
    }
}
