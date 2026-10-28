package com.yourname.expensetracker.ui.applock

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S21-b architecture-guardian P2 (test-only strengthening, owner-approvals item 62): the exact-path caller pin for
 * the deep-link applier and for the released-link value.
 *
 * Scans every text file under `app/src/main`, `app/src/debug` and `app/src/release` (the variants that exist):
 * - the token `DeepLinkApplier` may appear only in `ui/navigation/DeepLinkApplier.kt` (the declaration) and
 *   `ui/applock/AppLockDeepLinkRouter.kt` (the one caller, S21-b: the router is the only way a link reaches the
 *   applier, so the lock decision cannot be bypassed from `MainActivity`, a ViewModel or a debug screen).
 *   Kotlin and Java files are scanned with comments and string bodies blanked; a second pass over the raw text rejects
 *   any string literal that names the class (reflection, `Class.forName`);
 * - `HeldLinkRelease.Apply(` (a released link, the only value that makes the applier navigate after a hold) is
 *   constructed only in `domain/privacy/applock/AppLockController.kt`, which hands a held link out at most once. The
 *   router only matches on it (`is HeldLinkRelease.Apply`); it never constructs one. An unqualified `Apply(` is
 *   allowed only in `HeldDeepLink.kt`, the declaration.
 *
 * Verified with a text search when written (2026-10-28): the files above are exactly the current referencing set.
 * Planted self-checks prove each rule can fail. Not a registered guard.
 */
class DeepLinkApplierCallerPinTest {

    private val srcRoot: File by lazy {
        val candidates = listOf(
            File("src"),
            File("app/src"),
            File(System.getProperty("user.dir") ?: ".", "src"),
            File(System.getProperty("user.dir") ?: ".", "app/src")
        )
        candidates.firstOrNull { File(it, "main").isDirectory && File(it, "test").isDirectory }
            ?: error("Could not locate app/src. user.dir=${System.getProperty("user.dir")}")
    }

    private val base = "java/com/yourname/expensetracker"
    private val applierFile = "main/$base/ui/navigation/DeepLinkApplier.kt"
    private val routerFile = "main/$base/ui/applock/AppLockDeepLinkRouter.kt"
    private val controllerFile = "main/$base/domain/privacy/applock/AppLockController.kt"
    private val heldLinkFile = "main/$base/domain/privacy/applock/HeldDeepLink.kt"

    private val applierAllowed = setOf(applierFile, routerFile)
    private val applyConstructionAllowed = setOf(controllerFile)
    private val unqualifiedApplyAllowed = setOf(heldLinkFile)

    private val codeExtensions = setOf("kt", "java")
    private val otherTextExtensions = setOf("xml", "pro", "txt", "json", "yml", "yaml", "kts", "gradle", "properties")

    private val applierToken = Regex("""DeepLinkApplier""")
    private val applierStringLiteral = Regex("\"[^\"\\n]*DeepLinkApplier[^\"\\n]*\"")
    private val qualifiedApplyConstruction = Regex("""\bHeldLinkRelease\s*\.\s*Apply\s*\(""")
    private val unqualifiedApplyConstruction = Regex("""(?<![\w.])(?<!\.\s{1,40})Apply\s*\(""")

    /** (path relative to app/src, raw text) of every scanned file. */
    private fun scannedFiles(): List<Pair<String, String>> =
        listOf("main", "debug", "release")
            .map { File(srcRoot, it) }
            .filter { it.isDirectory }
            .flatMap { variantRoot ->
                variantRoot.walkTopDown()
                    .filter { it.isFile && (it.extension in codeExtensions || it.extension in otherTextExtensions) }
                    .map { file -> file.relativeTo(srcRoot).invariantSeparatorsPath to file.readText() }
                    .toList()
            }

    private fun isCode(path: String) = path.substringAfterLast('.') in codeExtensions

    private fun codeView(path: String, raw: String): String =
        if (isCode(path)) SourceTextSanitizer.stripCommentsAndStringBodies(raw) else raw

    private fun applierReferences(files: List<Pair<String, String>>): Set<String> =
        files.filter { (path, raw) -> applierToken.containsMatchIn(codeView(path, raw)) }.map { it.first }.toSet()

    private fun applierStringLiteralHits(files: List<Pair<String, String>>): List<String> =
        files.filter { (path, raw) -> isCode(path) && applierStringLiteral.containsMatchIn(raw) }.map { it.first }

    private fun qualifiedConstructors(files: List<Pair<String, String>>): Set<String> =
        files.filter { (path, raw) -> isCode(path) && qualifiedApplyConstruction.containsMatchIn(codeView(path, raw)) }
            .map { it.first }.toSet()

    private fun unqualifiedConstructors(files: List<Pair<String, String>>): Set<String> =
        files.filter { (path, raw) -> isCode(path) && unqualifiedApplyConstruction.containsMatchIn(codeView(path, raw)) }
            .map { it.first }.toSet()

    @Test
    fun s21b_scanCoversTheProductionTreeAndTheAllowedFilesExist() {
        val files = scannedFiles()
        assertTrue("scan found suspiciously few files: ${files.size}", files.size > 500)
        val paths = files.map { it.first }
        for (allowed in applierAllowed + applyConstructionAllowed + unqualifiedApplyAllowed) {
            assertTrue("allowed file not found (renamed or moved?): $allowed", allowed in paths)
        }
        for (variant in listOf("debug", "release")) {
            val hasSources = File(srcRoot, variant).walkTopDown().any { it.isFile && it.extension in codeExtensions }
            if (hasSources) {
                assertTrue("the $variant variant must be part of the scan", paths.any { it.startsWith("$variant/") })
            }
        }
    }

    @Test
    fun s21b_deepLinkApplierIsReferencedOnlyByItsOwnFileAndTheRouter() {
        assertEquals("files that reference DeepLinkApplier", applierAllowed, applierReferences(scannedFiles()))
    }

    @Test
    fun s21b_noStringLiteralNamesTheApplier() {
        assertEquals(emptyList<String>(), applierStringLiteralHits(scannedFiles()))
    }

    @Test
    fun s21b_heldLinkReleaseApplyIsConstructedOnlyInTheController() {
        val files = scannedFiles()
        assertEquals("files that construct HeldLinkRelease.Apply", applyConstructionAllowed, qualifiedConstructors(files))
        assertEquals("files that construct an unqualified Apply", unqualifiedApplyAllowed, unqualifiedConstructors(files))
    }

    @Test
    fun s21b_theRouterMatchesOnAReleasedLinkButNeverConstructsOne() {
        val router = scannedFiles().single { it.first == routerFile }
        val code = codeView(router.first, router.second)

        assertTrue("the router must match on the released link", Regex("""\bis\s+HeldLinkRelease\s*\.\s*Apply\b""").containsMatchIn(code))
        assertTrue("the router must not construct a released link", !qualifiedApplyConstruction.containsMatchIn(code))
    }

    // ---- planted self-checks ---------------------------------------------------------------------------------

    @Test
    fun selfCheck_aReferenceOutsideTheAllowedFilesIsDetected() {
        val planted = listOf(
            applierFile to "class DeepLinkApplier",
            routerFile to "val a: DeepLinkApplier",
            "main/$base/ui/MainActivity.kt" to "@Inject lateinit var applier: DeepLinkApplier",
            "debug/$base/ui/screens/debug/Probe.kt" to "val c = DeepLinkApplier::class",
            "release/res/xml/rules.xml" to "<keep name=\"DeepLinkApplier\"/>"
        )

        assertEquals(
            setOf(applierFile, routerFile, "main/$base/ui/MainActivity.kt", "debug/$base/ui/screens/debug/Probe.kt", "release/res/xml/rules.xml"),
            applierReferences(planted)
        )
        assertEquals(
            "the allowed set is the two allowed files only",
            setOf("main/$base/ui/MainActivity.kt", "debug/$base/ui/screens/debug/Probe.kt", "release/res/xml/rules.xml"),
            applierReferences(planted) - applierAllowed
        )
    }

    @Test
    fun selfCheck_commentsAndStringsAreNotCodeReferences_butAStringLiteralNameIsStillRejected() {
        val planted = listOf(
            "main/$base/ui/A.kt" to "/** see DeepLinkApplier */ // DeepLinkApplier\nclass A",
            "main/$base/ui/B.kt" to "val n = Class.forName(\"com.x.DeepLinkApplier\")"
        )

        assertEquals(emptySet<String>(), applierReferences(planted))
        assertEquals(listOf("main/$base/ui/B.kt"), applierStringLiteralHits(planted))
    }

    @Test
    fun selfCheck_aReleasedLinkConstructedElsewhereIsDetected() {
        val planted = listOf(
            controllerFile to "return HeldLinkRelease.Apply(request, afterUnlock = false)",
            routerFile to "val r = HeldLinkRelease.Apply(request, true)",
            "main/$base/ui/MainViewModel.kt" to "val r = HeldLinkRelease . Apply (request, true)",
            heldLinkFile to "data class Apply(val request: R, val afterUnlock: Boolean)",
            "main/$base/ui/C.kt" to "import x.HeldLinkRelease.Apply\nval r = Apply(request, true)",
            "main/$base/ui/D.kt" to "when (r) { is HeldLinkRelease.Apply -> Unit }\nfun onApply(x: Int) = startApply(x)",
            "main/$base/ui/E.kt" to "// HeldLinkRelease.Apply(x) in a comment\nval s = \"HeldLinkRelease.Apply(x)\"",
            "main/$base/ui/F.kt" to "val r = HeldLinkRelease.\n    Apply(request, true)"
        )

        assertEquals(
            setOf(controllerFile, routerFile, "main/$base/ui/MainViewModel.kt", "main/$base/ui/F.kt"),
            qualifiedConstructors(planted)
        )
        assertEquals(setOf(heldLinkFile, "main/$base/ui/C.kt"), unqualifiedConstructors(planted))
    }
}
