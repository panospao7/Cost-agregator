package com.yourname.expensetracker.domain.export

/**
 * T05-FIXED: Centralized CSV field sanitizer.
 * Prevents CSV formula injection by neutralizing leading =, +, -, @ characters
 * and stripping tab/newline from non-quoted cells.
 */
object CsvCellSanitizer {
    fun sanitize(field: String): String {
        val trimmed = field.trimStart()
        if (trimmed.startsWith("=") || trimmed.startsWith("+") || 
            trimmed.startsWith("-") || trimmed.startsWith("@")) {
            return "'$field"
        }
        // Replace tabs and newlines in non-quoted contexts
        return field.replace("\t", " ").replace("\n", " ").replace("\r", " ")
    }
}
