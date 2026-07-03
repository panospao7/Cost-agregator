package com.yourname.expensetracker.architecture

import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.LocalDate

/**
 * PR21-1: Ensures [com.yourname.expensetracker.domain.transaction.TransactionContext]
 * is only constructed inside [DomainTransactionRunner] or approved test/migration code.
 *
 * This guard scans all Kotlin source files in `app/src/main/java/` for manual
 * `TransactionContext(` constructor calls and fails unless the file is in the
 * allowlist.
 *
 * Each allowlist entry records owner, reason, issue tracking, and expiry date —
 * ensuring no allowlisted entry is left unaccounted for.
 */
class TransactionContextProvenanceGuardTest {

    data class ProvenanceAllowlistEntry(
        val fileName: String,
        val owner: String,
        val reason: String,
        val issue: String,
        val expires: LocalDate
    )

    private val ALLOWLIST = listOf(
        // RoomDomainTransactionRunner creates contexts — the canonical factory
        ProvenanceAllowlistEntry(
            "RoomDomainTransactionRunner.kt", "Transaction",
            "Canonical TransactionContext factory in production",
            "MIT-031", LocalDate.of(2026, 12, 31)
        ),
        // DomainTransactionRunner interface definition references context in KDoc
        ProvenanceAllowlistEntry(
            "DomainTransactionRunner.kt", "Transaction",
            "Interface definition references context in KDoc and parameter types",
            "MIT-031", LocalDate.of(2026, 12, 31)
        ),
        // TransactionContext data class itself — copy() calls its own constructor
        ProvenanceAllowlistEntry(
            "TransactionContext.kt", "Transaction",
            "Data class definition — copy() calls internal constructor",
            "MIT-031", LocalDate.of(2026, 12, 31)
        ),
        // ReceiptLifecycleEventWriter — deprecated context-free write with @Suppress(DEPRECATION_ERROR)
        ProvenanceAllowlistEntry(
            "ReceiptLifecycleEventWriter.kt", "Transaction",
            "Deprecated context-free write implementation uses @Suppress(DEPRECATION_ERROR)",
            "MIT-031", LocalDate.of(2026, 12, 31)
        ),
        // TransactionLifecycleEventWriter — deprecated context-free write with @Suppress(DEPRECATION_ERROR)
        ProvenanceAllowlistEntry(
            "TransactionLifecycleEventWriter.kt", "Transaction",
            "Deprecated context-free write implementation uses @Suppress(DEPRECATION_ERROR)",
            "MIT-031", LocalDate.of(2026, 12, 31)
        ),
        // TransactionSideEffectFailureEventWriter — migrated in PR20
        ProvenanceAllowlistEntry(
            "TransactionSideEffectFailureEventWriter.kt", "Transaction",
            "Side-effect failure writer migrated in PR20",
            "MIT-031", LocalDate.of(2026, 12, 31)
        ),
        // PR20 manual-context callers — must migrate to DomainTransactionRunner
        ProvenanceAllowlistEntry(
            "GroupTransactionCoordinator.kt", "Transaction",
            "PR20 manual context — must migrate to DomainTransactionRunner",
            "MIT-031", LocalDate.of(2026, 8, 15)
        ),
        ProvenanceAllowlistEntry(
            "NotificationProcessingPipeline.kt", "Transaction",
            "PR20 manual context — must migrate to DomainTransactionRunner",
            "MIT-031", LocalDate.of(2026, 8, 15)
        ),
        ProvenanceAllowlistEntry(
            "WarrantyTrackerRepository.kt", "Transaction",
            "PR20 manual context — must migrate to DomainTransactionRunner",
            "MIT-031", LocalDate.of(2026, 8, 15)
        ),
        ProvenanceAllowlistEntry(
            "ReceiptLinkService.kt", "Transaction",
            "PR20 manual context — must migrate to DomainTransactionRunner",
            "MIT-031", LocalDate.of(2026, 8, 15)
        ),
    )

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

    /**
     * Main guard: scans all production .kt files for `TransactionContext(` constructor
     * calls outside the allowlist.
     *
     * The regex matches the constructor call pattern `TransactionContext(correlationId`
     * which is the canonical form used in constructor calls. It does NOT match
     * type references or KDoc mentions that lack the opening parenthesis.
     */
    @Test
    fun `manual TransactionContext construction outside allowlist fails`() {
        val mainDir = sourceRoot
        val pattern = Regex("""TransactionContext\(\s*correlationId""")

        val allKtFiles = mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        val allowlistFileNames = ALLOWLIST.map { it.fileName }.toSet()

        assertTrue(
            "Architecture guard scanned ZERO .kt files in ${mainDir.absolutePath}. " +
                "Source root resolution is broken — this test would pass vacuously.",
            allKtFiles.size >= 10
        )

        val violations = mutableListOf<String>()

        for (file in allKtFiles) {
            if (file.name in allowlistFileNames) continue
            val content = file.readText()
            if (!pattern.containsMatchIn(content)) continue

            val relativePath = file.relativeTo(mainDir).path
            val matches = pattern.findAll(content).toList()
            for (match in matches) {
                val lineNum = content.substring(0, match.range.first).count { it == '\n' } + 1
                violations.add(
                    "$relativePath:$lineNum — TransactionContext() construction " +
                        "outside allowlisted files"
                )
            }
        }

        assertTrue(
            "PR21-1 violations: manual TransactionContext construction from non-allowlisted files.\n" +
                "Add the file to ALLOWLIST with owner/reason/issue/expiry, " +
                "or route construction through DomainTransactionRunner.\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    // ── Structured allowlist validation ──────────────────────────────────────

    @Test
    fun `allowlist entries map to real source files`() {
        val allKtNames = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name }
            .toSet()
        val stale = ALLOWLIST.filter { it.fileName !in allKtNames }
        assertTrue(
            "ALLOWLIST contains entries that don't map to real source files: " +
                stale.map { it.fileName } +
                ". Remove stale/renamed entries.",
            stale.isEmpty()
        )
    }

    @Test
    fun `structured allowlist requires owner reason issue expiry`() {
        for (entry in ALLOWLIST) {
            assertTrue(
                "Allowlist entry ${entry.fileName} missing owner",
                entry.owner.isNotBlank()
            )
            assertTrue(
                "Allowlist entry ${entry.fileName} missing reason",
                entry.reason.isNotBlank()
            )
            assertTrue(
                "Allowlist entry ${entry.fileName} missing issue",
                entry.issue.isNotBlank()
            )
            assertNotNull(
                "Allowlist entry ${entry.fileName} missing expiry",
                entry.expires
            )
        }
    }

    @Test
    fun `expired provenance allowlist entries fail`() {
        val today = LocalDate.now()
        val expired = ALLOWLIST.filter { it.expires.isBefore(today) }
        assertTrue(
            "Expired provenance allowlist entries found: " +
                expired.map { it.fileName },
            expired.isEmpty()
        )
    }

    @Test
    fun `no duplicate provenance allowlist entries`() {
        val duplicates = ALLOWLIST.groupBy { it.fileName }
            .filter { it.value.size > 1 }
            .keys
        assertTrue(
            "Duplicate provenance allowlist entries found: $duplicates",
            duplicates.isEmpty()
        )
    }
}
