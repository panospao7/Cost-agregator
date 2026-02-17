Based on a second exhaustive "line-by-line" review of the newly provided files (`NotificationCaptureService`, `FinancialWeatherRepository`, `MerchantNormalizer`, etc.) combined with previous findings, here is the final identification of leftover issues and architectural insufficiencies.

### 1. High-Performance & Concurrency Bottlenecks

* **Flow Re-analysis Thrashing (FinancialWeatherRepository):** The method `getAllRecurringPatterns()` maps a DB flow directly to `recurringExpenseEngine.getPatterns()`. This is a critical performance bug. `getPatterns()` performs a heavy analysis of the last 12 months of expenses. Every time a manual recurring expense is updated in the database, the system will re-scan thousands of raw expenses.
* **Proposed Fix:** Use a `conflate()` operator on the flow and implement a debounce or a "last analysis timestamp" check inside `RecurringExpenseEngine` to prevent redundant 12-month scans.


* **Main Thread Blocking on Model Load (ExpenseCategoryClassifier):** The `classify` and `train` methods call `loadModel()` if `!isLoaded`. While the internal read is on `Dispatchers.IO`, the check and initialization logic are called from the `HybridExpenseClassifier`, which is often triggered during rapid notification processing.
* **Proposed Fix:** Change `isLoaded` to an `AtomicBoolean` or use an `AsyncObject` pattern to ensure the classifier is truly ready before it's called in the notification pipeline.


* **Inefficient Dashboard Config Merging (DashboardRepository):**
`getDashboardConfig` parses a `JSONArray` and then iterates through `getDefaultConfig` to find missing IDs. This is  for widget management.
* **Proposed Fix:** Convert `getDefaultConfig` to a `Map` during the merge process for  lookups.



### 2. Logic Vulnerabilities & "Edge Case" Bugs

* **Deduplication Window Risk (NotificationCaptureService):**
The `DEDUP_WINDOW_MS` is set to 5000ms (5 seconds). Many banking apps (e.g., Revolut) send a "Transaction started" notification followed immediately (often < 1s) by a "Transaction successful" notification with updated merchant info. The current hash-based deduplication might discard the second, more accurate notification if the hash is too similar or the key doesn't differentiate sufficiently.
* **Proposed Fix:** Narrow the deduplication key to include the notification ID rather than just the hash, and reduce the window for "discovery mode" packages.


* **Merchant "Over-Cleaning" (MerchantNormalizer):**
The `LOCATION_PATTERN` RegEx matches `\s*At\s+[A-Z][a-z]+` to strip locations like "At Athens". However, this will incorrectly strip legitimate parts of brand names like "The Atrium" or "Coffee At Its Best".
* **Proposed Fix:** Restrict the "At [City]" pattern to only match if it appears at the very end of the string or is preceded by a known POS indicator.


* **The "31st Day" Forecast Gap (FinancialWeatherRepository):**
The `pastSumDaily` calculation uses `((now - monthStart) / 86400000L).toInt()`. On days when Daylight Savings Time starts or ends, a 24-hour period might be 23 or 25 hours long, causing the `dayIndex` to skip a day or double-count a day index.
* **Proposed Fix:** Always use `Calendar` or `java.time` to calculate the "Day of Month" index rather than raw millisecond division.



### 3. Data Integrity & Serialization Issues

* **Unsafe JSON Conversion in Extras (NotificationCaptureService):**
`buildExtrasJson` iterates through all keys in a notification Bundle. While it skips `sensitiveKeys`, it calls `value.toString()` on everything else. If a notification contains a Parcelable that isn't intended for string conversion, this could trigger a `TransactionTooLargeException` or a crash if the Bundle is lazily loaded.
* **Proposed Fix:** Add an explicit type check (e.g., `is String || is Int || is Double`) before attempting to put the value into the JSON object.


* **Fragmented Domain Models (FinancialWeather):**
There is a significant disconnect between `WeatherState` (enum), `RiskLevel` (enum), and the `riskLevel` (Int 0-100) returned to the UI. The `FinancialWeatherRepository` manually maps `RiskLevel` to 10, 40, 70, or 100. This creates a "Magic Number" dependency in the UI.
* **Proposed Fix:** Define the UI-friendly risk percentage within the `RiskLevel` enum itself.



### 4. Database & DAO Insufficiencies

* **Race Condition in Merchant Creation (MerchantNormalizer):**
While `createNewMerchant` uses a `Mutex`, the `normalize` function calls `dao.getAliasByNormalizedKey` *before* acquiring that lock. If two notifications for the same new merchant arrive simultaneously, both could pass the "exact match" check and attempt to enter the `createNewMerchant` block.
* **Proposed Fix:** Move the initial "Existence Check" inside the `creationMutex.withLock` block to ensure true atomicity.


* **Migration Complexity (AppDatabase):**
The database is at version 20 with many manual migrations. Several migrations (like `MIGRATION_16_17`) perform complex data transformations (moving data from `expenses` to `merchant_canonicals`). If these migrations fail midway, the database can be left in an inconsistent state because SQLite doesn't support full DDL transactions in all versions.
* **Proposed Fix:** For future complex migrations, implement a "Double-Write" period or use a temporary table and verification step before dropping old columns.



### 5. Categorization Logic Weaknesses

* **Fallback to "Uncategorized" (HybridExpenseClassifier):**
If rules and ML fail, the system falls back to the first available category. In `InsightsEngine`, this will skew "Top Category" reports significantly if a user has many unclassified transactions.
* **Proposed Fix:** Introduce a `PENDING_CATEGORIZATION` state that explicitly excludes these transactions from "Spending Pace" and "Forecast" until the user reviews them.

Here is the exhaustive analysis of the codebase. While the refactoring has improved structure, there are several **critical logic races**, **performance bottlenecks (N+1)**, and **regex fragilities** remaining.

### **Category 1: Critical Logic & Race Conditions**

#### **1. Double Counting in Budget Forecasting (`SynthesisEngine.kt`)**

**Issue:**
There is a high risk of double-counting expenses in `synthesize()`.

* `spendingPace.currentMonthSpent` includes transactions made **today**.
* `projectedObligations` calculates `recurringPatterns` where `nextExpectedDate >= startOfToday`.
* **Scenario:** If a user pays a bill at 09:00 AM, it appears in `currentMonthSpent`. If the Recurring Engine hasn't run yet to update the `nextExpectedDate` to next month, the pattern still shows due "today". The engine adds it to `projectedObligations` *again*.

**File:** `SynthesisEngine.kt`
**Proposed Fix:**
Change the filter for projected obligations to strictly look at the **future** relative to `now`, or exclude items that have a matching transaction today.

```kotlin
// Change this:
it.nextExpectedDate >= startOfToday

// To this (strictly future):
it.nextExpectedDate > System.currentTimeMillis() 

```

*Note: This is safer. If a bill was due this morning and hasn't been paid, it's technically "overdue" not "upcoming" in a projection sense, or it needs a specific "Overdue" bucket. For projection, strictly `> now` prevents the double count.*

#### **2. SQLite Constraint Crash in Router (`ConfidenceRouter.kt`)**

**Issue:**
The method `ensureSourceStats` checks for existence and then inserts. This is not atomic.
If two notifications arrive simultaneously from a new app (e.g., "Welcome to App" and "You spent $5"), both threads see `null` in cache/DB, and both attempt `insertIfNotExists`. The second one will crash with `SQLiteConstraintException`.

**File:** `ConfidenceRouter.kt`
**Proposed Fix:**
Use `try-catch` or ensure the DAO uses `ON CONFLICT IGNORE`.

```kotlin
suspend fun ensureSourceStats(packageName: String) {
    if (sourceStatsCache.containsKey(packageName)) return

    // Fix: Handle race condition gracefully
    try {
        val existing = sourceStatsDao.getByPackage(packageName)
        if (existing == null) {
            sourceStatsDao.insertIfNotExists(SourceStats(packageName = packageName))
        }
    } catch (e: Exception) {
        // Ignore unique constraint violation, another thread inserted it
    }
    // Update cache...
}

```

---

### **Category 2: Bad Logic & Intelligence Flaws**

#### **1. "Dumb" ML Downgrading "Smart" Parsers (`ConfidenceRouter.kt`)**

**Issue:**
The `route` logic blends the Parser confidence with ML confidence.
If a specific parser (e.g., `GreekBankParser`) matches a text perfectly (Confidence 0.95), but the ML model is new or uncertain (Prediction 0.2), the weighted average drags the confidence down below the `AUTO_ACCEPT_THRESHOLD`.
**Result:** Perfect Regex matches get sent to "Needs Review" unnecessarily.

**File:** `ConfidenceRouter.kt`
**Code:**

```kotlin
val mlWeight = calculateMlWeight(classifierStats) // e.g., 0.4
adjustedConfidence = parsed.confidence * parserWeight + mlPrediction * mlWeight
// 0.95 * 0.6 + 0.2 * 0.4 = 0.57 + 0.08 = 0.65 (Needs Review)

```

**Proposed Fix:**
If the Parser confidence is extremely high (> 0.9), **bypass** the ML blending or cap the ML's negative impact.

```kotlin
if (parsed.confidence >= 0.9f) {
    // Trust specific parsers explicitly, ignore ML noise
    adjustedConfidence = parsed.confidence
} else if (classifierStats.isReady) {
    // ... existing blending logic for ambiguous cases ...
}

```

#### **2. Multiplicative Penalties Kill Valid Transactions (`ConfidenceRouter.kt`)**

**Issue:**
The penalties stack multiplicatively.

* New Merchant (Unknown) = 0.5x
* New Package (No stats) = potentially low trust
* **Result:** A valid, clear transaction from a new source might drop to `0.4` confidence and be Auto-Rejected.

**Proposed Fix:**
Use a `max()` floor or additive penalties instead of aggressive multiplication, or ensure `AUTO_REJECT` isn't triggered solely by "Unknown Merchant".

---

### **Category 3: Bad Optimization & Performance**

#### **1. N+1 Query Disaster in Anomaly Detection (`InsightsEngine.kt`)**

**Issue:**
In `findAnomalies`, the code fetches top merchants, then maps them to `async` calls.

```kotlin
topMerchants.mapNotNull { ... async { expenseDao.getLargestExpenseForMerchant(...) } }

```

If you have 100 top merchants, this spawns **100 concurrent DB queries**. This will choke the SQLite thread pool and UI.

**File:** `InsightsEngine.kt`
**Proposed Fix:**
Fetch the "Max Amount" data in the initial aggregation query using SQL.

```sql
-- In ExpenseDao (Pseudo-code)
SELECT merchant, AVG(amount) as avg, MAX(amount) as max_val 
FROM expense 
GROUP BY merchant

```

Then `findAnomalies` becomes pure Kotlin memory logic without the loop of DB calls.

#### **2. Calendar Instantiation inside Loops (`RecurringExpenseEngine.kt`)**

**Issue:**
In `determineFrequency`, `Calendar.getInstance()` is called **twice** inside the loop iterating over dates. `Calendar` creation is expensive.

```kotlin
for (i in 0 until dates.size - 1) {
    val cal1 = java.util.Calendar.getInstance() // BAD
    val cal2 = java.util.Calendar.getInstance() // BAD
    ...
}

```

**File:** `RecurringExpenseEngine.kt`
**Proposed Fix:**
Move instantiation outside the loop and use `setTimeInMillis`.

```kotlin
val cal1 = Calendar.getInstance()
val cal2 = Calendar.getInstance()

for (i in 0 until dates.size - 1) {
    cal1.timeInMillis = dates[i]
    cal2.timeInMillis = dates[i+1]
    // ...
}

```

#### **3. String Formatting in Loop (`InsightsEngine.kt`)**

**Issue:**
In `buildDailyTotals`, `SimpleDateFormat` and string formatting are used to group expenses by day. String manipulation is slow for high-volume grouping.

```kotlin
val key = dateKeyFormat.format(dateObj) // Inside loop

```

**File:** `InsightsEngine.kt`
**Proposed Fix:**
Group by `DayOfYear` + `Year` (Integers) or truncate time division.

---

### **Category 4: Bugs & Regex Fragility**

#### **1. Fuzzy Group Extraction (`GreekBankParser.kt`)**

**Issue:**
The `tryExtract` function iterates *all* regex groups to guess which one is the amount.

```kotlin
for (i in 1..matcher.groupCount()) {
    if (group.matches(Regex("""^\d+[.,]\d{2}$"""))) ...
}

```

**Risk:** If the merchant name contains a number (e.g., "Shop 24.00"), the parser might confuse it for the amount if the order isn't perfect. Specific parsers should know that `group(1)` is amount and `group(2)` is merchant.

**File:** `GreekBankParser.kt`
**Proposed Fix:**
Hardcode group indices based on the specific pattern matched, rather than iterating generic groups.

#### **2. Loose SMS Merchant Extraction (`SmsParser.kt`)**

**Issue:**
The merchant pattern is too permissive:

```kotlin
Pattern.compile("""-\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{2,30})""", ...)

```

It matches *any* dash followed by text.
**Scenario:** SMS: "Transaction - Successful - Thank you".
**Result:** Merchant might be parsed as "Successful" or "Thank you".

**File:** `SmsParser.kt`
**Proposed Fix:**
Require the dash to be preceded by known currency or amount, or restrict the allowed characters/context further.

#### **3. Unhandled "Received" Transactions (`RevolutParser.kt`)**

**Issue:**
The parser detects `TransactionType.DEPOSIT` (e.g., "Received €50").
However, `SynthesisEngine` and `RecurringExpenseEngine` seem to operate purely on **Expenses**.
**Risk:** If these are saved into the `Expense` table with a positive amount, the analytics will treat "Income" as "Spending", destroying the budget accuracy.

**File:** `RevolutParser.kt`
**Proposed Fix:**
Ensure `DEPOSIT` types are either:

1. Ignored by the parser (return `null`).
2. Or stored with a **negative amount** in the `Expense` table so `sum()` works correctly.
3. Or the Engine filters `type == PURCHASE`.

---

### **Category 5: Date/Time Math Errors**

#### **1. Forecast Horizon Calculation (`SynthesisEngine.kt`)**

**Issue:**

```kotlin
val daysRemaining = (daysInMonth - dayOfMonth).coerceAtLeast(1)

```

If today is Jan 30th and the month has 31 days: `31 - 30 = 1`. Correct.
If today is Jan 31st: `31 - 31 = 0` -> coerced to `1`.
**Effect:** On the last day of the month, the engine divides discretionary spending by 1 and projects 1 day of spending.
However, `projectedPoints` logic:

```kotlin
(1..daysRemaining).map { ... }

```

On the last day (Jan 31), it projects 1 point into the future (Feb 1st?). The forecast horizon is "Rest of Month". If it is the last day, there is no "Rest of Month".

**File:** `SynthesisEngine.kt`
**Proposed Fix:**
If `dayOfMonth == daysInMonth`, `daysRemaining` should be 0.
Handle the `0` case in projection (return empty list of projected points).

```kotlin
val daysRemaining = (daysInMonth - dayOfMonth).coerceAtLeast(0) 
// If 0, skip projection logic to avoid div/0 or weird lists.

```

I found several **high-impact** leftover problems (some are real correctness bugs) in the provided modules, especially in Room SQL, notification deduplication, and a few UI/ViewModel logic edges. Below I grouped issues by category and went one-by-one with minimally risky fixes.

## Critical correctness bugs

- Room query logic likely returns the wrong rows because `AND`/`OR` conditions are mixed without parentheses in `ExpenseDao.getExpensesWithCategoryFilteredFlow` (the `... AND type IS NULL OR transactionType type ...` shape changes meaning due to operator precedence). [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/9698aefc-11c1-4d27-84ce-f07ebdc662f7/3_Data_Layer.md)
  Proposed fix (safe, standard Room pattern):  
  ```sql
  SELECT * FROM expenses
  WHERE date BETWEEN :startMs AND :endMs
    AND (:type IS NULL OR transactionType = :type)
    AND (:categoryId IS NULL OR categoryId = :categoryId)
    AND (:merchant IS NULL OR merchant = :merchant)
  ORDER BY date DESC
  ```
  Add an instrumented test that passes `null` and non-null parameters to verify filtering combinations.

- `RawNotificationDao.exists(...)` appears logically broken for nullable comparisons (it contains patterns like `title = title OR title IS NULL AND title IS NULL`, which collapses to “title is null” regardless of the input parameter, and similarly for text). [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/9698aefc-11c1-4d27-84ce-f07ebdc662f7/3_Data_Layer.md)
  Proposed fix (correct null-safe equality):  
  ```sql
  SELECT EXISTS(
    SELECT 1 FROM rawnotifications
    WHERE packageName = :packageName
      AND timestamp = :timestamp
      AND ((:title IS NULL AND title IS NULL) OR title = :title)
      AND ((:text  IS NULL AND text  IS NULL) OR text  = :text)
  )
  ```
  This is important because you use this as a first-line dedup gate in the processing pipeline. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/9698aefc-11c1-4d27-84ce-f07ebdc662f7/3_Data_Layer.md)

- Notification dedup cache cleanup is not thread-safe as written: `processedNotifications` is a `synchronizedMap(...)`, but `processedNotifications.entries.removeIf { ... }` is performed without synchronizing on the map (iteration/removal on a synchronized wrapper still requires external synchronization). [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/e1206ec2-eaf7-4825-955d-37934d39bbb6/4_Infrastructure_DI.md)
  Proposed fix:  
  ```kotlin
  private fun cleanupCacheIfNeeded() {
    if (processCount.incrementAndGet() >= CACHE_CLEANUP_THRESHOLD) {
      processCount.set(0)
      val now = System.currentTimeMillis()
      synchronized(processedNotifications) {
        processedNotifications.entries.removeIf { now - it.value > CACHE_MAX_AGE_MS }
      }
    }
  }
  ```
  This avoids rare `ConcurrentModificationException`/undefined behavior when notifications arrive quickly.

- Heuristic capture likely under-matches currencies: you lowercase `content` but your currency regex is declared as `Regex("EURUSDGBPCHF")` (no ignore-case), so `containsMatchIn(content)` won’t match `eur`/`usd` after lowercasing. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/e1206ec2-eaf7-4825-955d-37934d39bbb6/4_Infrastructure_DI.md)
  Proposed fix: either don’t lowercase before regex checks, or compile regex with `RegexOption.IGNORE_CASE`, e.g. `Regex("(EUR|USD|GBP|CHF)", RegexOption.IGNORE_CASE)` and keep the lowercasing for keyword `contains(...)`.

## Data & SQL issues

- Duplicate detection query is both expensive and semantically risky: it uses `ABS(date - :date) < :windowMs` (prevents index-friendly range scans) and includes multiple merchant match strategies including `LIKE` variants that can create false positives; I also see repeated/duplicated `LIKE` terms. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/9698aefc-11c1-4d27-84ce-f07ebdc662f7/3_Data_Layer.md)
  Proposed fix (more index-friendly and predictable):  
  - Replace `ABS(date - :date) < :windowMs` with `date BETWEEN :date - :windowMs AND :date + :windowMs`.  
  - Prefer a single canonical merchant key (e.g., normalized, no spaces, uppercase) stored in the row, then compare equality on that key rather than `LIKE`.

- Merchant alias normalization inside a DAO transaction builds a `normalizedKey` with a regex that looks malformed in the snippet (`replace(Regexa-z0-9--, ...)`), which risks “normalizing” to an empty/incorrect key and breaking lookups or causing collisions. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/9698aefc-11c1-4d27-84ce-f07ebdc662f7/3_Data_Layer.md)
  Proposed fix: centralize normalization in one utility and unit-test it heavily (Greek + Latin + digits + punctuation), then call it from repository/domain (not inside DAO) so it’s easier to test/version.

- Day-of-week analytics SQL depends on a passed `timeZoneOffset` and uses it inside the query expression; if you compute that offset using “now” and then query older data across DST boundaries, day-of-week bucketing can be wrong for some historical dates. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/9698aefc-11c1-4d27-84ce-f07ebdc662f7/3_Data_Layer.md)
  Proposed fix: if you want correctness across DST, either store local-date at insert time, or compute buckets in Kotlin using `java.time` with an explicit ZoneId (slower but correct), or accept the approximation and document it as “current offset bucketing”.

## Concurrency & lifecycle issues

- `NotificationCaptureService` builds a dedupe key as `sbn.key + contentHash` without a delimiter; while rare, concatenation ambiguity and `hashCode()` collisions can cause missed processing or over-deduplication. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/e1206ec2-eaf7-4825-955d-37934d39bbb6/4_Infrastructure_DI.md)
  Proposed fix: at minimum `"$key:$contentHash"`; safer: hash a structured string with separators (or a stable digest) and store that.

- Your tests (and likely production intent) emphasize concurrency (parallel categorization, concurrent inserts, etc.), but there are patterns like `parallelStream().map { runBlocking { ... } }` in tests that can hide deadlocks or thread starvation rather than revealing real coroutine issues. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/e1206ec2-eaf7-4825-955d-37934d39bbb6/4_Infrastructure_DI.md)
  Proposed fix: prefer `coroutineScope { merchants.map { async { engine.categorize(it) } }.awaitAll() }` so you test coroutine scheduling realistically.

- In `NotificationRepository.processAndSaveAll(...)`, chunking notifications and launching `async` per item can spike IO/CPU and create backpressure on Room if the list is large. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/9698aefc-11c1-4d27-84ce-f07ebdc662f7/3_Data_Layer.md)
  Proposed fix: apply a semaphore/limited parallelism (e.g., `Dispatchers.IO.limitedParallelism(n)`) or process sequentially per package.

## Logic gaps & inconsistencies

- `BudgetViewModel.validateThresholds` allows `notifyAtCritical` up to `1.05f` but the error message says “between warning and 100” (implying 1.0), which is inconsistent and will confuse users/testing. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/f1534f07-5868-4b4e-8634-b92258fcf923/1_Presentation_Layer.md)
  Proposed fix: either clamp to `<= 1.0f` or explicitly support “exceeded” thresholds and rename the field/message accordingly.

- `TransactionFilter` includes `correlationId: Long = System.currentTimeMillis()` which makes two “identical” filters unequal by default, potentially causing unnecessary recompositions, cache misses, and harder debugging. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/f1534f07-5868-4b4e-8634-b92258fcf923/1_Presentation_Layer.md)
  Proposed fix: remove it, or make it optional and only set it when you truly need a uniqueness token.

- In Compose UI, `AddEditBudgetDialog` silently does nothing when the amount is invalid/≤0 (`amt` becomes 0.0 and confirm just won’t proceed), rather than showing inline validation. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/f1534f07-5868-4b4e-8634-b92258fcf923/1_Presentation_Layer.md)
  Proposed fix: add a local `isAmountError` state and show `supportingText`, plus disable the confirm button until valid.

## Performance & maintainability issues

- You compute formatted/grouped transaction dates in multiple places: DB model `ExpenseWithCategory` precomputes format lazily, but `TransactionsViewModel.groupTransactionsByDate` creates its own `SimpleDateFormat` and sorts/groups on every emission. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/f1534f07-5868-4b4e-8634-b92258fcf923/1_Presentation_Layer.md)
  Proposed fix: either group using the already-formatted date (if acceptable), or move grouping to a paging layer, or at least reuse a single formatter (ThreadLocal or `java.time`).

- Using `fallbackToDestructiveMigration()` in the Room builder is dangerous for real users because any missed migration path can wipe data. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/e1206ec2-eaf7-4825-955d-37934d39bbb6/4_Infrastructure_DI.md)
  Proposed fix: remove it for release builds; if you need it during development, guard it behind `BuildConfig.DEBUG`.

- Debug data persistence writes a “lastdebugdata.json” file in internal storage containing parsing metadata and previews; this can contain sensitive financial text and should be gated/controlled. [ppl-ai-file-upload.s3.amazonaws](https://ppl-ai-file-upload.s3.amazonaws.com/web/direct-files/attachments/93127243/f1534f07-5868-4b4e-8634-b92258fcf923/1_Presentation_Layer.md)
  Proposed fix: only enable in debug builds, or encrypt-at-rest, or store only redacted summaries (amounts rounded, merchants hashed) by default.

***

