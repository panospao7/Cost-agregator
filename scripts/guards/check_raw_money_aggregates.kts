#!/usr/bin/env kotlin

// Scan for raw Double financial aggregates that should use MoneyAggregate.
// Patterns to flag: sumOf { it.amount }, sumOf { it.effectiveAmount }, etc.
// Legitimate use in MoneyAggregateBuilder.fromBuckets() is allowed.
// Violations exit non-zero so CI can gate.
//
// ARCH-01 / PR-E23: hardened — uses line-level scanning instead of
// file-level skip. A file that imports MoneyAggregateBuilder is no longer
// blanket-skipped; only lines inside fromBuckets { } blocks are exempt.

import java.io.File

val srcDir = File("app/src/main/java")
var violations = 0

// Patterns that indicate raw Double sum without MoneyAggregateBuilder
val rawSumPatterns = listOf(
    Regex("""\.sumOf\s*\{\s*it\.amount\s*\}"""),
    Regex("""\.sumOf\s*\{\s*it\.effectiveAmount\s*\}"""),
    Regex("""\.sumOf\s*\{\s*it\.normalizedAmount\s*\}"""),
    Regex("""\.sumOf\s*\{\s*it\.\w*[Pp]rice\s*\}"""),
    // Additional: sumBy or manual total: Double accumulation
    Regex("""\.sumBy\s*\{\s*it\.amount\s*\.(?:toInt|roundToInt)\s*\(\)\s*\}"""),
    Regex("""total\s*:\s*Double"""),
    Regex("""var\s+total\s*=\s*0\.0\s*;?\s*//?\s*.*sum"""),
)

// Files where raw sums are expected (infrastructure, DAOs, MoneyAggregateBuilder itself)
val allowlistFiles = setOf(
    "MoneyAggregateBuilder.kt",
    "MoneyAggregate.kt",
    "ConvertedMoney.kt",
    "CurrencyConverter.kt",
    "MultiCurrencyRepository.kt",
    "ExpenseDao.kt",
    "BudgetDao.kt",
)

srcDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
    val fileName = file.name
    if (fileName in allowlistFiles) return@forEach

    // Skip test files completely — tests may use raw sums for assertions
    if ("test" in file.path.lowercase() || "androidtest" in file.path.lowercase()) return@forEach

    val lines = file.readLines()
    var inFromBuckets = false
    var bracketDepth = 0

    for ((lineNum, line) in lines) {
        val stripped = line.trim()

        // Track fromBuckets { ... } blocks — these legitimately use entry.amount
        if (stripped.contains("fromBuckets") && stripped.contains("{")) {
            inFromBuckets = true
            bracketDepth = 0
        }
        if (inFromBuckets) {
            bracketDepth += stripped.count { it == '{' } - stripped.count { it == '}' }
            if (bracketDepth <= 0) {
                inFromBuckets = false
                bracketDepth = 0
            }
            continue  // skip lines inside fromBuckets blocks
        }

        // Skip import lines and comment-only lines
        if (stripped.startsWith("import ") || stripped.startsWith("//") || stripped.startsWith("*") || stripped.startsWith("/*")) continue

        for (pattern in rawSumPatterns) {
            if (pattern.containsMatchIn(stripped)) {
                println("VIOLATION: ${file.path}:${lineNum + 1}: Raw money aggregate — matches '${pattern.pattern}'")
                println("           $stripped")
                violations++
            }
        }
    }
}

if (violations > 0) {
    println("\nFound $violations raw money aggregate violation(s). Use MoneyAggregateBuilder instead.")
    System.exit(1)
} else {
    println("OK: No raw money aggregate violations found")
}
