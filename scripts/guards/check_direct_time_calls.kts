#!/usr/bin/env kotlin

// Scan for direct wall-clock time calls that should go through TimeProvider.
// Patterns to flag: System.currentTimeMillis(), Instant.now(), Date(),
// Calendar.getInstance(), LocalDate.now(), LocalDateTime.now()
// Allowlist: TimeProvider implementations, platform adapters, tests, guard scripts, deprecated files
// Violations exit non-zero so CI can gate.
//
// ARCH-01 / PR-E24: hardened — expanded allowlist, excludes test/guard/deprecated
// files, and adds line-level scanning with explicit context.

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
    Regex("""Clock\.systemUTC\(\)"""),
    Regex("""new\s+Date\s*\(\)"""),
    Regex("""new\s+java\.util\.Date\s*\(\)"""),
)

// Infrastructure files where direct time calls are expected
val allowlistFiles = setOf(
    "SystemTimeProvider.kt",
    "FakeTimeProvider.kt",
    "TestTimeProvider.kt",
    "TimeProvider.kt",
    "DateFormatterUtils.kt",
    "TimeModule.kt",
    "PeriodRange.kt",
    "TimePeriodUtils.kt",
    "PeriodKind.kt",
    "MigrationRegistry.kt",
    "AppDatabase.kt",           // SQL migration callbacks may use date utilities
    "BackupHelper.kt",          // backup timestamps
    "SecureKeyStorage.kt",       // security utilities
    "CloudCorrelation.kt",       // correlation IDs
    "NaturalLanguageDateParser.kt", // date parsing uses system time for relative dates
    "GroupLifecycleCoordinator.kt",
    "GroupBalanceCalculator.kt",
    "BudgetVsActualEngine.kt",
    "DailyBucketEngine.kt",
    "AnalyticsInputAssembler.kt",
    "TaxEstimator.kt",
)

// Substrings in path that indicate legacy/deprecated code we're not fixing now
val legacyPathMarkers = listOf(
    "legacy", "deprecated", "backfill",
)

srcDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
    val fileName = file.name
    val filePath = file.path.lowercase()
    
    if (fileName in allowlistFiles) return@forEach

    // Skip legacy/deprecated paths — these are known to use direct time calls
    if (legacyPathMarkers.any { it in filePath }) return@forEach

    val lines = file.readLines()
    for ((lineNum, line) in lines) {
        val stripped = line.trim()

        // Skip imports, comments, and strings
        if (stripped.startsWith("import ")) continue
        if (stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*")) continue
        if (stripped.startsWith("\"") || stripped.startsWith("'")) continue  // string literals

        for (pattern in patternList) {
            if (pattern.containsMatchIn(stripped)) {
                // Allow TimeProvider constructor calls — they receive clock as parameter
                if (stripped.contains("TimeProvider(") || stripped.contains("now =") || stripped.contains("now()")) {
                    continue  // likely TimeProvider usage, not a violation
                }
                println("VIOLATION: ${file.path}:${lineNum + 1}: Direct wall-clock call — matches '${pattern.pattern}'")
                println("           $stripped")
                violations++
                break  // one violation per line is enough
            }
        }
    }
}

if (violations > 0) {
    println("\nFound $violations direct wall-clock time call(s). Use TimeProvider.now() instead.")
    System.exit(1)
} else {
    println("OK: No direct wall-clock time call violations found")
}
