package com.yourname.expensetracker.util

/**
 * RP-19 (19-A): character-stream RFC-4180 CSV record reader.
 *
 * Replaces the previous `String.lines()` + per-line field split, which broke
 * quoted fields containing embedded LF/CRLF (a quoted multi-line note split the
 * record into spurious rows). This reader treats the whole content as one
 * character stream, so a quoted field may safely contain LF, CRLF, CR and
 * embedded double quotes.
 *
 * Rules:
 * - Records are separated by LF, CRLF or a bare CR **outside** quotes.
 * - Inside quotes, LF/CRLF/CR are preserved verbatim as field content.
 * - `""` inside a quoted field is a literal double quote.
 * - A quote toggling into quote mode mid-field is tolerated (legacy lenient
 *   behavior of the old per-line parser), but a quote that is still open at
 *   end-of-input marks exactly ONE malformed record — it is never split into
 *   spurious rows.
 * - A file ending with a trailing record separator does not yield a trailing
 *   empty record.
 */
internal object Rfc4180CsvReader {

    /** One parsed CSV record. [malformed] means an unterminated quoted field. */
    internal data class Record(
        val fields: List<String>,
        val malformed: Boolean
    )

    fun parse(content: String): List<Record> {
        val records = mutableListOf<Record>()
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var recordStarted = false
        var malformed = false

        fun endField() {
            fields += current.toString()
            current.clear()
        }

        fun endRecord() {
            endField()
            records += Record(fields.toList(), malformed)
            fields.clear()
            recordStarted = false
            malformed = false
        }

        var index = 0
        while (index < content.length) {
            val ch = content[index]
            recordStarted = true
            when {
                ch == '"' && inQuotes && index + 1 < content.length && content[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> endField()
                ch == '\n' && !inQuotes -> endRecord()
                ch == '\r' && !inQuotes -> {
                    // CRLF outside quotes: consume the LF with the CR.
                    if (index + 1 < content.length && content[index + 1] == '\n') index++
                    endRecord()
                }
                else -> current.append(ch)
            }
            index++
        }

        if (recordStarted) {
            if (inQuotes) {
                // Unterminated quote: EOF ends the field, and the record is
                // flagged malformed instead of silently accepting a truncated value.
                malformed = true
            }
            endRecord()
        }
        return records
    }
}
