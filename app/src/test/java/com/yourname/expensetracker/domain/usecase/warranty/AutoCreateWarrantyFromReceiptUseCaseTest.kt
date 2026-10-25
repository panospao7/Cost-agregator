package com.yourname.expensetracker.domain.usecase.warranty

import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.domain.receipt.WarrantyExtractionData
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

    // ai-fixes B1a: local confidence bands are independent of cloud/privacy settings (INV-8).

    @Test
    fun `use case has no PrivacyGate or other cloud gate dependency`() {
        // AE-11 / OD-1(a): the receipt-text guard is ReceiptSideEffectPlanner Policy A, not a cloud gate.
        val parameterTypes = AutoCreateWarrantyFromReceiptUseCase::class.java.constructors
            .flatMap { it.parameterTypes.toList() }
            .map { it.name }
        assertTrue(
            "Unexpected cloud/privacy dependency in constructor: $parameterTypes",
            parameterTypes.none { it.contains("PrivacyGate") || it.contains("AiCapabilityRouter") || it.contains("Cloud") }
        )
    }

    @Test
    fun `execute with 0_40 to 0_70 confidence creates a PENDING_REVIEW ocr draft with a return window and no gate`() = runTest {
        // AE-11 (retarget of `extractWarrantyFromReceipt skips cloud extraction when route is not cloud`)
        val receiptId = 1101L
        val ocr = """
            MERCHANT: CORNER SHOP
            DATE: ${recentDate()}
            PRODUCT: USB C CABLE
        """.trimIndent()
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns null
        val draftSlot = slot<Warranty>()
        coEvery { warrantyTrackerRepository.addWarrantyIgnoreConflicts(capture(draftSlot)) } returns 801L
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) } returns 1L

        val result = useCase.execute(receiptId, ocr)

        assertTrue(result is WarrantyCreationResult.LowConfidence)
        assertEquals(WarrantyStatus.PENDING_REVIEW, draftSlot.captured.status)
        assertEquals(true, draftSlot.captured.needsReview)
        assertEquals("ocr", draftSlot.captured.extractionSource)
        assertEquals(true, draftSlot.captured.autoDetected)
        coVerify(exactly = 1) {
            warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, match { it.id == 801L && it.needsReview })
        }
    }

    @Test
    fun `execute with confidence below 0_40 persists no warranty and no return window`() = runTest {
        // AE-12
        val receiptId = 1102L
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns null

        val result = useCase.execute(receiptId, "@@@ ### ??? -- not a receipt --")

        assertTrue(result is WarrantyCreationResult.Failure)
        coVerify(exactly = 0) { warrantyTrackerRepository.addWarrantyIgnoreConflicts(any()) }
        coVerify(exactly = 0) { warrantyTrackerRepository.upsertReturnWindowForReceipt(any(), any()) }
    }

    @Test
    fun `execute with high confidence creates an ACTIVE warranty and its return window`() = runTest {
        // AE-10 (use-case side; the zero-cloud-call proof is the constructor pin above plus guard AE-22)
        val receiptId = 1103L
        val ocr = """
            MERCHANT: TECH STORE
            DATE: ${recentDate()}
            PRODUCT: ULTRA LAPTOP PRO 15
            WARRANTY: 24 MONTHS
            SUPPORT: support@techstore.com
        """.trimIndent()
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns null
        val inserted = slot<Warranty>()
        coEvery { warrantyTrackerRepository.addWarrantyIgnoreConflicts(capture(inserted)) } returns 802L
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) } returns 2L

        val result = useCase.execute(receiptId, ocr)

        assertTrue(result is WarrantyCreationResult.Success)
        assertEquals(WarrantyStatus.ACTIVE, inserted.captured.status)
        assertEquals(false, inserted.captured.needsReview)
        coVerify(exactly = 1) { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) }
    }

    @Test
    fun `execute for an existing warranty only upserts the return window`() = runTest {
        // AE-9 (use-case side): AlreadyExists, one return-window upsert, no new warranty.
        val receiptId = 1104L
        val existing = Warranty(
            id = 60L,
            receiptId = receiptId,
            productName = "Existing Item",
            merchantName = "Best Buy",
            purchaseDate = FIXED_NOW - 1_000L,
            warrantyDurationMonths = 12,
            warrantyEndDate = FIXED_NOW + 1_000L
        )
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns existing
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, existing) } returns 3L

        val result = useCase.execute(receiptId, "Returns accepted within 60 days")

        assertTrue(result is WarrantyCreationResult.AlreadyExists)
        coVerify(exactly = 1) { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, existing) }
        coVerify(exactly = 0) { warrantyTrackerRepository.addWarrantyIgnoreConflicts(any()) }
    }

    @Test
    fun `production end date adds calendar months - Jan 31 plus one month is covered through Feb 29`() = runTest {
        // AE-24 (retarget of `extractWarrantyFromReceipt uses calendar month addition for warranty end date`):
        // exercised through the production calculateWarrantyEndDate via the review-draft promote path.
        val receiptId = 1105L
        val zone = ZoneId.systemDefault()
        val purchase = LocalDate.of(2024, 1, 31).atStartOfDay(zone).toInstant().toEpochMilli()
        val draft = Warranty(
            id = 92L,
            receiptId = receiptId,
            productName = "Camera",
            merchantName = "Calendar Store",
            purchaseDate = purchase,
            warrantyDurationMonths = 12,
            warrantyEndDate = purchase + 1_000L,
            status = WarrantyStatus.PENDING_REVIEW,
            needsReview = true
        )
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns draft
        val editSlot = slot<(Warranty) -> Warranty>()
        coEvery { warrantyTrackerRepository.promoteReviewDraft(92L, capture(editSlot)) } returns
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(92L, WarrantyStatus.ACTIVE)
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) } returns null

        val result = useCase.createWarrantyForReview(
            receiptId,
            WarrantyExtractionData(
                productName = "Camera",
                merchantName = "Calendar Store",
                purchaseDate = purchase,
                warrantyDurationMonths = 1,
                warrantyEndDate = null,
                confidence = 0.9
            )
        )

        assertTrue(result is WarrantyCreationResult.Success)
        // Stored warranty ends are exclusive: Jan 31 plus one calendar month is covered through
        // Feb 29, so the stored boundary is Mar 1 at 00:00 local.
        // The edit lambda runs against the repository's freshly read row (a distinct object here).
        val freshRow = draft.copy()
        val edited = editSlot.captured.invoke(freshRow)
        val exclusiveEnd = Instant.ofEpochMilli(edited.warrantyEndDate).atZone(zone)
        assertEquals(LocalDate.of(2024, 3, 1), exclusiveEnd.toLocalDate())
        assertEquals(LocalDate.of(2024, 2, 29), Instant.ofEpochMilli(edited.warrantyEndDate - 1L).atZone(zone).toLocalDate())
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
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) } returns null

        val result = useCase.execute(receiptId = receiptId, receiptText = ocr)

        assertTrue(result is WarrantyCreationResult.Success)
        val success = result as WarrantyCreationResult.Success
        assertEquals(777L, success.warrantyId)
        assertTrue(success.confidence >= 0.70)

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
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) } returns null

        val result = useCase.execute(receiptId = receiptId, receiptText = ocr)

        assertTrue(result is WarrantyCreationResult.LowConfidence)
        val low = result as WarrantyCreationResult.LowConfidence
        assertTrue(low.extractedData.confidence >= 0.40)
        assertTrue(low.extractedData.confidence < 0.70)

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
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) } returns null

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
    fun `execute propagates cancellation without converting it to Failure`() = runTest {
        val receiptId = 1008L
        val cancellation = CancellationException("cancelled")
        coEvery { receiptRepository.getReceiptById(receiptId) } throws cancellation

        val thrown = try {
            useCase.execute(receiptId, "OCR")
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `execute propagates cancellation from return window persistence`() = runTest {
        val receiptId = 1009L
        val cancellation = CancellationException("return-window-cancelled")
        val ocr = """
            MERCHANT: TECH STORE
            DATE: ${recentDate()}
            PRODUCT: ULTRA LAPTOP PRO 15
            WARRANTY: 24 MONTHS
        """.trimIndent()

        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns null
        coEvery { warrantyTrackerRepository.addWarrantyIgnoreConflicts(any()) } returns 779L
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) } throws cancellation

        val thrown = try {
            useCase.execute(receiptId, ocr)
            null
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, thrown)
    }

    @Test
    fun `execute returns controlled failure when return window persistence fails`() = runTest {
        val receiptId = 1010L
        val ocr = """
            MERCHANT: TECH STORE
            DATE: ${recentDate()}
            PRODUCT: ULTRA LAPTOP PRO 15
            WARRANTY: 24 MONTHS
        """.trimIndent()

        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns null
        coEvery { warrantyTrackerRepository.addWarrantyIgnoreConflicts(any()) } returns 780L
        coEvery {
            warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any())
        } throws IllegalStateException("database detail must not escape")

        val result = useCase.execute(receiptId, ocr)

        assertTrue(result is WarrantyCreationResult.Failure)
        assertEquals("RETURN_WINDOW_PERSIST_FAILED", (result as WarrantyCreationResult.Failure).error)
    }

    @Test
    fun `execute retries return window persistence for an existing warranty`() = runTest {
        val receiptId = 1011L
        val existingWarranty = Warranty(
            id = 56L,
            receiptId = receiptId,
            productName = "Existing Item",
            merchantName = "Existing Shop",
            purchaseDate = FIXED_NOW - 1_000L,
            warrantyDurationMonths = 12,
            warrantyEndDate = FIXED_NOW + 1_000L
        )
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns existingWarranty
        coEvery {
            warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, existingWarranty)
        } throws IllegalStateException("database detail must not escape")

        val result = useCase.execute(receiptId, "ANY OCR")

        assertTrue(result is WarrantyCreationResult.Failure)
        assertEquals("RETURN_WINDOW_PERSIST_FAILED", (result as WarrantyCreationResult.Failure).error)
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

        val editSlot = slot<(Warranty) -> Warranty>()
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns existingDraft
        coEvery { warrantyTrackerRepository.promoteReviewDraft(91L, capture(editSlot)) } returns
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(91L, WarrantyStatus.ACTIVE)
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) } returns null

        val result = useCase.createWarrantyForReview(receiptId, confirmedData)

        assertTrue(result is WarrantyCreationResult.Success)
        assertEquals(91L, (result as WarrantyCreationResult.Success).warrantyId)
        // F-AI-1: ONE atomic repository operation owns edit + leaving review; the use case never
        // calls updateWarranty / confirmWarranty directly.
        coVerify(exactly = 1) { warrantyTrackerRepository.promoteReviewDraft(91L, any()) }
        coVerify(exactly = 0) { warrantyTrackerRepository.updateWarranty(any()) }
        coVerify(exactly = 0) { warrantyTrackerRepository.confirmWarranty(any()) }
        coVerify(exactly = 0) { warrantyTrackerRepository.addWarrantyIgnoreConflicts(any()) }
        coVerify(exactly = 1) { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) }
        // The lambda never supplies status / needsReview / claimedAt: they come from the fresh row.
        val freshRow = existingDraft.copy()
        val edited = editSlot.captured.invoke(freshRow)
        assertEquals(WarrantyStatus.PENDING_REVIEW, edited.status)
        assertEquals(true, edited.needsReview)
        assertEquals(freshRow.claimedAt, edited.claimedAt)
        assertEquals(91L, edited.id)
    }

    @Test
    fun `createWarrantyForReview edit lambda takes content fallbacks from the fresh row not the first read`() = runTest {
        val receiptId = 2004L
        val firstRead = Warranty(
            id = 95L,
            receiptId = receiptId,
            productName = "Stale Product",
            merchantName = "Stale Merchant",
            purchaseDate = FIXED_NOW - 9_000L,
            warrantyDurationMonths = 6,
            warrantyEndDate = FIXED_NOW + 9_000L,
            supportPhone = "+00 stale-phone",
            supportEmail = "stale@example.test",
            notes = "stale notes",
            status = WarrantyStatus.PENDING_REVIEW,
            needsReview = true
        )
        val freshDay = LocalDate.of(2025, 3, 10).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fresh = Warranty(
            id = 95L,
            receiptId = receiptId,
            productName = "Fresh Product",
            merchantName = "Fresh Merchant",
            purchaseDate = freshDay,
            warrantyDurationMonths = 24,
            warrantyEndDate = freshDay + 1_000L,
            supportPhone = "+00 fresh-phone",
            supportEmail = "fresh@example.test",
            notes = "fresh notes",
            status = WarrantyStatus.PENDING_REVIEW,
            needsReview = true
        )
        val editSlot = slot<(Warranty) -> Warranty>()
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns firstRead
        coEvery { warrantyTrackerRepository.promoteReviewDraft(95L, capture(editSlot)) } returns
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(95L, WarrantyStatus.ACTIVE)
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) } returns null

        // No extracted content at all: every content field must fall back.
        val result = useCase.createWarrantyForReview(
            receiptId,
            WarrantyExtractionData(
                productName = null,
                merchantName = "   ",
                purchaseDate = null,
                warrantyDurationMonths = null,
                warrantyEndDate = null,
                supportPhone = null,
                supportEmail = null,
                confidence = 0.9
            )
        )

        assertTrue(result is WarrantyCreationResult.Success)
        val edited = editSlot.captured.invoke(fresh)
        assertEquals("Fresh Product", edited.productName)
        assertEquals("Fresh Merchant", edited.merchantName)
        assertEquals(freshDay, edited.purchaseDate)
        assertEquals(24, edited.warrantyDurationMonths)
        assertEquals("+00 fresh-phone", edited.supportPhone)
        assertEquals("fresh@example.test", edited.supportEmail)
        // End date is recomputed from the FRESH purchase date and duration (24 months), not the
        // first read's 6 months: it lands roughly two years after the fresh purchase date.
        assertTrue(edited.warrantyEndDate > freshDay + 700L * 86_400_000L)
        assertTrue(edited.warrantyEndDate < freshDay + 760L * 86_400_000L)
        assertEquals(true, edited.autoDetected)
        assertEquals("ocr", edited.extractionSource)
        assertEquals(0.9, edited.extractionConfidence, 0.0)
        assertEquals(WarrantyStatus.PENDING_REVIEW, edited.status)
        assertEquals(true, edited.needsReview)
    }

    @Test
    fun `createWarrantyForReview edit lambda prefers extracted content over the fresh row`() = runTest {
        val receiptId = 2005L
        val firstRead = Warranty(
            id = 96L,
            receiptId = receiptId,
            productName = "Stale Product",
            merchantName = "Stale Merchant",
            purchaseDate = FIXED_NOW - 9_000L,
            warrantyDurationMonths = 6,
            warrantyEndDate = FIXED_NOW + 9_000L,
            status = WarrantyStatus.PENDING_REVIEW,
            needsReview = true
        )
        val fresh = firstRead.copy(productName = "Fresh Product", merchantName = "Fresh Merchant", supportPhone = "+00 fresh-phone")
        val purchase = FIXED_NOW - 86_400_000L
        val editSlot = slot<(Warranty) -> Warranty>()
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns firstRead
        coEvery { warrantyTrackerRepository.promoteReviewDraft(96L, capture(editSlot)) } returns
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(96L, WarrantyStatus.ACTIVE)
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) } returns null

        useCase.createWarrantyForReview(
            receiptId,
            WarrantyExtractionData(
                productName = "Extracted Product",
                merchantName = "Extracted Merchant",
                purchaseDate = purchase,
                warrantyDurationMonths = 12,
                warrantyEndDate = purchase + 5_000L,
                supportPhone = "+00 extracted-phone",
                confidence = 0.8
            )
        )

        val edited = editSlot.captured.invoke(fresh)
        assertEquals("Extracted Product", edited.productName)
        assertEquals("Extracted Merchant", edited.merchantName)
        assertEquals(purchase, edited.purchaseDate)
        assertEquals(12, edited.warrantyDurationMonths)
        assertEquals(purchase + 5_000L, edited.warrantyEndDate)
        assertEquals("+00 extracted-phone", edited.supportPhone)
    }

    @Test
    fun `createWarrantyForReview keeps an expired unconfirmed draft expired via confirmWarranty and never writes ACTIVE`() = runTest {
        // F-AI-1: confirmWarranty owns the past-end rule (never EXPIRED -> ACTIVE); the use case only
        // reports the repository outcome and never writes a promoted status itself.
        val receiptId = 2002L
        val expiredDraft = Warranty(
            id = 93L,
            receiptId = receiptId,
            productName = "Old Kettle",
            merchantName = "Corner Shop",
            purchaseDate = FIXED_NOW - 400L * 86_400_000L,
            warrantyDurationMonths = 12,
            warrantyEndDate = FIXED_NOW - 35L * 86_400_000L,
            status = WarrantyStatus.EXPIRED,
            needsReview = true
        )
        val editSlot = slot<(Warranty) -> Warranty>()
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns expiredDraft
        coEvery { warrantyTrackerRepository.promoteReviewDraft(93L, capture(editSlot)) } returns
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(93L, WarrantyStatus.EXPIRED)
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) } returns null

        val result = useCase.createWarrantyForReview(
            receiptId,
            WarrantyExtractionData(
                productName = "Old Kettle",
                merchantName = "Corner Shop",
                purchaseDate = expiredDraft.purchaseDate,
                warrantyDurationMonths = 12,
                warrantyEndDate = expiredDraft.warrantyEndDate,
                confidence = 0.9
            )
        )

        assertTrue(result is WarrantyCreationResult.Success)
        // The edit lambda never promotes: status / needsReview come from the fresh row untouched.
        val edited = editSlot.captured.invoke(expiredDraft.copy())
        assertEquals(WarrantyStatus.EXPIRED, edited.status)
        assertEquals(true, edited.needsReview)
        coVerify(exactly = 1) { warrantyTrackerRepository.promoteReviewDraft(93L, any()) }
        coVerify(exactly = 0) { warrantyTrackerRepository.updateWarranty(any()) }
        coVerify(exactly = 0) { warrantyTrackerRepository.confirmWarranty(any()) }
    }

    @Test
    fun `createWarrantyForReview reports a failure and no return window when confirmWarranty finds no row`() = runTest {
        val receiptId = 2003L
        val draft = Warranty(
            id = 94L,
            receiptId = receiptId,
            productName = "Lamp",
            merchantName = "Shop",
            purchaseDate = FIXED_NOW - 1_000L,
            warrantyDurationMonths = 12,
            warrantyEndDate = FIXED_NOW + 10_000L,
            status = WarrantyStatus.PENDING_REVIEW,
            needsReview = true
        )
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returns draft
        coEvery { warrantyTrackerRepository.promoteReviewDraft(94L, any()) } returns
            WarrantyTrackerRepository.ConfirmWarrantyResult.NotFound

        val result = useCase.createWarrantyForReview(
            receiptId,
            WarrantyExtractionData(
                productName = "Lamp",
                merchantName = "Shop",
                purchaseDate = null,
                warrantyDurationMonths = null,
                warrantyEndDate = null,
                confidence = 0.9
            )
        )

        assertTrue(result is WarrantyCreationResult.Failure)
        assertEquals("REVIEW_CONFIRM_FAILED", (result as WarrantyCreationResult.Failure).error)
        coVerify(exactly = 0) { warrantyTrackerRepository.upsertReturnWindowForReceipt(any(), any()) }
        coVerify(exactly = 0) { warrantyTrackerRepository.updateWarranty(any()) }
        coVerify(exactly = 0) { warrantyTrackerRepository.confirmWarranty(any()) }
    }

    @Test
    fun `createWarrantyForReview maps AlreadyConfirmed to AlreadyExists and still upserts the return window from the re-read row`() = runTest {
        val receiptId = 2006L
        val firstRead = Warranty(
            id = 97L,
            receiptId = receiptId,
            productName = "Router",
            merchantName = "Net Shop",
            purchaseDate = FIXED_NOW - 1_000L,
            warrantyDurationMonths = 12,
            warrantyEndDate = FIXED_NOW + 10_000L,
            status = WarrantyStatus.PENDING_REVIEW,
            needsReview = true
        )
        // What is persisted after a concurrent claim: a distinct object from the first read.
        val reRead = firstRead.copy(status = WarrantyStatus.CLAIMED, needsReview = false, claimedAt = FIXED_NOW - 500L)
        val windowRow = slot<Warranty>()
        coEvery { warrantyTrackerRepository.getWarrantyByReceiptId(receiptId) } returnsMany listOf(firstRead, reRead)
        coEvery { warrantyTrackerRepository.promoteReviewDraft(97L, any()) } returns
            WarrantyTrackerRepository.ConfirmWarrantyResult.AlreadyConfirmed
        coEvery { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, capture(windowRow)) } returns null

        val result = useCase.createWarrantyForReview(
            receiptId,
            WarrantyExtractionData(
                productName = "Router",
                merchantName = "Net Shop",
                purchaseDate = firstRead.purchaseDate,
                warrantyDurationMonths = 12,
                warrantyEndDate = firstRead.warrantyEndDate,
                confidence = 0.9
            )
        )

        assertTrue(result is WarrantyCreationResult.AlreadyExists)
        assertEquals(97L, (result as WarrantyCreationResult.AlreadyExists).existingWarrantyId)
        coVerify(exactly = 1) { warrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, any()) }
        assertSame(reRead, windowRow.captured)
        assertTrue(windowRow.captured !== firstRead)
        coVerify(exactly = 1) { warrantyTrackerRepository.promoteReviewDraft(97L, any()) }
        coVerify(exactly = 0) { warrantyTrackerRepository.updateWarranty(any()) }
        coVerify(exactly = 0) { warrantyTrackerRepository.confirmWarranty(any()) }
        coVerify(exactly = 0) { warrantyTrackerRepository.addWarrantyIgnoreConflicts(any()) }
    }

    private fun recentDate(): String {
        val cal = Calendar.getInstance().apply {
            // Keep the relative-date fixture aligned with the use case's injected clock.
            timeInMillis = FIXED_NOW
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
