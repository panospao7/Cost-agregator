package com.yourname.expensetracker.domain.quickadd

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Inline golden table for the quick-add parser (spec 12, 132 cases, categories A-M).
 *
 * Each case maps (line, home, now, zone, categories) to the per-field [FieldParse] and the line
 * hints. Expectations are written as short rendered strings so a failure prints a readable diff:
 *
 * - `V(x)` a Value, `F(REASON)` a Flagged field, `-` Absent;
 * - dates render as ISO offset date-time with seconds, in the case zone.
 *
 * Invisible and look-alike characters are built from code points (named constants below), never
 * written raw: U+00A0 NBSP, U+202F narrow NBSP, U+2009 thin space, U+2212 minus sign, U+00D7
 * multiplication sign, U+037E Greek question mark, U+0387 ano teleia. The table is NOT stored under
 * src/test/resources/golden/.
 *
 * Expected values come from the domain spec (docs/features/nl-quick-add/domain-spec.md). They are
 * never adjusted to match the code: a disagreement goes back to the spec author (plan N11).
 */
internal data class GoldenCtx(
    val name: String,
    val home: String,
    val nowIso: String,
    val zoneId: String,
    val categories: List<CategoryRef>
) {
    val zone: ZoneId get() = ZoneId.of(zoneId)
    val nowEpochMs: Long get() = OffsetDateTime.parse(nowIso).toInstant().toEpochMilli()

    fun input(line: String): QuickAddInput =
        QuickAddInput(line = line, nowEpochMs = nowEpochMs, zone = zone, homeCurrency = home, categories = categories)
}

internal data class GoldenCase(
    val id: String,
    val line: String,
    val ctx: GoldenCtx,
    val amount: String,
    val date: String,
    val merchant: String,
    val payment: String,
    val category: String,
    val hints: Set<LineHint>
) {
    /** Taxonomy letter (A-M). */
    val group: Char get() = id[0]

    fun expectedRendering(): String = QuickAddParserGoldenFixtures.rendering(
        amount, date, merchant, payment, category, hints
    )
}

internal object QuickAddParserGoldenFixtures {

    // ----------------------------------------------------------------------- rendering

    private const val ABSENT = "-"

    // Invisible and look-alike characters are built from code points, so none sits raw in this source.
    private val NBSP = 0x00A0.toChar()
    private val NARROW_NBSP = 0x202F.toChar()
    private val THIN_SPACE = 0x2009.toChar()
    private val MINUS_SIGN = 0x2212.toChar()
    private val MULTIPLICATION_SIGN = 0x00D7.toChar()
    private val GREEK_QUESTION_MARK = 0x037E.toChar()
    private val ANO_TELEIA = 0x0387.toChar()

    private val ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME

    fun rendering(
        amount: String,
        date: String,
        merchant: String,
        payment: String,
        category: String,
        hints: Set<LineHint>
    ): String =
        "amount=$amount date=$date merchant=$merchant payment=$payment category=$category " +
            "hints=${hints.map { it.name }.sorted()}"

    fun render(result: QuickAddParseResult, zone: ZoneId): String = rendering(
        amount = field(result.amount) { it.text },
        date = field(result.date) { ISO.format(Instant.ofEpochMilli(it.epochMs).atZone(zone)) },
        merchant = field(result.merchant) { it.text },
        payment = field(result.payment) { it.name },
        category = field(result.category) { "${it.categoryId}:${it.source.name}" },
        hints = result.lineHints
    )

    private fun <T> field(parse: FieldParse<T>, text: (T) -> String): String = when (parse) {
        is FieldParse.Value -> "V(${text(parse.value)})"
        is FieldParse.Flagged -> "F(${parse.reason.name})"
        FieldParse.Absent -> ABSENT
    }

    private fun v(text: String) = "V($text)"
    private fun f(reason: FlagReason) = "F(${reason.name})"
    private fun d(iso: String) = "V($iso)"
    private fun cat(id: Long, source: CategorySource = CategorySource.EXACT_NAME) = "V($id:${source.name})"
    private fun h(vararg hints: LineHint): Set<LineHint> = hints.toSet()

    // ------------------------------------------------------------------------ contexts

    private val ATHENS = "Europe/Athens"

    private val CATEGORIES_A = listOf(
        CategoryRef(1, "Groceries"),
        CategoryRef(2, "Transport"),
        CategoryRef(3, "Food"),
        CategoryRef(15, "Home"),
        CategoryRef(16, "Kids"),
        CategoryRef(17, "Gifts"),
        CategoryRef(19, "Legal & Gov"),
        CategoryRef(20, "Uncategorized")
    )

    /** CTX-A: home EUR, Europe/Athens, now Friday 2026-10-16T14:30:15.250+03:00. */
    val CTX_A = GoldenCtx("CTX-A", "EUR", "2026-10-16T14:30:15.250+03:00", ATHENS, CATEGORIES_A)

    /** CTX-B: CTX-A plus a user category 30 "Καφές". */
    val CTX_B = CTX_A.copy(name = "CTX-B", categories = CATEGORIES_A + CategoryRef(30, "Καφές"))

    val CTX_USD = CTX_A.copy(name = "CTX-A/home-USD", home = "USD")
    val CTX_GBP = CTX_A.copy(name = "CTX-A/home-GBP", home = "GBP")
    val CTX_JPY = CTX_A.copy(name = "CTX-A/home-JPY", home = "JPY")

    /** AE-14: now Sunday 2027-01-03T10:00:00+02:00. */
    val CTX_YEAR_START = CTX_A.copy(name = "AE-14", nowIso = "2027-01-03T10:00:00+02:00")

    /** AE-15: the morning after the spring-forward Sunday (Athens, 2026-03-29). */
    val CTX_DST_GAP = CTX_A.copy(name = "AE-15/gap", nowIso = "2026-03-30T03:30:00+03:00")

    /** AE-15: the morning after the fall-back Sunday (Athens, 2026-10-25). */
    val CTX_DST_OVERLAP = CTX_A.copy(name = "AE-15/overlap", nowIso = "2026-10-26T03:30:00+02:00")

    /** AE-15: just after midnight. */
    val CTX_MIDNIGHT = CTX_A.copy(name = "AE-15/midnight", nowIso = "2026-10-16T00:00:30+03:00")

    /** Fill at 23:59:59 (milliseconds must not push the result past the end of the day). */
    val CTX_LATE = CTX_A.copy(name = "late", nowIso = "2026-10-16T23:59:59.900+03:00")

    private const val T0 = "2026-10-16T14:30:15+03:00"
    private const val Y1 = "2026-10-15T14:30:15+03:00"
    private const val Y2 = "2026-10-14T14:30:15+03:00"

    private fun case(
        id: String,
        line: String,
        ctx: GoldenCtx = CTX_A,
        amount: String = ABSENT,
        date: String = ABSENT,
        merchant: String = ABSENT,
        payment: String = ABSENT,
        category: String = ABSENT,
        hints: Set<LineHint> = emptySet()
    ) = GoldenCase(id, line, ctx, amount, date, merchant, payment, category, hints)

    // --------------------------------------------------------------------------- table

    private val groupA = listOf(
        // AE-1..AE-9 at parser level (learned category and save tails belong to C2/C3).
        case("A01", "καφές 3,50 χθες", amount = v("3.50"), date = d(Y1), merchant = v("καφές")),
        case("A02", "groceries 42.30 lidl", amount = v("42.30"), merchant = v("lidl"), category = cat(1)),
        case(
            "A03", "taxi 15€ σήμερα κάρτα",
            amount = v("15.00"), date = d(T0), merchant = v("taxi"), payment = v("CARD")
        ),
        case("A04", "kafes 3.50", amount = v("3.50"), merchant = v("kafes")),
        case(
            "A05", "dinner 20 usd",
            amount = f(FlagReason.AMOUNT_FOREIGN_CURRENCY), merchant = v("dinner"),
            hints = h(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED)
        ),
        case("A06", "2 καφέδες 7", amount = f(FlagReason.AMOUNT_QUANTITY), merchant = v("καφέδες")),
        case("A07", "lidl 1.500", amount = f(FlagReason.AMOUNT_AMBIGUOUS_SEPARATOR), merchant = v("lidl")),
        case(
            "A08", "taxi 12 αύριο",
            amount = v("12.00"), date = f(FlagReason.DATE_FUTURE), merchant = v("taxi")
        ),
        case("A09", "καφές 3,50", amount = v("3.50"), merchant = v("καφές"))
    )

    private val groupB = listOf(
        case("B01", "taxi 3,5", amount = v("3.50"), merchant = v("taxi")),
        case("B02", "tv 1.234,56", amount = v("1234.56"), merchant = v("tv")),
        case("B03", "taxi 1,234.56", amount = v("1234.56"), merchant = v("taxi")),
        // B04..B06: NBSP (U+00A0), narrow NBSP (U+202F) and thin space (U+2009) grouping.
        case("B04", "taxi 1${NBSP}234,56", amount = v("1234.56"), merchant = v("taxi")),
        case("B05", "taxi 1${NARROW_NBSP}234,56", amount = v("1234.56"), merchant = v("taxi")),
        case("B06", "taxi 1${THIN_SPACE}234,56", amount = v("1234.56"), merchant = v("taxi")),
        case("B07", "taxi 15 ευρώ", amount = v("15.00"), merchant = v("taxi")),
        case("B08", "taxi 15ε", amount = v("15.00"), merchant = v("taxi")),
        case("B09", "EUR 15", amount = v("15.00")),
        case("B10", "evro 15", amount = v("15.00")),
        case("B11", "€ 15", amount = v("15.00")),
        case("B12", "taxi 3,50.", amount = v("3.50"), merchant = v("taxi")),
        case("B13", "taxi (15)", amount = v("15.00"), merchant = v("taxi")),
        case("B14", "taxi 1000000", amount = v("1000000.00"), merchant = v("taxi"))
    )

    private val groupC = listOf(
        case("C01", "taxi ${MINUS_SIGN}5", amount = f(FlagReason.AMOUNT_NOT_POSITIVE), merchant = v("taxi")),
        case("C02", "taxi .50", amount = f(FlagReason.AMOUNT_UNCLEAR), merchant = v("taxi")),
        case("C03", "taxi 0150", amount = f(FlagReason.AMOUNT_UNCLEAR), merchant = v("taxi")),
        case("C04", "lidl 3,500", amount = f(FlagReason.AMOUNT_AMBIGUOUS_SEPARATOR), merchant = v("lidl")),
        case("C05", "tv 1.234.567", amount = f(FlagReason.AMOUNT_UNCLEAR), merchant = v("tv")),
        case("C06", "taxi 3.5055", amount = f(FlagReason.AMOUNT_UNCLEAR), merchant = v("taxi")),
        case("C07", "taxi 0,00", amount = f(FlagReason.AMOUNT_NOT_POSITIVE), merchant = v("taxi")),
        case("C08", "taxi 1000000,01", amount = f(FlagReason.AMOUNT_OVER_LIMIT), merchant = v("taxi")),
        case("C09", "taxi 5 7", amount = f(FlagReason.AMOUNT_MULTIPLE_NUMBERS), merchant = v("taxi")),
        // C10: ASCII-space grouping never joins, so this is two numbers.
        case("C10", "taxi 1 234,56", amount = f(FlagReason.AMOUNT_MULTIPLE_NUMBERS), merchant = v("taxi")),
        case("C11", "2x καφές 7", amount = f(FlagReason.AMOUNT_QUANTITY), merchant = v("καφές")),
        case("C12", "x2 καφές 7", amount = f(FlagReason.AMOUNT_QUANTITY), merchant = v("καφές")),
        case("C13", "${MULTIPLICATION_SIGN}2 καφές 7", amount = f(FlagReason.AMOUNT_QUANTITY), merchant = v("καφές")),
        case("C14", "2 τμχ καφές 7", amount = f(FlagReason.AMOUNT_QUANTITY), merchant = v("καφές")),
        case("C15", "taxi 10-15", amount = f(FlagReason.AMOUNT_UNCLEAR), merchant = v("taxi")),
        case("C16", "taxi 15💰", amount = f(FlagReason.AMOUNT_UNCLEAR), merchant = v("taxi"))
    )

    private val foreignHint = h(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED)
    private val foreignAmount = f(FlagReason.AMOUNT_FOREIGN_CURRENCY)

    private val groupD = listOf(
        case("D01", "20 USD €", amount = foreignAmount, hints = foreignHint),
        case("D02", "\$20 cab", amount = foreignAmount, merchant = v("cab"), hints = foreignHint),
        case("D03", "£20 cab", amount = foreignAmount, merchant = v("cab"), hints = foreignHint),
        case("D04", "¥500 cab", amount = foreignAmount, merchant = v("cab"), hints = foreignHint),
        case("D05", "C\$20 cab", amount = foreignAmount, merchant = v("cab"), hints = foreignHint),
        case("D06", "100kr", amount = foreignAmount, hints = foreignHint),
        case("D07", "dinner 20 δολάρια", amount = foreignAmount, merchant = v("dinner"), hints = foreignHint),
        case("D08", "dinner 20 dollars", amount = foreignAmount, merchant = v("dinner"), hints = foreignHint),
        case("D09", "dinner 20 λίρες", amount = foreignAmount, merchant = v("dinner"), hints = foreignHint),
        case("D10", "\$20 cab", ctx = CTX_USD, amount = v("20.00"), merchant = v("cab")),
        case("D11", "€20 cab", ctx = CTX_USD, amount = foreignAmount, merchant = v("cab"), hints = foreignHint),
        case("D12", "£20 cab", ctx = CTX_GBP, amount = v("20.00"), merchant = v("cab")),
        case("D13", "¥500 cab", ctx = CTX_JPY, amount = foreignAmount, merchant = v("cab"), hints = foreignHint),
        case("D14", "abc 20", amount = v("20.00"), merchant = v("abc")),
        case("D15", "20 thb", amount = foreignAmount, hints = foreignHint),
        case("D16", "thb dinner 20", amount = v("20.00"), merchant = v("thb dinner"))
    )

    private fun relative(id: String, word: String, date: String) =
        case(id, "taxi 5 $word", amount = v("5.00"), date = d(date), merchant = v("taxi"))

    private val groupE = listOf(
        relative("E01", "today", T0),
        relative("E02", "σήμερα", T0),
        relative("E03", "shmera", T0),
        relative("E04", "yesterday", Y1),
        relative("E05", "yday", Y1),
        relative("E06", "χθες", Y1),
        relative("E07", "ΧΘΕΣ", Y1),
        relative("E08", "χτές", Y1),
        relative("E09", "xthes", Y1),
        relative("E10", "εχθές", Y1),
        relative("E11", "προχθές", Y2),
        relative("E12", "prochthes", Y2),
        relative("E13", "the day before yesterday", Y2),
        relative("E14", "day before yesterday", Y2)
    )

    private fun weekday(id: String, word: String, date: String) =
        case(id, "taxi 9 $word", amount = v("9.00"), date = d(date), merchant = v("taxi"))

    private val groupF = listOf(
        weekday("F01", "Δευτέρα", "2026-10-12T14:30:15+03:00"),
        weekday("F02", "τη Δευτέρα", "2026-10-12T14:30:15+03:00"),
        weekday("F03", "Πέμπτη", Y1),
        weekday("F04", "Σάββατο", "2026-10-10T14:30:15+03:00"),
        case(
            "F05", "taxi 9 Παρασκευή",
            amount = v("9.00"), date = f(FlagReason.DATE_AMBIGUOUS), merchant = v("taxi")
        ),
        case(
            "F06", "taxi 9 last monday",
            amount = v("9.00"), date = f(FlagReason.DATE_UNSUPPORTED), merchant = v("taxi")
        ),
        weekday("F07", "mon.", "2026-10-12T14:30:15+03:00"),
        weekday("F08", "Δευτ.", "2026-10-12T14:30:15+03:00"),
        weekday("F09", "kyriaki", "2026-10-11T14:30:15+03:00"),
        weekday("F10", "wednesday", Y2)
    )

    private fun dated(id: String, line: String, ctx: GoldenCtx, date: String) =
        case(id, line, ctx = ctx, amount = v("5.00"), date = date, merchant = v("taxi"))

    private val groupG = listOf(
        dated("G01", "taxi 5 28/12", CTX_YEAR_START, d("2026-12-28T10:00:00+02:00")),
        dated("G02", "taxi 5 3/10", CTX_YEAR_START, d("2026-10-03T10:00:00+03:00")),
        dated("G03", "taxi 5 2/10", CTX_YEAR_START, f(FlagReason.DATE_FUTURE)),
        dated("G04", "taxi 5 4/1", CTX_YEAR_START, f(FlagReason.DATE_FUTURE)),
        dated("G05", "taxi 5 31/12/26", CTX_YEAR_START, d("2026-12-31T10:00:00+02:00")),
        dated("G06", "taxi 5 5/1/27", CTX_YEAR_START, f(FlagReason.DATE_FUTURE)),
        dated("G07", "taxi 5 31/4", CTX_YEAR_START, f(FlagReason.DATE_INVALID)),
        case(
            "G08", "taxi 5 5.3", ctx = CTX_YEAR_START,
            amount = f(FlagReason.AMOUNT_MULTIPLE_NUMBERS), merchant = v("taxi")
        ),
        dated("G09", "taxi 5 29/2/2024", CTX_A, d("2024-02-29T14:30:15+02:00")),
        dated("G10", "taxi 5 15.10.2026", CTX_A, d(Y1)),
        dated("G11", "taxi 5 15-10-26", CTX_A, d(Y1)),
        dated("G12", "taxi 5 29/2", CTX_A, f(FlagReason.DATE_INVALID))
    )

    private val groupH = listOf(
        dated("H01", "taxi 5 αύριο", CTX_A, f(FlagReason.DATE_FUTURE)),
        dated("H02", "taxi 5 tomorrow", CTX_A, f(FlagReason.DATE_FUTURE)),
        dated("H03", "taxi 5 avrio", CTX_A, f(FlagReason.DATE_FUTURE)),
        dated("H04", "taxi 5 μεθαύριο", CTX_A, f(FlagReason.DATE_FUTURE)),
        case(
            "H05", "taxi 9 χθες 15/10",
            amount = v("9.00"), date = f(FlagReason.DATE_AMBIGUOUS), merchant = v("taxi")
        )
    )

    private val groupI = listOf(
        case(
            "I01", "καφές 2 χθες", ctx = CTX_DST_GAP,
            amount = v("2.00"), date = d("2026-03-29T04:30:00+03:00"), merchant = v("καφές")
        ),
        case("I02", "χθες", ctx = CTX_DST_OVERLAP, date = d("2026-10-25T03:30:00+03:00")),
        case("I03", "χθες", ctx = CTX_MIDNIGHT, date = d("2026-10-15T00:00:30+03:00")),
        case(
            "I04", "taxi 5 σήμερα", ctx = CTX_LATE,
            amount = v("5.00"), date = d("2026-10-16T23:59:59+03:00"), merchant = v("taxi")
        ),
        case(
            "I05", "lidl 12,40 21:30",
            amount = v("12.40"), merchant = v("lidl"), hints = h(LineHint.TIME_IGNORED)
        )
    )

    private fun payment(id: String, word: String, method: String) =
        case(id, "taxi 5 $word", amount = v("5.00"), merchant = v("taxi"), payment = v(method))

    private val groupJ = listOf(
        payment("J01", "karta", "CARD"),
        payment("J02", "ΚΑΡΤΑ", "CARD"),
        payment("J03", "με κάρτα", "CARD"),
        payment("J04", "cash", "CASH"),
        payment("J05", "μετρητά", "CASH"),
        payment("J06", "metrita", "CASH"),
        case(
            "J07", "taxi 5 card cash",
            amount = v("5.00"), merchant = v("taxi"), payment = f(FlagReason.PAYMENT_CONFLICT)
        )
    )

    private val groupK = listOf(
        // K01: U+037E Greek question mark; K02: U+0387 ano teleia. Both fold to ';' / middle dot via NFC.
        case("K01", "καφές$GREEK_QUESTION_MARK 3,50", amount = v("3.50"), merchant = v("καφές")),
        case("K02", "καφές$ANO_TELEIA 3,50", amount = v("3.50"), merchant = v("καφές")),
        case("K03", "H&M 25", amount = v("25.00"), merchant = v("H&M")),
        case("K04", "McDonald's 8,90", amount = v("8.90"), merchant = v("McDonald's")),
        case(
            "K05", "A".repeat(99) + " " + "B".repeat(10) + " 5",
            amount = v("5.00"), merchant = v("A".repeat(99))
        ),
        case("K06", "Lidl ΜΑΣΟΥΤΗΣ 12", amount = v("12.00"), merchant = v("Lidl ΜΑΣΟΥΤΗΣ")),
        case("K07", "shopping 40", amount = v("40.00"), merchant = v("shopping")),
        case("K08", "Marks & Spencer 45,90", amount = v("45.90"), merchant = v("Marks & Spencer")),
        case("K09", "Lidl\tΠατήσια\n12", amount = v("12.00"), merchant = v("Lidl Πατήσια"))
    )

    private val groupL = listOf(
        case("L01", "GROCERIES 5", amount = v("5.00"), merchant = v("GROCERIES"), category = cat(1)),
        case("L02", "legal & gov 30 fee", amount = v("30.00"), merchant = v("fee"), category = cat(19)),
        case(
            "L03", "gifts kids 30",
            amount = v("30.00"), merchant = v("gifts kids"), category = f(FlagReason.CATEGORY_AMBIGUOUS)
        ),
        case("L04", "kafes 3.50", ctx = CTX_B, amount = v("3.50"), merchant = v("kafes"), category = cat(30)),
        case("L05", "taxi home 12", amount = v("12.00"), merchant = v("taxi"), category = cat(15)),
        case("L06", "FOOD 9 sushi", amount = v("9.00"), merchant = v("sushi"), category = cat(3)),
        case("L07", "ΚΑΦΕΣ 4", ctx = CTX_B, amount = v("4.00"), merchant = v("ΚΑΦΕΣ"), category = cat(30))
    )

    private val groupM = listOf(
        case("M01", ""),
        case("M02", "   \t "),
        case("M03", "!!! ;", hints = h(LineHint.NOTHING_RECOGNISED)),
        // Owner-approvals item 57: a merchant needs a letter or digit, so an emoji-only line is
        // FILL_NONE plus NOTHING_RECOGNISED (spec section 8).
        case("M04", "🍕", hints = h(LineHint.NOTHING_RECOGNISED)),
        case("M05", "a".repeat(201), hints = h(LineHint.LINE_TOO_LONG)),
        case("M06", "χθες", date = d(Y1)),
        case("M07", "12,50", amount = v("12.50")),
        case("M08", "a1 b2", merchant = v("a1 b2"))
    )

    /** The 132 golden cases, in taxonomy order. */
    val cases: List<GoldenCase> =
        groupA + groupB + groupC + groupD + groupE + groupF + groupG + groupH + groupI + groupJ + groupK +
            groupL + groupM

    /** Spec section 12 target counts per category. */
    val expectedCounts: Map<Char, Int> = linkedMapOf(
        'A' to 9, 'B' to 14, 'C' to 16, 'D' to 16, 'E' to 14, 'F' to 10, 'G' to 12,
        'H' to 5, 'I' to 5, 'J' to 7, 'K' to 9, 'L' to 7, 'M' to 8
    )

    const val EXPECTED_TOTAL = 132
}
