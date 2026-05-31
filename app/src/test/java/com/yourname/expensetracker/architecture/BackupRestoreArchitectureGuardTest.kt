package com.yourname.expensetracker.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pipeline 7 — static architecture guards for destructive backup/restore paths.
 *
 * Two invariants, both verified by scanning real production source so a future edit
 * that silently regresses them fails loudly:
 *
 *  • P7-CURRENT-020: `resetDatabase()` is a destructive operation (deletes the live DB +
 *    WAL/SHM). It MUST enter `RESETTING_DATABASE` maintenance mode (which drains workers
 *    and blocks all writes) and create a `RestoreJournal` so a crash mid-reset is
 *    recoverable. This guard asserts both wrappers are present in the implementation.
 *    (The typed-confirmation token is intentionally out of scope for this guard.)
 *
 *  • P7-CURRENT-021: the raw `exportDatabase()` path produces a legacy `.db`/`.enc`
 *    artifact with no `.costbackup` manifest/assets and is release-disabled. It is a
 *    debug-only affordance. This guard asserts NO production (non-debug) UI references it,
 *    so a future UI cannot accidentally depend on raw export. The debug screen
 *    (`ui/screens/debug/**`) is the single intended caller and is exempt.
 */
class BackupRestoreArchitectureGuardTest {

    private val sourceRoot: File by lazy { resolveSourceRoot() }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}, candidates=$candidates")
    }

    private fun readSource(relativePath: String): String {
        val file = File(sourceRoot, relativePath)
        assertTrue(
            "Expected source file not found: ${file.absolutePath}. Has it moved/renamed?",
            file.exists()
        )
        return file.readText()
    }

    // ── P7-CURRENT-020 ───────────────────────────────────────────

    @Test
    fun `resetDatabase enters maintenance mode and creates a restore journal`() {
        val source = readSource(
            "com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt"
        )
        val declIdx = source.indexOf("fun resetDatabase")
        assertTrue(
            "resetDatabase() not found in DatabaseBackupRepositoryImpl — guard cannot verify its safety wrappers",
            declIdx >= 0
        )
        // Bounded window over the function body. `enterAndDrain(...RESETTING_DATABASE` is
        // unique to resetDatabase, so a marker found here genuinely belongs to it.
        val body = source.substring(declIdx, minOf(declIdx + 4000, source.length))

        assertTrue(
            "P7-CURRENT-020 regression: resetDatabase() must enter RESETTING_DATABASE maintenance " +
                "mode (enterAndDrain) so workers are drained and writes are blocked during the reset.",
            body.contains("enterAndDrain(RestoreMaintenanceMode.Mode.RESETTING_DATABASE")
        )
        assertTrue(
            "P7-CURRENT-020 regression: resetDatabase() must create a RestoreJournal (beginJournal) " +
                "so a crash mid-reset is recoverable and leaves an audit trail.",
            body.contains("beginJournal(")
        )
    }

    // ── P7-CURRENT-021 ───────────────────────────────────────────

    private val exportDatabaseReference = Regex("""\bexportDatabase\b""")

    private fun productionUiKtFiles(): List<File> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val path = file.absolutePath.replace('\\', '/')
                path.contains("/expensetracker/ui/") &&
                    // The debug screen is the single intended, release-disabled caller.
                    !path.contains("/expensetracker/ui/screens/debug/")
            }
            .toList()

    @Test
    fun `production UI does not call raw exportDatabase`() {
        val uiFiles = productionUiKtFiles()
        assertTrue(
            "P7-CURRENT-021 guard scanned ZERO production UI files under $sourceRoot/.../ui — " +
                "a broken source-root resolver would make this test vacuously pass.",
            uiFiles.isNotEmpty()
        )

        val violations = uiFiles.filter { exportDatabaseReference.containsMatchIn(it.readText()) }
            .map { it.absolutePath.replace('\\', '/').substringAfter("/expensetracker/") }

        assertTrue(
            "P7-CURRENT-021: raw exportDatabase() is debug-only (legacy .db, no .costbackup manifest, " +
                "release-disabled). The following production UI file(s) reference it and must not — " +
                "use createCostBackup() instead, or move the call into ui/screens/debug/:\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `debug screen remains the intended raw-export caller`() {
        // Keeps the exemption honest: if the debug caller disappears, this guard's
        // exclusion is stale and 021 may have silently changed shape. The repository
        // interface still declaring exportDatabase is fine; this only checks the UI caller.
        val debugCallerExists = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .any { file ->
                val path = file.absolutePath.replace('\\', '/')
                path.contains("/expensetracker/ui/screens/debug/") &&
                    exportDatabaseReference.containsMatchIn(file.readText())
            }
        assertTrue(
            "Expected the debug screen (ui/screens/debug/**) to be the raw-export caller. " +
                "If raw export was fully removed from the UI, delete this guard's debug exemption.",
            debugCallerExists
        )
    }
}
