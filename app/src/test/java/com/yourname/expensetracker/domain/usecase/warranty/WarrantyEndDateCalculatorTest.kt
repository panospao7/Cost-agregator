package com.yourname.expensetracker.domain.usecase.warranty

import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.data.repository.WarrantyTrackerRepository
import com.yourname.expensetracker.domain.receipt.WarrantyExtractionData
import com.yourname.expensetracker.domain.receipt.WarrantyTextExtractor
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.GlobalTimeZoneTestLock
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.TimeZone

/**
 * warranty-edit S1: INV-1 (one pure end-date function, AE-1 table and parity with the receipt-path
 * implementations through their public outputs) and the pure date part of INV-2 (AE-2).
 *
 * Expected values are built from calendar-date literals with java.time, never from the function
 * under test. The default TimeZone and Locale are pinned for every test and restored afterwards;
 * the process-wide zone lock serializes this class against other zone-mutating tests.
 */
class WarrantyEndDateCalculatorTest {

    private lateinit var originalZone: TimeZone
    private lateinit var originalLocale: Locale

    @Before
    fun pinZoneAndLocale() {
        GlobalTimeZoneTestLock.acquire()
        originalZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(ATHENS))
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreZoneAndLocale() {
        try {
            TimeZone.setDefault(originalZone)
            Locale.setDefault(originalLocale)
        } finally {
            GlobalTimeZoneTestLock.release()
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private fun <T> inZone(zoneId: String, block: () -> T): T {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId))
        try {
            return block()
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    /** 00:00 local (current default zone) of [date], as epoch millis. */
    private fun midnight(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** [hour]:[minute]:[second].[millis] local on [date], as epoch millis. */
    private fun at(date: LocalDate, hour: Int, minute: Int = 0, second: Int = 0, millis: Int = 0): Long =
        date.atTime(hour, minute, second, millis * 1_000_000)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun localDateOf(epochMillis: Long): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

    // ---- INV-1 / AE-1: the table ----------------------------------------------------------------

    @Test
    fun `INV-1 AE-1 Jan 31 plus 1 month ends Mar 1 at local midnight and displays Feb 28`() {
        val purchase = midnight(LocalDate.of(2026, 1, 31))

        val end = WarrantyEndDate.exclusiveEnd(purchase, 1)

        assertEquals(midnight(LocalDate.of(2026, 3, 1)), end)
        // Half-open: the last covered instant is on Feb 28, the first excluded instant is Mar 1 00:00.
        assertEquals(LocalDate.of(2026, 2, 28), localDateOf(end - 1L))
    }

    @Test
    fun `INV-1 AE-1 leap day 2028-02-29 plus 12 months ends 2029-03-01 at local midnight`() {
        val purchase = midnight(LocalDate.of(2028, 2, 29))

        val end = WarrantyEndDate.exclusiveEnd(purchase, 12)

        assertEquals(midnight(LocalDate.of(2029, 3, 1)), end)
        assertEquals(LocalDate.of(2029, 2, 28), localDateOf(end - 1L))
    }

    @Test
    fun `INV-1 AE-1 DST start 2026-03-28 plus 1 month in Athens ends 2026-04-29 at local midnight`() {
        // Europe/Athens switches to summer time on 2026-03-29.
        val purchase = midnight(LocalDate.of(2026, 3, 28))

        val end = WarrantyEndDate.exclusiveEnd(purchase, 1)

        assertEquals(midnight(LocalDate.of(2026, 4, 29)), end)
        assertEquals(LocalDate.of(2026, 4, 28), localDateOf(end - 1L))
        assertEquals(LocalDate.of(2026, 4, 29), localDateOf(end))
    }

    @Test
    fun `INV-1 DST start 2026-03-08 plus 1 month in New York ends 2026-04-09 at local midnight`() {
        inZone("America/New_York") {
            // Purchase one month before the US spring-forward day; the end day is after it.
            val purchase = midnight(LocalDate.of(2026, 2, 8))
            val end = WarrantyEndDate.exclusiveEnd(purchase, 1)
            assertEquals(midnight(LocalDate.of(2026, 3, 9)), end)

            // Purchase on the spring-forward day itself (a 23 hour day).
            val dstDay = midnight(LocalDate.of(2026, 3, 8))
            assertEquals(midnight(LocalDate.of(2026, 4, 9)), WarrantyEndDate.exclusiveEnd(dstDay, 1))
        }
    }

    @Test
    fun `INV-1 purchase on the Athens DST day and across the autumn DST end resolve to local midnights`() {
        // Purchase on 2026-03-29 (23 hour day) + 1 month.
        assertEquals(
            midnight(LocalDate.of(2026, 4, 30)),
            WarrantyEndDate.exclusiveEnd(midnight(LocalDate.of(2026, 3, 29)), 1)
        )
        // Autumn: Athens leaves summer time on 2026-10-25 (25 hour day).
        assertEquals(
            midnight(LocalDate.of(2026, 10, 27)),
            WarrantyEndDate.exclusiveEnd(midnight(LocalDate.of(2026, 9, 26)), 1)
        )
        assertEquals(
            midnight(LocalDate.of(2026, 11, 26)),
            WarrantyEndDate.exclusiveEnd(midnight(LocalDate.of(2026, 10, 25)), 1)
        )
    }

    @Test
    fun `INV-1 normal control 2026-05-10 plus 12 months ends 2027-05-11 at local midnight`() {
        val end = WarrantyEndDate.exclusiveEnd(midnight(LocalDate.of(2026, 5, 10)), 12)

        assertEquals(midnight(LocalDate.of(2027, 5, 11)), end)
    }

    @Test
    fun `INV-1 maximum duration 120 months from 2026-01-31 ends 2036-02-01 at local midnight`() {
        val end = WarrantyEndDate.exclusiveEnd(midnight(LocalDate.of(2026, 1, 31)), 120)

        assertEquals(midnight(LocalDate.of(2036, 2, 1)), end)
    }

    @Test
    fun `INV-1 any instant on the purchase local day gives the same end`() {
        val day = LocalDate.of(2026, 1, 31)
        val expected = midnight(LocalDate.of(2026, 3, 1))

        assertEquals(expected, WarrantyEndDate.exclusiveEnd(midnight(day), 1))
        assertEquals(expected, WarrantyEndDate.exclusiveEnd(at(day, 12, 30), 1))
        assertEquals(expected, WarrantyEndDate.exclusiveEnd(at(day, 23, 59, 59, 999), 1))
    }

    @Test
    fun `INV-1 the instant just before local midnight belongs to the previous purchase day`() {
        val justBefore = at(LocalDate.of(2026, 1, 30), 23, 59, 59, 999)
        val midnightJan31 = midnight(LocalDate.of(2026, 1, 31))

        // A 2 month duration separates the two days: Jan 30 -> Mar 30 (end Mar 31 00:00),
        // Jan 31 -> Mar 31 (end Apr 1 00:00).
        assertEquals(midnight(LocalDate.of(2026, 3, 31)), WarrantyEndDate.exclusiveEnd(justBefore, 2))
        assertEquals(midnight(LocalDate.of(2026, 4, 1)), WarrantyEndDate.exclusiveEnd(midnightJan31, 2))
    }

    // ---- INV-1 / AE-1: parity with the receipt-path implementations (public outputs only) -------

    private fun extractWith(date: LocalDate, months: Int): WarrantyExtractionData {
        val text = "DATE: ${date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.US))}\n" +
            "WARRANTY: $months MONTH${if (months == 1) "" else "S"}"
        return WarrantyTextExtractor(FakeTimeProvider(EXTRACTOR_NOW)).extract(text)
    }

    private fun assertExtractorParity(date: LocalDate, months: Int, expectedEnd: LocalDate) {
        val extracted = extractWith(date, months)

        // Guard against a vacuous comparison: the extractor must have read the intended inputs.
        assertEquals(midnight(date), extracted.purchaseDate)
        assertEquals(months, extracted.warrantyDurationMonths)
        assertNotNull(extracted.warrantyEndDate)

        assertEquals(midnight(expectedEnd), extracted.warrantyEndDate)
        assertEquals(
            extracted.warrantyEndDate,
            WarrantyEndDate.exclusiveEnd(extracted.purchaseDate!!, extracted.warrantyDurationMonths!!)
        )
    }

    @Test
    fun `INV-1 AE-1 parity with extractor output for Jan 31 plus 1 month`() {
        assertExtractorParity(LocalDate.of(2026, 1, 31), 1, LocalDate.of(2026, 3, 1))
    }

    @Test
    fun `INV-1 AE-1 parity with extractor output for leap day plus 12 months`() {
        assertExtractorParity(LocalDate.of(2028, 2, 29), 12, LocalDate.of(2029, 3, 1))
    }

    @Test
    fun `INV-1 AE-1 parity with extractor output for the DST day 2026-03-28 plus 1 month`() {
        assertExtractorParity(LocalDate.of(2026, 3, 28), 1, LocalDate.of(2026, 4, 29))
    }

    @Test
    fun `INV-1 parity with extractor output holds in New York and UTC for the same table`() {
        for (zone in listOf("America/New_York", "UTC")) {
            inZone(zone) {
                assertExtractorParity(LocalDate.of(2026, 1, 31), 1, LocalDate.of(2026, 3, 1))
                assertExtractorParity(LocalDate.of(2028, 2, 29), 12, LocalDate.of(2029, 3, 1))
                assertExtractorParity(LocalDate.of(2026, 3, 8), 1, LocalDate.of(2026, 4, 9))
            }
        }
    }

    /**
     * The use case's end-date function is private; its public effect is the end date it puts on the
     * freshly read draft row when the extraction carries no end date (the review promotion path).
     */
    private suspend fun useCaseEndDate(purchase: Long, months: Int): Long {
        val receiptId = 3001L
        val repository = mockk<WarrantyTrackerRepository>()
        val receipts = mockk<ReceiptRepository>(relaxed = true)
        val draft = Warranty(
            id = 41L,
            receiptId = receiptId,
            productName = "Parity Item",
            merchantName = "Parity Store",
            purchaseDate = purchase,
            warrantyDurationMonths = months,
            warrantyEndDate = purchase + 1_000L,
            status = WarrantyStatus.PENDING_REVIEW,
            needsReview = true
        )
        val editSlot = slot<(Warranty) -> Warranty>()
        coEvery { receipts.getReceiptById(any()) } returns null
        coEvery { repository.getWarrantyByReceiptId(receiptId) } returns draft
        coEvery { repository.promoteReviewDraft(41L, capture(editSlot)) } returns
            WarrantyTrackerRepository.ConfirmWarrantyResult.Confirmed(41L, WarrantyStatus.ACTIVE)
        coEvery { repository.upsertReturnWindowForReceipt(receiptId, any()) } returns null

        AutoCreateWarrantyFromReceiptUseCase(repository, receipts, FakeTimeProvider(EXTRACTOR_NOW))
            .createWarrantyForReview(
                receiptId,
                WarrantyExtractionData(
                    productName = "Parity Item",
                    merchantName = "Parity Store",
                    purchaseDate = purchase,
                    warrantyDurationMonths = months,
                    warrantyEndDate = null,
                    confidence = 0.9
                )
            )

        return editSlot.captured.invoke(draft.copy()).warrantyEndDate
    }

    private fun assertUseCaseParity(date: LocalDate, months: Int, expectedEnd: LocalDate) = runTest {
        val purchase = midnight(date)

        val useCaseEnd = useCaseEndDate(purchase, months)

        assertEquals(midnight(expectedEnd), useCaseEnd)
        assertEquals(useCaseEnd, WarrantyEndDate.exclusiveEnd(purchase, months))
    }

    @Test
    fun `INV-1 AE-1 parity with use case output for Jan 31 plus 1 month`() {
        assertUseCaseParity(LocalDate.of(2026, 1, 31), 1, LocalDate.of(2026, 3, 1))
    }

    @Test
    fun `INV-1 AE-1 parity with use case output for leap day plus 12 months`() {
        assertUseCaseParity(LocalDate.of(2028, 2, 29), 12, LocalDate.of(2029, 3, 1))
    }

    @Test
    fun `INV-1 AE-1 parity with use case output for the DST day 2026-03-28 plus 1 month`() {
        assertUseCaseParity(LocalDate.of(2026, 3, 28), 1, LocalDate.of(2026, 4, 29))
    }

    @Test
    fun `INV-1 parity with use case output holds in New York and UTC for the same table`() {
        for (zone in listOf("America/New_York", "UTC")) {
            inZone(zone) {
                assertUseCaseParity(LocalDate.of(2026, 1, 31), 1, LocalDate.of(2026, 3, 1))
                assertUseCaseParity(LocalDate.of(2028, 2, 29), 12, LocalDate.of(2029, 3, 1))
                assertUseCaseParity(LocalDate.of(2026, 3, 8), 1, LocalDate.of(2026, 4, 9))
            }
        }
    }

    // ---- INV-2 / AE-2: resolveEditDates (pure date part) ----------------------------------------

    // Stored fixture: purchase at 17:45 local on [storedDay] (not midnight), 12 months, and an end that is
    // NOT the exclusive end of that purchase and duration (a legacy / non-canonical row).
    private val storedDay = LocalDate.of(2026, 5, 10)
    private val storedMonths = 12
    private val nonCanonicalEnd = 987_654_321L

    private fun storedPurchase(): Long = at(storedDay, 17, 45)

    @Test
    fun `INV-2 AE-2 same local day and same months return the stored purchase and end untouched`() {
        val stored = storedPurchase()

        val resolved = WarrantyEndDate.resolveEditDates(
            storedPurchase = stored,
            storedEnd = nonCanonicalEnd,
            storedMonths = storedMonths,
            chosenPurchase = midnight(storedDay),
            chosenMonths = storedMonths
        )

        assertEquals(WarrantyEndDate.ResolvedDates(stored, nonCanonicalEnd, false), resolved)
    }

    @Test
    fun `INV-2 AE-2 any instant on the stored local day keeps dates untouched`() {
        val stored = storedPurchase()
        for (chosen in listOf(midnight(storedDay), at(storedDay, 9), at(storedDay, 23, 59, 59, 999), stored)) {
            val resolved = WarrantyEndDate.resolveEditDates(stored, nonCanonicalEnd, storedMonths, chosen, storedMonths)

            assertEquals(WarrantyEndDate.ResolvedDates(stored, nonCanonicalEnd, false), resolved)
        }
    }

    @Test
    fun `INV-2 AE-2 a canonical stored row with same day and months is also untouched`() {
        val stored = midnight(storedDay)
        val canonicalEnd = midnight(LocalDate.of(2027, 5, 11))

        val resolved = WarrantyEndDate.resolveEditDates(stored, canonicalEnd, storedMonths, stored, storedMonths)

        assertEquals(WarrantyEndDate.ResolvedDates(stored, canonicalEnd, false), resolved)
    }

    @Test
    fun `INV-2 AE-2 picker day equal to the stored day in a different current zone keeps dates untouched`() {
        // Stored as Athens local midnight 2026-05-10 = 2026-05-09T21:00Z, i.e. 17:00 on 2026-05-09 in New York.
        val storedInAthens = midnight(storedDay)

        inZone("America/New_York") {
            val pickerDay = LocalDate.of(2026, 5, 9)

            val resolved = WarrantyEndDate.resolveEditDates(
                storedPurchase = storedInAthens,
                storedEnd = nonCanonicalEnd,
                storedMonths = storedMonths,
                chosenPurchase = midnight(pickerDay),
                chosenMonths = storedMonths
            )

            assertEquals(WarrantyEndDate.ResolvedDates(storedInAthens, nonCanonicalEnd, false), resolved)
        }
    }

    @Test
    fun `INV-2 AE-2 a changed local day recomputes purchase to local midnight and end from the new day`() {
        val newDay = LocalDate.of(2026, 5, 11)

        val resolved = WarrantyEndDate.resolveEditDates(
            storedPurchase = storedPurchase(),
            storedEnd = nonCanonicalEnd,
            storedMonths = storedMonths,
            chosenPurchase = at(newDay, 14),
            chosenMonths = storedMonths
        )

        assertEquals(midnight(newDay), resolved.purchaseDate)
        assertEquals(midnight(LocalDate.of(2027, 5, 12)), resolved.warrantyEndDate)
        assertEquals(true, resolved.endChanged)
    }

    @Test
    fun `INV-2 AE-2 the previous day by one millisecond counts as a changed day`() {
        val previousDay = LocalDate.of(2026, 5, 9)

        val resolved = WarrantyEndDate.resolveEditDates(
            storedPurchase = midnight(storedDay),
            storedEnd = nonCanonicalEnd,
            storedMonths = storedMonths,
            chosenPurchase = at(previousDay, 23, 59, 59, 999),
            chosenMonths = storedMonths
        )

        assertEquals(midnight(previousDay), resolved.purchaseDate)
        assertEquals(midnight(LocalDate.of(2027, 5, 10)), resolved.warrantyEndDate)
        assertEquals(true, resolved.endChanged)
    }

    @Test
    fun `INV-2 AE-2 changed months with the same day recompute the end and normalize purchase to midnight`() {
        val resolved = WarrantyEndDate.resolveEditDates(
            storedPurchase = storedPurchase(),
            storedEnd = nonCanonicalEnd,
            storedMonths = storedMonths,
            chosenPurchase = midnight(storedDay),
            chosenMonths = 24
        )

        assertEquals(midnight(storedDay), resolved.purchaseDate)
        assertEquals(midnight(LocalDate.of(2028, 5, 11)), resolved.warrantyEndDate)
        assertEquals(true, resolved.endChanged)
    }

    @Test
    fun `INV-2 AE-2 changed months on a canonical row yield a different end and endChanged true`() {
        val stored = midnight(storedDay)
        val canonicalEnd = midnight(LocalDate.of(2027, 5, 11))

        val resolved = WarrantyEndDate.resolveEditDates(stored, canonicalEnd, 12, stored, 6)

        assertEquals(midnight(LocalDate.of(2026, 11, 11)), resolved.warrantyEndDate)
        assertNotEquals(canonicalEnd, resolved.warrantyEndDate)
        assertEquals(true, resolved.endChanged)
    }

    @Test
    fun `INV-2 AE-2 endChanged is false when a changed day happens to produce the same end`() {
        // Jan 30 + 1 month and Jan 31 + 1 month both clamp to Feb 28, so both end at Mar 1 00:00.
        val storedJan31 = midnight(LocalDate.of(2026, 1, 31))
        val canonicalEnd = midnight(LocalDate.of(2026, 3, 1))
        val jan30 = midnight(LocalDate.of(2026, 1, 30))

        val resolved = WarrantyEndDate.resolveEditDates(storedJan31, canonicalEnd, 1, jan30, 1)

        assertEquals(jan30, resolved.purchaseDate)
        assertEquals(canonicalEnd, resolved.warrantyEndDate)
        assertEquals(false, resolved.endChanged)
    }

    @Test
    fun `INV-2 AE-2 endChanged is true when the recomputed end differs from a non-canonical stored end`() {
        // Same day and months would be untouched, but a changed day recomputes and replaces the odd stored end.
        val resolved = WarrantyEndDate.resolveEditDates(
            storedPurchase = storedPurchase(),
            storedEnd = nonCanonicalEnd,
            storedMonths = storedMonths,
            chosenPurchase = midnight(LocalDate.of(2026, 5, 20)),
            chosenMonths = storedMonths
        )

        assertNotEquals(nonCanonicalEnd, resolved.warrantyEndDate)
        assertEquals(true, resolved.endChanged)
    }

    @Test
    fun `INV-2 recomputed end equals the single exclusive end function result`() {
        val chosen = at(LocalDate.of(2028, 2, 29), 10)

        val resolved = WarrantyEndDate.resolveEditDates(storedPurchase(), nonCanonicalEnd, storedMonths, chosen, 12)

        assertEquals(midnight(LocalDate.of(2029, 3, 1)), resolved.warrantyEndDate)
        assertEquals(WarrantyEndDate.exclusiveEnd(chosen, 12), resolved.warrantyEndDate)
        assertEquals(midnight(LocalDate.of(2028, 2, 29)), resolved.purchaseDate)
    }

    @Test
    fun `INV-2 a stored day that differs only because the current zone changed is not drifted by months-equal edits`() {
        // Stored 2026-05-10 17:45 Athens is 2026-05-11 02:45 in Pacific/Auckland, a different local day.
        // Choosing the Auckland day of that same instant must not move the stored values.
        val stored = storedPurchase()
        inZone("Pacific/Auckland") {
            val aucklandDay = localDateOf(stored)

            val resolved = WarrantyEndDate.resolveEditDates(
                stored, nonCanonicalEnd, storedMonths, midnight(aucklandDay), storedMonths
            )

            assertEquals(WarrantyEndDate.ResolvedDates(stored, nonCanonicalEnd, false), resolved)
        }
    }

    private companion object {
        const val ATHENS = "Europe/Athens"

        /** 2030-01-01T00:00Z: well after every purchase date used with the extractor, within its 50 year window. */
        val EXTRACTOR_NOW: Long =
            LocalDate.of(2030, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }
}
