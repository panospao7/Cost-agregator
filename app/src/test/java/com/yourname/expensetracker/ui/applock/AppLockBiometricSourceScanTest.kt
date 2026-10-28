package com.yourname.expensetracker.ui.applock

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S15, INV-23 and INV-53: feature-scoped source scan (TECH-10; not a registered guard).
 *
 * Scans every production Kotlin file, with comments and string bodies blanked:
 * - BIOMETRIC_WEAK is never referenced in code;
 * - no file sets a negative button text or the deprecated credential flag, and no file that uses a
 *   biometric prompt API sets a description;
 * - no call to canAuthenticate takes zero arguments;
 * - setAllowedAuthenticators(, canAuthenticate( and BiometricPrompt( appear only in the two allowlisted
 *   files, and there every setAllowedAuthenticators and canAuthenticate call passes the named constant.
 *
 * A planted self-check proves each rule can fail.
 */
class AppLockBiometricSourceScanTest {

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

    private val promptFile = "com/yourname/expensetracker/ui/applock/BiometricPromptDeviceAuthenticator.kt"
    private val providerFile = "com/yourname/expensetracker/data/privacy/applock/AndroidDeviceAuthCapabilityProvider.kt"
    private val allowlist = setOf(promptFile, providerFile)

    private val callPattern = Regex("""(?<!fun )\b(setAllowedAuthenticators|canAuthenticate)\(\s*([^)]*?)\s*\)""")
    private val libraryEntryPattern = Regex("""(?<!fun )\b(setAllowedAuthenticators|canAuthenticate)\(|\bBiometricPrompt\(""")
    private val zeroArgCanAuthenticate = Regex("""(?<!fun )\bcanAuthenticate\(\s*\)""")
    private val biometricApiImport = Regex("""import\s+(androidx\.biometric|android\.hardware\.biometrics)\b""")

    /** Violations of the rules above in one sanitized file, as short bounded descriptions. */
    private fun violations(relative: String, sanitized: String): List<String> {
        val found = mutableListOf<String>()
        if (sanitized.contains("BIOMETRIC_WEAK")) found += "$relative: BIOMETRIC_WEAK in code"
        for (token in listOf("setNegativeButtonText", "setDeviceCredentialAllowed")) {
            if (sanitized.contains("$token(")) found += "$relative: $token"
        }
        if (biometricApiImport.containsMatchIn(sanitized) && sanitized.contains("setDescription(")) {
            found += "$relative: setDescription with a biometric API"
        }
        if (zeroArgCanAuthenticate.containsMatchIn(sanitized)) found += "$relative: canAuthenticate without a constant"
        if (relative !in allowlist && libraryEntryPattern.containsMatchIn(sanitized)) {
            found += "$relative: library entry point outside the allowlist"
        }
        return found
    }

    private fun productionFiles(): List<Pair<String, String>> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                file.relativeTo(sourceRoot).invariantSeparatorsPath to
                    SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
            }
            .toList()

    private fun sanitized(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("missing $relative", file.isFile)
        return SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    private fun assertCallsUseConstant(relative: String, callName: String, constantName: String) {
        val calls = callPattern.findAll(sanitized(relative)).filter { it.groupValues[1] == callName }.toList()
        assertTrue("$relative has no $callName call", calls.isNotEmpty())
        for (call in calls) {
            assertEquals("$relative $callName argument", constantName, call.groupValues[2])
        }
    }

    @Test
    fun inv23_inv53_noProductionFileBreaksTheBiometricRules() {
        val files = productionFiles()
        assertTrue("scan found suspiciously few files: ${files.size}", files.size > 100)
        assertTrue("allowlisted files must be scanned", files.map { it.first }.containsAll(allowlist))
        val all = files.flatMap { (relative, text) -> violations(relative, text) }
        assertTrue("violations: $all", all.isEmpty())
    }

    @Test
    fun inv23_setAllowedAuthenticators_usesTheNamedConstant() {
        assertCallsUseConstant(promptFile, "setAllowedAuthenticators", "PROMPT_ALLOWED_AUTHENTICATORS")
    }

    @Test
    fun inv23_canAuthenticate_usesTheNamedConstant() {
        assertCallsUseConstant(providerFile, "canAuthenticate", "APP_LOCK_ALLOWED_AUTHENTICATORS")
    }

    @Test
    fun selfCheck_plantedViolations_areDetected() {
        val planted = """
            package planted
            import androidx.biometric.BiometricPrompt
            fun f(m: Any, b: Any) {
                b.setDescription("d").setNegativeButtonText("n").setDeviceCredentialAllowed(true)
                m.canAuthenticate()
                m.setAllowedAuthenticators(BIOMETRIC_WEAK)
                BiometricPrompt(a, e, c)
            }
        """.trimIndent()

        val outside = violations("planted/Planted.kt", SourceTextSanitizer.stripCommentsAndStringBodies(planted))
        val inside = violations(promptFile, SourceTextSanitizer.stripCommentsAndStringBodies(planted))

        for (expected in listOf(
            "BIOMETRIC_WEAK in code",
            "setNegativeButtonText",
            "setDeviceCredentialAllowed",
            "setDescription with a biometric API",
            "canAuthenticate without a constant"
        )) {
            assertTrue("outside allowlist missed: $expected in $outside", outside.any { it.endsWith(expected) })
            assertTrue("inside allowlist missed: $expected in $inside", inside.any { it.endsWith(expected) })
        }
        assertTrue("allowlist rule missed: $outside", outside.any { it.endsWith("library entry point outside the allowlist") })
        assertTrue("allowlisted file wrongly flagged: $inside", inside.none { it.endsWith("library entry point outside the allowlist") })
    }

    @Test
    fun selfCheck_commentsAndStrings_areNotViolations() {
        val planted = """
            package planted
            /** BIOMETRIC_WEAK, setNegativeButtonText( and canAuthenticate() are only mentioned here. */
            fun f() {
                // setDeviceCredentialAllowed(true)
                val s = "BiometricPrompt( setAllowedAuthenticators( setDescription("
            }
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            violations("planted/Planted.kt", SourceTextSanitizer.stripCommentsAndStringBodies(planted))
        )
    }
}
