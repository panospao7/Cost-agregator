# P1+P2 Verification Review — 2026-05-03

## P1-1: CashFlowCalculator occurrence-driven prediction (FCST-11)

**File:** `app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt`

**Requirement:** `calculateDailyCashFlow()` must use `RecurringOccurrenceDao` / `generateOccurrences()` instead of ad-hoc `nextExpectedDate` matching.

**Finding:** ✅ **RESOLVED**

- Line 45: `private val recurringOccurrenceDao: RecurringOccurrenceDao` constructor injection
- Lines 83-91: Iterates over manual rules, calls `recurringLifecycleCoordinator.generateOccurrences(ruleId, startTime, endTime)` to materialise occurrences
- Lines 93-102: Queries `recurringOccurrenceDao.getByDateRange(startTime, endTime)` filtered by `SOURCE_TYPE_RECURRING_RULE`, `sourceId in ruleIds`, and `status == "PLANNED"`
- Lines 104-116: Builds day-indexed map from occurrences (`yyyy-MM-dd` → patterns)
- Lines 181-182: Path 1 uses occurrence-driven predictions from manual rules
- Lines 184-192: Path 2 (detected-only patterns without manual rules) uses ad-hoc `nextExpectedDate` as documented fallback

Also confirmed in `getUpcomingBills()` (lines 256-262): same occurrence-driven approach.

---

## P1-2: paidById same-group trigger (SHR-7, DB-3)

**File:** `app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt`

**Requirement:** MIGRATION_108_109 must contain a DB trigger enforcing `paidById` same-group constraint on `group_expenses`.

**Finding:** ✅ **RESOLVED**

- Lines 6687-6699: MIGRATION_108_109 header documents batches R+S including "S1: group_expenses paidById same-group trigger"
- Lines 6856-6866: Trigger SQL present:

```sql
CREATE TRIGGER IF NOT EXISTS enforce_paid_by_same_group
BEFORE INSERT ON group_expenses
BEGIN
    SELECT CASE WHEN (
        SELECT groupId FROM group_members WHERE id = NEW.paidById
    ) != NEW.groupId
    THEN RAISE(ABORT, 'paidById must belong to same group') END;
END
```

- Lines 6868-6876: FK integrity verification (`PRAGMA foreign_key_check`) after migration
- Line 6700: Migration object registered at `MIGRATION_108_109 = object : Migration(108, 109)`
- Line 7385: Included in the migrations array

This resolves both **SHR-7** (paidById cross-group not DB-enforced) and **DB-3** (paidById same-group enforcement out of scope).

---

## P1-3: CurrencyConverter.convertAsOf() (CURR-4)

**File:** `app/src/main/java/com/yourname/expensetracker/domain/currency/CurrencyConverter.kt`

**Requirement:** `convertAsOf()` must exist with `atMillis: Long` parameter for historically-accurate conversions.

**Finding:** ✅ **RESOLVED**

- Lines 144-155: Full KDoc documenting `convertAsOf()` behaviour, historical rate lookup via `ExchangeRateDao.getRateAsOf`, and fallback strategy
- Lines 156-161: Method signature `suspend fun convertAsOf(amount: Double, fromCurrency: String, toCurrency: String, atMillis: Long): ConversionResult?`
- Lines 162-216: Full implementation:
  - Same-currency short-circuit (lines 162-171)
  - Direct rate via `exchangeRateStore.getRateAsOf(from, to, atMillis)` (lines 174-178)
  - EUR intermediate fallback via `exchangeRateStore.getRateAsOf()` (lines 191-201)
  - Null return with Timber warning on no rate found (lines 214-216)

This resolves **CURR-4** (convert() no date/context parameter).

---

## P2: KDoc verification for InsightsEngine + AnomalyDetector

### InsightsEngine.kt

**File:** `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`

**Finding:** ✅ **KDoc PRESENT**

- Lines 22-43: Class-level KDoc block with three cross-referenced sections:
  - `## AIML-11: Confidence propagation` — documents `ConfidenceRouter` integration and confidence thresholds
  - `## AIML-12: Stale category IDs` — documents `categoryMap` resolution and fallback to `category = null`
  - `## AIML-13: Duplicate-inflated trust` — documents recurring-expense suppression in `AnomalyDetector.detect` and adaptive multiplier

### AnomalyDetector.kt

**File:** `app/src/main/java/com/yourname/expensetracker/domain/analytics/AnomalyDetector.kt`

**Finding:** ✅ **KDoc PRESENT**

- Lines 11-64: Class-level KDoc block with:
  - Lines 12-30: Method descriptions (IQR, MAD, Contextual)
  - Lines 32-39: `## AI-2: Recurring-expense suppression (RESOLVED)` — documents `suppressRecurringMerchantKeys` parameter
  - Lines 41-47: `## AIML-11: Confidence propagation` — documents statistical-only approach
  - Lines 49-53: `## AIML-12: Stale category IDs` — documents caller-level resolution
  - Lines 55-63: `## AIML-13: Duplicate-inflated trust` — documents recurring suppression parameter

### Verified as already-fixed via KDoc
- **AIML-7** (Anomaly detector does not suppress known recurring bills): The `suppressRecurringMerchantKeys` parameter on `detect()` is documented as RESOLVED in KDoc (AI-2). ✅
- **AIML-11/AIML-12/AIML-13**: KDoc documentation exists explaining current mitigations.

---

## Registry Impact Summary

| Issue | Previous Status | New Status | Reason |
|-------|----------------|------------|--------|
| FCST-11 | STILL PRESENT | ✅ RESOLVED | `CashFlowCalculator.calculateDailyCashFlow()` uses occurrence-driven approach |
| SHR-7 | STILL PRESENT | ✅ RESOLVED | `enforce_paid_by_same_group` trigger in MIGRATION_108_109 |
| DB-3 | STILL PRESENT | ✅ RESOLVED | Same trigger resolves paidById enforcement |
| CURR-4 | STILL PRESENT | ✅ RESOLVED | `convertAsOf(atMillis)` implemented in `CurrencyConverter` |
| AIML-7 | STILL PRESENT | ✅ RESOLVED | `suppressRecurringMerchantKeys` parameter documented RESOLVED in KDoc |

**Net change:** +5 STILL PRESENT → RESOLVED

---

*Verified 2026-05-03 against actual codebase (deepseek-v4-pro review)*
