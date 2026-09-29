package com.yourname.expensetracker.architecture

/** Strict worker-only lexical mask; the legacy shared sanitizer is unchanged. */
internal object WorkerSourceMask {
    fun mask(source: String): String = Lexer(source).mask()

    private class Lexer(private val source: String) {
        fun mask(): String {
            val output = source.toCharArray()
            var i = 0
            while (i < source.length) {
                val end = when {
                    source.startsWith("//", i) || source.startsWith("/*", i) -> commentEnd(i)
                    source[i] in "\"'" -> literalEnd(i)
                    else -> { i++; continue }
                }
                for (j in i until end) {
                    if (source[j] != '\n' && source[j] != '\r') output[j] = ' '
                }
                i = end
            }
            return String(output)
        }

        private fun commentEnd(start: Int): Int {
            if (source.startsWith("//", start)) {
                return source.indexOf('\n', start).takeIf { it >= 0 } ?: source.length
            }
            var depth = 1
            var i = start + 2
            while (i < source.length) {
                when {
                    source.startsWith("/*", i) -> { depth++; i += 2 }
                    source.startsWith("*/", i) -> {
                        depth--
                        i += 2
                        if (depth == 0) return i
                    }
                    else -> i++
                }
            }
            throw IllegalArgumentException("WORKER_SOURCE_UNPARSEABLE")
        }

        private fun literalEnd(start: Int): Int {
            val delimiter = if (source.startsWith("\"\"\"", start)) "\"\"\"" else source[start].toString()
            var i = start + delimiter.length
            while (i < source.length) {
                when {
                    source.startsWith(delimiter, i) -> return i + delimiter.length
                    delimiter != "\"\"\"" && source[i] == '\\' -> i += 2
                    delimiter != "'" && source.startsWith("$" + "{", i) -> i = interpolationEnd(i + 1)
                    else -> i++
                }
            }
            throw IllegalArgumentException("WORKER_SOURCE_UNPARSEABLE")
        }

        private fun interpolationEnd(start: Int): Int {
            var depth = 1
            var i = start + 1
            while (i < source.length) {
                when {
                    source.startsWith("//", i) || source.startsWith("/*", i) -> i = commentEnd(i)
                    source[i] in "\"'" -> i = literalEnd(i)
                    source[i] == '{' -> { depth++; i++ }
                    source[i] == '}' -> {
                        depth--
                        i++
                        if (depth == 0) return i
                    }
                    else -> i++
                }
            }
            throw IllegalArgumentException("WORKER_SOURCE_UNPARSEABLE")
        }
    }
}
