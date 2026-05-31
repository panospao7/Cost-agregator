package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * U-PR5 — Architecture guard: Bank API privacy mode isolation.
 *
 * Contract BANK-PRIVACY-01: [BankApiIntegration] must use
 * `rawBankStatementStorageMode` for bank transaction description/reference
 * redaction — never `rawOcrStorageMode`. Bank API data is not OCR output.
 *
 * This test scans BankApiIntegration.kt and fails if it references
 * `rawOcrStorageMode`, ensuring the semantic separation is maintained.
 */
class BankPrivacyModeArchitectureGuardTest {

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

    @Test
    fun bankApiIntegration_must_not_reference_rawOcrStorageMode() {
        val file = File(sourceRoot, "com/yourname/expensetracker/domain/bank/BankApiIntegration.kt")
        assertTrue("BankApiIntegration.kt must exist at $file", file.exists())

        val lines = file.readLines()
        assertTrue("BankApiIntegration.kt must not be empty", lines.isNotEmpty())

        val violations = lines.mapIndexedNotNull { idx, line ->
            if (line.contains("rawOcrStorageMode")) "Line ${idx + 1}: $line" else null
        }

        assertTrue(
            "BANK-PRIVACY-01: BankApiIntegration must use rawBankStatementStorageMode, " +
                "not rawOcrStorageMode. Violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    @Test
    fun bankApiIntegration_uses_rawBankStatementStorageMode() {
        val file = File(sourceRoot, "com/yourname/expensetracker/domain/bank/BankApiIntegration.kt")
        assertTrue("BankApiIntegration.kt must exist at $file", file.exists())

        val content = file.readText()
        assertTrue(
            "BANK-PRIVACY-01: BankApiIntegration must reference rawBankStatementStorageMode",
            content.contains("rawBankStatementStorageMode")
        )
    }
}
