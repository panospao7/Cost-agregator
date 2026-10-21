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
 *
 * The invariant "encryptedBackupEnabled=false never implies a raw export path" is proven
 * behaviourally by ExportPrivacyPolicyTest.encrypted_disabled_does_not_allow_rawbackup_export
 * (not by a source scan in this class).
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
        // Fail loudly: a missing scan root must never degrade into "no violations found".
        assertTrue("${dir.path} must exist — static privacy guard cannot scan", dir.exists())
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        // An existing but empty/mis-rooted scan directory must not degrade into "no violations found".
        assertTrue("${dir.path} must contain at least one .kt file — static privacy guard scanned nothing", files.isNotEmpty())
        return files
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

    /**
     * Extracts the balanced-brace block starting at the '{' located at
     * [openBraceIdx] (inclusive of both braces). Used to scan the FULL body of
     * an `object : PrivacyGate { ... }` so a `PrivacyDecision.Allowed` on any
     * line — not just the first few — is caught.
     */
    private fun balancedBraceBody(content: String, openBraceIdx: Int): String {
        var depth = 0
        val sb = StringBuilder()
        var i = openBraceIdx
        while (i < content.length) {
            val c = content[i]
            sb.append(c)
            if (c == '{') depth++
            else if (c == '}') {
                depth--
                if (depth == 0) break
            }
            i++
        }
        return sb.toString()
    }

    /** Returns the trimmed text of the line in [content] containing [index]. */
    private fun lineTextAt(content: String, index: Int): String {
        val start = content.lastIndexOf('\n', index).let { if (it < 0) 0 else it + 1 }
        val end = content.indexOf('\n', index).let { if (it < 0) content.length else it }
        return content.substring(start, end).trim()
    }

    // ── G5: No String.hashCode() in RawContentSanitizer ──────────────────────

    @Test
    fun no_hashCode_in_RawContentSanitizer() {
        val sanitizer = File(mainSrcRoot, "domain/privacy/RawContentSanitizer.kt")
        assertTrue("${sanitizer.path} must exist — G5 guard cannot run", sanitizer.exists())
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
        assertTrue("G4: mainSrcRoot not found: $mainSrcRoot — guard cannot run", mainSrcRoot.exists())
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
        // Fail loudly if the scan root is missing so a green run proves the
        // guard actually executed (previously this returned silently).
        assertTrue(
            "G4b: mainSrcRoot not found: $mainSrcRoot — guard cannot run",
            mainSrcRoot.exists()
        )
        val violations = mutableListOf<String>()
        val gatePattern = Regex("object\\s*:\\s*PrivacyGate")
        allKtFiles(mainSrcRoot).forEach { file ->
            val content = file.readText()
            gatePattern.findAll(content).forEach { match ->
                val matchIdx = match.range.first
                val lineText = lineTextAt(content, matchIdx)
                if (lineText.startsWith("//") || lineText.startsWith("*")) return@forEach
                // Scan the FULL object body via balanced-brace matching — not a
                // fixed 5-line window — so a PrivacyDecision.Allowed on the 6th+
                // line is still caught.
                val braceIdx = content.indexOf('{', match.range.last + 1)
                val body = if (braceIdx >= 0) balancedBraceBody(content, braceIdx) else ""
                if (body.contains("PrivacyDecision.Allowed")) {
                    val lineNo = content.substring(0, matchIdx).count { it == '\n' } + 1
                    violations += "${file.name}:${lineNo}: ${lineText}"
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
        // Fail loudly if the scan root is missing so a green run proves the
        // guard actually executed (previously this returned silently).
        assertTrue(
            "G4c: mainSrcRoot not found: $mainSrcRoot — guard cannot run",
            mainSrcRoot.exists()
        )
        val violations = mutableListOf<String>()
        val token = "CompositePrivacyGate("
        // Whitespace-robust: matches `gateHandledCapabilities = emptySet()` and
        // `gateHandledCapabilities=emptySet()` (any spacing). Such a call NAMES
        // the param but still fails OPEN because no capabilities are guarded.
        val emptyHandledPattern = Regex("gateHandledCapabilities\\s*=\\s*emptySet\\s*\\(\\s*\\)")
        allKtFiles(mainSrcRoot).forEach { file ->
            val content = file.readText()
            var searchFrom = 0
            while (true) {
                val idx = content.indexOf(token, searchFrom)
                if (idx < 0) break
                // The '(' sits at the end of the token.
                val openParenIdx = idx + token.length - 1
                val call = balancedCall(content, openParenIdx)
                // Fails OPEN when the gate list is empty AND either:
                //  (a) gateHandledCapabilities is not passed at all, or
                //  (b) gateHandledCapabilities is passed as emptySet() — naming
                //      the param without guarding any capability still fails open.
                if (call.contains("emptyList()") &&
                    (!call.contains("gateHandledCapabilities") ||
                        emptyHandledPattern.containsMatchIn(call))
                ) {
                    val lineNo = content.substring(0, idx).count { it == '\n' } + 1
                    val reason = if (emptyHandledPattern.containsMatchIn(call))
                        "gateHandledCapabilities = emptySet() (fails OPEN)"
                    else
                        "missing gateHandledCapabilities"
                    violations += "${file.name}:${lineNo}: ${token}emptyList()...) $reason"
                }
                searchFrom = idx + token.length
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "G4c: CompositePrivacyGate(emptyList(), ...) without real gateHandledCapabilities found " +
                    "in main source (this fails OPEN for sensitive capabilities — pass a non-empty " +
                    "PrivacyCapabilityHandlingPolicy.gateHandledCapabilities):\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // ── G4d: No CompositePrivacyGate(emptyList()) in main source ────────

    @Test
    fun noCompositePrivacyGateEmptyListInMainSource() {
        assertTrue(
            "G4d: mainSrcRoot not found: $mainSrcRoot — guard cannot run",
            mainSrcRoot.exists()
        )
        val violations = mutableListOf<String>()
        val token = "CompositePrivacyGate("
        allKtFiles(mainSrcRoot).forEach { file ->
            val content = file.readText()
            var searchFrom = 0
            while (true) {
                val idx = content.indexOf(token, searchFrom)
                if (idx < 0) break
                val openParenIdx = idx + token.length - 1
                val call = balancedCall(content, openParenIdx)
                if (call.contains("emptyList()")) {
                    val lineNo = content.substring(0, idx).count { it == '\n' } + 1
                    val reason = if (call.contains("gateHandledCapabilities"))
                        "has gateHandledCapabilities but empty gates list — no real gates consulted"
                    else
                        "missing gateHandledCapabilities — fails OPEN for local-only capabilities"
                    violations += "${file.name}:$lineNo: CompositePrivacyGate(emptyList()...) — $reason"
                }
                searchFrom = idx + token.length
            }
        }
        if (violations.isNotEmpty()) {
            fail(
                "G4d: CompositePrivacyGate(emptyList(), ...) found in main source — " +
                    "secondary constructors must use `object : PrivacyGate { override suspend fun check(...) = PrivacyDecision.FailClosed(...) }` " +
                    "instead of CompositePrivacyGate with no gates:\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // ── G5: No String.hashCode() for sensitive IDs in main source ─────────────

    @Test
    fun privacy_guard_fails_on_messageId_hashCode() {
        assertTrue("G5: mainSrcRoot not found: $mainSrcRoot — guard cannot run", mainSrcRoot.exists())
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

    // G8 (encryptedBackupEnabled=false must NOT allow raw export) was removed: its String.contains()
    // "regex" could never match (vacuous). The rule is proven behaviourally by
    // ExportPrivacyPolicyTest.encrypted_disabled_does_not_allow_rawbackup_export.

    // Enum-exists checks for the export PrivacyCapability values and the bank/export
    // CloudPayloadPurpose values were removed (tautologies): they are asserted in
    // ExportPrivacyPolicyTest (export capabilities), PrivacyCapabilityHandlingPolicyProductionTest
    // (EXPENSE_EXPORT) and CloudPayloadPolicyTest (cloud purposes).

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
    fun fail_closed_defaults_are_all_off_and_do_not_store() {
        val defaults = PrivacySettings.FAIL_CLOSED_DEFAULTS
        // Fail-closed means a corrupted store must not keep notification capture on
        // (the plain PrivacySettings() default is ON, so this differs on purpose).
        assertFalse("FAIL_CLOSED_DEFAULTS must disable notification capture", defaults.notificationCaptureEnabled)
        assertFalse("FAIL_CLOSED_DEFAULTS must disable cloud AI", defaults.cloudAiEnabled)
        assertFalse("FAIL_CLOSED_DEFAULTS must disable receipt image cloud upload", defaults.receiptImageCloudEnabled)
        assertFalse("FAIL_CLOSED_DEFAULTS must disable bank statement AI", defaults.bankStatementAiEnabled)
        assertFalse("FAIL_CLOSED_DEFAULTS must disable external geocoding", defaults.externalGeocodingEnabled)
        assertFalse(
            "FAIL_CLOSED_DEFAULTS must disable background location backfill",
            defaults.backgroundLocationBackfillEnabled
        )
        assertFalse("FAIL_CLOSED_DEFAULTS must disable device GPS location", defaults.deviceGpsLocationEnabled)
        assertEquals(
            "FAIL_CLOSED_DEFAULTS must not store raw notifications",
            RawStorageMode.DO_NOT_STORE,
            defaults.rawNotificationStorageMode
        )
        assertEquals(
            "FAIL_CLOSED_DEFAULTS must not store raw OCR",
            RawStorageMode.DO_NOT_STORE,
            defaults.rawOcrStorageMode
        )
        assertEquals(
            "FAIL_CLOSED_DEFAULTS must not store email receipts",
            RawStorageMode.DO_NOT_STORE,
            defaults.emailReceiptStorageMode
        )
        assertEquals(
            "FAIL_CLOSED_DEFAULTS must not store raw bank statements",
            RawStorageMode.DO_NOT_STORE,
            defaults.rawBankStatementStorageMode
        )
    }
}
