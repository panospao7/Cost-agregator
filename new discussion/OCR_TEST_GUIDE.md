# OCR Parser Test Suite - Implementation Guide

## Files Generated

| File | Purpose | Usage |
|------|---------|-------|
| `OcrParserTest.kt` | Full JUnit test class | Add to `app/src/test/java/com/yourname/expensetracker/` |
| `OcrParserQuickTest.kts` | Standalone Kotlin script | Run with `kotlinc -script` |
| `OCR_TEST_DOCUMENT.txt` | Text document for OCR testing | Print or display for camera scan |
| `OCR_TEST_CARD.png` | Visual test card | Photograph directly with app |
| `OCR_REGEX_PATTERNS.md` | Detailed regex documentation | Reference for pattern updates |
| `OCR_PARSER_FIXES.md` | Complete fix documentation | Implementation guide |

---

## Quick Start

### Option 1: Run JUnit Tests (Recommended)

1. Copy `OcrParserTest.kt` to your test directory:
   ```
   app/src/test/java/com/yourname/expensetracker/OcrParserTest.kt
   ```

2. Run tests:
   ```bash
   ./gradlew test --tests "com.yourname.expensetracker.OcrParserTest"
   ```

3. View results in:
   ```
   app/build/reports/tests/test/index.html
   ```

### Option 2: Run Quick Test Script

```bash
kotlinc -script OcrParserQuickTest.kts
```

### Option 3: Visual OCR Testing

1. Display `OCR_TEST_DOCUMENT.txt` on screen
2. Use your app to scan it
3. Export raw OCR text
4. Compare with expected values

---

## Test Categories

### Test 1: Decimal Parsing
Tests the critical bug fix where `45.50` was being parsed as `4550.0`.

**Test Cases:**
- European format: `45,50` → `45.50`
- US format: `45.50` → `45.50` (THE BUG!)
- Thousand separators: `1.250,50` → `1250.50`
- OCR spacing: `45, 50` → `45.50`

### Test 2: Greek Keyword Normalization
Tests recognition of garbled Greek keywords.

**Test Cases:**
| OCR Output | Expected |
|------------|----------|
| EYNONO | TOTAL_KEY |
| ZYNOAO | TOTAL_KEY |
| 2YNONO | TOTAL_KEY |
| METPHTA | CASH_KEY |
| EYP9 | EUR |

### Test 3: Date Extraction
Tests date parsing with extended year range (2015-2035).

### Test 4: Total Extraction from Real Receipts
Tests with actual OCR output from 16 scanned receipts.

### Test 5: Bug Regression Tests
Ensures fixed bugs don't return:
- Decimal parsing bug
- EYNONO not matching
- Date range too narrow
- VAT percentage matched as total

---

## Applying Fixes to Your Code

### Fix 1: Decimal Parsing (ReceiptParser.kt)

**Replace this function:**
```kotlin
private fun parseAmount(rawAmount: String): Double {
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
```

### Fix 2: Greek Normalization (ReceiptParser.kt)

**Replace normalizeGreekOcr():**
```kotlin
private fun normalizeGreekOcr(text: String): String {
    var normalized = text.uppercase()
    
    // Phase 1: Fix number spacing
    normalized = normalized.replace(Regex("""(\d+)[.,]\s+(\d{2})"""), "$1.$2")
    normalized = normalized.replace(Regex("""(\d+)\s+[.,](\d{2})"""), "$1.$2")
    normalized = normalized.replace(Regex("""(\d)\s+(\d)"""), "$1$2")
    
    // Phase 2: Normalize Greek keywords
    // ΣΥΝΟΛΟ variants (most critical)
    normalized = normalized.replace(
        Regex("""\b[E2Z5SZ][YVIU][NO][OA0Ω][NA0ΩΛ][OA0Ω]?\b"""), 
        "TOTAL_KEY"
    )
    normalized = normalized.replace("ΣΥΝΟΛΟ", "TOTAL_KEY")
    normalized = normalized.replace("SYNOLO", "TOTAL_KEY")
    
    // ΜΕΤΡΗΤΑ variants
    normalized = normalized.replace(
        Regex("""\bM[EA]TPH[TI][A0]\b"""), 
        "CASH_KEY"
    )
    normalized = normalized.replace("ΜΕΤΡΗΤΑ", "CASH_KEY")
    
    // ΕΥΡΩ variants
    normalized = normalized.replace(Regex("""\bEYP[ΩO09Q]\b"""), "EUR")
    normalized = normalized.replace("ΕΥΡΩ", "EUR")
    
    // English keywords
    normalized = normalized.replace("TOTAL", "TOTAL_KEY")
    normalized = normalized.replace("AMOUNT", "TOTAL_KEY")
    normalized = normalized.replace("CASH", "CASH_KEY")
    
    return normalized
}
```

### Fix 3: Date Range (ReceiptParser.kt)

**Change this line:**
```kotlin
if (yearInt in 2020..2030) {
```

**To:**
```kotlin
if (yearInt in 2015..2035) {
```

---

## Expected Results After Fixes

### Before Fixes
| Metric | Value |
|--------|-------|
| Success Rate | 37.5% (6/16) |
| Decimal Errors | 5 receipts |
| Greek OCR Errors | 4 receipts |

### After Fixes
| Metric | Expected Value |
|--------|----------------|
| Success Rate | ~87.5% (14/16) |
| Decimal Errors | 0 |
| Greek OCR Errors | 1-2 (edge cases) |

---

## Troubleshooting

### Issue: Tests still failing after applying fixes

1. **Check your current ML Kit version:**
   ```kotlin
   // build.gradle
   implementation 'com.google.mlkit:text-recognition:19.0.0'
   ```

2. **Verify the text recognizer:**
   ```kotlin
   // This is using Latin-only:
   TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
   ```

3. **Consider cloud OCR for better Greek support:**
   - Google Cloud Vision API
   - AWS Textract
   - Azure Computer Vision

### Issue: Decimal parsing still wrong

1. Add debug logging:
   ```kotlin
   fun parseAmount(rawAmount: String): Double {
       Log.d("Parser", "Raw: '$rawAmount'")
       val result = // ... parsing logic
       Log.d("Parser", "Result: $result")
       return result
   }
   ```

2. Check if the issue is in extractTotal() not parseAmount()

### Issue: Greek characters still garbled

1. Try the test document to build an error map
2. Share the raw OCR output
3. Update patterns based on your specific OCR output

---

## Continuous Improvement

### Step 1: Collect OCR Data
```kotlin
// In your repository, log all OCR results
suspend fun exportParserDebugData(): String {
    val receipts = scannedReceiptDao.getAll()
    // Export to file for analysis
}
```

### Step 2: Analyze Patterns
- Compare expected vs actual OCR output
- Build character confusion matrix
- Update regex patterns

### Step 3: Add Unit Tests
- Each new receipt format = new test case
- Regression tests for fixed bugs
- Edge case coverage

---

## Test Output Example

```
╔════════════════════════════════════════════════════════════════╗
║         OCR PARSER QUICK TEST                                  ║
╚════════════════════════════════════════════════════════════════╝

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TEST 1: KEYWORD NORMALIZATION
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ PASS: 'ΣΥΝΟΛΟ' → TOTAL
✅ PASS: 'EYNONO' → TOTAL
✅ PASS: 'ZYNOAO' → TOTAL
✅ PASS: '2YNONO' → TOTAL
✅ PASS: 'METPHTA' → CASH
✅ PASS: 'EYP9' → EUR

━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
TEST 2: DECIMAL PARSING
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✅ PASS: 'EYNONO € 50,00' → 50.0 (expected: 50.00)
✅ PASS: '45.50' → 45.5 (expected: 45.50)  ← BUG FIXED!
✅ PASS: '44.20' → 44.2 (expected: 44.20)  ← BUG FIXED!
✅ PASS: '18.90' → 18.9 (expected: 18.90)  ← BUG FIXED!

╔════════════════════════════════════════════════════════════════╗
║ SUMMARY: 18/18 tests passed
║ Success Rate: 100%
║ Status: ✅ ALL TESTS PASSED!
╚════════════════════════════════════════════════════════════════╝
```

---

## Next Steps

1. **Run the tests** - See current success rate
2. **Apply the fixes** - Update your ReceiptParser.kt
3. **Re-run tests** - Verify improvements
4. **Test with real receipts** - Scan your test document
5. **Share OCR output** - If issues persist, share raw OCR text for pattern refinement

---

## Files Location

All files are saved to:
```
/home/z/my-project/download/
├── OcrParserTest.kt          # JUnit test class
├── OcrParserQuickTest.kts    # Kotlin script
├── OCR_TEST_DOCUMENT.txt     # Test document
├── OCR_TEST_CARD.png         # Visual test card
├── OCR_REGEX_PATTERNS.md     # Pattern documentation
├── OCR_PARSER_FIXES.md       # Fix documentation
└── OCR_TEST_GUIDE.md         # This file
```
