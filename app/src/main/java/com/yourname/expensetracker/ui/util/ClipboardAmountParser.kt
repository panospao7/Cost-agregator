package com.yourname.expensetracker.ui.util

import android.content.ClipboardManager
import android.content.Context
import com.yourname.expensetracker.domain.util.AmountUtils

object ClipboardAmountParser {
    private val regex = Regex("""(?<![\d.,])(?:€|\$|EUR)?\s*(\d{1,3}(?:,\d{3})*(?:\.\d{2})?|\d{1,3}(?:\.\d{3})*(?:,\d{2})?|\d+[\.,]\d{2})(?![\d.,])\s*(?:€|\$|EUR)?""")

    fun parseAmountFromClipboard(clipboardManager: ClipboardManager): String? {
        return try {
            if (!clipboardManager.hasPrimaryClip()) return null
            
            val item = clipboardManager.primaryClip?.getItemAt(0)
            val text = item?.text?.toString() ?: ""
            
            val match = regex.find(text)
            if (match != null) {
                val value = AmountUtils.parseAmount(match.groupValues[1])
                if (value != null && value in 0.01..100000.0) {
                    return match.groupValues[1]
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
