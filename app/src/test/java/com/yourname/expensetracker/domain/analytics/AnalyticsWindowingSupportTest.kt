package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransactionType
import com.yourname.expensetracker.domain.model.ExpenseSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyticsWindowingSupportTest {

    @Test
    fun `canonicalMerchantKey uses stable fallback for null and blank merchant names`() {
        val nullLike = snapshot(merchant = "", merchantKey = null)
        val blankLike = snapshot(merchant = "   ", merchantKey = null)

        val nullKey = nullLike.canonicalMerchantKey()
        val blankKey = blankLike.canonicalMerchantKey()

        assertEquals("__unknown_merchant__", nullKey)
        assertEquals("__unknown_merchant__", blankKey)
    }

    @Test
    fun `canonicalMerchantKey handles punctuation-only merchant without crashing`() {
        val punctuationOnly = snapshot(merchant = "!!! --- ???", merchantKey = null)

        val key = punctuationOnly.canonicalMerchantKey()

        assertEquals("__unknown_merchant__", key)
    }

    @Test
    fun `canonicalMerchantKey normalizes mixed-case aliases to same canonical key`() {
        val upper = snapshot(merchant = "AMAZON")
        val title = snapshot(merchant = "Amazon")
        val lower = snapshot(merchant = "amazon")

        val keys = listOf(upper, title, lower).map { it.canonicalMerchantKey() }.toSet()

        assertEquals(1, keys.size)
        assertEquals("amazon", keys.first())
    }

    @Test
    fun `canonicalMerchantKey produces stable keys for unicode merchants`() {
        val japanese = snapshot(merchant = "カフェ")
        val greek = snapshot(merchant = "Καφές")

        val japaneseKey = japanese.canonicalMerchantKey()
        val greekKey = greek.canonicalMerchantKey()

        assertTrue(japaneseKey.isNotBlank())
        assertTrue(greekKey.isNotBlank())
        assertEquals(japaneseKey, japanese.canonicalMerchantKey())
        assertEquals(greekKey, greek.canonicalMerchantKey())
    }

    @Test
    fun `resolveMerchantDisplayName prefers most frequent trimmed label`() {
        val expenses = listOf(
            snapshot(merchant = " AMAZON "),
            snapshot(merchant = "Amazon"),
            snapshot(merchant = "Amazon")
        )

        assertEquals("Amazon", resolveMerchantDisplayName(expenses))
    }

    private fun snapshot(
        merchant: String,
        merchantKey: String? = null
    ): ExpenseSnapshot {
        return ExpenseSnapshot(
            id = 1L,
            amount = 1.0,
            effectiveAmount = 1.0,
            currency = "EUR",
            merchant = merchant,
            merchantKey = merchantKey,
            transactionType = DomainTransactionType.PURCHASE,
            date = 0L,
            categoryId = null,
            isNotMine = false,
            transferDirection = null,
            notes = null
        )
    }
}
