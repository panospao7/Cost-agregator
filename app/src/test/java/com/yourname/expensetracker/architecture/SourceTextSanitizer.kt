package com.yourname.expensetracker.architecture

/**
 * Shared helper for source-scanning architecture guards.
 *
 * Blanks the contents of Kotlin comments and string literals while preserving
 * the overall source layout: delimiters (`//`, `/* */`, `"`, `'`, `"""`) are
 * kept as-is, inner characters become spaces, and newlines survive so line
 * structure is unchanged.
 *
 * Guards run their detection regexes over sanitized text so that neither a
 * violation nor barrier-ownership evidence can be inferred from a comment or
 * a string-only mention (RP-02 U-004: "must not infer protection from a
 * caller list or from a comment/string-only mention").
 *
 * Known limits (acceptable for guard usage):
 *  - no Kotlin lexer; a `/` inside a string is already protected because
 *    strings are consumed first;
 *  - char literals and escaped quotes are handled; raw string templates with
 *    `${...}` expressions containing nested quotes are approximated;
 *  - files with pathological unterminated literals are blanked to EOF, which
 *    is conservative in the safe direction (fewer matches, never invented).
 */
internal object SourceTextSanitizer {

    fun stripCommentsAndStringBodies(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    var j = text.indexOf('\n', i)
                    if (j < 0) j = n
                    appendBlank(out, text, i, j)
                    i = j
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    var j = text.indexOf("*/", i + 2)
                    j = if (j < 0) n else j + 2
                    appendBlank(out, text, i, j)
                    i = j
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    var j = text.indexOf("\"\"\"", i + 3)
                    j = if (j < 0) n else j + 3
                    appendBlank(out, text, i, j)
                    i = j
                }
                c == '"' -> {
                    var j = i + 1
                    while (j < n) {
                        val cj = text[j]
                        if (cj == '\\') {
                            j += 2
                            continue
                        }
                        if (cj == '"') {
                            j++
                            break
                        }
                        j++
                    }
                    appendBlank(out, text, i, j.coerceAtMost(n))
                    i = j.coerceAtMost(n)
                }
                c == '\'' -> {
                    var j = i + 1
                    while (j < n) {
                        val cj = text[j]
                        if (cj == '\\') {
                            j += 2
                            continue
                        }
                        if (cj == '\'') {
                            j++
                            break
                        }
                        j++
                    }
                    appendBlank(out, text, i, j.coerceAtMost(n))
                    i = j.coerceAtMost(n)
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun appendBlank(out: StringBuilder, text: String, from: Int, to: Int) {
        for (k in from until to) {
            out.append(if (text[k] == '\n') '\n' else ' ')
        }
    }
}
