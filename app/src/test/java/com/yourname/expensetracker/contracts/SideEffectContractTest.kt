package com.yourname.expensetracker.contracts

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Architecture contract: Side-effect dispatches (dispatchOnCreated/Updated/Deleted)
 * must NOT occur inside withTransaction blocks. They must be called AFTER commit.
 */
class SideEffectContractTest {

    private val mainSrc = File("app/src/main/java/com/yourname/expensetracker")
    private val dispatchPattern = Regex("""dispatchOn(Created|Updated|Deleted|BulkUpdated)""")

    @Test
    fun `dispatch calls are never inside withTransaction blocks`() {
        val filesWithDispatch = mainSrc.walkTopDown()
            .filter { it.extension == "kt" }
            .filter { dispatchPattern.containsMatchIn(it.readText()) }
            .toList()

        assertTrue("Expected files with dispatch calls", filesWithDispatch.isNotEmpty())

        val violations = mutableListOf<String>()
        for (file in filesWithDispatch) {
            val content = file.readText()
            // Skip the dispatcher definition itself
            if (file.name.contains("SideEffectDispatcher")) continue
            if (!content.contains("withTransaction")) continue

            // Find all withTransaction block ranges using brace matching
            val transactionRanges = findTransactionBlockRanges(content)

            // Check if any dispatch call occurs inside a transaction range
            for (match in dispatchPattern.findAll(content)) {
                // Skip if inside a comment
                val lineStart = content.lastIndexOf('\n', match.range.first) + 1
                val lineText = content.substring(lineStart, match.range.first).trimStart()
                if (lineText.startsWith("//") || lineText.startsWith("*") || lineText.startsWith("/*")) continue

                if (transactionRanges.any { match.range.first in it }) {
                    val lineNum = content.substring(0, match.range.first).count { it == '\n' } + 1
                    violations.add("${file.name}:$lineNum")
                }
            }
        }
        assertTrue(
            "Side-effect dispatches found inside withTransaction blocks: $violations",
            violations.isEmpty()
        )
    }

    /**
     * Finds character ranges of withTransaction { ... } blocks in source code.
     */
    private fun findTransactionBlockRanges(source: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        val pattern = Regex("""withTransaction\s*\{""")
        for (match in pattern.findAll(source)) {
            val braceStart = match.range.last // position of the opening '{'
            var depth = 1
            var i = braceStart + 1
            while (i < source.length && depth > 0) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            ranges.add(braceStart..i)
        }
        return ranges
    }
}
