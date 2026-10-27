package com.yourname.expensetracker.ui.applock

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S19 privacy review Finding 3 (R2, INV-53): feature-scoped source pin (not a registered guard).
 *
 * The gate must collect the controller pair on the composition's Main dispatcher with plain `collectAsState`, and must
 * start nothing from composition. With comments and string bodies blanked, `AppLockGate.kt` contains none of the
 * tokens below: no `Main.immediate`/`Unconfined` collection, no lifecycle-aware collection, no effect that could run a
 * transition or a prompt. A planted self-check proves the scan can fail.
 */
class AppLockGateSourceScanTest {

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

    private val gateFile = "com/yourname/expensetracker/ui/applock/AppLockGate.kt"

    /** S20-c: the Activity-side shell is scanned with the gate; the set gains exactly this file. */
    private val shellFile = "com/yourname/expensetracker/ui/applock/AppLockShell.kt"
    private val scannedFiles = listOf(gateFile, shellFile)

    private val forbiddenTokens = listOf(
        "immediate",
        "Unconfined",
        "collectAsStateWithLifecycle",
        "LaunchedEffect",
        "SideEffect",
        "DisposableEffect"
    )

    private fun forbiddenIn(sanitized: String): List<String> = forbiddenTokens.filter { sanitized.contains(it) }

    /** The shell must call the gate's source overload: `AppLockGate(source = ...`, not the state/notice overload. */
    private val sourceOverloadCall = Regex("""\bAppLockGate\s*\(\s*source\s*=""")

    private fun sanitizedOf(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("missing $relative", file.isFile)
        return SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    @Test
    fun inv53_r2_everyScannedFileHasNoImmediateUnconfinedLifecycleCollectionOrEffects() {
        // One loop over the scanned set, so the set pin below is what decides which files are covered.
        for (relative in scannedFiles) {
            assertEquals("forbidden tokens in $relative", emptyList<String>(), forbiddenIn(sanitizedOf(relative)))
        }
    }

    @Test
    fun inv53_r2_theGateStillCollectsWithPlainCollectAsState() {
        val sanitized = sanitizedOf(gateFile)

        assertTrue("scan read an implausibly small gate file", sanitized.contains("fun AppLockGate("))
        assertTrue("the gate must still collect with collectAsState", sanitized.contains("collectAsState"))
    }

    @Test
    fun inv53_r2_theShellHandsTheGateTheSourceOverload() {
        val sanitized = sanitizedOf(shellFile)

        assertTrue("scan read an implausibly small shell file", sanitized.contains("fun AppLockShell("))
        assertTrue("the shell must call AppLockGate(source = ...", sourceOverloadCall.containsMatchIn(sanitized))
    }

    @Test
    fun scannedFileSet_isExactlyTheGateAndTheShell() {
        assertEquals(listOf(gateFile, shellFile), scannedFiles)
        scannedFiles.forEach { assertTrue("missing $it", File(sourceRoot, it).isFile) }
    }

    @Test
    fun selfCheck_sourceOverloadRegex_acceptsTheSourceOverloadOnly() {
        assertTrue(sourceOverloadCall.containsMatchIn("AppLockGate(\n    source = source,\n    x = 1\n)"))
        assertTrue(sourceOverloadCall.containsMatchIn("AppLockGate ( source=s )"))
        assertFalse(sourceOverloadCall.containsMatchIn("AppLockGate(state = s, notice = n)"))
        assertFalse(sourceOverloadCall.containsMatchIn("fun AppLockGate(source: AppLockGateSource)"))
        assertFalse(sourceOverloadCall.containsMatchIn("AppLockGate(state = s, source = x)"))
    }

    @Test
    fun selfCheck_plantedTokens_areDetected() {
        val planted = """
            package planted
            fun f(flow: Any) {
                val a = Dispatchers.Main.immediate
                val b = Dispatchers.Unconfined
                val c = flow.collectAsStateWithLifecycle()
                LaunchedEffect(Unit) {}
                SideEffect {}
                DisposableEffect(Unit) {}
            }
        """.trimIndent()

        assertEquals(forbiddenTokens, forbiddenIn(SourceTextSanitizer.stripCommentsAndStringBodies(planted)))
    }

    @Test
    fun selfCheck_commentsAndStrings_areNotViolations() {
        val planted = """
            package planted
            /** Not Main.immediate or Unconfined; no LaunchedEffect, SideEffect, DisposableEffect or collectAsStateWithLifecycle. */
            fun f() {
                // Dispatchers.Main.immediate
                val s = "Unconfined LaunchedEffect collectAsStateWithLifecycle"
            }
        """.trimIndent()

        assertEquals(
            emptyList<String>(),
            forbiddenIn(SourceTextSanitizer.stripCommentsAndStringBodies(planted))
        )
    }
}
