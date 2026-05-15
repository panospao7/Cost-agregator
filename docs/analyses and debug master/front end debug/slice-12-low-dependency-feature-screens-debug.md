Prepared Slice 12 as an agent-ready debugging report.

<slice-12-low-dependency-feature-screens-debug-report.md>
# Slice 12 Debug Report — Low-Dependency Feature Screens

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Scope:
- Warranty tracker
- Subscription management
- Bank connections
- Investment portfolio
- Carbon footprint
- Receipt matching

Assistant is excluded here because it was handled in Slice 11.

Sources inspected:
- Warranty:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerScreen.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/data/database/entity/Warranty.kt
- Subscription:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/subscription/SubscriptionManagementScreen.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/subscription/SubscriptionManagementViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/data/database/entity/ManualRecurringExpense.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/data/database/entity/SubscriptionCandidate.kt
- Bank:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/bank/BankConnectionsScreen.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/bank/BankConnectionsViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/data/database/entity/BankConnection.kt
- Investment:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/investment/InvestmentPortfolioScreen.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/investment/InvestmentViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/investment/InvestmentTracker.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/data/database/entity/Investment.kt
- Carbon:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/carbon/CarbonFootprintScreen.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/carbon/CarbonFootprintViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/carbon/CarbonFootprintCalculator.kt
- Receipt matching:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingScreen.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/receiptmatching/ReceiptTransactionMatcher.kt

Note: This is static debugging from GitHub source. The resolving agent must run Gradle locally.

---

## 1. Executive summary

Slice 12 contains screens that look low-dependency, but several are not low-risk.

Good signs:
- Each feature is mostly isolated.
- Most screens have one ViewModel and one primary repository/domain dependency.
- Warranty, subscription, investment, and receipt matching already moved some sensitive writes behind repositories/coordinators.
- Carbon already has load cancellation/request-id protection.
- Receipt matching uses `ReceiptLinkService` for link creation.

Main concerns:
1. Several screens still format or compute money on an unsafe currency basis.
2. Many actions are no-op/stubbed or optimistic UI-only actions.
3. Dialogs close before persistence result.
4. Mutation idempotency is missing.
5. Bank connections are currently a stub but the UI behaves as if actions are real.
6. Investment UI uses the deprecated raw summary path and never loads individual holdings.
7. Receipt matching ignores link failures in important paths.
8. Carbon footprint calculations mostly use raw spend despite factors being spend-currency dependent.
9. Warranty dates are likely displayed one day late because entity docs describe half-open end semantics.
10. Test coverage should be feature-contract based before UI refactors.

Recommended strategy:
- Do not rewrite all feature screens.
- Add focused ViewModel/domain tests first.
- Fix critical correctness issues: currency, no-op actions, idempotency, receipt-link result handling.
- Then extract route/content/components for testability.

---

## 2. Baseline commands

Run first:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

Targeted Slice 12 tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*Warranty*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Subscription*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Bank*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Investment*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Carbon*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptMatching*" --stacktrace
```

Inventory tests:

```bash
find app/src/test app/src/androidTest \
  -iname "*Warranty*" -o \
  -iname "*Subscription*" -o \
  -iname "*Bank*" -o \
  -iname "*Investment*" -o \
  -iname "*Carbon*" -o \
  -iname "*ReceiptMatching*"
```

Stop at first compile failure.

---

## 3. Current architecture map

### Warranty

```text
WarrantyTrackerRepository.getAllWarranties()
        ↓
WarrantyTrackerViewModel.state
        ↓
WarrantyTrackerScreen
        ↓
summary cards / filters / warranty cards / manual-add dialog
```

Mutation paths:
- add manual warranty
- confirm auto-detected warranty
- reject auto-detected warranty
- mark claimed
- delete

### Subscription

```text
SubscriptionManagementRepository
SubscriptionManagerEngine
CurrencySettingsRepository.homeCurrency()
        ↓
SubscriptionManagementViewModel.uiState + homeCurrency flow
        ↓
SubscriptionManagementScreen
        ↓
summary cards / detected candidates / active/inactive subscriptions / add dialog
```

Mutation paths:
- add subscription
- accept/reject candidate
- toggle active
- delete
- record usage

### Bank connections

```text
BankApiIntegration.SUPPORTED_BANKS stub list
        ↓
BankConnectionsViewModel.connections
        ↓
BankConnectionsScreen
```

Mutation paths are currently effectively no-op:
- sync
- disconnect
- refresh loads stub state

### Investment

```text
InvestmentTracker.getPortfolioSummary()
        ↓
InvestmentViewModel.portfolioSummary
        ↓
InvestmentPortfolioScreen
```

Current individual investments list is placeholder-empty.

### Carbon footprint

```text
CarbonFootprintViewModel.loadReport(days)
        ↓
CarbonFootprintCalculator.calculateCarbonFootprint(start, end)
        ↓
CarbonFootprintScreen
```

### Receipt matching

```text
ReceiptRepository unmatched/suggestions
ReceiptTransactionMatcher
ReceiptLinkService
        ↓
ReceiptMatchingViewModel.state
        ↓
ReceiptMatchingScreen
```

Mutation paths:
- run auto-matching
- approve suggestion
- reject suggestion
- manual match
- skip receipt
- rerun match

---

# 4. Issues

## S12-001 — Slice 12 is “low dependency” but not “low risk”

Severity: High  
Files:
- all Slice 12 screens/ViewModels

Problem:
This group includes:
- bank credentials/tokens,
- investment portfolio values,
- subscription recurring payments,
- warranty/return policy audit,
- carbon estimates from transaction history,
- receipt-to-expense links.

These are financially and privacy sensitive even if they have fewer dependencies.

Fix strategy:
Treat them as small isolated contracts, not throwaway feature screens.

Implementation plan:
Add per-feature contract tests:
- loading/error/empty/data states,
- one mutation success,
- one mutation failure,
- one idempotency test,
- currency/data-quality test where money is shown.

Acceptance:
- every Slice 12 screen has at least one ViewModel test class and one content/component smoke test.

---

## S12-002 — Missing typed mutation state across features

Severity: High  
Files:
- `WarrantyTrackerViewModel.kt`
- `SubscriptionManagementViewModel.kt`
- `BankConnectionsViewModel.kt`
- `InvestmentViewModel.kt`
- `ReceiptMatchingViewModel.kt`

Problem:
Most actions launch a coroutine and either reload or set a raw error string. There is no consistent:
- active mutation ID,
- saving state,
- inline error,
- one-off success event,
- retry policy.

This causes:
- double taps,
- dialogs closing too early,
- hidden failures,
- duplicate writes.

Fix strategy:
Introduce a lightweight mutation state per screen.

Implementation pattern:

```kotlin
data class FeatureMutationState(
    val operation: String? = null,
    val targetId: Long? = null,
    val isRunning: Boolean = false,
    val error: UiText? = null
)
```

Acceptance:
- duplicate action calls are guarded.
- UI disables target action while saving.
- failure keeps relevant dialog/sheet open.
- success event closes relevant UI.

---

## S12-003 — Currency basis is unsafe or unclear across multiple screens

Severity: Critical financial correctness  
Files:
- `WarrantyTrackerViewModel.kt`
- `WarrantyTrackerScreen.kt`
- `SubscriptionManagementViewModel.kt`
- `SubscriptionManagementScreen.kt`
- `InvestmentViewModel.kt`
- `InvestmentPortfolioScreen.kt`
- `CarbonFootprintCalculator.kt`
- `ReceiptMatchingScreen.kt`

Problem examples:
- Warranty protected value uses raw total instead of aggregate.
- Subscription totals raw-sum recurring amounts.
- Subscription UI formats candidate/subscription amounts using home currency instead of entity currency.
- Investment ViewModel uses deprecated raw portfolio summary.
- Carbon uses spend × emission factor mostly without normalizing.
- Receipt matching UI displays amounts without explicit currency.
- Several UI flows collect `homeCurrency` with initial empty string.

Fix strategy:
Create feature-specific money UI models.

Implementation:
```kotlin
data class MoneyDisplayUi(
    val amount: Double,
    val currency: String,
    val formatted: String,
    val isPartial: Boolean = false,
    val warning: UiText? = null
)
```

Acceptance:
- no Slice 12 UI formats a raw `Double` as money without explicit currency.
- no `collectAsState(initial = "")` for production money display.
- mixed-currency tests fail on old behavior and pass after fix.

---

## S12-004 — Dialogs close before persistence result

Severity: High  
Files:
- `WarrantyTrackerScreen.kt`
- `SubscriptionManagementScreen.kt`
- `ReceiptMatchingScreen.kt`
- bank disconnect confirmation flow

Problem:
Manual warranty add, subscription add/delete, receipt manual match, and bank disconnect flows close local UI before durable result is known or before result is even real.

Fix strategy:
Close only on mutation success.

Acceptance:
- save failure leaves dialog open with input preserved.
- success closes dialog.
- tests cover failure and success.

---

## S12-005 — Screens are small but still monolithic

Severity: Medium  
Files:
- all Slice 12 screen files

Fix strategy:
Split route/content/components only after behavior tests exist.

Suggested extraction:
```text
WarrantyTrackerRoute.kt / WarrantyTrackerContent.kt / WarrantyCard.kt / ManualWarrantyDialog.kt
SubscriptionRoute.kt / SubscriptionContent.kt / SubscriptionCard.kt / AddSubscriptionDialog.kt
BankConnectionsRoute.kt / BankConnectionsContent.kt / BankConnectionCard.kt
InvestmentPortfolioRoute.kt / InvestmentPortfolioContent.kt / InvestmentCard.kt
CarbonFootprintRoute.kt / CarbonFootprintContent.kt / CarbonCards.kt
ReceiptMatchingRoute.kt / ReceiptMatchingContent.kt / ReceiptMatchCards.kt / ManualMatchDialog.kt
```

Acceptance:
- route files collect ViewModel state only.
- content components render pure state and callbacks.
- Compose tests can render without Hilt.

---

# Warranty issues

## S12-006 — Warranty protected value uses deprecated raw aggregate

Severity: Critical multi-currency correctness  
Files:
- `WarrantyTrackerViewModel.kt`
- `WarrantyTrackerRepository.kt`
- `WarrantyTrackerScreen.kt`

Evidence:
Repository has `getTotalProtectedValueAggregate()` but ViewModel uses `getTotalProtectedValue()`.

Problem:
Protected value can raw-sum expenses across currencies and screen renders it with `String.format` without currency.

Fix:
Use aggregate path and expose `MoneyDisplayUi`.

Implementation:
```kotlin
val protectedValue: MoneyDisplayUi? = null
```

ViewModel:
```kotlin
val aggregate = warrantyRepository.getTotalProtectedValueAggregate()
state.copy(
    protectedValue = aggregate.toMoneyDisplayUi(currencyFormatter)
)
```

Acceptance:
- protected value includes currency.
- conversion failures show partial warning.
- no raw warranty protected-value sum in UI.

---

## S12-007 — Warranty end date display likely off by one day

Severity: High  
Files:
- `Warranty.kt`
- `WarrantyTrackerScreen.kt`
- `WarrantyTrackerRepository.kt`

Evidence:
Entity docs describe `warrantyEndDate` as a half-open exclusive boundary. Screen formats `warrantyEndDate` directly.

Problem:
If the stored end is the first instant after coverage, UI should display the previous calendar day. Otherwise warranty appears valid one day longer.

Fix:
Add display helper:
```kotlin
fun warrantyDisplayEndDate(exclusiveEndMs: Long, zoneId: ZoneId): LocalDate =
    Instant.ofEpochMilli(exclusiveEndMs - 1)
        .atZone(zoneId)
        .toLocalDate()
```

Acceptance:
- warranty ending May 6 00:00 displays May 5.
- active/expired calculation still uses exclusive boundary.
- tests cover boundary day.

---

## S12-008 — Manual warranty add uses wall clock and weak date validation

Severity: Medium/High  
Files:
- `WarrantyTrackerScreen.kt`
- `WarrantyTrackerViewModel.kt`

Problem:
Manual dialog initializes date from `LocalDate.now()` instead of injected `TimeProvider`/state reference time. Validation is local-only and errors are not visible beyond disabled save.

Fix:
Move validation to ViewModel or pure validator.

```kotlin
data class WarrantyInput(...)
sealed interface WarrantyValidationResult
```

Acceptance:
- fixed clock test controls default purchase date.
- invalid date/duration shows inline field error.
- save action is ViewModel-guarded.

---

## S12-009 — Reject auto-detected warranty is not atomic with return-window cleanup

Severity: High data consistency  
Files:
- `WarrantyTrackerViewModel.kt`
- `WarrantyTrackerRepository.kt`

Problem:
ViewModel deletes a linked return window and then deletes the warranty as separate calls. If the second fails, return window may be gone while warranty remains.

Fix:
Add repository method:
```kotlin
suspend fun rejectAutoDetectedWarranty(warrantyId: Long): Result<Unit>
```
Use `database.withTransaction`.

Acceptance:
- both warranty and linked return window are removed together.
- failure rolls back both.
- lifecycle/audit event is written.

---

## S12-010 — Warranty mutations lack error and idempotency handling

Severity: Medium/High  
Files:
- `WarrantyTrackerViewModel.kt`
- `WarrantyTrackerScreen.kt`

Affected:
- add manual
- confirm
- reject
- mark claimed
- delete

Fix:
Add mutation state and events.

Acceptance:
- double confirm calls repository once.
- delete failure shows error.
- mark claimed failure does not silently disappear.

---

# Subscription issues

## S12-011 — Subscription totals raw-sum amounts across currencies

Severity: Critical multi-currency correctness  
Files:
- `SubscriptionManagementViewModel.kt`
- `SubscriptionManagementScreen.kt`
- `ManualRecurringExpense.kt`

Problem:
Subscriptions have a `currency` field. ViewModel totals monthly cost by raw amount and UI formats totals as home currency.

Fix:
Normalize subscription recurring amounts to home currency or return per-currency aggregate.

Implementation:
```kotlin
data class SubscriptionTotalsUi(
    val monthly: MoneyDisplayUi,
    val annual: MoneyDisplayUi,
    val isPartial: Boolean
)
```

Acceptance:
- USD + EUR subscriptions convert before total.
- failed conversion shows partial warning.
- no raw sum is formatted as home currency.

---

## S12-012 — Candidate/subscription card displays entity amounts using home currency

Severity: High  
Files:
- `SubscriptionManagementScreen.kt`
- `SubscriptionCandidate.kt`
- `ManualRecurringExpense.kt`

Problem:
Candidates and subscriptions each carry currency. UI formats `candidate.averageAmount`, `candidate.estimatedAnnualCost`, and `subscription.amount` using `homeCurrency`.

Fix:
Use each entity’s own currency for row-level display. Use home currency only for normalized totals.

Acceptance:
- candidate with USD displays USD.
- subscription with GBP displays GBP.
- totals display home currency with conversion warning if partial.

---

## S12-013 — Add subscription dialog closes before result and has weak input handling

Severity: High  
Files:
- `SubscriptionManagementScreen.kt`
- `SubscriptionManagementViewModel.kt`

Problems:
- dialog closes immediately after `viewModel.addSubscription`.
- amount uses `toDoubleOrNull()` directly.
- date picker can select past/invalid billing dates.
- no inline repository/engine validation error.

Fix:
Use shared amount sanitizer and mutation events.

Acceptance:
- failed add keeps dialog open.
- next billing date must be valid according policy.
- double add calls engine once.
- validation errors are field-specific.

---

## S12-014 — Usage count label/time window is misleading

Severity: Medium  
Files:
- `SubscriptionManagementViewModel.kt`
- `SubscriptionManagementScreen.kt`

Problem:
UI says “uses this month,” but ViewModel uses different lookback windows depending on subscription frequency.

Fix:
Either:
- make usage always calendar-month based, or
- change label to “uses in current billing window.”

Acceptance:
- label matches calculation.
- tests cover weekly/monthly/annual usage windows.

---

## S12-015 — Candidate accept/reject/toggle/delete lack idempotency and partial error state

Severity: High  
Files:
- `SubscriptionManagementViewModel.kt`

Fix:
Add per-target mutation state:
```kotlin
val mutatingSubscriptionIds: Set<Long>
val mutatingCandidateIds: Set<Long>
```

Acceptance:
- double accept candidate calls engine once.
- failed accept keeps candidate visible.
- failed reject shows error.
- toggle spam is serialized or last-write-wins by policy.

---

# Bank connection issues

## S12-016 — Bank connections are currently stub/no-op but UI behaves as real

Severity: Critical product/security correctness  
Files:
- `BankConnectionsViewModel.kt`
- `BankConnectionsScreen.kt`

Evidence:
ViewModel initializes from supported bank list and has TODO/no-op sync/disconnect.

Problem:
Users can see “connect/sync/disconnect” flows that do not persist or sync anything. For financial accounts this is dangerous.

Fix strategy:
Choose explicit product mode:
1. Demo/read-only unsupported placeholder; or
2. Real repository-backed bank connection feature.

Recommended short-term:
- mark screen as “coming soon / demo.”
- disable sync/disconnect until repository exists.
- do not show fake connected state.

Acceptance:
- no UI implies a bank is actually connected unless repository says so.
- no-op actions are removed or visibly disabled.
- tests verify sync/disconnect disabled in stub mode.

---

## S12-017 — Stub connections all have ID 0, so hiding/removing one can hide all

Severity: High  
Files:
- `BankConnectionsViewModel.kt`
- `BankConnectionsScreen.kt`
- `BankConnection.kt`

Problem:
Stub `BankConnection` objects are created with default `id = 0`. Screen tracks `hiddenConnectionIds` by ID. Removing one stub connection can hide all stub rows with ID 0.

Fix:
Use stable UI key:
```kotlin
data class BankConnectionUi(
    val uiKey: String,
    val entity: BankConnection
)
```
For stubs:
```kotlin
uiKey = bank.id
```

Acceptance:
- hiding one stub bank does not hide others.
- `LazyColumn` keys use stable unique key.

---

## S12-018 — Disconnect is optimistic local UI, not durable state

Severity: High  
Files:
- `BankConnectionsScreen.kt`
- `BankConnectionsViewModel.kt`

Problem:
Screen hides connection locally and only calls no-op `disconnect` after snackbar timeout. On recomposition/process restart it returns.

Fix:
Move undo/delete/disconnect flow into ViewModel.

Acceptance:
- disconnect pending state survives recomposition.
- undo restores before repository mutation.
- final disconnect calls repository and updates flow.
- failure restores row with error.

---

## S12-019 — Bank token/security lifecycle is not implemented

Severity: Critical security  
Files:
- `BankConnection.kt`
- `BankConnectionsViewModel.kt`

Problem:
Entity has token fields and encryption version, but feature has no visible:
- secure token storage,
- token rotation,
- privacy consent,
- sync audit,
- disconnect token wipe,
- error/status model.

Fix:
Before enabling real bank connections, add:
```kotlin
interface BankConnectionRepository
interface BankTokenStore
interface BankSyncCoordinator
```

Acceptance:
- tokens never exposed in UI state.
- disconnect clears tokens.
- sync is privacy/audit gated.
- tests cover token wipe and sync failure.

---

# Investment issues

## S12-020 — Investment UI uses deprecated raw portfolio summary

Severity: Critical multi-currency correctness  
Files:
- `InvestmentViewModel.kt`
- `InvestmentTracker.kt`
- `InvestmentPortfolioScreen.kt`

Evidence:
`InvestmentTracker.getPortfolioSummary()` is deprecated because it raw-sums currencies. ViewModel still calls it.

Fix:
Use:
```kotlin
getPortfolioSummaryAggregate(...)
getInvestmentPerformances()
```

Expose:
```kotlin
data class InvestmentPortfolioUiState(
    val summary: PortfolioSummaryUi,
    val performances: List<InvestmentPerformanceUi>,
    val dataQuality: InvestmentDataQualityUi,
    val isLoading: Boolean,
    val error: UiText?
)
```

Acceptance:
- mixed-currency portfolio total is normalized or shown as aggregate.
- stale/missing prices visible.
- no deprecated summary call from UI ViewModel.

---

## S12-021 — Investment list is placeholder-empty

Severity: High product correctness  
Files:
- `InvestmentViewModel.kt`
- `InvestmentPortfolioScreen.kt`

Problem:
ViewModel sets investments to empty list after loading summary. The screen has an Add button but no empty state explaining no holdings vs unsupported feature.

Fix:
Load `investmentTracker.getInvestmentPerformances()`.

Acceptance:
- holdings appear.
- no holdings shows empty state with Add action.
- errors show retry.

---

## S12-022 — Investment has hidden EUR defaults/fallbacks

Severity: High  
Files:
- `Investment.kt`
- `InvestmentTracker.kt`

Problem:
Investment entity defaults currency to EUR. Aggregate methods also fall back to EUR when home currency fails.

Fix:
- creation UI/domain must require explicit currency.
- aggregate methods should fail/degrade if home currency unavailable.

Acceptance:
- new investment cannot default to EUR accidentally.
- home-currency failure does not show EUR portfolio.
- tests cover non-EUR and currency failure.

---

## S12-023 — Investment date logic uses raw Calendar

Severity: Medium  
File:
- `InvestmentTracker.kt`

Problem:
Portfolio history uses raw `Calendar.getInstance()`.

Fix:
Use `TimeProvider` + `ZoneId`/`java.time`.

Acceptance:
- DST/leap-day history tests pass.
- daily keys are deterministic.

---

# Carbon issues

## S12-024 — Carbon calculation raw-sums spend despite spend-based factors

Severity: Critical financial/data correctness  
Files:
- `CarbonFootprintCalculator.kt`

Problem:
Emission factors are expressed per spend unit, but main carbon calculation multiplies raw `expense.effectiveAmount` by factor. Mixed-currency spending produces invalid kg CO2 estimates.

Fix:
Normalize expenses to a configured factor currency before applying factors.

Implementation:
```kotlin
data class CarbonCalculationCurrencyPolicy(
    val factorCurrency: String = "EUR"
)
```

Use `AnalyticsCurrencyNormalizer` for all included expenses, not only monthly trend.

Acceptance:
- USD/EUR fixture normalizes before emissions.
- failed conversion produces partial carbon report.
- no raw mixed-currency carbon total.

---

## S12-025 — Carbon has hidden EUR fallback and region-specific assumptions

Severity: High  
Files:
- `CarbonFootprintCalculator.kt`
- `CarbonFootprintScreen.kt`

Problems:
- monthly trend falls back to EUR.
- offset cost is implicitly euro-based.
- benchmarks reference Greece-specific assumptions.
- UI formats offset cost using home currency even if offset cost was computed in another basis.

Fix:
Make region/currency policy explicit:
```kotlin
data class CarbonRegionProfile(
    val regionCode: String,
    val factorCurrency: String,
    val nationalDailyAverageKg: Double,
    val offsetCostPerTonne: MoneyAmount
)
```

Acceptance:
- user region/factor basis is visible or configurable.
- no hidden EUR fallback.
- offset cost has explicit currency.

---

## S12-026 — Carbon report can produce invalid percentages/formatting

Severity: Medium  
Files:
- `CarbonFootprintCalculator.kt`
- `CarbonFootprintScreen.kt`

Problems:
- percentage math can divide by zero in edge cases.
- `String.format` uses default locale.
- chart/cards do not expose test tags.
- some recommendation descriptions are hardcoded domain strings.

Fix:
- sanitize totals before percentage math.
- use formatters with explicit locale/resources.
- convert domain recommendation text to `UiText`.

Acceptance:
- zero/empty report does not show NaN/Infinity.
- locale tests pass.
- component tests find cards by test tag.

---

# Receipt matching issues

## S12-027 — Auto-match increments success count even if link fails

Severity: Critical data integrity  
Files:
- `ReceiptMatchingViewModel.kt`
- `ReceiptLinkService`

Problem:
`runAutoMatching()` calls `receiptLinkService.linkReceiptToExpense(...)` but increments `autoMatched` regardless of link result.

Fix:
Inspect result:
```kotlin
val linkResult = receiptLinkService.linkReceiptToExpense(...)
linkResult.fold(
    onSuccess = { autoMatched++ },
    onFailure = { errors += ... }
)
```

Acceptance:
- failed link does not increment success.
- UI shows partial auto-match result.
- test covers one success + one failure.

---

## S12-028 — Receipt matching has no error handling; loading can stick

Severity: High  
Files:
- `ReceiptMatchingViewModel.kt`

Problem:
`loadReceipts`, `runAutoMatching`, `manualMatch`, `rerunForReceipt`, etc. do not catch exceptions consistently. If repository/matcher throws, `isLoading` can remain true.

Fix:
Use typed state:
```kotlin
data class ReceiptMatchingState(
    ...
    val error: UiText? = null,
    val operation: ReceiptMatchingOperation? = null
)
```

Acceptance:
- repository failure sets error and clears loading.
- retry works.
- tests cover each failure path.

---

## S12-029 — Receipt matching mutations are not idempotency-safe

Severity: High  
Files:
- `ReceiptMatchingViewModel.kt`
- `ReceiptMatchingScreen.kt`

Affected:
- run auto-match
- approve suggestion
- reject suggestion
- manual match
- skip
- rerun

Fix:
Track operation by receipt ID:
```kotlin
val mutatingReceiptIds: Set<Long>
val globalOperation: ReceiptMatchingOperation?
```

Acceptance:
- double approve calls link service once.
- double manual match calls link service once.
- auto-match cannot be run twice concurrently.

---

## S12-030 — Receipt matching UI displays money without currency

Severity: High  
Files:
- `ReceiptMatchingScreen.kt`
- `MatchSuggestion`

Problem:
UI displays receipt total, expense amount, and candidate amounts using `String.format("%.2f")` with no currency. It also uses `expense.amount` in the manual match dialog, while suggestion uses `effectiveAmount`.

Fix:
Use typed money display:
```kotlin
data class MatchSuggestionUi(
    val receiptTotal: MoneyDisplayUi?,
    val expenseAmount: MoneyDisplayUi?
)
```

Acceptance:
- receipt currency shown.
- transaction currency shown.
- no raw amount in manual match dialog.

---

## S12-031 — Matcher currency handling is penalty-based, not conversion-aware

Severity: Medium/High  
Files:
- `ReceiptTransactionMatcher.kt`

Problem:
The matcher reduces amount score when currencies differ, but does not convert when rates are available. This lowers match quality for legitimate multi-currency receipts.

Fix:
Inject `CurrencyConverter` and compare normalized amounts when possible.

Acceptance:
- EUR receipt vs USD transaction can match correctly if conversion exists.
- failed conversion applies conservative penalty.
- tests cover same currency, convertible currency, unconvertible currency.

---

## S12-032 — Rerun/clear-match path must be audited for lifecycle safety

Severity: Medium/High  
Files:
- `ReceiptMatchingViewModel.kt`
- `ReceiptRepository`
- `ReceiptLinkService`

Problem:
`rerunForReceipt()` calls `receiptRepository.clearMatchForReceipt(receipt.id)` before rerunning. The agent must verify this does not directly mutate legacy receipt-expense fields outside `ReceiptLinkService`.

Fix:
If it bypasses lifecycle, replace with:
```kotlin
receiptLinkService.unlinkReceipt(...)
```

Acceptance:
- all link/unlink mutations go through `ReceiptLinkService`.
- audit event written for unlink/rerun.
- test guards direct legacy mutation.

---

# 5. Recommended new tests

## Warranty tests

`WarrantyTrackerViewModelTest`
- loads all warranties and derived filters.
- protected value uses aggregate with currency.
- add manual success sets timestamps through repository.
- add manual failure visible.
- confirm double-tap guarded.
- reject auto-detected deletes warranty + return window atomically.
- warranty display end date subtracts exclusive boundary.

`WarrantyTrackerContentTest`
- loading state.
- empty state actions.
- manual dialog validation.
- needs-review actions.
- protected value currency visible.

---

## Subscription tests

`SubscriptionManagementViewModelTest`
- loads active/inactive/candidates.
- totals normalize currencies.
- failed FX conversion shows partial warning.
- add success event.
- add failure keeps dialog open.
- accept candidate double-tap guarded.
- reject candidate failure visible.
- usage window label policy.

`SubscriptionManagementContentTest`
- empty contextual actions.
- add dialog invalid amount.
- date picker future/next-date policy.
- candidate row shows candidate currency.
- subscription row shows subscription currency.
- mutation disables row buttons.

---

## Bank tests

`BankConnectionsViewModelTest`
- stub mode exposes unsupported/demo state.
- sync no-op is not exposed as success.
- disconnect disabled until repository exists.
- repository-backed disconnect clears token when implemented.

`BankConnectionsContentTest`
- empty state.
- demo/coming-soon state.
- unique key per supported bank.
- undo flow does not hide all rows.
- sync/disconnect disabled state.

---

## Investment tests

`InvestmentViewModelTest`
- uses aggregate summary, not deprecated raw summary.
- loads performances.
- mixed-currency portfolio normalized.
- stale price warning visible.
- home currency unavailable produces degraded/error state.
- refresh failure visible.

`InvestmentPortfolioContentTest`
- loading state.
- empty holdings state.
- portfolio summary currency.
- stale-price warning.
- add callback.

---

## Carbon tests

`CarbonFootprintCalculatorCurrencyTest`
- same-currency spend produces expected kg.
- mixed-currency spend normalized before kg.
- missing FX conversion creates partial report.
- offset cost currency explicit.
- zero spend has no NaN percentages.

`CarbonFootprintViewModelTest`
- request ID last load wins.
- failure clears loading.
- period selection triggers correct range from fixed clock.
- stale report + refresh error shows inline banner.

`CarbonFootprintContentTest`
- full loading.
- full error.
- stale content with inline error.
- empty contextual action.
- offset card uses explicit currency.

---

## Receipt matching tests

`ReceiptMatchingViewModelTest`
- load success.
- load failure.
- auto-match success count only increments on link success.
- auto-match partial failure visible.
- approve suggestion success.
- approve suggestion failure visible.
- manual match failure keeps dialog open.
- double approve/manual guarded.
- rerun uses lifecycle-safe unlink.
- skip/reject visible.

`ReceiptTransactionMatcherCurrencyTest`
- same-currency amount match.
- different-currency convertible match.
- different-currency unconvertible penalty.
- bank-statement receipts skipped.
- non-purchase transactions excluded.

`ReceiptMatchingContentTest`
- empty state.
- loading state.
- suggestion card approve/reject.
- unmatched card manual/skip/rerun.
- manual dialog amount/currency display.
- per-receipt mutation disabled state.

---

# 6. Implementation order for agent

## Phase A — Baseline and inventory

1. Run compile.
2. Run existing Slice 12 tests.
3. Inventory hidden money defaults:
```bash
grep -R '"EUR"\|collectAsState(initial = "")\|String.format("%.2f"' \
  app/src/main/java/com/yourname/expensetracker/ui/screens/warranty \
  app/src/main/java/com/yourname/expensetracker/ui/screens/subscription \
  app/src/main/java/com/yourname/expensetracker/ui/screens/bank \
  app/src/main/java/com/yourname/expensetracker/ui/screens/investment \
  app/src/main/java/com/yourname/expensetracker/ui/screens/carbon \
  app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching \
  app/src/main/java/com/yourname/expensetracker/domain/investment \
  app/src/main/java/com/yourname/expensetracker/domain/carbon
```

4. Inventory no-op/stub actions:
```bash
grep -R "TODO\|Placeholder\|Would trigger\|Would disconnect" \
  app/src/main/java/com/yourname/expensetracker/ui/screens/bank \
  app/src/main/java/com/yourname/expensetracker/ui/screens/investment
```

## Phase B — Add contract tests first

Add:
- `WarrantyTrackerViewModelTest`
- `SubscriptionManagementViewModelTest`
- `BankConnectionsViewModelTest`
- `InvestmentViewModelTest`
- `CarbonFootprintCalculatorCurrencyTest`
- `ReceiptMatchingViewModelTest`
- `ReceiptTransactionMatcherCurrencyTest`

## Phase C — Fix critical correctness

1. Warranty protected value aggregate.
2. Subscription totals/entity-currency display.
3. Investment aggregate summary and list loading.
4. Carbon normalized spend basis.
5. Receipt matching link-result handling.
6. Bank stub UI truthfulness and unique stub keys.

## Phase D — Add mutation/error state

1. Warranty mutation state.
2. Subscription mutation state.
3. Bank pending disconnect/sync state.
4. Investment loading/error state.
5. Receipt matching per-receipt mutation state.

## Phase E — UI extraction and component tests

Extract route/content/components for each feature.

## Phase F — Localization/theme/accessibility

1. Replace raw `String.format` with formatters.
2. Add test tags.
3. Move hardcoded domain/user-facing strings to resources/`UiText`.
4. Replace dark-only `SemanticColors` where Slice 2 policy requires.

---

# 7. Cross-slice golden scenarios

Add after local Slice 12 tests are green:

1. Receipt scan creates receipt → Receipt Matching links it → Transactions row shows linked receipt.
2. Receipt matching link failure is visible and does not increment success.
3. Warranty created from receipt remains after receipt deletion.
4. Manual warranty protected value agrees with linked expense currency.
5. Subscription accepted from candidate appears as recurring expense and affects Home recurring widgets.
6. Subscription monthly total agrees with recurring expense projection.
7. Investment portfolio in mixed currencies does not raw-sum.
8. Carbon footprint mixed-currency report is normalized and warns on