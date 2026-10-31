package com.yourname.expensetracker.ui.screens.addexpense

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Process-wide handoff between the test body and [ZoneSessionHostActivity] (an Activity is created by the
 * framework, so it cannot take constructor arguments). The view model is held here, standing in for the
 * ViewModelStore retention that `hiltViewModel()` gives the production sheet across a configuration change.
 */
internal object ZoneSessionHost {
    // Plain (non-snapshot) fields only: no process-global Compose state may outlive the test class.
    // The observable show/hide state lives inside the activity instance (see ZoneSessionHostActivity).
    @Volatile
    var viewModel: AddExpenseViewModel? = null
    var dismissCalls = 0

    /** `isChangingConfigurations` as seen in each `onDestroy`, in order. */
    val destroyedWhileChangingConfigurations = mutableListOf<Boolean>()

    fun reset() {
        viewModel = null
        dismissCalls = 0
        destroyedWhileChangingConfigurations.clear()
    }
}

/** A minimal host that composes the REAL [AddExpenseSheet] with the supplied real view model. */
class ZoneSessionHostActivity : ComponentActivity() {
    /** Instance-scoped snapshot state: dies with the activity, never process-global. */
    internal val showSheet = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = checkNotNull(ZoneSessionHost.viewModel) { "test did not install a view model" }
        setContent {
            MaterialTheme {
                if (showSheet.value) {
                    AddExpenseSheet(
                        onDismiss = { ZoneSessionHost.dismissCalls++ },
                        viewModel = viewModel
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        ZoneSessionHost.destroyedWhileChangingConfigurations += isChangingConfigurations
        super.onDestroy()
    }
}

/**
 * manual-entry-currency B2 REQUIRED TEST (reviewer-strict ISSUE-1; domain AE-34 / F1): the sheet's entry-zone
 * wiring, driven through the REAL [AddExpenseSheet] and the REAL [AddExpenseViewModel].
 *
 *  - the zone captured when the sheet opens is kept across `ActivityScenario.recreate()` (a configuration
 *    change), and
 *  - it is cleared when the sheet leaves composition for any other reason (here: the host stops composing
 *    it without calling `reset()`), so the next session captures a fresh zone.
 *
 * Observable: the `entryZone` argument that `save()` hands to `addManualExpense`. The device default
 * `TimeZone` is changed between the capture and the save; the saved zone says which zone the view model held
 * (the captured one, or the fallback "device zone at save time" after the capture was cleared).
 * All zone ids are fixed IANA ids, so the expectations are independent of the machine running the test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AddExpenseSheetZoneSessionTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()
    private val harness = AddExpenseSheetHarness()
    private var scenario: ActivityScenario<ZoneSessionHostActivity>? = null

    private val newYork = ZoneId.of("America/New_York")
    private val tokyo = ZoneId.of("Asia/Tokyo")

    @Before
    fun setUp() {
        Locale.setDefault(Locale.US)
        ZoneSessionHost.reset()
        // testReleaseUnitTest does not merge a manifest: register the host with Robolectric.
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context.packageManager).addActivityIfNotPresent(
            ComponentName(context, ZoneSessionHostActivity::class.java)
        )
    }

    @After
    fun tearDown() {
        scenario?.close()
        scenario = null
        // After the activity (and its composition) is gone: cancel the view models' scopes so nothing keeps
        // running on the shared Robolectric main looper into the next test class.
        harness.close()
        TimeZone.setDefault(originalTimeZone)
        Locale.setDefault(originalLocale)
        ZoneSessionHost.reset()
    }

    private fun pinZone(id: String) = TimeZone.setDefault(TimeZone.getTimeZone(id))

    /** Pins [zoneId] as the device zone, then opens the sheet (which captures it). */
    private fun openSheetIn(zoneId: String): AddExpenseViewModel {
        pinZone(zoneId)
        val viewModel = harness.createViewModel()
        ZoneSessionHost.viewModel = viewModel
        scenario = ActivityScenario.launch(ZoneSessionHostActivity::class.java)
        composeRule.waitForIdle()
        composeRule.onNodeWithText(ApplicationProvider.getApplicationContext<Application>().getString(R.string.add_expense_title))
            .assertExists()
        return viewModel
    }

    /** Fills the minimum valid home-currency expense and taps Save through the view model. */
    private fun saveValidExpense(viewModel: AddExpenseViewModel) {
        composeRule.runOnIdle {
            viewModel.updateMerchant("Shop")
            viewModel.updateAmount("12.50")
            viewModel.save()
        }
        composeRule.waitForIdle()
    }

    private fun setSheetShown(shown: Boolean) {
        composeRule.waitForIdle()
        checkNotNull(scenario).onActivity { it.showSheet.value = shown }
        composeRule.waitForIdle()
    }

    private fun hideSheetWithoutReset() = setSheetShown(false)

    private fun showSheetAgain() = setSheetShown(true)

    // ---- control: the captured zone survives a device-zone change while the sheet stays open -------------

    @Test
    fun ae34_sheetStaysOpen_zoneChangeBeforeSave_saveUsesTheZoneCapturedAtOpen() {
        val viewModel = openSheetIn("America/New_York")

        pinZone("Asia/Tokyo")
        saveValidExpense(viewModel)

        assertEquals("exactly one save reached the repository", 1, harness.savedZones.size)
        assertEquals(newYork, harness.savedZones.single())
    }

    // ---- ISSUE-1 (a): leaving composition clears the zone ---------------------------------------------------

    @Test
    fun issue1_sheetLeavesComposition_clearsTheZone_nextSaveUsesTheNewDeviceZone() {
        val viewModel = openSheetIn("America/New_York")

        hideSheetWithoutReset()
        pinZone("Asia/Tokyo")
        saveValidExpense(viewModel)

        assertEquals("exactly one save reached the repository", 1, harness.savedZones.size)
        assertEquals(
            "a stale America/New_York here means onSheetLeft() was not called when the sheet left composition",
            tokyo,
            harness.savedZones.single()
        )
    }

    @Test
    fun issue1_leavingThenReopening_recapturesTheCurrentZone_notTheOldOne() {
        val viewModel = openSheetIn("America/New_York")

        hideSheetWithoutReset()
        pinZone("Asia/Tokyo")
        showSheetAgain()
        pinZone("Pacific/Auckland")
        saveValidExpense(viewModel)

        assertEquals("exactly one save reached the repository", 1, harness.savedZones.size)
        assertEquals(
            "the second session must hold the zone current when it opened, not the first session's",
            tokyo,
            harness.savedZones.single()
        )
    }

    // ---- ISSUE-1 (b): a configuration change keeps the zone ---------------------------------------------------

    @Test
    fun issue1_activityRecreate_keepsTheCapturedZone() {
        val viewModel = openSheetIn("America/New_York")

        pinZone("Asia/Tokyo") // the device zone changes while the activity is being recreated
        checkNotNull(scenario).recreate()
        composeRule.waitForIdle()

        // Harness precondition: Robolectric reported the destroy as a configuration change. If this fails the
        // harness (not the sheet) is at fault, because the sheet reads Activity.isChangingConfigurations.
        assertTrue(
            "recreate() was not reported as a configuration change: ${ZoneSessionHost.destroyedWhileChangingConfigurations}",
            ZoneSessionHost.destroyedWhileChangingConfigurations.isNotEmpty() &&
                ZoneSessionHost.destroyedWhileChangingConfigurations.first()
        )
        composeRule.onNodeWithText(
            ApplicationProvider.getApplicationContext<Application>().getString(R.string.add_expense_title)
        ).assertExists()

        saveValidExpense(viewModel)

        assertEquals("exactly one save reached the repository", 1, harness.savedZones.size)
        assertEquals(
            "recreation must not clear or re-capture the entry zone",
            newYork,
            harness.savedZones.single()
        )
    }
}
