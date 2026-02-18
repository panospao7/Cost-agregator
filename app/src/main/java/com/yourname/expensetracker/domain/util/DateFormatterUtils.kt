package com.yourname.expensetracker.domain.util

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

object DateFormatterUtils {
    private val formatters = mutableMapOf<String, SimpleDateFormat>()
    private val javaTimeFormatters = mutableMapOf<String, DateTimeFormatter>()

    fun get(pattern: String): SimpleDateFormat {
        return formatters.getOrPut(pattern) {
            SimpleDateFormat(pattern, Locale.getDefault())
        }
    }

    fun getJavaTime(pattern: String): DateTimeFormatter {
        return javaTimeFormatters.getOrPut(pattern) {
            DateTimeFormatter.ofPattern(pattern).withZone(ZoneId.systemDefault())
        }
    }

    fun monthDay(): SimpleDateFormat = get("MMM dd")
    fun monthDayShort(): SimpleDateFormat = get("MMM d")
    fun fullDate(): SimpleDateFormat = get("EEE, dd MMM yyyy")
    fun dateTime(): SimpleDateFormat = get("MMM dd, HH:mm")
    fun monthYear(): SimpleDateFormat = get("MMMM yyyy")
    fun timeOnly(): SimpleDateFormat = get("HH:mm")
    fun timeWithSeconds(): SimpleDateFormat = get("HH:mm:ss")
    fun shortDate(): SimpleDateFormat = get("dd/MM/yyyy")
    fun shortDateWithTime(): SimpleDateFormat = get("dd/MM/yyyy HH:mm")
    fun isoTimestamp(): SimpleDateFormat = get("yyyy-MM-dd'T'HH:mm:ss")
    fun dateKey(): SimpleDateFormat = get("yyyy-MM-dd")

    fun formatTimestamp(timestamp: Long, pattern: String): String {
        return get(pattern).format(Date(timestamp))
    }

    fun formatTimestampJavaTime(timestamp: Long, pattern: String): String {
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(getJavaTime(pattern))
    }
}
