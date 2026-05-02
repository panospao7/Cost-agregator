# Deep review — commit `0e43d6fc456546a030772e368d84ccaa613eafa2`

## Commit summary

Commit message:

> Phase 10 follow-up: forecast engines migrated to `RecurringOccurrence` infrastructure.

Files changed:

- `RecurringOccurrence.kt`
- `CashFlowModule.kt`
- `CashFlowCalculator.kt`
- `FinancialStressForecastEngine.kt`
- `SynthesisEngine.kt`
- `phase10_followup_review.md`

The commit tries to move recurring forecast logic away from ad-hoc pattern expansion and toward the materialized `recurring_occurrences` table.

## Overall verdict

**Useful and strategically correct, but not safe to mark complete.**

It improves architectural consistency by making more forecasting surfaces consume canonical occurrence rows, but there are several correctness risks:

1. `FinancialStressForecastEngine` sums **all occurrence statuses**, including skipped/cancelled/ignored/missed.
2. Occurrence amounts are still summed as raw `Double` without FX normalization.
3. Forecast/read paths now perform DB writes by calling `generateOccurrences()`.
4. Generation failures are silently swallowed and can understate obligations.
5. `SynthesisEngine` can hide manual recurring bills if occurrence rows are missing/stale.
6. `runBlocking` was introduced inside `SynthesisEngine.calculateBlockPartyData()`.
7. No tests were added.

So this commit should be treated as **partial Phase 10 hardening**, not final closure.

---

# What the commit does well

## 1. Moves `CashFlowCalculator.getUpcomingBills()` toward canonical occurrences

Before, upcoming bills were based on `RecurringPattern.nextExpectedDate`, which only represents one predicted date per pattern.

Now manual recurring rules are:

1. expanded through `RecurringLifecycleCoordinator.generateOccurrences(...)`;
2. queried through `RecurringOccurrenceDao.getByDateRange(...)`;
3. filtered to `PLANNED`;
4. mapped to `RecurringPattern`.

That is conceptually better because a weekly rule can produce multiple concrete upcoming bills in a window.

### Good outcome

This reduces the risk of missing repeated occurrences inside a multi-day window.

---

## 2. Moves `FinancialStressForecastEngine.calculateRecurringOutflows()` toward occurrence infrastructure

The engine now generates materialized occurrences for manual rules and sums occurrence amounts for the horizon.

That is directionally right. Stress forecast should not independently reinvent recurrence expansion.

---

## 3. Adds `RecurringOccurrence.toRecurringPattern()`

This gives shared mapping from persisted occurrence rows to the existing domain model.

That reduces local duplicated mapping code.

---

## 4. Makes `SynthesisEngine` block-party calendar occurrence-aware

`calculateBlockPartyData()` now uses materialized occurrences when a `RecurringOccurrenceDao` is available.

This can improve calendar accuracy for manual recurring rules because the UI can show actual occurrence dates instead of approximate day-matching.

---

# Major issues

## P0 — `FinancialStressForecastEngine` counts invalid statuses

The KDoc says the engine should sum PAID and PLANNED occurrences, but the code filters only by source type and source ID.

That means these can be counted as future obligations:

- `SKIPPED`
- `CANCELLED`
- `IGNORED`
- maybe `MISSED`, depending on intended semantics

This is a serious financial correctness bug.

### Current behavior

The engine does roughly:

```kotlin
recurringOccurrenceDao.getByDateRange(startDate, endDate)
    .filter { it.sourceType == RECURRING_RULE && it.sourceId in ruleIds }
    .sumOf { it.expectedAmount }
```

### Required fix

Filter status explicitly:

```kotlin
private val obligationStatuses = setOf("PLANNED", "PAID")
```

Then:

```kotlin
.filter {
    it.sourceType == RecurringLifecycleCoordinator.SOURCE_TYPE_RECURRING_RULE &&
    it.sourceId in ruleIds &&
    it.status in obligationStatuses
}
```

You also need to decide what `MISSED` means:

- If missed bill is still owed, count it separately as overdue.
- If missed means no longer expected, exclude it.
- Do not let it be ambiguous.

---

## P0 — recurring occurrence amounts are not currency-normalized

The stress engine normalizes historical deposits and expenses to `displayCurrency`, but recurring occurrence amounts are summed raw.

That means projected balance can mix units:

- normalized expenses in display currency
- normalized deposits in display currency
- raw recurring occurrence amounts in their original currency

Example:

- display currency: EUR
- salary/deposits normalized to EUR
- recurring bill: USD 100
- engine subtracts `100` as if EUR 100

This breaks Phase 10’s currency-normalization objective.

### Required fix

Normalize each occurrence amount before summing:

- Use `expectedAmount` / `expectedCurrency` for `PLANNED`.
- Prefer `paidAmount` / `paidCurrency` for `PAID` if present.
- Convert to `displayCurrency`.
- If conversion fails, return degraded quality or conservative risk, not silent undercounting.

Pseudo-shape:

```kotlin
private suspend fun occurrenceAmountInDisplayCurrency(
    occurrence: RecurringOccurrence,
    displayCurrency: String
): Double? {
    val amount = if (occurrence.status == "PAID" && occurrence.paidAmount != null) {
        occurrence.paidAmount
    } else {
        occurrence.expectedAmount
    }

    val currency = if (occurrence.status == "PAID" && occurrence.paidCurrency != null) {
        occurrence.paidCurrency
    } else {
        occurrence.expectedCurrency
    }

    return convert(amount, currency, displayCurrency)
}
```

---

## P0 — occurrence generation failures are silently swallowed

Both `CashFlowCalculator` and `FinancialStressForecastEngine` catch exceptions from `generateOccurrences()` and continue without logging or fallback.

This is dangerous because a failed rule can vanish from forecasts.

Failure scenarios:

- restore maintenance mode blocks writes
- database issue
- invalid rule
- migration mismatch
- corrupt recurring rule data

### Current effect

The user may see a lower-risk forecast because obligations were omitted.

### Required fix

At minimum:

1. log the failure with rule ID;
2. fallback to ad-hoc expansion for that specific rule;
3. attach data-quality warning if the surface supports it.

For stress forecast, undercounting recurring bills is worse than overcounting. Conservative fallback is preferred.

---

## P0 — read/forecast paths now mutate the database

`getUpcomingBills()` and `computeStressForecast()` now call `generateOccurrences()`, which writes to DB.

That means a read-like calculation can:

- write occurrence rows;
- fail during restore maintenance mode;
- cause side effects from UI refresh;
- do repeated work across 30/60/90 horizons;
- create subtle race behavior with workers.

This may be acceptable if materialization is intentionally lazy, but it needs to be explicitly controlled.

### Better architecture

Use a dedicated “ensure occurrences” boundary:

```kotlin
OccurrenceMaterializationUseCase.ensureGenerated(start, end)
```

Then forecast engines should mostly read.

For stress forecast, generate once for the max horizon, not once per horizon:

```kotlin
val maxEnd = horizon90End
ensureOccurrences(nowStart, maxEnd)
```

Then query subsets.

---

## P0 — `SynthesisEngine` can drop manual recurring bills if occurrences are missing

In production, `SynthesisEngine` will likely receive a non-null `RecurringOccurrenceDao`, so `calculateBlockPartyData()` takes the occurrence path.

But `buildRecurringByDayFromOccurrences()` only queries existing occurrence rows. It does **not** generate missing rows.

If occurrence rows are missing/stale, manual recurring bills disappear from block-party days.

This is a regression versus the old legacy matcher, which used `RecurringPattern` directly.

### Required fix

Either:

1. generate month occurrences before block-party calculation; or
2. if no occurrence rows are found for some manual rule IDs, fallback to legacy matching for those missing rules; or
3. make `calculateBlockPartyData()` accept a precomputed occurrence list produced by `ForecastInputAssembler`.

Best option:

```kotlin
manualPatternsWithOccurrences -> occurrence path
manualPatternsMissingOccurrences -> legacy fallback
detectedPatterns -> legacy fallback
```

Do not make the presence of DAO alone disable manual fallback.

---

## P1 — `runBlocking` inside `SynthesisEngine`

`calculateBlockPartyData()` is not suspend, so the commit uses `runBlocking` to query Room.

This is risky if called from the main thread. Even if Room moves suspend queries to its executor, `runBlocking` still blocks the caller until completion.

### Better options

- make `calculateBlockPartyData()` suspend;
- pass occurrences in as part of forecast input;
- prefetch occurrence rows in the use case/ViewModel layer;
- create `BlockPartyInputAssembler`.

---

## P1 — detected fallback is mostly dead in `CashFlowCalculator` and `FinancialStressForecastEngine`

Both use:

```kotlin
recurringPatternsProvider.getConfirmedPatterns()
```

But `MergedRecurringPatternsProvider.getConfirmedPatterns()` returns active manual recurring rules only. Detected patterns come from `getPatterns()` / `getPatternsFromSnapshots()`.

So the new “detected-only fallback” branches in:

- `CashFlowCalculator.getUpcomingBills()`
- `FinancialStressForecastEngine.calculateRecurringOutflows()`

are probably unused.

This is not a correctness bug if those engines intentionally only use manual confirmed rules. But the code/comment currently implies detected fallback is active.

### Fix

Either:

- change comments to say “manual-only”; or
- use a provider method that returns manual + detected patterns.

---

## P1 — inconsistent end-boundary semantics

DAO queries use exclusive end:

```sql
dueDate >= :start AND dueDate < :end
```

But `FinancialStressForecastEngine.expandDetectedPatterns()` uses:

```kotlin
while (nextDate in startDate..endDate)
```

That is inclusive end.

So manual occurrence expansion and detected fallback can disagree for a bill exactly at `endDate`.

### Fix

Use exclusive end everywhere:

```kotlin
while (nextDate >= startDate && nextDate < endDate)
```

Also compute horizon end from day boundaries, not `now + N * DAY_IN_MILLIS`, if the intended horizon is calendar days.

---

## P1 — `CashFlowCalculator.calculateDailyCashFlow()` was not migrated

The commit updates `getUpcomingBills()`, but `calculateDailyCashFlow()` still uses the old pattern check:

```kotlin
pattern.nextExpectedDate falls on current day
```

This only captures one next expected date. It does not materialize all future occurrences over the requested range.

So the cashflow engine is only partially migrated.

### Fix

Use occurrence rows for predicted recurring items inside `calculateDailyCashFlow()` too.

---

## P1 — `toRecurringPattern()` loses useful occurrence semantics

`RecurringOccurrence.toRecurringPattern()` maps:

- `expectedAmount` to `averageAmount`
- `expectedCurrency` to `currency`
- `dueDate` to `nextExpectedDate`
- `sourceId` to `id`
- confidence to `1.0f`

This is okay as a compatibility adapter, but it discards:

- occurrence status
- occurrence key
- paid amount/currency
- linked expense ID

For UI bill lists, that information can matter.

### Recommendation

For UI surfaces, consider a dedicated domain model:

```kotlin
UpcomingBillOccurrence
```

with fields:

- occurrenceKey
- ruleId
- dueDate
- status
- expectedMoney
- paidMoney?
- merchant
- categoryId
- linkedExpenseId

Use `RecurringPattern` only when truly modeling a recurrence pattern, not a concrete occurrence.

---

## P1 — no tests added

This commit changes financial forecast behavior without adding test coverage.

Minimum tests needed:

### `FinancialStressForecastEngine`

- PLANNED occurrence counted.
- PAID occurrence counted.
- SKIPPED/CANCELLED/IGNORED excluded.
- MISSED behavior explicitly tested.
- mixed-currency recurring occurrence converted to display currency.
- generation failure falls back or marks degraded.
- exclusive end boundary.

### `CashFlowCalculator`

- weekly rule produces multiple upcoming bills.
- PAID occurrence excluded from upcoming bills.
- skipped/cancelled occurrences excluded.
- results sorted by due date.
- generation failure fallback.

### `SynthesisEngine`

- block-party uses occurrences when present.
- block-party falls back to legacy for missing occurrence rows.
- detected patterns still appear.
- DAO path does not block UI or is moved to suspend use case.

---

# Updated Phase 10 status after this commit

## Improved

- More surfaces are occurrence-aware.
- `CashFlowCalculator.getUpcomingBills()` is better for manual rules.
- `FinancialStressForecastEngine` no longer has to manually expand every confirmed rule.
- Block-party calendar can now use canonical occurrence rows.
- Shared mapper added.

## Still incomplete

Phase 10 is still not fully closeable because:

1. occurrence status filtering is wrong in stress forecast;
2. recurring occurrence currency normalization is still missing;
3. generation failures silently undercount obligations;
4. `SynthesisEngine` can regress when occurrence rows are absent;
5. `runBlocking` was introduced;
6. cashflow migration is partial;
7. test coverage is absent.

Recommended status:

> **Phase 10: stronger, but still incomplete.**  
> This commit is a good architectural step, but it needs a hardening follow-up before closure.

---

# Priority fix list

## P0

1. Filter occurrence statuses in `FinancialStressForecastEngine`.
2. Normalize occurrence amounts to display/home currency.
3. Replace silent catch blocks with logging + fallback/degraded quality.
4. Add block-party fallback when occurrence rows are missing.
5. Add tests for status, currency, and missing-occurrence behavior.

## P1

1. Make block-party occurrence query suspend or preloaded; avoid `runBlocking`.
2. Migrate `calculateDailyCashFlow()` to occurrence rows.
3. Use exclusive end bounds consistently.
4. Sort upcoming bills by due date.
5. Clarify whether detected patterns should be included in stress/cashflow.

## P2

1. Move `RecurringOccurrence.toRecurringPattern()` out of the entity file into a mapper.
2. Add a concrete occurrence domain model instead of overloading `RecurringPattern`.
3. Generate occurrences once per max horizon instead of once per 30/60/90 calculation.

---

# Final verdict

Commit `0e43d6f` is a **good direction commit**, but it is not a completion commit.

It moves important forecast surfaces toward the canonical `RecurringOccurrence` infrastructure, which aligns with Phase 10’s dedup/correctness goals. However, it also expands the blast radius of occurrence correctness. Because status filtering, currency normalization, fallback behavior, and tests are still weak, I would not mark Phase 10 complete after this commit.

Safe label:

> **Phase 10 follow-up: partially successful, requires P0 hardening before closure.**

---

# Sources reviewed

- Commit: https://github.com/panospao7/Cost-agregator/commit/0e43d6fc456546a030772e368d84ccaa613eafa2
- `RecurringOccurrence.kt`: https://github.com/panospao7/Cost-agregator/blob/0e43d6fc456546a030772e368d84ccaa613eafa2/app/src/main/java/com/yourname/expensetracker/data/database/entity/RecurringOccurrence.kt
- `CashFlowCalculator.kt`: https://github.com/panospao7/Cost-agregator/blob/0e43d6fc456546a030772e368d84ccaa613eafa2/app/src/main/java/com/yourname/expensetracker/domain/cashflow/CashFlowCalculator.kt
- `FinancialStressForecastEngine.kt`: https://github.com/panospao7/Cost-agregator/blob/0e43d6fc456546a030772e368d84ccaa613eafa2/app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt
- `SynthesisEngine.kt`: https://github.com/panospao7/Cost-agregator/blob/0e43d6fc456546a030772e368d84ccaa613eafa2/app/src/main/java/com/yourname/expensetracker/domain/logic/SynthesisEngine.kt
- `RecurringLifecycleCoordinator.kt`: https://github.com/panospao7/Cost-agregator/blob/0e43d6fc456546a030772e368d84ccaa613eafa2/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt
- `RecurringOccurrenceDao.kt`: https://github.com/panospao7/Cost-agregator/blob/0e43d6fc456546a030772e368d84ccaa613eafa2/app/src/main/java/com/yourname/expensetracker/data/database/dao/RecurringOccurrenceDao.kt
- `MergedRecurringPatternsProvider.kt`: https://github.com/panospao7/Cost-agregator/blob/0e43d6fc456546a030772e368d84ccaa613eafa2/app/src/main/java/com/yourname/expensetracker/domain/forecasting/MergedRecurringPatternsProvider.kt
- `RecurringExpenseRepository.kt`: https://github.com/panospao7/Cost-agregator/blob/0e43d6fc456546a030772e368d84ccaa613eafa2/app/src/main/java/com/yourname/expensetracker/data/repository/RecurringExpenseRepository.kt