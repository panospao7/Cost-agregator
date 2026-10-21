package com.yourname.expensetracker.domain.parser.parsers

import com.yourname.expensetracker.domain.parser.ParsedTransactionType
import com.yourname.expensetracker.domain.util.CurrencyNormalizer
import com.yourname.expensetracker.domain.util.MerchantCleaner
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Regression for MONEY-02 hotfix H1.
 *
 * Former defect: [GreekBankParser] looped over every regex capture group and treated any group that
 * [com.yourname.expensetracker.domain.util.AmountUtils.parseAmount] could parse as "the amount", with the
 * LAST such group winning. Free-text tail groups (merchant / transfer destination) therefore overwrote
 * the real amount whenever they contained digits, because parseAmount strips everything except [0-9.,-]:
 * "SHOP 24" -> 24, "SKLAVENITIS στις 14:32" -> 1432, "σε λογαριασμό ****1234" -> 1234.
 * Each pattern now declares its own amount group, so free-text groups are never parsed as amounts.
 *
 * Independent oracle: the amount printed right after the transaction keyword in each notification.
 *
 * Setup: identity merchant cleaner, the REAL [CurrencyNormalizer] (maps symbols/codes to ISO codes) and a
 * home currency (GBP) that differs from every currency token used in the assertions, so a currency
 * assertion can only pass when the parser really read the currency from the notification.
 *
 * Review follow-ups covered below: the amount token must never give its last digit to a lazy tail
 * ("Αγορά €20" is not amount 2), a bare currency tail ("Αγορά 12,50 USD") is the currency and not the
 * merchant, and deposit/transfer patterns honour thousands grouping ("Κατάθεση 1.500,00" is 1500).
 *
 * Notes on expressibility (traced against the actual regexes):
 * - A date such as "12/05" cannot reproduce the defect: parseAmount rejects any token containing '/'.
 * - A time "14:32" after the amount DOES reproduce it, because ':' is stripped and the digits concatenate.
 */
class GreekBankParserAmountGroupTest {

    private lateinit var parser: GreekBankParser

    @Before
    fun setup() {
        val merchantCleaner = mockk<MerchantCleaner> {
            every { clean(any()) } answers { firstArg() ?: "Unknown" }
        }
        parser = GreekBankParser(CurrencyNormalizer(), merchantCleaner, homeCurrency = HOME_CURRENCY)
    }

    private fun parse(text: String, title: String = "Ειδοποίηση") = parser.parse(
        title = title,
        text = text,
        bigText = null,
        subText = null,
        packageName = "gr.nbg.mobilebanking"
    )

    private companion object {
        /** Deliberately different from the EUR/USD tokens asserted below. */
        const val HOME_CURRENCY = "GBP"
    }

    // ---- Case 1: merchant name ends in digits --------------------------------------------------

    /**
     * Hits PURCHASE_PATTERNS[0]: keyword "Αγορά", group1 = "12,50", group2 (.+?) = "SHOP 24".
     * Defect: group2 parses as 24 and overwrites the amount (actual 24.0, merchant left "Unknown").
     */
    @Test
    fun `purchase with merchant ending in digits keeps the real amount`() {
        val result = parse("Αγορά 12,50 EUR στο SHOP 24")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(12.50, result.amount, 0.001)
        assertEquals("SHOP 24", result.merchant)
    }

    // ---- Case 2: trailing time after the merchant ----------------------------------------------

    /**
     * Hits PURCHASE_PATTERNS[0]: group1 = "12,50", group2 = "SKLAVENITIS στις 14:32".
     * Defect: group2 parses as 1432 (colon and letters stripped) and overwrites the amount.
     */
    @Test
    fun `purchase with trailing time keeps the real amount`() {
        val result = parse("Αγορά 12,50 EUR στο SKLAVENITIS στις 14:32")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(12.50, result.amount, 0.001)
    }

    // ---- Case 3: transfer to masked account ----------------------------------------------------

    /**
     * Hits TRANSFER_PATTERNS[0] (no deposit or purchase pattern matches "Μεταφορά ..." without a
     * currency symbol before the amount): group1 = "100,00", group2 (.+) = "σε λογαριασμό ****1234".
     * Defect: group2 parses as 1234 and overwrites the amount.
     */
    @Test
    fun `transfer to masked account keeps the real amount`() {
        val result = parse("Μεταφορά 100,00 EUR σε λογαριασμό ****1234")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(100.00, result.amount, 0.001)
    }

    // ---- Case 4 (control): no digits in the tail -----------------------------------------------

    /** Hits PURCHASE_PATTERNS[0]; tail "MY MARKET" has no digits so it is never an amount token. */
    @Test
    fun `control - plain purchase without digits in tail`() {
        val result = parse("Αγορά 8,40 EUR στο MY MARKET")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(8.40, result.amount, 0.001)
        assertEquals("MY MARKET", result.merchant)
    }

    // ---- Case 5 (control): thousands format ----------------------------------------------------

    /**
     * Hits PURCHASE_PATTERNS[0]: group1 = "1.234,56" (\d{1,3}(?:[.,\s]\d{3})*(?:[.,]\d{1,2})?),
     * tail "ELECTRO STORE" has no digits. European grouping must resolve to 1234.56.
     */
    @Test
    fun `control - thousands format 1 dot 234 comma 56`() {
        val result = parse("Αγορά 1.234,56 EUR στο ELECTRO STORE")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(1234.56, result.amount, 0.001)
        assertEquals("ELECTRO STORE", result.merchant)
    }

    // ---- Cases 6-9: patterns whose amount group is not group 1 / other pattern families --------

    /**
     * PURCHASE_PATTERNS[1]: group1 = currency symbol, group2 = amount, group3 = merchant.
     * Amount is NOT group 1; merchant group3 "SHOP 24" must not overwrite it.
     */
    @Test
    fun `symbol-first purchase pattern uses group 2 as amount and keeps digit-bearing merchant`() {
        val result = parse("€12,50 στο SHOP 24")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(12.50, result.amount, 0.001)
        // Currency symbol captured by group 1 -> "EUR"; home currency is GBP so a fallback would fail.
        assertEquals("EUR", result.currency)
        assertEquals("SHOP 24", result.merchant)
    }

    /**
     * PURCHASE_PATTERNS[2] (card charge): group1 = amount, group2 = currency, group3 = merchant.
     * Merchant "VODAFONE 4G" must not be read as amount 4.
     */
    @Test
    fun `card charge pattern keeps real amount when merchant contains digits`() {
        val result = parse("Χρέωση κάρτας: 25,00 EUR - VODAFONE 4G")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(25.00, result.amount, 0.001)
        assertEquals("VODAFONE 4G", result.merchant)
    }

    /**
     * TRANSFER_PATTERNS[1] (transaction code): group1 = code letter, group2 = amount, group3 = tail.
     * Amount is group 2; tail "ΑΤΜ 24" must not overwrite it.
     */
    @Test
    fun `transaction code transfer pattern uses group 2 as amount`() {
        val result = parse("Χ 50,00 EUR ΑΤΜ 24")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(50.00, result.amount, 0.001)
    }

    /**
     * TRANSFER_PATTERNS[3] (bare "amount στο MERCHANT", no currency symbol so no purchase pattern
     * claims it): group1 = amount, group2 = merchant "SHOP 24".
     */
    @Test
    fun `bare amount transfer pattern keeps real amount when merchant contains digits`() {
        val result = parse("12,50 στο SHOP 24")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(12.50, result.amount, 0.001)
        assertEquals("SHOP 24", result.merchant)
    }

    /**
     * Alpha package ("gr.alpha.mobile") is routed to the same [GreekBankParser] patterns (no
     * Alpha-specific regex exists); verify a digit-bearing merchant under that package too.
     */
    @Test
    fun `alpha package purchase with digit-bearing merchant keeps the real amount`() {
        val result = parser.parse(
            title = "Alpha Bank",
            text = "Αγορά 12,50 EUR στο LIDL 24",
            bigText = null,
            subText = null,
            packageName = "gr.alpha.mobile"
        )

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(12.50, result.amount, 0.001)
        assertEquals("LIDL 24", result.merchant)
    }

    // ---- Review ISSUE-1: the amount must not give its last digit to a lazy/greedy tail ---------

    /**
     * PURCHASE_PATTERNS[0] needs at least one tail character. Without the trailing amount guard the
     * regex backtracked "€20" into amount "2" + tail "0". The title has no merchant so it must yield
     * nothing; the text then supplies the real amount (the engine parses title first).
     */
    @Test
    fun `title with bare amount does not steal the last digit and text still wins`() {
        val result = parse(title = "Αγορά €20", text = "Αγορά €20 στο SKLAVENITIS")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(20.0, result.amount, 0.001)
        assertEquals("SKLAVENITIS", result.merchant)
    }

    /**
     * With no tail the purchase patterns cannot match at all. (The old loop returned null for most
     * of these, but 5.0 for "Αγορά 12,55".) They must never return a digit-truncated amount.
     */
    @Test
    fun `purchase keyword with amount and no tail returns null instead of a truncated amount`() {
        assertNull(parse("Πληρωμή 100"))
        assertNull(parse("Αγορά €20"))
        assertNull(parse("Αγορά 12,55"))
        assertNull(parse("Αγορά 1.000"))
    }

    /** TRANSFER_PATTERNS[0] has a greedy tail too: "Μεταφορά €500" must not become 50. */
    @Test
    fun `transfer with bare amount and no tail returns null instead of a truncated amount`() {
        assertNull(parse("Μεταφορά €500"))
    }

    /**
     * "προς" keeps the text out of PURCHASE_PATTERNS[1] (which claims "€500 σε X" first, a
     * pre-existing type quirk recorded as a follow-up), so this reaches TRANSFER_PATTERNS[0].
     */
    @Test
    fun `transfer with amount and destination keeps the full amount`() {
        val result = parse("Μεταφορά €500 προς Μαρία")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(500.0, result.amount, 0.001)
    }

    /** Space-grouped amounts are atomic: with no tail they must not backtrack to "1" + "234,56". */
    @Test
    fun `space grouped amount with no tail returns null instead of a truncated amount`() {
        assertNull(parse("Αγορά 1 234,56"))
        assertNull(parse("Μεταφορά 1 500"))
    }

    /**
     * PURCHASE_PATTERNS[2]: `\d*` before the amount could swallow "2" of "25,00" and let the amount
     * start mid-number (5,00). The leading boundary forces the whole number.
     */
    @Test
    fun `card charge without colon keeps the whole amount`() {
        val result = parse("Χρέωση κάρτας 25,00 EUR - VODAFONE")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(25.0, result.amount, 0.001)
        assertEquals("VODAFONE", result.merchant)
    }

    // ---- Review ISSUE-2: a bare currency tail is the currency, not the merchant ----------------

    @Test
    fun `purchase whose whole tail is a currency code keeps that currency and default merchant`() {
        val result = parse("Αγορά 12,50 USD")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(12.50, result.amount, 0.001)
        assertEquals("USD", result.currency)
        assertEquals("Unknown", result.merchant)
    }

    @Test
    fun `purchase whose whole tail is a dollar sign keeps USD`() {
        val result = parse("Αγορά 12,50 \$")

        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertEquals("USD", result.currency)
        assertEquals("Unknown", result.merchant)
    }

    @Test
    fun `purchase whose whole tail is a euro code or sign keeps EUR when home currency differs`() {
        val code = parse("Αγορά 12,50 EUR")
        assertNotNull(code)
        assertEquals("EUR", code!!.currency)
        assertEquals("Unknown", code.merchant)

        val sign = parse("Αγορά 12,50 €")
        assertNotNull(sign)
        assertEquals("EUR", sign!!.currency)
        assertEquals("Unknown", sign.merchant)
    }

    @Test
    fun `transfer whose whole tail is a currency code keeps that currency and default merchant`() {
        val result = parse("Μεταφορά 100,00 USD")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(100.0, result.amount, 0.001)
        assertEquals("USD", result.currency)
        assertEquals("Transfer", result.merchant)
        assertFalse(result.transferAccountName?.contains("USD") == true)
    }

    @Test
    fun `transaction code transfer whose whole tail is a currency keeps that currency`() {
        // "Χ" is the Greek debit code letter.
        val code = parse("Χ 50,00 USD")
        assertNotNull(code)
        assertEquals(ParsedTransactionType.TRANSFER, code!!.type)
        assertEquals(50.0, code.amount, 0.001)
        assertEquals("USD", code.currency)
        assertEquals("Transfer", code.merchant)
        assertFalse(code.transferAccountName?.contains("USD") == true)

        val sign = parse("Χ 50,00 \$")
        assertNotNull(sign)
        assertEquals(50.0, sign!!.amount, 0.001)
        assertEquals("USD", sign.currency)
        assertEquals("Transfer", sign.merchant)
    }

    @Test
    fun `card charge currency group still wins over the home currency`() {
        val result = parse("Χρέωση κάρτας: 25,00 EUR - VODAFONE 4G")

        assertNotNull(result)
        assertEquals("EUR", result!!.currency)
    }

    // ---- F-H1-b: an explicit currency written after the amount is captured, not dropped --------
    // Home currency is GBP, so these can only pass when the currency token is really read.

    @Test
    fun `purchase with explicit USD and a merchant keeps USD and the merchant`() {
        val result = parse("Αγορά 12,50 USD στο SHOP")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(12.50, result.amount, 0.001)
        assertEquals("USD", result.currency)
        assertEquals("SHOP", result.merchant)
    }

    @Test
    fun `purchase with explicit EUR and a merchant keeps EUR when home currency differs`() {
        val result = parse("Αγορά 12,50 EUR στο SHOP")

        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertEquals("EUR", result.currency)
        assertEquals("SHOP", result.merchant)
    }

    @Test
    fun `transfer with explicit currency and a destination keeps that currency`() {
        val usd = parse("Μεταφορά 100,00 USD σε Μαρία")
        assertNotNull(usd)
        assertEquals(ParsedTransactionType.TRANSFER, usd!!.type)
        assertEquals(100.0, usd.amount, 0.001)
        assertEquals("USD", usd.currency)
        assertFalse(usd.merchant.contains("USD"))

        val eur = parse("Μεταφορά 100,00 EUR σε Μαρία")
        assertNotNull(eur)
        assertEquals(ParsedTransactionType.TRANSFER, eur!!.type)
        assertEquals(100.0, eur.amount, 0.001)
        assertEquals("EUR", eur.currency)
    }

    @Test
    fun `transaction code transfer with explicit currency and a tail keeps that currency`() {
        // "Χ" is the Greek debit code letter.
        val usd = parse("Χ 50,00 USD ΑΤΜ")
        assertNotNull(usd)
        assertEquals(ParsedTransactionType.TRANSFER, usd!!.type)
        assertEquals(50.0, usd.amount, 0.001)
        assertEquals("USD", usd.currency)
        assertEquals("ΑΤΜ", usd.merchant)

        val eur = parse("Χ 50,00 EUR ΑΤΜ 24")
        assertNotNull(eur)
        assertEquals(50.0, eur!!.amount, 0.001)
        assertEquals("EUR", eur.currency)
    }

    @Test
    fun `currency falls back to the home currency when none is written`() {
        val purchase = parse("Αγορά 12,50 στο SHOP")
        assertNotNull(purchase)
        assertEquals(12.50, purchase!!.amount, 0.001)
        assertEquals(HOME_CURRENCY, purchase.currency)
        assertEquals("SHOP", purchase.merchant)

        val transfer = parse("Μεταφορά 100,00 σε Μαρία")
        assertNotNull(transfer)
        assertEquals(100.0, transfer!!.amount, 0.001)
        assertEquals(HOME_CURRENCY, transfer.currency)

        val coded = parse("Χ 50,00 ΑΤΜ")
        assertNotNull(coded)
        assertEquals(50.0, coded!!.amount, 0.001)
        assertEquals(HOME_CURRENCY, coded.currency)
    }

    // ---- F-H1-d: resolveCurrency uses normalizeOrNull and keeps the home-currency fallback -----

    @Test
    fun `a currency token the normalizer cannot resolve falls back to the home currency`() {
        // Strict mock: only normalizeOrNull is stubbed, so calling the deprecated normalize()
        // would throw. Returning null must yield the home currency, never a silent EUR.
        val nullNormalizer = mockk<CurrencyNormalizer> {
            every { normalizeOrNull(any()) } returns null
        }
        val cleaner = mockk<MerchantCleaner> {
            every { clean(any()) } answers { firstArg() ?: "Unknown" }
        }
        val fallbackParser = GreekBankParser(nullNormalizer, cleaner, homeCurrency = HOME_CURRENCY)

        val result = fallbackParser.parse(
            title = "Ειδοποίηση",
            text = "Αγορά 12,50 USD στο SHOP",
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )

        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertEquals(HOME_CURRENCY, result.currency)
        assertEquals("SHOP", result.merchant)
    }

    // ---- Lead decision: thousands grouping in deposit / transfer patterns ----------------------

    @Test
    fun `greek deposit with european thousands grouping keeps the full amount`() {
        val result = parse("Κατάθεση 1.500,00 EUR")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(1500.0, result.amount, 0.001)
    }

    @Test
    fun `greek deposit with us thousands grouping keeps the full amount`() {
        val result = parse("Κατάθεση 1,500.00 EUR")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.DEPOSIT, result!!.type)
        assertEquals(1500.0, result.amount, 0.001)
    }

    /**
     * A lone separator followed by exactly three digits: ',' reads as grouping (1500, the
     * AmountUtils.parseAmount contract) and, since F-H1-a, GreekBankParser also reads '.' as the Greek
     * thousands separator (1500). Rationale for the changed dot row: it previously asserted 1.5, the
     * generic AmountUtils decimal reading, which is wrong for Greek notifications.
     */
    @Test
    fun `deposit lone separator with three digits reads as thousands grouping`() {
        val comma = parse("Κατάθεση 1,500 EUR")
        assertNotNull(comma)
        assertEquals(1500.0, comma!!.amount, 0.001)

        val dot = parse("Κατάθεση 1.500 EUR")
        assertNotNull(dot)
        assertEquals(1500.0, dot!!.amount, 0.001)
    }

    // ---- F-H1-a: dot followed by exactly three digits is a Greek thousands separator -----------

    @Test
    fun `purchase with dot thousands and merchant keeps the full amount`() {
        val dotted = parse("Αγορά 1.500 EUR στο SHOP")
        assertNotNull(dotted)
        assertEquals(ParsedTransactionType.PURCHASE, dotted!!.type)
        assertEquals(1500.0, dotted.amount, 0.001)
        assertEquals("SHOP", dotted.merchant)

        val full = parse("Αγορά 1.500,00 EUR στο SHOP")
        assertNotNull(full)
        assertEquals(1500.0, full!!.amount, 0.001)
    }

    @Test
    fun `two decimal digit dot amount stays a decimal`() {
        val result = parse("Αγορά 12.50 EUR στο SHOP")

        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
    }

    /**
     * Documents the existing comma rule (unchanged): "1,500" is grouping and reads as 1500 via
     * AmountUtils.parseAmount; it is NOT 1.5.
     */
    @Test
    fun `purchase with comma and three digits keeps the existing grouping reading`() {
        val result = parse("Αγορά 1,500 EUR στο SHOP")

        assertNotNull(result)
        assertEquals(1500.0, result!!.amount, 0.001)
    }

    @Test
    fun `multi group dot thousands read as an integer and respect the amount ceiling`() {
        val result = parse("Αγορά 12.345 EUR στο SHOP")
        assertNotNull(result)
        assertEquals(12345.0, result!!.amount, 0.001)

        // 1.234.567 is read as 1234567, which is above the 50000 parser ceiling -> rejected, never 1.234567.
        assertNull(parse("Αγορά 1.234.567 EUR στο SHOP"))
    }

    /**
     * Ceiling semantics are `amount > 50000` -> null, so 50000 itself is accepted. A dot followed by
     * exactly three digits is Greek thousands: "49.999" is 49999 (not 49.999) and "50.001" is 50001,
     * which is above the ceiling and rejected.
     */
    @Test
    fun `dot thousands around the 50000 ceiling accept at or below and reject above`() {
        val below = parse("Αγορά 49.999 EUR στο SHOP")
        assertNotNull(below)
        assertEquals(49999.0, below!!.amount, 0.001)

        val atCeiling = parse("Αγορά 50.000 EUR στο SHOP")
        assertNotNull(atCeiling)
        assertEquals(50000.0, atCeiling!!.amount, 0.001)

        assertNull(parse("Αγορά 50.001 EUR στο SHOP"))
    }

    /**
     * A leading-zero token is not Greek thousands grouping (GREEK_DOT_THOUSANDS requires [1-9] first),
     * so "0.500" stays the decimal 0.5 via AmountUtils.parseAmount, never 500.
     */
    @Test
    fun `leading zero dot three digits stays a decimal`() {
        val result = parse("Αγορά 0.500 EUR στο SHOP")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(0.5, result.amount, 0.001)
        assertEquals("SHOP", result.merchant)
    }

    @Test
    fun `transaction code transfer with thousands grouping keeps the full amount`() {
        // "Χ" is the Greek debit code letter.
        val result = parse("Χ 1.234,56")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(1234.56, result.amount, 0.001)
    }

    @Test
    fun `bare amount transfer with thousands grouping does not start mid-number`() {
        val result = parse("1.234,56 στο SHOP")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.TRANSFER, result!!.type)
        assertEquals(1234.56, result.amount, 0.001)
        assertEquals("SHOP", result.merchant)
    }

    /** A number with more than two fraction digits is rejected whole; its tail is never an amount. */
    @Test
    fun `bare amount transfer never matches inside a longer number`() {
        assertNull(parse("Σύνολο 1.234,5678 στο SHOP"))
    }

    @Test
    fun `plain deposits and transfers are unchanged`() {
        val deposit = parse("Κατάθεση €500.00 στον λογαριασμό σας")
        assertNotNull(deposit)
        assertEquals(ParsedTransactionType.DEPOSIT, deposit!!.type)
        assertEquals(500.0, deposit.amount, 0.001)

        val credited = parse("Amount credited 250.00 EUR to your account")
        assertNotNull(credited)
        assertEquals(ParsedTransactionType.DEPOSIT, credited!!.type)
        assertEquals(250.0, credited.amount, 0.001)

        val transfer = parse("Μεταφορά 100,00 EUR σε Μαρία")
        assertNotNull(transfer)
        assertEquals(ParsedTransactionType.TRANSFER, transfer!!.type)
        assertEquals(100.0, transfer.amount, 0.001)
    }

    // ---- Currency letter boundary: a merchant starting with a currency code is not a currency --
    // Home currency is GBP, so a wrongly read EUR/USD would show up as a currency mismatch.

    /**
     * PURCHASE_PATTERNS[0]: before the letter boundary "Eur" of "Eurospar" was read as the currency
     * EUR and the merchant was truncated to "ospar".
     */
    @Test
    fun `purchase whose merchant starts with a currency code keeps home currency and full merchant`() {
        val result = parse("Αγορά 12,50 Eurospar")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(12.50, result.amount, 0.001)
        assertEquals(HOME_CURRENCY, result.currency)
        assertEquals("Eurospar", result.merchant)
    }

    @Test
    fun `purchase with a real EUR token before a preposition still reads EUR`() {
        val result = parse("Αγορά 12,50 EUR στο SHOP")

        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertEquals("EUR", result.currency)
        assertEquals("SHOP", result.merchant)
    }

    /** TRANSFER_PATTERNS[0] and [1] share the same letter boundary. */
    @Test
    fun `transfers whose tail starts with a currency code keep home currency and full tail`() {
        val transfer = parse("Μεταφορά 100,00 Eurospar")
        assertNotNull(transfer)
        assertEquals(ParsedTransactionType.TRANSFER, transfer!!.type)
        assertEquals(100.0, transfer.amount, 0.001)
        assertEquals(HOME_CURRENCY, transfer.currency)
        assertEquals("Eurospar", transfer.merchant)

        // "Χ" is the Greek debit code letter.
        val coded = parse("Χ 50,00 Eurospar")
        assertNotNull(coded)
        assertEquals(ParsedTransactionType.TRANSFER, coded!!.type)
        assertEquals(50.0, coded.amount, 0.001)
        assertEquals(HOME_CURRENCY, coded.currency)
        assertEquals("Eurospar", coded.merchant)
    }

    // ---- C1: a card number or store number is never the amount ---------------------------------
    // Before the fix the bare "amount στο MERCHANT" transfer pattern read the masked card number
    // ("4***1234" -> 1234) because the real amount was not directly followed by the keyword.

    @Test
    fun `purchase with amount word and card number keeps the real amount and currency`() {
        val result = parse("Αγορά ποσού 15,20 EUR με την κάρτα 4***1234 στο ΜΑΣΟΥΤΗΣ")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(15.20, result.amount, 0.001)
        assertEquals("EUR", result.currency)
        assertEquals("ΜΑΣΟΥΤΗΣ", result.merchant)
    }

    @Test
    fun `purchase with fully masked card number keeps the real amount`() {
        val result = parse("Αγορά ποσού 15,20 EUR με την κάρτα ****1234 στο ΜΑΣΟΥΤΗΣ")

        assertNotNull(result)
        assertEquals(15.20, result!!.amount, 0.001)
        assertEquals("EUR", result.currency)
    }

    @Test
    fun `masked card number with no amount in the message is never an amount`() {
        assertNull(parse("Πληρωμή με κάρτα *1234 στο ΜΑΣΟΥΤΗΣ"))
    }

    @Test
    fun `card charge with masked card number still reads the real amount`() {
        val result = parse("Χρέωση κάρτας *1234: 45,00 EUR - SKLAVENITIS")

        assertNotNull(result)
        assertEquals(ParsedTransactionType.PURCHASE, result!!.type)
        assertEquals(45.00, result.amount, 0.001)
        assertEquals("EUR", result.currency)
    }

    @Test
    fun `bare store number is never an amount`() {
        // Both prepositions present, so the bare-amount transfer pattern is actually exercised.
        assertNull(parse("Αγορά στο ΚΑΤΑΣΤΗΜΑ 24 στο ΣΥΝΤΑΓΜΑ"))
    }

    @Test
    fun `masked account number before a credited keyword is never an amount`() {
        // Isolates the '*' lookbehind of the amount: "****1234" must not be read as money.
        assertNull(parse("Account ****1234 credited with EUR 500.00"))
    }

    @Test
    fun `bare integer before a preposition needs a currency marker`() {
        assertNull(parse("500 στο SHOP"))

        val withCode = parse("500 EUR στο SHOP")
        assertNotNull(withCode)
        assertEquals(500.0, withCode!!.amount, 0.001)
    }

    @Test
    fun `transaction code in the middle of free text does not start an amount`() {
        assertNull(parse("Ref P 77 κάτι άλλο"))
    }

    // ---- Trailing currency token on the bare-amount transfer pattern (home currency is GBP) ----

    @Test
    fun `bare amount transfer captures a trailing USD token`() {
        val result = parse("12,50 USD στο SHOP")

        assertNotNull(result)
        assertEquals(12.50, result!!.amount, 0.001)
        assertEquals("USD", result.currency)
    }

    @Test
    fun `bare amount transfer captures a trailing GBP token on an integer amount`() {
        val result = parse("50 GBP στο SHOP")

        assertNotNull(result)
        assertEquals(50.0, result!!.amount, 0.001)
        assertEquals("GBP", result.currency)
    }

    @Test
    fun `card prefixed bare amount with USD keeps USD and the amount`() {
        val result = parse("Κάρτα *1234: 30,00 USD στο AMAZON")

        assertNotNull(result)
        assertEquals(30.00, result!!.amount, 0.001)
        assertEquals("USD", result.currency)
    }

    @Test
    fun `bare dot thousands integer before a preposition still parses`() {
        val result = parse("1.500 στο SHOP")

        assertNotNull(result)
        assertEquals(1500.0, result!!.amount, 0.001)
    }

    // ---- Transaction code after a prefix, a newline or a date (not only at field start) -------

    @Test
    fun `transaction code after a bank prefix is read`() {
        val result = parse("NBG: Χ 50,00 EUR SKLAVENITIS")

        assertNotNull(result)
        assertEquals(50.00, result!!.amount, 0.001)
        assertEquals("EUR", result.currency)
    }

    @Test
    fun `transaction code on a later line of the big text is read`() {
        val result = parser.parse(
            title = "Ειδοποίηση",
            text = null,
            bigText = "Κίνηση λογαριασμού\nΧ 50,00 EUR SKLAVENITIS",
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )

        assertNotNull(result)
        assertEquals(50.00, result!!.amount, 0.001)
        assertEquals("EUR", result.currency)
    }

    @Test
    fun `credit code after a date prefix is read`() {
        val result = parse("21/10 Π 100,00 EUR ΜΙΣΘΟΔΟΣΙΑ")

        assertNotNull(result)
        assertEquals(100.00, result!!.amount, 0.001)
        assertEquals("EUR", result.currency)
    }
}
