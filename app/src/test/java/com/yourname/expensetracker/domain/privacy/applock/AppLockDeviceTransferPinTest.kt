package com.yourname.expensetracker.domain.privacy.applock

import com.yourname.expensetracker.architecture.SourceTextSanitizer
import com.yourname.expensetracker.domain.privacy.applock.pin.AppPinStoreState
import com.yourname.expensetracker.domain.privacy.applock.pin.PinVerifierState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Option A (owner-approvals item 96, D-I2-9): after a device transfer the settings (which are backed up with the
 * device data) may say APP_PIN while the PIN verifier, kept in `noBackupFilesDir`, is gone. That state must resolve to
 * device-auth, never to a hard lock nobody can open (INV-40, AMD-4).
 */
class AppLockDeviceTransferPinTest {

    private val appPinSettings = AppLockPrivacySettings(
        appLockEnabled = true,
        autoLockTimeout = AutoLockTimeout.IMMEDIATE,
        lockMode = LockMode.APP_PIN
    )

    private fun harness(verifier: PinVerifierState) = AppLockControllerHarness(
        AppLockSettingsLoadState.Loaded(appPinSettings),
        pinStore = FakeAppPinStore(AppPinStoreState(verifier, false, false))
    )

    @Test
    fun `Option A an ABSENT verifier with stored APP_PIN and the lock on resolves to DEVICE_AUTH, locked but unlockable`() {
        val h = harness(PinVerifierState.ABSENT)
        val controller = h.resolvedController()
        assertEquals(EffectiveLockMode.DEVICE_AUTH, controller.pin.value.effectiveLockMode)
        assertEquals(AppLockState.Locked(LockCause.COLD_START), controller.state.value)
        h.unlock(controller) // the device prompt works: not a hard lock
        assertEquals(AppLockState.Unlocked, controller.state.value)
        assertTrue(h.pinStore.calls.none { it.op == "verify" })
    }

    @Test
    fun `Option A control a PRESENT verifier with the same settings is effective APP_PIN and has no device prompt`() {
        val h = harness(PinVerifierState.PRESENT)
        val controller = h.resolvedController()
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
        assertTrue(h.authenticator.calls.isEmpty())
    }

    @Test
    fun `Option A boundary an UNREADABLE verifier stays effective APP_PIN and fails closed`() {
        val h = harness(PinVerifierState.UNREADABLE)
        val controller = h.resolvedController()
        assertEquals(EffectiveLockMode.APP_PIN, controller.pin.value.effectiveLockMode)
        assertEquals(UnlockTapResult.NotLocked, controller.onUnlockTapped())
        assertFalse(controller.state.value is AppLockState.Unlocked)
    }

    // --- Source pin: which store lives where ---------------------------------------------------------------------

    private val sourceRoot: File by lazy {
        listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        ).firstOrNull { it.isDirectory } ?: error("Could not locate production source root")
    }

    private fun code(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("missing production file $relative", file.isFile)
        return SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
    }

    /** Text of the function that provides the PIN store, from its name to the next provider annotation or end. */
    private fun pinProviderBody(text: String): String {
        val start = text.indexOf("fun provideAppPinDataStore")
        if (start < 0) return ""
        val end = text.indexOf("@Provides", start).let { if (it < 0) text.length else it }
        return text.substring(start, end)
    }

    @Test
    fun `Option A the PIN store file is built from noBackupFilesDir in AppLockModule`() {
        val module = code("com/yourname/expensetracker/di/AppLockModule.kt")
        assertTrue(pinProviderBody(module).contains("noBackupFilesDir"))
        assertEquals(1, Regex("""\bnoBackupFilesDir\b""").findAll(module).count())
    }

    @Test
    fun `Option A the app-lock settings DataStore is not built from noBackupFilesDir`() {
        val repository = code("com/yourname/expensetracker/data/privacy/applock/AppLockSettingsRepositoryImpl.kt")
        assertTrue(repository.contains("preferencesDataStore("))
        assertFalse(repository.contains("noBackupFilesDir"))
    }

    @Test
    fun `Option A self-check a settings store moved to noBackupFilesDir would be caught`() {
        val planted = "fun provideAppPinDataStore() = File(context.filesDir, N)\n@Provides fun other() = context.noBackupFilesDir"
        assertFalse(pinProviderBody(SourceTextSanitizer.stripCommentsAndStringBodies(planted)).contains("noBackupFilesDir"))
        val movedSettings = SourceTextSanitizer.stripCommentsAndStringBodies("val s = File(context.noBackupFilesDir, \"x\")")
        assertTrue(movedSettings.contains("noBackupFilesDir"))
    }
}
