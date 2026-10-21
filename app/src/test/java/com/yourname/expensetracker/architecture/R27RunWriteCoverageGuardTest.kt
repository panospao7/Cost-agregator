package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Release 1.0 R27 (owner-approved Q-R27-5) - TIGHTENING guard.
 *
 * R27 migrated the write paths below from the non-counting `checkWritesAllowed(...)`
 * to `DatabaseWriteBarrier.runWrite(...)`, so that `MaintenanceOperationRunner.enterAndDrain`
 * (backup/restore/wipe) can wait for them through `awaitNoInFlightWrites`. A mutation that
 * silently drifts back to a bare check is invisible to the drain and could race a restore.
 *
 * This guard pins a fixed map of (file, enclosing class, method) -> "body contains a
 * `runWrite(` call". A renamed or removed method FAILS (it is never silently skipped), so the
 * map must be updated deliberately together with the migration it protects.
 *
 * Detection runs on comment- and string-blanked source, so `runWrite(` mentioned only in a
 * comment or string literal never counts as coverage.
 *
 * Bank-statement finalisers are lambda stages inside `processBankStatement`, not methods, so
 * they are pinned by their operation label: `runWrite(DatabaseAccessOperation("<label>"))`.
 */
class R27RunWriteCoverageGuardTest {

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

    private fun readSource(relativePath: String): String {
        val file = File(sourceRoot, relativePath)
        assertTrue("R27 guard: pinned file does not exist (renamed/moved?): $relativePath", file.isFile)
        return file.readText()
    }

    /** One pinned file: optional enclosing class/object scope plus the methods that must call runWrite. */
    data class Pin(
        val path: String,
        val scope: String?,
        val methods: List<String>,
        val labels: List<String> = emptyList()
    )

    companion object {
        private const val BASE = "com/yourname/expensetracker/"

        val PINS: List<Pin> = listOf(
            Pin(
                path = BASE + "domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt",
                scope = "TransactionLifecycleCoordinator",
                methods = listOf(
                    // R27-migrated
                    "updateLocation", "clearLocation", "updateBusinessExpensePatch", "updateMerchant",
                    "updateTypeAndTransferDetails", "bulkUpdateCategory", "deleteExpense",
                    // pre-existing runWrite owners
                    "createExpenseMutation", "updateExpense", "updateCategory", "updateType",
                    "updateTransferDetails", "updateOwnershipDbOnlyV2", "bulkUpdateMerchant",
                    "writeDuplicateEvent"
                )
            ),
            Pin(
                path = BASE + "domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt",
                scope = "ReceiptLifecycleCoordinator",
                methods = listOf(
                    "processReceiptInput", "saveEmailReceiptTyped", "deleteReceipt",
                    "writeAssetDeleteFailedEvent", "createExpenseAndLinkReceipt", "processEmailReceipt"
                )
            ),
            Pin(
                path = BASE + "data/repository/NotificationRepository.kt",
                scope = "NotificationRepository",
                methods = listOf(
                    "save", "blockPackage", "unblockPackage", "delete", "deleteAllNotifications",
                    "deleteAll", "resetSourceStats", "restoreDebugSnapshot", "restoreSourceStatsSnapshot"
                )
            ),
            Pin(
                path = BASE + "domain/diagnostics/OperationRunRecorder.kt",
                scope = "RoomOperationRunRecorder",
                methods = listOf("start", "event", "increment", "finalizeNonCancellable")
            ),
            Pin(
                path = BASE + "domain/diagnostics/DiagnosticEventWriter.kt",
                scope = "RoomDiagnosticEventWriter",
                methods = listOf("emit")
            ),
            Pin(
                path = BASE + "data/privacy/PrivacyAuditLoggerImpl.kt",
                scope = "PrivacyAuditLoggerImpl",
                methods = listOf("logDecision")
            ),
            Pin(
                path = BASE + "domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt",
                scope = "BankStatementLifecycleProcessor",
                methods = emptyList(),
                labels = listOf(
                    "finalizeSuccess", "finalizeFailure", "finalizeCancelled",
                    "finalizeUnexpectedFailure", "finalizeLedgerOnlyFailure",
                    // R27-S4b: run creation, receipt/results transaction and per-item transactions
                    "createImportRun", "writeResults", "duplicateExpense.tx", "duplicatePending.tx",
                    "processItem.tx", "processItem.errorRecovery",
                    // SKIPPED ledger rows (AI rejected / invalid amount / currency / date)
                    "skipItem"
                )
            )
        )

        private val RUN_WRITE_CALL = Regex("""\brunWrite\s*(?:<[^>]*>)?\s*\(""")
        private val DECLARATION_WORD = Regex("""\b(?:fun|class|object|interface|val|var)\b""")
        private val NEXT_DECLARATION = Regex(
            """\n[ \t]*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:public|private|internal|protected|override|suspend|open|inline|abstract)\s+)*(?:fun|class|object|interface|val|var)\b"""
        )

        // ---- Source sanitizer: strips comments; optionally blanks string/char literal contents ----

        fun sanitize(src: String, keepStrings: Boolean): String {
            val sb = StringBuilder(src.length)
            var i = 0
            val n = src.length

            fun blank(from: Int, to: Int) {
                for (k in from until to) sb.append(if (src[k] == '\n') '\n' else ' ')
            }

            fun emitString(from: Int, to: Int) {
                if (keepStrings) sb.append(src, from, to) else blank(from, to)
            }

            while (i < n) {
                val c = src[i]
                when {
                    c == '/' && i + 1 < n && src[i + 1] == '/' -> {
                        var j = i
                        while (j < n && src[j] != '\n') j++
                        blank(i, j)
                        i = j
                    }
                    c == '/' && i + 1 < n && src[i + 1] == '*' -> {
                        var depth = 1
                        var j = i + 2
                        while (j < n && depth > 0) {
                            if (src[j] == '/' && j + 1 < n && src[j + 1] == '*') { depth++; j += 2 }
                            else if (src[j] == '*' && j + 1 < n && src[j + 1] == '/') { depth--; j += 2 }
                            else j++
                        }
                        blank(i, j)
                        i = j
                    }
                    src.startsWith("\"\"\"", i) -> {
                        var j = i + 3
                        while (j < n && !src.startsWith("\"\"\"", j)) j++
                        j = minOf(n, j + 3)
                        emitString(i, j)
                        i = j
                    }
                    c == '"' -> {
                        var j = i + 1
                        while (j < n && src[j] != '"' && src[j] != '\n') {
                            if (src[j] == '\\') {
                                j += 2
                            } else if (src[j] == '$' && j + 1 < n && src[j + 1] == '{') {
                                var depth = 1
                                j += 2
                                while (j < n && depth > 0) {
                                    when (src[j]) {
                                        '{' -> depth++
                                        '}' -> depth--
                                        '"' -> {
                                            j++
                                            while (j < n && src[j] != '"' && src[j] != '\n') {
                                                if (src[j] == '\\') j++
                                                j++
                                            }
                                        }
                                    }
                                    j++
                                }
                            } else {
                                j++
                            }
                        }
                        j = minOf(n, j + 1)
                        emitString(i, j)
                        i = j
                    }
                    c == '\'' && i + 2 < n && src[i + 1] == '\\' -> {
                        var j = i + 3 // skip the escaped character so '\'' is not cut short
                        while (j < n && src[j] != '\'' && src[j] != '\n') j++
                        j = minOf(n, j + 1)
                        emitString(i, j)
                        i = j
                    }
                    c == '\'' && i + 2 < n && src[i + 2] == '\'' -> {
                        emitString(i, i + 3)
                        i += 3
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
            return sb.toString()
        }

        /** Index of the bracket matching the opener at [openIdx], or -1. [text] must be sanitized. */
        fun matchClose(text: String, openIdx: Int, open: Char, close: Char): Int {
            var depth = 0
            for (k in openIdx until text.length) {
                val ch = text[k]
                if (ch == open) depth++
                else if (ch == close) {
                    depth--
                    if (depth == 0) return k
                }
            }
            return -1
        }

        /** Text of the first `class|object <name>` body (brace-matched), or null when not found. */
        fun extractScope(blanked: String, scopeName: String): String? {
            val decl = Regex("""\b(?:class|object)\s+${Regex.escape(scopeName)}\b""").find(blanked) ?: return null
            var parenDepth = 0
            var k = decl.range.last + 1
            while (k < blanked.length) {
                val ch = blanked[k]
                if (ch == '(') parenDepth++
                else if (ch == ')') parenDepth--
                else if (ch == '{' && parenDepth == 0) break
                k++
            }
            if (k >= blanked.length) return null
            val end = matchClose(blanked, k, '{', '}')
            if (end < 0) return null
            return blanked.substring(k, end + 1)
        }

        /**
         * Bodies of every declaration of `fun <name>(` in [text] that has a body. Bodyless
         * (interface/abstract) declarations are skipped. Returns an empty list when none.
         */
        fun extractMethodBodies(text: String, methodName: String): List<String> {
            val declRegex = Regex("""\bfun\s+(?:<[^>]*>\s*)?${Regex.escape(methodName)}\s*\(""")
            val bodies = mutableListOf<String>()
            for (m in declRegex.findAll(text)) {
                val parenOpen = m.range.last
                val parenClose = matchClose(text, parenOpen, '(', ')')
                if (parenClose < 0) continue
                var k = parenClose + 1
                while (k < text.length && text[k] != '{' && text[k] != '=' && text[k] != '}') k++
                if (k >= text.length) continue
                val header = text.substring(parenClose + 1, k)
                if (DECLARATION_WORD.containsMatchIn(header)) continue // abstract / bodyless
                if (text[k] == '}') continue
                if (text[k] == '{') {
                    val end = matchClose(text, k, '{', '}')
                    if (end > k) bodies.add(text.substring(k, end + 1))
                } else {
                    val rest = text.substring(k + 1)
                    val next = NEXT_DECLARATION.find(rest)
                    bodies.add(if (next != null) rest.substring(0, next.range.first) else rest)
                }
            }
            return bodies
        }

        fun containsRunWrite(blankedBody: String): Boolean = RUN_WRITE_CALL.containsMatchIn(blankedBody)

        /** Violations (human-readable) for one method of one file; empty means covered. */
        fun methodViolations(path: String, source: String, scope: String?, method: String): List<String> {
            val blanked = sanitize(source, keepStrings = false)
            val scoped = if (scope == null) blanked else extractScope(blanked, scope)
                ?: return listOf("$path: scope class/object '$scope' not found (renamed/removed?)")
            val bodies = extractMethodBodies(scoped, method)
            if (bodies.isEmpty()) {
                return listOf("$path: method '$method' not found (renamed/removed? update the R27 pin deliberately)")
            }
            val uncovered = bodies.count { !containsRunWrite(it) }
            return if (uncovered == 0) emptyList()
            else listOf("$path: method '$method' has $uncovered of ${bodies.size} body/bodies WITHOUT a runWrite( call " +
                "(mutation would be invisible to DatabaseWriteBarrier.awaitNoInFlightWrites)")
        }

        fun labelViolation(path: String, source: String, label: String): String? {
            val kept = sanitize(source, keepStrings = true)
            val regex = Regex(
                """\brunWrite\s*\(\s*DatabaseAccessOperation\s*\(\s*"[\w.]*${Regex.escape(label)}"\s*\)"""
            )
            return if (regex.containsMatchIn(kept)) null
            else "$path: no runWrite(DatabaseAccessOperation(\"...$label\")) stage found (finaliser lost its counted write)"
        }
    }

    // ---- Real-source pins -----------------------------------------------------------------

    @Test
    fun `R27 migrated write methods keep a runWrite call in their body`() {
        val violations = mutableListOf<String>()
        var pinned = 0
        for (pin in PINS) {
            val source = readSource(pin.path)
            for (method in pin.methods) {
                pinned++
                violations += methodViolations(pin.path, source, pin.scope, method)
            }
        }
        assertTrue("R27 guard pinned zero methods; the map is vacuous", pinned >= 30)
        assertTrue(
            "R27 runWrite coverage regression (Q-R27-5):\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `bank statement finalisers keep their counted runWrite stage`() {
        val pin = PINS.single { it.labels.isNotEmpty() }
        val source = readSource(pin.path)
        val violations = pin.labels.mapNotNull { labelViolation(pin.path, source, it) }
        assertEquals(12, pin.labels.size)
        assertTrue(
            "R27 bank-statement finaliser regression (Q-R27-5):\n" + violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `drain contract DatabaseWriteBarrier awaitNoInFlightWrites exists and enterAndDrain calls it`() {
        val barrierPath = BASE + "data/backup/DatabaseWriteBarrier.kt"
        val barrier = sanitize(readSource(barrierPath), keepStrings = false)
        assertTrue(
            "$barrierPath must declare fun awaitNoInFlightWrites (the drain primitive)",
            Regex("""\bfun\s+awaitNoInFlightWrites\s*\(""").containsMatchIn(barrier)
        )
        assertTrue(
            "$barrierPath must declare fun runWrite (the counting primitive)",
            Regex("""\bfun\s+<[^>]*>\s*runWrite\s*\(|\bfun\s+runWrite\s*\(""").containsMatchIn(barrier)
        )

        val runnerPath = BASE + "data/backup/MaintenanceOperationRunner.kt"
        val runner = sanitize(readSource(runnerPath), keepStrings = false)
        val bodies = extractMethodBodies(runner, "enterAndDrain")
        assertTrue("$runnerPath: enterAndDrain not found (renamed/removed?)", bodies.isNotEmpty())
        assertTrue(
            "$runnerPath: enterAndDrain must call awaitNoInFlightWrites( so R27 write paths are drained",
            bodies.all { Regex("""\bawaitNoInFlightWrites\s*\(""").containsMatchIn(it) }
        )
    }

    // ---- Matcher fixtures (synthetic sources) -----------------------------------------------

    @Test
    fun `fixture - method calling runWrite passes`() {
        val src = """
            class FakeWriter(private val writeBarrier: Barrier) {
                // writeBarrier.checkWritesAllowed("x") is only mentioned here
                suspend fun updateThing(id: Long): Int {
                    return writeBarrier.runWrite(DatabaseAccessOperation("FakeWriter.updateThing")) {
                        dao.update(id, mapOf("k" to "{"))
                    }
                }
            }
        """.trimIndent()
        assertEquals(emptyList<String>(), methodViolations("Fake.kt", src, "FakeWriter", "updateThing"))
    }

    @Test
    fun `fixture - method with only checkWritesAllowed fails and names file and method`() {
        val src = """
            class FakeWriter(private val writeBarrier: Barrier) {
                suspend fun updateThing(id: Long): Int {
                    writeBarrier.checkWritesAllowed("FakeWriter.updateThing")
                    return dao.update(id)
                }
            }
        """.trimIndent()
        val violations = methodViolations("Fake.kt", src, "FakeWriter", "updateThing")
        assertEquals(1, violations.size)
        assertTrue(violations.single().contains("Fake.kt"))
        assertTrue(violations.single().contains("updateThing"))
        assertTrue(violations.single().contains("runWrite"))
    }

    @Test
    fun `fixture - runWrite only in comment or string does not count and missing method fails`() {
        val src = """
            class FakeWriter(private val writeBarrier: Barrier) {
                suspend fun updateThing(id: Long): Int {
                    // writeBarrier.runWrite(op) { }
                    val label = "runWrite(x)"
                    writeBarrier.checkWritesAllowed(label)
                    return dao.update(id)
                }
            }
        """.trimIndent()
        assertFalse(methodViolations("Fake.kt", src, "FakeWriter", "updateThing").isEmpty())
        val missing = methodViolations("Fake.kt", src, "FakeWriter", "renamedAway")
        assertEquals(1, missing.size)
        assertTrue(missing.single().contains("renamedAway"))
        assertTrue(missing.single().contains("not found"))
    }

    @Test
    fun `fixture - scope isolates real implementation from no-op and interface siblings`() {
        val src = """
            interface Recorder { suspend fun event(stage: String) }
            object NoOpRecorder : Recorder {
                override suspend fun event(stage: String) = Unit
            }
            class RoomRecorder(private val writeBarrier: Barrier) : Recorder {
                override suspend fun event(stage: String) {
                    writeBarrier.runWrite(DatabaseAccessOperation("RoomRecorder.event")) { dao.insert(stage) }
                }
            }
        """.trimIndent()
        assertEquals(emptyList<String>(), methodViolations("Fake.kt", src, "RoomRecorder", "event"))
        assertFalse(methodViolations("Fake.kt", src, "NoOpRecorder", "event").isEmpty())
    }

    @Test
    fun `fixture - label matcher accepts counted stage and rejects bare check`() {
        val good = """
            writeBarrier.runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.finalizeSuccess")) { x() }
        """.trimIndent()
        val bad = """
            writeBarrier.checkWritesAllowed("BankStatementLifecycleProcessor.finalizeSuccess")
            // runWrite(DatabaseAccessOperation("BankStatementLifecycleProcessor.finalizeSuccess"))
        """.trimIndent()
        assertEquals(null, labelViolation("Bank.kt", good, "finalizeSuccess"))
        assertTrue(labelViolation("Bank.kt", bad, "finalizeSuccess")!!.contains("finalizeSuccess"))
    }
}
