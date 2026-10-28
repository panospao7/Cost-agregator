package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.quickadd.FlagReason
import com.yourname.expensetracker.domain.quickadd.LineHint
import com.yourname.expensetracker.domain.quickadd.QuickAddParser

/**
 * Resource ids of localised field names, joined (", ") and resolved by the Compose layer. Carries
 * only resource ids, never a typed value.
 */
data class QuickAddFieldNames(val fieldNameResIds: List<Int>)

enum class QuickAddTone { INFO, WARNING }

/** One line of the quick-add region: a [UiText] (resource id plus bounded arguments) and its tone. */
data class QuickAddLine(val text: UiText, val tone: QuickAddTone)

enum class QuickAddFieldNoteKind { FILLED, FLAG }

/** The "Filled" marker or the flag shown with one field (ux 2.4 marker logic). */
data class QuickAddFieldNote(val text: UiText, val kind: QuickAddFieldNoteKind)

/** Why Fill is disabled (INV-17). Precedence: currency loading, save in flight, blank line. */
enum class QuickAddFillBlock { CURRENCY_LOADING, SAVING, LINE_BLANK }

/** Category chip copy (G2-11): label names why the category is set, remove description names the category. */
data class QuickAddCategoryChip(val label: UiText, val removeContentDescription: UiText)

/**
 * What the last Fill reported (G2-13, item 71a). Held in the ViewModel state (so it survives rotation),
 * built once per applied Fill by [QuickAddUiMapper.snapshotOf]; later user edits clear the field markers
 * but never rewrite this. Enums, sets and a Boolean only: no String, so no typed text can be held here.
 * Sets keep the [QuickAddField] declaration order.
 */
data class QuickAddSummarySnapshot(
    val outcome: OutcomeCode,
    val hints: Set<LineHint>,
    val filled: Set<QuickAddField>,
    val flagged: Set<QuickAddField>,
    val keptEdits: Boolean
)

/**
 * Pure mapper for the quick-add region (C4). It maps state enums and form values to [UiText] and
 * never receives the typed line: only its length (counter) or whether it is blank (gating).
 */
object QuickAddUiMapper {

    const val MAX_LINE_LENGTH: Int = QuickAddParser.MAX_LINE_LENGTH

    /** The counter appears from this length (ux 2.3). */
    const val COUNTER_FROM: Int = 180

    fun counter(lineLength: Int): UiText? =
        if (lineLength >= COUNTER_FROM) {
            UiText.StringResource(R.string.quick_add_counter, listOf(lineLength, MAX_LINE_LENGTH))
        } else {
            null
        }

    fun fillBlock(lineIsBlank: Boolean, homeCurrencyLoaded: Boolean, isSaving: Boolean): QuickAddFillBlock? = when {
        !homeCurrencyLoaded -> QuickAddFillBlock.CURRENCY_LOADING
        isSaving -> QuickAddFillBlock.SAVING
        lineIsBlank -> QuickAddFillBlock.LINE_BLANK
        else -> null
    }

    /** State description for a disabled Fill; null when enabled or when no spec string exists (saving). */
    fun fillBlockDescription(block: QuickAddFillBlock?): UiText? = when (block) {
        QuickAddFillBlock.CURRENCY_LOADING -> UiText.StringResource(R.string.quick_add_fill_disabled_loading)
        QuickAddFillBlock.LINE_BLANK -> UiText.StringResource(R.string.quick_add_fill_disabled_blank)
        QuickAddFillBlock.SAVING, null -> null
    }

    fun fieldNameRes(field: QuickAddField): Int = when (field) {
        QuickAddField.AMOUNT -> R.string.quick_add_field_amount
        QuickAddField.MERCHANT -> R.string.quick_add_field_merchant
        QuickAddField.DATE -> R.string.quick_add_field_date
        QuickAddField.CATEGORY -> R.string.quick_add_field_category
        QuickAddField.PAYMENT -> R.string.quick_add_field_payment
    }

    /** Flag copy per reason. Names the problem, never the offending token. */
    fun flagText(reason: FlagReason, homeCurrency: String?): UiText = when (reason) {
        FlagReason.AMOUNT_FOREIGN_CURRENCY ->
            UiText.StringResource(R.string.quick_add_flag_amount_foreign, listOf(homeCurrency.orEmpty()))
        FlagReason.AMOUNT_QUANTITY -> UiText.StringResource(R.string.quick_add_flag_amount_quantity)
        FlagReason.AMOUNT_MULTIPLE_NUMBERS -> UiText.StringResource(R.string.quick_add_flag_amount_multiple)
        FlagReason.AMOUNT_AMBIGUOUS_SEPARATOR -> UiText.StringResource(R.string.quick_add_flag_amount_separator)
        FlagReason.AMOUNT_UNCLEAR -> UiText.StringResource(R.string.quick_add_flag_amount_unclear)
        FlagReason.AMOUNT_NOT_POSITIVE -> UiText.StringResource(R.string.quick_add_flag_amount_not_positive)
        FlagReason.AMOUNT_OVER_LIMIT -> UiText.StringResource(R.string.quick_add_flag_amount_over_limit)
        FlagReason.DATE_AMBIGUOUS -> UiText.StringResource(R.string.quick_add_flag_date_ambiguous)
        FlagReason.DATE_UNSUPPORTED -> UiText.StringResource(R.string.quick_add_flag_date_unsupported)
        FlagReason.DATE_INVALID -> UiText.StringResource(R.string.quick_add_flag_date_invalid)
        FlagReason.DATE_FUTURE -> UiText.StringResource(R.string.quick_add_flag_date_future)
        FlagReason.PAYMENT_CONFLICT -> UiText.StringResource(R.string.quick_add_flag_payment_conflict)
        FlagReason.CATEGORY_AMBIGUOUS -> UiText.StringResource(R.string.quick_add_flag_category_ambiguous)
    }

    /**
     * Marker logic (ux 2.4): a flag, when present, is shown and "Filled" never is (a Flagged field has
     * origin FILL); otherwise "Filled" iff the origin is FILL; otherwise nothing.
     */
    fun fieldNote(origin: FieldOrigin, flag: FlagReason?, homeCurrency: String?): QuickAddFieldNote? = when {
        flag != null -> QuickAddFieldNote(flagText(flag, homeCurrency), QuickAddFieldNoteKind.FLAG)
        origin == FieldOrigin.FILL ->
            QuickAddFieldNote(UiText.StringResource(R.string.quick_add_filled_marker), QuickAddFieldNoteKind.FILLED)
        else -> null
    }

    /** Chip copy iff the current category was set by Fill and its name is known (G2-11). */
    fun categoryChip(source: CategoryFillSource?, categoryName: String?): QuickAddCategoryChip? {
        if (source == null || categoryName == null) return null
        val labelRes = when (source) {
            CategoryFillSource.EXACT_NAME -> R.string.quick_add_category_exact_fmt
            CategoryFillSource.LEARNED -> R.string.quick_add_category_learned_fmt
        }
        return QuickAddCategoryChip(
            label = UiText.StringResource(labelRes, listOf(categoryName)),
            removeContentDescription = UiText.StringResource(R.string.quick_add_category_remove_cd, listOf(categoryName))
        )
    }

    /**
     * Builds the summary snapshot of one applied Fill (G2-13, item 71a) from the post-Fill form
     * bookkeeping. Called once, in the ViewModel's single Fill apply; the result is stored in state and
     * never recomputed on user edits, so the summary survives edits and rotation.
     */
    fun snapshotOf(
        outcome: OutcomeCode,
        hints: Set<LineHint>,
        origins: Map<QuickAddField, FieldOrigin>,
        flags: Map<QuickAddField, FlagReason>
    ): QuickAddSummarySnapshot {
        val resolved = QuickAddField.values().associateWith { origins[it] ?: FieldOrigin.DEFAULT }
        return QuickAddSummarySnapshot(
            outcome = outcome,
            hints = hints,
            filled = QuickAddField.values().filter { resolved[it] == FieldOrigin.FILL && it !in flags }.toSet(),
            flagged = QuickAddField.values().filter { it in flags }.toSet(),
            keptEdits = resolved.values.any { it == FieldOrigin.USER }
        )
    }

    /** Summary and line-hint lines for one snapshot (ux 3 state table). Empty when there is no snapshot. */
    fun summaryLines(snapshot: QuickAddSummarySnapshot?, homeCurrency: String?): List<QuickAddLine> {
        if (snapshot == null) return emptyList()
        val lines = mutableListOf<QuickAddLine>()
        val tooLong = LineHint.LINE_TOO_LONG in snapshot.hints
        val nothingRecognised = !tooLong && LineHint.NOTHING_RECOGNISED in snapshot.hints

        when {
            tooLong -> lines += QuickAddLine(
                UiText.StringResource(R.string.quick_add_hint_too_long, listOf(MAX_LINE_LENGTH)),
                QuickAddTone.WARNING
            )
            nothingRecognised -> lines += QuickAddLine(
                UiText.StringResource(R.string.quick_add_summary_none),
                QuickAddTone.INFO
            )
            else -> {
                if (snapshot.filled.isNotEmpty()) {
                    lines += QuickAddLine(
                        UiText.StringResource(R.string.quick_add_summary_filled, listOf(names(snapshot.filled))),
                        QuickAddTone.INFO
                    )
                } else {
                    lines += QuickAddLine(
                        UiText.StringResource(R.string.quick_add_summary_nothing_new),
                        QuickAddTone.INFO
                    )
                }
                if (snapshot.flagged.isNotEmpty()) {
                    lines += QuickAddLine(
                        UiText.StringResource(R.string.quick_add_summary_review, listOf(names(snapshot.flagged))),
                        QuickAddTone.WARNING
                    )
                }
                if (snapshot.outcome == OutcomeCode.FILL_FULL && snapshot.flagged.isEmpty()) {
                    lines += QuickAddLine(
                        UiText.StringResource(R.string.quick_add_summary_check_save),
                        QuickAddTone.INFO
                    )
                }
                if (LineHint.TIME_IGNORED in snapshot.hints) {
                    lines += QuickAddLine(
                        UiText.StringResource(R.string.quick_add_hint_time_ignored),
                        QuickAddTone.INFO
                    )
                }
                if (LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED in snapshot.hints && homeCurrency != null) {
                    lines += QuickAddLine(
                        UiText.StringResource(R.string.quick_add_hint_foreign, listOf(homeCurrency)),
                        QuickAddTone.WARNING
                    )
                }
                if (snapshot.keptEdits) {
                    lines += QuickAddLine(
                        UiText.StringResource(R.string.quick_add_summary_kept_edits),
                        QuickAddTone.INFO
                    )
                }
            }
        }
        return lines
    }

    private fun names(fields: Set<QuickAddField>): QuickAddFieldNames =
        QuickAddFieldNames(fields.map { fieldNameRes(it) })
}
