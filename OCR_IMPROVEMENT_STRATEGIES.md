# Improving Greek OCR with ML Kit (Free Strategies)

ML Kit's Latin text recognizer is famously problematic with Greek characters. It often hallucinates similar-looking English characters (e.g., seeing `Σ` as `E`, `Π` as `JI`, `ρ` as `p`). 

Because you want to keep the architecture completely **free** using ML Kit, we cannot rely on Google Cloud Vision API. Therefore, the parsing engine (`ReceiptParser.kt`) needs to become much smarter at *handling bad data* rather than expecting good data.

Here are highly actionable, completely free strategies to drastically improve parsing success rates on Greek receipts.

---

## 1. Aggressive Character Translation (The "Greeklish" Map)
**The Problem**: ML Kit frequently returns hybrid words like "E\^ETA" instead of "ΕΛΕΤΑ" (ELTA Courier) or "MA\O" instead of "ΜΑΣΟΥΤΗΣ".
Based on your `OCR_TEST_DOCUMENT (2).txt` analysis, ML Kit specifically fails on:
- **ΣΥΝΟΛΟ** becomes: `EYNONO`, `ZYNOAO`, `2YNONO`, `ZYNOIO`
- **ΠΛΗΡΩΤΕΟ** becomes: `NAHPQTEO`
- **ΠΟΣΟΤΗΤΑ** becomes: `noZOTHTA`
- **ΠΟΣΟ** becomes: `nozo`

**The Solution**: Build an aggressive pre-processing function that transliterates common ML Kit Greek hallucinations back into Greek characters *before* parsing.

```kotlin
// Exact Map based on your OCR_TEST_DOCUMENT (2).txt output
val exactHallucinationMap = mapOf(
    // Common individual character failures
    "E" to "Σ", "Z" to "Σ", "2" to "Σ", // Sigma
    "A" to "Λ", "1" to "Λ", // Lambda
    "N" to "Π", "n" to "Π", "TT" to "Π", // Pi
    "O" to "Ο", "Q" to "Ω", // Omega
    "H" to "Η", "I" to "Ι",
    // Common full-word failures from your receipts
    "ZYNOAO" to "ΣΥΝΟΛΟ",
    "EYNONO" to "ΣΥΝΟΛΟ",
    "2YNONO" to "ΣΥΝΟΛΟ",
    "ZYNOIO" to "ΣΥΝΟΛΟ",
    "NAHPQTEO" to "ΠΛΗΡΩΤΕΟ",
    "nozo" to "ΠΟΣΟ",
    "noZOTHTA" to "ΠΟΣΟΤΗΤΑ",
    "METPHTA" to "ΜΕΤΡΗΤΑ",
    "EYP9" to "ΕΥΡΩ"
)
```
*   **Implementation**: Create an `normalizeGreekOcr(rawText: String)` utility that runs this map across the text string, standardizing the OCR artifacts back to proper Greek anchors before the Regex engine looks for `ΣΥΝΟΛΟ` or `ΠΛΗΡΩΤΕΟ`.

## 2. Spatial Context (Bounding Boxes over Line Reading)
**The Problem**: Currently, `ReceiptParser` often relies on reading line-by-line (`\n`). Greek receipts are notoriously unaligned. The "Total" keyword might be on line 14, but the actual "15.50" amount is on line 15, shifted to the right.
**The Solution**: You are already collecting `TextBlock` bounding boxes, but you aren't using their spatial relationships effectively for the Total.
*   **Implementation**: Instead of looking for `line.contains("ΣΥΝΟΛΟ")`, find the `TextBlock` containing "ΣΥΝΟΛΟ" (or its garbled variant "EYNO/\\O"). 
*   Then, search the `blocks` list for any block whose `top` and `bottom` coordinates intersect horizontally with the "ΣΥΝΟΛΟ" block, and whose `left` coordinate is further to the right. That block is guaranteed to be your amount, regardless of how ML Kit ordered the text lines.

## 3. Fuzzy Keyword Matching (Levenshtein Distance)
**The Problem**: Regex like `Regex("(?i)(ΣΥΝΟΛΟ|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ)")` assumes perfect OCR. If ML Kit reads "ZYNO/\O", the regex fails, and the parser falls back to guessing the highest number on the receipt.
**The Solution**: Implement Levenshtein Distance (string similarity) for keyword searching.
*   **Implementation**: Don't use standard regex for anchors. Iterate through the lines and check string distance against your target keywords.
    *   Target: `ΣΥΝΟΛΟ`
    *   OCR returns: `ΣYNOAO`
    *   Distance = 1 (Very high match). 
*   Treat anything with a distance of ≤ 2 as a valid anchor for the Total or Tax fields.

## 4. The "Known Merchant" Anchor Strategy
**The Problem**: Extracting the merchant name from a blurry header is the hardest part of ML Kit Greek OCR. 
**The Solution**: Use the user's existing database (the Room `MerchantCanonical` table and `CategorizationEngine`) to brute-force the merchant.
*   **Implementation**: Don't just try to read the top 3 lines. Take the *entire* raw OCR text. Run a rapid fuzzy-search against the user's top 50 most frequently visited merchants.
*   If your dictionary has "ΣΚΛΑΒΕΝΙΤΗΣ" (Sklavenitis) and you find "EK/ABE" anywhere in the text block, **bypass the header extraction entirely** and immediately assign the merchant as "ΣΚΛΑΒΕΝΙΤΗΣ". 
*   Because Greek receipts from major chains use identical layouts nationally, once you identify the merchant, you can use a parser specific to that merchant (e.g., Sklavenitis receipts *always* have the total at the very bottom preceded by \*\*\*).

## 5. Exclude the "AFM" (Tax ID) Noise
**The Problem**: Greek receipts prominently display a 9-digit ΑΦΜ (Tax ID). ML Kit often misinterprets this as an amount or phone number, throwing off the parser.
**The Solution**: 
*   **Implementation**: Add a strict regex pass to instantly wipe any 9-digit integer sequence (and the word "ΑΦΜ" or "A.Φ.M.") from the `rawText` before attempting to find the Total amount. `Regex("""\b\d{9}\b""")`.

## 6. Physical Constraints & Print Artifacts (From your `images.pdf`)
**The Problem**: Analyzing your actual photos reveals that receipts are often folded, faded, or use multi-lingual formats that break standard Greek OCR rules.
*   **Latin Intrusion**: Many Greek Point-of-Sale machines print "ΠΟ**S**Ο/AMOUNT" instead of "ΠΟΣΟ".
*   **The "Arrow" Artifact**: Many terminals print an arrow or dash pointing to the total (e.g., `ΣΥΝΟΛΟ  ▶   € 9,50` or `ΣΥΝΟΛΟ  >   € 6,80`). ML Kit often reads this as a random character (`}`, `>`, `?`), breaking regex bounds.
*   **Extreme Spacing**: The word `ΣΥΝΟΛΟ` is on the far left, while `45,50` is on the far right. If the paper is folded or photographed at an angle, ML Kit might assign them to *different* lines entirely.
**The Solution**: 
*   **Implementation**: Add the Latin "S" to the hallucination map when combined with words (`ΠΟSΟ` -> `ΠΟΣΟ`).
*   **Implementation**: Strip all non-alphanumeric geometric characters (`>`,`<`,`▶`,`}`,`|`) from the raw line string before searching for the amount, guaranteeing that `ΣΥΝΟΛΟ   >    € 6,80` safely collapses to `ΣΥΝΟΛΟ € 6,80`.
*   **Reiteration**: This proves why **Strategy #2 (Spatial Bounding Boxes)** is absolutely necessary. You cannot rely on line-breaks (`\n`) for folded paper.

## Summary of Actionable Plan
1.  **Stop relying on perfect Regex**. Introduce `StringDistanceUtils.levenshtein()` for keyword searching (`ΣΥΝΟΛΟ`, `ΜΕΤΡΗΤΑ`).
2.  **Use Spatial Bounding Boxes**. Find the "Total" keyword block, then shoot a horizontal ray to the right to find the amount block, ignoring line breaks entirely.
3.  **Reverse Merchant Lookup**. Instead of reading the header to find the merchant, use the raw text to fuzzy-match against the user's known merchant dictionary.
4.  **Geometry Stripping**: Aggressively strip random geometric artifacts (`>`) and handle Latin `S` intrusions in Greek words.
