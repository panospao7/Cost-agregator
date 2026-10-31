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

    /**
     * S6c-F.1 (owner item 36a) / check 10. Plan code may call exactly [PLAN_ALLOWED_TLC_OPS] on a
     * TransactionLifecycleCoordinator. Receivers tracked on sanitized code (comments and strings ignored):
     *  - anything declared `name: (pkg.)TransactionLifecycleCoordinator` (ctor params, vals, locals);
     *  - the canonical names `transactionLifecycleCoordinator` and `tlc`;
     *  - local aliases `val|var x = <receiver>` (followed to a fixed point, so alias chains are covered).
     * Flagged when the member is not allowed: `r.m`, `r?.m`, `r!!.m`, a line break before the dot, scope
     * functions (`.let/.run/.apply/.also` are members outside the allow-list; `with(r)` is flagged outright),
     * and `r::m` / `TransactionLifecycleCoordinator::m`. Any member access is judged, not only `m(`, so a
     * property or a bare reference cannot dodge the list. [allowed] is a parameter only so the live-visibility
     * check can prove the matcher sees the real coordinator's receivers.
     * Limits: a receiver passed through a generic or function-typed parameter, a lambda parameter, a Lazy or
     * Provider wrapper, destructuring, or a differently named untyped property is not tracked. Also not
     * flagged: a receiver in parentheses or a cast (`(tlc).op(..)`, `(tlc as TransactionLifecycleCoordinator).op(..)`),
     * an alias assigned in parentheses (`val t = (tlc)`), an import alias of the type (`import ... as Tlc`), and a
     * call inside a string template (string bodies are blanked by the sanitizer). None exists in plan code today.
     */
    private fun planTlcCalls(rel: String, raw: String, allowed: Set<String> = PLAN_ALLOWED_TLC_OPS): List<Hit> {
        if (!isPlanCode(rel)) return emptyList()
        val code = code(raw)
        val names = linkedSetOf("transactionLifecycleCoordinator", "tlc")
        Regex("""\b([A-Za-z_]\w*)\s*:\s*(?:\w+\.)*TransactionLifecycleCoordinator\b""")
            .findAll(code).forEach { names += it.groupValues[1] }
        var grew = true
        while (grew) {
            grew = false
            val known = names.joinToString("|") { Regex.escape(it) }
            Regex(
                """\b(?:val|var)\s+([A-Za-z_]\w*)\s*(?::[^=\n(){},;]*)?=\s*(?:this\s*\.\s*)?(?:$known)(?!\w)(?:\s*!!)?(?!\s*(?:\??\s*\.|\(|::))"""
            ).findAll(code).forEach { if (names.add(it.groupValues[1])) grew = true }
        }
        val group = names.joinToString("|") { Regex.escape(it) }
        val rule = "PLAN_TLC_OP_NOT_ALLOWED"
        val notAllowed: (MatchResult) -> Boolean = { it.groupValues[1] !in allowed }
        val member = Regex("""(?<!\w)(?:$group)(?!\w)(?:\s*!!)?\s*\??\s*\.\s*(\w+)""")
        val reference = Regex("""(?<!\w)(?:$group|TransactionLifecycleCoordinator)(?!\w)\s*::\s*(\w+)""")
        val scopeWith = Regex("""\bwith\s*\(\s*(?:this\s*\.\s*)?(?:$group)(?!\w)""")
        return lineHits(rule, member, code, notAllowed) +
            lineHits(rule, reference, code, notAllowed) +
            lineHits(rule, scopeWith, code)
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

    @Test
    fun `S6c-F1 INV-24 INV-25 plan code calls only the allow-listed TLC operations`() {
        assertNoViolations(
            "Plan code calls a TransactionLifecycleCoordinator operation outside $PLAN_ALLOWED_TLC_OPS (INV-24, INV-25, owner item 36a)",
            { rel, raw -> planTlcCalls(rel, raw) }
        )
    }

    @Test
    fun `S6c-F1 non-vacuity - allow-list is exactly the two DbOnly ops, declared in the TLC and called by the coordinator`() {
        assertEquals(
            "a third TLC op for plan code needs explicit owner approval (S6c-F follow-up Stop and ask)",
            setOf("createExpenseDbOnlyV2", "setSpendingExclusionDbOnly"),
            PLAN_ALLOWED_TLC_OPS
        )
        val files = requireScan()
        val tlc = files.singleOrNull { it.rel == TLC_PATH }
        val coordinator = files.singleOrNull { it.rel == COORDINATOR_PATH }
        assertTrue("TLC file must be in the scan at $TLC_PATH (else this check is vacuous)", tlc != null)
        assertTrue("plan coordinator must be in the scan at $COORDINATOR_PATH (else this check is vacuous)", coordinator != null)
        val tlcCode = code(tlc!!.raw)
        val coordinatorCode = code(coordinator!!.raw)
        val dbOnlyName = Regex("""DbOnly(V\d+)?$""")
        for (op in PLAN_ALLOWED_TLC_OPS) {
            assertTrue("allowed op '$op' must be a DbOnly(V<n>) operation", dbOnlyName.containsMatchIn(op))
            assertTrue(
                "allowed op '$op' must be declared as 'fun $op(' in $TLC_PATH",
                Regex("""\bfun\s+${Regex.escape(op)}\s*\(""").containsMatchIn(tlcCode)
            )
            assertTrue(
                "allowed op '$op' must be called at least once in $COORDINATOR_PATH (stale allow-list entry otherwise)",
                Regex("""\.\s*${Regex.escape(op)}\b""").containsMatchIn(coordinatorCode)
            )
        }
    }

    @Test
    fun `S6c-F1 live visibility - with an empty allow-list the matcher sees every TLC call in the coordinator`() {
        val coordinator = requireScan().singleOrNull { it.rel == COORDINATOR_PATH }
        assertTrue("plan coordinator must be in the scan at $COORDINATOR_PATH", coordinator != null)
        val hits = planTlcCalls(coordinator!!.rel, coordinator.raw, allowed = emptySet())
        assertTrue(
            "matcher must see at least the three known TLC call sites in the coordinator, saw ${hits.size} (receiver tracking is blind)",
            hits.size >= 3
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

    @Test
    fun `self-check S6c-F1 TLC allow-list detector flags planted forbidden ops and ignores allowed ops comments strings and non-plan paths`() {
        val plan = COORDINATOR_PATH
        val siblingPlan = "$ROOT/domain/installment/lifecycle/InstallmentPlanRebuilder.kt"
        val workerPlan = "$ROOT/service/installment/InstallmentRecordDueWorker.kt"
        val uiPlan = "$ROOT/ui/screens/installments/InstallmentsViewModel.kt"
        val schedulePolicy = "$ROOT/domain/schedule/ExpenseScheduleLinkPolicy.kt"
        val tlcType = "com.yourname.expensetracker.domain.transaction.lifecycle.TransactionLifecycleCoordinator"
        val rule = "PLAN_TLC_OP_NOT_ALLOWED"

        val violating = listOf(
            "canonical tlc create" to
                "suspend fun f(r: R) { tlc.createExpense(r) }",
            "canonical long name update" to
                "suspend fun f(e: E) { transactionLifecycleCoordinator.updateExpense(e) }",
            "canonical tlc delete DbOnly (a DbOnly name is not automatically allowed)" to
                "suspend fun f() { tlc.deleteExpenseDbOnly(1L) }",
            "V2 lookalike outside the list" to
                "suspend fun f(r: R) { tlc.createExpenseDbOnly(r) }",
            "typed ctor property with arbitrary name" to
                "class R(private val coord: TransactionLifecycleCoordinator) { suspend fun f(r: Any) { coord.createExpense(r) } }",
            "fully qualified type" to
                "class R(private val c: $tlcType) { suspend fun f(r: Any) { c.createExpense(r) } }",
            "nullable typed receiver with safe call" to
                "class R(private val coord: TransactionLifecycleCoordinator?) { suspend fun f(r: Any) { coord?.createExpense(r) } }",
            "safe call on canonical name" to
                "suspend fun f(r: R) { tlc?.createExpense(r) }",
            "not-null assertion before the dot" to
                "suspend fun f(r: R) { tlc!!.createExpense(r) }",
            "line break before the dot" to
                "suspend fun f(r: R) { tlc\n    .createExpense(r) }",
            "line break before a safe-call dot" to
                "suspend fun f(r: R) { transactionLifecycleCoordinator\n    ?.updateExpense(r) }",
            "this-qualified receiver" to
                "class R { suspend fun f(r: R) { this.tlc.createExpense(r) } }",
            "local alias of the canonical name" to
                "suspend fun f(r: R) { val t = tlc\n t.createExpense(r) }",
            "alias of an alias" to
                "suspend fun f(r: R) { val a = transactionLifecycleCoordinator\n val b = a\n b.updateExpense(r) }",
            "alias of a typed receiver" to
                "class R(private val coord: TransactionLifecycleCoordinator) { suspend fun f(r: Any) { val c = coord\n c.createExpense(r) } }",
            "callable reference on a receiver" to
                "val ref = tlc::createExpense",
            "callable reference on the type" to
                "val ref = TransactionLifecycleCoordinator::createExpense",
            "with scope function" to
                "suspend fun f() { with(tlc) { } }",
            "let scope function" to
                "suspend fun f() { tlc.let { } }",
            "run scope function" to
                "suspend fun f() { transactionLifecycleCoordinator.run { } }",
            "apply scope function" to
                "suspend fun f() { tlc.apply { } }",
            "also scope function" to
                "suspend fun f() { tlc.also { } }",
            "property-style member access" to
                "suspend fun f() = tlc.someState"
        )
        for (path in listOf(plan, siblingPlan, workerPlan, uiPlan, schedulePolicy)) {
            for ((label, snippet) in violating) {
                assertFlagged("$path :: $label", planTlcCalls(path, snippet), rule)
            }
        }
        // The same typed-alias shape at a sibling plan path, spelled out as one named case.
        assertFlagged(
            "typed alias at a sibling plan path",
            planTlcCalls(
                siblingPlan,
                "class R(private val txCoordinator: TransactionLifecycleCoordinator) { suspend fun f(e: Any) { txCoordinator.deleteExpense(1L) } }"
            ),
            rule
        )

        val allowedClean = listOf(
            "canonical create V2" to
                "suspend fun f(r: R) { val x = tlc.createExpenseDbOnlyV2(r) }",
            "canonical long name exclusion" to
                "suspend fun f() { transactionLifecycleCoordinator.setSpendingExclusionDbOnly(1L, null) }",
            "typed alias, allowed op" to
                "class R(private val coord: TransactionLifecycleCoordinator) { suspend fun f(r: Any) { coord.createExpenseDbOnlyV2(r) } }",
            "typed alias, safe call, allowed op" to
                "class R(private val coord: TransactionLifecycleCoordinator?) { suspend fun f(r: Any) { coord?.setSpendingExclusionDbOnly(1L, null) } }",
            "typed alias, line break before the dot, allowed op" to
                "class R(private val coord: TransactionLifecycleCoordinator) { suspend fun f(r: Any) { coord\n    .createExpenseDbOnlyV2(r) } }",
            "local alias, allowed op" to
                "suspend fun f(r: R) { val t = tlc\n t.setSpendingExclusionDbOnly(1L, null) }",
            "allowed op callable reference" to
                "val ref = tlc::createExpenseDbOnlyV2",
            "line comment mention" to
                "// tlc.createExpense(r)\nfun f() {}",
            "block comment mention" to
                "/* transactionLifecycleCoordinator.updateExpense(e) and with(tlc) */ fun f() {}",
            "string mention" to
                "val s = \"tlc.createExpense(r)\"",
            "raw string mention" to
                "val s = \"\"\"transactionLifecycleCoordinator.deleteExpense(1L)\"\"\"",
            "declaration only, no member access" to
                "class R(private val transactionLifecycleCoordinator: TransactionLifecycleCoordinator)",
            "import line" to
                "import $tlcType",
            "unrelated receiver is not a TLC" to
                "suspend fun f(r: R) { planRepository.createExpense(r); clock.update(); tlcLike.createExpense(r); mytlc.createExpense(r) }",
            "alias of an unrelated value" to
                "suspend fun f(r: R) { val t = somethingElse\n t.createExpense(r) }"
        )
        for (path in listOf(plan, siblingPlan, workerPlan, uiPlan, schedulePolicy)) {
            for ((label, snippet) in allowedClean) {
                assertClean("$path :: $label", planTlcCalls(path, snippet))
            }
        }

        // Non-plan paths are not this rule's concern (the TLC has other legal callers).
        for (nonPlan in listOf(
            "$ROOT/ui/screens/transactions/TransactionsViewModel.kt",
            "$ROOT/data/repository/ExpenseRepository.kt",
            "$ROOT/domain/schedule/ExpenseSchedulePolicy.kt",
            TLC_PATH
        )) {
            assertClean("non-plan path $nonPlan", planTlcCalls(nonPlan, "suspend fun f(r: R) { tlc.createExpense(r); tlc.deleteExpense(1L) }"))
        }

        // Control: with the allow-list widened, a previously flagged op becomes clean, so the hits above
        // come from the allow-list rule and not from a receiver-shape accident.
        assertClean(
            "widened allow-list control",
            planTlcCalls(plan, "suspend fun f(r: R) { tlc.createExpense(r) }", allowed = setOf("createExpense"))
        )
    }

    // ---------------------------------------------------------------- S6a read-side seam (F6)

    /**
     * F6 / check 6. The three data-layer read implementations (exact paths) never call a DAO write.
     * Any receiver whose name or declared type ends in Dao is a DAO receiver (canonical name, typed
     * alias, accessor chain, local alias, safe call, line break before the dot); the verbs are
     * [MUTATION_VERB_PREFIXES]. Reads (get*, observe*) are allowed.
     */
    private fun readSeamDaoWrites(rel: String, raw: String): List<Hit> {
        if (rel !in READ_ONLY_IMPL_PATHS) return emptyList()
        val code = code(raw)
        val receivers = mutableListOf("""\w*dao\w*(?:\s*\(\s*\))?""")
        Regex("""\b([A-Za-z_]\w*)\s*:\s*(?:\w+\.)*\w*Dao\b""")
            .findAll(code).forEach { receivers += Regex.escape(it.groupValues[1]) }
        Regex("""\b(?:val|var)\s+([A-Za-z_]\w*)\s*=\s*[\w.?]*?\w*dao\s*\(\s*\)""", RegexOption.IGNORE_CASE)
            .findAll(code).forEach { receivers += Regex.escape(it.groupValues[1]) }
        val call = Regex(
            """(?<!\w)(?:${receivers.joinToString("|")})\s*\??\.\s*(?:${MUTATION_VERB_PREFIXES.joinToString("|")})\w*\s*\(""",
            RegexOption.IGNORE_CASE
        )
        return lineHits("READ_SEAM_DAO_WRITE_CALL", call, code)
    }

    /** F6 / check 7. The read implementations use no transaction, barrier, TLC or raw-query machinery. */
    private fun readSeamWriteMachinery(rel: String, raw: String): List<Hit> {
        if (rel !in READ_ONLY_IMPL_PATHS) return emptyList()
        return lineHits(
            "READ_SEAM_WRITE_MACHINERY",
            Regex(
                """\b(?:TransactionLifecycleCoordinator|\w*writeBarrier\w*|withTransaction|runInTransaction|writeTxn|RawQuery)\b""",
                RegexOption.IGNORE_CASE
            ),
            code(raw)
        )
    }

    /**
     * F6 / check 8. The plan read repository, the projection port and the candidate charge probe
     * (owner item 34) read the plan DAO and the facts port only; they never reference the expense,
     * recurring-occurrence or category DAOs.
     */
    private fun readImplExpenseStoreReferences(rel: String, raw: String): List<Hit> {
        if (rel != READ_REPOSITORY_IMPL_PATH && rel != PROJECTION_PORT_IMPL_PATH && rel != CANDIDATE_PROBE_IMPL_PATH) {
            return emptyList()
        }
        return lineHits(
            "READ_IMPL_EXPENSE_STORE_REFERENCE",
            Regex("""(?:Expense|RecurringOccurrence|Category)Dao""", RegexOption.IGNORE_CASE),
            code(raw)
        )
    }

    /** F6 / check 9. The domain port files and every readmodel file reference no *Dao type. */
    private fun domainPortDaoReferences(rel: String, raw: String): List<Hit> {
        if (rel !in DOMAIN_PORT_PATHS && !rel.startsWith(READMODEL_PREFIX)) return emptyList()
        return lineHits("DOMAIN_PORT_DAO_REFERENCE", Regex("""\b\w*dao\b""", RegexOption.IGNORE_CASE), code(raw))
    }

    @Test
    fun `F6 read side implementations contain no DAO write verbs`() {
        assertNoViolations(
            "DAO write verb in a read-side implementation (F6, INV-25)",
            this::readSeamDaoWrites
        )
    }

    @Test
    fun `F6 read side implementations use no transaction barrier TLC or raw query machinery`() {
        assertNoViolations(
            "write machinery in a read-side implementation (F6, INV-24)",
            this::readSeamWriteMachinery
        )
    }

    @Test
    fun `F6 plan read repository and projection port do not reference the expense recurring or category DAOs`() {
        assertNoViolations(
            "expense-store DAO referenced from a plan read implementation (F6, INV-24, INV-25)",
            this::readImplExpenseStoreReferences
        )
    }

    @Test
    fun `F6 domain port and readmodel files reference no Dao type`() {
        assertNoViolations(
            "Dao type referenced from a domain port or readmodel file (F6)",
            this::domainPortDaoReferences
        )
    }

    @Test
    fun `F6 every pinned path exists exactly once so the pins are not vacuous`() {
        val files = requireScan()
        for (path in READ_ONLY_IMPL_PATHS + DOMAIN_PORT_PATHS) {
            assertEquals("pinned file must exist exactly once: $path", 1, files.count { it.rel == path })
        }
        val readmodel = files.filter { it.rel.startsWith(READMODEL_PREFIX) }.map { it.rel.substringAfterLast('/') }
        assertTrue("readmodel must contain InstallmentPlanMath.kt, saw $readmodel", "InstallmentPlanMath.kt" in readmodel)
        assertTrue("readmodel must contain InstallmentPlanReadModel.kt, saw $readmodel", "InstallmentPlanReadModel.kt" in readmodel)
    }

    @Test
    fun `F6 the coordinator and TLC exemption paths are bound to the real classes`() {
        val files = requireScan()
        val coordinatorDecl = Regex("""\bclass\s+InstallmentPlanLifecycleCoordinator\b""")
        val tlcDecl = Regex("""\bclass\s+TransactionLifecycleCoordinator\b""")
        assertEquals(
            "exactly one file declares the plan coordinator and it is the pinned path",
            listOf(COORDINATOR_PATH),
            files.filter { it.raw.contains("InstallmentPlanLifecycleCoordinator") && coordinatorDecl.containsMatchIn(code(it.raw)) }
                .map { it.rel }
        )
        assertEquals(
            "exactly one file declares the TLC and it is the pinned path",
            listOf(TLC_PATH),
            files.filter { it.raw.contains("TransactionLifecycleCoordinator") && tlcDecl.containsMatchIn(code(it.raw)) }
                .map { it.rel }
        )
    }

    @Test
    fun `self-check F6 read side DAO write detector flags planted writes and ignores reads comments and unpinned paths`() {
        val violating = listOf(
            "typed constructor property" to
                "class R(private val expenseDao: ExpenseDao) { suspend fun f() { expenseDao.insert(e) } }",
            "arbitrary name with Dao type" to
                "class R(private val d: CategoryDao) { suspend fun f() { d.updateName(1L) } }",
            "accessor chain" to
                "suspend fun f() { database.recurringOccurrenceDao().deleteById(1L) }",
            "local alias" to
                "suspend fun f() { val d = db.expenseDao()\nd.clearFlag(1L) }",
            "safe call" to
                "class R(private val planDao: InstallmentPlanDao?) { suspend fun f() { planDao?.markPaid(1L) } }",
            "claim verb on canonical name" to
                "suspend fun f() { installmentPlanDao.claimEntryForExpense(1L, 2L, \"AUTO\", false, 0L) }",
            "release verb on typed alias" to
                "class R(private val dao: InstallmentPlanDao) { suspend fun f() { dao.releaseEntryLink(1L, 2L, null, 0L) } }",
            "line break before the dot" to
                "suspend fun f() { expenseDao\n    .setSpendingExclusionReason(1L, null) }"
        )
        for (path in READ_ONLY_IMPL_PATHS) {
            for ((label, snippet) in violating) {
                assertFlagged("$path :: $label", readSeamDaoWrites(path, snippet), "READ_SEAM_DAO_WRITE_CALL")
            }
        }
        val reads = listOf(
            "expense read" to "suspend fun f() = expenseDao.getById(1L)",
            "range read" to "suspend fun f() = expenseDao.getExpensesBetweenUncapped(1L, 2L)",
            "plan read" to "suspend fun f() = planDao.getEntriesForPlan(1L)",
            "flow read" to "fun f() = planDao.observeAllPlans()",
            "occurrence read" to "suspend fun f() = recurringOccurrenceDao.getByLinkedExpenseId(1L)",
            "line comment" to "// expenseDao.insert(e)\nfun f() {}",
            "block comment" to "/* database.expenseDao().deleteById(1L) */ fun f() {}",
            "string mention" to "val s = \"expenseDao.insert(e)\""
        )
        for (path in READ_ONLY_IMPL_PATHS) {
            for ((label, snippet) in reads) {
                assertClean("$path :: $label", readSeamDaoWrites(path, snippet))
            }
        }
        val writeSnippet = "class R(private val expenseDao: ExpenseDao) { suspend fun f() { expenseDao.insert(e) } }"
        assertClean("unpinned data path is not this rule's concern", readSeamDaoWrites("$ROOT/data/repository/ExpenseRepository.kt", writeSnippet))
        assertClean(
            "same basename in another directory is not a pinned path",
            readSeamDaoWrites("$ROOT/data/other/InstallmentExpenseFactsReaderImpl.kt", writeSnippet)
        )
    }

    @Test
    fun `self-check F6 write machinery detector flags planted tokens and ignores comments and unpinned paths`() {
        val violating = listOf(
            "class R(private val tlc: TransactionLifecycleCoordinator)",
            "class R(private val writeBarrier: DbWriteBarrier)",
            "suspend fun f() = database.withTransaction { 1 }",
            "@RawQuery suspend fun x(q: SupportSQLiteQuery): List<Long>",
            "import androidx.room.RawQuery",
            "suspend fun f() { db.runInTransaction { } }",
            "suspend fun f() = writeTxn(\"x\") { 1 }"
        )
        for (path in READ_ONLY_IMPL_PATHS) {
            for (snippet in violating) {
                assertFlagged("$path :: $snippet", readSeamWriteMachinery(path, snippet), "READ_SEAM_WRITE_MACHINERY")
            }
            assertClean("$path :: line comment", readSeamWriteMachinery(path, "// no TransactionLifecycleCoordinator here\nfun f() {}"))
            assertClean("$path :: block comment", readSeamWriteMachinery(path, "/* withTransaction RawQuery */ fun f() {}"))
            assertClean("$path :: string", readSeamWriteMachinery(path, "val s = \"writeBarrier withTransaction\""))
            assertClean("$path :: plain read", readSeamWriteMachinery(path, "suspend fun f() = expenseDao.getById(1L)"))
        }
        assertClean(
            "unpinned path may use a transaction",
            readSeamWriteMachinery("$ROOT/data/repository/ExpenseRepository.kt", "suspend fun f() = database.withTransaction { 1 }")
        )
    }

    @Test
    fun `self-check F6 plan read implementations flag expense recurring and category DAO references`() {
        val violating = listOf(
            "class R(private val expenseDao: ExpenseDao)",
            "class R(private val d: RecurringOccurrenceDao)",
            "class R(private val categoryDao: CategoryDao)",
            "suspend fun f() = database.expenseDao().getById(1L)"
        )
        for (path in listOf(READ_REPOSITORY_IMPL_PATH, PROJECTION_PORT_IMPL_PATH, CANDIDATE_PROBE_IMPL_PATH)) {
            for (snippet in violating) {
                assertFlagged("$path :: $snippet", readImplExpenseStoreReferences(path, snippet), "READ_IMPL_EXPENSE_STORE_REFERENCE")
            }
            assertClean("$path :: plan DAO", readImplExpenseStoreReferences(path, "class R(private val planDao: InstallmentPlanDao)"))
            assertClean("$path :: comment", readImplExpenseStoreReferences(path, "// never ExpenseDao\nfun f() {}"))
            assertClean("$path :: string", readImplExpenseStoreReferences(path, "val s = \"CategoryDao\""))
        }
        assertClean(
            "the facts reader implementation is the one place allowed to read those DAOs",
            readImplExpenseStoreReferences(FACTS_READER_IMPL_PATH, "class R(private val expenseDao: ExpenseDao)")
        )
    }

    @Test
    fun `self-check F6 domain ports flag any Dao type and ignore comments strings and unpinned paths`() {
        val pinned = DOMAIN_PORT_PATHS + listOf(
            "${READMODEL_PREFIX}InstallmentPlanMath.kt",
            "${READMODEL_PREFIX}SomeFutureReadModelFile.kt"
        )
        val violating = listOf(
            "import com.yourname.expensetracker.data.database.dao.ExpenseDao",
            "class P(private val planDao: InstallmentPlanDao)",
            "class P(private val d: RecurringOccurrenceDao)",
            "suspend fun f() = database.installmentPlanDao().getAllPlans()"
        )
        for (path in pinned) {
            for (snippet in violating) {
                assertFlagged("$path :: $snippet", domainPortDaoReferences(path, snippet), "DOMAIN_PORT_DAO_REFERENCE")
            }
            assertClean("$path :: line comment", domainPortDaoReferences(path, "// reads via a Dao in the data layer\nfun f() {}"))
            assertClean("$path :: block comment", domainPortDaoReferences(path, "/* ExpenseDao */ fun f() {}"))
            assertClean("$path :: string", domainPortDaoReferences(path, "val s = \"InstallmentPlanDao\""))
            assertClean("$path :: pure types", domainPortDaoReferences(path, "interface P { suspend fun f(): List<Long> }"))
        }
        assertClean(
            "the plan coordinator is not a port and may use the plan DAO",
            domainPortDaoReferences(COORDINATOR_PATH, "class C(private val planDao: InstallmentPlanDao)")
        )
        assertClean(
            "data layer implementations are not domain ports",
            domainPortDaoReferences(READ_REPOSITORY_IMPL_PATH, "class R(private val planDao: InstallmentPlanDao)")
        )
    }

    // ---------------------------------------------------------------- S6a coordinator never reads expense money

    /**
     * The coordinator decides on expense facts only (domain spec 7.0); the money reads of the facts reader
     * (`findExpenseMoney`, `findExpenseMoneyBetween`) belong to the read model. Matches any call, callable
     * reference or member access of a `findExpenseMoney*` name in the pinned coordinator file.
     */
    private fun coordinatorMoneyReads(rel: String, raw: String): List<Hit> {
        if (rel != COORDINATOR_PATH) return emptyList()
        return lineHits("COORDINATOR_EXPENSE_MONEY_READ", Regex("""\bfindExpenseMoney\w*"""), code(raw))
    }

    @Test
    fun `S6a the plan coordinator never references findExpenseMoney or findExpenseMoneyBetween`() {
        assertNoViolations(
            "plan coordinator reads expense money; only the read model may (facts reader contract)",
            this::coordinatorMoneyReads
        )
    }

    @Test
    fun `self-check S6a coordinator money read detector flags planted references and ignores comments strings and other files`() {
        val violating = listOf(
            "suspend fun f() = factsReader.findExpenseMoney(1L)",
            "suspend fun f() = factsReader.findExpenseMoneyBetween(1L, 2L)",
            "val ref = factsReader::findExpenseMoney",
            "suspend fun f() = factsReader\n    .findExpenseMoney (1L)"
        )
        for (snippet in violating) {
            assertFlagged(snippet, coordinatorMoneyReads(COORDINATOR_PATH, snippet), "COORDINATOR_EXPENSE_MONEY_READ")
        }
        assertClean("line comment", coordinatorMoneyReads(COORDINATOR_PATH, "// never findExpenseMoney(1L)\nfun f() {}"))
        assertClean("block comment", coordinatorMoneyReads(COORDINATOR_PATH, "/* findExpenseMoneyBetween */ fun f() {}"))
        assertClean("string literal", coordinatorMoneyReads(COORDINATOR_PATH, "val s = \"findExpenseMoney\""))
        assertClean("facts reads are legal", coordinatorMoneyReads(COORDINATOR_PATH, "suspend fun f() = factsReader.findExpenseFacts(1L)"))
        assertClean(
            "the read repository may read money",
            coordinatorMoneyReads(READ_REPOSITORY_IMPL_PATH, "suspend fun f() = factsReader.findExpenseMoney(1L)")
        )
    }

    // ---------------------------------------------------------------- S10b-1 F3 single post-commit runner owner

    /**
     * Owner approvals items 48 and 51c (S10b-1 F3): under the installment packages in
     * [POST_COMMIT_RUNNER_SCOPE_PREFIXES] only [POST_COMMIT_DISPATCHER_PATH] may reference
     * `PostCommitActionRunner`. Matched on sanitized code (comments and string bodies blanked), so imports,
     * type references, constructor params, fully qualified names and `::` references are all caught while a
     * comment mention is not. The pattern also covers `PostCommitActionRunner*` lookalikes such as the Impl.
     * No allow-list beyond the single dispatcher path (exact relative path, never basename).
     *
     * Owner item 59c extends the same rule to the public post-commit wrappers, which run a batch without
     * naming the runner: [POST_COMMIT_WRAPPER_TYPES] (`PostCommitSideEffectEvidenceService`,
     * `TransactionSideEffectDispatcher`, `ReceiptSideEffectDispatcher`, each with `*` lookalikes) and the
     * service calls `runWithEvidence` / `runBestEffortWithEvidence`. Same scope, same single exemption.
     *
     * Known limits (not covered by this rule):
     * - a typealias for the runner or a wrapper declared outside the three scope packages;
     * - a post-commit wrapper reached through a differently named type that is not listed in
     *   [POST_COMMIT_WRAPPER_TYPES] (a new wrapper type needs a new entry; the non-vacuity test only pins the
     *   three known declarations);
     * - installment code outside the three packages: `di/InstallmentModule.kt`,
     *   `data/repository/Installment*Impl.kt`.
     */
    private fun postCommitRunnerReferences(rel: String, raw: String): List<Hit> {
        if (POST_COMMIT_RUNNER_SCOPE_PREFIXES.none { rel.startsWith(it) }) return emptyList()
        if (rel == POST_COMMIT_DISPATCHER_PATH) return emptyList()
        val types = (listOf("PostCommitActionRunner") + POST_COMMIT_WRAPPER_TYPES).joinToString("|") { "$it\\w*" }
        return lineHits(
            "POST_COMMIT_RUNNER_OUTSIDE_DISPATCHER",
            Regex("""\b(?:$types|runWithEvidence\b|runBestEffortWithEvidence\b)"""),
            code(raw)
        )
    }

    @Test
    fun `S10b-1 F3 PostCommitActionRunner is referenced only by InstallmentPostCommitDispatcher in installment packages`() {
        assertNoViolations(
            "PostCommitActionRunner referenced in an installment package outside $POST_COMMIT_DISPATCHER_PATH " +
                "(S10b-1 F3 single owner, owner items 48 and 51c)",
            this::postCommitRunnerReferences
        )
    }

    @Test
    fun `self-check S10b-1 F3 post-commit runner detector flags planted references and ignores comments strings the dispatcher and other paths`() {
        val rule = "POST_COMMIT_RUNNER_OUTSIDE_DISPATCHER"
        val runnerFqn = "com.yourname.expensetracker.domain.sideeffect.PostCommitActionRunner"
        val violating = listOf(
            "import" to "import $runnerFqn\nclass X",
            "constructor param" to "class X(private val runner: PostCommitActionRunner)",
            "nullable constructor param" to "class X(private val runner: PostCommitActionRunner?)",
            "fully qualified type" to "class X(private val runner: $runnerFqn)",
            "class reference" to "val k = PostCommitActionRunner::class",
            "callable reference" to "val ref = PostCommitActionRunner::run",
            "Impl lookalike" to "class X(private val runner: PostCommitActionRunnerImpl)",
            "local declaration" to "suspend fun f(r: Any) { val x: PostCommitActionRunner = r as PostCommitActionRunner }",
            "reference beside a comment mention" to "// mentions PostCommitActionRunner\nclass X(private val r: PostCommitActionRunner)"
        )
        val installmentPaths = listOf(
            "$ROOT/ui/screens/installments/InstallmentsViewModel.kt",
            "$ROOT/ui/screens/installments/detail/InstallmentPlanDetailViewModel.kt",
            "$ROOT/service/installment/InstallmentRecordDueWorker.kt",
            "$ROOT/domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt",
            "$ROOT/domain/installment/usecase/CreateInstallmentPlanUseCase.kt",
            // A sibling of the dispatcher and a same-basename file elsewhere are NOT the dispatcher.
            "$ROOT/domain/installment/lifecycle/InstallmentPlanRebuilder.kt",
            "$ROOT/domain/installment/other/InstallmentPostCommitDispatcher.kt"
        )
        for (path in installmentPaths) {
            for ((label, snippet) in violating) {
                assertFlagged("$path :: $label", postCommitRunnerReferences(path, snippet), rule)
            }
        }

        val runnerSnippet = "import $runnerFqn\nclass D(private val postCommitActionRunner: PostCommitActionRunner)"
        assertClean("the dispatcher is the single legal owner", postCommitRunnerReferences(POST_COMMIT_DISPATCHER_PATH, runnerSnippet))
        for (otherPath in listOf(
            "$ROOT/ui/screens/addexpense/AddExpenseViewModel.kt",
            "$ROOT/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt",
            "$ROOT/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt",
            "$ROOT/domain/sideeffect/PostCommitActionRunnerImpl.kt",
            "$ROOT/data/repository/ManualExpenseRepository.kt",
            // Prefix boundary: a sibling directory sharing the name stem is not an installment package.
            "$ROOT/ui/screens/installmentsettings/SomeViewModel.kt"
        )) {
            assertClean("non-installment path $otherPath", postCommitRunnerReferences(otherPath, runnerSnippet))
        }

        val uiPath = installmentPaths.first()
        assertClean("line comment", postCommitRunnerReferences(uiPath, "// never PostCommitActionRunner here\nfun f() {}"))
        assertClean("block comment", postCommitRunnerReferences(uiPath, "/* PostCommitActionRunner */ fun f() {}"))
        assertClean("KDoc link", postCommitRunnerReferences(uiPath, "/** runs via [PostCommitActionRunner] */ class X"))
        assertClean("string literal", postCommitRunnerReferences(uiPath, "val s = \"PostCommitActionRunner\""))
        assertClean(
            "the dispatcher itself is the legal collaborator",
            postCommitRunnerReferences(uiPath, "class X(private val dispatcher: InstallmentPostCommitDispatcher)")
        )

        // Owner item 59c: the public post-commit wrappers are flagged the same way as the runner.
        val wrapperViolating = listOf(
            "evidence service import" to
                "import com.yourname.expensetracker.domain.sideeffect.PostCommitSideEffectEvidenceService\nclass X",
            "evidence service ctor param" to
                "class X(private val evidence: PostCommitSideEffectEvidenceService)",
            "evidence service lookalike" to
                "class X(private val evidence: PostCommitSideEffectEvidenceServiceImpl)",
            "runWithEvidence call" to
                "suspend fun f(b: Any) { evidence.runWithEvidence(b) }",
            "runBestEffortWithEvidence call" to
                "suspend fun f(b: Any) { evidence.runBestEffortWithEvidence(b, 1) }",
            "runWithEvidence callable reference" to
                "val ref = evidence::runWithEvidence",
            "transaction dispatcher ctor param" to
                "class X(private val d: TransactionSideEffectDispatcher)",
            "transaction dispatcher fully qualified" to
                "class X(private val d: com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectDispatcher)",
            "receipt dispatcher ctor param" to
                "class X(private val d: ReceiptSideEffectDispatcher?)",
            "receipt dispatcher class reference" to
                "val k = ReceiptSideEffectDispatcher::class",
            "wrapper beside a comment mention" to
                "// mentions TransactionSideEffectDispatcher\nclass X(private val d: TransactionSideEffectDispatcher)"
        )
        for (path in installmentPaths) {
            for ((label, snippet) in wrapperViolating) {
                assertFlagged("$path :: $label", postCommitRunnerReferences(path, snippet), rule)
            }
        }
        val wrapperSnippet = wrapperViolating.joinToString("\n") { it.second }
        assertClean("the dispatcher may use any post-commit wrapper (single owner)", postCommitRunnerReferences(POST_COMMIT_DISPATCHER_PATH, wrapperSnippet))
        for (otherPath in listOf(
            "$ROOT/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt",
            "$ROOT/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt",
            "$ROOT/data/repository/ReceiptRepository.kt",
            "$ROOT/domain/sideeffect/PostCommitSideEffectEvidenceService.kt",
            "$ROOT/ui/screens/installmentsettings/SomeViewModel.kt"
        )) {
            assertClean("non-installment path $otherPath", postCommitRunnerReferences(otherPath, wrapperSnippet))
        }
        for (benign in listOf(
            "line comment" to "// never TransactionSideEffectDispatcher or runWithEvidence here\nfun f() {}",
            "block comment" to "/* ReceiptSideEffectDispatcher, PostCommitSideEffectEvidenceService */ fun f() {}",
            "KDoc link" to "/** via [TransactionSideEffectDispatcher.runBestEffortWithEvidence] */ class X",
            "string literal" to "val s = \"PostCommitSideEffectEvidenceService.runWithEvidence\"",
            "planner is not a wrapper" to "class X(private val p: TransactionSideEffectPlanner, q: ReceiptSideEffectPlanner)",
            "lookalike member name" to "fun f() { rerunWithEvidenceFlag() }"
        )) {
            assertClean("wrapper rule benign: ${benign.first}", postCommitRunnerReferences(uiPath, benign.second))
        }
    }

    @Test
    fun `S10b-1 F3 non-vacuity - the dispatcher exists in the scan and itself references PostCommitActionRunner`() {
        val dispatcher = requireScan().singleOrNull { it.rel == POST_COMMIT_DISPATCHER_PATH }
        assertTrue(
            "InstallmentPostCommitDispatcher must be in the scan at $POST_COMMIT_DISPATCHER_PATH " +
                "(else the single-owner rule passes vacuously after a rename)",
            dispatcher != null
        )
        assertTrue(
            "the dispatcher must reference PostCommitActionRunner in code (imports/params, not comments) - " +
                "otherwise the single-owner rule has no owner",
            Regex("""\bPostCommitActionRunner\b""").containsMatchIn(code(dispatcher!!.raw))
        )
        // The exemption must be what saves the dispatcher: the same matcher with the dispatcher at a
        // non-exempt sibling path flags the real file text.
        assertFlagged(
            "real dispatcher text at a non-exempt sibling path",
            postCommitRunnerReferences("$ROOT/domain/installment/lifecycle/Renamed.kt", dispatcher.raw),
            "POST_COMMIT_RUNNER_OUTSIDE_DISPATCHER"
        )
        assertClean("real dispatcher at its pinned path", postCommitRunnerReferences(dispatcher.rel, dispatcher.raw))
        // Each scope prefix must still match at least one scanned file (else a directory rename makes it vacuous).
        for (p in POST_COMMIT_RUNNER_SCOPE_PREFIXES) {
            assertTrue("scope prefix $p matches no scanned file", requireScan().any { it.rel.startsWith(p) })
        }
    }

    @Test
    fun `owner 59c non-vacuity - each post-commit wrapper and its service calls exist in the scan and the extended matcher sees them`() {
        val files = requireScan()
        assertEquals(
            "a new post-commit wrapper type needs an entry here and in the rule (owner item 59c)",
            setOf("PostCommitSideEffectEvidenceService", "TransactionSideEffectDispatcher", "ReceiptSideEffectDispatcher"),
            POST_COMMIT_WRAPPER_TYPES.toSet()
        )
        val declarationPaths = mapOf(
            "PostCommitSideEffectEvidenceService" to "$ROOT/domain/sideeffect/PostCommitSideEffectEvidenceService.kt",
            "TransactionSideEffectDispatcher" to "$ROOT/domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt",
            "ReceiptSideEffectDispatcher" to "$ROOT/domain/receipt/lifecycle/ReceiptSideEffectDispatcher.kt"
        )
        for (type in POST_COMMIT_WRAPPER_TYPES) {
            val path = declarationPaths.getValue(type)
            val file = files.singleOrNull { it.rel == path }
            assertTrue("wrapper $type must be in the scan at $path (else the rule passes vacuously after a rename)", file != null)
            assertTrue(
                "wrapper $type must be declared as a class in $path",
                Regex("""\bclass\s+${Regex.escape(type)}\b""").containsMatchIn(code(file!!.raw))
            )
            // The real declaration text, planted into an installment package, is flagged by the extended matcher.
            assertFlagged(
                "real $type text planted at a non-exempt installment path",
                postCommitRunnerReferences("$ROOT/domain/installment/lifecycle/Renamed.kt", file.raw),
                "POST_COMMIT_RUNNER_OUTSIDE_DISPATCHER"
            )
        }
        val evidence = files.single { it.rel == declarationPaths.getValue("PostCommitSideEffectEvidenceService") }
        val evidenceCode = code(evidence.raw)
        for (call in listOf("runWithEvidence", "runBestEffortWithEvidence")) {
            assertTrue(
                "the evidence service must declare 'fun $call(' (else the call-name arm is stale)",
                Regex("""\bfun\s+$call\s*\(""").containsMatchIn(evidenceCode)
            )
        }
        // Wrapper names are absent from the three installment prefixes today (grep at 59c time); the dispatcher
        // is the only file there that may name post-commit machinery at all.
        val wrapperName = Regex("""\b(?:${POST_COMMIT_WRAPPER_TYPES.joinToString("|")})\w*|\brunWithEvidence\b|\brunBestEffortWithEvidence\b""")
        val inScope = files.filter { f -> POST_COMMIT_RUNNER_SCOPE_PREFIXES.any { f.rel.startsWith(it) } }
        assertTrue("installment scope must contain scanned files", inScope.isNotEmpty())
        val offenders = inScope.filter { it.rel != POST_COMMIT_DISPATCHER_PATH && wrapperName.containsMatchIn(code(it.raw)) }
        assertTrue("wrapper references outside the dispatcher: ${offenders.map { it.rel }}", offenders.isEmpty())
    }

    // ---------------------------------------------------------------- owner 59b Home weather consumer pin

    /**
     * Owner approvals items 53c and 59b: `HomeInstallmentWeatherSource` may be referenced in production only by
     * [HOME_VIEW_MODEL_PATH] and its own file [HOME_WEATHER_SOURCE_PATH] (exact relative paths, never basenames).
     * No DI module binds it (the class has an `@Inject` constructor), so there is no third exemption. Scope is ALL
     * of app/src/main, not only the plan prefixes. Matched on sanitized code (comments and string bodies blanked),
     * so imports, ctor params, fully qualified names and `::` references are caught while a comment, KDoc or log
     * string mention is not; `HomeInstallmentWeatherSource*` lookalikes are caught too.
     *
     * Known limits: a typealias declared in an exempt file; a consumer that obtains the source's flow through a
     * differently named wrapper (the wrapper itself would reference the type and be flagged).
     */
    private fun homeWeatherSourceReferences(rel: String, raw: String): List<Hit> {
        if (rel == HOME_VIEW_MODEL_PATH || rel == HOME_WEATHER_SOURCE_PATH) return emptyList()
        return lineHits(
            "HOME_INSTALLMENT_WEATHER_OUTSIDE_HOME",
            Regex("""\bHomeInstallmentWeatherSource\w*"""),
            code(raw)
        )
    }

    @Test
    fun `owner 59b HomeInstallmentWeatherSource is referenced only by HomeViewModel and its own file`() {
        assertNoViolations(
            "HomeInstallmentWeatherSource referenced outside $HOME_VIEW_MODEL_PATH / $HOME_WEATHER_SOURCE_PATH " +
                "(HOME_INSTALLMENT_WEATHER_OUTSIDE_HOME, owner items 53c and 59b)",
            this::homeWeatherSourceReferences
        )
    }

    @Test
    fun `self-check owner 59b Home weather detector flags planted references outside Home and ignores comments strings and the two exempt files`() {
        val rule = "HOME_INSTALLMENT_WEATHER_OUTSIDE_HOME"
        val typeFqn = "com.yourname.expensetracker.data.repository.HomeInstallmentWeatherSource"
        val violating = listOf(
            "import" to "import $typeFqn\nclass X",
            "constructor param" to "class X(private val source: HomeInstallmentWeatherSource)",
            "nullable constructor param" to "class X(private val source: HomeInstallmentWeatherSource?)",
            "fully qualified type" to "class X(private val source: $typeFqn)",
            "class reference" to "val k = HomeInstallmentWeatherSource::class",
            "callable reference" to "val ref = HomeInstallmentWeatherSource::observe",
            "Impl lookalike" to "class X(private val source: HomeInstallmentWeatherSourceImpl)",
            "reference beside a comment mention" to "// mentions HomeInstallmentWeatherSource\nclass X(private val s: HomeInstallmentWeatherSource)"
        )
        val outsiders = listOf(
            "$ROOT/data/ai/AiBriefingContextBuilder.kt",
            "$ROOT/domain/ai/usecase/BuildDashboardBriefingUseCase.kt",
            "$ROOT/data/repository/DashboardContractsAdapter.kt",
            "$ROOT/ui/screens/assistant/AssistantViewModel.kt",
            "$ROOT/data/repository/FinancialWeatherRepository.kt",
            "$ROOT/di/RepositoryModule.kt",
            // Siblings and same-basename files are NOT the exempt files.
            "$ROOT/ui/screens/home/HomeScreenState.kt",
            "$ROOT/ui/screens/home/other/HomeViewModel.kt",
            "$ROOT/ui/screens/homebase/HomeViewModel.kt",
            "$ROOT/data/repository/other/HomeInstallmentWeatherSource.kt"
        )
        for (path in outsiders) {
            for ((label, snippet) in violating) {
                assertFlagged("$path :: $label", homeWeatherSourceReferences(path, snippet), rule)
            }
        }
        val snippet = "import $typeFqn\nclass H(private val homeInstallmentWeatherSource: HomeInstallmentWeatherSource)"
        assertClean("HomeViewModel is the single legal consumer", homeWeatherSourceReferences(HOME_VIEW_MODEL_PATH, snippet))
        assertClean("the source's own file", homeWeatherSourceReferences(HOME_WEATHER_SOURCE_PATH, snippet))

        val aiPath = outsiders.first()
        assertClean("line comment", homeWeatherSourceReferences(aiPath, "// never HomeInstallmentWeatherSource here\nfun f() {}"))
        assertClean("block comment", homeWeatherSourceReferences(aiPath, "/* HomeInstallmentWeatherSource */ fun f() {}"))
        assertClean("KDoc link", homeWeatherSourceReferences(aiPath, "/** merged via [HomeInstallmentWeatherSource] */ class X"))
        assertClean("string literal", homeWeatherSourceReferences(aiPath, "val s = \"HomeInstallmentWeatherSource\""))
        assertClean(
            "an unrelated Home type",
            homeWeatherSourceReferences(aiPath, "class X(private val m: HomeWeatherMerge, p: InstallmentProjectionPort)")
        )
    }

    @Test
    fun `owner 59b non-vacuity - HomeViewModel exists in the scan and really references HomeInstallmentWeatherSource`() {
        val files = requireScan()
        val home = files.singleOrNull { it.rel == HOME_VIEW_MODEL_PATH }
        assertTrue("HomeViewModel must be in the scan at $HOME_VIEW_MODEL_PATH (else the pin passes vacuously after a rename)", home != null)
        assertTrue(
            "HomeViewModel must reference HomeInstallmentWeatherSource in code (import/ctor param, not comments) - otherwise the pin has no consumer",
            Regex("""\bHomeInstallmentWeatherSource\b""").containsMatchIn(code(home!!.raw))
        )
        val source = files.singleOrNull { it.rel == HOME_WEATHER_SOURCE_PATH }
        assertTrue("HomeInstallmentWeatherSource must be in the scan at $HOME_WEATHER_SOURCE_PATH", source != null)
        assertTrue(
            "HomeInstallmentWeatherSource must be declared as a class in $HOME_WEATHER_SOURCE_PATH",
            Regex("""\bclass\s+HomeInstallmentWeatherSource\b""").containsMatchIn(code(source!!.raw))
        )
        // The exemption must be what saves HomeViewModel and the source: both texts at a non-exempt sibling path are flagged.
        assertFlagged(
            "real HomeViewModel text at a non-exempt sibling path",
            homeWeatherSourceReferences("$ROOT/ui/screens/home/Renamed.kt", home.raw),
            "HOME_INSTALLMENT_WEATHER_OUTSIDE_HOME"
        )
        assertFlagged(
            "real source text at a non-exempt sibling path",
            homeWeatherSourceReferences("$ROOT/data/repository/Renamed.kt", source.raw),
            "HOME_INSTALLMENT_WEATHER_OUTSIDE_HOME"
        )
        assertClean("real HomeViewModel at its pinned path", homeWeatherSourceReferences(home.rel, home.raw))
        assertClean("real source at its pinned path", homeWeatherSourceReferences(source.rel, source.raw))
    }

    private companion object {
        const val ROOT = "com/yourname/expensetracker"

        /** The single legal owner of PostCommitActionRunner among the installment packages (exact path). */
        const val POST_COMMIT_DISPATCHER_PATH =
            "$ROOT/domain/installment/lifecycle/InstallmentPostCommitDispatcher.kt"

        /** Directory prefixes in scope of the single-owner rule (S10b-1 F3). */
        val POST_COMMIT_RUNNER_SCOPE_PREFIXES = listOf(
            "$ROOT/domain/installment/",
            "$ROOT/service/installment/",
            "$ROOT/ui/screens/installments/"
        )

        /**
         * Public post-commit wrappers that run a batch without naming PostCommitActionRunner (owner item 59c).
         * Only the dispatcher may reference them under [POST_COMMIT_RUNNER_SCOPE_PREFIXES].
         */
        val POST_COMMIT_WRAPPER_TYPES = listOf(
            "PostCommitSideEffectEvidenceService",
            "TransactionSideEffectDispatcher",
            "ReceiptSideEffectDispatcher"
        )

        /** Exact paths for the Home weather consumer pin (owner item 59b). */
        const val HOME_VIEW_MODEL_PATH = "$ROOT/ui/screens/home/HomeViewModel.kt"
        const val HOME_WEATHER_SOURCE_PATH = "$ROOT/data/repository/HomeInstallmentWeatherSource.kt"

        const val FACTS_READER_IMPL_PATH ="$ROOT/data/repository/InstallmentExpenseFactsReaderImpl.kt"

        const val READ_REPOSITORY_IMPL_PATH = "$ROOT/data/repository/InstallmentPlanReadRepositoryImpl.kt"

        const val PROJECTION_PORT_IMPL_PATH = "$ROOT/data/repository/InstallmentProjectionPortImpl.kt"

        const val CANDIDATE_PROBE_IMPL_PATH = "$ROOT/data/repository/InstallmentCandidateChargeProbeImpl.kt"

        const val ENTRY_LINK_READER_IMPL_PATH = "$ROOT/data/repository/InstallmentEntryLinkReaderImpl.kt"

        /**
         * Exact paths of the read-only data-layer implementations (never basenames). The candidate
         * charge probe (S7a-1) and the entry link reader (owner approval item 36) are read-only seams too.
         */
        val READ_ONLY_IMPL_PATHS = listOf(
            FACTS_READER_IMPL_PATH,
            READ_REPOSITORY_IMPL_PATH,
            PROJECTION_PORT_IMPL_PATH,
            CANDIDATE_PROBE_IMPL_PATH,
            ENTRY_LINK_READER_IMPL_PATH
        )

        const val READMODEL_PREFIX = "$ROOT/domain/installment/readmodel/"

        /** Exact paths of the domain ports; the readmodel directory is covered by [READMODEL_PREFIX]. */
        val DOMAIN_PORT_PATHS = listOf(
            "$ROOT/domain/installment/lifecycle/InstallmentExpenseFactsReader.kt",
            "$ROOT/domain/installment/InstallmentPlanReadRepository.kt",
            "$ROOT/domain/installment/projection/InstallmentProjectionPort.kt",
            "$ROOT/domain/installment/lifecycle/InstallmentCandidateChargeProbe.kt",
            "$ROOT/domain/installment/lifecycle/InstallmentEntryLinkReader.kt"
        )

        const val COORDINATOR_PATH =
            "$ROOT/domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt"

        const val TLC_PATH =
            "$ROOT/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt"

        const val DAO_PACKAGE_PREFIX = "$ROOT/data/database/dao/"

        /**
         * The only TransactionLifecycleCoordinator operations plan code may call (owner item 36a, S6c-F.1):
         * the DbOnly creation of a charge expense and the DbOnly spending-exclusion write. A third op needs
         * explicit owner approval; the non-vacuity test pins this set.
         */
        val PLAN_ALLOWED_TLC_OPS = setOf("createExpenseDbOnlyV2", "setSpendingExclusionDbOnly")

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
