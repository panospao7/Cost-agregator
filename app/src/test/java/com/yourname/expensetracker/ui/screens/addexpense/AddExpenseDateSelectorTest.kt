package com.yourname.expensetracker.ui.screens.addexpense

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.R
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * manual-entry-currency slice B0' (RF-2b): [DateSelector] scopes its date-picker state to the dialog,
 * so every opening seeds from the CURRENT `dateMs`. A seed captured at first composition would go
 * stale after an external date change (quick-add Fill / reset) and OK would revert the date.
 *
 * Expected values are built independently from explicit local date-times in the pinned zone; all
 * instants are whole-second (zero milliseconds).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AddExpenseDateSelectorTest {

    private val composeRule = createComposeRule()

    // testReleaseUnitTest does not merge ui-test-manifest, so ComponentActivity must be registered
    // with Robolectric before the compose rule starts (same pattern as QuickAddSectionTest).
    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(
            object : ExternalResource() {
                override fun before() {
                    val context = ApplicationProvider.getApplicationContext<Application>()
                    shadowOf(context.packageManager).addActivityIfNotPresent(
                        ComponentName(context, ComponentActivity::class.java)
                    )
                }
            }
        )
        .around(composeRule)

    private val originalTimeZone: TimeZone = TimeZone.getDefault()

    private val selected = mutableListOf<Long>()

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    private fun pinZone(id: String): ZoneId {
        TimeZone.setDefault(TimeZone.getTimeZone(id))
        return ZoneId.of(id)
    }

    private fun instant(zone: ZoneId, year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, second, 0, zone).toInstant().toEpochMilli()

    private fun string(resId: Int): String =
        ApplicationProvider.getApplicationContext<Application>().getString(resId)

    private fun setContent(dateState: androidx.compose.runtime.MutableState<Long>) {
        composeRule.setContent {
            MaterialTheme {
                DateSelector(
                    dateMs = dateState.value,
                    onDateSelected = { selected += it }
                )
            }
        }
    }

    private fun openPicker() {
        composeRule.onNodeWithText(string(R.string.date_label)).performClick()
        composeRule.waitForIdle()
    }

    private fun confirm() {
        composeRule.onNodeWithText(string(R.string.ok_button)).performClick()
        composeRule.waitForIdle()
    }

    private fun cancel() {
        composeRule.onNodeWithText(string(R.string.cancel_button)).performClick()
        composeRule.waitForIdle()
    }

    private fun assertSingleSelection(zone: ZoneId, expectedMs: Long) {
        assertEquals("OK must invoke onDateSelected exactly once", 1, selected.size)
        assertEquals(expectedMs, selected.single())
        assertEquals(
            java.time.Instant.ofEpochMilli(expectedMs).atZone(zone).toLocalDate(),
            java.time.Instant.ofEpochMilli(selected.single()).atZone(zone).toLocalDate()
        )
    }

    // ---- initial OK-without-change is idempotent -------------------------------------------

    @Test
    fun initialOkWithoutChange_isIdempotent_newYork() {
        val zone = pinZone("America/New_York")
        // 23:30 local is already the next UTC day: a UTC-based day derivation would shift it.
        val start = instant(zone, 2026, 6, 15, 23, 30)
        setContent(mutableStateOf(start))

        openPicker()
        confirm()

        assertSingleSelection(zone, start)
        assertEquals(
            LocalDate.of(2026, 6, 15),
            java.time.Instant.ofEpochMilli(selected.single()).atZone(zone).toLocalDate()
        )
    }

    @Test
    fun initialOkWithoutChange_isIdempotent_auckland() {
        val zone = pinZone("Pacific/Auckland")
        // 00:30 local is still the previous UTC day.
        val start = instant(zone, 2026, 6, 15, 0, 30)
        setContent(mutableStateOf(start))

        openPicker()
        confirm()

        assertSingleSelection(zone, start)
        assertEquals(
            LocalDate.of(2026, 6, 15),
            java.time.Instant.ofEpochMilli(selected.single()).atZone(zone).toLocalDate()
        )
    }

    @Test
    fun initialOkWithoutChange_isIdempotent_tokyo() {
        val zone = pinZone("Asia/Tokyo")
        val start = instant(zone, 2026, 6, 15, 0, 15, 42)
        setContent(mutableStateOf(start))

        openPicker()
        confirm()

        assertSingleSelection(zone, start)
        assertEquals(
            LocalTime.of(0, 15, 42),
            java.time.Instant.ofEpochMilli(selected.single()).atZone(zone).toLocalTime()
        )
    }

    // ---- external dateMs change, then open + OK keeps the NEW date -------------------------

    @Test
    fun externalDateChange_thenOpenOk_keepsNewDate_newYork() {
        val zone = pinZone("America/New_York")
        val first = instant(zone, 2026, 6, 15, 23, 30)
        val second = instant(zone, 2026, 9, 2, 8, 5)
        val state = mutableStateOf(first)
        setContent(state)

        composeRule.runOnIdle { state.value = second }
        composeRule.waitForIdle()
        openPicker()
        confirm()

        assertSingleSelection(zone, second)
        assertEquals(
            LocalDate.of(2026, 9, 2),
            java.time.Instant.ofEpochMilli(selected.single()).atZone(zone).toLocalDate()
        )
    }

    @Test
    fun externalDateChange_thenOpenOk_keepsNewDate_auckland() {
        val zone = pinZone("Pacific/Auckland")
        val first = instant(zone, 2026, 6, 15, 0, 30)
        val second = instant(zone, 2026, 12, 31, 23, 45)
        val state = mutableStateOf(first)
        setContent(state)

        composeRule.runOnIdle { state.value = second }
        composeRule.waitForIdle()
        openPicker()
        confirm()

        assertSingleSelection(zone, second)
        assertEquals(
            LocalDate.of(2026, 12, 31),
            java.time.Instant.ofEpochMilli(selected.single()).atZone(zone).toLocalDate()
        )
    }

    // ---- reopening seeds from the current date ---------------------------------------------

    @Test
    fun reopenAfterCancelAndExternalChange_seedsFromCurrentDate_tokyo() {
        val zone = pinZone("Asia/Tokyo")
        val first = instant(zone, 2026, 6, 15, 0, 15)
        val second = instant(zone, 2026, 7, 20, 22, 10, 7)
        val state = mutableStateOf(first)
        setContent(state)

        openPicker()
        cancel()
        assertTrue("Cancel must not select", selected.isEmpty())

        composeRule.runOnIdle { state.value = second }
        composeRule.waitForIdle()
        openPicker()
        confirm()

        assertSingleSelection(zone, second)
        assertEquals(
            LocalDate.of(2026, 7, 20),
            java.time.Instant.ofEpochMilli(selected.single()).atZone(zone).toLocalDate()
        )
    }

    // ---- DST spring-forward day keeps the day ----------------------------------------------

    @Test
    fun dstSpringForwardDay_okKeepsDayAndTimeOfDay_newYork() {
        val zone = pinZone("America/New_York")
        // 2026-03-08: clocks jump 02:00 EST -> 03:00 EDT. 03:30 is the first valid half-hour after the gap.
        val afterGap = instant(zone, 2026, 3, 8, 3, 30)
        setContent(mutableStateOf(afterGap))

        openPicker()
        confirm()

        assertSingleSelection(zone, afterGap)
        val result = java.time.Instant.ofEpochMilli(selected.single()).atZone(zone)
        assertEquals(LocalDate.of(2026, 3, 8), result.toLocalDate())
        assertEquals(LocalTime.of(3, 30), result.toLocalTime())
    }

    @Test
    fun dstSpringForwardDay_beforeGap_okKeepsDayAndTimeOfDay_newYork() {
        val zone = pinZone("America/New_York")
        val beforeGap = instant(zone, 2026, 3, 8, 1, 30)
        setContent(mutableStateOf(beforeGap))

        openPicker()
        confirm()

        assertSingleSelection(zone, beforeGap)
        val result = java.time.Instant.ofEpochMilli(selected.single()).atZone(zone)
        assertEquals(LocalDate.of(2026, 3, 8), result.toLocalDate())
        assertEquals(LocalTime.of(1, 30), result.toLocalTime())
    }

    // ---- dismiss does not select -----------------------------------------------------------

    @Test
    fun cancel_doesNotInvokeOnDateSelected() {
        pinZone("America/New_York")
        val start = instant(ZoneId.of("America/New_York"), 2026, 6, 15, 12, 0)
        setContent(mutableStateOf(start))

        openPicker()
        cancel()

        assertTrue("Cancel must not invoke onDateSelected", selected.isEmpty())
    }
}
