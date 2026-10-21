package com.yourname.expensetracker.ui.util

import com.yourname.expensetracker.domain.util.TimeProvider
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * Time/date utilities for the UI layer.
 *
 * Rule: No composable should use Calendar.getInstance(), LocalDate.now(),
 * or System.currentTimeMillis() directly. Use these helpers with injected TimeProvider.
 *
 * This ensures:
 * 1. Tests can use fixed time
 * 2. All screens agree on "now"
 * 3. Timezone handling is explicit
 */
object UiTimeUtils {

    /**
     * Get start-of-day millis for a given timestamp.
     */
    fun startOfDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        return Instant.ofEpochMilli(millis)
            .atZone(zone)
            .toLocalDate()
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Get end-of-day millis (start of next day) for a given timestamp.
     */
    fun endOfDay(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        return Instant.ofEpochMilli(millis)
            .atZone(zone)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Get the year from a timestamp.
     */
    fun getYear(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        return Instant.ofEpochMilli(millis).atZone(zone).year
    }

    /**
     * Get day range (startOfDay to startOfNextDay) for navigation filters.
     */
    fun dayRange(millis: Long, zone: ZoneId = ZoneId.systemDefault()): Pair<Long, Long> {
        val start = startOfDay(millis, zone)
        val end = endOfDay(millis, zone)
        return start to end
    }

    // ---- Material3 DatePicker conversions ----
    //
    // The Material3 DatePicker's selectedDateMillis / initialSelectedDateMillis are
    // UTC-midnight millis of the picked calendar day, NOT a local-zone instant.
    // Reading them in the system zone shows the previous day west of UTC.

    /**
     * Calendar day picked in a Material3 DatePicker (UTC-midnight millis) as a [LocalDate].
     * Zone independent by design.
     */
    fun pickerMillisToLocalDate(pickerMillis: Long): LocalDate {
        return Instant.ofEpochMilli(pickerMillis).atZone(ZoneOffset.UTC).toLocalDate()
    }

    /**
     * UTC-midnight millis for a calendar day, as expected by the Material3 DatePicker.
     */
    fun localDateToPickerMillis(date: LocalDate): Long {
        return date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    }

    /**
     * Initial DatePicker value for an instant: the instant's calendar day in [zone],
     * expressed as UTC-midnight millis.
     */
    fun instantToPickerMillis(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long {
        val localDate = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()
        return localDateToPickerMillis(localDate)
    }

    /**
     * Start of the picked calendar day in [zone] (epoch millis).
     */
    fun pickerMillisToLocalStartOfDay(
        pickerMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        return pickerMillisToLocalDate(pickerMillis)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli()
    }

    /**
     * Picked calendar day combined with the local hour/minute/second of
     * [referenceEpochMillis] in [zone] (milliseconds are zeroed). A time-of-day that
     * does not exist on the picked day (DST gap) is resolved by [ZonedDateTime.of],
     * which shifts it forward by the gap length.
     */
    fun pickerMillisKeepingTimeOf(
        pickerMillis: Long,
        referenceEpochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long {
        val pickedDate = pickerMillisToLocalDate(pickerMillis)
        val referenceTime = Instant.ofEpochMilli(referenceEpochMillis)
            .atZone(zone)
            .toLocalTime()
            .withNano(0)
        return ZonedDateTime.of(pickedDate, referenceTime, zone)
            .toInstant()
            .toEpochMilli()
    }
}
