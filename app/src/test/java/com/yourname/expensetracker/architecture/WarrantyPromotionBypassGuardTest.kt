package com.yourname.expensetracker.architecture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F-AI-1 guard: a warranty may leave review (needsReview=false, PENDING_REVIEW -> ACTIVE) only through
 * `WarrantyTrackerRepository.confirmWarranty` (fresh in-transaction read, CONFIRMED event, past-end rule,
 * never EXPIRED -> ACTIVE). Production code outside `WarrantyTrackerRepository.kt` must not call
 * `updateWarranty(...)` from a function that also writes a promoted `status` or `needsReview = false`.
 *
 * Scope: production `.kt` sources, function-level text scan after comments are stripped. LEGAL_PATHS
 * FORBIDDEN line: "Warranty promotion out of review ... via updateWarranty".
 */
class WarrantyPromotionBypassGuardTest {

    private val sourceRoot: File by lazy {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        const val OWNER_FILE = "WarrantyTrackerRepository.kt"
        val UPDATE_CALL = Regex("""(?<!fun\s)\bupdateWarranty\s*\(""")
        val FUN_START = Regex("""\bfun\s+\w+""")
        val PROMOTION_WRITE = Regex(
            """needsReview\s*=\s*false|status\s*=\s*WarrantyStatus\.(?:ACTIVE|EXPIRED)\b|status\s*=\s*if\b"""
        )
        val BLOCK_COMMENT = Regex("""/\*[\s\S]*?\*/""")
        val LINE_COMMENT = Regex("""//[^\n]*""")
    }

    private fun stripComments(text: String): String =
        text.replace(BLOCK_COMMENT) { " ".repeat(it.value.length) }.replace(LINE_COMMENT, "")

    /** Returns "file:line" for each updateWarranty call whose enclosing function also writes a promotion. */
    private fun findViolations(fileName: String, rawText: String): List<String> {
        if (fileName == OWNER_FILE) return emptyList()
        val text = stripComments(rawText)
        val funStarts = FUN_START.findAll(text).map { it.range.first }.toList()
        val violations = mutableListOf<String>()
        for (call in UPDATE_CALL.findAll(text)) {
            val start = funStarts.lastOrNull { it <= call.range.first } ?: 0
            val end = funStarts.firstOrNull { it > call.range.first } ?: text.length
            if (PROMOTION_WRITE.containsMatchIn(text.substring(start, end))) {
                val line = text.substring(0, call.range.first).count { it == '\n' } + 1
                violations.add("$fileName:$line")
            }
        }
        return violations
    }

    @Test
    fun `no production code promotes a warranty out of review via updateWarranty outside the confirm path`() {
        val ktFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            "Scanned too few .kt files under ${sourceRoot.absolutePath}; source root resolution is broken.",
            ktFiles.size >= 10
        )
        val useCase = ktFiles.firstOrNull { it.name == "AutoCreateWarrantyFromReceiptUseCase.kt" }
        assertTrue("AutoCreateWarrantyFromReceiptUseCase.kt must be scanned (non-vacuous check).", useCase != null)
        val useCaseText = stripComments(useCase!!.readText())
        assertTrue(
            "The use case must still reach the F-AI-1 review-promotion site through " +
                "WarrantyTrackerRepository.promoteReviewDraft so the scan covers it.",
            useCaseText.contains("warrantyTrackerRepository.promoteReviewDraft(")
        )
        assertTrue(
            "The use case must not call updateWarranty directly; content edit + confirm is the " +
                "repository's single atomic promoteReviewDraft operation.",
            !UPDATE_CALL.containsMatchIn(useCaseText)
        )

        val violations = ktFiles.flatMap { findViolations(it.name, it.readText()) }

        assertTrue(
            "F-AI-1 violations: warranty promotion must go through WarrantyTrackerRepository.confirmWarranty.\n" +
                violations.joinToString("\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun `self-check flags a planted updateWarranty status promotion and ignores edit-only writes`() {
        val planted = """
            class Planted {
                suspend fun promote(w: Warranty) {
                    repo.updateWarranty(w.copy(status = WarrantyStatus.ACTIVE, needsReview = false))
                }
            }
        """.trimIndent()
        val plantedFlagOnly = """
            class PlantedFlag {
                suspend fun promote(w: Warranty) {
                    repo.updateWarranty(w.copy(needsReview = false))
                }
            }
        """.trimIndent()
        val editOnly = """
            class Clean {
                suspend fun edit(w: Warranty) {
                    repo.updateWarranty(w.copy(productName = "x"))
                    repo.confirmWarranty(w.id)
                }
                // repo.updateWarranty(w.copy(needsReview = false)) is only a comment
                suspend fun other(w: Warranty) = w.copy(status = WarrantyStatus.ACTIVE)
            }
        """.trimIndent()

        assertEquals(1, findViolations("Planted.kt", planted).size)
        assertEquals(1, findViolations("PlantedFlag.kt", plantedFlagOnly).size)
        assertTrue(findViolations("Clean.kt", editOnly).isEmpty())
        assertTrue("owner file is exempt", findViolations(OWNER_FILE, planted).isEmpty())
    }
}
