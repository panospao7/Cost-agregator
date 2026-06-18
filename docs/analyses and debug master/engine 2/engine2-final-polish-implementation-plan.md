# Engine 2 Final Polish Implementation Plan

Target commits reviewed:

```text
3b9490e14367846aa8274e6c1a2dae6e2dcc9e53
8cc202512ffd19b2a3cab2c5fc9614a8811354ce
```

Current Engine 2 status:

```text
YELLOW / GREEN-candidate
```

Goal:

> Close remaining P2/P3 Engine 2 hardening items so Engine 2 can be treated as clean after validation, while keeping historical-category and Engine 5 money policy work explicitly deferred.

No schema changes.  
No global `CurrencyConverter` semantics changes.  
No broad analytics refactor.  
No `MoneyAmount` representation changes.

---

# Remaining issues

## E2-FINAL-001

`AnalyticsViewModel` catches `Exception` around spending personality classification and can swallow `CancellationException`.

## E2-FINAL-002

`AreaSpendingEngine.computeNormalized()` and `TravelDetectionEngine.computeNormalized()` trust inconsistent `LocatedMoneyExpense` objects:

```text
conversionStatus = HOME_CURRENCY or CONVERTED
normalizedAmount = null
```

then may fallback to `originalAmount`.

## E2-FINAL-003

Deprecated `AnalyticsState.moneyCurrentTotal` uses EUR fallback for compatibility.

Risk:

```text
Any production caller using deprecated helper can silently display EUR during null/invalid currency state.
```

## E2-FINAL-004

`AnalyticsRepository.buildMoneyAggregate()` uses:

```kotlin
CurrencyCode.parseOr(currency, CurrencyCode.EUR)
```

for source/failure currencies.

Risk:

```text
invalid source currency diagnostic can become EUR
```

## E2-FINAL-005

Stale TODO/comment says budget conversion uses latest/current rate even though code now uses `convertAsOf`.

## Remaining deferred issues

These stay deferred unless human explicitly approves schema/design work:

```text
Historical category identity
MoneyAmount v2 / raw Double cleanup
fully structured budget item data-quality model
spending personality true budget adherence
```

---

# PR1 — AnalyticsViewModel cancellation safety + stale comment cleanup

## Goal

Fix the remaining cancellation-safety smell and update misleading budget FX comment.

## Files

```text
AnalyticsViewModel.kt
AnalyticsViewModel cancellation/unit test if feasible
CancellationSafetyArchitectureGuardTest.kt maybe
```

## Implementation

### Step 1 — Rethrow cancellation in personality block

Find:

```kotlin
val personalityProfile = try {
    spendingPersonalityClassifier.classify(allInput)
} catch (e: Exception) {
    null
}
```

Replace with:

```kotlin
val personalityProfile = try {
    spendingPersonalityClassifier.classify(allInput)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
```

Import:

```kotlin
kotlinx.coroutines.CancellationException
```

If already imported, reuse.

### Step 2 — Audit nearby `catch(Exception)` blocks in analytics loading

For any suspend/loading block added by Engine 2 fixes:

```text
catch CancellationException -> throw
catch Exception -> fallback/log
```

Do not do broad repo cleanup in this PR.

### Step 3 — Update stale budget conversion comment

Replace stale comment:

```text
Budget limit conversion currently uses latest available rate via currencyConverter.convert()
```

with:

```text
Budget limit conversion uses period-end convertAsOf(currentEnd - 1).
Future improvement: expose explicit rate basis in UI/model.
```

## Tests

Add one if easy:

```text
analyticsViewModel_personalityCancellationRethrows()
```

If hard due ViewModel test setup, rely on static guard and document.

## Guard update

If possible, reduce `AnalyticsViewModel.kt` allowlist in cancellation guard only after all broad catches are fixed. If too broad, leave for later.

## Risk

Low.

---

# PR2 — Harden normalized location input contracts

## Goal

Prevent normalized location engines from ever treating raw `originalAmount` as normalized display amount when status says converted/home but `normalizedAmount` is missing.

## Files

```text
LocatedMoneyExpense.kt
AreaSpendingEngine.kt
TravelDetectionEngine.kt
AreaSpendingEngineNormalizedTest.kt
TravelDetectionEngineNormalizedTest.kt
```

## Current risky pattern

Something like:

```kotlin
val amount = expense.normalizedAmount ?: expense.originalAmount
```

after filtering by successful conversion status.

## Implementation

### Step 1 — Add helper on `LocatedMoneyExpense`

If appropriate:

```kotlin
fun normalizedAmountOrNull(): Double? {
    return normalizedAmount
        ?.takeIf { it.isFinite() && it >= 0.0 }
        ?.takeIf {
            conversionStatus == ConversionStatus.HOME_CURRENCY ||
            conversionStatus == ConversionStatus.CONVERTED
        }
}
```

If negative expenses/refunds are valid, use:

```text
isFinite()
```

instead of `>= 0`.

Choose based on current analytics policy. For spending-only analytics, non-negative is likely fine, but refunds may exist. Be careful.

### Step 2 — Area engine filter

Use only rows with valid normalized amount:

```kotlin
val validExpenses = expenses.filter {
    it.isSuccessfullyNormalized &&
    it.normalizedAmount != null &&
    it.normalizedAmount.isFinite()
}
```

Do not fallback to `originalAmount` for successful statuses.

For failed/partial rows, create failure/warning metadata if model supports it.

### Step 3 — Travel engine filter

Same policy:

```text
successful status requires finite normalizedAmount
```

No raw fallback.

### Step 4 — Preserve ViewModel behavior

The ViewModel currently builds `LocatedMoneyExpense` from normalized analytics rows. It should already pass normalized amount, so normal output should be unchanged.

## Tests

### Area tests

```text
areaSpending_convertedStatusWithNullNormalizedAmount_excluded()
areaSpending_homeCurrencyStatusWithNullNormalizedAmount_excluded()
areaSpending_convertedStatusWithNaNNormalizedAmount_excluded()
areaSpending_validConvertedAmount_included()
```

### Travel tests

```text
travelDetection_convertedStatusWithNullNormalizedAmount_excluded()
travelDetection_homeCurrencyStatusWithNullNormalizedAmount_excluded()
travelDetection_validConvertedAmount_included()
```

### Regression tests

```text
analyticsViewModel_locationSectionsStillLoadWithValidNormalizedRows()
areaSpending_partialWarningsStillVisible()
travelDetection_partialWarningsStillVisible()
```

## Risk

Medium-low.

Potential behavior change only for invalid/inconsistent normalized input, which should be excluded.

---

# PR3 — Remove production dependency on deprecated `moneyCurrentTotal`

## Goal

Ensure the deprecated compatibility helper with EUR fallback cannot affect production UI.

## Files

```text
AnalyticsViewModel.kt
AnalyticsScreen.kt
DeprecatedApiArchitectureGuardTest.kt or new AnalyticsStateGuardTest.kt
AnalyticsStateMoneySafetyTest.kt
```

## Implementation options

### Option A — preferred

Make deprecated property internal/test-only or remove production usage.

If no production code uses:

```kotlin
moneyCurrentTotal
```

add guard:

```text
noProductionUseOfAnalyticsStateMoneyCurrentTotal()
```

Allow:

```text
AnalyticsViewModel.kt declaration
tests
```

### Option B — safer property behavior

Change deprecated helper to throw clear exception when currency missing:

```kotlin
@Deprecated("Use moneyCurrentTotalOrNull")
val moneyCurrentTotal: MoneyAmount
    get() = moneyCurrentTotalOrNull
        ?: error("moneyCurrentTotal requires valid homeCurrency; use moneyCurrentTotalOrNull")
```

This prevents silent EUR fallback but may break legacy callers.

### Option C — leave fallback but document

Least preferred.

## Recommended path

Use Option A first:

1. Confirm UI uses `moneyCurrentTotalOrNull` or raw display guarded by homeCurrency.
2. Add static guard preventing new production reads.
3. Keep fallback temporarily only for binary/source compatibility.

## Tests

```text
analyticsState_deprecatedMoneyCurrentTotal_notUsedByProductionUi()
analyticsState_moneyCurrentTotalOrNull_usedForSafeMoneyDisplay()
```

If Option B:

```text
analyticsState_deprecatedMoneyCurrentTotal_invalidCurrencyThrowsClearError()
```

## Risk

Low if using guard-only.

---

# PR4 — Avoid invalid source currency becoming EUR in aggregate diagnostics

## Goal

Stop diagnostic currency metadata from silently becoming EUR when source currency is invalid.

## Files

```text
AnalyticsRepository.kt
AnalyticsRepositoryAggregateTest.kt
MoneyAggregate / ConversionFailure helpers if needed
```

## Current problem

`buildMoneyAggregate()` uses:

```kotlin
CurrencyCode.parseOr(currency, CurrencyCode.EUR)
```

for source/failure metadata.

## Implementation constraints

Do not redesign `CurrencyCode` globally.  
Do not implement Engine 5 currency policy here.

## Option A — minimal no-model-change fix

When `CurrencyCode.parse(currency)` fails:

1. use display/home currency for `MoneyAmount` only if required by type
2. include raw invalid currency in:
   - failure reason
   - warning message
   - metadata if available

Example:

```kotlin
val parsedCurrency = CurrencyCode.parse(currency)
val failureCurrency = parsedCurrency ?: displayCurrency

ConversionFailure(
    originalAmount = MoneyAmount(amount, failureCurrency),
    reason = FailureReason.INVALID_CURRENCY or CONVERSION_FAILED,
    message = "Invalid source currency '$currency'; amount not converted"
)
```

This still uses a valid `CurrencyCode` object, but no longer silently pretends source was EUR because the message carries raw code.

### Option B — add raw currency field

If `ConversionFailure` has metadata or can safely add:

```kotlin
rawCurrencyCode: String?
```

then preserve exact raw value.

But this may ripple through constructors/tests. Avoid unless easy.

## Preferred

Option A now; Engine 5 handles richer invalid currency model later.

## Tests

```text
spendingSummary_invalidCurrencyFailureMessageContainsRawCode()
spendingSummary_invalidCurrencyDoesNotSilentlyLabelAsEur()
spendingSummary_validCurrencyStillGroupsBySourceCurrency()
```

Test should assert warning/message contains:

```text
"XYZ" or "123"
```

and not just `EUR`.

## Risk

Medium-low. Mostly diagnostics.

---

# PR5 — Final guard/test/docs polish

## Goal

Make Engine 2 final status honest and prevent regressions.

## Files

```text
DeprecatedApiArchitectureGuardTest.kt
engine2-final-status.md
ENGINE_ISSUES_MASTER_TRACKER.md
HISTORICAL_CATEGORY_IDENTITY_PLAN.md maybe
```

## Guard additions

Add or confirm:

```text
no production call to raw AreaSpendingEngine.compute
no production call to raw TravelDetectionEngine.compute
no production call to raw SpendingPersonalityClassifier.classify
no production call to legacy InsightsEngine overload from AnalyticsViewModel
no production use of AnalyticsState.moneyCurrentTotal
```

If broad filename allowlists are still needed, document why.

## Docs update

Update Engine 2 status to:

```text
GREEN candidate after PR1–PR4 and validation
```

Keep deferrals:

```text
Historical category identity: deferred, schema/design required
MoneyAmount v2 / invalid currency model: Engine 5
Spending personality true budget adherence: deferred
Budget item full structured data quality: future enhancement
```

## Final validation commands

After all PRs:

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

Targeted tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*AnalyticsRepositoryAggregateTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AnalyticsStateMoneySafetyTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AnalyticsViewModelInsightsTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BudgetVsActualFxBasisTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AreaSpendingEngineNormalizedTest*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*TravelDetectionEngineNormalizedTest*" --stacktrace
```

Do not use:

```bash
./gradlew test --tests ...
```

Use:

```bash
./gradlew :app:testDebugUnitTest --tests "*Pattern*" --stacktrace
```

---

# Pipeline non-regression checklist

## Analytics screen

- [ ] screen loads while homeCurrency is null/loading
- [ ] current total displays after valid currency arrives
- [ ] insights use selected week/year/all
- [ ] advanced analytics use normalized input
- [ ] spending personality cancellation is not swallowed
- [ ] partial conversion warnings still visible

## Dashboard

- [ ] `SpendingSummary.aggregate` non-null
- [ ] aggregate amount equals displayed total
- [ ] invalid currency diagnostic does not pretend source was EUR
- [ ] dashboard widgets still load

## Budget-vs-actual

- [ ] budget conversion uses `convertAsOf`
- [ ] closed period stable against latest FX changes
- [ ] conversion failure warning still visible
- [ ] stale TODO removed

## Location analytics

- [ ] area/travel use normalized APIs
- [ ] valid normalized rows still included
- [ ] converted/home status with null normalized amount excluded
- [ ] no fallback to raw original amount as normalized amount
- [ ] warnings still visible

## Spending personality

- [ ] ViewModel uses normalized classifier
- [ ] cancellation rethrows
- [ ] raw path guarded from production
- [ ] caveats documented

## Guardrails

- [ ] no raw/deprecated production analytics calls
- [ ] no deprecated money helper production usage
- [ ] no broad catch swallowing cancellation in new Engine 2 code
- [ ] docs/tracker match real state

---

# Definition of done

Engine 2 can be marked **GREEN candidate** when:

```text
- personality cancellation catch rethrows
- stale budget comment fixed
- normalized location engines reject missing normalized amounts
- deprecated moneyCurrentTotal cannot affect production
- invalid currency aggregate diagnostics preserve raw-code warning
- guards protect raw/deprecated APIs
- historical category identity remains honestly deferred
- full validation passes or unrelated failures are documented
```

Do not mark absolute GREEN until validation passes.