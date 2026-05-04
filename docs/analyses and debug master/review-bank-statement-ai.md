# Bank Statement AI Parsing — Deep Architecture Review

**Date:** 2026-05-04  
**Reviewer:** Senior Architect  
**Scope:** Bank statement OCR → deterministic parser → AI validation pipeline  
**Files Reviewed:** 9 core files + 5 supporting files  
**DB Version:** v113

---

## VERDICT: FAIL

Three CRITICAL issues require immediate remediation before production use:

---

## CRITICAL ISSUES

### [ISSUE-1] [CRITICAL] CloudReceiptAssistService.suggestFromText bypasses privacy gate entirely

- **File:** `data/ai/provider/CloudReceiptAssistService.kt` (lines 216–284)
- **Impact:** Raw OCR text containing bank transactions (merchants, amounts, dates) can be sent to the cloud without any privacy-gate check when `suggestFromText` is called — either from the current use case (whose gate check happens at a higher layer, which is fragile) or from any future caller that omits the check.
- **Root cause:** The `suggest` method (line 91) calls `privacyGate.check(PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST, …)` and returns `Failure` on denial. The `suggestFromText` method (line 216) has **no gate check at all** — it only checks `apiKey.isBlank()`.
- **Evidence:** Compare `CloudReceiptAssistService.kt` line 91–99 (`suggest`) against line 216–219 (`suggestFromText`). The gate check is completely absent from the text-only path.
- **Suggested fix:**
  ```kotlin
  suspend fun suggestFromText(prompt: String): AiServiceResult<String> {
      if (apiKey.isBlank()) { … }
      // ADD gate check:
      val gateDecision = privacyGate.check(
          PrivacyCapability.CLOUD_AI_RECEIPT_ASSIST,
          mapOf("caller" to "suggestFromText")
      )
      if (gateDecision is PrivacyDecision.Denied) {
          return AiServiceResult.Failure(AiServiceError.Disabled(gateDecision.reason))
      }
      // … rest of method
  }
  ```
  Also add `CLOUD_AI_BANK_STATEMENT` as a secondary check so the cloud layer is self-defending regardless of which use case calls it.

### [ISSUE-2] [CRITICAL] `resolveHomeCurrency()` uses `runBlocking` from a synchronous `parse()` default parameter

- **File:** `domain/receipt/BankStatementParser.kt` (lines 106–121, 133)
- **Impact:** The `parse()` function is not a `suspend` function, but its default parameter `homeCurrency` calls `resolveHomeCurrency()` which uses `runBlocking`. If `parse()` is called from a coroutine on the main thread (or any thread that shouldn't block), it can cause an ANR or thread-starvation. The call site in `BankStatementLifecycleProcessor.processBankStatement` (line 117) is already inside a `suspend` function but the default parameter evaluation happens **synchronously before the function body runs**, so it blocks the coroutine thread.
- **Evidence:** `BankStatementParser.kt` line 108: `runBlocking { currencySettingsRepository.homeCurrency().first() }`.
- **Suggested fix:** Make `parse()` a `suspend` function and call `currencySettingsRepository.homeCurrency().first()` directly without `runBlocking`:
  ```kotlin
  suspend fun parse(blocks: List<TextBlock>, homeCurrency: String? = null): List<ParsedTransaction> {
      val resolvedCurrency = homeCurrency ?: resolveHomeCurrencySuspend()
      // …
  }
  private suspend fun resolveHomeCurrencySuspend(): String {
      return runCatching {
          val currency = currencySettingsRepository.homeCurrency().first()
          currency.ifBlank { "EUR".also { Timber.w(…) } }
      }.getOrElse { … }
  }
  ```
  All callers must then call `parse()` from a coroutine context (they already do, in `BankStatementLifecycleProcessor`).

### [ISSUE-3] [CRITICAL] AI validation pipeline is implemented but NOT wired into the lifecycle processor

- **File:** `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` (lines 69–71, 129–143)
- **Impact:** `ValidateBankStatementTransactionsUseCase` is fully implemented and ready, but `BankStatementLifecycleProcessor` has **commented-out TODO injection** and a hardcoded fallback: `val validationSources: Map<Int, String> = parsedTransactions.indices.associateWith { "PARSER_ONLY" }`. This means the entire on-device→cloud→parser fallback chain, prompt building, and JSON parsing code is dead code that is never called. No transaction will ever be AI-validated or AI-corrected.
- **Evidence:** `BankStatementLifecycleProcessor.kt` lines 69–71:
  ```kotlin
  // TODO: Inject ValidateBankStatementTransactionsUseCase for AI validation
  // TODO:   private val transactionValidator: ValidateBankStatementTransactionsUseCase
  ```
  Lines 129–143 show the TODO body and the hardcoded fallback.
- **Suggested fix:**
  1. Add the injection:
     ```kotlin
     private val transactionValidator: ValidateBankStatementTransactionsUseCase
     ```
  2. Wire the call between Step 2 and Step 3 (around line 143):
     ```kotlin
     val debugTransactions = parsedTransactions.map { DebugTransaction.fromParsedTransaction(it) }
     val validatedTransactions = transactionValidator.validateTransactions(
         rawOcrText = ocrResult.fullText,
         candidateTransactions = debugTransactions,
         homeCurrency = parsedTransactions.firstOrNull()?.currency ?: "EUR"
     )
     val validationSources = validatedTransactions.mapIndexed { i, tx -> i to tx.source }.toMap()
     ```
  3. Use `validatedTransactions` instead of `parsedTransactions` in the downstream `PendingReview` creation loop (Step 5).

---

## MAJOR ISSUES

### [ISSUE-4] [MAJOR] `SmartReceiptAssistService.suggestFromText` tries cloud first, contradicting the use case's on-device-first design

- **File:** `data/ai/provider/SmartReceiptAssistService.kt` (lines 118–128)
- **Impact:** `ValidateBankStatementTransactionsUseCase` first tries `onDeviceReceiptAssist.suggestFromText(prompt)` directly. Only when that fails does it call `smartReceiptAssist.suggestFromText(prompt)` — but `SmartReceiptAssistService.suggestFromText` tries **cloud first**, then on-device. This means:
  - On-device is attempted **twice** (once directly, once as cloud fallback inside SmartReceiptAssist) — wasteful but not harmful.
  - The semantic intent of the use case (on-device FIRST, cloud ONLY as fallback) is partially undermined because the `SmartReceiptAssist` layer may call cloud even though the use case already got an on-device failure. The privacy gate check prevents actual cloud data leakage, but the architecture is misaligned.
- **Suggested fix:** Either:
  - (A) Have `SmartReceiptAssistService.suggestFromText` try on-device first, then cloud, matching the use case's intent, OR
  - (B) Remove the direct on-device call from `ValidateBankStatementTransactionsUseCase` and let `SmartReceiptAssistService.suggestFromText` handle the full fallback chain (adding the privacy gate inside it).

### [ISSUE-5] [MAJOR] `parseAiResponse` does NOT handle AI returning a JSON object instead of array; also lacks response-correlation with candidates

- **File:** `domain/ai/usecase/ValidateBankStatementTransactionsUseCase.kt` (lines 174–222)
- **Impact:**
  1. Many AI models wrap arrays in objects like `{"transactions": […]}`. If the AI returns this format, `JSONArray(cleanJson)` throws `JSONException` and the entire AI response is discarded — the pipeline falls through to PARSER_ONLY silently.
  2. The AI could hallucinate entirely new transactions unrelated to any candidate. The parser does not verify that AI-returned transactions correspond to candidates (by index or by approximate merchant/amount matching).
- **Evidence:** Line 184: `val jsonArray = JSONArray(cleanJson)` — no handling for `JSONObject` responses.
- **Suggested fix:**
  ```kotlin
  val jsonArray = try {
      JSONArray(cleanJson)
  } catch (e: JSONException) {
      // Try unwrapping from a JSON object
      JSONObject(cleanJson).optJSONArray("transactions")
          ?: JSONObject(cleanJson).optJSONArray("results")
          ?: return null
  }
  ```
  For candidate correlation, add an optional index field in the prompt (`$i: merchant=…`) and have the AI return it, or do a post-hoc best-effort matching by merchant substring.

### [ISSUE-6] [MAJOR] AI source is always `"AI_VALIDATED"`, never `"AI_CORRECTED"` — documented capability is unimplemented

- **File:** `domain/ai/usecase/ValidateBankStatementTransactionsUseCase.kt` (line 212)
- **Impact:** The `CleanTransaction.source` field is documented as accepting `"AI_CORRECTED"` (meaning: the AI changed the merchant, amount, or date vs. the parser candidate), but the code always sets it to `"AI_VALIDATED"` regardless of whether the AI actually corrected anything. Users and debug output cannot distinguish between "AI agreed with parser" and "AI fixed a mistake."
- **Evidence:** Line 212: `source = "AI_VALIDATED"` — no comparison against candidates.
- **Suggested fix:** After parsing the AI response, compute the source per transaction:
  ```kotlin
  val source = if (merchant != candidate.merchant || 
                    abs(amount - candidate.amount) > 0.001 ||
                    currency != candidate.currency ||
                    date != candidate.date) "AI_CORRECTED"
               else "AI_VALIDATED"
  ```

### [ISSUE-7] [MAJOR] `preFilterRows` line 293 filters all lines with zero letters — could drop legitimate transactions with numeric-only descriptions

- **File:** `domain/receipt/BankStatementParser.kt` (line 293)
- **Impact:** If a bank statement row contains only dates, numbers, and symbols (e.g., `"15/03/2025 705 040 12345 -456.78"` where a merchant name was somehow not captured by OCR), the entire row is silently dropped. In practice this is unlikely because merchant names contain letters, but an OCR failure or a pure-reference-number transaction could be lost.
- **Evidence:** `if (!line.any { it.isLetter() }) continue`
- **Suggested fix:** Add a weaker guard — require at least one recognized pattern (date OR amount OR known symbol like X/Π) rather than strictly requiring a letter:
  ```kotlin
  val hasDateOrAmount = hasDatePattern || hasAmount
  if (!line.any { it.isLetter() } && !hasDateOrAmount) continue
  ```

### [ISSUE-8] [MAJOR] `tryParseRevolutTransaction` regex `Reference\s+.*` never matches due to word-boundary anchoring

- **File:** `domain/receipt/BankStatementParser.kt` (line 465)
- **Impact:** The regex `(?i)\b(To:|From:|Card:|Reference:|Reference\s+.*|Fee:.*)\b` has a `\b` at the end that follows `.*`, which means the word boundary check applies after any characters that `.*` matched — and `.*` can eat the entire remainder of the string, so `\b` will only match if the string ends with a word character and the next character (past the string end) is a non-word character (always true at end-of-string). But if the input is `"Reference: 12345"`, the `.*` matches ` 12345`, and `\b` at position after `5` checks if `5` is a word character and the character after (none) is a non-word character → true. However, the alternation `Reference:|Reference\s+.*` means `Reference:` matches first (greedy alternation order), so the second branch is unreachable. Actually the alternation `Reference:|Reference\s+.*` — the regex engine tries `Reference:` first. If the text is `"Reference: ABC"`, it matches `"Reference:"` (colon is not a word char, `\b` fails because `:` follows `e` and `\b` looks for a word/non-word boundary — `e` is word, `:` is non-word, so `\b` succeeds!). Wait, `\b` is at the END of the alternation. Let me re-analyze:

  The full pattern: `\b(To:|From:|Card:|Reference:|Reference\s+.*|Fee:.*)\b`

  For `"Reference: ABC"`:
  - Alternation `Reference:` matches `"Reference:"`.
  - Then `\b` checks: is the position after `:` a word boundary? The character after `:` is `" "` (space). `:` is non-word, space is non-word → not a boundary. So `\b` FAILS.
  - The engine backtracks and tries `Reference\s+.*` which matches `"Reference: ABC"`. Then `\b` checks: after `C` at end of string. `C` is word, end-of-string is non-word → boundary. SUCCESS.

  Actually, the regex would work for `Reference: ABC` via the second alternation branch. But for `"Reference:"` alone (with nothing after), the first branch `Reference:` would match, then `\b` would check if there's a word boundary after `:`. If there's nothing else, it's end-of-string, `:` is non-word, end-of-string is non-word → NOT a boundary → FAIL. Then it tries `Reference\s+.*` which requires at least one space and some characters → can't match `"Reference:"` alone.

  So the regex has subtle behavior but isn't entirely broken. Still, the `\b` anchors at both ends interacting with alternation branches is fragile and unintentional.

- **Suggested fix:** Simplify to remove the word-boundary dependency:
  ```kotlin
  Regex("""(?i)\b(To|From|Card|Reference|Fee)\s*:?\s*.*""")
  ```
  Or make it a list of `replace` calls for clarity.

### [ISSUE-9] [MAJOR] `DebugDataStorage.parseDebugDataFromJson` does NOT restore `validationSources` — AI source info lost on reload

- **File:** `ui/screens/debug/DebugDataStorage.kt` (lines 129–136)
- **Impact:** When the user views saved debug data after app restart, the `validationSource` per transaction is always `"PARSER_ONLY"` because `DebugDataStorage` constructs `DebugData` without passing `validationSources` (defaults to `emptyMap()`). This means the user cannot see which transactions were AI-validated in persistent debug data, even though `DebugData.toJson()` correctly serializes the field.
- **Evidence:** Line 129: `DebugData(rawText = …, parsedTransactions = …, …)` — no `validationSources` argument.
- **Suggested fix:** Parse `validationSource` from each transaction object during deserialization and reconstruct the map:
  ```kotlin
  val validationSources = mutableMapOf<Int, String>()
  for (i in 0 until transactionsArray.length()) {
      val txObj = transactionsArray.getJSONObject(i)
      val vs = txObj.optString("validationSource", "PARSER_ONLY")
      if (vs != "PARSER_ONLY") validationSources[i] = vs
      // … rest of parsing
  }
  DebugData(…, validationSources = validationSources)
  ```

---

## MINOR ISSUES

### [ISSUE-10] [MINOR] HEADER_KEYWORDS set missing some Greek bank statement variants

- **File:** `domain/receipt/BankStatementParser.kt` (lines 40–50)
- **Impact:** Some NBG statement headers may pass through the pre-filter if they use variants not in the set. Examples of potentially missing headers:
  - `"ΛΟΓΙΣΤΙΚΟ"` (used in "Λογιστικό Υπόλοιπο")
  - `"ΠΕΛΑΤΗΣ"` / `"ΠΕΛΑΤΗ"` (customer reference)
  - `"ΕΚΤΥΠΩΣΗΣ"` (printing metadata)
  - `"ΚΙΝΗΣΗ"` (movement/activity — but `"Κίνηση"` is already present, which covers lowercase; the uppercase variant is checked via `.uppercase()` so this is actually fine)
  - `"ΑΝΑΛΥΤΙΚΗ"` (detailed)
  - OCR-mangled variants like `"HMEPOMHNIA"` (without accent) — partially covered by Greeklish variants
- **Evidence:** The pre-filter does `upper.contains(keyword.uppercase())` so accent differences matter. `"ΗΜΕΡΟΜΗΝΙΑ"` is NOT in the set, but `"Ημερομηνία"` is — and `.uppercase()` strips accents in Kotlin/Java (`Ημερομηνία` → `ΗΜΕΡΟΜΗΝΙΑ`). Actually, Java's `String.toUpperCase()` does NOT strip Greek accents — `"Ημερομηνία".uppercase()` = `"ΗΜΕΡΟΜΗΝΊΑ"`. So the keyword `"Ημερομηνία"` in the set, when uppercased, becomes `"ΗΜΕΡΟΜΗΝΊΑ"` but the line uppercased would be `"ΗΜΕΡΟΜΗΝΙΑ"` → these don't match! Wait, let me check: Java `toUpperCase()` for Greek: `"ή"` → `"Η"` (accent stripped), `"ί"` → `"Ι"`, `"ύ"` → `"Υ"`. Actually this depends on the Locale. The default `uppercase()` without locale uses `Locale.getDefault()`. On Android with Greek locale, `"ημερομηνία".uppercase()` = `"ΗΜΕΡΟΜΗΝΙΑ"` (accents stripped). But the keyword in code is `"Ημερομηνία"` (with accent on ί) → `"Ημερομηνία".uppercase()` = `"ΗΜΕΡΟΜΗΝΙΑ"` (accent stripped). So they would match. This is fine.

  However, `"Ημ/νία Εκτύπωσης"` in the set → `.uppercase()` = `"ΗΜ/ΝΊΑ ΕΚΤΎΠΩΣΗΣ"` vs the line text which would also be uppercased similarly. Should be fine.

- **Suggested fix:** Add `"ΛΟΓΙΣΤΙΚΟ"`, `"ΠΕΛΑΤΗ"`, `"ΕΚΤΥΠΩΣΗ"`. These are minor because they're already partially blocked by other keywords (e.g., `"Λογιστικό Υπόλοιπο"` would be caught by `"Υπόλοιπο"`).

### [ISSUE-11] [MINOR] AI prompt truncates OCR text to 4000 chars without signaling to the AI that truncation occurred

- **File:** `domain/ai/usecase/ValidateBankStatementTransactionsUseCase.kt` (line 161)
- **Impact:** The AI may receive incomplete context if the OCR output exceeds 4000 characters. There's no `"(truncated)"` annotation, so the AI cannot know it has partial data.
- **Suggested fix:**
  ```kotlin
  if (rawOcrText.length > 4000) {
      appendLine(rawOcrText.take(4000))
      appendLine("… (OCR text truncated to 4000 characters)")
  } else {
      appendLine(rawOcrText)
  }
  ```

### [ISSUE-12] [MINOR] `suggestFromText` in CloudReceiptAssistService lacks retry logic (unlike `suggest`)

- **File:** `data/ai/provider/CloudReceiptAssistService.kt` (lines 253–283)
- **Impact:** The `suggest` method retries up to `MAX_RETRY_ATTEMPTS` with exponential backoff. `suggestFromText` makes exactly one attempt and fails on any transient error. This makes cloud-based bank statement validation less reliable.
- **Suggested fix:** Extract the retry loop into a shared helper or replicate it in `suggestFromText`.

### [ISSUE-13] [MINOR] `validatedTaxAmount` in `CloudReceiptAssistService.parseResponse` has a redundant null check

- **File:** `data/ai/provider/CloudReceiptAssistService.kt` (line 466)
- **Impact:** No functional impact — just dead code.
- **Evidence:**
  ```kotlin
  suggestion.optJSONObject("taxAmount")?.toSuggestedDoubleOrNull()
      ?.let { if (it != null && !AiOutputValidators.isPositiveAmount(it.value)) null else it }
  ```
  `toSuggestedDoubleOrNull()` already returns `SuggestedValue<Double>?`. The `?.let` only executes when non-null. Inside, `it` is `SuggestedValue<Double>` (non-nullable), so `if (it != null …)` is always true — the `it != null` check is unreachable dead code.
- **Suggested fix:** Remove `it != null &&`:
  ```kotlin
  ?.let { if (!AiOutputValidators.isPositiveAmount(it.value)) null else it }
  ```

### [ISSUE-14] [MINOR] `PrivacyCapability` has both `CLOUD_AI_BANK_STATEMENT` and `AI_BANK_STATEMENT_PARSING` — redundant

- **File:** `domain/privacy/PrivacyCapability.kt` (lines 9–10)
- **Impact:** Both capabilities are gated identically in `CloudAiPrivacyGate` (line 35–36: both checked against `settings.cloudAiEnabled`). This duplication creates confusion about which capability to use. `ValidateBankStatementTransactionsUseCase` uses `CLOUD_AI_BANK_STATEMENT` (line 99), which is correct for the cloud path, but `AI_BANK_STATEMENT_PARSING` is never referenced anywhere in the codebase.
- **Suggested fix:** Either remove the unused `AI_BANK_STATEMENT_PARSING` or document that it is reserved for a future on-device gating scenario. If it's truly unused, remove it to avoid dead capabilities in the privacy UI.

### [ISSUE-15] [MINOR] `tryParseGreekNbgTransaction` merchant extraction fails if merchant name starts with a digit

- **File:** `domain/receipt/BankStatementParser.kt` (lines 547–549)
- **Impact:** If a merchant is named something like `"365 Market"`, the merchant start index finder `parts.drop(2).indexOfFirst { part -> !part.matches(Regex("\\d+")) … } + 2` would skip past `"365"` thinking it's a numeric code. The merchant name would be `"Market"` instead of `"365 Market"`. However, this is unlikely for Greek statements where merchant names almost never start with digits.
- **Suggested fix:** Instead of looking for the first non-numeric token, use the known structure of NBG statements (fixed column positions before the merchant field).

### [ISSUE-16] [MINOR] `SmartReceiptAssistService.suggestFromText` javadoc says privacy gate is caller's responsibility — fragile contract

- **File:** `data/ai/provider/SmartReceiptAssistService.kt` (lines 110–117)
- **Impact:** Documented but fragile. Any new caller of `suggestFromText` may miss the privacy gate check. Defense-in-depth would have the gate inside the service method or at minimum in `CloudReceiptAssistService.suggestFromText`.
- **Suggested fix:** Move the privacy gate check into `CloudReceiptAssistService.suggestFromText` (see ISSUE-1).

### [ISSUE-17] [MINOR] On-device AI uses `ON_DEVICE_RECEIPT_TEMPERATURE` for bank statement validation — temperature may be too high for deterministic output

- **File:** `data/ai/provider/OnDeviceReceiptAssistService.kt` (line 83)
- **Impact:** The bank statement validation task requires deterministic, structured JSON output. If `ON_DEVICE_RECEIPT_TEMPERATURE` is set for creative receipt analysis (e.g., 0.4–0.7), it could produce non-deterministic or hallucinated corrections. A lower temperature (0.0–0.2) is more appropriate.
- **Suggested fix:** Use a separate config value like `AppConfig.Ai.ON_DEVICE_VALIDATION_TEMPERATURE` for the `suggestFromText` path, or explicitly set temperature to 0.1 as the cloud `suggestFromText` does (line 232: `put("temperature", 0.1)`).

### [ISSUE-18] [MINOR] Missing unit tests for `ValidateBankStatementTransactionsUseCase`

- **File:** No test file found for `ValidateBankStatementTransactionsUseCase`
- **Impact:** The AI validation pipeline has zero test coverage. This is the most complex and error-prone part of the pipeline (prompt building, JSON parsing, privacy gate, fallback chain) and should have tests covering:
  - Happy path: AI returns valid JSON array
  - AI returns markdown-fenced JSON
  - AI returns JSON object (not array)
  - AI returns malformed JSON
  - On-device AI succeeds
  - On-device AI fails → cloud fallback
  - Privacy gate denies cloud → parser-only fallback
  - AI returns empty array
  - AI returns transactions with invalid dates/amounts
- **Suggested fix:** Create `ValidateBankStatementTransactionsUseCaseTest.kt` in the test directory.

---

## ARCHITECTURE ASSESSMENT

### Layering — PASS (with notes)

The implementation follows the existing `domain/usecase` + `data/provider` + `domain/lifecycle` pattern. `ValidateBankStatementTransactionsUseCase` sits in `domain/ai/usecase`, mirroring existing use cases. The `BankStatementParser` is a `@Singleton` domain service. The AI services (`OnDeviceReceiptAssistService`, `SmartReceiptAssistService`, `CloudReceiptAssistService`) reside in `data/ai/provider`, consistent with the existing AI architecture.

**Note:** The lifecycle processor (`BankStatementLifecycleProcessor`) is a coordinator that mixes orchestration logic with entity construction. This is acceptable for a lifecycle processor pattern but would benefit from extracting the transaction-creation loop into a separate use case.

### Separation of Concerns — PASS

- **Parser** (`BankStatementParser`): deterministic extraction from spatial OCR blocks. Does not call AI. ✓
- **Validator** (`ValidateBankStatementTransactionsUseCase`): AI-based correction and filtering. Calls AI services. ✓
- **Coordinator** (`BankStatementLifecycleProcessor`): orchestrates the flow, persists results, creates PendingReviews. ✓

### DI via Hilt — PASS (with one gap)

All dependencies are properly annotated with `@Inject` and `@Singleton`. The Hilt modules (`AiModule`, `PrivacyModule`, `ReceiptParsingModule`) bind the correct implementations.

**Gap:** `ValidateBankStatementTransactionsUseCase` is NOT injected into `BankStatementLifecycleProcessor` (ISSUE-3).

### Privacy Gate Placement — CONDITIONAL PASS

The privacy gate (`PrivacyCapability.CLOUD_AI_BANK_STATEMENT`) is checked at the use case level (`ValidateBankStatementTransactionsUseCase` line 98) before any cloud AI call. This is the correct architectural layer for authorization decisions.

**BUT:** The lower-level `CloudReceiptAssistService.suggestFromText` omits the gate check entirely (ISSUE-1), creating a bypass vulnerability. The architecture should follow defense-in-depth: gate at BOTH the use case (authorization policy) and the service (enforcement point).

### AI Pipeline Pattern Consistency — PASS (with note)

The on-device→cloud→parser fallback chain follows the same pattern used by `SmartReceiptAssistService.executeWithFallback` (cloud→on-device→deterministic for receipts). The bank statement pipeline inverts the order (on-device first for privacy), which is appropriate for a text-only, lower-sensitivity-but-still-private flow.

**Note:** `SmartReceiptAssistService.suggestFromText` also tries cloud first (ISSUE-4), which contradicts the use case's intent. The text-only path should probably match the on-device-first preference.

---

## CODE QUALITY ASSESSMENT

### Error Handling — ADEQUATE

- AI service failures: wrapped in `runCatching {…}.getOrNull()`, with Timber logging. ✓
- JSON parsing failures: caught in `parseAiResponse`'s outer `try/catch`. ✓
- Network errors: handled in `CloudReceiptAssistService.suggestFromText` with specific exception types. ✓
- Empty candidate list: early return at line 73. ✓

**Missing:** No retry for `suggestFromText` cloud path (ISSUE-12).

### Logging — ADEQUATE

- Timber logs at key decision points (AI success/failure, privacy gate blocks, fallback paths). ✓
- Debug logging behind `BuildConfig.DEBUG` guards in `BankStatementParser`. ✓

**Missing:** The truncated OCR text (4000 chars) is not logged, making it hard to debug truncation-related issues (ISSUE-11).

### Thread Safety — PASS

No shared mutable state in the use case or parser. The `OnDeviceReceiptAssistService.cachedModel` uses `@Volatile` + `synchronized` for thread-safe lazy initialization.

### Race Conditions — PASS

The suspend function chain is sequential — each step awaits the previous step's result. No concurrent AI calls that could race.

---

## PARSER PRE-FILTER EVALUATION

### HEADER_KEYWORDS Coverage — ADEQUATE

The set covers the primary NBG statement headers (`Γ.Ε.Μ.Η`, `Μ.Α.Ε`, `Ημ/νία Εκτύπωσης`, `Κίνηση`, `Αρ. Κάρτας`, `Σελίδα`, `Αρ. Λογαριασμού`, `ΙΒΑΝ`, `Υπόλοιπο`, `Κατάστημα`, `Ημερομηνία`, `Περιγραφή`, `ΠΟΣΟ`, `Ποσό`) as well as English equivalents. A few minor additions are suggested (ISSUE-10).

### Efficiency — PASS

`preFilterRows` scans each row once with O(n·k) keyword matching. For typical statement sizes (<1000 lines, ~40 keywords), this is negligible.

### Greek Text Handling — ADEQUATE

The uppercasing approach handles most accent variations. The NBG-specific parser handles `Χ`/`Π` indicators correctly. The pre-filter catches `"Ημερομηνία"` and `"ΗΜΕΡΟΜΗΝΙΑ"` (accent-stripped via `uppercase()`).

### Edge Cases — MINOR RISK

The pure-numeric-line filter (line 293) could theoretically drop a transaction row where OCR failed to capture the merchant name (ISSUE-7). In practice, the parser-specific paths (Revolut, NBG) have their own detection logic before reaching the generic `extractTransactionFromRow`, so most real transactions would survive.

---

## DEBUG OUTPUT ASSESSMENT

### Validation Sources Propagation — PARTIAL (blocked by ISSUE-3 + ISSUE-9)

- `DebugData.validationSources` correctly designed to carry per-transaction source info. ✓
- `DebugData.toJson()` correctly serializes `validationSource` per transaction. ✓
- `BankStatementLifecycleProcessor` populates validation sources but currently hardcodes all to `"PARSER_ONLY"` (ISSUE-3).
- `DebugDataStorage.parseDebugDataFromJson` does NOT restore `validationSources` on reload (ISSUE-9).

### User Visibility — NOT YET ACHIEVABLE

Since AI validation is not wired (ISSUE-3), users cannot see AI-validated transactions in the debug viewer. Once wired, the infrastructure is in place for users to see which transactions were validated by AI.

---

## PRIVACY INTEGRATION ASSESSMENT

### New Capabilities — ADEQUATE

- `CLOUD_AI_BANK_STATEMENT`: gated by `CloudAiPrivacyGate` under `settings.cloudAiEnabled`. ✓
- `AI_BANK_STATEMENT_PARSING`: declared but unused and redundant (ISSUE-14).

### User Control — ADEQUATE

Users control cloud AI access via `PrivacySettings.cloudAiEnabled`. The `CloudAiPrivacyGate` checks this setting before allowing `CLOUD_AI_BANK_STATEMENT`.

### Gate Check Layer — NEEDS STRENGTHENING

The gate is checked at the use case level (`ValidateBankStatementTransactionsUseCase`) but NOT at the cloud service level for the text-only path (ISSUE-1). Defense-in-depth would place a gate check in `CloudReceiptAssistService.suggestFromText` as well.

---

## SUMMARY TABLE

| # | Severity | File | Issue |
|---|----------|------|-------|
| 1 | **CRITICAL** | `CloudReceiptAssistService.kt` | `suggestFromText` bypasses privacy gate |
| 2 | **CRITICAL** | `BankStatementParser.kt` | `runBlocking` in non-suspend `parse()` default parameter |
| 3 | **CRITICAL** | `BankStatementLifecycleProcessor.kt` | AI validation use case not wired — dead code |
| 4 | MAJOR | `SmartReceiptAssistService.kt` | `suggestFromText` tries cloud first (misaligned with use case) |
| 5 | MAJOR | `ValidateBankStatementTransactionsUseCase.kt` | AI response JSON parsing doesn't handle object wrappers |
| 6 | MAJOR | `ValidateBankStatementTransactionsUseCase.kt` | `source` always `"AI_VALIDATED"`, never `"AI_CORRECTED"` |
| 7 | MAJOR | `BankStatementParser.kt` | Pure-numeric line filter may drop valid transactions |
| 8 | MAJOR | `BankStatementParser.kt` | Revolut regex `\b` anchoring issue with alternation |
| 9 | MAJOR | `DebugDataStorage.kt` | `validationSources` lost on deserialization |
| 10 | MINOR | `BankStatementParser.kt` | HEADER_KEYWORDS missing some Greek variants |
| 11 | MINOR | `ValidateBankStatementTransactionsUseCase.kt` | OCR truncation not signaled to AI |
| 12 | MINOR | `CloudReceiptAssistService.kt` | `suggestFromText` lacks retry logic |
| 13 | MINOR | `CloudReceiptAssistService.kt` | Dead null check in `validatedTaxAmount` |
| 14 | MINOR | `PrivacyCapability.kt` | Redundant `AI_BANK_STATEMENT_PARSING` capability |
| 15 | MINOR | `BankStatementParser.kt` | NBG merchant extraction fails for digit-starting names |
| 16 | MINOR | `SmartReceiptAssistService.kt` | Fragile privacy contract (caller responsible) |
| 17 | MINOR | `OnDeviceReceiptAssistService.kt` | Temperature too high for deterministic validation |
| 18 | MINOR | N/A | Missing unit tests for `ValidateBankStatementTransactionsUseCase` |

---

## COVERAGE

- **Requirements met:** PARTIAL — The AI pipeline is implemented but not integrated into the main flow (ISSUE-3). The privacy gate is checked at the use case level but missing at the cloud service level (ISSUE-1).
- **Testing adequate:** NO — The parser has decent test coverage (Revolut, generic, date order) but no NBG tests, no pre-filter tests, and no tests for the AI validation use case.

---

## RECOMMENDED FIX ORDER

1. **ISSUE-2** (`runBlocking` in parser) — potential ANR, must fix first.
2. **ISSUE-1** (privacy gate bypass in `suggestFromText`) — security, must fix before cloud AI is used.
3. **ISSUE-3** (wire AI validation into lifecycle processor) — after 1 & 2 are fixed, this unlocks the entire feature.
4. **ISSUE-5** (JSON object handling) — prevents silent AI response failures.
5. **ISSUE-6** (AI_CORRECTED source) — needed for accurate debug output.
6. **ISSUE-9** (validationSources persistence) — UX issue for debug data review.
7. **ISSUE-4** (cloud-first misalignment) — architectural cleanup.
8. Remaining MAJOR and MINOR issues at team's discretion.
