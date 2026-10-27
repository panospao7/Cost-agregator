package com.yourname.expensetracker.ui.applock

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
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

    private val forbiddenTokens = listOf(
        "immediate",
        "Unconfined",
        "collectAsStateWithLifecycle",
        "LaunchedEffect",
        "SideEffect",
        "DisposableEffect"
    )

    private fun forbiddenIn(sanitized: String): List<String> = forbiddenTokens.filter { sanitized.contains(it) }

    @Test
    fun inv53_r2_appLockGateSourceHasNoImmediateUnconfinedLifecycleCollectionOrEffects() {
        val file = File(sourceRoot, gateFile)
        assertTrue("missing $gateFile", file.isFile)
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())

        assertTrue("scan read an implausibly small gate file", sanitized.contains("fun AppLockGate("))
        assertTrue("the gate must still collect with collectAsState", sanitized.contains("collectAsState"))
        assertEquals("forbidden tokens in $gateFile", emptyList<String>(), forbiddenIn(sanitized))
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
