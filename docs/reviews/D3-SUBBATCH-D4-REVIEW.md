# D3 SubBatch D.4 Review

Scope audited: `MASTER-ISSUE-REGISTRY.md` → `### SubBatch D.4` only.

Sources read:
- `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- `docs/reviews/AUDIT-PHASE-C-D.md`

## Summary

- Total issues audited: **21**
- **RESOLVED:** 13
- **PARTIALLY_RESOLVED:** 3
- **STILL_OPEN:** 5
- **FALSE_POSITIVE:** 0

## Issue Audit

1. **Line 837** — `OnDeviceCategorizationAssistService` lenient numeric parsing can emit `categoryId = 0`, `confidence = NaN`  
   **Status:** STILL_OPEN  
   **Evidence:** `OnDeviceCategorizationAssistService.kt:119-126` still uses `obj.optLong("categoryId")` and `obj.optDouble("confidence").toFloat()` without positive/finite validation, so invalid numeric payloads can still coerce to `0`/`NaN`.  
   **Suggested registry wording:** No change.

2. **Line 838** — `NotificationFilter.shouldCapture()` lowercases content but regex only matches uppercase  
   **Status:** RESOLVED  
   **Evidence:** `NotificationFilter.kt:53-56` defines `REGEX_CURRENCY` with `RegexOption.IGNORE_CASE`.  
   **Suggested registry wording:**
   ```
   - `NotificationFilter.shouldCapture()` lowercases content but regex only matches uppercase — make regex case-insensitive (B20) **[RESOLVED - `REGEX_CURRENCY` now uses `RegexOption.IGNORE_CASE`]**
   ```

3. **Line 839** — `RecommendationStateManager.clearForUser()` clears in-memory state for non-current user  
   **Status:** RESOLVED  
   **Evidence:** `RecommendationStateManager.kt:238-243` now clears `_recommendations` only when `currentUserId == userId`.  
   **Suggested registry wording:**
   ```
   - `RecommendationStateManager.clearForUser()` clears in-memory state for non-current user — only clear when `currentUserId == userId` (B20) **[RESOLVED - state clear is now conditional on `currentUserId == userId`]**
   ```

4. **Line 840** — `RecommendationDeduplicator.computeSignature()` always includes `rec.category`  
   **Status:** RESOLVED  
   **Evidence:** `RecommendationDeduplicator.kt:76-103` no longer uses `rec.category`; signature is built from navigation target plus deserialized filter fields.  
   **Suggested registry wording:**
   ```
   - `RecommendationDeduplicator.computeSignature()` always includes `rec.category` — build target-specific signatures (B20) **[RESOLVED - signature no longer includes `rec.category`; it is derived from navigation target plus deserialized filter fields]**
   ```

5. **Line 841** — `RecommendationInvalidator` swallows exceptions with empty catch  
   **Status:** RESOLVED  
   **Evidence:** `RecommendationInvalidator.kt:35-49`, `57-71`, `77-91`, and `99-110` all log failures with `Timber.e(...)`.  
   **Suggested registry wording:**
   ```
   - `RecommendationInvalidator` swallows exceptions with empty catch — log failures (B21) **[RESOLVED - invalidation/clear/cleanup paths now log failures with `Timber.e(...)`]**
   ```

6. **Line 842** — `NotificationSeeder` derives package names from display labels  
   **Status:** STILL_OPEN  
   **Evidence:** `NotificationSeeder.kt:102-105` still emits `"com.simulation.$source".lowercase()`, `NotificationSeeder.kt:129` emits `com.simulation.revolut`, and deposit templates still use `com.revolut` (`NotificationSeeder.kt:57-63`) while `RevolutParser.kt:29` only supports `com.revolut.revolut`.  
   **Suggested registry wording:** No change.

7. **Line 843** — `NotificationSeeder.generateRecurring()` produces isolated random charges  
   **Status:** STILL_OPEN  
   **Evidence:** `NotificationSeeder.kt:123-135` generates exactly one random recurring notification with one random timestamp in the last 60 days; it does not emit clustered series/intervals.  
   **Suggested registry wording:** No change.

8. **Line 844** — `ServiceDiagnostics` counters use unsynchronized read-modify-write on `SharedPreferences`  
   **Status:** RESOLVED  
   **Evidence:** `ServiceDiagnostics.kt:15-20` introduces a shared `lock`, and all counter writes/reads are synchronized (`31-37`, `40-66`, `77-85`).  
   **Suggested registry wording:**
   ```
   - `ServiceDiagnostics` counters use unsynchronized read-modify-write on SharedPreferences — guard with lock (B39) **[RESOLVED - counter updates and snapshot reads are synchronized on a shared lock]**
   ```

9. **Line 845** — `DebugIssueDetector` OCR-quality heuristic counts literal `?`  
   **Status:** STILL_OPEN  
   **Evidence:** `DebugIssueDetector.kt:116-117` still counts both `it == '\uFFFD'` and `it == '?'`.  
   **Suggested registry wording:** No change.

10. **Line 846** — `DebugData.toJson()` hand-builds JSON, only escapes subset of fields  
    **Status:** STILL_OPEN  
    **Evidence:** `DebugData.kt:17-72` still manually concatenates JSON strings; no serializer is used, and escaping remains ad hoc per field.  
    **Suggested registry wording:** No change.

11. **Line 847** — `DashboardFollowThroughEngine` category/merchant recommendations hardcode `PURCHASE`  
    **Status:** PARTIALLY_RESOLVED  
    **Evidence:** `DashboardFollowThroughEngine.kt:148-152` now preserves source type for high-amount recommendations, but `createCategoryRecommendation()` still hardcodes `DomainTransactionType.PURCHASE` (`179-183`), and `createMerchantRecommendation()` still does not preserve the source transaction type (`208-210`).  
    **Suggested registry wording:**
    ```
    - `DashboardFollowThroughEngine` recommendation filters still do not consistently preserve source transaction type — high-amount recommendations now use the source type, but category recommendations still hardcode `PURCHASE` and merchant recommendations still omit transaction type (B39) **[PARTIALLY_RESOLVED]**
    ```

12. **Line 848** — `DatabaseBackupRepository` import restart semantics tunnelled through sentinel values  
    **Status:** PARTIALLY_RESOLVED  
    **Evidence:** `DatabaseOperationResults.kt:12-16` now has explicit `DatabaseImportResult.SuccessNeedsRestart`, but `DatabaseBackupRepository.kt:27` still returns `Result<DatabaseImportSummary>`, `DatabaseBackupRepositoryImpl.kt:210-215` / `443-447` still use `transactionCount == -1` as the restart sentinel, and `DebugViewModel.kt:445-450` still branches on that sentinel.  
    **Suggested registry wording:**
    ```
    - Database backup import restart semantics are still tunneled through `DatabaseImportSummary.transactionCount == -1` in `DatabaseBackupRepository.importDatabase()`, even though `DatabaseImportResult.SuccessNeedsRestart` now exists at the UI/result layer (B39) **[PARTIALLY_RESOLVED]**
    ```

13. **Line 849** — `AccountingExporters` `SimpleDateFormat` singleton instance state  
    **Status:** RESOLVED  
    **Evidence:** `AccountingExporters.kt:4-7`, `14`, `53`, and `104` use immutable `java.time.format.DateTimeFormatter`, not `SimpleDateFormat`.  
    **Suggested registry wording:**
    ```
    - `AccountingExporters` SimpleDateFormat as singleton instance state — use `java.time` or instantiate per call (B39) **[RESOLVED - exporters now use immutable `java.time.format.DateTimeFormatter`]**
    ```

14. **Line 850** — `AccountingExporters` emit raw `Double.toString()` for money  
    **Status:** RESOLVED  
    **Evidence:** `AccountingExporters.kt:33-34`, `69`, and `120` now format money via `CurrencyFormatter.formatForExport(...)`.  
    **Suggested registry wording:**
    ```
    - `AccountingExporters` emit raw `Double.toString()` for money — centralize money formatting (B39) **[RESOLVED - money output now goes through `CurrencyFormatter.formatForExport(...)`]**
    ```

15. **Line 851** — Generic CSV export header omits currency column  
    **Status:** RESOLVED  
    **Evidence:** `ExportOptionsViewModel.kt:185-197` now writes `Date,Merchant,Amount,Currency,Category,Notes,ID` and includes `expense.currency` in each row.  
    **Suggested registry wording:**
    ```
    - `Generic CSV export` header omits currency column — add Currency column (B39-missed) **[RESOLVED - generic CSV header and rows now include `Currency`]**
    ```

16. **Line 852** — CSV escaping doesn't handle formula-injection prefixes  
    **Status:** PARTIALLY_RESOLVED  
    **Evidence:** `ExportOptionsViewModel.kt:326-356` and `AccountingExporters.kt:76-100` / `127-150` now neutralize leading `=`, `+`, `-`, and `@`, but `BusinessExpenseReportGenerator.kt:278-281` still only quotes commas/quotes/newlines and leaves formula prefixes raw.  
    **Suggested registry wording:**
    ```
    - CSV formula-injection hardening is incomplete — generic/export-accounting CSV paths now prefix dangerous `=`, `+`, `-`, `@` starters, but `BusinessExpenseReportGenerator.escapeCSV()` still writes them raw (B37) **[PARTIALLY_RESOLVED]**
    ```

17. **Line 853** — Mileage summary exposes first trip's rate as if uniform  
    **Status:** RESOLVED  
    **Evidence:** `BusinessExpenseReportGenerator.kt:143-146` now shows a weighted average when multiple rates exist, backed by `effectiveDeductionRatePerKm` / `hasMultipleRates` (`207-219`).  
    **Suggested registry wording:**
    ```
    - `Mileage summary` exposes first trip's rate as if uniform — show weighted rate (B37) **[RESOLVED - mileage summary now shows a weighted average when multiple deduction rates are present]**
    ```

18. **Line 854** — `SmsParser.detectSmsDirection()` returns `INCOMING` on tie for transfers  
    **Status:** RESOLVED  
    **Evidence:** `SmsParser.kt:154-168` now returns `null` for tie/no-evidence cases.  
    **Suggested registry wording:**
    ```
    - `SmsParser.detectSmsDirection()` returns `INCOMING` on tie for transfers — return `null` for ambiguous transfers (B44) **[RESOLVED - tie/no-evidence cases now return `null`]**
    ```

19. **Line 855** — `RevolutParser` amount regex only accepts single decimal separator  
    **Status:** RESOLVED  
    **Evidence:** `RevolutParser.kt:38-58` now embeds `CommonPatterns.GROUPED_AMOUNT_TOKEN`, and parsed amounts go through `AmountUtils.parseAmount()` (`91-145`); shared token is defined in `CommonPatterns.kt:29-30`.  
    **Suggested registry wording:**
    ```
    - `RevolutParser` amount regex only accepts single decimal separator — broaden regex (B44-missed) **[RESOLVED - parser now uses shared grouped-amount token and delegates normalization to `AmountUtils.parseAmount()`]**
    ```

20. **Line 856** — `SmsParser` amount regex same limitation  
    **Status:** RESOLVED  
    **Evidence:** `SmsParser.kt:38-45` now uses `CommonPatterns.GROUPED_AMOUNT_TOKEN`, and parsing delegates to `AmountUtils.parseAmount()` (`116-123`).  
    **Suggested registry wording:**
    ```
    - `SmsParser` amount regex same limitation — capture full token, delegate to `AmountUtils.parseAmount()` (B44-missed) **[RESOLVED - parser now uses shared grouped-amount token and delegates normalization to `AmountUtils.parseAmount()`]**
    ```

21. **Line 857** — `ImageCache` keyed only by URI `hashCode`  
    **Status:** RESOLVED  
    **Evidence:** `ImageCache.kt:42` and `80-82` now include `uri`, `maxWidth`, and `maxHeight` in the cache-key source before hashing.  
    **Suggested registry wording:**
    ```
    - `ImageCache` keyed only by URI hashCode — include dimensions in key (B44) **[RESOLVED - cache key now includes URI plus requested dimensions before hashing]**
    ```

## Registry Update Instructions

Apply the following updates in `MASTER-ISSUE-REGISTRY.md` under `### SubBatch D.4`:

### Replace these lines exactly

- **Line 838** with:
  ```
  - `NotificationFilter.shouldCapture()` lowercases content but regex only matches uppercase — make regex case-insensitive (B20) **[RESOLVED - `REGEX_CURRENCY` now uses `RegexOption.IGNORE_CASE`]**
  ```

- **Line 839** with:
  ```
  - `RecommendationStateManager.clearForUser()` clears in-memory state for non-current user — only clear when `currentUserId == userId` (B20) **[RESOLVED - state clear is now conditional on `currentUserId == userId`]**
  ```

- **Line 840** with:
  ```
  - `RecommendationDeduplicator.computeSignature()` always includes `rec.category` — build target-specific signatures (B20) **[RESOLVED - signature no longer includes `rec.category`; it is derived from navigation target plus deserialized filter fields]**
  ```

- **Line 841** with:
  ```
  - `RecommendationInvalidator` swallows exceptions with empty catch — log failures (B21) **[RESOLVED - invalidation/clear/cleanup paths now log failures with `Timber.e(...)`]**
  ```

- **Line 844** with:
  ```
  - `ServiceDiagnostics` counters use unsynchronized read-modify-write on SharedPreferences — guard with lock (B39) **[RESOLVED - counter updates and snapshot reads are synchronized on a shared lock]**
  ```

- **Line 847** with:
  ```
  - `DashboardFollowThroughEngine` recommendation filters still do not consistently preserve source transaction type — high-amount recommendations now use the source type, but category recommendations still hardcode `PURCHASE` and merchant recommendations still omit transaction type (B39) **[PARTIALLY_RESOLVED]**
  ```

- **Line 848** with:
  ```
  - Database backup import restart semantics are still tunneled through `DatabaseImportSummary.transactionCount == -1` in `DatabaseBackupRepository.importDatabase()`, even though `DatabaseImportResult.SuccessNeedsRestart` now exists at the UI/result layer (B39) **[PARTIALLY_RESOLVED]**
  ```

- **Line 849** with:
  ```
  - `AccountingExporters` SimpleDateFormat as singleton instance state — use `java.time` or instantiate per call (B39) **[RESOLVED - exporters now use immutable `java.time.format.DateTimeFormatter`]**
  ```

- **Line 850** with:
  ```
  - `AccountingExporters` emit raw `Double.toString()` for money — centralize money formatting (B39) **[RESOLVED - money output now goes through `CurrencyFormatter.formatForExport(...)`]**
  ```

- **Line 851** with:
  ```
  - `Generic CSV export` header omits currency column — add Currency column (B39-missed) **[RESOLVED - generic CSV header and rows now include `Currency`]**
  ```

- **Line 852** with:
  ```
  - CSV formula-injection hardening is incomplete — generic/export-accounting CSV paths now prefix dangerous `=`, `+`, `-`, `@` starters, but `BusinessExpenseReportGenerator.escapeCSV()` still writes them raw (B37) **[PARTIALLY_RESOLVED]**
  ```

- **Line 853** with:
  ```
  - `Mileage summary` exposes first trip's rate as if uniform — show weighted rate (B37) **[RESOLVED - mileage summary now shows a weighted average when multiple deduction rates are present]**
  ```

- **Line 854** with:
  ```
  - `SmsParser.detectSmsDirection()` returns `INCOMING` on tie for transfers — return `null` for ambiguous transfers (B44) **[RESOLVED - tie/no-evidence cases now return `null`]**
  ```

- **Line 855** with:
  ```
  - `RevolutParser` amount regex only accepts single decimal separator — broaden regex (B44-missed) **[RESOLVED - parser now uses shared grouped-amount token and delegates normalization to `AmountUtils.parseAmount()`]**
  ```

- **Line 856** with:
  ```
  - `SmsParser` amount regex same limitation — capture full token, delegate to `AmountUtils.parseAmount()` (B44-missed) **[RESOLVED - parser now uses shared grouped-amount token and delegates normalization to `AmountUtils.parseAmount()`]**
  ```

- **Line 857** with:
  ```
  - `ImageCache` keyed only by URI hashCode — include dimensions in key (B44) **[RESOLVED - cache key now includes URI plus requested dimensions before hashing]**
  ```

### Leave these lines unchanged (still open)

- **Line 837**
- **Line 842**
- **Line 843**
- **Line 845**
- **Line 846**
