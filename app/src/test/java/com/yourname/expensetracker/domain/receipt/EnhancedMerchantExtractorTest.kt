package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnhancedMerchantExtractorTest {

    private lateinit var merchantNormalizationDao: MerchantNormalizationDao
    private lateinit var extractor: EnhancedMerchantExtractor

    @Before
    fun setUp() {
        merchantNormalizationDao = mockk(relaxed = true)
        extractor = EnhancedMerchantExtractor(merchantNormalizationDao)
    }

    @Test
    fun `extract merchant from ocr text with clear merchant name`() = runTest {
        coEvery { merchantNormalizationDao.getTopMerchants(any()) } returns emptyList()

        val ocrText = """
            LIDL
            ATHENS CENTER
            TOTAL €12.30
        """.trimIndent()

        val result = extractor.extractMerchant(ocrText)

        assertEquals("LIDL", result.merchantName)
        assertEquals("ocr_extraction", result.source)
        assertApproxEquals(0.6, result.confidence, 0.0001)
    }

    @Test
    fun `existing merchant provided enhanced with ocr data`() = runTest {
        coEvery { merchantNormalizationDao.getTopMerchants(any()) } returns emptyList()

        val existingMerchant = "STARBUCKS"
        val ocrText = """
            STARBUCKS
            RECEIPT 000123
            TOTAL €4.80
        """.trimIndent()

        val result = extractor.extractMerchant(
            ocrText = ocrText,
            existingMerchant = existingMerchant
        )

        assertEquals(existingMerchant, result.merchantName)
        assertEquals("verified_existing", result.source)
        assertApproxEquals(0.9, result.confidence, 0.0001)
    }

    @Test
    fun `no merchant in ocr text returns existing merchant`() = runTest {
        coEvery { merchantNormalizationDao.getTopMerchants(any()) } returns emptyList()

        val existingMerchant = "THANK YOU"
        val ocrText = """
            RECEIPT
            THANK YOU
            CARD PAYMENT
            01/03/2026
            €9.99
        """.trimIndent()

        val result = extractor.extractMerchant(
            ocrText = ocrText,
            existingMerchant = existingMerchant
        )

        assertEquals(existingMerchant, result.merchantName)
        assertEquals("verified_existing", result.source)
        assertApproxEquals(0.9, result.confidence, 0.0001)
        assertTrue(result.alternatives.isNotEmpty())
    }

    @Test
    fun `empty ocr text returns null`() = runTest {
        coEvery { merchantNormalizationDao.getTopMerchants(any()) } returns emptyList()

        val result = extractor.extractMerchant(ocrText = "")

        assertNull(result.merchantName.toNullableMerchant())
        assertEquals("fallback", result.source)
        assertApproxEquals(0.0, result.confidence, 0.0001)
    }

    private fun String.toNullableMerchant(): String? {
        return takeUnless { it == "Unknown Merchant" }
    }
}
