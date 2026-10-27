package com.yourname.expensetracker.ui.applock

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S20-c (TECH-10): feature-scoped source scan (not a registered guard) for the MainActivity shell integration.
 * Comments and string bodies are blanked first.
 *
 * - Exactly one production `MainScreen(` call exists and it sits lexically inside the `AppLockShell { ... }` content
 *   lambda of `MainActivity.kt` (INV-26: the gate decides whether it is composed).
 * - `setContent` holds no Dialog, AlertDialog or Popup outside the shell (CF-5, INV-29: no Activity-level overlay).
 * - `onCreate` order: `applyInitial` is the first statement after `super.onCreate`, before the DB gate; the binder
 *   install, the prompt host attach and the secure-window bind come after the DB gate and before `setContent`; the
 *   binder, host and window are never touched from `onNewIntent`.
 * - `MainActivity` extends `FragmentActivity` (TECH-1 A).
 * - `FLAG_SECURE` and window flag writes exist only in `SecureWindowController.kt` (single writer).
 *
 * Planted self-checks prove each rule can fail.
 */
class MainScreenCallSiteTest {

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

    private val base = "com/yourname/expensetracker"
    private val mainActivityFile = "$base/ui/MainActivity.kt"
    private val secureWindowFile = "$base/ui/applock/SecureWindowController.kt"

    private fun sanitized(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("missing $relative", file.isFile)
        return SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    private fun productionFiles(): List<Pair<String, String>> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .map { file ->
                file.relativeTo(sourceRoot).invariantSeparatorsPath to
                    SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
            }
            .toList()

    // --- lexical helpers ------------------------------------------------------------------------------------

    /** Index of the bracket that closes the one opened at [open], or -1. */
    private fun matching(text: String, open: Int, openChar: Char, closeChar: Char): Int {
        require(text[open] == openChar)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                openChar -> depth++
                closeChar -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return -1
    }

    /** The `{ ... }` range of a trailing lambda of the call that starts at [callStart] (`Name(` ... `) {`). */
    private fun trailingLambda(text: String, callStart: Int): IntRange? {
        val openParen = text.indexOf('(', callStart)
        if (openParen < 0) return null
        val closeParen = matching(text, openParen, '(', ')')
        if (closeParen < 0) return null
        var i = closeParen + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '{') return null
        val close = matching(text, i, '{', '}')
        return if (close < 0) null else i..close
    }

    private val mainScreenCall = Regex("""(?<!fun\s)\bMainScreen\s*\(""")

    private fun mainScreenCallIndices(sanitized: String): List<Int> =
        mainScreenCall.findAll(sanitized).map { it.range.first }.toList()

    private fun shellLambda(sanitized: String): IntRange? {
        val start = Regex("""\bAppLockShell\s*\(""").findAll(sanitized).map { it.range.first }.firstOrNull() ?: return null
        return trailingLambda(sanitized, start)
    }

    private fun setContentRange(sanitized: String): IntRange? {
        // The call, not the import line.
        val start = Regex("""\bsetContent\s*\{""").find(sanitized)?.range?.last ?: return null
        val open = start
        if (sanitized[open] != '{') return null
        val close = matching(sanitized, open, '{', '}')
        return if (close < 0) null else open..close
    }

    // Window-opening composables: each opens its own window, so it would draw over (or beside) the gate.
    private val overlayCall = Regex(
        """\b\w*(Dialog|Popup|ModalBottomSheet|BottomSheet|DropdownMenu|Tooltip)\w*\s*\("""
    )

    private fun overlaysOutsideShell(sanitized: String): List<String> {
        val setContent = setContentRange(sanitized) ?: return listOf("no setContent block")
        val shell = shellLambda(sanitized)
        val outside = StringBuilder()
        for (i in setContent) {
            if (shell != null && i in shell) outside.append(' ') else outside.append(sanitized[i])
        }
        return overlayCall.findAll(outside).map { it.value.trim() }.toList()
    }

    private fun onCreateBody(sanitized: String): String {
        val start = sanitized.indexOf("override fun onCreate(")
        assertTrue("onCreate not found", start >= 0)
        val open = sanitized.indexOf('{', start)
        val close = matching(sanitized, open, '{', '}')
        assertTrue(close > open)
        return sanitized.substring(open + 1, close)
    }

    private fun onNewIntentBody(sanitized: String): String {
        val start = sanitized.indexOf("override fun onNewIntent(")
        assertTrue("onNewIntent not found", start >= 0)
        val open = sanitized.indexOf('{', start)
        return sanitized.substring(open + 1, matching(sanitized, open, '{', '}'))
    }

    // --- MainScreen call site -------------------------------------------------------------------------------

    @Test
    fun inv26_exactlyOneProductionMainScreenCall_andItIsInsideTheAppLockShellLambda() {
        val callsByFile = productionFiles()
            .map { (relative, text) -> relative to mainScreenCallIndices(text).size }
            .filter { it.second > 0 }
        assertEquals(
            "exactly one production MainScreen( call, in MainActivity.kt",
            listOf(mainActivityFile to 1),
            callsByFile
        )

        val text = sanitized(mainActivityFile)
        val shell = shellLambda(text)
        assertNotNull("MainActivity must call AppLockShell with a trailing content lambda", shell)
        val call = mainScreenCallIndices(text).single()
        assertTrue("MainScreen( must be inside the AppLockShell content lambda", call in shell!!)
    }

    @Test
    fun inv26_theShellIsInsideTheNavigationProvider_andMainScreenKeepsItsDeclaration() {
        val text = sanitized(mainActivityFile)
        val provider = text.indexOf("ProvideNavigationController(")
        val shell = Regex("""\bAppLockShell\s*\(""").find(text)?.range?.first ?: -1
        assertTrue(provider in 0 until shell)
        assertTrue("the MainScreen declaration must remain", Regex("""\bfun\s+MainScreen\s*\(""").containsMatchIn(text))
        assertTrue(
            "MainScreen takes the process-wide prompt-once claim",
            text.contains("notificationPromptOnce: NotificationPermissionPromptOnce")
        )
    }

    @Test
    fun cf5_noDialogAlertDialogOrPopupInSetContentOutsideTheShell() {
        assertEquals(emptyList<String>(), overlaysOutsideShell(sanitized(mainActivityFile)))
    }

    // --- onCreate order -------------------------------------------------------------------------------------

    @Test
    fun inv29_applyInitialIsTheFirstStatementAfterSuperOnCreate_andBeforeTheDbGateAndSetContent() {
        val body = onCreateBody(sanitized(mainActivityFile))
        val superCall = body.indexOf("super.onCreate(savedInstanceState)")
        val applyInitial = body.indexOf("applyInitial(window)")
        val dbGate = body.indexOf("DatabaseOpenGate")
        val setContent = body.indexOf("setContent")
        assertTrue(superCall >= 0 && applyInitial > superCall)
        assertEquals(
            "applyInitial must be the first statement after super.onCreate",
            "",
            body.substring(superCall + "super.onCreate(savedInstanceState)".length, applyInitial)
                .replace("secureWindowController.", "")
                .trim()
        )
        assertTrue("applyInitial before the DB gate", applyInitial < dbGate)
        assertTrue("applyInitial before setContent", applyInitial < setContent)
        assertEquals("applyInitial exactly once", 1, Regex("""\bapplyInitial\s*\(""").findAll(body).count())
    }

    @Test
    fun s20a_f4_installAttachAndBindComeAfterTheDbGateAndBeforeSetContent_inThatOrder() {
        val body = onCreateBody(sanitized(mainActivityFile))
        val dbGateReturn = body.indexOf("return", body.indexOf("DatabaseOpenGate"))
        val install = body.indexOf("appLockLifecycleBinder.install(application)")
        val attach = body.indexOf(".attach(this)")
        val bind = body.indexOf("secureWindowController.bind(this)")
        val setContent = body.indexOf("setContent")
        assertTrue("DB gate return found", dbGateReturn >= 0)
        assertTrue("install after the DB gate", install > dbGateReturn)
        assertTrue("attach after install", attach > install)
        assertTrue("bind after attach", bind > attach)
        assertTrue("setContent after bind", setContent > bind)
        assertEquals(1, Regex("""\.install\s*\(""").findAll(body).count())
        assertEquals(1, Regex("""\.attach\s*\(""").findAll(body).count())
        assertEquals(1, Regex("""\.bind\s*\(""").findAll(body).count())
    }

    @Test
    fun s20b_f6_bindInstallAndAttachAreNeverCalledFromOnNewIntent() {
        val text = sanitized(mainActivityFile)
        val body = onNewIntentBody(text)
        listOf("bind(", "install(", "attach(", "applyInitial(", "AppLockShell").forEach {
            assertFalse("onNewIntent must not contain $it", body.contains(it))
        }
        // The only bind call in the whole file is the one in onCreate.
        assertEquals(1, Regex("""\.bind\s*\(""").findAll(text).count())
    }

    @Test
    fun ae39_theR24EffectStartsWithThePromptOnceClaim_beforeThePolicyAndTheStore() {
        val text = sanitized(mainActivityFile)
        val claim = Regex("""LaunchedEffect\s*\(\s*Unit\s*\)\s*\{\s*if\s*\(\s*!\s*notificationPromptOnce\.tryClaim\(\)\s*\)\s*return@LaunchedEffect""")
            .find(text)
        assertNotNull("the R24 effect must start with the prompt-once claim", claim)
        val after = text.substring(claim!!.range.last)
        assertTrue(after.indexOf("NotificationPromptPolicy.shouldAutoPrompt") >= 0)
        assertEquals("exactly one claim in MainActivity.kt", 1, Regex("""\.tryClaim\s*\(""").findAll(text).count())
    }

    @Test
    fun s19InsetsNote_mainScreenKeepsTheDefaultScaffoldInsets_soTheSlotsConsumedInsetsAreHonoured() {
        assertFalse(sanitized(mainActivityFile).contains("contentWindowInsets"))
    }

    @Test
    fun mainActivity_reachesTheLockOnlyThroughTheShellTheBinderTheHostAndTheWindowController() {
        val text = sanitized(mainActivityFile)
        listOf(
            "AppLockAuthenticatorRelay",
            "DeviceAuthenticator",
            "AppLockSettingsOperations",
            "AppLockSettingsRepository",
            "onUnlockTapped",
            "onAppForegrounded",
            "onAppBackgrounded",
            "resolve("
        ).forEach { assertFalse("MainActivity.kt must not reference $it", text.contains(it)) }
    }

    @Test
    fun tech1_mainActivityExtendsFragmentActivity() {
        val text = sanitized(mainActivityFile)
        assertTrue(Regex("""class\s+MainActivity\s*:\s*FragmentActivity\s*\(\s*\)""").containsMatchIn(text))
        assertFalse(Regex("""\bComponentActivity\b""").containsMatchIn(text))
    }

    // --- the single FLAG_SECURE writer ----------------------------------------------------------------------

    private val windowWrite = Regex(
        """\bFLAG_SECURE\b|\bSecureFlagPolicy\b|\bwindow\w*\??\s*\.\s*(addFlags|clearFlags|setFlags)\s*\(|\.attributes\b[^;\n]*\bflags\b"""
    )

    private fun windowWriteViolations(files: List<Pair<String, String>>, allowed: String): List<String> =
        files.filter { (relative, text) -> relative != allowed && windowWrite.containsMatchIn(text) }.map { it.first }

    @Test
    fun inv29_flagSecureAndWindowFlagWritesAppearOnlyInSecureWindowController() {
        val files = productionFiles()
        assertTrue(
            "the single writer must still write the flag",
            windowWrite.containsMatchIn(files.first { it.first == secureWindowFile }.second)
        )
        assertEquals(emptyList<String>(), windowWriteViolations(files, secureWindowFile))
    }

    // --- self-checks ----------------------------------------------------------------------------------------

    @Test
    fun selfCheck_mainScreenCallCounting_seesCallsAndNotDeclarationsOrComments() {
        val planted = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            // MainScreen( in a comment
            fun MainScreen(a: Int) {}
            fun f() { MainScreen(1); MainScreen (2) }
            """.trimIndent()
        )
        assertEquals(2, mainScreenCallIndices(planted).size)
    }

    @Test
    fun selfCheck_aMainScreenCallOutsideTheShellLambda_isDetected() {
        val planted = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            fun f() {
                AppLockShell(controller = c) { Other() }
                MainScreen(a)
            }
            """.trimIndent()
        )
        val shell = shellLambda(planted)!!
        assertFalse(mainScreenCallIndices(planted).single() in shell)
    }

    @Test
    fun selfCheck_aMainScreenCallInsideTheShellLambda_isAccepted_withNestedBracesAndParens() {
        val planted = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            fun f() {
                AppLockShell(controller = g(x), navigateTo = h::i) {
                    Box { MainScreen(a = b(c)) }
                }
            }
            """.trimIndent()
        )
        assertTrue(mainScreenCallIndices(planted).single() in shellLambda(planted)!!)
    }

    @Test
    fun selfCheck_overlaysOutsideTheShell_areDetected_andInsideAreNot() {
        val outside = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            override fun onCreate() {
                setContent {
                    AlertDialog(onDismissRequest = {})
                    AppLockShell(controller = c) { Dialog(onDismissRequest = {}) }
                    Popup(alignment = a) {}
                }
            }
            """.trimIndent()
        )
        assertEquals(listOf("AlertDialog(", "Popup("), overlaysOutsideShell(outside))
        val inside = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            override fun onCreate() {
                setContent {
                    AppLockShell(controller = c) { Dialog(onDismissRequest = {}) }
                }
            }
            """.trimIndent()
        )
        assertEquals(emptyList<String>(), overlaysOutsideShell(inside))
    }

    @Test
    fun selfCheck_otherWindowOpeningComposables_outsideTheShell_areDetected_andInsideAreNot() {
        val outside = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            override fun onCreate() {
                setContent {
                    ModalBottomSheet(onDismissRequest = {}) {}
                    DropdownMenu(expanded = true, onDismissRequest = {}) {}
                    TooltipBox(positionProvider = p, tooltip = {}, state = s) {}
                    AppLockShell(controller = c) { ModalBottomSheet(onDismissRequest = {}) {} }
                }
            }
            """.trimIndent()
        )
        assertEquals(listOf("ModalBottomSheet(", "DropdownMenu(", "TooltipBox("), overlaysOutsideShell(outside))
        val inside = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            override fun onCreate() {
                setContent {
                    AppLockShell(controller = c) {
                        DropdownMenu(expanded = true, onDismissRequest = {}) {}
                        TooltipBox(positionProvider = p, tooltip = {}, state = s) {}
                    }
                }
            }
            """.trimIndent()
        )
        assertEquals(emptyList<String>(), overlaysOutsideShell(inside))
    }

    @Test
    fun selfCheck_windowWriteScan_detectsPlantedWritesAndIgnoresCommentsStringsAndIntentFlags() {
        val bad = listOf(
            "a.kt" to SourceTextSanitizer.stripCommentsAndStringBodies("val f = WindowManager.LayoutParams.FLAG_SECURE"),
            "b.kt" to SourceTextSanitizer.stripCommentsAndStringBodies("fun g(a: Activity) { a.window.addFlags(1) }"),
            "c.kt" to SourceTextSanitizer.stripCommentsAndStringBodies("fun g(d: Dialog) { d.window?.clearFlags(1) }"),
            "d.kt" to SourceTextSanitizer.stripCommentsAndStringBodies("val p = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn)")
        )
        assertEquals(listOf("a.kt", "b.kt", "c.kt", "d.kt"), windowWriteViolations(bad, "allowed.kt"))

        val fine = listOf(
            "e.kt" to SourceTextSanitizer.stripCommentsAndStringBodies(
                """
                // FLAG_SECURE window.addFlags(1)
                val s = "FLAG_SECURE"
                fun g() = Intent(x).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                """.trimIndent()
            ),
            "allowed.kt" to SourceTextSanitizer.stripCommentsAndStringBodies("val f = FLAG_SECURE")
        )
        assertEquals(emptyList<String>(), windowWriteViolations(fine, "allowed.kt"))
    }

    @Test
    fun selfCheck_orderScan_detectsApplyInitialAfterTheDbGate() {
        val planted = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            override fun onCreate(savedInstanceState: Bundle?) {
                super.onCreate(savedInstanceState)
                if (DatabaseOpenGate.blockedReasonOrNull(c) != null) { finish(); return }
                secureWindowController.applyInitial(window)
                setContent { }
            }
            """.trimIndent()
        )
        val body = onCreateBody(planted)
        assertFalse(body.indexOf("applyInitial(window)") < body.indexOf("DatabaseOpenGate"))
    }
}
