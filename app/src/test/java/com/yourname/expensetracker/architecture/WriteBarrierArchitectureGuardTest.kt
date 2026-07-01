package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * P7-P1-02 — Architecture guard: DatabaseWriteBarrier enforcement for DAO write calls.
 *
 * Every caller of a DAO write method in production source MUST either:
 *  - inject [com.yourname.expensetracker.data.backup.DatabaseWriteBarrier] (a
 *    "barrier-protected" class), or
 *  - be listed in [EXEMPT_CLASSES] with a documented rationale.
 *
 * ## Detected write methods
 *
 * The guard considers the following as DAO write methods:
 *  - Methods annotated with `@Insert`, `@Update`, or `@Delete` (standard Room annotations).
 *  - Methods annotated with `@Query` whose SQL starts with `UPDATE`, `DELETE`, or `INSERT INTO`
 *    (detected via content inspection of the annotation argument — both simple-quoted and
 *    triple-quoted string literals are supported).
 *  - Methods annotated with `@Transaction` whose body (non-abstract only) directly calls any
 *    already-registered write method within the same DAO interface.
 *
 * ## Known coverage gaps
 *
 * 1. **Multi-line calls**: A DAO method call split across multiple lines
 *    (e.g., `dao.method(\n  arg\n)`) is matched by the regex, but extremely
 *    unusual formatting (e.g., `dao.\nmethod(`) could evade detection. None of
 *    the current production code uses such patterns.
 *
 * 2. **Interposed annotations**: If a call site has additional annotations
 *    between the variable and the method call (e.g., `@SomeAnnotation
 *    dao.method()`) the regex may fail to match. This pattern does not occur
 *    in the current codebase.
 *
 * 3. **@Transaction promotion scope**: A `@Transaction` method is promoted to
 *    write method only if its body directly calls a registered write method.
 *    Indirect calls (through another `@Transaction` wrapper defined later in
 *    the same file) are not transitively resolved.
 *
 * 4. **@Query SQL format**: The SQL inside `@Query(...)` is inspected by
 *    simple string prefix matching. If the SQL uses unusual formatting
 *    (e.g., leading comments, CTEs before UPDATE/DELETE/INSERT) it would
 *    not be detected. None of the current DAOs use such patterns.
 *
 * 5. **@RawQuery with write SQL**: Methods using `@RawQuery` with dynamic
 *    SQL that performs writes are not statically detectable and are not
 *    tracked.
 *
 * Why this matters: during backup/restore, [DatabaseWriteBarrier.checkWritesAllowed]
 * throws [com.yourname.expensetracker.data.backup.DatabaseAccessBlockedException] to
 * prevent data corruption. A DAO-write caller that bypasses the barrier silently
 * re-introduces the write-during-restore bug.
 *
 * This test is the CI gate for that invariant. It scans real source files and
 * fails loudly if a new DAO-write caller is added without the barrier.
 */
class WriteBarrierArchitectureGuardTest {

    private val sourceRoot: File by lazy { resolveSourceRoot() }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        /**
         * Classes that legitimately call DAO write methods without injecting
         * [com.yourname.expensetracker.data.backup.DatabaseWriteBarrier].
         *
         * Every entry MUST have a documented rationale. This set must only shrink,
         * never grow — new callers must inject the barrier instead.
         */
        val EXEMPT_CLASSES = setOf(
            // ── Backup / restore operations ──────────────────────────
            "DatabaseBackupRepositoryImpl",     // orchestrates backup/restore within maintenance mode
            "AppDatabase",                      // Room database class; migrations use raw SQL, not DAO write calls
            "AppStartupCoordinator",            // startup recovery — runs before barrier is available
            "MaintenanceOperationRunner",       // maintenance mode itself — enters/drains, does not call DAO writes
            // ── Stale references (kept for safety) ───────────────────
            // "RestoreVerificationCoordinator"  // does not exist yet — uncomment when created
            // "FreshInstallCallback"            // does not exist yet — uncomment when created
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** All production .kt files under the source root. */
    private fun allKotlinFiles(): List<File> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    /** All DAO interface files in the standard DAO package. Cached to avoid O(n×m×k) re-scanning. */
    private val daoFiles: List<File> by lazy {
        File(sourceRoot, "com/yourname/expensetracker/data/database/dao")
            .takeIf { it.exists() }
            ?.walkTopDown()
            ?.filter { it.isFile && it.extension == "kt" && !it.name.contains("RestrictedExpenseDaoMutation") }
            ?.toList()
            ?: emptyList()
    }

    /**
     * Parses a DAO file and returns the interface simple name and the set of
     * method names that perform database writes.
     *
     * Detected write methods:
     *  1. Methods annotated with @Insert, @Update, or @Delete (standard Room annotations).
     *  2. Methods annotated with @Query whose SQL starts with UPDATE, DELETE, or INSERT INTO.
     *  3. @Transaction methods whose body calls any already-registered write method.
     */
    private data class DaoInfo(val interfaceName: String, val writeMethods: Set<String>)

    private fun parseDaoInfo(file: File): DaoInfo? {
        val text = runCatching { file.readText() }.getOrNull() ?: return null

        // Find the @Dao interface declaration to get the interface name.
        val interfaceMatch = Regex("""@Dao\s*\n.*?\binterface\s+(\w+)""").find(text)
            ?: return null
        val interfaceName = interfaceMatch.groupValues[1]

        val writeMethods = mutableSetOf<String>()

        // ── Pass 1: Standard @Insert / @Update / @Delete annotations ─────────
        val standardAnnotationPattern = Regex(
            """@(Insert|Update|Delete)\b(?:\([^)]*\))?\s*\n?\s*(?:\w+\s+)?(?:suspend\s+)?fun\s+(\w+)"""
        )
        for (match in standardAnnotationPattern.findAll(text)) {
            writeMethods.add(match.groupValues[2])
        }

        // ── Pass 1b: @Query with write SQL (UPDATE / DELETE / INSERT INTO) ──
        // Simple-quoted @Query("UPDATE|DELETE|INSERT INTO ...")
        val querySimplePattern = Regex(
            """@Query\("((?:[^"\\]|\\.)*)"\s*\)[\s\n]*(?:\w+\s+)?(?:suspend\s+)?fun\s+(\w+)""",
            setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL)
        )
        for (match in querySimplePattern.findAll(text)) {
            val sql = match.groupValues[1].trimStart()
            if (sql.startsWith("UPDATE", ignoreCase = true) ||
                sql.startsWith("DELETE", ignoreCase = true) ||
                sql.startsWith("INSERT INTO", ignoreCase = true)
            ) {
                writeMethods.add(match.groupValues[2])
            }
        }
        // Triple-quoted @Query("""UPDATE|DELETE|INSERT INTO ...""")
        val queryTriplePattern = Regex(
            "@Query\\(\"\"\"([\\s\\S]*?)\"\"\"\\s*\\)[\\s\\n]*(?:\\w+\\s+)?(?:suspend\\s+)?fun\\s+(\\w+)"
        )
        for (match in queryTriplePattern.findAll(text)) {
            val sql = match.groupValues[1].trimStart()
            if (sql.startsWith("UPDATE", ignoreCase = true) ||
                sql.startsWith("DELETE", ignoreCase = true) ||
                sql.startsWith("INSERT INTO", ignoreCase = true)
            ) {
                writeMethods.add(match.groupValues[2])
            }
        }

        // ── Pass 2: @Transaction methods that call registered writes ─────────
        // A @Transaction method with a body that directly invokes any write method
        // already registered above is itself promoted to a write method.
        val txMethodPattern = Regex(
            """@Transaction[\s\S]*?(?:suspend\s+)?fun\s+(\w+)\s*\("""
        )
        for (match in txMethodPattern.findAll(text)) {
            val txMethodName = match.groupValues[1]
            if (txMethodName in writeMethods) continue // Already registered

            // Find the opening brace of the method body.
            val sigEnd = match.range.last + 1
            val bodyStart = text.indexOf('{', sigEnd)
            if (bodyStart < 0) continue // Abstract method — no body to inspect.

            // Extract the method body by matching braces.
            val body = extractMatchingBraceBody(text, bodyStart) ?: continue

            // If the body calls any already-registered write method, promote it.
            if (writeMethods.any { writeMethod ->
                    Regex("""\b${Regex.escape(writeMethod)}\s*\(""").containsMatchIn(body)
                }
            ) {
                writeMethods.add(txMethodName)
            }
        }

        return DaoInfo(interfaceName, writeMethods)
    }

    /**
     * Extracts text from [startIndex] (which must point to a `{`) up to and
     * including the matching `}`, respecting brace nesting.
     */
    private fun extractMatchingBraceBody(text: String, startIndex: Int): String? {
        if (startIndex >= text.length || text[startIndex] != '{') return null
        var depth = 0
        for (i in startIndex until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(startIndex, i + 1)
                }
            }
        }
        return null
    }

    /**
     * Builds a registry of class simple names that inject DatabaseWriteBarrier.
     * Scans all production .kt files for the barrier injection pattern.
     */
    private fun buildBarrierProtectedClasses(): Set<String> {
        val barrierPattern = Regex(
            """writeBarrier\s*:\s*(?:com\.yourname\.expensetracker\.data\.backup\.)?DatabaseWriteBarrier"""
        )
        return allKotlinFiles()
            .filter { file ->
                runCatching { barrierPattern.containsMatchIn(file.readText()) }.getOrElse { false }
            }
            .map { it.nameWithoutExtension }
            .toSet()
    }

    /**
     * Builds the full set of caller regexes for a given DAO interface + method name.
     * Covers standard variable calls plus `database.daoAccessor()` patterns.
     */
    private fun buildCallerRegexes(interfaceName: String, methodName: String): List<Regex> {
        val lowerFirst = interfaceName.replaceFirstChar { it.lowercaseChar() }
        val regexes = mutableListOf<Regex>()

        // Direct: `expenseDao.insert(` or `ExpenseDao.insert(`
        regexes.add(Regex("""\b${Regex.escape(lowerFirst)}\.${Regex.escape(methodName)}\s*\("""))
        regexes.add(Regex("""\b${Regex.escape(interfaceName)}\.${Regex.escape(methodName)}\s*\("""))

        // Accessor pattern: `database.expenseDao().insert(`, `appDatabase.expenseDao().insert(`
        val accessorPattern = Regex.escape(lowerFirst)
        for (dbVar in listOf("database", "appDatabase", "db")) {
            regexes.add(
                Regex("""\b${Regex.escape(dbVar)}\.${accessorPattern}\s*\(\s*\)\s*\.\s*${Regex.escape(methodName)}\s*\(""")
            )
        }

        return regexes
    }

    // ── Tests ────────────────────────────────────────────────────────────────

    @Test
    fun `all DAO write callers are barrier-protected or exempt`() {
        val allFiles = allKotlinFiles()
        assertTrue(
            "Architecture guard scanned ZERO .kt files in ${sourceRoot.absolutePath}. " +
                "Source root resolution is broken — this test would pass vacuously.",
            allFiles.isNotEmpty()
        )

        val allDaoInfos = daoFiles.mapNotNull { parseDaoInfo(it) }

        val barrierProtected = buildBarrierProtectedClasses()
        val daoFileNames = daoFiles.map { it.nameWithoutExtension }.toSet()

        val violations = mutableListOf<String>()

        for (daoInfo in allDaoInfos) {
            for (methodName in daoInfo.writeMethods) {
                val callRegexes = buildCallerRegexes(daoInfo.interfaceName, methodName)

                for (file in allFiles) {
                    val content = runCatching { file.readText() }.getOrNull() ?: continue
                    val callerClassName = file.nameWithoutExtension

                    // Skip DAO files themselves and the annotation definition.
                    if (callerClassName in daoFileNames) continue
                    if (callerClassName == "RestrictedExpenseDaoMutation") continue

                    val hasCall = callRegexes.any { regex -> regex.containsMatchIn(content) }
                    if (!hasCall) continue

                    // This file calls the DAO write method. Check if it's protected.
                    if (callerClassName in barrierProtected || callerClassName in EXEMPT_CLASSES) continue

                    val relativePath = file.relativeTo(sourceRoot).path
                    violations.add(
                        "$relativePath ($callerClassName) calls ${daoInfo.interfaceName}.$methodName() " +
                            "but does not inject DatabaseWriteBarrier and is not exempt"
                    )
                }
            }
        }

        assertTrue(
            "DAO write callers that bypass DatabaseWriteBarrier:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `dao file count exceeds 30 - guard is not vacuous`() {
        val count = daoFiles.size
        assertTrue(
            "Expected at least 30 DAO files; found $count. DAO resolution may be broken.",
            count > 30
        )
    }

    @Test
    fun `barrier-protected class count exceeds 15 - guard is not vacuous`() {
        val count = buildBarrierProtectedClasses().size
        assertTrue(
            "Expected at least 15 barrier-protected classes; found $count. " +
                "Source scanning may be broken.",
            count > 15
        )
    }

    @Test
    fun `known barrier-protected classes are detected`() {
        val protected = buildBarrierProtectedClasses()
        // These classes are known to inject DatabaseWriteBarrier.
        // If any is missing, the detection regex may have broken.
        val expected = setOf(
            "BankApiIntegration",
            "BudgetRepository",
            "BudgetForecastingEngine",
            "ExpenseRepository",
            "ReceiptLifecycleCoordinator",
            "ReceiptRepository",
            "RecurringLifecycleCoordinator",
            "TransactionLifecycleCoordinator",
            "RecurringRuleLifecycleCoordinator",
            "NotificationProcessingPipeline",
            "WorkerExecutionGuard",
            "BankStatementLifecycleProcessor",
            "EmailReceiptIngestionService",
            "NotificationRepository"
        )
        val missing = expected.filter { it !in protected }
        assertTrue(
            "Expected barrier-protected classes not detected: $missing. " +
                "The detection regex in buildBarrierProtectedClasses() may be broken.",
            missing.isEmpty()
        )
    }

    @Test
    fun `exemption list entries correspond to actual source files`() {
        val allNames = allKotlinFiles().map { it.nameWithoutExtension }.toSet()
        val stale = EXEMPT_CLASSES.filter { it !in allNames }
        assertTrue(
            "EXEMPT_CLASSES contains entries that don't map to real source files: $stale. " +
                "Remove stale entries.",
            stale.isEmpty()
        )
    }

    /**
     * Verifies that every exported DAO file has at least one write annotation
     * (@Insert/@Update/@Delete). Prevents silent removal of all write methods
     * from a DAO without updating this guard.
     */
    @Test
    fun `every DAO file has registered write methods`() {
        val daosWithoutWrites = daoFiles.filter { file ->
            val info = parseDaoInfo(file)
            info == null || info.writeMethods.isEmpty()
        }.map { it.name }
        assertTrue(
            "DAO files with no write methods detected: $daosWithoutWrites. " +
                "If a DAO genuinely has no writes, exclude it from the DAO directory scan.",
            daosWithoutWrites.isEmpty()
        )
    }
}
