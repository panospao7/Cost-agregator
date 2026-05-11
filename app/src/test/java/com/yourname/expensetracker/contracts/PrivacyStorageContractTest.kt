package com.yourname.expensetracker.contracts

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Architecture contract: DO_NOT_STORE mode must never persist raw text.
 * Verifies exhaustive `when` blocks and that NotificationCaptureService handles DO_NOT_STORE.
 */
class PrivacyStorageContractTest {

    private val mainSrc = File("app/src/main/java/com/yourname/expensetracker")

    @Test
    fun `RawStorageMode usages use exhaustive when blocks`() {
        val files = mainSrc.walkTopDown()
            .filter { it.extension == "kt" && it.readText().contains("RawStorageMode") }
            .toList()
        assertTrue("Expected files using RawStorageMode", files.isNotEmpty())

        val violations = mutableListOf<String>()
        for (file in files) {
            val content = file.readText()
            // Skip the enum definition itself
            if (content.contains("enum class RawStorageMode")) continue
            // If file references RawStorageMode in logic, it should use `when` not if/else
            val hasWhenBlock = content.contains(Regex("""when\s*\(.*[Rr]aw.*[Ss]torage[Mm]ode"""))
                    || content.contains(Regex("""when\s*\(.*storageMode"""))
                    || content.contains(Regex("""when\s*\(settings\.raw"""))
            val hasIfElseChain = content.contains(Regex("""if\s*\(.*RawStorageMode\."""))
            if (hasIfElseChain && !hasWhenBlock) {
                violations.add(file.name)
            }
        }
        assertTrue(
            "Files using if/else instead of exhaustive when for RawStorageMode: $violations",
            violations.isEmpty()
        )
    }

    @Test
    fun `NotificationCaptureService has DO_NOT_STORE branch`() {
        val file = File("app/src/main/java/com/yourname/expensetracker/service/NotificationCaptureService.kt")
        assertTrue("NotificationCaptureService.kt must exist", file.exists())
        val content = file.readText()
        assertTrue(
            "NotificationCaptureService must handle DO_NOT_STORE",
            content.contains("RawStorageMode.DO_NOT_STORE")
        )
        assertTrue(
            "NotificationCaptureService must use when block for storage mode",
            content.contains(Regex("""when\s*\(.*[Ss]torageMode"""))
        )
    }

    @Test
    fun `RawContentSanitizer returns empty for DO_NOT_STORE`() {
        val file = File("app/src/main/java/com/yourname/expensetracker/domain/privacy/RawContentSanitizer.kt")
        assertTrue("RawContentSanitizer.kt must exist", file.exists())
        val content = file.readText()
        assertTrue(
            "RawContentSanitizer must handle DO_NOT_STORE with empty string",
            content.contains("DO_NOT_STORE") && content.contains("\"\"")
        )
    }
}
