package com.yourname.expensetracker.domain.receipt.statement

import com.yourname.expensetracker.domain.parser.ParsedTransferDirection

/**
 * Builds the merchant and account labels for statement transfers. A counterparty IBAN is never
 * part of a label: IBAN-only counterparties become "<BANK> ****<last4>" and every output is
 * scrubbed through [maskIbans].
 */
object CounterpartyLabel {
    /** [merchant] fills the merchant field; [accountName] fills transferAccountName ("To: X"/"From: X"). */
    data class Label(val merchant: String, val accountName: String)

    private const val MAX_NAME_LENGTH = 80
    private const val UNKNOWN_COUNTERPARTY = "Counterparty"

    private val IBAN_PATTERN = Regex("""[A-Z]{2}\d{2}(?:\s?[A-Z0-9]){11,30}""")

    /** IBAN lengths per country; a longer regex match gives the extra characters back. */
    private val IBAN_LENGTHS: Map<String, Int> = mapOf(
        "GR" to 27, "CY" to 28, "DE" to 22, "FR" to 27, "IT" to 27, "ES" to 24, "PT" to 25,
        "NL" to 18, "BE" to 16, "AT" to 20, "IE" to 22, "LU" to 20, "FI" to 18, "GB" to 22,
        "LT" to 20, "LV" to 21, "EE" to 20, "SK" to 24, "SI" to 19, "MT" to 31, "BG" to 22,
        "RO" to 24, "PL" to 28, "CH" to 21, "SE" to 24, "DK" to 18, "NO" to 15, "HU" to 28,
        "CZ" to 24, "HR" to 21
    )

    private data class IbanMatch(val start: Int, val end: Int, val last4: String)

    /** Replaces every IBAN-shaped run in [text] with "****<last4>". */
    fun maskIbans(text: String): String {
        val matches = findIbans(text)
        if (matches.isEmpty()) return text
        val out = StringBuilder()
        var cursor = 0
        for (match in matches) {
            out.append(text, cursor, match.start).append("****").append(match.last4)
            cursor = match.end
        }
        out.append(text, cursor, text.length)
        return out.toString()
    }

    /**
     * Digits with up to two whitespace, Unicode space-separator (\p{Zs}: no-break space, narrow
     * no-break space, figure space, ...) or hyphen characters between digit groups, so
     * double-spaced and NBSP-separated card groups are caught even before whitespace is collapsed;
     * counted before replacing.
     */
    private val DIGIT_RUN = Regex("""\d(?:[\s\p{Zs}-]{0,2}\d)*""")
    private const val LONG_DIGIT_RUN_MIN = 12

    /**
     * Replaces every run of 12 or more digits (up to two spaces/NBSPs or hyphens between digit
     * groups are part of the run, so card-number-like "1234 5678 9012 3456" counts) with
     * "****<last4>". Shorter numbers such as store numbers and amounts are left untouched.
     */
    fun scrubLongDigitRuns(text: String): String {
        if (text.length < LONG_DIGIT_RUN_MIN) return text
        return DIGIT_RUN.replace(text) { match ->
            val digits = match.value.filter { it.isDigit() }
            if (digits.length >= LONG_DIGIT_RUN_MIN) "****" + digits.takeLast(4) else match.value
        }
    }

    /** Merchant/account labels for a counterparty known by [name]. */
    fun fromName(name: String, direction: ParsedTransferDirection): Label? {
        val clean = scrubLongDigitRuns(maskIbans(name)).replace(Regex("""\s+"""), " ").trim().take(MAX_NAME_LENGTH).trim()
        if (clean.isEmpty() || clean.none { it.isLetter() }) return null
        return Label(clean, prefix(direction) + clean)
    }

    /** Label for an IBAN-only counterparty: "<BANK> ****<last4>" or "Account ****<last4>". */
    fun fromIban(iban: String, bank: String?, direction: ParsedTransferDirection): Label {
        val compact = iban.filter { it.isLetterOrDigit() }
        return fromReference(bank, compact.takeLast(4), direction)
    }

    /** Label for a counterparty known only by a masked account reference ending in [last4]. */
    fun fromReference(bank: String?, last4: String, direction: ParsedTransferDirection): Label {
        val digits = last4.filter { it.isLetterOrDigit() }.takeLast(4)
        val bankName = bank?.let { scrubLongDigitRuns(maskIbans(it)) }?.replace(Regex("""\s+"""), " ")?.trim()
            ?.take(MAX_NAME_LENGTH)?.trim()?.takeIf { it.isNotEmpty() && it.any { c -> c.isLetter() } }
        val label = "${bankName ?: "Account"} ****$digits"
        return Label(label, prefix(direction) + label)
    }

    /**
     * Label from a free-text statement description: the name when one is present, otherwise the
     * masked reference of the IBAN it contains, otherwise a generic label.
     */
    fun fromDescription(
        description: String,
        bank: String?,
        direction: ParsedTransferDirection
    ): Label {
        val ibans = findIbans(description)
        var nameText = description
        for (match in ibans.asReversed()) {
            nameText = nameText.removeRange(match.start, match.end)
        }
        fromName(nameText, direction)?.let { return it }
        ibans.firstOrNull()?.let { return fromReference(bank, it.last4, direction) }
        return Label(UNKNOWN_COUNTERPARTY, prefix(direction) + UNKNOWN_COUNTERPARTY)
    }

    private fun prefix(direction: ParsedTransferDirection): String =
        if (direction == ParsedTransferDirection.INCOMING) "From: " else "To: "

    /**
     * Manual scan instead of findAll: the greedy regex run can swallow the start of a following
     * IBAN, so after trimming a match to its country length the next search must resume at the end
     * of the TRIMMED match, not at the end of the untrimmed one.
     */
    private fun findIbans(text: String): List<IbanMatch> {
        val matches = mutableListOf<IbanMatch>()
        var pos = 0
        while (pos < text.length) {
            val match = IBAN_PATTERN.find(text, pos) ?: break
            val start = match.range.first
            val raw = match.value
            val expected = IBAN_LENGTHS[raw.take(2)]
            var cut = raw.length
            var compact = raw.filter { !it.isWhitespace() }
            if (expected != null && compact.length > expected) {
                var seen = 0
                for (index in raw.indices) {
                    if (!raw[index].isWhitespace()) seen++
                    if (seen == expected) {
                        cut = index + 1
                        break
                    }
                }
                compact = compact.take(expected)
            }
            val end = start + cut
            matches += IbanMatch(start, end, compact.takeLast(4))
            pos = end
        }
        return matches
    }
}
