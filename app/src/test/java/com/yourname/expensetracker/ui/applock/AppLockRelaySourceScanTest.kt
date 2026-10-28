package com.yourname.expensetracker.ui.applock

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * S20-a, relay rule (e) (handoff S16 binding): feature-scoped source scan (not a registered guard).
 *
 * Scans every production Kotlin file, with comments and string bodies blanked:
 * - no worker, receiver or service file references `AppLockAuthenticatorRelay`, `DeviceAuthenticator`,
 *   `BiometricPromptDeviceAuthenticator` or `AppLockController`. A file counts as one of those when its name ends
 *   in `Worker.kt`, `Receiver.kt` or `Service.kt`, or when it sits in a `worker`, `workers`, `receiver`,
 *   `receivers`, `service` or `services` package directory;
 * - `AppLockAuthenticatorRelay` is referenced only by its own file, `di/AppLockModule.kt` (the one binding) and
 *   `ui/applock/AppLockPromptHost.kt` (the only caller of register and unregister).
 *
 * Two planted self-checks prove each rule can fail.
 */
class AppLockRelaySourceScanTest {

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
    private val relayFile = "$base/ui/applock/AppLockAuthenticatorRelay.kt"
    private val relayAllowlist = setOf(
        relayFile,
        "$base/di/AppLockModule.kt",
        "$base/ui/applock/AppLockPromptHost.kt"
    )

    private val backgroundDirectories = setOf("worker", "workers", "receiver", "receivers", "service", "services")
    private val backgroundSuffixes = listOf("Worker.kt", "Receiver.kt", "Service.kt")

    private val forbiddenInBackground = listOf(
        "AppLockAuthenticatorRelay",
        "BiometricPromptDeviceAuthenticator",
        "DeviceAuthenticator",
        "AppLockController"
    ).map { it to Regex("""\b${Regex.escape(it)}\b""") }

    private val relayReference = Regex("""\bAppLockAuthenticatorRelay\b""")

    /** True for a worker, receiver or service file by name or by package directory. */
    private fun isBackgroundEntry(relative: String): Boolean {
        val segments = relative.split('/')
        val directories = segments.dropLast(1)
        return backgroundSuffixes.any { segments.last().endsWith(it) } || directories.any { it in backgroundDirectories }
    }

    private fun backgroundViolations(relative: String, sanitized: String): List<String> {
        if (!isBackgroundEntry(relative)) return emptyList()
        return forbiddenInBackground.filter { (_, pattern) -> pattern.containsMatchIn(sanitized) }
            .map { (name, _) -> "$relative references $name" }
    }

    private fun relayReferenceViolation(relative: String, sanitized: String): String? =
        if (relative !in relayAllowlist && relayReference.containsMatchIn(sanitized)) {
            "$relative references AppLockAuthenticatorRelay outside the allowlist"
        } else {
            null
        }

    private fun productionFiles(): List<Pair<String, String>> =
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { file ->
                file.relativeTo(sourceRoot).invariantSeparatorsPath to
                    SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
            }
            .toList()

    @Test
    fun ruleE_noWorkerReceiverOrServiceFileReferencesTheRelayTheAuthenticatorOrTheController() {
        val files = productionFiles()
        assertTrue("scan found suspiciously few files: ${files.size}", files.size > 100)
        val background = files.filter { isBackgroundEntry(it.first) }
        assertTrue("scan matched suspiciously few background files: ${background.size}", background.size >= 10)
        assertTrue(
            "the known worker, receiver and service files must be matched",
            background.map { it.first }.containsAll(
                listOf(
                    "$base/data/privacy/DataRetentionWorker.kt",
                    "$base/receiver/BootReceiver.kt",
                    "$base/service/NotificationCaptureService.kt",
                    "$base/service/reminder/BillReminderWorker.kt",
                    "$base/worker/NotificationIntakeWorker.kt"
                )
            )
        )

        val found = files.flatMap { (relative, text) -> backgroundViolations(relative, text) }

        assertEquals("rule (e) violations", emptyList<String>(), found)
    }

    @Test
    fun ruleE_theRelayIsReferencedOnlyByItsOwnFileTheModuleAndThePromptHost() {
        val files = productionFiles()
        val referencing = files.filter { (_, text) -> relayReference.containsMatchIn(text) }.map { it.first }.toSet()

        assertEquals("files that reference AppLockAuthenticatorRelay", relayAllowlist, referencing)
        val violations = files.mapNotNull { (relative, text) -> relayReferenceViolation(relative, text) }
        assertEquals(emptyList<String>(), violations)
    }

    /**
     * True when the sanitized source declares `AppLockAuthenticatorRelay` directly under a class-level `@Singleton`.
     * The prompt host and the controller share ONE relay only because of this scope (an unscoped relay would give
     * each consumer its own instance and the controller's attempts would never reach the host's adapter).
     */
    private fun declaresSingletonRelayClass(sanitized: String): Boolean =
        Regex("""@Singleton\s+(?:(?:public|internal)\s+)?class\s+AppLockAuthenticatorRelay\b""").containsMatchIn(sanitized)

    @Test
    fun scope_theRelayClassIsDeclaredSingleton_soTheHostAndTheControllerShareOneInstance() {
        val file = File(sourceRoot, relayFile)
        assertTrue("relay source not found: ${file.path}", file.isFile)
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())

        assertTrue("AppLockAuthenticatorRelay must carry a class-level @Singleton", declaresSingletonRelayClass(sanitized))
    }

    @Test
    fun selfCheck_singletonPin_detectsAMissingOrMisplacedScope() {
        fun sanitize(source: String) = SourceTextSanitizer.stripCommentsAndStringBodies(source.trimIndent())

        assertTrue(
            declaresSingletonRelayClass(
                sanitize(
                    """
                    @Singleton
                    class AppLockAuthenticatorRelay @Inject constructor() : DeviceAuthenticator
                    """
                )
            )
        )
        assertFalse(
            "no scope at all",
            declaresSingletonRelayClass(sanitize("class AppLockAuthenticatorRelay @Inject constructor() : DeviceAuthenticator"))
        )
        assertFalse(
            "the scope on another class",
            declaresSingletonRelayClass(
                sanitize(
                    """
                    @Singleton
                    class Other
                    class AppLockAuthenticatorRelay @Inject constructor() : DeviceAuthenticator
                    """
                )
            )
        )
        assertFalse(
            "the scope only in a comment",
            declaresSingletonRelayClass(
                sanitize(
                    """
                    // @Singleton
                    /** @Singleton */
                    class AppLockAuthenticatorRelay @Inject constructor() : DeviceAuthenticator
                    """
                )
            )
        )
    }

    @Test
    fun selfCheck_plantedBackgroundReferences_areDetected_andOtherFilesAreNotScanned() {
        val planted = """
            package planted
            import com.yourname.expensetracker.ui.applock.AppLockAuthenticatorRelay
            import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthenticator
            import com.yourname.expensetracker.ui.applock.BiometricPromptDeviceAuthenticator
            import com.yourname.expensetracker.domain.privacy.applock.AppLockController
            class Planted(
                val a: AppLockAuthenticatorRelay,
                val b: DeviceAuthenticator,
                val c: BiometricPromptDeviceAuthenticator,
                val d: AppLockController
            )
        """.trimIndent()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(planted)

        for (path in listOf(
            "$base/data/PlantedWorker.kt",
            "$base/receiver/Other.kt",
            "$base/x/PlantedReceiver.kt",
            "$base/x/PlantedService.kt",
            "$base/workers/Other.kt",
            "$base/services/Other.kt"
        )) {
            assertTrue("$path must count as a background file", isBackgroundEntry(path))
            val found = backgroundViolations(path, sanitized)
            for (name in listOf("AppLockAuthenticatorRelay", "DeviceAuthenticator", "BiometricPromptDeviceAuthenticator", "AppLockController")) {
                assertTrue("$path missed $name in $found", found.any { it.endsWith("references $name") })
            }
        }
        assertFalse(isBackgroundEntry("$base/ui/applock/AppLockPromptHost.kt"))
        assertFalse(isBackgroundEntry("$base/di/AppLockModule.kt"))
        assertEquals(emptyList<String>(), backgroundViolations("$base/ui/applock/AppLockPromptHost.kt", sanitized))
    }

    @Test
    fun selfCheck_plantedRelayReference_outsideTheAllowlist_isDetected_andComments_areNot() {
        val planted = """
            package planted
            import com.yourname.expensetracker.ui.applock.AppLockAuthenticatorRelay
            class Planted(val relay: AppLockAuthenticatorRelay)
        """.trimIndent()
        val onlyMentioned = """
            package planted
            /** AppLockAuthenticatorRelay, DeviceAuthenticator and AppLockController are only mentioned here. */
            class Mentioned {
                // AppLockAuthenticatorRelay
                val s = "AppLockAuthenticatorRelay DeviceAuthenticator AppLockController"
            }
        """.trimIndent()

        val outside = relayReferenceViolation("$base/data/Planted.kt", SourceTextSanitizer.stripCommentsAndStringBodies(planted))
        val inside = relayReferenceViolation("$base/ui/applock/AppLockPromptHost.kt", SourceTextSanitizer.stripCommentsAndStringBodies(planted))
        val mentioned = SourceTextSanitizer.stripCommentsAndStringBodies(onlyMentioned)

        assertTrue("outside the allowlist must be flagged: $outside", outside != null)
        assertEquals(null, inside)
        assertEquals(null, relayReferenceViolation("$base/data/Mentioned.kt", mentioned))
        assertEquals(emptyList<String>(), backgroundViolations("$base/data/MentionedWorker.kt", mentioned))
    }
}
