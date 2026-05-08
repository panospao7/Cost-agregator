#!/usr/bin/env kotlin

// Scan for raw Double financial aggregates.
// Patterns to flag: sumOf { it.amount }, sumOf { it.effectiveAmount }, sumOf { it.effectiveAmount }
// Allowlist: MoneyAggregateBuilder, CurrencyConverter, tests
// Violations exit non-zero so CI can gate.

import java.io.File

val srcDir = File("app/src/main/java")
var violations = 0

// Patterns that indicate raw Double sum without MoneyAggregateBuilder
val rawSumPatterns = listOf(
    Regex("""sumOf\s*\{\s*it\.amount\s*\}"""),
    Regex("""sumOf\s*\{\s*it\.effectiveAmount\s*\}"""),
    Regex("""sumOf\s*\{\s*it\.normalizedAmount\s*\}"""),
    Regex("""\.sumOf\s*\{\s*it\.\w*[Pp]rice\s*\}""")
)

srcDir.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
    val content = file.readText()
    
    // Skip files that use MoneyAggregateBuilder (these are safe)
    if ("MoneyAggregateBuilder" in content || "CurrencyConverter" in content) return@forEach

    for (pattern in rawSumPatterns) {
        if (pattern.containsMatchIn(content)) {
            println("WARN: Raw amount sum in ${file.path} (matches: ${pattern.pattern})")
            violations++
        }
    }

    // Additional heuristic: sumOf with any Double field that looks like a money amount
    if ("sumOf { it.amount }" in content && "MoneyAggregateBuilder" !in content) {
        println("WARN: Raw amount sum in ${file.path}")
        violations++
    }
}

if (violations > 0) {
    println("$violations potential raw money aggregates found")
    System.exit(1)
} else {
    println("OK: No raw money aggregate violations found")
}
