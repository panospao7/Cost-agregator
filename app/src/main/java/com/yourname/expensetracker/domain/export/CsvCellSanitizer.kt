package com.yourname.expensetracker.domain.export

/**
 * T05-FIXED: Centralized CSV field sanitizer.
 * Prevents CSV formula injection by neutralizing leading =, +, -, @ characters
 * and stripping tab/newline from non-quoted cells.
 */
object CsvCellSanitizer {
    fun sanitize(field: String): String {
        val cleaned = field.replace("\t", " ").replace("\n", " ").replace("\r", " ")
        val trimmed = cleaned.trimStart()
        return if (trimmed.startsWith("=") || trimmed.startsWith("+") || 
                   trimmed.startsWith("-") || trimmed.startsWith("@")) {
            "'$cleaned"
        } else {
            cleaned
        }
    }
}
