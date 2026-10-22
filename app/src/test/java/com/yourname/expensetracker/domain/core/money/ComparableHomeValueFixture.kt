package com.yourname.expensetracker.domain.core.money

import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType

/**
 * Shared fixture for the comparable-home-value (CV) rule. Owned by the rule: the
 * Kotlin owner ([ComparableHomeValue]) and its SQL mirror
 * (`ExpenseDao.COMPARABLE_HOME_VALUE_E_SQL`) are both tested against these rows,
 * so neither mirror may change without this file.
 *
 * Ids are unique across the whole fixture so every row can be seeded into one
 * database and looked up per home currency. Dates are `BASE_DATE + id * 1000`
 * unless a case overrides them. The AE-n labels refer to
 * `docs/features/money-correctness/domain-spec.md` section 13.
 */
object ComparableHomeValueFixture {

    const val BASE_DATE: Long = 1_700_000_000_000L

    val EUR: CurrencyCode = CurrencyCode.EUR
    val USD: CurrencyCode = CurrencyCode.USD

    /**
     * One row, the home currency it is evaluated against, and the expected outcome.
     * [expectedCv] null means "no comparable value" (Kotlin `None`, SQL `NULL`).
     * [expectedReason] is set exactly when [expectedCv] is null.
     */
    data class Case(
        val label: String,
        val home: CurrencyCode,
        val expense: Expense,
        val expectedCv: Double?,
        val expectedReason: NoComparableValueReason? = null
    ) {
        init {
            require((expectedCv == null) == (expectedReason != null)) {
                "Case $label: reason must be set exactly when expectedCv is null"
            }
        }
    }

    /** Inclusive min/max pairs exercised by both mirrors. Null means "no bound". */
    val bounds: List<Pair<Double?, Double?>> = listOf(
        15.0 to null,       // AE-1: min=15
        null to 25.0,       // AE-10: max=25 (boundary value 25.0 is included)
        0.0 to null,        // AE-9: min=0 includes zero-valued rows
        0.01 to null,       // AE-9: min=0.01 excludes zero-valued rows
        null to 19.0,       // AE-2: max=19 includes C1 (18.40)
        19.0 to null,       // AE-2: min=19 excludes C1 (18.40)
        18.4 to null,       // AE-26: min=18.40 includes 18.400000000000002
        null to 18.4,       // AE-26: max=18.40 excludes 18.400000000000002
        25.0 to 25.0,       // inclusive on both ends
        -5.0 to -5.0,       // AE-17: negative legacy home-currency amount
        50.0 to 50.0        // AE-3: min=50 includes L1 (H=USD)
    )

    @Suppress("LongParameterList")
    private fun row(
        id: Long,
        currency: String,
        amount: Double,
        base: Triple<Double, String, Double>,
        isNotMine: Boolean = false,
        isShared: Boolean = false,
        myShareAmount: Double? = null,
        sharePercentage: Int? = null,
        date: Long = BASE_DATE + id * 1_000L
    ): Expense = Expense(
        id = id,
        amount = amount,
        currency = currency,
        merchant = "M$id",
        transactionType = TransactionType.PURCHASE,
        date = date,
        isNotMine = isNotMine,
        isSharedExpense = isShared,
        myShareAmount = myShareAmount,
        mySharePercentage = sharePercentage,
        baseAmount = base.first,
        baseCurrency = base.second,
        exchangeRateUsed = base.third
    )

    private fun base(amount: Double, currency: String, rate: Double) = Triple(amount, currency, rate)

    private val NO_SNAPSHOT_EUR = base(0.0, "EUR", 0.0)
    private val NO_SNAPSHOT_EMPTY = base(0.0, "", 0.0)

    /** Every case. Rows are evaluated against their own [Case.home]. */
    val cases: List<Case> = listOf(
        // ── H = EUR ──────────────────────────────────────────────────────────
        // AE-1: no-rate foreign rows sort last and are never compared by raw amount.
        Case("AE-1 E1 identity EUR 15", EUR, row(1, "EUR", 15.0, base(15.0, "EUR", 1.0)), 15.0),
        Case("AE-1 E2 identity EUR 25", EUR, row(2, "EUR", 25.0, base(25.0, "EUR", 1.0)), 25.0),
        Case("AE-1 U1 USD 20 legacy default", EUR, row(3, "USD", 20.0, NO_SNAPSHOT_EUR),
            null, NoComparableValueReason.NO_SNAPSHOT),
        Case("AE-1 U1 USD 20 sentinel", EUR, row(4, "USD", 20.0, NO_SNAPSHOT_EMPTY),
            null, NoComparableValueReason.NO_SNAPSHOT),
        // AE-2: converted foreign row compares by its snapshot, not its raw amount.
        Case("AE-2 C1 USD 20 converted", EUR, row(5, "USD", 20.0, base(18.40, "EUR", 0.92)), 18.40),
        // AE-4: legacy default snapshot never makes a foreign row comparable.
        Case("AE-4 L2 EUR 40 legacy default", EUR, row(6, "EUR", 40.0, NO_SNAPSHOT_EUR), 40.0),
        Case("AE-4 L3 USD 50 legacy default", EUR, row(7, "USD", 50.0, NO_SNAPSHOT_EUR),
            null, NoComparableValueReason.NO_SNAPSHOT),
        // AE-5: home unresolved at create.
        Case("AE-5 X1 EUR 12 sentinel", EUR, row(8, "EUR", 12.0, NO_SNAPSHOT_EMPTY), 12.0),
        Case("AE-5 X2 USD 12 sentinel", EUR, row(9, "USD", 12.0, NO_SNAPSHOT_EMPTY),
            null, NoComparableValueReason.NO_SNAPSHOT),
        // AE-7: Rescue-made foreign identity snapshot.
        Case("AE-7 R1 USD 20 foreign identity", EUR, row(10, "USD", 20.0, base(20.0, "USD", 1.0)),
            null, NoComparableValueReason.SNAPSHOT_OTHER_CURRENCY),
        Case("AE-7 R2 EUR 10", EUR, row(11, "EUR", 10.0, NO_SNAPSHOT_EMPTY), 10.0),
        // AE-9: not-mine is always comparable, at zero.
        Case("AE-9 N1 USD 50 not mine", EUR, row(12, "USD", 50.0, NO_SNAPSHOT_EUR, isNotMine = true), 0.0),
        Case("AE-9 E1 EUR 5", EUR, row(13, "EUR", 5.0, base(5.0, "EUR", 1.0)), 5.0),
        // AE-10: shared rows.
        Case("AE-10 S1 USD shared myShare 50", EUR,
            row(14, "USD", 100.0, base(50.0, "EUR", 0.5), isShared = true, myShareAmount = 50.0), 25.0),
        Case("AE-10 S2 USD shared pct 50", EUR,
            row(15, "USD", 100.0, base(92.0, "EUR", 0.92), isShared = true, sharePercentage = 50), 46.0),
        Case("AE-10 S3 USD shared sentinel", EUR,
            row(16, "USD", 100.0, NO_SNAPSHOT_EMPTY, isShared = true, myShareAmount = 50.0),
            null, NoComparableValueReason.NO_SNAPSHOT),
        Case("AE-10 S4 EUR shared pct 25", EUR,
            row(17, "EUR", 80.0, NO_SNAPSHOT_EUR, isShared = true, sharePercentage = 25), 20.0),
        // AE-17: legacy oddities.
        Case("AE-17 Q2 EUR -5", EUR, row(18, "EUR", -5.0, NO_SNAPSHOT_EUR), -5.0),
        Case("AE-17 Q3 USD -5 negative snapshot", EUR, row(19, "USD", -5.0, base(-4.6, "EUR", 0.92)),
            null, NoComparableValueReason.NO_SNAPSHOT),
        // Home-currency snapshot with an unusable rate.
        Case("rate zero", EUR, row(20, "USD", 10.0, base(9.0, "EUR", 0.0)),
            null, NoComparableValueReason.SNAPSHOT_RATE_INVALID),
        Case("rate negative", EUR, row(21, "USD", 10.0, base(9.0, "EUR", -0.5)),
            null, NoComparableValueReason.SNAPSHOT_RATE_INVALID),
        // AE-26: unrounded legacy snapshot is kept as-is.
        Case("AE-26 legacy unrounded snapshot", EUR,
            row(22, "USD", 20.0, base(18.400000000000002, "EUR", 0.92)), 18.400000000000002),
        // Shared branch variants.
        Case("branch 2 shared 0 percent", EUR,
            row(23, "EUR", 30.0, NO_SNAPSHOT_EUR, isShared = true, sharePercentage = 0), 0.0),
        Case("branch 2 shared myShare wins over pct", EUR,
            row(24, "EUR", 80.0, NO_SNAPSHOT_EUR, isShared = true, myShareAmount = 30.5, sharePercentage = 10), 30.5),
        Case("branch 3 shared without share fields", EUR,
            row(25, "USD", 100.0, base(92.0, "EUR", 0.92), isShared = true), 92.0),
        Case("branch 3 myShare ignored when not shared", EUR,
            row(26, "USD", 10.0, base(9.0, "EUR", 0.9), myShareAmount = 5.0), 9.0),
        Case("branch 3 myShare wins over pct", EUR,
            row(27, "USD", 100.0, base(92.0, "EUR", 0.92), isShared = true, myShareAmount = 50.0, sharePercentage = 10),
            50.0 * 0.92),
        Case("branch 2 negative zero myShare", EUR,
            row(28, "EUR", 10.0, NO_SNAPSHOT_EUR, isShared = true, myShareAmount = -0.0), 0.0),
        Case("not mine ignores snapshot and currency", EUR,
            row(29, "JPY", 1000.0, base(500.0, "GBP", 0.5), isNotMine = true), 0.0),

        // ── H = USD ──────────────────────────────────────────────────────────
        // AE-3: legacy home-currency row compares by raw amount.
        Case("AE-3 L1 USD 50 legacy default", USD, row(30, "USD", 50.0, NO_SNAPSHOT_EUR), 50.0),
        // AE-4: same legacy row in a foreign currency.
        Case("AE-4 L2 EUR 40 legacy default", USD, row(31, "EUR", 40.0, NO_SNAPSHOT_EUR),
            null, NoComparableValueReason.NO_SNAPSHOT),
        // AE-6: after a home switch EUR to USD.
        Case("AE-6 A EUR 100 old-home snapshot", USD, row(32, "EUR", 100.0, base(100.0, "EUR", 1.0)),
            null, NoComparableValueReason.SNAPSHOT_OTHER_CURRENCY),
        Case("AE-6 B USD 30 recorded under EUR", USD, row(33, "USD", 30.0, base(27.78, "EUR", 0.926)), 30.0),
        Case("AE-6 C USD 95 identity under USD", USD, row(34, "USD", 95.0, base(95.0, "USD", 1.0)), 95.0),
        // AE-17: lowercase currency is not the home currency.
        Case("AE-17 Q1 usd 10 legacy default", USD, row(35, "usd", 10.0, NO_SNAPSHOT_EUR),
            null, NoComparableValueReason.NO_SNAPSHOT),
        Case("lowercase row currency with valid USD snapshot", USD, row(36, "usd", 10.0, base(10.0, "USD", 1.0)), 10.0),
        Case("lowercase snapshot currency is not home", USD, row(37, "EUR", 10.0, base(10.0, "usd", 1.0)),
            null, NoComparableValueReason.SNAPSHOT_OTHER_CURRENCY),
        // Foreign row snapshotted in USD.
        Case("foreign row valid for USD", USD, row(38, "EUR", 100.0, base(108.0, "USD", 1.08)), 108.0)
    )

    /** AE-6 reversed: the same rows 32..34 evaluated against EUR again. */
    val ae6BackToEur: List<Case> = listOf(
        Case("AE-6 A EUR 100 back to EUR", EUR, row(32, "EUR", 100.0, base(100.0, "EUR", 1.0)), 100.0),
        Case("AE-6 B USD 30 back to EUR", EUR, row(33, "USD", 30.0, base(27.78, "EUR", 0.926)), 27.78),
        Case("AE-6 C USD 95 back to EUR", EUR, row(34, "USD", 95.0, base(95.0, "USD", 1.0)),
            null, NoComparableValueReason.SNAPSHOT_OTHER_CURRENCY)
    )

    /** AE-16: three rows with an identical date, ids 7, 8, 9. */
    val sameDateRows: List<Expense> = listOf(
        row(7, "EUR", 10.0, base(10.0, "EUR", 1.0), date = BASE_DATE),
        row(8, "EUR", 10.0, base(10.0, "EUR", 1.0), date = BASE_DATE),
        row(9, "EUR", 10.0, base(10.0, "EUR", 1.0), date = BASE_DATE)
    )

    /** Rows for the comparator tests: equal CV with differing dates and ids, plus None rows. */
    val orderingRows: List<Expense> = listOf(
        row(101, "EUR", 10.0, base(10.0, "EUR", 1.0), date = BASE_DATE + 10),
        row(102, "EUR", 10.0, base(10.0, "EUR", 1.0), date = BASE_DATE + 20),
        row(103, "EUR", 10.0, base(10.0, "EUR", 1.0), date = BASE_DATE + 20),
        row(104, "EUR", 25.0, base(25.0, "EUR", 1.0), date = BASE_DATE + 5),
        row(105, "EUR", 5.0, base(5.0, "EUR", 1.0), date = BASE_DATE + 5),
        row(106, "USD", 99.0, NO_SNAPSHOT_EMPTY, date = BASE_DATE + 30),
        row(107, "USD", 1.0, NO_SNAPSHOT_EMPTY, date = BASE_DATE + 30),
        row(108, "USD", 50.0, NO_SNAPSHOT_EUR, date = BASE_DATE + 40)
    )
}
