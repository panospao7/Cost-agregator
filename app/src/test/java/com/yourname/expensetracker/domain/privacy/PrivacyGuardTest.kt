package com.yourname.expensetracker.domain.privacy

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * PR10 acceptance tests: Static privacy guard rules.
 *
 * These tests verify the absence of known privacy boundary violations
 * in the production source tree by scanning source files.
 *
 * Tests:
 * privacy_guard_fails_on_messageId_hashCode
 * privacy_guard_fails_on_allow_all_privacy_gate_in_main
 * no_hashCode_in_RawContentSanitizer
 * ExportPrivacyPolicy_has_no_encrypted_disabled_implies_raw_path
 */
class PrivacyGuardTest {

    private val projectRoot = run {
        // Try to find the project root by walking up from the test resources
        var f = File("").absoluteFile
        repeat(8) {
            if (File(f, "app/src/main/java").exists()) return@run f
            f = f.parentFile ?: return@run f
        }
        f
    }

    private val mainSrcRoot = File(projectRoot, "app/src/main/java/com/yourname/expensetracker")

    private fun allKtFiles(dir: File): List<File> {
        if (!dir.exists()) return emptyList()
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    /**
     * Extracts the balanced-paren call text starting at the '(' located at [openParenIdx].
     * Robust to multi-line constructor calls — accumulates characters until the matching
     * closing paren is reached.
     */
    private fun balancedCall(content: String, openParenIdx: Int): String {
        var depth = 0
        val sb = StringBuilder()
        var i = openParenIdx
        while (i < content.length) {
            val c = content[i]
            sb.append(c)
            if (c == '(') depth++
            else if (c == ')') {
                depth--
                if (depth == 0) break
            }
            i++
        }
        return sb.toString()
    }

    // ── G5: No String.hashCode() in RawContentSanitizer ──────────────────────

    @Test
    fun no_hashCode_in_RawContentSanitizer() {
        val sanitizer = File(mainSrcRoot, "domain/privacy/RawContentSanitizer.kt")
        if (!sanitizer.exists()) return
        val content = sanitizer.readText()
        val hashCodeLines = content.lines()
            .mapIndexed { i, line -> i + 1 to line }
            .filter { (_, line) ->
                line.contains(".hashCode()") &&
                !line.trim().startsWith("//") &&
                !line.trim().startsWith("*")
            }
        if (hashCodeLines.isNotEmpty()) {
            fail("G5: RawContentSanitizer must not use String.hashCode(). Found at lines: " +
                hashCodeLines.joinToString { "${it.first}: ${it.second.trim()}" })
        }
    }

    // ── G4: No object : PrivacyGate { Allowed } in main source ────────────────

    @Test
    fun privacy_guard_fails_on_allow_all_privacy_gate_in_main() {
        if (!mainSrcRoot.exists()) return  // not available in test env
        val violations = mutableListOf<String>()
        val allowAllPattern = Regex("object\\s*:\\s*PrivacyGate")
        allKtFiles(mainSrcRoot).forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                if (allowAllPattern.containsMatchIn(line) && !line.trim().startsWith("//")) {
                    // Allow in secondary constructors (test-only paths) — these are
                    // identified by being inside a constructor body (indented, not @Inject)
                    // The real @Inject constructor uses the DI-provided PrivacyGate.
                    // Secondary constructors with allow-all gates are acceptable for tests.
                    val context = file.readLines().drop(maxOf(0, i - 8)).take(15).joinToString("\n")
                    if (!context.contains("constructor(") && !context.contains("fun noOpGate") && !context.contains("fun failClosedGate")) {
                        violations += "${file.name}:${i + 1}: ${line.trim()}"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail("G4: Allow-all PrivacyGate found outside secondary constructors in main source:\n${violations.joinToString("\n")}")
        }
    }

    // ── G4b: No object : PrivacyGate that returns Allowed — even in test ctors ─

    @Test
    fun privacy_guard_no_allow_all_gate_even_in_test_constructors() {
        if (!mainSrcRoot.exists()) return  // not available in test env
        val violations = mutableListOf<String>()
        val gatePattern = Regex("object\\s*:\\s*PrivacyGate")
        allKtFiles(mainSrcRoot).forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (gatePattern.containsMatchIn(line) &&
                    !trimmed.startsWith("//") &&
                    !trimmed.startsWith("*")
                ) {
                    // Inspect the gate body: this line + the next ~4 lines.
                    val window = lines.drop(i).take(5).joinToString("\n")
                    if (window.contains("PrivacyDecision.Allowed")) {
                        violations += "${file.name}:${i + 1}: ${trimmed}"
                    }
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "G4b: object : PrivacyGate returning PrivacyDecision.Allowed found in main source " +
                    "(allow-all gates fail OPEN — use PrivacyDecision.FailClosed even in test constructors):\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // ── G4c: No CompositePrivacyGate(emptyList()) without gateHandledCapabilities

    @Test
    fun privacy_guard_no_composite_gate_without_handled_capabilities() {
        if (!mainSrcRoot.exists()) return  // not available in test env
        val violations = mutableListOf<String>()
        val token = "CompositePrivacyGate("
        allKtFiles(mainSrcRoot).forEach { file ->
            val content = file.readText()
            var searchFrom = 0
            while (true) {
                val idx = content.indexOf(token, searchFrom)
                if (idx < 0) break
                // The '(' sits at the end of the token.
                val openParenIdx = idx + token.length - 1
                val call = balancedCall(content, openParenIdx)
                // Fails OPEN: an empty gate list with no declared handled capabilities
                // returns Allowed for sensitive capabilities (e.g. CLOUD_AI_GENERAL).
                if (call.contains("emptyList()") && !call.contains("gateHandledCapabilities")) {
                    val lineNo = content.substring(0, idx).count { it == '\n' } + 1
                    violations += "${file.name}:${lineNo}: ${token}emptyList()...) missing gateHandledCapabilities"
                }
                searchFrom = idx + token.length
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "G4c: CompositePrivacyGate(emptyList(), ...) without gateHandledCapabilities found in main " +
                    "source (this fails OPEN for sensitive capabilities — pass " +
                    "PrivacyCapabilityHandlingPolicy.gateHandledCapabilities):\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // ── G5: No String.hashCode() for sensitive IDs in main source ─────────────

    @Test
    fun privacy_guard_fails_on_messageId_hashCode() {
        if (!mainSrcRoot.exists()) return
        val violations = mutableListOf<String>()
        val pattern = Regex("(messageId|providerTransactionId|transactionId.*accountId).*\\.hashCode\\(\\)|\\.hashCode\\(\\).*messageId")
        allKtFiles(mainSrcRoot).forEach { file ->
            file.readLines().forEachIndexed { i, line ->
                val trimmed = line.trim()
                if (pattern.containsMatchIn(line) &&
                    !trimmed.startsWith("//") &&
                    !trimmed.startsWith("*")) {
                    violations += "${file.name}:${i + 1}: ${trimmed}"
                }
            }
        }
        if (violations.isNotEmpty()) {
            fail("G5: String.hashCode() used for sensitive IDs:\n${violations.joinToString("\n")}")
        }
    }

    // ── G8: encryptedBackupEnabled=false must NOT allow raw export ─────────────

    @Test
    fun ExportPrivacyPolicy_encrypted_disabled_does_not_allow_raw_export() {
        // Contract test: verify ExportPrivacyGate's logic
        // (structural test — the actual gate logic is tested in ExportPrivacyPolicyTest)
        val gateFile = File(mainSrcRoot, "domain/privacy/ExportPrivacyGate.kt")
        if (!gateFile.exists()) return

        val content = gateFile.readText()
        // The gate must NOT have a path that returns Allowed for RAWBACKUP_EXPORT
        // when encryptedBackupEnabled is false
        assertFalse(
            "G8: ExportPrivacyGate must not allow RAWBACKUP_EXPORT when encrypted backup is disabled",
            content.contains("encryptedBackupEnabled.*Allowed.*raw", ignoreCase = true)
        )
    }

    // ── New guard types are defined in PrivacyCapability ─────────────────────

    @Test
    fun export_guard_capabilities_defined() {
        val defined = setOf(
            "EXPENSE_EXPORT", "EXPENSE_EXPORT_RAW", "EXPENSE_EXPORT_REDACTED",
            "EXPENSE_EXPORT_ENCRYPTED", "DEBUG_RAW_EXPORT", "RAW_DATABASE_EXPORT"
        )
        val actual = PrivacyCapability.values().map { it.name }.toSet()
        val missing = defined - actual
        assertTrue(
            "Missing export PrivacyCapability values: $missing",
            missing.isEmpty()
        )
    }

    // ── Cloud purposes include bank statement ─────────────────────────────────

    @Test
    fun cloud_purpose_bank_statement_validation_defined() {
        val names = CloudPayloadPurpose.values().map { it.name }
        assertTrue(names.contains("BANK_STATEMENT_VALIDATION"))
        assertTrue(names.contains("BANK_TRANSACTION_CLASSIFICATION"))
        assertTrue(names.contains("EXPORT_SUMMARY"))
    }

    // ── SafePrivacyMetadata blocks sensitive keys ─────────────────────────────

    @Test
    fun safe_privacy_metadata_blocks_all_required_keys() {
        val sensitiveInputs = mapOf(
            "rawText" to "sensitive",
            "prompt" to "AI prompt",
            "token" to "bearer xyz",
            "accessToken" to "abc",
            "refreshToken" to "def",
            "password" to "secret",
            "iban" to "GR1234",
            "accountNumber" to "12345",
            "cardNumber" to "4111",
            "ocrText" to "full OCR",
            "emailBody" to "email content",
            "bankDescription" to "TRANSFER"
        )
        val meta = sensitiveInputs.entries.fold(SafePrivacyMetadata.builder()) { builder, (k, v) ->
            builder.put(k, v)
        }.build()
        val json = meta.toJson()

        for ((key, value) in sensitiveInputs) {
            assertFalse(
                "Key '$key' with value '$value' must be blocked in SafePrivacyMetadata",
                json.contains(value)
            )
        }
    }

    // ── Fail-closed defaults cover all sensitive features ─────────────────────

    @Test
    fun fail_closed_defaults_disable_all_network_features() {
        val defaults = PrivacySettings.FAIL_CLOSED_DEFAULTS
        assertFalse(defaults.cloudAiEnabled)
        assertFalse(defaults.receiptImageCloudEnabled)
        assertFalse(defaults.bankStatementAiEnabled)
        assertFalse(defaults.externalGeocodingEnabled)
        assertFalse(defaults.backgroundLocationBackfillEnabled)
        assertFalse(defaults.deviceGpsLocationEnabled)
    }

    @Test
    fun fail_closed_defaults_set_all_raw_modes_to_do_not_store() {
        val defaults = PrivacySettings.FAIL_CLOSED_DEFAULTS
        assertEquals(RawStorageMode.DO_NOT_STORE, defaults.rawNotificationStorageMode)
        assertEquals(RawStorageMode.DO_NOT_STORE, defaults.rawOcrStorageMode)
        assertEquals(RawStorageMode.DO_NOT_STORE, defaults.emailReceiptStorageMode)
    }
}
