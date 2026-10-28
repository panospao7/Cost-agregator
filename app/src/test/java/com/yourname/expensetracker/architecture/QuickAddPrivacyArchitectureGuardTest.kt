package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * nl-quick-add slice E: static privacy/architecture guards G-1..G-4
 * (domain-spec 7.5; INV-11, INV-12, INV-13, INV-14; plan "E", pre-approved G2-6).
 *
 * - G-1 (INV-12): nothing under `ui/screens/addexpense` uses `rememberSaveable` or `SavedStateHandle`.
 *   The quick-add line lives in Compose `remember` state only.
 * - G-2 (INV-14): the domain.quickadd package tree imports nothing from `android.*`, `timber`, `domain.ai`, and no
 *   `data.*` except the learned-category lookup's single repository read. The parser files import no
 *   `data.*` at all.
 * - G-3 (INV-13): `AddExpenseViewModel` has no `Timber.*(e, ...)` (throwable argument) and no
 *   interpolated or concatenated Timber message.
 * - G-4 (INV-11, spec R2): the lookup file references none of `categorize`, `normalize(`,
 *   `getCategoryForMerchant`, `MerchantNormalizer`, `traceDecision`.
 *
 * Detection runs over [SourceTextSanitizer.stripCommentsAndStringBodies] output so comment or
 * string-only mentions never count (RP-02 U-004). G-3 additionally inspects the raw text of the
 * balanced argument span (offsets are preserved by the sanitizer) because interpolation lives inside
 * string bodies. No allowlist entries exist. Each guard has a seeded-violation self-check proving its
 * matcher fails, and a non-vacuity assertion (files present, counts above zero).
 */
class QuickAddPrivacyArchitectureGuardTest {

    // ── Source tree resolution ────────────────────────────────────────────────

    private val sourceRoot: File by lazy {
        val userDir = System.getProperty("user.dir") ?: "."
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(userDir, "src/main/java"),
            File(userDir, "app/src/main/java")
        )
        candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=$userDir")
    }

    private val basePackageDir: File
        get() = File(sourceRoot, "com/yourname/expensetracker")

    private val addExpenseDir: File
        get() = File(basePackageDir, "ui/screens/addexpense")

    private val quickAddDir: File
        get() = File(basePackageDir, "domain/quickadd")

    private val viewModelFile: File
        get() = File(addExpenseDir, "AddExpenseViewModel.kt")

    private val lookupFile: File
        get() = File(quickAddDir, LOOKUP_FILE_NAME)

    private val parserFiles: List<File>
        get() = PARSER_FILE_NAMES.map { File(quickAddDir, it) }

    private fun kotlinFilesUnder(dir: File): List<File> =
        dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()

    private fun sanitized(file: File): String =
        SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())

    /** A literal dollar sign for use inside raw-string fixtures without triggering interpolation. */
    private val d = "$"

    // ── G-1 matcher ───────────────────────────────────────────────────────────

    private val saveableStateRegex = Regex("""(?<!\w)(rememberSaveable|SavedStateHandle)""")

    private fun saveableStateViolations(label: String, rawText: String): List<String> {
        val text = SourceTextSanitizer.stripCommentsAndStringBodies(rawText)
        return saveableStateRegex.findAll(text).map { m ->
            val line = text.substring(0, m.range.first).count { it == '\n' } + 1
            "$label:$line ${m.value}"
        }.toList()
    }

    // ── G-2 matchers ──────────────────────────────────────────────────────────

    private val importRegex = Regex("""(?m)^[ \t]*import[ \t]+([\w.]+)""")

    private fun importsOf(text: String): List<String> =
        importRegex.findAll(text).map { it.groupValues[1] }.toList()

    private fun isForbiddenPlatformImport(fqName: String): Boolean =
        fqName.startsWith("android.") ||
            fqName.startsWith("androidx.") ||
            fqName.startsWith("timber.") ||
            fqName == "$BASE_PACKAGE.domain.ai" ||
            fqName.startsWith("$BASE_PACKAGE.domain.ai.")

    private fun isDataImport(fqName: String): Boolean = fqName.startsWith("$BASE_PACKAGE.data.")

    private val inlineForbiddenRegex = Regex(
        """(?<![\w.])(android|androidx|timber)\.\w|(?<![\w.])Timber\s*\.|com\.yourname\.expensetracker\.(data\.|domain\.ai\b)"""
    )

    private fun nonImportLines(text: String): String =
        text.lines().filterNot { it.trimStart().startsWith("import ") }.joinToString("\n")

    /** android.*, androidx.*, timber, domain.ai: imports plus fully-qualified inline references. */
    private fun platformViolations(label: String, rawText: String): List<String> {
        val text = SourceTextSanitizer.stripCommentsAndStringBodies(rawText)
        val fromImports = importsOf(text).filter { isForbiddenPlatformImport(it) }.map { "$label import $it" }
        val inline = inlineForbiddenRegex.findAll(nonImportLines(text))
            .map { it.value }
            .filterNot { it.startsWith("com.yourname.expensetracker.data.") }
            .map { "$label inline ${it.trim()}" }
            .toList()
        return fromImports + inline
    }

    /** Every data.* import and inline data.* reference in the file. */
    private fun dataReferences(rawText: String): Set<String> {
        val text = SourceTextSanitizer.stripCommentsAndStringBodies(rawText)
        val imports = importsOf(text).filter { isDataImport(it) }
        val inline = Regex("""com\.yourname\.expensetracker\.data\.[\w.]+""")
            .findAll(nonImportLines(text)).map { it.value }.toList()
        return (imports + inline).toSet()
    }

    // ── G-3 matcher ───────────────────────────────────────────────────────────

    private val timberCallRegex = Regex("""\bTimber\s*\.\s*(\w+)\s*\(""")
    private val throwableLikeRegex = Regex(
        """^(e|t|ex|err|error|cause|th|throwable|exception)$|\w*(Exception|Throwable)$|\.cause$"""
    )
    private val constantNameRegex = Regex("""^([A-Za-z_][\w]*\.)*[A-Z][A-Z0-9_]*$""")

    private data class TimberCall(val name: String, val line: Int, val rawArgs: String, val sanitizedArgs: String)

    private fun timberCalls(rawText: String): List<TimberCall> {
        val text = SourceTextSanitizer.stripCommentsAndStringBodies(rawText)
        require(text.length == rawText.length) { "Sanitizer must preserve offsets" }
        val calls = mutableListOf<TimberCall>()
        for (m in timberCallRegex.findAll(text)) {
            val open = m.range.last // index of '('
            var depth = 0
            var i = open
            while (i < text.length) {
                when (text[i]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) break
                    }
                }
                i++
            }
            val end = i.coerceAtMost(text.length)
            val line = text.substring(0, m.range.first).count { it == '\n' } + 1
            calls += TimberCall(m.groupValues[1], line, rawText.substring(open + 1, end), text.substring(open + 1, end))
        }
        return calls
    }

    private fun topLevelArguments(sanitizedArgs: String): List<String> {
        val parts = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        for (c in sanitizedArgs) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
            }
            if (c == ',' && depth == 0) {
                parts += current.toString()
                current.clear()
            } else {
                current.append(c)
            }
        }
        if (current.isNotBlank()) parts += current.toString()
        return parts.map { it.trim() }
    }

    private fun hasTopLevelPlus(sanitizedArgs: String): Boolean {
        var depth = 0
        for (c in sanitizedArgs) {
            when (c) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                '+' -> if (depth == 0) return true
            }
        }
        return false
    }

    private fun timberViolations(label: String, rawText: String): List<String> {
        val violations = mutableListOf<String>()
        for (call in timberCalls(rawText)) {
            val args = topLevelArguments(call.sanitizedArgs)
            val first = args.firstOrNull().orEmpty()
            val site = "$label:${call.line}"
            if (call.name == "tag") {
                // Timber.tag(...).x(...) chains hide the real log call from this matcher; fail closed.
                violations += "$site Timber.tag chain not inspected"
                continue
            }
            if (throwableLikeRegex.containsMatchIn(first)) violations += "$site throwable argument"
            if (call.rawArgs.contains('$')) violations += "$site interpolated message"
            if (hasTopLevelPlus(call.sanitizedArgs)) violations += "$site concatenated message"
            if (args.size > 1) violations += "$site extra arguments"
            if (first.isNotEmpty() && !constantNameRegex.matches(first)) {
                violations += "$site non-constant message"
            }
        }
        return violations.distinct()
    }

    // ── G-4 matcher ───────────────────────────────────────────────────────────

    private val forbiddenLookupRegexes: List<Pair<String, Regex>> = listOf(
        "categorize" to Regex("categorize"),
        "normalize(" to Regex("""normalize\s*\("""),
        "getCategoryForMerchant" to Regex("""getCategoryForMerchant"""),
        "MerchantNormalizer" to Regex("""MerchantNormalizer"""),
        "traceDecision" to Regex("""traceDecision""")
    )

    private fun lookupViolations(rawText: String): List<String> {
        val text = SourceTextSanitizer.stripCommentsAndStringBodies(rawText)
        return forbiddenLookupRegexes.filter { (_, regex) -> regex.containsMatchIn(text) }.map { it.first }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // G-1 (INV-12): no rememberSaveable / SavedStateHandle under addexpense
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `INV-12 G-1 add-expense package has no rememberSaveable and no SavedStateHandle`() {
        val files = kotlinFilesUnder(addExpenseDir)
        assertTrue("No Kotlin files found under ${addExpenseDir.absolutePath}; guard would be vacuous", files.isNotEmpty())
        val names = files.map { it.name }.toSet()
        listOf("AddExpenseViewModel.kt", "AddExpenseSheet.kt", "QuickAddFillContract.kt").forEach {
            assertTrue("Expected $it under the add-expense package; found $names", it in names)
        }

        val violations = files.flatMap { saveableStateViolations(it.name, it.readText()) }
        assertTrue(
            "INV-12/G-1: the quick-add line must live in Compose `remember` state only. Found: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun `INV-12 G-1 seeded rememberSaveable and SavedStateHandle violations are detected`() {
        val saveable = """
            @Composable
            fun QuickAddSection() {
                var line by rememberSaveable { mutableStateOf("") }
            }
        """.trimIndent()
        assertEquals(1, saveableStateViolations("Seed.kt", saveable).size)

        val handle = """
            import androidx.lifecycle.SavedStateHandle
            class Vm @Inject constructor(private val state: SavedStateHandle)
        """.trimIndent()
        assertTrue(
            "Both the import and the constructor parameter must be flagged",
            saveableStateViolations("Seed.kt", handle).size >= 2
        )
    }

    @Test
    fun `INV-12 G-1 plain remember state and comment or string mentions are not flagged`() {
        // Slice F compatibility: the QuickAddSection holds the line in `remember` only.
        val remembered = """
            @Composable
            fun QuickAddSection() {
                var line by remember { mutableStateOf("") }
                // never rememberSaveable and never SavedStateHandle here
                val note = "rememberSaveable SavedStateHandle"
            }
        """.trimIndent()
        assertEquals(emptyList<String>(), saveableStateViolations("Seed.kt", remembered))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // G-2 (INV-14): quickadd domain import boundary
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `INV-14 G-2 quickadd domain imports nothing from android timber or domain ai`() {
        val files = kotlinFilesUnder(quickAddDir)
        assertTrue("No Kotlin files under ${quickAddDir.absolutePath}; guard would be vacuous", files.isNotEmpty())
        val names = files.map { it.name }.toSet()
        (PARSER_FILE_NAMES + LOOKUP_FILE_NAME).forEach {
            assertTrue("Expected $it under domain/quickadd; found $names", it in names)
        }
        assertTrue(
            "Scanned files declare no imports at all; import extraction is broken",
            files.sumOf { importsOf(sanitized(it)).size } > 0
        )

        val violations = files.flatMap { platformViolations(it.name, it.readText()) }
        assertTrue("INV-14/G-2: forbidden android/timber/domain.ai reference: $violations", violations.isEmpty())
    }

    @Test
    fun `INV-14 G-2 only the lookup file reads data and only its pinned repository import`() {
        assertTrue("Lookup file missing: ${lookupFile.absolutePath}", lookupFile.exists())
        val lookupData = dataReferences(lookupFile.readText())
        assertEquals(
            "The lookup file's data.* dependencies are pinned to the single repository read",
            LOOKUP_ALLOWED_DATA_IMPORTS,
            lookupData
        )

        val others = kotlinFilesUnder(quickAddDir).filter { it.name != LOOKUP_FILE_NAME }
        assertTrue("No non-lookup quickadd files found", others.size >= PARSER_FILE_NAMES.size)
        val violations = others.associate { it.name to dataReferences(it.readText()) }.filterValues { it.isNotEmpty() }
        assertTrue("INV-14/G-2: non-lookup quickadd files must import no data.*: $violations", violations.isEmpty())
    }

    @Test
    fun `INV-14 G-2 parser files import no data at all`() {
        parserFiles.forEach { file ->
            assertTrue("Parser file missing: ${file.absolutePath}", file.exists())
            assertEquals("${file.name} must import no data.*", emptySet<String>(), dataReferences(file.readText()))
        }
    }

    @Test
    fun `INV-14 G-2 seeded violations are detected`() {
        val android = "package x\nimport android.util.Log\nclass A"
        assertEquals(1, platformViolations("Seed.kt", android).size)

        val androidx = "package x\nimport androidx.compose.runtime.Composable\nclass A"
        assertEquals(1, platformViolations("Seed.kt", androidx).size)

        val timber = "package x\nimport timber.log.Timber\nclass A"
        assertEquals(1, platformViolations("Seed.kt", timber).size)

        val ai = "package x\nimport $BASE_PACKAGE.domain.ai.AiGateway\nclass A"
        assertEquals(1, platformViolations("Seed.kt", ai).size)

        val inline = "package x\nclass A { fun f() = android.util.Log.d(\"t\", \"m\") }"
        assertTrue(platformViolations("Seed.kt", inline).isNotEmpty())

        val parserWithData = "package x\nimport $BASE_PACKAGE.data.repository.CategoryRepository\nclass A"
        assertEquals(
            setOf("$BASE_PACKAGE.data.repository.CategoryRepository"),
            dataReferences(parserWithData)
        )

        val lookupWithExtraRead = """
            package x
            import $BASE_PACKAGE.data.repository.MerchantCategoryRepository
            import $BASE_PACKAGE.data.database.dao.MerchantCategoryDao
            class A
        """.trimIndent()
        assertTrue(
            "An extra data import in the lookup file must differ from the pinned set",
            dataReferences(lookupWithExtraRead) != LOOKUP_ALLOWED_DATA_IMPORTS
        )
    }

    @Test
    fun `INV-14 G-2 allowed imports and comment mentions are not flagged`() {
        val clean = """
            package x
            // import android.util.Log and import timber.log.Timber mentioned in a comment
            import java.time.LocalDate
            import javax.inject.Inject
            import $BASE_PACKAGE.domain.categorization.GreeklishNormalizer
            class A { val s = "android.util.Log timber.log.Timber" }
        """.trimIndent()
        assertEquals(emptyList<String>(), platformViolations("Seed.kt", clean))
        assertEquals(emptySet<String>(), dataReferences(clean))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // G-3 (INV-13): no throwable / interpolated Timber message in the VM
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `INV-13 G-3 add-expense view model has no throwable and no interpolated Timber message`() {
        assertTrue("AddExpenseViewModel.kt missing: ${viewModelFile.absolutePath}", viewModelFile.exists())
        val calls = timberCalls(viewModelFile.readText())
        assertTrue("No Timber calls found in AddExpenseViewModel.kt; guard would be vacuous", calls.isNotEmpty())

        val violations = timberViolations(viewModelFile.name, viewModelFile.readText())
        assertTrue(
            "INV-13/G-3: Timber calls in the VM must pass a bounded constant only (no throwable, no interpolation). " +
                "Found: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun `INV-13 G-3 seeded violations are detected`() {
        val throwable = "class V { fun f() { Timber.e(e, LOG_SAVE_FAILED) } }"
        assertTrue(timberViolations("Seed.kt", throwable).any { it.contains("throwable") })

        val throwableOnly = "class V { fun f() { Timber.w(t) } }"
        assertTrue(timberViolations("Seed.kt", throwableOnly).any { it.contains("throwable") })

        val interpolated = "class V { fun f() { Timber.w(\"lookup failed for ${d}line\") } }"
        assertTrue(timberViolations("Seed.kt", interpolated).any { it.contains("interpolated") })

        val braced = "class V { fun f() { Timber.i(\"amount ${d}{state.amount}\") } }"
        assertTrue(timberViolations("Seed.kt", braced).any { it.contains("interpolated") })

        val concatenated = "class V { fun f() { Timber.w(\"failed: \" + line) } }"
        assertTrue(timberViolations("Seed.kt", concatenated).any { it.contains("concatenated") })

        val formatArgs = "class V { fun f() { Timber.w(\"failed %s\", line) } }"
        assertTrue(timberViolations("Seed.kt", formatArgs).any { it.contains("extra arguments") })

        val dynamic = "class V { fun f() { Timber.w(line) } }"
        assertTrue(timberViolations("Seed.kt", dynamic).any { it.contains("non-constant") })

        val tagChain = "class V { fun f() { Timber.tag(\"x\").w(LOG_SAVE_FAILED) } }"
        assertTrue(timberViolations("Seed.kt", tagChain).any { it.contains("Timber.tag chain not inspected") })

        val multiline = """
            class V { fun f() {
                Timber.e(
                    ex,
                    LOG_SAVE_FAILED
                )
            } }
        """.trimIndent()
        assertTrue(timberViolations("Seed.kt", multiline).any { it.contains("throwable") })
    }

    @Test
    fun `INV-13 G-3 bounded constant calls and comment or string mentions are not flagged`() {
        val clean = """
            class V {
                fun f() {
                    Timber.w(LOG_MERCHANT_SUGGESTION_SEARCH_FAILED)
                    Timber.i("fixed_literal_code")
                    when (code) { A -> Timber.i(LOG_FILL_FULL) }
                    // Timber.e(e, "interpolated ${d}line")
                    val s = "Timber.e(e, ${d}line)"
                }
            }
        """.trimIndent()
        assertEquals(emptyList<String>(), timberViolations("Seed.kt", clean))
        assertEquals("Only the three real calls are inspected", 3, timberCalls(clean).size)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // G-4 (INV-11, spec R2): lookup file is write-free and engine-free
    // ═════════════════════════════════════════════════════════════════════════

    @Test
    fun `INV-11 G-4 lookup file references no categorize normalize merchant-category or trace API`() {
        assertTrue("Lookup file missing: ${lookupFile.absolutePath}", lookupFile.exists())
        val text = sanitized(lookupFile)
        // Non-vacuity: the file really is the lookup, and the permitted near-miss names are present.
        assertTrue(text.contains("normalizedCanonicalNameForMerchant"))
        assertTrue(text.contains("getCategoryByNormalizedCanonical"))
        assertTrue(text.contains("class QuickAddLearnedCategoryLookup"))

        val violations = lookupViolations(lookupFile.readText())
        assertTrue(
            "INV-11/G-4: the lookup must not use the learning/normalizing/tracing API. Found: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun `INV-11 G-4 seeded violations are detected`() {
        val seeds = mapOf(
            "categorize" to "class A { fun f() = engine.categorize(m) }",
            "normalize(" to "class A { fun f() = greeklish.normalize(m) }",
            "getCategoryForMerchant" to "class A { fun f() = repo.getCategoryForMerchant(m) }",
            "MerchantNormalizer" to "import x.MerchantNormalizer\nclass A",
            "traceDecision" to "class A { fun f() = engine.traceDecision(m) }"
        )
        seeds.forEach { (expected, source) ->
            assertTrue("Seed for $expected must be detected", expected in lookupViolations(source))
        }
    }

    @Test
    fun `INV-11 G-4 permitted near-miss names and comment mentions are not flagged`() {
        val clean = """
            // categorize normalize( getCategoryForMerchant MerchantNormalizer traceDecision mentioned in prose
            class A {
                private val categorizationEngine: CategorizationEngine
                fun f() {
                    val key = categorizationEngine.normalizedCanonicalNameForMerchant(m)
                    repo.getCategoryByNormalizedCanonical(key)
                }
            }
        """.trimIndent()
        assertEquals(emptyList<String>(), lookupViolations(clean))
    }

    private companion object {
        const val BASE_PACKAGE = "com.yourname.expensetracker"
        const val LOOKUP_FILE_NAME = "QuickAddLearnedCategoryLookup.kt"
        val PARSER_FILE_NAMES = listOf(
            "QuickAddParser.kt",
            "QuickAddModels.kt",
            "QuickAddAmountRules.kt",
            "QuickAddDateRules.kt",
            "QuickAddVocabulary.kt"
        )

        /** The lookup file's only data.* read dependency, pinned from the file as of slice C. */
        val LOOKUP_ALLOWED_DATA_IMPORTS = setOf("$BASE_PACKAGE.data.repository.MerchantCategoryRepository")
    }
}
