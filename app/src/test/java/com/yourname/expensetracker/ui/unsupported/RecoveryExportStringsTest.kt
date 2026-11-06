package com.yourname.expensetracker.ui.unsupported

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Comment
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * J-20-1: the 3 new keys of the raw export from the critical-recovery screen exist in values, values-el and
 * values-es exactly once; every es and el entry is flagged DRAFT in the comment directly above it (owner native
 * sign-off pending); no value is blank, none takes a format placeholder, apostrophes are escaped; the keys are used
 * by the two screens. Follows the PaymentLinkStringsTest pattern.
 */
class RecoveryExportStringsTest {

    private val keys = listOf(
        "critical_recovery_export_raw_copy",
        "unsupported_db_reason_recovery",
        "unsupported_db_recovery_export_note"
    )
    private val translatedFolders = listOf("values-es", "values-el")
    private val allFolders = listOf("values") + translatedFolders

    private fun parse(folder: String): Document {
        val file = listOf(
            "src/main/res/$folder/strings.xml",
            "app/src/main/res/$folder/strings.xml",
            "../app/src/main/res/$folder/strings.xml"
        ).map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("$folder/strings.xml not found", file)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file!!)
    }

    private fun strings(doc: Document, name: String): List<Element> {
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.filter { it.getAttribute("name") == name }
    }

    private fun precedingComment(element: Element): Comment? {
        var previous: Node? = element.previousSibling
        while (previous != null && previous.nodeType == Node.TEXT_NODE && previous.textContent.isBlank()) {
            previous = previous.previousSibling
        }
        return previous as? Comment
    }

    @Test
    fun `the 3 keys are defined exactly once in values values-es and values-el`() {
        assertEquals(3, keys.size)
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys) {
                assertEquals("$folder must define $key exactly once", 1, strings(doc, key).size)
            }
        }
    }

    @Test
    fun `no value is blank in any locale`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys) {
                assertTrue("$folder/$key is blank", strings(doc, key).single().textContent.isNotBlank())
            }
        }
    }

    @Test
    fun `es and el entries are each directly preceded by a DRAFT comment`() {
        for (folder in translatedFolders) {
            val doc = parse(folder)
            for (key in keys) {
                val comment = precedingComment(strings(doc, key).single())
                assertTrue(
                    "$folder/$key must be directly preceded by a DRAFT comment",
                    comment != null && comment.data.contains("DRAFT")
                )
            }
        }
    }

    @Test
    fun `the DRAFT flag sits on the translated entries and the english source has no pending flag`() {
        val en = parse("values")
        for (key in keys) {
            val comment = precedingComment(strings(en, key).single())
            assertFalse("values/$key is the source text and needs no DRAFT flag", comment != null && comment.data.contains("DRAFT"))
        }
    }

    @Test
    fun `the new strings take no format placeholders in any locale`() {
        val placeholder = Regex("%(\\d+\\$)?[a-zA-Z]")
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys) {
                val value = strings(doc, key).single().textContent
                assertFalse("$folder/$key must not have placeholders: $value", placeholder.containsMatchIn(value))
            }
        }
    }

    @Test
    fun `apostrophes are escaped so aapt accepts the strings`() {
        for (folder in allFolders) {
            val doc = parse(folder)
            for (key in keys) {
                val value = strings(doc, key).single().textContent
                assertFalse("$folder/$key has an unescaped apostrophe: $value", Regex("(?<!\\\\)'").containsMatchIn(value))
            }
        }
    }

    @Test
    fun `english copy states the export is a raw and possibly incomplete copy`() {
        val en = parse("values")
        assertEquals("Export a raw copy", strings(en, "critical_recovery_export_raw_copy").single().textContent)
        assertTrue(strings(en, "unsupported_db_recovery_export_note").single().textContent.contains("raw copy"))
        assertTrue(strings(en, "unsupported_db_recovery_export_note").single().textContent.contains("incomplete"))
    }

    @Test
    fun `translations differ from the english source`() {
        val en = parse("values")
        for (folder in translatedFolders) {
            val doc = parse(folder)
            for (key in keys) {
                assertFalse(
                    "$folder/$key is an untranslated copy of the english source",
                    strings(doc, key).single().textContent == strings(en, key).single().textContent
                )
            }
        }
    }

    @Test
    fun `every new key is referenced by the screens that show it`() {
        fun source(relative: String): String {
            val file = listOf(
                "src/main/java/com/yourname/expensetracker/$relative",
                "app/src/main/java/com/yourname/expensetracker/$relative",
                "../app/src/main/java/com/yourname/expensetracker/$relative"
            ).map { File(it) }.firstOrNull { it.isFile }
            assertNotNull("source $relative not found", file)
            return file!!.readText()
        }

        val main = source("ui/MainActivity.kt")
        val activity = source("ui/unsupported/UnsupportedDatabaseActivity.kt")
        assertTrue(main.contains("R.string.critical_recovery_export_raw_copy"))
        assertTrue(activity.contains("R.string.unsupported_db_reason_recovery"))
        assertTrue(activity.contains("R.string.unsupported_db_recovery_export_note"))
    }
}
