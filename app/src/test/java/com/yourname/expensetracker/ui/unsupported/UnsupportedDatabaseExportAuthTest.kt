package com.yourname.expensetracker.ui.unsupported

import android.content.Context
import com.yourname.expensetracker.architecture.SourceTextSanitizer
import com.yourname.expensetracker.data.privacy.applock.AndroidDeviceAuthCapabilityProvider
import com.yourname.expensetracker.data.privacy.applock.AppLockSettingsRepositoryImpl
import com.yourname.expensetracker.domain.privacy.applock.AppLockPrivacySettings
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsLoadState
import com.yourname.expensetracker.domain.privacy.applock.AppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.DeviceAuthCapability
import com.yourname.expensetracker.domain.privacy.applock.FakeAppLockSettingsRepository
import com.yourname.expensetracker.domain.privacy.applock.FakeCapabilityProvider
import com.yourname.expensetracker.domain.privacy.applock.SettingsUnreadableCause
import java.io.File
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * App lock S21-c (#10, owner decision 73a): the pure decision "must the user pass device authentication before the
 * unsupported-database export?" over fake ports. No Android, no Hilt, no real store.
 *
 * Rule under test (explicit tables below, not derived from the production `when`):
 * - lock OFF (Loaded or FirstRunDefault): never required, and the capability is not even read;
 * - lock ON, or the store is unreadable (any cause), or the read threw, or timed out at 2000 ms, or no repository
 *   could be resolved: required when the capability is AVAILABLE or CHECK_FAILED (or no answer), not required for
 *   UNSUPPORTED_API, NO_CREDENTIAL, HW_UNAVAILABLE and UNKNOWN (nothing can be asked on those devices).
 *
 * Virtual time only (runTest); nothing sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UnsupportedDatabaseExportAuthTest {

    private val gatedCapabilities = setOf(DeviceAuthCapability.AVAILABLE, DeviceAuthCapability.CHECK_FAILED)
    private val ungatedCapabilities = setOf(
        DeviceAuthCapability.UNSUPPORTED_API,
        DeviceAuthCapability.NO_CREDENTIAL,
        DeviceAuthCapability.HW_UNAVAILABLE,
        DeviceAuthCapability.UNKNOWN
    )

    private fun loaded(lockOn: Boolean) =
        AppLockSettingsLoadState.Loaded(AppLockPrivacySettings(appLockEnabled = lockOn))

    private fun firstRun(lockOn: Boolean) =
        AppLockSettingsLoadState.FirstRunDefault(AppLockPrivacySettings(appLockEnabled = lockOn))

    private fun unreadable(cause: SettingsUnreadableCause) = AppLockSettingsLoadState.Unreadable(cause)

    private fun repo(state: AppLockSettingsLoadState) = FakeAppLockSettingsRepository(state)

    private fun capability(value: DeviceAuthCapability) = FakeCapabilityProvider(value)

    /** A repository whose read completes only after [delayMillis] of virtual time. */
    private class DelayedRepository(
        private val inner: FakeAppLockSettingsRepository,
        private val delayMillis: Long
    ) : AppLockSettingsRepository by inner {
        override suspend fun getLoadState(): AppLockSettingsLoadState {
            delay(delayMillis)
            return inner.getLoadState()
        }
    }

    // ---- the capability partition is complete ---------------------------------------------------

    @Test
    fun s21c_everyCapabilityIsDecidedExactlyOnce() {
        assertEquals(DeviceAuthCapability.entries.toSet(), gatedCapabilities + ungatedCapabilities)
        assertTrue(gatedCapabilities.intersect(ungatedCapabilities).isEmpty())
    }

    @Test
    fun s21c_capabilityRule_perValue() {
        for (value in gatedCapabilities) {
            assertTrue("$value", UnsupportedDatabaseExportAuth.capabilityRequiresAuth(value))
        }
        for (value in ungatedCapabilities) {
            assertFalse("$value", UnsupportedDatabaseExportAuth.capabilityRequiresAuth(value))
        }
        assertTrue("no answer is treated as failed", UnsupportedDatabaseExportAuth.capabilityRequiresAuth(null))
    }

    // ---- lock ON -------------------------------------------------------------------------------

    @Test
    fun s21c_lockOn_available_requiresAuth_control() = runTest {
        val provider = capability(DeviceAuthCapability.AVAILABLE)
        assertTrue(UnsupportedDatabaseExportAuth.decide(repo(loaded(true)), provider))
        assertEquals(1, provider.evaluations)
    }

    @Test
    fun s21c_lockOn_checkFailed_requiresAuth() = runTest {
        assertTrue(UnsupportedDatabaseExportAuth.decide(repo(loaded(true)), capability(DeviceAuthCapability.CHECK_FAILED)))
    }

    @Test
    fun s21c_lockOn_inertCapabilities_doNotRequireAuth() = runTest {
        for (value in ungatedCapabilities) {
            val provider = capability(value)
            assertFalse("lock on + $value", UnsupportedDatabaseExportAuth.decide(repo(loaded(true)), provider))
            assertEquals("the capability is read once for $value", 1, provider.evaluations)
        }
    }

    @Test
    fun s21c_firstRunDefaultWithLockFlagOn_isTreatedAsLockOn() = runTest {
        assertTrue(UnsupportedDatabaseExportAuth.decide(repo(firstRun(true)), capability(DeviceAuthCapability.AVAILABLE)))
    }

    // ---- lock OFF: never required, capability never read ----------------------------------------

    @Test
    fun s21c_lockOff_loaded_neverRequiresAuth_andNeverReadsTheCapability() = runTest {
        for (value in DeviceAuthCapability.entries) {
            val provider = capability(value)
            assertFalse("lock off + $value", UnsupportedDatabaseExportAuth.decide(repo(loaded(false)), provider))
            assertEquals("lock off must not read the capability ($value)", 0, provider.evaluations)
        }
    }

    @Test
    fun s21c_lockOff_firstRunDefault_neverRequiresAuth_andNeverReadsTheCapability() = runTest {
        for (value in DeviceAuthCapability.entries) {
            val provider = capability(value)
            assertFalse("first run + $value", UnsupportedDatabaseExportAuth.decide(repo(firstRun(false)), provider))
            assertEquals("first run must not read the capability ($value)", 0, provider.evaluations)
        }
    }

    @Test
    fun s21c_lockOff_evenWhenTheCapabilityProviderWouldThrow_doesNotRequireAuth() = runTest {
        val provider = capability(DeviceAuthCapability.AVAILABLE).apply { failure = IllegalStateException("never called") }
        assertFalse(UnsupportedDatabaseExportAuth.decide(repo(loaded(false)), provider))
        assertEquals(0, provider.evaluations)
    }

    // ---- UNREADABLE: every cause ----------------------------------------------------------------

    @Test
    fun s21c_unreadable_everyCause_isNeverReadAsOff() = runTest {
        for (cause in SettingsUnreadableCause.entries) {
            assertEquals(
                "cause $cause",
                ExportLockRead.UNREADABLE,
                UnsupportedDatabaseExportAuth.readLock(repo(unreadable(cause)), 2_000L)
            )
        }
        // The closed cause set is what the table above was written for; a new cause must be considered here.
        assertEquals(
            setOf("IO_ERROR", "CORRUPTED", "TIMEOUT", "INVALID_VALUE"),
            SettingsUnreadableCause.entries.map { it.name }.toSet()
        )
    }

    @Test
    fun s21c_unreadable_everyCause_withAvailable_requiresAuth() = runTest {
        for (cause in SettingsUnreadableCause.entries) {
            val provider = capability(DeviceAuthCapability.AVAILABLE)
            assertTrue("cause $cause", UnsupportedDatabaseExportAuth.decide(repo(unreadable(cause)), provider))
            assertEquals("the capability is read for $cause", 1, provider.evaluations)
        }
    }

    @Test
    fun s21c_unreadable_everyCause_withCheckFailed_requiresAuth() = runTest {
        for (cause in SettingsUnreadableCause.entries) {
            assertTrue(
                "cause $cause",
                UnsupportedDatabaseExportAuth.decide(repo(unreadable(cause)), capability(DeviceAuthCapability.CHECK_FAILED))
            )
        }
    }

    @Test
    fun s21c_unreadable_everyCause_withInertCapabilities_doesNotRequireAuth() = runTest {
        for (cause in SettingsUnreadableCause.entries) {
            for (value in ungatedCapabilities) {
                assertFalse(
                    "cause $cause + $value",
                    UnsupportedDatabaseExportAuth.decide(repo(unreadable(cause)), capability(value))
                )
            }
        }
    }

    // ---- timeout boundary (virtual time) ----------------------------------------------------------

    @Test
    fun s21c_theTimeoutConstantIs2000Millis() {
        assertEquals(2_000L, EXPORT_AUTH_READ_TIMEOUT_MILLIS)
    }

    @Test
    fun s21c_aReadThatNeverReturns_isUnreadableAtExactly2000Millis_andRequiresAuthWhenAvailable() = runTest {
        val store = repo(loaded(false)).apply { holdReads() }
        val provider = capability(DeviceAuthCapability.AVAILABLE)
        val start = testScheduler.currentTime

        val required = UnsupportedDatabaseExportAuth.decide(store, provider)

        assertTrue("a timed out read counts as unreadable, never as lock off", required)
        assertEquals("the gate waited exactly the bound", 2_000L, testScheduler.currentTime - start)
        assertEquals(1, store.readCount)
        assertEquals(1, provider.evaluations)
    }

    @Test
    fun s21c_aReadThatNeverReturns_readLockIsUnreadable() = runTest {
        val store = repo(loaded(false)).apply { holdReads() }
        assertEquals(ExportLockRead.UNREADABLE, UnsupportedDatabaseExportAuth.readLock(store, 2_000L))
    }

    @Test
    fun s21c_aReadThatNeverReturns_withNoCredential_doesNotRequireAuth() = runTest {
        val store = repo(loaded(true)).apply { holdReads() }
        assertFalse(UnsupportedDatabaseExportAuth.decide(store, capability(DeviceAuthCapability.NO_CREDENTIAL)))
    }

    @Test
    fun s21c_readJustUnderTheBound_returnsItsValue_lockOffIsNotGated() = runTest {
        val store = DelayedRepository(repo(loaded(false)), 1_999L)
        val provider = capability(DeviceAuthCapability.AVAILABLE)

        assertFalse("a read that finished in time keeps its value", UnsupportedDatabaseExportAuth.decide(store, provider))
        assertEquals(1_999L, testScheduler.currentTime)
        assertEquals("lock off still skips the capability", 0, provider.evaluations)
    }

    @Test
    fun s21c_readJustUnderTheBound_returnsItsValue_lockOnIsGated() = runTest {
        val store = DelayedRepository(repo(loaded(true)), 1_999L)
        assertTrue(UnsupportedDatabaseExportAuth.decide(store, capability(DeviceAuthCapability.AVAILABLE)))
        assertEquals(1_999L, testScheduler.currentTime)
    }

    @Test
    fun s21c_readJustOverTheBound_isUnreadable_evenThoughItWouldHaveSaidOff() = runTest {
        val store = DelayedRepository(repo(loaded(false)), 2_001L)
        val provider = capability(DeviceAuthCapability.AVAILABLE)

        assertTrue("a late OFF must not relax the gate", UnsupportedDatabaseExportAuth.decide(store, provider))
        assertEquals("the call returned at the bound, not when the late read would have finished", 2_000L, testScheduler.currentTime)
    }

    @Test
    fun s21c_aTimedOutReadDoesNotCancelTheCaller_theNextCallReadsFresh() = runTest {
        val slow = DelayedRepository(repo(loaded(false)), 5_000L)
        assertTrue(UnsupportedDatabaseExportAuth.decide(slow, capability(DeviceAuthCapability.AVAILABLE)))
        // The caller is alive and a later call with a fast store answers from that store.
        assertFalse(UnsupportedDatabaseExportAuth.decide(repo(loaded(false)), capability(DeviceAuthCapability.AVAILABLE)))
    }

    // ---- throws from the repository -----------------------------------------------------------------

    @Test
    fun s21c_aRepositoryThatThrows_isUnreadable_andRequiresAuthWhenAvailable() = runTest {
        val failures = listOf<Exception>(
            RuntimeException("SYNTHETIC-SECRET-1"),
            IllegalStateException("SYNTHETIC-SECRET-2"),
            IOException("/synthetic/path/secret")
        )
        for (failure in failures) {
            val store = repo(loaded(false)).apply { this.failure = failure }
            assertEquals(
                failure.javaClass.simpleName,
                ExportLockRead.UNREADABLE,
                UnsupportedDatabaseExportAuth.readLock(store, 2_000L)
            )
            val provider = capability(DeviceAuthCapability.AVAILABLE)
            assertTrue(failure.javaClass.simpleName, UnsupportedDatabaseExportAuth.decide(store, provider))
            assertEquals(1, provider.evaluations)
        }
    }

    @Test
    fun s21c_aRepositoryThatThrows_withInertCapability_doesNotRequireAuth() = runTest {
        val store = repo(loaded(true)).apply { failure = RuntimeException("SYNTHETIC") }
        assertFalse(UnsupportedDatabaseExportAuth.decide(store, capability(DeviceAuthCapability.UNSUPPORTED_API)))
    }

    // ---- throws from the capability provider ------------------------------------------------------------

    @Test
    fun s21c_aProviderThatThrows_isNoAnswer_andRequiresAuthWhenTheLockIsOn() = runTest {
        val provider = capability(DeviceAuthCapability.NO_CREDENTIAL).apply { failure = RuntimeException("SYNTHETIC-SECRET") }
        assertNull(UnsupportedDatabaseExportAuth.readCapability(provider))
        assertTrue(UnsupportedDatabaseExportAuth.decide(repo(loaded(true)), provider))
    }

    @Test
    fun s21c_aProviderThatThrows_requiresAuthWhenTheStoreIsUnreadable() = runTest {
        val provider = capability(DeviceAuthCapability.AVAILABLE).apply { failure = IllegalStateException("SYNTHETIC") }
        assertTrue(UnsupportedDatabaseExportAuth.decide(repo(unreadable(SettingsUnreadableCause.IO_ERROR)), provider))
    }

    @Test
    fun s21c_readCapability_returnsTheValueAndEvaluatesEveryCall() {
        val provider = capability(DeviceAuthCapability.AVAILABLE)
        assertEquals(DeviceAuthCapability.AVAILABLE, UnsupportedDatabaseExportAuth.readCapability(provider))
        provider.capability = DeviceAuthCapability.NO_CREDENTIAL
        assertEquals(DeviceAuthCapability.NO_CREDENTIAL, UnsupportedDatabaseExportAuth.readCapability(provider))
        assertEquals("never cached", 2, provider.evaluations)
    }

    // ---- stale snapshots: nothing is cached across calls ------------------------------------------------

    @Test
    fun s21c_everyCallReadsTheLockFresh() = runTest {
        val store = repo(loaded(true))
        val provider = capability(DeviceAuthCapability.AVAILABLE)

        assertTrue(UnsupportedDatabaseExportAuth.decide(store, provider))
        store.loadState = loaded(false)
        assertFalse("the second call sees the committed change", UnsupportedDatabaseExportAuth.decide(store, provider))
        store.loadState = unreadable(SettingsUnreadableCause.CORRUPTED)
        assertTrue(UnsupportedDatabaseExportAuth.decide(store, provider))

        assertEquals(3, store.readCount)
    }

    @Test
    fun s21c_everyCallEvaluatesTheCapabilityFresh() = runTest {
        val store = repo(loaded(true))
        val provider = capability(DeviceAuthCapability.AVAILABLE)

        assertTrue(UnsupportedDatabaseExportAuth.decide(store, provider))
        provider.capability = DeviceAuthCapability.NO_CREDENTIAL
        assertFalse(UnsupportedDatabaseExportAuth.decide(store, provider))
        assertEquals(2, provider.evaluations)
    }

    // ---- cancellation ---------------------------------------------------------------------------------------

    @Test
    fun s21c_aCancellationExceptionFromTheRepository_isRethrown_notSwallowed() = runTest {
        val store = repo(loaded(true)).apply { failure = CancellationException("synthetic") }
        val provider = capability(DeviceAuthCapability.AVAILABLE)

        assertFailsWith<CancellationException> { UnsupportedDatabaseExportAuth.decide(store, provider) }
        assertFailsWith<CancellationException> { UnsupportedDatabaseExportAuth.readLock(store, 2_000L) }
        assertEquals("a cancelled read never reaches the capability", 0, provider.evaluations)
    }

    @Test
    fun s21c_aCancellationExceptionFromTheProvider_isRethrown_notSwallowed() = runTest {
        val provider = capability(DeviceAuthCapability.AVAILABLE).apply { failure = CancellationException("synthetic") }

        assertFailsWith<CancellationException> { UnsupportedDatabaseExportAuth.decide(repo(loaded(true)), provider) }
        assertFailsWith<CancellationException> { UnsupportedDatabaseExportAuth.readCapability(provider) }
    }

    @Test
    fun s21c_cancellingTheCallerDuringTheRead_propagates_andNothingIsDecided() = runTest {
        val store = repo(loaded(true)).apply { holdReads() }
        val provider = capability(DeviceAuthCapability.AVAILABLE)
        var result: Boolean? = null
        val job = launch { result = UnsupportedDatabaseExportAuth.decide(store, provider) }

        runCurrent()
        assertEquals("the read started", 1, store.readCount)
        advanceTimeBy(500L)
        runCurrent()
        assertTrue("still waiting inside the bound", job.isActive)

        job.cancelAndJoin()

        assertTrue("the caller was cancelled, not completed with a fail-closed answer", job.isCancelled)
        assertNull(result)
        advanceTimeBy(10_000L)
        runCurrent()
        assertNull("the timeout did not resurrect a result", result)
        assertEquals("the capability was never read", 0, provider.evaluations)
    }

    // ---- null ports (entry point could not be resolved) --------------------------------------------------------

    @Test
    fun s21c_nullPorts_requireAuth() = runTest {
        assertTrue(UnsupportedDatabaseExportAuth.decide(null, null))
    }

    @Test
    fun s21c_nullRepository_isUnreadable_andFollowsTheCapability() = runTest {
        assertEquals(ExportLockRead.UNREADABLE, UnsupportedDatabaseExportAuth.readLock(null, 2_000L))
        assertTrue(UnsupportedDatabaseExportAuth.decide(null, capability(DeviceAuthCapability.AVAILABLE)))
        assertTrue(UnsupportedDatabaseExportAuth.decide(null, capability(DeviceAuthCapability.CHECK_FAILED)))
        assertFalse(UnsupportedDatabaseExportAuth.decide(null, capability(DeviceAuthCapability.NO_CREDENTIAL)))
    }

    @Test
    fun s21c_nullProvider_isNoAnswer() = runTest {
        assertNull(UnsupportedDatabaseExportAuth.readCapability(null))
        assertTrue(UnsupportedDatabaseExportAuth.decide(repo(loaded(true)), null))
        assertTrue(UnsupportedDatabaseExportAuth.decide(repo(unreadable(SettingsUnreadableCause.TIMEOUT)), null))
        assertFalse("lock off needs no capability", UnsupportedDatabaseExportAuth.decide(repo(loaded(false)), null))
    }

    // ---- source scan of the new file ------------------------------------------------------------------------------

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

    private val authFile = "com/yourname/expensetracker/ui/unsupported/UnsupportedDatabaseExportAuth.kt"

    private fun sanitizedAuthSource(): String {
        val file = File(sourceRoot, authFile)
        assertTrue("missing $authFile", file.isFile)
        return SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    private val forbiddenTokens = listOf(
        "fun interface" to Regex("""\bfun\s+interface\b"""),
        "@Composable" to Regex("""@Composable\b"""),
        "Dao" to Regex("""Dao"""),
        "AppDatabase" to Regex("""AppDatabase"""),
        "Room" to Regex("""\bRoom\b"""),
        "logging" to Regex("""\b(Log|Timber)\s*\.|\bprintln\s*\(|printStackTrace"""),
        "exception text" to Regex("""\.(message|localizedMessage|stackTrace)\b|\.toString\s*\("""),
        "diagnostics" to Regex("""PrivacyDiagnostic|Diagnostic"""),
        "settings writes" to Regex("""\b(reviewSave|setAppLockEnabled|setAutoLockTimeout\w*|setDiscreetNotifications\w*|setSecureScreen\w*)\s*\("""),
        "controller or prompt" to Regex("""\b(AppLockController|DeviceAuthenticator|BiometricPrompt\w*)\b""")
    )

    private fun violations(sanitized: String): List<String> =
        forbiddenTokens.filter { (_, pattern) -> pattern.containsMatchIn(sanitized) }.map { it.first }

    @Test
    fun s21c_sourceScan_newFileHasNoFunInterfaceComposableDaoAppDatabaseLoggingOrWrites() {
        assertEquals(emptyList<String>(), violations(sanitizedAuthSource()))
    }

    @Test
    fun s21c_sourceScan_exactlyOneEntryPointLookup() {
        val sanitized = sanitizedAuthSource()
        assertEquals(1, Regex("""EntryPointAccessors\s*\.\s*fromApplication\s*\(""").findAll(sanitized).count())
        assertEquals(0, Regex("""EntryPoints\s*\.\s*get\s*\(""").findAll(sanitized).count())
        assertEquals(0, Regex("""\bfromActivity\s*\(|\bfromFragment\s*\(|\bfromView\s*\(""").findAll(sanitized).count())
    }

    @Test
    fun s21c_sourceScan_entryPointIsSingletonScopedWithExactlyTwoReadMembers() {
        val sanitized = sanitizedAuthSource()
        val declaration = Regex("""interface\s+UnsupportedDatabaseExportAuthEntryPoint\s*\{""").find(sanitized)
        assertTrue("entry point interface missing", declaration != null)
        val open = declaration!!.range.last
        var depth = 0
        var close = -1
        for (i in open until sanitized.length) {
            when (sanitized[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        close = i
                        break
                    }
                }
            }
        }
        assertTrue("entry point interface is not closed", close > open)
        val members = Regex("""\bfun\s+(\w+)\s*\(""").findAll(sanitized.substring(open, close)).map { it.groupValues[1] }.toList()
        assertEquals(listOf("appLockSettingsRepository", "deviceAuthCapabilityProvider"), members)

        val header = sanitized.substring(0, declaration.range.first)
        assertTrue(header.contains("@EntryPoint"))
        assertTrue(Regex("""@InstallIn\s*\(\s*SingletonComponent::class\s*\)""").containsMatchIn(header))
    }

    // ---- the entry point graph stays Room-free (F7) ------------------------------------------------------------------
    //
    // The unsupported-database screen resolves the two bindings of the entry point while the live database cannot be
    // opened. Hilt builds each binding through its @Inject constructor, so a new constructor parameter (a DAO, the
    // database, anything Room-backed) would pull Room into a graph that must not touch it. Both constructors must take
    // exactly one parameter, the application Context.

    private fun injectConstructorProblems(
        type: Class<*>,
        marker: Class<out Annotation> = Inject::class.java
    ): List<String> {
        val injected = type.declaredConstructors.filter { it.isAnnotationPresent(marker) }
        if (injected.size != 1) {
            return listOf("${type.simpleName}: expected exactly one @Inject constructor, found ${injected.size}")
        }
        val parameters = injected.single().parameterTypes.toList()
        return if (parameters == listOf<Class<*>>(Context::class.java)) {
            emptyList()
        } else {
            listOf("${type.simpleName}: @Inject constructor parameters were ${parameters.map { it.name }}")
        }
    }

    // Test-local marker standing in for @Inject on the planted classes. Hilt/Dagger KSP also processes test sources and
    // rejects a real @Inject on a private class, so the planted cases must stay Dagger-free.
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.CONSTRUCTOR)
    private annotation class PlantedInject

    private class PlantedOk @PlantedInject constructor(@Suppress("UNUSED_PARAMETER") context: Context)
    private class PlantedTwoParameters @PlantedInject constructor(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") extra: Any
    )
    private class PlantedWrongType @PlantedInject constructor(@Suppress("UNUSED_PARAMETER") extra: Any)
    private class PlantedNoParameters @PlantedInject constructor()
    private class PlantedNotInjected(@Suppress("UNUSED_PARAMETER") context: Context)

    @Test
    fun s21c_entryPointGraph_theLockRepositoryInjectConstructorTakesOnlyTheContext() {
        assertEquals(emptyList<String>(), injectConstructorProblems(AppLockSettingsRepositoryImpl::class.java))
    }

    @Test
    fun s21c_entryPointGraph_theCapabilityProviderInjectConstructorTakesOnlyTheContext() {
        assertEquals(emptyList<String>(), injectConstructorProblems(AndroidDeviceAuthCapabilityProvider::class.java))
    }

    @Test
    fun s21c_entryPointGraph_selfCheck_theConstructorRuleCanFail() {
        assertEquals(
            "the exact shape passes",
            emptyList<String>(),
            injectConstructorProblems(PlantedOk::class.java, PlantedInject::class.java)
        )
        for (planted in listOf(
            PlantedTwoParameters::class.java,
            PlantedWrongType::class.java,
            PlantedNoParameters::class.java,
            PlantedNotInjected::class.java
        )) {
            assertTrue(
                "${planted.simpleName} was not detected",
                injectConstructorProblems(planted, PlantedInject::class.java).isNotEmpty()
            )
        }
    }

    @Test
    fun s21c_sourceScan_selfCheck_eachRuleCanFail() {
        val planted = SourceTextSanitizer.stripCommentsAndStringBodies(
            """
            package planted
            fun interface Hook { fun run() }
            @Composable fun Ui() {}
            class X(val dao: ExpenseDao, val db: AppDatabase) {
                fun f(e: Exception) {
                    Log.w("t", e.message)
                    println(e.toString())
                    repo.setAppLockEnabled(false)
                    PrivacyDiagnosticsEmitter()
                    Room.databaseBuilder()
                    AppLockController()
                }
            }
            """.trimIndent()
        )
        val found = violations(planted)
        for (rule in forbiddenTokens.map { it.first }) {
            assertTrue("rule '$rule' did not detect the planted violation: $found", rule in found)
        }
        assertEquals(
            "comments and strings are not violations",
            emptyList<String>(),
            violations(
                SourceTextSanitizer.stripCommentsAndStringBodies(
                    "/** fun interface Dao AppDatabase Log.w */ val s = \"@Composable e.message\""
                )
            )
        )
    }
}
