package com.yourname.expensetracker.architecture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * RP-08 (P6-002/P6-003) plan-mandated static architecture guard for the
 * budget autopilot engine.
 *
 * [BudgetAutopilotEngine] must consume the typed
 * `MultiCurrencyRepository.getHistoricalCategoryMonthlySpend()` history API:
 *
 * - NO reflection on the repository's private `expenseDao`
 *   (`getDeclaredField` / `setAccessible`);
 * - NO call to the deprecated raw mixed-currency DAO aggregate
 *   `ExpenseDao.getMonthlySpendingTotalsByCategoryBetween`;
 * - NO call to the deprecated `MultiCurrencyRepository.getMonthlyTotalsInHomeCurrency`
 *   (its `Result.Error` flattened to an empty history).
 *
 * Detection is real (source scan over the sanitized production source, same
 * policy as [SourceScanningArchitectureGuardTest]): comments and string
 * literals are blanked via [SourceTextSanitizer.stripCommentsAndStringBodies]
 * before matching, so neither commented-out code nor a KDoc mention can
 * satisfy the rule (RP-02 U-004). Negative fixtures prove the scanner is not
 * vacuous, and the positive test fails loudly if the engine file cannot be
 * located (source-root resolution broken).
 */
class BudgetAutopilotArchitectureGuardTest {

    // ── Source tree resolution (same candidates as the shared guard test) ────

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

    private val engineFile: File
        get() = File(
            sourceRoot,
            "com/yourname/expensetracker/domain/budget/BudgetAutopilotEngine.kt"
        )

    /** Forbidden markers for the de-reflection contract (RP-08 P6-002/P6-003). */
    private val forbiddenMarkers = listOf(
        "getDeclaredField",
        "setAccessible",
        "getMonthlySpendingTotalsByCategoryBetween",
        "getMonthlyTotalsInHomeCurrency"
    )

    // ── Positive test: the real production engine file ────────────────────────

    @Test
    fun `autopilot engine contains no reflection and no deprecated DAO aggregates`() {
        assertTrue(
            "BudgetAutopilotEngine.kt not found at ${engineFile.absolutePath}. " +
                "Source root resolution is broken — this guard would pass vacuously.",
            engineFile.exists()
        )

        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(engineFile.readText())

        val violations = forbiddenMarkers.filter { marker -> sanitized.contains(marker) }

        assertTrue(
            "RP-08 P6-002/P6-003 violation in BudgetAutopilotEngine.kt — the engine must consume " +
                "MultiCurrencyRepository.getHistoricalCategoryMonthlySpend() instead of reflection or " +
                "deprecated mixed-currency DAO aggregates. Forbidden markers found: $violations",
            violations.isEmpty()
        )

        // Non-vacuity: the guard must be scanning real code — the engine's
        // de-reflection successor API must be present in the sanitized source.
        assertTrue(
            "BudgetAutopilotEngine.kt does not reference getHistoricalCategoryMonthlySpend — " +
                "the guard may be scanning the wrong file.",
            sanitized.contains("getHistoricalCategoryMonthlySpend")
        )
    }

    // ── Negative fixtures: the scanner must actually detect violations ────────

    @Test
    fun `negative_fixture_reflection_bridge_is_detected`() {
        val badEngine = """
            class BadEngine {
                fun load(): List<Any> {
                    val field = repo.javaClass.getDeclaredField("expenseDao")
                    field.isAccessible = true
                    return emptyList()
                }
            }
        """.trimIndent()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(badEngine)
        assertTrue(
            "Negative fixture: getDeclaredField reflection bridge must be detected",
            sanitized.contains("getDeclaredField")
        )
    }

    @Test
    fun `negative_fixture_deprecated_dao_aggregate_is_detected`() {
        val badEngine = """
            class BadEngine {
                fun load(): List<Any> {
                    return dao.getMonthlySpendingTotalsByCategoryBetween(1L, 0L, 1L)
                }
            }
        """.trimIndent()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(badEngine)
        assertTrue(
            "Negative fixture: deprecated DAO aggregate call must be detected",
            sanitized.contains("getMonthlySpendingTotalsByCategoryBetween")
        )
    }

    @Test
    fun `negative_fixture_deprecated_repository_result_path_is_detected`() {
        val badEngine = """
            class BadEngine {
                fun load(): List<Any> {
                    return repo.getMonthlyTotalsInHomeCurrency(0L, 1L, "EUR").data
                }
            }
        """.trimIndent()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(badEngine)
        assertTrue(
            "Negative fixture: deprecated Result-flattening repository call must be detected",
            sanitized.contains("getMonthlyTotalsInHomeCurrency")
        )
    }

    @Test
    fun `negative_fixture_commented_violation_is_not_detected`() {
        // A violation that exists ONLY inside comments must NOT satisfy the
        // scan (RP-02 U-004: no protection inferred from a comment mention).
        val commentedEngine = """
            // TODO: remove dao.getMonthlySpendingTotalsByCategoryBetween usage
            // val f = repo.javaClass.getDeclaredField("expenseDao")
            class FineEngine {
                fun load(): List<Any> = repo.getHistoricalCategoryMonthlySpend(0L, 1L)
            }
        """.trimIndent()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(commentedEngine)
        val hits = forbiddenMarkers.filter { sanitized.contains(it) }
        assertTrue(
            "Comment-only violations must be stripped before scanning (found: $hits)",
            hits.isEmpty()
        )
    }
}
