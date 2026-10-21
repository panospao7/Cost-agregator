package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.util.MerchantCleaner
import com.yourname.expensetracker.domain.util.TimeProvider
import java.time.ZoneId
import javax.inject.Inject

/**
 * Parses the text of a statement PDF (one list of lines per page) with the layout its header
 * names. When no layout matches, [TextStatementParseResult.layout] is null and the caller falls
 * back to the generic [com.yourname.expensetracker.domain.receipt.BankStatementParser].
 *
 * A matched layout that throws yields [TextStatementParseResult.failed] with its layout id and no
 * rows, so the caller does not fall back for a statement it recognised.
 *
 * Pure and total: no Android, no Room, no logging of line contents; malformed input yields
 * Skipped rows or no rows, never an exception.
 */
class TextStatementParser @Inject constructor(
    private val merchantCleaner: MerchantCleaner,
    private val timeProvider: TimeProvider
) {
    private val layouts: List<TextStatementLayout> = listOf(
        EurobankTransfersLayout,
        RevolutTextLayout,
        NbgAccountLayout,
        EurobankCardLayout
    )

    fun parse(pages: List<List<String>>, home: String?): TextStatementParseResult =
        parseWith(layouts, pages, home)

    /** Selection and failure handling, separated so a throwing layout can be exercised in tests. */
    internal fun parseWith(
        candidates: List<TextStatementLayout>,
        pages: List<List<String>>,
        home: String?
    ): TextStatementParseResult {
        val layout = candidates.firstOrNull { it.matches(pages) } ?: return NO_LAYOUT
        val ctx = StatementContext(merchantCleaner, timeProvider.now(), ZoneId.systemDefault(), home)
        return try {
            layout.parse(pages, ctx)
        } catch (e: RuntimeException) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            // The layout recognised the statement: report it as failed with no rows instead of
            // layout = null, so the caller does not feed a known statement to the generic parser.
            TextStatementParseResult(layout = layout.id, rows = emptyList(), failed = true)
        }
    }

    private companion object {
        val NO_LAYOUT = TextStatementParseResult(layout = null, rows = emptyList())
    }
}
