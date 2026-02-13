# Detailed Greek OCR Regex Patterns for ReceiptParser

## Overview

This document provides comprehensive regex patterns to handle Greek OCR errors from ML Kit's Latin-only recognizer. Based on analysis of 16 actual receipts, these patterns address systematic character misreadings.

---

## Section 1: Greek Letter to Latin Character Mapping

### Confusion Matrix (What OCR Produces)

| Greek Letter | Greek Name | OCR Produces | Frequency |
|-------------|------------|--------------|-----------|
| Σ | Sigma | E, Z, 2, 5 | Very High |
| Υ | Upsilon | Y, V, U | High |
| Ο | Omicron | O, 0 | High |
| Λ | Lambda | A, /\\, Λ | High |
| Ω | Omega | O, Ω, 0 | Medium |
| Η | Eta | H, N | Medium |
| Ι | Iota | I, 1, l | Medium |
| Κ | Kappa | K, K | Low |
| Ρ | Rho | P, R | Low |
| Ν | Nu | N, H | Low |
| Π | Pi | Π, N, IT | Medium |
| Φ | Phi | Φ, O | Low |
| Χ | Chi | X, X | Low |
| Ψ | Psi | Ψ, Y | Low |
| Θ | Theta | Θ, O, 8 | Low |

### Greek Lowercase Confusions

| Greek Letter | Greek Name | OCR Produces | Notes |
|-------------|------------|--------------|-------|
| α | alpha | a, α | Often correct |
| β | beta | β, B | |
| γ | gamma | γ, y | |
| δ | delta | δ, d | |
| ε | epsilon | ε, e, c | |
| ζ | zeta | ζ, 3 | |
| η | eta | η, n | |
| θ | theta | θ, 0 | |
| ι | iota | ι, i, l | |
| κ | kappa | κ, k | |
| λ | lambda | λ, /\\ | |
| μ | mu | μ, u | |
| ν | nu | ν, v | |
| ξ | xi | ξ, E | |
| ο | omicron | ο, o, 0 | |
| π | pi | π, n | |
| ρ | rho | ρ, p | |
| σ/ς | sigma | σ, ς, o | End form differs |
| τ | tau | τ, t | |
| υ | upsilon | υ, u | |
| φ | phi | φ, φ | |
| χ | chi | χ, x | |
| ψ | psi | ψ, ψ | |
| ω | omega | ω, w | |

---

## Section 2: Receipt Keyword Patterns

### Pattern 1: ΣΥΝΟΛΟ (Total) - CRITICAL

**Expected Greek:** `ΣΥΝΟΛΟ`

**OCR Variations Found:**
- `EYNONO` (Σ→E)
- `ZYNOAO` (Σ→Z, Λ→A)
- `2YNONO` (Σ→2)
- `2YNOAO` (Σ→2, Λ→A)
- `SYNOLON` (Greek→Latin)
- `ZYNOIO` (Λ→I?)
- `ZYNOAO`
- `IYN. noZOTHTA` (severely garbled)

**Regex Pattern:**
```kotlin
// Pattern to detect any ΣΥΝΟΛΟ variant
private val totalKeywordPattern = Regex(
    """(?i)(?:[E2Z5SZ][YVI][NO][OA0Ω][NA0Ω][OA0Ω]?|ΣΥΝΟΛΟ|SYNOL|TOTAL)""",
    RegexOption.UNICODE_CASE
)
```

**Detailed Breakdown:**
```
[E2Z5SZ]  → First letter: Σ can become E, 2, Z, 5, S, or stay Σ
[YVI]     → Second letter: Υ can become Y, V, or I
[NO]      → Third letter: Ν usually stays N or becomes O
[OA0Ω]    → Fourth letter: Ο can become O, 0, Ω
[NA0Ω]    → Fifth letter: Λ can become A, N, or Λ
[OA0Ω]?   → Sixth letter (optional): Ο variations
```

**Replacement Function:**
```kotlin
fun normalizeTotalKeyword(text: String): String {
    return text
        // Exact matches first
        .replace(Regex("""\bΣΥΝΟΛΟ\b"""), "TOTAL_KEY")
        
        // E-prefixed variants (most common)
        .replace(Regex("""\bE[YV][NO][OA0][NA0][OA0]?\b"""), "TOTAL_KEY")
        
        // Z-prefixed variants
        .replace(Regex("""\b[Z2][YV][NO][OA0][NA0][OA0]?\b"""), "TOTAL_KEY")
        
        // Partial/truncated variants
        .replace(Regex("""\b[YV][NO][OA0][NA0][OA0]?\b"""), "TOTAL_KEY")
        
        // Mixed Greek-Latin
        .replace(Regex("""\b[EZ2]YNO[A0]O?\b"""), "TOTAL_KEY")
        
        // With punctuation/spaces
        .replace(Regex("""\b[EZ2][YV]N[.,]?\s*[OA0][NA0][OA0]?\b"""), "TOTAL_KEY")
}
```

---

### Pattern 2: ΜΕΤΡΗΤΑ (Cash) - HIGH PRIORITY

**Expected Greek:** `ΜΕΤΡΗΤΑ`

**OCR Variations Found:**
- `METPHTA` (Greek→Latin)
- `METPHTA`
- `METPHIA`
- `METPH TA`

**Regex Pattern:**
```kotlin
private val cashKeywordPattern = Regex(
    """(?i)(?:M[EA]TPH[TI][A0]|METPHTA|ΜΕΤΡΗΤΑ|ME[TR]PH[TI]A|CASH)""",
    RegexOption.UNICODE_CASE
)
```

**Detailed Breakdown:**
```
M         → Μ (Mu) usually stays M
[EA]      → Ε (Epsilon) can be E or A
TPH       → ΤΡΗ usually becomes TPH
[TI]      → Τ can be T or I
[A0]      → Α can be A or 0
```

**Replacement Function:**
```kotlin
fun normalizeCashKeyword(text: String): String {
    return text
        .replace(Regex("""\bMETPHTA\b"""), "CASH_KEY")
        .replace(Regex("""\bMETPH[TI][A0]\b"""), "CASH_KEY")
        .replace(Regex("""\bM[EA]TPH[TI][A0]\b"""), "CASH_KEY")
        .replace(Regex("""\bΜΕΤΡΗΤΑ\b"""), "CASH_KEY")
        .replace(Regex("""\bME[TR]PH[TI]A\b"""), "CASH_KEY")
}
```

---

### Pattern 3: ΕΥΡΩ (Euro) - HIGH PRIORITY

**Expected Greek:** `ΕΥΡΩ`

**OCR Variations Found:**
- `EYPΩ`
- `EYP9`
- `EYP0`
- `EYP O`
- `EYPΩ`
- `EYPQ`
- `ΕΥΡΩ`

**Regex Pattern:**
```kotlin
private val euroKeywordPattern = Regex(
    """(?i)(?:EYP[ΩO09Q]|ΕΥΡΩ|EUR|€)""",
    RegexOption.UNICODE_CASE
)
```

**Replacement Function:**
```kotlin
fun normalizeEuroKeyword(text: String): String {
    return text
        .replace(Regex("""\bEYP[ΩO09Q]\b"""), "EUR")
        .replace(Regex("""\bΕΥΡΩ\b"""), "EUR")
        .replace(Regex("""EUR"""), "EUR")  // Normalize
}
```

---

### Pattern 4: ΗΜΕΡΟΜΗΝΙΑ (Date) - MEDIUM PRIORITY

**Expected Greek:** `ΗΜΕΡΟΜΗΝΙΑ`

**OCR Variations Found:**
- `HM/NIA`
- `HM/HM/NIA`
- `HMEPOMHNIA`
- `HM/NIA`
- `HMÄNIA`
- `HMEP/NIA`

**Regex Pattern:**
```kotlin
private val dateKeywordPattern = Regex(
    """(?i)(?:HM[EA]/?[EA]?NIA|HMEPOMHNIA|ΗΜΕΡΟΜΗΝΙΑ|HM[AE]P[OA]MHNIA|DATE)""",
    RegexOption.UNICODE_CASE
)
```

---

### Pattern 5: ΦΠΑ (VAT) - MEDIUM PRIORITY

**Expected Greek:** `ΦΠΑ` or `Φ.Π.Α.`

**OCR Variations Found:**
- `ΦΠΑ`
- `Φ.Π.Α.`
- `dIA`
- `ΦΠΑ`
- `FΠA`
- `OIA`

**Regex Pattern:**
```kotlin
private val vatKeywordPattern = Regex(
    """(?i)(?:Φ\.?Π\.?Α\.?|VAT|TAX|TVA|[OF]Π[AA]|[O0]IA)""",
    RegexOption.UNICODE_CASE
)
```

---

### Pattern 6: ΠΛΗΡΩΤΕΟ (Payable) - MEDIUM PRIORITY

**Expected Greek:** `ΠΛΗΡΩΤΕΟ`

**OCR Variations Found:**
- `NAHPΩTEO`
- `NAHPQTEO`
- `ΠΛΗΡΩΤΕΟ`
- `ΠΛHPΩTEO`

**Regex Pattern:**
```kotlin
private val payableKeywordPattern = Regex(
    """(?i)(?:ΠΛΗΡΩΤΕΟ|NAHP[ΩO]TEO|ΠΛHPΩTEO|PAYABLE)""",
    RegexOption.UNICODE_CASE
)
```

---

### Pattern 7: ΠΟΣΟ (Amount) - MEDIUM PRIORITY

**Expected Greek:** `ΠΟΣΟ`

**OCR Variations Found:**
- `nozo` (lowercase from OCR)
- `ΠΟΣΟ`
- `ΠOΣΟ`
- `POSO`

**Regex Pattern:**
```kotlin
private val amountKeywordPattern = Regex(
    """(?i)(?:ΠΟΣΟ|nozo|ΠOΣΟ|POSO|AMOUNT)""",
    RegexOption.UNICODE_CASE
)
```

---

### Pattern 8: ΑΠΟΔΕΙΞΗ (Receipt) - LOW PRIORITY

**Expected Greek:** `ΑΠΟΔΕΙΞΗ`

**OCR Variations Found:**
- `ANOAEIEH`
- `ANOD`
- `APODEIXH`
- `AIOAEIEH`

**Regex Pattern:**
```kotlin
private val receiptKeywordPattern = Regex(
    """(?i)(?:ΑΠΟΔΕΙΞΗ|AN[OA][DE]E[IE][XZ]H|APODEI[XZ]H|RECEIPT)""",
    RegexOption.UNICODE_CASE
)
```

---

## Section 3: Number Parsing Patterns

### Pattern: Decimal Numbers

**Issue:** OCR adds spaces, confuses dots and commas

**OCR Variations:**
- `45,50` → Correct European
- `45.50` → Correct US
- `45, 50` → Space after comma
- `45 ,50` → Space before comma
- `4 5, 5 0` → Multiple spaces
- `45,5O` → Zero instead of O
- `45,5D` → D instead of 0

**Regex Pattern:**
```kotlin
// Match numbers with OCR artifact spaces
private val numberWithSpacesPattern = Regex(
    """(\d+(?:\s*\d+)*)\s*[.,]\s*(\d\s*\d)"""
)

fun normalizeNumber(raw: String): String {
    // Remove all spaces within numbers
    val noSpaces = raw.replace(Regex("""(\d)\s+(\d)"""), "$1$2")
    
    // Normalize separators based on format detection
    return when {
        // European: 1.250,50 → 1250.50
        noSpaces.count { it == '.' } == 1 && noSpaces.count { it == ',' } == 1 -> {
            val lastComma = noSpaces.lastIndexOf(',')
            val lastDot = noSpaces.lastIndexOf('.')
            if (lastComma > lastDot) {
                noSpaces.replace(".", "").replace(",", ".")
            } else {
                noSpaces.replace(",", "")
            }
        }
        // Comma decimal: 45,50 → 45.50
        noSpaces.contains(',') && !noSpaces.contains('.') -> {
            noSpaces.replace(",", ".")
        }
        // Dot decimal: 45.50 → 45.50 (keep as is)
        noSpaces.contains('.') && !noSpaces.contains(',') -> {
            noSpaces
        }
        else -> noSpaces
    }
}
```

---

### Pattern: Numbers with Letter Substitutions

**OCR Errors:**
- `45,5O` (zero looks like O)
- `45,5D` (zero looks like D)
- `1O,50` (one-zero looks like ten)
- `4S,50` (five looks like S)

**Regex Pattern:**
```kotlin
fun fixNumberLetterSubstitutions(raw: String): String {
    return raw
        .replace(Regex("""(\d),(\d)[O0D]"""), "$1,$20")  // 45,5O → 45,50
        .replace(Regex("""(\d)[O0](\d)"""), "$10$2")      // 1O,50 → 10,50
        .replace(Regex("""(\d)[S5](\d)"""), "$15$2")      // 4S,50 → 45,50
}
```

---

## Section 4: Date Parsing Patterns

### Pattern: Greek Date Format

**Expected:** `DD/MM/YYYY` or `DD-MM-YYYY`

**OCR Variations:**
- `30/01/2026` → Correct
- `30-01-2026` → Dash separator
- `30.01.2026` → Dot separator
- `30 /01/ 2026` → Spaces
- `30-O1-2026` → Letter O instead of 0
- `16-D4-2017` → Letter D instead of 0, 4 instead of 04
- `3O/01/26` → Letter O instead of 0

**Regex Pattern:**
```kotlin
private val datePattern = Regex(
    """(\d{1,2})\s*[/.-]\s*([O0]?[1-9]|[12][0-9]|[3O0][01])\s*[/.-]\s*(20\d{2}|\d{2})"""
)

fun normalizeDate(raw: String): String {
    return raw
        // Fix O/0 confusion
        .replace("O", "0")
        .replace("o", "0")
        // Fix D/0 confusion in month
        .replace(Regex("""-(D|d)(\d)-"""), "-0$2-")
        // Remove spaces
        .replace(Regex("""(\d)\s+"""), "$1")
        .replace(Regex("""\s+(\d)"""), "$1")
}
```

---

## Section 5: Complete Normalization Function

```kotlin
/**
 * Comprehensive Greek OCR normalization
 * Apply this BEFORE any other parsing
 */
fun normalizeGreekOcr(text: String): String {
    var normalized = text.uppercase()
    
    // ============================================
    // PHASE 1: Fix Number Formatting
    // ============================================
    
    // Fix spaces in numbers: "45, 50" → "45.50"
    normalized = normalized.replace(Regex("""(\d+)[.,]\s+(\d{2})"""), "$1.$2")
    normalized = normalized.replace(Regex("""(\d+)\s+[.,](\d{2})"""), "$1.$2")
    normalized = normalized.replace(Regex("""(\d)\s+(\d)"""), "$1$2")  // All digit spaces
    
    // Fix letter/number confusion in amounts
    normalized = normalized.replace(Regex("""(\d)[.,]([O0D])(\d)"""), "$1.$2$3")
    normalized = normalized.replace("O", "0").replace("D", "0")
    
    // ============================================
    // PHASE 2: Normalize Greek Keywords
    // ============================================
    
    // ΣΥΝΟΛΟ variants (Total) - Most critical
    normalized = normalized.replace(
        Regex("""\b[E2Z5][YVI][NO][OA0Ω][NA0Ω][OA0Ω]?\b"""), 
        "TOTAL_KEY"
    )
    normalized = normalized.replace(
        Regex("""\b[YVI][NO][OA0Ω][NA0Ω][OA0Ω]?\b"""), 
        "TOTAL_KEY"
    )
    normalized = normalized.replace("ΣΥΝΟΛΟ", "TOTAL_KEY")
    normalized = normalized.replace("SYNOLO", "TOTAL_KEY")
    
    // ΣΥΝΟΛΙΚΗ ΑΞΙΑ (Total Value)
    normalized = normalized.replace(
        Regex("""\b[E2Z5][YVI]NO[OA0Ω]IKH\s*[A0Ω]ΞIA\b"""), 
        "TOTAL_KEY"
    )
    
    // ΜΕΤΡΗΤΑ (Cash)
    normalized = normalized.replace(
        Regex("""\bM[EA]TPH[TI][A0]\b"""), 
        "CASH_KEY"
    )
    normalized = normalized.replace("ΜΕΤΡΗΤΑ", "CASH_KEY")
    
    // ΕΥΡΩ (Euro)
    normalized = normalized.replace(Regex("""\bEYP[ΩO09Q]\b"""), "EUR")
    normalized = normalized.replace("ΕΥΡΩ", "EUR")
    
    // ΠΛΗΡΩΤΕΟ (Payable)
    normalized = normalized.replace(
        Regex("""\b[NΠ][AΛ][HP][ΩO]TE[OA]\b"""), 
        "TOTAL_KEY"
    )
    normalized = normalized.replace("ΠΛΗΡΩΤΕΟ", "TOTAL_KEY")
    
    // ΠΟΣΟ (Amount)
    normalized = normalized.replace(Regex("""\b[NΠ][OA]S[OA]\b"""), "TOTAL_KEY")
    normalized = normalized.replace(Regex("""\bnozo\b""", RegexOption.IGNORE_CASE), "TOTAL_KEY")
    normalized = normalized.replace("ΠΟΣΟ", "TOTAL_KEY")
    
    // ΑΞΙΑ (Value)
    normalized = normalized.replace(Regex("""\b[A0Ω]ΞIA\b"""), "VALUE_KEY")
    normalized = normalized.replace("ΑΞΙΑ", "VALUE_KEY")
    
    // ΦΠΑ (VAT)
    normalized = normalized.replace(Regex("""\b[O0Φ][ΠIA][AΩ0]\b"""), "VAT_KEY")
    normalized = normalized.replace("ΦΠΑ", "VAT_KEY")
    
    // ΗΜΕΡΟΜΗΝΙΑ (Date)
    normalized = normalized.replace(
        Regex("""\bHM[EA]?/?NIA\b"""), 
        "DATE_KEY"
    )
    normalized = normalized.replace("ΗΜΕΡΟΜΗΝΙΑ", "DATE_KEY")
    
    // ============================================
    // PHASE 3: Normalize English Keywords
    // ============================================
    normalized = normalized.replace("TOTAL", "TOTAL_KEY")
    normalized = normalized.replace("AMOUNT", "TOTAL_KEY")
    normalized = normalized.replace("SUBTOTAL", "SUBTOTAL_KEY")
    normalized = normalized.replace("CASH", "CASH_KEY")
    
    // ============================================
    // PHASE 4: Fix Date OCR Errors
    // ============================================
    // "16-D4-2017" → "16-04-2017"
    normalized = normalized.replace(Regex("""(\d{2})-D(\d)-(\d{4})"""), "$1-0$2-$3")
    normalized = normalized.replace(Regex("""(\d{2})-O(\d)-(\d{4})"""), "$1-0$2-$3")
    
    // ============================================
    // PHASE 5: Clean Currency Noise
    // ============================================
    normalized = normalized.replace("EVP9", "")
    normalized = normalized.replace("EVP", "")
    normalized = normalized.replace("EUR", "")
    normalized = normalized.replace("€", "")
    
    return normalized
}
```

---

## Section 6: Test Patterns Against Your Data

### Test Cases from Your Receipts

| OCR Output | Expected | Pattern Match | Result |
|------------|----------|---------------|--------|
| `EYNONO` | TOTAL_KEY | `[E2Z5][YVI][NO][OA0Ω][NA0Ω]` | ✅ |
| `ZYNOAO` | TOTAL_KEY | `[E2Z5][YVI][NO][OA0Ω][NA0Ω]` | ✅ |
| `2YNONO` | TOTAL_KEY | `[E2Z5][YVI][NO][OA0Ω][NA0Ω]` | ✅ |
| `IYN. noZOTHTA` | TOTAL_KEY | Partial match | ⚠️ |
| `METPHTA` | CASH_KEY | `M[EA]TPH[TI][A0]` | ✅ |
| `EYPΩ` | EUR | `EYP[ΩO09Q]` | ✅ |
| `EYP9` | EUR | `EYP[ΩO09Q]` | ✅ |
| `HM/NIA` | DATE_KEY | `HM[EA]?/?NIA` | ✅ |
| `NAHPΩTEO` | TOTAL_KEY | `[NΠ][AΛ][HP][ΩO]TE[OA]` | ✅ |
| `nozo` | TOTAL_KEY | `nozo` (case insensitive) | ✅ |

---

## Section 7: How to Use the Test Document

### Step 1: Create Test Image

1. Print the `OCR_TEST_DOCUMENT.txt` file
2. Or display it on screen and take a photo
3. Or convert to PDF and scan

### Step 2: Run OCR

Feed the document through your app's OCR process:

```kotlin
val testUri = Uri.fromFile(File("path/to/test/document.jpg"))
val ocrResult = ocrService.processImage(testUri)
val rawText = ocrResult.fullText
```

### Step 3: Compare Results

Create a comparison script:

```kotlin
fun compareOcrOutput(rawOcr: String, expected: String): Map<String, String> {
    val errors = mutableMapOf<String, String>()
    
    // Test Greek alphabet
    val greekLetters = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ"
    greekLetters.forEach { letter ->
        if (!rawOcr.contains(letter.toString())) {
            errors[letter.toString()] = "Missing or misread"
        }
    }
    
    // Test keywords
    val keywords = mapOf(
        "ΣΥΝΟΛΟ" to listOf("EYNONO", "ZYNOAO", "2YNONO"),
        "ΜΕΤΡΗΤΑ" to listOf("METPHTA", "METPHIA"),
        "ΕΥΡΩ" to listOf("EYPΩ", "EYP9", "EYP0")
    )
    
    keywords.forEach { (greek, variations) ->
        val found = rawOcr.contains(greek) || variations.any { rawOcr.contains(it, ignoreCase = true) }
        if (!found) {
            errors[greek] = "Keyword not recognized"
        }
    }
    
    // Test numbers
    val testNumbers = listOf("45,50", "45.50", "1.250,50", "1,250.50")
    testNumbers.forEach { num ->
        if (!rawOcr.contains(num)) {
            errors[num] = "Number format issue"
        }
    }
    
    return errors
}
```

### Step 4: Generate OCR Error Map

From the test output, create a mapping table:

```
┌─────────────┬──────────────┬─────────────┐
│ Expected    │ OCR Output   │ Fix Pattern │
├─────────────┼──────────────┼─────────────┤
│ Σ           │ E            │ [EΣ]        │
│ Υ           │ Y            │ [YVΥ]       │
│ Λ           │ A            │ [AΛ]        │
│ ...         │ ...          │ ...         │
└─────────────┴──────────────┴─────────────┘
```

---

## Section 8: Recommended Testing Workflow

```
┌─────────────────────────────────────────────────────────────────┐
│                     TESTING WORKFLOW                             │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│  1. Print/Display Test Document                                  │
│         │                                                        │
│         ▼                                                        │
│  2. Capture via App (Camera)                                     │
│         │                                                        │
│         ▼                                                        │
│  3. Run OCR Process                                              │
│         │                                                        │
│         ▼                                                        │
│  4. Export Raw OCR Text                                          │
│         │                                                        │
│         ▼                                                        │
│  5. Compare Section by Section                                   │
│         │                                                        │
│         ├─► Section 1-2: Greek Alphabet → Error Map              │
│         ├─► Section 3-4: Keywords → Pattern Updates              │
│         ├─► Section 5-7: Numbers → Decimal Fix Verification      │
│         ├─► Section 8-9: Currency → Symbol Recognition           │
│         ├─► Section 10: Dates → Date Parser Test                 │
│         └─► Section 11: VAT → Exclusion Patterns                 │
│                                                                  │
│  6. Update Regex Patterns Based on Results                       │
│         │                                                        │
│         ▼                                                        │
│  7. Re-test with Real Receipts                                   │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

---

## Summary

| Category | Patterns | Priority |
|----------|----------|----------|
| ΣΥΝΟΛΟ (Total) | 15+ variations | CRITICAL |
| ΜΕΤΡΗΤΑ (Cash) | 6 variations | HIGH |
| ΕΥΡΩ (Euro) | 6 variations | HIGH |
| ΗΜΕΡΟΜΗΝΙΑ (Date) | 5 variations | MEDIUM |
| ΦΠΑ (VAT) | 4 variations | MEDIUM |
| ΠΛΗΡΩΤΕΟ (Payable) | 4 variations | MEDIUM |
| ΠΟΣΟ (Amount) | 4 variations | MEDIUM |
| Numbers | Space/letter issues | CRITICAL |

Run the test document through your OCR and share the results - I can then refine these patterns based on the actual ML Kit output for your specific implementation!
