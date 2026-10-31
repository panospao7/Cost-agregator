package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.domain.quickadd.CategoryMatch
import com.yourname.expensetracker.domain.quickadd.CategorySource
import com.yourname.expensetracker.domain.quickadd.FieldParse
import com.yourname.expensetracker.domain.quickadd.FlagReason
import com.yourname.expensetracker.domain.quickadd.LineHint
import com.yourname.expensetracker.domain.quickadd.QuickAddParseResult
import com.yourname.expensetracker.domain.quickadd.QuickAddPayment

/** The five fields quick-add Fill may write (INV-2). */
enum class QuickAddField { AMOUNT, MERCHANT, DATE, CATEGORY, PAYMENT }

/** Where a field's current value came from (spec 2.6). */
enum class FieldOrigin { DEFAULT, PREFILL, FILL, USER }

/** One bounded outcome per Fill (spec 2.7). */
enum class OutcomeCode { FILL_FULL, FILL_PARTIAL, FILL_NONE, FOREIGN_CURRENCY_BLOCKED }

/** Why the current category is set by Fill (G2-11). Cleared on any user edit of the category. */
enum class CategoryFillSource { EXACT_NAME, LEARNED }

/**
 * The fillable slice of the add-expense form plus its Fill bookkeeping. Plain data so the
 * contract below stays pure (no ViewModel, no coroutine, no clock, no logging).
 */
data class QuickAddFormFields(
    val amount: String,
    val merchant: String,
    val dateMs: Long,
    val categoryId: Long?,
    val payment: PaymentMethod,
    val origins: Map<QuickAddField, FieldOrigin>,
    val flags: Map<QuickAddField, FlagReason>,
    val categorySource: CategoryFillSource?
) {
    fun originOf(field: QuickAddField): FieldOrigin = origins[field] ?: FieldOrigin.DEFAULT
}

/**
 * Result of one pure apply. [valueFields] are the fields that received a parse `Value`;
 * [writtenFields] are all fields whose content was rewritten (Value, Flagged default, or reset
 * to default) and therefore have their stale field error cleared by the caller.
 */
data class QuickAddFillApplication(
    val form: QuickAddFormFields,
    val lineHints: Set<LineHint>,
    val outcome: OutcomeCode,
    val valueFields: Set<QuickAddField>,
    val writtenFields: Set<QuickAddField>
)

/**
 * C3 fill contract: the single owner of fillability (INV-1), the apply table (spec 5.3, INV-3/7)
 * and the outcome code (spec 5.4). Pure and deterministic: applying the same parse to the state it
 * produced gives the same state (AE-30).
 *
 * It does NOT derive `NOTHING_RECOGNISED`; the parser (C1) owns it and the hint set is passed
 * through unchanged (owner clarification 2026-10-28).
 */
object QuickAddFillContract {

    private val TEXT_FIELDS = setOf(QuickAddField.AMOUNT, QuickAddField.MERCHANT)

    /** Initial and reset origins: every fillable field is DEFAULT. */
    val DEFAULT_ORIGINS: Map<QuickAddField, FieldOrigin> =
        QuickAddField.values().associateWith { FieldOrigin.DEFAULT }

    /**
     * INV-1: a field is fillable iff its origin is DEFAULT, PREFILL or FILL, or it is a text field
     * (amount, merchant) whose current value is blank. Date, category and payment with origin USER
     * are never fillable.
     */
    fun isFillable(field: QuickAddField, form: QuickAddFormFields): Boolean {
        val origin = form.originOf(field)
        if (origin != FieldOrigin.USER) return true
        if (field !in TEXT_FIELDS) return false
        return when (field) {
            QuickAddField.AMOUNT -> form.amount.isBlank()
            QuickAddField.MERCHANT -> form.merchant.isBlank()
            else -> false
        }
    }

    /**
     * Spec 4.11: the learned lookup (C2) runs only when the parser category is `Absent`, the category
     * field is fillable, and this Fill writes a merchant `Value` (so the merchant field is fillable too).
     */
    fun wantsLearnedLookup(parse: QuickAddParseResult, form: QuickAddFormFields): Boolean =
        parse.category is FieldParse.Absent &&
            parse.merchant is FieldParse.Value &&
            isFillable(QuickAddField.MERCHANT, form) &&
            isFillable(QuickAddField.CATEGORY, form)

    /**
     * Applies [parse] (plus an optional [learned] category from C2) to [form] per spec 5.3 and
     * computes the outcome per spec 5.4. [nowEpochMs] is "now at this Fill" and is the date default.
     * The learned category is used only if the same apply writes a merchant `Value` and the parser
     * category is `Absent` (INV-10); fillability is re-evaluated here, at apply time (INV-18).
     */
    fun apply(
        form: QuickAddFormFields,
        parse: QuickAddParseResult,
        learned: CategoryMatch?,
        nowEpochMs: Long
    ): QuickAddFillApplication {
        var amount = form.amount
        var merchant = form.merchant
        var dateMs = form.dateMs
        var categoryId = form.categoryId
        var payment = form.payment
        var categorySource = form.categorySource
        val origins = form.origins.toMutableMap()
        val flags = form.flags.toMutableMap()
        val valueFields = linkedSetOf<QuickAddField>()
        val writtenFields = linkedSetOf<QuickAddField>()
        var flagSet = false

        fun <T> applyField(
            field: QuickAddField,
            result: FieldParse<T>,
            writeValue: (T) -> Unit,
            writeDefault: () -> Unit
        ) {
            if (!isFillable(field, form)) return
            when (result) {
                is FieldParse.Value -> {
                    writeValue(result.value)
                    origins[field] = FieldOrigin.FILL
                    flags.remove(field)
                    valueFields += field
                    writtenFields += field
                }
                is FieldParse.Flagged -> {
                    writeDefault()
                    origins[field] = FieldOrigin.FILL
                    flags[field] = result.reason
                    flagSet = true
                    writtenFields += field
                }
                FieldParse.Absent -> {
                    if (form.originOf(field) == FieldOrigin.FILL) {
                        writeDefault()
                        origins[field] = FieldOrigin.DEFAULT
                        writtenFields += field
                    }
                    flags.remove(field)
                }
            }
        }

        val effectiveCategory: FieldParse<CategoryMatch> =
            if (parse.category is FieldParse.Absent &&
                learned != null &&
                parse.merchant is FieldParse.Value &&
                isFillable(QuickAddField.MERCHANT, form)
            ) {
                FieldParse.Value(learned)
            } else {
                parse.category
            }

        applyField(QuickAddField.AMOUNT, parse.amount, { amount = it.text }, { amount = "" })
        applyField(QuickAddField.MERCHANT, parse.merchant, { merchant = it.text }, { merchant = "" })
        applyField(QuickAddField.DATE, parse.date, { dateMs = it.epochMs }, { dateMs = nowEpochMs })
        applyField(
            QuickAddField.CATEGORY,
            effectiveCategory,
            {
                categoryId = it.categoryId
                categorySource = it.source.toFillSource()
            },
            {
                categoryId = null
                categorySource = null
            }
        )
        applyField(QuickAddField.PAYMENT, parse.payment, { payment = it.toPaymentMethod() }, { payment = PaymentMethod.CASH })

        val outcome = when {
            LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED in parse.lineHints -> OutcomeCode.FOREIGN_CURRENCY_BLOCKED
            valueFields.isEmpty() -> OutcomeCode.FILL_NONE
            QuickAddField.AMOUNT in valueFields && QuickAddField.MERCHANT in valueFields && !flagSet ->
                OutcomeCode.FILL_FULL
            else -> OutcomeCode.FILL_PARTIAL
        }

        return QuickAddFillApplication(
            form = QuickAddFormFields(
                amount = amount,
                merchant = merchant,
                dateMs = dateMs,
                categoryId = categoryId,
                payment = payment,
                origins = origins,
                flags = flags,
                categorySource = categorySource
            ),
            lineHints = parse.lineHints,
            outcome = outcome,
            valueFields = valueFields,
            writtenFields = writtenFields
        )
    }

    private fun CategorySource.toFillSource(): CategoryFillSource = when (this) {
        CategorySource.EXACT_NAME -> CategoryFillSource.EXACT_NAME
        CategorySource.LEARNED -> CategoryFillSource.LEARNED
    }

    private fun QuickAddPayment.toPaymentMethod(): PaymentMethod = when (this) {
        QuickAddPayment.CARD -> PaymentMethod.CARD
        QuickAddPayment.CASH -> PaymentMethod.CASH
    }
}
