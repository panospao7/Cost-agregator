# HIGH Compliance Fix Review — DB v113

**Review date:** 2026-05-04  
**Reviewer:** Automated deep-review  
**Verdict:** PASS

---

## H1: BankStatementLifecycleProcessor no longer calls scannedReceiptDao directly → **PASS**

| Check | Result |
|---|---|
| `scannedReceiptDao.insert()` replaced with `receiptRepository.insertReceipt()`? | ✅ YES — line 204: `val receiptId = receiptRepository.insertReceipt(statementReceipt)` |
| `ReceiptRepository.insertReceipt()` exists? | ✅ YES — lines 487–489: `suspend fun insertReceipt(receipt: ScannedReceipt): Long { return scannedReceiptDao.insert(receipt) }` |
| `scannedReceiptDao` still injected? | ✅ Still injected (line 62) but used only for `getById()` (line 110) for duplicate hash-check — not for the insert. This is correct; the insert path now goes through the repository. |

**Details:**
- `BankStatementLifecycleProcessor.kt` line 204: `receiptRepository.insertReceipt(statementReceipt)` — correctly routes through the repository.
- `ReceiptRepository.kt` lines 487–489: `insertReceipt()` delegates to `scannedReceiptDao.insert()` internally — providing proper abstraction.
- The DAO is still injected for `getById()` lookup at line 110 (duplicate-detection by file hash), which is a legitimate read-only access, not bypassing the repository's write path.

---

## H2: SynthesisEngine currency-aware sums → **PASS**

| Check | Result |
|---|---|
| `PlannedExpense` sums grouped by currency? | ✅ YES — multiple locations: |
| — committedPlanned (line 167–173) | `.groupBy { it.currency }.mapValues { (_, exps) -> exps.sumOf { it.amount } }` with multi-currency warning |
| — likelyPlanned (line 200–206) | Same pattern, plus `* LIKELY_EXPENSE_WEIGHT` weighting |
| — mustExpensesByDay (lines 257–261) | Groups by currency within each day, warns on multi-currency |
| — likelyExpensesByDay (lines 269–272) | Same pattern |
| — thisMonthPlannedByCurrency (lines 380–393) | Groups by currency for block-party, warns on multi-currency |
| — plannedOnDay (lines 459–471) | Inline currency grouping for each calendar day |
| `PlannedExpense` domain model has `currency` field? | ✅ YES — `PlannedExpense.kt` line 7: `val currency: String = "EUR"` |
| `ForecastInputAssembler.mapPlannedExpenses()` maps currency? | ✅ YES — line 119: `currency = entity.currency` |
| `DashboardContractsAdapter.observePlannedExpenses()` maps currency? | ✅ YES — line 99: `currency = entity.currency` |
| `RecurringExpensesScreen.plannedExpenses` maps currency? | ✅ YES — line 134: `currency = entity.currency` |

**Additional verification:**
- Entity `PlannedExpense.kt` has `currency` column with `@ColumnInfo(defaultValue = "'EUR'")` at line 41 and a `currencyAssumption` tracking field at line 42.
- All mappers consistently pass `entity.currency` through to the domain model.
- Multi-currency warnings are logged via `Timber.w()` when detected, aiding debugging.

---

## H3: Domain models KDoc for EUR defaults → **PASS**

| Check | Result |
|---|---|
| `DashboardPrimitives.kt` — KDoc on EUR default? | ✅ YES — lines 9–10: `@param currency Currency code (e.g. "EUR", "USD"). Always populated by callers; the default "EUR" is a backward-compat placeholder — do not rely on it.` |
| `SpendingSummary.kt` — KDoc on EUR default? | ✅ YES — lines 9–11: `@param currency Currency code (e.g. "EUR", "USD"). The default "EUR" is a backward-compat placeholder — production callers should always pass the user's home currency from [CurrencySettingsRepository.homeCurrency].` |

**Details:**
- Both KDocs clearly explain that "EUR" is a backward-compat placeholder.
- `SpendingSummary` specifically references `CurrencySettingsRepository.homeCurrency` as the canonical source.
- `DashboardExpense` also has a `moneyAmount` / `moneyEffectiveAmount` derived property using `CurrencyCode`, providing type-safe currency handling.

---

## H4: ReceiptRepository + CsvExpenseImporter use home currency → **PASS**

| Check | Result |
|---|---|
| `ReceiptRepository` — is `currency = "EUR"` replaced with `homeCurrency()`? | ✅ YES in main paths, with minor residual hardcoding in secondary paths (see notes) |
| — `processReceipt()` success path (line 149) | ✅ Uses `parsed.currency` from the parser — not hardcoded EUR |
| — `processReceipt()` failure path (line 228) | ✅ `currency = homeCurrency()` |
| — `saveManualReceiptRecord()` (line 271/279) | ✅ `val homeCur = homeCurrency()` then `currency = homeCur` |
| — `homeCurrency()` helper (lines 462–465) | ✅ `runCatching { currencySettingsRepository.homeCurrency().first() }.getOrDefault("EUR")` |
| — `processReceipt()` failure review `suggestedCurrency` (line 244) | ⚠️ Still `"EUR"` — but this is `suggestedCurrency` on a parse-failure PendingReview (the receipt's own currency at line 228 is already `homeCurrency()`) |
| — `createExpenseFromReceipt()` parameter default (line 344) | ⚠️ `currency: String = "EUR"` — acceptable; the method is `@Deprecated` |
| — `processStatement()` fallback (line 617) | ⚠️ `parsedTransactions.firstOrNull()?.currency ?: "EUR"` — acceptable; uses parsed currency first, "EUR" only as unreachable fallback (empty transactions return failure at line 127 before reaching this code) |
| `CsvExpenseImporter` — `CurrencySettingsRepository` injected? | ✅ YES — line 33: `private val currencySettingsRepository: CurrencySettingsRepository` |
| — Currency detected from CSV? | ✅ YES — `detectCurrencySymbol()` at lines 233–240 detects €→EUR, $→USD, £→GBP, ¥→JPY |
| — Falls back to home currency? | ✅ YES — lines 141–142: `currencyFromSymbol ?: runCatching { currencySettingsRepository.homeCurrency().first() }.getOrDefault("EUR")` |

**Notes:**
- The hardcoded `"EUR"` at line 244 (`suggestedCurrency`) and line 344 (deprecated method default) are minor residual items that do not affect correctness of the main processing paths.
- The `processStatement()` fallback (line 617) is practically unreachable for the currency fallback case.

---

## H5: DAO methods KDoc on System.currentTimeMillis() defaults → **PASS**

| Check | Result |
|---|---|
| KDoc present on `System.currentTimeMillis()` defaults? | ✅ YES — verified across multiple DAOs |

**Sample verification (2 random DAOs + grep across all DAOs):**

| DAO File | Example |
|---|---|
| `AiArtifactDao.kt` | ✅ Line 52: `@param now Defaults to [System.currentTimeMillis] for backward compat;` |
| `SplitItemAssignmentDao.kt` | ✅ Line 31: `@param timestamp Defaults to [System.currentTimeMillis] for backward compat;` |
| `SplitTemplateDao.kt` | ✅ Line 28: same pattern |
| `SubscriptionCandidateDao.kt` | ✅ Lines 80, 95: same pattern |
| `RecommendationDao.kt` | ✅ Lines 37, 61, 85, 121, etc. — 12 occurrences, all with KDoc |
| `SpendingPersonalityProfileDao.kt` | ✅ Line 70: same pattern |
| `SavingsSweepPlanDao.kt` | ✅ Lines 95, 104, 113, 122: same pattern |
| `BudgetAdjustmentDao.kt` | ✅ Lines 54, 61, 68: same pattern |

**Additional notes:**
- DAOs without `System.currentTimeMillis()` defaults (e.g., `ExchangeRateDao`, `BlockedPackageDao`, `SavingsGoalDao`, `WarrantyDao`) do not need this KDoc — they either use `timeProvider.now()` or `Long` parameters without defaults.
- All DAO methods with `System.currentTimeMillis()` defaults consistently carry the KDoc `@param ___ Defaults to [System.currentTimeMillis] for backward compat;`.

---

## H6: ExpenseDao deprecated queries → **PASS**

| Check | Result |
|---|---|
| Are raw SUM queries deprecated? | ✅ YES — all raw SUM queries without currency grouping are marked `@Deprecated` |

**Inventory of deprecated SUM queries in `ExpenseDao.kt`:**

| Query | Line | Deprecation Message |
|---|---|---|
| `getTotalSpentFlow()` | 259 | "Returns raw Double without currency conversion" |
| `getCategorySpentInPeriod()` | 819 | "Returns raw Double without currency conversion" |
| `getCategorySpentInPeriodFlow()` | 833 | "Unsafe: raw SUM across mixed currencies" |
| `getTotalSpentBetween()` | 971 | "Returns raw Double without currency conversion" |
| `getEffectiveSpentBetweenForCategory()` | 994 | "Raw SUM across mixed currencies" |
| `getMonthlySpendingTotals()` | 1182 | "Raw SUM across mixed currencies" |
| `getMonthlySpendingTotalsBetween()` | 1200 | "Raw SUM across mixed currencies" |
| `getMonthlySpendingTotalsByCategoryBetween()` | 1219 | "Raw SUM across mixed currencies" |
| `getMerchantTotalsBetween()` | 1234 | "Raw SUM across mixed currencies" |
| `getCategoryTotalsBetween()` | 1248 | "Raw SUM across mixed currencies" |
| `getSpendingDailyTotalsBetween()` | 1267 | "Raw SUM across mixed currencies" |
| `getTotalForPeriod()` | 1287 | "Returns raw Double without currency conversion" |
| `getCategoryTotalsForPeriod()` | 1310 | "Raw SUM across mixed currencies" |
| `getDailyTotalsForPeriod()` | 1432 | "Raw SUM across mixed currencies" |
| `getTotalDepositsForPeriod()` | 1496 | "Raw SUM across mixed currencies" |
| `getTotalDeposits()` | 1512 | "Raw SUM across mixed currencies" |
| `getWeeklyTotalsForPeriod()` | 1744 | "Raw SUM across mixed currencies" |
| `getMonthlyTotalsForPeriod()` | 1760 | "Raw SUM across mixed currencies" |
| `getDailyTotalsWithDatesForPeriod()` | 1776 | "Raw SUM across mixed currencies" |
| `getAverageDailySpend()` | 1792 | "Raw SUM across mixed currencies" |
| `getCategoryBreakdown()` | 1805 | "Raw SUM across mixed currencies" |
| `getTotalBusinessExpensesBetween()` | 1841 | "Raw SUM across mixed currencies" |
| `getBusinessExpensesByCategory()` | 1850 | "Raw SUM across mixed currencies" |
| `getBusinessExpensesByProject()` | 1865 | "Raw SUM across mixed currencies" |

**Currency-aware replacements (NOT deprecated):**

| Replacement | Line |
|---|---|
| `getTotalSpentBetweenByCurrency()` — GROUP BY UPPER(currency) | 1027 |
| `getCategoryTotalsBetweenByCurrency()` — GROUP BY categoryId, UPPER(currency) | 1056 |
| `getMerchantTotalsBetweenByCurrency()` — GROUP BY merchantKey, UPPER(currency) | 1078 |
| `getMonthlyTotalsBetweenByCurrency()` — GROUP BY monthKey, UPPER(currency) | 1097 |
| `getAllSpentBetweenByCurrency()` — type-agnostic variant | 1119 |
| `getAllCategoryTotalsBetweenByCurrency()` | 1137 |
| `getAllMerchantTotalsBetweenByCurrency()` | 1158 |
| `getAllMonthlyTotalsBetweenByCurrency()` | 1175 |

**Additional notes:**
- All deprecated queries reference `MultiCurrencyRepository` as the replacement path in their `@Deprecated` annotation.
- Some deprecation messages include `ReplaceWith` annotations providing the exact replacement call.
- The currency-aware replacements all use `GROUP BY UPPER(currency)` to properly handle per-currency aggregation.

---

## Summary

| Fix | Verdict | Issues |
|---|---|---|
| H1: BankStatementLifecycleProcessor uses repository | ✅ PASS | None |
| H2: SynthesisEngine currency-aware sums | ✅ PASS | None |
| H3: Domain models KDoc for EUR defaults | ✅ PASS | None |
| H4: ReceiptRepository + CsvExpenseImporter home currency | ✅ PASS | None (2 minor residual hardcoded "EUR" in secondary/deprecated paths, not regressions) |
| H5: DAO methods KDoc on System.currentTimeMillis defaults | ✅ PASS | None |
| H6: ExpenseDao deprecated queries | ✅ PASS | None |

**Overall Verdict: PASS — all 6 HIGH compliance fixes are correctly applied.**
