package com.yourname.expensetracker.domain.transaction.validation

import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.transaction.CreateExpenseRequest
import com.yourname.expensetracker.domain.transaction.ExpenseSource
import com.yourname.expensetracker.domain.transaction.UserPresentRateRequirement
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * MEC A2: per-currency cap for all creates (INV-14, G2-3; AE-18) and the flagged-only amount
 * precision check (INV-13; AE-19). Pure validator test; no coordinator, no rates.
 */
class TransactionValidatorTest {

    private val now = 1_712_000_000_000L
    private val timeProvider: TimeProvider = mockk<TimeProvider>().also { every { it.now() } returns now }
    private val validator = TransactionValidator(timeProvider, DefaultTransactionDatePolicy())
    private val flag = UserPresentRateRequirement(ZoneId.of("Europe/Athens"))

    private fun request(
        amount: Double,
        currency: String,
        flagged: Boolean = false,
        source: ExpenseSource = ExpenseSource.MANUAL_ENTRY
    ) = CreateExpenseRequest(
        merchant = "Cafe",
        amount = amount,
        currency = currency,
        date = now - 86_400_000L,
        transactionType = TransactionType.PURCHASE,
        source = source,
        userPresentRate = if (flagged) flag else null
    )

    private fun codes(request: CreateExpenseRequest) = validator.validateCreate(request).map { it.code }

    // ── AE-18 caps (independent of rates; all creates) ───────────────────────

    @Test
    fun `AE-18 KRW 1,500,000 is allowed and KRW above one billion is refused`() {
        assertEquals(emptyList<String>(), codes(request(1_500_000.0, "KRW")))
        assertEquals(listOf("AMOUNT_EXCEEDS_MAX"), codes(request(1_000_000_001.0, "KRW")))
        assertEquals(emptyList<String>(), codes(request(1_000_000_000.0, "KRW")))
    }

    @Test
    fun `AE-18 EUR and USD keep the 1,000,000 limit exactly`() {
        assertEquals(listOf("AMOUNT_EXCEEDS_MAX"), codes(request(1_000_000.01, "EUR")))
        assertEquals(emptyList<String>(), codes(request(1_000_000.0, "USD")))
        assertEquals(listOf("AMOUNT_EXCEEDS_MAX"), codes(request(1_000_000.01, "USD")))
    }

    @Test
    fun `cap table groups apply to unflagged creates from other sources`() {
        assertEquals(emptyList<String>(), codes(request(9_999_999.0, "MXN", source = ExpenseSource.CSV_IMPORT)))
        assertEquals(listOf("AMOUNT_EXCEEDS_MAX"), codes(request(10_000_001.0, "SEK", source = ExpenseSource.CSV_IMPORT)))
        assertEquals(emptyList<String>(), codes(request(99_999_999.0, "JPY", source = ExpenseSource.BANK_SYNC)))
        assertEquals(listOf("AMOUNT_EXCEEDS_MAX"), codes(request(100_000_001.0, "HUF", source = ExpenseSource.BANK_SYNC)))
    }

    @Test
    fun `non-catalog codes keep the 1,000,000 limit`() {
        assertEquals(emptyList<String>(), codes(request(1_000_000.0, "XYZ")))
        assertEquals(listOf("AMOUNT_EXCEEDS_MAX"), codes(request(1_000_000.5, "XYZ")))
    }

    @Test
    fun `cap message carries no amount or currency`() {
        val error = validator.validateCreate(request(2_000_000.0, "EUR")).single { it.code == "AMOUNT_EXCEEDS_MAX" }
        assertTrue(listOf("EUR", "2000000", "2,000,000").none { error.message.contains(it) })
    }

    // ── AE-19 precision: flagged only ────────────────────────────────────────

    @Test
    fun `AE-19 flagged JPY 1500_5 is refused with AMOUNT_PRECISION_EXCEEDED`() {
        assertEquals(listOf("AMOUNT_PRECISION_EXCEEDED"), codes(request(1500.5, "JPY", flagged = true)))
        assertEquals(
            listOf("AMOUNT_PRECISION_EXCEEDED"),
            validator.validateCreate(request(1500.5, "JPY", flagged = true)).map { it.message }
        )
    }

    @Test
    fun `AE-19 unflagged JPY 1500_5 is created as today`() {
        assertEquals(emptyList<String>(), codes(request(1500.5, "JPY", flagged = false, source = ExpenseSource.SMS_NOTIFICATION)))
    }

    @Test
    fun `flagged whole and trailing-zero amounts are valid`() {
        assertEquals(emptyList<String>(), codes(request(1500.0, "JPY", flagged = true)))
        assertEquals(emptyList<String>(), codes(request(12.5, "EUR", flagged = true)))
        assertEquals(emptyList<String>(), codes(request(12.34, "USD", flagged = true)))
    }

    @Test
    fun `flagged EUR with three decimals is refused`() {
        assertEquals(listOf("AMOUNT_PRECISION_EXCEEDED"), codes(request(12.345, "EUR", flagged = true)))
    }

    @Test
    fun `flagged precision check does not hide the cap error and is reported once`() {
        val result = codes(request(2_000_000.123, "EUR", flagged = true))
        assertTrue(result.contains("AMOUNT_EXCEEDS_MAX"))
        assertEquals(1, result.count { it == "AMOUNT_PRECISION_EXCEEDED" })
    }

    @Test
    fun `flagged request in a currency with unknown digits is refused with AMOUNT_DIGITS_UNAVAILABLE`() {
        val flaggedErrors = validator.validateCreate(request(10.0, "XYZ", flagged = true))
        assertEquals(listOf("AMOUNT_DIGITS_UNAVAILABLE"), flaggedErrors.map { it.code })
        assertEquals(listOf("AMOUNT_DIGITS_UNAVAILABLE"), flaggedErrors.map { it.message })
    }

    @Test
    fun `unflagged request in a currency with unknown digits adds no digits error`() {
        assertEquals(emptyList<String>(), codes(request(10.0, "XYZ", flagged = false)))
    }

    @Test
    fun `non-positive and non-finite amounts keep their existing error and add no precision error`() {
        assertEquals(listOf("AMOUNT_NOT_POSITIVE_FINITE"), codes(request(0.0, "EUR", flagged = true)))
        assertTrue(codes(request(Double.NaN, "EUR", flagged = true)).none { it == "AMOUNT_PRECISION_EXCEEDED" })
    }

    // ── Update path shares the per-currency cap (an edit of a large KRW row must not regress) ──

    @Test
    fun `final-state validation uses the same per-currency cap`() {
        val errors = validator.validateFinalExpenseState(
            amount = 1_500_000.0,
            merchant = "Cafe",
            currency = "KRW",
            date = now - 86_400_000L,
            transactionType = TransactionType.PURCHASE,
            transferDirectionPresent = false,
            transferAccountName = null,
            isNotMine = false,
            isSharedExpense = false,
            latitude = null,
            longitude = null
        )
        assertEquals(emptyList<String>(), errors.map { it.code })
    }
}
