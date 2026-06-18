package com.yourname.expensetracker.domain.analytics

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IncludeDepositsForBehaviorCleanupTest {

    @Test
    fun `noProductionCodeReliesOnIncludeDepositsForBehavior`() {
        // Search all production .kt files for "includeDepositsForBehavior"
        val sourceRoot = File("app/src/main/java")
        val violations = mutableListOf<String>()
        sourceRoot.walkTopDown().filter { it.name.endsWith(".kt") }.forEach { file ->
            val content = file.readText()
            if (content.contains("includeDepositsForBehavior")) {
                violations.add(file.name)
            }
        }
        assertTrue(
            "Found references in: ${violations.joinToString()}",
            violations.isEmpty()
        )
    }
}
