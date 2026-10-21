package com.yourname.expensetracker.contracts

import com.yourname.expensetracker.architecture.FailClosedScan
import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFailsWith

/**
 * Architecture contract: post-commit side-effect dispatch must NEVER happen inside a database
 * transaction. Side effects (PostCommitActionRunner `.run(` / `runBestEffortAfterCommit(` /
 * `dispatchOnCreated|Updated|Deleted|BulkUpdated(`) must run AFTER the enclosing
 * `withTransaction { }` / `runInTransaction(...) { }` has committed.
 *
 * Scan: every `.kt` file under `app/src/main`, on comment/string-sanitized code. A transaction
 * region is the whole `withTransaction`/`runInTransaction` call (argument list plus trailing lambda),
 * so a dispatch passed as an argument or buried in a nested lambda is still "inside". Declarations
 * (`fun ...runBestEffortAfterCommit(`, `fun dispatchOnX(`) are not call sites.
 *
 * Non-vacuity: the production scan must have seen at least one transaction block AND at least one
 * post-commit call site of each live kind, otherwise the guard fails instead of passing on nothing.
 */
class SideEffectContractTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val mainSrc: File by lazy {
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

    @Test
    fun `no post-commit side-effect dispatch inside a database transaction in production`() {
        val result = PostCommitInTransactionScan.scanTree(mainSrc)
        assertTrue(
            "Post-commit side-effect dispatch found inside a transaction block " +
                "(must run after commit):\n${result.violations.joinToString("\n")}",
            result.violations.isEmpty()
        )
        // Non-vacuity: a guard that saw no transaction or no dispatch call proves nothing.
        PostCommitInTransactionScan.requireNonVacuous(result)
    }

    // ---- self-tests: detector controls -------------------------------------------------------

    private fun violationsIn(source: String) = PostCommitInTransactionScan.scanSource("Fixture.kt", source).violations

    @Test
    fun `dispatch inside withTransaction is flagged for every dispatch kind`() {
        listOf(
            "runner.run(batch)",
            "postCommitActionRunner.run(outcome.transactionActions)",
            "runner.runBestEffortAfterCommit(batch)",
            "postCommitActionRunner.runBestEffortAfterCommit(actions)",
            "dispatchOnCreated(id)",
            "dispatcher.dispatchOnUpdated(id)",
            "dispatchOnDeleted(id)",
            "dispatchOnBulkUpdated(source, 3)"
        ).forEach { call ->
            val source = "suspend fun save() {\n  database.withTransaction {\n    dao.insert(x)\n    $call\n  }\n}"
            val result = violationsIn(source)
            assertEquals("must flag: $call -> $result", 1, result.size)
            assertTrue(result.single(), result.single().contains("Fixture.kt:4"))
        }
    }

    @Test
    fun `dispatch inside runInTransaction with arguments is flagged`() {
        val source = """
            suspend fun save() {
                transactionRunner.runInTransaction(
                    correlationId = id,
                    operationId = "op"
                ) { context ->
                    dao.insert(x)
                    runner.runBestEffortAfterCommit(batch)
                }
            }
        """.trimIndent()
        assertEquals(1, violationsIn(source).size)
    }

    @Test
    fun `dispatch nested in a lambda or branch inside the transaction is flagged`() {
        val source = """
            suspend fun save() {
                database.withTransaction {
                    if (ok) {
                        items.forEach { runner.run(batch) }
                    }
                }
            }
        """.trimIndent()
        assertEquals(1, violationsIn(source).size)
    }

    @Test
    fun `dispatch passed as a transaction argument is flagged`() {
        val source = "suspend fun save() { transactionRunner.runInTransaction(sideEffect = runner.run(batch)) { dao.insert(x) } }"
        assertEquals(1, violationsIn(source).size)
    }

    @Test
    fun `dispatch after the transaction block closes is not flagged`() {
        val source = """
            suspend fun save() {
                val outcome = database.withTransaction {
                    dao.insert(x)
                    Outcome(batch)
                }
                runner.runBestEffortAfterCommit(outcome.batch)
                database.withTransaction { dao.update(y) }
                postCommitActionRunner.run(other)
            }
        """.trimIndent()
        assertEquals(emptyList<String>(), violationsIn(source))
        val scan = PostCommitInTransactionScan.scanSource("Fixture.kt", source)
        assertEquals(2, scan.txBlocks)
        assertEquals(2, scan.postCommitCalls)
    }

    @Test
    fun `comments strings declarations and return labels are not call sites or blocks`() {
        val source = """
            suspend fun PostCommitActionRunner.runBestEffortAfterCommit(batch: Batch) { run(batch) }
            suspend fun dispatchOnCreated(id: Long) { runner.run(batch) }
            suspend fun save() {
                database.withTransaction {
                    // runner.run(batch)
                    val doc = "runner.runBestEffortAfterCommit(batch)"
                    /* dispatchOnCreated(1) */
                    return@withTransaction
                }
            }
        """.trimIndent()
        // runner.run(batch) in the dispatchOnCreated declaration body is a real (non-transaction) call.
        val scan = PostCommitInTransactionScan.scanSource("Fixture.kt", source)
        assertEquals(emptyList<String>(), scan.violations)
        assertEquals(1, scan.txBlocks)
        assertEquals(1, scan.postCommitCalls)
    }

    @Test
    fun `bare references to transaction names are not blocks`() {
        val source = """
            import androidx.room.withTransaction
            suspend fun f() {
                val x = db.withTransaction
                runner.run(batch)
            }
        """.trimIndent()
        val scan = PostCommitInTransactionScan.scanSource("Fixture.kt", source)
        assertEquals(0, scan.txBlocks)
        assertEquals(emptyList<String>(), scan.violations)
    }

    // ---- self-tests: fail-closed branches ---------------------------------------------------

    @Test
    fun `an unbalanced transaction block fails closed instead of being skipped`() {
        val error = assertFailsWith<AssertionError> {
            PostCommitInTransactionScan.scanSource("Broken.kt", "suspend fun f() { database.withTransaction { runner.run(b) ")
        }
        assertTrue(error.message, error.message.orEmpty().contains("Broken.kt"))
    }

    @Test
    fun `a scan that saw no transaction or no dispatch is rejected as vacuous`() {
        val noTx = PostCommitInTransactionScan.scanSource("A.kt", "suspend fun f() { runner.run(b) }")
        val noCall = PostCommitInTransactionScan.scanSource("B.kt", "suspend fun f() { db.withTransaction { x() } }")
        assertTrue(
            assertFailsWith<AssertionError> { PostCommitInTransactionScan.requireNonVacuous(noTx) }
                .message.orEmpty().contains("transaction")
        )
        assertTrue(
            assertFailsWith<AssertionError> { PostCommitInTransactionScan.requireNonVacuous(noCall) }
                .message.orEmpty().contains("post-commit")
        )
        // Both kinds seen -> accepted.
        val both = PostCommitInTransactionScan.scanSource(
            "C.kt",
            "suspend fun f() { db.withTransaction { x() }\n runner.run(b)\n runner.runBestEffortAfterCommit(b) }"
        )
        PostCommitInTransactionScan.requireNonVacuous(both)
    }

    @Test
    fun `a missing or empty scan root fails closed`() {
        assertFailsWith<AssertionError> { PostCommitInTransactionScan.scanTree(File(temp.root, "missing")) }
        assertFailsWith<AssertionError> { PostCommitInTransactionScan.scanTree(temp.root) }
    }

    @Test
    fun `scanTree reports the relative file and line of a violation in a fixture tree`() {
        File(temp.newFolder("pkg"), "Bad.kt").writeText(
            "class Bad {\n  suspend fun f() {\n    database.withTransaction {\n      runner.run(batch)\n    }\n  }\n}\n"
        )
        File(temp.root, "Good.kt").writeText(
            "class Good {\n  suspend fun f() {\n    database.withTransaction { x() }\n    runner.run(batch)\n  }\n}\n"
        )
        val result = PostCommitInTransactionScan.scanTree(temp.root)
        assertEquals(1, result.violations.size)
        assertTrue(result.violations.single(), result.violations.single().contains("pkg/Bad.kt:4"))
        assertEquals(2, result.txBlocks)
        assertEquals(2, result.postCommitCalls)
        assertFalse(result.violations.any { it.contains("Good.kt") })
    }
}

internal data class PostCommitScanResult(
    val violations: List<String>,
    val txBlocks: Int,
    val postCommitCalls: Int,
    val runCalls: Int,
    val afterCommitCalls: Int
) {
    operator fun plus(other: PostCommitScanResult) = PostCommitScanResult(
        violations + other.violations,
        txBlocks + other.txBlocks,
        postCommitCalls + other.postCommitCalls,
        runCalls + other.runCalls,
        afterCommitCalls + other.afterCommitCalls
    )

    companion object {
        val EMPTY = PostCommitScanResult(emptyList(), 0, 0, 0, 0)
    }
}

/** Pure scanner: post-commit dispatch call sites located textually inside transaction regions. */
internal object PostCommitInTransactionScan {

    private val transactionName = Regex("""(?<![@\w])(?:withTransaction|runInTransaction)\b""")

    private val declaration = Regex(
        """\bfun\s+(?:<[^>]*>\s*)?(?:[\w.<>?, ]+\.)?(?:runBestEffortAfterCommit|dispatchOn(?:Created|Updated|Deleted|BulkUpdated))\s*\("""
    )

    private val runCall = Regex("""\b(?:runner|\w*[pP]ostCommit\w*|\w*ActionRunner)\s*\.\s*run\s*\(""")
    private val afterCommitCall = Regex("""\brunBestEffortAfterCommit\s*\(""")
    private val dispatchOnCall = Regex("""\bdispatchOn(?:Created|Updated|Deleted|BulkUpdated)\s*\(""")

    fun scanTree(root: File): PostCommitScanResult {
        var total = PostCommitScanResult.EMPTY
        FailClosedScan.walkSourceFiles(root) { file, text ->
            total += scanSource(FailClosedScan.relativePath(root, file), text)
        }
        return total
    }

    /** At least one transaction block and one live post-commit call of each kind must have been seen. */
    fun requireNonVacuous(result: PostCommitScanResult) {
        if (result.txBlocks < 1) {
            throw AssertionError("scan saw no transaction block - side-effect contract would be vacuous")
        }
        if (result.postCommitCalls < 1) {
            throw AssertionError("scan saw no post-commit call site - side-effect contract would be vacuous")
        }
        if (result.runCalls < 1 || result.afterCommitCalls < 1) {
            throw AssertionError(
                "scan saw runner.run(${result.runCalls}) / runBestEffortAfterCommit(${result.afterCommitCalls}) " +
                    "post-commit call sites; both kinds are live in production - a detector regression"
            )
        }
    }

    fun scanSource(label: String, source: String): PostCommitScanResult {
        val code = SourceTextSanitizer.stripCommentsAndStringBodies(source)
        val regions = transactionRegions(label, code)
        val callCode = declaration.replace(code) { match -> " ".repeat(match.value.length) }
        val calls = mutableListOf<Pair<Int, String>>()
        runCall.findAll(callCode).forEach { calls += it.range.first to it.value.trim() }
        afterCommitCall.findAll(callCode).forEach { calls += it.range.first to "runBestEffortAfterCommit(" }
        dispatchOnCall.findAll(callCode).forEach { calls += it.range.first to it.value.trim() }

        val violations = calls.filter { (offset, _) -> regions.any { offset in it } }.map { (offset, kind) ->
            "$label:${lineOf(code, offset)}: $kind inside a database transaction block"
        }
        return PostCommitScanResult(
            violations = violations,
            txBlocks = regions.size,
            postCommitCalls = calls.size,
            runCalls = calls.count { it.second.contains(".run") },
            afterCommitCalls = calls.count { it.second.startsWith("runBestEffortAfterCommit") }
        )
    }

    /** Whole-call regions (name through end of argument list / trailing lambda) of transaction calls with a body. */
    private fun transactionRegions(label: String, code: String): List<IntRange> {
        val regions = mutableListOf<IntRange>()
        for (match in transactionName.findAll(code)) {
            var index = match.range.last + 1
            index = skipWhitespace(code, index)
            var end = match.range.last
            if (index < code.length && code[index] == '(') {
                val close = FailClosedScan.matchingParen(code, index)
                    ?: throw AssertionError("$label:${lineOf(code, index)}: unbalanced '(' after ${match.value} - cannot scan")
                end = close
                index = skipWhitespace(code, close + 1)
            }
            var hasBlock = false
            if (index < code.length && code[index] == '{') {
                val close = closingBrace(code, index)
                    ?: throw AssertionError("$label:${lineOf(code, index)}: unbalanced '{' after ${match.value} - cannot scan")
                end = close
                hasBlock = true
            }
            // A bare name (import, property reference) opens no region; a call with arguments but
            // no trailing lambda still guards its argument list.
            if (hasBlock || end > match.range.last) regions += match.range.first..end
        }
        return regions
    }

    private fun skipWhitespace(code: String, from: Int): Int {
        var index = from
        while (index < code.length && code[index].isWhitespace()) index++
        return index
    }

    private fun lineOf(code: String, offset: Int): Int = code.substring(0, offset).count { it == '\n' } + 1

    private fun closingBrace(code: String, opening: Int): Int? {
        var depth = 0
        for (index in opening until code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return index
            }
        }
        return null
    }
}
