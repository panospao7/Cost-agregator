package com.yourname.expensetracker.ui.util

import android.content.ClipboardManager
import android.content.Context
import com.yourname.expensetracker.domain.quickadd.FieldParse
import com.yourname.expensetracker.domain.quickadd.QuickAddAmountRules
import java.math.BigDecimal

object ClipboardAmountParser {
    private val MIN_AMOUNT = BigDecimal("0.01")
    private val MAX_AMOUNT = BigDecimal("100000")

    private val regex = Regex("""(?<![\d.,])(?:€|\$|EUR)?\s*(\d{1,3}(?:,\d{3})*(?:\.\d{2})?|\d{1,3}(?:\.\d{3})*(?:,\d{2})?|\d+[\.,]\d{2})(?![\d.,])\s*(?:€|\$|EUR)?""")

    fun parseAmountFromClipboard(clipboardManager: ClipboardManager): String? {
        return try {
            if (!clipboardManager.hasPrimaryClip()) return null
            
            val item = clipboardManager.primaryClip?.getItemAt(0)
            val text = item?.text?.toString() ?: ""
            
            // First match only. The matched numeric group (no whitespace, no currency marker) goes
            // through the single canonicalisation owner; Flagged/Absent fail closed (no prefill).
            val match = regex.find(text)
            if (match != null) {
                val parsed = QuickAddAmountRules.canonicalize(match.groupValues[1])
                if (parsed is FieldParse.Value) {
                    val canonical = parsed.value.text
                    val value = BigDecimal(canonical)
                    if (value >= MIN_AMOUNT && value <= MAX_AMOUNT) {
                        return canonical
                    }
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    fun getClipboardManager(context: Context): ClipboardManager {
        return context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    }
}
