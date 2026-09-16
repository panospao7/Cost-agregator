package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * P7-P1-02 / RP-02 U-004 — Architecture guard: DatabaseWriteBarrier enforcement
 * for DAO write calls.
 *
 * Every class that performs a DAO write call in production source MUST either:
 *  - declare executable barrier ownership in the same class: a
 *    [com.yourname.expensetracker.data.backup.DatabaseWriteBarrier]-typed
 *    constructor parameter / property (the injector-side convention names it
 *    `writeBarrier`, but any property name with the typed declaration counts)
 *    or a `checkWritesAllowed(...)` call in the same class, or
 *  - be listed in [EXEMPT_CLASSES] with a non-empty justification.
 *
 * ## Class-level detection (primary, alias-proof)
 *
 * A DAO write call is any invocation of a registered write method of a
 * registered DAO interface through:
 *  - a **declared alias**: a constructor parameter or property typed as the
 *    DAO interface under ANY name (e.g. `intakeDao: NotificationIntakeDao`,
 *    `subscriptionDao: ManualRecurringExpenseDao`, fully-qualified types);
 *  - a **local database alias**: `val x = appDatabase|database|db.<daoAccessor>()`
 *    at any scope in the file;
 *  - a **chained accessor**: `appDatabase.daoAccessor().writeMethod(...)`;
 *  - the **canonical receiver** name derived from the interface
 *    (e.g. `expenseDao.updateCategory(...)`), or the interface name itself.
 *
 * Method-level caller regexes ([buildCallerRegexes]) are retained as a
 * secondary check for accessor forms not captured by the class contract.
 *
 * WorkerExecutionGuard entry/checkpoint usage is deliberately NOT accepted as
 * ownership evidence: RP-02 requires the check at the writer, not only at a
 * worker entry wrapper (see the DataRetentionWriter row of the RP-02 plan).
 *
 * Detection runs over text sanitized by [SourceTextSanitizer], so neither a
 * violation nor protection can be inferred from comments or string literals.
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
 * 1. **File-level alias scope**: aliases are resolved per file, not per class —
 *    two top-level classes in one file that reuse the same alias name are
 *    treated conservatively (over-detection, never under-detection).
 *
 * 2. **Multi-line receivers**: a call chain split across lines
 *    (e.g. `appDatabase\n.expenseDao()\n.insert()`) is not matched. None of
 *    the current production code uses such patterns.
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
         * TEMPORARY-RATCHET exemption map: className (file basename) -> justification.
         *
         * Standing entries (no `owner=` tag) document infrastructure classes that
         * legitimately own unbarriered writes. Every entry added by RP-02 batch 1
         * is TEMPORARY and MUST shrink as writers gain barrier ownership:
         *  - each temporary entry carries `owner=<remediation owner> issue=U-004
         *    expiry=YYYY-MM-DD` inside its reason;
         *  - entries with `owner=UNASSIGNED` still need a remediation owner;
         *  - the hygiene test fails on blank reasons and on expired entries,
         *    so the map cannot silently rot.
         */
        val EXEMPT_CLASSES: Map<String, String> = mapOf(
            // ── Standing exemptions ─────────────────────────────────
            "DatabaseBackupRepositoryImpl" to "orchestrates backup/restore within maintenance mode",
            "AppDatabase" to "Room database class; migrations use raw SQL, not DAO write calls",
            "AppStartupCoordinator" to "startup recovery — runs before barrier is available",
            "MaintenanceOperationRunner" to "maintenance mode itself — enters/drains, does not call DAO writes",
            // ── TEMPORARY surfaced by RP-02 alias-proof detection ──
            //
            // PROPOSED OWNERS — coordinator to confirm (RP-02 batch 2 triage).
            // These owner=UNASSIGNED entries are NOT remediated by RP-02 batch 2;
            // each RP must register barrier ownership for its writers:
            //
            // | Class                               | Proposed owner | Domain                        |
            // |-------------------------------------|----------------|-------------------------------|
            // | WorkerRunLogger                     | RP-16          | worker observability          |
            // | OperationRunRecorder                | RP-16          | worker observability          |
            // | WarrantyExpirationWorker            | RP-16 area     | worker lifecycle writes       |
            // | DiagnosticEventWriter               | RP-14 / RP-15  | privacy/diagnostics           |
            // | PrivacyAuditLoggerImpl              | RP-14 / RP-15  | privacy/diagnostics           |
            // | RestoreJournalImporter              | RP-03          | restore journal               |
            // | ReceiptInsertResolver               | RP-12          | receipt lifecycle             |
            // | ReceiptLifecycleEventWriter         | RP-12          | receipt lifecycle             |
            // | CsvExpenseImporter                  | RP-19          | import pipelines              |
            // | JsonExpenseImporter                 | RP-19          | import pipelines              |
            // | LegacyDataMigrationService          | RP-20 candidate| legacy migration              |
            // | NotificationIntakeWorker            | RP-10          | notification intake workers   |
            // | NotificationIntakePayloadRepairer   | RP-10          | notification intake workers   |
            // | NotificationIntakeRecoveryScheduler | RP-10          | notification intake workers   |
            // | TransactionLifecycleEventWriter     | RP-11 area     | transaction lifecycle events  |
            // | DebugExpenseAuditWriter             | RP-11 area     | transaction event audit       |
            "LegacyDataMigrationService" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 pre-existing debt: categoryDao.insert without barrier; triage owner required",
            "CsvExpenseImporter" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 pre-existing debt: categoryDao.insert without barrier; triage owner required",
            "JsonExpenseImporter" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 pre-existing debt: categoryDao.insert without barrier; triage owner required",
            "RestoreJournalImporter" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 pre-existing debt: operationRunDao/operationRunEventDao inserts without barrier (restore-path journal); triage owner required",
            "ReceiptInsertResolver" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 pre-existing debt: scannedReceiptDao.insert without barrier; triage owner required",
            "DebugExpenseAuditWriter" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 pre-existing debt: transactionEventDao.insert without barrier; triage owner required",
            "NotificationIntakeWorker" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 alias-detected: intakeDao claim/mark/purge state writes without barrier; triage owner required",
            "NotificationIntakePayloadRepairer" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 alias-detected: intakeDao payload purge/encrypt writes without barrier; triage owner required",
            "NotificationIntakeRecoveryScheduler" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 alias-detected: intakeDao.releaseStaleProcessing without barrier; triage owner required",
            "WarrantyExpirationWorker" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 alias-detected: deliveryDao delivery lifecycle writes without barrier; triage owner required",
            "WorkerRunLogger" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 alias-detected: backgroundJobRunDao insert/terminal writes without barrier; triage owner required",
            "DiagnosticEventWriter" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 alias-detected: RoomDiagnosticEventWriter pipelineDiagnosticEventDao.insert without barrier; triage owner required",
            "OperationRunRecorder" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 alias-detected: runDao/eventDao operation-run writes without barrier; triage owner required",
            "PrivacyAuditLoggerImpl" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 alias-detected: privacyAuditDao.insert without barrier; triage owner required",
            "ReceiptLifecycleEventWriter" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 alias-detected: receiptEventDao.insert event boundary without barrier; triage owner required",
            "TransactionLifecycleEventWriter" to "owner=UNASSIGNED issue=U-004 expiry=2026-10-31 alias-detected: transactionEventDao.insert event boundary without barrier; triage owner required"
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

    /** Registry of DAO interface -> registered write methods. */
    private fun buildDaoRegistry(): Map<String, Set<String>> =
        daoFiles.mapNotNull { parseDaoInfo(it) }.associate { it.interfaceName to it.writeMethods }

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
     * Retained as a SECONDARY check behind the class-level alias detection.
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

    // ── Class-level contract (RP-02 U-004) ──────────────────────────────────

    /** One detected DAO write call with the receiver evidence that bound it. */
    private data class DaoWriteCall(
        val daoInterface: String,
        val methodName: String,
        val receiver: String,
        val reason: String
    )

    /** Cache of per-DAO declared-alias regexes (compiled once per test instance). */
    private val aliasDeclRegexCache = mutableMapOf<String, Regex>()

    private fun aliasDeclRegex(daoInterface: String): Regex =
        aliasDeclRegexCache.getOrPut(daoInterface) {
            Regex("""\b([A-Za-z_]\w*)\s*:\s*(?:\w+\.)*${Regex.escape(daoInterface)}\b""")
        }

    /** Cache of secondary caller regexes keyed by DAO interface + method. */
    private val callerRegexCache = mutableMapOf<Pair<String, String>, List<Regex>>()

    private fun cachedCallerRegexes(daoInterface: String, methodName: String): List<Regex> =
        callerRegexCache.getOrPut(daoInterface to methodName) {
            buildCallerRegexes(daoInterface, methodName)
        }

    /**
     * Class-level barrier ownership evidence:
     *  - a declaration of a DatabaseWriteBarrier-typed constructor parameter or
     *    property (the injector-side convention names it `writeBarrier`, but any
     *    property name with the typed declaration counts), or
     *  - a `checkWritesAllowed(...)` call in the same class.
     *
     * WorkerExecutionGuard usage is intentionally NOT sufficient (RP-02: the
     * worker entry/checkpoint check is not ownership of later writes).
     * [sanitizedContent] must already be comment/string-stripped, so evidence
     * inside comments or string literals does not count.
     */
    private fun isClassLevelBarrierProtected(sanitizedContent: String): Boolean {
        val declaresBarrier = Regex(
            """\b[A-Za-z_]\w*\s*:\s*(?:com\.yourname\.expensetracker\.data\.backup\.)?DatabaseWriteBarrier\b"""
        ).containsMatchIn(sanitizedContent)
        val callsCheck = Regex("""\bcheckWritesAllowed\s*\(""").containsMatchIn(sanitizedContent)
        return declaresBarrier || callsCheck
    }

    /**
     * Detects every DAO write call in [sanitizedContent] against the [registry].
     * Receiver binding is alias-proof: any DAO-typed declared name, any local
     * `val x = appDatabase|database|db.<daoAccessor>()` alias, chained
     * accessors, canonical receiver names, and (secondary) the classic
     * caller regexes.
     */
    private fun detectDaoWriteCalls(
        sanitizedContent: String,
        registry: Map<String, Set<String>>
    ): List<DaoWriteCall> {
        // method name -> DAO interfaces that own it as a write method
        val methodIndex = registry.entries
            .flatMap { (dao, methods) -> methods.map { it to dao } }
            .groupBy({ it.first }, { it.second })

        // accessor name -> DAO interface
        val accessorToDao = registry.keys.associateBy { it.replaceFirstChar { c -> c.lowercaseChar() } }

        val found = mutableMapOf<String, DaoWriteCall>() // key: dao|method|receiver

        fun record(dao: String, method: String, receiver: String, reason: String) {
            found.putIfAbsent("$dao|$method|$receiver", DaoWriteCall(dao, method, receiver, reason))
        }

        // 1. Local aliases: val x = appDatabase|database|db.<daoAccessor>()
        val localAliases = mutableMapOf<String, MutableSet<String>>() // alias -> DAOs
        val localAliasRegex = Regex(
            """\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*(appDatabase|database|db)\s*\.\s*([A-Za-z_]\w*)\s*\(\s*\)"""
        )
        for (match in localAliasRegex.findAll(sanitizedContent)) {
            val dao = accessorToDao[match.groupValues[3]] ?: continue
            localAliases.getOrPut(match.groupValues[1]) { mutableSetOf() }.add(dao)
        }

        // 2. Declared aliases per DAO (lazy, cached regexes)
        val declaredAliases = mutableMapOf<String, Set<String>>() // DAO -> alias names
        fun declaredNames(dao: String): Set<String> =
            declaredAliases.getOrPut(dao) {
                aliasDeclRegex(dao).findAll(sanitizedContent).map { it.groupValues[1] }.toSet()
            }

        // 3. Chained accessors: appDatabase.daoAccessor().writeMethod(
        val chainedRegex = Regex(
            """\b(appDatabase|database|db)\s*\.\s*([A-Za-z_]\w*)\s*\(\s*\)\s*\.\s*([A-Za-z_]\w*)\s*\("""
        )
        for (match in chainedRegex.findAll(sanitizedContent)) {
            val dao = accessorToDao[match.groupValues[2]] ?: continue
            val method = match.groupValues[3]
            if (method !in registry[dao].orEmpty()) continue
            record(
                dao,
                method,
                "${match.groupValues[1]}.${match.groupValues[2]}()",
                "chained accessor '${match.groupValues[1]}.${match.groupValues[2]}().'"
            )
        }

        // 4. receiver.method( pairs bound to a DAO by declared/canonical/local alias
        val callPairRegex = Regex("""\b([A-Za-z_]\w*)\s*\.\s*([A-Za-z_]\w*)\s*\(""")
        for (match in callPairRegex.findAll(sanitizedContent)) {
            val receiver = match.groupValues[1]
            val method = match.groupValues[2]
            val candidateDaos = methodIndex[method] ?: continue
            for (dao in candidateDaos) {
                if (method !in registry[dao].orEmpty()) continue
                val canonical = dao.replaceFirstChar { it.lowercaseChar() }
                when {
                    receiver == canonical ->
                        record(dao, method, receiver, "canonical receiver '$receiver'")
                    receiver == dao ->
                        record(dao, method, receiver, "interface-name receiver '$receiver'")
                    dao in localAliases[receiver].orEmpty() ->
                        record(dao, method, receiver, "local database alias '$receiver'")
                    receiver in declaredNames(dao) ->
                        record(dao, method, receiver, "declared alias '$receiver: $dao'")
                }
            }
        }

        // 5. Secondary check: classic caller regexes for pairs not already covered
        //    by the structural detection above (safety net against future changes
        //    to the class-level contract).
        for ((dao, methods) in registry) {
            for (method in methods) {
                if (found.keys.any { it.startsWith("$dao|$method|") }) continue
                for (regex in cachedCallerRegexes(dao, method)) {
                    if (regex.containsMatchIn(sanitizedContent)) {
                        record(dao, method, "regex", "caller regex (secondary check)")
                        break
                    }
                }
            }
        }

        return found.values.sortedWith(compareBy({ it.daoInterface }, { it.methodName }, { it.receiver }))
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

        val registry = buildDaoRegistry()
        assertTrue(
            "Expected a non-empty DAO registry; parseDaoInfo failed for every DAO file.",
            registry.isNotEmpty()
        )

        val daoFileNames = daoFiles.map { it.nameWithoutExtension }.toSet()

        val violations = mutableListOf<String>()

        for (file in allFiles) {
            val callerClassName = file.nameWithoutExtension

            // Skip DAO files themselves and the annotation definition.
            if (callerClassName in daoFileNames) continue
            if (callerClassName == "RestrictedExpenseDaoMutation") continue

            val sanitized = runCatching {
                SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
            }.getOrNull() ?: continue

            if (callerClassName in EXEMPT_CLASSES.keys) continue
            if (isClassLevelBarrierProtected(sanitized)) continue

            val calls = detectDaoWriteCalls(sanitized, registry)
            if (calls.isEmpty()) continue

            val relativePath = file.relativeTo(sourceRoot).path
            for (call in calls) {
                violations.add(
                    "$relativePath ($callerClassName): ${call.daoInterface}.${call.methodName}() " +
                        "via ${call.reason} — class has no DatabaseWriteBarrier ownership and is not exempt"
                )
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
    fun `subscription management repository alias usage is protected - not exempt`() {
        val file = allKotlinFiles().firstOrNull { it.nameWithoutExtension == "SubscriptionManagementRepository" }
            ?: error("SubscriptionManagementRepository.kt not found under ${sourceRoot.absolutePath}")
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())

        // The alias pattern MUST be detected (no under-detection)…
        val calls = detectDaoWriteCalls(sanitized, buildDaoRegistry())
        assertTrue(
            "Alias detection must observe SubscriptionManagementRepository's " +
                "subscriptionDao (ManualRecurringExpenseDao) write calls.",
            calls.any { it.receiver == "subscriptionDao" && it.daoInterface == "ManualRecurringExpenseDao" }
        )
        // …and the class MUST be recognized as barrier-protected WITHOUT any exemption.
        assertTrue(
            "SubscriptionManagementRepository injects writeBarrier and calls checkWritesAllowed " +
                "before every mutation; the class-level contract must accept it.",
            isClassLevelBarrierProtected(sanitized)
        )
        assertFalse(
            "SubscriptionManagementRepository must NOT be exempted.",
            "SubscriptionManagementRepository" in EXEMPT_CLASSES.keys
        )
    }

    @Test
    fun `exemption list entries correspond to actual source files`() {
        val allNames = allKotlinFiles().map { it.nameWithoutExtension }.toSet()
        val stale = EXEMPT_CLASSES.keys.filter { it !in allNames }
        assertTrue(
            "EXEMPT_CLASSES contains entries that don't map to real source files: $stale. " +
                "Remove stale entries.",
            stale.isEmpty()
        )
    }

    /**
     * Hygiene for the exemption map: non-blank justifications; temporary entries
     * (tagged `owner=`) must carry an issue id and a parseable, non-expired
     * expiry date so the map is forced to shrink over time.
     */
    private fun exemptionHygieneViolations(exemptions: Map<String, String>): List<String> {
        val violations = mutableListOf<String>()
        val expiryPattern = Regex("""expiry=(\d{4}-\d{2}-\d{2})""")
        val today = LocalDate.now()
        for ((className, reason) in exemptions) {
            if (reason.isBlank()) {
                violations.add("$className: blank justification")
                continue
            }
            if (!reason.contains("owner=")) continue // standing entry — no ratchet
            if (!reason.contains("issue=")) {
                violations.add("$className: temporary entry missing issue= id")
            }
            val expiryMatch = expiryPattern.find(reason)
            when {
                expiryMatch == null ->
                    violations.add("$className: temporary entry missing expiry=YYYY-MM-DD")
                else -> {
                    val parsed = runCatching { LocalDate.parse(expiryMatch.groupValues[1]) }.getOrNull()
                    when {
                        parsed == null ->
                            violations.add("$className: unparseable expiry date in reason")
                        parsed.isBefore(today) ->
                            violations.add(
                                "$className: exemption EXPIRED on $parsed — remove it or remediate the writer"
                            )
                    }
                }
            }
        }
        return violations
    }

    @Test
    fun `exemption justifications are non-blank and temporary entries are unexpired`() {
        val violations = exemptionHygieneViolations(EXEMPT_CLASSES)
        assertTrue(
            "EXEMPT_CLASSES hygiene violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
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

    // ── Fixtures for the class-level detection contract ─────────────────────

    @Test
    fun `fixture - injected alias writer without barrier is flagged`() {
        val source = """
            class IntakeWriter @Inject constructor(
                private val intakeDao: NotificationIntakeDao
            ) {
                suspend fun persist(e: NotificationIntakeEntity): Long = intakeDao.insertOrIgnore(e)
            }
        """.trimIndent()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(source)
        val registry = mapOf("NotificationIntakeDao" to setOf("insertOrIgnore"))

        val calls = detectDaoWriteCalls(sanitized, registry)
        assertTrue(
            "Expected the injected alias write to be detected: $calls",
            calls.any {
                it.daoInterface == "NotificationIntakeDao" &&
                    it.methodName == "insertOrIgnore" &&
                    it.receiver == "intakeDao"
            }
        )
        assertFalse(
            "A class without any DatabaseWriteBarrier reference must not count as protected.",
            isClassLevelBarrierProtected(sanitized)
        )
    }

    @Test
    fun `fixture - local database alias writer without barrier is flagged`() {
        val source = """
            class RetentionAudit(private val appDatabase: AppDatabase) {
                suspend fun audit(e: PrivacyAuditEvent) {
                    val auditDao = appDatabase.privacyAuditDao()
                    auditDao.insert(e)
                }
            }
        """.trimIndent()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(source)
        val registry = mapOf("PrivacyAuditDao" to setOf("insert"))

        val calls = detectDaoWriteCalls(sanitized, registry)
        assertTrue(
            "Expected the local db alias write to be detected: $calls",
            calls.any {
                it.daoInterface == "PrivacyAuditDao" &&
                    it.methodName == "insert" &&
                    it.receiver == "auditDao"
            }
        )
        assertFalse(isClassLevelBarrierProtected(sanitized))
    }

    @Test
    fun `fixture - chained accessor writer without barrier is flagged`() {
        val source = """
            class Purge(private val appDatabase: AppDatabase) {
                suspend fun purge(cutoff: Long): Int = appDatabase.aiArtifactDao().deleteExpired(cutoff)
            }
        """.trimIndent()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(source)
        val registry = mapOf("AiArtifactDao" to setOf("deleteExpired"))

        val calls = detectDaoWriteCalls(sanitized, registry)
        assertTrue(
            "Expected the chained accessor write to be detected: $calls",
            calls.any {
                it.daoInterface == "AiArtifactDao" &&
                    it.methodName == "deleteExpired" &&
                    it.receiver == "appDatabase.aiArtifactDao()"
            }
        )
        assertFalse(isClassLevelBarrierProtected(sanitized))
    }

    @Test
    fun `fixture - barrier-protected aliased recurring DAO writer passes`() {
        val source = """
            class GuardedSubscriptionRepo @Inject constructor(
                private val writeBarrier: DatabaseWriteBarrier,
                private val subscriptionDao: ManualRecurringExpenseDao
            ) {
                suspend fun update(s: ManualRecurringExpense) {
                    writeBarrier.checkWritesAllowed("GuardedSubscriptionRepo.update")
                    subscriptionDao.update(s)
                }
            }
        """.trimIndent()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(source)
        val registry = mapOf("ManualRecurringExpenseDao" to setOf("update", "deleteById", "insert"))

        val calls = detectDaoWriteCalls(sanitized, registry)
        assertTrue(
            "Alias detection must still observe the aliased write (no under-detection): $calls",
            calls.any {
                it.daoInterface == "ManualRecurringExpenseDao" &&
                    it.methodName == "update" &&
                    it.receiver == "subscriptionDao"
            }
        )
        assertTrue(
            "A class declaring writeBarrier: DatabaseWriteBarrier and calling " +
                "checkWritesAllowed must count as protected.",
            isClassLevelBarrierProtected(sanitized)
        )
    }

    @Test
    fun `fixture - protection requires typed declaration or check call - not a comment`() {
        val source = """
            /**
             * This class mentions DatabaseWriteBarrier and checkWritesAllowed in a comment only.
             */
            class CommentOnly(private val expenseDao: ExpenseDao) {
                suspend fun save(e: Expense) = expenseDao.insert(e)
            }
        """.trimIndent()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(source)
        assertFalse(
            "Comment-only mentions must not count as barrier ownership.",
            isClassLevelBarrierProtected(sanitized)
        )
    }

    @Test
    fun `fixture - justified exemption entry passes hygiene`() {
        val exemptions = mapOf(
            "SomeWriter" to "owner=RP-02 issue=U-004 expiry=2999-01-01 barrier pending batch 2"
        )
        val violations = exemptionHygieneViolations(exemptions)
        assertTrue("Expected no hygiene violations: $violations", violations.isEmpty())
    }

    @Test
    fun `fixture - blank reason exemption fails hygiene`() {
        val exemptions = mapOf("SomeWriter" to "   ")
        val violations = exemptionHygieneViolations(exemptions)
        assertTrue(
            "A blank exemption reason must fail the hygiene check.",
            violations.isNotEmpty()
        )
    }

    @Test
    fun `fixture - expired exemption fails hygiene`() {
        val exemptions = mapOf(
            "SomeWriter" to "owner=RP-02 issue=U-004 expiry=2020-01-01 stale entry"
        )
        val violations = exemptionHygieneViolations(exemptions)
        assertTrue(
            "An expired temporary exemption must fail the hygiene check.",
            violations.isNotEmpty()
        )
    }
}
