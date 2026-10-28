package com.yourname.expensetracker.ui.unsupported

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App lock S21-c (#10): structural pins on `UnsupportedDatabaseActivity.kt`. The matching behaviour is driven
 * through the activity's two test seams by `UnsupportedDatabaseActivityExportAuthSeamTest`; these pins are the
 * source-level backstop for the same rules (they fail even if a behaviour test were changed or removed) and for
 * the seam hygiene (the seams are declared once and never assigned from production code). Each pin names the
 * behaviour it backs. Comments and string bodies are blanked first. Not a registered guard.
 *
 * - the gate-not-blocking finish path builds no authenticator;
 * - the document picker is opened only from the export gate: once when auth is not required, once after Succeeded;
 * - a late, repeated or stale outcome is ignored before it can open the picker;
 * - the attempt is recorded as open before `authenticate` runs (a synchronous callback still works);
 * - a non-LAUNCHED answer and a missing authenticator both fail closed;
 * - a second tap while CHECKING, AUTHENTICATING or RUNNING does nothing;
 * - the auth path writes no log line and no diagnostic.
 */
class UnsupportedDatabaseActivitySourceScanTest {

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

    private val activityFile = "com/yourname/expensetracker/ui/unsupported/UnsupportedDatabaseActivity.kt"

    private val source: String by lazy {
        val file = File(sourceRoot, activityFile)
        assertTrue("missing $activityFile", file.isFile)
        SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    private fun bodyOf(text: String, functionName: String): String {
        val start = text.indexOf("fun $functionName(")
        assertTrue("function $functionName not found", start >= 0)
        val open = text.indexOf('{', start)
        assertTrue("function $functionName has no block body", open >= 0)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        error("function $functionName is not closed")
    }

    private fun countOf(text: String, pattern: Regex): Int = pattern.findAll(text).count()

    @Test
    fun s21c_gateNotBlockingFinishPath_buildsNoAuthenticator() {
        val onCreate = bodyOf(source, "onCreate")
        val finish = onCreate.indexOf("finish()")
        val earlyReturn = onCreate.indexOf("return", finish)
        val build = onCreate.indexOf("BiometricPromptDeviceAuthenticator(this)")
        val setContent = onCreate.indexOf("setContent")

        assertTrue("finish() missing in onCreate", finish >= 0)
        assertTrue("the finish path must return before anything is built", earlyReturn > finish)
        assertTrue("the authenticator must be built after the early return", build > earlyReturn)
        assertTrue("the authenticator is built before the content is set", setContent > build)
        assertEquals(
            "the activity file constructs exactly one authenticator",
            1,
            countOf(source, Regex("""\bBiometricPromptDeviceAuthenticator\s*\("""))
        )
    }

    @Test
    fun s21c_thePickerIsOpenedOnlyFromTheExportGate() {
        assertEquals(
            "exactly one launcher.launch( call",
            1,
            countOf(source, Regex("""\blauncher\s*\.\s*launch\s*\("""))
        )
        assertTrue(
            "the button must reach the picker only through onExportRequested",
            Regex("""onClick\s*=\s*\{\s*onExportRequested\s*\{\s*launcher\s*\.\s*launch\s*\(""").containsMatchIn(source)
        )
        assertEquals(
            "the export itself starts only from the picker result",
            1,
            countOf(source, Regex("""(?<!fun )\bstartExport\s*\("""))
        )

        val calls = Regex("""\blaunchPicker\s*\(\s*\)""")
        val gate = bodyOf(source, "onExportRequested")
        val outcome = bodyOf(source, "onExportAuthOutcome")
        assertEquals("one picker call when auth is not required", 1, countOf(gate, calls))
        assertEquals("one picker call after Succeeded", 1, countOf(outcome, calls))
        assertEquals("no other picker call in the file", 2, countOf(source, calls))
        assertTrue(
            "the picker call after a prompt sits behind the Succeeded check",
            outcome.indexOf("DeviceAuthOutcome.Succeeded") in 0 until outcome.indexOf("launchPicker()")
        )
        assertEquals(
            "Succeeded is the only outcome the activity names",
            0,
            countOf(outcome, Regex("""DeviceAuthOutcome\s*\.\s*(Cancelled|NotAvailable|LockedOut|Failed)"""))
        )
    }

    @Test
    fun s21c_aStaleRepeatedOrLateOutcome_isIgnoredBeforeItCanOpenThePicker() {
        val outcome = bodyOf(source, "onExportAuthOutcome")
        val attemptGuard = outcome.indexOf("attemptId != exportAuthAttemptId")
        val stateGuard = outcome.indexOf("exportState != ExportState.AUTHENTICATING")
        val picker = outcome.indexOf("launchPicker()")

        assertTrue("attempt id guard missing", attemptGuard >= 0)
        assertTrue("state guard missing", stateGuard >= 0)
        assertTrue("both guards come before the picker", attemptGuard < picker && stateGuard < picker)
        assertTrue(
            "the guard returns",
            Regex("""attemptId\s*!=\s*exportAuthAttemptId\s*\|\|\s*exportState\s*!=\s*ExportState\.AUTHENTICATING\s*\)\s*return""")
                .containsMatchIn(outcome)
        )
        assertTrue(
            "a finishing or destroyed host never opens the picker",
            outcome.contains("!isFinishing") && outcome.contains("!isDestroyed")
        )
    }

    @Test
    fun s21c_theAttemptIsRecordedOpenBeforeAuthenticateRuns() {
        val begin = bodyOf(source, "beginExportAuthentication")
        val attemptId = begin.indexOf("++exportAuthAttemptId")
        val recorded = begin.indexOf("exportState = ExportState.AUTHENTICATING")
        val call = begin.indexOf(".authenticate(")

        assertTrue(attemptId in 0 until recorded)
        assertTrue("state must be AUTHENTICATING before authenticate is called", recorded in 0 until call)
        assertTrue("the constant UNLOCK copy is the only prompt copy", begin.contains("AuthPromptCopy.UNLOCK"))
        assertEquals(1, countOf(source, Regex("""\bAuthPromptCopy\s*\.""")))
    }

    @Test
    fun s21c_aNonLaunchedAnswerOrAMissingAuthenticator_failsClosed() {
        val begin = bodyOf(source, "beginExportAuthentication")

        assertTrue(
            "a missing authenticator reads as PROMPT_UNAVAILABLE",
            begin.contains("?: AuthLaunch.PROMPT_UNAVAILABLE")
        )
        assertTrue(
            "any answer other than LAUNCHED ends as AUTH_DENIED",
            Regex("""launch\s*!=\s*AuthLaunch\.LAUNCHED[^{]*\{\s*[^}]*exportState\s*=\s*ExportState\.AUTH_DENIED""")
                .containsMatchIn(begin)
        )
        assertEquals(
            "no launch answer other than LAUNCHED is special-cased, so each one is denied",
            0,
            countOf(begin, Regex("""AuthLaunch\.(ALREADY_IN_FLIGHT|HOST_UNAVAILABLE|AWAITING_PRIOR_TERMINAL)"""))
        )
    }

    @Test
    fun s21c_aSecondTapWhileCheckingAuthenticatingOrRunning_doesNothing() {
        val canStart = bodyOf(source, "canStartExport")
        val request = bodyOf(source, "onExportRequested")

        assertTrue(
            "CHECKING, AUTHENTICATING and RUNNING are the blocked states",
            Regex("""ExportState\.CHECKING\s*,\s*ExportState\.AUTHENTICATING\s*,\s*ExportState\.RUNNING\s*->\s*false""")
                .containsMatchIn(canStart)
        )
        assertTrue(
            "the request returns first when a check, prompt or export is in flight",
            Regex("""^\{\s*if\s*\(\s*!canStartExport\(\)\s*\)\s*return""").containsMatchIn(request)
        )
        assertTrue(
            "the button is disabled in those states",
            Regex("""enabled\s*=\s*canStartExport\(\)""").containsMatchIn(source)
        )
    }

    @Test
    fun s21c_theAuthPathWritesNoLogLineAndNoDiagnostic() {
        val start = source.indexOf("fun onExportRequested(")
        val end = source.indexOf("fun startExport(")
        assertTrue(start in 0 until end)
        val authPath = source.substring(start, end)

        assertEquals(
            emptyList<String>(),
            listOf("Log.", "Timber", "println", "PrivacyDiagnostic", "Diagnostic", "printStackTrace", ".message", "toString(")
                .filter { authPath.contains(it) }
        )
    }

    // ---- pin hardening (privacy-security-guardian P2, owner-approvals item 75b) -----------------------------------
    //
    // The unsupported-database surface is the activity file plus UnsupportedDatabaseExportAuth.kt. Rules:
    // - the @EntryPoint interface has exactly two members, with exactly these return types;
    // - at most one @EntryPoint on the surface, and no EntryPoints.get( / fromActivity( / fromFragment( lookup;
    // - no file under app/src/main, debug or release assigns a test seam of the activity companion.

    private val authFile = "com/yourname/expensetracker/ui/unsupported/UnsupportedDatabaseExportAuth.kt"

    private val authSource: String by lazy {
        val file = File(sourceRoot, authFile)
        assertTrue("missing $authFile", file.isFile)
        SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    private val expectedEntryPointMembers = mapOf(
        "appLockSettingsRepository" to "AppLockSettingsRepository",
        "deviceAuthCapabilityProvider" to "DeviceAuthCapabilityProvider"
    )

    private val entryPointDeclaration = Regex("""interface\s+UnsupportedDatabaseExportAuthEntryPoint\s*\{""")
    private val entryPointMember = Regex("""\bfun\s+(\w+)\s*\(\s*\)\s*:\s*(\w+)\s*""")
    private val entryPointAnnotation = Regex("""@EntryPoint\b""")
    private val forbiddenLookups = mapOf(
        "EntryPoints.get(" to Regex("""\bEntryPoints\s*\.\s*get\s*\("""),
        "fromActivity(" to Regex("""\bfromActivity\s*\("""),
        "fromFragment(" to Regex("""\bfromFragment\s*\(""")
    )
    private val seamNames = listOf("exportAuthRequiredForTest", "authenticatorFactoryForTest")

    /** The text between the braces of the entry point interface, or null when it is missing or not closed. */
    private fun entryPointBody(sanitized: String): String? {
        val declaration = entryPointDeclaration.find(sanitized) ?: return null
        val open = declaration.range.last
        var depth = 0
        for (i in open until sanitized.length) {
            when (sanitized[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return sanitized.substring(open + 1, i)
                }
            }
        }
        return null
    }

    /**
     * Why the entry point is not exactly {AppLockSettingsRepository, DeviceAuthCapabilityProvider}; empty when it is.
     * The member names and the return TYPES must match, and nothing else (no other fun, val, var or parameter) may be
     * declared in the body.
     */
    private fun entryPointProblems(sanitized: String): List<String> {
        val body = entryPointBody(sanitized) ?: return listOf("entry point interface missing or not closed")
        val found = entryPointMember.findAll(body).map { it.groupValues[1] to it.groupValues[2] }.toList()
        val problems = mutableListOf<String>()
        if (found.toMap() != expectedEntryPointMembers || found.size != expectedEntryPointMembers.size) {
            problems += "members (name to type) were $found, expected ${expectedEntryPointMembers.toList()}"
        }
        if (found.map { it.second }.toSet() != expectedEntryPointMembers.values.toSet()) {
            problems += "member types were ${found.map { it.second }}"
        }
        val residue = entryPointMember.replace(body, "").trim()
        if (residue.isNotEmpty()) problems += "unexpected declaration text in the body: '$residue'"
        return problems
    }

    private fun lookupProblems(surface: List<String>): List<String> =
        forbiddenLookups.filter { (_, pattern) -> surface.any { pattern.containsMatchIn(it) } }.map { it.key }

    private fun entryPointAnnotationCount(surface: List<String>): Int =
        surface.sumOf { entryPointAnnotation.findAll(it).count() }

    /**
     * Lines (file:line) that assign a seam, other than the `var name: Type = null` declaration. Reads
     * (`val g = name`, `name?.invoke`) are not assignments; `==` is not an assignment.
     */
    private fun seamAssignments(files: List<Pair<String, String>>): List<String> {
        val hits = mutableListOf<String>()
        for ((path, raw) in files) {
            val code = SourceTextSanitizer.stripCommentsAndStringBodies(raw)
            for (name in seamNames) {
                // `name = ...`, a generated setter call (`setName(`, including the `$module` mangled form of an
                // internal property, e.g. `setNameForTest$app_release(`) as a Java caller would write it, or a
                // property reference (`::name`, which can be used to assign through `KMutableProperty0.set`).
                val setter = "set" + name.replaceFirstChar { it.uppercaseChar() }
                val assignment = Regex("""\b$name\s*=(?!=)|\b${setter}[\w$]*\s*\(|::\s*$name\b""")
                val declaration = Regex("""\b(var|val)\s+$name\b""")
                for (match in assignment.findAll(code)) {
                    val lineStart = code.lastIndexOf('\n', match.range.first) + 1
                    val lineEnd = code.indexOf('\n', match.range.first).let { if (it < 0) code.length else it }
                    if (declaration.containsMatchIn(code.substring(lineStart, lineEnd))) continue
                    val line = code.substring(0, match.range.first).count { it == '\n' } + 1
                    hits += "$path:$line:$name"
                }
            }
        }
        return hits
    }

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

    private fun productionFiles(): List<Pair<String, String>> =
        listOf("main", "debug", "release")
            .map { File(srcRoot, it) }
            .filter { it.isDirectory }
            .flatMap { variantRoot ->
                variantRoot.walkTopDown()
                    .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
                    .map { file -> file.relativeTo(srcRoot).invariantSeparatorsPath to file.readText() }
                    .toList()
            }

    @Test
    fun s21c_pin_entryPointMembersAreExactlyTheLockRepositoryAndTheCapabilityProvider() {
        assertEquals(emptyList<String>(), entryPointProblems(authSource))
        assertTrue(
            "the member types are the domain ports, not look-alikes",
            authSource.contains("import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository") &&
                authSource.contains("import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapabilityProvider")
        )
    }

    @Test
    fun s21c_pin_theSurfaceHasAtMostOneEntryPointAndNoOtherLookupForm() {
        val surface = listOf(source, authSource)

        assertEquals("at most one @EntryPoint on the surface", 1, entryPointAnnotationCount(surface))
        assertEquals(emptyList<String>(), lookupProblems(surface))
        assertEquals(
            "exactly one fromApplication lookup, in the auth file",
            1,
            Regex("""EntryPointAccessors\s*\.\s*fromApplication\s*\(""").findAll(authSource).count() +
                Regex("""EntryPointAccessors\s*\.\s*fromApplication\s*\(""").findAll(source).count()
        )
    }

    @Test
    fun s21c_pin_theSeamsAreDeclaredOnceAsNullInternalVarsInTheActivityCompanion() {
        for (name in seamNames) {
            assertEquals(
                "$name must be declared once as `internal var $name: <type>? = null`",
                1,
                Regex("""@VisibleForTesting\s+internal\s+var\s+$name\s*:[^=]*\?\s*=\s*null\b""").findAll(source).count()
            )
        }
    }

    @Test
    fun s21c_pin_noProductionFileAssignsATestSeam() {
        val files = productionFiles()
        assertTrue("scan found suspiciously few files: ${files.size}", files.size > 200)
        assertTrue(
            "the activity (the declaring file) must be part of the scan",
            files.any { it.first == "main/java/$activityFile" }
        )
        assertEquals(emptyList<String>(), seamAssignments(files))
    }

    // ---- planted self-checks for the pin hardening ------------------------------------------------------------

    private fun plantedEntryPoint(body: String): String = SourceTextSanitizer.stripCommentsAndStringBodies(
        "@EntryPoint\n@InstallIn(SingletonComponent::class)\ninterface UnsupportedDatabaseExportAuthEntryPoint {\n$body\n}\n"
    )

    @Test
    fun s21c_pin_selfCheck_theEntryPointMemberRuleCanFail() {
        val good = plantedEntryPoint(
            "fun appLockSettingsRepository(): AppLockSettingsRepository\n" +
                "fun deviceAuthCapabilityProvider(): DeviceAuthCapabilityProvider"
        )
        assertEquals("the exact shape passes", emptyList<String>(), entryPointProblems(good))

        val wrongType = plantedEntryPoint(
            "fun appLockSettingsRepository(): AppLockSettingsRepository\n" +
                "fun deviceAuthCapabilityProvider(): AppDatabase"
        )
        val swappedTypes = plantedEntryPoint(
            "fun appLockSettingsRepository(): DeviceAuthCapabilityProvider\n" +
                "fun deviceAuthCapabilityProvider(): AppLockSettingsRepository"
        )
        val extraMember = plantedEntryPoint(
            "fun appLockSettingsRepository(): AppLockSettingsRepository\n" +
                "fun deviceAuthCapabilityProvider(): DeviceAuthCapabilityProvider\n" +
                "fun expenseDao(): ExpenseDao"
        )
        val missingMember = plantedEntryPoint("fun appLockSettingsRepository(): AppLockSettingsRepository")
        val withParameter = plantedEntryPoint(
            "fun appLockSettingsRepository(): AppLockSettingsRepository\n" +
                "fun deviceAuthCapabilityProvider(): DeviceAuthCapabilityProvider\n" +
                "fun other(x: Int): Foo"
        )
        val withProperty = plantedEntryPoint(
            "fun appLockSettingsRepository(): AppLockSettingsRepository\n" +
                "fun deviceAuthCapabilityProvider(): DeviceAuthCapabilityProvider\n" +
                "val database: AppDatabase"
        )
        for ((label, planted) in mapOf(
            "wrong type" to wrongType,
            "swapped types" to swappedTypes,
            "extra member" to extraMember,
            "missing member" to missingMember,
            "member with a parameter" to withParameter,
            "property member" to withProperty
        )) {
            assertTrue("$label was not detected", entryPointProblems(planted).isNotEmpty())
        }
        assertTrue(entryPointProblems("class NoInterface").isNotEmpty())
    }

    @Test
    fun s21c_pin_selfCheck_theLookupAndEntryPointCountRulesCanFail() {
        fun clean(text: String) = SourceTextSanitizer.stripCommentsAndStringBodies(text)

        assertEquals(emptyList<String>(), lookupProblems(listOf(clean("EntryPointAccessors.fromApplication(c, X::class.java)"))))
        assertEquals(listOf("EntryPoints.get("), lookupProblems(listOf(clean("val e = EntryPoints.get(app, X::class.java)"))))
        assertEquals(listOf("EntryPoints.get("), lookupProblems(listOf(clean("EntryPoints . get (app, X::class.java)"))))
        assertEquals(listOf("fromActivity("), lookupProblems(listOf(clean("EntryPointAccessors.fromActivity(a, X::class.java)"))))
        assertEquals(listOf("fromFragment("), lookupProblems(listOf(clean("EntryPointAccessors.fromFragment(f, X::class.java)"))))
        assertEquals(
            "a violation in the second file of the surface is found",
            listOf("fromActivity("),
            lookupProblems(listOf(clean("val a = 1"), clean("EntryPointAccessors.fromActivity(a, X::class.java)")))
        )
        assertEquals(
            "comments and strings are not lookups",
            emptyList<String>(),
            lookupProblems(listOf(clean("/** EntryPoints.get( fromActivity( */ val s = \"fromFragment(\"")))
        )

        assertEquals(1, entryPointAnnotationCount(listOf(clean("@EntryPoint\ninterface A"), clean("class B"))))
        assertEquals(2, entryPointAnnotationCount(listOf(clean("@EntryPoint\ninterface A"), clean("@EntryPoint interface B"))))
        assertEquals(
            "a second @EntryPoint in one file is counted",
            2,
            entryPointAnnotationCount(listOf(clean("@EntryPoint interface A\n@EntryPoint interface B")))
        )
        assertEquals("import lines and other annotations are not counted", 0, entryPointAnnotationCount(listOf(clean("import dagger.hilt.EntryPoint\n@InstallIn(X::class)\n@EntryPoints"))))
    }

    @Test
    fun s21c_pin_selfCheck_theSeamAssignmentRuleCanFail() {
        val planted = listOf(
            "main/java/p/A.kt" to "fun f() { UnsupportedDatabaseActivity.exportAuthRequiredForTest = { _ -> true } }",
            "debug/java/p/B.kt" to "fun g() { UnsupportedDatabaseActivity.authenticatorFactoryForTest = null }",
            "release/java/p/C.kt" to "fun h() {\n    exportAuthRequiredForTest\n        = { _ -> false }\n}",
            "main/java/p/D.java" to "class D { void x() { UnsupportedDatabaseActivity.Companion.setAuthenticatorFactoryForTest(null); } }",
            "main/java/p/E.kt" to "fun k() { authenticatorFactoryForTest   =   null }",
            // the setter Kotlin generates for an internal property carries the module name after a dollar sign
            "main/java/p/F.java" to
                "class F { void x() { UnsupportedDatabaseActivity.Companion.setExportAuthRequiredForTest\$app_release(null); } }",
            "release/java/p/G.java" to
                "class G { void x() { UnsupportedDatabaseActivity.Companion.setAuthenticatorFactoryForTest\$app_debug(null); } }",
            // property references
            "main/java/p/H.kt" to "fun r() { val p = UnsupportedDatabaseActivity.Companion::exportAuthRequiredForTest }",
            "debug/java/p/I.kt" to "fun s() { val p = ::authenticatorFactoryForTest }"
        )
        val hits = seamAssignments(planted)
        assertEquals(
            listOf(
                "main/java/p/A.kt:1:exportAuthRequiredForTest",
                "debug/java/p/B.kt:1:authenticatorFactoryForTest",
                "release/java/p/C.kt:2:exportAuthRequiredForTest",
                "main/java/p/D.java:1:authenticatorFactoryForTest",
                "main/java/p/E.kt:1:authenticatorFactoryForTest",
                "main/java/p/F.java:1:exportAuthRequiredForTest",
                "release/java/p/G.java:1:authenticatorFactoryForTest",
                "main/java/p/H.kt:1:exportAuthRequiredForTest",
                "debug/java/p/I.kt:1:authenticatorFactoryForTest"
            ).sorted(),
            hits.sorted()
        )

        val allowed = listOf(
            "main/java/p/Decl.kt" to
                "@VisibleForTesting\ninternal var exportAuthRequiredForTest: (suspend (Context) -> Boolean)? = null\n" +
                "@VisibleForTesting\ninternal var authenticatorFactoryForTest: ((FragmentActivity) -> DeviceAuthenticator)? = null",
            "main/java/p/Read.kt" to
                "val testGate = exportAuthRequiredForTest\nval a = authenticatorFactoryForTest?.invoke(this)\n" +
                "val same = exportAuthRequiredForTest == null\nval other = (authenticatorFactoryForTest != null)",
            "main/java/p/Doc.kt" to "/** exportAuthRequiredForTest = null */\n// authenticatorFactoryForTest = x\nval s = \"exportAuthRequiredForTest = 1\""
        )
        assertEquals("declarations, reads, comparisons, comments and strings are allowed", emptyList<String>(), seamAssignments(allowed))
    }
}
