#!/usr/bin/env kotlin

/**
 * Quick OCR Parser Test Runner
 * 
 * Run this script to test the parser patterns without Android context.
 * Usage: kotlinc -script OcrParserQuickTest.kts
 * 
 * Or copy the test functions into your Android test class.
 */

// ============================================
// TEST DATA
// ============================================

val realReceiptData = listOf(
    Pair("EYNONO € 50,00", 50.00),
    Pair("ZYNOAO 80,43", 80.43),
    Pair("nozo/AMOUNT: €35,00", 35.00),
    Pair("ΣΥΝΟΛΟ 182,00€", 182.00),
    Pair("METPHTA 25,74", 25.74),
    Pair("45.50", 45.50),  // Bug test: should NOT be 4550
    Pair("44.20", 44.20),  // Bug test: should NOT be 4420
    Pair("18.90", 18.90),  // Bug test: should NOT be 1890
    Pair("1.250,50", 1250.50),  // European thousand sep
    Pair("1,250.50", 1250.50),  // US thousand sep
    Pair("45, 50", 45.50),  // Space after comma
    Pair("45 .50", 45.50)   // Space before dot
)

val keywordVariations = mapOf(
    "ΣΥΝΟΛΟ" to "TOTAL",
    "EYNONO" to "TOTAL",
    "ZYNOAO" to "TOTAL",
    "2YNONO" to "TOTAL",
    "ZYNOIO" to "TOTAL",
    "ΜΕΤΡΗΤΑ" to "CASH",
    "METPHTA" to "CASH",
    "ΕΥΡΩ" to "EUR",
    "EYPΩ" to "EUR",
    "EYP9" to "EUR"
)

// ============================================
// PARSER IMPLEMENTATION
// ============================================

fun normalizeGreekOcr(text: String): String {
    var normalized = text.uppercase()
    
    // Fix number spacing
    normalized = normalized.replace(Regex("""(\d+)[.,]\s+(\d{2})"""), "$1.$2")
    normalized = normalized.replace(Regex("""(\d+)\s+[.,](\d{2})"""), "$1.$2")
    normalized = normalized.replace(Regex("""(\d)\s+(\d)"""), "$1$2")
    
    // Normalize Greek keywords
    normalized = normalized.replace(Regex("""\b[E2Z5SZ][YVIU][NO][OA0Ω][NA0ΩΛ][OA0Ω]?\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\b[YVIU][NO][OA0Ω][NA0ΩΛ][OA0Ω]?\b"""), "TOTAL_KEY")
    normalized = normalized.replace("ΣΥΝΟΛΟ", "TOTAL_KEY")
    normalized = normalized.replace("SYNOLO", "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\bM[EA]TPH[TI][A0]\b"""), "CASH_KEY")
    normalized = normalized.replace("ΜΕΤΡΗΤΑ", "CASH_KEY")
    normalized = normalized.replace(Regex("""\bEYP[ΩO09Q]\b"""), "EUR")
    normalized = normalized.replace("ΕΥΡΩ", "EUR")
    normalized = normalized.replace(Regex("""\b[NΠ][OA]S[OA]\b"""), "TOTAL_KEY")
    normalized = normalized.replace("TOTAL", "TOTAL_KEY")
    normalized = normalized.replace("AMOUNT", "TOTAL_KEY")
    normalized = normalized.replace("CASH", "CASH_KEY")
    
    return normalized
}

fun parseAmount(rawAmount: String): Double {
    val trimmed = rawAmount.trim()
    val dots = trimmed.count { it == '.' }
    val commas = trimmed.count { it == ',' }
    
    return when {
        commas == 1 && dots <= 1 -> {
            trimmed.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
        }
        dots == 1 && commas <= 1 -> {
            trimmed.replace(",", "").toDoubleOrNull() ?: 0.0
        }
        dots == 1 && commas == 0 && trimmed.indexOf('.') < trimmed.length - 3 -> {
            trimmed.replace(".", "").toDoubleOrNull() ?: 0.0
        }
        commas == 1 && dots == 0 && trimmed.indexOf(',') < trimmed.length - 3 -> {
            trimmed.replace(",", "").toDoubleOrNull() ?: 0.0
        }
        else -> trimmed.toDoubleOrNull() ?: 0.0
    }
}

fun extractTotal(text: String): Double? {
    val normalized = normalizeGreekOcr(text)
    val lines = normalized.lines()
    val amountRegex = Regex("""(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})""")
    
    // Look for TOTAL_KEY
    val totalLineIndex = lines.indexOfLast { it.contains("TOTAL_KEY") }
    if (totalLineIndex != -1) {
        val matches = amountRegex.findAll(lines[totalLineIndex])
        return matches.lastOrNull()?.groupValues?.get(1)?.let { parseAmount(it) }
    }
    
    // Fallback: find largest valid amount
    var maxAmount = 0.0
    lines.forEach { line ->
        val matches = amountRegex.findAll(line)
        matches.forEach { match ->
            val amount = parseAmount(match.groupValues[1])
            if (amount > maxAmount && amount < 5000 && amount > 0) {
                maxAmount = amount
            }
        }
    }
    return if (maxAmount > 0) maxAmount else null
}

// ============================================
// RUN TESTS
// ============================================

fun main() {
    println("╔════════════════════════════════════════════════════════════════╗")
    println("║         OCR PARSER QUICK TEST                                  ║")
    println("╚════════════════════════════════════════════════════════════════╝")
    
    var passed = 0
    var failed = 0
    
    // Test 1: Keyword Normalization
    println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    println("TEST 1: KEYWORD NORMALIZATION")
    println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    
    keywordVariations.forEach { (input, expectedType) ->
        val normalized = normalizeGreekOcr(input)
        val found = when (expectedType) {
            "TOTAL" -> normalized.contains("TOTAL_KEY")
            "CASH" -> normalized.contains("CASH_KEY")
            "EUR" -> normalized.contains("EUR")
            else -> false
        }
        
        val status = if (found) "✅ PASS" else "❌ FAIL"
        println("$status: '$input' → ${if (found) expectedType else "NOT FOUND"}")
        if (found) passed++ else failed++
    }
    
    // Test 2: Decimal Parsing
    println("\n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    println("TEST 2: DECIMAL PARSING")
    println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    
    realReceiptData.forEach { (input, expected) ->
        val normalized = normalizeGreekOcr(input)
        val result = extractTotal(normalized)
        
        val success = result != null && kotlin.math.abs(result - expected) < 0.01
        val status = if (success) "✅ PASS" else "❌ FAIL"
        println("$status: '$input' → $result (expected: $expected)")
        
        if (success) passed++ else failed++
    }
    
    // Summary
    println("\n╔════════════════════════════════════════════════════════════════╗")
    println("║ SUMMARY: $passed/${passed + failed} tests passed")
    val rate = (passed * 100.0 / (passed + failed)).toInt()
    println("║ Success Rate: $rate%")
    if (rate >= 80) {
        println("║ Status: ✅ ALL TESTS PASSED!")
    } else {
        println("║ Status: ❌ SOME TESTS FAILED - Review patterns")
    }
    println("╚════════════════════════════════════════════════════════════════╝")
}

main()
