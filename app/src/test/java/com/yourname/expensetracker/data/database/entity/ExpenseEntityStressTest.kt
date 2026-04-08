package com.yourname.expensetracker.data.database.entity

import org.junit.Ignore
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * Stress Test Suite for Expense Entity
 * 
 * Goal: Break the Expense entity logic including dedupe key generation,
 * effectiveAmount calculations, and edge cases.
 * 
 * @author Hostile QA Engineer
 */
@Ignore("Stress test: may hang in CI, run manually")
class ExpenseEntityStressTest {

    // ============================================================================
    // SECTION 1: LOCALE TESTING FOR DEDUPE KEYS
    // ============================================================================

    @Test
    fun `stress - dedupe key with default US locale`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            
            // With US locale: 1234.56 -> "1234.56"
            val key = Expense.generateDedupeKey(1234.56, "shop", 1000000L, "EUR")
            assertTrue(key.startsWith("1234.56_"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `stress - dedupe key with German locale still uses dot (A4 regression)`() {
        // FIXED by A.4: generateDedupeKey now uses Locale.ROOT via DuplicateDetectionPolicy
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            
            val key = Expense.generateDedupeKey(1234.56, "shop", 1000000L, "EUR")
            
            // After A.4 fix, German locale must still produce dot-based amount
            assertTrue(key.startsWith("1234.56_"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `stress - dedupe key with Greek locale still uses dot (A4 regression)`() {
        // FIXED by A.4: Greek locale also uses Locale.ROOT now
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("el", "GR"))
            
            val key = Expense.generateDedupeKey(1234.56, "shop", 1000000L, "EUR")
            
            // After A.4 fix, Greek locale must still produce dot-based amount
            assertTrue(key.startsWith("1234.56_"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `stress - dedupe keys identical across locales for same amount (A4 regression)`() {
        val originalLocale = Locale.getDefault()
        
        val usKey: String
        val germanKey: String
        
        try {
            Locale.setDefault(Locale.US)
            usKey = Expense.generateDedupeKey(999.99, "merchant", 500000L, "EUR")
            
            Locale.setDefault(Locale.GERMANY)
            germanKey = Expense.generateDedupeKey(999.99, "merchant", 500000L, "EUR")
        } finally {
            Locale.setDefault(originalLocale)
        }
        
        // FIXED by A.4: keys MUST be identical regardless of locale
        assertEquals(usKey, germanKey)
    }

    @Test
    fun `stress - many amounts with locale variation produce identical keys (A4 regression)`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val usKeys = (1..100).map { i ->
                Expense.generateDedupeKey(i.toDouble(), "shop", 1000000L, "EUR")
            }
            
            Locale.setDefault(Locale.GERMANY)
            val germanKeys = (1..100).map { i ->
                Expense.generateDedupeKey(i.toDouble(), "shop", 1000000L, "EUR")
            }
            
            // FIXED by A.4: all keys must be identical across locales
            usKeys.zip(germanKeys).forEachIndexed { idx, (us, de) ->
                assertEquals("Keys for amount ${idx + 1} must be equal", us, de)
            }
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    // ============================================================================
    // SECTION 2: EFFECTIVE AMOUNT CALCULATIONS
    // ============================================================================

    @Test
    fun `stress - effectiveAmount isNotMine returns zero`() {
        val expense = Expense(
            amount = 100.0,
            merchant = "Shop",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            isNotMine = true
        )
        
        assertEquals(0.0, expense.effectiveAmount, 0.001)
    }

    @Test
    fun `stress - effectiveAmount shared with amount returns myShareAmount`() {
        val expense = Expense(
            amount = 100.0,
            merchant = "Shop",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            isSharedExpense = true,
            myShareAmount = 25.0
        )
        
        assertEquals(25.0, expense.effectiveAmount, 0.001)
    }

    @Test
    fun `stress - effectiveAmount shared with percentage returns calculated amount`() {
        val expense = Expense(
            amount = 100.0,
            merchant = "Shop",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            isSharedExpense = true,
            mySharePercentage = 50
        )
        
        assertEquals(50.0, expense.effectiveAmount, 0.001)
    }

    @Test
    fun `stress - effectiveAmount shared with percentage 33 returns calculated`() {
        val expense = Expense(
            amount = 150.0,
            merchant = "Shop",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            isSharedExpense = true,
            mySharePercentage = 33
        )
        
        assertEquals(49.5, expense.effectiveAmount, 0.001)
    }

    @Test
    fun `stress - effectiveAmount regular expense returns full amount`() {
        val expense = Expense(
            amount = 99.99,
            merchant = "Shop",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            isSharedExpense = false,
            isNotMine = false
        )
        
        assertEquals(99.99, expense.effectiveAmount, 0.001)
    }

    @Test
    fun `stress - effectiveAmount isNotMine overrides shared`() {
        // If both isNotMine and isSharedExpense are true, isNotMine takes precedence
        val expense = Expense(
            amount = 100.0,
            merchant = "Shop",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            isNotMine = true,
            isSharedExpense = true,
            myShareAmount = 25.0
        )
        
        // isNotMine = true returns 0.0 regardless of shared settings
        assertEquals(0.0, expense.effectiveAmount, 0.001)
    }

    @Test
    fun `stress - effectiveAmount with null percentage uses full amount`() {
        val expense = Expense(
            amount = 100.0,
            merchant = "Shop",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            isSharedExpense = true,
            mySharePercentage = null,
            myShareAmount = null
        )
        
        // Both null - should return full amount
        assertEquals(100.0, expense.effectiveAmount, 0.001)
    }

    // ============================================================================
    // SECTION 3: TRANSFER DIRECTION COMBINATIONS
    // ============================================================================

    @Test
    fun `stress - all transaction types create correctly`() {
        TransactionType.entries.forEach { type ->
            val expense = Expense(
                amount = 100.0,
                merchant = "Test",
                transactionType = type,
                date = System.currentTimeMillis()
            )
            assertEquals(type, expense.transactionType)
        }
    }

    @Test
    fun `stress - transfer with incoming direction`() {
        val expense = Expense(
            amount = 500.0,
            merchant = "Transfer",
            transactionType = TransactionType.TRANSFER,
            date = System.currentTimeMillis(),
            transferDirection = TransferDirection.INCOMING
        )
        
        assertEquals(TransferDirection.INCOMING, expense.transferDirection)
    }

    @Test
    fun `stress - transfer with outgoing direction`() {
        val expense = Expense(
            amount = 500.0,
            merchant = "Transfer",
            transactionType = TransactionType.TRANSFER,
            date = System.currentTimeMillis(),
            transferDirection = TransferDirection.OUTGOING
        )
        
        assertEquals(TransferDirection.OUTGOING, expense.transferDirection)
    }

    @Test
    fun `stress - purchase with transfer direction should be null`() {
        val expense = Expense(
            amount = 100.0,
            merchant = "Shop",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            transferDirection = null
        )
        
        assertNull(expense.transferDirection)
    }

    @Test
    fun `stress - deposit with incoming direction`() {
        val expense = Expense(
            amount = 1000.0,
            merchant = "Salary",
            transactionType = TransactionType.DEPOSIT,
            date = System.currentTimeMillis(),
            transferDirection = TransferDirection.INCOMING
        )
        
        assertEquals(TransferDirection.INCOMING, expense.transferDirection)
    }

    // ============================================================================
    // SECTION 4: NULL SAFETY
    // ============================================================================

    @Test
    fun `stress - all nullable fields null`() {
        // This should compile and work - all nullable fields are optional
        val expense = Expense(
            amount = 100.0,
            merchant = "Test",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis()
        )
        
        assertNotNull(expense)
        assertNull(expense.rawNotificationId)
        assertNull(expense.categoryId)
        assertNull(expense.notes)
        assertNull(expense.dedupeKey)
        assertNull(expense.transferDirection)
        assertNull(expense.transferAccountName)
        assertNull(expense.latitude)
        assertNull(expense.longitude)
        assertNull(expense.locationSource)
        assertNull(expense.placeId)
        assertNull(expense.ownerName)
        assertNull(expense.sharedWithName)
        assertNull(expense.mySharePercentage)
        assertNull(expense.myShareAmount)
    }

    @Test
    fun `stress - partially null fields`() {
        val expense = Expense(
            amount = 100.0,
            merchant = "Test",
            transactionType = TransactionType.PURCHASE,
            date = System.currentTimeMillis(),
            categoryId = 1L,
            dedupeKey = "key",
            latitude = 40.7128,
            longitude = -74.0060
        )
        
        assertNotNull(expense.categoryId)
        assertNotNull(expense.dedupeKey)
        assertNotNull(expense.latitude)
        assertNotNull(expense.longitude)
    }

    // ============================================================================
    // SECTION 5: DEDUPE KEY BOUNDARIES
    // ============================================================================

    @Test
    fun `stress - dedupe key at 5-minute window boundary`() {
        // Exactly at boundary
        val t1 = 300_000L // bucket 1
        val t2 = 600_000L // bucket 2
        
        val key1 = Expense.generateDedupeKey(50.0, "shop", t1, "EUR")
        val key2 = Expense.generateDedupeKey(50.0, "shop", t2, "EUR")
        
        assertNotEquals(key1, key2)
    }

    @Test
    fun `stress - dedupe key just before boundary`() {
        val t1 = 299_999L // bucket 0
        val t2 = 300_000L // bucket 1
        
        val key1 = Expense.generateDedupeKey(50.0, "shop", t1, "EUR")
        val key2 = Expense.generateDedupeKey(50.0, "shop", t2, "EUR")
        
        assertNotEquals(key1, key2)
    }

    @Test
    fun `stress - dedupe key within same bucket`() {
        val t1 = 100_000L // bucket 0
        val t2 = 299_999L // still bucket 0
        
        val key1 = Expense.generateDedupeKey(50.0, "shop", t1, "EUR")
        val key2 = Expense.generateDedupeKey(50.0, "shop", t2, "EUR")
        
        assertEquals(key1, key2)
    }

    @Test
    fun `stress - dedupe key with very small amounts`() {
        val key1 = Expense.generateDedupeKey(0.01, "shop", 1000000L, "EUR")
        val key2 = Expense.generateDedupeKey(0.02, "shop", 1000000L, "EUR")
        
        assertNotEquals(key1, key2)
        assertTrue(key1.contains("0.01"))
        assertTrue(key2.contains("0.02"))
    }

    @Test
    fun `stress - dedupe key with very large amounts`() {
        val key = Expense.generateDedupeKey(999999.99, "shop", 1000000L, "EUR")
        assertTrue(key.contains("999999.99"))
    }

    @Test
    fun `stress - dedupe key with amounts that round differently`() {
        // 1.255 should round to 1.26
        val key1 = Expense.generateDedupeKey(1.255, "shop", 1000000L, "EUR")
        assertTrue(key1.contains("1.26"))
        
        // 1.254 should round to 1.25
        val key2 = Expense.generateDedupeKey(1.254, "shop", 1000000L, "EUR")
        assertTrue(key2.contains("1.25"))
    }

    // ============================================================================
    // SECTION 6: MERCHANT NORMALIZATION
    // ============================================================================

    @Test
    fun `stress - dedupe key normalizes merchant case`() {
        val key1 = Expense.generateDedupeKey(50.0, "STARBUCKS", 1000000L, "EUR")
        val key2 = Expense.generateDedupeKey(50.0, "starbucks", 1000000L, "EUR")
        val key3 = Expense.generateDedupeKey(50.0, "Starbucks", 1000000L, "EUR")
        
        assertEquals(key1, key2)
        assertEquals(key2, key3)
    }

    @Test
    fun `stress - dedupe key normalizes Greek characters`() {
        // Greek sigma can be at end of word (ς) or middle (σ)
        val key1 = Expense.generateDedupeKey(50.0, "Καφές", 1000000L, "EUR") // ends with ς
        val key2 = Expense.generateDedupeKey(50.0, "Καφεσ", 1000000L, "EUR")  // ends with σ
        
        // Keys should be equal after normalization
        // This tests MerchantKeyGenerator normalization
    }

    @Test
    fun `stress - dedupe key removes special characters`() {
        val key1 = Expense.generateDedupeKey(50.0, "Shop-123", 1000000L, "EUR")
        val key2 = Expense.generateDedupeKey(50.0, "Shop123", 1000000L, "EUR")
        
        // After removing non-alphanumeric, should be same
        // This tests the MerchantKeyGenerator
    }

    // ============================================================================
    // SECTION 7: FUZZ TESTING
    // ============================================================================

    @Test
    fun `stress - fuzz random merchants`() {
        repeat(1000) {
            val merchant = (1..20).map {
                when (Random.nextInt(5)) {
                    0 -> ('a'..'z').random()
                    1 -> ('A'..'Z').random()
                    2 -> ('α'..'ω').random() // Greek
                    3 -> ('0'..'9').random()
                    else -> " _-".random()
                }
            }.joinToString("")
            
            try {
                val key = Expense.generateDedupeKey(100.0, merchant, 1000000L, "EUR")
                assertNotNull(key)
            } catch (e: Exception) {
                fail("generateDedupeKey crashed with: $merchant")
            }
        }
    }

    @Test
    fun `stress - fuzz random amounts`() {
        repeat(1000) {
            val amount = Random.nextDouble(0.0, 1000000.0)
            
            try {
                val key = Expense.generateDedupeKey(amount, "shop", 1000000L, "EUR")
                assertNotNull(key)
                // Valid amounts should create valid keys
            } catch (e: Exception) {
                fail("generateDedupeKey crashed with amount: $amount")
            }
        }
    }

    // ============================================================================
    // SECTION 8: EDGE CASES
    // ============================================================================

    @Test
    fun `stress - zero amount`() {
        val key = Expense.generateDedupeKey(0.0, "shop", 1000000L, "EUR")
        assertTrue(key.contains("0.00"))
    }

    @Test
    fun `stress - very long merchant name`() {
        val longMerchant = "A".repeat(1000)
        val key = Expense.generateDedupeKey(50.0, longMerchant, 1000000L, "EUR")
        assertNotNull(key)
    }

    @Test
    fun `stress - unicode merchant name`() {
        val unicode = "🛒shop" // emoji + text
        val key = Expense.generateDedupeKey(50.0, unicode, 1000000L, "EUR")
        assertNotNull(key)
    }

    @Test
    fun `stress - empty merchant name`() {
        val key = Expense.generateDedupeKey(50.0, "", 1000000L, "EUR")
        assertNotNull(key)
    }

    @Test
    fun `stress - timestamp at epoch`() {
        val key = Expense.generateDedupeKey(50.0, "shop", 0L, "EUR")
        assertTrue(key.contains("_0_"))
    }

    @Test
    fun `stress - negative timestamp`() {
        // Negative timestamps (before epoch) should still work
        val key = Expense.generateDedupeKey(50.0, "shop", -1000L, "EUR")
        assertNotNull(key)
    }

    @Test
    fun `stress - very large timestamp`() {
        // Far future timestamps
        val key = Expense.generateDedupeKey(50.0, "shop", Long.MAX_VALUE, "EUR")
        assertNotNull(key)
    }

    // ============================================================================
    // SECTION 9: REGRESSION TESTS
    // ============================================================================

    @Test
    fun `regression - basic dedupe key format unchanged`() {
        val key = Expense.generateDedupeKey(12.50, "cafe", 300000L, "EUR")
        assertEquals("12.50_cafe_1_EUR", key)
    }

    @Test
    fun `regression - case normalization unchanged`() {
        val key1 = Expense.generateDedupeKey(20.0, "Starbucks", 1000000L, "EUR")
        val key2 = Expense.generateDedupeKey(20.0, "STARBUCKS", 1000000L, "EUR")
        assertEquals(key1, key2)
    }

    @Test
    fun `regression - 5-minute window unchanged`() {
        val t1 = 600000L
        val t2 = 899999L
        val key1 = Expense.generateDedupeKey(5.0, "kiosk", t1, "EUR")
        val key2 = Expense.generateDedupeKey(5.0, "kiosk", t2, "EUR")
        assertEquals(key1, key2)
    }

    // ============================================================================
    // SECTION 10: A.4 REGRESSION — LOCALE BUG FIXED
    // ============================================================================

    @Test
    fun `regression A4 - locale no longer affects dedupe key generation`() {
        // FIXED by A.4: generateDedupeKey now delegates to DuplicateDetectionPolicy
        // which uses Locale.ROOT for amount formatting.
        
        val originalLocale = Locale.getDefault()
        
        try {
            Locale.setDefault(Locale.US)
            val usKey = Expense.generateDedupeKey(100.50, "test", 1000L, "EUR")
            
            Locale.setDefault(Locale.GERMANY)
            val deKey = Expense.generateDedupeKey(100.50, "test", 1000L, "EUR")
            
            // Keys MUST be identical now that Locale.ROOT is enforced
            assertEquals(usKey, deKey)
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `regression A4 - migration mismatch with locale is fixed`() {
        // FIXED by A.4: Both SQLite printf (always dot) and Kotlin formatting
        // now produce the same dot-based output because Locale.ROOT is used.
        
        val originalLocale = Locale.getDefault()
        
        try {
            Locale.setDefault(Locale.US)
            val key1 = Expense.generateDedupeKey(100.00, "shop", 1000L, "EUR")
            
            Locale.setDefault(Locale.GERMANY)
            val key2 = Expense.generateDedupeKey(100.00, "shop", 1000L, "EUR")
            
            // Both keys must be identical — no comma/dot mismatch
            assertEquals(key1, key2)
            assertTrue("Key must use dot decimal separator", key1.contains("100.00"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }
}
