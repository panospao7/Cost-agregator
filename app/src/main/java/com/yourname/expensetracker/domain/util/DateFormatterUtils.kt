package com.yourname.expensetracker.domain.util

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

object DateFormatterUtils {
    private const val MAX_CACHE_SIZE = 16

    private data class FormatterCacheKey(
        val pattern: String,
        val locale: Locale
    )

    // Thread-safe cache for DateTimeFormatter (immutable and thread-safe)
    private val javaTimeFormatters = Collections.synchronizedMap(
        createLruCache<DateTimeFormatter>()
    )

    private fun <T> createLruCache(): MutableMap<FormatterCacheKey, T> {
        return object : LinkedHashMap<FormatterCacheKey, T>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FormatterCacheKey, T>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    }

    fun javaTime(pattern: String, locale: Locale = Locale.getDefault()): DateTimeFormatter {
        val cacheKey = FormatterCacheKey(pattern = pattern, locale = locale)
        return synchronized(javaTimeFormatters) {
            javaTimeFormatters.getOrPut(cacheKey) {
                DateTimeFormatter.ofPattern(pattern, locale).withZone(ZoneId.systemDefault())
            }
        }
    }

    // Thread-safe java.time formatters
    fun javaTimeMonthDay(timestamp: Long): String = javaTime("MMM dd").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeMonthDayShort(timestamp: Long): String = javaTime("MMM d").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeFullDate(timestamp: Long): String = javaTime("EEE, dd MMM yyyy").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeDateTime(timestamp: Long): String = javaTime("MMM dd, HH:mm").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeMonthYear(timestamp: Long): String = javaTime("MMMM yyyy").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeTimeOnly(timestamp: Long): String = javaTime("HH:mm").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeTimeWithSeconds(timestamp: Long): String = javaTime("HH:mm:ss").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeTimeWithSecondsAndDate(timestamp: Long): String = javaTime("HH:mm:ss dd/MM").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeShortDate(timestamp: Long): String = javaTime("dd/MM/yyyy").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeShortDateWithTime(timestamp: Long): String = javaTime("dd/MM/yyyy HH:mm").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeIsoTimestamp(timestamp: Long): String = javaTime("yyyy-MM-dd'T'HH:mm:ss").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeDateKey(timestamp: Long): String = javaTime("yyyy-MM-dd").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))
    fun javaTimeFullDateWithDay(timestamp: Long): String = javaTime("EEEE, MMMM d, yyyy").format(Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()))

    fun formatTimestampJavaTime(timestamp: Long, pattern: String): String {
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(javaTime(pattern))
    }
}
