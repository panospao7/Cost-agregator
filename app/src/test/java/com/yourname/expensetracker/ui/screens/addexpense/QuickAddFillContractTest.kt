package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.domain.quickadd.AmountText
import com.yourname.expensetracker.domain.quickadd.CategoryMatch
import com.yourname.expensetracker.domain.quickadd.CategorySource
import com.yourname.expensetracker.domain.quickadd.FieldParse
import com.yourname.expensetracker.domain.quickadd.FlagReason
import com.yourname.expensetracker.domain.quickadd.LineHint
import com.yourname.expensetracker.domain.quickadd.MerchantText
import com.yourname.expensetracker.domain.quickadd.QuickAddParseResult
import com.yourname.expensetracker.domain.quickadd.QuickAddPayment
import com.yourname.expensetracker.domain.quickadd.ResolvedDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * nl-quick-add slice C: the pure fill contract (spec 5.3 apply table, 5.4 outcome, INV-1/2/3/7/10/18,
 * AE-9, AE-20, AE-22, AE-24, AE-30). The table is written out independently of the production code:
 * 3 parse results x 4 origins x 5 fields.
 */
class QuickAddFillContractTest {

    private val now = 7_777L

    private fun form(
        amount: String = "",
        merchant: String = "",
        dateMs: Long = 1_000L,
        categoryId: Long? = null,
        payment: PaymentMethod = PaymentMethod.CASH,
        origins: Map<QuickAddField, FieldOrigin> = QuickAddFillContract.DEFAULT_ORIGINS,
        flags: Map<QuickAddField, FlagReason> = emptyMap(),
        categorySource: CategoryFillSource? = null
    ) = QuickAddFormFields(amount, merchant, dateMs, categoryId, payment, origins, flags, categorySource)

    private fun parse(
        amount: FieldParse<AmountText> = FieldParse.Absent,
        date: FieldParse<ResolvedDate> = FieldParse.Absent,
        merchant: FieldParse<MerchantText> = FieldParse.Absent,
        payment: FieldParse<QuickAddPayment> = FieldParse.Absent,
        category: FieldParse<CategoryMatch> = FieldParse.Absent,
        hints: Set<LineHint> = emptySet()
    ) = QuickAddParseResult(amount, date, merchant, payment, category, hints)

    private fun apply(f: QuickAddFormFields, p: QuickAddParseResult, learned: CategoryMatch? = null) =
        QuickAddFillContract.apply(f, p, learned, now)

    private fun withOrigin(field: QuickAddField, origin: FieldOrigin) =
        QuickAddFillContract.DEFAULT_ORIGINS + (field to origin)

    // ---- the full apply table ----

    private class Spec(
        val field: QuickAddField,
        val seed: (QuickAddFormFields) -> QuickAddFormFields,
        val read: (QuickAddFormFields) -> Any?,
        val oldValue: Any?,
        val newValue: Any?,
        val defaultValue: Any?,
        val value: () -> QuickAddParseResult,
        val flagged: (FlagReason) -> QuickAddParseResult,
        val flagReason: FlagReason
    )

    private fun specs(): List<Spec> = listOf(
        Spec(
            field = QuickAddField.AMOUNT,
            seed = { it.copy(amount = "5.00") },
            read = { it.amount },
            oldValue = "5.00", newValue = "3.50", defaultValue = "",
            value = { parse(amount = FieldParse.Value(AmountText("3.50"))) },
            flagged = { parse(amount = FieldParse.Flagged(it)) },
            flagReason = FlagReason.AMOUNT_UNCLEAR
        ),
        Spec(
            field = QuickAddField.MERCHANT,
            seed = { it.copy(merchant = "Old") },
            read = { it.merchant },
            oldValue = "Old", newValue = "kafes", defaultValue = "",
            value = { parse(merchant = FieldParse.Value(MerchantText("kafes"))) },
            // The parser never flags a merchant; the contract is nevertheless total over the type.
            flagged = { parse(merchant = FieldParse.Flagged(it)) },
            flagReason = FlagReason.AMOUNT_UNCLEAR
        ),
        Spec(
            field = QuickAddField.DATE,
            seed = { it.copy(dateMs = 5_000L) },
            read = { it.dateMs },
            oldValue = 5_000L, newValue = 9_000L, defaultValue = now,
            value = { parse(date = FieldParse.Value(ResolvedDate(LocalDate.of(2026, 10, 15), 9_000L))) },
            flagged = { parse(date = FieldParse.Flagged(it)) },
            flagReason = FlagReason.DATE_FUTURE
        ),
        Spec(
            field = QuickAddField.CATEGORY,
            seed = { it.copy(categoryId = 1L, categorySource = CategoryFillSource.EXACT_NAME) },
            read = { it.categoryId to it.categorySource },
            oldValue = 1L to CategoryFillSource.EXACT_NAME,
            newValue = 2L to CategoryFillSource.LEARNED,
            defaultValue = null to null,
            value = { parse(category = FieldParse.Value(CategoryMatch(2L, CategorySource.LEARNED))) },
            flagged = { parse(category = FieldParse.Flagged(it)) },
            flagReason = FlagReason.CATEGORY_AMBIGUOUS
        ),
        Spec(
            field = QuickAddField.PAYMENT,
            seed = { it.copy(payment = PaymentMethod.BANK_TRANSFER) },
            read = { it.payment },
            oldValue = PaymentMethod.BANK_TRANSFER, newValue = PaymentMethod.CARD, defaultValue = PaymentMethod.CASH,
            value = { parse(payment = FieldParse.Value(QuickAddPayment.CARD)) },
            flagged = { parse(payment = FieldParse.Flagged(it)) },
            flagReason = FlagReason.PAYMENT_CONFLICT
        )
    )

    @Test
    fun `apply table - 3 results x 4 origins x 5 fields`() {
        var rows = 0
        for (spec in specs()) {
            for (origin in FieldOrigin.values()) {
                val label = "${spec.field}/$origin"
                // Current value is non-default and non-blank; a stale flag is set so "clear flag" is observable.
                val seeded = spec.seed(form(origins = withOrigin(spec.field, origin), flags = mapOf(spec.field to spec.flagReason)))
                // INV-1: only a USER origin with a non-blank value (or any USER date/category/payment) is protected.
                val fillable = origin != FieldOrigin.USER

                // Value(v)
                val valueResult = apply(seeded, spec.value())
                if (fillable) {
                    assertEquals("$label Value value", spec.newValue, spec.read(valueResult.form))
                    assertEquals("$label Value origin", FieldOrigin.FILL, valueResult.form.originOf(spec.field))
                    assertNull("$label Value flag", valueResult.form.flags[spec.field])
                    assertTrue("$label Value in W", spec.field in valueResult.valueFields)
                } else {
                    assertEquals("$label Value protected", spec.oldValue, spec.read(valueResult.form))
                    assertEquals("$label Value protected origin", FieldOrigin.USER, valueResult.form.originOf(spec.field))
                    assertFalse("$label Value not in W", spec.field in valueResult.valueFields)
                }

                // Flagged(r)
                val flaggedResult = apply(seeded, spec.flagged(spec.flagReason))
                if (fillable) {
                    assertEquals("$label Flagged default", spec.defaultValue, spec.read(flaggedResult.form))
                    assertEquals("$label Flagged origin", FieldOrigin.FILL, flaggedResult.form.originOf(spec.field))
                    assertEquals("$label Flagged flag", spec.flagReason, flaggedResult.form.flags[spec.field])
                    assertFalse("$label Flagged not in W", spec.field in flaggedResult.valueFields)
                } else {
                    assertEquals("$label Flagged protected", spec.oldValue, spec.read(flaggedResult.form))
                    assertEquals("$label Flagged protected origin", FieldOrigin.USER, flaggedResult.form.originOf(spec.field))
                    // Non-fillable: nothing changes and no new flag is set (the stale flag is left alone).
                    assertEquals("$label Flagged protected flag", spec.flagReason, flaggedResult.form.flags[spec.field])
                }

                // Absent
                val absentResult = apply(seeded, parse())
                when (origin) {
                    FieldOrigin.FILL -> {
                        assertEquals("$label Absent resets", spec.defaultValue, spec.read(absentResult.form))
                        assertEquals("$label Absent origin", FieldOrigin.DEFAULT, absentResult.form.originOf(spec.field))
                        assertNull("$label Absent flag", absentResult.form.flags[spec.field])
                    }
                    FieldOrigin.DEFAULT, FieldOrigin.PREFILL -> {
                        assertEquals("$label Absent keeps", spec.oldValue, spec.read(absentResult.form))
                        assertEquals("$label Absent keeps origin", origin, absentResult.form.originOf(spec.field))
                        assertNull("$label Absent flag", absentResult.form.flags[spec.field])
                    }
                    FieldOrigin.USER -> {
                        assertEquals("$label Absent protected", spec.oldValue, spec.read(absentResult.form))
                        assertEquals("$label Absent protected origin", FieldOrigin.USER, absentResult.form.originOf(spec.field))
                    }
                }
                assertFalse("$label Absent not in W", spec.field in absentResult.valueFields)
                rows += 3
            }
        }
        assertEquals(60, rows)
    }

    @Test
    fun `apply never touches a field the parse result does not cover`() {
        val seeded = form(
            amount = "5.00", merchant = "Old", dateMs = 5_000L, categoryId = 1L, payment = PaymentMethod.CARD,
            origins = QuickAddFillContract.DEFAULT_ORIGINS.mapValues { FieldOrigin.PREFILL }
        )

        val result = apply(seeded, parse())

        assertEquals(seeded.copy(flags = emptyMap()), result.form)
        assertTrue(result.writtenFields.isEmpty())
    }

    // ---- INV-1 blank text rule ----

    @Test
    fun `a USER text field is fillable again only while blank (AE-24)`() {
        val userBlank = form(amount = "", merchant = "", origins = withOrigin(QuickAddField.AMOUNT, FieldOrigin.USER))
        assertTrue(QuickAddFillContract.isFillable(QuickAddField.AMOUNT, userBlank))
        val filled = apply(userBlank, parse(amount = FieldParse.Value(AmountText("3.50"))))
        assertEquals("3.50", filled.form.amount)
        assertEquals(FieldOrigin.FILL, filled.form.originOf(QuickAddField.AMOUNT))

        val userTyped = form(amount = "4", origins = withOrigin(QuickAddField.AMOUNT, FieldOrigin.USER))
        assertFalse(QuickAddFillContract.isFillable(QuickAddField.AMOUNT, userTyped))

        val userMerchantBlank = form(merchant = "", origins = withOrigin(QuickAddField.MERCHANT, FieldOrigin.USER))
        assertTrue(QuickAddFillContract.isFillable(QuickAddField.MERCHANT, userMerchantBlank))
        val userMerchantTyped = form(merchant = "Taxi Beat", origins = withOrigin(QuickAddField.MERCHANT, FieldOrigin.USER))
        assertFalse(QuickAddFillContract.isFillable(QuickAddField.MERCHANT, userMerchantTyped))
    }

    @Test
    fun `a blank USER text field with an Absent result is kept as USER and blank`() {
        val userBlank = form(amount = "", origins = withOrigin(QuickAddField.AMOUNT, FieldOrigin.USER))

        val result = apply(userBlank, parse())

        assertEquals("", result.form.amount)
        assertEquals(FieldOrigin.USER, result.form.originOf(QuickAddField.AMOUNT))
    }

    @Test
    fun `date category and payment with USER origin are never fillable even when they hold the default`() {
        val allUser = form(origins = QuickAddFillContract.DEFAULT_ORIGINS.mapValues { FieldOrigin.USER })
        assertFalse(QuickAddFillContract.isFillable(QuickAddField.DATE, allUser))
        assertFalse(QuickAddFillContract.isFillable(QuickAddField.CATEGORY, allUser))
        assertFalse(QuickAddFillContract.isFillable(QuickAddField.PAYMENT, allUser))
    }

    // ---- AE-9 ----

    @Test
    fun `AE-9 a USER amount is kept while merchant and category are filled and the outcome is FILL_PARTIAL`() {
        val f = form(amount = "4.00", origins = withOrigin(QuickAddField.AMOUNT, FieldOrigin.USER))

        val result = apply(
            f,
            parse(
                amount = FieldParse.Value(AmountText("3.50")),
                merchant = FieldParse.Value(MerchantText("καφές"))
            ),
            learned = CategoryMatch(3L, CategorySource.LEARNED)
        )

        assertEquals("4.00", result.form.amount)
        assertNull(result.form.flags[QuickAddField.AMOUNT])
        assertEquals("καφές", result.form.merchant)
        assertEquals(3L, result.form.categoryId)
        assertEquals(CategoryFillSource.LEARNED, result.form.categorySource)
        assertEquals(OutcomeCode.FILL_PARTIAL, result.outcome)
    }

    // ---- AE-20 / AE-22 / AE-30 ----

    @Test
    fun `AE-20 a PREFILL amount survives an Absent amount and is replaced by a Value`() {
        val f = form(amount = "12.40", origins = withOrigin(QuickAddField.AMOUNT, FieldOrigin.PREFILL))

        val kept = apply(f, parse(merchant = FieldParse.Value(MerchantText("lidl"))))
        assertEquals("12.40", kept.form.amount)
        assertEquals(FieldOrigin.PREFILL, kept.form.originOf(QuickAddField.AMOUNT))

        val replaced = apply(f, parse(amount = FieldParse.Value(AmountText("9.99"))))
        assertEquals("9.99", replaced.form.amount)
    }

    @Test
    fun `INV-4 a foreign amount clears a PREFILL clipboard amount and sets the flag and the outcome`() {
        val f = form(amount = "12.40", origins = withOrigin(QuickAddField.AMOUNT, FieldOrigin.PREFILL))

        val result = apply(
            f,
            parse(
                amount = FieldParse.Flagged(FlagReason.AMOUNT_FOREIGN_CURRENCY),
                merchant = FieldParse.Value(MerchantText("dinner")),
                hints = setOf(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED)
            )
        )

        assertEquals("", result.form.amount)
        assertEquals(FlagReason.AMOUNT_FOREIGN_CURRENCY, result.form.flags[QuickAddField.AMOUNT])
        assertEquals(setOf(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED), result.lineHints)
        assertEquals(OutcomeCode.FOREIGN_CURRENCY_BLOCKED, result.outcome)
    }

    @Test
    fun `foreign hint with a USER amount leaves the amount untouched but still reports the hint and outcome`() {
        val f = form(amount = "18", origins = withOrigin(QuickAddField.AMOUNT, FieldOrigin.USER))

        val result = apply(
            f,
            parse(
                amount = FieldParse.Flagged(FlagReason.AMOUNT_FOREIGN_CURRENCY),
                merchant = FieldParse.Value(MerchantText("dinner")),
                hints = setOf(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED)
            )
        )

        assertEquals("18", result.form.amount)
        assertNull(result.form.flags[QuickAddField.AMOUNT])
        assertTrue(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED in result.lineHints)
        assertEquals(OutcomeCode.FOREIGN_CURRENCY_BLOCKED, result.outcome)
    }

    @Test
    fun `AE-22 a FILL payment mentioned earlier but absent now resets to CASH and DEFAULT`() {
        val first = apply(form(), parse(payment = FieldParse.Value(QuickAddPayment.CARD)))
        assertEquals(PaymentMethod.CARD, first.form.payment)
        assertEquals(FieldOrigin.FILL, first.form.originOf(QuickAddField.PAYMENT))

        val second = apply(first.form, parse())

        assertEquals(PaymentMethod.CASH, second.form.payment)
        assertEquals(FieldOrigin.DEFAULT, second.form.originOf(QuickAddField.PAYMENT))
    }

    @Test
    fun `AE-30 applying the same parse twice gives an identical form and outcome`() {
        val p = parse(
            amount = FieldParse.Value(AmountText("3.50")),
            date = FieldParse.Value(ResolvedDate(LocalDate.of(2026, 10, 15), 9_000L)),
            merchant = FieldParse.Value(MerchantText("kafes")),
            payment = FieldParse.Flagged(FlagReason.PAYMENT_CONFLICT),
            category = FieldParse.Flagged(FlagReason.CATEGORY_AMBIGUOUS),
            hints = setOf(LineHint.TIME_IGNORED)
        )

        val first = apply(form(), p)
        val second = apply(first.form, p)

        assertEquals(first.form, second.form)
        assertEquals(first.outcome, second.outcome)
        assertEquals(first.lineHints, second.lineHints)
        assertEquals(first.valueFields, second.valueFields)
    }

    // ---- INV-10 learned category ----

    @Test
    fun `learned category is used only with a merchant Value written by the same apply`() {
        val learned = CategoryMatch(3L, CategorySource.LEARNED)
        val withMerchant = parse(merchant = FieldParse.Value(MerchantText("kafes")))

        val used = apply(form(), withMerchant, learned)
        assertEquals(3L, used.form.categoryId)
        assertEquals(CategoryFillSource.LEARNED, used.form.categorySource)
        assertEquals(FieldOrigin.FILL, used.form.originOf(QuickAddField.CATEGORY))
        assertTrue(QuickAddField.CATEGORY in used.valueFields)

        val noMerchant = apply(form(), parse(), learned)
        assertNull(noMerchant.form.categoryId)

        val merchantProtected = apply(
            form(merchant = "Taxi Beat", origins = withOrigin(QuickAddField.MERCHANT, FieldOrigin.USER)),
            withMerchant,
            learned
        )
        assertEquals("Taxi Beat", merchantProtected.form.merchant)
        assertNull(merchantProtected.form.categoryId)
    }

    @Test
    fun `exact-name category beats a learned one and ambiguity is not replaced by a learned one`() {
        val learned = CategoryMatch(3L, CategorySource.LEARNED)
        val exact = parse(
            merchant = FieldParse.Value(MerchantText("kafes")),
            category = FieldParse.Value(CategoryMatch(30L, CategorySource.EXACT_NAME))
        )
        val exactResult = apply(form(), exact, learned)
        assertEquals(30L, exactResult.form.categoryId)
        assertEquals(CategoryFillSource.EXACT_NAME, exactResult.form.categorySource)

        val ambiguous = parse(
            merchant = FieldParse.Value(MerchantText("gifts kids")),
            category = FieldParse.Flagged(FlagReason.CATEGORY_AMBIGUOUS)
        )
        val ambiguousResult = apply(form(), ambiguous, learned)
        assertNull(ambiguousResult.form.categoryId)
        assertEquals(FlagReason.CATEGORY_AMBIGUOUS, ambiguousResult.form.flags[QuickAddField.CATEGORY])
    }

    @Test
    fun `AE-29 apply-level a learned category never overwrites a USER category`() {
        val f = form(categoryId = 2L, origins = withOrigin(QuickAddField.CATEGORY, FieldOrigin.USER))

        val result = apply(f, parse(merchant = FieldParse.Value(MerchantText("kafes"))), CategoryMatch(3L, CategorySource.LEARNED))

        assertEquals(2L, result.form.categoryId)
        assertEquals(FieldOrigin.USER, result.form.originOf(QuickAddField.CATEGORY))
        assertEquals(OutcomeCode.FILL_PARTIAL, result.outcome)
    }

    @Test
    fun `wantsLearnedLookup requires an Absent parser category a merchant Value and two fillable fields`() {
        val merchantValue = parse(merchant = FieldParse.Value(MerchantText("kafes")))
        assertTrue(QuickAddFillContract.wantsLearnedLookup(merchantValue, form()))

        assertFalse(QuickAddFillContract.wantsLearnedLookup(parse(), form()))
        assertFalse(
            QuickAddFillContract.wantsLearnedLookup(
                parse(
                    merchant = FieldParse.Value(MerchantText("kafes")),
                    category = FieldParse.Value(CategoryMatch(1L, CategorySource.EXACT_NAME))
                ),
                form()
            )
        )
        assertFalse(
            QuickAddFillContract.wantsLearnedLookup(
                parse(
                    merchant = FieldParse.Value(MerchantText("kafes")),
                    category = FieldParse.Flagged(FlagReason.CATEGORY_AMBIGUOUS)
                ),
                form()
            )
        )
        assertFalse(
            QuickAddFillContract.wantsLearnedLookup(
                merchantValue,
                form(categoryId = 2L, origins = withOrigin(QuickAddField.CATEGORY, FieldOrigin.USER))
            )
        )
        assertFalse(
            QuickAddFillContract.wantsLearnedLookup(
                merchantValue,
                form(merchant = "Typed", origins = withOrigin(QuickAddField.MERCHANT, FieldOrigin.USER))
            )
        )
    }

    // ---- 5.4 outcomes ----

    @Test
    fun `outcome FILL_FULL needs amount and merchant Values and no flag`() {
        val full = apply(
            form(),
            parse(
                amount = FieldParse.Value(AmountText("3.50")),
                merchant = FieldParse.Value(MerchantText("kafes")),
                date = FieldParse.Value(ResolvedDate(LocalDate.of(2026, 10, 15), 9_000L)),
                hints = setOf(LineHint.TIME_IGNORED)
            )
        )
        assertEquals(OutcomeCode.FILL_FULL, full.outcome)

        val flagged = apply(
            form(),
            parse(
                amount = FieldParse.Value(AmountText("3.50")),
                merchant = FieldParse.Value(MerchantText("kafes")),
                date = FieldParse.Flagged(FlagReason.DATE_FUTURE)
            )
        )
        assertEquals(OutcomeCode.FILL_PARTIAL, flagged.outcome)
    }

    @Test
    fun `outcome FILL_PARTIAL when amount or merchant is missing or flagged`() {
        assertEquals(
            OutcomeCode.FILL_PARTIAL,
            apply(form(), parse(merchant = FieldParse.Value(MerchantText("kafes")))).outcome
        )
        assertEquals(
            OutcomeCode.FILL_PARTIAL,
            apply(form(), parse(amount = FieldParse.Value(AmountText("3.50")))).outcome
        )
        assertEquals(
            OutcomeCode.FILL_PARTIAL,
            apply(
                form(),
                parse(
                    amount = FieldParse.Flagged(FlagReason.AMOUNT_QUANTITY),
                    merchant = FieldParse.Value(MerchantText("kafedes"))
                )
            ).outcome
        )
    }

    @Test
    fun `outcome FILL_NONE when no Value was applied and hints pass through unchanged without a derived NOTHING_RECOGNISED`() {
        val empty = apply(form(), parse())
        assertEquals(OutcomeCode.FILL_NONE, empty.outcome)
        assertTrue(empty.lineHints.isEmpty())

        val parserNothing = apply(form(), parse(hints = setOf(LineHint.NOTHING_RECOGNISED)))
        assertEquals(OutcomeCode.FILL_NONE, parserNothing.outcome)
        assertEquals(setOf(LineHint.NOTHING_RECOGNISED), parserNothing.lineHints)

        val tooLong = apply(form(), parse(hints = setOf(LineHint.LINE_TOO_LONG)))
        assertEquals(OutcomeCode.FILL_NONE, tooLong.outcome)
        assertEquals(setOf(LineHint.LINE_TOO_LONG), tooLong.lineHints)

        // Values that are all non-fillable are not "applied": FILL_NONE (W is empty).
        val protectedForm = form(
            amount = "4", merchant = "Taxi Beat",
            origins = QuickAddFillContract.DEFAULT_ORIGINS.mapValues { FieldOrigin.USER }
        )
        val blocked = apply(
            protectedForm,
            parse(amount = FieldParse.Value(AmountText("3.50")), merchant = FieldParse.Value(MerchantText("kafes")))
        )
        assertEquals(OutcomeCode.FILL_NONE, blocked.outcome)
    }

    @Test
    fun `line hints are replaced on every apply`() {
        val first = apply(form(), parse(hints = setOf(LineHint.TIME_IGNORED)))
        val second = apply(first.form, parse(hints = setOf(LineHint.LINE_TOO_LONG)))

        assertEquals(setOf(LineHint.LINE_TOO_LONG), second.lineHints)
    }

    @Test
    fun `written fields cover Value Flagged and reset writes but not kept or protected fields`() {
        val f = form(origins = withOrigin(QuickAddField.PAYMENT, FieldOrigin.FILL))

        val result = apply(
            f,
            parse(
                amount = FieldParse.Value(AmountText("3.50")),
                date = FieldParse.Flagged(FlagReason.DATE_INVALID)
            )
        )

        // amount: Value, date: Flagged, payment: FILL + Absent resets, merchant/category: kept.
        assertEquals(
            setOf(QuickAddField.AMOUNT, QuickAddField.DATE, QuickAddField.PAYMENT),
            result.writtenFields
        )
        assertEquals(setOf(QuickAddField.AMOUNT), result.valueFields)
    }
}
