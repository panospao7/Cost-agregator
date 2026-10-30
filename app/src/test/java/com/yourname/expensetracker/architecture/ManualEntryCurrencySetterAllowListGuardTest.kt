package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * MEC INV-5 / AE-35 (G2-10): the user-present rate flag may be SET only by three allow-listed
 * production files. A "setter" is a construction of `UserPresentRateRequirement(` or a named
 * argument / assignment `userPresentRate =` in main sources (the declaration of the data class and
 * of the defaulted property `userPresentRate: UserPresentRateRequirement? = null` are not setters).
 *
 * The allow-list is FIXED at three entries and never grows. Entries may be absent files (the
 * installment coordinator path exists only once the loan feature lands); an absent file passes.
 * Detection runs over comment- and string-sanitized text, so a comment or string mention can
 * neither create nor hide a setter. Fail-closed: a missing/empty scan root fails; the self-tests
 * prove the detector fires on a violating snippet and ignores the legal shapes.
 */
class ManualEntryCurrencySetterAllowListGuardTest {

    private val sourceRoot: File by lazy {
        FailClosedScan.requireRoot(
            listOf(
                File("src/main/java"),
                File("app/src/main/java"),
                File(System.getProperty("user.dir") ?: ".", "src/main/java"),
                File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
            ),
            "production source root"
        )
    }

    /** Paths relative to the production source root. Fixed; never grows (G2-10). */
    private val allowedSetterFiles: Set<String> = setOf(
        "com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt",
        "com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt",
        "com/yourname/expensetracker/domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt"
    )

    /**
     * Other production source sets (debug/release) that exist. They have no allow-list entries:
     * any setter there is a violation. Absent source sets are skipped (they are optional); the
     * main root above remains mandatory and fail-closed.
     */
    private val extraSourceRoots: List<File> by lazy {
        val base = System.getProperty("user.dir") ?: "."
        listOf("debug", "release").mapNotNull { set ->
            listOf(
                File("src/$set/java"),
                File("app/src/$set/java"),
                File(base, "src/$set/java"),
                File(base, "app/src/$set/java")
            ).firstOrNull { it.isDirectory }
        }
    }

    private val constructionPattern = Regex("""(?<!\bclass\s)\bUserPresentRateRequirement\s*\(""")
    private val constructorReferencePattern = Regex("""::\s*UserPresentRateRequirement\b""")
    private val namedSetterPattern = Regex("""\buserPresentRate\s*=(?!=)""")
    private val aliasImportPattern = Regex("""\bimport\s+[\w.]*UserPresentRateRequirement\s+as\b""")
    private val typeAliasPattern = Regex("""\btypealias\s+\w+\s*=\s*[\w.]*UserPresentRateRequirement\b""")

    private fun violationsIn(rawText: String): List<String> {
        val code = SourceTextSanitizer.stripCommentsAndStringBodies(rawText)
        val found = mutableListOf<String>()
        if (constructionPattern.containsMatchIn(code)) found += "constructs UserPresentRateRequirement("
        if (constructorReferencePattern.containsMatchIn(code)) found += "references UserPresentRateRequirement constructor via ::"
        if (namedSetterPattern.containsMatchIn(code)) found += "assigns/passes userPresentRate ="
        if (aliasImportPattern.containsMatchIn(code)) found += "aliases UserPresentRateRequirement via import"
        if (typeAliasPattern.containsMatchIn(code)) found += "aliases UserPresentRateRequirement via typealias"
        return found
    }

    @Test
    fun `allow-list is fixed at exactly the three G2-10 sites`() {
        assertEquals(
            setOf(
                "com/yourname/expensetracker/data/repository/ManualExpenseRepository.kt",
                "com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt",
                "com/yourname/expensetracker/domain/installment/lifecycle/InstallmentPlanLifecycleCoordinator.kt"
            ),
            allowedSetterFiles
        )
    }

    @Test
    fun `scan root exists and contains Kotlin files`() {
        var count = 0
        FailClosedScan.walkSourceFiles(sourceRoot) { _, _ -> count++ }
        assertTrue("scanned zero Kotlin files", count > 0)
    }

    @Test
    fun `only the allow-listed files set the user-present rate flag`() {
        val errors = mutableListOf<String>()
        FailClosedScan.walkSourceFiles(sourceRoot) { file, raw ->
            val relative = FailClosedScan.relativePath(sourceRoot, file)
            if (relative in allowedSetterFiles) return@walkSourceFiles
            violationsIn(raw).forEach { errors += "$relative: $it" }
        }
        extraSourceRoots.forEach { root ->
            FailClosedScan.walkSourceFiles(root) { file, raw ->
                val relative = "${root.parentFile?.name}/" + FailClosedScan.relativePath(root, file)
                violationsIn(raw).forEach { errors += "$relative: $it" }
            }
        }
        assertTrue(
            "userPresentRate may be set only in ${allowedSetterFiles.joinToString()}; found:\n" +
                errors.joinToString("\n"),
            errors.isEmpty()
        )
    }

    // ── Self-tests (negative controls and legal shapes) ──────────────────────

    @Test
    fun `detector fires on a constructed requirement`() {
        val snippet = """
            class Sneaky {
                fun req() = CreateExpenseRequest(
                    merchant = "m", amount = 1.0, currency = "USD", date = 1L,
                    transactionType = TransactionType.PURCHASE, source = ExpenseSource.UNKNOWN,
                    userPresentRate = UserPresentRateRequirement(java.time.ZoneId.of("UTC"))
                )
            }
        """.trimIndent()
        val found = violationsIn(snippet)
        assertTrue(found.any { it.contains("constructs") })
        assertTrue(found.any { it.contains("userPresentRate =") })
    }

    @Test
    fun `detector fires on a copy assignment and on an alias import`() {
        assertTrue(violationsIn("val r = base.copy(userPresentRate = other)").isNotEmpty())
        assertTrue(
            violationsIn("import com.x.UserPresentRateRequirement as Flag").isNotEmpty()
        )
        assertTrue(
            violationsIn("typealias Flag = com.x.UserPresentRateRequirement").isNotEmpty()
        )
    }

    @Test
    fun `declarations reads and comment or string mentions are not setters`() {
        val declaration = """
            data class UserPresentRateRequirement(val entryZone: java.time.ZoneId)
            data class CreateExpenseRequest(
                val sourceLinkFallbackPolicy: Int = 0,
                val userPresentRate: UserPresentRateRequirement? = null
            )
        """.trimIndent()
        assertEquals(emptyList<String>(), violationsIn(declaration))

        val reads = """
            // userPresentRate = UserPresentRateRequirement(zone) is only a comment
            val note = "userPresentRate = UserPresentRateRequirement(zone)"
            val flagged = request.userPresentRate != null
            val same = request.userPresentRate == other
            import com.x.UserPresentRateRequirement
        """.trimIndent()
        assertEquals(emptyList<String>(), violationsIn(reads))
    }

    @Test
    fun `detector fires on a constructor reference to the requirement`() {
        assertTrue(violationsIn("val make = ::UserPresentRateRequirement").isNotEmpty())
        assertTrue(violationsIn("val make = Factory::UserPresentRateRequirement").isNotEmpty())
        assertTrue(violationsIn("val make = ::  UserPresentRateRequirement").isNotEmpty())
    }

    @Test
    fun `non-main scan roots that exist are scanned and the allow-list is main-only`() {
        // Every allow-listed path is a main-source path; debug/release roots have no exemptions.
        assertTrue(allowedSetterFiles.none { it.startsWith("debug/") || it.startsWith("release/") })
        extraSourceRoots.forEach { root ->
            var count = 0
            FailClosedScan.walkSourceFiles(root) { _, _ -> count++ }
            assertTrue("scanned zero Kotlin files under ${root.name}", count > 0)
        }
    }
}
