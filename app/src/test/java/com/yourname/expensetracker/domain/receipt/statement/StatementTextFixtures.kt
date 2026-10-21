package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.util.MerchantCleaner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Synthetic statement text, one list of lines per page. Every name, merchant, IBAN, date, branch
 * code, postal code and amount here is invented (dates are in 2031); none is taken from a real
 * statement.
 */
object StatementTextFixtures {
    val ZONE: ZoneId = ZoneId.systemDefault()

    /** Fixed clock for the parsers: after every fixture date. */
    val NOW_MS: Long = LocalDate.of(2031, 12, 31).atStartOfDay(ZONE).toInstant().toEpochMilli()

    fun context(home: String? = "EUR"): StatementContext =
        StatementContext(MerchantCleaner(), NOW_MS, ZONE, home)

    fun day(year: Int, month: Int, day: Int): Long =
        LocalDate.of(year, month, day).atStartOfDay(ZONE).toInstant().toEpochMilli()

    fun moment(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute, second).atZone(ZONE).toInstant().toEpochMilli()

    // Invented IBANs (GR = 27 characters).
    const val OWN_IBAN = "GR9600000000000000000001234"
    const val RECIPIENT_IBAN_1 = "GR1100000000000000000005678"
    const val RECIPIENT_IBAN_2 = "GR1100000000000000000009999"
    const val RECIPIENT_IBAN_WRAPPED = "GR1100000000000000000007778"
    const val RECIPIENT_IBAN_4 = "GR1100000000000000000004321"
    const val RECIPIENT_IBAN_5 = "GR1100000000000000000002468"

    // ---------------------------------------------------------------- Revolut

    val REVOLUT_PAGES: List<List<String>> = listOf(
        listOf(
            "EUR Statement",
            "Generated on Dec 20, 2031",
            "Revolut Bank UAB",
            "Page  of 1 2",
            "JOHN DOE",
            "Balance summary",
            "Product Opening balance Money out Money in",
            "Closing",
            "balance",
            "Account (Current Account) €7.00 €539.70 €90.00 €19.50",
            "Total €7.00 €539.70 €90.00 €19.50",
            "Account transactions from March 1, 2031 to December 20, 2031",
            "Date Description Money out Money in Balance",
            "Mar 10, 2031 Top-up by *1111 €12.00 €19.00",
            "From: *1111",
            "Mar 10, 2031 Top-up by *1111 €12.00 €31.00",
            "From: *1111",
            "Mar 10, 2031 Top-up by *1111 €12.00 €43.00",
            "From: *1111",
            "Mar 11, 2031 Transfer to MARIA EXAMPLE €21.50 €21.50",
            "Reference: To MARIA EXAMPLE",
            "To: MARIA EXAMPLE",
            "Mar 12, 2031 Some Cafe €6.40 €15.05",
            "Reference: secret-ref-123",
            "Fee: €0.05 €6.40",
            "\$7.10",
            "To: Some Cafe, Springfield",
            "Card: 123456******7890",
            "Mar 13, 2031 Transfer from ANNA EXAMPLE €31.00 €46.05",
            "Reference: Lunch",
            "From: ANNA EXAMPLE"
        ),
        listOf(
            "EUR Statement",
            "Generated on Dec 20, 2031",
            "Revolut Bank UAB",
            "Page  of 2 2",
            "Date Description Money out Money in Balance",
            "Mar 14, 2031 Exchanged to EUR €11.00 €57.05",
            "\$12.10",
            "Mar 15, 2031 Some Shop €7.25 €49.80",
            "To: Some Shop, Springfield",
            "Card: 123456******7890",
            "Mar 16, 2031 Transfer to MARIA EXAMPLE €500.00 €12.00",
            "Reference: To MARIA EXAMPLE",
            "To: MARIA EXAMPLE",
            "Mar 17, 2031 Some Shop €4.50 €7.50",
            "To: Some Shop, Springfield",
            "Mar 18, 2031 Top-up by *1111 €12.00 €19.50",
            "Reference:",
            "From: *1111",
            "IBAN",
            "BIC",
            "LT000000000000000001",
            "EXAMPLE1",
            "Some Street 1",
            "00000",
            "Example City",
            "Pending from March 1, 2031 to December 20, 2031",
            "Start date Description Money out Money in",
            "Mar 19, 2031 Some Hold €1.00",
            "To: Some Hold, Springfield",
            "Card: 123456******7890",
            "Reverted from March 1, 2031 to December 20, 2031",
            "Start date Description Money out Money in",
            "Mar 20, 2031 Transfer to MARIA EXAMPLE €2.00",
            "Reference: To MARIA EXAMPLE",
            "To: MARIA EXAMPLE"
        )
    )

    /** Header and page furniture only: no rows. */
    val REVOLUT_JUNK_PAGES: List<List<String>> = listOf(
        listOf(
            "EUR Statement",
            "Generated on Dec 20, 2031",
            "Revolut Bank UAB",
            "Page  of 1 1",
            "JOHN DOE",
            "Balance summary",
            "Account (Current Account) €0.00 €0.00 €0.00 €0.00",
            "Account transactions from March 1, 2031 to December 20, 2031",
            "Date Description Money out Money in Balance",
            "IBAN",
            "LT000000000000000001",
            "Some Street 1",
            "00000"
        )
    )

    // -------------------------------------------------------------------- NBG

    private val NBG_FURNITURE_PAGE_1 = listOf(
        "Εθνική Τράπεζα της Ελλάδος Α.Ε.",
        "Αρ. Γ.Ε.Μ.Η.: 000000000 | Αρ. Μ.Α.Ε.: 0000/00/β/00/00 1 από 2 Ημ/νία Εκτύπωσης 20/12/2031 10:00:00",
        "(+30) 210 0000 000 | www.nbg.gr"
    )

    private val NBG_FURNITURE_PAGE_2 = listOf(
        "Εθνική Τράπεζα της Ελλάδος Α.Ε.",
        "Αρ. Γ.Ε.Μ.Η.: 000000000 | Αρ. Μ.Α.Ε.: 0000/00/β/00/00 2 από 2 Ημ/νία Εκτύπωσης 20/12/2031 10:00:00",
        "(+30) 210 0000 000 | www.nbg.gr"
    )

    private val NBG_COLUMN_HEADER =
        "Ημερομηνία / Ώρα Valeur Κατάστημα Συν Περιγραφή Χ/Π Ποσό Λογιστικό Υπόλοιπο"

    private val NBG_ACCOUNT_BLOCK = listOf(
        "Κίνηση Λογαριασμού 00000000000 από 1/1/2031 έως 20/12/2031",
        "Είδος Λογαριασμού: ΑΠΛΟ ΤΑΜΙΕΥΤΗΡΙΟ Λογιστικό Υπόλοιπο: 100,00 €",
        "ΙΒΑΝ Λογαριασμού: $OWN_IBAN Αλλες Δεσμεύσεις: 0,00 €",
        "Δικαιούχος: JOHN DOE Διαθέσιμο Υπόλοιπο: 100,00 €"
    )

    val NBG_PAGES: List<List<String>> = listOf(
        NBG_FURNITURE_PAGE_1 + NBG_ACCOUNT_BLOCK + listOf(
            NBG_COLUMN_HEADER,
            "19/12/2031 09:15:42 19/12/2031 111 013 MARIA EXAMPLE Π 15,00 100,00",
            "17/12/2031 14:05:09 16/12/2031 222 040 SOME SHOP Χ -0,85 85,00",
            "15/12/2031 02:31:55 13/12/2031 222 040 SOME SHOP Χ -3,70 85,85",
            "15/12/2031 02:31:55 13/12/2031 222 040 SOME SHOP Χ -3,70 89,55",
            "13/12/2031 11:20:36 13/12/2031 333 064 JOHN DOE",
            "SMITH EXAMPLE Π 2,00 91,55",
            "13/12/2031 11:18:07 13/12/2031 333 064 JOHN DOE SMITH EXAMPLE",
            "Χ 1.250,00 88,40",
            "10/12/2031 21:44:18 09/12/2031 222 040 SOME SHOP Π 6,15 241,15",
            "08/12/2031 10:00:00 08/12/2031 444 076 SOME BENEFIT Π 25,00 260,00",
            "07/12/2031 10:00:00 07/12/2031 444 999 UNKNOWN THING Χ -3,00 257,00",
            "06/12/2031 10:00:00 06/12/2031 222 040 ACME MARKET WITH A VERY LONG"
        ),
        NBG_FURNITURE_PAGE_2 + listOf(
            NBG_COLUMN_HEADER,
            "DESCRIPTION NAME Χ -12,34 244,66",
            "05/12/2031 10:00:00 05/12/2031 444 068 MARIA EXAMPLE Χ -50,00 194,66",
            "Π 5,00 199,66",
            "04/12/2031 10:00:00 04/12/2031 444 022 $OWN_IBAN Χ -5,00 194,66"
        )
    )

    /** Account header block, column header and page furniture only: no rows. */
    val NBG_JUNK_PAGES: List<List<String>> = listOf(
        NBG_FURNITURE_PAGE_1 + NBG_ACCOUNT_BLOCK + listOf(NBG_COLUMN_HEADER),
        NBG_FURNITURE_PAGE_2 + listOf(NBG_COLUMN_HEADER)
    )

    /** An accounts-overview page: shares the bank name line but is not a statement. */
    val ACCOUNTS_OVERVIEW_PAGES: List<List<String>> = listOf(
        listOf(
            "Εθνική Τράπεζα της Ελλάδος Α.Ε.",
            "Αρ. Γ.Ε.Μ.Η.: 000000000 | Αρ. Μ.Α.Ε.: 0000/00/β/00/00 1 από 1 Ημ/νία Εκτύπωσης 20/12/2031 10:00:00",
            "(+30) 210 0000 000 | www.nbg.gr",
            "Υπόλοιπα Λογαριασμών",
            "DOE",
            "IBAN Είδος Λογαριασμού Φιλική Ονομασία Νόμ Διαθέσιμο Υπόλοιπο Λογιστικό Υπόλοιπο",
            "$OWN_IBAN ΑΠΛΟ ΤΑΜΙΕΥΤΗΡΙΟ EUR 12,00 12,00",
            "$RECIPIENT_IBAN_1 ΑΠΛΟ ΤΑΜΙΕΥΤΗΡΙΟ Αποταμίευση EUR 321,00 321,00"
        )
    )

    // ------------------------------------------------------------ Eurobank card

    private val EUROBANK_CARD_FOOTER_1 = listOf(
        "Για οποιαδήποτε πληροφορία τηλεφωνήστε στο EuroPhone Banking 24ώρες το 24ωρο στο 2100000000 ή στείλτε email",
        "στο ebanking@eurobank.gr.",
        "ΤΡΑΠΕΖΑ EUROBANK A.E.",
        "Οδός Δοκιμής 1, 000 00 Αθήνα",
        "Αρ. Γ.Ε.ΜΗ.    000000000000",
        "Σελίδα 1/2                             Ημ/νία και ώρα αντιγράφου: 20/12/2031 17:00:00"
    )

    private val EUROBANK_CARD_TABLE_HEADER = listOf(
        "ΗΜ/ΝΙΑ",
        "ΣΥΝΑΛΛΑΓΗΣ",
        "ΗΜ/ΝΙΑ",
        "ΑΞΙΑΣ",
        "ΠΕΡΙΓΡΑΦΗ ΠΟΣΟ"
    )

    private val EUROBANK_CARD_HEADER_BLOCK = listOf(
        "Κινήσεις Κάρτας",
        "ΟΝΟΜΑΤΕΠΩΝΥΜΟ: JOHN DOE",
        "ΔΙΕΥΘΥΝΣΗ: SOME TOWN 00000",
        "ΤΥΠΟΣ ΚΑΡΤΑΣ: DEBIT MASTERCARD",
        "ΑΡΙΘΜΟΣ ΚΑΡΤΑΣ: 0000 00** **** 0000",
        "ΗΜΕΡΟΜΗΝΙΑ ΛΗΞΗΣ: 12/2035",
        "ΗΜΕΡ. ΟΡΙΟ ΑΓΟΡΩΝ: 1.500,00 €",
        "ΑΡΙΘΜΟΣ ΠΡΩΤ. ΣΥΝΔ. ΛΟΓΑΡΙΑΣΜΟΥ",
        "(IBAN):",
        OWN_IBAN,
        "ΚΡΙΤΗΡΙΑ ΑΝΑΖΗΤΗΣΗΣ: Χρονική Περίοδος από: 01/01/2031 - έως 20/12/2031"
    )

    val EUROBANK_CARD_PAGES: List<List<String>> = listOf(
        EUROBANK_CARD_HEADER_BLOCK + EUROBANK_CARD_TABLE_HEADER + listOf(
            "11/11/2031 13/11/2031 SOME SHOP -3,00",
            "11/11/2031 13/11/2031 SOME SHOP -3,00",
            "11/11/2031 13/11/2031 SOME SHOP -3,00",
            "10/11/2031 11/11/2031 OTHER SHOP LTD -2,10",
            "09/11/2031 10/11/2031 REFUND SOME SHOP 12,50",
            "08/11/2031 09/11/2031 SHOP / 210 949 -1.234,56"
        ) + EUROBANK_CARD_FOOTER_1,
        EUROBANK_CARD_TABLE_HEADER + listOf(
            "07/11/2031 08/11/2031 PAYPAL *SOMESHOP -5,99",
            "Σελίδα 2/2                             Ημ/νία και ώρα αντιγράφου: 20/12/2031 17:00:00"
        )
    )

    /** The header block (postal code, limit, search period), table header and footer only. */
    val EUROBANK_CARD_JUNK_PAGES: List<List<String>> = listOf(
        EUROBANK_CARD_HEADER_BLOCK + EUROBANK_CARD_TABLE_HEADER + EUROBANK_CARD_FOOTER_1
    )

    // ------------------------------------------------------- Eurobank transfers

    private val TRANSFERS_HEADER = listOf(
        "Ιστορικό Μεταφορών σε άλλη τράπεζα",
        "ΗΜ/ΝΙΑ ΕΚΤΕΛΕΣΗΣ ΛΟΓΑΡΙΑΣΜΟΣ/ ΚΑΡΤΑ ΧΡΕΩΣΗΣ ΛΟΓΑΡΙΑΣΜΟΣ / ΙBAN ΠΑΡΑΛΗΠΤΗ ΤΡΑΠΕΖΑ",
        "ΠΑΡΑΛΗΠΤΗ",
        "ΠΟΣΟ ΝΟΜΙΣΜΑ ΚΑΤΑΣΤΑΣΗ"
    )

    private val TRANSFERS_FOOTER_1 = listOf(
        "Για οποιαδήποτε πληροφορία τηλεφωνήστε στο EuroPhone Banking 24ώρες το 24ωρο στο 2100000000 ή στείλτε email",
        "στο ebanking@eurobank.gr.",
        "ΤΡΑΠΕΖΑ EUROBANK A.E.",
        "Σελίδα 1/2                             Ημ/νία και ώρα αντιγράφου: 20/12/2031 17:00:00"
    )

    val EUROBANK_TRANSFERS_PAGES: List<List<String>> = listOf(
        TRANSFERS_HEADER + listOf(
            "07/12/2031 $OWN_IBAN $RECIPIENT_IBAN_1 SOME BANK SA 11,00 EUR Πιστώθηκε στον",
            "δικαιούχο",
            "05/12/2031 $OWN_IBAN $RECIPIENT_IBAN_2 OTHER BANK 4,00 EUR Ακυρώθηκε",
            "20/11/2031 $OWN_IBAN ${RECIPIENT_IBAN_WRAPPED.dropLast(1)}",
            RECIPIENT_IBAN_WRAPPED.takeLast(1),
            "EUROBANK TEST SA 6,75 EUR Πιστώθηκε στον",
            "δικαιούχο",
            "15/11/2031 $OWN_IBAN $RECIPIENT_IBAN_4 THIRD BANK 2,90 EUR Σε επεξεργασία"
        ) + TRANSFERS_FOOTER_1,
        TRANSFERS_HEADER + listOf(
            "01/10/2031 $OWN_IBAN $RECIPIENT_IBAN_5 SOME BANK SA 8,25 EUR Πιστώθηκε στον",
            "δικαιούχο",
            "Σελίδα 2/2                             Ημ/νία και ώρα αντιγράφου: 20/12/2031 17:00:00"
        )
    )

    /** Title, column headers and footer only: no rows. */
    val EUROBANK_TRANSFERS_JUNK_PAGES: List<List<String>> = listOf(TRANSFERS_HEADER + TRANSFERS_FOOTER_1)
}
