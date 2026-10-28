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
        // ai-fixes B3a (INV-24, INV-29): DailyBriefingWorker and WarrantyExpirationWorker
        // follow the same pattern so generation and expiry reconciliation never depend on
        // notification permission; only posting is gated locally.
        val optionalNotificationWorkers = setOf(
            "ReceiptMatchingWorker.kt",
            "DailyBriefingWorker.kt",
            "WarrantyExpirationWorker.kt"
        )

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

    // ── ai-fixes B1a guard (a): cloud warranty extraction has no production caller ────
    // INV-5 / AE-22 / G2-4. `CloudWarrantyExtractionService` is retained but unreachable: in
    // app/src/main it may be referenced only from di/AiModule.kt (the Hilt provider). Its own
    // file and WarrantyExtractionModels.kt are exempt. Reintroducing a caller needs a new reviewed
    // increment (A6), not an allowlist edit here.

    private val cloudWarrantyServiceToken = "CloudWarrantyExtractionService"

    /** Source-root-relative paths (forward slashes) that may mention the service token. */
    private val cloudWarrantyServiceAllowedFiles = setOf(
        "com/yourname/expensetracker/di/AiModule.kt",
        // Exempt: the service's own file and its model types.
        "com/yourname/expensetracker/data/ai/provider/CloudWarrantyExtractionService.kt",
        "com/yourname/expensetracker/domain/ai/model/WarrantyExtractionModels.kt"
    )

    /**
     * Pure scan used by the production test and its negative fixture: returns the relative paths
     * (outside [allowed]) whose comment-stripped text mentions the service token.
     */
    private fun cloudWarrantyServiceReferences(
        sources: Map<String, String>,
        allowed: Set<String>
    ): List<String> = sources
        .filter { (path, _) -> path !in allowed }
        .filter { (_, text) -> stripComments(text).contains(cloudWarrantyServiceToken) }
        .keys
        .sorted()

    private fun relativeSources(): Map<String, String> = kotlinFiles().associate { file ->
        file.relativeTo(sourceRoot).path.replace('\\', '/') to file.readText()
    }

    @Test
    fun `negative_fixture_cloud_warranty_service_reference_outside_di_is_caught`() {
        val sources = mapOf(
            "com/yourname/expensetracker/di/AiModule.kt" to
                "fun provide(): CloudWarrantyExtractionService = CloudWarrantyExtractionService()",
            "com/yourname/expensetracker/data/repository/BadRepository.kt" to
                "class BadRepository @Inject constructor(private val svc: CloudWarrantyExtractionService)",
            "com/yourname/expensetracker/data/repository/OkRepository.kt" to
                "// CloudWarrantyExtractionService is not used here\nclass OkRepository"
        )
        val violations = cloudWarrantyServiceReferences(sources, cloudWarrantyServiceAllowedFiles)
        assertEquals(
            "Scanner must flag only the non-DI, non-exempt reference (comments are stripped)",
            listOf("com/yourname/expensetracker/data/repository/BadRepository.kt"),
            violations
        )
    }

    @Test
    fun `cloud_warranty_extraction_service_is_referenced_in_production_only_from_AiModule`() {
        val sources = relativeSources()
        // Fail closed: the scan must actually see the files it relies on.
        assertTrue("Source scan found too few files (${sources.size}); source root may be wrong", sources.size > 100)
        for (required in cloudWarrantyServiceAllowedFiles) {
            assertTrue("Expected production file not found by the scan: $required", required in sources)
        }
        assertTrue(
            "di/AiModule.kt must still provide CloudWarrantyExtractionService (retained, unreachable)",
            stripComments(sources.getValue("com/yourname/expensetracker/di/AiModule.kt")).contains(cloudWarrantyServiceToken)
        )

        val violations = cloudWarrantyServiceReferences(sources, cloudWarrantyServiceAllowedFiles)
        assertTrue(
            "CloudWarrantyExtractionService may be referenced in app/src/main only from di/AiModule.kt " +
                "(INV-5, AE-22). Offending files: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun `warranty_tracker_repository_has_no_cloud_or_ai_extraction_references`() {
        val sources = relativeSources()
        val repoPath = "com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt"
        assertTrue("WarrantyTrackerRepository.kt not found by the scan", repoPath in sources)
        val text = stripComments(sources.getValue(repoPath))
        val forbidden = listOf(
            cloudWarrantyServiceToken,
            "AiCapabilityRouter",
            "WarrantyExtractionResult",
            "AUTO_ACCEPT_CLOUD_CONFIDENCE"
        )
        val present = forbidden.filter { text.contains(it) }
        assertTrue(
            "WarrantyTrackerRepository must not reference cloud/AI warranty extraction (INV-5, AE-22): $present",
            present.isEmpty()
        )
    }

    // ── ai-fixes B1b guard (c): the warranty ViewModel never builds review/status transitions ────
    // INV-13 / FG-12. The only legal user-driven exits from review are
    // WarrantyTrackerRepository.confirmWarranty(id) and rejectAutoDetectedWarranty. The ViewModel
    // must not call updateWarranty(...) (its only former use was the confirm copy; S0 D-4) and must
    // not build a copy(...) that sets `status =` or `needsReview =`. Fail-closed: a missing file or
    // an unbalanced copy( call is reported as a violation.

    private val warrantyViewModelPath =
        "com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerViewModel.kt"

    private val reviewFieldAssignment = Regex("""\b(status|needsReview)\s*=(?!=)""")
    private val copyCall = Regex("""\bcopy\s*\(""")

    /** Pure scan: returns bounded violation codes for a ViewModel source text. */
    private fun warrantyViewModelReviewViolations(source: String): List<String> {
        val text = stripComments(source)
        val violations = mutableListOf<String>()
        if (text.contains("updateWarranty(")) violations += "UPDATE_WARRANTY_CALL"

        for (match in copyCall.findAll(text)) {
            var depth = 1
            var index = match.range.last + 1
            val start = index
            while (index < text.length && depth > 0) {
                when (text[index]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                index++
            }
            if (depth != 0) {
                violations += "UNBALANCED_COPY_CALL"
                continue
            }
            val args = text.substring(start, index - 1)
            if (reviewFieldAssignment.containsMatchIn(args)) violations += "COPY_SETS_STATUS_OR_NEEDS_REVIEW"
        }
        return violations
    }

    @Test
    fun `negative_fixture_warranty_view_model_review_transition_is_caught`() {
        assertEquals(
            listOf("UPDATE_WARRANTY_CALL"),
            warrantyViewModelReviewViolations("repo.updateWarranty(w)")
        )
        assertEquals(
            listOf("COPY_SETS_STATUS_OR_NEEDS_REVIEW"),
            warrantyViewModelReviewViolations("val u = w.copy(status = WarrantyStatus.ACTIVE, updatedAt = 1L)")
        )
        assertEquals(
            listOf("COPY_SETS_STATUS_OR_NEEDS_REVIEW"),
            warrantyViewModelReviewViolations("val u = w.copy(\n    needsReview = false\n)")
        )
        assertEquals(
            "implicit-receiver copy must also be scanned",
            listOf("COPY_SETS_STATUS_OR_NEEDS_REVIEW"),
            warrantyViewModelReviewViolations("fun S.f() = copy(needsReview = false)")
        )
        assertEquals(
            "fail-closed on an unbalanced copy call",
            listOf("UNBALANCED_COPY_CALL"),
            warrantyViewModelReviewViolations("val u = w.copy(status = X")
                .filter { it == "UNBALANCED_COPY_CALL" }
        )
        assertTrue(
            "comments and unrelated copy arguments must not be flagged",
            warrantyViewModelReviewViolations(
                "// repo.updateWarranty(w) and w.copy(status = X)\n" +
                    "val s = state.copy(selectedFilter = status, showNeedsReviewOnly = true, needsReviewCount = 2)"
            ).isEmpty()
        )
    }

    @Test
    fun `warranty_view_model_never_builds_status_or_needs_review_transitions`() {
        val sources = relativeSources()
        assertTrue("Source scan found too few files (${sources.size}); source root may be wrong", sources.size > 100)
        assertTrue("WarrantyTrackerViewModel.kt not found by the scan", warrantyViewModelPath in sources)

        val violations = warrantyViewModelReviewViolations(sources.getValue(warrantyViewModelPath))
        assertTrue(
            "WarrantyTrackerViewModel must delegate review transitions to WarrantyTrackerRepository " +
                "(confirmWarranty / rejectAutoDetectedWarranty): no updateWarranty( and no copy( setting " +
                "status/needsReview (INV-13, FG-12). Violations: $violations",
            violations.isEmpty()
        )
    }

    // ── ai-fixes Part C guard (Ca): redaction policy is resolved only in the input builder ────
    // AE-C30. AssistantViewModel must not decide redaction (`redactBeforeCloud`); the
    // FinancialQueryInterpretationInputBuilder must not read privacy settings, call any `.shouldRedact(`
    // or read `settings.redactBeforeCloud` (its only legitimate source is the effective-policy resolver).
    // Fail-closed: a missing file is reported as a violation.

    private val partCAssistantViewModelPath =
        "com/yourname/expensetracker/ui/screens/assistant/AssistantViewModel.kt"
    private val partCInputBuilderPath =
        "com/yourname/expensetracker/domain/ai/usecase/FinancialQueryInterpretationInputBuilder.kt"

    private val partCViewModelForbidden = listOf("redactBeforeCloud")
    private val partCBuilderForbidden = listOf(
        "privacySettingsRepository",
        "PrivacySettingsRepository",
        "aiPolicy.shouldRedact",
        ".shouldRedact(",
        "settings.redactBeforeCloud"
    )

    /** Pure scan: returns the forbidden tokens present in the comment-stripped source. */
    private fun forbiddenTokensPresent(source: String, forbidden: List<String>): List<String> {
        val text = stripComments(source)
        return forbidden.filter { text.contains(it) }
    }

    @Test
    fun `Part C (Ca) negative_fixture_forbidden_redaction_tokens_are_caught`() {
        assertEquals(
            listOf("redactBeforeCloud"),
            forbiddenTokensPresent("val r = policy.redactBeforeCloud", partCViewModelForbidden)
        )
        assertEquals(
            listOf("privacySettingsRepository", "aiPolicy.shouldRedact"),
            forbiddenTokensPresent(
                "class B(private val privacySettingsRepository: X) { fun f() = aiPolicy.shouldRedact }",
                partCBuilderForbidden
            )
        )
        assertEquals(
            listOf("PrivacySettingsRepository"),
            forbiddenTokensPresent("class B(r: PrivacySettingsRepository)", partCBuilderForbidden)
        )
        assertEquals(
            listOf(".shouldRedact("),
            forbiddenTokensPresent("val r = policy.shouldRedact(settings)", partCBuilderForbidden)
        )
        assertEquals(
            listOf("aiPolicy.shouldRedact", ".shouldRedact("),
            forbiddenTokensPresent("val r = aiPolicy.shouldRedact(settings)", partCBuilderForbidden)
        )
        assertEquals(
            listOf("settings.redactBeforeCloud"),
            forbiddenTokensPresent("val r = settings.redactBeforeCloud", partCBuilderForbidden)
        )
        assertTrue(
            "the builder's legitimate resolver read and local shouldRedact flag must not be flagged",
            forbiddenTokensPresent(
                "val shouldRedact = a && effectiveCloudAiPolicyResolver.resolve().redactBeforeCloud\n" +
                    "private fun f(value: String, shouldRedact: Boolean) = g(value, shouldRedact = true)",
                partCBuilderForbidden
            ).isEmpty()
        )
        assertTrue(
            "comments must not be flagged",
            forbiddenTokensPresent(
                "// redactBeforeCloud privacySettingsRepository aiPolicy.shouldRedact " +
                    "x.shouldRedact( settings.redactBeforeCloud\nclass Ok",
                partCViewModelForbidden + partCBuilderForbidden
            ).isEmpty()
        )
    }

    @Test
    fun `Part C (Ca) assistant_view_model_and_input_builder_do_not_resolve_redaction_policy_themselves`() {
        val sources = relativeSources()
        assertTrue("Source scan found too few files (${sources.size}); source root may be wrong", sources.size > 100)
        assertTrue("AssistantViewModel.kt not found by the scan", partCAssistantViewModelPath in sources)
        assertTrue(
            "FinancialQueryInterpretationInputBuilder.kt not found by the scan",
            partCInputBuilderPath in sources
        )

        val viewModelViolations =
            forbiddenTokensPresent(sources.getValue(partCAssistantViewModelPath), partCViewModelForbidden)
        assertTrue(
            "AssistantViewModel must not reference redactBeforeCloud (AE-C30): $viewModelViolations",
            viewModelViolations.isEmpty()
        )

        val builderViolations =
            forbiddenTokensPresent(sources.getValue(partCInputBuilderPath), partCBuilderForbidden)
        assertTrue(
            "FinancialQueryInterpretationInputBuilder must not read privacy settings, call .shouldRedact( " +
                "or read settings.redactBeforeCloud (AE-C30): $builderViolations",
            builderViolations.isEmpty()
        )
    }

    // ── ai-fixes Part C guard (Cb): single construction site for the interpretation input ────
    // PC-9 item 2 / spec §8 guard (b). Within app/src/main/java the only file that constructs
    // FinancialQueryInterpretationInput (directly, via `::FinancialQueryInterpretationInput`, or by
    // `.copy(priorUserQueries = ...)` / a named `priorUserQueries =` argument) is
    // FinancialQueryInterpretationInputBuilder.kt. The data class declaration in
    // FinancialQueryModels.kt (`priorUserQueries: List<String> = ...`) is exempt.

    private val interpretationInputConstructorCall =
        Regex("""(?<!data class )\bFinancialQueryInterpretationInput\(""")

    /** Constructor reference form, e.g. `.map(::FinancialQueryInterpretationInput)`. */
    private val interpretationInputConstructorReference =
        Regex("""::FinancialQueryInterpretationInput\b""")

    /**
     * Assigns `priorUserQueries` (copy(...) or named ctor arg). The declaration form
     * `priorUserQueries: List<String> = ...` has a `:` before the `=`, and `==` comparisons are excluded.
     */
    private val priorUserQueriesAssignment = Regex("""\bpriorUserQueries\s*=(?!=)""")

    /** Pure scan: returns relative paths whose comment-stripped text constructs the input type. */
    private fun interpretationInputConstructionSites(sources: Map<String, String>): List<String> = sources
        .filter { (_, text) -> interpretationInputConstructorCall.containsMatchIn(stripComments(text)) }
        .keys
        .sorted()

    /**
     * Pure scan: returns relative paths, other than the builder, that reference the input constructor
     * or assign `priorUserQueries` (bypassing the builder's USER-only/redaction handling).
     */
    private fun interpretationInputBypassSites(sources: Map<String, String>): List<String> = sources
        .filter { (path, _) -> path != partCInputBuilderPath }
        .filter { (_, text) ->
            val stripped = stripComments(text)
            interpretationInputConstructorReference.containsMatchIn(stripped) ||
                priorUserQueriesAssignment.containsMatchIn(stripped)
        }
        .keys
        .sorted()

    @Test
    fun `Part C (Cb) negative_fixture_extra_interpretation_input_construction_site_is_caught`() {
        val sources = mapOf(
            partCInputBuilderPath to "return FinancialQueryInterpretationInput(rawQuery = q)",
            "com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt" to
                "data class FinancialQueryInterpretationInput(val rawQuery: String)",
            "com/yourname/expensetracker/ui/screens/assistant/BadViewModel.kt" to
                "val i = FinancialQueryInterpretationInput(rawQuery = q)",
            "com/yourname/expensetracker/ui/screens/assistant/OkViewModel.kt" to
                "// FinancialQueryInterpretationInput(rawQuery = q)\nclass OkViewModel"
        )
        assertEquals(
            "Scanner must flag the builder and the planted caller, but not the declaration or comments",
            listOf(
                partCInputBuilderPath,
                "com/yourname/expensetracker/ui/screens/assistant/BadViewModel.kt"
            ).sorted(),
            interpretationInputConstructionSites(sources)
        )
    }

    @Test
    fun `Part C (Cb) financial_query_interpretation_input_is_constructed_only_by_the_input_builder`() {
        val sources = relativeSources()
        assertTrue("Source scan found too few files (${sources.size}); source root may be wrong", sources.size > 100)

        val sites = interpretationInputConstructionSites(sources)
        assertTrue(
            "Fail closed: FinancialQueryInterpretationInputBuilder.kt must construct the input (found: $sites)",
            partCInputBuilderPath in sites
        )
        val offenders = sites.filter { it != partCInputBuilderPath }
        assertTrue(
            "FinancialQueryInterpretationInput may be constructed in app/src/main only by " +
                "FinancialQueryInterpretationInputBuilder (PC-9 item 2 / spec §8 guard (b)). " +
                "Offending files: $offenders",
            offenders.isEmpty()
        )

        val bypassSites = interpretationInputBypassSites(sources)
        assertTrue(
            "No file other than FinancialQueryInterpretationInputBuilder may assign priorUserQueries " +
                "(copy(priorUserQueries = ...) / named argument) or reference " +
                "::FinancialQueryInterpretationInput (PC-9 item 2 / spec §8 guard (b)). " +
                "Offending files: $bypassSites",
            bypassSites.isEmpty()
        )
    }

    @Test
    fun `Part C (Cb) negative_fixture_prior_user_queries_assignment_and_constructor_reference_are_caught`() {
        val modelsPath = "com/yourname/expensetracker/domain/ai/model/FinancialQueryModels.kt"
        val copyPath = "com/yourname/expensetracker/data/ai/provider/BadCopyService.kt"
        val namedArgPath = "com/yourname/expensetracker/ui/screens/assistant/BadNamedArg.kt"
        val referencePath = "com/yourname/expensetracker/ui/screens/assistant/BadReference.kt"
        val okLookupCopyPath = "com/yourname/expensetracker/data/ai/provider/OkLookupCopy.kt"
        val okCommentPath = "com/yourname/expensetracker/ui/screens/assistant/OkComment.kt"
        val okComparisonPath = "com/yourname/expensetracker/ui/screens/assistant/OkComparison.kt"
        val sources = mapOf(
            // The builder is the one allowed assigner.
            partCInputBuilderPath to "return Input(priorUserQueries = userRows)",
            // The declaration (`name: Type = default`) is exempt.
            modelsPath to "data class FinancialQueryInterpretationInput(\n" +
                "    val priorUserQueries: List<String> = emptyList()\n)",
            copyPath to "fun f(i: Input) = i.copy(priorUserQueries = emptyList())",
            namedArgPath to "fun g() = Other(rawQuery = q, priorUserQueries   =   assistantTexts)",
            referencePath to "val make = ::FinancialQueryInterpretationInput",
            // copy() that sets only lookup maps is the legitimate CloudQueryInterpretationService shape.
            okLookupCopyPath to "fun f(i: Input) = i.copy(merchantLookupMap = m, categoryLookupMap = c)",
            okCommentPath to "// copy(priorUserQueries = x) and ::FinancialQueryInterpretationInput\nclass Ok",
            okComparisonPath to "fun same(a: Input, b: Input) = a.priorUserQueries == b.priorUserQueries"
        )
        assertEquals(
            "Scanner must flag copy/named-arg/constructor-reference bypasses only; " +
                "not the builder, the declaration, lookup-map copies, comments or == comparisons",
            listOf(copyPath, namedArgPath, referencePath).sorted(),
            interpretationInputBypassSites(sources)
        )
    }

    // ── ai-fixes B2d guard (b): the transaction insight use case is on-device only ────
    // INV-15 / AE-39. GenerateTransactionInsightUseCase.kt must not import or reference the
    // hybrid or cloud dashboard-briefing services: the per-transaction insight has no cloud path.
    // Fail-closed: a missing file is reported as a failure. Reuses forbiddenTokensPresent (comments stripped).

    private val transactionInsightUseCasePath =
        "com/yourname/expensetracker/domain/ai/usecase/GenerateTransactionInsightUseCase.kt"

    private val transactionInsightForbidden = listOf(
        "HybridDashboardBriefingService",
        "CloudDashboardBriefingService"
    )

    // The bare DashboardBriefingService interface is also forbidden (it can be bound to the hybrid or
    // cloud implementation). Word-boundary match so OnDeviceDashboardBriefingService is not flagged.
    private val bareBriefingServiceInterface = Regex("""\bDashboardBriefingService\b""")

    private fun transactionInsightBriefingViolations(source: String): List<String> {
        val tokens = forbiddenTokensPresent(source, transactionInsightForbidden)
        val bare = if (bareBriefingServiceInterface.containsMatchIn(stripComments(source))) {
            listOf("DashboardBriefingService")
        } else {
            emptyList()
        }
        return tokens + bare
    }

    @Test
    fun `B2d (b) negative_fixture_bare_dashboard_briefing_service_interface_is_caught`() {
        assertEquals(
            listOf("DashboardBriefingService"),
            transactionInsightBriefingViolations(
                "import com.yourname.expensetracker.domain.ai.service.DashboardBriefingService\nclass U"
            )
        )
        assertEquals(
            listOf("DashboardBriefingService"),
            transactionInsightBriefingViolations("class U(private val svc: DashboardBriefingService)")
        )
        assertTrue(
            "OnDeviceDashboardBriefingService and comments must not be flagged by the bare-interface rule",
            transactionInsightBriefingViolations(
                "// DashboardBriefingService\n" +
                    "import com.yourname.expensetracker.domain.ai.service.OnDeviceDashboardBriefingService\n" +
                    "class U(private val svc: OnDeviceDashboardBriefingService)"
            ).isEmpty()
        )
    }

    @Test
    fun `B2d (b) negative_fixture_transaction_insight_cloud_or_hybrid_reference_is_caught`() {
        assertEquals(
            listOf("HybridDashboardBriefingService"),
            forbiddenTokensPresent(
                "import com.yourname.expensetracker.domain.ai.service.HybridDashboardBriefingService\nclass U",
                transactionInsightForbidden
            )
        )
        assertEquals(
            listOf("CloudDashboardBriefingService"),
            forbiddenTokensPresent(
                "class U(private val svc: CloudDashboardBriefingService)",
                transactionInsightForbidden
            )
        )
        assertTrue(
            "the on-device service and comments must not be flagged",
            forbiddenTokensPresent(
                "// HybridDashboardBriefingService CloudDashboardBriefingService\n" +
                    "import com.yourname.expensetracker.domain.ai.service.OnDeviceDashboardBriefingService\nclass U",
                transactionInsightForbidden
            ).isEmpty()
        )
    }

    @Test
    fun `B2d (b) generate_transaction_insight_use_case_imports_neither_hybrid_nor_cloud_briefing_service`() {
        val sources = relativeSources()
        assertTrue("Source scan found too few files (${sources.size}); source root may be wrong", sources.size > 100)
        assertTrue("GenerateTransactionInsightUseCase.kt not found by the scan", transactionInsightUseCasePath in sources)

        val violations = transactionInsightBriefingViolations(sources.getValue(transactionInsightUseCasePath))
        assertTrue(
            "GenerateTransactionInsightUseCase must not reference the hybrid, cloud or bare briefing service " +
                "(INV-15, AE-39): $violations",
            violations.isEmpty()
        )
    }

    // ── ai-fixes B2c carry-forward: foreground observer registration is pinned ────
    // ProcessAppForegroundState is the only source of the foreground signal that gates the transaction
    // insight (INV-17). It must be added as a process lifecycle observer exactly once, and only inside
    // AppStartupCoordinator.registerLifecycleObserver, which initialize() must still call. Removing that
    // line would silently skip every insight, so it is pinned here. Fail-closed on a missing file/body.

    private val appStartupCoordinatorPath =
        "com/yourname/expensetracker/startup/AppStartupCoordinator.kt"

    private val foregroundObserverRegistration =
        Regex("""addObserver\(\s*(foregroundStateObserver|ProcessAppForegroundState)\b""")

    /** Returns the brace-matched body of the named function, or null when absent or unbalanced. */
    private fun functionBody(text: String, functionName: String): String? {
        val declaration = Regex("""\bfun\s+""" + Regex.escape(functionName) + """\s*\(""").find(text) ?: return null
        val open = text.indexOf('{', declaration.range.last)
        if (open < 0) return null
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, i)
                }
            }
        }
        return null
    }

    /** Pure scan: returns human-readable violations of the foreground-observer registration rule. */
    private fun foregroundObserverRegistrationViolations(sources: Map<String, String>): List<String> {
        val violations = mutableListOf<String>()
        sources
            .filter { (path, _) -> path != appStartupCoordinatorPath }
            .filter { (_, text) -> foregroundObserverRegistration.containsMatchIn(stripComments(text)) }
            .keys.sorted()
            .forEach { violations += "registered outside AppStartupCoordinator: $it" }

        val coordinator = sources[appStartupCoordinatorPath]?.let { stripComments(it) }
        if (coordinator == null) {
            violations += "AppStartupCoordinator.kt not found"
            return violations
        }
        val body = functionBody(coordinator, "registerLifecycleObserver")
        if (body == null) {
            violations += "registerLifecycleObserver body not found"
            return violations
        }
        val inBody = foregroundObserverRegistration.findAll(body).count()
        val inFile = foregroundObserverRegistration.findAll(coordinator).count()
        if (inBody != 1) violations += "registerLifecycleObserver must register the foreground observer exactly once (found $inBody)"
        if (inFile != inBody) violations += "foreground observer also registered outside registerLifecycleObserver"
        val callSites = Regex("""\bregisterLifecycleObserver\s*\(\s*\)""").findAll(coordinator).count()
        if (callSites < 2) violations += "registerLifecycleObserver() is declared but never called"
        return violations
    }

    @Test
    fun `B2c negative_fixture_foreground_observer_registration_violations_are_caught`() {
        val okCoordinator = "class C { fun initialize() { registerLifecycleObserver() }\n" +
            "private fun registerLifecycleObserver() {\n" +
            "  ProcessLifecycleOwner.get().lifecycle.addObserver(backgroundLifecycleObserver)\n" +
            "  ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundStateObserver)\n}\n}"
        assertTrue(
            "the legitimate registration must not be flagged",
            foregroundObserverRegistrationViolations(mapOf(appStartupCoordinatorPath to okCoordinator)).isEmpty()
        )

        val removed = okCoordinator.replace("  ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundStateObserver)\n", "")
        assertEquals(
            listOf("registerLifecycleObserver must register the foreground observer exactly once (found 0)"),
            foregroundObserverRegistrationViolations(mapOf(appStartupCoordinatorPath to removed))
        )

        val moved = okCoordinator.replace(
            "  ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundStateObserver)\n", ""
        ) + "\nfun other() { x.addObserver(foregroundStateObserver) }"
        assertEquals(
            listOf(
                "registerLifecycleObserver must register the foreground observer exactly once (found 0)",
                "foreground observer also registered outside registerLifecycleObserver"
            ),
            foregroundObserverRegistrationViolations(mapOf(appStartupCoordinatorPath to moved))
        )

        val uncalled = okCoordinator.replace("registerLifecycleObserver() }", "}")
        assertEquals(
            listOf("registerLifecycleObserver() is declared but never called"),
            foregroundObserverRegistrationViolations(mapOf(appStartupCoordinatorPath to uncalled))
        )

        val elsewhere = mapOf(
            appStartupCoordinatorPath to okCoordinator,
            "com/yourname/expensetracker/ui/Other.kt" to "fun f() { lifecycle.addObserver(foregroundStateObserver) }",
            "com/yourname/expensetracker/ui/Commented.kt" to "// lifecycle.addObserver(foregroundStateObserver)\nclass Ok"
        )
        assertEquals(
            listOf("registered outside AppStartupCoordinator: com/yourname/expensetracker/ui/Other.kt"),
            foregroundObserverRegistrationViolations(elsewhere)
        )
    }

    @Test
    fun `B2c foreground_state_observer_is_registered_only_in_app_startup_coordinator_register_lifecycle_observer`() {
        val sources = relativeSources()
        assertTrue("Source scan found too few files (${sources.size}); source root may be wrong", sources.size > 100)
        val violations = foregroundObserverRegistrationViolations(sources)
        assertTrue(
            "ProcessAppForegroundState must be added as a lifecycle observer exactly once, only inside " +
                "AppStartupCoordinator.registerLifecycleObserver (INV-17). Violations: $violations",
            violations.isEmpty()
        )
    }
}
