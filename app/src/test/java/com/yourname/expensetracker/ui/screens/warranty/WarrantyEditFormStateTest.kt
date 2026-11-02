package com.yourname.expensetracker.ui.screens.warranty

import com.yourname.expensetracker.data.database.entity.Warranty
import com.yourname.expensetracker.data.database.entity.WarrantyStatus
import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyEditError
import com.yourname.expensetracker.domain.usecase.warranty.WarrantyReviewState
import com.yourname.expensetracker.domain.util.GlobalTimeZoneTestLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/**
 * warranty-edit S4: the pure logic behind the shared add/edit form and the card action enablement.
 * INV-2 (preview shows the stored end when day and months are unchanged), INV-4 (validator is the
 * single owner of the rules, field errors), INV-14 (drafts carry no logging surface; bounded
 * reasons only), INV-15 (save is blocked while saving), INV-21 / AE-22 (card enablement), AE-18
 * (the clock is a parameter), AE-7 (CLAIMED / TRANSFERRED offer no edit entry).
 *
 * The default TimeZone and Locale are pinned (Europe/Athens, US) for every test and restored
 * afterwards. All expected instants are written out as calendar dates, never computed with the
 * production helpers. All data is synthetic.
 */
class WarrantyEditFormStateTest {

    private lateinit var originalZone: TimeZone
    private lateinit var originalLocale: Locale

    private val athens: ZoneId = ZoneId.of("Europe/Athens")

    @Before
    fun pinZoneAndLocale() {
        GlobalTimeZoneTestLock.acquire()
        originalZone = TimeZone.getDefault()
        originalLocale = Locale.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Athens"))
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

    private fun local(
        year: Int,
        month: Int,
        day: Int,
        hour: Int = 0,
        minute: Int = 0,
        zone: ZoneId = athens
    ): Long = LocalDate.of(year, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun utcMidnight(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** 2026-11-02 12:00 Athens. */
    private val now = local(2026, 11, 2, 12)

    private fun draft(
        product: String = "Pixel 9",
        merchant: String = "Contoso",
        purchase: Long = local(2026, 10, 26),
        months: String = "24",
        type: WarrantyType = WarrantyType.EXTENDED,
        phone: String = "+30 6900000000"
    ) = WarrantyDraft(product, merchant, purchase, months, type, phone)

    private fun warranty(
        purchase: Long = local(2026, 1, 15),
        months: Int = 24,
        end: Long = local(2028, 1, 16),
        status: WarrantyStatus = WarrantyStatus.ACTIVE,
        needsReview: Boolean = false,
        phone: String? = "+30 6900000000"
    ) = Warranty(
        receiptId = null,
        productName = "Pixel 9",
        merchantName = "Contoso",
        purchaseDate = purchase,
        warrantyDurationMonths = months,
        warrantyEndDate = end,
        warrantyType = WarrantyType.EXTENDED,
        supportPhone = phone,
        status = status,
        needsReview = needsReview
    )

    private fun shown(endExclusive: Long, isPast: Boolean) =
        WarrantyCoveragePreview.Shown(endExclusive, endExclusive - 1, isPast)

    private val futurePreview = shown(now + 1_000L, isPast = false)
    private val pastPreview = shown(now - 1_000L, isPast = true)

    // ---- isDirty (INV-10 value-equality used by the dirty flag) -------------------------------------

    @Test
    fun `isDirty is false for a pristine draft`() {
        assertFalse(WarrantyEditFormState.isDirty(draft(), draft()))
    }

    @Test
    fun `isDirty is true when the product name changes`() {
        assertTrue(WarrantyEditFormState.isDirty(draft(), draft(product = "Pixel 10")))
    }

    @Test
    fun `isDirty is true when the merchant name changes`() {
        assertTrue(WarrantyEditFormState.isDirty(draft(), draft(merchant = "Fabrikam")))
    }

    @Test
    fun `isDirty is true when the purchase day changes`() {
        assertTrue(WarrantyEditFormState.isDirty(draft(), draft(purchase = local(2026, 10, 27))))
    }

    @Test
    fun `isDirty is true when the months change`() {
        assertTrue(WarrantyEditFormState.isDirty(draft(), draft(months = "25")))
    }

    @Test
    fun `isDirty is true when the warranty type changes`() {
        assertTrue(WarrantyEditFormState.isDirty(draft(), draft(type = WarrantyType.STORE)))
    }

    @Test
    fun `isDirty is true when the support phone changes`() {
        assertTrue(WarrantyEditFormState.isDirty(draft(), draft(phone = "+30 6911111111")))
    }

    @Test
    fun `isDirty is true when a blank phone becomes non blank`() {
        assertTrue(WarrantyEditFormState.isDirty(draft(phone = ""), draft(phone = "+30 6900000000")))
    }

    @Test
    fun `isDirty returns to false when a changed field is changed back`() {
        val initial = draft()
        val edited = initial.copy(productName = "Pixel 10")
        assertTrue(WarrantyEditFormState.isDirty(initial, edited))
        val restored = edited.copy(productName = "Pixel 9")
        assertFalse(WarrantyEditFormState.isDirty(initial, restored))
    }

    @Test
    fun `isDirty ignores whitespace only edits and a different time on the same purchase day`() {
        val initial = draft()
        assertFalse(WarrantyEditFormState.isDirty(initial, initial.copy(productName = "  Pixel 9  ")))
        assertFalse(WarrantyEditFormState.isDirty(initial, initial.copy(merchantName = "Contoso ")))
        assertFalse(WarrantyEditFormState.isDirty(initial, initial.copy(supportPhone = " +30 6900000000 ")))
        assertFalse(
            WarrantyEditFormState.isDirty(initial, initial.copy(purchaseDateMillis = local(2026, 10, 26, 17, 45)))
        )
    }

    @Test
    fun `isDirty treats a blank phone as equal to an empty phone`() {
        assertFalse(WarrantyEditFormState.isDirty(draft(phone = ""), draft(phone = "   ")))
    }

    @Test
    fun `isDirty compares months as numbers`() {
        assertFalse(WarrantyEditFormState.isDirty(draft(months = "24"), draft(months = "0024")))
    }

    @Test
    fun `isDirty in add mode is false at the defaults and true after any field is typed`() {
        val initial = WarrantyEditFormState.initialAddDraft(now)
        assertFalse(WarrantyEditFormState.isDirty(initial, initial))
        assertTrue(WarrantyEditFormState.isDirty(initial, initial.copy(productName = "Pixel 9")))
        assertTrue(WarrantyEditFormState.isDirty(initial, initial.copy(durationMonthsText = "12")))
    }

    // ---- fieldErrors (INV-4) ---------------------------------------------------------------------------

    @Test
    fun `fieldErrors is empty for a valid draft and validate agrees`() {
        assertTrue(WarrantyEditFormState.fieldErrors(draft(), now).isEmpty())
        assertNull(WarrantyEditFormState.validate(draft(), now))
    }

    @Test
    fun `fieldErrors reports blank product and merchant on their own fields`() {
        assertEquals(
            mapOf(WarrantyFormField.PRODUCT to WarrantyEditError.PRODUCT_BLANK),
            WarrantyEditFormState.fieldErrors(draft(product = "   "), now)
        )
        assertEquals(
            mapOf(WarrantyFormField.MERCHANT to WarrantyEditError.MERCHANT_BLANK),
            WarrantyEditFormState.fieldErrors(draft(merchant = ""), now)
        )
    }

    @Test
    fun `fieldErrors name length boundary is 120 trimmed characters`() {
        val ok = "a".repeat(120)
        val tooLong = "a".repeat(121)
        assertTrue(WarrantyEditFormState.fieldErrors(draft(product = "  $ok  "), now).isEmpty())
        assertEquals(
            mapOf(WarrantyFormField.PRODUCT to WarrantyEditError.NAME_TOO_LONG),
            WarrantyEditFormState.fieldErrors(draft(product = tooLong), now)
        )
        assertEquals(
            mapOf(WarrantyFormField.MERCHANT to WarrantyEditError.NAME_TOO_LONG),
            WarrantyEditFormState.fieldErrors(draft(merchant = tooLong), now)
        )
    }

    @Test
    fun `fieldErrors phone length boundary is 40 trimmed characters`() {
        assertTrue(WarrantyEditFormState.fieldErrors(draft(phone = "9".repeat(40)), now).isEmpty())
        assertTrue(WarrantyEditFormState.fieldErrors(draft(phone = " " + "9".repeat(40) + " "), now).isEmpty())
        assertEquals(
            mapOf(WarrantyFormField.PHONE to WarrantyEditError.PHONE_TOO_LONG),
            WarrantyEditFormState.fieldErrors(draft(phone = "9".repeat(41)), now)
        )
    }

    @Test
    fun `fieldErrors duration boundaries are 1 to 120 and blank or overflow is out of range`() {
        assertTrue(WarrantyEditFormState.fieldErrors(draft(months = "1"), now).isEmpty())
        assertTrue(WarrantyEditFormState.fieldErrors(draft(months = "120"), now).isEmpty())
        val expected = mapOf(WarrantyFormField.DURATION to WarrantyEditError.DURATION_OUT_OF_RANGE)
        for (bad in listOf("0", "121", "", "99999999999")) {
            assertEquals("months text '$bad'", expected, WarrantyEditFormState.fieldErrors(draft(months = bad), now))
        }
    }

    @Test
    fun `fieldErrors purchase date boundaries are 1970-01-01 through today plus 365 days`() {
        val expected = mapOf(WarrantyFormField.PURCHASE_DATE to WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE)
        assertTrue(WarrantyEditFormState.fieldErrors(draft(purchase = local(1970, 1, 1)), now).isEmpty())
        assertEquals(expected, WarrantyEditFormState.fieldErrors(draft(purchase = local(1969, 12, 31)), now))
        assertTrue(WarrantyEditFormState.fieldErrors(draft(purchase = local(2027, 11, 2)), now).isEmpty())
        assertEquals(expected, WarrantyEditFormState.fieldErrors(draft(purchase = local(2027, 11, 3)), now))
        assertEquals(expected, WarrantyEditFormState.fieldErrors(draft(purchase = local(2100, 1, 1)), now))
    }

    @Test
    fun `fieldErrors reports every bad field at once and validate returns the first failure`() {
        val bad = draft(
            product = "",
            merchant = "",
            purchase = local(1969, 12, 31),
            months = "0",
            phone = "9".repeat(41)
        )
        assertEquals(
            mapOf(
                WarrantyFormField.PRODUCT to WarrantyEditError.PRODUCT_BLANK,
                WarrantyFormField.MERCHANT to WarrantyEditError.MERCHANT_BLANK,
                WarrantyFormField.PURCHASE_DATE to WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
                WarrantyFormField.DURATION to WarrantyEditError.DURATION_OUT_OF_RANGE,
                WarrantyFormField.PHONE to WarrantyEditError.PHONE_TOO_LONG
            ),
            WarrantyEditFormState.fieldErrors(bad, now)
        )
        assertEquals(WarrantyEditError.PRODUCT_BLANK, WarrantyEditFormState.validate(bad, now))
    }

    @Test
    fun `fieldErrors uses the passed clock and not the wall clock (AE-18)`() {
        val purchase = local(2026, 12, 1)
        // With "now" in Nov 2026 the day is in range; with "now" in 2020 it is more than 365 days ahead.
        assertTrue(WarrantyEditFormState.fieldErrors(draft(purchase = purchase), now).isEmpty())
        assertEquals(
            mapOf(WarrantyFormField.PURCHASE_DATE to WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE),
            WarrantyEditFormState.fieldErrors(draft(purchase = purchase), local(2020, 1, 1))
        )
    }

    // ---- fieldsForError ----------------------------------------------------------------------------------

    @Test
    fun `fieldsForError maps single field reasons to their field`() {
        val d = draft()
        assertEquals(setOf(WarrantyFormField.PRODUCT), WarrantyEditFormState.fieldsForError(WarrantyEditError.PRODUCT_BLANK, d))
        assertEquals(setOf(WarrantyFormField.MERCHANT), WarrantyEditFormState.fieldsForError(WarrantyEditError.MERCHANT_BLANK, d))
        assertEquals(setOf(WarrantyFormField.PHONE), WarrantyEditFormState.fieldsForError(WarrantyEditError.PHONE_TOO_LONG, d))
        assertEquals(
            setOf(WarrantyFormField.PURCHASE_DATE),
            WarrantyEditFormState.fieldsForError(WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE, d)
        )
        assertEquals(
            setOf(WarrantyFormField.DURATION),
            WarrantyEditFormState.fieldsForError(WarrantyEditError.DURATION_OUT_OF_RANGE, d)
        )
    }

    @Test
    fun `fieldsForError NAME_TOO_LONG marks whichever name is over 120`() {
        val tooLong = "a".repeat(121)
        assertEquals(
            setOf(WarrantyFormField.PRODUCT),
            WarrantyEditFormState.fieldsForError(WarrantyEditError.NAME_TOO_LONG, draft(product = tooLong))
        )
        assertEquals(
            setOf(WarrantyFormField.MERCHANT),
            WarrantyEditFormState.fieldsForError(WarrantyEditError.NAME_TOO_LONG, draft(merchant = tooLong))
        )
        assertEquals(
            setOf(WarrantyFormField.PRODUCT, WarrantyFormField.MERCHANT),
            WarrantyEditFormState.fieldsForError(
                WarrantyEditError.NAME_TOO_LONG,
                draft(product = tooLong, merchant = tooLong)
            )
        )
    }

    @Test
    fun `fieldsForError NAME_TOO_LONG is empty when neither name is over the limit so the generic banner shows`() {
        assertEquals(
            emptySet<WarrantyFormField>(),
            WarrantyEditFormState.fieldsForError(WarrantyEditError.NAME_TOO_LONG, draft(product = "a".repeat(120)))
        )
    }

    // ---- visibleErrors (touched gating) ---------------------------------------------------------------------

    @Test
    fun `visibleErrors shows only touched fields`() {
        val errors = mapOf(
            WarrantyFormField.PRODUCT to WarrantyEditError.PRODUCT_BLANK,
            WarrantyFormField.DURATION to WarrantyEditError.DURATION_OUT_OF_RANGE
        )
        assertEquals(
            mapOf(WarrantyFormField.PRODUCT to WarrantyEditError.PRODUCT_BLANK),
            WarrantyEditFormState.visibleErrors(errors, setOf(WarrantyFormField.PRODUCT))
        )
        assertEquals(errors, WarrantyEditFormState.visibleErrors(errors, WarrantyFormField.values().toSet()))
    }

    @Test
    fun `visibleErrors is empty when nothing is touched and ignores touched fields without an error`() {
        val errors = mapOf(WarrantyFormField.PRODUCT to WarrantyEditError.PRODUCT_BLANK)
        assertTrue(WarrantyEditFormState.visibleErrors(errors, emptySet()).isEmpty())
        assertTrue(WarrantyEditFormState.visibleErrors(errors, setOf(WarrantyFormField.PHONE)).isEmpty())
        assertTrue(WarrantyEditFormState.visibleErrors(emptyMap(), WarrantyFormField.values().toSet()).isEmpty())
    }

    // ---- isSaveEnabled (INV-8, INV-10, INV-15) ---------------------------------------------------------------

    @Test
    fun `isSaveEnabled is true for a valid dirty form that is not saving`() {
        assertTrue(WarrantyEditFormState.isSaveEnabled(valid = true, dirty = true, inReview = false, saving = false))
    }

    @Test
    fun `isSaveEnabled is false for a pristine row that is not in review`() {
        assertFalse(WarrantyEditFormState.isSaveEnabled(valid = true, dirty = false, inReview = false, saving = false))
    }

    @Test
    fun `isSaveEnabled is true for a pristine row in review (save and confirm)`() {
        assertTrue(WarrantyEditFormState.isSaveEnabled(valid = true, dirty = false, inReview = true, saving = false))
    }

    @Test
    fun `isSaveEnabled is false while the form is invalid even when dirty or in review`() {
        assertFalse(WarrantyEditFormState.isSaveEnabled(valid = false, dirty = true, inReview = false, saving = false))
        assertFalse(WarrantyEditFormState.isSaveEnabled(valid = false, dirty = false, inReview = true, saving = false))
        assertFalse(WarrantyEditFormState.isSaveEnabled(valid = false, dirty = true, inReview = true, saving = false))
    }

    @Test
    fun `isSaveEnabled is false while saving in every other state`() {
        for (dirty in listOf(true, false)) {
            for (inReview in listOf(true, false)) {
                assertFalse(
                    "dirty=$dirty inReview=$inReview",
                    WarrantyEditFormState.isSaveEnabled(valid = true, dirty = dirty, inReview = inReview, saving = true)
                )
            }
        }
    }

    // ---- primaryAction -----------------------------------------------------------------------------------------

    @Test
    fun `primaryAction is Save and confirm for the two review states in edit mode`() {
        assertEquals(
            WarrantyPrimaryAction.SAVE_AND_CONFIRM,
            WarrantyEditFormState.primaryAction(true, WarrantyReviewState.AWAITING_REVIEW)
        )
        assertEquals(
            WarrantyPrimaryAction.SAVE_AND_CONFIRM,
            WarrantyEditFormState.primaryAction(true, WarrantyReviewState.EXPIRED_UNCONFIRMED)
        )
    }

    @Test
    fun `primaryAction is Save for a confirmed row and for add mode`() {
        assertEquals(
            WarrantyPrimaryAction.SAVE,
            WarrantyEditFormState.primaryAction(true, WarrantyReviewState.NOT_IN_REVIEW)
        )
        assertEquals(
            WarrantyPrimaryAction.SAVE,
            WarrantyEditFormState.primaryAction(false, WarrantyReviewState.NOT_IN_REVIEW)
        )
        assertEquals(
            WarrantyPrimaryAction.SAVE,
            WarrantyEditFormState.primaryAction(false, WarrantyReviewState.AWAITING_REVIEW)
        )
    }

    @Test
    fun `primaryAction follows reviewStateOf of the live row and updates when the row leaves review`() {
        val state = WarrantyTrackerState(referenceNowMillis = now)
        val inReview = warranty(status = WarrantyStatus.PENDING_REVIEW, needsReview = true, end = now + 10_000L)
        val expiredUnconfirmed = warranty(status = WarrantyStatus.EXPIRED, needsReview = true, end = now - 10_000L)
        val confirmed = inReview.copy(needsReview = false, status = WarrantyStatus.ACTIVE)
        assertEquals(
            WarrantyPrimaryAction.SAVE_AND_CONFIRM,
            WarrantyEditFormState.primaryAction(true, state.reviewStateOf(inReview))
        )
        assertEquals(
            WarrantyPrimaryAction.SAVE_AND_CONFIRM,
            WarrantyEditFormState.primaryAction(true, state.reviewStateOf(expiredUnconfirmed))
        )
        assertEquals(
            WarrantyPrimaryAction.SAVE,
            WarrantyEditFormState.primaryAction(true, state.reviewStateOf(confirmed))
        )
    }

    // ---- coveragePreview (INV-1 arithmetic, INV-2 stored end, AE-18) ------------------------------------------

    @Test
    fun `coveragePreview in add mode uses exclusiveEnd of the chosen day and months`() {
        val preview = WarrantyEditFormState.coveragePreview(
            draft(purchase = local(2026, 10, 26), months = "24"), stored = null, nowMillis = now
        )
        // 2026-10-26 + 24 months = 2028-10-26 is the last covered day; exclusive end is the next midnight.
        assertEquals(shown(local(2028, 10, 27), isPast = false), preview)
        assertEquals(local(2028, 10, 27) - 1, (preview as WarrantyCoveragePreview.Shown).lastCoveredDay)
    }

    @Test
    fun `coveragePreview clamps month end dates`() {
        val preview = WarrantyEditFormState.coveragePreview(
            draft(purchase = local(2026, 1, 31), months = "1"), stored = null, nowMillis = now
        )
        // Jan 31 + 1 month lands on Feb 28 (2026 is not a leap year); exclusive end is Mar 1 00:00.
        assertEquals(local(2026, 3, 1), (preview as WarrantyCoveragePreview.Shown).endExclusive)
    }

    @Test
    fun `coveragePreview with unchanged day and months shows the STORED end even when it is non canonical (INV-2)`() {
        val storedEnd = local(2028, 3, 1) // not what exclusiveEnd(2026-01-15, 24) would give (2028-01-16)
        val stored = warranty(purchase = local(2026, 1, 15, 9), months = 24, end = storedEnd)
        val unchanged = draft(purchase = local(2026, 1, 15, 18), months = "24")
        assertEquals(shown(storedEnd, isPast = false), WarrantyEditFormState.coveragePreview(unchanged, stored, now))
        // Editing only a non-date field must not change the preview.
        assertEquals(
            shown(storedEnd, isPast = false),
            WarrantyEditFormState.coveragePreview(unchanged.copy(supportPhone = "123", productName = "X"), stored, now)
        )
    }

    @Test
    fun `coveragePreview recomputes when the months change`() {
        val stored = warranty(purchase = local(2026, 1, 15), months = 24, end = local(2028, 3, 1))
        val preview = WarrantyEditFormState.coveragePreview(
            draft(purchase = local(2026, 1, 15), months = "12"), stored, now
        )
        assertEquals(shown(local(2027, 1, 16), isPast = false), preview)
    }

    @Test
    fun `coveragePreview recomputes when the purchase day changes`() {
        val stored = warranty(purchase = local(2026, 1, 15), months = 24, end = local(2028, 3, 1))
        val preview = WarrantyEditFormState.coveragePreview(
            draft(purchase = local(2026, 1, 20), months = "24"), stored, now
        )
        assertEquals(shown(local(2028, 1, 21), isPast = false), preview)
    }

    @Test
    fun `coveragePreview treats the same local day in the current zone as unchanged after a zone change (INV-2)`() {
        val storedEnd = local(2028, 3, 1)
        // Stored at Athens local midnight, 2026-01-14T22:00Z, which is Jan 14 17:00 in New York.
        val stored = warranty(purchase = local(2026, 1, 15), months = 24, end = storedEnd)
        val preview = inZone("America/New_York") {
            val chosen = local(2026, 1, 14, zone = ZoneId.of("America/New_York"))
            WarrantyEditFormState.coveragePreview(draft(purchase = chosen, months = "24"), stored, now)
        }
        assertEquals(shown(storedEnd, isPast = false), preview)
    }

    @Test
    fun `coveragePreview is Hidden for a blank, zero, too large or overflowing duration`() {
        for (bad in listOf("", "0", "121", "99999999999")) {
            assertEquals(
                "months text '$bad'",
                WarrantyCoveragePreview.Hidden,
                WarrantyEditFormState.coveragePreview(draft(months = bad), null, now)
            )
            assertEquals(
                "months text '$bad' with stored",
                WarrantyCoveragePreview.Hidden,
                WarrantyEditFormState.coveragePreview(draft(months = bad), warranty(), now)
            )
        }
    }

    @Test
    fun `coveragePreview is Hidden for an out of range purchase date`() {
        assertEquals(
            WarrantyCoveragePreview.Hidden,
            WarrantyEditFormState.coveragePreview(draft(purchase = local(1969, 12, 31)), null, now)
        )
        assertEquals(
            WarrantyCoveragePreview.Hidden,
            WarrantyEditFormState.coveragePreview(draft(purchase = local(2100, 1, 1)), null, now)
        )
    }

    @Test
    fun `coveragePreview is still shown when only a non coverage field is invalid`() {
        val preview = WarrantyEditFormState.coveragePreview(draft(product = ""), null, now)
        assertTrue(preview is WarrantyCoveragePreview.Shown)
    }

    @Test
    fun `coveragePreview isPast is true only when the end is strictly before now`() {
        // 2026-01-15 + 12 months, exclusive end 2027-01-16 00:00 Athens.
        val d = draft(purchase = local(2026, 1, 15), months = "12")
        val end = local(2027, 1, 16)
        val atEnd = WarrantyEditFormState.coveragePreview(d, null, end) as WarrantyCoveragePreview.Shown
        assertEquals(end, atEnd.endExclusive)
        assertFalse(atEnd.isPast)
        val justAfter = WarrantyEditFormState.coveragePreview(d, null, end + 1) as WarrantyCoveragePreview.Shown
        assertTrue(justAfter.isPast)
        val justBefore = WarrantyEditFormState.coveragePreview(d, null, end - 1) as WarrantyCoveragePreview.Shown
        assertFalse(justBefore.isPast)
    }

    @Test
    fun `coveragePreview on a DST day uses the next local midnight as the exclusive end`() {
        // 2026-03-29 is the 23 hour spring-forward day in Athens: last covered day Mar 29, end Mar 30 00:00.
        val springForward = WarrantyEditFormState.coveragePreview(
            draft(purchase = local(2025, 3, 29), months = "12"), null, now
        )
        assertEquals(local(2026, 3, 30), (springForward as WarrantyCoveragePreview.Shown).endExclusive)
        // 2026-10-25 is the 25 hour fall-back day: last covered day Oct 25, end Oct 26 00:00.
        val fallBack = WarrantyEditFormState.coveragePreview(
            draft(purchase = local(2025, 10, 25), months = "12"), null, now
        )
        assertEquals(local(2026, 10, 26), (fallBack as WarrantyCoveragePreview.Shown).endExclusive)
    }

    // ---- outcomeLine -------------------------------------------------------------------------------------------

    @Test
    fun `outcomeLine is NONE in add mode and when the preview is hidden`() {
        assertEquals(
            WarrantyOutcomeLine.NONE,
            WarrantyEditFormState.outcomeLine(null, WarrantyReviewState.NOT_IN_REVIEW, futurePreview)
        )
        assertEquals(
            WarrantyOutcomeLine.NONE,
            WarrantyEditFormState.outcomeLine(
                warranty(status = WarrantyStatus.PENDING_REVIEW, needsReview = true),
                WarrantyReviewState.AWAITING_REVIEW,
                WarrantyCoveragePreview.Hidden
            )
        )
    }

    @Test
    fun `outcomeLine for a review row says it will be confirmed active when the end is in the future`() {
        val pending = warranty(status = WarrantyStatus.PENDING_REVIEW, needsReview = true)
        assertEquals(
            WarrantyOutcomeLine.CONFIRM_ACTIVE,
            WarrantyEditFormState.outcomeLine(pending, WarrantyReviewState.AWAITING_REVIEW, futurePreview)
        )
        val expiredUnconfirmed = warranty(status = WarrantyStatus.EXPIRED, needsReview = true)
        assertEquals(
            WarrantyOutcomeLine.CONFIRM_ACTIVE,
            WarrantyEditFormState.outcomeLine(expiredUnconfirmed, WarrantyReviewState.EXPIRED_UNCONFIRMED, futurePreview)
        )
    }

    @Test
    fun `outcomeLine for a review row says it will be listed as expired when the end is past`() {
        val pending = warranty(status = WarrantyStatus.PENDING_REVIEW, needsReview = true)
        assertEquals(
            WarrantyOutcomeLine.CONFIRM_EXPIRED,
            WarrantyEditFormState.outcomeLine(pending, WarrantyReviewState.AWAITING_REVIEW, pastPreview)
        )
        val expiredUnconfirmed = warranty(status = WarrantyStatus.EXPIRED, needsReview = true)
        assertEquals(
            WarrantyOutcomeLine.CONFIRM_EXPIRED,
            WarrantyEditFormState.outcomeLine(expiredUnconfirmed, WarrantyReviewState.EXPIRED_UNCONFIRMED, pastPreview)
        )
    }

    @Test
    fun `outcomeLine reactivates a confirmed EXPIRED row only when the edited end is in the future`() {
        val expired = warranty(status = WarrantyStatus.EXPIRED)
        assertEquals(
            WarrantyOutcomeLine.REACTIVATE,
            WarrantyEditFormState.outcomeLine(expired, WarrantyReviewState.NOT_IN_REVIEW, futurePreview)
        )
        assertEquals(
            WarrantyOutcomeLine.NONE,
            WarrantyEditFormState.outcomeLine(expired, WarrantyReviewState.NOT_IN_REVIEW, pastPreview)
        )
    }

    @Test
    fun `outcomeLine marks a confirmed ACTIVE row expired only when the edited end is past`() {
        val active = warranty(status = WarrantyStatus.ACTIVE)
        assertEquals(
            WarrantyOutcomeLine.WILL_EXPIRE,
            WarrantyEditFormState.outcomeLine(active, WarrantyReviewState.NOT_IN_REVIEW, pastPreview)
        )
        assertEquals(
            WarrantyOutcomeLine.NONE,
            WarrantyEditFormState.outcomeLine(active, WarrantyReviewState.NOT_IN_REVIEW, futurePreview)
        )
    }

    // ---- cardActionState (INV-21, AE-22, AE-7) ----------------------------------------------------------------------

    @Test
    fun `cardActionState ACTIVE confirmed card shows Edit, Claim and Delete in a row`() {
        val s = WarrantyEditFormState.cardActionState(WarrantyStatus.ACTIVE, WarrantyReviewState.NOT_IN_REVIEW, false)
        assertEquals(WarrantyEditPlacement.ACTIVE_ROW, s.placement)
        assertTrue(s.showEdit)
        assertTrue(s.showClaimDelete)
        assertFalse(s.showConfirmReject)
        assertTrue(s.enabled)
    }

    @Test
    fun `cardActionState review cards show Confirm Edit Reject whatever the persisted status`() {
        val cases = listOf(
            WarrantyStatus.PENDING_REVIEW to WarrantyReviewState.AWAITING_REVIEW,
            WarrantyStatus.EXPIRED to WarrantyReviewState.EXPIRED_UNCONFIRMED,
            WarrantyStatus.ACTIVE to WarrantyReviewState.EXPIRED_UNCONFIRMED
        )
        for ((status, review) in cases) {
            val s = WarrantyEditFormState.cardActionState(status, review, false)
            assertEquals("$status/$review", WarrantyEditPlacement.REVIEW_STACK, s.placement)
            assertTrue("$status/$review edit", s.showEdit)
            assertTrue("$status/$review confirm", s.showConfirmReject)
            assertFalse("$status/$review claim", s.showClaimDelete)
            assertTrue("$status/$review enabled", s.enabled)
        }
    }

    @Test
    fun `cardActionState confirmed EXPIRED card has only the Edit text button`() {
        val s = WarrantyEditFormState.cardActionState(WarrantyStatus.EXPIRED, WarrantyReviewState.NOT_IN_REVIEW, false)
        assertEquals(WarrantyEditPlacement.EXPIRED_TEXT, s.placement)
        assertTrue(s.showEdit)
        assertFalse(s.showConfirmReject)
        assertFalse(s.showClaimDelete)
        assertTrue(s.enabled)
    }

    @Test
    fun `cardActionState CLAIMED and TRANSFERRED cards have no edit entry or actions (AE-7)`() {
        for (status in listOf(WarrantyStatus.CLAIMED, WarrantyStatus.TRANSFERRED)) {
            for (mutating in listOf(false, true)) {
                val s = WarrantyEditFormState.cardActionState(status, WarrantyReviewState.NOT_IN_REVIEW, mutating)
                assertEquals("$status", WarrantyEditPlacement.NONE, s.placement)
                assertFalse("$status edit", s.showEdit)
                assertFalse("$status confirm", s.showConfirmReject)
                assertFalse("$status claim", s.showClaimDelete)
            }
        }
    }

    @Test
    fun `cardActionState disables every action while the card id is mutating and changes nothing else (AE-22)`() {
        for (status in WarrantyStatus.values()) {
            for (review in WarrantyReviewState.values()) {
                val idle = WarrantyEditFormState.cardActionState(status, review, isMutating = false)
                val busy = WarrantyEditFormState.cardActionState(status, review, isMutating = true)
                assertTrue("$status/$review idle enabled", idle.enabled)
                assertFalse("$status/$review busy disabled", busy.enabled)
                assertEquals("$status/$review shape", idle.copy(enabled = false), busy)
            }
        }
    }

    // ---- isPickerDaySelectable (O-11) ------------------------------------------------------------------------------

    @Test
    fun `isPickerDaySelectable lower bound is 1970-01-01 inclusive`() {
        assertTrue(WarrantyEditFormState.isPickerDaySelectable(utcMidnight(LocalDate.of(1970, 1, 1)), now, athens))
        assertFalse(WarrantyEditFormState.isPickerDaySelectable(utcMidnight(LocalDate.of(1969, 12, 31)), now, athens))
    }

    @Test
    fun `isPickerDaySelectable upper bound is today plus 365 days inclusive`() {
        // Today in Athens is 2026-11-02, so the last selectable day is 2027-11-02.
        assertTrue(WarrantyEditFormState.isPickerDaySelectable(utcMidnight(LocalDate.of(2026, 11, 2)), now, athens))
        assertTrue(WarrantyEditFormState.isPickerDaySelectable(utcMidnight(LocalDate.of(2027, 11, 2)), now, athens))
        assertFalse(WarrantyEditFormState.isPickerDaySelectable(utcMidnight(LocalDate.of(2027, 11, 3)), now, athens))
    }

    @Test
    fun `isPickerDaySelectable derives today from the supplied zone`() {
        // 2026-11-02 00:30 Athens is still 2026-11-01 in Los Angeles.
        val earlyAthens = local(2026, 11, 2, 0, 30)
        val lastDayInAthens = utcMidnight(LocalDate.of(2027, 11, 2))
        assertTrue(WarrantyEditFormState.isPickerDaySelectable(lastDayInAthens, earlyAthens, athens))
        assertFalse(
            WarrantyEditFormState.isPickerDaySelectable(lastDayInAthens, earlyAthens, ZoneId.of("America/Los_Angeles"))
        )
        assertTrue(
            WarrantyEditFormState.isPickerDaySelectable(
                utcMidnight(LocalDate.of(2027, 11, 1)), earlyAthens, ZoneId.of("America/Los_Angeles")
            )
        )
    }

    @Test
    fun `isPickerDaySelectable agrees with the validator at both upper edges`() {
        val lastOk = LocalDate.of(2027, 11, 2)
        val firstBad = LocalDate.of(2027, 11, 3)
        assertTrue(WarrantyEditFormState.isPickerDaySelectable(utcMidnight(lastOk), now, athens))
        assertTrue(
            WarrantyEditFormState.fieldErrors(
                draft(purchase = lastOk.atStartOfDay(athens).toInstant().toEpochMilli()), now
            ).isEmpty()
        )
        assertFalse(WarrantyEditFormState.isPickerDaySelectable(utcMidnight(firstBad), now, athens))
        assertEquals(
            mapOf(WarrantyFormField.PURCHASE_DATE to WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE),
            WarrantyEditFormState.fieldErrors(
                draft(purchase = firstBad.atStartOfDay(athens).toInstant().toEpochMilli()), now
            )
        )
    }

    // ---- sanitizeDurationText -------------------------------------------------------------------------------------

    @Test
    fun `sanitizeDurationText keeps digits and drops everything else`() {
        assertEquals("24", WarrantyEditFormState.sanitizeDurationText("24"))
        assertEquals("12", WarrantyEditFormState.sanitizeDurationText("a1b2"))
        assertEquals("5", WarrantyEditFormState.sanitizeDurationText("-5"))
        assertEquals("15", WarrantyEditFormState.sanitizeDurationText("1.5"))
        assertEquals("", WarrantyEditFormState.sanitizeDurationText(""))
        assertEquals("", WarrantyEditFormState.sanitizeDurationText("abc +-."))
    }

    @Test
    fun `sanitizeDurationText cuts to four digits after filtering`() {
        assertEquals("1234", WarrantyEditFormState.sanitizeDurationText("1234"))
        assertEquals("1234", WarrantyEditFormState.sanitizeDurationText("12345"))
        assertEquals("1234", WarrantyEditFormState.sanitizeDurationText("1a2b3c4d5"))
    }

    // ---- initialAddDraft / draftFromWarranty / WarrantyDraft ------------------------------------------------------------

    @Test
    fun `initialAddDraft uses the passed clock day, 24 months, Manufacturer and empty text`() {
        val d = WarrantyEditFormState.initialAddDraft(local(2026, 11, 2, 15, 45))
        assertEquals("", d.productName)
        assertEquals("", d.merchantName)
        assertEquals(local(2026, 11, 2), d.purchaseDateMillis)
        assertEquals("24", d.durationMonthsText)
        assertEquals(WarrantyType.MANUFACTURER, d.warrantyType)
        assertEquals("", d.supportPhone)
    }

    @Test
    fun `initialAddDraft on the 25 hour DST fall back day still starts at local midnight`() {
        // Athens leaves DST on 2026-10-25 (25 hour day).
        val d = WarrantyEditFormState.initialAddDraft(local(2026, 10, 25, 23, 30))
        assertEquals(local(2026, 10, 25), d.purchaseDateMillis)
    }

    @Test
    fun `initialAddDraft is invalid until names are typed so the add form needs input`() {
        val errors = WarrantyEditFormState.fieldErrors(WarrantyEditFormState.initialAddDraft(now), now)
        assertEquals(
            mapOf(
                WarrantyFormField.PRODUCT to WarrantyEditError.PRODUCT_BLANK,
                WarrantyFormField.MERCHANT to WarrantyEditError.MERCHANT_BLANK
            ),
            errors
        )
    }

    @Test
    fun `draftFromWarranty copies the six fields and maps a null phone to empty`() {
        val w = warranty(purchase = local(2026, 1, 15), months = 18, phone = null)
        val d = WarrantyEditFormState.draftFromWarranty(w)
        assertEquals("Pixel 9", d.productName)
        assertEquals("Contoso", d.merchantName)
        assertEquals(local(2026, 1, 15), d.purchaseDateMillis)
        assertEquals("18", d.durationMonthsText)
        assertEquals(WarrantyType.EXTENDED, d.warrantyType)
        assertEquals("", d.supportPhone)
        assertFalse(WarrantyEditFormState.isDirty(d, d))
    }

    @Test
    fun `WarrantyDraft toInput trims text, nulls a blank phone and maps unparseable months to zero`() {
        val input = draft(product = "  Pixel 9 ", merchant = " Contoso", months = "99999999999", phone = "   ").toInput()
        assertEquals("Pixel 9", input.productName)
        assertEquals("Contoso", input.merchantName)
        assertNull(input.supportPhone)
        assertEquals(0, input.durationMonths)
        assertNull(draft(months = "").durationMonths)
        assertEquals(24, draft(months = "24").toInput().durationMonths)
    }

    @Test
    fun `typeOptions lists the four warranty types in declaration order`() {
        assertEquals(
            listOf(WarrantyType.MANUFACTURER, WarrantyType.EXTENDED, WarrantyType.STORE, WarrantyType.THIRD_PARTY),
            WarrantyEditFormState.typeOptions()
        )
    }

    // ---- purchaseEpochDay / startOfEpochDay (saved-state round trip) ------------------------------------------------------

    @Test
    fun `purchaseEpochDay returns the local calendar epoch day`() {
        assertEquals(
            LocalDate.of(2026, 11, 2).toEpochDay(),
            WarrantyEditFormState.purchaseEpochDay(local(2026, 11, 2, 23, 59), athens)
        )
        assertEquals(0L, WarrantyEditFormState.purchaseEpochDay(0L, ZoneOffset.UTC))
    }

    @Test
    fun `purchaseEpochDay depends on the supplied zone near midnight`() {
        // 2026-11-02T23:30Z is Nov 3 in Athens (UTC+2) and Nov 2 in Los Angeles.
        val instant = LocalDate.of(2026, 11, 2).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(LocalDate.of(2026, 11, 3).toEpochDay(), WarrantyEditFormState.purchaseEpochDay(instant, athens))
        assertEquals(
            LocalDate.of(2026, 11, 2).toEpochDay(),
            WarrantyEditFormState.purchaseEpochDay(instant, ZoneId.of("America/Los_Angeles"))
        )
    }

    @Test
    fun `startOfEpochDay returns local midnight of that day`() {
        assertEquals(
            local(2026, 11, 2),
            WarrantyEditFormState.startOfEpochDay(LocalDate.of(2026, 11, 2).toEpochDay(), athens)
        )
        assertEquals(0L, WarrantyEditFormState.startOfEpochDay(0L, ZoneOffset.UTC))
    }

    @Test
    fun `epoch day round trip restores the start of the same local day including DST days`() {
        val samples = listOf(
            local(2026, 11, 2, 15, 45), // ordinary day
            local(2026, 3, 29, 23, 59), // 23 hour spring-forward day in Athens
            local(2026, 10, 25, 23, 30), // 25 hour fall-back day in Athens
            local(2024, 2, 29, 12) // leap day
        )
        for (instant in samples) {
            val day = WarrantyEditFormState.purchaseEpochDay(instant, athens)
            val restored = WarrantyEditFormState.startOfEpochDay(day, athens)
            val expectedStart = expectedStartOfLocalDay(instant)
            assertEquals("instant $instant", expectedStart, restored)
            assertEquals("idempotent for $instant", day, WarrantyEditFormState.purchaseEpochDay(restored, athens))
        }
    }

    @Test
    fun `epoch day round trip in another zone is stable in that zone`() {
        val tokyo = ZoneId.of("Asia/Tokyo")
        val instant = local(2026, 5, 5, 3, 0, tokyo)
        val restored = WarrantyEditFormState.startOfEpochDay(WarrantyEditFormState.purchaseEpochDay(instant, tokyo), tokyo)
        assertEquals(local(2026, 5, 5, 0, 0, tokyo), restored)
    }

    /** Independent expected value: local midnight of the instant's Athens calendar day. */
    private fun expectedStartOfLocalDay(instant: Long): Long =
        java.time.Instant.ofEpochMilli(instant).atZone(athens).toLocalDate().atStartOfDay(athens)
            .toInstant().toEpochMilli()
}
