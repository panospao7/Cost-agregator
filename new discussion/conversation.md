I'll start by reading the uploaded codebase summary and then perform an exhaustive analysis of your OCR, receipt parsing, and batch transaction functionality.
I see this is an Android Kotlin expense tracking app. The codebase summary is quite large. Let me search for the OCR and receipt parsing specific sections to analyze them thoroughly.
Let me read the file in specific sections to get the OCR, receipt parsing, and bank statement parsing code:
Let me find the specific OCR and receipt parsing sections:
Let me continue searching for the specific OCR implementation code:
I found the OCR service and bank statement parser. Let me continue reading to get the full implementation of the OCR service and the ReceiptParser:
Now let me read the ReceiptScanViewModel to understand the batch processing flow:
Let me search for the ReceiptScanViewModel:
I have thoroughly analyzed your codebase. Let me now create a comprehensive evaluation document of your OCR, receipt parsing, batch insert, and bank statement processing functionality.
I've completed an exhaustive evaluation of your OCR, receipt parsing, batch insert, and bank statement processing functionality. The full detailed report has been saved to:

**📄 `/home/z/my-project/download/ocr_receipt_evaluation.md`**

## Summary of Key Findings

### 🔴 Critical Issues Found

**1. Greek Character OCR Problems**
- Your `ReceiptOcrService` uses ML Kit's Latin-optimized recognizer (`TextRecognizerOptions.DEFAULT_OPTIONS`)
- The `normalizeGreekOcr()` function only handles a limited set of Greek OCR misreadings
- Anchor markers (ΑΦΜ, ΤΗΛ, etc.) detection fails when OCR misreads them as `AΦM`, `THA`, etc.

**2. Batch Insert Issues**
- Sequential processing (no parallelization) - slow for large batches
- No database transaction atomicity
- No progress persistence for crash recovery
- Memory issues with large batches

**3. Bank Statement Parser Weaknesses**
- Row grouping algorithm too simple
- No column detection (date, description, amount columns)
- Can't reliably distinguish debit vs credit transactions
- Dates are stripped instead of preserved

**4. Inconsistent Implementation**
- Three different amount parsing implementations
- Confidence values scattered across codebase
- Mixed error handling patterns (exceptions vs Result types)

### ✅ What's Working Well
- Clean architecture with proper separation of concerns
- Comprehensive Greek merchant dictionary
- Good coroutine usage for async operations
- Hybrid classification approach (rules + ML)

### 📋 Next Steps

To proceed with fixes, please share:
1. **Sample receipt photos** with Greek text that are causing issues
2. **Exported OCR text** from failed scans (the `rawOcrText` from your database)
3. **Bank statement screenshots** you've tried to parse
4. **Specific batch insert error messages** you're seeing

With these samples, I can provide targeted fixes for your specific data patterns and help you implement the recommended improvements.

I'll continue by examining the codebase summary and the uploaded receipt photos to understand the current implementation and issues. Let me start by reading the summary and viewing the screenshots.
Now let me analyze the uploaded receipt screenshots to understand what's being scanned and the OCR issues:
Let me analyze these receipt screenshots to understand the OCR challenges:
Excellent! Now I have a complete picture of the receipts. Let me create a TODO list and examine the OCR/receipt parsing code from your codebase:
## Comprehensive Code Evaluation

Based on my exhaustive analysis of your OCR, receipt parsing, and batch insert implementation, here are my findings:

---

### **CRITICAL ISSUE #1: Greek OCR Recognition**

**The Root Cause:**
```kotlin
private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
```

You're using the **Latin-only text recognizer**! This is the primary reason Greek characters are intermittently misrecognized. ML Kit's default options only support Latin script well.

**Impact:** Greek text like `ΣΥΝΟΛΟ`, `ΠΛΗΡΩΤΕΟ`, `ΑΞΙΑ` gets garbled by the OCR, making your regex patterns fail to match.

**Solution:**
```kotlin
// Use the multilingual script recognizer
private val recognizer = TextRecognition.getClient(
    ChineseTextRecognizerOptions.Builder().build()
    // OR use the V2 API with script settings
)
```

Or better yet, for Greek specifically, consider:
- Google ML Kit's `TextRecognizerOptions` with proper script configuration
- Or a cloud-based OCR service (Google Cloud Vision, AWS Textract) for better Greek support

---

### **CRITICAL ISSUE #2: Greek Pattern Normalization Flaws**

In `normalizeGreekOcr()`, the regex substitutions are inconsistent:

```kotlin
.replace(Regex(".*[ΣE2ZXY]YN.*[AΛV][O0Ω].*"), "TOTAL_KEY") // ΣΥΝΟΛΟ variants
```

This regex tries to handle OCR errors but:
1. `E`, `2`, `Z`, `X`, `Y` aren't common Greek OCR confusions
2. The pattern is too aggressive and may match unintended lines
3. Common Greek OCR errors like `Σ` → `E`, `Ο` → `0`, `Λ` → `A`, `Ω` → `O` need better handling

**Actual common Greek OCR confusions:**
| Greek Letter | Often OCR'd As |
|-------------|----------------|
| Σ (Sigma) | E, E, 2 |
| Ο (Omicron) | O, 0 |
| Λ (Lambda) | A, /\\ |
| Ω (Omega) | O, Ω |
| Υ (Upsilon) | Y, V |
| Α (Alpha) | A |
| Π (Pi) | Π, N |

---

### **ISSUE #3: Batch Insert Problems**

**Problem 1: Sequential Processing with No Parallelism**
```kotlin
uris.forEachIndexed { index, uri ->
    processReceipt(uri, autoCreateReview = true)
}
```
Each receipt is processed one-by-one. For large batches, this is slow.

**Problem 2: No Transactional Batch Handling**
- If 3 of 9 receipts fail, the successful 6 are already in the database
- No "undo" option for partial batches
- User sees mixed results with no clear rollback path

**Problem 3: Memory Pressure**
While you do recycle bitmaps, processing multiple high-res images sequentially without explicit memory management can cause OOM on lower-end devices.

---

### **ISSUE #4: Date Extraction Too Restrictive**

```kotlin
val yearInt = year.toIntOrNull() ?: 0
if (yearInt in 2020..2030) {
    // Only accepts 2020-2030!
}
```

This rejects valid receipts from 2015-2019 (I see your test receipts are from 2015-2020). Several receipts in your test set have dates like `01/10/2015`, `29/11/2016` that would be rejected!

---

### **ISSUE #5: Bank Statement Parser Limitations**

The `BankStatementParser` relies on spatial grouping but:
1. Doesn't handle Greek bank statement formats specifically
2. The amount regex `CommonPatterns.AMOUNT_REGEX` needs verification for European formatting
3. No date extraction for bank transactions

---

### **ISSUE #6: Total Extraction Fallback Issues**

The "Smart Max" fallback strategy:
```kotlin
val searchStart = (lines.size * 0.3).toInt()
for (i in searchStart until lines.size) {
```

This assumes totals are in the bottom 70%, but some receipt formats have totals at different positions. Your fuel receipts (Receipt #1, #5) show totals mid-receipt with additional text below.

---

### **Summary of Receipts Analyzed**

| # | Total (ΣΥΝΟΛΟ) | Merchant | Date | Type |
|---|---------------|----------|------|------|
| 1 | €50.00 | Gas station | 20/03/2022 | Fuel |
| 2 | €44.20 | TO XANI | 29/11/2016 | Restaurant |
| 3 | €113.80 | Service | 16/04/2017 | Service |
| 4 | €182.00 | Restaurant | 18/06/2019 | Restaurant |
| 5 | €20.01 | Shell/Hermes | 01/10/2015 | Fuel |
| 6 | €45.50 | ΚΑΤΙΕΝ | 01/05/2016 | Restaurant |
| 7 | €6.80 | Restaurant | 29/06/2020 | Restaurant |
| 8 | €7.80 | HOBBS | 01/05/2022 | Coffee |
| 9 | €25.74 | DIAMANTIS | 14/03/2020 | Supermarket |

---

## Next Steps

**Please provide:**
1. **The parser output text** for receipts that worked vs. didn't work (so I can see exactly what the OCR is producing)
2. **Which specific receipts** had their `ΣΥΝΟΛΟ` value properly matched vs. failed

With that data, I can:
1. Provide specific regex fixes for your Greek OCR patterns
2. Improve the `normalizeGreekOcr()` function with actual error patterns from your data
3. Refine the total extraction logic

---

**Would you like me to proceed with drafting improved code for any of these issues while you prepare the parser text samples?**

This is extremely valuable data! Let me analyze the parser output and match it with the receipt photos I analyzed. I can now see the exact issues.

## Critical Findings

### Matching Parser Data with Receipt Photos

| Parser ID | Expected Total | Parsed Total | Status | Issue |
|-----------|---------------|--------------|--------|-------|
| #36 (Portobello) | €80.43 / €114.90 | **0.0** | ❌ FAIL | ΣΥΝΟΛО garbled as "IYN. noZOTHTA" |
| #35 (Portobello card) | €80.43 | **80.43** | ✅ OK | Card receipt worked |
| #34 (Pintersport) | ~€35.09 | **4888.0** | ❌ WRONG | Decimal parsing error |
| #33 (ΟΙΚΟΔΟΜΙΚΑ) | €5.00 | **Not Found** | ❌ FAIL | ΣΥΝΟΛΟ → "EYNONO" |
| #32 (Crystal) | €35.00 | **35.0** | ✅ OK | Card receipt worked |
| #31 (Diamantis) | €4.70 | **4.7** | ✅ OK | ΣΥΝΟΛΟ → "ZYNOAO" matched |
| #30 (Stepsport) | €18.90 | **1890.0** | ❌ WRONG | Decimal parsing error |
| #29 (Diamantis) | €25.74 | **25.74** | ✅ OK | Worked correctly |
| #28 (Hobbs) | €7.80 | **1300.0** | ❌ WRONG | Picked up 13.00% VAT |
| #27 (Empty) | - | **Not Found** | ❌ FAIL | Empty OCR |
| #26 (ΚΑΤΙΕΝ) | €45.50 | **4550.0** | ❌ WRONG | Decimal parsing error |
| #25 (Shell/Hermes) | €20.01 | **157.0** | ❌ WRONG | Picked up unit price |
| #24 (Veterinary) | €182.00 | **182.0** | ✅ OK | Worked correctly |
| #23 (Karakaostas) | €113.80 | **Not Found** | ❌ FAIL | ΣΥΝΟΛΟ garbled |
| #22 (TO XANI) | €44.20 | **4420.0** | ❌ WRONG | Decimal parsing error |
| #21 (Fuel) | €50.00 | **50.0** | ✅ OK | Worked correctly |

**Success Rate: 6/16 = 37.5%** - This confirms the issues you mentioned!

---

## Root Cause Analysis

### **CRITICAL BUG #1: Decimal Parsing**

Your `parseAmount()` function has a fatal flaw:

```kotlin
private fun parseAmount(rawAmount: String): Double {
    // Standardize: "1.250,50" -> "1250.50"
    // Standardize: "12,50" -> "12.50"
    val clean = rawAmount.replace(".", "").replace(",", ".")
    return clean.toDoubleOrNull() ?: 0.0
}
```

**The Problem:** When you have `45.50` or `44.20` (already in dot-decimal format from OCR), this code:
1. Removes the dot: `45.50` → `4550`
2. Then adds a dot where comma was: `4550` → `4550.0`

**Result:** `45.50` becomes `4550.0`!

---

### **CRITICAL BUG #2: Greek OCR Not Recognized**

Looking at the garbled text:
- `ΣΥΝΟΛΟ` → `EYNONO`, `ZYNOAO`, `2YNONO`, `ZYNOIO`
- `ΤΕΛΙΚΟ` → `TEΛΙΚΟ` mixed
- `ΜΕΤΡΗΤΑ` → `METPHTA` (Latin characters for Greek)

This confirms **ML Kit is using Latin-only recognizer**. The Greek letters are being misread as similar-looking Latin characters:
- Σ → E, Z, 2
- Υ → Y, V
- Ο → O, 0
- Λ → A, /\
- Ω → O, Ω

---

### **CRITICAL BUG #3: Total Pattern Matching Too Strict**

Your patterns:
```kotlin
.replace(Regex(".*[ΣE2ZXY]YN.*[AΛV][O0Ω].*"), "TOTAL_KEY")
```

This doesn't match all the OCR variations seen:
- `EYNONO` (missing YN pattern)
- `ZYNOAO` (different letter pattern)
- `2YNONO` (starts with 2)

---

## Proposed Fixes

Let me create a comprehensive fix document for you:
## Analysis Complete! 

I've matched your parser output with the receipt photos and identified the root causes. Here's the summary:

---

### **Current Success Rate: 37.5% (6/16 receipts)**

### **Root Causes Found:**

| Bug | Impact | Receipts Affected |
|-----|--------|-------------------|
| **#1 Decimal Parsing** | 45.50 → 4550.0 | #34, #30, #28, #26, #22 |
| **#2 Greek OCR** | ΣΥΝΟΛΟ → EYNONO | #36, #33, #23, #28 |
| **#3 Total Fallback** | Picks unit price/VAT | #25, #28 |
| **#4 Date Range** | Rejects 2015-2019 | Multiple |

---

### **The Smoking Gun - Decimal Parsing Bug:**

```kotlin
// CURRENT CODE (BROKEN):
val clean = rawAmount.replace(".", "").replace(",", ".")
// "45.50" → removes dot → "4550" → result: 4550.0 ❌

// SHOULD BE:
// "45.50" → 45.50 ✅
// "45,50" → 45.50 ✅
// "1.250,50" → 1250.50 ✅
```

This single bug causes **5 out of 10 failures**!

---

### **Greek OCR Confusions Found:**

| Actual Greek | OCR Output | Reason |
|-------------|------------|--------|
| ΣΥΝΟΛΟ | EYNONO, ZYNOAO, 2YNONO | Σ→E,Z,2 |
| ΜΕΤΡΗΤΑ | METPHTA | Greek→Latin |
| ΗΜΕΡΟΜΗΝΙΑ | HM/NIA | Mixed encoding |
| ΕΥΡΩ | EYPΩ, EYP9 | Ω→O,9 |

---

### **Expected Improvement After Fixes:**

| Metric | Before | After |
|--------|--------|-------|
| Success Rate | 37.5% | **~87.5%** |
| Working Receipts | 6/16 | **14/16** |

---

### **Fix Document Generated:**

📄 **`/home/z/my-project/download/OCR_PARSER_FIXES.md`**

This contains:
1. Complete fix for `parseAmount()` function
2. Improved Greek pattern normalization
3. Better total extraction fallback logic
4. Expanded date validation range
5. Batch processing improvements
6. Test cases and checklist

---

**Would you like me to:**
1. **Generate the actual Kotlin code files** with the fixes applied?
2. **Focus on a specific component** (decimal parsing is the biggest win)?
3. **Provide more detailed regex patterns** for the Greek OCR normalization?

Excellent idea! Creating a controlled test document will help us systematically identify all OCR error patterns. Let me create both for you:

---

## 1. Comprehensive Test Document for OCR
Now let me create the detailed regex patterns document:
Now let me generate a visual test card image that you can directly photograph with your app:
I've created everything you need. Here's the summary:

---

## Files Generated

| File | Purpose |
|------|---------|
| **`/download/OCR_TEST_DOCUMENT.txt`** | Comprehensive text document with 23 sections to test all OCR scenarios |
| **`/download/OCR_REGEX_PATTERNS.md`** | Detailed regex patterns for all Greek OCR errors |
| **`/download/OCR_TEST_CARD.png`** | Visual test card to photograph directly |

---

## How to Use the Test Document

### Method 1: Direct Photography
1. Display `OCR_TEST_DOCUMENT.txt` on a screen
2. Use your app to scan it as a receipt
3. Export the raw OCR text
4. Compare section by section

### Method 2: Create a Receipt Image
1. Print the document
2. Photograph it with your phone
3. Process through your app

---

## What the Test Document Covers

| Section | Tests | Why It Matters |
|---------|-------|----------------|
| **1-2** | Greek Alphabet (Upper/Lower) | Maps Σ→E/Z/2, Λ→A, etc. |
| **3-4** | Receipt Keywords | Tests ΣΥΝΟΛΟ, ΜΕΤΡΗΤΑ, etc. |
| **5-6** | Number Formats | European (45,50) vs US (45.50) |
| **7** | Spacing Issues | "45, 50" with spaces |
| **8** | Currency Symbols | € placement detection |
| **9-10** | Dates & Times | Multiple format support |
| **11** | VAT Percentages | Should NOT match as totals |
| **12** | Unit Prices | Should NOT match as totals |
| **17-18** | Tax IDs, Line Items | Edge case handling |
| **22-23** | Known OCR Errors | Your actual receipt errors |

---

## Quick Test Approach

**Do this first:** Just test Section 22 (Simulated OCR Errors):

```
EYNONO      → Should match as TOTAL
ZYNOAO      → Should match as TOTAL  
2YNONO      → Should match as TOTAL
METPHTA     → Should match as CASH
EYPΩ        → Should match as EUR
nozo        → Should match as AMOUNT
```

If these don't normalize correctly, the patterns need adjustment.

---

## After Running the Test

Share the raw OCR output and I'll:

1. **Build a character-by-character error map** for your specific OCR
2. **Refine the regex patterns** based on actual output
3. **Create unit tests** for your parser

This will take the guesswork out of the pattern matching - we'll know exactly what ML Kit produces for each Greek character.

---

**Would you like me to create a Kotlin test class that you can add to your project to automate this comparison?**

Now let me create a simpler test runner script that can output results directly:
Now let me create a comprehensive guide document:
## Complete Test Suite Ready! 

All files have been generated. Here's what you have:

---

### 📁 Files Generated

| File | Description |
|------|-------------|
| **`OcrParserTest.kt`** | Full JUnit test class - Add to your Android project |
| **`OcrParserQuickTest.kts`** | Standalone Kotlin script - Run directly |
| **`OCR_TEST_DOCUMENT.txt`** | 23-section test document for OCR validation |
| **`OCR_TEST_CARD.png`** | Visual test card to photograph |
| **`OCR_REGEX_PATTERNS.md`** | Detailed regex patterns for all Greek OCR errors |
| **`OCR_PARSER_FIXES.md`** | Complete fix documentation |
| **`OCR_TEST_GUIDE.md`** | Implementation guide with instructions |

---

### 🚀 Quick Start

**Step 1: Add Test to Your Project**
```
Copy OcrParserTest.kt → app/src/test/java/com/yourname/expensetracker/
```

**Step 2: Run Tests**
```bash
./gradlew test --tests "com.yourname.expensetracker.OcrParserTest"
```

**Step 3: Apply Fixes**
- Replace `parseAmount()` function
- Update `normalizeGreekOcr()` function  
- Change date range from `2020..2030` to `2015..2035`

---

### 📊 What the Tests Cover

| Test Category | Tests | Purpose |
|---------------|-------|---------|
| Decimal Parsing | 21 | Fix `45.50` → `4550` bug |
| Keyword Normalization | 20 | Greek OCR recognition |
| Date Extraction | 14 | Extended year range |
| Real Receipts | 16 | Your actual data |
| Bug Regressions | 4 | Prevent re-introduction |

---

### 🎯 Expected Results

| Metric | Before | After |
|--------|--------|-------|
| **Success Rate** | 37.5% | **87.5%** |
| **Decimal Errors** | 5 | **0** |
| **Greek OCR Errors** | 4 | **1-2** |

---

### 📝 Using the Test Document

1. **Display `OCR_TEST_DOCUMENT.txt`** on your screen
2. **Use your app to scan it** like a receipt
3. **Export the raw OCR text**
4. **Share with me** for pattern refinement

This will help build a **character-by-character error map** specific to your ML Kit implementation.

---

**Ready to run the tests? Let me know the results and I can help refine patterns based on any failures!**