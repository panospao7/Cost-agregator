package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PR12F — Source-scanning static architecture guards.
 *
 * Unlike [WorkerGuardStaticVerificationTest] (which cross-checks the hardcoded
 * worker list in [WorkerGuardVerifier]), these tests read actual Kotlin source
 * files from the production source tree and verify architecture rules directly.
 *
 * This catches violations that the hardcoded-list approach cannot:
 * - A [CoroutineWorker] that was never added to any registry
 * - A [BroadcastReceiver] that directly injects a DAO or repository
 * - Workers that call [runGuardedWithContext] but omit [workId] or [runAttemptCount]
 * - Schema version mismatches between [AppDatabase.APP_DATABASE_SCHEMA_VERSION]
 *   and the latest exported Room schema JSON
 */
class SourceScanningArchitectureGuardTest {

    // ── Source tree resolution ────────────────────────────────────────────────

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

    private val schemaRoot: File by lazy { resolveSchemaRoot() }

    private fun resolveSchemaRoot(): File {
        val candidates = listOf(
            File("app/schemas/com.yourname.expensetracker.data.database.AppDatabase"),
            File("schemas/com.yourname.expensetracker.data.database.AppDatabase"),
            File(System.getProperty("user.dir") ?: ".", "app/schemas/com.yourname.expensetracker.data.database.AppDatabase"),
            File(System.getProperty("user.dir") ?: ".", "schemas/com.yourname.expensetracker.data.database.AppDatabase")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate Room schema directory. user.dir=${System.getProperty("user.dir")}")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Returns all .kt files under the source root. */
    private fun kotlinFiles(): List<File> = sourceRoot.walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    // ── Tests ─────────────────────────────────────────────────────────────────

    @Test
    fun `all_coroutine_worker_files_contain_guard_call`() {
        val workerFiles = kotlinFiles().filter { file ->
            val text = file.readText()
            text.contains(": CoroutineWorker") || text.contains("extends CoroutineWorker")
        }

        assertTrue(
            "Architecture guard scanned ZERO CoroutineWorker files in ${sourceRoot.absolutePath}. " +
                "Source root resolution is broken — this test would pass vacuously.",
            workerFiles.isNotEmpty()
        )

        val violations = workerFiles.filter { file ->
            val text = file.readText()
            !text.contains("runGuarded(") && !text.contains("runGuardedWithContext(")
        }

        assertTrue(
            "CoroutineWorker files missing guard call (runGuarded/runGuardedWithContext): " +
                violations.map { it.relativeTo(sourceRoot).path },
            violations.isEmpty()
        )
    }

    @Test
    fun `no_broadcast_receiver_contains_direct_dao_injection`() {
        val receiverFiles = kotlinFiles().filter { file ->
            file.readText().contains(": BroadcastReceiver")
        }

        val violations = receiverFiles.filter { file ->
            val text = file.readText()
            text.contains("@Inject") && (text.contains("Dao") || text.contains("Repository"))
        }

        assertTrue(
            "BroadcastReceiver files with direct DAO/repository injection: " +
                violations.map { it.relativeTo(sourceRoot).path },
            violations.isEmpty()
        )
    }

    @Test
    fun `all_coroutine_workers_pass_workId_and_runAttemptCount`() {
        val workerFiles = kotlinFiles().filter { file ->
            val text = file.readText()
            text.contains(": CoroutineWorker")
        }

        assertTrue(
            "Architecture guard scanned ZERO CoroutineWorker files in ${sourceRoot.absolutePath} " +
                "for workId/runAttemptCount check. Source root resolution is broken.",
            workerFiles.isNotEmpty()
        )

        val violations = workerFiles.filter { file ->
            val text = file.readText()
            text.contains("runGuardedWithContext(") &&
            (!text.contains("workId") || !text.contains("runAttemptCount"))
        }

        assertTrue(
            "Workers missing workId/runAttemptCount in guard request: " +
                violations.map { it.relativeTo(sourceRoot).path },
            violations.isEmpty()
        )
    }

    @Test
    fun `schema_version_matches_latest_json_file`() {
        val jsonFiles = schemaRoot.listFiles { f -> f.extension == "json" } ?: emptyArray()
        val maxVersion = jsonFiles.map { it.nameWithoutExtension.toIntOrNull() ?: 0 }.maxOrNull() ?: 0

        assertTrue(
            "No schema JSON files found in ${schemaRoot.absolutePath}. " +
                "Schema directory may be missing or resolution is broken.",
            maxVersion > 0
        )

        val appDatabaseFile = File(sourceRoot, "com/yourname/expensetracker/data/database/AppDatabase.kt")
        assertTrue(
            "AppDatabase.kt not found at expected path: ${appDatabaseFile.absolutePath}",
            appDatabaseFile.exists()
        )

        val versionLine = appDatabaseFile.readLines()
            .firstOrNull { it.contains("APP_DATABASE_SCHEMA_VERSION") }
            ?: error("APP_DATABASE_SCHEMA_VERSION declaration not found in AppDatabase.kt")

        val declaredVersion = versionLine.filter { it.isDigit() }.toInt()

        assertEquals(
            "APP_DATABASE_SCHEMA_VERSION ($declaredVersion) must match latest schema JSON ($maxVersion). " +
                "If you just added a Room migration, update the constant in AppDatabase.kt.",
            maxVersion, declaredVersion
        )
    }
}
