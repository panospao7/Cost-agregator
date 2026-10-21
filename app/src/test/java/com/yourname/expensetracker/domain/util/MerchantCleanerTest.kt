package com.yourname.expensetracker.domain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Table-driven tests for [MerchantCleaner] (formerly MerchantCleanerStressTest).
 * Every distinct stress case is preserved as a named row; the failure message
 * names the row.
 */
class MerchantCleanerTest {

    private lateinit var cleaner: MerchantCleaner

    @Before
    fun setup() {
        cleaner = MerchantCleaner()
    }

    @Test
    fun `exact cleaned output rows`() {
        // label to (input to expected output)
        val rows: List<Pair<String, Pair<String?, String>>> = listOf(
            "null input returns Unknown" to (null to "Unknown"),
            "empty string returns Unknown" to ("" to "Unknown"),
            "blank string returns Unknown" to ("   " to "Unknown"),
            "only whitespace returns Unknown" to ("\t\n" to "Unknown"),
            "single character is returned" to ("A" to "A"),
            "Greek characters preserved" to ("Σκλαβενίτης" to "Σκλαβενίτης"),
            "date with dots is removed" to ("Buy 2024.03.15" to "Buy"),
            "trailing punctuation is removed" to ("Shop!." to "Shop"),
            "only stop word confirmed becomes Unknown" to ("confirmed" to "Unknown"),
            "only stop word at becomes Unknown" to ("at" to "Unknown"),
            "emoji is stripped" to ("Store👋" to "Store"),
            "multiple spaces collapse to a single space" to ("Store    Name" to "Store Name"),
            "non-breaking space becomes a regular space" to ("Store\u00A0Name" to "Store Name"),
            "consecutive cleaning operations leave only the merchant" to
                ("  Store  12/01  14:30  •••• 1234  confirmed  " to "Store"),
            "apostrophe in merchant name preserved" to ("McDonald's" to "McDonald's")
        )
        assertEquals(15, rows.size)
        rows.forEach { (label, inputAndExpected) ->
            val (input, expected) = inputAndExpected
            assertEquals("row '$label'", expected, cleaner.clean(input))
        }
    }

    private data class RemovedFragmentRow(
        val label: String,
        val input: String,
        val forbiddenFragment: String,
        val compareLowercase: Boolean = false
    )

    @Test
    fun `cleaned output no longer contains removed fragment rows`() {
        val realWorld = "Πληρωμή στο SUPER MARKET 12/01/2024 14:30 •••• 4532 confirmed"
        val rows = listOf(
            // time removal
            RemovedFragmentRow("time at end", "Starbucks 14:30", "14:30"),
            RemovedFragmentRow("time with seconds", "Store 10:15:30", "10:15:30"),
            RemovedFragmentRow("time with AM PM", "Coffee 9:00 AM", "9:00"),
            // date removal
            RemovedFragmentRow("date with slashes", "Purchase 12/25", "12/25"),
            RemovedFragmentRow("date with dashes", "Shop 01-15", "01-15"),
            RemovedFragmentRow("full date", "Test 15/03/2024", "15/03/2024"),
            // card info removal
            RemovedFragmentRow("card number", "Purchase •••• 1234", "1234"),
            RemovedFragmentRow("Visa card", "Store Visa 4532", "4532"),
            RemovedFragmentRow("Mastercard", "Shop Mastercard 5555", "5555"),
            RemovedFragmentRow("Greek card text", "Αγορά με κάρτα 1234", "1234"),
            RemovedFragmentRow("multiple card patterns first", "Store •••• 1234 ···· 5678", "1234"),
            RemovedFragmentRow("multiple card patterns second", "Store •••• 1234 ···· 5678", "5678"),
            // stop words
            RemovedFragmentRow("confirmed from end", "Starbucks confirmed", "confirmed", compareLowercase = true),
            RemovedFragmentRow("successful from end", "Payment successful", "successful", compareLowercase = true),
            RemovedFragmentRow("Greek stop word", "Κατάστημα ολοκληρώθηκε", "ολοκληρώθηκε"),
            RemovedFragmentRow("case insensitive stop word", "Store CONFIRMED", "confirmed", compareLowercase = true),
            // whitespace normalization
            RemovedFragmentRow("multiple spaces collapsed", "Store    Name", "  "),
            RemovedFragmentRow("non-breaking space removed", "Store\u00A0Name", "\u00A0"),
            // real world notification
            RemovedFragmentRow("real world date", realWorld, "12/01/2024"),
            RemovedFragmentRow("real world time", realWorld, "14:30"),
            RemovedFragmentRow("real world card", realWorld, "4532"),
            RemovedFragmentRow("real world stop word", realWorld, "confirmed", compareLowercase = true)
        )
        assertEquals(22, rows.size)
        rows.forEach { row ->
            val result = cleaner.clean(row.input)
            val haystack = if (row.compareLowercase) result.lowercase() else result
            assertFalse(
                "row '${row.label}': cleaned '$result' should not contain '${row.forbiddenFragment}'",
                haystack.contains(row.forbiddenFragment)
            )
        }
    }

    private data class RetainedRow(
        val label: String,
        val input: String,
        val requiredLowercaseFragment: String? = null
    )

    @Test
    fun `cleaned output is non-empty and keeps required fragment rows`() {
        val rows = listOf(
            RetainedRow("leading stop word at keeps merchant", "at Starbucks", "starbucks"),
            RetainedRow("numbers in merchant name preserved", "Store123", "123"),
            RetainedRow("mixed Greek English", "LIDL Ελλάδα"),
            RetainedRow("multiple consecutive cleaning operations",
                "  Store  12/01  14:30  •••• 1234  confirmed  "),
            RetainedRow("special characters preserved", "McDonald's")
        )
        assertEquals(5, rows.size)
        rows.forEach { row ->
            val result = cleaner.clean(row.input)
            assertTrue("row '${row.label}': cleaned output should not be empty", result.isNotEmpty())
            val fragment: String? = row.requiredLowercaseFragment
            if (fragment != null) {
                assertTrue(
                    "row '${row.label}': cleaned '$result' should contain '$fragment'",
                    result.lowercase().contains(fragment)
                )
            }
        }
    }

    @Test
    fun `very long merchant name is truncated to max length`() {
        val result = cleaner.clean("A".repeat(500))
        assertTrue(
            "length ${result.length} should be <= ${AppConstants.Parser.MAX_MERCHANT_LENGTH}",
            result.length <= AppConstants.Parser.MAX_MERCHANT_LENGTH
        )
    }
}
