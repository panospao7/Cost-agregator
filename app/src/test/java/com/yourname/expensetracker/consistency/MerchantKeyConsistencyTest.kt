package com.yourname.expensetracker.consistency

import com.yourname.expensetracker.data.repository.MerchantRulesRepository
import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.MerchantKeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Ensures MerchantKeyGenerator produces consistent keys regardless of whether
 * the merchant comes from parser path (MerchantCleaner) or categorization path
 * (MerchantRulesRepository). Both CategorizationEngine and parsers ultimately
 * use MerchantKeyGenerator for deduplication; this test catches drift.
 */
class MerchantKeyConsistencyTest {

    private lateinit var merchantCleaner: MerchantCleaner
    private lateinit var merchantRules: MerchantRulesRepository

    @Before
    fun setup() {
        merchantCleaner = MerchantCleaner()
        merchantRules = MerchantRulesRepository()
    }

    @Test
    fun `consistency - same merchant produces same key via MerchantCleaner and MerchantRules`() {
        val merchants = listOf(
            "Starbucks",
            "SKLAVENITIS",
            "LIDL",
            "Pizza Hood",
            "AB Vasilopoulos"
        )
        for (merchant in merchants) {
            val cleanedByParser = merchantCleaner.clean(merchant)
            val cleanedByRules = merchantRules.cleanMerchantName(merchant)
            val keyFromParser = MerchantKeyGenerator.generate(cleanedByParser)
            val keyFromRules = MerchantKeyGenerator.generate(cleanedByRules)
            assertEquals(
                "Parser path and Rules path must produce same key for: $merchant",
                keyFromParser,
                keyFromRules
            )
        }
    }

    @Test
    fun `consistency - MerchantKeyGenerator is single source for keys`() {
        val raw = "  Starbucks  "
        val keyDirect = MerchantKeyGenerator.generate("Starbucks")
        val keyFromCleaned = MerchantKeyGenerator.generate(merchantCleaner.clean(raw))
        val keyFromRules = MerchantKeyGenerator.generate(merchantRules.cleanMerchantName(raw))
        assertEquals("starbucks", keyDirect)
        assertEquals(keyDirect, keyFromCleaned)
        assertEquals(keyDirect, keyFromRules)
    }

    @Test
    fun `consistency - corporate suffix stripped by Rules produces same key as base name`() {
        val withSuffix = "Acme Corp LTD"
        val base = "Acme Corp"
        val keyWithSuffix = MerchantKeyGenerator.generate(merchantRules.cleanMerchantName(withSuffix))
        val keyBase = MerchantKeyGenerator.generate(base)
        assertEquals(
            "Corporate suffix stripping must not change key for known merchant",
            keyBase,
            keyWithSuffix
        )
    }
}
