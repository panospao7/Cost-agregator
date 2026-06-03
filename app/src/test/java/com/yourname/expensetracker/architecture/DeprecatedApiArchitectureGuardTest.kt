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
            if (file.name == "AreaSpendingEngine.kt" || file.name == "AnalyticsViewModel.kt") return@forEach
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
            if (file.name == "TravelDetectionEngine.kt" || file.name == "AnalyticsViewModel.kt") return@forEach
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
}
