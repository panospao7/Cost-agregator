## Technical Plan

### Scope
- In: still-open D.3 standalone-medium UI issues from SubBatches D.10-D.12 that match the requested themes: explicit error vs no-data state, scaffold-padded loading, hardcoded English copy, lifecycle-aware state collection, dialog/input state correctness, invalid button enablement, and small Compose-state correctness bugs.
- Out: non-UI/domain rows from the same reviews (`CaptureAssistInput.amount`, hashing/import boundary issues, `RecurrenceFrequency.IRREGULAR`, duplicate model families, `SavingsGoal` domain/entity boundary, `MonteCarloBudgetImpact` explicit currency, `NarrativeGenerator` boundary cleanup), plus any broad app-wide `collectAsState` audit outside the named screens.
- Assumptions / unknowns:
  - `androidx.lifecycle.compose.collectAsStateWithLifecycle` is already available in the module (it is used elsewhere), so the Receipt Scan fix should be local and low-risk.
  - There is no active Compose instrumentation test suite under `app/src/androidTest`; validation will rely on JVM tests, compile checks, grep checks, and manual QA.
  - Assistant starter prompts likely still need English-default payloads for parser quality; they still must become resource-backed so translations can be introduced only after query interpretation is proven locale-safe.
  - `ReviewScreen` raw-evidence UX is ambiguous. Preferred remediation is an explicit expand/collapse affordance; acceptable fallback is removing the dead state entirely if product does not want expandable evidence text.

### Files
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/carbon/CarbonFootprintViewModel.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/carbon/CarbonFootprintScreen.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/lifestyle/LifestyleInflationViewModel.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/lifestyle/LifestyleInflationScreen.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/challenge/SpendingChallengesScreen.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/assistant/AssistantSheet.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModel.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseSheet.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerViewModel.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerScreen.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsScreen.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/currency/CurrencyManagementScreen.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/split/VisualSplitEditorScreen.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt`
- modify: `app/src/main/res/values/strings.xml`
- modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/carbon/CarbonFootprintViewModelTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/lifestyle/LifestyleInflationViewModelTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/lifestyle/LifestyleInflationScreenTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/warranty/WarrantyTrackerViewModelTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/ui/screens/addexpense/AddExpenseViewModelTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/ui/screens/currency/CurrencyManagementScreenValidationTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/ui/screens/split/VisualSplitEditorScreenStateTest.kt`
- create: `app/src/test/java/com/yourname/expensetracker/ui/screens/groups/SharedExpenseGroupsScreenStateTest.kt`
- modify: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`

### Implementation Steps
1. **Grouped issue list (execution inventory)**
   - **Explicit error/loading state**
     - `CarbonFootprintViewModel` collapses exceptions into `report = null`.
     - `LifestyleInflationViewModel` collapses exceptions into `report = null`.
     - `CarbonFootprintScreen` loading spinner ignores scaffold padding.
     - `CarbonFootprintScreen` shows negative values in the “below target” Paris-gap string.
   - **Hardcoded / non-resource UI copy**
     - `SpendingChallengesScreen`: unavailable card title/body and target/baseline strings.
     - `ReceiptScanScreen`: camera-permission denial copy and item-analysis retry label.
     - `AssistantSheet`: starter-chip click handlers inject hardcoded English prompt payloads.
     - `LifestyleInflationScreen`: `SavingsPromptCard` title/body/button text.
   - **Lifecycle-aware collection + composable/state correctness**
     - `ReceiptScanScreen` still uses `collectAsState()` for `state` and `categories`.
     - `AddExpenseViewModel.reset()` does not cancel the debounced merchant-search job.
     - `AddExpenseSheet` prefill is keyed to `LaunchedEffect(Unit)` instead of incoming props.
     - `WarrantyTrackerViewModel` auto-detected chip cannot toggle off.
     - `WarrantyTrackerScreen` expired warranties never hit the expired badge path.
     - `SharedExpenseGroupsScreen` uses exact `balance == 0.0`.
     - `ReviewScreen` has a dead `showTrustSignal` state that never toggles.
   - **Dialog/input validation correctness**
     - `CurrencyManagementScreen` leaves Convert enabled for invalid input and silently no-ops.
     - `VisualSplitEditorScreen` round-trips editable values through `Double.toString()`, destroying in-progress text input.

2. **File-level fix plan**
   - `CarbonFootprintViewModel.kt`
     - Add an explicit error channel/state instead of using `report = null` as the only failure signal.
     - Preserve request-id stale-response protection and `CancellationException` behavior.
     - Prefer additive state over a broad ViewModel contract rewrite.
   - `CarbonFootprintScreen.kt`
     - Render scaffold-padded loading and error states.
     - Decide display precedence: full-screen error only when there is no previously loaded report; banner/inline error if stale data is still visible.
     - Format “below target” with `abs(gap)` semantics.
   - `LifestyleInflationViewModel.kt`
     - Mirror the CarbonFootprint error-state remediation.
   - `LifestyleInflationScreen.kt`
     - Render explicit retryable error UI.
     - Move `SavingsPromptCard` strings into resources.
     - Audit loading padding while the file is open; do not leave a second unpadded loading state behind if the same pattern is still present.
   - `ReceiptScanScreen.kt`
     - Replace screen-owned `collectAsState()` with `collectAsStateWithLifecycle()`.
     - Move permission-denial and retry copy into `strings.xml`.
   - `SpendingChallengesScreen.kt`
     - Extract unavailable-card and active-target copy into resources with placeholder support.
     - Prefer a small internal formatter/helper so percentage/target phrasing is not rebuilt inline in multiple branches.
     - Audit raw enum-name display while touching the screen; fix only if trivial and low-risk.
   - `AssistantSheet.kt`
     - Replace hardcoded prompt payloads with resource-backed strings distinct from visible labels.
     - Keep the default-resource payloads parser-safe if non-English parsing is not yet verified.
   - `AddExpenseViewModel.kt`
     - Cancel `searchJob` during `reset()` and ensure stale search completions cannot repopulate suggestions after the sheet is cleared/dismissed.
   - `AddExpenseSheet.kt`
     - Re-key prefill application to the incoming `(initialAmount, initialMerchant)` tuple.
     - Protect user edits from being overwritten on ordinary recomposition by tracking the last-applied prefill tuple.
   - `WarrantyTrackerViewModel.kt`
     - Make the auto-detected filter chip a true toggle and keep it mutually exclusive with status and needs-review filters.
   - `WarrantyTrackerScreen.kt`
     - Show the expired badge when `isExpired || isExpiringSoon`, not only on the expiring-soon branch.
   - `SharedExpenseGroupsScreen.kt`
     - Replace exact-zero comparison with a currency-aware epsilon/rounded-balance helper.
   - `CurrencyManagementScreen.kt`
     - Derive parsed validity in the dialog, disable the confirm button when invalid, and surface inline validation instead of silently ignoring taps.
   - `VisualSplitEditorScreen.kt`
     - Store editable text separately from parsed numeric state, keyed by participant index and field kind.
     - Parse only when the text is valid or when the user commits, so partial entries like `""`, `"0."`, and locale-typed decimals are not destroyed.
   - `ReviewScreen.kt`
     - Either add an explicit expand/collapse control for the trust-signal section or remove the dead state path entirely.
     - Keep the existing debug tap separate from any new expansion affordance.
   - `strings.xml`
     - Add resource ids for challenge unavailable/target text, receipt permission/open-settings/retry copy, lifestyle savings prompt text, and assistant prompt payload strings.

3. **Batch 1 — Explicit error/loading state recovery (Carbon + Lifestyle)**
   - **Dependencies:** none; execute first because later UX verification depends on screens distinguishing failure from empty/no-data.
   - **Primary files:** `CarbonFootprintViewModel.kt`, `CarbonFootprintScreen.kt`, `LifestyleInflationViewModel.kt`, `LifestyleInflationScreen.kt`, related tests.
   - **Work:**
     - Add explicit error state to both ViewModels.
     - Keep last successful report available across refresh failures when practical.
     - Render retryable error UI instead of falling through to generic empty state.
     - Ensure loading content sits inside scaffold padding.
     - Fix the Carbon Paris-gap display to use positive magnitude for the “below target” branch.
   - **Validation strategy:**
     - `./gradlew.bat :app:compileDebugKotlin`
     - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.carbon.CarbonFootprintViewModelTest"`
     - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.lifestyle.LifestyleInflationViewModelTest"`
     - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.lifestyle.LifestyleInflationScreenTest"`
     - Manual QA: first-load failure, refresh-after-success failure, retry recovery, and padded loading on both screens.
   - **Completion criteria:**
     - Failure is visually distinct from “no data.”
     - Retry can recover the screen without app restart.
     - Loading/error surfaces no longer overlap the top app bar.
   - **Failure / rollback note:** if a shared `UiState` refactor starts touching unrelated screens, stop and fall back to additive `error` state per screen.

4. **Batch 2 — Resource-backed copy and localization cleanup**
   - **Dependencies:** after Batch 1 for `LifestyleInflationScreen` merge safety; otherwise independent.
   - **Primary files:** `SpendingChallengesScreen.kt`, `ReceiptScanScreen.kt`, `AssistantSheet.kt`, `LifestyleInflationScreen.kt`, `strings.xml`.
   - **Work:**
     - Move all reviewed hardcoded copy into string resources.
     - Use format strings/placeholders rather than string concatenation for baseline/target and savings-prompt text.
     - Back starter-chip payloads with resource ids separate from visible labels.
   - **Validation strategy:**
     - `./gradlew.bat :app:compileDebugKotlin`
     - Grep check for known literals (`"Active challenges unavailable"`, `"Camera permission denied"`, `"Open Settings"`, `"Retry"`, `"Boost Your Savings"`, starter-prompt English payloads) returns zero in targeted files.
     - Manual QA: challenge unavailable state, challenge target text, receipt permission-denied card, item-analysis retry, lifestyle savings prompt, assistant starter chips.
   - **Completion criteria:**
     - No reviewed user-facing literal remains hardcoded in the targeted screens.
     - Assistant prompt labels and submitted payloads are both resource-backed.
   - **Failure / rollback note:** if non-English starter-prompt payloads reduce parser quality, keep default-resource values English for now but do not reintroduce inline string literals.

5. **Batch 3 — Lifecycle-aware collection and composable/state correctness**
   - **Dependencies:** none.
   - **Primary files:** `ReceiptScanScreen.kt`, `AddExpenseViewModel.kt`, `AddExpenseSheet.kt`, `WarrantyTrackerViewModel.kt`, `WarrantyTrackerScreen.kt`, `SharedExpenseGroupsScreen.kt`, `ReviewScreen.kt`, related tests.
   - **Work:**
     - Replace Receipt Scan screen collectors with `collectAsStateWithLifecycle()`.
     - Cancel stale Add Expense search work on reset.
     - Reapply Add Expense prefill only when the incoming tuple changes.
     - Toggle the Warranty auto-detected filter on/off correctly.
     - Fix expired badge rendering.
     - Use epsilon/rounded comparison for settled balances.
     - Make the review trust-signal state reachable or delete it.
   - **Validation strategy:**
     - `./gradlew.bat :app:compileDebugKotlin`
     - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.warranty.WarrantyTrackerViewModelTest"`
     - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.addexpense.AddExpenseViewModelTest"`
     - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.groups.SharedExpenseGroupsScreenStateTest"`
     - Manual QA: background/resume Receipt Scan, dismiss/reset Add Expense during an active merchant search, relaunch Add Expense with changed prefill, toggle warranty filters twice, open expired warranty list, inspect near-zero balances, exercise review evidence panel.
   - **Completion criteria:**
     - Receipt Scan collectors are lifecycle-aware.
     - Reset no longer allows stale suggestions to reappear.
     - Prefilled Add Expense values update correctly between invocations without clobbering active edits.
     - Auto-detected warranty chip can be enabled and cleared.
     - Expired warranties visibly show the expired badge.
     - Near-zero balances render as settled.
     - Trust-signal UI no longer has unreachable state.
   - **Failure / rollback note:** if `LaunchedEffect(initialAmount, initialMerchant)` starts overwriting user edits, add a last-applied tuple guard instead of reverting to `Unit`.

6. **Batch 4 — Dialog/input state correctness and validation**
   - **Dependencies:** independent, but safest after Batch 3 if any local helper patterns are reused.
   - **Primary files:** `CurrencyManagementScreen.kt`, `VisualSplitEditorScreen.kt`, related tests.
   - **Work:**
     - Derive dialog-level validation state for currency conversion and drive both button enabled-state and inline helper/error text from it.
     - Introduce transient text state in Visual Split so user typing is not rewritten by parsed `Double` values every recomposition.
   - **Validation strategy:**
     - `./gradlew.bat :app:compileDebugKotlin`
     - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.currency.CurrencyManagementScreenValidationTest"`
     - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.split.VisualSplitEditorScreenStateTest"`
     - Manual QA: blank/invalid conversion input, locale-style decimal entry, partial decimal entry in split fields, clear-and-retype scenarios, apply after valid correction.
   - **Completion criteria:**
     - Convert cannot be tapped into a silent no-op.
     - Visual Split fields preserve in-progress user text until a valid parse/commit occurs.
   - **Failure / rollback note:** do not move transient text state into long-lived ViewModel state unless synchronization rules are explicit and unit-tested.

7. **Batch 5 — Closeout verification and registry sync**
   - **Dependencies:** after all code batches pass.
   - **Primary files:** `MASTER-ISSUE-REGISTRY.md` plus any touched test files.
   - **Work:**
     - Re-run the targeted validation commands and manual QA checklist.
     - Update the D.10-D.12 registry bullets only after the fixes are verified; use the exact replacement wording already prepared in the review docs where applicable.
     - Capture any newly discovered adjacency items as follow-up work instead of widening the finished batches.
   - **Validation strategy:**
     - `./gradlew.bat :app:compileDebugKotlin`
     - Targeted unit-test commands from Batches 1-4
     - Optional `./gradlew.bat :app:testDebugUnitTest` only if the branch is already green enough for full-suite confirmation.
   - **Completion criteria:**
     - Registry status matches the implemented and verified code.
     - No targeted literal/behavior regression remains in the named screens.

### Risks
- Localized starter-prompt payloads may expose assistant parser locale gaps; mitigate by resource-backing first and translating only after validation.
- Additive error-state work can accidentally blank previously loaded content if screen precedence is not defined; preserve last-success semantics where possible.
- `AddExpenseSheet` prop-driven prefill can easily overwrite active edits; guard with a last-applied tuple or equivalent one-shot-per-input strategy.
- `VisualSplitEditorScreen` can desynchronize text vs parsed numeric state if commit timing is unclear; use one explicit rule for when parsing occurs and unit-test it.
- Epsilon-based balance settlement must match displayed currency precision; otherwise small but real balances may be hidden.
- `ReviewScreen` trust-signal remediation has UX ambiguity; confirm whether the intended end state is expandable content or removal of the dead branch.
- Because there is no instrumentation suite, manual QA is required for final sign-off on Compose rendering and focus/input behavior.

### Acceptance Criteria
- [ ] Carbon Footprint and Lifestyle Inflation screens expose explicit error state instead of collapsing failures to `null`/empty state.
- [ ] Carbon Footprint and Lifestyle Inflation loading UI respects scaffold padding and does not overlap the app bar.
- [ ] Carbon “below target” messaging never renders a negative percent value.
- [ ] Spending Challenges, Receipt Scan, Assistant starter prompts, and Lifestyle savings prompt use resource-backed copy for all reviewed strings.
- [ ] Receipt Scan collects screen state with `collectAsStateWithLifecycle()`.
- [ ] Add Expense reset cancels stale merchant-search work, and changed prefill inputs are applied correctly on later openings.
- [ ] Warranty auto-detected filtering toggles on/off correctly, and expired warranties visibly show an expired badge.
- [ ] Shared-expense near-zero balances render as settled rather than owing/getting-back.
- [ ] Currency conversion confirm is disabled or visibly invalid for bad input; no silent no-op remains.
- [ ] Visual Split inputs preserve partial user-entered text instead of snapping back to formatted `Double.toString()` output.
- [ ] Review trust-signal UI is either explicitly toggleable or the dead state is removed.
- [ ] After validation, the D.10-D.12 registry entries are updated to match the verified closure state.
