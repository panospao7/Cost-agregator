package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * PR8 — Engine 5 primitive architecture guards.
 *
 * Prevents new violations of Engine 5 primitives (deprecated domain.model.PeriodRange,
 * direct System.currentTimeMillis() in domain core packages, raw CurrencyCode constructor
 * calls in domain/core).
 *
 * Allowlists are intentionally strict — every entry must map to a real file that is either
 * the definition site or a pre-existing (pre-PR8) usage site. Any new production code
 * that triggers these patterns will fail the corresponding test, forcing an explicit
 * decision: migrate to the Engine 5 alternative, or document the exemption.
 */
class Engine5PrimitiveGuardTest {

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

    private fun allKtFiles(root: File): Sequence<File> {
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
    }

    private fun relativePath(root: File, file: File): String {
        return file.absolutePath.removePrefix(root.absolutePath).trimStart(File.separatorChar)
            .replace('\\', '/')
    }

    // ── Guard 1: No new imports of the deprecated domain.model.PeriodRange ─────

    /**
     * E5-GUARD-001: Prevents NEW production code from importing the deprecated
     * [com.yourname.expensetracker.domain.model.PeriodRange].
     *
     * Allowlisted files (known holdovers still using the deprecated type):
     *   - InsightsEngine.kt
     *   - ExecuteFinancialQueryUseCase.kt
     *   - InterpretFinancialQueryUseCase.kt
     *   - FinancialQueryModels.kt
     *   - OnDeviceQueryInterpretationService.kt
     */
    @Test
    fun noNewProductionImportLegacyPeriodRange() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val allowlist = setOf(
            "InsightsEngine.kt",
            "ExecuteFinancialQueryUseCase.kt",
            "InterpretFinancialQueryUseCase.kt",
            "FinancialQueryModels.kt",
            "OnDeviceQueryInterpretationService.kt"
        )
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name in allowlist) return@forEach
            val content = file.readText()
            val regex = Regex("import\\s+com\\.yourname\\.expensetracker\\.domain\\.model\\.PeriodRange")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} import(s) of domain.model.PeriodRange")
            }
        }
        if (violations.isNotEmpty()) {
            fail("E5-GUARD-001: New production code imports deprecated domain.model.PeriodRange:\n${violations.joinToString("\n")}")
        }
    }

    // ── Guard 2: No direct System.currentTimeMillis() in domain/core|budget|analytics ─

    /**
     * E5-GUARD-002: Prevents NEW direct [System.currentTimeMillis()] calls in the
     * most critical domain packages: domain/core/, domain/budget/, domain/analytics/.
     *
     * These packages should obtain wall-clock time through dependency-injected
     * [Clock] or [TimeProvider] abstractions only.
     *
     * No allowlist needed — PR1–PR7 cleaned up all existing occurrences.
     */
    @Test
    fun noNewDirectWallClockInDomainCore() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val targetPrefixes = listOf("domain/core/", "domain/budget/", "domain/analytics/")
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            val relPath = relativePath(sourceRoot, file)
            val matchesPrefix = targetPrefixes.any { relPath.startsWith(it) }
            if (!matchesPrefix) return@forEach
            val content = file.readText()
            val regex = Regex("System\\.currentTimeMillis\\(\\)")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name} (${relPath}): found ${matches.size} call(s) to System.currentTimeMillis()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("E5-GUARD-002: Direct System.currentTimeMillis() found in domain/core|budget|analytics:\n${violations.joinToString("\n")}")
        }
    }

    // ── Guard 3: No raw CurrencyCode constructor in domain/core ─────────────────

    /**
     * E5-GUARD-003: Prevents NEW raw [CurrencyCode(...)] constructor calls from
     * unchecked strings in domain/core/. Production code should use the predefined
     * constants ([CurrencyCode.EUR], [CurrencyCode.USD], etc.) or the safe factory
     * [CurrencyCode.fromOrNull].
     *
     * Allowlisted:
     *   - CurrencyCode.kt (definition file — the constructor is declared here)
     *   - MoneyNormalizationEngine.kt (intentional CurrencyCode("XXX") as
     *     INVALID_CURRENCY placeholder)
     */
    @Test
    fun noNewRawCurrencyCodeInDomainCore() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val allowlist = setOf(
            "CurrencyCode.kt",
            "MoneyNormalizationEngine.kt"
        )
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            val relPath = relativePath(sourceRoot, file)
            if (!relPath.startsWith("domain/core/")) return@forEach
            if (file.name in allowlist) return@forEach
            val content = file.readText()
            val regex = Regex("CurrencyCode\\(")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name} (${relPath}): found ${matches.size} raw CurrencyCode constructor call(s)")
            }
        }
        if (violations.isNotEmpty()) {
            fail("E5-GUARD-003: Raw CurrencyCode constructor call found in domain/core:\n${violations.joinToString("\n")}")
        }
    }
}
