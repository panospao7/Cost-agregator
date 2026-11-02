package com.yourname.expensetracker.domain.usecase.warranty

import com.yourname.expensetracker.data.database.entity.WarrantyType
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.domain.util.GlobalTimeZoneTestLock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone

/**
 * warranty-edit S1: INV-4 (single pure validator, AE-4) and INV-17 (the clock is a parameter,
 * AE-18), plus [WarrantyEditInput.normalized].
 *
 * The default TimeZone and Locale are pinned for every test and restored afterwards. All data is
 * synthetic.
 *
 * Not covered here: the "Int.MAX+1 as an input string" case of AE-4. The validator takes an Int,
 * so that case is decided at the text-to-Int parse boundary of the ViewModel/UI slice. Int.MAX_VALUE
 * and Int.MIN_VALUE are covered below as the Int-level overflow neighbours.
 */
class WarrantyEditValidatorTest {

    private lateinit var originalZone: TimeZone
    private lateinit var originalLocale: Locale

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

    private fun localMillis(
        date: LocalDate,
        hour: Int = 0,
        minute: Int = 0,
        second: Int = 0,
        millis: Int = 0
    ): Long = date.atTime(hour, minute, second, millis * 1_000_000)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val today = LocalDate.of(2026, 11, 2)

    /** 2026-11-02 12:00 local (Athens). */
    private val now: Long get() = localMillis(today, 12)

    private fun validInput(
        productName: String = "Pixel 9",
        merchantName: String = "Contoso",
        purchaseDate: Long = localMillis(LocalDate.of(2026, 10, 1)),
        durationMonths: Int = 12,
        warrantyType: WarrantyType = WarrantyType.MANUFACTURER,
        supportPhone: String? = "+00 000 0000000"
    ) = WarrantyEditInput(
        productName = productName,
        merchantName = merchantName,
        purchaseDate = purchaseDate,
        durationMonths = durationMonths,
        warrantyType = warrantyType,
        supportPhone = supportPhone
    )

    private fun validate(input: WarrantyEditInput, nowMillis: Long = now) =
        WarrantyEditValidator.validate(input, nowMillis)

    // ---- INV-4: the bounded error set and the O-11 limits ---------------------------------------

    @Test
    fun `INV-4 the error reasons are exactly the six bounded codes`() {
        assertEquals(
            listOf(
                "PRODUCT_BLANK",
                "MERCHANT_BLANK",
                "NAME_TOO_LONG",
                "PHONE_TOO_LONG",
                "PURCHASE_DATE_OUT_OF_RANGE",
                "DURATION_OUT_OF_RANGE"
            ),
            WarrantyEditError.values().map { it.name }
        )
    }

    @Test
    fun `INV-4 O-11 limits are 120 name chars 40 phone chars 1 to 120 months and 365 days ahead`() {
        assertEquals(120, WarrantyEditValidator.MAX_NAME_LENGTH)
        assertEquals(40, WarrantyEditValidator.MAX_PHONE_LENGTH)
        assertEquals(1, WarrantyEditValidator.MIN_DURATION_MONTHS)
        assertEquals(120, WarrantyEditValidator.MAX_DURATION_MONTHS)
        assertEquals(LocalDate.of(1970, 1, 1), WarrantyEditValidator.MIN_PURCHASE_DAY)
        assertEquals(365L, WarrantyEditValidator.MAX_PURCHASE_DAYS_AHEAD)
    }

    // ---- INV-4 / AE-4: normal control ------------------------------------------------------------

    @Test
    fun `INV-4 a valid input returns null`() {
        assertNull(validate(validInput()))
    }

    @Test
    fun `INV-4 a valid input with no phone and every warranty type returns null`() {
        for (type in WarrantyType.values()) {
            assertNull(validate(validInput(warrantyType = type, supportPhone = null)))
        }
    }

    // ---- INV-4 / AE-4: blank names ---------------------------------------------------------------

    @Test
    fun `INV-4 AE-4 blank product after trim is PRODUCT_BLANK`() {
        for (blank in listOf("", " ", "   ", "\t", "\n", " \t\n ")) {
            assertEquals(
                "product=[${blank.length} chars]",
                WarrantyEditError.PRODUCT_BLANK,
                validate(validInput(productName = blank))
            )
        }
    }

    @Test
    fun `INV-4 AE-4 blank merchant after trim is MERCHANT_BLANK`() {
        for (blank in listOf("", " ", "   ", "\t", "\n", " \t\n ")) {
            assertEquals(
                "merchant=[${blank.length} chars]",
                WarrantyEditError.MERCHANT_BLANK,
                validate(validInput(merchantName = blank))
            )
        }
    }

    @Test
    fun `INV-4 a one character product and merchant are valid`() {
        assertNull(validate(validInput(productName = "A", merchantName = "B")))
    }

    // ---- INV-4 / AE-4: name length ---------------------------------------------------------------

    @Test
    fun `INV-4 AE-4 121 character merchant is NAME_TOO_LONG`() {
        assertEquals(WarrantyEditError.NAME_TOO_LONG, validate(validInput(merchantName = "m".repeat(121))))
    }

    @Test
    fun `INV-4 AE-4 121 character product is NAME_TOO_LONG`() {
        assertEquals(WarrantyEditError.NAME_TOO_LONG, validate(validInput(productName = "p".repeat(121))))
    }

    @Test
    fun `INV-4 120 character product and merchant are valid`() {
        assertNull(validate(validInput(productName = "p".repeat(120), merchantName = "m".repeat(120))))
    }

    @Test
    fun `INV-4 name length is measured after trim`() {
        // 120 real characters padded with whitespace stay valid; 121 real characters padded stay invalid.
        assertNull(validate(validInput(productName = "  " + "p".repeat(120) + "  ")))
        assertNull(validate(validInput(merchantName = "\t" + "m".repeat(120) + "\n")))
        assertEquals(
            WarrantyEditError.NAME_TOO_LONG,
            validate(validInput(productName = "  " + "p".repeat(121) + "  "))
        )
        assertEquals(
            WarrantyEditError.NAME_TOO_LONG,
            validate(validInput(merchantName = "\t" + "m".repeat(121) + "\n"))
        )
    }

    // ---- INV-4 / AE-4: phone length --------------------------------------------------------------

    @Test
    fun `INV-4 AE-4 41 character phone is PHONE_TOO_LONG`() {
        assertEquals(WarrantyEditError.PHONE_TOO_LONG, validate(validInput(supportPhone = "1".repeat(41))))
    }

    @Test
    fun `INV-4 40 character phone is valid`() {
        assertNull(validate(validInput(supportPhone = "1".repeat(40))))
    }

    @Test
    fun `INV-4 phone length is measured after trim`() {
        assertNull(validate(validInput(supportPhone = "  " + "1".repeat(40) + "  ")))
        assertEquals(
            WarrantyEditError.PHONE_TOO_LONG,
            validate(validInput(supportPhone = "  " + "1".repeat(41) + "  "))
        )
    }

    @Test
    fun `INV-4 a blank or null phone is valid`() {
        assertNull(validate(validInput(supportPhone = null)))
        assertNull(validate(validInput(supportPhone = "")))
        assertNull(validate(validInput(supportPhone = "     ")))
        // Whitespace beyond 40 chars is still blank after trim, never PHONE_TOO_LONG.
        assertNull(validate(validInput(supportPhone = " ".repeat(100))))
    }

    // ---- INV-4 / AE-4: purchase date -------------------------------------------------------------

    @Test
    fun `INV-4 AE-4 purchase 1969-12-31 is PURCHASE_DATE_OUT_OF_RANGE`() {
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(validInput(purchaseDate = localMillis(LocalDate.of(1969, 12, 31))))
        )
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(validInput(purchaseDate = localMillis(LocalDate.of(1969, 12, 31), 23, 59, 59, 999)))
        )
    }

    @Test
    fun `INV-4 AE-4 purchase 2100-01-01 is PURCHASE_DATE_OUT_OF_RANGE`() {
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(validInput(purchaseDate = localMillis(LocalDate.of(2100, 1, 1))))
        )
    }

    @Test
    fun `INV-4 purchase on exactly 1970-01-01 local is valid at any time of that day`() {
        val first = LocalDate.of(1970, 1, 1)
        assertNull(validate(validInput(purchaseDate = localMillis(first))))
        assertNull(validate(validInput(purchaseDate = localMillis(first, 23, 59, 59, 999))))
    }

    @Test
    fun `INV-4 purchase today and a past purchase are valid`() {
        assertNull(validate(validInput(purchaseDate = localMillis(today))))
        assertNull(validate(validInput(purchaseDate = localMillis(today, 23, 59, 59, 999))))
        assertNull(validate(validInput(purchaseDate = localMillis(LocalDate.of(2001, 9, 9)))))
    }

    @Test
    fun `INV-4 purchase exactly today plus 365 days is valid through the end of that day`() {
        val limit = today.plusDays(365)
        assertNull(validate(validInput(purchaseDate = localMillis(limit))))
        assertNull(validate(validInput(purchaseDate = localMillis(limit, 23, 59, 59, 999))))
    }

    @Test
    fun `INV-4 purchase today plus 366 days is PURCHASE_DATE_OUT_OF_RANGE from its first instant`() {
        val over = today.plusDays(366)
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(validInput(purchaseDate = localMillis(over)))
        )
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(validInput(purchaseDate = localMillis(over, 12)))
        )
    }

    @Test
    fun `INV-4 extreme purchase instants are PURCHASE_DATE_OUT_OF_RANGE`() {
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(validInput(purchaseDate = Long.MIN_VALUE))
        )
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(validInput(purchaseDate = Long.MAX_VALUE))
        )
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(validInput(purchaseDate = -1L - 86_400_000L * 365L))
        )
    }

    @Test
    fun `INV-4 the lower purchase bound is judged on the local day of the current zone`() {
        // 1970-01-01T03:00Z is 05:00 on 1970-01-01 in Athens (valid) but 22:00 on 1969-12-31 in New York.
        val instant = 3L * 3_600_000L

        assertNull(validate(validInput(purchaseDate = instant)))
        inZone("America/New_York") {
            assertEquals(
                WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
                validate(validInput(purchaseDate = instant))
            )
        }
    }

    @Test
    fun `INV-4 the upper purchase bound follows the local calendar day of now in the current zone`() {
        // now = 2026-11-02T23:30Z is already 2026-11-03 in Athens but still 2026-11-02 in UTC.
        val nowUtc = LocalDate.of(2026, 11, 2).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val limitAthens = LocalDate.of(2027, 11, 3)

        assertNull(validate(validInput(purchaseDate = localMillis(limitAthens)), nowUtc))
        inZone("UTC") {
            assertEquals(
                WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
                validate(validInput(purchaseDate = localMillis(limitAthens)), nowUtc)
            )
            assertNull(validate(validInput(purchaseDate = localMillis(LocalDate.of(2027, 11, 2))), nowUtc))
        }
    }

    // ---- INV-4 / AE-4: duration ------------------------------------------------------------------

    @Test
    fun `INV-4 AE-4 duration 0 is DURATION_OUT_OF_RANGE`() {
        assertEquals(WarrantyEditError.DURATION_OUT_OF_RANGE, validate(validInput(durationMonths = 0)))
    }

    @Test
    fun `INV-4 AE-4 duration -1 is DURATION_OUT_OF_RANGE`() {
        assertEquals(WarrantyEditError.DURATION_OUT_OF_RANGE, validate(validInput(durationMonths = -1)))
    }

    @Test
    fun `INV-4 AE-4 duration 121 is DURATION_OUT_OF_RANGE`() {
        assertEquals(WarrantyEditError.DURATION_OUT_OF_RANGE, validate(validInput(durationMonths = 121)))
    }

    @Test
    fun `INV-4 AE-4 Int overflow neighbours are DURATION_OUT_OF_RANGE`() {
        assertEquals(
            WarrantyEditError.DURATION_OUT_OF_RANGE,
            validate(validInput(durationMonths = Int.MAX_VALUE))
        )
        assertEquals(
            WarrantyEditError.DURATION_OUT_OF_RANGE,
            validate(validInput(durationMonths = Int.MIN_VALUE))
        )
    }

    @Test
    fun `INV-4 duration 1 and 120 are valid`() {
        assertNull(validate(validInput(durationMonths = 1)))
        assertNull(validate(validInput(durationMonths = 120)))
        assertNull(validate(validInput(durationMonths = 60)))
    }

    // ---- INV-4: ordering and purity --------------------------------------------------------------

    @Test
    fun `INV-4 the first failure in the documented order is reported`() {
        val everythingBad = validInput(
            productName = " ",
            merchantName = "m".repeat(200),
            purchaseDate = localMillis(LocalDate.of(2100, 1, 1)),
            durationMonths = 0,
            supportPhone = "1".repeat(99)
        )
        assertEquals(WarrantyEditError.PRODUCT_BLANK, validate(everythingBad))
        assertEquals(WarrantyEditError.MERCHANT_BLANK, validate(everythingBad.copy(productName = "ok", merchantName = " ")))
        assertEquals(WarrantyEditError.NAME_TOO_LONG, validate(everythingBad.copy(productName = "ok")))
        assertEquals(
            WarrantyEditError.PHONE_TOO_LONG,
            validate(everythingBad.copy(productName = "ok", merchantName = "ok"))
        )
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(everythingBad.copy(productName = "ok", merchantName = "ok", supportPhone = null))
        )
        assertEquals(
            WarrantyEditError.DURATION_OUT_OF_RANGE,
            validate(
                everythingBad.copy(
                    productName = "ok",
                    merchantName = "ok",
                    supportPhone = null,
                    purchaseDate = localMillis(LocalDate.of(2026, 10, 1))
                )
            )
        )
    }

    @Test
    fun `INV-4 validation is deterministic and does not alter the input`() {
        val input = validInput(productName = "  Pixel 9  ", supportPhone = "  +00 000  ")
        val before = input.copy()

        val first = validate(input)
        val second = validate(input)

        assertNull(first)
        assertNull(second)
        assertEquals(before, input)
    }

    // ---- INV-17 / AE-18: the clock is a parameter ------------------------------------------------

    @Test
    fun `INV-17 AE-18 bounds use the fake clock on a DST transition day not the wall clock`() {
        // Europe/Athens switched to summer time on 2020-03-29; the fake clock sits on that day, far in the past.
        val clock = FakeTimeProvider(localMillis(LocalDate.of(2020, 3, 29), 12))
        val limit = LocalDate.of(2021, 3, 29)

        assertNull(validate(validInput(purchaseDate = localMillis(limit)), clock.now()))
        assertNull(validate(validInput(purchaseDate = localMillis(limit, 23, 59, 59, 999)), clock.now()))
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(validInput(purchaseDate = localMillis(limit.plusDays(1))), clock.now())
        )
        // A date in 2026 is in the past for the wall clock but in the future for the fake clock.
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(validInput(purchaseDate = localMillis(LocalDate.of(2026, 1, 1))), clock.now())
        )
    }

    @Test
    fun `INV-17 AE-18 a fake clock in the future widens the bound beyond the wall clock`() {
        // 2040-03-25 is the Athens DST start day in 2040.
        val clock = FakeTimeProvider(localMillis(LocalDate.of(2040, 3, 25), 12))

        assertNull(validate(validInput(purchaseDate = localMillis(LocalDate.of(2041, 3, 25))), clock.now()))
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(validInput(purchaseDate = localMillis(LocalDate.of(2041, 3, 26))), clock.now())
        )
    }

    @Test
    fun `INV-17 the same input flips from valid to invalid only because nowMillis moved`() {
        val input = validInput(purchaseDate = localMillis(LocalDate.of(2027, 6, 1)))

        assertNull(validate(input, localMillis(LocalDate.of(2026, 11, 2), 12)))
        assertEquals(
            WarrantyEditError.PURCHASE_DATE_OUT_OF_RANGE,
            validate(input, localMillis(LocalDate.of(2026, 1, 1), 12))
        )
    }

    // ---- normalized() ----------------------------------------------------------------------------

    @Test
    fun `normalized trims product merchant and phone`() {
        val normalized = validInput(
            productName = "  Pixel 9 \n",
            merchantName = "\tContoso  ",
            supportPhone = "  +00 000 0000000 "
        ).normalized()

        assertEquals("Pixel 9", normalized.productName)
        assertEquals("Contoso", normalized.merchantName)
        assertEquals("+00 000 0000000", normalized.supportPhone)
    }

    @Test
    fun `normalized turns a blank phone into null`() {
        assertNull(validInput(supportPhone = "").normalized().supportPhone)
        assertNull(validInput(supportPhone = "   ").normalized().supportPhone)
        assertNull(validInput(supportPhone = " \t\n ").normalized().supportPhone)
    }

    @Test
    fun `normalized keeps a null phone null`() {
        assertNull(validInput(supportPhone = null).normalized().supportPhone)
    }

    @Test
    fun `normalized leaves purchase date duration and warranty type unchanged`() {
        val input = validInput(
            productName = " a ",
            purchaseDate = 123_456_789L,
            durationMonths = 37,
            warrantyType = WarrantyType.THIRD_PARTY
        )

        val normalized = input.normalized()

        assertEquals(123_456_789L, normalized.purchaseDate)
        assertEquals(37, normalized.durationMonths)
        assertEquals(WarrantyType.THIRD_PARTY, normalized.warrantyType)
    }

    @Test
    fun `normalized keeps interior whitespace and is idempotent`() {
        val input = validInput(productName = "  Galaxy   Buds  ", supportPhone = " +00  000 ")

        val once = input.normalized()

        assertEquals("Galaxy   Buds", once.productName)
        assertEquals("+00  000", once.supportPhone)
        assertEquals(once, once.normalized())
    }

    @Test
    fun `normalized does not mutate the original input`() {
        val input = validInput(productName = "  Pixel 9  ")

        input.normalized()

        assertEquals("  Pixel 9  ", input.productName)
    }

    @Test
    fun `normalized input equal after trim validates identically to the padded input`() {
        val padded = validInput(productName = "  Pixel 9  ", merchantName = " Contoso ", supportPhone = " 123 ")

        assertEquals(validate(padded), validate(padded.normalized()))
        assertNull(validate(padded.normalized()))
    }
}
