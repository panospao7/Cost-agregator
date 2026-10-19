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
 * WorkerExecutionGuard entry/checkpoint usage is NOT accepted as ownership
 * evidence by default: RP-02 requires the check at the writer, not only at a
 * worker entry wrapper (see the DataRetentionWriter row of the RP-02 plan).
 *
 * ## COVERED_BY_CALLER (owner-approved narrow exception, G3-S3)
 *
 * A class may instead be listed in [COVERED_BY_CALLER] (HARD CAP of
 * [COVERED_BY_CALLER_CAP] entries, no overlap with [EXEMPT_CLASSES]) with a
 * mechanism, a non-blank justification and existing evidence files. The class
 * is then skipped by the main scan ONLY after its mechanism is mechanically
 * proven by its own test:
 *  - `WORKER_CHECKPOINT`: every detected DAO write is preceded, in the same
 *    function and within [CHECKPOINT_WINDOW_CHARS] characters of sanitized
 *    source, by a `.checkpoint(` call; the evidence files must implement
 *    `checkpoint` by calling `checkWritesAllowed` (WorkerExecutionGuard binds
 *    `WorkerRunContext` to it; the lease checkpoint does the same).
 *  - `SOLE_CALLER_GUARDED`: no production file outside the evidence files
 *    references the class, and the first evidence file (the guarded caller)
 *    calls `checkWritesAllowed` shortly before every use of it.
 * Comment/string-only evidence never counts (sanitized text).
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
            "MaintenanceOperationRunner" to "maintenance mode itself — enters/drains, does not call DAO writes"
        )

        // ── COVERED_BY_CALLER (owner-approved, G3-S3; HARD CAP) ─────────────
        const val MECH_WORKER_CHECKPOINT = "WORKER_CHECKPOINT"
        const val MECH_SOLE_CALLER_GUARDED = "SOLE_CALLER_GUARDED"
        val KNOWN_MECHANISMS: Set<String> = setOf(MECH_WORKER_CHECKPOINT, MECH_SOLE_CALLER_GUARDED)

        /** Hard cap on [COVERED_BY_CALLER]; raising it requires an owner decision. */
        const val COVERED_BY_CALLER_CAP = 3

        /** Max sanitized-source distance from a `.checkpoint(` call to the DAO write it covers. */
        const val CHECKPOINT_WINDOW_CHARS = 1500

        /** Max sanitized-source distance from `checkWritesAllowed(` to a guarded use of the class. */
        const val SOLE_CALLER_CHECK_WINDOW_CHARS = 300

        /**
         * @property mechanism one of [KNOWN_MECHANISMS]
         * @property justification non-blank reason this class needs no own barrier
         * @property evidenceFiles paths relative to the production source root. For
         *   SOLE_CALLER_GUARDED the FIRST file is the guarded caller and ALL files are the
         *   only production files allowed to reference the class. For WORKER_CHECKPOINT
         *   they are the files implementing the barrier-calling checkpoint.
         */
        data class CoveredByCaller(
            val mechanism: String,
            val justification: String,
            val evidenceFiles: List<String>
        )

        private const val WORKERS_DIR = "com/yourname/expensetracker/domain/workers"
        private val CHECKPOINT_EVIDENCE = listOf(
            "$WORKERS_DIR/WorkerExecutionGuard.kt",
            "$WORKERS_DIR/WorkerLeaseRegistryImpl.kt"
        )

        val COVERED_BY_CALLER: Map<String, CoveredByCaller> = mapOf(
            "WorkerRunLogger" to CoveredByCaller(
                mechanism = MECH_SOLE_CALLER_GUARDED,
                justification = "run-log writes are reachable only through WorkerExecutionGuard: the start write is " +
                    "preceded by checkWritesAllowed; terminal writes run under the worker lease before lease.close(), " +
                    "and every maintenance drain fails rather than proceeding while a lease is held; " +
                    "DiagnosticsModule only binds it",
                evidenceFiles = listOf(
                    "$WORKERS_DIR/WorkerExecutionGuard.kt",
                    "com/yourname/expensetracker/di/DiagnosticsModule.kt"
                )
            ),
            "WarrantyExpirationWorker" to CoveredByCaller(
                mechanism = MECH_WORKER_CHECKPOINT,
                justification = "every delivery-state DAO write is preceded by ctx.checkpoint, which calls " +
                    "writeBarrier.checkWritesAllowed via WorkerExecutionGuard.checkpoint",
                evidenceFiles = CHECKPOINT_EVIDENCE
            ),
            "NotificationIntakeWorker" to CoveredByCaller(
                mechanism = MECH_WORKER_CHECKPOINT,
                justification = "every intake-state DAO write is preceded by ctx.checkpoint, which calls " +
                    "writeBarrier.checkWritesAllowed via WorkerExecutionGuard.checkpoint",
                evidenceFiles = CHECKPOINT_EVIDENCE
            )
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
        val daoDir = File(sourceRoot, "com/yourname/expensetracker/data/database/dao")
        // Fail closed: a missing DAO directory must never degrade to "no DAOs registered".
        assertTrue(
            "DAO directory missing: ${daoDir.path} — update the guard if it was moved",
            daoDir.isDirectory
        )
        daoDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.name.contains("RestrictedExpenseDaoMutation") }
            .toList()
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
        // Fail closed: an unreadable DAO file must fail the guard, never be dropped from the registry.
        val text = try {
            file.readText()
        } catch (e: Exception) {
            throw AssertionError("DAO file unreadable: ${file.path} (${e.javaClass.simpleName})", e)
        }

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
                // Result-preserving pre-filter: every regex from buildCallerRegexes embeds the
                // literal (Regex.escape'd, case-sensitive) method name, so a regex can only match
                // if the content contains the plain method name. Skipping files without it
                // therefore never changes which pairs are recorded.
                if (!sanitizedContent.contains(method)) continue
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

    // ── COVERED_BY_CALLER mechanisms (G3-S3) ────────────────────────────────

    /** Sanitized production sources keyed by source-root-relative path (forward slashes). */
    private val sanitizedMainSources: Map<String, String> by lazy {
        allKotlinFiles().associate { file ->
            file.relativeTo(sourceRoot).invariantSeparatorsPath to
                SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
        }
    }

    /** Mechanism violations per COVERED_BY_CALLER class (empty list = mechanism proven). */
    private val coveredByCallerMechanismResults: Map<String, List<String>> by lazy {
        val registry = buildDaoRegistry()
        COVERED_BY_CALLER.mapValues { (className, entry) ->
            coveredByCallerMechanismViolations(className, entry, registry, sanitizedMainSources)
        }
    }

    private fun baseNameOf(relativePath: String): String =
        relativePath.substringAfterLast('/').removeSuffix(".kt")

    private fun coveredByCallerHygieneViolations(
        covered: Map<String, CoveredByCaller>,
        exemptClassNames: Set<String>,
        evidenceExists: (String) -> Boolean,
        cap: Int = COVERED_BY_CALLER_CAP
    ): List<String> {
        val violations = mutableListOf<String>()
        if (covered.size > cap) {
            violations.add("COVERED_BY_CALLER has ${covered.size} entries; the hard cap is $cap")
        }
        for ((className, entry) in covered) {
            if (className in exemptClassNames) {
                violations.add("$className: listed in both COVERED_BY_CALLER and the permanent exemption list")
            }
            when {
                entry.mechanism.isBlank() -> violations.add("$className: blank mechanism")
                entry.mechanism !in KNOWN_MECHANISMS ->
                    violations.add("$className: unknown mechanism '${entry.mechanism}'")
            }
            if (entry.justification.isBlank()) violations.add("$className: blank justification")
            if (entry.evidenceFiles.isEmpty()) violations.add("$className: no evidence files")
            for (evidence in entry.evidenceFiles) {
                if (!evidenceExists(evidence)) violations.add("$className: evidence file does not exist: $evidence")
            }
        }
        return violations
    }

    private fun funDeclarationStarts(sanitized: String): List<Int> =
        Regex("""\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_][\w.]*\.)?[A-Za-z_]\w*\s*\(""")
            .findAll(sanitized).map { it.range.first }.toList()

    /** Start offsets of every textual occurrence of the detected [call] in [sanitized]. */
    private fun writeCallSites(sanitized: String, call: DaoWriteCall): List<Int> {
        val method = Regex.escape(call.methodName)
        val pattern = when {
            call.receiver == "regex" -> Regex("""\.\s*${method}\s*\(""")
            call.receiver.contains('(') -> {
                val parts = call.receiver.removeSuffix("()").split('.', limit = 2)
                Regex(
                    """\b${Regex.escape(parts[0])}\s*\.\s*${Regex.escape(parts.getOrElse(1) { "" })}""" +
                        """\s*\(\s*\)\s*\.\s*${method}\s*\("""
                )
            }
            else -> Regex("""\b${Regex.escape(call.receiver)}\s*\.\s*${method}\s*\(""")
        }
        return pattern.findAll(sanitized).map { it.range.first }.toList()
    }

    /**
     * WORKER_CHECKPOINT: every detected DAO write must be preceded, inside the same
     * function and within [windowChars] sanitized characters, by a `.checkpoint(` call.
     */
    private fun workerCheckpointViolations(
        sanitized: String,
        registry: Map<String, Set<String>>,
        windowChars: Int = CHECKPOINT_WINDOW_CHARS
    ): List<String> {
        val calls = detectDaoWriteCalls(sanitized, registry)
        if (calls.isEmpty()) {
            return listOf("no DAO write calls detected: a WORKER_CHECKPOINT entry must cover at least one write")
        }
        val funStarts = funDeclarationStarts(sanitized)
        val checkpointEnds = Regex("""\.\s*checkpoint\s*\(""").findAll(sanitized).map { it.range.last }.toList()
        val violations = mutableListOf<String>()
        val seenSites = mutableSetOf<Int>()
        for (call in calls) {
            val sites = writeCallSites(sanitized, call)
            if (sites.isEmpty()) {
                violations.add("${call.receiver}.${call.methodName}(): detected write site could not be located")
            }
            for (site in sites) {
                if (!seenSites.add(site)) continue
                val line = sanitized.substring(0, site).count { it == '\n' } + 1
                val funStart = funStarts.lastOrNull { it < site } ?: 0
                val nearest = checkpointEnds.lastOrNull { it in funStart until site }
                when {
                    nearest == null -> violations.add(
                        "line $line: ${call.receiver}.${call.methodName}() has no preceding " +
                            "checkpoint( call in the same function"
                    )
                    site - nearest > windowChars -> violations.add(
                        "line $line: ${call.receiver}.${call.methodName}() is ${site - nearest} chars after " +
                            "the nearest checkpoint( (window $windowChars)"
                    )
                }
            }
        }
        return violations
    }

    /**
     * The evidence files of a WORKER_CHECKPOINT entry must implement `checkpoint` by calling
     * `checkWritesAllowed`, and at least one must bind WorkerRunContext to that checkpoint.
     */
    private fun checkpointImplementationViolations(evidence: Map<String, String?>): List<String> {
        if (evidence.isEmpty()) return listOf("no checkpoint evidence files")
        val violations = mutableListOf<String>()
        var bound = false
        for ((path, text) in evidence) {
            if (text == null) {
                violations.add("$path: evidence file missing")
                continue
            }
            val start = Regex("""\bfun\s+checkpoint\s*\(""").find(text)
            if (start == null) {
                violations.add("$path: no 'fun checkpoint(' implementation")
                continue
            }
            val next = Regex("""\bfun\s+\w""").find(text, start.range.last)
            val slice = text.substring(start.range.first, next?.range?.first ?: text.length)
            if (!Regex("""\bcheckWritesAllowed\s*\(""").containsMatchIn(slice)) {
                violations.add("$path: checkpoint implementation does not call checkWritesAllowed")
            }
            if (Regex("""WorkerRunContext\s*\(\s*checkpointDelegate\s*=\s*::checkpoint\s*\)""").containsMatchIn(text)) {
                bound = true
            }
        }
        if (!bound) violations.add("no evidence file binds WorkerRunContext(checkpointDelegate = ::checkpoint)")
        return violations
    }

    /**
     * SOLE_CALLER_GUARDED: no production file outside [evidenceFiles] (and the class's own
     * file) may reference [className], and the first evidence file (the guarded caller) must
     * call `checkWritesAllowed` shortly before every use of the class instance.
     */
    private fun soleCallerGuardedViolations(
        className: String,
        evidenceFiles: List<String>,
        sanitizedSources: Map<String, String>
    ): List<String> {
        val violations = mutableListOf<String>()
        val allowed = evidenceFiles.toSet()
        // G3 review G1: also match derived names (e.g. the injectable `<className>Impl`), so a
        // rogue file cannot bypass the scan by depending on the implementation class.
        val reference = Regex("""\b${Regex.escape(className)}\w*\b""")
        for ((path, text) in sanitizedSources) {
            if (baseNameOf(path) == className || path in allowed) continue
            if (reference.containsMatchIn(text)) {
                violations.add("$path references $className but is not an allowed evidence file")
            }
        }
        val guardedCaller = evidenceFiles.firstOrNull() ?: return violations + "no evidence files"
        val callerText = sanitizedSources[guardedCaller]
            ?: return violations + "guarded caller evidence file missing: $guardedCaller"
        val instance = className.replaceFirstChar { it.lowercaseChar() }
        val uses = Regex("""\b${Regex.escape(instance)}\s*\.\s*\w+\s*\(""").findAll(callerText).toList()
        if (uses.isEmpty()) violations.add("$guardedCaller never calls $instance.<method>(")
        val check = Regex("""\bcheckWritesAllowed\s*\(""")
        for (use in uses) {
            val from = (use.range.first - SOLE_CALLER_CHECK_WINDOW_CHARS).coerceAtLeast(0)
            if (!check.containsMatchIn(callerText.substring(from, use.range.first))) {
                val line = callerText.substring(0, use.range.first).count { it == '\n' } + 1
                violations.add(
                    "$guardedCaller line $line: $instance use has no checkWritesAllowed( within " +
                        "$SOLE_CALLER_CHECK_WINDOW_CHARS chars before it"
                )
            }
        }
        return violations
    }

    private fun coveredByCallerMechanismViolations(
        className: String,
        entry: CoveredByCaller,
        registry: Map<String, Set<String>>,
        sanitizedSources: Map<String, String>
    ): List<String> = when (entry.mechanism) {
        MECH_WORKER_CHECKPOINT -> {
            val own = sanitizedSources.entries.filter { baseNameOf(it.key) == className }
            if (own.size != 1) {
                listOf("expected exactly one source file for $className, found ${own.size}")
            } else {
                workerCheckpointViolations(own.single().value, registry) +
                    checkpointImplementationViolations(entry.evidenceFiles.associateWith { sanitizedSources[it] })
            }
        }
        MECH_SOLE_CALLER_GUARDED ->
            soleCallerGuardedViolations(className, entry.evidenceFiles, sanitizedSources)
        else -> listOf("unknown mechanism '${entry.mechanism}'")
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

        // Reuse the lazily built sanitized sources: sanitizedMainSources is built from the very
        // same allKotlinFiles() walk with the same sanitizer, so the text per file is identical
        // (result-preserving; avoids re-reading and re-sanitizing every file). If building it
        // fails (e.g. an unreadable file) fall back to the per-file path below so the original
        // fail-closed "scanned file unreadable" assertion is still what reports it.
        val cachedSanitized: Map<String, String>? = try {
            sanitizedMainSources
        } catch (e: Exception) {
            null
        }

        for (file in allFiles) {
            val callerClassName = file.nameWithoutExtension

            // Skip DAO files themselves and the annotation definition.
            if (callerClassName in daoFileNames) continue
            if (callerClassName == "RestrictedExpenseDaoMutation") continue

            // Fail closed: an unreadable scanned file must fail the guard, never be skipped.
            val sanitized = try {
                cachedSanitized?.get(file.relativeTo(sourceRoot).invariantSeparatorsPath)
                    ?: SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
            } catch (e: Exception) {
                throw AssertionError("scanned file unreadable: ${file.path} (${e.javaClass.simpleName})", e)
            }

            if (callerClassName in EXEMPT_CLASSES.keys) continue
            // COVERED_BY_CALLER is honoured only while its mechanism check passes; a failing
            // mechanism falls through to the normal scan (and fails its own dedicated test).
            if (callerClassName in COVERED_BY_CALLER.keys &&
                coveredByCallerMechanismResults[callerClassName].orEmpty().isEmpty()
            ) continue
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

        // The alias pattern MUST be detected (no under-detection). RP-04 A1 routed
        // rule mutations through RecurringRuleLifecycleCoordinator, so the only
        // remaining direct ManualRecurringExpenseDao write is the display-only
        // scoped op `updateSubscriptionCategory` (plan step 7 carve-out).
        val calls = detectDaoWriteCalls(sanitized, buildDaoRegistry())
        assertTrue(
            "Alias detection must observe SubscriptionManagementRepository's " +
                "subscriptionDao (ManualRecurringExpenseDao) write calls.",
            calls.any {
                it.receiver == "subscriptionDao" &&
                    it.daoInterface == "ManualRecurringExpenseDao" &&
                    it.methodName == "updateSubscriptionCategory"
            }
        )
        assertFalse(
            "SubscriptionManagementRepository must not retain direct rule mutator calls " +
                "(update/deleteById) after RP-04 A1 coordinator routing.",
            calls.any {
                it.daoInterface == "ManualRecurringExpenseDao" &&
                    it.methodName in setOf("update", "deleteById")
            }
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

    // ── COVERED_BY_CALLER: real-source tests (G3-S3) ────────────────────────

    @Test
    fun `covered-by-caller map is capped disjoint from exemptions and fully described`() {
        val violations = coveredByCallerHygieneViolations(
            covered = COVERED_BY_CALLER,
            exemptClassNames = EXEMPT_CLASSES.keys,
            evidenceExists = { File(sourceRoot, it).isFile }
        )
        assertTrue(
            "COVERED_BY_CALLER hygiene violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
        assertTrue(
            "COVERED_BY_CALLER hard cap is $COVERED_BY_CALLER_CAP, found ${COVERED_BY_CALLER.size}.",
            COVERED_BY_CALLER.size <= COVERED_BY_CALLER_CAP
        )
    }

    @Test
    fun `covered-by-caller mechanisms hold for every entry`() {
        val failures = coveredByCallerMechanismResults.filterValues { it.isNotEmpty() }
        assertTrue(
            "COVERED_BY_CALLER mechanism checks failed:\n" +
                failures.entries.joinToString("\n") { (cls, v) -> "$cls:\n  " + v.joinToString("\n  ") },
            failures.isEmpty()
        )
        assertEquals(COVERED_BY_CALLER.keys, coveredByCallerMechanismResults.keys)
    }

    @Test
    fun `covered-by-caller classes still perform detected DAO writes - entries are not vacuous`() {
        val registry = buildDaoRegistry()
        for ((className, _) in COVERED_BY_CALLER) {
            val own = sanitizedMainSources.entries.filter { baseNameOf(it.key) == className }
            assertEquals("Exactly one source file expected for $className", 1, own.size)
            assertTrue(
                "$className is listed in COVERED_BY_CALLER but has no detected DAO writes; remove the entry.",
                detectDaoWriteCalls(own.single().value, registry).isNotEmpty()
            )
        }
    }

    // ── COVERED_BY_CALLER: fixtures ─────────────────────────────────────────

    private val fixtureRegistry = mapOf("NotificationIntakeDao" to setOf("markTerminal", "purgeAllPayload"))

    @Test
    fun `fixture - worker checkpoint before every write passes`() {
        val source = """
            class FxWorker(private val intakeDao: NotificationIntakeDao) {
                suspend fun run(ctx: WorkerRunContext) {
                    ctx.checkpoint("terminal")
                    intakeDao.markTerminal(1L)
                    ctx.checkpoint("purge")
                    intakeDao.purgeAllPayload(1L)
                }
            }
        """.trimIndent()
        val violations = workerCheckpointViolations(
            SourceTextSanitizer.stripCommentsAndStringBodies(source), fixtureRegistry
        )
        assertTrue("Expected no violations: $violations", violations.isEmpty())
    }

    @Test
    fun `fixture - worker write without preceding checkpoint fails`() {
        val source = """
            class FxWorker(private val intakeDao: NotificationIntakeDao) {
                suspend fun run(ctx: WorkerRunContext) {
                    ctx.checkpoint("terminal")
                    intakeDao.markTerminal(1L)
                    intakeDao.purgeAllPayload(1L)
                }
            }
        """.trimIndent()
        // purgeAllPayload is still within the window of the first checkpoint, so tighten the
        // window to prove the distance rule, and separately prove the missing-checkpoint rule.
        val tight = workerCheckpointViolations(
            SourceTextSanitizer.stripCommentsAndStringBodies(source), fixtureRegistry, windowChars = 10
        )
        assertTrue("Write beyond the window must fail: $tight", tight.any { it.contains("purgeAllPayload") })

        val noCheckpoint = """
            class FxWorker(private val intakeDao: NotificationIntakeDao) {
                suspend fun run() {
                    intakeDao.markTerminal(1L)
                }
            }
        """.trimIndent()
        val violations = workerCheckpointViolations(
            SourceTextSanitizer.stripCommentsAndStringBodies(noCheckpoint), fixtureRegistry
        )
        assertTrue("A write with no checkpoint must fail: $violations", violations.isNotEmpty())
    }

    @Test
    fun `fixture - checkpoint in another function or after the write does not cover it`() {
        val otherFunction = """
            class FxWorker(private val intakeDao: NotificationIntakeDao) {
                suspend fun first(ctx: WorkerRunContext) {
                    ctx.checkpoint("a")
                }
                suspend fun second() {
                    intakeDao.markTerminal(1L)
                }
            }
        """.trimIndent()
        assertTrue(
            "A checkpoint in a different function must not cover the write.",
            workerCheckpointViolations(
                SourceTextSanitizer.stripCommentsAndStringBodies(otherFunction), fixtureRegistry
            ).isNotEmpty()
        )

        val checkpointAfter = """
            class FxWorker(private val intakeDao: NotificationIntakeDao) {
                suspend fun run(ctx: WorkerRunContext) {
                    intakeDao.markTerminal(1L)
                    ctx.checkpoint("late")
                }
            }
        """.trimIndent()
        assertTrue(
            "A checkpoint AFTER the write must not cover it.",
            workerCheckpointViolations(
                SourceTextSanitizer.stripCommentsAndStringBodies(checkpointAfter), fixtureRegistry
            ).isNotEmpty()
        )

        val commentOnly = """
            class FxWorker(private val intakeDao: NotificationIntakeDao) {
                suspend fun run() {
                    // ctx.checkpoint("not real")
                    intakeDao.markTerminal(1L)
                }
            }
        """.trimIndent()
        assertTrue(
            "A checkpoint mentioned only in a comment must not count.",
            workerCheckpointViolations(
                SourceTextSanitizer.stripCommentsAndStringBodies(commentOnly), fixtureRegistry
            ).isNotEmpty()
        )
    }

    @Test
    fun `fixture - checkpoint implementation must call checkWritesAllowed`() {
        val good = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            class G {
                val ctx = WorkerRunContext(checkpointDelegate = ::checkpoint)
                suspend fun checkpoint(operation: String) {
                    writeBarrier.checkWritesAllowed(operation)
                }
                fun other() {}
            }
            """.trimIndent()
        )
        assertTrue(checkpointImplementationViolations(mapOf("G.kt" to good)).isEmpty())

        val noBarrier = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            class G {
                val ctx = WorkerRunContext(checkpointDelegate = ::checkpoint)
                suspend fun checkpoint(operation: String) {
                    yield()
                }
                fun other() { writeBarrier.checkWritesAllowed("x") }
            }
            """.trimIndent()
        )
        assertTrue(
            "A checkpoint that never calls checkWritesAllowed must fail.",
            checkpointImplementationViolations(mapOf("G.kt" to noBarrier)).isNotEmpty()
        )
        assertTrue(
            "A missing evidence file must fail.",
            checkpointImplementationViolations(mapOf("Missing.kt" to null)).isNotEmpty()
        )
    }

    private fun soleCallerSources(guardBody: String, extra: Map<String, String> = emptyMap()): Map<String, String> =
        (mapOf(
            "pkg/FxLogger.kt" to "interface FxLogger { suspend fun start() }",
            "pkg/Guard.kt" to guardBody,
            "pkg/Module.kt" to "abstract class Module { abstract fun bind(impl: FxLoggerImpl): FxLogger }"
        ) + extra).mapValues { SourceTextSanitizer.stripCommentsAndStringBodies(it.value) }

    private val soleCallerEvidence = listOf("pkg/Guard.kt", "pkg/Module.kt")

    private val guardedCallerSource = """
        class Guard(private val fxLogger: FxLogger) {
            suspend fun go() {
                writeBarrier.checkWritesAllowed("FxLogger.start")
                fxLogger.start()
            }
        }
    """.trimIndent()

    @Test
    fun `fixture - sole caller guarded passes when only evidence files reference the class`() {
        val violations = soleCallerGuardedViolations("FxLogger", soleCallerEvidence, soleCallerSources(guardedCallerSource))
        assertTrue("Expected no violations: $violations", violations.isEmpty())
    }

    @Test
    fun `fixture - sole caller guarded fails on an extra production reference`() {
        val sources = soleCallerSources(
            guardedCallerSource,
            extra = mapOf("pkg/Rogue.kt" to "class Rogue(private val logger: FxLogger) { suspend fun x() = logger.start() }")
        )
        val violations = soleCallerGuardedViolations("FxLogger", soleCallerEvidence, sources)
        assertTrue(
            "A production file outside the evidence list referencing the class must fail: $violations",
            violations.any { it.contains("pkg/Rogue.kt") }
        )
    }

    @Test
    fun `fixture - sole caller guarded fails on an extra production reference to the Impl class`() {
        val sources = soleCallerSources(
            guardedCallerSource,
            extra = mapOf("pkg/RogueImpl.kt" to "class RogueImpl(private val logger: FxLoggerImpl) { suspend fun x() = logger.start() }")
        )
        val violations = soleCallerGuardedViolations("FxLogger", soleCallerEvidence, sources)
        assertTrue(
            "A production file depending on the implementation class must fail too: $violations",
            violations.any { it.contains("pkg/RogueImpl.kt") }
        )
    }

    @Test
    fun `fixture - sole caller guarded fails when the guarded caller has no barrier check`() {
        val unguarded = """
            class Guard(private val fxLogger: FxLogger) {
                suspend fun go() {
                    fxLogger.start()
                }
            }
        """.trimIndent()
        val violations = soleCallerGuardedViolations("FxLogger", soleCallerEvidence, soleCallerSources(unguarded))
        assertTrue("A guarded caller without checkWritesAllowed must fail: $violations", violations.isNotEmpty())

        val missingCaller = soleCallerGuardedViolations(
            "FxLogger", listOf("pkg/DoesNotExist.kt"), soleCallerSources(guardedCallerSource)
        )
        assertTrue("A missing guarded-caller evidence file must fail: $missingCaller", missingCaller.isNotEmpty())
    }

    @Test
    fun `fixture - covered-by-caller over the hard cap fails hygiene`() {
        val entry = CoveredByCaller(MECH_WORKER_CHECKPOINT, "justified", listOf("a.kt"))
        val covered = (1..4).associate { "Worker$it" to entry }
        val violations = coveredByCallerHygieneViolations(covered, emptySet(), { true })
        assertTrue("4 entries must exceed the cap of 3: $violations", violations.any { it.contains("hard cap") })
    }

    @Test
    fun `fixture - covered-by-caller blank justification fails hygiene`() {
        val covered = mapOf("W" to CoveredByCaller(MECH_WORKER_CHECKPOINT, "   ", listOf("a.kt")))
        val violations = coveredByCallerHygieneViolations(covered, emptySet(), { true })
        assertTrue("Blank justification must fail: $violations", violations.any { it.contains("blank justification") })
    }

    @Test
    fun `fixture - covered-by-caller missing evidence file fails hygiene`() {
        val covered = mapOf("W" to CoveredByCaller(MECH_WORKER_CHECKPOINT, "justified", listOf("missing.kt")))
        val violations = coveredByCallerHygieneViolations(covered, emptySet(), { false })
        assertTrue("A missing evidence file must fail: $violations", violations.any { it.contains("does not exist") })
    }

    @Test
    fun `fixture - covered-by-caller unknown or blank mechanism fails hygiene`() {
        val unknown = mapOf("W" to CoveredByCaller("TRUST_ME", "justified", listOf("a.kt")))
        assertTrue(coveredByCallerHygieneViolations(unknown, emptySet(), { true }).any { it.contains("unknown mechanism") })
        val blank = mapOf("W" to CoveredByCaller(" ", "justified", listOf("a.kt")))
        assertTrue(coveredByCallerHygieneViolations(blank, emptySet(), { true }).any { it.contains("blank mechanism") })
        val mechanism = coveredByCallerMechanismViolations(
            "W", unknown.getValue("W"), emptyMap(), emptyMap()
        )
        assertTrue("An unknown mechanism must fail the mechanism check: $mechanism", mechanism.isNotEmpty())
    }

    @Test
    fun `fixture - covered-by-caller overlapping the permanent exemption list fails hygiene`() {
        val covered = mapOf("W" to CoveredByCaller(MECH_WORKER_CHECKPOINT, "justified", listOf("a.kt")))
        val violations = coveredByCallerHygieneViolations(covered, setOf("W"), { true })
        assertTrue("Overlap with EXEMPT_CLASSES must fail: $violations", violations.any { it.contains("both") })
    }
}
