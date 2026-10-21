package com.yourname.expensetracker.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * R20c-1: Material3 DatePicker values are UTC-midnight millis of the picked calendar day.
 * These conversions must yield the same calendar day in every zone (west and east of UTC).
 */
class UiTimeUtilsTest {

    private val newYork = ZoneId.of("America/New_York")
    private val athens = ZoneId.of("Europe/Athens")
    private val kiritimati = ZoneId.of("Pacific/Kiritimati")
    private val zones = listOf(newYork, athens, kiritimati, ZoneId.of("UTC"))

    private val mar15 = LocalDate.of(2025, 3, 15)
    private val mar15PickerMillis = Instant.parse("2025-03-15T00:00:00Z").toEpochMilli()

    private fun millis(local: LocalDateTime, zone: ZoneId): Long =
        local.atZone(zone).toInstant().toEpochMilli()

    private fun localOf(epochMillis: Long, zone: ZoneId): LocalDateTime =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()

    @Test
    fun pickerMillisToLocalDate_isZoneIndependent() {
        assertEquals(mar15, UiTimeUtils.pickerMillisToLocalDate(mar15PickerMillis))
    }

    @Test
    fun localDateToPickerMillis_isUtcMidnight() {
        assertEquals(mar15PickerMillis, UiTimeUtils.localDateToPickerMillis(mar15))
    }

    @Test
    fun pickerMillisRoundTrip_localDate() {
        val dates = listOf(
            LocalDate.of(2025, 1, 1),
            LocalDate.of(2025, 3, 9),
            LocalDate.of(2025, 3, 30),
            LocalDate.of(2025, 12, 31)
        )
        dates.forEach { date ->
            assertEquals(
                date,
                UiTimeUtils.pickerMillisToLocalDate(UiTimeUtils.localDateToPickerMillis(date))
            )
        }
    }

    @Test
    fun instantToPickerMillis_newYorkLateEvening_keepsLocalDay() {
        // 23:30 local Mar 15 in New York is already Mar 16 in UTC.
        val instant = millis(LocalDateTime.of(2025, 3, 15, 23, 30), newYork)
        assertEquals(mar15PickerMillis, UiTimeUtils.instantToPickerMillis(instant, newYork))
    }

    @Test
    fun instantToPickerMillis_athensAfterMidnight_keepsLocalDay() {
        // 00:30 local Mar 15 in Athens is still Mar 14 in UTC.
        val instant = millis(LocalDateTime.of(2025, 3, 15, 0, 30), athens)
        assertEquals(mar15PickerMillis, UiTimeUtils.instantToPickerMillis(instant, athens))
    }

    @Test
    fun instantToPickerMillis_kiritimati_keepsLocalDay() {
        val early = millis(LocalDateTime.of(2025, 3, 15, 0, 30), kiritimati)
        val late = millis(LocalDateTime.of(2025, 3, 15, 23, 30), kiritimati)
        assertEquals(mar15PickerMillis, UiTimeUtils.instantToPickerMillis(early, kiritimati))
        assertEquals(mar15PickerMillis, UiTimeUtils.instantToPickerMillis(late, kiritimati))
    }

    @Test
    fun instantToPickerMillis_thenPickerToLocalDate_roundTripsInEveryZone() {
        zones.forEach { zone ->
            listOf(
                LocalDateTime.of(2025, 3, 15, 0, 0),
                LocalDateTime.of(2025, 3, 15, 0, 30),
                LocalDateTime.of(2025, 3, 15, 12, 0),
                LocalDateTime.of(2025, 3, 15, 23, 59, 59)
            ).forEach { local ->
                val instant = millis(local, zone)
                val picker = UiTimeUtils.instantToPickerMillis(instant, zone)
                assertEquals(
                    "zone=$zone local=$local",
                    mar15,
                    UiTimeUtils.pickerMillisToLocalDate(picker)
                )
            }
        }
    }

    @Test
    fun pickerMillisToLocalStartOfDay_isLocalMidnightOfPickedDay() {
        zones.forEach { zone ->
            val result = UiTimeUtils.pickerMillisToLocalStartOfDay(mar15PickerMillis, zone)
            assertEquals(
                "zone=$zone",
                LocalDateTime.of(2025, 3, 15, 0, 0),
                localOf(result, zone)
            )
        }
    }

    @Test
    fun pickerMillisToLocalStartOfDay_matchesUiTimeUtilsStartOfDay() {
        zones.forEach { zone ->
            val result = UiTimeUtils.pickerMillisToLocalStartOfDay(mar15PickerMillis, zone)
            assertEquals(result, UiTimeUtils.startOfDay(result, zone))
        }
    }

    @Test
    fun pickerMillisToLocalStartOfDay_dstDays_returnLocalMidnight() {
        val nyDstStart = UiTimeUtils.localDateToPickerMillis(LocalDate.of(2025, 3, 9))
        val nyDstEnd = UiTimeUtils.localDateToPickerMillis(LocalDate.of(2025, 11, 2))
        val athensDstStart = UiTimeUtils.localDateToPickerMillis(LocalDate.of(2025, 3, 30))
        val athensDstEnd = UiTimeUtils.localDateToPickerMillis(LocalDate.of(2025, 10, 26))
        assertEquals(
            LocalDateTime.of(2025, 3, 9, 0, 0),
            localOf(UiTimeUtils.pickerMillisToLocalStartOfDay(nyDstStart, newYork), newYork)
        )
        assertEquals(
            LocalDateTime.of(2025, 11, 2, 0, 0),
            localOf(UiTimeUtils.pickerMillisToLocalStartOfDay(nyDstEnd, newYork), newYork)
        )
        assertEquals(
            LocalDateTime.of(2025, 3, 30, 0, 0),
            localOf(UiTimeUtils.pickerMillisToLocalStartOfDay(athensDstStart, athens), athens)
        )
        assertEquals(
            LocalDateTime.of(2025, 10, 26, 0, 0),
            localOf(UiTimeUtils.pickerMillisToLocalStartOfDay(athensDstEnd, athens), athens)
        )
    }

    @Test
    fun pickerMillisKeepingTimeOf_preservesLocalTimeOfDay_andZeroesMillis() {
        zones.forEach { zone ->
            val reference = millis(LocalDateTime.of(2025, 3, 1, 21, 45, 12), zone) + 789L
            val result = UiTimeUtils.pickerMillisKeepingTimeOf(mar15PickerMillis, reference, zone)
            assertEquals(
                "zone=$zone",
                LocalDateTime.of(2025, 3, 15, 21, 45, 12),
                localOf(result, zone)
            )
            assertEquals(0L, result % 1000L)
        }
    }

    @Test
    fun pickerMillisKeepingTimeOf_newYorkLateEvening_doesNotShiftDay() {
        // The west-of-UTC regression: 23:30 local must stay on the picked day.
        val reference = millis(LocalDateTime.of(2025, 3, 1, 23, 30), newYork)
        val result = UiTimeUtils.pickerMillisKeepingTimeOf(mar15PickerMillis, reference, newYork)
        assertEquals(LocalDateTime.of(2025, 3, 15, 23, 30), localOf(result, newYork))
    }

    @Test
    fun pickerMillisKeepingTimeOf_athensEarlyMorning_doesNotShiftDay() {
        val reference = millis(LocalDateTime.of(2025, 3, 1, 0, 30), athens)
        val result = UiTimeUtils.pickerMillisKeepingTimeOf(mar15PickerMillis, reference, athens)
        assertEquals(LocalDateTime.of(2025, 3, 15, 0, 30), localOf(result, athens))
    }

    @Test
    fun pickerMillisKeepingTimeOf_kiritimati_preservesDayAndTime() {
        val reference = millis(LocalDateTime.of(2025, 3, 1, 0, 15), kiritimati)
        val result = UiTimeUtils.pickerMillisKeepingTimeOf(mar15PickerMillis, reference, kiritimati)
        assertEquals(LocalDateTime.of(2025, 3, 15, 0, 15), localOf(result, kiritimati))
    }

    @Test
    fun pickerMillisKeepingTimeOf_springForwardGap_newYork_shiftsForward() {
        // 02:30 does not exist on 2025-03-09 in New York (02:00 -> 03:00).
        val reference = millis(LocalDateTime.of(2025, 3, 8, 2, 30), newYork)
        val picked = UiTimeUtils.localDateToPickerMillis(LocalDate.of(2025, 3, 9))
        val result = UiTimeUtils.pickerMillisKeepingTimeOf(picked, reference, newYork)
        assertEquals(LocalDateTime.of(2025, 3, 9, 3, 30), localOf(result, newYork))
    }

    @Test
    fun pickerMillisKeepingTimeOf_springForwardGap_athens_shiftsForward() {
        // 03:30 does not exist on 2025-03-30 in Athens (03:00 -> 04:00).
        val reference = millis(LocalDateTime.of(2025, 3, 29, 3, 30), athens)
        val picked = UiTimeUtils.localDateToPickerMillis(LocalDate.of(2025, 3, 30))
        val result = UiTimeUtils.pickerMillisKeepingTimeOf(picked, reference, athens)
        assertEquals(LocalDateTime.of(2025, 3, 30, 4, 30), localOf(result, athens))
    }

    @Test
    fun pickerMillisKeepingTimeOf_awayFromGap_onDstDay_keepsTime() {
        val referenceNy = millis(LocalDateTime.of(2025, 3, 8, 10, 15), newYork)
        val pickedNy = UiTimeUtils.localDateToPickerMillis(LocalDate.of(2025, 3, 9))
        assertEquals(
            LocalDateTime.of(2025, 3, 9, 10, 15),
            localOf(UiTimeUtils.pickerMillisKeepingTimeOf(pickedNy, referenceNy, newYork), newYork)
        )
        val referenceAthens = millis(LocalDateTime.of(2025, 3, 29, 10, 15), athens)
        val pickedAthens = UiTimeUtils.localDateToPickerMillis(LocalDate.of(2025, 3, 30))
        assertEquals(
            LocalDateTime.of(2025, 3, 30, 10, 15),
            localOf(
                UiTimeUtils.pickerMillisKeepingTimeOf(pickedAthens, referenceAthens, athens),
                athens
            )
        )
    }

    @Test
    fun pickerMillisKeepingTimeOf_fallBackOverlap_keepsLocalTimeAndDay() {
        // 01:30 occurs twice on 2025-11-02 in New York; any resolution must keep the wall time.
        val reference = millis(LocalDateTime.of(2025, 11, 1, 1, 30), newYork)
        val picked = UiTimeUtils.localDateToPickerMillis(LocalDate.of(2025, 11, 2))
        val result = UiTimeUtils.pickerMillisKeepingTimeOf(picked, reference, newYork)
        assertEquals(LocalDateTime.of(2025, 11, 2, 1, 30), localOf(result, newYork))
    }

    @Test
    fun keepingTimeOf_sameDayPick_isIdempotentForWholeSecondInstants() {
        zones.forEach { zone ->
            val instant = millis(LocalDateTime.of(2025, 3, 15, 18, 20, 5), zone)
            val picker = UiTimeUtils.instantToPickerMillis(instant, zone)
            assertEquals(
                "zone=$zone",
                instant,
                UiTimeUtils.pickerMillisKeepingTimeOf(picker, instant, zone)
            )
        }
    }
}
