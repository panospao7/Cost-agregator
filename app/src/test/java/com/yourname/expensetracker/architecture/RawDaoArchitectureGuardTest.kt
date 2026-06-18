package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * PR7 — Architecture guard: Raw DAO mutator access.
 *
 * Contract DAO-01: Production code outside of repository classes MUST NOT call
 * raw DAO mutator methods (insert, update, delete, insertAll, updateCanonicalCategory,
 * incrementMerchantStats, etc.). All mutations must route through a repository
 * that enforces write barriers, validation, and side-effect coordination.
 *
 * This test scans all production `.kt` source files and fails if any non-repository
 * file contains a call to a raw DAO mutator.
 *
 * Allowlisted files (repositories, tests, DI modules, database baseline) are excluded.
 * The allowlist MUST NOT grow — only shrink.
 */
class RawDaoArchitectureGuardTest {

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

    /**
     * Files that are ALLOWED to call Engine 3 DAO mutators because they ARE
     * the repository layer or database infrastructure for merchant/category normalization.
     *
     * The allowlist MUST NOT grow — only shrink.
     */
    private val ALLOWED_FILES = setOf(
        // Repository layer (the legitimate callers for merchant/category DAOs)
        "MerchantCategoryRepository.kt",
        "MerchantNormalizationRepository.kt",
        "CategoryRepository.kt",

        // Database infrastructure
        "AppDatabase.kt",
        "DaoModule.kt",
        "DatabaseWriteBarrier.kt",
    )

    /**
     * DAO mutator patterns that are forbidden outside repositories.
     * These are intentionally scoped to Engine 3 (merchant/category normalization)
     * DAOs only. Other DAOs (expenseDao, transactionEventDao, etc.) are outside
     * the scope of this guard and are managed by their own coordinators.
     *
     * Patterns match specific variable names (e.g. `merchantCategoryDao.insert()`)
     * rather than broad method names to avoid false positives.
     */
    private val DAO_MUTATOR_PATTERNS = listOf(
        Regex("merchantCategoryDao\\.(insert|insertAll|updateNormalizedCanonicalName|deleteAll)\\("),
        Regex("merchantNormalizationDao\\.(insertCanonical|insertAlias|updateCanonical|updateCanonicalCategory|updateAlias|deleteUnusedAliasesOlderThan|incrementMerchantStats|incrementAliasOccurrence|linkAliasToCanonical)\\("),
    )

    @Test
    fun noRawDaoMutatorsOutsideRepositories() {
        assertTrue("Main source root not found: $sourceRoot", sourceRoot.exists())
        val violations = mutableListOf<String>()

        allKtFiles(sourceRoot).forEach { file ->
            if (file.name in ALLOWED_FILES) return@forEach
            val content = file.readText()
            DAO_MUTATOR_PATTERNS.forEach { pattern ->
                val matches = pattern.findAll(content).toList()
                if (matches.isNotEmpty()) {
                    violations.add("${file.name}: found ${matches.size} call(s) matching ${pattern.pattern}")
                }
            }
        }

        assertTrue(
            "Raw DAO mutator calls found outside repository layer:\n" +
                violations.joinToString("\n") { "  \u2022 $it" },
            violations.isEmpty()
        )
    }
}
