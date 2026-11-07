package com.yourname.expensetracker.ui.screens.captureskip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * P-01-3: the 18 "Apps to skip" keys (14 original + 4 capture_skip_confirm_* warning keys) exist in values, values-es and values-el; every es and el entry
 * is flagged DRAFT directly above it (owner native sign-off pending); the format placeholders are
 * identical across the locales; every key is referenced by the screens.
 */
class CaptureSkipStringsTest {

    private val keys = listOf(
        "capture_skip_entry_title",
        "capture_skip_entry_summary",
        "capture_skip_title",
        "capture_skip_intro",
        "capture_skip_section_skipped",
        "capture_skip_section_candidates",
        "capture_skip_empty_skipped",
        "capture_skip_empty_candidates",
        "capture_skip_action_skip",
        "capture_skip_action_resume",
        "capture_skip_cd_skip_format",
        "capture_skip_cd_resume_format",
        "capture_skip_error_generic",
        "capture_skip_load_failed",
        "capture_skip_confirm_title",
        "capture_skip_confirm_message_format",
        "capture_skip_confirm_action",
        "capture_skip_confirm_cancel"
    )

    /** The four confirm-dialog keys added with the bank-parser skip warning. */
    private val confirmKeys = listOf(
        "capture_skip_confirm_title",
        "capture_skip_confirm_message_format",
        "capture_skip_confirm_action",
        "capture_skip_confirm_cancel"
    )

    /** The content-description formats and the confirm message take the app label as their only argument. */
    private val formatKeys = listOf(
        "capture_skip_cd_skip_format",
        "capture_skip_cd_resume_format",
        "capture_skip_confirm_message_format"
    )

    private val placeholder = Regex("%(\\d+)\\$([a-z])")
    private val translatedFolders = listOf("values-es", "values-el")
    private val allFolders = listOf("values") + translatedFolders

    private fun parse(folder: String): Document {
        val file = listOf(
            "src/main/res/$folder/strings_capture_skip.xml",
            "app/src/main/res/$folder/strings_capture_skip.xml",
            "../app/src/main/res/$folder/strings_capture_skip.xml"
        ).map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("$folder/strings_capture_skip.xml not found", file)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file!!)
    }

    private fun strings(doc: Document): List<Element> {
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun value(doc: Document, key: String): String? =
        strings(doc).firstOrNull { it.getAttribute("name") == key }?.textContent

    private fun placeholders(text: String): List<Pair<String, String>> =
        placeholder.findAll(text).map { it.groupValues[1] to it.groupValues[2] }.toList()

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
    fun `all 18 keys are present in values values-es and values-el`() {
        assertEquals(18, keys.size)
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys) {
                assertNotNull("$folder is missing $key", value(doc, key))
            }
        }
    }

    @Test
    fun `each locale file defines exactly the 18 keys and no duplicates`() {
        for (folder in allFolders) {
            val names = strings(parse(folder)).map { it.getAttribute("name") }
            assertEquals("$folder defines duplicate or extra keys: $names", keys.sorted(), names.sorted())
        }
    }

    @Test
    fun `no value is blank in any locale`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys) {
                assertTrue("$folder/$key is blank", value(doc, key)!!.isNotBlank())
            }
        }
    }

    // ---- DRAFT flag on es and el ----------------------------------------------------------------

    @Test
    fun `es and el entries are each directly preceded by a DRAFT comment`() {
        for (folder in translatedFolders) {
            val doc = parse(folder)
            for (key in keys) {
                val element = strings(doc).first { it.getAttribute("name") == key }
                val comment = precedingComment(element)
                assertTrue(
                    "$folder/$key must be directly preceded by a DRAFT comment",
                    comment != null && comment.data.contains("DRAFT")
                )
            }
        }
    }

    @Test
    fun `control - the English entries are not flagged DRAFT`() {
        val doc = parse("values")
        for (key in keys) {
            val element = strings(doc).first { it.getAttribute("name") == key }
            val comment = precedingComment(element)
            assertFalse("values/$key must not carry a DRAFT flag", comment != null && comment.data.contains("DRAFT"))
        }
    }

    @Test
    fun `es and el values are translations and not copies of the English text`() {
        val en = parse("values")
        for (folder in translatedFolders) {
            val doc = parse(folder)
            for (key in keys) {
                assertNotEquals("$folder/$key is identical to the English text", value(en, key), value(doc, key))
            }
        }
    }

    @Test
    fun `the four confirm keys exist in all locales and the translated ones carry a DRAFT comment`() {
        assertEquals(4, confirmKeys.size)
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in confirmKeys) {
                assertNotNull("$folder is missing $key", value(doc, key))
            }
        }
        for (folder in translatedFolders) {
            val doc = parse(folder)
            for (key in confirmKeys) {
                val element = strings(doc).first { it.getAttribute("name") == key }
                val comment = precedingComment(element)
                assertTrue(
                    "$folder/$key must be directly preceded by a DRAFT comment",
                    comment != null && comment.data.contains("DRAFT")
                )
            }
        }
    }

    // ---- placeholders ---------------------------------------------------------------------------

    @Test
    fun `the label formats take exactly the app label as one string argument in every locale`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in formatKeys) {
                assertEquals("$folder/$key", listOf("1" to "s"), placeholders(value(doc, key)!!))
            }
        }
    }

    @Test
    fun `strings without arguments carry no placeholder in any locale`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys - formatKeys.toSet()) {
                assertTrue("$folder/$key must not have placeholders", placeholders(value(doc, key)!!).isEmpty())
            }
        }
    }

    @Test
    fun `format strings format without error and keep the argument`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in formatKeys) {
                val formatted = String.format(Locale.US, value(doc, key)!!, "ARG1")
                assertTrue("$folder/$key lost the argument: $formatted", formatted.contains("ARG1"))
                assertFalse("$folder/$key left a placeholder: $formatted", formatted.contains("%"))
            }
        }
    }

    // ---- content rules --------------------------------------------------------------------------

    @Test
    fun `apostrophes are escaped so aapt accepts the strings`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys) {
                val text = value(doc, key)!!
                assertFalse("$folder/$key has an unescaped apostrophe: $text", Regex("(?<!\\\\)'").containsMatchIn(text))
            }
        }
    }

    @Test
    fun `no string embeds a package name`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys) {
                assertFalse(
                    "$folder/$key must not hard-code a package name",
                    Regex("\\bcom\\.[a-z]+\\.").containsMatchIn(value(doc, key)!!)
                )
            }
        }
    }

    // ---- wiring ---------------------------------------------------------------------------------

    @Test
    fun `every key is referenced by the screens or the view model`() {
        val sources = sourceText("ui/screens/captureskip/AppsToSkipScreen.kt") +
            sourceText("ui/screens/captureskip/AppsToSkipViewModel.kt") +
            sourceText("ui/screens/privacysettings/PrivacySettingsScreen.kt")
        for (key in keys) {
            assertTrue("$key is not referenced by any screen or view model", sources.contains("R.string.$key"))
        }
    }
}
