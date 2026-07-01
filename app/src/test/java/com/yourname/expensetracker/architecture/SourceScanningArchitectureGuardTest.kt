package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

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

    /**
     * Structured allowlist entry that requires owner, reason, issue tracking, and
     * expiry date — ensuring no allowlisted entry is left unaccounted for.
     */
    data class ArchitectureAllowlistEntry(
        val fileName: String,
        val rule: String,
        val owner: String,
        val reason: String,
        val issue: String,
        val expires: LocalDate
    )

    /**
     * Structured allowlist of files excluded from the DIRECT_DAO_IN_WORKER rule.
     * Each entry records who owns the exception, why it exists, the tracking issue,
     * and when it expires — forcing periodic review.
     */
    private val allowlist = listOf(
        ArchitectureAllowlistEntry(
            fileName = "DismissReminderActionWorker.kt",
            rule = "DIRECT_DAO_IN_WORKER",
            owner = "WorkerArchitecture",
            reason = "Action workers enqueue via WorkManager, no guard required",
            issue = "MIT-016",
            expires = LocalDate.of(2026, 12, 31)
        ),
        ArchitectureAllowlistEntry(
            fileName = "SnoozeReminderActionWorker.kt",
            rule = "DIRECT_DAO_IN_WORKER",
            owner = "WorkerArchitecture",
            reason = "Action workers enqueue via WorkManager, no guard required",
            issue = "MIT-016",
            expires = LocalDate.of(2026, 12, 31)
        ),
        ArchitectureAllowlistEntry(
            fileName = "SourceLinkBackfillWorker.kt",
            rule = "DIRECT_DAO_IN_WORKER",
            owner = "WorkerArchitecture",
            reason = "Non-WM worker uses barrier + time provider, not CoroutineWorker",
            issue = "MIT-016",
            expires = LocalDate.of(2026, 12, 31)
        ),
        ArchitectureAllowlistEntry(
            fileName = "DataRetentionWorker.kt",
            rule = "DIRECT_DAO_IN_WORKER",
            owner = "WorkerArchitecture",
            reason = "PR12K-1: cleanup worker reads privacy settings internally; direct DAO for audit only",
            issue = "PR12K-1",
            expires = LocalDate.of(2026, 12, 31)
        ),
        ArchitectureAllowlistEntry(
            fileName = "WarrantyExpirationWorker.kt",
            rule = "DIRECT_DAO_IN_WORKER",
            owner = "WorkerArchitecture",
            reason = "Legacy worker with direct DAO; scheduled for refactor",
            issue = "PR12K-1",
            expires = LocalDate.of(2026, 12, 31)
        ),
        ArchitectureAllowlistEntry(
            fileName = "NotificationIntakeWorker.kt",
            rule = "DIRECT_DAO_IN_WORKER",
            owner = "WorkerArchitecture",
            reason = "PR12H-2: privacy-split reload requires direct DAO for metadata/payload separation",
            issue = "PR12H-2",
            expires = LocalDate.of(2026, 12, 31)
        )
    )

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
     * Scans a single source string for architecture violations.
     * Uses the same logic as the production source-tree tests.
     */
    private fun scanSource(name: String, text: String): List<String> {
        val stripped = stripComments(text)
        val violations = mutableListOf<String>()

        // Rule: CoroutineWorker must have guard call
        if (stripped.contains(": CoroutineWorker") || stripped.contains("extends CoroutineWorker")) {
            if (!stripped.contains("runGuarded(") && !stripped.contains("runGuardedWithContext(")) {
                violations.add("MISSING_GUARD")
            }
        }

        // Rule: Optional notification worker must have local permission check
        if ((stripped.contains("NotificationService") || stripped.contains("sendBudgetAlert")) &&
            !stripped.contains("requiresNotificationPermission = true")
        ) {
            if (!stripped.contains("notificationPermissionChecker.areNotificationsEnabled()")) {
                violations.add("OPTIONAL_NOTIFICATION_WITHOUT_LOCAL_CHECK")
            }
        }

        // Rule: DataRetention must not require raw-retention capabilities
        if (stripped.contains("WorkerGuardRequest(")) {
            val guardBlock = stripped.substringAfter("WorkerGuardRequest(").substringBefore(")")
            if (guardBlock.contains("requiredCapabilities") &&
                (guardBlock.contains("PrivacyCapability.RAW_NOTIFICATION_RETENTION") ||
                    guardBlock.contains("PrivacyCapability.RAW_OCR_RETENTION"))
            ) {
                violations.add("DATA_RETENTION_RAW_CAPABILITY")
            }
        }

        return violations
    }

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
        // roughly aligned for diagnostics). Use negative lookbehind to avoid stripping
        // URL schemes like https:// or http:// inside string literals.
        result = result.replace(Regex("(?<!:)//[^\n]*"), "")

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
        // See the class-level [allowlist] for owner, reason, issue, and expiry.
        val allowedFileNames = allowlist.map { it.fileName }.toSet()
        val violations = workerFiles.filter { file ->
            if (file.name in allowedFileNames) return@filter false
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

        // PR12K-3: ReceiptMatchingWorker treats notifications as optional side effects.
        // It must NOT require global permission, but MUST locally check before posting.
        val optionalNotificationWorkers = setOf("ReceiptMatchingWorker.kt")

        val requiredViolations = notificationWorkers.filter { file ->
            if (file.name in optionalNotificationWorkers) return@filter false
            val text = stripComments(file.readText())
            !text.contains("requiresNotificationPermission = true")
        }

        val optionalViolations = notificationWorkers.filter { file ->
            if (file.name !in optionalNotificationWorkers) return@filter false
            val text = stripComments(file.readText())
            // Optional workers must have requiresNotificationPermission = false
            // AND must locally check permission before posting
            text.contains("requiresNotificationPermission = true") ||
                !text.contains("notificationPermissionChecker.areNotificationsEnabled()")
        }

        assertTrue(
            "Required notification workers missing requiresNotificationPermission = true: " +
                requiredViolations.map { it.relativeTo(sourceRoot).path },
            requiredViolations.isEmpty()
        )
        assertTrue(
            "Optional notification workers missing local permission check (PR12K-3): " +
                optionalViolations.map { it.relativeTo(sourceRoot).path },
            optionalViolations.isEmpty()
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

    // ── Structured allowlist validation ───────────────────────────────────────

    @Test
    fun `structured_allowlist_requires_owner_reason_issue_expiry`() {
        for (entry in allowlist) {
            assertTrue("Allowlist entry ${entry.fileName} missing owner", entry.owner.isNotBlank())
            assertTrue("Allowlist entry ${entry.fileName} missing reason", entry.reason.isNotBlank())
            assertTrue("Allowlist entry ${entry.fileName} missing issue", entry.issue.isNotBlank())
            assertNotNull("Allowlist entry ${entry.fileName} missing expiry", entry.expires)
        }
    }

    @Test
    fun `expired_allowlist_entries_fail`() {
        val today = LocalDate.now()
        val expired = allowlist.filter { it.expires.isBefore(today) }
        assertTrue(
            "Expired allowlist entries found: ${expired.map { it.fileName }}",
            expired.isEmpty()
        )
    }

    // ── Negative fixtures for specific violations ─────────────────────────────

    @Test
    fun `data_retention_must_not_require_raw_retention_capabilities`() {
        val dataRetentionFile = kotlinFiles().find { it.name == "DataRetentionWorker.kt" }
        assertNotNull("DataRetentionWorker.kt not found", dataRetentionFile)
        val text = stripComments(dataRetentionFile!!.readText())
        // Only check the WorkerGuardRequest constructor call, not audit-event logging
        // or other references to these constants elsewhere in the file.
        val guardRequestRegex = Regex(
            "WorkerGuardRequest\\s*\\(([^)]*)\\)",
            RegexOption.DOT_MATCHES_ALL
        )
        val guardRequestBlocks = guardRequestRegex.findAll(text).map { it.groupValues[1] }.toList()
        val hasWrongGating = guardRequestBlocks.any { block ->
            block.contains("requiredCapabilities") && (
                block.contains("PrivacyCapability.RAW_NOTIFICATION_RETENTION") ||
                    block.contains("PrivacyCapability.RAW_OCR_RETENTION")
            )
        }
        assertFalse(
            "DataRetentionWorker guard request must not require raw-retention capabilities (PR12K-1)",
            hasWrongGating
        )
    }

    @Test
    fun `receipt_matching_must_not_require_global_notification_permission`() {
        val receiptMatchingFile = kotlinFiles().find { it.name == "ReceiptMatchingWorker.kt" }
        assertNotNull("ReceiptMatchingWorker.kt not found", receiptMatchingFile)
        val text = stripComments(receiptMatchingFile!!.readText())
        assertFalse(
            "ReceiptMatchingWorker must not require global notification permission (PR12K-3)",
            text.contains("requiresNotificationPermission = true")
        )
    }

    @Test
    fun `workers_with_broad_catch_must_rethrow_cancellation`() {
        // PR12K-6: Existing workers with broad catch that predate this rule.
        // Each entry must have owner, reason, issue, and expiry.
        val broadCatchAllowlist = listOf(
            ArchitectureAllowlistEntry(
                fileName = "LocationBackfillWorker.kt",
                rule = "BROAD_CATCH_NO_CANCELLATION_RETHROW",
                owner = "WorkerArchitecture",
                reason = "Legacy worker — CancellationException rethrow to be added",
                issue = "PR12K-6",
                expires = LocalDate.of(2026, 9, 30)
            ),
            ArchitectureAllowlistEntry(
                fileName = "MerchantKeyBackfillWorker.kt",
                rule = "BROAD_CATCH_NO_CANCELLATION_RETHROW",
                owner = "WorkerArchitecture",
                reason = "Legacy worker — CancellationException rethrow to be added",
                issue = "PR12K-6",
                expires = LocalDate.of(2026, 9, 30)
            ),
            ArchitectureAllowlistEntry(
                fileName = "ReceiptMatchingWorker.kt",
                rule = "BROAD_CATCH_NO_CANCELLATION_RETHROW",
                owner = "WorkerArchitecture",
                reason = "Legacy worker — CancellationException rethrow to be added",
                issue = "PR12K-6",
                expires = LocalDate.of(2026, 9, 30)
            ),
            ArchitectureAllowlistEntry(
                fileName = "BillReminderWorker.kt",
                rule = "BROAD_CATCH_NO_CANCELLATION_RETHROW",
                owner = "WorkerArchitecture",
                reason = "Legacy worker — CancellationException rethrow to be added",
                issue = "PR12K-6",
                expires = LocalDate.of(2026, 9, 30)
            ),
            ArchitectureAllowlistEntry(
                fileName = "WarrantyExpirationWorker.kt",
                rule = "BROAD_CATCH_NO_CANCELLATION_RETHROW",
                owner = "WorkerArchitecture",
                reason = "Legacy worker — CancellationException rethrow to be added",
                issue = "PR12K-6",
                expires = LocalDate.of(2026, 9, 30)
            )
        )

        val workerFiles = kotlinFiles().filter { file ->
            val text = stripComments(file.readText())
            text.contains(": CoroutineWorker")
        }
        val allowedFileNames = broadCatchAllowlist.map { it.fileName }.toSet()
        val violations = workerFiles.filter { file ->
            if (file.name in allowedFileNames) return@filter false
            val text = stripComments(file.readText())
            val hasBroadCatch = text.contains("catch (e: Exception)") || text.contains("catch (t: Throwable)")
            val hasCancellationRethrow = text.contains("if (e is CancellationException) throw e") ||
                text.contains("if (e is kotlinx.coroutines.CancellationException) throw e")
            hasBroadCatch && !hasCancellationRethrow
        }
        assertTrue(
            "Workers with broad catch missing CancellationException rethrow: " +
                violations.map { it.relativeTo(sourceRoot).path },
            violations.isEmpty()
        )
    }

    @Test
    fun `urls_in_strings_are_not_mangled_by_comment_stripper`() {
        val sourceWithUrl = """
            val url = "https://example.com/path"
            // this is a comment
            /* block comment */
        """.trimIndent()
        val stripped = stripComments(sourceWithUrl)
        assertTrue("URL in string must survive comment stripping", stripped.contains("https://example.com/path"))
        assertFalse("Line comment must be stripped", stripped.contains("this is a comment"))
        assertFalse("Block comment must be stripped", stripped.contains("block comment"))
    }

    // ── PR12L-5: Additional negative fixtures ───────────────────────────────

    @Test
    fun `negative_fixture_optional_notification_worker_without_local_check_fails`() {
        val badWorker = """
            class BadWorker : CoroutineWorker(context, params) {
                override suspend fun doWork(): Result {
                    // requiresNotificationPermission = false (correct)
                    // BUT no local permission check before posting
                    notificationService.sendBudgetAlert(...)
                    return Result.success()
                }
            }
        """.trimIndent()
        val violations = scanSource("BadWorker.kt", badWorker)
        assertTrue(
            "Scanner must detect OPTIONAL_NOTIFICATION_WITHOUT_LOCAL_CHECK",
            violations.contains("OPTIONAL_NOTIFICATION_WITHOUT_LOCAL_CHECK")
        )
    }

    @Test
    fun `negative_fixture_data_retention_with_raw_retention_capability_fails`() {
        val badWorker = """
            class BadWorker : CoroutineWorker(context, params) {
                override suspend fun doWork(): Result {
                    executionGuard.runGuardedWithContext(
                        WorkerGuardRequest(
                            workerName = "data_retention",
                            requiredCapabilities = listOf(
                                PrivacyCapability.RAW_NOTIFICATION_RETENTION,
                                PrivacyCapability.RAW_OCR_RETENTION
                            )
                        )
                    )
                    return Result.success()
                }
            }
        """.trimIndent()
        val violations = scanSource("BadWorker.kt", badWorker)
        assertTrue(
            "Scanner must detect DATA_RETENTION_RAW_CAPABILITY",
            violations.contains("DATA_RETENTION_RAW_CAPABILITY")
        )
    }

    @Test
    fun `negative_fixture_expired_allowlist_entry_fails`() {
        val expiredEntry = ArchitectureAllowlistEntry(
            fileName = "ExpiredWorker.kt",
            rule = "DIRECT_DAO_IN_WORKER",
            owner = "WorkerArchitecture",
            reason = "Expired entry",
            issue = "PR12L-5",
            expires = LocalDate.of(2020, 1, 1)
        )
        val today = LocalDate.now()
        assertTrue(
            "Expired allowlist entry must be detected: ${expiredEntry.fileName}",
            expiredEntry.expires.isBefore(today)
        )
    }
}
