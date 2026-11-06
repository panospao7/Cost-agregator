package com.yourname.expensetracker.ui.screens.categories

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
 * Category delete and rename (owner item 144, slice C2): the keys in strings_category_manage.xml exist in en,
 * es and el; every es and el entry is flagged DRAFT directly above it (owner native sign-off pending); the
 * plural has one and other (es also many); placeholders are identical across locales; the screen and view
 * model reference every key and render no hardcoded user text.
 */
class CategoryManageStringsTest {

    private val file = "strings_category_manage.xml"

    private val stringKeys = listOf(
        "category_manage_menu_cd",
        "category_manage_rename",
        "category_manage_delete",
        "category_manage_default_protected",
        "category_manage_not_found",
        "category_manage_failed",
        "category_rename_title",
        "category_rename_error_duplicate",
        "category_rename_success",
        "category_delete_title",
        "category_delete_count_loading",
        "category_delete_count_unavailable",
        "category_delete_picker_label",
        "category_delete_budget_note",
        "category_delete_error_replacement",
        "category_delete_success"
    )
    private val pluralKey = "category_delete_move_summary"
    private val allKeys = stringKeys + pluralKey

    /** Number of positional arguments each format string takes. */
    private val expectedArgumentCount = mapOf(
        "category_manage_menu_cd" to 1,
        "category_rename_success" to 1,
        "category_delete_title" to 1,
        "category_delete_success" to 2
    )

    private val placeholder = Regex("%(\\d+)\\$([a-z])")
    private val translatedFolders = listOf("values-es", "values-el")
    private val allFolders = listOf("values") + translatedFolders

    private fun parse(folder: String): Document {
        val found = listOf(
            "src/main/res/$folder/$file",
            "app/src/main/res/$folder/$file",
            "../app/src/main/res/$folder/$file"
        ).map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("$folder/$file not found", found)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(found!!)
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

    // ---- presence -------------------------------------------------------------------------------

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
    fun `each locale file defines exactly the same keys with no duplicates`() {
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
                    comment != null && comment.data.contains("DRAFT: owner native sign-off pending")
                )
            }
        }
    }

    @Test
    fun `English entries carry no DRAFT marker`() {
        val doc = parse("values")
        for (key in allKeys) {
            val comment = precedingComment(element(doc, key)!!)
            assertFalse("values/$key must not be flagged DRAFT", comment != null && comment.data.contains("DRAFT"))
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
    fun `every plural item takes the count as an integer then the replacement name`() {
        for (folder in allFolders) {
            for ((quantity, value) in quantities(pluralElement(parse(folder))!!)) {
                assertEquals("$folder/$quantity", setOf("1" to "d", "2" to "s"), placeholders(value).toSet())
            }
        }
    }

    @Test
    fun `English plural reads as one expense versus expenses`() {
        val en = quantities(pluralElement(parse("values"))!!)
        assertEquals("1 expense will move to Food.", String.format(Locale.US, en.getValue("one"), 1, "Food"))
        assertEquals("2 expenses will move to Food.", String.format(Locale.US, en.getValue("other"), 2, "Food"))
    }

    // ---- placeholders ---------------------------------------------------------------------------

    @Test
    fun `format strings take exactly the documented positional arguments in en`() {
        val en = parse("values")
        for ((key, count) in expectedArgumentCount) {
            val value = stringElement(en, key)!!.textContent
            assertEquals("values/$key", (1..count).toSet(), placeholders(value).map { it.first.toInt() }.toSet())
            assertTrue("values/$key uses only string placeholders", placeholders(value).all { it.second == "s" })
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
    fun `format strings format without error and without leftover placeholders`() {
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

    // ---- wiring: no hardcoded user text, every key is used -----------------------------------------

    private fun sourceText(relative: String): String {
        val found = listOf(
            "src/main/java/com/yourname/expensetracker/$relative",
            "app/src/main/java/com/yourname/expensetracker/$relative",
            "../app/src/main/java/com/yourname/expensetracker/$relative"
        ).map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("source $relative not found", found)
        return found!!.readText()
    }

    @Test
    fun `every key is referenced by the screen or view model`() {
        val sources = sourceText("ui/screens/categories/CategoryScreen.kt") +
            sourceText("ui/screens/categories/CategoryViewModel.kt")
        for (key in stringKeys) {
            assertTrue("$key is not referenced", sources.contains("R.string.$key"))
        }
        assertTrue("$pluralKey is not referenced", sources.contains("R.plurals.$pluralKey"))
    }

    @Test
    fun `the new composables render user text only from string resources`() {
        val screen = sourceText("ui/screens/categories/CategoryScreen.kt")
        val from = screen.indexOf("private fun CategoryOverflowMenu(")
        assertTrue("CategoryOverflowMenu not found", from >= 0)
        val body = screen.substring(from)
        val literalText = Regex("""\bText\s*\(\s*(text\s*=\s*)?"[^$]""")
        val literalDescription = Regex("""contentDescription\s*=\s*"""")
        assertFalse("a Text(...) takes a string literal", literalText.containsMatchIn(body))
        assertFalse("a contentDescription takes a string literal", literalDescription.containsMatchIn(body))
    }

    @Test
    fun `the view model emits only string resource messages`() {
        val vm = sourceText("ui/screens/categories/CategoryViewModel.kt")
        assertFalse("DynamicString must not carry category messages", vm.contains("DynamicString"))
        assertFalse("UiText.from(String) must not carry category messages", Regex("""UiText\.from\(\s*"""").containsMatchIn(vm))
    }

    @Test
    fun `the English labels read as rename and delete`() {
        val en = parse("values")
        assertEquals("Rename", stringElement(en, "category_manage_rename")!!.textContent)
        assertEquals("Delete", stringElement(en, "category_manage_delete")!!.textContent)
    }
}
