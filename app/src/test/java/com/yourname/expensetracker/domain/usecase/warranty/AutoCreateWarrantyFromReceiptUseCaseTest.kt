package com.yourname.expensetracker.domain.usecase.warranty

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class AutoCreateWarrantyFromReceiptUseCaseTest {

    private val warrantyTrackerRepository = mockk<WarrantyTrackerRepository>()
    private val receiptRepository = mockk<ReceiptRepository>(relaxed = true)
    private val timeProvider = FakeTimeProvider(FIXED_NOW)

    private lateinit var useCase: AutoCreateWarrantyFromReceiptUseCase

    @Before
    fun setup() {
        // Default: any receipt lookup returns null (receipt not found or bypass)
        coEvery { receiptRepository.getReceiptById(any()) } returns null

        useCase = AutoCreateWarrantyFromReceiptUseCase(
            warrantyTrackerRepository = warrantyTrackerRepository,
            receiptRepository = receiptRepository,
            timeProvider = timeProvider
        )
    }

    @Test
    fun `execute auto-creates warranty when extraction confidence is high at or above 70`() = runTest {
        val receiptId = 1001L
        val ocr = """
            MERCHANT: TECH STORE
            DATE: ${recentDate()}
            PRODUCT: ULTRA LAPTOP PRO 15
            WARRANTY: 24 MONTHS
            SUPPORT: support@techstore.com
        """.trimIndent()

        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns null
        val insertedWarrantySlot = slot<Warranty>()
        coEvery { warrantyTrackerRepository.addWarrantyIgnoreConflicts(capture(insertedWarrantySlot)) } returns 777L

        val result = useCase.execute(receiptId = receiptId, receiptText = ocr)

        assertTrue(result is WarrantyCreationResult.Success)
        val success = result as WarrantyCreationResult.Success
        assertEquals(777L, success.warrantyId)
        assertTrue(success.confidence >= 70.0)

        assertEquals(receiptId, insertedWarrantySlot.captured.receiptId)
        assertEquals(true, insertedWarrantySlot.captured.autoDetected)
        assertEquals(false, insertedWarrantySlot.captured.needsReview)
        assertEquals(WarrantyStatus.ACTIVE, insertedWarrantySlot.captured.status)
        assertApproxEquals(success.confidence, insertedWarrantySlot.captured.extractionConfidence, 0.0001)
    }

    @Test
    fun `execute creates low-confidence review draft and returns LowConfidence for 40-70`() = runTest {
        val receiptId = 1002L
        val ocr = """
            MERCHANT: CORNER SHOP
            DATE: ${recentDate()}
            PRODUCT: USB C CABLE
        """.trimIndent()

        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns null
        val draftSlot = slot<Warranty>()
        coEvery { warrantyTrackerRepository.addWarrantyIgnoreConflicts(capture(draftSlot)) } returns 778L

        val result = useCase.execute(receiptId = receiptId, receiptText = ocr)

        assertTrue(result is WarrantyCreationResult.LowConfidence)
        val low = result as WarrantyCreationResult.LowConfidence
        assertTrue(low.extractedData.confidence >= 40.0)
        assertTrue(low.extractedData.confidence < 70.0)

        assertEquals(true, draftSlot.captured.needsReview)
        assertEquals(WarrantyStatus.PENDING_REVIEW, draftSlot.captured.status)
        assertEquals(true, draftSlot.captured.autoDetected)
        assertTrue(draftSlot.captured.notes?.contains("needs review", ignoreCase = true) == true)
    }

    @Test
    fun `execute returns AlreadyExists when warranty already exists for receipt id`() = runTest {
        val receiptId = 1003L
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns Warranty(
            id = 55L,
            receiptId = receiptId,
            productName = "Existing Item",
            merchantName = "Existing Shop",
            purchaseDate = FIXED_NOW - 1_000L,
            warrantyDurationMonths = 12,
            warrantyEndDate = FIXED_NOW + 1_000L
        )

        val result = useCase.execute(receiptId, "ANY OCR")

        assertTrue(result is WarrantyCreationResult.AlreadyExists)
        assertEquals(55L, (result as WarrantyCreationResult.AlreadyExists).existingWarrantyId)
        coVerify(exactly = 0) { warrantyTrackerRepository.addWarrantyIgnoreConflicts(any()) }
    }

    @Test
    fun `execute handles unique constraint conflict on receiptId and returns AlreadyExists`() = runTest {
        val receiptId = 1004L
        val ocr = """
            MERCHANT: DUP STORE
            DATE: ${recentDate()}
            PRODUCT: GAMING MONITOR X
            WARRANTY: 12 MONTHS
        """.trimIndent()

        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returnsMany listOf(
            null,
            Warranty(
                id = 88L,
                receiptId = receiptId,
                productName = "Gaming Monitor X",
                merchantName = "Dup Store",
                purchaseDate = FIXED_NOW,
                warrantyDurationMonths = 12,
                warrantyEndDate = FIXED_NOW + 10_000L
            )
        )
        coEvery { warrantyTrackerRepository.addWarrantyIgnoreConflicts(any()) } returns -1L

        val result = useCase.execute(receiptId = receiptId, receiptText = ocr)

        assertTrue(result is WarrantyCreationResult.AlreadyExists)
        assertEquals(88L, (result as WarrantyCreationResult.AlreadyExists).existingWarrantyId)
    }

    @Test
    fun `execute returns Failure for empty OCR text edge case`() = runTest {
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(1005L) } returns null

        val result = useCase.execute(receiptId = 1005L, receiptText = "")

        assertTrue(result is WarrantyCreationResult.Failure)
        assertTrue((result as WarrantyCreationResult.Failure).error.contains("Confidence too low", ignoreCase = true))
    }

    @Test
    fun `execute fails when date missing even if confidence is otherwise high`() = runTest {
        val receiptId = 1006L
        val ocr = """
            MERCHANT: APPLE STORE
            PRODUCT: IPHONE 15 PRO MAX
            WARRANTY: 24 MONTHS
            MANUFACTURER WARRANTY INCLUDED
        """.trimIndent()

        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns null

        val result = useCase.execute(receiptId = receiptId, receiptText = ocr)

        assertTrue(result is WarrantyCreationResult.Failure)
        assertTrue((result as WarrantyCreationResult.Failure).error.contains("purchaseDate", ignoreCase = true))
    }

    @Test
    fun `execute returns Failure for malformed warranty text edge case`() = runTest {
        val receiptId = 1007L
        val malformed = "@@@ ### ??? -- not a receipt --"

        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns null

        val result = useCase.execute(receiptId = receiptId, receiptText = malformed)

        assertTrue(result is WarrantyCreationResult.Failure)
        assertTrue((result as WarrantyCreationResult.Failure).error.contains("Confidence too low", ignoreCase = true))
    }

    @Test
    fun `createWarrantyForReview promotes existing draft instead of inserting duplicate`() = runTest {
        val receiptId = 2001L
        val existingDraft = Warranty(
            id = 91L,
            receiptId = receiptId,
            productName = "Unknown Product",
            merchantName = "Unknown Merchant",
            purchaseDate = FIXED_NOW - 5_000L,
            warrantyDurationMonths = 12,
            warrantyEndDate = FIXED_NOW + 10_000L,
            status = WarrantyStatus.PENDING_REVIEW,
            needsReview = true
        )
        val confirmedData = useCase.getExtractor().extract(
            """
                MERCHANT: REVIEW STORE
                DATE: ${recentDate()}
                PRODUCT: GAMING HEADSET PRO
                WARRANTY: 24 MONTHS
            """.trimIndent()
        )

        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns existingDraft
        coEvery { warrantyTrackerRepository.updateWarranty(any()) } returns Unit

        val result = useCase.createWarrantyForReview(receiptId, confirmedData)

        assertTrue(result is WarrantyCreationResult.Success)
        assertEquals(91L, (result as WarrantyCreationResult.Success).warrantyId)
        coVerify(exactly = 1) { warrantyTrackerRepository.updateWarranty(match { it.id == 91L && !it.needsReview && it.status == WarrantyStatus.ACTIVE }) }
        coVerify(exactly = 0) { warrantyTrackerRepository.addWarrantyIgnoreConflicts(any()) }
    }

    private fun recentDate(): String {
        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            add(Calendar.DAY_OF_MONTH, -1)
        }
        val day = cal.get(Calendar.DAY_OF_MONTH).toString().padStart(2, '0')
        val month = (cal.get(Calendar.MONTH) + 1).toString().padStart(2, '0')
        val year = cal.get(Calendar.YEAR)
        return "$day/$month/$year"
    }

    companion object {
        private const val FIXED_NOW = 1_730_000_000_000L
    }
}
