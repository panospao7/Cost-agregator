package com.yourname.expensetracker.ui.screens.addexpense

import com.yourname.expensetracker.R
import com.yourname.expensetracker.domain.model.UiText
import com.yourname.expensetracker.domain.quickadd.FlagReason
import com.yourname.expensetracker.domain.quickadd.LineHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * nl-quick-add slice F: the pure UI mapper (ux-flows 2.4, 3, 3.1, 5; G2-11..G2-13; INV-17, INV-20).
 * The mapper never receives the typed line, so these tests also pin that no output can contain it.
 */
class QuickAddUiMapperTest {

    /** What the ViewModel's Fill apply hands to [QuickAddUiMapper.snapshotOf] (test-local; not production code). */
    private data class FillInput(
        val outcome: OutcomeCode,
        val hints: Set<LineHint>,
        val origins: Map<QuickAddField, FieldOrigin>,
        val flags: Map<QuickAddField, FlagReason>
    )

    private fun view(
        outcome: OutcomeCode,
        hints: Set<LineHint> = emptySet(),
        origins: Map<QuickAddField, FieldOrigin> = emptyMap(),
        flags: Map<QuickAddField, FlagReason> = emptyMap()
    ) = FillInput(outcome = outcome, hints = hints, origins = origins, flags = flags)

    private fun snapshot(input: FillInput): QuickAddSummarySnapshot =
        QuickAddUiMapper.snapshotOf(input.outcome, input.hints, input.origins, input.flags)

    private fun lines(input: FillInput, homeCurrency: String? = "EUR"): List<QuickAddLine> =
        QuickAddUiMapper.summaryLines(snapshot(input), homeCurrency)

    private fun resIds(lines: List<QuickAddLine>): List<Int> =
        lines.map { (it.text as UiText.StringResource).resId }

    private fun names(line: QuickAddLine): List<Int> =
        ((line.text as UiText.StringResource).args.single() as QuickAddFieldNames).fieldNameResIds

    // --- counter and Fill gating (INV-17) ---------------------------------------------------------

    @Test
    fun `counter appears from 180 characters and shows length of max`() {
        assertNull(QuickAddUiMapper.counter(0))
        assertNull(QuickAddUiMapper.counter(179))
        assertEquals(
            UiText.StringResource(R.string.quick_add_counter, listOf(180, 200)),
            QuickAddUiMapper.counter(180)
        )
        assertEquals(
            UiText.StringResource(R.string.quick_add_counter, listOf(200, 200)),
            QuickAddUiMapper.counter(200)
        )
        assertEquals(200, QuickAddUiMapper.MAX_LINE_LENGTH)
    }

    @Test
    fun `Fill is enabled only for a non-blank line with a loaded currency and no save running`() {
        assertNull(QuickAddUiMapper.fillBlock(lineIsBlank = false, homeCurrencyLoaded = true, isSaving = false))
        assertNull(QuickAddUiMapper.fillBlockDescription(null))
    }

    @Test
    fun `Fill disabled states carry their description in precedence order`() {
        assertEquals(
            QuickAddFillBlock.CURRENCY_LOADING,
            QuickAddUiMapper.fillBlock(lineIsBlank = true, homeCurrencyLoaded = false, isSaving = true)
        )
        assertEquals(
            QuickAddFillBlock.SAVING,
            QuickAddUiMapper.fillBlock(lineIsBlank = true, homeCurrencyLoaded = true, isSaving = true)
        )
        assertEquals(
            QuickAddFillBlock.LINE_BLANK,
            QuickAddUiMapper.fillBlock(lineIsBlank = true, homeCurrencyLoaded = true, isSaving = false)
        )
        assertEquals(
            UiText.StringResource(R.string.quick_add_fill_disabled_loading),
            QuickAddUiMapper.fillBlockDescription(QuickAddFillBlock.CURRENCY_LOADING)
        )
        assertEquals(
            UiText.StringResource(R.string.quick_add_fill_disabled_blank),
            QuickAddUiMapper.fillBlockDescription(QuickAddFillBlock.LINE_BLANK)
        )
        // No spec string exists for a save in flight; the button is simply disabled.
        assertNull(QuickAddUiMapper.fillBlockDescription(QuickAddFillBlock.SAVING))
    }

    // --- ux section 3 state table -----------------------------------------------------------------

    @Test
    fun `Idle and Typing have no summary`() {
        // No Fill applied means no snapshot in the ViewModel state, hence no lines.
        assertTrue(QuickAddUiMapper.summaryLines(null, "EUR").isEmpty())
    }

    @Test
    fun `Filled-full lists filled fields then asks to check and save`() {
        val result = lines(
            view(
                OutcomeCode.FILL_FULL,
                origins = mapOf(QuickAddField.AMOUNT to FieldOrigin.FILL, QuickAddField.MERCHANT to FieldOrigin.FILL)
            )
        )
        assertEquals(
            listOf(R.string.quick_add_summary_filled, R.string.quick_add_summary_check_save),
            resIds(result)
        )
        assertEquals(
            listOf(R.string.quick_add_field_amount, R.string.quick_add_field_merchant),
            names(result[0])
        )
        assertTrue(result.all { it.tone == QuickAddTone.INFO })
    }

    @Test
    fun `Filled-partial lists filled fields and the fields to check`() {
        val result = lines(
            view(
                OutcomeCode.FILL_PARTIAL,
                origins = mapOf(
                    QuickAddField.AMOUNT to FieldOrigin.FILL,
                    QuickAddField.MERCHANT to FieldOrigin.FILL,
                    QuickAddField.DATE to FieldOrigin.FILL
                ),
                flags = mapOf(QuickAddField.DATE to FlagReason.DATE_FUTURE)
            )
        )
        assertEquals(listOf(R.string.quick_add_summary_filled, R.string.quick_add_summary_review), resIds(result))
        assertEquals(
            listOf(R.string.quick_add_field_amount, R.string.quick_add_field_merchant),
            names(result[0])
        )
        assertEquals(listOf(R.string.quick_add_field_date), names(result[1]))
        assertEquals(QuickAddTone.WARNING, result[1].tone)
        // INV-20: every Fill-written field is named once, a flagged field is never also "filled".
        assertFalse(R.string.quick_add_field_date in names(result[0]))
    }

    @Test
    fun `Nothing recognised shows only the neutral notice`() {
        val result = lines(view(OutcomeCode.FILL_NONE, hints = setOf(LineHint.NOTHING_RECOGNISED)))
        assertEquals(listOf(R.string.quick_add_summary_none), resIds(result))
        assertEquals(QuickAddTone.INFO, result.single().tone)
    }

    @Test
    fun `Fill-none without that hint says nothing new and keeps flags and edits visible`() {
        val result = lines(
            view(
                OutcomeCode.FILL_NONE,
                origins = mapOf(QuickAddField.AMOUNT to FieldOrigin.USER, QuickAddField.DATE to FieldOrigin.FILL),
                flags = mapOf(QuickAddField.DATE to FlagReason.DATE_AMBIGUOUS)
            )
        )
        assertEquals(
            listOf(
                R.string.quick_add_summary_nothing_new,
                R.string.quick_add_summary_review,
                R.string.quick_add_summary_kept_edits
            ),
            resIds(result)
        )
    }

    @Test
    fun `Foreign blocked shows the review line and the home-currency hint`() {
        val result = lines(
            view(
                OutcomeCode.FOREIGN_CURRENCY_BLOCKED,
                hints = setOf(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED),
                origins = mapOf(QuickAddField.AMOUNT to FieldOrigin.FILL, QuickAddField.MERCHANT to FieldOrigin.FILL),
                flags = mapOf(QuickAddField.AMOUNT to FlagReason.AMOUNT_FOREIGN_CURRENCY)
            ),
            homeCurrency = "EUR"
        )
        assertEquals(
            listOf(
                R.string.quick_add_summary_filled,
                R.string.quick_add_summary_review,
                R.string.quick_add_hint_foreign
            ),
            resIds(result)
        )
        assertEquals(listOf(R.string.quick_add_field_merchant), names(result[0]))
        assertEquals(listOf(R.string.quick_add_field_amount), names(result[1]))
        assertEquals(listOf<Any>("EUR"), (result[2].text as UiText.StringResource).args)
        assertEquals(QuickAddTone.WARNING, result[2].tone)
    }

    @Test
    fun `Line too long shows only the bounded notice`() {
        val result = lines(view(OutcomeCode.FILL_NONE, hints = setOf(LineHint.LINE_TOO_LONG)))
        assertEquals(listOf(R.string.quick_add_hint_too_long), resIds(result))
        assertEquals(listOf<Any>(200), (result.single().text as UiText.StringResource).args)
    }

    @Test
    fun `Time ignored is a short info hint after the summary`() {
        val result = lines(
            view(
                OutcomeCode.FILL_FULL,
                hints = setOf(LineHint.TIME_IGNORED),
                origins = mapOf(QuickAddField.AMOUNT to FieldOrigin.FILL, QuickAddField.MERCHANT to FieldOrigin.FILL)
            )
        )
        assertEquals(
            listOf(
                R.string.quick_add_summary_filled,
                R.string.quick_add_summary_check_save,
                R.string.quick_add_hint_time_ignored
            ),
            resIds(result)
        )
    }

    // --- markers and flags (ux 2.4, 3.1) ----------------------------------------------------------

    @Test
    fun `Flagged field shows the flag and never Filled`() {
        FlagReason.values().forEach { reason ->
            val note = QuickAddUiMapper.fieldNote(FieldOrigin.FILL, reason, "EUR")
            assertNotNull(reason.name, note)
            assertEquals(reason.name, QuickAddFieldNoteKind.FLAG, note!!.kind)
            assertNotEquals(
                reason.name,
                UiText.StringResource(R.string.quick_add_filled_marker),
                note.text
            )
        }
    }

    @Test
    fun `Filled marker only for origin FILL without a flag`() {
        assertEquals(
            QuickAddFieldNote(UiText.StringResource(R.string.quick_add_filled_marker), QuickAddFieldNoteKind.FILLED),
            QuickAddUiMapper.fieldNote(FieldOrigin.FILL, null, "EUR")
        )
        assertNull(QuickAddUiMapper.fieldNote(FieldOrigin.USER, null, "EUR"))
        assertNull(QuickAddUiMapper.fieldNote(FieldOrigin.DEFAULT, null, "EUR"))
        assertNull(QuickAddUiMapper.fieldNote(FieldOrigin.PREFILL, null, "EUR"))
    }

    @Test
    fun `every flag reason has its own copy and the foreign flag names the home currency`() {
        val ids = FlagReason.values().map { (QuickAddUiMapper.flagText(it, "EUR") as UiText.StringResource).resId }
        assertEquals(FlagReason.values().size, ids.toSet().size)
        assertEquals(
            UiText.StringResource(R.string.quick_add_flag_amount_foreign, listOf("EUR")),
            QuickAddUiMapper.flagText(FlagReason.AMOUNT_FOREIGN_CURRENCY, "EUR")
        )
    }

    @Test
    fun `MEC F4 the foreign amount flag names whichever currency the sheet passes, not a fixed home currency`() {
        val note = QuickAddUiMapper.fieldNote(FieldOrigin.FILL, FlagReason.AMOUNT_FOREIGN_CURRENCY, "USD")!!
        assertEquals(UiText.StringResource(R.string.quick_add_flag_amount_foreign, listOf("USD")), note.text)
        assertEquals(QuickAddFieldNoteKind.FLAG, note.kind)
        // A null currency (still loading) names an empty currency rather than a stale one.
        assertEquals(
            UiText.StringResource(R.string.quick_add_flag_amount_foreign, listOf("")),
            QuickAddUiMapper.fieldNote(FieldOrigin.FILL, FlagReason.AMOUNT_FOREIGN_CURRENCY, null)!!.text
        )
    }

    @Test
    fun `category chip names why the category is set and which one Remove clears`() {
        assertNull(QuickAddUiMapper.categoryChip(null, "Home"))
        assertNull(QuickAddUiMapper.categoryChip(CategoryFillSource.EXACT_NAME, null))
        val exact = QuickAddUiMapper.categoryChip(CategoryFillSource.EXACT_NAME, "Home")!!
        assertEquals(UiText.StringResource(R.string.quick_add_category_exact_fmt, listOf("Home")), exact.label)
        assertEquals(
            UiText.StringResource(R.string.quick_add_category_remove_cd, listOf("Home")),
            exact.removeContentDescription
        )
        val learned = QuickAddUiMapper.categoryChip(CategoryFillSource.LEARNED, "Home")!!
        assertEquals(UiText.StringResource(R.string.quick_add_category_learned_fmt, listOf("Home")), learned.label)
    }

    // --- G2-12 kept edits, G2-13 stability --------------------------------------------------------

    @Test
    fun `kept edits line appears iff any field origin is USER`() {
        val base = mapOf(QuickAddField.AMOUNT to FieldOrigin.FILL, QuickAddField.MERCHANT to FieldOrigin.FILL)
        val without = lines(view(OutcomeCode.FILL_FULL, origins = base))
        assertFalse(R.string.quick_add_summary_kept_edits in resIds(without))

        QuickAddField.values().forEach { field ->
            val result = lines(view(OutcomeCode.FILL_PARTIAL, origins = base + (field to FieldOrigin.USER)))
            assertEquals(field.name, 1, resIds(result).count { it == R.string.quick_add_summary_kept_edits })
            assertEquals(field.name, R.string.quick_add_summary_kept_edits, resIds(result).last())
        }
    }

    @Test
    fun `foreign hint persists after the user types an amount`() {
        val atFill = view(
            OutcomeCode.FOREIGN_CURRENCY_BLOCKED,
            hints = setOf(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED),
            origins = mapOf(QuickAddField.AMOUNT to FieldOrigin.FILL, QuickAddField.MERCHANT to FieldOrigin.FILL),
            flags = mapOf(QuickAddField.AMOUNT to FlagReason.AMOUNT_FOREIGN_CURRENCY)
        )

        // The snapshot is built once at Fill and held in the ViewModel state; the user's later typing does
        // not rebuild it, so the hint stays.
        val held = snapshot(atFill)
        assertTrue(
            R.string.quick_add_hint_foreign in resIds(QuickAddUiMapper.summaryLines(held, "EUR"))
        )
        // The field flag itself is gone: the user's typed amount replaced it.
        assertNull(QuickAddUiMapper.fieldNote(FieldOrigin.USER, null, "EUR"))
    }

    @Test
    fun `summary is stable after the user edits filled fields`() {
        val atFill = view(
            OutcomeCode.FILL_PARTIAL,
            origins = mapOf(
                QuickAddField.AMOUNT to FieldOrigin.FILL,
                QuickAddField.MERCHANT to FieldOrigin.FILL,
                QuickAddField.DATE to FieldOrigin.FILL
            ),
            flags = mapOf(QuickAddField.DATE to FlagReason.DATE_FUTURE)
        )
        val held = snapshot(atFill)
        val linesAtFill = QuickAddUiMapper.summaryLines(held, "EUR")

        // User edits amount, then merchant, then the flagged date. The held snapshot is not rebuilt (the
        // ViewModel only builds it in the Fill apply), so every read renders the same lines.
        listOf(QuickAddField.AMOUNT, QuickAddField.MERCHANT, QuickAddField.DATE).forEach { field ->
            assertEquals(field.name, linesAtFill, QuickAddUiMapper.summaryLines(held, "EUR"))
        }
        // The per-field markers do follow the edits.
        assertNull(QuickAddUiMapper.fieldNote(FieldOrigin.USER, null, "EUR"))
    }

    @Test
    fun `a new Fill builds a new snapshot that replaces the old one and never stacks`() {
        val first = snapshot(
            view(
                OutcomeCode.FILL_FULL,
                origins = mapOf(QuickAddField.AMOUNT to FieldOrigin.FILL, QuickAddField.MERCHANT to FieldOrigin.FILL)
            )
        )
        assertEquals(setOf(QuickAddField.AMOUNT, QuickAddField.MERCHANT), first.filled)

        // Second Fill: merchant no longer FILL (reverted to DEFAULT), outcome changed.
        val second = view(
            OutcomeCode.FILL_PARTIAL,
            origins = mapOf(QuickAddField.AMOUNT to FieldOrigin.FILL, QuickAddField.MERCHANT to FieldOrigin.DEFAULT)
        )
        assertEquals(setOf(QuickAddField.AMOUNT), snapshot(second).filled)

        // A field moved FILL -> DEFAULT: the new snapshot lists nothing filled.
        val third = second.copy(origins = second.origins + (QuickAddField.AMOUNT to FieldOrigin.DEFAULT))
        assertTrue(snapshot(third).filled.isEmpty())
    }

    @Test
    fun `snapshot fields keep declaration order and flagged fields are never filled`() {
        val snap = snapshot(
            view(
                OutcomeCode.FILL_PARTIAL,
                origins = QuickAddField.values().associateWith { FieldOrigin.FILL },
                flags = mapOf(QuickAddField.DATE to FlagReason.DATE_FUTURE)
            )
        )
        assertEquals(
            listOf(QuickAddField.AMOUNT, QuickAddField.MERCHANT, QuickAddField.CATEGORY, QuickAddField.PAYMENT),
            snap.filled.toList()
        )
        assertEquals(listOf(QuickAddField.DATE), snap.flagged.toList())
    }

    @Test
    fun `a second no-op Fill after a user edit produces a fresh summary`() {
        val atFill = view(
            OutcomeCode.FILL_PARTIAL,
            origins = mapOf(QuickAddField.AMOUNT to FieldOrigin.FILL, QuickAddField.MERCHANT to FieldOrigin.FILL)
        )
        val held = snapshot(atFill)
        assertEquals(setOf(QuickAddField.AMOUNT, QuickAddField.MERCHANT), held.filled)
        assertFalse(held.keptEdits)

        // The user edits the amount, then a second Fill that changes nothing else is applied; the ViewModel
        // builds a new snapshot from the post-Fill origins (amount stays USER).
        val afterEdit = atFill.copy(origins = atFill.origins + (QuickAddField.AMOUNT to FieldOrigin.USER))
        val fresh = snapshot(afterEdit)
        assertNotEquals(held, fresh)
        assertEquals(setOf(QuickAddField.MERCHANT), fresh.filled)
        assertTrue(fresh.keptEdits)
        val freshIds = resIds(QuickAddUiMapper.summaryLines(fresh, "EUR"))
        assertTrue(R.string.quick_add_summary_kept_edits in freshIds)
        val filledLine = QuickAddUiMapper.summaryLines(fresh, "EUR")
            .first { (it.text as UiText.StringResource).resId == R.string.quick_add_summary_filled }
        assertEquals(listOf(R.string.quick_add_field_merchant), names(filledLine))
    }

    // --- privacy: the mapper has no way to echo the line ------------------------------------------

    @Test
    fun `mapper outputs carry only resource ids and bounded arguments`() {
        val everyHint = LineHint.values().toSet()
        val everyField = QuickAddField.values().associateWith { FieldOrigin.FILL }
        val everyFlag = mapOf(
            QuickAddField.AMOUNT to FlagReason.AMOUNT_QUANTITY,
            QuickAddField.DATE to FlagReason.DATE_UNSUPPORTED,
            QuickAddField.PAYMENT to FlagReason.PAYMENT_CONFLICT,
            QuickAddField.CATEGORY to FlagReason.CATEGORY_AMBIGUOUS
        )
        val collected = mutableListOf<UiText>()
        OutcomeCode.values().forEach { outcome ->
            listOf(emptySet<LineHint>(), everyHint, setOf(LineHint.FOREIGN_CURRENCY_NOT_SUPPORTED)).forEach { hints ->
                listOf(emptyMap<QuickAddField, FlagReason>(), everyFlag).forEach { flags ->
                    val snapshot = snapshot(
                        view(outcome, hints, everyField + (QuickAddField.PAYMENT to FieldOrigin.USER), flags)
                    )
                    collected += QuickAddUiMapper.summaryLines(snapshot, "EUR").map { it.text }
                }
            }
        }
        FlagReason.values().forEach { collected += QuickAddUiMapper.flagText(it, "EUR") }
        QuickAddUiMapper.counter(190)?.let { collected += it }
        QuickAddUiMapper.categoryChip(CategoryFillSource.LEARNED, "Home")?.let {
            collected += it.label
            collected += it.removeContentDescription
        }

        assertTrue(collected.isNotEmpty())
        val allowedStrings = setOf("EUR", "Home")
        collected.forEach { text ->
            assertTrue("only resource-backed text: $text", text is UiText.StringResource)
            (text as UiText.StringResource).args.forEach { arg ->
                val ok = arg is Int || arg is QuickAddFieldNames || (arg is String && arg in allowedStrings)
                assertTrue("unexpected argument type in $text", ok)
            }
        }
        // A sentinel typed line cannot appear: the mapper has no parameter that could carry it.
        val sentinel = "SENTINEL-LINE-93711"
        assertFalse(collected.joinToString { it.toString() }.contains(sentinel))
    }
}
