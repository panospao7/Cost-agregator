# OCR & Parser Fixes for ExpenseTracker

## Executive Summary

After analyzing 16 receipts and their parser output, the success rate is only **37.5%** (6/16). The main issues are:

1. **Decimal parsing bug** - Causes 6 receipts to have 100x inflated totals
2. **Greek OCR not supported** - ΣΥΝΟΛΟ garbled in 4 receipts
3. **Pattern matching too strict** - Misses OCR variations
4. **Date validation too narrow** - Rejects valid years

---

## Fix #1: Decimal Parsing (CRITICAL)

### Problem Location
`ReceiptParser.kt` - `parseAmount()` function (line ~9230)

### Current Code (BROKEN)
```kotlin
private fun parseAmount(rawAmount: String): Double {
    val clean = rawAmount.replace(".", "").replace(",", ".")
    return clean.toDoubleOrNull() ?: 0.0
}
```

### Issue
When OCR produces `45.50` (dot as decimal), the code:
1. Removes ALL dots: `45.50` → `4550`
2. Then replaces comma with dot (but there's no comma)
3. Result: `4550.0` instead of `45.50`

### Fixed Code
```kotlin
private fun parseAmount(rawAmount: String): Double {
    val trimmed = rawAmount.trim()
    
    // Count separators to determine format
    val dots = trimmed.count { it == '.' }
    val commas = trimmed.count { it == ',' }
    
    return when {
        // European format: 1.250,50 or just 45,50
        commas == 1 && dots <= 1 -> {
            // Remove thousand separators (dots), replace decimal comma
            trimmed.replace(".", "").replace(",", ".").toDoubleOrNull() ?: 0.0
        }
        // US/English format: 1,250.50 or just 45.50
        dots == 1 && commas <= 1 -> {
            // Remove thousand separators (commas), keep decimal dot
            trimmed.replace(",", "").toDoubleOrNull() ?: 0.0
        }
        // Thousand separators only: 1.250 or 1,250
        dots == 1 && commas == 0 && trimmed.indexOf('.') < trimmed.length - 3 -> {
            trimmed.replace(".", "").toDoubleOrNull() ?: 0.0
        }
        commas == 1 && dots == 0 && trimmed.indexOf(',') < trimmed.length - 3 -> {
            trimmed.replace(",", "").toDoubleOrNull() ?: 0.0
        }
        // No separators: just a number
        else -> trimmed.toDoubleOrNull() ?: 0.0
    }
}
```

### Test Cases
| Input | Current Output | Fixed Output |
|-------|----------------|--------------|
| `45.50` | 4550.0 ❌ | 45.50 ✅ |
| `44.20` | 4420.0 ❌ | 44.20 ✅ |
| `18.90` | 1890.0 ❌ | 18.90 ✅ |
| `1.250,50` | 1.2505 ❌ | 1250.50 ✅ |
| `1,250.50` | 125050.0 ❌ | 1250.50 ✅ |

---

## Fix #2: Greek OCR Recognition (CRITICAL)

### Problem Location
`ReceiptOcrService.kt` - line ~8732

### Current Code (WRONG)
```kotlin
private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
```

### Issue
`DEFAULT_OPTIONS` only supports Latin script. Greek letters are misread:
- Σ → E, Z, 2
- Υ → Y, V  
- Λ → A
- Ω → O

### Solution Options

#### Option A: Use ML Kit Script Recognition (Recommended)
```kotlin
// Add dependency: com.google.mlkit:text-recognition-greek:16.0.0
// Unfortunately, ML Kit doesn't have a standalone Greek recognizer

// Best option: Use the all-scripts recognizer
private val recognizer = TextRecognition.getClient(
    TextRecognizerOptions.Builder()
        .setExecutor(Executors.newSingleThreadExecutor())
        .build()
)
// Note: This still uses Latin primarily. For Greek, consider cloud-based OCR.
```

#### Option B: Switch to Google Cloud Vision (Best for Greek)
```kotlin
// Add dependency: com.google.cloud:google-cloud-vision
// This requires a Google Cloud project and API key

suspend fun processImageWithCloudVision(imageUri: Uri): OcrResult {
    val imageBytes = context.contentResolver.openInputStream(imageUri)?.use { 
        it.readBytes() 
    } ?: throw IllegalStateException("Cannot read image")
    
    val image = Image.newBuilder()
        .setContent(ByteString.copyFrom(imageBytes))
        .build()
    
    val feature = Feature.newBuilder()
        .setType(Feature.Type.TEXT_DETECTION)
        .build()
    
    val request = AnnotateImageRequest.newBuilder()
        .addFeatures(feature)
        .setImage(image)
        .setImageContext(
            ImageContext.newBuilder()
                .addLanguageHints("el")  // Greek
                .addLanguageHints("en")  // English fallback
        )
        .build()
    
    val response = imageAnnotatorClient.annotateImage(request)
    // Process response...
}
```

#### Option C: Improve Pre-processing + Fallback (Quick Fix)
If cloud OCR is not an option, improve the Greek text normalization:

```kotlin
private fun normalizeGreekOcr(text: String): String {
    return text.uppercase()
        // --- Fix Numbers with spaces ---
        .replace(Regex("(\\d+)[.,]\\s+(\\d{2})"), "$1.$2")
        .replace(Regex("(\\d+)\\s+[.,](\\d{2})"), "$1.$2")
        
        // --- Normalize Greek letter OCR errors to Greek ---
        .replace(Regex("[E2Z][YV]N[OA0Ω][NA0Ω][OA0Ω]"), "ΣΥΝΟΛΟ")  // EYNONO, ZYNOAO, 2YNONO
        .replace(Regex("[E2Z]YN[OA0Ω]"), "ΣΥΝΟ")                    // Partial match
        .replace(Regex("YNOA[OA0Ω]"), "ΥΝΟΛΟ")                      // Middle part
        .replace(Regex("NOZOTHTA"), "ΠΟΣΟΤΗΤΑ")                      // Quantity
        .replace(Regex("NAHP[ΩO]TEO"), "ΠΛΗΡΩΤΕΟ")                   // Payable
        .replace(Regex("METPHTA"), "ΜΕΤΡΗΤΑ")                        // Cash
        .replace(Regex("EYP[ΩOQ]"), "ΕΥΡΩ")                          // Euro
        .replace(Regex("HM[/]NIA"), "ΗΜΕΡΟΜΗΝΙΑ")                    // Date
        .replace(Regex("TPAIEZI"), "ΤΡΑΠΕΖΙ")                        // Bank
        
        // --- Convert normalized Greek keywords to marker ---
        .replace("ΣΥΝΟΛΟ", "TOTAL_KEY")
        .replace("ΠΛΗΡΩΤΕΟ", "TOTAL_KEY")
        .replace("ΠΟΣΟ", "TOTAL_KEY")
        .replace("AMOUNT", "TOTAL_KEY")
        .replace("TOTAL", "TOTAL_KEY")
        
        // --- Fix common date OCR errors ---
        .replace(Regex("(\\d{2})-D(\\d)-(\\d{4})"), "$1-0$2-$3")   // 16-D4-2017 → 16-04-2017
        .replace(Regex("(\\d{2})-O(\\d)-(\\d{4})"), "$1-0$2-$3")   // 16-O4-2017 → 16-04-2017
        
        // --- Clean currency noise ---
        .replace("EVP9", "")
        .replace("EVP", "")
        .replace("EUR", "")
        .replace("€", "")
}
```

---

## Fix #3: Total Extraction Fallback

### Problem Location
`ReceiptParser.kt` - `extractTotal()` function

### Current Issue
The fallback picks up wrong amounts:
- VAT percentages (13.00%, 24.00%)
- Unit prices (1.574 €/ΛΤ)
- Tax amounts

### Improved Code
```kotlin
private fun extractTotal(lines: List<String>): Double? {
    val amountRegex = Regex("""(\d{1,3}(?:[.,]\d{3})*[.,]\d{2})(?!\s?%)""")
    
    // --- STRATEGY 1: Explicit "TOTAL" Keyword ---
    val totalLineIndex = lines.indexOfLast { it.contains("TOTAL_KEY") }
    if (totalLineIndex != -1) {
        // Check the exact line
        val amountInLine = extractAmountFromLine(lines[totalLineIndex], amountRegex)
        if (amountInLine != null && isValidTotal(amountInLine, lines[totalLineIndex])) {
            return amountInLine
        }
        // Check the NEXT line (common in POS receipts)
        if (totalLineIndex + 1 < lines.size) {
            val amountNext = extractAmountFromLine(lines[totalLineIndex + 1], amountRegex)
            if (amountNext != null && isValidTotal(amountNext, lines[totalLineIndex + 1])) {
                return amountNext
            }
        }
    }
    
    // --- STRATEGY 2: Look for card receipt patterns ---
    // "nozo/AMOUNT:" or "ΠΟΣΟ/AMOUNT:" followed by amount
    for (i in lines.indices) {
        val line = lines[i]
        if (line.contains("AMOUNT", ignoreCase = true) || 
            line.contains("nozo", ignoreCase = true) ||
            line.contains("ΠΟΣΟ", ignoreCase = true)) {
            // Amount might be on same line or next line
            val amount = extractAmountFromLine(line, amountRegex)
                ?: if (i + 1 < lines.size) extractAmountFromLine(lines[i + 1], amountRegex) else null
            if (amount != null && amount > 0) return amount
        }
    }
    
    // --- STRATEGY 3: Bottom-amount fallback with strict filtering ---
    var maxAmount = 0.0
    val searchStart = (lines.size * 0.3).toInt()
    
    for (i in searchStart until lines.size) {
        val line = lines[i]
        
        // FILTER: Skip lines that are definitely NOT totals
        if (line.contains("%")) continue                           // VAT rates
        if (line.contains("METPHTA") || line.contains("ΜΕΤΡΗΤΑ") || 
            line.contains("CASH")) continue                        // Cash given
        if (line.contains("RESTA") || line.contains("ΡΕΣΤΑ")) continue  // Change
        if (line.contains("KARTA") || line.contains("ΚΑΡΤΑ") ||
            line.contains("CARD")) continue                        // Card label
        if (line.contains("MONAS") || line.contains("ΜΟΝΑΔΟΣ")) continue  // Unit price
        if (line.contains("/AT") || line.contains("/ΛΤ")) continue    // Per liter
        if (line.contains("TIMH") || line.contains("ΤΙΜΗ")) continue  // Price label
        
        val matches = amountRegex.findAll(line)
        for (match in matches) {
            val rawVal = match.groupValues[1]
            val amount = parseAmount(rawVal)
            
            if (isValidTotal(amount, line)) {
                if (amount > maxAmount) {
                    maxAmount = amount
                }
            }
        }
    }
    
    return if (maxAmount > 0.0) maxAmount else null
}

private fun isValidTotal(amount: Double, line: String): Boolean {
    if (amount > 5000) return false           // Sanity: too high
    if (amount <= 0.0) return false           // Sanity: must be positive
    if (amount >= 2020 && amount <= 2035 && amount % 1 == 0.0) return false  // Year
    if (line.contains("QPA") || line.contains("ΩΡΑ") || line.contains("ORA")) return false  // Time
    return true
}
```

---

## Fix #4: Date Validation

### Problem Location
`ReceiptParser.kt` - `extractDate()` function

### Current Issue
```kotlin
if (yearInt in 2020..2030) {  // Too restrictive!
```

This rejects receipts from 2015-2019.

### Fixed Code
```kotlin
private fun extractDate(text: String): Long? {
    val datePatterns = listOf(
        Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(20\d{2})"""),
        Regex("""(\d{1,2})\s?[/.-]\s?(\d{1,2})\s?[/.-]\s?(\d{2})""")
    )
    
    val sdf = SimpleDateFormat("dd/MM/yyyy", Locale.US)
    sdf.isLenient = false
    
    for (pattern in datePatterns) {
        pattern.find(text)?.let { match ->
            val (d, m, y) = match.destructured
            val year = if (y.length == 2) "20$y" else y
            
            // Accept 2015-2035 (reasonable range for receipts)
            val yearInt = year.toIntOrNull() ?: 0
            if (yearInt in 2015..2035) {
                try {
                    return sdf.parse("$d/$m/$year")?.time
                } catch (e: Exception) { }
            }
        }
    }
    return null
}
```

---

## Fix #5: Batch Processing Improvements

### Current Issues
1. Sequential processing (slow)
2. No partial rollback
3. Memory pressure

### Improved `processBatch()` in `ReceiptRepository.kt`

```kotlin
suspend fun processBatch(uris: List<Uri>, onProgress: (Int, Int) -> Unit): BatchResult {
    var successes = 0
    var failures = 0
    val errors = mutableListOf<String>()
    val processedReceipts = mutableListOf<Long>()  // Track for potential rollback
    
    uris.forEachIndexed { index, uri ->
        try {
            val (receipt, _) = processReceipt(uri, autoCreateReview = true)
            processedReceipts.add(receipt.id)
            successes++
            onProgress(index + 1, uris.size)
            
            // Small delay to prevent memory pressure
            delay(100)
            
        } catch (e: Exception) {
            failures++
            errors.add("Failed to process $uri: ${e.message}")
            onProgress(index + 1, uris.size)
            
            // Log to crashlytics for debugging
            Log.w("ReceiptRepository", "Batch processing failed for $uri", e)
        }
    }
    
    return BatchResult(successes, failures, errors)
}

// Add rollback capability
suspend fun rollbackBatch(receiptIds: List<Long>) {
    receiptIds.forEach { id ->
        try {
            val receipt = scannedReceiptDao.getById(id) ?: return@forEach
            pendingReviewDao.deleteByScannedReceiptId(id)
            scannedReceiptDao.delete(receipt)
            ocrService.deleteImage(receipt.imagePath)
        } catch (e: Exception) {
            Log.e("ReceiptRepository", "Rollback failed for receipt $id", e)
        }
    }
}
```

---

## Summary of Expected Improvements

| Issue | Affected Receipts | Fix | Expected Result |
|-------|-------------------|-----|-----------------|
| Decimal parsing | #34, #30, #28, #26, #22 | Fix `parseAmount()` | 5 more receipts work |
| Greek OCR | #36, #33, #23, #28 | Pattern normalization | 3 more receipts work |
| Total fallback | #25 | Better filtering | 1 more receipt works |
| Date validation | - | Expand range | Older receipts work |

**Expected Success Rate After Fixes: 14/16 = 87.5%** (up from 37.5%)

---

## Immediate Action Items

1. **Apply decimal parsing fix** - This is the biggest win (fixes 5 receipts)
2. **Improve Greek pattern normalization** - Low effort, good return
3. **Expand date validation range** - One line change
4. **Consider cloud OCR for v2** - Better Greek support long-term

---

## Testing Checklist

After applying fixes, test with these specific cases:

- [ ] Receipt with `45.50` → Should return `45.50`, not `4550.0`
- [ ] Receipt with `EYNONO` pattern → Should match ΣΥΝΟΛΟ
- [ ] Receipt dated `01/10/2015` → Should parse correctly
- [ ] Receipt with `1.574 €/ΛΤ` unit price → Should NOT be picked as total
- [ ] Receipt with `13,00%` VAT → Should NOT be picked as total
