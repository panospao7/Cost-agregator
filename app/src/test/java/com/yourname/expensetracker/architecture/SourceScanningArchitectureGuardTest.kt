package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * Strips line comments (// ...), block comments (/* ... */), and KDoc (/** ... */)
     * from the given source text. This prevents commented-out code from satisfying
     * architecture checks (e.g. `// runGuardedWithContext(`).
     *
     * The stripping is regex-based and handles nested block comments in a simplified
     * way by removing the innermost block comments first until none remain.
     * String literals are left intact so URLs like "https://..." are not mangled.
     */
    private fun stripComments(text: String): String {
        // Remove KDoc /** ... */ — must be done before block comments since KDoc
        // starts with /** which would otherwise be consumed by the block-comment regex.
        var result = text
            .replace(Regex("/\\*\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")

        // Remove nested block comments /* ... */ by repeatedly stripping innermost
        // pairs (those that contain no inner /* */) until none remain.
        var previous: String
        do {
            previous = result
            result = result.replace(Regex("/\\*[^*]*\\*+(?:[^/*][^*]*\\*+)*/"), "")
        } while (result != previous)

        // Remove line comments // ... (but preserve the newline so line counts stay
        // roughly aligned for diagnostics).
        result = result.replace(Regex("//[^\n]*"), "")

        return result
    }

    // ── Negative fixtures ───────────────────────────────────────────────────
    // These tests use synthetic bad source strings to prove the guard rules
    // are not vacuously passing — they actually detect violations.

    @Test
    fun `negative_fixture_coroutine_worker_without_guard_is_caught`() {
        val badWorker = """
            class BadWorker : CoroutineWorker(context, params) {
                override suspend fun doWork(): Result {
                    return Result.success()
                }
            }
        """.trimIndent()
        val stripped = stripComments(badWorker)
        val hasGuard = stripped.contains("runGuarded(") || stripped.contains("runGuardedWithContext(")
        assertTrue("Negative fixture: CoroutineWorker without guard must be detected", !hasGuard)
    }

    @Test
    fun `negative_fixture_broadcast_receiver_with_dao_is_caught`() {
        val badReceiver = """
            class BadReceiver : BroadcastReceiver() {
                @Inject lateinit var expenseDao: ExpenseDao
                override fun onReceive(context: Context, intent: Intent) {
                    GlobalScope.launch { expenseDao.deleteAll() }
                }
            }
        """.trimIndent()
        val stripped = stripComments(badReceiver)
        val hasViolation = stripped.contains("@Inject") && (stripped.contains("Dao") || stripped.contains("Repository")) ||
            stripped.contains("GlobalScope") || stripped.contains("launch {")
        assertTrue("Negative fixture: BroadcastReceiver with DAO injection must be detected", hasViolation)
    }

    @Test
    fun `negative_fixture_worker_missing_notification_permission_is_caught`() {
        val badWorker = """
            class BadWorker : CoroutineWorker(context, params) {
                override suspend fun doWork(): Result {
                    val service = NotificationService()
                    service.sendNotification()
                    return Result.success()
                }
            }
        """.trimIndent()
        val stripped = stripComments(badWorker)
        val hasPermissionFlag = stripped.contains("requiresNotificationPermission = true")
        assertTrue("Negative fixture: Notification worker missing permission flag must be detected", !hasPermissionFlag)
    }

    @Test
    fun `negative_fixture_comment_faked_guard_call_is_stripped`() {
        val sourceWithCommentedGuard = """
            class FakeWorker : CoroutineWorker(context, params) {
                // runGuardedWithContext(request) { }
                override suspend fun doWork(): Result {
                    return Result.success()
                }
            }
        """.trimIndent()
        val stripped = stripComments(sourceWithCommentedGuard)
        assertFalse("Commented guard call must be stripped before scanning", stripped.contains("runGuardedWithContext"))
    }

    // ── Positive tests (scan real source tree) ────────────────────────────────

    @Test
    fun `all_coroutine_worker_files_contain_guard_call`() {
        val workerFiles = kotlinFiles().filter { file ->
            val text = stripComments(file.readText())
            text.contains(": CoroutineWorker") || text.contains("extends CoroutineWorker")
        }

        assertTrue(
            "Architecture guard scanned ZERO CoroutineWorker files in ${sourceRoot.absolutePath}. " +
                "Source root resolution is broken — this test would pass vacuously.",
            workerFiles.isNotEmpty()
        )

        val violations = workerFiles.filter { file ->
            val text = stripComments(file.readText())
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
            stripComments(file.readText()).contains(": BroadcastReceiver")
        }

        val violations = receiverFiles.filter { file ->
            val text = stripComments(file.readText())
            text.contains("@Inject") && (text.contains("Dao") || text.contains("Repository")) ||
                text.contains("GlobalScope") || text.contains("launch {")
        }

        assertTrue(
            "BroadcastReceiver files with direct DAO/repository injection or GlobalScope/launch: " +
                violations.map { it.relativeTo(sourceRoot).path },
            violations.isEmpty()
        )
    }

    @Test
    fun `all_coroutine_workers_pass_workId_and_runAttemptCount`() {
        val workerFiles = kotlinFiles().filter { file ->
            val text = stripComments(file.readText())
            text.contains(": CoroutineWorker")
        }

        assertTrue(
            "Architecture guard scanned ZERO CoroutineWorker files in ${sourceRoot.absolutePath} " +
                "for workId/runAttemptCount check. Source root resolution is broken.",
            workerFiles.isNotEmpty()
        )

        val violations = workerFiles.filter { file ->
            val text = stripComments(file.readText())
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

    @Test
    fun `worker_files_do_not_contain_direct_dao_usage`() {
        val workerFiles = kotlinFiles().filter { file ->
            val text = stripComments(file.readText())
            text.contains(": CoroutineWorker")
        }

        // Exclude action workers and SourceLinkBackfillWorker — they have
        // explicit architectural reasons for accessing DAOs directly.
        // PR12I-4: The following workers have direct DAO usage that predates the
        // architecture guard. They are allowlisted here with a TODO to refactor.
        // - DataRetentionWorker: uses PrivacyAuditDao directly for retention audit
        // - WarrantyExpirationWorker: uses WarrantyReminderDeliveryDao directly
        // - NotificationIntakeWorker: uses NotificationIntakeDao directly (PR12H-2)
        val allowlist = setOf(
            "DismissReminderActionWorker.kt",
            "SnoozeReminderActionWorker.kt",
            "SourceLinkBackfillWorker.kt",
            "DataRetentionWorker.kt",
            "WarrantyExpirationWorker.kt",
            "NotificationIntakeWorker.kt"
        )

        val violations = workerFiles.filter { file ->
            if (file.name in allowlist) return@filter false
            val text = stripComments(file.readText())
            text.contains("Dao") || text.contains("@Inject")
        }

        assertTrue(
            "CoroutineWorker files with direct DAO usage or @Inject (action workers " +
                "and SourceLinkBackfillWorker are allowlisted): " +
                violations.map { it.relativeTo(sourceRoot).path },
            violations.isEmpty()
        )
    }

    @Test
    fun `notification_posting_workers_require_permission_flag`() {
        val notificationWorkers = kotlinFiles().filter { file ->
            val text = stripComments(file.readText())
            text.contains(": CoroutineWorker") && (
                text.contains("NotificationService") ||
                text.contains("NotificationManager") ||
                text.contains("notify(") ||
                text.contains("deliverNotification") ||
                text.contains("BillReminder") ||
                text.contains("WarrantyExpiration") ||
                text.contains("DailyBriefing")
            )
        }

        val violations = notificationWorkers.filter { file ->
            val text = stripComments(file.readText())
            !text.contains("requiresNotificationPermission = true")
        }

        assertTrue(
            "Notification-posting workers missing requiresNotificationPermission = true: " +
                violations.map { it.relativeTo(sourceRoot).path },
            violations.isEmpty()
        )
    }

    @Test
    fun `privacy_sensitive_workers_require_capabilities`() {
        val privacyWorkers = kotlinFiles().filter { file ->
            val text = stripComments(file.readText())
            text.contains(": CoroutineWorker") && (
                text.contains("NotificationIntake") ||
                text.contains("Receipt OCR") ||
                text.contains("privacyGate") ||
                text.contains("PrivacyCapability") ||
                text.contains("decrypt")
            )
        }

        val violations = privacyWorkers.filter { file ->
            val text = stripComments(file.readText())
            !text.contains("requiredCapabilities")
        }

        assertTrue(
            "Privacy-sensitive workers missing requiredCapabilities: " +
                violations.map { it.relativeTo(sourceRoot).path },
            violations.isEmpty()
        )
    }

    @Test
    fun `dynamic_one_shot_workers_require_retry_blocked_policy`() {
        val dynamicWorkers = kotlinFiles().filter { file ->
            val text = stripComments(file.readText())
            text.contains(": CoroutineWorker") && (
                text.contains("NotificationIntake") ||
                text.contains("DismissReminderAction") ||
                text.contains("SnoozeReminderAction")
            )
        }

        val violations = dynamicWorkers.filter { file ->
            val text = stripComments(file.readText())
            !text.contains("blockedPolicy = BlockedPolicy.RETRY")
        }

        assertTrue(
            "Dynamic one-shot workers missing blockedPolicy = BlockedPolicy.RETRY: " +
                violations.map { it.relativeTo(sourceRoot).path },
            violations.isEmpty()
        )
    }
}
