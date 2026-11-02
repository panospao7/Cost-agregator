package com.yourname.expensetracker.ui.util

import android.content.ClipboardManager
import android.content.Context
import com.yourname.expensetracker.domain.quickadd.FieldParse
import com.yourname.expensetracker.domain.quickadd.QuickAddAmountRules
import java.math.BigDecimal

object ClipboardAmountParser {
    private val MIN_AMOUNT = BigDecimal("0.01")
    private val MAX_AMOUNT = BigDecimal("100000")
    private const val BALANCE_ADJACENCY_WINDOW = 35

    private val BALANCE_KEYWORDS = listOf(
        "balance", "available", "avail", "remaining", "limit",
        "υπόλοιπο", "υπολοιπο", "διαθέσιμο", "διαθεσιμο",
        "ypoloipo", "ipolipo", "diathesimo"
    )

    private val regex = Regex(
        """(?<![\d.,])(?:€|\$|EUR|ΕΥΡΩ|ευρώ)?\s*(\d{1,3}(?:,\d{3})+\.\d{1,2}|\d{1,3}(?:\.\d{3})+,\d{1,2}|\d{1,3}(?:[.,]\d{3})+|\d+[.,]\d{1,2})(?![\d,]|\.\d)\s*(?:€|\$|EUR|ΕΥΡΩ|ευρώ)?""",
        RegexOption.IGNORE_CASE
    )

    fun parseAmountFromClipboard(clipboardManager: ClipboardManager): String? {
        return try {
            if (!clipboardManager.hasPrimaryClip()) return null
            
            val item = clipboardManager.primaryClip?.getItemAt(0)
            val text = item?.text?.toString() ?: ""
            
            val matches = regex.findAll(text).toList()
            if (matches.isEmpty()) return null

            val validCandidates = mutableListOf<Pair<MatchResult, String>>()
            for (m in matches) {
                val parsed = QuickAddAmountRules.canonicalize(m.groupValues[1])
                if (parsed is FieldParse.Value) {
                    val canonical = parsed.value.text
                    val value = BigDecimal(canonical)
                    if (value >= MIN_AMOUNT && value <= MAX_AMOUNT) {
                        validCandidates.add(m to canonical)
                    }
                }
            }

            // Reject if no valid amount, more than one distinct currency amount, or balance keyword adjacent.
            val distinctAmounts = validCandidates.map { it.second }.distinct()
            if (distinctAmounts.size != 1) return null

            if (validCandidates.any { (m, _) -> isAdjacentToBalanceKeyword(text, m.range.first, m.range.last + 1) }) {
                return null
            }

            distinctAmounts.single()
        } catch (e: Exception) {
            null
        }
    }

    private fun isAdjacentToBalanceKeyword(text: String, matchStart: Int, matchEnd: Int): Boolean {
        val prefixStart = (matchStart - BALANCE_ADJACENCY_WINDOW).coerceAtLeast(0)
        val prefix = text.substring(prefixStart, matchStart).lowercase()
        val lastClause = prefix.substringAfterLast('.').substringAfterLast(';').substringAfterLast(',')
            .substringAfterLast('\n').substringAfterLast('(').substringAfterLast('-')
        if (!Regex("""\d""").containsMatchIn(lastClause) &&
            BALANCE_KEYWORDS.any { kw -> Regex("""(?<![\p{L}\p{N}])${Regex.escape(kw)}(?![\p{L}\p{N}])""").containsMatchIn(lastClause) }
        ) {
            return true
        }

        val suffixEnd = (matchEnd + BALANCE_ADJACENCY_WINDOW).coerceAtMost(text.length)
        val suffix = text.substring(matchEnd, suffixEnd).lowercase()
        val nextClause = suffix.substringBefore('.').substringBefore(';').substringBefore(',')
            .substringBefore('\n').substringBefore(')').substringBefore('-')
        return Regex("""^\s*(?:in|of|for|:)?\s*(?:avail(?:able)?\s+|διαθέσιμο\s+|διαθεσιμο\s+|diathesimo\s+)?(?:balance|limit|remaining|υπόλοιπο|υπολοιπο|ypoloipo|ipolipo)(?![\p{L}\p{N}])""").containsMatchIn(nextClause)
    }

    fun getClipboardManager(context: Context): ClipboardManager {
        return context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
}
