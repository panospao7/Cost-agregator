package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * PR9 — Deprecated raw-Double API architecture guard.
 *
 * Prevents new production code from calling deprecated raw-Double APIs.
 * Existing call sites in AnalyticsViewModel are explicitly allowlisted
 * until they can be migrated to the normalized equivalents.
 *
 * NOTE: Allowlists use exact filenames (e.g., "AnalyticsViewModel.kt").
 * If AnalyticsViewModel is renamed, update the allowlist in
 * [areaSpendingCompute_onlyCalledFromAnalyticsViewModel] and
 * [travelDetectionCompute_onlyCalledFromAnalyticsViewModel].
 */
class DeprecatedApiArchitectureGuardTest {

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

    @Test
    fun noProductionCallToGetTotalProtectedValue() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "WarrantyTrackerRepository.kt") return@forEach
            val content = file.readText()
            val regex = Regex("getTotalProtectedValue\\(\\)")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to getTotalProtectedValue()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("W01: getTotalProtectedValue() called from production code:\n${violations.joinToString("\n")}")
        }
    }

    @Test
    fun noProductionCallToGetTotalMonthlySubscriptionCost() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "SubscriptionManagerEngine.kt") return@forEach
            val content = file.readText()
            val regex = Regex("getTotalMonthlySubscriptionCost\\(\\)")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to getTotalMonthlySubscriptionCost()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("W06: getTotalMonthlySubscriptionCost() called from production code:\n${violations.joinToString("\n")}")
        }
    }

    @Test
    fun noProductionCallToCalculatePotentialSavings() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "SubscriptionManagerEngine.kt") return@forEach
            val content = file.readText()
            val regex = Regex("calculatePotentialSavings\\(")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to calculatePotentialSavings()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("W06: calculatePotentialSavings() called from production code:\n${violations.joinToString("\n")}")
        }
    }

    @Test
    fun areaSpendingCompute_onlyCalledFromAnalyticsViewModel() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "AreaSpendingEngine.kt") return@forEach
            val content = file.readText()
            val regex = Regex("areaSpendingEngine\\.compute\\(")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to areaSpendingEngine.compute()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("W29: areaSpendingEngine.compute() called from unexpected production code:\n${violations.joinToString("\n")}")
        }
    }

    @Test
    fun travelDetectionCompute_onlyCalledFromAnalyticsViewModel() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "TravelDetectionEngine.kt") return@forEach
            val content = file.readText()
            val regex = Regex("travelDetectionEngine\\.compute\\(")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to travelDetectionEngine.compute()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("W29: travelDetectionEngine.compute() called from unexpected production code:\n${violations.joinToString("\n")}")
        }
    }

    // ── PR8: AdvancedAnalyticsEngine self-fetching overload guards ─────────

    /**
     * PR8-GUARDRAIL: getCategoryAnalytics(period, displayCurrency) is the deprecated
     * self-fetching overload. It is allowlisted in HomeViewModel (category trends)
     * and AnalyticsViewModel (safe NormalizedAnalyticsInput overload at lines 576-579).
     * NOTE: This regex matches both overloads; the allowlist must include all callers.
     */
    @Test
    fun advancedCategoryAnalytics_onlyCalledFromHomeViewModel() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "AdvancedAnalyticsEngine.kt" || file.name == "HomeViewModel.kt" || file.name == "AnalyticsViewModel.kt") return@forEach
            val content = file.readText()
            val regex = Regex("advancedAnalyticsEngine\\.getCategoryAnalytics\\(")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to advancedAnalyticsEngine.getCategoryAnalytics()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("PR8: advancedAnalyticsEngine.getCategoryAnalytics() called from unexpected production code:\n${violations.joinToString("\n")}")
        }
    }

    /**
     * PR8-GUARDRAIL: getMerchantAnalytics(period, displayCurrency, limit) is the deprecated
     * self-fetching overload. It has no production callers; any new caller must be reviewed and
     * allowlisted.
     * NOTE: This regex matches both the deprecated self-fetching overload and the safe
     * NormalizedAnalyticsInput overload; the allowlist must include all callers.
     */
    @Test
    fun advancedMerchantAnalytics_noProductionCallers() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "AdvancedAnalyticsEngine.kt" || file.name == "AnalyticsViewModel.kt") return@forEach
            val content = file.readText()
            val regex = Regex("advancedAnalyticsEngine\\.getMerchantAnalytics\\(")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to advancedAnalyticsEngine.getMerchantAnalytics()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("PR8: advancedAnalyticsEngine.getMerchantAnalytics() called from unexpected production code:\n${violations.joinToString("\n")}")
        }
    }

    /**
     * PR8-GUARDRAIL: getSpendingPatterns(period, displayCurrency) is the deprecated
     * self-fetching overload. It has no production callers; any new caller must be reviewed and
     * allowlisted.
     * NOTE: This regex matches both the deprecated self-fetching overload and the safe
     * NormalizedAnalyticsInput overload; the allowlist must include all callers.
     */
    @Test
    fun advancedSpendingPatterns_noProductionCallers() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "AdvancedAnalyticsEngine.kt" || file.name == "AnalyticsViewModel.kt") return@forEach
            val content = file.readText()
            val regex = Regex("advancedAnalyticsEngine\\.getSpendingPatterns\\(")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to advancedAnalyticsEngine.getSpendingPatterns()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("PR8: advancedAnalyticsEngine.getSpendingPatterns() called from unexpected production code:\n${violations.joinToString("\n")}")
        }
    }

    /**
     * PR8-GUARDRAIL: getStatisticalInsights(period, displayCurrency) is the deprecated
     * self-fetching overload. It has no production callers; any new caller must be reviewed and
     * allowlisted.
     * NOTE: This regex matches both the deprecated self-fetching overload and the safe
     * NormalizedAnalyticsInput overload; the allowlist must include all callers.
     */
    @Test
    fun advancedStatisticalInsights_noProductionCallers() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "AdvancedAnalyticsEngine.kt" || file.name == "AnalyticsViewModel.kt") return@forEach
            val content = file.readText()
            val regex = Regex("advancedAnalyticsEngine\\.getStatisticalInsights\\(")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to advancedAnalyticsEngine.getStatisticalInsights()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("PR8: advancedAnalyticsEngine.getStatisticalInsights() called from unexpected production code:\n${violations.joinToString("\n")}")
        }
    }

    /**
     * PR8-GUARDRAIL: AdvancedAnalyticsDashboard.generateDashboardData() self-fetches
     * raw expenses and bypasses NormalizedAnalyticsInput. Allowlisted in
     * AdvancedAnalyticsViewModel only.
     */
    @Test
    fun generateDashboardData_onlyCalledFromAdvancedAnalyticsViewModel() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "AdvancedAnalyticsDashboard.kt" || file.name == "AdvancedAnalyticsViewModel.kt") return@forEach
            val content = file.readText()
            val regex = Regex("generateDashboardData\\(")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to generateDashboardData()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("PR8: generateDashboardData() called from unexpected production code:\n${violations.joinToString("\n")}")
        }
    }

    /**
     * PR8-GUARDRAIL: insightsEngine.getLegacyInsights() is a deprecated
     * WARNING-level convenience wrapper that bypasses NormalizedAnalyticsInput.
     * This regex matches only this method name. Allowlisted in the defining
     * file (InsightsEngine.kt) and the sole production caller (AnalyticsViewModel.kt).
     */
    @Test
    fun legacyInsights_onlyCalledFromAnalyticsViewModel() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "InsightsEngine.kt" || file.name == "AnalyticsViewModel.kt") return@forEach
            val content = file.readText()
            val regex = Regex("insightsEngine\\.getLegacyInsights\\(")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to insightsEngine.getLegacyInsights()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("PR8: insightsEngine.getLegacyInsights() called from unexpected production code:\n${violations.joinToString("\n")}")
        }
    }

    // ── PR6: SpendingPersonalityClassifier raw classify() guard ─────────────

    /**
     * PR6-GUARDRAIL: spendingPersonalityClassifier.classify() (no-arg) is the
     * deprecated raw self-fetching overload. It must not be called from unexpected
     * production code. Allowlisted in SpendingPersonalityClassifier.kt (defining
     * file) only.
     */
    @Test
    fun noProductionCallToRawSpendingPersonalityClassify() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            if (file.name == "SpendingPersonalityClassifier.kt") return@forEach
            val content = file.readText()
            // Match "classify()" but not "classify(input" or "classify(Normalized"
            val regex = Regex("spendingPersonalityClassifier\\.classify\\s*\\(\\s*\\)")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} call(s) to raw classify()")
            }
        }
        if (violations.isNotEmpty()) {
            fail("W30: raw classify() called from unexpected production code:\n${violations.joinToString("\n")}")
        }
    }

    // ── PR3: Deprecated AnalyticsState.moneyCurrentTotal guard ─────────────

    /**
     * PR3-GUARDRAIL: [AnalyticsState.moneyCurrentTotal] (with EUR fallback) is
     * deprecated. Allowlisted in AnalyticsViewModel.kt (declaration site) and
     * AnalyticsModels.kt (non-deprecated moneyCurrentTotal declarations).
     */
    @Test
    fun noProductionUseOfDeprecatedAnalyticsStateMoneyCurrentTotal() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()
        allKtFiles(sourceRoot).forEach { file ->
            // Allow declaration site in AnalyticsViewModel.kt and non-deprecated
            // moneyCurrentTotal declarations in AnalyticsModels.kt
            if (file.name == "AnalyticsViewModel.kt" || file.name == "AnalyticsModels.kt") return@forEach
            val content = file.readText()
            // Match any read/reference of moneyCurrentTotal (not just declaration)
            val regex = Regex("\\.moneyCurrentTotal\\b")
            val matches = regex.findAll(content).toList()
            if (matches.isNotEmpty()) {
                violations.add("${file.name}: found ${matches.size} reference(s) to .moneyCurrentTotal")
            }
        }
        if (violations.isNotEmpty()) {
            fail("W31: Deprecated AnalyticsState.moneyCurrentTotal referenced from production code:\n${violations.joinToString("\n")}")
        }
    }
}
