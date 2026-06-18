# Engine 5 Implementation Plan — Money / Time Primitives

Engine: **Money / Time Primitives**  
Current verdict: **YELLOW / red-leaning**  
Risk: **critical / foundational**

Goal:

> Fix the highest-value money/time correctness bugs without destabilizing analytics, dashboard, budget, export, groups, investment, tax, backup/restore, or database recovery work.

Do **not** start with `MoneyAmount` representation rewrite.  
Do **not** change global `CurrencyConverter` semantics.  
Do **not** add schema changes in early PRs.  
Do **not** combine time-period fixes with money-formatting/export fixes.

---

# Current main problems

1. `PeriodKind.CUSTOM` ignores explicit bounds.
2. `PeriodKind.CUSTOM` silently becomes last-30-days.
3. `LAST_7_DAYS` / `LAST_30_DAYS` are off by one calendar day.
4. Invalid dirty currency strings can still crash normalization paths.
5. `CurrencyFormatter.formatForExport()` silently converts NaN/Infinity to `0.00`.
6. Deprecated/default-EUR formatter APIs remain dangerous.
7. `RestoreJournal` still uses direct wall-clock time.
8. Week helpers are inconsistent.
9. `BudgetCalculator` still uses old `domain.model.PeriodRange`.
10. `MoneyAmount` still stores `Double`.
11. `MoneyAmount.fromBigDecimal()` is lossy.
12. raw `Double` money still dominates entities/UI/domain models.

---

# Guiding principle

Engine 5 fixes must be **compatibility-first**.

For now:

```text
dirty data -> partial/excluded/warning
not:
dirty data -> crash
```

and:

```text
ambiguous time period -> explicit error
not:
ambiguous time period -> silent fallback
```

---

# Affected pipelines

| Primitive | Affected pipelines |
|---|---|
| `PeriodKind` / `PeriodRange` | analytics, budget, export, dashboard, reports |
| `TimePeriodUtils` | analytics, recurring, budget, reports |
| `TimeProvider` | restore, workers, diagnostics, lifecycle events |
| `MoneyAggregate` / builder | analytics, dashboard, budget, groups, tax, investment, export |
| `CurrencyCode` | all currency validation |
| `CurrencyFormatter` | UI, CSV/export, business reports, tax reports |
| `MoneyAmount` | all future money-safe models |

---

# Recommended PR sequence

## PR0 — Baseline checkpoint

### Goal

Freeze current stable state before foundational primitive changes.

### Steps

```bash
git checkout -b engine5-money-time-hardening
git tag working-before-engine5
```

### Deliverables

- app currently boots
- DB backup exists
- CSV backup exists
- rescue disabled
- schema baseline untouched

---

# PR1 — PeriodKind correctness quick fix

## Closes

- `E5-NOW-001`
- `E5-NOW-002`
- `E5-NOW-003`
- parts of `M04`, `M12`

## Files

```text
PeriodKind.kt
PeriodRangeTest.kt
new PeriodKindContractTest.kt
analytics/budget period tests if present
```

## Current problem

`PeriodKind.toPeriodRange()` accepts:

```kotlin
customStart
customEnd
```

but ignores them.

`PeriodKind.CUSTOM` silently returns last-30-days.

`LAST_7_DAYS` covers 8 calendar dates.  
`LAST_30_DAYS` covers 31 calendar dates.

## Implementation

### 1. Make CUSTOM explicit

In `toPeriodRange(...)`:

```kotlin
if (this == PeriodKind.CUSTOM) {
    require(customStart != null && customEnd != null) {
        "CUSTOM period requires customStart and customEnd"
    }
    require(customEnd > customStart) {
        "CUSTOM period end must be after start"
    }

    return PeriodRange(
        kind = this,
        startInclusive = customStart,
        endExclusive = customEnd,
        zoneId = zoneId,
        label = "Custom"
    )
}
```

### 2. Make `toPeriodRangeZoned(CUSTOM)` throw

Do not silently return last-30-days.

```kotlin
PeriodKind.CUSTOM -> error(
    "CUSTOM period requires explicit bounds; use toPeriodRange(..., customStart, customEnd)"
)
```

### 3. Fix last-N calendar ranges

For calendar days including today:

```text
LAST_7_DAYS: start = today.minusDays(6), end = tomorrow start
LAST_30_DAYS: start = today.minusDays(29), end = tomorrow start
```

### 4. Preserve half-open intervals

All returned ranges remain:

```text
[startInclusive, endExclusive)
```

## Engine tests

```text
customPeriod_usesExplicitBounds()
customPeriod_withoutBoundsThrows()
customPeriod_endBeforeStartThrows()
customPeriodZonedWithoutBoundsThrows()
last7Days_containsExactly7LocalDatesIncludingToday()
last30Days_containsExactly30LocalDatesIncludingToday()
last7Days_matchesTimePeriodUtilsGetLastNCalendarDaysRange()
last30Days_matchesTimePeriodUtilsGetLastNCalendarDaysRange()
```

## Pipeline tests

```text
analytics_last7Days_has7DailyBuckets()
dashboard_last30Days_has30CalendarDays()
budget_customPeriod_usesExplicitBounds()
```

## Risk

Medium. User-visible date ranges change, but current behavior is objectively wrong.

No schema/Hilt impact.

---

# PR2 — Invalid currency becomes partial failure, not crash

## Closes

- `E5-NOW-004`
- parts of `M03`, `M07`, `M14`

## Files

```text
MoneyNormalizationEngine.kt
MoneyAggregateBuilder.kt
MoneyMappers.kt
CurrencyCode.kt maybe
ConversionFailure.kt maybe
tests
```

## Current problem

Some paths construct:

```kotlin
CurrencyCode(rawCurrency)
```

from dirty DB strings. Invalid strings like `"123"` can throw and crash analytics/export/budget.

## Implementation

### 1. Add explicit failure reason if feasible

Add:

```kotlin
INVALID_CURRENCY
```

to `FailureReason`.

If exhaustive `when` risks are high, use existing closest failure reason temporarily and document it. But explicit is better.

### 2. Safe parse before construction

Use:

```kotlin
val parsed = CurrencyCode.parse(raw)
if (parsed == null) {
    return excluded/failure(...)
}
```

### 3. Normalizer behavior

Invalid currency row should produce:

```text
ExcludedExpense / ConversionFailure
reason = INVALID_CURRENCY
isPartial = true
warning message includes raw code safely
```

It must **not** crash the whole aggregate.

### 4. Aggregate builder behavior

Invalid bucket currency should create failure entry, not throw.

### 5. Mapper behavior

Legacy failed conversions with bad currency should become safe failure metadata.

## Engine tests

```text
normalizer_invalidCurrency123_returnsExcludedNotThrow()
normalizer_invalidCurrency_marksPartial()
aggregateExpenses_invalidCurrency_returnsPartialAggregate()
aggregateBuilder_invalidBucketCurrency_failureNotCrash()
moneyMappers_invalidCurrency_doesNotThrow()
```

## Pipeline tests

```text
analytics_dirtyCurrencyRow_doesNotCrashScreen()
budget_dirtyCurrencyRow_marksPartial()
export_dirtyCurrencyRow_reportsWarningNotCrash()
```

## Risk

Medium-high because all aggregates consume this behavior.

No schema impact.

---

# PR3 — Formatter and export safety

## Closes

- `E5-NOW-006`
- `E5-NOW-007`
- parts of `M09`

## Files

```text
CurrencyFormatter.kt
MoneyAmount.kt maybe
export tests
business/tax report tests if affected
```

## Current problem

`CurrencyFormatter.formatForExport()` does:

```kotlin
if non-finite -> 0.00
```

This can turn corrupt financial data into plausible zero.

Also default-EUR APIs still exist and can mislabel invalid currency as EUR.

## Implementation

### 1. Stop non-finite-to-zero coercion

Change export formatting to either:

Option A:

```kotlin
require(amount.isFinite()) { "Cannot export non-finite monetary amount" }
```

Option B:

```kotlin
fun formatForExportOrNull(...): String?
```

Option C:

```kotlin
Result<String>
```

Recommended first: **throw/require** for export helpers and make export callers handle/report.

### 2. Split private implementation

Create private explicit formatter:

```kotlin
private fun formatExplicit(
    amount: Double,
    currencyCode: String,
    mode: FormatMode
): String
```

Deprecated wrappers call this, but explicit APIs should not rely on default-EUR overloads.

### 3. Invalid explicit currency must not become EUR

For invalid currency:

```text
show raw code or return error
not EUR symbol
```

### 4. Keep UI compatibility

Do not change every UI formatter in one PR. Prioritize export-stable correctness.

## Engine tests

```text
formatForExport_nanRejected()
formatForExport_infinityRejected()
formatMoney_invalidCurrency_doesNotShowEuro()
formatMoney_validEURStillWorks()
formatMoney_validUSDStillWorks()
formatMoney_validJPYStillWorks()
```

## Pipeline tests

```text
csvExport_nonFiniteMoneyFailsWithWarning()
businessReport_invalidMoneyNotExportedAsZero()
taxReport_invalidMoneyNotExportedAsZero()
```

## Risk

Medium. Export/report callers may need error handling.

No schema impact.

---

# PR4 — RestoreJournal TimeProvider

## Closes

- `E5-NOW-010`
- parts of `M10`

## Files

```text
RestoreJournal.kt
RestoreJournal tests
DI/factory call sites if any
```

## Current problem

`RestoreJournal` uses direct:

```kotlin
System.currentTimeMillis()
```

for journal entries and events.

## Implementation

### 1. Inject or pass `TimeProvider`

Preferred constructor:

```kotlin
class RestoreJournal(
    private val timeProvider: TimeProvider,
    ...
)
```

If this class is manually constructed, provide factory or default only in production wiring.

### 2. Replace all direct wall-clock calls

Use:

```kotlin
timeProvider.now()
```

for:

- journal start
- event occurredAt
- import markers
- fallback timestamps

### 3. Keep `SystemTimeProvider` as only production direct clock

Do not add more direct wall-clock elsewhere.

## Tests

```text
restoreJournal_beginUsesFakeTime()
restoreJournal_appendEventUsesFakeTime()
restoreJournal_fromJsonFallbackUsesFakeTime()
restoreJournal_successFailureMarkersUseFakeTime()
```

## Pipeline tests

```text
backupRestore_journalStillRecordsEvents()
restoreFailure_journalTimestampsDeterministic()
```

## Risk

Medium due constructor/DI call sites.

No schema impact.

---

# PR5 — Week helper split

## Closes

- `M11`

## Files

```text
TimePeriodUtils.kt
TimePeriodUtils tests
docs
```

## Current problem

Current helpers mix:

```text
week number: Monday + minimalDays=1
week-based year: ISO Monday + minimalDays=4
```

This can create invalid week keys around New Year.

## Implementation

Add explicit helpers:

```kotlin
getIsoWeekNumber(dateMillis, zoneId)
getIsoWeekBasedYear(dateMillis, zoneId)
getIsoWeekKey(dateMillis, zoneId)

getAppCalendarWeekNumber(dateMillis, zoneId)
getAppCalendarWeekYear(dateMillis, zoneId)
getAppCalendarWeekKey(dateMillis, zoneId)
```

Deprecate ambiguous old pairing.

Do not remove old helpers immediately.

## Tests

```text
isoWeekKey_2021_01_01_is_2020_W53()
isoWeekKey_2020_12_31_is_2020_W53()
appCalendarWeekKey_usesConsistentAppRules()
oldWeekHelpers_markedDeprecated()
```

## Pipeline tests

```text
analytics_weekGrouping_newYearBoundaryStable()
dashboard_weekKey_newYearBoundaryStable()
```

## Risk

Medium.

No schema impact.

---

# PR6 — Migrate BudgetCalculator to core PeriodRange

## Closes

- `E5-NOW-009`
- parts of `M05`

## Files

```text
BudgetCalculator.kt
budget call sites
budget tests
static guard tests
```

## Current problem

`BudgetCalculator` still imports:

```kotlin
domain.model.PeriodRange
```

while core time has:

```kotlin
domain.core.time.PeriodRange
```

## Implementation

### 1. Add new core API first

Do not break all callers immediately.

Example:

```kotlin
fun calculateCorePeriodRange(...): domain.core.time.PeriodRange
```

### 2. Migrate call sites

Update budget UI/domain consumers to use core range.

### 3. Preserve half-open boundaries

Core range should remain:

```text
[startInclusive, endExclusive)
```

### 4. Deprecate old type harder after migration

After no production imports remain:

```kotlin
@Deprecated(..., level = DeprecationLevel.ERROR)
```

## Tests

```text
budgetCalculator_returnsCorePeriodRange()
budgetPeriodRange_isHalfOpen()
budgetMonthlyRange_zoneAware()
noProductionImport_domain_model_PeriodRange()
```

## Pipeline tests

```text
budgetScreen_currentMonthStillLoads()
budgetRollover_usesSamePeriodBoundaries()
analyticsBudgetVsActual_periodStillMatchesBudget()
```

## Risk

Medium-high because budget is central.

No schema impact.

---

# PR7 — Currency policy APIs

## Closes

- `E5-NOW-005`
- `E5-NOW-008`
- parts of `M03`

## Files

```text
CurrencyCode.kt
SupportedCurrency.kt if present
CurrencySettings / validation call sites
tests
```

## Current problem

`CurrencyCode.parse("ZZZ")` accepts syntactically valid but unsupported code.

Some mappers silently fallback to EUR.

## Implementation

### 1. Split APIs by intent

Add:

```kotlin
parseIsoLike(raw: String): CurrencyCode?
parseSupported(raw: String): CurrencyCode?
parseActiveSupported(raw: String): CurrencyCode?
```

Meaning:

```text
parseIsoLike = syntax only
parseSupported = app-supported list
parseActiveSupported = allowed for new writes
```

### 2. Do not globally change existing `parse()` yet

Changing `parse()` globally may break diagnostics/legacy handling.

### 3. Migrate high-risk write paths first

Examples:

- subscription create
- investment create
- tax settings
- budget create
- currency settings

### 4. Replace silent legacy EUR fallback with structured result

Instead of:

```text
invalid -> EUR
```

use:

```text
Resolved(value)
LegacyAssumedEUR(warning)
InvalidCurrency(raw)
MissingCurrency
```

## Tests

```text
parseIsoLike_acceptsZZZ()
parseSupported_rejectsZZZ()
parseActiveSupported_rejectsInactiveCurrency()
legacyEurFallbackProducesWarning()
invalidCurrencyDoesNotBecomeSilentEUR()
```

## Pipeline tests

```text
settings_invalidHomeCurrencyShowsWarning()
budget_invalidCurrencyRejected()
investment_invalidCurrencyRejected()
taxSettings_invalidCurrencyRejected()
```

## Risk

Medium-high. Do after PR2.

No schema unless adding persisted validation metadata.

---

# PR8 — Static guardrails

## Closes

- parts of `M10`, `M14`, `M09`, `M05`

## Files

```text
static guard tests
docs
possibly buildSrc/scripts if already used
```

## Guards

Add allowlist-based static checks for:

```text
No direct System.currentTimeMillis outside SystemTimeProvider/test/allowlist
No new java.util.Calendar in analytics/budget/time-sensitive domain packages
No production import of domain.model.PeriodRange
No production call to default-EUR CurrencyFormatter overloads
No formatForExport call without error handling if API changed
No raw CurrencyCode(...) from untrusted strings
No new public domain/UI bare Double money field without allowlist
```

## Tests

```text
noDirectWallClockOutsideAllowlist()
noProductionImportLegacyPeriodRange()
noProductionCallDefaultEurFormatter()
noNewBareDoubleMoneyFieldWithoutAllowlist()
```

## Risk

Medium because guards may expose many existing call sites.

Do after PR1–PR7 so violations are manageable.

---

# PR9 — MoneyAmount v2 design, not implementation

## Closes eventually

- `M02`
- `M06`
- part of `M14`

## Do not implement immediately

This is a design/migration project.

## Options

### Option A — minor units

```kotlin
data class MoneyAmount(
    val minorUnits: Long,
    val currency: CurrencyCode
)
```

Pros:

- exact cents
- fast
- good for common currencies

Cons:

- harder for 3-decimal currencies and crypto-like precision
- requires currency minor-unit metadata

### Option B — BigDecimal

```kotlin
data class MoneyAmount(
    val amount: BigDecimal,
    val currency: CurrencyCode
)
```

Pros:

- exact decimal
- easier migration from legacy `Money`

Cons:

- formatting/scale rules still needed
- Room/entity boundaries still raw

## Required design doc

Before code, create:

```text
docs/architecture/MONEY_AMOUNT_V2_DESIGN.md
```

Include:

- representation
- rounding policy
- minor units
- split/remainder policy
- Room migration strategy
- adapter strategy for entities still using `Double`
- pipeline migration plan

## Risk

High. Defer until DB baseline is stable and Engine 5 quick wins are done.

---

# Engine 5 specific non-regression checklist

Use after every Engine 5 PR.

## Time / period correctness

- [ ] `CUSTOM` period requires explicit start/end.
- [ ] `CUSTOM` period uses caller-provided bounds exactly.
- [ ] `CUSTOM` without bounds throws clearly.
- [ ] `LAST_7_DAYS` includes exactly 7 local calendar dates.
- [ ] `LAST_30_DAYS` includes exactly 30 local calendar dates.
- [ ] Date ranges remain half-open `[startInclusive, endExclusive)`.
- [ ] DST transition day does not duplicate or skip invalidly.
- [ ] Leap day/month ranges remain correct.
- [ ] Week/month/year ranges use explicit zone.
- [ ] No selected-period analytics silently uses `now` unless intended.

## Analytics/dashboard period consumers

- [ ] Analytics daily buckets match selected period.
- [ ] Last-7-days analytics has 7 buckets.
- [ ] Last-30-days analytics has 30 buckets.
- [ ] Dashboard weekly/monthly widgets still load.
- [ ] Category/merchant totals still use same period as summary.
- [ ] Custom-range analytics/export uses exact custom bounds.
- [ ] No extra future day appears in charts.

## Budget period consumers

- [ ] Active budget period calculation still works.
- [ ] Monthly budget range remains correct.
- [ ] Weekly budget range remains correct.
- [ ] Custom budget/report range uses exact bounds.
- [ ] Budget-vs-actual period matches analytics period.
- [ ] Budget rollover behavior is unchanged unless explicitly fixed.
- [ ] No production budget code imports legacy `domain.model.PeriodRange` after migration.

## Money normalization

- [ ] Same-currency conversion remains exact identity.
- [ ] Foreign-currency conversion uses requested rate basis.
- [ ] Historical conversion requires a date.
- [ ] Missing historical rate produces partial failure, not latest silent fallback.
- [ ] Invalid currency row produces excluded/failure row, not crash.
- [ ] Failed transaction count reflects affected transactions.
- [ ] Failed bucket count remains distinct from failed transaction count.
- [ ] Source buckets are preserved where supported.
- [ ] Conversion provenance remains available where supported.
- [ ] Dirty rescued/legacy DB rows cannot crash analytics/budget/export.

## Currency validation

- [ ] ASCII currency validation still rejects digits and non-letters.
- [ ] Syntax-only parse and supported-currency parse are clearly separate.
- [ ] Unsupported code like `ZZZ` does not enter new user-written money unless explicitly allowed.
- [ ] Inactive currencies are handled by explicit policy.
- [ ] Invalid currency does not silently become EUR.
- [ ] Home currency/settings errors surface warning or failure.
- [ ] Write paths validate currency before persisting.

## Formatting/export

- [ ] Valid EUR/USD/GBP formatting still works.
- [ ] JPY or zero-decimal currency display remains correct if supported.
- [ ] Export formatting uses stable decimal separator.
- [ ] NaN is not exported as `0.00`.
- [ ] Infinity is not exported as `0.00`.
- [ ] Invalid money export returns error/warning.
- [ ] Invalid currency does not display euro symbol.
- [ ] Deprecated default-EUR formatter calls are not used by production after guard PR.
- [ ] Business/tax/accounting exports handle formatter failures explicitly.

## Restore/backup time

- [ ] Restore journal start time uses `TimeProvider`.
- [ ] Restore journal events use `TimeProvider`.
- [ ] Restore import success/failure markers use `TimeProvider`.
- [ ] Tests can replay restore journal timestamps deterministically.
- [ ] Direct wall-clock calls are only in `SystemTimeProvider` or allowlisted code.
- [ ] Backup/restore diagnostics remain readable.

## Week helpers

- [ ] ISO week key uses ISO week number and ISO week-based year.
- [ ] App-calendar week key uses internally consistent app-calendar year/week.
- [ ] New Year boundary cases are tested.
- [ ] Old ambiguous helpers are deprecated or documented.
- [ ] No code pairs non-ISO week number with ISO week-year.

## Legacy PeriodRange

- [ ] Core `domain.core.time.PeriodRange` remains zone-aware and half-open.
- [ ] Legacy `domain.model.PeriodRange` is not used in new production code.
- [ ] BudgetCalculator migration preserves old behavior where intended.
- [ ] Static guard catches new legacy imports.
- [ ] Tests cover range labels/kind/zone where relevant.

## MoneyAmount / raw Double

- [ ] Existing `MoneyAmount` still rejects NaN/Infinity.
- [ ] No new public domain/UI bare `Double` money fields are added without allowlist.
- [ ] Raw Room entity doubles are treated as persistence boundary, not ideal domain model.
- [ ] `fromBigDecimal()` lossy behavior remains documented until v2.
- [ ] No partial MoneyAmount v2 migration is done without design doc.
- [ ] Existing app data is not migrated/destructively changed.

## Groups/investment/tax consumers

- [ ] Group shared budget offsets still convert as-of expense date.
- [ ] Investment aggregate paths still report partial state on conversion failure.
- [ ] Tax estimates do not silently latest-rate convert closed periods.
- [ ] Business reports do not silently format invalid money.
- [ ] Portfolio/history/budget code handles invalid currency failure gracefully.

## Tests/static review

- [ ] Engine unit tests added for every primitive fix.
- [ ] Pipeline regression tests added for affected analytics/budget/export consumers.
- [ ] No `@Ignore`.
- [ ] No weak assertions only checking non-null.
- [ ] Tests use fixed `TimeProvider`.
- [ ] Time tests include DST/leap/New Year boundaries.
- [ ] Money tests include invalid currency, missing rate, same currency, failed conversion.
- [ ] Export tests include NaN/Infinity.
- [ ] Static guards are allowlist-based and documented.

## Build/schema discipline

- [ ] No Room migration in PR1–PR8.
- [ ] No destructive migration.
- [ ] No DB rescue rerun.
- [ ] No global `CurrencyConverter` semantics change.
- [ ] No `MoneyAmount` representation change before PR9 design approval.
- [ ] No broad Hilt rewiring except RestoreJournal TimeProvider if needed.
- [ ] Every behavior change is documented in tracker/docs.

---

# Suggested final validation commands

Only after all Engine 5 PRs are complete:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If schema/migration is later introduced:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt/DI changed:

```bash
./gradlew :app:assembleDebug --stacktrace
```

---

# Definition of done

Engine 5 is clean when:

- time periods are explicit, zone-aware, half-open, and tested
- `CUSTOM` never silently falls back
- last-N periods contain exactly N calendar days
- invalid currency produces partial/warning, not crash
- export never converts corrupt money to plausible zero
- formatter APIs do not silently default to EUR
- restore/backup time uses `TimeProvider`
- week helpers are internally consistent
- legacy `PeriodRange` is removed from production consumers
- raw `Double` money is contained behind documented persistence boundaries
- `MoneyAmount` v2 has an approved design before implementation