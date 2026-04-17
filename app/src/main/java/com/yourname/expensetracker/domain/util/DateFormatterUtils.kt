package com.yourname.expensetracker.domain.util

import java.text.SimpleDateFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

object DateFormatterUtils {
    private const val MAX_CACHE_SIZE = 16

    private data class FormatterCacheKey(
        val pattern: String,
        val locale: Locale
    )

    // ThreadLocal for SimpleDateFormat - each thread gets its own instance
    private val threadLocalFormatters = ThreadLocal<MutableMap<FormatterCacheKey, SimpleDateFormat>>()

    // Thread-safe cache for DateTimeFormatter (immutable and thread-safe)
    private val javaTimeFormatters = Collections.synchronizedMap(
        createLruCache<DateTimeFormatter>()
    )

    private fun getLocalFormatters(): MutableMap<FormatterCacheKey, SimpleDateFormat> {
        var formatters = threadLocalFormatters.get()
        if (formatters == null) {
            formatters = createLruCache()
            threadLocalFormatters.set(formatters)
        }
        return formatters
    }

    private fun <T> createLruCache(): MutableMap<FormatterCacheKey, T> {
        return object : LinkedHashMap<FormatterCacheKey, T>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<FormatterCacheKey, T>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    }

    @Deprecated("Use javaTime() methods instead - SimpleDateFormat is not thread-safe", ReplaceWith("javaTime(pattern)"))
    fun get(pattern: String, locale: Locale = Locale.getDefault()): SimpleDateFormat {
        val local = getLocalFormatters()
        val cacheKey = FormatterCacheKey(pattern = pattern, locale = locale)
        return local.getOrPut(cacheKey) {
            SimpleDateFormat(pattern, locale)
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

    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeMonthDay()"))
    fun monthDay(): SimpleDateFormat = get("MMM dd")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeMonthDayShort()"))
    fun monthDayShort(): SimpleDateFormat = get("MMM d")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeFullDate()"))
    fun fullDate(): SimpleDateFormat = get("EEE, dd MMM yyyy")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeDateTime()"))
    fun dateTime(): SimpleDateFormat = get("MMM dd, HH:mm")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeMonthYear()"))
    fun monthYear(): SimpleDateFormat = get("MMMM yyyy")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeTimeOnly()"))
    fun timeOnly(): SimpleDateFormat = get("HH:mm")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeTimeWithSeconds()"))
    fun timeWithSeconds(): SimpleDateFormat = get("HH:mm:ss")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeTimeWithSecondsAndDate()"))
    fun timeWithSecondsAndDate(): SimpleDateFormat = get("HH:mm:ss dd/MM")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeShortDate()"))
    fun shortDate(): SimpleDateFormat = get("dd/MM/yyyy")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeShortDateWithTime()"))
    fun shortDateWithTime(): SimpleDateFormat = get("dd/MM/yyyy HH:mm")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeIsoTimestamp()"))
    fun isoTimestamp(): SimpleDateFormat = get("yyyy-MM-dd'T'HH:mm:ss")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeDateKey()"))
    fun dateKey(): SimpleDateFormat = get("yyyy-MM-dd")
    @Deprecated("Use javaTime() methods instead", ReplaceWith("javaTimeFullDateWithDay()"))
    fun fullDateWithDay(): SimpleDateFormat = get("EEEE, MMMM d, yyyy")

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
