package com.yourname.expensetracker.domain.export

/**
 * RFC-4180-safe CSV field encoder.
 *
 * P12-P1-02: Upgraded from strip-only sanitizer to a proper RFC-4180 encoder.
 * The old implementation stripped tabs/newlines and neutralized formula prefixes
 * but did NOT quote fields containing commas or double-quotes, which broke CSV
 * column alignment for values like "Coffee, Snacks" or 'ACME "North"'.
 *
 * Rules applied in order:
 *  1. Strip null/vertical-tab control characters that are always harmful.
 *  2. Formula neutralization: prepend "'" if the trimmed field starts with
 *     =, +, -, @ (OWASP CSV injection protection).
 *  3. RFC-4180 quoting: if the (possibly formula-fixed) field contains a
 *     comma, double-quote, CR, or LF, wrap it in double-quotes and double
 *     any embedded double-quotes.
 *
 * IIF (QuickBooks) tab-delimited fields use [sanitizeIif] instead.
 */
object CsvCellSanitizer {

    /**
     * Encode a single CSV field according to RFC-4180, with optional formula
     * injection neutralization.
     *
     * @param field       Raw field value.
     * @param formulaSafe Whether to neutralize leading =, +, -, @ characters.
     */
    fun sanitize(field: String, formulaSafe: Boolean = true): String {
        // 1. Remove always-harmful control characters.
        val stripped = field.replace("\u0000", "").replace("\u000B", "")

        // 2. Formula injection: prefix with single-quote if leading char is dangerous.
        // P12-PR1 (NEW-P12-003): A leading "-" is safe ONLY when the entire trimmed
        // value is a plain number (genuine negative amount). Any other leading "-"
        // (e.g. "-2+3+cmd|'/C calc'!A0", "-cmd|'/C calc'!A0", "-Pizza Place") is treated
        // as potentially dangerous and neutralized, same as =, +, @.
        val formulaFixed = if (formulaSafe) {
            val trimmed = stripped.trimStart()
            val isPlainNumber = trimmed.matches(Regex("^-?\\d+(\\.\\d+)?$"))
            val isDangerousFormula = !isPlainNumber && (
                trimmed.startsWith("=") || trimmed.startsWith("+") ||
                    trimmed.startsWith("@") || trimmed.startsWith("-"))
            if (isDangerousFormula) {
                "'$stripped"
            } else {
                stripped
            }
        } else {
            stripped
        }

        // 3. RFC-4180 quoting: quote if the field contains any delimiter-sensitive character.
        return if (formulaFixed.contains(',') || formulaFixed.contains('"') ||
            formulaFixed.contains('\n') || formulaFixed.contains('\r')
        ) {
            val escaped = formulaFixed.replace("\"", "\"\"")
            "\"$escaped\""
        } else {
            formulaFixed
        }
    }

    /**
     * Sanitize a field for IIF (QuickBooks tab-delimited) format.
     * IIF uses tabs as delimiters, so tabs and newlines are replaced with spaces.
     * Formula injection protection still applies.
     */
    fun sanitizeIif(field: String): String {
        val stripped = field
            .replace("\u0000", "")
            .replace("\u000B", "")
            .replace("\t", " ")
            .replace("\n", " ")
            .replace("\r", "")
            .trim()
        val trimmed = stripped.trimStart()
        // P12-PR1 (NEW-P12-007): A leading "-" is safe ONLY when the entire trimmed
        // value is a plain number (genuine negative amount). Any other leading "-"
        // (e.g. "-2+3+cmd|'/C calc'!A0", "-cmd|'/C calc'!A0", "-Burger Joint") is treated
        // as potentially dangerous and neutralized, same as =, +, @.
        val isPlainNumber = trimmed.matches(Regex("^-?\\d+(\\.\\d+)?$"))
        val isDangerousFormula = !isPlainNumber && (
            trimmed.startsWith("=") || trimmed.startsWith("+") ||
                trimmed.startsWith("@") || trimmed.startsWith("-"))
        return if (isDangerousFormula) {
            "'$stripped"
        } else {
            stripped
        }
    }
}
