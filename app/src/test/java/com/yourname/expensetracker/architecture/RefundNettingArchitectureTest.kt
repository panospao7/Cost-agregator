package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Architecture fences for refund netting (DR-05): domain spec INV-2 (single writer) and INV-16
 * (explicit user action only, single entry point).
 *
 * - INV-2: only `TransactionLifecycleCoordinator` writes `expense_refund_links` through
 *   `ExpenseRefundLinkDao.insert` / `deleteById` and writes `expenses.refundNettedAmount` through
 *   `ExpenseDao.updateRefundNettedAmount`.
 * - INV-16: `TransactionLifecycleCoordinator.linkRefund` / `unlinkRefund` are called only by
 *   `RefundLinkUseCase`, and no worker file references that use case.
 *
 * Matching runs over sanitized text ([SourceTextSanitizer]), so a comment or string mention can
 * neither fake nor hide a violation. The scan is fail closed (source root must exist, enough Kotlin
 * files must be seen, and the allowed owners must really contain the calls), and each rule has
 * negative self-checks on planted snippets. Failure messages carry path, rule id, line number and the
 * matched line only.
 */
class RefundNettingArchitectureTest {

    private data class Hit(val rule: String, val line: Int, val snippet: String)

    private class ScannedFile(val rel: String, val raw: String)

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
            files += ScannedFile(
                FailClosedScan.relativePath(sourceRoot, file).removePrefix("com/yourname/expensetracker/"),
                text
            )
        }
        files
    }

    private fun requireScan(): List<ScannedFile> {
        val files = scannedFiles
        assertTrue(
            "refund netting architecture guard scanned only ${files.size} Kotlin files " +
                "(need more than $MIN_SCANNED_FILES) - the scan root is wrong or truncated",
            files.size > MIN_SCANNED_FILES
        )
        return files
    }

    private fun assertNoViolations(title: String, matcher: (String, String) -> List<Hit>) {
        val errors = requireScan().flatMap { file ->
            matcher(file.rel, file.raw).map { "${file.rel} [${it.rule}] line ${it.line}: ${it.snippet}" }
        }
        val shown = errors.take(MAX_REPORTED).joinToString("\n")
        assertTrue("$title - ${errors.size} violation(s):\n$shown", errors.isEmpty())
    }

    private fun code(raw: String): String = SourceTextSanitizer.stripCommentsAndStringBodies(raw)

    private fun findHits(rule: String, regex: Regex, raw: String): List<Hit> {
        val clean = code(raw)
        val lines = clean.lines()
        return regex.findAll(clean).map { match ->
            val line = clean.substring(0, match.range.first).count { it == '\n' } + 1
            Hit(rule, line, lines.getOrElse(line - 1) { "" }.trim().take(MAX_SNIPPET))
        }.toList()
    }

    // ------------------------------------------------------------------ INV-2 matchers

    /** insert / deleteById on any value named like a refund-link DAO, or on the accessor call. */
    private fun linkDaoWriteHits(rel: String, raw: String): List<Hit> {
        if (rel == TLC_REL) return emptyList()
        return findHits("INV-2-link-dao-write", LINK_DAO_WRITE, raw)
    }

    /** `ExpenseDao.updateRefundNettedAmount` outside the coordinator (and the DAO declaration). */
    private fun nettedColumnWriteHits(rel: String, raw: String): List<Hit> {
        if (rel == TLC_REL || rel == EXPENSE_DAO_REL) return emptyList()
        return findHits("INV-2-netted-column-write", NETTED_WRITE, raw)
    }

    /** Aliasing the link DAO (`= ...expenseRefundLinkDao()`) hides later writes from the name rule. */
    private fun linkDaoAliasHits(rel: String, raw: String): List<Hit> {
        if (rel == TLC_REL || rel in LINK_DAO_PROVIDER_RELS) return emptyList()
        return findHits("INV-2-link-dao-alias", LINK_DAO_ALIAS, raw)
    }

    // ------------------------------------------------------------------ INV-16 matchers

    private fun linkEntryPointHits(rel: String, raw: String): List<Hit> {
        if (rel == USE_CASE_REL) return emptyList()
        return findHits("INV-16-link-call", LINK_CALL, raw)
    }

    private fun workerReferenceHits(rel: String, raw: String): List<Hit> {
        val isWorker = rel.startsWith("worker/") || rel.substringAfterLast('/').endsWith("Worker.kt")
        if (!isWorker) return emptyList()
        return findHits("INV-16-worker-reference", USE_CASE_REFERENCE, raw)
    }

    // ------------------------------------------------------------------ live scans

    @Test
    fun `INV-2 only the lifecycle coordinator inserts or deletes refund link rows`() {
        assertNoViolations("INV-2: expenseRefundLinkDao insert/deleteById outside TLC", ::linkDaoWriteHits)
    }

    @Test
    fun `INV-2 only the lifecycle coordinator writes expenses refundNettedAmount`() {
        assertNoViolations("INV-2: updateRefundNettedAmount outside TLC", ::nettedColumnWriteHits)
    }

    @Test
    fun `INV-2 nobody outside the coordinator aliases the refund link dao`() {
        assertNoViolations("INV-2: refund link dao alias outside TLC", ::linkDaoAliasHits)
    }

    @Test
    fun `INV-2 scan is not vacuous - the coordinator really owns the three writes`() {
        val tlc = requireScan().firstOrNull { it.rel == TLC_REL }
            ?: throw AssertionError("scanned file missing: $TLC_REL - update the guard if it was moved")
        val clean = code(tlc.raw)
        assertTrue("TLC must insert link rows", Regex("""expenseRefundLinkDao\(\)\s*\.\s*insert\s*\(""").containsMatchIn(clean))
        assertTrue("TLC must delete link rows", Regex("""expenseRefundLinkDao\(\)\s*\.\s*deleteById\s*\(""").containsMatchIn(clean))
        assertTrue("TLC must update the netted column", NETTED_WRITE.containsMatchIn(clean))
        val dao = requireScan().firstOrNull { it.rel == EXPENSE_DAO_REL }
            ?: throw AssertionError("scanned file missing: $EXPENSE_DAO_REL")
        assertTrue(
            "ExpenseDao must declare updateRefundNettedAmount",
            Regex("""fun\s+updateRefundNettedAmount\s*\(""").containsMatchIn(code(dao.raw))
        )
    }

    @Test
    fun `INV-16 only RefundLinkUseCase calls linkRefund and unlinkRefund`() {
        assertNoViolations("INV-16: linkRefund/unlinkRefund call outside RefundLinkUseCase", ::linkEntryPointHits)
    }

    @Test
    fun `INV-16 the use case really is the caller - scan is not vacuous`() {
        val useCase = requireScan().firstOrNull { it.rel == USE_CASE_REL }
            ?: throw AssertionError("scanned file missing: $USE_CASE_REL - update the guard if it was moved")
        val clean = code(useCase.raw)
        assertTrue(Regex("""\.\s*linkRefund\s*\(""").containsMatchIn(clean))
        assertTrue(Regex("""\.\s*unlinkRefund\s*\(""").containsMatchIn(clean))
    }

    @Test
    fun `INV-16 no worker file references RefundLinkUseCase`() {
        assertNoViolations("INV-16: worker references RefundLinkUseCase", ::workerReferenceHits)
    }

    // ------------------------------------------------------------------ negative self-checks

    @Test
    fun `negative fixture - link dao insert and deleteById outside the coordinator are caught`() {
        val planted = """
            class Rogue(private val database: AppDatabase, private val refundLinkDao: ExpenseRefundLinkDao) {
                suspend fun a() { database.expenseRefundLinkDao().insert(link) }
                suspend fun b() { refundLinkDao.deleteById(1L) }
                suspend fun c() { refundLinkDao?.insert(link) }
            }
        """.trimIndent()
        assertEquals(3, linkDaoWriteHits("data/repository/Rogue.kt", planted).size)
    }

    @Test
    fun `negative fixture - the coordinator and read-only calls are not flagged`() {
        val coordinator = "database.expenseRefundLinkDao().insert(link)\ndatabase.expenseRefundLinkDao().deleteById(1L)"
        assertTrue(linkDaoWriteHits(TLC_REL, coordinator).isEmpty())
        val reads = "refundLinkDao.getForExpense(1L)\ndatabase.expenseRefundLinkDao().countAll()"
        assertTrue(linkDaoWriteHits("domain/transaction/RefundLinkUseCase.kt", reads).isEmpty())
    }

    @Test
    fun `negative fixture - comment and string mentions neither fake nor hide a link dao write`() {
        val commentOnly = "// database.expenseRefundLinkDao().insert(link)\nval s = \"refundLinkDao.deleteById(1)\""
        assertTrue(linkDaoWriteHits("data/repository/Doc.kt", commentOnly).isEmpty())
    }

    @Test
    fun `negative fixture - netted column write outside the coordinator is caught`() {
        val planted = "suspend fun f() { expenseDao.updateRefundNettedAmount(1L, 40.0) }"
        assertEquals(1, nettedColumnWriteHits("data/repository/ExpenseRepository.kt", planted).size)
        assertTrue(nettedColumnWriteHits(TLC_REL, planted).isEmpty())
        assertTrue(nettedColumnWriteHits(EXPENSE_DAO_REL, "suspend fun updateRefundNettedAmount(id: Long, a: Double)").isEmpty())
    }

    @Test
    fun `negative fixture - aliasing the link dao outside the coordinator is caught`() {
        val planted = "val dao = database.expenseRefundLinkDao()\ndao.insert(link)"
        assertEquals(1, linkDaoAliasHits("data/repository/Rogue.kt", planted).size)
        assertTrue(linkDaoAliasHits("di/DaoModule.kt", "fun p(database: AppDatabase): X = database.expenseRefundLinkDao()").isEmpty())
        assertTrue(linkDaoAliasHits("data/repository/Reader.kt", "val n = database.expenseRefundLinkDao().countAll()").isEmpty())
    }

    @Test
    fun `negative fixture - link entry point calls outside the use case are caught`() {
        val planted = """
            suspend fun a() { coordinator.linkRefund(1L, 2L) }
            suspend fun b() { transactionLifecycleCoordinator.unlinkRefund(1L) }
            val ref = coordinator::linkRefund
        """.trimIndent()
        assertEquals(3, linkEntryPointHits("ui/screens/transactions/TransactionsViewModel.kt", planted).size)
        assertEquals(3, linkEntryPointHits("worker/SomeWorker.kt", planted).size)
        assertTrue(linkEntryPointHits(USE_CASE_REL, planted).isEmpty())
    }

    @Test
    fun `negative fixture - use case link and unlink calls and comments are not flagged`() {
        val allowed = "val r = refundLinkUseCase.link(1L, 2L)\nval u = refundLinkUseCase.unlink(1L)\n// coordinator.linkRefund(1L, 2L)"
        assertTrue(linkEntryPointHits("ui/screens/transactions/TransactionsViewModel.kt", allowed).isEmpty())
    }

    @Test
    fun `negative fixture - a worker referencing the use case is caught and a viewmodel is not`() {
        val planted = "class W(private val refundLinkUseCase: RefundLinkUseCase)"
        assertEquals(1, workerReferenceHits("worker/NotificationIntakeWorker.kt", planted).size)
        assertEquals(1, workerReferenceHits("service/SomeWorker.kt", planted).size)
        assertTrue(workerReferenceHits("ui/screens/transactions/TransactionsViewModel.kt", planted).isEmpty())
    }

    private companion object {
        const val MIN_SCANNED_FILES = 200
        const val MAX_REPORTED = 20
        const val MAX_SNIPPET = 160

        const val TLC_REL = "domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt"
        const val USE_CASE_REL = "domain/transaction/RefundLinkUseCase.kt"
        const val EXPENSE_DAO_REL = "data/database/dao/ExpenseDao.kt"
        val LINK_DAO_PROVIDER_RELS = setOf("data/database/AppDatabase.kt", "di/DaoModule.kt")

        val LINK_DAO_WRITE =
            Regex("""\w*[rR]efundLinkDao\s*(?:\(\s*\))?\s*\??\.\s*(?:insert|deleteById)\s*\(""")
        val NETTED_WRITE = Regex("""\bupdateRefundNettedAmount\s*\(""")
        val LINK_DAO_ALIAS = Regex("""=\s*[\w.?]*expenseRefundLinkDao\s*\(\s*\)(?!\s*\??\.)""")
        val LINK_CALL = Regex("""(?:\.\s*|::\s*)(?:linkRefund|unlinkRefund)\b""")
        val USE_CASE_REFERENCE = Regex("""\bRefundLinkUseCase\b""")
    }
}
