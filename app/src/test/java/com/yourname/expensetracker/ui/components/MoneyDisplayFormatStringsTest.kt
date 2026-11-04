package com.yourname.expensetracker.ui.components

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
import javax.xml.parsers.DocumentBuilderFactory

/**
 * fix-1.0.4 money display (N-08-1 and related): format strings that wrap an already formatted
 * money value must not carry their own currency symbol (the value comes from the currency
 * formatter and the user's home currency may not be EUR). New el/es keys carry the DRAFT marker.
 */
class MoneyDisplayFormatStringsTest {

    // Positional placeholders such as %1$s contain a dollar sign, so they are removed before the check.
    private val formatPlaceholder = Regex("%\\d+\\$")
    private val currencySymbol = Regex("\\p{Sc}")

    private val moneyWrapperKeys = listOf(
        "recurring_planned_item_cd_format",
        "retro_score_label_format",
        "budget_amount_label",
        "forecast_range_format",
        "a11y_category_spending_format",
        "a11y_expense_item_format",
        "dialog_plan_expense_amount_label",
        "calendar_label_starting_balance",
        "avg_amount_format",
        "label_add_amount_format"
    )

    private fun parse(folder: String): Document {
        val file = listOf(
            "src/main/res/$folder/strings.xml",
            "app/src/main/res/$folder/strings.xml",
            "../app/src/main/res/$folder/strings.xml"
        ).map { File(it) }.firstOrNull { it.isFile }
        assertNotNull("$folder/strings.xml not found", file)
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file!!)
    }

    private fun stringElement(doc: Document, name: String): Element? {
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }.firstOrNull { it.getAttribute("name") == name }
    }

    @Test
    fun `N-08-1 recurring planned item cd format retro score label and budget amount label have no currency symbol`() {
        val doc = parse("values")
        for (key in moneyWrapperKeys) {
            val value = stringElement(doc, key)?.textContent
            assertNotNull("values/strings.xml is missing $key", value)
            assertFalse("$key must not contain a currency symbol: $value", currencySymbol.containsMatchIn(value!!.replace(formatPlaceholder, "")))
            assertFalse("$key must not use a float placeholder: $value", value.contains("%.2f") || value.contains("%.1f"))
        }
    }

    @Test
    fun `N-08-1 recurring planned item cd format keeps its three string placeholders`() {
        val value = stringElement(parse("values"), "recurring_planned_item_cd_format")!!.textContent
        val placeholders = Regex("%(\\d+)\\$([a-z])").findAll(value).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertEquals(listOf("1" to "s", "2" to "s", "3" to "s"), placeholders.sortedBy { it.first })
    }

    @Test
    fun `N-08-1 retro score label format takes the already formatted amount as a string placeholder`() {
        val value = stringElement(parse("values"), "retro_score_label_format")!!.textContent
        assertTrue("must contain %1\$s: $value", value.contains("%1\$s"))
        assertEquals("SCORE: 1,234.50", String.format(java.util.Locale.US, value, "1,234.50"))
    }

    @Test
    fun `N-08-1 translated copies of the money wrapper keys when present also carry no currency symbol`() {
        for (folder in listOf("values-el", "values-es")) {
            val doc = parse(folder)
            for (key in moneyWrapperKeys) {
                val value = stringElement(doc, key)?.textContent ?: continue
                assertFalse("$folder/$key must not contain a currency symbol: $value", currencySymbol.containsMatchIn(value))
            }
        }
    }

    @Test
    fun `B1-11 new el and es keys carry the DRAFT comment marker directly above the string`() {
        // Keys added by the fix-1.0.4 money-display branch. Add a key here when a slice adds another.
        val newTranslatedKeys = listOf("stress_forecast_risk_percent_format") + moneyWrapperKeys
        for (folder in listOf("values-el", "values-es")) {
            val doc = parse(folder)
            for (key in newTranslatedKeys) {
                val element = stringElement(doc, key) ?: continue
                var previous: Node? = element.previousSibling
                while (previous != null && previous.nodeType == Node.TEXT_NODE && previous.textContent.isBlank()) {
                    previous = previous.previousSibling
                }
                assertTrue(
                    "$folder/$key must be directly preceded by a DRAFT comment",
                    previous is Comment && previous.data.contains("DRAFT")
                )
            }
        }
        // The one key known to exist in both locales must really be present (guards the loop above).
        for (folder in listOf("values-el", "values-es")) {
            assertNotNull("$folder missing stress_forecast_risk_percent_format", stringElement(parse(folder), "stress_forecast_risk_percent_format"))
        }
    }
}
