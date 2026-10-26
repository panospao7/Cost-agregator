package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.architecture.FailClosedScan
import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S6c-3 (INV-29): the installment exclusion lives in `RecurringExpenseEngine.detectPatterns`. The pure
 * detector `detectPatternsFromSnapshots` must therefore be referenced (call, function reference, or
 * spaced call) from no production file other than RecurringExpenseEngine.kt, and inside that file only
 * by its declaration and the single call in detectPatterns, or a caller could bypass the exclusion.
 *
 * Fail-closed: a missing source root, zero scanned files, or an engine file that is not seen at all
 * fails the test (FailClosedScan); it never passes by scanning nothing. Comments and string bodies are
 * stripped first, so a KDoc mention does not count as a call.
 */
class RecurringDetectorSingleEntryPointScanTest {

    private val engineRelativePath = "com/yourname/expensetracker/domain/logic/RecurringExpenseEngine.kt"
    private val providerRelativePath = "com/yourname/expensetracker/domain/forecasting/MergedRecurringPatternsProvider.kt"

    private fun sourceRoot(): File = FailClosedScan.requireRoot(
        listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        ),
        "production source root"
    )

    /**
     * Any mention of the pure detector's name in stripped code: a call, a function reference
     * (`::detectPatternsFromSnapshots`), a call with whitespace before the paren, or a declaration.
     */
    private val pureDetectorName = Regex("""\bdetectPatternsFromSnapshots\b""")
    private val pureDetectorDeclaration = Regex("""\bfun\s+detectPatternsFromSnapshots\b""")
    private val entryPointDeclaration = Regex("""\bfun\s+detectPatterns\b""")

    private fun pureDetectorMatches(strippedCode: String): List<MatchResult> =
        pureDetectorName.findAll(strippedCode).toList()

    /** Index range of the `{ ... }` body of `fun detectPatterns` in stripped code, or null if not found. */
    private fun entryPointBodyRange(strippedCode: String): IntRange? {
        val declaration = entryPointDeclaration.find(strippedCode) ?: return null
        val paramsOpen = strippedCode.indexOf('(', declaration.range.last)
        if (paramsOpen < 0) return null
        var depth = 0
        var paramsClose = -1
        for (i in paramsOpen until strippedCode.length) {
            when (strippedCode[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        paramsClose = i
                        break
                    }
                }
            }
        }
        if (paramsClose < 0) return null
        val bodyOpen = strippedCode.indexOf('{', paramsClose)
        if (bodyOpen < 0) return null
        depth = 0
        for (i in bodyOpen until strippedCode.length) {
            when (strippedCode[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return bodyOpen..i
                }
            }
        }
        return null
    }

    @Test
    fun noProductionFileOutsideTheEngineReferencesThePureDetector() {
        val root = sourceRoot()
        val violations = mutableListOf<String>()
        var sawEngine = false
        FailClosedScan.walkSourceFiles(root) { file, content ->
            val relative = FailClosedScan.relativePath(root, file)
            val code = SourceTextSanitizer.stripCommentsAndStringBodies(content)
            if (relative == engineRelativePath) {
                sawEngine = true
            } else if (pureDetectorMatches(code).isNotEmpty()) {
                violations.add(relative)
            }
        }
        assertTrue("RecurringExpenseEngine.kt was not scanned; the guard would be vacuous", sawEngine)
        assertTrue(
            "detectPatternsFromSnapshots may only be referenced inside RecurringExpenseEngine.kt; found in: " +
                violations.joinToString(),
            violations.isEmpty()
        )
    }

    @Test
    fun engineReferencesThePureDetectorOnlyFromItsDeclarationAndDetectPatterns() {
        val root = sourceRoot()
        val engineCode = SourceTextSanitizer.stripCommentsAndStringBodies(
            FailClosedScan.requireScannedFile(root, engineRelativePath).readText()
        )
        val matches = pureDetectorMatches(engineCode)
        assertEquals(
            "RecurringExpenseEngine.kt must mention detectPatternsFromSnapshots exactly twice " +
                "(its declaration and the single call in detectPatterns)",
            2,
            matches.size
        )
        val declarations = pureDetectorDeclaration.findAll(engineCode).toList()
        assertEquals("exactly one 'fun detectPatternsFromSnapshots' declaration", 1, declarations.size)
        val callMatches = matches.filter { match ->
            declarations.none { it.range.contains(match.range.first) }
        }
        assertEquals("exactly one non-declaration reference (the call)", 1, callMatches.size)
        val call = callMatches.single()
        val after = engineCode.substring(call.range.last + 1)
        assertTrue(
            "the single reference must be a direct call, not a function reference",
            after.trimStart().startsWith("(") && !engineCode.substring(0, call.range.first).trimEnd().endsWith("::")
        )
        val bodyRange = entryPointBodyRange(engineCode)
        assertNotNull("could not locate the body of fun detectPatterns; the guard would be vacuous", bodyRange)
        assertTrue(
            "the single detectPatternsFromSnapshots call must be inside the body of detectPatterns",
            bodyRange!!.contains(call.range.first)
        )
    }

    @Test
    fun matcherFlagsPlantedFunctionReferenceAndSpacedCall() {
        val reference = SourceTextSanitizer.stripCommentsAndStringBodies(
            "val f = engine::detectPatternsFromSnapshots\n"
        )
        val spacedCall = SourceTextSanitizer.stripCommentsAndStringBodies(
            "val p = engine.detectPatternsFromSnapshots (list)\n"
        )
        assertTrue("a function reference must be flagged", pureDetectorMatches(reference).isNotEmpty())
        assertTrue("a call with whitespace before the paren must be flagged", pureDetectorMatches(spacedCall).isNotEmpty())
        assertTrue(
            "a comment-only mention must not be flagged",
            pureDetectorMatches(
                SourceTextSanitizer.stripCommentsAndStringBodies("// detectPatternsFromSnapshots(list)\n")
            ).isEmpty()
        )
    }

    @Test
    fun mergedProviderAndEngineGoThroughDetectPatterns() {
        val root = sourceRoot()
        val providerCode = SourceTextSanitizer.stripCommentsAndStringBodies(
            FailClosedScan.requireScannedFile(root, providerRelativePath).readText()
        )
        val engineCode = SourceTextSanitizer.stripCommentsAndStringBodies(
            FailClosedScan.requireScannedFile(root, engineRelativePath).readText()
        )
        assertTrue(
            "MergedRecurringPatternsProvider must call the engine detectPatterns entry point",
            providerCode.contains("recurringExpenseEngine.detectPatterns(")
        )
        assertTrue(
            "the engine's pattern-building path must call detectPatterns",
            engineCode.contains("val detectedPatterns = detectPatterns(")
        )
    }
}
