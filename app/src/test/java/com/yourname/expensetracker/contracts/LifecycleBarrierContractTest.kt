package com.yourname.expensetracker.contracts

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Architecture contract: All lifecycle coordinators and repositories with transactional writes
 * must check DatabaseWriteBarrier before performing writes.
 */
class LifecycleBarrierContractTest {

    private val mainSrc = File("app/src/main/java/com/yourname/expensetracker")

    @Test
    fun `all LifecycleCoordinators with transactions reference writeBarrier`() {
        val coordinators = mainSrc.walkTopDown()
            .filter { it.name.contains("LifecycleCoordinator") && it.extension == "kt" }
            .filter { !it.name.contains("Test") }
            .toList()

        assertTrue("Expected LifecycleCoordinator files", coordinators.isNotEmpty())

        val violations = mutableListOf<String>()
        for (file in coordinators) {
            val content = file.readText()
            // Only check files that use withTransaction (atomic multi-write operations)
            if (content.contains("withTransaction")) {
                val hasBarrier = content.contains("writeBarrier") ||
                        content.contains("DatabaseWriteBarrier")
                if (!hasBarrier) {
                    violations.add(file.name)
                }
            }
        }
        assertTrue(
            "LifecycleCoordinators with transactions missing writeBarrier: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun `repositories with withTransaction reference writeBarrier`() {
        val repos = mainSrc.walkTopDown()
            .filter { it.name.contains("Repository") && it.extension == "kt" }
            .filter { !it.name.contains("Test") }
            .toList()

        assertTrue("Expected Repository files", repos.isNotEmpty())

        val violations = mutableListOf<String>()
        for (file in repos) {
            val content = file.readText()
            // Only flag repos that use withTransaction (actual multi-write operations)
            if (content.contains("withTransaction")) {
                val hasBarrier = content.contains("writeBarrier") ||
                        content.contains("restoreMaintenanceMode") ||
                        content.contains("DatabaseWriteBarrier")
                if (!hasBarrier) {
                    violations.add(file.name)
                }
            }
        }
        assertTrue(
            "Repositories with withTransaction missing writeBarrier: $violations",
            violations.isEmpty()
        )
    }
}
