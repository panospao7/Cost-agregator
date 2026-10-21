package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.assertFailsWith

/**
 * Fail-closed scanner primitives shared by source-scanning guards (FG-08).
 *
 * Every helper throws [AssertionError] (never degrades to "nothing scanned" /
 * "marker not found, so nothing to check") when its precondition does not hold.
 * [ScannerFailClosedSelfTest] proves each fail-closed branch really fails.
 */
internal object FailClosedScan {

    /** First existing directory among [candidates]; AssertionError if none exists. */
    fun requireRoot(candidates: List<File>, description: String): File =
        candidates.firstOrNull { it.isDirectory }
            ?: throw AssertionError(
                "$description not found (candidates=${candidates.map { it.path }}) — scanner cannot run"
            )

    /** Resolves a file under [sourceRoot], failing (never skipping) if it is missing. */
    fun requireScannedFile(sourceRoot: File, relativePath: String): File {
        val file = File(sourceRoot, relativePath)
        if (!file.isFile) {
            throw AssertionError("scanned file missing: ${file.path} — update the guard if it was moved")
        }
        return file
    }

    /** Path of [file] relative to [root], always '/'-separated. */
    fun relativePath(root: File, file: File): String =
        file.relativeTo(root).path.replace(File.separatorChar, '/')

    /**
     * Visits every `.kt` file under [root]. A missing root, a root with zero Kotlin files
     * and an unreadable file all fail the guard; a failing [block] is never swallowed.
     */
    fun walkSourceFiles(root: File, block: (File, String) -> Unit) {
        if (!root.isDirectory) {
            throw AssertionError("scan root missing: ${root.path} — update the guard if it was moved")
        }
        val files = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        if (files.isEmpty()) {
            throw AssertionError("scanned zero Kotlin files under ${root.path} — guard would be vacuous")
        }
        for (file in files) {
            val text = try {
                file.readText()
            } catch (e: IOException) {
                throw AssertionError("scanned file unreadable: ${file.path} (${e.javaClass.simpleName})", e)
            }
            block(file, text)
        }
    }

    /**
     * Returns the raw source slice of one declaration. Bounds are computed on comment- and
     * string-sanitized code (offsets are preserved), so a marker mentioned in a comment or
     * string can neither satisfy nor shift a section.
     *
     * - [startMarker] must occur in code EXACTLY once (missing or ambiguous fails).
     * - [endMarker], when given, must occur in code after the start (missing fails) and bounds the
     *   section; otherwise the section ends at the next declaration (`fun`) at the same indentation as
     *   the start line, or at end of text for the last declaration.
     * - Trailing blank/comment-only lines (the next declaration's KDoc) are trimmed off.
     */
    fun section(text: String, startMarker: String, endMarker: String? = null): String {
        if (startMarker.isBlank()) throw AssertionError("section start marker must not be blank")
        val code = SourceTextSanitizer.stripCommentsAndStringBodies(text)
        val starts = allIndices(code, startMarker)
        if (starts.isEmpty()) {
            throw AssertionError(
                "section start marker not found in code: '$startMarker' (renamed or moved? update the guard)"
            )
        }
        if (starts.size > 1) {
            throw AssertionError(
                "section start marker '$startMarker' occurs ${starts.size} times in code (must be exactly once)"
            )
        }
        val start = starts.single()
        val end = if (endMarker != null) {
            if (endMarker.isBlank()) throw AssertionError("section end marker must not be blank")
            val index = code.indexOf(endMarker, start + startMarker.length)
            if (index < 0) {
                throw AssertionError(
                    "section end marker not found in code after '$startMarker': '$endMarker'"
                )
            }
            index
        } else {
            nextSiblingFunctionStart(code, start) ?: code.length
        }
        return text.substring(start, trimTrailingBlankLines(code, start, end))
    }

    /** Index of the ')' matching the '(' at [open] in sanitized [code], or null if unbalanced. */
    fun matchingParen(code: String, open: Int): Int? {
        if (open < 0 || open >= code.length || code[open] != '(') return null
        var depth = 0
        for (index in open until code.length) {
            when (code[index]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return index
            }
        }
        return null
    }

    /**
     * `eventType = "X"` literals found in real code (not comments / not inside other strings)
     * of [sectionText]; returns the X values.
     */
    fun eventTypeLiterals(sectionText: String): List<String> =
        eventTypeLiterals(sectionText, SourceTextSanitizer.stripCommentsAndStringBodies(sectionText))

    /**
     * Event types passed as `eventType = "X"` inside the argument list of an
     * `eventWriter.writeCritical(...)` call of [sectionText]. Calls made through any other receiver
     * (e.g. a direct lifecycle DAO insert) are not counted.
     */
    fun writeCriticalEventTypes(sectionText: String): List<String> {
        val code = SourceTextSanitizer.stripCommentsAndStringBodies(sectionText)
        val result = mutableListOf<String>()
        for (call in Regex("""\beventWriter\s*\.\s*writeCritical\s*\(""").findAll(code)) {
            val open = call.range.last
            val close = matchingParen(code, open)
                ?: throw AssertionError("unbalanced eventWriter.writeCritical( call — cannot read its arguments")
            result += eventTypeLiterals(
                sectionText.substring(open, close + 1),
                code.substring(open, close + 1)
            )
        }
        return result
    }

    /**
     * [SourceTextSanitizer] blanks a whole string literal INCLUDING its quote delimiters, so
     * `eventType = "X"` becomes `eventType =` followed by spaces in [code]. Structure is therefore
     * matched on [code] (`eventType =` must be real code: not in a comment, not inside another
     * string), then the value is read from [raw] at the same offset (sanitizing preserves length):
     * the first non-whitespace char after the `=` must be a `"` in [raw] that the sanitizer
     * blanked in [code] (a real string literal in code position). Constant references,
     * `==` comparisons and raw (triple-quoted) strings are not event-type literals.
     */
    private fun eventTypeLiterals(raw: String, code: String): List<String> {
        if (raw.length != code.length) {
            throw AssertionError("sanitized code and raw text differ in length — offsets cannot be mapped")
        }
        return Regex("\\beventType\\s*=").findAll(code).mapNotNull { match ->
            var quote = match.range.last + 1
            while (quote < raw.length && raw[quote].isWhitespace()) quote++
            if (quote >= raw.length || raw[quote] != '"' || code[quote] != ' ') return@mapNotNull null
            if (raw.startsWith("\"\"\"", quote)) return@mapNotNull null
            var close = quote + 1
            while (close < raw.length && raw[close] != '"') {
                close += if (raw[close] == '\\') 2 else 1
            }
            if (close >= raw.length) throw AssertionError("unterminated eventType literal")
            raw.substring(quote + 1, close)
        }.toList()
    }

    private fun allIndices(code: String, marker: String): List<Int> {
        val result = mutableListOf<Int>()
        var from = 0
        while (true) {
            val index = code.indexOf(marker, from)
            if (index < 0) return result
            result += index
            from = index + 1
        }
    }

    private fun nextSiblingFunctionStart(code: String, start: Int): Int? {
        val lineStart = code.lastIndexOf('\n', start - 1) + 1
        val indent = code.substring(lineStart).takeWhile { it == ' ' || it == '\t' }
        val newline = code.indexOf('\n', start)
        if (newline < 0) return null
        val pattern = Regex(
            "(?m)^" + Regex.escape(indent) +
                "(?:(?:private|internal|protected|public|override|open|suspend|inline|operator|tailrec|abstract|final)\\s+)*fun\\s"
        )
        return pattern.find(code, newline + 1)?.range?.first
    }

    private fun trimTrailingBlankLines(code: String, start: Int, end: Int): Int {
        var e = end
        val lineStart = code.lastIndexOf('\n', e - 1) + 1
        if (lineStart > start && code.substring(lineStart, e).isBlank()) e = lineStart
        while (e > start) {
            val previousNewline = e - 1
            if (previousNewline < 0 || code[previousNewline] != '\n') break
            val previousStart = code.lastIndexOf('\n', previousNewline - 1) + 1
            if (previousStart <= start) break
            if (code.substring(previousStart, previousNewline).isBlank()) e = previousStart else break
        }
        return e
    }
}

/**
 * FG-08 fail-closed self-tests: each test drives a scanner into the branch that must
 * fail and asserts the [AssertionError] names the offending path / marker. A scanner whose
 * fail-closed branch silently degraded to "nothing found" would make these tests fail.
 */
class ScannerFailClosedSelfTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun failure(block: () -> Unit): String {
        val error = assertFailsWith<AssertionError> { block() }
        return error.message.orEmpty()
    }

    // ---- requireRoot -------------------------------------------------------------------------

    @Test
    fun `requireRoot fails when no candidate directory exists`() {
        val missing = File(temp.root, "does-not-exist/src/main/java")
        val message = failure { FailClosedScan.requireRoot(listOf(missing), "production source root") }
        assertTrue(message, message.contains("production source root"))
        assertTrue(message, message.contains(missing.path))
    }

    @Test
    fun `requireRoot fails when the candidate is a file not a directory`() {
        val file = temp.newFile("root.kt")
        val message = failure { FailClosedScan.requireRoot(listOf(file), "production source root") }
        assertTrue(message, message.contains(file.path))
    }

    @Test
    fun `requireRoot returns the first existing directory`() {
        val missing = File(temp.root, "nope")
        val existing = temp.newFolder("real")
        assertEquals(existing, FailClosedScan.requireRoot(listOf(missing, existing), "root"))
    }

    // ---- requireScannedFile ------------------------------------------------------------------

    @Test
    fun `requireScannedFile fails on a missing path and names it`() {
        val message = failure { FailClosedScan.requireScannedFile(temp.root, "pkg/Missing.kt") }
        assertTrue(message, message.contains("scanned file missing"))
        assertTrue(message, message.contains("Missing.kt"))
    }

    @Test
    fun `requireScannedFile fails when the path is a directory`() {
        temp.newFolder("pkg", "Dir.kt")
        val message = failure { FailClosedScan.requireScannedFile(temp.root, "pkg/Dir.kt") }
        assertTrue(message, message.contains("Dir.kt"))
    }

    @Test
    fun `requireScannedFile returns an existing file`() {
        val file = File(temp.newFolder("pkg"), "Present.kt").apply { writeText("class Present") }
        assertEquals(file, FailClosedScan.requireScannedFile(temp.root, "pkg/Present.kt"))
    }

    // ---- walkSourceFiles ---------------------------------------------------------------------

    @Test
    fun `walkSourceFiles fails on a missing root and names it`() {
        val missing = File(temp.root, "no-such-root")
        val message = failure { FailClosedScan.walkSourceFiles(missing) { _, _ -> } }
        assertTrue(message, message.contains("scan root missing"))
        assertTrue(message, message.contains(missing.path))
    }

    @Test
    fun `walkSourceFiles fails on an empty root`() {
        val message = failure { FailClosedScan.walkSourceFiles(temp.root) { _, _ -> } }
        assertTrue(message, message.contains("scanned zero Kotlin files"))
        assertTrue(message, message.contains(temp.root.path))
    }

    @Test
    fun `walkSourceFiles fails on a root holding only non kotlin files`() {
        File(temp.root, "notes.txt").writeText("not kotlin")
        File(temp.root, "Build.java").writeText("class Build {}")
        val message = failure { FailClosedScan.walkSourceFiles(temp.root) { _, _ -> } }
        assertTrue(message, message.contains("scanned zero Kotlin files"))
    }

    @Test
    fun `walkSourceFiles visits every nested kotlin file with its text`() {
        File(temp.newFolder("a", "b"), "One.kt").writeText("class One")
        File(temp.root, "Two.kt").writeText("class Two")
        val seen = mutableMapOf<String, String>()
        FailClosedScan.walkSourceFiles(temp.root) { file, text -> seen[file.name] = text }
        assertEquals(mapOf("One.kt" to "class One", "Two.kt" to "class Two"), seen)
    }

    @Test
    fun `walkSourceFiles does not swallow a failing rule block`() {
        File(temp.root, "Bad.kt").writeText("class Bad")
        val message = failure {
            FailClosedScan.walkSourceFiles(temp.root) { file, _ ->
                throw AssertionError("rule violated in ${file.name}")
            }
        }
        assertEquals("rule violated in Bad.kt", message)
    }

    // ---- section -----------------------------------------------------------------------------

    private val sample = """
        class Subject {
            // suspend fun ghost(): never a real declaration
            /** KDoc of first. */
            suspend fun first() {
                fun localHelper() { markerInLocal() }
                firstBody()
            }

            /** KDoc of second mentioning firstBodyInKdoc. */
            private fun second() {
                secondBody()
            }

            fun third() { thirdBody() }
        }
    """.trimIndent()

    @Test
    fun `section fails when the start marker is missing`() {
        val message = failure { FailClosedScan.section(sample, "fun doesNotExist") }
        assertTrue(message, message.contains("fun doesNotExist"))
        assertTrue(message, message.contains("not found"))
    }

    @Test
    fun `section fails when the start marker only appears in a comment`() {
        val message = failure { FailClosedScan.section(sample, "suspend fun ghost") }
        assertTrue(message, message.contains("suspend fun ghost"))
        assertTrue(message, message.contains("not found"))
    }

    @Test
    fun `section fails when the start marker is not unique`() {
        val doubled = "fun twin() { a() }\nfun twin() { b() }\n"
        val message = failure { FailClosedScan.section(doubled, "fun twin") }
        assertTrue(message, message.contains("fun twin"))
        assertTrue(message, message.contains("exactly once"))
    }

    @Test
    fun `section fails when the explicit end marker is missing`() {
        val message = failure { FailClosedScan.section(sample, "suspend fun first", "fun notThere") }
        assertTrue(message, message.contains("fun notThere"))
    }

    @Test
    fun `section fails when the explicit end marker only appears before the start`() {
        val message = failure { FailClosedScan.section(sample, "private fun second", "suspend fun first") }
        assertTrue(message, message.contains("suspend fun first"))
    }

    @Test
    fun `section ends at the next sibling fun and ignores deeper local funs`() {
        val body = FailClosedScan.section(sample, "suspend fun first")
        assertTrue(body, body.contains("firstBody()"))
        assertTrue("local fun must stay inside the section: $body", body.contains("markerInLocal()"))
        assertTrue("must not leak into the next function: $body", !body.contains("secondBody()"))
        assertTrue("next function's KDoc must be trimmed: $body", !body.contains("firstBodyInKdoc"))
    }

    @Test
    fun `section honours an explicit end marker and excludes everything after it`() {
        val body = FailClosedScan.section(sample, "suspend fun first", "fun third")
        assertTrue(body, body.contains("firstBody()"))
        assertTrue("explicit end marker must not stop at the next sibling: $body", body.contains("secondBody()"))
        assertTrue(body, !body.contains("thirdBody()"))
    }

    @Test
    fun `section for the last declaration runs to the end of the text`() {
        val body = FailClosedScan.section(sample, "fun third")
        assertTrue(body, body.contains("thirdBody()"))
    }

    // ---- writeCritical event types ----------------------------------------------------------

    @Test
    fun `writeCriticalEventTypes reads only eventWriter writeCritical calls in real code`() {
        val code = """
            fun linked() {
                // eventWriter.writeCritical(eventType = "COMMENTED")
                val note = "eventWriter.writeCritical(eventType = \"STRINGED\")"
                eventWriter.writeCritical(
                    occurrenceId = 1L,
                    eventType = "OCCURRENCE_PAID",
                    metadata = JSONObject().apply { put("eventType", "NOT_AN_EVENT_TYPE") }.toString()
                )
                lifecycleEventDao.insert(RecurringLifecycleEvent(eventType = "DIRECT_DAO"))
            }
        """.trimIndent()
        assertEquals(listOf("OCCURRENCE_PAID"), FailClosedScan.writeCriticalEventTypes(code))
        assertEquals(
            listOf("OCCURRENCE_PAID", "DIRECT_DAO"),
            FailClosedScan.eventTypeLiterals(code)
        )
    }

    @Test
    fun `eventTypeLiterals reads a real code literal despite the sanitizer blanking quote delimiters`() {
        // Regression: the sanitizer blanks the whole literal (quotes included), so the literal must be
        // read from raw text at the same offset. Covers same-line, split-across-lines and escaped content.
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies("eventType = \"X\"")
        assertEquals("sanitizer preserves length", "eventType = \"X\"".length, sanitized.length)
        assertTrue("literal body must be blanked in code: [$sanitized]", !sanitized.contains("X"))
        assertEquals(listOf("RULE_CREATED_GENERATED"), FailClosedScan.eventTypeLiterals(
            "lifecycleEventDao.insert(RecurringLifecycleEvent(eventType = \"RULE_CREATED_GENERATED\"))"
        ))
        assertEquals(listOf("SPLIT"), FailClosedScan.eventTypeLiterals("RecurringLifecycleEvent(eventType =\n    \"SPLIT\")"))
        assertEquals(listOf("A\\\"B"), FailClosedScan.eventTypeLiterals("x(eventType = \"A\\\"B\")"))
    }

    @Test
    fun `eventTypeLiterals ignores literals that are only in comments or strings`() {
        val code = """
            fun createRule() {
                // eventType = "LINE_COMMENT"
                /* eventType = "BLOCK_COMMENT" */
                /** writes eventType = "KDOC" */
                val note = "eventType = \"IN_STRING\""
                lifecycleEventDao.insert(RecurringLifecycleEvent(eventType = "REAL"))
            }
        """.trimIndent()
        assertEquals(listOf("REAL"), FailClosedScan.eventTypeLiterals(code))
    }

    @Test
    fun `eventTypeLiterals does not count constant references or comparisons`() {
        val code = "fun f() { x(eventType = EVENT_CONSTANT); if (eventType == \"CMP\") {}; y(eventType = \"OK\") }"
        assertEquals(listOf("OK"), FailClosedScan.eventTypeLiterals(code))
    }

    @Test
    fun `eventTypeLiterals fails on an unterminated literal`() {
        val message = failure { FailClosedScan.eventTypeLiterals("x(eventType = \"OPEN") }
        assertTrue(message, message.contains("eventType"))
    }

    @Test
    fun `writeCriticalEventTypes ignores a call and a literal that exist only in a comment or string`() {
        val code = """
            fun linked() {
                // eventWriter.writeCritical(eventType = "OCCURRENCE_PAID")
                val note = "eventWriter.writeCritical(eventType = \"OCCURRENCE_PAID\")"
                eventWriter.writeCritical(
                    occurrenceId = 1L,
                    // eventType = "OCCURRENCE_PAID"
                    eventType = "OTHER"
                )
                lifecycleEventDao.insert(RecurringLifecycleEvent(eventType = "OCCURRENCE_PAID"))
            }
        """.trimIndent()
        assertEquals(listOf("OTHER"), FailClosedScan.writeCriticalEventTypes(code))
        assertEquals(listOf("OTHER", "OCCURRENCE_PAID"), FailClosedScan.eventTypeLiterals(code))
    }

    @Test
    fun `writeCriticalEventTypes fails on an unbalanced call`() {
        val message = failure { FailClosedScan.writeCriticalEventTypes("fun x() { eventWriter.writeCritical(eventType = \"A\"") }
        assertTrue(message, message.contains("writeCritical"))
    }
}
