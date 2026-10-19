package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.architecture.FailClosedScan
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IncludeDepositsForBehaviorCleanupTest {

    @Test
    fun `noProductionCodeReliesOnIncludeDepositsForBehavior`() {
        // Gradle runs unit tests with the module dir (app/) as cwd, so resolve the root from several
        // candidates. A missing root, a root with zero .kt files or an unreadable file fails the
        // guard (FailClosedScan, proven by ScannerFailClosedSelfTest) - it never scans nothing.
        val sourceRoot = FailClosedScan.requireRoot(
            listOf(
                File("src/main/java"),
                File("app/src/main/java"),
                File(System.getProperty("user.dir") ?: ".", "src/main/java"),
                File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
            ),
            "production source root"
        )
        val violations = mutableListOf<String>()
        FailClosedScan.walkSourceFiles(sourceRoot) { file, content ->
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
