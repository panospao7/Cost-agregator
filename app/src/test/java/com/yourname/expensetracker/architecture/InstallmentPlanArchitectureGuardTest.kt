package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.test.assertFailsWith

/**
 * Architecture fences for the loan-installments feature (workflow S1; domain spec INV-23, INV-24,
 * INV-25 and section 5.2 "Forbidden" list).
 *
 * The fences go up BEFORE any plan code exists, so every live-scan test passes vacuously today.
 * They are made non-vacuous in two ways:
 *  - the scan is fail-closed (source root must exist and more than [MIN_SCANNED_FILES] Kotlin files
 *    must be seen), so a broken path can never pass silently;
 *  - each rule has a negative self-check that runs the SAME matcher on planted in-memory snippets and
 *    proves violations are flagged while comment-only / string-only / exempt-path mentions are not.
 *
 * Matching runs over sanitized text ([SourceTextSanitizer]) so a comment or string mention can neither
 * fake nor hide a violation. Raw SQL rules need string bodies, so they use a comment-only stripper.
 *
 * Failure messages carry the relative file path, rule id, line number and the matched line only
 * (bounded to [MAX_SNIPPET] characters); never file contents.
 *
 * Plan-code packages (main source set):
 *  - domain/installment/, service/installment/ and ui/screens/installments/ (recursively)
 * Single writer: domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt (path is also
 * pinned by ManualEntryCurrencySetterAllowListGuardTest; renaming needs owner approval).
 */
class InstallmentPlanArchitectureGuardTest {

    private data class Hit(val rule: String, val line: Int, val snippet: String)

    private class ScannedFile(val rel: String, val raw: String)

    // ---------------------------------------------------------------- source tree (fail closed)

    private val sourceRoot: File by lazy {
        FailClosedScan.requireRoot(
            listOf(
                File("src/main/java"),
                File("app/src/main/java"),
                File(System.getProperty("user.dir") ?: ".", "src/main/java"),
                File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
            ),
            "production source root"
        )
    }

    private val scannedFiles: List<ScannedFile> by lazy {
        val files = mutableListOf<ScannedFile>()
        FailClosedScan.walkSourceFiles(sourceRoot) { file, text ->
            files += ScannedFile(FailClosedScan.relativePath(sourceRoot, file), text)
        }
        files
    }

    private fun requireScan(): List<ScannedFile> {
        assertTrue("source root must exist: ${sourceRoot.path}", sourceRoot.isDirectory)
        val files = scannedFiles
        assertTrue(
            "installment architecture guard scanned only ${files.size} Kotlin files " +
                "(need more than $MIN_SCANNED_FILES) - the scan root is wrong or truncated, guard would be vacuous",
            files.size > MIN_SCANNED_FILES
        )
        return files
    }

    private fun assertNoViolations(title: String, matcher: (String, String) -> List<Hit>) {
        val errors = requireScan().flatMap { file ->
            matcher(file.rel, file.raw).map { "${file.rel} [${it.rule}] line ${it.line}: ${it.snippet}" }
        }
        val shown = errors.take(MAX_REPORTED).joinToString("\n")
        val more = if (errors.size > MAX_REPORTED) "\n... and ${errors.size - MAX_REPORTED} more" else ""
        assertTrue("$title - ${errors.size} violation(s):\n$shown$more", errors.isEmpty())
    }

    // ---------------------------------------------------------------- shared path model

    private fun isPlanCode(rel: String): Boolean = PLAN_CODE_PREFIXES.any { rel.startsWith(it) }

    // ---------------------------------------------------------------- text helpers

    /** Comments and string bodies blanked (offsets and newlines preserved). */
    private fun code(raw: String): String = SourceTextSanitizer.stripCommentsAndStringBodies(raw)

    /**
     * Comments blanked, string literal bodies KEPT (offsets and newlines preserved). Needed for raw SQL
     * rules, where table names live inside string literals. Understands nested block comments, regular
     * and raw strings and char literals, so `//` inside a string is not treated as a comment.
     */
    private fun stripCommentsKeepStrings(text: String): String {
        val out = StringBuilder(text.length)
        val n = text.length
        var i = 0
        fun blank(from: Int, to: Int) {
            for (k in from until to) out.append(if (text[k] == '\n') '\n' else ' ')
        }
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    val j = text.indexOf('\n', i).let { if (it < 0) n else it }
                    blank(i, j)
                    i = j
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    var depth = 1
                    var j = i + 2
                    while (j < n && depth > 0) {
                        when {
                            text.startsWith("/*", j) -> { depth++; j += 2 }
                            text.startsWith("*/", j) -> { depth--; j += 2 }
                            else -> j++
                        }
                    }
                    blank(i, j.coerceAtMost(n))
                    i = j.coerceAtMost(n)
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val close = text.indexOf("\"\"\"", i + 3)
                    val j = if (close < 0) n else close + 3
                    out.append(text, i, j)
                    i = j
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n) {
                        val cj = text[j]
                        if (cj == '\\') { j += 2; continue }
                        if (cj == c) { j++; break }
                        j++
                    }
                    val end = j.coerceAtMost(n)
                    out.append(text, i, end)
                    i = end
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun lineHits(rule: String, regex: Regex, scanText: String, accept: (MatchResult) -> Boolean = { true }): List<Hit> =
        regex.findAll(scanText).filter(accept).map { match ->
            val start = match.range.first
            val line = scanText.substring(0, start).count { it == '\n' } + 1
            val lineStart = scanText.lastIndexOf('\n', start - 1) + 1
            val lineEnd = scanText.indexOf('\n', start).let { if (it < 0) scanText.length else it }
            Hit(rule, line, scanText.substring(lineStart, lineEnd).trim().take(MAX_SNIPPET))
        }.toList()

    // ---------------------------------------------------------------- matchers

    /**
     * INV-25 / check 1. Plan DAO write calls (every verb prefix in [MUTATION_VERB_PREFIXES], which mirrors
     * MUTATION_VERBS in scripts/verify_db_access_boundaries.py, so claim/release link owners are covered) through ANY
     * receiver typed or named as an Installment*Dao, the DAO accessor chain, or a local alias; plus a plan
     * DAO declared outside data/database/dao; plus raw SQL writes to installment_* tables. Exempt: the plan
     * coordinator (exact relative path, never basename) and DAO declarations under data/database/dao.
     */
    private fun planDaoWrites(rel: String, raw: String): List<Hit> {
        if (rel == COORDINATOR_PATH || rel.startsWith(DAO_PACKAGE_PREFIX)) return emptyList()
        val code = code(raw)
        val receivers = mutableListOf("""\w*installment\w*Dao(?:\s*\(\s*\))?""")
        Regex("""\b([A-Za-z_]\w*)\s*:\s*(?:\w+\.)*Installment\w*Dao\b""")
            .findAll(code).forEach { receivers += Regex.escape(it.groupValues[1]) }
        Regex("""\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*[\w.?]*?installment\w*Dao\s*\(\s*\)""", RegexOption.IGNORE_CASE)
            .findAll(code).forEach { receivers += Regex.escape(it.groupValues[1]) }
        val call = Regex(
            """(?<!\w)(?:${receivers.joinToString("|")})\s*\??\.\s*(?:${MUTATION_VERB_PREFIXES.joinToString("|")})\w*\s*\(""",
            RegexOption.IGNORE_CASE
        )
        val hits = mutableListOf<Hit>()
        hits += lineHits("PLAN_DAO_WRITE_CALL", call, code)
        hits += lineHits(
            "PLAN_DAO_DECLARED_OUTSIDE_DAO_PACKAGE",
            Regex("""\b(?:interface|(?:abstract\s+)?class)\s+Installment\w*Dao\b"""),
            code
        )
        hits += lineHits(
            "PLAN_TABLE_RAW_SQL_WRITE",
            Regex(
                """\b(?:INSERT(?:\s+OR\s+\w+)?\s+INTO|REPLACE\s+INTO|UPDATE(?:\s+OR\s+\w+)?|DELETE\s+FROM)\s+["'`\[]?installment_\w+""",
                RegexOption.IGNORE_CASE
            ),
            stripCommentsKeepStrings(raw)
        )
        return hits
    }

    /** INV-24 / check 2. Any ExpenseDao (or restricted-mutation gate) reference in plan-feature code. */
    private fun expenseDaoReferences(rel: String, raw: String): List<Hit> {
        if (!isPlanCode(rel)) return emptyList()
        // Other DAOs whose names merely end in ExpenseDao are not this rule's concern. Check 4 covers the
        // recurring/planned stores (RecurringOccurrenceDao, ManualRecurringExpenseDao, RecurringExpenseDao,
        // PlannedExpenseDao); GroupExpenseDao is intentionally outside both rules.
        val reference = Regex(
            """(?<!Recurring)(?<!Group)(?<!Planned)(?<!Manual)ExpenseDao""",
            RegexOption.IGNORE_CASE
        )
        return lineHits("EXPENSE_DAO_REFERENCE_IN_PLAN_CODE", reference, code(raw))
    }

    /** INV-23 / check 3. Any deleteExpense-family call or callable reference in plan-feature code. */
    private fun deleteExpenseCalls(rel: String, raw: String): List<Hit> {
        if (!isPlanCode(rel)) return emptyList()
        val code = code(raw)
        return lineHits(
            "DELETE_EXPENSE_CALL_IN_PLAN_CODE",
            Regex("""\w*deleteExpense\w*\s*\(""", RegexOption.IGNORE_CASE),
            code
        ) + lineHits(
            "DELETE_EXPENSE_CALL_IN_PLAN_CODE",
            Regex("""::\s*\w*deleteExpense\w*""", RegexOption.IGNORE_CASE),
            code
        )
    }

    /**
     * INV-25 / check 4. Plan code must not reference the recurring/planned stores: their DAOs (code) or
     * their table names (raw SQL). Installment schedules never go into recurring_occurrences,
     * manual_recurring_expenses or planned_expenses.
     */
    private fun recurringStoreReferences(rel: String, raw: String): List<Hit> {
        if (!isPlanCode(rel)) return emptyList()
        return lineHits(
            "RECURRING_STORE_DAO_REFERENCE_IN_PLAN_CODE",
            Regex("""(?:RecurringOccurrence|ManualRecurringExpense|RecurringExpense|PlannedExpense)Dao""", RegexOption.IGNORE_CASE),
            code(raw)
        ) + lineHits(
            "RECURRING_STORE_TABLE_REFERENCE_IN_PLAN_CODE",
            Regex("""\b(?:recurring_occurrences|manual_recurring_expenses|planned_expenses)\b""", RegexOption.IGNORE_CASE),
            stripCommentsKeepStrings(raw)
        )
    }

    /**
     * INV-24 / check 5. setSpendingExclusion* calls or callable references appear only in the plan
     * coordinator and the TLC. Declarations (`fun [Receiver.]setSpendingExclusionX(`) are definition sites,
     * not calls, and never hide a call beside them.
     */
    private fun spendingExclusionCalls(rel: String, raw: String): List<Hit> {
        if (rel == COORDINATOR_PATH || rel == TLC_PATH) return emptyList()
        val code = code(raw)
        val declarationTail = Regex("""\bfun\s+(?:[\w<>?]+\s*\.\s*)?$""")
        val call = lineHits(
            "SET_SPENDING_EXCLUSION_CALL_OUTSIDE_COORDINATOR",
            Regex("""\bsetSpendingExclusion\w*\s*\("""),
            code
        ) { match ->
            val tail = code.substring((match.range.first - 80).coerceAtLeast(0), match.range.first)
            !declarationTail.containsMatchIn(tail)
        }
        val reference = lineHits(
            "SET_SPENDING_EXCLUSION_CALL_OUTSIDE_COORDINATOR",
            Regex("""::\s*setSpendingExclusion\w*"""),
            code
        )
        return call + reference
    }

    // ---------------------------------------------------------------- live scans

    @Test
    fun `scan is fail closed - source root exists and more than 100 Kotlin files are scanned`() {
        val files = requireScan()
        assertTrue("source root must be a directory: ${sourceRoot.path}", sourceRoot.isDirectory)
        assertTrue("scan saw ${files.size} files", files.size > MIN_SCANNED_FILES)
        // Relative paths are '/'-separated from the source root so the path model below can match them.
        assertTrue(
            "scan did not see the expected package root '$ROOT/' - relative path model is broken",
            files.any { it.rel.startsWith("$ROOT/") }
        )
    }

    @Test
    fun `scan walker fails closed on a missing root`() {
        val missing = File(System.getProperty("java.io.tmpdir") ?: ".", "installment-guard-missing-root-does-not-exist")
        assertFalse("test precondition: planted root must not exist", missing.exists())
        assertFailsWith<AssertionError> {
            FailClosedScan.walkSourceFiles(missing) { _, _ -> }
        }
    }

    @Test
    fun `INV-25 plan DAO writes appear only in the plan lifecycle coordinator`() {
        assertNoViolations(
            "Plan/entry DAO writes outside $COORDINATOR_PATH (INV-25)",
            this::planDaoWrites
        )
    }

    @Test
    fun `INV-24 no ExpenseDao reference in plan packages`() {
        assertNoViolations(
            "ExpenseDao referenced from plan code; expense writes go through TLC only (INV-24)",
            this::expenseDaoReferences
        )
    }

    @Test
    fun `INV-23 no deleteExpense call from plan packages`() {
        assertNoViolations(
            "deleteExpense called from plan code; plan actions never delete expenses (INV-23)",
            this::deleteExpenseCalls
        )
    }

    @Test
    fun `INV-25 plan code does not touch recurring occurrence manual recurring or planned expense stores`() {
        assertNoViolations(
            "Plan code references recurring_occurrences / manual_recurring_expenses / planned_expenses (INV-25, spec 5.2)",
            this::recurringStoreReferences
        )
    }

    @Test
    fun `INV-24 setSpendingExclusion is called only from the plan coordinator and the TLC`() {
        assertNoViolations(
            "setSpendingExclusion* called outside the plan coordinator / TLC definition (INV-24, spec 5.2)",
            this::spendingExclusionCalls
        )
    }

    // ---------------------------------------------------------------- negative self-checks

    private fun assertFlagged(label: String, hits: List<Hit>, rule: String) {
        assertTrue("matcher must flag [$rule]: $label (hits=$hits)", hits.any { it.rule == rule })
    }

    private fun assertClean(label: String, hits: List<Hit>) {
        assertTrue("matcher must NOT flag: $label (hits=$hits)", hits.isEmpty())
    }

    @Test
    fun `self-check comment stripper blanks comments but keeps string bodies and offsets`() {
        val src = "val s = \"a // b\" // c\n/* x /* nested */ still comment */ val t = 1"
        val stripped = stripCommentsKeepStrings(src)
        assertEquals("offsets must be preserved", src.length, stripped.length)
        assertTrue("string body must survive", stripped.contains("\"a // b\""))
        assertFalse("line comment must be blanked", stripped.contains("// c"))
        assertFalse("nested block comment must be fully blanked", stripped.contains("still comment"))
        assertTrue("code after a comment must survive", stripped.contains("val t = 1"))
        assertEquals("newlines must be preserved", src.count { it == '\n' }, stripped.count { it == '\n' })
    }

    @Test
    fun `self-check INV-25 plan DAO write detector flags planted writes and ignores benign text`() {
        val nonCoordinator = "$ROOT/data/repository/InstallmentPlanRepository.kt"
        val violating = listOf(
            "declared-type alias" to
                "class R(private val planDao: InstallmentPlanDao) { suspend fun f(p: P) { planDao.insert(p) } }",
            "arbitrary property name" to
                "class R(private val dao: InstallmentEntryDao) { suspend fun f() { dao.updateStatus(1L) } }",
            "accessor chain" to
                "suspend fun f() { database.installmentPlanDao().deleteById(1L) }",
            "local alias" to
                "suspend fun f() { val d = db.installmentEntryDao()\nd.upsertAll(list) }",
            "canonical name over a line break" to
                "suspend fun f() { installmentPlanDao\n    .insertAll(x) }",
            "safe call" to
                "class R(private val planDao: InstallmentPlanDao?) { suspend fun f(p: P) { planDao?.insert(p) } }",
            "mock-prefixed property" to
                "class R { lateinit var mockInstallmentPlanDao: Any; suspend fun f() { mockInstallmentPlanDao.update(x) } }",
            "claim verb on the canonical name" to
                "suspend fun f() { installmentPlanDao.claimEntryForExpense(1L, 2L, \"AUTO\", false, 0L) }",
            "release verb on an arbitrary property name" to
                "class R(private val dao: InstallmentPlanDao) { suspend fun f() { dao.releaseEntryLink(1L, 2L, null, 0L) } }",
            "claim verb through the accessor chain" to
                "suspend fun f() { database.installmentPlanDao().claimEntryForExpense(1L, 2L, \"AUTO\", false, 0L) }"
        )
        for ((label, snippet) in violating) {
            assertFlagged(label, planDaoWrites(nonCoordinator, snippet), "PLAN_DAO_WRITE_CALL")
        }
        assertFlagged(
            "plan DAO declared outside the dao package",
            planDaoWrites(nonCoordinator, "@Dao interface InstallmentPlanDao { @Insert suspend fun insert(p: P): Long }"),
            "PLAN_DAO_DECLARED_OUTSIDE_DAO_PACKAGE"
        )
        assertFlagged(
            "abstract class plan DAO outside the dao package",
            planDaoWrites(nonCoordinator, "abstract class InstallmentEntryDao { }"),
            "PLAN_DAO_DECLARED_OUTSIDE_DAO_PACKAGE"
        )
        val sqlWrites = listOf(
            "@Query(\"DELETE FROM installment_entries WHERE planId = :id\") suspend fun x()",
            "@Query(\"\"\"UPDATE installment_plans SET status = 'X'\"\"\") suspend fun x()",
            "db.execSQL(\"INSERT OR REPLACE INTO installment_plans VALUES (1)\")",
            "db.execSQL(\"REPLACE INTO `installment_entries` VALUES (1)\")"
        )
        for (snippet in sqlWrites) {
            assertFlagged(snippet, planDaoWrites(nonCoordinator, snippet), "PLAN_TABLE_RAW_SQL_WRITE")
        }
        // A same-basename file in a different directory is NOT the coordinator.
        assertFlagged(
            "same basename, different directory",
            planDaoWrites(
                "$ROOT/domain/installment/other/InstallmentPlanLifecycleCoordinator.kt",
                "class R(private val planDao: InstallmentPlanDao) { suspend fun f(p: P) { planDao.insert(p) } }"
            ),
            "PLAN_DAO_WRITE_CALL"
        )
        // A sibling file inside the coordinator's own directory is NOT the coordinator either.
        assertFlagged(
            "sibling of the coordinator",
            planDaoWrites(
                "$ROOT/domain/installment/lifecycle/InstallmentPlanRebuilder.kt",
                "class R(private val planDao: InstallmentPlanDao) { suspend fun f(p: P) { planDao.insert(p) } }"
            ),
            "PLAN_DAO_WRITE_CALL"
        )

        val benign = listOf(
            "plan DAO reads" to
                "class R(private val planDao: InstallmentPlanDao) { suspend fun f() = planDao.getById(1L) }",
            "entry read getEntryById" to
                "class R(private val dao: InstallmentPlanDao) { suspend fun f() = dao.getEntryById(1L) }",
            "entry read getEntryByLinkedExpenseId via canonical name" to
                "suspend fun f() = installmentPlanDao.getEntryByLinkedExpenseId(2L)",
            "accessor read" to "suspend fun f() = database.installmentPlanDao().observeAll()",
            "line comment mention" to
                "class R(private val planDao: InstallmentPlanDao) { // planDao.insert(p)\n suspend fun f() = planDao.getById(1L) }",
            "block comment mention" to "/* database.installmentPlanDao().insert(p) */ fun f() {}",
            "string mention" to "val s = \"installmentPlanDao.insert(p)\"",
            "SQL read" to "@Query(\"SELECT * FROM installment_plans\") fun all()",
            "SQL write in a comment" to "// DELETE FROM installment_plans\nfun f() {}",
            "unrelated DAO write" to "class R(private val expenseDao: Any) { fun f() { expenseDao.insert(e) } }",
            "DI accessor declaration" to
                "abstract class Db { abstract fun installmentPlanDao(): InstallmentPlanDao }"
        )
        for ((label, snippet) in benign) {
            assertClean(label, planDaoWrites(nonCoordinator, snippet))
        }
        // Exempt: the coordinator itself and DAO declarations in the dao package.
        assertClean(
            "coordinator may claim an entry link",
            planDaoWrites(
                COORDINATOR_PATH,
                "suspend fun f() { installmentPlanDao.claimEntryForExpense(1L, 2L, \"AUTO\", false, 0L) }"
            )
        )
        assertClean(
            "coordinator may release an entry link",
            planDaoWrites(
                COORDINATOR_PATH,
                "class C(private val dao: InstallmentPlanDao) { suspend fun f() { dao.releaseEntryLink(1L, 2L, null, 0L) } }"
            )
        )
        assertClean(
            "coordinator may write",
            planDaoWrites(
                COORDINATOR_PATH,
                "class C(private val planDao: InstallmentPlanDao) { suspend fun f(p: P) { planDao.insert(p) } }"
            )
        )
        assertClean(
            "dao package may declare and write",
            planDaoWrites(
                "$DAO_PACKAGE_PREFIX" + "InstallmentPlanDao.kt",
                "@Dao interface InstallmentPlanDao { @Query(\"DELETE FROM installment_plans\") suspend fun deleteAll() }"
            )
        )
    }

    /**
     * A9 (S2): the additive S2 schema DDL lives in DatabaseMigrations.kt (and could be echoed in
     * AppDatabase.kt). `CREATE TABLE/INDEX installment_*` and `ALTER TABLE expenses ADD COLUMN` are
     * schema creation, not plan-table row writes, so PLAN_TABLE_RAW_SQL_WRITE must not flag them even
     * though they mention installment_* tables. Row writes (INSERT/UPDATE/DELETE) at the same location
     * are still flagged by the matcher itself (control below, planted at a non-exempt path).
     */
    @Test
    fun `self-check A9 S2 schema DDL is not flagged by the raw SQL write rule`() {
        val s2Ddl = """
            val MIGRATION_151_152 = object : Migration(151, 152) {
                override fun migrate(database: SupportSQLiteDatabase) {
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS `installment_plans` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`planUuid` TEXT NOT NULL, " +
                            "`purchaseExpenseId` INTEGER, " +
                            "`status` TEXT NOT NULL)"
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_plans_planUuid` " +
                            "ON `installment_plans` (`planUuid`)"
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_plans_purchaseExpenseId` " +
                            "ON `installment_plans` (`purchaseExpenseId`)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_installment_plans_status` " +
                            "ON `installment_plans` (`status`)"
                    )
                    database.execSQL(
                        "CREATE TABLE IF NOT EXISTS `installment_entries` (" +
                            "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                            "`planId` INTEGER NOT NULL, " +
                            "FOREIGN KEY(`planId`) REFERENCES `installment_plans`(`id`) " +
                            "ON UPDATE NO ACTION ON DELETE CASCADE )"
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_entries_planId_kind_seq` " +
                            "ON `installment_entries` (`planId`, `kind`, `seq`)"
                    )
                    database.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_entries_linkedExpenseId` " +
                            "ON `installment_entries` (`linkedExpenseId`)"
                    )
                    database.execSQL(
                        "CREATE INDEX IF NOT EXISTS `index_installment_entries_dueDate` " +
                            "ON `installment_entries` (`dueDate`)"
                    )
                    database.execSQL("ALTER TABLE `expenses` ADD COLUMN `spendingExclusionReason` TEXT DEFAULT NULL")
                }
            }
        """.trimIndent()
        val migrationsPath = "$ROOT/data/database/DatabaseMigrations.kt"
        val appDatabasePath = "$ROOT/data/database/AppDatabase.kt"

        assertClean("S2 DDL block at the DatabaseMigrations.kt path", planDaoWrites(migrationsPath, s2Ddl))
        assertClean("S2 DDL block at the AppDatabase.kt path", planDaoWrites(appDatabasePath, s2Ddl))

        // Future-proofing: the DDL non-match must hold on its own, independent of any path exemption.
        // DDL ALONE (no INSERT/UPDATE/DELETE) at a path that is neither exempt nor a migrations file.
        val nonExemptPath = "$ROOT/data/repository/InstallmentPlanRepository.kt"
        assertClean("S2 DDL alone at a NON-exempt path", planDaoWrites(nonExemptPath, s2Ddl))

        val singleStatements = listOf(
            "CREATE TABLE IF NOT EXISTS `installment_plans` (`id` INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS `installment_entries` (`id` INTEGER NOT NULL, " +
                "FOREIGN KEY(`planId`) REFERENCES `installment_plans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_plans_planUuid` ON `installment_plans` (`planUuid`)",
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_installment_entries_linkedExpenseId` " +
                "ON `installment_entries` (`linkedExpenseId`)",
            "CREATE INDEX IF NOT EXISTS `index_installment_entries_status` ON `installment_entries` (`status`)",
            "ALTER TABLE `expenses` ADD COLUMN `spendingExclusionReason` TEXT DEFAULT NULL"
        )
        for (statement in singleStatements) {
            val snippet = "fun f(database: SupportSQLiteDatabase) { database.execSQL(\"$statement\") }"
            assertClean(statement, planDaoWrites(migrationsPath, snippet))
        }

        // Control: the matcher is not blind. Row writes next to the same DDL are flagged at a
        // non-exempt path, so the clean results above cannot come from a dead rule.
        val nonExempt = "$ROOT/data/repository/InstallmentPlanRepository.kt"
        assertFlagged("DDL plus INSERT at a non-exempt path", planDaoWrites(nonExempt, s2Ddl + "\n" +
            "fun g(db: SupportSQLiteDatabase) { db.execSQL(\"INSERT INTO installment_plans (id) VALUES (1)\") }"),
            "PLAN_TABLE_RAW_SQL_WRITE")
        assertFlagged("DDL plus UPDATE at a non-exempt path", planDaoWrites(nonExempt, s2Ddl + "\n" +
            "fun g(db: SupportSQLiteDatabase) { db.execSQL(\"UPDATE installment_entries SET status = 'X'\") }"),
            "PLAN_TABLE_RAW_SQL_WRITE")
        assertFlagged("DDL plus DELETE at a non-exempt path", planDaoWrites(nonExempt, s2Ddl + "\n" +
            "fun g(db: SupportSQLiteDatabase) { db.execSQL(\"DELETE FROM installment_entries\") }"),
            "PLAN_TABLE_RAW_SQL_WRITE")
    }

    @Test
    fun `A9 live S2 schema DDL in DatabaseMigrations is present and not flagged by the plan DAO write rule`() {
        val files = requireScan()
        val migrations = files.singleOrNull { it.rel == "$ROOT/data/database/DatabaseMigrations.kt" }
        assertTrue("DatabaseMigrations.kt must be in the scan (else this check is vacuous)", migrations != null)
        val text = migrations!!.raw
        assertTrue("S2 plans DDL must be present", text.contains("CREATE TABLE IF NOT EXISTS `installment_plans`"))
        assertTrue("S2 entries DDL must be present", text.contains("CREATE TABLE IF NOT EXISTS `installment_entries`"))
        assertTrue(
            "S2 expenses column DDL must be present",
            text.contains("ALTER TABLE `expenses` ADD COLUMN `spendingExclusionReason`")
        )
        assertClean("real DatabaseMigrations.kt", planDaoWrites(migrations.rel, text))

        val appDatabase = files.singleOrNull { it.rel == "$ROOT/data/database/AppDatabase.kt" }
        assertTrue("AppDatabase.kt must be in the scan", appDatabase != null)
        assertClean("real AppDatabase.kt", planDaoWrites(appDatabase!!.rel, appDatabase.raw))
    }

    @Test
    fun `self-check INV-24 ExpenseDao detector flags planted references in every plan package and ignores benign text`() {
        val planPaths = listOf(
            "$ROOT/domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt",
            "$ROOT/domain/installment/InstallmentScheduleCalculator.kt",
            "$ROOT/service/installment/InstallmentRecordDueWorker.kt",
            "$ROOT/ui/screens/installments/InstallmentsViewModel.kt"
        )
        val violating = listOf(
            "import com.yourname.expensetracker.data.database.dao.ExpenseDao",
            "class X(private val expenseDao: ExpenseDao)",
            "suspend fun f() = database.expenseDao().getById(1L)",
            "class X(private val gate: RestrictedExpenseDaoMutation)",
            "class X(private val mockExpenseDao: Any)"
        )
        for (path in planPaths) {
            for (snippet in violating) {
                assertFlagged("$path :: $snippet", expenseDaoReferences(path, snippet), "EXPENSE_DAO_REFERENCE_IN_PLAN_CODE")
            }
        }
        val planFile = planPaths[1]
        assertClean("line comment", expenseDaoReferences(planFile, "// uses ExpenseDao\nfun f() {}"))
        assertClean("block comment", expenseDaoReferences(planFile, "/* expenseDao.insert(e) */ fun f() {}"))
        assertClean("string literal", expenseDaoReferences(planFile, "val s = \"ExpenseDao\""))
        assertClean(
            "non-plan package may use ExpenseDao",
            expenseDaoReferences("$ROOT/data/repository/ExpenseRepository.kt", "class R(private val expenseDao: ExpenseDao)")
        )
        assertClean(
            "TLC is not a plan package",
            expenseDaoReferences(TLC_PATH, "class T(private val expenseDao: ExpenseDao)")
        )
        assertClean(
            "other DAOs ending in ExpenseDao are check 4's concern, not this rule's",
            expenseDaoReferences(planFile, "class X(private val d: GroupExpenseDao)")
        )
        assertClean(
            "TLC use from plan code is legal",
            expenseDaoReferences(planFile, "class X(private val tlc: TransactionLifecycleCoordinator)")
        )
    }

    @Test
    fun `self-check INV-23 deleteExpense detector flags planted calls and ignores benign text`() {
        val planPath = "$ROOT/domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt"
        val violating = listOf(
            "suspend fun f() { tlc.deleteExpense(id) }",
            "suspend fun f() { repo.deleteExpense(expense) }",
            "suspend fun f() { deleteExpense(1L) }",
            "val ref = tlc::deleteExpense",
            "suspend fun f() { tlc\n    .deleteExpense (1L) }",
            "suspend fun f() { tlc.deleteExpenseDbOnly(1L) }",
            "suspend fun f() { helper.hardDeleteExpense(1L) }"
        )
        for (snippet in violating) {
            assertFlagged(snippet, deleteExpenseCalls(planPath, snippet), "DELETE_EXPENSE_CALL_IN_PLAN_CODE")
        }
        for (otherPackage in listOf(
            "$ROOT/service/installment/InstallmentRecordDueWorker.kt",
            "$ROOT/ui/screens/installments/InstallmentsViewModel.kt"
        )) {
            assertFlagged(otherPackage, deleteExpenseCalls(otherPackage, "fun f() { tlc.deleteExpense(1L) }"), "DELETE_EXPENSE_CALL_IN_PLAN_CODE")
        }
        assertClean("line comment", deleteExpenseCalls(planPath, "// never tlc.deleteExpense(id)\nfun f() {}"))
        assertClean("block comment", deleteExpenseCalls(planPath, "/* deleteExpense(1L) */ fun f() {}"))
        assertClean("string literal", deleteExpenseCalls(planPath, "val s = \"deleteExpense(1)\""))
        assertClean(
            "deleting a plan is a different op",
            deleteExpenseCalls(planPath, "suspend fun f() { deleteInstallmentPlan(1L); unlinkExpense(2L) }")
        )
        assertClean(
            "non-plan code (e.g. transactions screen) may delete expenses",
            deleteExpenseCalls("$ROOT/ui/screens/transactions/TransactionsViewModel.kt", "fun f() { tlc.deleteExpense(1L) }")
        )
    }

    @Test
    fun `self-check INV-25 recurring store detector flags planted DAO and SQL references and ignores benign text`() {
        val planPath = "$ROOT/service/installment/InstallmentRecordDueWorker.kt"
        val daoSnippets = listOf(
            "class X(private val occurrenceDao: RecurringOccurrenceDao)",
            "class X(private val d: ManualRecurringExpenseDao)",
            "class X(private val d: PlannedExpenseDao)",
            "suspend fun f() { plannedExpenseDao.insert(x) }",
            "suspend fun f() = database.manualRecurringExpenseDao().getAll()",
            "suspend fun f() = database.recurringOccurrenceDao().getAll()"
        )
        for (snippet in daoSnippets) {
            assertFlagged(snippet, recurringStoreReferences(planPath, snippet), "RECURRING_STORE_DAO_REFERENCE_IN_PLAN_CODE")
        }
        val sqlSnippets = listOf(
            "val s = \"INSERT INTO planned_expenses VALUES (1)\"",
            "@Query(\"UPDATE manual_recurring_expenses SET isActive = 0\") suspend fun x()",
            "@Query(\"DELETE FROM recurring_occurrences\") suspend fun x()",
            "db.execSQL(\"\"\"INSERT INTO recurring_occurrences (id) VALUES (1)\"\"\")"
        )
        for (snippet in sqlSnippets) {
            assertFlagged(snippet, recurringStoreReferences(planPath, snippet), "RECURRING_STORE_TABLE_REFERENCE_IN_PLAN_CODE")
        }
        for (path in listOf(
            "$ROOT/domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt",
            "$ROOT/ui/screens/installments/InstallmentsViewModel.kt"
        )) {
            assertFlagged(
                path,
                recurringStoreReferences(path, "class X(private val d: PlannedExpenseDao)"),
                "RECURRING_STORE_DAO_REFERENCE_IN_PLAN_CODE"
            )
        }
        assertClean("line comment DAO", recurringStoreReferences(planPath, "// not PlannedExpenseDao\nfun f() {}"))
        assertClean("block comment DAO", recurringStoreReferences(planPath, "/* RecurringOccurrenceDao */ fun f() {}"))
        assertClean("string DAO name", recurringStoreReferences(planPath, "val s = \"ManualRecurringExpenseDao\""))
        assertClean(
            "comment mention of RecurringExpenseDao",
            recurringStoreReferences(planPath, "// never touch RecurringExpenseDao\n/* recurringExpenseDao().insert(m) */ fun f() {}")
        )
        assertClean("SQL in a comment", recurringStoreReferences(planPath, "// DELETE FROM planned_expenses\nfun f() {}"))
        assertClean(
            "plan's own tables are fine here",
            recurringStoreReferences(
                planPath,
                "@Query(\"SELECT * FROM installment_entries\") fun all()\nclass X(private val d: InstallmentEntryDao)"
            )
        )
        assertClean(
            "recurring code may use its own stores",
            recurringStoreReferences(
                "$ROOT/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt",
                "class X(private val d: RecurringOccurrenceDao) { val s = \"DELETE FROM recurring_occurrences\" }"
            )
        )
    }

    @Test
    fun `self-check INV-25 RecurringExpenseDao typed write is flagged at a plan path`() {
        val planPath = "$ROOT/domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt"
        val snippet = "class X(private val d: RecurringExpenseDao) { suspend fun f() { d.insert(m) } }"
        assertFlagged(
            "typed RecurringExpenseDao write at a plan path",
            recurringStoreReferences(planPath, snippet),
            "RECURRING_STORE_DAO_REFERENCE_IN_PLAN_CODE"
        )
    }

    @Test
    fun `self-check INV-25 recurringExpenseDao accessor chain delete is flagged at a plan path`() {
        val planPath = "$ROOT/service/installment/InstallmentRecordDueWorker.kt"
        val snippet = "suspend fun f() { database.recurringExpenseDao().deleteById(1L) }"
        assertFlagged(
            "recurringExpenseDao() accessor delete at a plan path",
            recurringStoreReferences(planPath, snippet),
            "RECURRING_STORE_DAO_REFERENCE_IN_PLAN_CODE"
        )
    }

    @Test
    fun `self-check INV-24 setSpendingExclusion detector flags planted calls and ignores declarations comments and exempt files`() {
        val outsiders = listOf(
            "$ROOT/data/repository/ExpenseRepository.kt",
            "$ROOT/service/installment/InstallmentRecordDueWorker.kt",
            "$ROOT/ui/screens/installments/InstallmentsViewModel.kt",
            "$ROOT/domain/installment/lifecycle/InstallmentPlanRebuilder.kt",
            "$ROOT/domain/installment/other/InstallmentPlanLifecycleCoordinator.kt",
            "$ROOT/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt"
        )
        val violating = listOf(
            "suspend fun f() { tlc.setSpendingExclusionDbOnly(1L, null) }",
            "suspend fun f() { setSpendingExclusionDbOnly(1L, r) }",
            "suspend fun f() { expenseDao.setSpendingExclusionReason(1L, r) }",
            "val ref = tlc::setSpendingExclusionDbOnly",
            "suspend fun f() { tlc\n    .setSpendingExclusion (1L, null) }",
            // A declaration beside a call must not hide the call.
            "suspend fun setSpendingExclusionDbOnly(id: Long, r: String?) {}\nsuspend fun g() { setSpendingExclusionDbOnly(3L, null) }"
        )
        for (path in outsiders) {
            for (snippet in violating) {
                assertFlagged("$path :: $snippet", spendingExclusionCalls(path, snippet), "SET_SPENDING_EXCLUSION_CALL_OUTSIDE_COORDINATOR")
            }
        }
        val callSnippet = "suspend fun f() { tlc.setSpendingExclusionDbOnly(1L, null) }"
        assertClean("plan coordinator may call", spendingExclusionCalls(COORDINATOR_PATH, callSnippet))
        assertClean("TLC may call and define", spendingExclusionCalls(TLC_PATH, callSnippet))

        val benignOutsider = outsiders.first()
        assertClean(
            "definition site (member declaration) is not a call",
            spendingExclusionCalls(benignOutsider, "interface Port { suspend fun setSpendingExclusionDbOnly(id: Long, r: String?) }")
        )
        assertClean(
            "definition site (extension declaration) is not a call",
            spendingExclusionCalls(benignOutsider, "suspend fun ExpenseDao.setSpendingExclusionReason(id: Long, r: String?) {}")
        )
        assertClean("line comment", spendingExclusionCalls(benignOutsider, "// tlc.setSpendingExclusionDbOnly(1L, null)\nfun f() {}"))
        assertClean("block comment", spendingExclusionCalls(benignOutsider, "/* setSpendingExclusionDbOnly(1L, null) */ fun f() {}"))
        assertClean("string literal", spendingExclusionCalls(benignOutsider, "val s = \"setSpendingExclusionDbOnly(1)\""))
    }

    private companion object {
        const val ROOT = "com/yourname/expensetracker"

        const val COORDINATOR_PATH =
            "$ROOT/domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt"

        const val TLC_PATH =
            "$ROOT/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt"

        const val DAO_PACKAGE_PREFIX = "$ROOT/data/database/dao/"

        /**
         * Entries are directory prefixes plus one exact file: the schedule exclusivity policy sits outside
         * the installment package (S5, A9) and is plan code, but the rest of domain/schedule is not.
         */
        val PLAN_CODE_PREFIXES = listOf(
            "$ROOT/domain/installment/",
            "$ROOT/domain/schedule/ExpenseScheduleLinkPolicy.kt",
            "$ROOT/service/installment/",
            "$ROOT/ui/screens/installments/"
        )

        /** Mirrors MUTATION_VERBS in scripts/verify_db_access_boundaries.py (detection grammar, prefix match). */
        val MUTATION_VERB_PREFIXES = listOf(
            "insert", "update", "delete", "clear", "mark", "set", "claim",
            "archive", "restore", "unlink", "link", "block", "unblock", "purge",
            "redact", "suppress", "fulfill", "approve", "reject", "dismiss",
            "expire", "deactivate", "finalize", "transition", "repair", "seed",
            "increment", "decrement", "upsert", "replace", "merge", "reassign",
            "record", "recover", "release", "reopen", "refresh", "reset", "cancel",
            "attach", "disconnect", "complete", "accept", "encrypt",
            "cleanup", "bulk", "add", "conditionally"
        )

        const val MIN_SCANNED_FILES = 100
        const val MAX_SNIPPET = 120
        const val MAX_REPORTED = 25
    }
}
