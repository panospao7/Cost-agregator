# Currency & Exchange Review — Cross-Check Against Current Codebase

**Date:** 2026-05-02  
**Review branch:** current `HEAD`  
**Analysis reviewed:** `docs\analyses and debug master\currency-exchange-analysis.md`  
**Codebase root:** `app/src/main/java/com/yourname/expensetracker`

---

## Executive Summary

**Overall Verdict: PARTIALLY RESOLVED — Significant progress made on domain modeling (PR 1–PR 4 partially done), but critical data-integrity gaps remain unfixed (no historical rate storage, no conversion snapshots populated, no re-normalization path).**

The codebase demonstrates substantial architectural investment since the analysis was written. A new `domain/core/money/` package introduces `CurrencyCode`, `MoneyAmount`, `MoneyAggregate`, `MoneyBucket`, `ConvertedMoney`, `ConversionStatus`, and `CurrencyAssumption` — all of which directly address issues #1 (money model), #3 (aggregate types), #5 (currency on PlannedExpense), and #7 (validation) from the analysis. A `MultiCurrencyRepository` wraps the DAO layer with currency-aware grouped aggregates and produces `MoneyAggregate` results. A proper `CurrencyFormatter` is introduced. Over 20 raw-sum DAO methods are deprecated with migration guidance.

However, the three **highest-priority critical issues** from the analysis remain **essentially unaddressed**:

1. **Historical conversion snapshots are schema-only** — `Expense.baseAmount`/`baseCurrency`/`exchangeRateUsed` fields exist but are never populated.  
2. **Exchange rates are still latest-state only** — the `validDate` field was added to the entity but the unique constraint still prevents historical rows, the domain contract doesn't expose it, and no `getRateAsOf()` method exists.  
3. **No home-currency-change re-normalization path** — `setHomeCurrency()` still just writes a string with no data migration.

---

## Issue-by-Issue Cross-Check

---

### 🔴 ISSUE 1 — Transaction-level conversion snapshot — PARTIALLY RESOLVED

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| Schema | `Expense` has no `baseAmount`, `baseCurrency`, `exchangeRateUsed` | **FIXED** — `Expense.kt:122-125` adds `baseAmount: Double = 0.0`, `baseCurrency: String = "EUR"`, `exchangeRateUsed: Double = 0.0` |
| Doc comment | — | Fields are annotated: `"Historical conversion snapshot fields (D.19) — schema only, not populated yet"` |
| Populated? | — | **NOT POPULATED.** The fields exist in the table but all rows have default values (0.0 / "EUR" / 0.0). No code path writes to them. |
| Non-snapshot fields missing | `exchangeRateTimestamp`, `exchangeRateSource`, `conversionStatus` | **STILL MISSING.** Only 3 of 6 recommended fields were added. |
| Consumer usage | — | No consumer reads `baseAmount`. All queries still use `effectiveAmount` for aggregation. |

**Verdict: PARTIALLY RESOLVED** — Schema is in place (migration D.19), but the fields are dead code. The actual population logic and consumer migration are needed.

**Remaining work:**
- Add `exchangeRateTimestamp`, `exchangeRateSource`, `conversionStatus` columns.
- Implement population in `CurrencyConverter` or a dedicated service at expense-creation time.
- Backfill existing rows (EUR rows → baseAmount=amount & rate=1.0; non-EUR → `conversionStatus=MISSING_RATE`).
- Switch consumers (`MultiCurrencyRepository`, `AnalyticsRepository`, etc.) to read `baseAmount` where available.

---

### 🔴 ISSUE 2 — Exchange rates latest-state only — STILL PRESENT

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| Unique constraint | `(fromCurrency, toCurrency)` unique — prevents historical rows | **STILL THE SAME.** `ExchangeRate.kt:21`: `Index(value = ["fromCurrency", "toCurrency"], unique = true)` |
| `validDate` field | Not present | **ADDED.** `ExchangeRate.kt:36`: `validDate: Long = 0L` — but unused |
| Non-unique index | Not present | **ADDED.** `ExchangeRate.kt:22`: `Index(value = ["fromCurrency", "toCurrency", "validDate"])` — non-unique, correct direction |
| `getRateAsOf()` | Not present | **STILL MISSING.** Neither `ExchangeRateDao` nor `ExchangeRateStore` expose a date-qualified lookup. |
| Domain model | No `validDate` | **STILL MISSING.** `DomainExchangeRate` (ExchangeRateContracts.kt:8-14) does not include `validDate`. |
| Adapter mapping | — | **BROKEN.** `ExchangeRateStoreAdapter.toEntity()` (line 54-61) does not map `validDate`; `toDomain()` (line 44-52) does not read it. The field exists on the entity but is invisible above the adapter. |

**Verdict: STILL PRESENT** — The `validDate` column and non-unique index were added (correct schema direction), but the unique constraint on the pair is still active, the domain layer doesn't see `validDate`, and no `getRateAsOf()` query exists. Without removing the pair-unique constraint, historical rows cannot be stored.

**Remaining work:**
1. Remove unique constraint on `(fromCurrency, toCurrency)`; replace with `(fromCurrency, toCurrency, validDate, source)`.
2. Add `validDate` to `DomainExchangeRate`.
3. Fix `ExchangeRateStoreAdapter` mappers to propagate `validDate`.
4. Add `getRateAsOf(fromCurrency, toCurrency, asOfTimestamp: Long)` to DAO, Store, and Converter.
5. Update ECB refresh to set `validDate` on stored rates.

---

### 🟡 ISSUE 3 — DAO raw mixed-currency aggregate queries — PARTIALLY RESOLVED

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| Deprecation | No deprecation warnings | **FIXED.** 20+ methods are `@Deprecated` with migration guidance (e.g., `ExpenseDao.kt:816-818`, `829-837`, `967-970`, `990-1001`, etc.) |
| Grouped-by-currency helpers | Partial (`getTotalSpentBetweenByCurrency`, `getCategoryTotalsBetweenByCurrency`) | **EXTENDED.** Now includes: `getTotalSpentBetweenByCurrency`, `getCategoryTotalsBetweenByCurrency`, `getMerchantTotalsBetweenByCurrency`, `getMonthlyTotalsBetweenByCurrency`, and their type-agnostic `getAll...ByCurrency` variants. |
| New DTOs | — | **ADDED.** `CurrencyTotal`, `CategoryCurrencyTotal`, `MerchantCurrencyTotal`, `MonthlyCurrencyTotal` data classes (ExpenseDao.kt:1996-2046). |
| `MultiCurrencyRepository` | Not present | **ADDED.** Full `MultiCurrencyRepository` with type-agnostic and PURCHASE-only variants, producing `MoneyAggregate` results. |
| Remaining raw sums | Many | **Still present but deprecated.** All raw-sum paths carry `@Deprecated` annotations. Consumers are being migrated (e.g., `AnalyticsRepository`, `ComputeDashboardWidgetsUseCase` reference `MultiCurrencyRepository`). |
| `getTotalSpentFlow()` | No deprecation, no currency grouping | **STILL UNSAFE.** `ExpenseDao.kt:256`: `fun getTotalSpentFlow(): Flow<Double?>` — no `@Deprecated`, no currency, used for real-time dashboard total. |

**Verdict: PARTIALLY RESOLVED** — Excellent deprecation coverage and `MultiCurrencyRepository` bridge. The `getTotalSpentFlow()` on line 256 is a notable oversight (no deprecation, still used).

**Remaining work:**
- Deprecate `getTotalSpentFlow()` and replace with a currency-aware reactive variant.
- Verify all callers have migrated away from deprecated methods.
- Consider removing deprecated methods after a deprecation period.

---

### 🟠 ISSUE 4 — `CurrencyConverter.convert()` no date/context parameter — STILL PRESENT

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| Method signature | `convert(amount, fromCurrency, toCurrency)` | **UNCHANGED.** `CurrencyConverter.kt:79-83`: same signature. |
| `convertNow()` | Not present | **NOT ADDED.** |
| `convertAsOf()` | Not present | **NOT ADDED.** |
| `createMoneySnapshot()` | Not present | **NOT ADDED.** |

**Verdict: STILL PRESENT** — No changes to the convert API. The method is still date-unaware. This is a prereq for fixing issues #1 and #2.

**Remaining work:**
- Add `convertAsOf(amount, from, to, asOfTimestamp: Long)` using historical rate lookup.
- Add `createMoneySnapshot(amount, currency, baseCurrency, transactionDate: Long): ConvertedMoney`.
- Keep `convert()` as `convertNow()` alias for current-display use, possibly deprecating the ambiguous name.

---

### 🟡 ISSUE 5 — `PlannedExpense` has no currency — PARTIALLY RESOLVED

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| `currency` field | Not present | **FIXED.** `PlannedExpense.kt:41`: `@ColumnInfo(defaultValue = "'EUR'") val currency: String = "EUR"` |
| `currencyAssumption` field | Not present | **ADDED.** `PlannedExpense.kt:42`: `@ColumnInfo(defaultValue = "'LEGACY_DEFAULT'") val currencyAssumption: String = "LEGACY_DEFAULT"` — tracks why currency was assigned |
| `MoneyAmount` getter | Not present | **ADDED.** `PlannedExpense.kt:64-65`: `val moneyAmount: MoneyAmount get() = MoneyAmount(amount, CurrencyCode(currency))` |
| `baseAmount`/`baseCurrency` | Recommended | **NOT ADDED.** No normalized/base amount fields. |
| `conversionStatus` | Recommended | **NOT ADDED.** |

**Verdict: PARTIALLY RESOLVED** — Core currency field is present with assumption tracking. The normalized snapshot (matching issue #1 for Expense) is still missing.

**Remaining work:**
- Add `baseAmount`, `baseCurrency`, `exchangeRateUsed`, `conversionStatus` fields.
- For future expenses, decide: use latest rate with `APPROXIMATE_RATE` status, or store original currency only and convert dynamically.

---

### 🔴 ISSUE 6 — Home currency can be changed without re-normalization — STILL PRESENT

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| `setHomeCurrency()` | Writes raw string, no migration | **UNCHANGED.** `CurrencySettingsRepositoryImpl.kt:43-47`: still just writes the string. |
| Re-normalization job | Not present | **NOT ADDED.** No migration job, no recalculation of stored base amounts. |
| Accounting vs. display currency | No separation | **NO SEPARATION.** `homeCurrency()` serves as both display and accounting base. |

**Verdict: STILL PRESENT** — No change. This becomes more acute once base amounts are actually stored (issue #1).

**Remaining work:**
- Separate: `accountingBaseCurrency`, `displayCurrency`, `originalTransactionCurrency`.
- `setDisplayCurrency()` should not touch stored accounting data.
- `setAccountingBaseCurrency()` must trigger a migration/recalculation of all stored base amounts.
- Without this separation, fixing issue #1 introduces a new hazard: base amounts stored in old home currency become wrong after change.

---

### 🟡 ISSUE 7 — Currency validation weak at boundaries — PARTIALLY RESOLVED

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| `CurrencyCode` value class | Not present | **ADDED.** `CurrencyCode.kt`: `@JvmInline value class CurrencyCode(val code: String)` with 3-letter uppercase validation, `parse()` for untrusted input. |
| Adoption at settings boundary | — | **NOT ADOPTED.** `CurrencySettingsRepositoryImpl.setHomeCurrency(code: String)` still takes raw `String`. |
| Adoption at converter | — | **NOT ADOPTED.** `CurrencyConverter` still uses raw `String` parameters. |
| Adoption at DAO | — | **PARTIALLY ADOPTED.** DAO currency-aware queries use `UPPER(currency) = UPPER(:currency)` in SQL, but parameters are still raw `String`. |
| `MoneyAmount` using `CurrencyCode` | — | **YES.** `MoneyAmount.kt:23`: `val currency: CurrencyCode`. |
| `CurrencyAssumption` enum | — | **ADDED.** Tracks why a currency was assigned, preventing silent EUR defaults. |

**Verdict: PARTIALLY RESOLVED** — The building blocks exist (`CurrencyCode`, `CurrencyAssumption`) and are used in domain models, but haven't been pushed down to repository/settings/DAO boundaries yet.

**Remaining work:**
- Use `CurrencyCode.parse()` in `CurrencySettingsRepositoryImpl.setHomeCurrency()` to reject invalid codes.
- Update `CurrencyConverter` to accept `CurrencyCode` parameters.
- Update `ExchangeRateStore` to use `CurrencyCode`.
- Consider adding ISO 4217 allowlist validation to `CurrencyCode.parse()`.

---

### 🔴 ISSUE 8 — Rate refresh sets `lastRateUpdate` even with zero rates — STILL PRESENT

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| Unconditional `setLastRateUpdate` | Sets timestamp regardless of `rates.size` | **UNCHANGED.** `CurrencyRatesRepositoryImpl.kt:92-93`: `currencyConverter.storeRates(rates, source = "ecb")` then `currencySettingsRepository.setLastRateUpdate(timeProvider.now())` — no `rates.isNotEmpty()` guard. |
| ViewModel-level guard | — | **PARTIAL.** `CurrencyManagementViewModel.refreshRates()` checks `refreshedCount <= 0` and throws, but this is at the UI layer, not the repository. |

**Verdict: STILL PRESENT** — The repository-level defect remains. Callers other than the ViewModel (e.g., a background worker) could trigger the same issue.

**Remaining work:**
- In `CurrencyRatesRepositoryImpl.refresh()`, guard `setLastRateUpdate()` with `if (rates.isNotEmpty())`.
- Also verify minimum expected coverage (e.g., at least `PRIORITY_CURRENCIES.size` rates).

---

### 🔴 ISSUE 9 — `lastRateUpdate` stored separately from rate rows — STILL PRESENT

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| Storage location | DataStore for timestamp, Room for rates | **UNCHANGED.** Same split architecture. |
| `areRatesStale()` | Checks DataStore, not Room | **UNCHANGED.** `CurrencySettingsRepositoryImpl.kt:60-65`: reads `lastRateUpdate` from DataStore only. |
| DB-sourced freshness | Not present | **NOT ADDED.** No `SELECT MAX(lastUpdated) FROM exchange_rates` query. |
| Refresh batch metadata | Not present | **NOT ADDED.** No `ExchangeRateRefreshBatch` entity. |

**Verdict: STILL PRESENT** — Architecture unchanged. The DataStore-timestamp/Room-rates split remains a drift risk.

**Remaining work:**
- Add `SELECT MAX(lastUpdated) FROM exchange_rates` to `ExchangeRateDao`.
- Use DB query as source-of-truth in `areRatesStale()`.
- Or add an `ExchangeRateRefreshBatch` metadata table in Room.

---

### 🟠 ISSUE 10 — `getAllRatesForBase(baseCurrency)` naming confusing — STILL PRESENT

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| Method name | `getAllRatesForBase(baseCurrency)` — ambiguous direction | **UNCHANGED.** `ExchangeRateDao.kt:26`, `ExchangeRateContracts.kt:23`, `CurrencyConverter.kt:238` all still use the same name. |
| `getRatesToCurrency()` | Recommended | **NOT ADDED.** |
| `getRatesFromCurrency()` | Recommended | **NOT ADDED.** |

**Verdict: STILL PRESENT** — No rename. The query is `WHERE toCurrency = :baseCurrency`, meaning "all rates *to* this currency." The name could be interpreted as "rates *from* this base."

**Remaining work:**
- Rename to `getRatesToCurrency(targetCurrency: String)`.
- Add `getRatesFromCurrency(sourceCurrency: String)` if needed.
- Update all callers.

---

### 🟡 ISSUE 11 — Money uses `Double` — PARTIALLY RESOLVED

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| Wrapper type | No domain money type | **ADDED.** `MoneyAmount`, `MoneyAggregate`, `MoneyBucket`, `ConvertedMoney` all wrap amounts with currency. |
| Internal representation | — | **STILL `Double`.** `MoneyAmount.amount` is `Double` (MoneyAmount.kt:22). No `Long` minor-unit representation. |
| Rate precision | `Double` for rates | **STILL `Double`.** `ExchangeRate.rate` is `Double`. No `BigDecimal`. |

**Verdict: PARTIALLY RESOLVED** — Domain types prevent currency-less amounts, but `Double` precision risks remain. The wrapper types make future migration to `Long`/`BigDecimal` easier since they encapsulate the internal representation.

**Remaining work:**
- Migrate `MoneyAmount` to `Long` minor units internally.
- Use `BigDecimal` for exchange rates.
- Add rounding modes for conversion (HALF_UP, etc.).

---

### 🟡 ISSUE 12 — Formatting too simple — PARTIALLY RESOLVED

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| `CurrencyConverter.formatAmount()` | `$symbol + String.format("%.2f")` — no locale, no JPY handling | **UNCHANGED.** `CurrencyConverter.kt:251-255`: still the same simple format. |
| New `CurrencyFormatter` | — | **ADDED.** `CurrencyFormatter.kt`: uses `NumberFormat.getCurrencyInstance(locale)` with `java.util.Currency`, configurable fraction digits, compact/signed variants. |
| `MoneyAmount.formatDisplay()` | — | **STILL SIMPLE.** `MoneyAmount.kt:36`: uses `${CurrencyCode.symbolFor(currency)}${String.format("%.2f", amount)}` — same issue. |
| `MoneyFormatUtils` extensions | — | **ADDED.** Bridges `MoneyAmount` to `CurrencyFormatter` for proper formatting. |
| Old `CurrencyConverter.formatAmount()` deprecated? | — | **NOT DEPRECATED.** No `@Deprecated` annotation, still callable. |

**Verdict: PARTIALLY RESOLVED** — A proper `CurrencyFormatter` exists, but the old `CurrencyConverter.formatAmount()` is not deprecated, and `MoneyAmount.formatDisplay()` doesn't use it.

**Remaining work:**
- Deprecate `CurrencyConverter.formatAmount()` and point to `CurrencyFormatter.formatMoney()`.
- Fix `MoneyAmount.formatDisplay()` to delegate to `CurrencyFormatter` for locale-aware formatting.
- Handle JPY zero-decimal-place formatting in `CurrencyFormatter` (currently hardcodes `minimumFractionDigits = if (showCents) 2 else 0` — should use `Currency.getDefaultFractionDigits()`).

---

### 🟢 ISSUE 13 — HRK in supported currency list — STILL PRESENT

| Aspect | Analysis finding | Current codebase |
|---|---|---|
| `SupportedCurrency.HRK` | Present | **STILL PRESENT.** `CurrencyConverter.kt:29`: `HRK("HRK", "kn", "Croatian Kuna")`. |
| `CurrencyCode.HRK` | — | **PRESENT.** `CurrencyCode.kt:42`: `val HRK = CurrencyCode("HRK")`. |
| `isActive` metadata | Recommended | **NOT ADDED.** No currency lifecycle metadata. |
| Legacy marking | Recommended | **NOT ADDED.** No way to hide HRK from new-entry UI. |

**Verdict: STILL PRESENT** — HRK remains in the active supported list with no legacy marking.

**Remaining work:**
- Add `isActive: Boolean`, `validFrom`, `validTo` metadata or a dedicated `LegacyCurrency` list.
- Allow HRK for historical import/viewing but hide from new-expense currency picker.
- Consider similar treatment for other legacy currencies (e.g., pre-Euro national currencies that users may have historical data in).

---

## New Issues Discovered During Review

---

### 🟠 ISSUE 14 (NEW) — `DomainExchangeRate` doesn't include `validDate`

**File:** `ExchangeRateContracts.kt:8-14` + `ExchangeRateStoreAdapter.kt:44-61`

`ExchangeRate` entity has `validDate: Long`, but `DomainExchangeRate` does not. The adapter mappers (`toDomain()` / `toEntity()`) don't propagate `validDate`. Adding the column without plumbing it through the domain layer means it will always be `0L` and never used.

**Fix:** Add `val validDate: Long = 0L` to `DomainExchangeRate` and update both mapper functions.

---

### 🟡 ISSUE 15 (NEW) — `CurrencyConverter` doesn't use `CurrencyCode` type

**File:** `CurrencyConverter.kt`

All method signatures still use raw `String` for currency parameters despite `CurrencyCode` being available. Example: `convert(amount: Double, fromCurrency: String, toCurrency: String)`. This bypasses the validation in `CurrencyCode`'s constructor.

**Fix:** Overload or replace `convert()` with a variant accepting `CurrencyCode`. Keep String overloads for backward compatibility with `@Deprecated` annotation.

---

### 🟢 ISSUE 16 (NEW) — `CurrencyRatesRepositoryImpl` computes N×N rate pairs

**File:** `CurrencyRatesRepositoryImpl.kt:79-89`

The ECB refresh computes rates for all combinations of supported currencies (Cartesian product). For ~20 priority currencies, this produces ~380 rate rows. Most of these pairs will never be queried. While not a correctness bug, this wastes storage and I/O.

**Fix:** Consider lazy rate generation — only store EUR→X and X→EUR rates (linear), computing cross-rates at query time via EUR triangulation (which `CurrencyConverter.convert()` already does as fallback).

---

### 🟠 ISSUE 17 (NEW) — Unchecked cast in `MultiCurrencyRepository.aggregateCurrencyTotalsToMoneyAggregate`

**File:** `MultiCurrencyRepository.kt:476-500`

```kotlin
private suspend fun aggregateCurrencyTotalsToMoneyAggregate(
    buckets: List<*>,
    homeCurrency: String
): MoneyAggregate {
    ...
    for (bucket in buckets) {
        when (bucket) {
            is CategoryCurrencyTotal -> { ... }
            is MerchantCurrencyTotal -> { ... }
            is MonthlyCurrencyTotal -> { ... }
        }
    }
```

If `buckets` contains a new type not listed in the `when`, it silently produces an empty aggregate with zero total — no warning, no error. This could produce wrong financial totals if a new `*CurrencyTotal` type is added to the DAO but the `when` is not updated.

**Fix:** Add an `else -> Timber.w(...)` branch, or better, make `aggregateCurrencyTotalsToMoneyAggregate` a sealed-interface method on a common supertype.

---

### 🟡 ISSUE 18 (NEW) — `getTotalSpentFlow()` is currency-unsafe and not deprecated

**File:** `ExpenseDao.kt:256`

```kotlin
@Query("SELECT SUM(${EFFECTIVE_AMOUNT_SQL}) FROM expenses WHERE ${SPENDING_TYPE_SQL} AND isNotMine = 0")
fun getTotalSpentFlow(): Flow<Double?>
```

This reactive Flow is used for real-time dashboard totals but has no `@Deprecated` annotation and no currency grouping. It raw-sums across all currencies.

**Fix:** Deprecate and replace with a Flow variant of `getTotalSpentBetweenByCurrency` or a `MultiCurrencyRepository`-style reactive method.

---

### 🟢 ISSUE 19 (NEW) — `CurrencyFormatter` doesn't use `Currency.getDefaultFractionDigits()`

**File:** `CurrencyFormatter.kt:80-87`

```kotlin
minimumFractionDigits = if (showCents) 2 else 0
maximumFractionDigits = if (showCents) 2 else 0
```

This hardcodes 2 decimal places. JPY (and other zero-decimal currencies) should have 0 fraction digits. `java.util.Currency.getDefaultFractionDigits()` returns the correct value per ISO 4217.

**Fix:** Use `currency.defaultFractionDigits` as the initial value, with `showCents` as an override for cases where the user explicitly wants cents.

---

## Summary of Status Counts

| Status | Count | Issues |
|---|---|---|
| **RESOLVED** | 0 | — |
| **PARTIALLY RESOLVED** | 7 | #1, #3, #5, #7, #11, #12, #5 |
| **STILL PRESENT** | 6 | #2, #4, #6, #8, #9, #10, #13 |
| **NEW** | 6 | #14, #15, #16, #17, #18, #19 |

---

## Priority Action Items

### 🔴 Critical — Must fix before production use with multiple currencies

1. **Issue #2 + #14:** Add `validDate` to domain layer + remove pair-unique constraint + add `getRateAsOf()`.
2. **Issue #1:** Populate `Expense` base-amount fields at creation time.
3. **Issue #8:** Guard `setLastRateUpdate()` with `rates.isNotEmpty()`.

### 🟠 High — Should fix in next iteration

4. **Issue #4:** Add `convertAsOf()` and `createMoneySnapshot()` to `CurrencyConverter`.
5. **Issue #18:** Deprecate `getTotalSpentFlow()`.
6. **Issue #6:** Add re-normalization path for home currency changes.
7. **Issue #9:** Use Room as source-of-truth for rate freshness.

### 🟡 Medium — Quality improvements

8. **Issue #15:** Adopt `CurrencyCode` in `CurrencyConverter` method signatures.
9. **Issue #17:** Fix silent failure in `aggregateCurrencyTotalsToMoneyAggregate`.
10. **Issue #12:** Deprecate `CurrencyConverter.formatAmount()`, fix `MoneyAmount.formatDisplay()`.
11. **Issue #19:** Use `Currency.getDefaultFractionDigits()` in `CurrencyFormatter`.

### 🟢 Low/Nice-to-have

12. **Issue #10:** Rename `getAllRatesForBase()` to `getRatesToCurrency()`.
13. **Issue #13:** Mark HRK as legacy.
14. **Issue #16:** Consider lazy cross-rate computation.

---

## Regression Tests to Add/Verify

| # | Test | Current coverage |
|---|---|---|
| 1 | `€10 + $10` is not raw-summed as `20` | ✅ Covered by `MultiCurrencyRepository` grouped-by-currency approach |
| 2 | Dashboard total uses converted base/display currency | ⚠️ `getTotalSpentFlow()` is still raw-sum — #18 |
| 3 | Category total grouped by currency converts correctly | ✅ Covered by `getCategoryTotalsInHomeCurrency()` |
| 4 | Budget in EUR compares correctly against USD expense | ⚠️ Depends on `BudgetRepository` migration to `MultiCurrencyRepository` |
| 5 | Historical report does not change after latest rate update | ❌ Not possible without #2 (historical rate storage) and #1 (snapshots) |
| 6 | Expense stores conversion snapshot at creation | ❌ Not implemented — #1 |
| 7 | Missing exchange rate marks conversion as failed, not 1:1 | ✅ `ConversionStatus.FAILED_MISSING_RATE` exists, `MoneyAggregate.isPartial` |
| 8 | `PlannedExpense` with USD appears correctly in forecast | ⚠️ Currency field exists (#5 partially resolved), but normalized amounts missing |
| 9 | Changing display currency does not mutate stored original amount | ❌ No separation — #6 |
| 10 | `setHomeCurrency("bad")` is rejected | ❌ Validation not wired at boundary — #7 |
| 11 | Empty rate refresh does not update `lastRateUpdate` | ❌ Not guarded — #8 |
| 12 | Cleared rates make staleness reflect missing rates | ❌ DataStore/Room drift — #9 |
| 13 | JPY formatting does not force `.00` | ❌ `CurrencyFormatter` hardcodes 2 decimal places — #19 |
| 14 | HRK is treated as legacy | ❌ Not implemented — #13 |
| 15 | `getRatesToCurrency` / `getRatesFromCurrency` semantics tested separately | ❌ Not implemented — #10 |

---

## Conclusion

The codebase has made significant progress on **domain modeling** (PR 1 of the recommended fix order): `CurrencyCode`, `MoneyAmount`, `MoneyAggregate`, `ConversionStatus`, and `CurrencyAssumption` are all solid foundations. The DAO deprecation campaign (PR 4) and `MultiCurrencyRepository` bridge are well done.

However, the **data-integrity critical paths** are still unfixed:
- Historical conversion snapshots are schema-only (PR 2 not done).
- Historical rate storage still blocked by the old unique constraint (PR 3 not done).
- No separation of display vs. accounting currency (PR 6 not done).

**Priority for next iteration: Fix issues #1, #2, #8.** These three are prerequisites for safe multi-currency operation.
