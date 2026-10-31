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

    private val WHITESPACE_RUN = Regex("""\s+""")

    /**
     * Not preceded by an ASCII letter or digit (so a run inside a word such as "SHOP12 ..." is not
     * an IBAN start), then country code, check digits and 11-30 more characters.
     */
    private val IBAN_PATTERN = Regex("""(?<![A-Za-z0-9])[A-Z]{2}\d{2}(?:\s?[A-Z0-9]){11,30}""")

    /** An IBAN has a long numeric body; "GR03 THESSALONIKI" has two digits and is a place name. */
    private const val IBAN_MIN_DIGITS = 8

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

    private const val LONG_DIGIT_RUN_MIN = 12
    private const val ACCOUNT_NUMBER_MIN = 9
    private const val MAX_JOINERS_BETWEEN_DIGITS = 2

    /** Ascii digits only, like the regex \d it replaces. */
    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    /** Whitespace, Unicode space separators (no-break space, narrow no-break space, ...) and hyphen. */
    private fun isCardJoiner(c: Char): Boolean =
        c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\u000C' || c == '\r' ||
            Character.getType(c) == Character.SPACE_SEPARATOR.toInt() || c == '-'

    /** Card joiners plus '/' and '.', the separators of domestic account and phone numbers. */
    private fun isAccountJoiner(c: Char): Boolean = isCardJoiner(c) || c == '/' || c == '.'

    /**
     * Linear scanner: a run is a digit followed by any number of (up to [MAX_JOINERS_BETWEEN_DIGITS]
     * joiner characters, digit) steps, so double-spaced and NBSP-separated card groups are caught
     * even before whitespace is collapsed. A run with at least [minDigits] digits becomes
     * "****<last4>"; every other character is copied unchanged. No regex, no recursion: each
     * character is visited a bounded number of times, so a 100,000-digit run costs O(n).
     */
    private fun maskDigitRuns(text: String, minDigits: Int, isJoiner: (Char) -> Boolean): String {
        if (text.length < minDigits) return text
        val length = text.length
        var out: StringBuilder? = null
        var cursor = 0
        var index = 0
        while (index < length) {
            if (!isAsciiDigit(text[index])) {
                index++
                continue
            }
            val start = index
            var last = index
            var digits = 1
            while (true) {
                var probe = last + 1
                var joiners = 0
                while (probe < length && joiners < MAX_JOINERS_BETWEEN_DIGITS && isJoiner(text[probe])) {
                    probe++
                    joiners++
                }
                if (probe < length && isAsciiDigit(text[probe])) {
                    last = probe
                    digits++
                } else {
                    break
                }
            }
            if (digits >= minDigits) {
                val builder = out ?: StringBuilder(length).also { out = it }
                builder.append(text, cursor, start).append("****")
                var tail = 0
                var back = last
                val lastFour = CharArray(4)
                while (back >= start && tail < 4) {
                    if (isAsciiDigit(text[back])) lastFour[3 - tail++] = text[back]
                    back--
                }
                builder.append(lastFour, 4 - tail, tail)
                cursor = last + 1
            }
            index = last + 1
        }
        val result = out ?: return text
        result.append(text, cursor, length)
        return result.toString()
    }

    /**
     * Replaces every run of 12 or more digits (up to two spaces/NBSPs or hyphens between digit
     * groups are part of the run, so card-number-like "1234 5678 9012 3456" counts) with
     * "****<last4>". Shorter numbers such as store numbers and amounts are left untouched.
     */
    fun scrubLongDigitRuns(text: String): String =
        maskDigitRuns(text, LONG_DIGIT_RUN_MIN) { isCardJoiner(it) }

    /**
     * Like [scrubLongDigitRuns] with a minimum of 9 digits and '/', '.' and '-' groups also joining
     * a run: domestic account numbers and mobile numbers. Only for transfer counterparty labels;
     * purchase merchants keep their store numbers.
     */
    fun scrubAccountNumbers(text: String): String =
        maskDigitRuns(text, ACCOUNT_NUMBER_MIN) { isAccountJoiner(it) }

    /** Merchant/account labels for a counterparty known by [name]. */
    fun fromName(name: String, direction: ParsedTransferDirection): Label? {
        val clean = scrubAccountNumbers(maskIbans(name)).replace(WHITESPACE_RUN, " ").trim().take(MAX_NAME_LENGTH).trim()
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
        val bankName = bank?.let { scrubLongDigitRuns(maskIbans(it)) }?.replace(WHITESPACE_RUN, " ")?.trim()
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
            if (compact.drop(2).count { it in '0'..'9' } < IBAN_MIN_DIGITS) {
                // Not an IBAN (a name or place after a two-letter word): look again one character on.
                pos = start + 1
                continue
            }
            val end = start + cut
            matches += IbanMatch(start, end, compact.takeLast(4))
            pos = end
        }
        return matches
    }
}
