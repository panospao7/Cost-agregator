package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Pipeline 9 (S8) — Static architecture guard for background workers.
 *
 * Asserts that EVERY production `CoroutineWorker` either routes its work through
 * [com.yourname.expensetracker.domain.workers.WorkerExecutionGuard] (via
 * `runGuarded` / `runGuardedWithContext`) OR is an explicitly documented,
 * intentional exemption in [ALLOWLISTED_WORKERS].
 *
 * Why this matters: the guard centralises restore/maintenance barriers, worker
 * lease acquisition, privacy + notification-permission gating, and durable run
 * logging. A worker that bypasses it silently re-introduces the exact regressions
 * Pipeline 9 was built to prevent. This test fails loudly the moment a new worker
 * is added without the guard, forcing an explicit decision: guard it, or justify
 * an allowlist entry with a documented rationale.
 *
 * ── Allowlist rationale ──────────────────────────────────────────────────────
 *  • NotificationIntakeWorker — Pipeline-1 notification intake worker. It is NOT
 *    a WorkerSpec/Registry-scheduled job; it drains a single queued intake row
 *    using its own [com.yourname.expensetracker.data.backup.DatabaseWriteBarrier]
 *    check plus an attempt/backoff state machine, and is intentionally NOT routed
 *    through WorkerExecutionGuard.
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Note: SourceLinkBackfillWorker (domain/provenance) is deliberately NOT in the
 * allowlist because it does not extend CoroutineWorker — it is a `@Singleton`
 * injected helper exposing a `runBackfill` suspend function, so it is never
 * scanned by this guard.
 */
class WorkerGuardArchitectureGuardTest {

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

    private companion object {
        /**
         * Worker simple names intentionally exempt from the WorkerExecutionGuard
         * requirement. Keep this set minimal — every entry MUST have a documented
         * justification in the class KDoc above. The companion tests also fail if
         * an entry here does not map to a real worker file (stale allowlist) or if
         * an allowlisted worker actually uses the guard (redundant exemption).
         */
        val ALLOWLISTED_WORKERS = setOf(
            "NotificationIntakeWorker"
        )
    }

    /**
     * Matches the supertype clause `: CoroutineWorker` of a class declaration.
     * The `androidx.work.CoroutineWorker` import line does not match because it is
     * preceded by a `.` rather than a `:`, and `\s*` only spans contiguous
     * whitespace so it cannot bridge an unrelated colon to a distant token.
     */
    private val coroutineWorkerSupertype = Regex(""":\s*CoroutineWorker\b""")

    /**
     * Matches an actual guard invocation: `runGuarded(` or `runGuardedWithContext(`.
     * Receiver-agnostic so a worker that injects the guard under a different field
     * name is still recognised, while a worker that never calls the guard is not.
     */
    private val guardInvocation = Regex("""runGuarded(WithContext)?\s*\(""")

    private fun discoverCoroutineWorkerFiles(): List<File> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { file ->
                val text = runCatching { file.readText() }.getOrNull() ?: return@filter false
                coroutineWorkerSupertype.containsMatchIn(text)
            }
            .toList()

    @Test
    fun `every CoroutineWorker uses WorkerExecutionGuard or is explicitly allowlisted`() {
        val workerFiles = discoverCoroutineWorkerFiles()

        // Guard against a broken source path silently making this test vacuously pass.
        assertTrue(
            "Worker architecture guard scanned ZERO CoroutineWorker files in ${sourceRoot.absolutePath}. " +
                "A broken source-root resolver would make this test pass without checking anything.",
            workerFiles.isNotEmpty()
        )

        val violations = mutableListOf<String>()
        for (file in workerFiles) {
            val name = file.nameWithoutExtension
            if (name in ALLOWLISTED_WORKERS) continue
            val text = file.readText()
            if (!guardInvocation.containsMatchIn(text)) {
                violations.add(
                    "${file.name}: extends CoroutineWorker but never calls runGuarded/runGuardedWithContext " +
                        "(WorkerExecutionGuard). Route it through the guard, or — only if it is a genuinely " +
                        "exempt non-registry worker — add it to ALLOWLISTED_WORKERS with a documented rationale."
                )
            }
        }

        assertTrue(
            "CoroutineWorker(s) bypass WorkerExecutionGuard:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun `allowlist contains only real worker files - no allowlist creep`() {
        val discoveredNames = discoverCoroutineWorkerFiles().map { it.nameWithoutExtension }.toSet()
        val stale = ALLOWLISTED_WORKERS.filter { it !in discoveredNames }
        assertTrue(
            "ALLOWLISTED_WORKERS contains entries that are not actual CoroutineWorker source files: $stale. " +
                "Remove stale entries — every allowlisted name must map to a real worker.",
            stale.isEmpty()
        )
    }

    @Test
    fun `allowlisted workers genuinely do not use the guard`() {
        // Keeps the allowlist honest: if an allowlisted worker is later wired
        // through WorkerExecutionGuard, it no longer needs the exemption and must
        // be removed from ALLOWLISTED_WORKERS so the allowlist stays meaningful.
        val redundant = mutableListOf<String>()
        for (file in discoverCoroutineWorkerFiles()) {
            val name = file.nameWithoutExtension
            if (name !in ALLOWLISTED_WORKERS) continue
            if (guardInvocation.containsMatchIn(file.readText())) {
                redundant.add(name)
            }
        }
        assertTrue(
            "Allowlisted worker(s) actually USE WorkerExecutionGuard and should be removed from " +
                "ALLOWLISTED_WORKERS: $redundant",
            redundant.isEmpty()
        )
    }
}
