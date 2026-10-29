package com.yourname.expensetracker.ui.applock

import android.app.Instrumentation
import android.app.KeyguardManager
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.yourname.expensetracker.data.privacy.applock.appLockPrivacyDataStore
import com.yourname.expensetracker.domain.privacy.applock.AppLockState
import com.yourname.expensetracker.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * Support for the G1b device tests (plan amendment 2026-10-29). Synthetic settings only; no real data.
 *
 * ## Why a process ledger
 * The app-lock controller is a process-wide singleton that resolves the settings store exactly once per process
 * (cold start). A test can seed the store and cold-start only if it is the FIRST MainActivity launch of the
 * instrumentation process. Request every test with its own runner filter (`Class#method`), which gives it its own
 * process. If a whole class is requested the first test claims the process; the tests that need a different cold
 * scenario are SKIPPED (visible as skipped, never as a false pass), so the runner must check skipped == 0.
 */
internal enum class G1bColdScenario { LOCK_ON, INERT, UNREADABLE_SECURE, UNREADABLE_OPEN, LOCK_OFF }

internal object G1bProcessLedger {
    private var claimed: G1bColdScenario? = null

    /**
     * Exclusive per process: the FIRST claim wins; any later claim in the same process (even for the same scenario)
     * skips, because the singleton controller and its DataStore are already cold-started and a re-seed would not be
     * honoured. Skips are visible, never a false pass.
     */
    @Synchronized
    fun claimOrSkip(scenario: G1bColdScenario) {
        val owner = claimed
        if (owner == null) {
            claimed = scenario
            return
        }
        assumeTrue(
            "G1b: this process already cold-started as $owner; request '$scenario' with its own Class#method filter",
            false
        )
    }
}

/** Settings seeding, device credential and shell helpers shared by the two G1b classes. */
internal class AppLockDeviceHarness {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val ctx: Context = instrumentation.targetContext
    val ui: UiAutomation get() = instrumentation.uiAutomation

    private var weSetPin = false

    /** True only when this harness set the PIN itself (so it knows it is [PIN]). */
    val harnessOwnsPin: Boolean get() = weSetPin

    // --- shell ----------------------------------------------------------------------------------

    fun shell(command: String): String {
        val pfd = ui.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
    }

    fun wakeAndDismissKeyguard() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
    }

    // --- device credential ------------------------------------------------------------------------

    private fun isDeviceSecure(): Boolean =
        ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure

    /**
     * Makes the device secure ([secure] true, PIN 1234) or non-secure. Returns whether the wanted state holds. A device
     * that already carries a credential this harness did not set cannot be changed (the PIN is unknown), so it returns
     * false for [secure] == false and true for [secure] == true.
     */
    fun ensureCredential(secure: Boolean): Boolean {
        if (secure) {
            if (!isDeviceSecure()) {
                assumeTrue("locksettings changes are only allowed on an emulator", isEmulator())
                shell("locksettings set-pin $PIN")
                weSetPin = true
                weSetPin = true
                waitFor(5_000) { isDeviceSecure() }
            }
            return isDeviceSecure()
        }
        if (isDeviceSecure()) {
            assumeTrue("locksettings changes are only allowed on an emulator", isEmulator())
            shell("locksettings clear --old $PIN")
            waitFor(5_000) { !isDeviceSecure() }
        }
        return !isDeviceSecure()
    }

    fun releaseCredential() {
        // weSetPin is only ever true after the emulator guard in ensureCredential passed.
        if (weSetPin && isDeviceSecure()) {
            shell("locksettings clear --old $PIN")
            waitFor(5_000) { !isDeviceSecure() }
        }
        weSetPin = false
    }

    private fun isEmulator(): Boolean {
        val id = "${Build.FINGERPRINT} ${Build.HARDWARE}".lowercase()
        return "generic" in id || "ranchu" in id || "emulator" in id
    }

    /** Types the harness PIN into the focused system credential view and presses ENTER. Only for [harnessOwnsPin]. */
    fun enterPinAndConfirm() {
        check(weSetPin) { "the harness did not set the PIN, so it does not know it" }
        shell("input text $PIN")
        shell("input keyevent 66")
    }

    // --- settings store (the app's own singleton DataStore, same process) -------------------------------

    private val lockEnabled = booleanPreferencesKey("app_lock_enabled")
    private val secureScreen = booleanPreferencesKey("secure_screen")
    private val timeout = stringPreferencesKey("auto_lock_timeout")
    private val marker = stringPreferencesKey("_app_lock_load_state")

    fun seedLock(enabled: Boolean, secureScreenSetting: Boolean = false) = runBlocking {
        ctx.appLockPrivacyDataStore.edit {
            it.clear()
            it[lockEnabled] = enabled
            it[secureScreen] = secureScreenSetting
            it[timeout] = "IMMEDIATE"
            it[marker] = "NORMAL"
        }
        Unit
    }

    /**
     * The CORRUPTED sentinel is what the store's corruption handler writes. Overwriting the file itself is not used:
     * the DataStore caches its content in the process, so a file overwrite is only honoured by a fresh process
     * (adb-scripted C step).
     */
    fun seedCorrupted() = runBlocking {
        ctx.appLockPrivacyDataStore.edit {
            it.clear()
            it[marker] = "CORRUPTED"
        }
        Unit
    }

    fun clearStore() = runBlocking {
        ctx.appLockPrivacyDataStore.edit { it.clear() }
        Unit
    }

    // --- window and state reads --------------------------------------------------------------------

    fun isSecureFlagSet(scenario: ActivityScenario<MainActivity>): Boolean {
        var set = false
        scenario.onActivity { set = (it.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0 }
        return set
    }

    fun controllerState(scenario: ActivityScenario<MainActivity>): AppLockState {
        lateinit var state: AppLockState
        scenario.onActivity { state = it.appLockController.state.value }
        return state
    }

    /**
     * Default home (launcher) package, resolved once. Null when it cannot be resolved.
     */
    private val homePackage: String? by lazy {
        val last = shell("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
            .lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
        last?.substringBefore('/', "")?.takeIf { it.isNotEmpty() }
    }

    /**
     * True when the resumed top activity belongs to neither our package nor the default home package (e.g. the API 30
     * system credential activity shown on top of MainActivity). False when our package or the launcher is on top,
     * nothing is resumed, the dump is unreadable, or the home package cannot be resolved (fail-closed for callers that
     * want to assert "system UI is on top"). Filtering happens here, not in the shell.
     */
    fun isSystemCredentialUiResumed(): Boolean {
        val home = homePackage ?: return false
        val line = shell("dumpsys activity activities").lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("mResumedActivity") || it.startsWith("topResumedActivity") }
            ?: return false
        return line.contains("ActivityRecord") && !line.contains(ctx.packageName) && !line.contains(home)
    }

    fun restoreRotation() {
        ui.setRotation(UiAutomation.ROTATION_UNFREEZE)
    }

    /** Brings the app's task back to the front the way the launcher does (no second instance is created). */
    fun returnToApp() {
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            ?: Intent(ctx, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
    }

    companion object {
        const val SDK_LOCK_CAPABLE: Int = Build.VERSION_CODES.R
        private const val PIN = "1234"
    }
}

/**
 * Root of the app's accessibility window. findAccessibilityNodeInfosByText does not work with Compose's node provider,
 * so callers walk the tree with [findNodesByExactText]. If the active window is not the app's (e.g. a system sheet),
 * the window whose root package is the app package is used.
 */
internal fun AppLockDeviceHarness.appAccessibilityRoot(): AccessibilityNodeInfo? {
    val pkg = ctx.packageName
    val active = ui.rootInActiveWindow
    if (active != null && active.packageName?.toString() == pkg) return active
    return ui.windows.asSequence()
        .mapNotNull { it.root }
        .firstOrNull { it.packageName?.toString() == pkg }
}

/** Manual recursive walk (childCount/getChild) collecting nodes whose text equals [expected] exactly. */
internal fun AppLockDeviceHarness.findNodesByExactText(expected: String): List<AccessibilityNodeInfo> {
    val result = mutableListOf<AccessibilityNodeInfo>()
    fun walk(node: AccessibilityNodeInfo?) {
        if (node == null) return
        if (node.text?.toString() == expected) result.add(node)
        for (i in 0 until node.childCount) walk(node.getChild(i))
    }
    walk(appAccessibilityRoot())
    return result
}

internal fun waitFor(timeoutMillis: Long, intervalMillis: Long = 100, condition: () -> Boolean): Boolean {
    val deadline = SystemClock.elapsedRealtime() + timeoutMillis
    while (SystemClock.elapsedRealtime() < deadline) {
        if (condition()) return true
        SystemClock.sleep(intervalMillis)
    }
    return condition()
}
