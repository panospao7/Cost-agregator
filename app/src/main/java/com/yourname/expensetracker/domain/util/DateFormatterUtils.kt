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
    fun javaTimeMonthDay(): String = javaTime("MMM dd").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeMonthDayShort(): String = javaTime("MMM d").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeFullDate(): String = javaTime("EEE, dd MMM yyyy").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeDateTime(): String = javaTime("MMM dd, HH:mm").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeMonthYear(): String = javaTime("MMMM yyyy").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeTimeOnly(): String = javaTime("HH:mm").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeTimeWithSeconds(): String = javaTime("HH:mm:ss").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeTimeWithSecondsAndDate(): String = javaTime("HH:mm:ss dd/MM").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeShortDate(): String = javaTime("dd/MM/yyyy").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeShortDateWithTime(): String = javaTime("dd/MM/yyyy HH:mm").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeIsoTimestamp(): String = javaTime("yyyy-MM-dd'T'HH:mm:ss").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeDateKey(): String = javaTime("yyyy-MM-dd").format(Instant.now().atZone(ZoneId.systemDefault()))
    fun javaTimeFullDateWithDay(): String = javaTime("EEEE, MMMM d, yyyy").format(Instant.now().atZone(ZoneId.systemDefault()))

    fun formatTimestampJavaTime(timestamp: Long, pattern: String): String {
        return Instant.ofEpochMilli(timestamp)
            .atZone(ZoneId.systemDefault())
            .format(javaTime(pattern))
    }
}
