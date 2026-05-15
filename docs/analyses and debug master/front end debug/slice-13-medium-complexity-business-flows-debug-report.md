# Slice 13 Debug Report — Medium-Complexity Business Feature Screens

Target repo/commit: `panospao7/Cost-agregator@18d442c5abb42a8997fd8b6bd04978776c5f6596`

Scope:
- Savings goals
- Spending challenges
- Natural language search
- Currency management
- Export options
- Shared expense groups
- Visual split editor/templates
- Bill reminders
- Tax configuration
- Price protection
- Bill negotiation
- Lifestyle inflation

Sources inspected:
- Savings VM/screen/model:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsScreen.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/model/SavingsGoal.kt
- Challenges:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/challenge/SpendingChallengeManager.kt
- Natural language:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/naturallanguage/NaturalLanguageSearchViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/naturallanguage/NaturalLanguageSearchEngine.kt
- Currency:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/currency/CurrencyManagementViewModel.kt
- Export:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt
- Groups/splits:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsScreen.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/split/VisualSplitViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/split/SplitTemplatesScreen.kt
- Reminders/tax/price/negotiation/lifestyle:
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/reminder/BillRemindersViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/reminder/BillReminderManager.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/tax/TaxConfigurationViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/price/PriceProtectionViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/price/PriceProtectionTracker.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/negotiation/BillNegotiationViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/negotiation/SmartBillNegotiationEngine.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/ui/screens/lifestyle/LifestyleInflationViewModel.kt
  - https://raw.githubusercontent.com/panospao7/Cost-agregator/18d442c5abb42a8997fd8b6bd04978776c5f6596/app/src/main/java/com/yourname/expensetracker/domain/lifestyle/LifestyleInflationDetector.kt

Note: Static source debugging only. The fixing agent must run Gradle locally.

---

## 1. Executive summary

Slice 13 is “medium complexity” but not low risk. It crosses money, exports, user history, recurring bills, shared ledgers, tax estimates, and receipt-derived price data.

Major findings:

1. Hidden/default `"EUR"` still appears in production models/states.
2. Several features raw-sum money across currencies.
3. Some features still use deprecated raw DAO aggregates.
4. Several actions are not idempotency-safe.
5. Many dialogs close before durable mutation success.
6. Natural Language Search still uses a deprecated legacy engine despite Slice 11’s newer financial-query pipeline.
7. Bill Reminders still uses deprecated recurring lifecycle methods.
8. Price Protection persists raw item names in SharedPreferences keys.
9. Export encryption exists but is not wired to UI and uses a hardcoded `"default"` key if enabled.
10. Tax configuration is mostly demo/static and not persisted.
11. Lifestyle inflation uses raw DAO expenses, raw effective amounts, English keyword heuristics, and fixed 30-day months.
12. Tests exist for some screens, but important currency/race/mutation/privacy contracts are missing.

Recommended fix approach:
- First add contract tests for money basis, mutation idempotency, and deprecated path guards.
- Fix currency correctness and deprecated lifecycle paths before UI polish.
- Extract route/content/components after behavior is covered.

---

## 2. Baseline commands

Run:

```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
```

Then targeted tests:

```bash
./gradlew :app:testDebugUnitTest --tests "*Savings*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Challenge*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*NaturalLanguage*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Currency*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Export*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Groups*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Split*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Reminder*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Tax*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*PriceProtection*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Negotiation*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Lifestyle*" --stacktrace
```

Inventory:

```bash
find app/src/test app/src/androidTest \
  -iname "*Savings*" -o \
  -iname "*Challenge*" -o \
  -iname "*NaturalLanguage*" -o \
  -iname "*Currency*" -o \
  -iname "*Export*" -o \
  -iname "*Groups*" -o \
  -iname "*Split*" -o \
  -iname "*Reminder*" -o \
  -iname "*Tax*" -o \
  -iname "*Price*" -o \
  -iname "*Negotiation*" -o \
  -iname "*Lifestyle*"
```

---

# 3. Cross-cutting issues

## S13-001 — Hidden `"EUR"` defaults still leak into production feature models

Severity: Critical  
Files:
- `SavingsGoal.kt`
- `SavingsGoalsViewModel.kt`
- `SpendingChallengesViewModel.kt`
- `CurrencyManagementViewModel.kt`
- `GroupsUiState`
- `BillRemindersScreen.kt`
- `TaxConfigurationViewModel.kt`
- `PriceProtectionTracker.kt`

Examples:
- `SavingsGoal.currency = "EUR"` and `currencyAssumption = "LEGACY_DEFAULT"`.
- Savings goal creation does not pass home currency.
- Challenge/currency/groups state defaults to `"EUR"`.
- Bill reminder screen collects `homeCurrency` with `initial = ""`.
- Tax screen defaults GR/EUR.
- Price protected item defaults purchase currency to EUR.

Fix strategy:
Create explicit currency states:
```kotlin
sealed interface CurrencyUiState {
    data object Loading : CurrencyUiState
    data class Ready(val code: String) : CurrencyUiState
    data class Error(val message: UiText) : CurrencyUiState
}
```

Acceptance:
- no feature can persist a default EUR when the real home currency is unavailable.
- creation actions require explicit currency.
- tests delay/fail home-currency flow and verify no EUR persistence/display.

---

## S13-002 — Money display lacks typed money UI models

Severity: Critical  
Affected:
- Savings goals total/current/target/recommendations
- Challenges target/spent/saved
- Natural language results
- Group totals/balances
- Bill reminders monthly totals
- Negotiation opportunities
- Price protection savings/deals
- Lifestyle monthly data

Fix:
Use:
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
- no UI formats `Double` money without explicit currency.
- mixed-currency fixtures produce converted totals or visible partial warnings.

---

## S13-003 — Mutation state/idempotency is inconsistent

Severity: High  
Affected actions:
- add savings goal
- contribute/sweep savings
- create challenge
- change home currency
- refresh rates
- create group/member/expense
- split template create/delete/default
- mark bill paid
- record negotiation outcome
- tax estimate
- price tracking exclusion
- export generation/cancel

Fix pattern:
```kotlin
data class MutationState(
    val operation: String? = null,
    val targetId: Long? = null,
    val isRunning: Boolean = false,
    val error: UiText? = null
)
```

Acceptance:
- double tap calls use case once.
- failure leaves UI context open.
- success emits event and closes UI.

---

## S13-004 — Raw strings and exception messages are user-visible

Severity: Medium/High  
Examples:
- `SearchState.Error(e.message ?: "Unknown error")`
- `CurrencyManagementUiState.error`
- groups errors from exception messages
- export messages
- tax errors
- lifestyle constant error string

Fix:
Use typed error codes or `UiText`.

Acceptance:
- tests assert reason codes, not English text.
- internal exception messages are not shown directly.

---

## S13-005 — Feature screens should be split into route/content/components

Severity: Medium  
Current state: many screens collect ViewModel state and also own dialogs, formatting, local validation, lists, and mutations.

Recommended extraction:
```text
SavingsGoalsRoute.kt / SavingsGoalsContent.kt / SavingsGoalCard.kt / AddGoalDialog.kt
SpendingChallengesRoute.kt / SpendingChallengesContent.kt / CreateChallengeDialog.kt
NaturalLanguageSearchRoute.kt / NaturalLanguageSearchContent.kt / SearchResultCard.kt
CurrencyManagementRoute.kt / CurrencyManagementContent.kt
ExportOptionsRoute.kt / ExportOptionsContent.kt
SharedExpenseGroupsRoute.kt / GroupsContent.kt / GroupDialogs.kt
SplitTemplatesRoute.kt / SplitTemplatesContent.kt
BillRemindersRoute.kt / BillRemindersContent.kt
TaxConfigurationRoute.kt / TaxConfigurationContent.kt
PriceProtectionRoute.kt / PriceProtectionContent.kt
BillNegotiationRoute.kt / BillNegotiationContent.kt
LifestyleInflationRoute.kt / LifestyleInflationContent.kt
```

Acceptance:
- route files only collect state/events.
- content components are testable without Hilt.
- dialogs are pure state + callbacks.

---

# 4. Savings goals issues

## S13-006 — New savings goals default to EUR

Severity: Critical  
Files:
- `SavingsGoal.kt`
- `SavingsGoalsViewModel.kt`

Evidence:
`SavingsGoal.currency` defaults to `"EUR"`. `addGoal(...)` does not pass currency.

Fix:
When adding:
```kotlin
val currency = requireNotNull(_state.value.currency as? Ready)?.code
SavingsGoal(..., currency = currency, currencyAssumption = "HOME_CURRENCY")
```

Acceptance:
- non-EUR user creates non-EUR goal.
- home currency unavailable disables create/save.

---

## S13-007 — Total saved raw-sums goals across currencies

Severity: Critical  
File:
- `SavingsGoalsViewModel.kt`

Evidence:
`totalSaved += goal.currentAmount`, then formatted as `homeCurrency`.

Fix:
Use money aggregation by `goal.currency`.

Acceptance:
- USD + EUR goals produce converted home-currency total with warnings.
- failed conversion shows partial warning.

---

## S13-008 — Monthly sweep applies allocations non-atomically

Severity: Critical data integrity  
File:
- `SavingsGoalsViewModel.kt`

Evidence:
`acceptSweepRecommendation()` loops allocations and increments goals one-by-one, recording contributions after each.

Problem:
Partial allocation can occur if one write fails.

Fix:
Add repository/use case:
```kotlin
suspend fun applySavingsSweep(recommendationId, allocations): SweepApplyResult
```
Use DB transaction.

Acceptance:
- all allocations + history rows succeed together or none do.
- double accept applies once.
- failure keeps recommendation visible.

---

## S13-009 — Add/contribute dialogs close before persistence result

Severity: High  
File:
- `SavingsGoalsScreen.kt`

Fix:
- ViewModel emits `GoalCreated`, `GoalCreateFailed`, `ContributionSaved`, etc.
- dialog closes only on success.
- snackbars use events.

Acceptance:
- repository failure keeps Add Goal dialog open.
- quick-add failure is visible.

---

## S13-010 — Savings date and amount input validation is UI-local

Severity: Medium  
Files:
- `SavingsGoalsScreen.kt`

Fix:
Create `SavingsGoalInputValidator`.

Acceptance:
- amount sanitizer shared with Slice 5.
- past target date policy is tested.
- DatePicker UTC/local date behavior tested.

---

# 5. Spending challenges issues

## S13-011 — Challenge progress uses deprecated raw DAO sums

Severity: Critical  
Files:
- `SpendingChallengeManager.kt`

Evidence:
Uses deprecated `ExpenseDao.getTotalSpentBetween`, `getCategorySpentInPeriod`, and raw daily totals.

Fix:
Create `ChallengeSpendProvider` using canonical normalized aggregation.

Acceptance:
- mixed-currency challenge progress is normalized.
- failed conversion makes challenge progress partial/blocked.
- no `@Suppress("DEPRECATION_ERROR")` in challenge manager.

---

## S13-012 — No-spend streak uses fixed 24h days and raw Calendar

Severity: High  
File:
- `SpendingChallengeManager.kt`

Problem:
DST and timezone changes can break streak logic.

Fix:
Use `java.time.LocalDate` + injected `ZoneId`.

Acceptance:
- DST boundary test passes.
- “today” uses app/user zone.

---

## S13-013 — Loading active challenges mutates DB

Severity: High architecture  
File:
- `SpendingChallengeManager.kt`

Evidence:
`getActiveChallengesSnapshot()` deactivates completed challenges during load.

Problem:
A read UI load has write side effects and can be blocked by restore/write barriers.

Fix:
Separate:
```kotlin
getActiveChallengesSnapshot()
reconcileCompletedChallenges()
```

Acceptance:
- read path is side-effect free.
- reconciliation respects write barrier.

---

## S13-014 — Challenge creation lacks idempotency and typed validation

Severity: Medium/High  
Files:
- `SpendingChallengesViewModel.kt`

Fix:
- `CreateChallengeInputValidator`
- guard if `isCreating`
- emit success/failure events

Acceptance:
- double create calls manager once.
- invalid duration/amount/category never calls manager.

---

# 6. Natural language search issues

## S13-015 — Legacy NaturalLanguageSearch still uses deprecated engine

Severity: High  
Files:
- `NaturalLanguageSearchViewModel.kt`
- `NaturalLanguageSearchEngine.kt`

Evidence:
`NaturalLanguageSearchEngine` is explicitly deprecated in source comments. New query work should use `ExecuteFinancialQueryUseCase`.

Fix:
Either:
1. migrate this screen to the Slice 11 financial query pipeline, or
2. clearly mark it “legacy local search” and block new functionality.

Recommended:
Use the same interpretation/execution/navigation core as Assistant.

Acceptance:
- NL search and Assistant return the same result count for same query.
- drilldown filters match Transactions.

---

## S13-016 — Search race: stale results can overwrite cleared/newer query

Severity: High  
File:
- `NaturalLanguageSearchViewModel.kt`

Evidence:
Debounced search and `executeVoiceQuery()` both call `performSearch`; no request ID.

Fix:
```kotlin
private var searchJob: Job? = null
private var searchSeq = 0L
```
or `query.debounce(...).flatMapLatest`.

Acceptance:
- query A slow, query B fast => B wins.
- clearQuery while A running cannot repopulate results.
- voice query cancels pending typed query.

---

## S13-017 — Data quality warnings are not surfaced

Severity: High  
Files:
- `NaturalLanguageSearchViewModel.kt`
- `NaturalLanguageSearchEngine.kt`

Evidence:
Engine tracks failed conversions/unsupported locations but ViewModel only exposes total and results.

Fix:
Expose:
```kotlin
data class NaturalLanguageSearchUiState(
    val dataQuality: QueryDataQualityUi,
    ...
)
```

Acceptance:
- failed FX conversion shows partial warning.
- unsupported location filter shows “location filter unavailable.”

---

## S13-018 — Query privacy/logging is weak

Severity: Medium/High  
Files:
- `NaturalLanguageSearchViewModel.kt`
- `NaturalLanguageSearchEngine.kt`

Fix:
- no `printStackTrace()`
- no raw queries in release logs
- error messages sanitized

Acceptance:
- static grep for `printStackTrace` in this slice is clean.
- release logs do not contain merchant/query text.

---

# 7. Currency management issues

## S13-019 — Currency data loading can race

Severity: High  
File:
- `CurrencyManagementViewModel.kt`

Evidence:
`homeCurrency().collect { loadCurrencyData() }`, and `loadCurrencyData()` launches another coroutine. Old loads can finish after new loads.

Fix:
Use `flatMapLatest` or request IDs.

Acceptance:
- USD load slow, GBP load fast => final rates are GBP.
- no nested launch race.

---

## S13-020 — Home currency change has no failure/idempotency guard

Severity: High  
File:
- `CurrencyManagementViewModel.kt`

Fix:
- validate currency code against supported set
- set mutation state
- catch repository/classifier failures
- do not leave `isLoading = true`

Acceptance:
- invalid code rejected.
- double set calls repository once.
- failure leaves old currency visible.

---

## S13-021 — Refresh/convert validation is incomplete

Severity: Medium  
File:
- `CurrencyManagementViewModel.kt`

Fix:
- reject NaN/Infinity/negative if unsupported.
- refresh has request ID.
- rate provider errors are typed.

Acceptance:
- convert invalid amount does not call converter.
- refresh failure shows retryable state.

---

# 8. Export options issues

## S13-022 — Export encryption is not user-facing and hardcoded

Severity: Critical privacy/security  
File:
- `ExportOptionsViewModel.kt`

Evidence:
`encryptExport` parameter exists, UI toggle is pending, and enabled path uses `"default"`.

Fix:
- add UI toggle and password/key selection
- never use hardcoded encryption key
- integrate privacy setting

Acceptance:
- raw export requires explicit plaintext consent.
- encrypted export uses user-provided/secure key.
- test verifies `"default"` is not used.

---

## S13-023 — Export preview can leak sensitive raw data

Severity: High privacy  
File:
- `ExportOptionsViewModel.kt`

Fix:
- preview redacts merchant/notes/card-like data unless diagnostics mode allows raw.
- show row count/schema, not raw full rows, by default.

Acceptance:
- privacy setting disabled raw export blocks preview.
- preview redaction test covers merchant and notes.

---

## S13-024 — Accounting validation loads full dataset, undermining streaming

Severity: Medium/High  
File:
- `ExportOptionsViewModel.kt`

Evidence:
For accounting formats, it fetches all expenses before streaming.

Fix:
Make `AccountingExportPolicy` streaming-capable:
```kotlin
validatePage(...)
finishValidation()
```

Acceptance:
- large accounting export remains O(page size).
- tests use 10k+ fake rows.

---

## S13-025 — Export cancel/cleanup needs explicit contract

Severity: Medium  
File:
- `ExportOptionsViewModel.kt`

Fix:
- cancellation deletes temp and unfinished output.
- `ExportCancelled` state is not treated as error if user requested.

Acceptance:
- no partial files remain after cancel.
- test cancels mid-page.

---

# 9. Shared expense groups issues

## S13-026 — Group totals/balances raw-sum amounts

Severity: Critical  
Files:
- `SharedExpenseGroupsViewModel.kt`

Evidence:
`totalSpent = aggregate.expenses.sumOf { it.totalAmount }`; balances use `SplitCalculator` raw.

Fix:
Define group currency policy:
- all group expenses must use group default currency, or
- normalize per expense to group currency.

Acceptance:
- mixed-currency group cannot silently raw-sum.
- invalid currency shows error/partial warning.

---

## S13-027 — Group add expense falls back to EUR

Severity: Critical  
File:
- `SharedExpenseGroupsViewModel.kt`

Evidence:
`homeCurrency` failure returns `"EUR"`.

Fix:
No fallback:
```kotlin
CurrencyUnavailable -> block add expense
```

Acceptance:
- home currency failure never creates EUR group expense.

---

## S13-028 — Group mutations lack loading/idempotency state

Severity: High  
Files:
- `SharedExpenseGroupsViewModel.kt`
- `SharedExpenseGroupsScreen.kt`

Fix:
Track:
```kotlin
creatingGroup
addingMember
addingExpense
deletingGroupId
```
as true mutation states, not just dialog booleans.

Acceptance:
- double add expense calls use case once.
- failure keeps dialog open.
- delete failure keeps selected group.

---

## S13-029 — Current user name is hardcoded `"You"`

Severity: Low/Medium  
File:
- `SharedExpenseGroupsViewModel.kt`

Fix:
Use localized/user profile identity.

Acceptance:
- no hardcoded English identity in persisted group member name unless intended.

---

# 10. Visual split issues

## S13-030 — Template operations have no error/loading/idempotency state

Severity: High  
File:
- `VisualSplitViewModel.kt`

Fix:
Expose `SplitTemplateUiState`.

Acceptance:
- delete failure keeps dialog open.
- set default double tap calls once.
- create invalid template rejected.

---

## S13-031 — Compose parses template JSON directly

Severity: Medium  
File:
- `SplitTemplatesScreen.kt`

Evidence:
`TemplateCard` uses `Gson().fromJson(...)`.

Fix:
ViewModel/domain maps to typed UI:
```kotlin
SplitTemplateUi(shares: List<SplitShareUi>)
```

Acceptance:
- no JSON parsing in composables.
- invalid JSON becomes visible corrupted-template state.

---

## S13-032 — Split calculations lack money/currency context

Severity: Medium/High  
Files:
- `VisualSplitViewModel.kt`
- `VisualSplitEditorScreen.kt`

Fix:
Add explicit currency to split editor state and formatted amounts.

Acceptance:
- split editor opened from non-EUR expense uses expense currency.
- restored route does not default to EUR.

---

# 11. Bill reminders issues

## S13-033 — Bill Reminders uses deprecated lifecycle path

Severity: Critical  
Files:
- `BillRemindersViewModel.kt`
- `BillReminderManager.kt`

Evidence:
`markBillPaid` suppresses deprecation error and the domain KDoc says it does not update occurrence/planned/reminder state.

Fix:
Migrate to `RecurringLifecycleCoordinator.linkExpenseToOccurrence` or equivalent.

Acceptance:
- paying bill marks occurrence paid, fulfills planned expense, suppresses reminder, writes lifecycle event.
- no deprecation suppression remains.

---

## S13-034 — Monthly total raw-sums recurring bills across currencies

Severity: Critical  
File:
- `BillReminderManager.kt`

Evidence:
`getMonthlyBillsTotal()` sums `toMonthlyAmount(expense.amount, frequency)`.

Fix:
Normalize to home currency with FX and data-quality warnings.

Acceptance:
- USD + EUR recurring bills convert before total.
- failed conversion shows partial warning.

---

## S13-035 — Reminder rows format native amounts as home currency

Severity: High  
File:
- `BillRemindersScreen.kt`

Fix:
- row amount uses `reminder.currency`
- monthly total uses normalized home currency.

Acceptance:
- GBP reminder displays GBP.
- total displays home currency with partial warning.

---

## S13-036 — Load errors look like empty/zero state

Severity: Medium/High  
File:
- `BillRemindersViewModel.kt`

Fix:
Use:
```kotlin
sealed interface BillRemindersUiState { Loading, Data, Empty, Error }
```

Acceptance:
- repository failure shows retry.
- monthly total failure does not show `0.00` as truth.

---

# 12. Tax configuration issues

## S13-037 — Tax screen is static/demo and not persisted

Severity: Medium/High  
File:
- `TaxConfigurationViewModel.kt`

Evidence:
default country GR, supported countries hardcoded, no repository.

Fix:
Add `TaxSettingsRepository`.

Acceptance:
- selected country persists.
- user understands estimates are demo/configurable if not production-ready.

---

## S13-038 — Country selection can race

Severity: Medium  
File:
- `TaxConfigurationViewModel.kt`

Evidence:
`selectCountry` launches, sets state, then calls `loadTaxConfiguration()` which launches again.

Fix:
Make load synchronous inside same coroutine or use request ID.

Acceptance:
- rapid GR/US switching cannot show US selected with GR brackets.

---

## S13-039 — Sample estimate is hardcoded and period policy is unclear

Severity: Medium  
File:
- `TaxConfigurationViewModel.kt`

Fix:
- user-editable income sample
- tax-year period resolver
- validation for finite/positive income

Acceptance:
- sample estimate uses selected country currency.
- invalid income does not call estimator.

---

# 13. Price protection issues

## S13-040 — Excluded tracking keys leak item names in SharedPreferences

Severity: Critical privacy  
File:
- `PriceProtectionViewModel.kt`

Evidence:
tracking key includes `receiptId:itemName:purchaseDate`.

Fix:
Use stable fingerprint/hash only:
```kotlin
trackingKey = item.fingerprint ?: sha256("$receiptId:$normalizedItem:$purchaseDate")
```
Do not store raw names.

Acceptance:
- SharedPreferences keys contain no merchant/item names.
- migration removes old raw keys.

---

## S13-041 — Price protection identity is still fragile

Severity: High  
File:
- `PriceProtectionTracker.kt`

Evidence:
Source comments say fingerprint is planned; current ViewModel key uses receiptId/name/date.

Fix:
Persist fingerprint in item/domain storage and use it everywhere.

Acceptance:
- re-parsed/reordered receipt items do not duplicate tracked items.

---

## S13-042 — Price comparisons are simulated and currency-unsafe

Severity: High  
File:
- `PriceProtectionTracker.kt`

Fix:
- mark simulated results clearly in UI/state.
- compare only same currency or converted with captured rate.
- show data-quality warning when current price currency differs.

Acceptance:
- current price in different currency does not produce false drop.
- simulated status visible.

---

## S13-043 — Loading state can stick on price-drop refresh

Severity: Medium  
File:
- `PriceProtectionViewModel.kt`

Risk:
`refreshPriceDrops()` sets loading true and relies on shared flow emission/catch to clear it.

Fix:
Use typed load operation with timeout/result.

Acceptance:
- no-emission flow clears or shows timeout.
- refresh failure visible.

---

# 14. Bill negotiation issues

## S13-044 — Negotiation market rates are static/mock and region/currency ambiguous

Severity: High  
Files:
- `SmartBillNegotiationEngine.kt`

Evidence:
hardcoded market rates, mostly Greece providers, rates created with `System.currentTimeMillis()`.

Fix:
Make market rates data-source backed:
```kotlin
MarketRateRepository(region, currency)
```

Acceptance:
- screen labels data as simulated until real source exists.
- region/currency visible.
- rates have stable test timestamps via TimeProvider.

---

## S13-045 — Negotiation mixes subscription currency and home currency

Severity: High  
Files:
- `BillNegotiationScreen.kt`
- `SmartBillNegotiationEngine.kt`

Problem:
UI formats opportunity prices with home currency. Scripts format with subscription currency. Market rates have no explicit currency.

Fix:
Add `currency` to `NegotiationOpportunity` and `MarketRate`.

Acceptance:
- opportunity and script use same explicit currency.
- mixed-currency subscriptions do not raw-sum potential yearly savings.

---

## S13-046 — Recording outcome is not durable

Severity: High  
File:
- `SmartBillNegotiationEngine.kt`

Evidence:
history is in-memory list of maps; record outcome does not persist or update subscription price.

Fix:
Create repository table/entity for negotiation attempts and optionally subscription price update.

Acceptance:
- outcome survives process restart.
- failure visible.
- double save guarded.
- dialog closes only on success.

---

## S13-047 — Outcome dialog accepts invalid savings

Severity: Medium  
File:
- `BillNegotiationScreen.kt`

Fix:
- use amount sanitizer
- validate `0 < savings <= current monthly price`
- partial outcome supports savings amount if policy allows

Acceptance:
- invalid savings blocks save.
- failure keeps dialog open.

---

# 15. Lifestyle inflation issues

## S13-048 — Lifestyle inflation raw-sums mixed currencies

Severity: Critical  
File:
- `LifestyleInflationDetector.kt`

Evidence:
uses `expense.effectiveAmount` directly.

Fix:
Use normalized analytics input / currency normalizer.

Acceptance:
- multi-currency monthly income/spending is normalized.
- failed conversion lowers confidence/partial report.

---

## S13-049 — Date range uses fixed 30-day months

Severity: High  
File:
- `LifestyleInflationDetector.kt`

Evidence:
`monthsToAnalyze * 30L * 24 * 60 * 60 * 1000`.

Fix:
Use `YearMonth` ranges via `java.time`.

Acceptance:
- exact month boundaries.
- leap year/DST tests pass.

---

## S13-050 — Discretionary classification is English keyword-only

Severity: Medium/High  
File:
- `LifestyleInflationDetector.kt`

Fix:
Use category IDs/configurable discretionary categories; fallback to keywords only for legacy.

Acceptance:
- user category corrections affect lifestyle classification.
- non-English categories supported.

---

## S13-051 — Lifestyle recommendations contain raw strings and locale-fragile formatting

Severity: Medium  
File:
- `LifestyleInflationDetector.kt`

Fix:
Use `UiText` with arguments and formatters.

Acceptance:
- no direct `String.format` in domain user-facing text.
- tests assert recommendation codes.

---

# 16. Recommended tests to add

## JVM/domain/ViewModel

Add:

```text
SavingsGoalsCurrencyTest
SavingsSweepAtomicityTest
SpendingChallengeCurrencyTest
SpendingChallengeDateBoundaryTest
NaturalLanguageSearchRaceTest
NaturalLanguageSearchCurrencyQualityTest
CurrencyManagementRaceTest
CurrencyManagementMutationTest
ExportPrivacyEncryptionTest
ExportStreamingMemoryContractTest
SharedExpenseGroupsCurrencyTest
SharedExpenseGroupsMutationTest
VisualSplitTemplateStateTest
BillRemindersLifecycleTest
BillRemindersCurrencyTest
TaxConfigurationRaceTest
PriceProtectionPrivacyKeyTest
PriceProtectionCurrencyTest
BillNegotiationCurrencyPersistenceTest
LifestyleInflationCurrencyDateTest
```

Key required cases:
- home currency delayed/fails -> no EUR persistence.
- mixed currencies -> normalized totals or partial warnings.
- double mutation -> one repository call.
- mutation failure -> dialog/context stays open.
- deprecated path guard -> no suppressions remain for new flow.
- stale async result cannot overwrite latest request.

## Compose/component

Add:
```text
SavingsGoalsContentTest
SpendingChallengesContentTest
NaturalLanguageSearchContentTest
CurrencyManagementContentTest
ExportOptionsContentTest
SharedExpenseGroupsContentTest
SplitTemplatesContentTest
VisualSplitEditorContentTest
BillRemindersContentTest
TaxConfigurationContentTest
PriceProtectionContentTest
BillNegotiationContentTest
LifestyleInflationContentTest
```

Minimum assertions:
- loading/empty/error/data state.
- invalid input validation.
- save/delete buttons disabled while mutation running.
- currency labels visible.
- partial warning visible.
- stable test tags.

---

# 17. Implementation order for agent

## Phase A — Compile and inventory

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --stacktrace
```

Inventory:
```bash
grep -R '"EUR"\|collectAsState(initial = "")\|@Suppress("DEPRECATION_ERROR")\|printStackTrace' \
  app/src/main/java/com/yourname/expensetracker/ui/screens \
  app/src/main/java/com/yourname/expensetracker/domain
```

## Phase B — Add contract tests first

Prioritize:
1. Savings currency + sweep atomicity.
2. Challenges currency/date.
3. Bill reminders lifecycle/currency.
4. Export privacy/encryption.
5. Groups currency/mutation.
6. Lifestyle currency/date.
7. Natural-language race/currency.

## Phase C — Critical correctness fixes

1. Remove EUR persistence from Savings/Groups/Price/Bill/Challenge.
2. Migrate Challenge and Bill Reminders off deprecated raw/lifecycle paths.
3. Make group/savings/bill/lifestyle totals currency-safe.
4. Fix export encryption/preview privacy.
5. Remove raw SharedPreferences keys in Price Protection.

## Phase D — Robust state/mutations

1. Add mutation states.
2. Close dialogs only on success.
3. Add request IDs or `flatMapLatest`.
4. Replace swallowed errors with typed UI states.

## Phase E — UI extraction

Split route/content/components for all screens.

## Phase F — Localization/privacy/accessibility

1. Replace raw strings with resources/UiText.
2. Add stable test tags.
3. Remove `printStackTrace`.
4. Redact logs and persisted keys.
5. Add light/dark smoke tests where visual components are large.

---

# 18. Cross-slice golden scenarios

Add after Slice 13 local tests pass:

1. Create non-EUR savings goal -> Savings total uses correct currency.
2. Monthly savings sweep applies all allocations atomically.
3. Challenge progress equals Analytics/Home spend for same period/currency.
4. NL search count equals Assistant/Transactions drilldown count.
5. Change home currency -> Currency screen updates rates and downstream displays do not show stale EUR.
6. Export denied by privacy -> no preview/file generated.
7. Encrypted export -> no plaintext file remains.
8. Group expense creates linked transaction and group balance atomically.
9. Bill marked paid updates recurring occurrence/planned/reminder state.
10. Lifestyle inflation monthly spending equals Analytics normalized monthly spending.
11. Price-protection exclusion persists without raw item names.
12. Negotiation outcome persists after app restart.

---

# 19. Acceptance checklist for Slice 13 green

- [ ] Compile passes.
- [ ] Unit-test Kotlin compiles.
- [ ] No production feature persists placeholder EUR.
- [ ] Money displays use explicit currency.
- [ ] Mixed-currency totals are normalized or visibly partial.
- [ ] Savings sweep is atomic.
- [ ] Challenge manager no longer uses deprecated raw DAO aggregate path.
- [ ] Bill reminders no longer use deprecated mark-paid path.
- [ ] Natural language search cannot show stale results.
- [ ] Export plaintext/encryption policy is explicit and tested.
- [ ] Export preview respects privacy/redaction.
- [ ] Group totals/balances are currency-safe.
- [ ] Split template operations expose error/loading state.
- [ ] Price protection does not store raw item names in prefs.
- [ ] Negotiation outcomes persist and have explicit currency.
- [ ] Lifestyle inflation uses normalized analytics/date policy.
- [ ] Dialogs close only after success.
- [ ] Errors are typed/safe.
- [ ] UI files are split enough for component tests.
- [ ] Docs updated only after tests/source are green.

---

# 20. Agent guardrails

Do:
- Fix currency correctness before visual polish.
- Use fixed `TimeProvider` and `ZoneId`.
- Use fake FX rates and fake repositories.
- Add tests before refactors.
- Treat export/price/search/history data as privacy-sensitive.
- Keep deprecated-path fixes small and contract-tested.

Do not:
- Add new feature behavior before money/mutation contracts exist.
- Persist default EUR.
- Let composables compute financial totals.
- Close dialogs before save success.
- Store raw merchant/item/search text in logs/preferences.
- Hide failed conversions behind empty/zero UI.

Main invariant:

> Slice 13 features must operate on explicit currency/date/privacy bases, mutate data idempotently and atomically where needed, expose partial/degraded states honestly, and avoid deprecated lifecycle/aggregate paths.