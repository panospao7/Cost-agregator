package com.yourname.expensetracker.domain.receipt.lifecycle

import com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * CA-P-03-004: the text fingerprint strips amounts and dates, so a text hit
 * must not declare a duplicate when both semantic fingerprints are known and
 * disagree (same merchant template, different total/date).
 */
class ReceiptDuplicateDetectorTest {

    private val scannedReceiptDao = mockk<ScannedReceiptDao>()
    private val receiptExpenseLinkDao = mockk<ReceiptExpenseLinkDao>(relaxed = true)
    private lateinit var detector: ReceiptDuplicateDetector

    @Before
    fun setUp() {
        coEvery { scannedReceiptDao.getByImageHash(any()) } returns null
        coEvery { scannedReceiptDao.getByTextFingerprint(any()) } returns null
        coEvery { scannedReceiptDao.getBySemanticFingerprint(any()) } returns null
        coEvery { scannedReceiptDao.getBySourceFingerprint(any()) } returns null
        detector = ReceiptDuplicateDetector(scannedReceiptDao, receiptExpenseLinkDao)
    }

    private fun receipt(id: Long, semantic: String?) = ScannedReceipt(
        id = id,
        imagePath = null,
        rawOcrText = "",
        parsedTotal = null,
        parsedMerchant = null,
        parsedDate = null,
        parsedItems = null,
        parsedTaxAmount = null,
        confidence = 1.0f,
        textFingerprint = TEXT_FP,
        semanticFingerprint = semantic
    )

    @Test
    fun `same text different amounts is not a text duplicate`() = runTest {
        // Two receipts from the same template: TOTAL 3.50 vs TOTAL 4.50.
        val fp350 = detector.computeSemanticFingerprintPublic("Cafe", 3.50, null, "EUR")
        val fp450 = detector.computeSemanticFingerprintPublic("Cafe", 4.50, null, "EUR")
        assertEquals(
            detector.computeTextFingerprintPublic("CAFE\nTOTAL 3.50"),
            detector.computeTextFingerprintPublic("CAFE\nTOTAL 4.50")
        )
        coEvery { scannedReceiptDao.getByTextFingerprint(TEXT_FP) } returns receipt(7L, fp350)

        val result = detector.checkDuplicate(null, TEXT_FP, fp450, null)

        assertFalse(result.isDuplicate)
        assertEquals("NONE", result.matchType)
    }

    @Test
    fun `text hit with matching semantic fingerprint is a duplicate`() = runTest {
        coEvery { scannedReceiptDao.getByTextFingerprint(TEXT_FP) } returns receipt(7L, "sem-a")

        val result = detector.checkDuplicate(null, TEXT_FP, "sem-a", null)

        assertTrue(result.isDuplicate)
        assertEquals("TEXT_FINGERPRINT", result.matchType)
        assertEquals(7L, result.existingReceiptId)
    }

    @Test
    fun `text hit is kept when incoming semantic fingerprint is unknown`() = runTest {
        coEvery { scannedReceiptDao.getByTextFingerprint(TEXT_FP) } returns receipt(7L, "sem-a")

        val result = detector.checkDuplicate(null, TEXT_FP, null, null)

        assertTrue(result.isDuplicate)
        assertEquals("TEXT_FINGERPRINT", result.matchType)
    }

    @Test
    fun `text hit is kept when existing semantic fingerprint is unknown`() = runTest {
        coEvery { scannedReceiptDao.getByTextFingerprint(TEXT_FP) } returns receipt(7L, null)

        val result = detector.checkDuplicate(null, TEXT_FP, "sem-b", null)

        assertTrue(result.isDuplicate)
        assertEquals("TEXT_FINGERPRINT", result.matchType)
    }

    @Test
    fun `conflicting text hit falls through to semantic match`() = runTest {
        coEvery { scannedReceiptDao.getByTextFingerprint(TEXT_FP) } returns receipt(7L, "sem-a")
        coEvery { scannedReceiptDao.getBySemanticFingerprint("sem-b") } returns receipt(9L, "sem-b")

        val result = detector.checkDuplicate(null, TEXT_FP, "sem-b", null)

        assertTrue(result.isDuplicate)
        assertEquals("SEMANTIC", result.matchType)
        assertEquals(9L, result.existingReceiptId)
    }

    private companion object {
        const val TEXT_FP = "text-fp"
    }
}
