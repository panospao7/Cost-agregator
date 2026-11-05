package com.yourname.expensetracker.ui.screens.review

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Feature-scoped source pin (not a registered guard): the edit-approve call in `ReviewScreen.kt` must pass the
 * AI prefill id through, otherwise `ReviewViewModel.approveReviewWithEdits` cannot tell an untouched AI prefill
 * from a user pick and would record a learning correction for it.
 *
 * The search is limited to the argument list of the `viewModel.approveReviewWithEdits(` call, found by matching
 * parentheses on comment- and string-stripped source, so an unrelated occurrence cannot satisfy it.
 */
class ReviewScreenAiPrefillWiringSourceScanTest {

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

    private val screenFile = "com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt"
    private val callOpening = "viewModel.approveReviewWithEdits("
    private val wiring = Regex("""\baiPrefilledCategoryId\s*=\s*editPrefillCategoryId\b""")

    /** Argument lists (text between the matching parentheses) of every call opening in [sanitized]. */
    private fun argumentLists(sanitized: String): List<String> {
        val result = mutableListOf<String>()
        var from = 0
        while (true) {
            val start = sanitized.indexOf(callOpening, from)
            if (start < 0) break
            val open = start + callOpening.length
            var depth = 1
            var i = open
            while (i < sanitized.length && depth > 0) {
                when (sanitized[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            assertEquals("unbalanced parentheses in approveReviewWithEdits call", 0, depth)
            result += sanitized.substring(open, i - 1)
            from = i
        }
        return result
    }

    @Test
    fun reviewScreenEditApproveCallPassesTheAiPrefillIdThrough() {
        val file = File(sourceRoot, screenFile)
        assertTrue("missing $screenFile", file.isFile)
        val calls = argumentLists(SourceTextSanitizer.stripCommentsAndStringBodies(file.readText()))

        assertTrue("no viewModel.approveReviewWithEdits( call found in ReviewScreen.kt", calls.isNotEmpty())
        calls.forEach { args ->
            assertTrue(
                "approveReviewWithEdits call must pass aiPrefilledCategoryId = editPrefillCategoryId",
                wiring.containsMatchIn(args)
            )
        }
    }

    @Test
    fun scanIsScopedToTheCallArgumentList() {
        // Planted: the wiring text exists elsewhere in the file but not inside the call's argument list.
        val planted = """
            val x = foo(aiPrefilledCategoryId = editPrefillCategoryId)
            viewModel.approveReviewWithEdits(reviewId = 1L, finalCategoryId = c)
            val y = bar(aiPrefilledCategoryId = editPrefillCategoryId)
        """.trimIndent()
        val calls = argumentLists(planted)

        assertEquals(1, calls.size)
        assertFalse(wiring.containsMatchIn(calls.single()))
    }

    @Test
    fun scanAcceptsAWiredCallWithNestedParentheses() {
        val wired = """
            viewModel.approveReviewWithEdits(
                reviewId = item.review.id,
                locationCleared = viewModel.locationEditState.value.locationCleared,
                finalAmount = compute(a, (b)),
                aiPrefilledCategoryId = editPrefillCategoryId
            )
        """.trimIndent()
        val calls = argumentLists(wired)

        assertEquals(1, calls.size)
        assertTrue(wiring.containsMatchIn(calls.single()))
    }
}
