#!/usr/bin/env kotlin

// Scan for direct wall-clock time calls that should go through TimeProvider.
// Patterns to flag: System.currentTimeMillis(), Instant.now(), Date(),
// Calendar.getInstance(), LocalDate.now(), LocalDateTime.now()
// Allowlist: TimeProvider implementations, platform adapters, tests
// Violations exit non-zero so CI can gate.

import java.io.File

val srcDir = File("app/src/main/java")
var violations = 0

// Patterns that indicate direct wall-clock time calls
val patternList = listOf(
    Regex("""System\.currentTimeMillis\(\)"""),
    Regex("""Instant\.now\(\)"""),
    Regex("""Date\(\)"""),
    Regex("""Calendar\.getInstance\(\)"""),
    Regex("""LocalDate\.now\(\)"""),
    Regex("""LocalDateTime\.now\(\)"""),
    Regex("""LocalTime\.now\(\)"""),
    Regex("""ZonedDateTime\.now\(\)"""),
    Regex("""OffsetDateTime\.now\(\)"""),
    Regex("""Clock\.systemDefaultZone\(\)"""),
    Regex("""Clock\.systemUTC\(\)""")
)

srcDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
    val content = file.readText()

    // Skip files that are part of the TimeProvider infrastructure
    if ("class SystemTimeProvider" in content || "class FakeTimeProvider" in content ||
        "class TestTimeProvider" in content || "object TimeProvider" in content ||
        "DateFormatterUtils" in content
    ) return@forEach

    for (pattern in patternList) {
        if (pattern.containsMatchIn(content)) {
            println("WARN: Direct wall-clock call in ${file.path} (matches: ${pattern.pattern})")
            violations++
        }
    }
}

if (violations > 0) {
    println("$violations potential direct wall-clock time calls found — use TimeProvider instead")
    System.exit(1)
} else {
    println("OK: No direct wall-clock time call violations found")
}
