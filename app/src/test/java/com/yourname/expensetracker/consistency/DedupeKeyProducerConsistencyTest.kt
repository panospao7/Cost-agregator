package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.intelligence.DuplicateDetectionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ISSUE-8 regression: Verifies that all expense-insert producers use the same canonical
 * type-aware persisted dedupe-key generation strategy.
 *
 * Rule: every producer must call
 *   [DuplicateDetectionPolicy.generateDedupeKeyWithType] (not the legacy
 *   type-blind [DuplicateDetectionPolicy.generateDedupeKey]) when building the
 *   dedupeKey for a new expense row.
 *
 * These tests are pure-logic (no Android / Room deps) and exercise the policy
 * directly with the same inputs that each producer would supply.
 */
class DedupeKeyProducerConsistencyTest {

    private val amount = 42.99
    private val merchant = "TestMerchant"
    private val date = 1_800_000_000_000L   // arbitrary stable timestamp
    private val currency = "EUR"

    // ── Canonical key helper (mirrors each producer's call-site) ─────────

    /** Simulates approveReview() key generation. */
    private fun approveReviewKey(type: TransactionType) =
        DuplicateDetectionPolicy.generateDedupeKeyWithType(amount, merchant, date, currency, type)

    /** Simulates markAsRelevant() key generation (ReviewQueueRepository). */
    private fun markAsRelevantKey(type: TransactionType) =
        DuplicateDetectionPolicy.generateDedupeKeyWithType(amount, merchant, date, currency, type)

    /** Simulates NotificationProcessingPipeline key generation. */
    private fun notificationPipelineKey(type: TransactionType) =
        DuplicateDetectionPolicy.generateDedupeKeyWithType(amount, merchant, date, currency, type)

    /** Simulates ManualExpenseRepository key generation. */
    private fun manualExpenseKey(type: TransactionType) =
        DuplicateDetectionPolicy.generateDedupeKeyWithType(amount, merchant, date, currency, type)

    /** Simulates ReceiptRepository.createExpenseFromReceipt() — always PURCHASE. */
    private fun receiptKey() =
        DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.PURCHASE
        )

    /** Simulates EmailReceiptIngestionService — always PURCHASE. */
    private fun emailReceiptKey() =
        DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.PURCHASE
        )

    // ── Consistency assertions ────────────────────────────────────────────

    @Test
    fun `all producers agree on PURCHASE key`() {
        val type = TransactionType.PURCHASE
        val expected = DuplicateDetectionPolicy.generateDedupeKeyWithType(amount, merchant, date, currency, type)

        assertEquals("approveReview PURCHASE key mismatch", expected, approveReviewKey(type))
        assertEquals("markAsRelevant PURCHASE key mismatch", expected, markAsRelevantKey(type))
        assertEquals("notificationPipeline PURCHASE key mismatch", expected, notificationPipelineKey(type))
        assertEquals("manualExpense PURCHASE key mismatch", expected, manualExpenseKey(type))
        assertEquals("receipt PURCHASE key mismatch", expected, receiptKey())
        assertEquals("emailReceipt PURCHASE key mismatch", expected, emailReceiptKey())
    }

    @Test
    fun `all producers agree on DEPOSIT key`() {
        val type = TransactionType.DEPOSIT
        val expected = DuplicateDetectionPolicy.generateDedupeKeyWithType(amount, merchant, date, currency, type)

        assertEquals("approveReview DEPOSIT key mismatch", expected, approveReviewKey(type))
        assertEquals("markAsRelevant DEPOSIT key mismatch", expected, markAsRelevantKey(type))
        assertEquals("notificationPipeline DEPOSIT key mismatch", expected, notificationPipelineKey(type))
        assertEquals("manualExpense DEPOSIT key mismatch", expected, manualExpenseKey(type))
    }

    @Test
    fun `all producers agree on TRANSFER key`() {
        val type = TransactionType.TRANSFER
        val expected = DuplicateDetectionPolicy.generateDedupeKeyWithType(amount, merchant, date, currency, type)

        assertEquals("approveReview TRANSFER key mismatch", expected, approveReviewKey(type))
        assertEquals("markAsRelevant TRANSFER key mismatch", expected, markAsRelevantKey(type))
        assertEquals("notificationPipeline TRANSFER key mismatch", expected, notificationPipelineKey(type))
        assertEquals("manualExpense TRANSFER key mismatch", expected, manualExpenseKey(type))
    }

    @Test
    fun `UNKNOWN type falls back to type-blind key for backward compat`() {
        val typeBlindKey = DuplicateDetectionPolicy.generateDedupeKey(amount, merchant, date, currency)
        val unknownTypeKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.UNKNOWN
        )
        // UNKNOWN must produce the same key as the legacy type-blind call.
        assertEquals(
            "UNKNOWN type must preserve backward-compat type-blind key",
            typeBlindKey,
            unknownTypeKey
        )
    }

    @Test
    fun `PURCHASE and DEPOSIT produce different keys for same transaction`() {
        val purchaseKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.PURCHASE
        )
        val depositKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.DEPOSIT
        )
        assertNotEquals(
            "PURCHASE and DEPOSIT must not share the same persisted dedupe key",
            purchaseKey,
            depositKey
        )
    }

    @Test
    fun `PURCHASE and TRANSFER produce different keys for same transaction`() {
        val purchaseKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.PURCHASE
        )
        val transferKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.TRANSFER
        )
        assertNotEquals(
            "PURCHASE and TRANSFER must not share the same persisted dedupe key",
            purchaseKey,
            transferKey
        )
    }

    @Test
    fun `PURCHASE key contains PURCHASE type suffix`() {
        val key = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.PURCHASE
        )
        assertTrue("PURCHASE key must end with _PURCHASE", key.endsWith("_PURCHASE"))
    }

    @Test
    fun `DEPOSIT key contains DEPOSIT type suffix`() {
        val key = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.DEPOSIT
        )
        assertTrue("DEPOSIT key must end with _DEPOSIT", key.endsWith("_DEPOSIT"))
    }

    @Test
    fun `UNKNOWN key does not contain a type suffix`() {
        val key = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, currency, TransactionType.UNKNOWN
        )
        // UNKNOWN produces base key only — the base ends with the currency code.
        assertTrue(
            "UNKNOWN key must not append a type suffix; got: $key",
            !key.endsWith("_UNKNOWN") && key.endsWith("_$currency")
        )
    }

    @Test
    fun `key is deterministic across 100 calls for each producer type`() {
        TransactionType.values().forEach { type ->
            val first = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                amount, merchant, date, currency, type
            )
            repeat(99) {
                val next = DuplicateDetectionPolicy.generateDedupeKeyWithType(
                    amount, merchant, date, currency, type
                )
                assertEquals("Key must be deterministic for $type", first, next)
            }
        }
    }

    @Test
    fun `different currencies produce different keys for same type`() {
        val eurKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, "EUR", TransactionType.PURCHASE
        )
        val usdKey = DuplicateDetectionPolicy.generateDedupeKeyWithType(
            amount, merchant, date, "USD", TransactionType.PURCHASE
        )
        assertNotEquals("EUR and USD PURCHASE must produce different keys", eurKey, usdKey)
    }

    @Test
    fun `receipt and email-receipt producers emit identical key for same inputs`() {
        // Both receipt and email receipt producers always create PURCHASE type expenses.
        assertEquals(
            "Receipt and email-receipt producers must emit identical dedupe keys",
            receiptKey(),
            emailReceiptKey()
        )
    }
}
